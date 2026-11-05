//! Wire types for the agentd -> agent-runner HTTP API.
//!
//! agentd (trusted) calls the runner inside the builder sandbox over the
//! internal Docker network. Every route is `POST` with a JSON body except
//! `GET /v1/health` and `GET /v1/slots/{slot}/bundle`. Errors are any non-2xx
//! status with an [`ErrorBody`]. Paths in requests are relative to the slot's
//! checkout root; the runner rejects anything that resolves outside it.

use serde::{Deserialize, Serialize};

/// Default runner port inside the builder container.
pub const RUNNER_PORT: u16 = 7070;

/// Branch prefix agents work and push on.
pub const AGENT_BRANCH_PREFIX: &str = "agent/";

/// `POST /v1/slots/{slot}/prepare` — (re)create the slot checkout on a fresh
/// branch from the mirror's `main`, keeping ignored build outputs warm.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrepareRequest {
    /// Full branch name, must start with [`AGENT_BRANCH_PREFIX`].
    pub branch: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrepareResponse {
    /// Commit the branch was created from.
    pub base_sha: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ExecKind {
    /// Short command, runs niced, no global lock.
    Shell,
    /// Build/test command, serialized behind the runner's single build slot.
    Build,
}

/// `POST /v1/slots/{slot}/exec` — run `bash -lc <command>` in the slot root
/// as the unprivileged sandbox user, in its own process group.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecRequest {
    pub command: String,
    pub kind: ExecKind,
    pub timeout_secs: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecResponse {
    /// `None` when killed (timeout, cancel, preemption).
    pub exit_code: Option<i32>,
    /// Head + tail kept when output exceeds the runner's cap.
    pub stdout: String,
    pub stderr: String,
    pub truncated: bool,
    pub timed_out: bool,
    /// Killed by `/cancel` or `/v1/builds/kill`.
    pub killed: bool,
    /// Time spent waiting for the build slot (Build only).
    pub queued_ms: u64,
    pub duration_ms: u64,
}

/// `POST /v1/slots/{slot}/read`
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReadRequest {
    pub path: String,
    /// 1-based first line; `None` = start of file.
    pub start_line: Option<usize>,
    /// Max lines to return; `None` = runner default cap.
    pub max_lines: Option<usize>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReadResponse {
    pub content: String,
    pub total_lines: usize,
    pub truncated: bool,
}

/// `POST /v1/slots/{slot}/write` — create or overwrite (parents created).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WriteRequest {
    pub path: String,
    pub content: String,
}

/// `POST /v1/slots/{slot}/edit` — replace exactly one occurrence of `old`.
/// Zero or multiple matches is a 409 with an explanatory [`ErrorBody`].
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EditRequest {
    pub path: String,
    pub old: String,
    pub new: String,
}

/// `POST /v1/slots/{slot}/list` — one directory level; dirs end with `/`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ListRequest {
    pub path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ListResponse {
    pub entries: Vec<String>,
    pub truncated: bool,
}

/// `POST /v1/slots/{slot}/search` — ripgrep, respecting .gitignore.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchRequest {
    pub pattern: String,
    /// Subdirectory to search; `None` = slot root.
    pub path: Option<String>,
    /// e.g. `*.kt`
    pub glob: Option<String>,
    pub fixed_string: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchResponse {
    /// `path:line:text` lines.
    pub matches: String,
    pub truncated: bool,
}

/// `POST /v1/slots/{slot}/diff` (empty body) — working tree vs branch base,
/// including untracked files.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiffResponse {
    pub stat: String,
    pub diff: String,
    pub truncated: bool,
}

/// `POST /v1/slots/{slot}/commit` — stage everything in the slot and commit.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CommitRequest {
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CommitResponse {
    pub sha: String,
}

// `GET /v1/slots/{slot}/bundle` — `application/octet-stream` git bundle of
// `<base_sha>..<branch>` for the slot's current branch. agentd imports it
// into the mirror (bundles are data; nothing in the sandbox's repo config is
// ever executed by agentd).

/// `POST /v1/slots/{slot}/cancel` (empty body) — kill whatever the slot runs.
/// `POST /v1/builds/kill` (empty body) — kill the in-flight Build, if any.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KillResponse {
    pub killed: bool,
}

/// `GET /v1/health`
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthResponse {
    pub slots: u32,
    pub build_running: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorBody {
    pub error: String,
}
