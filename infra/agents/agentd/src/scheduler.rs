//! Scheduler: `AGENTD_SLOTS` workers pull queued tasks from the DB.
//!
//! Each running task holds one worker (and one runner slot) for its whole
//! lifetime. The queue lives in SQLite so it survives restarts; on startup,
//! tasks left `running` by a previous process are marked failed
//! ("interrupted by restart") — see
//! [`Store::mark_interrupted_running_failed`](crate::store::Store::mark_interrupted_running_failed).
//! The scheduler loop also polls the release-signal file so the UI banner
//! stays fresh; build preemption itself happens inside the agent loop.

use crate::agent::{drive_task, DriverCtx, TaskOutcome};
use crate::models::TaskStatus;

/// Spawn `slots` worker tasks. Each worker claims the oldest queued task
/// atomically (`claim_queued`), drives it, records the terminal status, and
/// repeats. Returns the join handles.
pub fn spawn_workers(ctx: DriverCtx, slots: usize) -> Vec<tokio::task::JoinHandle<()>> {
    let mut handles = Vec::new();
    for slot in 0..slots as u32 {
        let ctx = ctx.clone();
        handles.push(tokio::spawn(async move {
            worker_loop(ctx, slot).await;
        }));
    }
    handles
}

async fn worker_loop(ctx: DriverCtx, slot: u32) {
    let poll = ctx.config.poll_interval();
    loop {
        let claimed = ctx.store.claim_queued(slot);
        let task = match claimed {
            Ok(Some(t)) => t,
            Ok(None) => {
                tokio::time::sleep(poll).await;
                continue;
            }
            Err(e) => {
                tracing::error!("scheduler claim failed: {e}");
                tokio::time::sleep(poll).await;
                continue;
            }
        };
        let task_id = task.id;
        ctx.emit(
            task_id,
            "status",
            serde_json::json!({"message": format!("claimed by worker {slot}"), "slot": slot}),
        );
        match drive_task(ctx.clone(), task_id, slot).await {
            TaskOutcome::Succeeded { commit_sha, pushed, summary } => {
                let _ = ctx.store.update_status(
                    task_id,
                    TaskStatus::Succeeded,
                    None,
                    Some(&summary),
                );
                ctx.emit(
                    task_id,
                    "status",
                    serde_json::json!({"message": "task succeeded", "commit": commit_sha, "pushed": pushed}),
                );
            }
            TaskOutcome::Failed { reason } => {
                let _ = ctx.store.update_status(task_id, TaskStatus::Failed, Some(&reason), None);
            }
            TaskOutcome::Cancelled => {
                // Status already `cancelled` (set by the cancel route); emit
                // for the UI in case the driver exited first.
                ctx.emit(
                    task_id,
                    "status",
                    serde_json::json!({"message": "task cancelled"}),
                );
            }
        }
    }
}

/// Cancel a task: mark `cancelled` in the DB (the driver loop observes it
/// and stops) and ask the runner to kill whatever the slot runs. `slot` is
/// the worker/slot id recorded on the task row, if any.
pub async fn cancel_task(ctx: &DriverCtx, task_id: i64) -> Result<bool, String> {
    let task = ctx
        .store
        .get_task(task_id)?
        .ok_or_else(|| "task not found".to_string())?;
    if task.status.is_terminal() {
        return Ok(false);
    }
    ctx.store
        .update_status(task_id, TaskStatus::Cancelled, Some("cancelled by operator"), None)?;
    ctx.emit(
        task_id,
        "status",
        serde_json::json!({"message": "cancel requested by operator"}),
    );
    if let Some(slot) = task.slot {
        // Best effort: the driver also stops on its own via the DB flag.
        match ctx.runner.cancel_slot(slot).await {
            Ok(killed) => Ok(killed),
            Err(e) => {
                tracing::warn!("runner cancel slot {slot}: {e}");
                Ok(false)
            }
        }
    } else {
        Ok(true)
    }
}
