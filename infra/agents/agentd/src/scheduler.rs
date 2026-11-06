//! Scheduler: `AGENTD_SLOTS` workers pull queued tasks from the DB.
//!
//! A worker holds a runner slot only while a task is `running`. Whenever the
//! agent must wait (question, chat, budget, error, Stop) the driver parks
//! the task — persisting the conversation and releasing the slot (`slot =
//! NULL`) — and returns to the claim loop. The queue lives in SQLite so it
//! survives restarts; on startup, tasks left `running` by a previous process
//! are recovered (synthetic results + re-park); see
//! [`Store::recover_interrupted`](crate::store::Store::recover_interrupted).
//! The scheduler loop also polls the release-signal file so the UI banner
//! stays fresh; build preemption itself happens inside the agent loop.

use crate::agent::{drive_task, DriverCtx, TaskOutcome};
use crate::models::{Pending, TaskStatus, PENDING_ERROR, PENDING_PLAN, EVENT_STATUS, INBOX_PLAN_APPROVE};

/// Restart recovery (replaces `mark_interrupted_running_failed`).
///
/// For every task left `running` at startup:
/// 1. Append synthetic "interrupted by restart" tool results for any
///    unanswered `tool_call_id`s in the stored history (the protocol must
///    never carry an unanswered call).
/// 2. Try to park from the task's recorded slot (diff → commit "WIP" →
///    bundle → import as `refs/agent-wip/<id>`) and set `queued`; the claim
///    loop will resume it normally.
/// 3. If parking fails, mark the task `failed`.
///
/// Tasks already parked (`needs_input` / `review` / `queued`) are untouched.
/// Returns the number of tasks recovered (parked + failed).
pub async fn recover_interrupted(ctx: &DriverCtx) -> usize {
    let ids = ctx.store.interrupted_running_ids().unwrap_or_default();
    let mut done = 0;
    for id in ids {
        recover_one(ctx, id).await;
        done += 1;
    }
    done
}

async fn recover_one(ctx: &DriverCtx, task_id: i64) {
    // 1. Synthetic results for unanswered tool calls in stored history.
    let unanswered = unanswered_call_ids(ctx, task_id);
    for call_id in unanswered {
        let msg = crate::openrouter::Message::tool_result(
            call_id,
            "interrupted by restart: the previous process died before answering; re-check state with git_diff and continue.".to_string(),
        );
        if let Err(e) = ctx.store.append_message(task_id, &msg) {
            tracing::warn!("recovery append for task {task_id}: {e}");
        }
    }
    // 2. Park from the slot checkout (usually intact — the builder is a
    // separate container). Needs the recorded slot + branch + base.
    let task = match ctx.store.get_task(task_id).ok().flatten() {
        Some(t) => t,
        None => return,
    };
    let (Some(slot), branch) = (task.slot, task.branch.clone()) else {
        ctx.store
            .update_status(task_id, TaskStatus::Failed, Some("interrupted by restart (no slot to recover from)"), None)
            .ok();
        return;
    };
    if branch.is_empty() {
        ctx.store
            .update_status(task_id, TaskStatus::Failed, Some("interrupted by restart (no branch)"), None)
            .ok();
        return;
    }
    let pending = Pending::new(
        PENDING_ERROR,
        None,
        serde_json::json!({"message": "interrupted by restart; work re-parked"}),
    );
    // Best-effort WIP snapshot from the slot (same shape as the driver park,
    // minus stop-flag checks — no driver is running).
    let wip_sha: Option<String> = match ctx.runner.diff(slot).await {
        Ok(d) if d.diff.trim().is_empty() && d.stat.trim().is_empty() => None,
        Ok(_) => {
            let committed = ctx
                .runner
                .commit(slot, &format!("WIP task {task_id}"))
                .await
                .is_ok();
            if !committed {
                None
            } else {
                match ctx.runner.bundle(slot).await {
                    Ok(bundle) => {
                        let tmp = match tempfile::Builder::new()
                            .prefix("agentd-recover-")
                            .tempfile()
                        {
                            Ok(f) => f.into_temp_path(),
                            Err(_) => {
                                return fail_recovery(ctx, task_id, "interrupted by restart (bundle tempfile failed)");
                            }
                        };
                        if std::fs::write(&tmp, &bundle).is_err() {
                            return fail_recovery(ctx, task_id, "interrupted by restart (bundle write failed)");
                        }
                        match crate::git::import_wip_bundle(
                            &ctx.config.mirror_dir,
                            &tmp,
                            &branch,
                            task_id,
                        )
                        .await
                        {
                            Ok(sha) => Some(sha),
                            Err(_) => {
                                return fail_recovery(ctx, task_id, "interrupted by restart (WIP import failed)");
                            }
                        }
                    }
                    Err(_) => {
                        return fail_recovery(ctx, task_id, "interrupted by restart (bundle failed)");
                    }
                }
            }
        }
        Err(_) => {
            return fail_recovery(ctx, task_id, "interrupted by restart (diff failed)");
        }
    };
    match ctx
        .store
        .park_task(task_id, TaskStatus::Queued, &pending, wip_sha.as_deref())
    {
        Ok(_) => {
            ctx.emit(task_id, EVENT_STATUS,
                serde_json::json!({"message": "recovered after restart; re-queued"}),
            );
        }
        Err(e) => fail_recovery(ctx, task_id, &format!("interrupted by restart (park: {e})")),
    }
}

/// `tool_call_id`s in stored history with no later matching tool result.
fn unanswered_call_ids(ctx: &DriverCtx, task_id: i64) -> Vec<String> {
    let hist = ctx.store.load_messages(task_id).unwrap_or_default();
    let mut answered = std::collections::HashSet::new();
    for m in &hist {
        if m.role == "tool" {
            if let Some(id) = &m.tool_call_id {
                answered.insert(id.clone());
            }
        }
    }
    let mut out = Vec::new();
    for m in &hist {
        if m.role == "assistant" {
            if let Some(calls) = &m.tool_calls {
                for c in calls {
                    if !answered.contains(&c.id) && !out.contains(&c.id) {
                        out.push(c.id.clone());
                    }
                }
            }
        }
    }
    out
}

fn fail_recovery(ctx: &DriverCtx, task_id: i64, reason: &str) {
    ctx.store
        .update_status(task_id, TaskStatus::Failed, Some(reason), None)
        .ok();
    ctx.emit(task_id, EVENT_STATUS, serde_json::json!({"message": reason}));
}

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
        ctx.emit(task_id, EVENT_STATUS,
            serde_json::json!({"message": format!("claimed by worker {slot}"), "slot": slot}),
        );
        match drive_task(ctx.clone(), task_id, slot).await {
            TaskOutcome::Failed { reason } => {
                let _ = ctx.store.update_status(task_id, TaskStatus::Failed, Some(&reason), None);
            }
            TaskOutcome::Parked => {
                // Status + pending_json already stored atomically by
                // `Store::park_task`; the worker just returns to the claim
                // loop (the slot is free — `slot = NULL`).
            }
        }
    }
}

/// Stop a task: set the stop flag the driver checks (top of loop, per tool
/// call, after park import). The runner slot is cancelled ONLY while the
/// task's status is `running`, so a late Stop can never kill the next task to
/// use that slot. The driver parks as `needs_input(stopped)` with work kept.
pub async fn stop_task(ctx: &DriverCtx, task_id: i64) -> Result<bool, String> {
    let task = ctx
        .store
        .get_task(task_id)?
        .ok_or_else(|| "task not found".to_string())?;
    if task.status.is_terminal() {
        return Ok(false);
    }
    ctx.store.request_stop(task_id)?;
    ctx.emit(task_id, EVENT_STATUS,
        serde_json::json!({"message": "stop requested by operator"}),
    );
    // Kill the runner slot only while the task actually runs there.
    if task.status == TaskStatus::Running {
        if let Some(slot) = task.slot {
            match ctx.runner.cancel_slot(slot).await {
                Ok(killed) => return Ok(killed),
                Err(e) => {
                    tracing::warn!("runner cancel slot {slot}: {e}");
                    return Ok(false);
                }
            }
        }
    }
    Ok(true)
}

/// Approve a parked plan or a review, and discard from more states.
///
/// * `approve_task(task_id, "plan")` — only in `needs_input(plan)`: sets
///   `plan_approved=1` and posts "approved" via the tagged inbox path, so the
///   pending `propose_plan` call resumes with "Plan approved. Proceed.".
/// * `approve_task(task_id, "review")` — only in `review`: pushes
///   `refs/agent-wip/<id>` → `refs/heads/<branch>` (scoped token,
///   `http.extraHeader`), then status `succeeded`, `pushed=1`, `pushed_sha`,
///   `base_sha = pushed_sha`, pending cleared, local WIP ref deleted, status
///   event emitted. Calls the no-op `crate::hooks::after_push` (never fails
///   the approve). On push failure the task stays in `review` with the error.
/// * `discard_task` — works in any non-terminal state INCLUDING review;
///   deletes the local WIP ref. When the task was pushed before, the UI notes
///   the remote branch remains (the agent token cannot delete it).
pub async fn approve_task(
    ctx: &DriverCtx,
    task_id: i64,
    what: &str,
) -> Result<&'static str, String> {
    let task = ctx
        .store
        .get_task(task_id)?
        .ok_or_else(|| "task not found".to_string())?;
    match what {
        "plan" => {
            if task.status != TaskStatus::NeedsInput
                || task.pending.as_ref().map(|p| p.kind.as_str()) != Some(PENDING_PLAN)
            {
                return Err("no plan awaiting approval".to_string());
            }
            ctx.store.approve_plan(task_id)?;
            // Tagged inbox row → the resume formats the pending propose_plan
            // result as "Plan approved. Proceed." Races behave like other
            // messages (same inbox/requeue transaction).
            ctx.store
                .inbox_post_kind(task_id, "approved", INBOX_PLAN_APPROVE)?;
            ctx.emit(
                task_id,
                EVENT_STATUS,
                serde_json::json!({"message": "plan approved by operator"}),
            );
            Ok("plan approved")
        }
        "review" => {
            if !ctx.store.claim_approve(task_id)? {
                return Err("task is not awaiting review (or an approve is already in progress)".to_string());
            }
            let r = approve_review(ctx, &task).await;
            if let Err(e) = ctx.store.finish_approve(task_id) {
                tracing::error!("finish_approve {task_id}: {e}");
            }
            r
        }
        _ => Err(format!("unknown approve target {what:?}")),
    }
}

async fn approve_review(ctx: &DriverCtx, task: &crate::models::Task) -> Result<&'static str, String> {
    let task_id = task.id;
    if task.status != TaskStatus::Review {
        return Err("task is not awaiting review".to_string());
    }
    let wip_sha = task.wip_sha.clone().filter(|s| !s.is_empty());
    let branch = task.branch.clone();
    if branch.is_empty() {
        return Err("task has no branch".to_string());
    }
    // The WIP ref must still resolve (it was verified at park; re-check).
    match crate::git::wip_ref_sha(&ctx.config.mirror_dir, task_id).await {
        Ok(Some(found)) => {
            if let Some(expected) = &wip_sha {
                if &found != expected {
                    return Err(format!(
                        "WIP ref mismatch (expected {expected}, found {found})"
                    ));
                }
            }
        }
        Ok(None) => return Err("WIP ref missing in mirror; cannot push".to_string()),
        Err(e) => return Err(format!("WIP ref check: {e}")),
    }
    // Push WIP ref → branch via [`crate::git::push_ref`] (scoped token,
    // `http.extraHeader`).
    if let Err(e) = crate::git::push_ref(
        &ctx.config.mirror_dir,
        &ctx.config.repo_url,
        &format!("refs/agent-wip/{task_id}"),
        &format!("refs/heads/{branch}"),
        &ctx.config.git_push_token,
    )
    .await
    {
        ctx.emit(
            task_id,
            EVENT_STATUS,
            serde_json::json!({"message": format!("approve push failed: {e}")}),
        );
        return Err(format!("push failed (staying in review): {e}"));
    }
    // Resolve the pushed sha from the local WIP ref (what we pushed).
    let pushed_sha = crate::git::wip_ref_sha(&ctx.config.mirror_dir, task_id)
        .await
        .map_err(|e| format!("read pushed sha: {e}"))?
        .ok_or_else(|| "WIP ref vanished after push".to_string())?;
    ctx.store.set_pushed(task_id, &pushed_sha)?;
    let _ = ctx.store.set_finished_commit(
        task_id,
        &pushed_sha,
        true,
        task.summary.as_deref().unwrap_or(""),
    );
    ctx.store
        .update_status(task_id, TaskStatus::Succeeded, None, None)?;
    let _ = crate::git::delete_wip_ref(&ctx.config.mirror_dir, task_id).await;
    // Fresh task snapshot for the hook.
    if let Some(fresh) = ctx.store.get_task(task_id).ok().flatten() {
        // Must never fail the approve: ignore the result entirely.
        let _ = crate::hooks::after_push(ctx, &fresh).await;
    }
    ctx.emit(
        task_id,
        EVENT_STATUS,
        serde_json::json!({"message": format!("approved and pushed {branch}"), "sha": pushed_sha}),
    );
    Ok("pushed")
}

/// Discard a task from any non-terminal state (queued, running, needs_input,
/// review, succeeded-followup-able): terminal `discarded`, and delete the
/// local `refs/agent-wip/<id>`. After a push the remote branch remains — the
/// scoped token cannot delete it (the task page notes this when the task was
/// pushed; see the `pushed` flag on the row).
pub async fn discard_task(ctx: &DriverCtx, task_id: i64) -> Result<bool, String> {
    let task = ctx
        .store
        .get_task(task_id)?
        .ok_or_else(|| "task not found".to_string())?;
    if task.status.is_terminal() {
        return Ok(false);
    }
    if let Err(e) = crate::git::delete_wip_ref(&ctx.config.mirror_dir, task_id).await {
        tracing::warn!("discard delete WIP ref for task {task_id}: {e}");
    }
    ctx.store.update_status(
        task_id,
        TaskStatus::Discarded,
        Some("discarded by operator"),
        None,
    )?;
    ctx.emit(task_id, EVENT_STATUS,
        serde_json::json!({"message": "task discarded by operator"}),
    );
    Ok(true)
}
