#![allow(dead_code)]
//! Minimal GitHub REST client for agentd's issue tools.
//!
//! Covers exactly what the orchestrator needs: searching issues,
//! reading one issue with its comments, posting a comment, and adding
//! labels (the last two need `AGENTD_GITHUB_TOKEN`). The base URL defaults
//! to `https://api.github.com` and is overridable so tests can point at a
//! local mock server.
//!
//! Pure helpers ([`app_module_for_labels`], [`render_issue_for_model`],
//! [`render_search_for_model`], [`task_from_issue`]) shape GitHub data for
//! the model and the scheduler.

use std::time::Duration;

use serde::{Deserialize, Serialize};

/// Default API root; override per client for tests.
pub const DEFAULT_BASE_URL: &str = "https://api.github.com";
/// Pinned GitHub API version sent on every request.
pub const API_VERSION: &str = "2022-11-28";
/// `User-Agent` sent on every request.
pub const USER_AGENT: &str = "agentd";
/// Label agentd adds after a user-approved successful push.
pub const SOLVED_LABEL: &str = "solved for next release";
/// Total budget in bytes for an issue body plus its comment bodies.
pub const BODY_BUDGET_BYTES: usize = 12 * 1024;
/// Comment pages (`per_page=100`) followed when reading an issue.
pub const MAX_COMMENT_PAGES: u32 = 3;
/// `per_page` used when listing comments.
pub const COMMENTS_PER_PAGE: u32 = 100;

/// Errors from the GitHub client.
#[derive(Debug)]
pub enum Error {
    /// A write call (`post_comment`, `add_labels`) without a token.
    NoToken(&'static str),
    /// Transport failure (connect, timeout, bad body, ...).
    Transport(reqwest::Error),
    /// Non-success status that is not a rate limit.
    Api { status: u16, message: String },
    /// HTTP 429, or HTTP 403 with `x-ratelimit-remaining: 0`.
    RateLimited { status: u16, reset: Option<String> },
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::NoToken(op) => {
                write!(f, "GitHub token required for {op} (set AGENTD_GITHUB_TOKEN)")
            }
            Error::Transport(e) => write!(f, "GitHub request failed: {e}"),
            Error::Api { status, message } => {
                write!(f, "GitHub API error (HTTP {status}): {message}")
            }
            Error::RateLimited { status, reset } => write!(
                f,
                "GitHub rate limit exceeded (HTTP {status}); resets at {}",
                reset.as_deref().unwrap_or("unknown")
            ),
        }
    }
}

impl std::error::Error for Error {}

/// Which issues a search/list call includes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IssueState {
    Open,
    Closed,
    All,
}

impl IssueState {
    /// Lowercase value the GitHub API expects.
    pub fn as_str(self) -> &'static str {
        match self {
            IssueState::Open => "open",
            IssueState::Closed => "closed",
            IssueState::All => "all",
        }
    }
}

/// One row from a search or list call.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct IssueSummary {
    pub number: u64,
    pub title: String,
    pub state: String,
    pub labels: Vec<String>,
    pub comments: u64,
    pub created_at: String,
    pub updated_at: String,
    pub is_pr: bool,
}

/// A single issue comment.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Comment {
    pub author: String,
    pub created_at: String,
    pub body: String,
}

/// A full issue with comments. `body` plus `comments` fit in
/// [`BODY_BUDGET_BYTES`]; `truncated` is set when anything was cut.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Issue {
    pub number: u64,
    pub title: String,
    pub state: String,
    pub labels: Vec<String>,
    pub body: String,
    pub author: String,
    pub created_at: String,
    pub comments: Vec<Comment>,
    pub truncated: bool,
}

#[derive(Debug, Clone, Deserialize)]
struct ApiLabel {
    #[serde(default)]
    name: String,
}

#[derive(Debug, Clone, Deserialize)]
struct ApiUser {
    #[serde(default)]
    login: String,
}

/// Subset of the issue object agentd reads. Everything defaults so a
/// minimal mock (or a future API field change) still parses.
#[derive(Debug, Clone, Deserialize)]
struct ApiIssue {
    #[serde(default)]
    number: u64,
    #[serde(default)]
    title: String,
    #[serde(default)]
    state: String,
    #[serde(default)]
    labels: Vec<ApiLabel>,
    #[serde(default)]
    body: Option<String>,
    #[serde(default)]
    user: Option<ApiUser>,
    #[serde(default)]
    created_at: String,
    #[serde(default)]
    updated_at: String,
    #[serde(default)]
    comments: u64,
    #[serde(default)]
    pull_request: Option<serde_json::Value>,
}

impl ApiIssue {
    fn is_pr(&self) -> bool {
        self.pull_request.is_some()
    }

    fn label_names(&self) -> Vec<String> {
        self.labels.iter().map(|l| l.name.clone()).collect()
    }
}

#[derive(Debug, Clone, Deserialize)]
struct ApiComment {
    #[serde(default)]
    user: Option<ApiUser>,
    #[serde(default)]
    created_at: String,
    #[serde(default)]
    body: Option<String>,
}

impl From<ApiComment> for Comment {
    fn from(c: ApiComment) -> Comment {
        Comment {
            author: c.user.map(|u| u.login).unwrap_or_default(),
            created_at: c.created_at,
            body: c.body.unwrap_or_default(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
struct SearchResponse {
    #[serde(default)]
    items: Vec<ApiIssue>,
}

#[derive(Debug, Clone, Deserialize)]
struct ApiErrorBody {
    #[serde(default)]
    message: String,
}

#[derive(Debug, Clone, Deserialize)]
struct PostedComment {
    #[serde(default)]
    html_url: String,
}

/// GitHub API client scoped to one `owner/name` repo.
#[derive(Clone)]
pub struct GitHub {
    base_url: String,
    repo: String,
    token: Option<String>,
    http: reqwest::Client,
}

impl GitHub {
    /// Client against `https://api.github.com`.
    pub fn new(repo: impl Into<String>, token: Option<String>) -> Result<GitHub, Error> {
        GitHub::with_base_url(DEFAULT_BASE_URL, repo, token)
    }

    /// Client against an explicit API root (tests point at a mock server).
    pub fn with_base_url(
        base_url: impl Into<String>,
        repo: impl Into<String>,
        token: Option<String>,
    ) -> Result<GitHub, Error> {
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(30))
            .no_proxy()
            .build()
            .map_err(Error::Transport)?;
        Ok(GitHub {
            base_url: base_url.into().trim_end_matches('/').to_string(),
            repo: repo.into(),
            token,
            http,
        })
    }

    /// Client for `repo` with the token from `AGENTD_GITHUB_TOKEN` (if set).
    pub fn new_from_env(repo: impl Into<String>) -> Result<GitHub, Error> {
        let token = std::env::var("AGENTD_GITHUB_TOKEN")
            .ok()
            .filter(|t| !t.trim().is_empty());
        GitHub::new(repo, token)
    }

    fn request(&self, method: reqwest::Method, path: &str) -> reqwest::RequestBuilder {
        // Pagination `Link` headers are absolute URLs. Only follow ones on our
        // API base so the bearer token can never be sent to another host.
        let url = if path.starts_with("http") {
            if path.starts_with(&format!("{}/", self.base_url)) {
                path.to_string()
            } else {
                format!("{}/invalid-pagination-link", self.base_url)
            }
        } else {
            format!("{}{path}", self.base_url)
        };
        let mut req = self
            .http
            .request(method, url)
            .header(reqwest::header::USER_AGENT, USER_AGENT)
            .header(reqwest::header::ACCEPT, "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION);
        if let Some(token) = &self.token {
            req = req.header(
                reqwest::header::AUTHORIZATION,
                format!("Bearer {token}"),
            );
        }
        req
    }

    /// Send a request, mapping rate limits and API errors.
    async fn send(&self, req: reqwest::RequestBuilder) -> Result<reqwest::Response, Error> {
        let resp = req.send().await.map_err(Error::Transport)?;
        let status = resp.status();
        let rate_limited = status.as_u16() == 429
            || (status.as_u16() == 403
                && matches!(
                    resp.headers()
                        .get("x-ratelimit-remaining")
                        .and_then(|v| v.to_str().ok()),
                    Some("0")
                ));
        if rate_limited {
            let reset = resp
                .headers()
                .get("x-ratelimit-reset")
                .and_then(|v| v.to_str().ok())
                .map(str::to_string);
            return Err(Error::RateLimited {
                status: status.as_u16(),
                reset,
            });
        }
        if !status.is_success() {
            let message = resp
                .json::<ApiErrorBody>()
                .await
                .map(|e| e.message)
                .unwrap_or_else(|_| format!("HTTP {status}"));
            return Err(Error::Api {
                status: status.as_u16(),
                message,
            });
        }
        Ok(resp)
    }

    /// Search issues. With `query` the Search API is used
    /// (`q=repo:<repo> is:issue ...`, labels folded in as `label:".."`),
    /// otherwise the list endpoint with a `labels` filter. Pull requests
    /// are always excluded.
    pub async fn search_issues(
        &self,
        query: Option<&str>,
        labels: &[String],
        state: IssueState,
        limit: u32,
    ) -> Result<Vec<IssueSummary>, Error> {
        let per_page = limit.clamp(1, 100);
        if let Some(query) = query {
            let mut q = format!("repo:{} is:issue {query}", self.repo);
            match state {
                IssueState::Open => q.push_str(" state:open"),
                IssueState::Closed => q.push_str(" state:closed"),
                IssueState::All => {}
            }
            for label in labels {
                q.push_str(&format!(" label:\"{label}\""));
            }
            let resp = self
                .send(self.request(reqwest::Method::GET, "/search/issues").query(&[
                    ("q", q),
                    ("per_page", per_page.to_string()),
                ]))
                .await?;
            let found: SearchResponse = resp.json().await.map_err(Error::Transport)?;
            Ok(found
                .items
                .into_iter()
                .filter(|i| !i.is_pr())
                .map(summary_from)
                .collect())
        } else {
            let mut params: Vec<(&str, String)> = vec![
                ("state", state.as_str().to_string()),
                ("per_page", per_page.to_string()),
                ("sort", "updated".to_string()),
                ("direction", "desc".to_string()),
            ];
            if !labels.is_empty() {
                params.push(("labels", labels.join(",")));
            }
            let path = format!("/repos/{}/issues", self.repo);
            let resp = self
                .send(self.request(reqwest::Method::GET, &path).query(&params))
                .await?;
            let found: Vec<ApiIssue> = resp.json().await.map_err(Error::Transport)?;
            Ok(found
                .into_iter()
                .filter(|i| !i.is_pr())
                .map(summary_from)
                .collect())
        }
    }

    /// Read one issue with its comments (paginated, up to
    /// [`MAX_COMMENT_PAGES`] pages). Body plus comments are capped at
    /// [`BODY_BUDGET_BYTES`] total; `truncated` notes when anything was cut.
    pub async fn read_issue(&self, number: u64) -> Result<Issue, Error> {
        let path = format!("/repos/{}/issues/{number}", self.repo);
        let resp = self
            .send(self.request(reqwest::Method::GET, &path))
            .await?;
        let raw: ApiIssue = resp.json().await.map_err(Error::Transport)?;

        let mut comments = Vec::new();
        let mut next: Option<String> = Some(format!(
            "/repos/{}/issues/{number}/comments?per_page={COMMENTS_PER_PAGE}",
            self.repo
        ));
        let mut pages: u32 = 0;
        let mut more_available = false;
        while let Some(url) = next.take() {
            if pages >= MAX_COMMENT_PAGES {
                more_available = true;
                break;
            }
            pages += 1;
            let resp = self.send(self.request(reqwest::Method::GET, &url)).await?;
            // Presence of a `rel="next"` link governs paging (a short last
            // page still carries none); the page cap above bounds runaway
            // `Link` headers.
            next = next_link(resp.headers())
                .filter(|u| u.starts_with(&format!("{}/", self.base_url)));
            let batch: Vec<ApiComment> = resp.json().await.map_err(Error::Transport)?;
            comments.extend(batch.into_iter().map(Comment::from));
        }

        let labels = raw.label_names();
        let (body, comments, mut truncated) =
            apply_budget(raw.body.unwrap_or_default(), comments);
        if more_available {
            truncated = true;
        }
        Ok(Issue {
            number: raw.number,
            title: raw.title,
            state: raw.state,
            labels,
            body,
            author: raw.user.map(|u| u.login).unwrap_or_default(),
            created_at: raw.created_at,
            comments,
            truncated,
        })
    }

    /// Post a comment; returns the comment's HTML URL. Needs a token.
    pub async fn post_comment(&self, number: u64, body: &str) -> Result<String, Error> {
        if self.token.is_none() {
            return Err(Error::NoToken("post_comment"));
        }
        let path = format!("/repos/{}/issues/{number}/comments", self.repo);
        let resp = self
            .send(
                self.request(reqwest::Method::POST, &path)
                    .json(&serde_json::json!({ "body": body })),
            )
            .await?;
        let posted: PostedComment = resp.json().await.map_err(Error::Transport)?;
        if posted.html_url.is_empty() {
            return Err(Error::Api {
                status: 200,
                message: "comment response carried no html_url".to_string(),
            });
        }
        Ok(posted.html_url)
    }

    /// Add labels to an issue. Needs a token.
    pub async fn add_labels(&self, number: u64, labels: &[String]) -> Result<(), Error> {
        if self.token.is_none() {
            return Err(Error::NoToken("add_labels"));
        }
        let path = format!("/repos/{}/issues/{number}/labels", self.repo);
        self.send(
            self.request(reqwest::Method::POST, &path)
                .json(&serde_json::json!({ "labels": labels })),
        )
        .await?;
        Ok(())
    }

    /// Whether a token is configured (write calls need one).
    pub fn has_token(&self) -> bool {
        self.token.as_ref().is_some_and(|t| !t.trim().is_empty())
    }

    /// Remove one label from an issue. Needs a token. A 404 means the label
    /// (or issue) is already gone, so it succeeds.
    pub async fn remove_label(&self, number: u64, name: &str) -> Result<(), Error> {
        if !self.has_token() {
            return Err(Error::NoToken("remove_label"));
        }
        let path = format!(
            "/repos/{}/issues/{number}/labels/{}",
            self.repo,
            percent_encode_label(name)
        );
        let resp = self.send(self.request(reqwest::Method::DELETE, &path)).await;
        match resp {
            Err(Error::Api { status: 404, .. }) => Ok(()),
            other => other.map(|_| ()),
        }
    }

    /// List recent issues created after `created_after` (RFC3339 UTC), oldest
    /// first. PRs are excluded. The list endpoint returns
    /// `state=all&sort=created&direction=asc`; `created_at > created_after`
    /// filters server-ascending output (RFC3339 `Z` strings compare
    /// lexicographically once normalized).
    pub async fn list_issues_since(
        &self,
        created_after: &str,
        limit: u32,
    ) -> Result<Vec<IssueSummary>, Error> {
        let per_page = limit.clamp(1, 100);
        let path = format!("/repos/{}/issues", self.repo);
        let resp = self
            .send(self.request(reqwest::Method::GET, &path).query(&[
                ("state", "all".to_string()),
                ("sort", "created".to_string()),
                ("direction", "asc".to_string()),
                ("per_page", per_page.to_string()),
            ]))
            .await?;
        let found: Vec<ApiIssue> = resp.json().await.map_err(Error::Transport)?;
        let after = normalize_rfc3339(created_after);
        Ok(found
            .into_iter()
            .filter(|i| !i.is_pr())
            .map(summary_from)
            .filter(|s| normalize_rfc3339(&s.created_at) > after)
            .collect())
    }

    /// Whether any comment on the issue contains `marker` (paginated like
    /// [`GitHub::read_issue`]). Read-only: works without a token.
    pub async fn issue_has_marker(&self, number: u64, marker: &str) -> Result<bool, Error> {
        let mut next: Option<String> = Some(format!(
            "/repos/{}/issues/{number}/comments?per_page={COMMENTS_PER_PAGE}",
            self.repo
        ));
        let mut pages: u32 = 0;
        while let Some(url) = next.take() {
            if pages >= MAX_COMMENT_PAGES {
                break;
            }
            pages += 1;
            let resp = self.send(self.request(reqwest::Method::GET, &url)).await?;
            next = next_link(resp.headers())
                .filter(|u| u.starts_with(&format!("{}/", self.base_url)));
            let batch: Vec<ApiComment> = resp.json().await.map_err(Error::Transport)?;
            if batch
                .iter()
                .any(|c| c.body.as_deref().unwrap_or_default().contains(marker))
            {
                return Ok(true);
            }
        }
        Ok(false)
    }
}

/// Percent-encode a label name for the `DELETE .../labels/{name}` path
/// segment (RFC 3986 unreserved set passes through; everything else,
/// including `/` and spaces, is `%XX` uppercase hex).
pub fn percent_encode_label(name: &str) -> String {
    const UNRESERVED: &[u8] =
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~";
    let mut out = String::with_capacity(name.len());
    for b in name.bytes() {
        if UNRESERVED.contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Normalize an RFC3339 timestamp to canonical `Z` UTC form so lexicographic
/// comparison equals chronological order. Non-`Z` offsets are rare from this
/// API (it returns `Z`); anything unparseable is returned unchanged.
///
/// Handles `+00:00` → `Z` and fractional seconds.
pub fn normalize_rfc3339(s: &str) -> String {
    let s = s.trim();
    if let Some(base) = s.strip_suffix('Z') {
        return format!("{base}Z");
    }
    if let Some(base) = s.strip_suffix("+00:00") {
        return format!("{base}Z");
    }
    s.to_string()
}

/// Every label the issue labeler treats as an app name (mirrors
/// [`app_module_for_label`]). Triage passes these as the app-label universe
/// for [`crate::triage::plan_label_changes`].
pub const ALL_APP_LABELS: &[&str] = &[
    "appstore",
    "arrows",
    "astronomy",
    "auto",
    "backup",
    "calculator",
    "calendar",
    "camera",
    "cast",
    "chess",
    "clock",
    "code",
    "communicate",
    "contacts",
    "education",
    "email",
    "emergency",
    "euicc",
    "everysync",
    "files",
    "findfamily",
    "flashcards",
    "fooddelivery",
    "globalesim",
    "health",
    "hub",
    "kart",
    "keyboard",
    "launcher",
    "logicgate",
    "logviewer",
    "maps",
    "measure",
    "minesweeper",
    "music",
    "networklocation",
    "nonogram",
    "notes",
    "office",
    "openassistant",
    "parentalcontrols",
    "passwords",
    "pdf",
    "photos",
    "pipes",
    "screentime",
    "securemail",
    "setupwizard",
    "share",
    "shop",
    "solitaire",
    "speech",
    "sudoku",
    "taxi",
    "things",
    "todo",
    "translate",
    "travel",
    "tuner",
    "unblockjam",
    "uniflix",
    "updater",
    "voxels",
    "vpn",
    "watch",
    "weather",
    "web",
    "wordmaker",
    "youpipe",
];

fn summary_from(i: ApiIssue) -> IssueSummary {
    let labels = i.label_names();
    let is_pr = i.is_pr();
    IssueSummary {
        number: i.number,
        title: i.title,
        state: i.state,
        labels,
        comments: i.comments,
        created_at: i.created_at,
        updated_at: i.updated_at,
        is_pr,
    }
}

/// Extract the `rel="next"` URL from a GitHub `Link` header, if present.
fn next_link(headers: &reqwest::header::HeaderMap) -> Option<String> {
    let link = headers.get(reqwest::header::LINK)?.to_str().ok()?;
    for part in link.split(',') {
        let mut halves = part.splitn(2, ';');
        let url = halves.next()?.trim();
        let params = halves.next().unwrap_or("").trim();
        if params.contains("rel=\"next\"") {
            let url = url.strip_prefix('<')?.strip_suffix('>')?;
            return Some(url.to_string());
        }
    }
    None
}

const TRUNC_MARKER: &str = "\n[…truncated]";

/// Cut `s` to at most `max_bytes` (char boundary), noting the cut.
fn truncate_text(s: &str, max_bytes: usize) -> String {
    if s.len() <= max_bytes {
        return s.to_string();
    }
    let mut end = max_bytes.saturating_sub(TRUNC_MARKER.len());
    while end > 0 && !s.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}{TRUNC_MARKER}", &s[..end])
}

/// Fit an issue body plus comments into [`BODY_BUDGET_BYTES`]. The body wins;
/// comments fill the rest in order, the last one fragmented if it partly
/// fits. Returns `(body, comments, truncated)`.
fn apply_budget(body: String, comments: Vec<Comment>) -> (String, Vec<Comment>, bool) {
    if body.len() > BODY_BUDGET_BYTES {
        return (truncate_text(&body, BODY_BUDGET_BYTES), Vec::new(), true);
    }
    let mut kept = Vec::with_capacity(comments.len());
    let mut remaining = BODY_BUDGET_BYTES - body.len();
    let mut truncated = false;
    for c in comments {
        if c.body.len() <= remaining {
            remaining -= c.body.len();
            kept.push(c);
        } else {
            if remaining > 0 {
                kept.push(Comment {
                    body: truncate_text(&c.body, remaining),
                    ..c
                });
            }
            truncated = true;
            break;
        }
    }
    (body, kept, truncated)
}

/// Map an issue-labeler app name to its repo module dir.
///
/// Derived from the `validApps` list in
/// `.github/workflows/issue_labeler.yml`: most app names equal the top-level
/// dir (`weather` → `weather`); games live under `games/` (`chess` →
/// `games/chess`). Names with no module dir (`watch`) map to `None`, as do
/// non-app labels (`bug`, `enhancement`, versions).
fn app_module_for_label(label: &str) -> Option<String> {
    let label = label.trim().to_lowercase();
    let module = match label.as_str() {
        "arrows" | "chess" | "hub" | "kart" | "logicgate" | "minesweeper" | "nonogram"
        | "pipes" | "solitaire" | "sudoku" | "unblockjam" | "voxels" | "wordmaker" => {
            format!("games/{label}")
        }
        "appstore" | "astronomy" | "auto" | "backup" | "calculator" | "calendar" | "camera"
        | "cast" | "clock" | "code" | "communicate" | "contacts" | "education" | "email"
        | "emergency" | "euicc" | "everysync" | "files" | "findfamily" | "flashcards"
        | "fooddelivery" | "globalesim" | "health" | "keyboard" | "launcher" | "logviewer"
        | "maps" | "measure" | "music" | "networklocation" | "notes" | "office"
        | "openassistant" | "passwords" | "pdf" | "photos" | "parentalcontrols" | "screentime"
        | "setupwizard" | "share" | "shop" | "securemail" | "speech" | "taxi" | "things"
        | "todo" | "translate" | "travel" | "tuner" | "uniflix" | "updater" | "vpn" | "weather"
        | "web" | "youpipe" => label,
        _ => return None,
    };
    Some(module)
}

/// First app-label hit wins; non-app labels are skipped.
pub fn app_module_for_labels(labels: &[String]) -> Option<String> {
    labels.iter().find_map(|l| app_module_for_label(l))
}

/// Every repo module dir an app label can map to (compile-time list; the
/// `mapped_module_dirs_exist_in_worktree` test checks each against the
/// filesystem).
pub const ALL_APP_MODULES: &[&str] = &[
    "appstore",
    "astronomy",
    "auto",
    "backup",
    "calculator",
    "calendar",
    "camera",
    "cast",
    "clock",
    "code",
    "communicate",
    "contacts",
    "education",
    "email",
    "emergency",
    "euicc",
    "everysync",
    "files",
    "findfamily",
    "flashcards",
    "fooddelivery",
    "games/arrows",
    "games/chess",
    "games/hub",
    "games/kart",
    "games/logicgate",
    "games/minesweeper",
    "games/nonogram",
    "games/pipes",
    "games/solitaire",
    "games/sudoku",
    "games/unblockjam",
    "games/voxels",
    "games/wordmaker",
    "globalesim",
    "health",
    "keyboard",
    "launcher",
    "logviewer",
    "maps",
    "measure",
    "music",
    "networklocation",
    "notes",
    "office",
    "openassistant",
    "parentalcontrols",
    "passwords",
    "pdf",
    "photos",
    "screentime",
    "securemail",
    "setupwizard",
    "share",
    "shop",
    "speech",
    "taxi",
    "things",
    "todo",
    "translate",
    "travel",
    "tuner",
    "uniflix",
    "updater",
    "vpn",
    "weather",
    "web",
    "youpipe",
];

/// Compact one-issue rendering for model context.
pub fn render_issue_for_model(issue: &Issue) -> String {
    let mut s = format!("#{} [{}] {}\n", issue.number, issue.state, issue.title);
    s.push_str(&format!("labels: {}\n", issue.labels.join(", ")));
    s.push_str(&format!(
        "author: {} opened: {}\n\n{}\n",
        issue.author, issue.created_at, issue.body
    ));
    if !issue.comments.is_empty() {
        s.push_str(&format!("\n--- {} comment(s) ---\n", issue.comments.len()));
        for c in &issue.comments {
            s.push_str(&format!("\n[{} @ {}]\n{}\n", c.author, c.created_at, c.body));
        }
    }
    if issue.truncated {
        s.push_str("\n[note: body/comments truncated to fit the context budget]\n");
    }
    s
}

/// One line per issue for model context.
pub fn render_search_for_model(issues: &[IssueSummary]) -> String {
    issues
        .iter()
        .map(|i| {
            format!(
                "#{} [{}] ({} comments) [{}] {} — updated {}",
                i.number,
                i.state,
                i.comments,
                i.labels.join(", "),
                i.title,
                i.updated_at
            )
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn trim_chars(s: &str, max_chars: usize) -> String {
    if s.chars().count() <= max_chars {
        s.to_string()
    } else {
        s.chars().take(max_chars).collect()
    }
}

/// Build a scheduler task from an issue: `(title, prompt, module)`. The title
/// is `"#<n> <issue title>"` trimmed to 80 chars; the prompt carries the body
/// plus comments and instructs the agent to reference `(#n)` in the commit
/// subject.
pub fn task_from_issue(issue: &Issue) -> (String, String, Option<String>) {
    let title = trim_chars(&format!("#{} {}", issue.number, issue.title), 80);
    let module = app_module_for_labels(&issue.labels);
    let labels = if issue.labels.is_empty() {
        "(none)".to_string()
    } else {
        issue.labels.join(", ")
    };
    let mut prompt = format!(
        "GitHub issue #{}: {}\nState: {} | Labels: {} | Author: {}\n\n{}\n",
        issue.number, issue.title, issue.state, labels, issue.author, issue.body
    );
    if !issue.comments.is_empty() {
        prompt.push_str(&format!("\nKey comments ({}):\n", issue.comments.len()));
        for c in &issue.comments {
            prompt.push_str(&format!(
                "\n[{} @ {}]\n{}\n",
                c.author, c.created_at, c.body
            ));
        }
    }
    if issue.truncated {
        prompt.push_str("\n[note: issue body/comments were truncated to fit the context budget]\n");
    }
    if let Some(m) = &module {
        prompt.push_str(&format!("\nWork in module `{m}` (repo-relative).\n"));
    }
    prompt.push_str(&format!(
        "\nWhen you commit the fix, reference (#{}) in the commit subject, e.g. `<module>: <short description> (#{})`.\n",
        issue.number, issue.number
    ));
    (title, prompt, module)
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::extract::State;
    use axum::http::{HeaderMap, StatusCode};
    use axum::response::IntoResponse;
    use std::collections::HashMap;
    use std::sync::{Arc, Mutex};

    const ISSUE_903: &str = include_str!("testdata/github/issue_903.json");
    const COMMENTS_903: &str = include_str!("testdata/github/comments_903.json");
    const COMMENTS_891: &str = include_str!("testdata/github/comments_891.json");
    const SEARCH_WEATHER: &str = include_str!("testdata/github/search_weather.json");
    const LIST_WEATHER: &str = include_str!("testdata/github/list_weather.json");

    #[derive(Debug, Clone)]
    struct RecordedRequest {
        method: String,
        path: String,
        query: String,
        headers: HeaderMap,
        body: Vec<u8>,
    }

    #[derive(Debug, Clone)]
    struct MockResponse {
        status: u16,
        headers: Vec<(String, String)>,
        body: Vec<u8>,
    }

    #[derive(Debug, Clone, Default)]
    struct Mock {
        recorded: Arc<Mutex<Vec<RecordedRequest>>>,
        responses: Arc<Mutex<HashMap<String, MockResponse>>>,
    }

    impl Mock {
        fn respond(
            &self,
            path: &str,
            status: u16,
            headers: &[(&str, &str)],
            body: impl Into<Vec<u8>>,
        ) {
            self.responses.lock().unwrap().insert(
                path.to_string(),
                MockResponse {
                    status,
                    headers: headers
                        .iter()
                        .map(|(k, v)| ((*k).to_string(), (*v).to_string()))
                        .collect(),
                    body: body.into(),
                },
            );
        }

        fn requests(&self) -> Vec<RecordedRequest> {
            self.recorded.lock().unwrap().clone()
        }
    }

    async fn mock_handler(
        State(mock): State<Mock>,
        req: axum::http::Request<Body>,
    ) -> impl IntoResponse {
        let (parts, body) = req.into_parts();
        let body = axum::body::to_bytes(body, 4 * 1024 * 1024)
            .await
            .unwrap_or_default()
            .to_vec();
        mock.recorded.lock().unwrap().push(RecordedRequest {
            method: parts.method.to_string(),
            path: parts.uri.path().to_string(),
            query: parts.uri.query().unwrap_or_default().to_string(),
            headers: parts.headers.clone(),
            body,
        });
        let found = mock
            .responses
            .lock()
            .unwrap()
            .get(parts.uri.path())
            .cloned();
        match found {
            Some(r) => {
                let mut builder = axum::http::Response::builder()
                    .status(StatusCode::from_u16(r.status).unwrap_or(StatusCode::OK));
                for (k, v) in &r.headers {
                    builder = builder.header(k, v);
                }
                builder.body(Body::from(r.body)).unwrap()
            }
            None => axum::http::Response::builder()
                .status(StatusCode::NOT_FOUND)
                .body(Body::from("no mock for path"))
                .unwrap(),
        }
    }

    async fn spawn_mock(mock: Mock) -> String {
        let app = axum::Router::new()
            .fallback(mock_handler)
            .with_state(mock);
        let lis = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .unwrap();
        let addr = lis.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(lis, app).await.unwrap() });
        format!("http://{addr}")
    }

    fn client(base: &str, token: Option<&str>) -> GitHub {
        GitHub::with_base_url(base, "test-owner/test-repo", token.map(str::to_string)).unwrap()
    }

    fn json() -> [(&'static str, &'static str); 1] {
        [("content-type", "application/json")]
    }

    /// Decode `application/x-www-form-urlencoded` enough for assertions.
    fn decode_query(q: &str) -> String {
        let mut out = String::new();
        let bytes = q.as_bytes();
        let mut i = 0;
        while i < bytes.len() {
            match bytes[i] {
                b'+' => {
                    out.push(' ');
                    i += 1;
                }
                b'%' if i + 2 < bytes.len() => {
                    if let Ok(b) = u8::from_str_radix(&q[i + 1..i + 3], 16) {
                        out.push(b as char);
                    }
                    i += 3;
                }
                b => {
                    out.push(b as char);
                    i += 1;
                }
            }
        }
        out
    }

    fn sample_issue() -> Issue {
        Issue {
            number: 903,
            title: "Weather widgets only show Celsius".to_string(),
            state: "open".to_string(),
            labels: vec!["bug".to_string(), "weather".to_string()],
            body: "Widget shows C only.".to_string(),
            author: "coolpx".to_string(),
            created_at: "2026-11-05T04:51:07Z".to_string(),
            comments: vec![Comment {
                author: "vayun-mathur".to_string(),
                created_at: "2026-11-05T10:00:00Z".to_string(),
                body: "Reproduced, fixing.".to_string(),
            }],
            truncated: false,
        }
    }

    #[test]
    fn foreign_pagination_links_never_get_the_token() {
        let gh = GitHub::with_base_url("https://api.github.com", "o/r", Some("tok".into())).unwrap();
        let ok = gh.request(reqwest::Method::GET, "https://api.github.com/repos/o/r/issues/1/comments?page=2").build().unwrap();
        assert_eq!(ok.url().host_str(), Some("api.github.com"));
        let evil = gh.request(reqwest::Method::GET, "https://evil.example/steal").build().unwrap();
        assert_eq!(evil.url().host_str(), Some("api.github.com"));
        let sneaky = gh.request(reqwest::Method::GET, "https://api.github.com.evil.example/x").build().unwrap();
        assert_eq!(sneaky.url().host_str(), Some("api.github.com"));
    }

    #[tokio::test]
    async fn search_uses_search_api_with_query() {
        let mock = Mock::default();
        mock.respond("/search/issues", 200, &json(), SEARCH_WEATHER);
        let base = spawn_mock(mock.clone()).await;
        let gh = client(&base, None);

        let out = gh
            .search_issues(Some("celsius"), &["weather".to_string()], IssueState::Open, 5)
            .await
            .unwrap();
        assert_eq!(out.len(), 3);
        assert!(out.iter().all(|i| !i.is_pr));
        let nums: Vec<u64> = out.iter().map(|i| i.number).collect();
        assert!(nums.contains(&903));

        let reqs = mock.requests();
        assert_eq!(reqs.len(), 1);
        assert_eq!(reqs[0].method, "GET");
        assert_eq!(reqs[0].path, "/search/issues");
        let q = decode_query(&reqs[0].query);
        assert!(q.contains("repo:test-owner/test-repo"), "q={q}");
        assert!(q.contains("is:issue"), "q={q}");
        assert!(q.contains("celsius"), "q={q}");
        assert!(q.contains("state:open"), "q={q}");
        assert!(q.contains("label:\"weather\""), "q={q}");

        assert_eq!(reqs[0].headers.get("user-agent").unwrap(), "agentd");
        assert_eq!(
            reqs[0].headers.get("accept").unwrap(),
            "application/vnd.github+json"
        );
        assert_eq!(
            reqs[0].headers.get("x-github-api-version").unwrap(),
            "2022-11-28"
        );
        assert!(reqs[0].headers.get("authorization").is_none());
    }

    #[tokio::test]
    async fn list_endpoint_used_without_query() {
        let mock = Mock::default();
        mock.respond("/repos/test-owner/test-repo/issues", 200, &json(), LIST_WEATHER);
        let base = spawn_mock(mock.clone()).await;
        let gh = client(&base, None);

        let out = gh
            .search_issues(None, &["weather".to_string()], IssueState::Open, 5)
            .await
            .unwrap();
        assert_eq!(out.len(), 3);
        assert!(out.iter().all(|i| !i.is_pr));

        let reqs = mock.requests();
        assert_eq!(reqs.len(), 1);
        assert_eq!(reqs[0].path, "/repos/test-owner/test-repo/issues");
        let q = decode_query(&reqs[0].query);
        assert!(q.contains("labels=weather"), "q={q}");
        assert!(q.contains("state=open"), "q={q}");
    }

    #[tokio::test]
    async fn search_excludes_pull_requests() {
        let body = serde_json::json!({
            "total_count": 2,
            "incomplete_results": false,
            "items": [
                {"number": 10, "title": "Real issue", "state": "open",
                 "labels": [], "comments": 0, "updated_at": "2026-11-05T00:00:00Z"},
                {"number": 11, "title": "A PR", "state": "open",
                 "labels": [], "comments": 0, "updated_at": "2026-11-05T00:00:00Z",
                 "pull_request": {"url": "https://api.github.com/x", "html_url": "https://github.com/x"}},
            ],
        });
        let mock = Mock::default();
        mock.respond(
            "/search/issues",
            200,
            &json(),
            serde_json::to_vec(&body).unwrap(),
        );
        let base = spawn_mock(mock.clone()).await;
        let gh = client(&base, None);

        let out = gh
            .search_issues(Some("x"), &[], IssueState::All, 10)
            .await
            .unwrap();
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].number, 10);
    }

    #[tokio::test]
    async fn read_issue_parses_fixture() {
        let mock = Mock::default();
        mock.respond("/repos/test-owner/test-repo/issues/903", 200, &json(), ISSUE_903);
        mock.respond(
            "/repos/test-owner/test-repo/issues/903/comments",
            200,
            &json(),
            COMMENTS_903,
        );
        let base = spawn_mock(mock.clone()).await;
        let gh = client(&base, None);

        let issue = gh.read_issue(903).await.unwrap();
        assert_eq!(issue.number, 903);
        assert_eq!(issue.title, "[bug]: Weather widgets only show Celsius");
        assert_eq!(issue.state, "open");
        assert_eq!(issue.author, "coolpx");
        assert!(issue.labels.contains(&"weather".to_string()));
        assert!(issue.labels.contains(&"bug".to_string()));
        assert!(issue.body.contains("Celsius"));
        assert!(issue.comments.is_empty());
        assert!(!issue.truncated);
    }

    #[tokio::test]
    async fn read_issue_follows_comment_pagination() {
        let mock = Mock::default();
        let base = spawn_mock(mock.clone()).await;
        // Register pages after the base URL is known so the `Link`
        // header can point at the absolute mock URL (the handler
        // looks responses up at request time, so this is race-free here:
        // the client issues no request until below).
        mock.respond(
            "/repos/test-owner/test-repo/issues/42",
            200,
            &json(),
            r#"{"number":42,"title":"T","state":"open","labels":[],"user":{"login":"a"},
                "created_at":"2026-11-05T00:00:00Z","body":"b"}"#,
        );
        mock.respond(
            "/repos/test-owner/test-repo/issues/42/comments",
            200,
            &[
                ("content-type", "application/json"),
                (
                    "link",
                    &format!("<{base}/page2>; rel=\"next\", <{base}/page2>; rel=\"last\""),
                ),
            ],
            r#"[{"id":1,"user":{"login":"a"},"created_at":"2026-11-05T01:00:00Z","body":"first"}]"#,
        );
        mock.respond(
            "/page2",
            200,
            &json(),
            r#"[{"id":2,"user":{"login":"b"},"created_at":"2026-11-05T02:00:00Z","body":"second"}]"#,
        );
        let gh = client(&base, None);

        let issue = gh.read_issue(42).await.unwrap();
        assert_eq!(issue.comments.len(), 2);
        assert_eq!(issue.comments[0].body, "first");
        assert_eq!(issue.comments[1].author, "b");
        assert!(!issue.truncated);
        let paths: Vec<String> = mock.requests().iter().map(|r| r.path.clone()).collect();
        assert!(paths.contains(&"/page2".to_string()), "{paths:?}");
    }

    #[tokio::test]
    async fn read_issue_truncates_to_budget() {
        let mock = Mock::default();
        mock.respond(
            "/repos/test-owner/test-repo/issues/903",
            200,
            &json(),
            ISSUE_903,
        );
        // Real comment fixture plus a synthetic long comment so the total
        // exceeds the 12 KB budget (recorded bodies alone fit).
        let mut comments: Vec<serde_json::Value> =
            serde_json::from_str(COMMENTS_891).unwrap();
        comments.push(serde_json::json!({
            "id": 999, "user": {"login": "someone"},
            "created_at": "2026-11-05T03:00:00Z",
            "body": "y".repeat(20_000),
        }));
        mock.respond(
            "/repos/test-owner/test-repo/issues/903/comments",
            200,
            &json(),
            serde_json::to_vec(&comments).unwrap(),
        );
        let base = spawn_mock(mock.clone()).await;
        let gh = client(&base, None);

        let issue = gh.read_issue(903).await.unwrap();
        assert!(issue.truncated);
        let total: usize =
            issue.body.len() + issue.comments.iter().map(|c| c.body.len()).sum::<usize>();
        assert!(total <= BODY_BUDGET_BYTES, "total={total}");
        // The over-budget tail comment is kept but fragmented in place.
        let last = issue.comments.last().unwrap();
        assert!(last.body.ends_with("[…truncated]"), "last={:?}", &last.body[last.body.len().saturating_sub(20)..]);
    }

    #[test]
    fn apply_budget_truncates_oversize_body() {
        let (body, comments, truncated) = apply_budget("x".repeat(20_000), vec![sample_issue().comments.remove(0)]);
        assert!(truncated);
        assert!(body.len() <= BODY_BUDGET_BYTES);
        assert!(body.ends_with(TRUNC_MARKER));
        assert!(comments.is_empty());
    }

    #[tokio::test]
    async fn auth_header_sent_only_with_token() {
        let mock = Mock::default();
        mock.respond("/search/issues", 200, &json(), SEARCH_WEATHER);
        let base = spawn_mock(mock.clone()).await;

        client(&base, Some("tok123"))
            .search_issues(Some("q"), &[], IssueState::Open, 1)
            .await
            .unwrap();
        client(&base, None)
            .search_issues(Some("q"), &[], IssueState::Open, 1)
            .await
            .unwrap();

        let reqs = mock.requests();
        assert_eq!(reqs.len(), 2);
        assert_eq!(reqs[0].headers.get("authorization").unwrap(), "Bearer tok123");
        assert!(reqs[1].headers.get("authorization").is_none());
    }

    #[tokio::test]
    async fn rate_limit_errors_carry_reset_time() {
        let mock = Mock::default();
        mock.respond(
            "/search/issues",
            403,
            &[
                ("content-type", "application/json"),
                ("x-ratelimit-remaining", "0"),
                ("x-ratelimit-reset", "1793900736"),
            ],
            r#"{"message":"API rate limit exceeded"}"#,
        );
        mock.respond(
            "/repos/test-owner/test-repo/issues",
            429,
            &[("content-type", "application/json"), ("retry-after", "60")],
            r#"{"message":"slow down"}"#,
        );
        let base = spawn_mock(mock.clone()).await;
        let gh = client(&base, None);

        let err = gh
            .search_issues(Some("q"), &[], IssueState::Open, 1)
            .await
            .unwrap_err();
        assert!(matches!(err, Error::RateLimited { .. }), "{err:?}");
        assert!(err.to_string().contains("1793900736"), "{err}");

        let err = gh
            .search_issues(None, &[], IssueState::Open, 1)
            .await
            .unwrap_err();
        assert!(matches!(err, Error::RateLimited { .. }), "{err:?}");

        // A plain 403 without rate-limit headers is a normal API error.
        let mock2 = Mock::default();
        mock2.respond(
            "/repos/test-owner/test-repo/issues/1",
            403,
            &json(),
            r#"{"message":"Forbidden"}"#,
        );
        let base2 = spawn_mock(mock2.clone()).await;
        let err = client(&base2, None).read_issue(1).await.unwrap_err();
        assert!(matches!(err, Error::Api { status: 403, .. }), "{err:?}");
        assert!(err.to_string().contains("Forbidden"), "{err}");
    }

    #[tokio::test]
    async fn post_comment_shape_and_token_required() {
        let mock = Mock::default();
        mock.respond(
            "/repos/test-owner/test-repo/issues/1/comments",
            201,
            &json(),
            r#"{"html_url":"https://github.com/test-owner/test-repo/issues/1#issuecomment-9"}"#,
        );
        let base = spawn_mock(mock.clone()).await;

        let url = client(&base, Some("tok"))
            .post_comment(1, "hello")
            .await
            .unwrap();
        assert_eq!(
            url,
            "https://github.com/test-owner/test-repo/issues/1#issuecomment-9"
        );
        let reqs = mock.requests();
        assert_eq!(reqs.len(), 1);
        assert_eq!(reqs[0].method, "POST");
        let v: serde_json::Value = serde_json::from_slice(&reqs[0].body).unwrap();
        assert_eq!(v, serde_json::json!({ "body": "hello" }));

        // No token: error before any HTTP call (unreachable base proves it).
        let offline =
            GitHub::with_base_url("http://127.0.0.1:9", "o/r", None).unwrap();
        assert!(matches!(
            offline.post_comment(1, "x").await,
            Err(Error::NoToken(_))
        ));
        assert!(matches!(
            offline.add_labels(1, &["a".to_string()]).await,
            Err(Error::NoToken(_))
        ));
    }

    #[tokio::test]
    async fn add_labels_shape() {
        let mock = Mock::default();
        mock.respond(
            "/repos/test-owner/test-repo/issues/1/labels",
            200,
            &json(),
            r#"[{"name":"solved for next release"}]"#,
        );
        let base = spawn_mock(mock.clone()).await;

        client(&base, Some("tok"))
            .add_labels(1, &["solved for next release".to_string()])
            .await
            .unwrap();
        let reqs = mock.requests();
        assert_eq!(reqs.len(), 1);
        assert_eq!(reqs[0].method, "POST");
        assert_eq!(reqs[0].path, "/repos/test-owner/test-repo/issues/1/labels");
        let v: serde_json::Value = serde_json::from_slice(&reqs[0].body).unwrap();
        assert_eq!(v, serde_json::json!({ "labels": ["solved for next release"] }));
    }

    #[test]
    fn label_to_module_spot_checks() {
        let app = |s: &str| app_module_for_labels(&[s.to_string()]);
        assert_eq!(app("weather"), Some("weather".to_string()));
        assert_eq!(app("chess"), Some("games/chess".to_string()));
        assert_eq!(app("logicgate"), Some("games/logicgate".to_string()));
        assert_eq!(app("hub"), Some("games/hub".to_string()));
        assert_eq!(app("voxels"), Some("games/voxels".to_string()));
        assert_eq!(app("Weather"), Some("weather".to_string()));
        assert_eq!(app("watch"), None);
        assert_eq!(app("bug"), None);
        assert_eq!(app("enhancement"), None);
        assert_eq!(app("2.6.12"), None);
        assert_eq!(app("solved for next release"), None);
        // First app label wins over non-app labels.
        assert_eq!(
            app_module_for_labels(&[
                "bug".to_string(),
                "2.6.12".to_string(),
                "kart".to_string()
            ]),
            Some("games/kart".to_string())
        );
        assert_eq!(app_module_for_labels(&["bug".to_string()]), None);
    }

    #[test]
    fn mapped_module_dirs_exist_in_worktree() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        assert!(root.join("settings.gradle.kts").is_file());
        for m in ALL_APP_MODULES {
            assert!(root.join(m).is_dir(), "module dir missing: {m}");
        }
    }

    #[test]
    fn task_from_issue_shapes_title_prompt_module() {
        let (title, prompt, module) = task_from_issue(&sample_issue());
        assert_eq!(title, "#903 Weather widgets only show Celsius");
        assert_eq!(module, Some("weather".to_string()));
        assert!(prompt.contains("Widget shows C only."), "{prompt}");
        assert!(prompt.contains("Reproduced, fixing."), "{prompt}");
        assert!(prompt.contains("(#903)"), "{prompt}");

        let mut long = sample_issue();
        long.title = "x".repeat(100);
        let (title, _, _) = task_from_issue(&long);
        assert!(title.chars().count() <= 80, "{title}");
        assert!(title.starts_with("#903 "), "{title}");
    }

    #[test]
    fn percent_encode_label_cases() {
        assert_eq!(percent_encode_label("bug"), "bug");
        assert_eq!(percent_encode_label("solved for next release"), "solved%20for%20next%20release");
        assert_eq!(percent_encode_label("2.6.12"), "2.6.12");
        assert_eq!(percent_encode_label("a/b+c"), "a%2Fb%2Bc");
        assert_eq!(percent_encode_label("~x-_."), "~x-_.");
    }

    #[test]
    fn normalize_rfc3339_cases() {
        assert_eq!(normalize_rfc3339("2026-11-05T00:00:00Z"), "2026-11-05T00:00:00Z");
        assert_eq!(normalize_rfc3339("2026-11-05T00:00:00+00:00"), "2026-11-05T00:00:00Z");
        // Unparseable / other offsets pass through unchanged.
        assert_eq!(normalize_rfc3339("garbage"), "garbage");
        // Normalized strings compare chronologically.
        assert!(normalize_rfc3339("2026-11-05T04:51:08Z") > normalize_rfc3339("2026-11-05T04:51:07+00:00"));
    }

    #[test]
    fn app_labels_cover_all_app_modules() {
        // Every label mapping to a module dir appears in ALL_APP_LABELS
        // (probes beyond the spot checks in label_to_module_spot_checks).
        for probe in [
            "weather", "chess", "hub", "voxels", "logicgate", "notes", "watch",
        ] {
            assert!(ALL_APP_LABELS.contains(&probe), "missing {probe}");
            // `watch` has no module dir but is still an app-name label.
        }
        // Non-app labels are not in the universe.
        for non in ["bug", "enhancement", "2.6.12", "solved for next release"] {
            assert!(!ALL_APP_LABELS.contains(&non), "unexpected {non}");
        }
        // Every module-mapped label resolves back to a known module.
        for label in ALL_APP_LABELS {
            if *label == "watch" {
                assert_eq!(app_module_for_labels(&[label.to_string()]), None);
            } else {
                assert!(app_module_for_labels(&[label.to_string()]).is_some(), "no module for {label}");
            }
        }
    }

    /// Issues list fixture for `list_issues_since`: PR filtered in the
    /// client, one stale (before watermark), two fresh.
    fn list_since_body() -> Vec<u8> {
        serde_json::to_vec(&serde_json::json!([
            {"number": 800, "title": "Old", "state": "open", "labels": [],
             "comments": 0, "created_at": "2026-10-01T00:00:00Z",
             "updated_at": "2026-10-01T00:00:00Z"},
            {"number": 901, "title": "A PR", "state": "open", "labels": [],
             "comments": 0, "created_at": "2026-11-06T00:00:00Z",
             "updated_at": "2026-11-06T00:00:00Z",
             "pull_request": {"url": "https://api.github.com/x"}},
            {"number": 902, "title": "Fresh one", "state": "closed", "labels": [{"name": "bug"}],
             "comments": 1, "created_at": "2026-11-06T01:00:00Z",
             "updated_at": "2026-11-06T02:00:00Z"},
            {"number": 903, "title": "Fresh two", "state": "open",
             "labels": [{"name": "weather"}], "comments": 0,
             "created_at": "2026-11-06T03:00:00.500Z",
             "updated_at": "2026-11-06T03:00:00Z"},
        ]))
        .unwrap()
    }

    #[tokio::test]
    async fn list_issues_since_filters_and_shapes_request() {
        let mock = Mock::default();
        mock.respond(
            "/repos/test-owner/test-repo/issues",
            200,
            &json(),
            list_since_body(),
        );
        let base = spawn_mock(mock.clone()).await;
        let gh = client(&base, None);

        let out = gh
            .list_issues_since("2026-11-05T00:00:00Z", 50)
            .await
            .unwrap();
        assert_eq!(out.len(), 2);
        assert_eq!(out[0].number, 902);
        assert_eq!(out[1].number, 903);
        assert_eq!(out[1].created_at, "2026-11-06T03:00:00.500Z");
        assert_eq!(out[0].labels, vec!["bug".to_string()]);

        let reqs = mock.requests();
        assert_eq!(reqs.len(), 1);
        assert_eq!(reqs[0].method, "GET");
        assert_eq!(reqs[0].path, "/repos/test-owner/test-repo/issues");
        let q = decode_query(&reqs[0].query);
        assert!(q.contains("state=all"), "q={q}");
        assert!(q.contains("sort=created"), "q={q}");
        assert!(q.contains("direction=asc"), "q={q}");
        assert!(q.contains("per_page=50"), "q={q}");
    }

    #[tokio::test]
    async fn list_issues_since_normalizes_watermark_offset() {
        let mock = Mock::default();
        mock.respond(
            "/repos/test-owner/test-repo/issues",
            200,
            &json(),
            list_since_body(),
        );
        let base = spawn_mock(mock.clone()).await;
        let gh = client(&base, None);

        // `+00:00` watermark behaves like `Z`.
        let out = gh
            .list_issues_since("2026-11-06T01:00:00+00:00", 50)
            .await
            .unwrap();
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].number, 903);
    }

    #[tokio::test]
    async fn remove_label_encodes_path_and_tolerates_404() {
        let mock = Mock::default();
        mock.respond(
            "/repos/test-owner/test-repo/issues/1/labels/enhancement",
            200,
            &json(),
            r#"[]"#,
        );
        let base = spawn_mock(mock.clone()).await;
        let gh = client(&base, Some("tok"));

        gh.remove_label(1, "enhancement").await.unwrap();
        // Space in the label is percent-encoded; the mock has no such path
        // → 404 → Ok (already gone).
        gh.remove_label(1, "solved for next release").await.unwrap();

        let reqs = mock.requests();
        assert_eq!(reqs.len(), 2);
        assert!(reqs.iter().all(|r| r.method == "DELETE"));
        assert_eq!(reqs[0].path, "/repos/test-owner/test-repo/issues/1/labels/enhancement");
        assert_eq!(
            reqs[1].path,
            "/repos/test-owner/test-repo/issues/1/labels/solved%20for%20next%20release"
        );

        // No token: error before any HTTP call.
        let offline = GitHub::with_base_url("http://127.0.0.1:9", "o/r", None).unwrap();
        assert!(!offline.has_token());
        assert!(matches!(
            offline.remove_label(1, "bug").await,
            Err(Error::NoToken(_))
        ));
        assert!(client(&base, Some("tok")).has_token());
    }

    #[tokio::test]
    async fn issue_has_marker_scans_comments() {
        let mock = Mock::default();
        mock.respond(
            "/repos/test-owner/test-repo/issues/10/comments",
            200,
            &json(),
            r#"[{"id":1,"user":{"login":"a"},"created_at":"2026-11-06T00:00:00Z","body":"plain comment"},
                {"id":2,"user":{"login":"b"},"created_at":"2026-11-06T01:00:00Z","body":"x <!-- agentd-triage --> y"}]"#,
        );
        mock.respond(
            "/repos/test-owner/test-repo/issues/11/comments",
            200,
            &json(),
            r#"[{"id":3,"user":{"login":"a"},"created_at":"2026-11-06T00:00:00Z","body":"nothing here"}]"#,
        );
        let base = spawn_mock(mock.clone()).await;
        let gh = client(&base, None);

        assert!(gh.issue_has_marker(10, "<!-- agentd-triage -->").await.unwrap());
        assert!(!gh.issue_has_marker(11, "<!-- agentd-triage -->").await.unwrap());
        // Substring search: partial marker still matches scanner semantics.
        assert!(gh.issue_has_marker(10, "agentd-triage").await.unwrap());
    }

    #[test]
    fn render_helpers_include_key_fields() {
        let issue = sample_issue();
        let text = render_issue_for_model(&issue);
        assert!(text.contains("#903"));
        assert!(text.contains("Weather widgets only show Celsius"));
        assert!(text.contains("weather"));
        assert!(text.contains("vayun-mathur"));

        let summaries = vec![IssueSummary {
            number: 903,
            title: "T".to_string(),
            state: "open".to_string(),
            labels: vec!["weather".to_string()],
            comments: 2,
            created_at: "2026-11-05T00:00:00Z".to_string(),
            updated_at: "2026-11-05T00:00:00Z".to_string(),
            is_pr: false,
        }];
        let text = render_search_for_model(&summaries);
        assert!(text.contains("#903"));
        assert!(text.contains("weather"));
    }
}
