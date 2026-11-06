//! Task / event / session row types and small pure helpers.
//!
//! The canonical schema lives in [`crate::store`]; this module holds the
//! Rust-side enums and the pure functions that are unit-tested (slug,
//! truncation, commit-message validation).

use serde::{Deserialize, Serialize};

/// Task lifecycle.
///
/// * `queued` — waiting for a worker (fresh or re-queued after a park).
/// * `running` — held by a worker.
/// * `needs_input` — parked; the driver released its slot. The `kind` field of
///   [`Pending`] (`pending_json`) says what it waits for: `question`, `plan`,
///   `chat`, `budget`, `error`, `stopped`.
/// * `review` — parked with finished work awaiting approval.
/// * `succeeded` — pushed after Approve.
/// * `discarded` — terminal; operator threw the work away.
/// * `failed` — unrecoverable only.
///
/// `cancelled` is retired: nothing creates it anymore, but old rows still
/// parse (mapped to the kept `Cancelled` variant) so historic tasks render.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TaskStatus {
    Queued,
    Running,
    NeedsInput,
    Review,
    Succeeded,
    Discarded,
    Failed,
    Cancelled,
}

impl TaskStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            TaskStatus::Queued => "queued",
            TaskStatus::Running => "running",
            TaskStatus::NeedsInput => "needs_input",
            TaskStatus::Review => "review",
            TaskStatus::Succeeded => "succeeded",
            TaskStatus::Discarded => "discarded",
            TaskStatus::Failed => "failed",
            TaskStatus::Cancelled => "cancelled",
        }
    }

    pub fn parse(s: &str) -> Option<TaskStatus> {
        match s {
            "queued" => Some(TaskStatus::Queued),
            "running" => Some(TaskStatus::Running),
            "needs_input" => Some(TaskStatus::NeedsInput),
            "review" => Some(TaskStatus::Review),
            "succeeded" => Some(TaskStatus::Succeeded),
            "discarded" => Some(TaskStatus::Discarded),
            "failed" => Some(TaskStatus::Failed),
            "cancelled" => Some(TaskStatus::Cancelled),
            _ => None,
        }
    }

    pub fn is_terminal(self) -> bool {
        matches!(
            self,
            TaskStatus::Succeeded
                | TaskStatus::Failed
                | TaskStatus::Discarded
                | TaskStatus::Cancelled
        )
    }

    /// Parked-but-resumable: a message in the inbox re-queues the task.
    pub fn is_parked(self) -> bool {
        matches!(self, TaskStatus::NeedsInput | TaskStatus::Review)
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
    /// Task kind: `task` (interactive, default) or `triage` (automatic
    /// issue triage; read-only tools, ends via `finish_triage`).
    #[serde(default = "default_task_kind")]
    pub kind: String,
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
    /// What a parked task waits for (`pending_json`); `None` on fresh tasks
    /// and after terminal transitions.
    #[serde(default)]
    pub pending: Option<Pending>,
    /// Raw `todos_json` / `rules_json` (managed by later phases; empty now).
    #[serde(default)]
    pub todos_json: Option<String>,
    #[serde(default)]
    pub rules_json: Option<String>,
    #[serde(default)]
    pub require_plan: bool,
    #[serde(default)]
    pub plan_approved: bool,
    /// Mirror commit holding the parked work (`refs/agent-wip/<id>`).
    #[serde(default)]
    pub wip_sha: Option<String>,
    #[serde(default)]
    pub pushed_sha: Option<String>,
    #[serde(default)]
    pub final_diff: Option<String>,
    #[serde(default)]
    pub commit_message: Option<String>,
    #[serde(default)]
    pub issue_number: Option<i64>,
    /// Stored `propose_plan` markdown (the approved plan also appears in the
    /// volatile tail of the system prompt).
    #[serde(default)]
    pub plan_md: Option<String>,
    /// Operator Stop flag observed by a running driver (cleared on park).
    #[serde(default)]
    pub stop_requested: bool,
}

/// What a parked (`needs_input` / `review`) task is waiting for.
///
/// Stored as `pending_json`. The shape is deliberately generic:
///
/// ```json
/// {"kind": "question", "tool_call_id": "call_abc",
///  "payload": {"question": "...", "options": ["a", "b"], "multi_select": false}}
/// ```
///
/// `kind` values: `question`, `plan`, `review`, `chat`, `budget`, `error`,
/// `stopped`. `tool_call_id` is set when the park answers a specific pending
/// tool call (`ask_user`, `propose_plan`, `finish`); on resume the driver
/// appends exactly one tool result for it built from the drained inbox.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Pending {
    pub kind: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    #[serde(default)]
    pub payload: serde_json::Value,
}

impl Pending {
    pub fn new(
        kind: &str,
        tool_call_id: Option<String>,
        payload: serde_json::Value,
    ) -> Pending {
        Pending {
            kind: kind.to_string(),
            tool_call_id,
            payload,
        }
    }
}

/// `pending.kind` values (park reasons).
pub const PENDING_QUESTION: &str = "question";
pub const PENDING_PLAN: &str = "plan";
pub const PENDING_REVIEW: &str = "review";
pub const PENDING_CHAT: &str = "chat";
pub const PENDING_BUDGET: &str = "budget";
pub const PENDING_ERROR: &str = "error";
pub const PENDING_STOPPED: &str = "stopped";

/// Event `kind` values for the task timeline.
pub const EVENT_ASSISTANT: &str = "assistant";
pub const EVENT_TOOL_CALL: &str = "tool_call";
pub const EVENT_TOOL_RESULT: &str = "tool_result";
pub const EVENT_STATUS: &str = "status";
pub const EVENT_USER_MESSAGE: &str = "user_message";
pub const EVENT_QUESTION: &str = "question";
pub const EVENT_PLAN: &str = "plan";
pub const EVENT_TODOS: &str = "todos";
pub const EVENT_REVIEW: &str = "review";

/// Default [`Task::kind`] for rows created before the column existed.
pub fn default_task_kind() -> String {
    TASK_KIND_TASK.to_string()
}

/// `kind` for normal interactive tasks.
pub const TASK_KIND_TASK: &str = "task";
/// `kind` for automatic issue-triage tasks.
#[allow(dead_code)] // Read by the driver worker's triage path (later step).
pub const TASK_KIND_TRIAGE: &str = "triage";

/// Mirror ref holding a task's parked work.
pub fn wip_ref(task_id: i64) -> String {
    format!("refs/agent-wip/{task_id}")
}

/// Single place mapping tool name → "parking tool". Parking tools must be the
/// sole call in their turn (enforced server-side): `ask_user`, `propose_plan`,
/// and `finish` (which parks as `review` instead of pushing).
pub fn is_parking_tool(name: &str) -> bool {
    matches!(name, "ask_user" | "propose_plan" | "finish")
}

/// Message returned to the model when a mutating tool is blocked by the plan
/// gate (`require_plan` set, plan not yet approved).
pub fn plan_gate_message() -> &'static str {
    "Propose a plan with propose_plan and wait for approval before editing or building."
}

/// Cap for stored `propose_plan` markdown (~32 KB) with a truncation note.
pub const PLAN_MD_CAP_BYTES: usize = 32 * 1024;

/// Cap plan markdown for `plan_md` / event / pending storage.
pub fn cap_plan_markdown(plan: &str) -> (String, bool) {
    if plan.len() <= PLAN_MD_CAP_BYTES {
        return (plan.to_string(), false);
    }
    let mut end = PLAN_MD_CAP_BYTES;
    while end > 0 && !plan.is_char_boundary(end) {
        end -= 1;
    }
    (
        format!(
            "{}\n… [plan truncated at {} bytes] …",
            &plan[..end],
            plan.len()
        ),
        true,
    )
}

/// Inbox `kind` tags. The plan-approve / plan-changes / review routes tag
/// their rows so the resume path can format the pending tool-call answer
/// differently from the raw text (plain steering rows keep `kind == ""`).
pub const INBOX_PLAN_APPROVE: &str = "plan_approve";
pub const INBOX_PLAN_CHANGES: &str = "plan_changes";
pub const INBOX_REVIEW_CHANGES: &str = "review_changes";

/// One todo item for `update_todos{items}`.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct TodoItem {
    pub text: String,
    pub status: String,
}

/// Max todo items per `update_todos` call.
pub const MAX_TODOS: usize = 30;
/// Max chars per todo `text`.
pub const MAX_TODO_CHARS: usize = 200;

/// Validate `update_todos` items. Returns the canonical items on success.
pub fn validate_todos(items: &[TodoItem]) -> Result<Vec<TodoItem>, String> {
    if items.len() > MAX_TODOS {
        return Err(format!(
            "too many todos: {} (limit {MAX_TODOS})",
            items.len()
        ));
    }
    for item in items {
        if !matches!(item.status.as_str(), "pending" | "in_progress" | "done") {
            return Err(format!(
                "bad todo status {:?}: must be pending|in_progress|done",
                item.status
            ));
        }
        if item.text.chars().count() > MAX_TODO_CHARS {
            return Err(format!(
                "todo text too long ({} chars, limit {MAX_TODO_CHARS}): {:?}",
                item.text.chars().count(),
                item.text.chars().take(60).collect::<String>(),
            ));
        }
        if item.text.trim().is_empty() {
            return Err("todo text must be non-empty".to_string());
        }
    }
    Ok(items.to_vec())
}

/// Render stored todos as human-readable text for the system prompt volatile
/// tail. `todos_json` holds `[{text, status}]`.
pub fn render_todos(todos_json: Option<&str>) -> String {
    let Some(raw) = todos_json else {
        return String::new();
    };
    let items: Vec<TodoItem> = match serde_json::from_str(raw) {
        Ok(v) => v,
        Err(_) => return String::new(),
    };
    if items.is_empty() {
        return String::new();
    }
    items
        .iter()
        .map(|t| {
            let mark = match t.status.as_str() {
                "done" => "[x]",
                "in_progress" => "[>]",
                _ => "[ ]",
            };
            format!("{mark} {}", t.text)
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// Cap for `final_diff` stored on the task row (~50 KB) plus truncation note.
pub const FINAL_DIFF_CAP_BYTES: usize = 50 * 1024;

/// Cap a runner diff for `final_diff` storage, appending a truncation note.
pub fn cap_final_diff(diff: &str) -> (String, bool) {
    if diff.len() <= FINAL_DIFF_CAP_BYTES {
        return (diff.to_string(), false);
    }
    let mut end = FINAL_DIFF_CAP_BYTES;
    while end > 0 && !diff.is_char_boundary(end) {
        end -= 1;
    }
    (
        format!(
            "{}\n… [diff truncated at {} bytes; full diff in the branch] …",
            &diff[..end],
            diff.len()
        ),
        true,
    )
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
///
/// When `issue` is `Some(n)`, the subject must end with ` (#<n>)` (repo
/// convention `appname: short description (#iss)`), so issue-linked fixes
/// always reference their issue.
pub fn validate_commit_message(msg: &str) -> Result<(), String> {
    validate_commit_message_for_issue(msg, None)
}

/// [`validate_commit_message`] plus the ` (#<n>)` suffix requirement when the
/// task links issue `n`. Used by `finish` for issue-linked tasks.
pub fn validate_commit_message_for_issue(msg: &str, issue: Option<i64>) -> Result<(), String> {
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
    if let Some(n) = issue {
        let suffix = format!("(#{n})");
        if !first.trim_end().ends_with(&suffix) {
            return Err(format!(
                "this task fixes issue #{n}, so the commit subject must end with ` (#{n})` (e.g. `notes: fix editor crash (#{n})`)"
            ));
        }
    }
    if !msg.trim().contains('\n') {
        return Err(
            "commit message needs a blank line and a prose body after the first line".to_string(),
        );
    }
    Ok(())
}
