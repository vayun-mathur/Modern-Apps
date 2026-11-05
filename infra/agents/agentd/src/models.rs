//! Task / event / session row types and small pure helpers.
//!
//! The canonical schema lives in [`crate::store`]; this module holds the
//! Rust-side enums and the pure functions that are unit-tested (slug,
//! truncation, commit-message validation).

use serde::{Deserialize, Serialize};

/// Task lifecycle. `queued -> running -> {succeeded|failed|cancelled}`.
/// `running` rows found at startup are marked failed ("interrupted by
/// restart"); see [`crate::store::Store::mark_interrupted_running_failed`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TaskStatus {
    Queued,
    Running,
    Succeeded,
    Failed,
    Cancelled,
}

impl TaskStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            TaskStatus::Queued => "queued",
            TaskStatus::Running => "running",
            TaskStatus::Succeeded => "succeeded",
            TaskStatus::Failed => "failed",
            TaskStatus::Cancelled => "cancelled",
        }
    }

    pub fn parse(s: &str) -> Option<TaskStatus> {
        match s {
            "queued" => Some(TaskStatus::Queued),
            "running" => Some(TaskStatus::Running),
            "succeeded" => Some(TaskStatus::Succeeded),
            "failed" => Some(TaskStatus::Failed),
            "cancelled" => Some(TaskStatus::Cancelled),
            _ => None,
        }
    }

    pub fn is_terminal(self) -> bool {
        matches!(
            self,
            TaskStatus::Succeeded | TaskStatus::Failed | TaskStatus::Cancelled
        )
    }
}

/// One task row.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Task {
    pub id: i64,
    pub title: String,
    pub prompt: String,
    pub module_scope: String,
    pub model: String,
    pub status: TaskStatus,
    pub slot: Option<u32>,
    pub branch: String,
    pub base_sha: Option<String>,
    pub commit_sha: Option<String>,
    pub pushed: bool,
    pub created_at: i64,
    pub updated_at: i64,
    pub started_at: Option<i64>,
    pub finished_at: Option<i64>,
    pub steps: i64,
    pub prompt_tokens: i64,
    pub completion_tokens: i64,
    pub cost_usd: f64,
    pub budget_steps: i64,
    pub budget_usd: f64,
    pub error: Option<String>,
    pub summary: Option<String>,
}

/// One event row (append-only live log for a task).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Event {
    pub id: i64,
    pub task_id: i64,
    pub seq: i64,
    pub ts: i64,
    pub kind: String,
    pub body: serde_json::Value,
}

pub fn now_unix() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// Build a safe branch name `agent/<id>-<slug>` from the numeric id and a
/// free-text title. Only `[a-z0-9-]` survive; runs collapse to one `-`;
/// empty slugs fall back to `task`.
pub fn branch_name(id: i64, title: &str) -> String {
    let slug = slugify(title);
    format!("agent/{id}-{slug}")
}

pub fn slugify(title: &str) -> String {
    let mut out = String::new();
    let mut prev_dash = true; // collapse leading run too
    for c in title.to_lowercase().chars() {
        if c.is_ascii_alphanumeric() {
            out.push(c);
            prev_dash = false;
        } else if !prev_dash {
            out.push('-');
            prev_dash = true;
        }
    }
    while out.ends_with('-') {
        out.pop();
    }
    if out.is_empty() {
        out.push_str("task");
    }
    if out.len() > 48 {
        out.truncate(48);
        while out.ends_with('-') {
            out.pop();
        }
    }
    out
}

/// Max bytes kept per tool result before truncation.
pub const RESULT_TRUNCATE_BYTES: usize = 16 * 1024;

/// Truncate a tool result to ~16 KiB, keeping head + tail with a marker.
/// Returns `(text, was_truncated)`.
pub fn truncate_result(s: &str) -> (String, bool) {
    if s.len() <= RESULT_TRUNCATE_BYTES {
        return (s.to_string(), false);
    }
    let keep_head = RESULT_TRUNCATE_BYTES * 3 / 4;
    let keep_tail = RESULT_TRUNCATE_BYTES / 4;
    // Snap to char boundaries.
    let mut head_end = keep_head.min(s.len());
    while head_end > 0 && !s.is_char_boundary(head_end) {
        head_end -= 1;
    }
    let mut tail_start = s.len().saturating_sub(keep_tail);
    while tail_start < s.len() && !s.is_char_boundary(tail_start) {
        tail_start += 1;
    }
    let omitted = s.len().saturating_sub(head_end + (s.len() - tail_start));
    (
        format!(
            "{}\n… [truncated {} bytes] …\n{}",
            &s[..head_end],
            omitted,
            &s[tail_start..]
        ),
        true,
    )
}

/// Human placeholder swapped in for old tool results once the conversation
/// exceeds the context budget (keeps the model oriented without the bytes).
pub fn trim_placeholder(tool_name: &str, bytes: usize) -> String {
    format!("[earlier {tool_name} result trimmed, was ~{bytes} chars]")
}

/// Validate the `finish{commit_message}` first line: `<module>: <desc>`
/// with `module` a plausible Gradle path (`[a-z0-9-/]+`) and the whole line
/// <= 80 chars, mirroring the repo's `appname: short description` rule.
pub fn validate_commit_message(msg: &str) -> Result<(), String> {
    let first = msg.lines().next().unwrap_or("").trim();
    if first.is_empty() {
        return Err("commit message is empty".to_string());
    }
    if first.chars().count() > 80 {
        return Err(format!(
            "commit message first line is {} chars (limit 80): {first:?}",
            first.chars().count()
        ));
    }
    let Some((scope, desc)) = first.split_once(':') else {
        return Err(
            "commit message must start with `<module>: <description>` (e.g. `notes: fix editor crash`)".to_string(),
        );
    };
    let scope = scope.trim();
    let desc = desc.trim();
    if scope.is_empty()
        || !scope
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '/' || c == '-')
    {
        return Err(format!(
            "commit scope {scope:?} must match [a-z0-9/-]+ (e.g. `notes`, `games/logicgate`)"
        ));
    }
    if desc.is_empty() {
        return Err("commit description after `<module>:` must be non-empty".to_string());
    }
    if !msg.trim().contains('\n') {
        return Err(
            "commit message needs a blank line and a prose body after the first line".to_string(),
        );
    }
    Ok(())
}
