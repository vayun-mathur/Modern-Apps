//! Process-wide configuration, loaded once at startup from the environment.
//!
//! Every value comes from the compose file / env_file. Secrets are held in
//! memory only; agentd never forwards them to the runner, the browser, or
//! log/event output.

use std::path::PathBuf;

/// Test-only timeout squeezes: respected by [`Config::from_env`] when the
/// `AGENTD_TEST_*` variables are set; ignored in production.
#[derive(Debug, Clone, Default)]
pub struct TestTuning {
    /// Poll interval for scheduler + release-signal watch, in milliseconds.
    pub poll_ms: Option<u64>,
    /// How long `build` waits for the release signal to clear, in seconds.
    pub build_wait_secs: Option<u64>,
    /// Per-tool-call context/char budget overrides (unit tests use these
    /// without standing up the HTTP server).
    pub context_chars: Option<usize>,
}

#[derive(Debug, Clone)]
pub struct Config {
    /// Key for OpenRouter chat completions. Never logged or exposed.
    pub openrouter_api_key: String,
    /// Base URL for OpenRouter; overridden in tests to point at a mock.
    pub openrouter_base_url: String,
    /// Argon2 PHC hash of the single operator password.
    pub password_hash: String,
    /// Git push token (`Authorization: Bearer <token>`). Never logged.
    pub git_push_token: String,
    /// Remote repo URL, e.g. `https://git.maproj.org/Modern-Apps.git`.
    pub repo_url: String,
    /// Public origin, e.g. `https://agents.maproj.org` (Origin checks).
    pub public_origin: String,
    /// Default model sent to OpenRouter when the task form leaves it blank.
    pub default_model: String,
    /// Runner base URL, e.g. `http://builder:7070`.
    pub runner_url: String,
    /// Directory holding `agentd.db` (SQLite).
    pub state_dir: PathBuf,
    /// Shared bare mirror, read-only for the builder.
    pub mirror_dir: PathBuf,
    /// Presence of this file means a prod release build is running.
    pub release_signal: PathBuf,
    /// Listen address, e.g. `0.0.0.0:8080`.
    pub listen: String,
    /// Number of concurrent agent tasks (scheduler workers).
    pub slots: usize,
    /// `owner/name` repo the GitHub tools talk to (issues read + after-push
    /// comment/label). Mirrors `GitHub::new_from_env("vayun-mathur/Modern-Apps")`.
    pub github_repo: String,
    /// GitHub API base URL override (tests point at a mock server).
    pub github_api_base: String,
    /// GitHub token for issue read/comment/label (from `AGENTD_GITHUB_TOKEN`,
    /// empty when unset). Held in memory only; never sent to the sandbox.
    pub github_token: Option<String>,
    /// Test tuning (timeouts only). `None` in production.
    pub test: TestTuning,
    // -- test-only network flags (never read in prod paths) --
    /// Test fixture: skip the real release-signal wait.
    pub test_skip_release_wait: bool,
    /// Automatic issue triage poller (`AGENTD_TRIAGE`; default on, `0` off).
    pub triage_enabled: bool,
    /// Triage poll period in seconds (`AGENTD_TRIAGE_INTERVAL_SECS`; 120).
    pub triage_interval_secs: u64,
}

impl Config {
    fn required(name: &str) -> Result<String, String> {
        std::env::var(name)
            .map(|v| {
                let v = v.trim().to_string();
                v
            })
            .map_err(|_| format!("missing required env var {name}"))
            .and_then(|v| {
                if v.is_empty() {
                    Err(format!("missing required env var {name} (empty)"))
                } else {
                    Ok(v)
                }
            })
    }

    fn optional(name: &str, default: &str) -> String {
        std::env::var(name)
            .ok()
            .map(|v| v.trim().to_string())
            .filter(|v| !v.is_empty())
            .unwrap_or_else(|| default.to_string())
    }

    pub fn from_env() -> Result<Config, String> {
        let test = TestTuning {
            poll_ms: std::env::var("AGENTD_TEST_POLL_MS")
                .ok()
                .and_then(|v| v.parse().ok()),
            build_wait_secs: std::env::var("AGENTD_TEST_BUILD_WAIT_SECS")
                .ok()
                .and_then(|v| v.parse().ok()),
            context_chars: std::env::var("AGENTD_TEST_CONTEXT_CHARS")
                .ok()
                .and_then(|v| v.parse().ok()),
        };
        let slots: usize = Self::optional("AGENTD_SLOTS", "4")
            .parse()
            .map_err(|_| "AGENTD_SLOTS must be a positive integer".to_string())?;
        if slots == 0 {
            return Err("AGENTD_SLOTS must be >= 1".to_string());
        }
        Ok(Config {
            openrouter_api_key: Self::required("OPENROUTER_API_KEY")?,
            openrouter_base_url: Self::optional(
                "OPENROUTER_BASE_URL",
                "https://openrouter.ai/api/v1",
            ),
            password_hash: Self::required("AGENTD_PASSWORD_HASH")?,
            git_push_token: Self::required("GIT_AGENT_PUSH_TOKEN")?,
            repo_url: Self::optional("AGENTD_REPO_URL", "https://git.maproj.org/Modern-Apps.git"),
            public_origin: Self::required("AGENTD_PUBLIC_ORIGIN")?,
            default_model: Self::optional("AGENTD_DEFAULT_MODEL", "anthropic/claude-sonnet-4"),
            runner_url: Self::optional("AGENTD_RUNNER_URL", "http://builder:7070"),
            state_dir: PathBuf::from(Self::optional("AGENTD_STATE_DIR", "/state")),
            mirror_dir: PathBuf::from(Self::optional(
                "AGENTD_MIRROR_DIR",
                "/mirror/Modern-Apps.git",
            )),
            release_signal: PathBuf::from(Self::optional(
                "AGENTD_RELEASE_SIGNAL",
                "/signal/release",
            )),
            listen: Self::optional("AGENTD_LISTEN", "0.0.0.0:8080"),
            slots,
            github_repo: Self::optional("AGENTD_GITHUB_REPO", "vayun-mathur/Modern-Apps"),
            github_api_base: Self::optional("AGENTD_GITHUB_API", crate::github::DEFAULT_BASE_URL),
            github_token: std::env::var("AGENTD_GITHUB_TOKEN")
                .ok()
                .filter(|t| !t.trim().is_empty()),
            test,
            test_skip_release_wait: false,
            triage_enabled: Self::optional("AGENTD_TRIAGE", "1") != "0",
            triage_interval_secs: Self::optional("AGENTD_TRIAGE_INTERVAL_SECS", "120")
                .parse()
                .unwrap_or(120),
        })
    }

    /// Release-signal poll interval.
    pub fn poll_interval(&self) -> std::time::Duration {
        std::time::Duration::from_millis(self.test.poll_ms.unwrap_or(3_000))
    }

    /// Max time a `build` tool call waits for the release signal to clear.
    pub fn build_wait_limit(&self) -> std::time::Duration {
        std::time::Duration::from_secs(self.test.build_wait_secs.unwrap_or(60 * 60))
    }

    ///conversation context budget (chars) before old tool results are trimmed.
    pub fn context_chars(&self) -> usize {
        self.test.context_chars.unwrap_or(120_000)
    }

    pub fn redacted_debug(&self) -> String {
        format!(
            "runner={} origin={} slots={} state={} mirror={} model={}",
            self.runner_url,
            self.public_origin,
            self.slots,
            self.state_dir.display(),
            self.mirror_dir.display(),
            self.default_model,
        )
    }
}
