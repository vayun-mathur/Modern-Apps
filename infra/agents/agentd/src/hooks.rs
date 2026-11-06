//! Post-push hook: runs after a successful Approve push, never fails it.

use crate::agent::DriverCtx;
use crate::issues::AfterPush;
use crate::models::EVENT_STATUS;

/// Called after a task's WIP ref is pushed to its branch on Approve: for
/// issue-linked tasks, comment on the GitHub issue and add the
/// "solved for next release" label. The caller ignores the `Result`, so a
/// GitHub failure never fails the approve; it is surfaced as a status event.
pub async fn after_push(ctx: &DriverCtx, task: &crate::models::Task) -> Result<(), String> {
    let message = match crate::issues::after_push(ctx, task).await {
        AfterPush::NoIssue => return Ok(()),
        AfterPush::Posted { comment_url } => format!("commented on issue: {comment_url}"),
        AfterPush::NoToken => "issue not updated: no AGENTD_GITHUB_TOKEN configured".to_string(),
        AfterPush::Failed { message } => format!("issue update failed: {message}"),
    };
    ctx.emit(task.id, EVENT_STATUS, serde_json::json!({ "message": message }));
    Ok(())
}
