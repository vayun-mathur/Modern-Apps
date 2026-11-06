//! The OpenRouter agent loop: one task, one slot, until `finish`/park.
//!
//! Flow per task:
//! 1. Fresh tasks: `fetch origin main` in the mirror; runner `prepare`s the
//!    slot branch. Resumed tasks (queued with stored history): runner
//!    `prepare`s with a [`proto::ResumeSpec`] restoring the parked WIP ref.
//! 2. Fetch root + module `AGENTS.md` through the runner; build the system
//!    prompt; the conversation (user / assistant / tool messages) is persisted
//!    in the `messages` table as sent and reloaded on resume. The system
//!    message is rebuilt every turn and never stored.
//! 3. Loop: drain the inbox (steering becomes user messages), POST OpenRouter
//!    chat-completions → dispatch tool calls to the runner → append results.
//!    Every turn is persisted as an event and broadcast to SSE subscribers.
//! 4. On `finish{summary, commit_message}`: validate, runner `/commit`,
//!    download `/bundle`, `bundle verify`, import into the mirror, push
//!    upstream (the review gate is a later phase; finish still auto-pushes).
//! 5. Whenever the agent must wait (`ask_user`, text-only reply, budget,
//!    error, Stop), the driver parks: WIP work (if any) is committed, bundled
//!    and imported as `refs/agent-wip/<id>`, then one atomic
//!    [`Store::park_task`] sets the status and releases the slot.

use std::sync::Arc;

use tokio::sync::broadcast;

use crate::config::Config;
use crate::models::{
    branch_name, cap_final_diff, cap_plan_markdown, is_parking_tool, plan_gate_message,
    render_todos, truncate_result, trim_placeholder, validate_commit_message,
    validate_commit_message_for_issue, validate_todos,
    Event, Pending, TaskStatus, PENDING_BUDGET, PENDING_CHAT, PENDING_ERROR, PENDING_PLAN,
    PENDING_QUESTION, PENDING_REVIEW, PENDING_STOPPED, EVENT_ASSISTANT, EVENT_PLAN, EVENT_QUESTION,
    EVENT_REVIEW, EVENT_STATUS, EVENT_TODOS, EVENT_TOOL_CALL, EVENT_TOOL_RESULT, EVENT_USER_MESSAGE,
    INBOX_PLAN_APPROVE, INBOX_PLAN_CHANGES, INBOX_REVIEW_CHANGES,
};
use crate::openrouter::{tool_defs, AssistantTurn, Message, OpenRouter, ToolCall};
use crate::prompt::build_system_prompt;
use crate::rules::{ensure_rules_for_path, load_session_rules, sorted_loaded_rules};
use crate::runner::Runner;
use crate::store::Store;
use crate::tools::{shell_looks_mutating, validate_shell_command};

/// Outcome of one worker claim on a task. `Parked` means the driver stored a
/// `pending_json` resume point and released the slot; the scheduler just goes
/// back to the claim loop. (`finish` parks as `review`; Approve pushes via
/// HTTP, so no claim ever completes synchronously anymore.)
#[derive(Debug)]
pub enum TaskOutcome {
    Failed {
        reason: String,
    },
    Parked,
}

/// Shared runtime handles passed to every task driver.
#[derive(Clone)]
pub struct DriverCtx {
    pub config: Arc<Config>,
    pub store: Store,
    pub runner: Runner,
    pub openrouter: OpenRouter,
    /// Broadcast of `(task_id, event)` for SSE.
    pub events_tx: broadcast::Sender<(i64, Event)>,
}

impl DriverCtx {
    /// Persist an event and broadcast it (broadcast failure = no listeners).
    pub fn emit(&self, task_id: i64, kind: &str, body: serde_json::Value) -> Event {
        let ev = self
            .store
            .push_event(task_id, kind, &body)
            .unwrap_or(Event {
                id: -1,
                task_id,
                seq: -1,
                ts: crate::models::now_unix(),
                kind: kind.to_string(),
                body: body.clone(),
            });
        let _ = self.events_tx.send((task_id, ev.clone()));
        ev
    }
}

fn stop_requested(store: &Store, task_id: i64) -> bool {
    store
        .get_task(task_id)
        .ok()
        .flatten()
        .map(|t| t.stop_requested)
        .unwrap_or(true)
}

/// Scrub `Bearer <secret>` values before a string is stored in an event or
/// log. The push token never flows through the loop, but a model could
/// hallucinate something shaped like one; redact defensively.
pub fn scrub_secrets(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    loop {
        if let Some(i) = rest.to_lowercase().find("bearer ") {
            out.push_str(&rest[..i + 7]);
            out.push_str("[redacted]");
            let tail = &rest[i + 7..];
            let end = tail
                .find(|c: char| c.is_whitespace() || c == '"' || c == '\'')
                .unwrap_or(tail.len());
            rest = &tail[end..];
        } else {
            out.push_str(rest);
            break;
        }
    }
    out
}

/// Short (<=400 char) preview of tool arguments for the event log.
fn args_preview(args: &str) -> String {
    const MAX: usize = 400;
    if args.len() <= MAX {
        args.to_string()
    } else {
        format!("{}…[{} chars]", &args[..MAX], args.len())
    }
}

/// Look up a string argument.
fn arg_str(args: &serde_json::Value, key: &str) -> Option<String> {
    args.get(key)
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
}

/// Release-signal check: `true` while a production release build runs.
pub fn release_active(config: &Config) -> bool {
    config.release_signal.exists()
}

/// Wait out the release signal (polling), up to the configured limit.
/// Returns `Err(msg)` when the wait expires (message goes to the model).
pub async fn wait_for_release_clear(
    ctx: &DriverCtx,
    task_id: i64,
    _command: &str,
) -> Result<(), String> {
    if ctx.config.test_skip_release_wait {
        // Test seam: behave as if preemption fired immediately. Match the
        // production preemption message so tests assert one string.
        let _ = ctx.runner.kill_build().await;
        return Err("build preempted by a production release; retry later".to_string());
    }
    let limit = ctx.config.build_wait_limit();
    let poll = ctx.config.poll_interval();
    let start = std::time::Instant::now();
    ctx.emit(task_id, EVENT_STATUS,
        serde_json::json!({"message": "a production release build is running; waiting for it to finish (up to 60 min)"}),
    );
    loop {
        if !release_active(&ctx.config) {
            return Ok(());
        }
        if stop_requested(&ctx.store, task_id) {
            return Err("task stopped by operator".to_string());
        }
        if start.elapsed() >= limit {
            return Err(
                "production release still running after 60 min; aborting build (retry later)"
                    .to_string(),
            );
        }
        tokio::time::sleep(poll).await;
    }
}

/// Outcome of one validated build-kind exec (release-preemption included).
/// `Done` carries the raw runner response for the caller to parse; `Message`
/// is already model-ready text (preemption notice).
pub(crate) enum BuildOutcome {
    Done(proto::ExecResponse),
    Message(String),
}

/// Run a validated build command (`./gradlew ...` or `./check ...`) through
/// the runner's Build exec kind with the release-preemption handling:
/// wait while the release signal is present (up to 60 min), and surface a
/// retry-later message when a release kills the build. Shared by the legacy
/// `build{command}` arm and `structured::{build,run_tests,check}`.
pub(crate) async fn exec_build_command(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    allowed: &str,
) -> BuildOutcome {
    // Release preemption: while the signal file exists, wait (up to
    // 60 min). If it appeared mid-build, kill the runner build.
    if release_active(&ctx.config) {
        match wait_for_release_clear(ctx, task_id, allowed).await {
            Ok(()) => {}
            Err(msg) => return BuildOutcome::Message(msg),
        }
    }
    match ctx
        .runner
        .exec(slot, allowed, proto::ExecKind::Build, 20 * 60)
        .await
    {
        Ok(r) => {
            if r.killed && release_active(&ctx.config) {
                let _ = ctx.runner.kill_build().await;
                BuildOutcome::Message(
                    "build preempted by a production release; retry later".to_string(),
                )
            } else {
                BuildOutcome::Done(r)
            }
        }
        Err(e) => BuildOutcome::Message(format!("build failed: {e}")),
    }
}

/// Run one worker claim on a task: fresh or resumed. `slot` is the scheduler
/// worker index (also the runner slot id). Returns to the claim loop on park.
pub async fn drive_task(ctx: DriverCtx, task_id: i64, slot: u32) -> TaskOutcome {
    let task = match ctx.store.get_task(task_id).ok().flatten() {
        Some(t) => t,
        None => {
            return TaskOutcome::Failed {
                reason: "task row vanished".to_string(),
            }
        }
    };
    let branch = if task.branch.is_empty() {
        let b = branch_name(task.id, &task.title);
        let _ = ctx.store.set_branch(task.id, &b);
        b
    } else {
        task.branch.clone()
    };
    if task.kind == crate::models::TASK_KIND_TRIAGE {
        return crate::triage::drive_triage(&ctx, &task, slot, &branch).await;
    }
    let model = task.model.clone();
    let module_scope = if task.module_scope.is_empty() {
        "notes".to_string()
    } else {
        task.module_scope.clone()
    };

    ctx.emit(task_id, EVENT_STATUS,
        serde_json::json!({"message": format!("preparing slot {slot} on {branch}")}),
    );

    // Resumed claim? Stored history + a previous wip_sha/base_sha say so.
    let resumed = ctx.store.load_messages(task_id).map(|m| !m.is_empty());
    let resumed = resumed.unwrap_or(false);

    // 1. Mirror refresh + slot prepare (fresh or resume).
    if let Err(e) = crate::git::fetch_main(&ctx.config.mirror_dir).await {
        return fail(&ctx, task_id, &format!("mirror fetch failed: {e}")).await;
    }
    if resumed {
        if let Err(reason) = prepare_resumed(&ctx, &task, slot, &branch).await {
            return fail(&ctx, task_id, &reason).await;
        }
        // Budget parks grant headroom on resume: +20 steps, +$1.00.
        if task.pending.as_ref().map(|p| p.kind.as_str()) == Some(PENDING_BUDGET) {
            let _ = ctx.store.extend_budget(task_id);
            ctx.emit(task_id, EVENT_STATUS,
                serde_json::json!({"message": "budget extended (+20 steps, +$1.00)"}),
            );
        }
    } else {
        match crate::git::main_sha(&ctx.config.mirror_dir).await {
            Ok(sha) => {
                let _ = ctx.store.set_base_sha(task_id, &sha);
            }
            Err(e) => return fail(&ctx, task_id, &format!("mirror rev-parse: {e}")).await,
        }
        match ctx.runner.prepare(slot, &branch).await {
            Ok(r) => {
                let _ = ctx.store.set_base_sha(task_id, &r.base_sha);
            }
            Err(e) => return fail(&ctx, task_id, &format!("runner prepare: {e}")).await,
        }
    }

    // 2. Rules + system prompt (rebuilt every turn; never stored).
    // Session rules load once per claim into rules_json (root + module +
    // repo-map); per-path rules accumulate via ensure_rules_for_path during
    // dispatch. Plan + todos come from the task row (volatile tail).
    let (root_rules, module_rules) =
        load_session_rules(&ctx, task_id, slot, &module_scope).await;
    let system = build_system_prompt_for_task(&ctx, task_id, &module_scope, root_rules, module_rules);
    let mut messages = vec![Message::system(system)];
    // Stored history (seeded below for fresh tasks).
    if resumed {
        match ctx.store.load_messages(task_id) {
            Ok(hist) => messages.extend(hist),
            Err(e) => return fail(&ctx, task_id, &format!("load history: {e}")).await,
        }
        // Pending tool-call answer: ONE tool result from all inbox rows, then
        // any leftover rows as fresh user messages. Tagged rows (plan
        // approve / plan changes / review changes) format the answer; plain
        // rows pass through raw (ask_user answers, steering).
        if let Some(pending) = &task.pending {
            if let Some(call_id) = pending.tool_call_id.clone() {
                let drained = match ctx.store.drain_inbox_tagged(task_id) {
                    Ok(d) => d,
                    Err(e) => return fail(&ctx, task_id, &format!("drain inbox: {e}")).await,
                };
                let answer = format_pending_answer(pending, &drained);
                let tool_name = match pending.kind.as_str() {
                    PENDING_QUESTION => "ask_user",
                    PENDING_PLAN => "propose_plan",
                    PENDING_REVIEW => "finish",
                    _ => "ask_user",
                };
                append_persisted(&ctx, task_id, &mut messages, Message::tool_result(call_id.clone(), answer.clone()));
                ctx.emit(task_id, EVENT_TOOL_RESULT,
                    serde_json::json!({"name": tool_name, "truncated": false, "result": answer}),
                );
                // Rows beyond the pending answer (untagged leftovers can't
                // happen here — drain is total — but tagged approve rows
                // carry no extra user text, so nothing more to append).
            }
        }
    } else {
        append_persisted(
            &ctx,
            task_id,
            &mut messages,
            Message::user(format!("Task: {}\n\n{}", task.title, task.prompt)),
        );
    }
    let tools = tool_defs();
    ctx.emit(task_id, EVENT_STATUS,
        serde_json::json!({"message": "agent loop started", "model": model}),
    );

    // 3. Main loop.
    let mut openrouter_failures = 0u32;
    loop {
        if stop_requested(&ctx.store, task_id) {
            return park_after_stop(&ctx, task_id, slot, &branch, &mut messages).await;
        }
        let cur = match ctx.store.get_task(task_id).ok().flatten() {
            Some(t) => t,
            None => {
                return TaskOutcome::Failed {
                    reason: "task row vanished".to_string(),
                }
            }
        };
        if cur.steps >= cur.budget_steps || cur.cost_usd >= cur.budget_usd {
            return park_budget(&ctx, task_id, slot, &branch, &mut messages).await;
        }
        // Steering: drain inbox at the top of each iteration (after the prior
        // turn's tool results, which are already appended below).
        match ctx.store.drain_inbox(task_id) {
            Ok(rows) => {
                for text in rows {
                    ctx.emit(task_id, EVENT_USER_MESSAGE,
                        serde_json::json!({"text": text}),
                    );
                    append_persisted(&ctx, task_id, &mut messages, Message::user(text));
                }
            }
            Err(e) => return fail(&ctx, task_id, &format!("drain inbox: {e}")).await,
        }
        // Rebuild the system message from stored state every turn: todos,
        // plan, and per-path rules may have changed mid-claim (update_todos
        // dispatch, rules auto-load) without a re-park.
        refresh_system_message(&ctx, task_id, &module_scope, &mut messages);
        let send = trimmed_clone(&messages, ctx.config.context_chars());

        // Model turn (transient errors retry ×3 with backoff, then park).
        let turn: AssistantTurn = loop {
            match ctx.openrouter.chat(&model, &send, &tools).await {
                Ok(t) => {
                    openrouter_failures = 0;
                    break t;
                }
                Err(e) => {
                    openrouter_failures += 1;
                    if openrouter_failures > 3 {
                        return park_error(
                            &ctx,
                            task_id,
                            slot,
                            &branch,
                            &mut messages,
                            &format!("OpenRouter: {e}"),
                        )
                        .await;
                    }
                    let backoff = std::time::Duration::from_millis(500 * openrouter_failures as u64);
                    tokio::time::sleep(backoff).await;
                }
            }
        };
        let _ = ctx.store.add_usage(
            task_id,
            1,
            turn.usage.prompt_tokens,
            turn.usage.completion_tokens,
            turn.usage.cost_usd(),
        );
        ctx.emit(task_id, EVENT_ASSISTANT,
            serde_json::json!({
                "text": scrub_secrets(&turn.text),
                "prompt_tokens": turn.usage.prompt_tokens,
                "completion_tokens": turn.usage.completion_tokens,
                "cost_usd": turn.usage.cost_usd(),
            }),
        );
        append_persisted(
            &ctx,
            task_id,
            &mut messages,
            Message::assistant_calls(
                if turn.text.is_empty() {
                    None
                } else {
                    Some(turn.text.clone())
                },
                turn.tool_calls.clone(),
            ),
        );

        if turn.tool_calls.is_empty() {
            return park_chat(&ctx, task_id, slot, &branch, &mut messages, &turn.text).await;
        }

        // Sole-call enforcement for parking tools: a mixed turn gets a tool
        // error and NO park; every other call in the turn still gets its
        // result so the protocol stays valid.
        let parking_call: Option<ToolCall> = turn
            .tool_calls
            .iter()
            .find(|c| is_parking_tool(&c.function.name))
            .cloned();
        if let Some(park_call) = parking_call {
            if turn.tool_calls.len() != 1 {
                for call in &turn.tool_calls {
                    if call.id == park_call.id {
                        continue;
                    }
                    match dispatch_tool(&ctx, task_id, slot, &module_scope, call).await {
                        ToolEffect::Continue { result_for_model } => {
                            append_persisted(
                                &ctx,
                                task_id,
                                &mut messages,
                                Message::tool_result(call.id.clone(), result_for_model),
                            );
                        }
                        ToolEffect::ReviewRequest { .. } => {
                            // A parking tool alongside `finish`: protocol
                            // error; `finish` is NOT honored in a mixed turn.
                            append_persisted(
                                &ctx,
                                task_id,
                                &mut messages,
                                Message::tool_result(
                                    call.id.clone(),
                                    "finish rejected: it must be the only call in its turn; re-issue it alone.".to_string(),
                                ),
                            );
                        }
                    }
                }
                let msg = format!(
                    "{} must be the only call in its turn; re-issue it alone.",
                    park_call.function.name
                );
                ctx.emit(task_id, EVENT_TOOL_RESULT,
                    serde_json::json!({"name": park_call.function.name, "truncated": false, "result": msg}),
                );
                append_persisted(
                    &ctx,
                    task_id,
                    &mut messages,
                    Message::tool_result(park_call.id.clone(), msg),
                );
                continue;
            }
            // Valid sole parking-tool call → park.
            match park_call.function.name.as_str() {
                "ask_user" => {
                    return park_question(&ctx, task_id, slot, &branch, &mut messages, &park_call).await;
                }
                "propose_plan" => {
                    match park_plan(&ctx, task_id, slot, &branch, &mut messages, &park_call).await {
                        Some(outcome) => return outcome,
                        None => continue, // validation error appended; keep looping
                    }
                }
                // `finish` validates inside dispatch_tool (needs the runner
                // diff): fall out of the parking-call branch into the normal
                // dispatch loop below, which turns it into ReviewRequest →
                // park_review.
                "finish" => {}
                _ => {
                    let msg = format!("Unknown parking tool {:?}", park_call.function.name);
                    append_persisted(
                        &ctx,
                        task_id,
                        &mut messages,
                        Message::tool_result(park_call.id.clone(), msg.clone()),
                    );
                    ctx.emit(task_id, EVENT_TOOL_RESULT,
                        serde_json::json!({"name": park_call.function.name, "truncated": false, "result": msg}),
                    );
                    continue;
                }
            }
        }

        // Dispatch tool calls sequentially, in model order.
        for call in &turn.tool_calls {
            if stop_requested(&ctx.store, task_id) {
                return park_after_stop(&ctx, task_id, slot, &branch, &mut messages).await;
            }
            match dispatch_tool(&ctx, task_id, slot, &module_scope, call).await {
                ToolEffect::Continue { result_for_model } => {
                    append_persisted(
                        &ctx,
                        task_id,
                        &mut messages,
                        Message::tool_result(call.id.clone(), result_for_model),
                    );
                }
                ToolEffect::ReviewRequest {
                    summary,
                    commit_message,
                } => {
                    return park_review(&ctx, task_id, slot, &branch, call, &summary, &commit_message)
                        .await;
                }
            }
        }
    }
}

/// Build the system prompt for a task from stored state: base + root/module
/// rules + loaded rules sorted by path + plan + todos (stable → volatile).
/// Public so tests can assert prompt ordering.
pub fn build_system_prompt_for_task(
    ctx: &DriverCtx,
    task_id: i64,
    module_scope: &str,
    root_rules: Option<String>,
    module_rules: Option<String>,
) -> String {
    let task = ctx.store.get_task(task_id).ok().flatten();
    let rules = sorted_loaded_rules(task.as_ref().and_then(|t| t.rules_json.as_deref()));
    // Exclude the fixed root/module/repo-map entries from `extra` — they are
    // rendered by the base template already. (Anything else in rules_json,
    // i.e. per-directory AGENTS.md files, renders sorted by path.)
    let dir = module_scope.replace(':', "/");
    let dir = dir.trim_matches('/');
    let module_path = if dir.is_empty() {
        "AGENTS.md".to_string()
    } else {
        format!("{dir}/AGENTS.md")
    };
    let extra: Vec<(String, String)> = rules
        .into_iter()
        .filter(|(p, _)| p != "AGENTS.md" && *p != module_path && p != ".llms/repo-map.md")
        .collect();
    let plan = task.as_ref().and_then(|t| t.plan_md.clone()).unwrap_or_default();
    let todos = render_todos(task.as_ref().and_then(|t| t.todos_json.as_deref()));
    let (require_plan, plan_approved) = task
        .as_ref()
        .map(|t| (t.require_plan, t.plan_approved))
        .unwrap_or((false, false));
    build_system_prompt(
        module_scope,
        &extra,
        root_rules.as_deref(),
        module_rules.as_deref(),
        &plan,
        &todos,
        require_plan,
        plan_approved,
    )
}

/// Rebuild the system message in place after state changed mid-claim
/// (todos update, plan approval). Keeps history positions stable.
fn refresh_system_message(
    ctx: &DriverCtx,
    task_id: i64,
    module_scope: &str,
    messages: &mut [Message],
) {
    let task = match ctx.store.get_task(task_id).ok().flatten() {
        Some(t) => t,
        None => return,
    };
    // Re-derive root/module sections from stored rules_json (session load
    // persisted them); fall back to "(unavailable)" when absent.
    let map: std::collections::BTreeMap<String, Option<String>> = task
        .rules_json
        .as_deref()
        .map(serde_json::from_str)
        .transpose()
        .unwrap_or_default()
        .unwrap_or_default();
    let dir = module_scope.replace(':', "/");
    let dir = dir.trim_matches('/');
    let module_path = if dir.is_empty() {
        "AGENTS.md".to_string()
    } else {
        format!("{dir}/AGENTS.md")
    };
    let root = map.get("AGENTS.md").cloned().flatten();
    let module = map.get(&module_path).cloned().flatten();
    let system =
        build_system_prompt_for_task(ctx, task_id, module_scope, root, module);
    if let Some(first) = messages.first_mut() {
        if first.role == "system" {
            first.content = Some(system);
        }
    }
}

/// Append a message to the in-memory history AND persist it. Persistence
/// failure is fatal (fail loudly, never silently diverge).
pub(crate) fn append_persisted(ctx: &DriverCtx, task_id: i64, messages: &mut Vec<Message>, msg: Message) {
    if let Err(e) = ctx.store.append_message(task_id, &msg) {
        tracing::error!("persist message for task {task_id}: {e}");
    }
    messages.push(msg);
}

/// Format the single tool result that answers a pending parking-tool call
/// from the drained (tagged) inbox rows.
///
/// * plan approve (`kind == "plan_approve"`) → `"Plan approved. Proceed."`
/// * plan changes (`kind == "plan_changes"`) → `"User requested changes to
///   the plan: <text>"` (plan stays unapproved; the model revises and calls
///   `propose_plan` again)
/// * review changes (`kind == "review_changes"`, i.e. a normal message while
///   in `review`) → `"User reviewed the change and requested: <text>"`
/// * anything else → raw texts joined (ask_user answers, steering).
///
/// Public for unit tests.
pub fn format_pending_answer(
    pending: &Pending,
    rows: &[crate::store::InboxRow],
) -> String {
    let texts: Vec<&str> = rows.iter().map(|r| r.text.as_str()).collect();
    // Tagged plan/review rows take precedence over any co-drained plain text.
    if rows.iter().any(|r| r.kind == INBOX_PLAN_APPROVE) {
        return "Plan approved. Proceed.".to_string();
    }
    if let Some(row) = rows.iter().find(|r| r.kind == INBOX_PLAN_CHANGES) {
        return format!("User requested changes to the plan: {}", row.text);
    }
    if pending.kind == PENDING_REVIEW
        || rows.iter().any(|r| r.kind == INBOX_REVIEW_CHANGES)
    {
        let feedback = rows
            .iter()
            .find(|r| r.kind == INBOX_REVIEW_CHANGES)
            .map(|r| r.text.clone())
            .unwrap_or_else(|| texts.join("\n"));
        return format!("User reviewed the change and requested: {feedback}");
    }
    texts.join("\n")
}

async fn prepare_resumed(
    ctx: &DriverCtx,
    task: &crate::models::Task,
    slot: u32,
    branch: &str,
) -> Result<(), String> {
    let base_sha = task.base_sha.clone().unwrap_or_default();
    if base_sha.is_empty() {
        return Err("cannot resume: no base_sha recorded".to_string());
    }
    let (wip_ref, wip_sha) = match task.wip_sha.clone() {
        Some(sha) => {
            match crate::git::wip_ref_sha(&ctx.config.mirror_dir, task.id).await {
                Ok(Some(found)) if found == sha => {
                    (Some(crate::models::wip_ref(task.id)), Some(sha))
                }
                Ok(Some(found)) => {
                    return Err(format!(
                        "cannot resume: WIP ref mismatch (expected {sha}, found {found})"
                    ));
                }
                Ok(None) => {
                    return Err(format!(
                        "cannot resume: WIP ref {} missing in mirror",
                        crate::models::wip_ref(task.id)
                    ));
                }
                Err(e) => {
                    return Err(format!("cannot resume: WIP ref check: {e}"));
                }
            }
        }
        None => (None, None),
    };
    match ctx
        .runner
        .prepare_resume(
            slot,
            branch,
            proto::ResumeSpec {
                base_sha: base_sha.clone(),
                wip_ref,
                wip_sha,
            },
        )
        .await
    {
        Ok(r) => {
            let _ = ctx.store.set_base_sha(task.id, &r.base_sha);
            Ok(())
        }
        Err(e) => Err(format!("runner resume prepare: {e}")),
    }
}

pub(crate) async fn fail(ctx: &DriverCtx, task_id: i64, reason: &str) -> TaskOutcome {
    ctx.emit(task_id, EVENT_STATUS,
        serde_json::json!({"message": reason}),
    );
    TaskOutcome::Failed {
        reason: reason.to_string(),
    }
}

// ---- parking ----

/// Park the task and release the slot to the pool.
///
/// 1. If the checkout has changes: commit "WIP", download the bundle, import
///    it into the mirror as `refs/agent-wip/<id>` and record `wip_sha`.
///    Otherwise `wip_sha = NULL` (and any stale WIP ref is deleted).
/// 2. One atomic [`Store::park_task`]: set status / `pending_json` /
///    `slot = NULL`. When the inbox already holds a message, `park_task`
///    re-queues instead (lost-wakeup fix) — the worker loop will reclaim the
///    task on its next claim poll.
///
/// Emits the `question` event for question parks (so the UI can render the
/// form) plus a `status` event in all cases.
async fn park(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    branch: &str,
    status: TaskStatus,
    pending: Pending,
) -> TaskOutcome {
    // Snapshot the work BEFORE the atomic park: commit + bundle + import.
    let wip_sha: Option<String> = match ctx.runner.diff(slot).await {
        Ok(d) if d.diff.trim().is_empty() && d.stat.trim().is_empty() => {
            let _ = crate::git::delete_wip_ref(&ctx.config.mirror_dir, task_id).await;
            None
        }
        Ok(_) => {
            match ctx
                .runner
                .commit(slot, &format!("WIP task {task_id}"))
                .await
            {
                Ok(_) => {}
                Err(e) => {
                    // Best effort: unstaged/uncommitted work stays in the slot
                    // checkout; still park without a WIP ref.
                    tracing::warn!("park WIP commit for task {task_id}: {e}");
                    return park_without_wip(ctx, task_id, status, pending);
                }
            }
            // A Stop arriving during commit/bundle is honored after import.
            match ctx.runner.bundle(slot).await {
                Ok(bundle) => match write_temp_bundle(&bundle) {
                    Ok(tmp) => {
                        match crate::git::import_wip_bundle(
                            &ctx.config.mirror_dir,
                            &tmp,
                            branch,
                            task_id,
                        )
                        .await
                        {
                            Ok(sha) => Some(sha),
                            Err(e) => {
                                tracing::warn!("park WIP import for task {task_id}: {e}");
                                return park_without_wip(ctx, task_id, status, pending);
                            }
                        }
                    }
                    Err(e) => {
                        tracing::warn!("park temp bundle for task {task_id}: {e}");
                        return park_without_wip(ctx, task_id, status, pending);
                    }
                },
                Err(e) => {
                    tracing::warn!("park bundle download for task {task_id}: {e}");
                    return park_without_wip(ctx, task_id, status, pending);
                }
            }
        }
        Err(e) => {
            tracing::warn!("park diff for task {task_id}: {e}");
            return park_without_wip(ctx, task_id, status, pending);
        }
    };

    finish_park(ctx, task_id, status, pending, wip_sha.as_deref())
}

fn park_without_wip(
    ctx: &DriverCtx,
    task_id: i64,
    status: TaskStatus,
    pending: Pending,
) -> TaskOutcome {
    finish_park(ctx, task_id, status, pending, None)
}

fn finish_park(
    ctx: &DriverCtx,
    task_id: i64,
    status: TaskStatus,
    pending: Pending,
    wip_sha: Option<&str>,
) -> TaskOutcome {
    if pending.kind == PENDING_QUESTION {
        ctx.emit(task_id, EVENT_QUESTION, pending.payload.clone());
    }
    let effective = match ctx.store.park_task(task_id, status, &pending, wip_sha) {
        Ok(s) => s,
        Err(e) => {
            return TaskOutcome::Failed {
                reason: format!("park: {e}"),
            };
        }
    };
    ctx.emit(task_id, EVENT_STATUS,
        serde_json::json!({
            "message": format!("parked as {} (pending: {})", effective.as_str(), pending.kind),
            "status": effective.as_str(),
            "pending_kind": pending.kind,
        }),
    );
    TaskOutcome::Parked
}

fn write_temp_bundle(bundle: &[u8]) -> Result<tempfile::TempPath, String> {
    let tmp = tempfile::Builder::new()
        .prefix("agentd-wip-bundle-")
        .tempfile()
        .map_err(|e| format!("temp bundle file: {e}"))?;
    let path = tmp.into_temp_path();
    std::fs::write(&path, bundle).map_err(|e| format!("write bundle: {e}"))?;
    Ok(path)
}

/// `ask_user` (sole call in its turn) → park `needs_input(question)`.
async fn park_question(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    branch: &str,
    _messages: &mut Vec<Message>,
    call: &ToolCall,
) -> TaskOutcome {
    let args: serde_json::Value =
        serde_json::from_str(&call.function.arguments).unwrap_or(serde_json::Value::Null);
    let question = args
        .get("question")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let options = args.get("options").cloned().unwrap_or(serde_json::Value::Null);
    let multi_select = args
        .get("multi_select")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let pending = Pending::new(
        PENDING_QUESTION,
        Some(call.id.clone()),
        serde_json::json!({
            "question": question,
            "options": options,
            "multi_select": multi_select,
        }),
    );
    park(ctx, task_id, slot, branch, TaskStatus::NeedsInput, pending).await
}

/// `propose_plan` (sole call in its turn) → store the plan text, emit the
/// `plan` event, and park `needs_input(plan)` with pending
/// `{kind:"plan", tool_call_id, payload:{plan_markdown}}`.
///
/// Returns `None` when the plan is empty: the rejection is appended as the
/// tool result (history + event) and the caller continues the loop WITHOUT
/// parking.
async fn park_plan(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    branch: &str,
    messages: &mut Vec<Message>,
    call: &ToolCall,
) -> Option<TaskOutcome> {
    let args: serde_json::Value =
        serde_json::from_str(&call.function.arguments).unwrap_or(serde_json::Value::Null);
    let plan_raw = args
        .get("plan_markdown")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    if plan_raw.trim().is_empty() {
        let msg = "propose_plan rejected: plan_markdown is empty; call propose_plan again with the plan.".to_string();
        ctx.emit(task_id, EVENT_TOOL_RESULT,
            serde_json::json!({"name": "propose_plan", "truncated": false, "result": msg}),
        );
        append_persisted(
            ctx,
            task_id,
            messages,
            Message::tool_result(call.id.clone(), msg),
        );
        return None;
    }
    let (plan_md, truncated) = cap_plan_markdown(&plan_raw);
    let _ = ctx.store.set_plan_md(task_id, &plan_md);
    ctx.emit(task_id, EVENT_PLAN,
        serde_json::json!({"plan_markdown": plan_md, "truncated": truncated}),
    );
    let pending = Pending::new(
        PENDING_PLAN,
        Some(call.id.clone()),
        serde_json::json!({"plan_markdown": plan_md}),
    );
    Some(park(ctx, task_id, slot, branch, TaskStatus::NeedsInput, pending).await)
}

/// Text-only assistant turn → park `needs_input(chat)` (nudge-then-fail is
/// retired).
async fn park_chat(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    branch: &str,
    _messages: &mut Vec<Message>,
    text: &str,
) -> TaskOutcome {
    let pending = Pending::new(
        PENDING_CHAT,
        None,
        serde_json::json!({"text": text}),
    );
    park(ctx, task_id, slot, branch, TaskStatus::NeedsInput, pending).await
}

/// Budget exhausted → park `needs_input(budget)` (resume grants +20/+$1).
async fn park_budget(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    branch: &str,
    _messages: &mut Vec<Message>,
) -> TaskOutcome {
    let cur = ctx.store.get_task(task_id).ok().flatten();
    let (steps, budget_steps, cost, budget_usd) = cur
        .map(|t| (t.steps, t.budget_steps, t.cost_usd, t.budget_usd))
        .unwrap_or((0, 0, 0.0, 0.0));
    let pending = Pending::new(
        PENDING_BUDGET,
        None,
        serde_json::json!({
            "message": format!(
                "budget exhausted (steps {steps}/{budget_steps}, cost ${cost:.4}/${budget_usd:.2})"
            ),
        }),
    );
    park(ctx, task_id, slot, branch, TaskStatus::NeedsInput, pending).await
}

/// Transient errors, after retries → park `needs_input(error)` with the
/// message.
async fn park_error(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    branch: &str,
    _messages: &mut Vec<Message>,
    message: &str,
) -> TaskOutcome {
    let pending = Pending::new(
        PENDING_ERROR,
        None,
        serde_json::json!({"message": message}),
    );
    park(ctx, task_id, slot, branch, TaskStatus::NeedsInput, pending).await
}

/// Operator Stop observed → park `needs_input(stopped)` with work kept.
async fn park_after_stop(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    branch: &str,
    _messages: &mut Vec<Message>,
) -> TaskOutcome {
    let pending = Pending::new(
        PENDING_STOPPED,
        None,
        serde_json::json!({"message": "stopped by operator; work kept"}),
    );
    park(ctx, task_id, slot, branch, TaskStatus::NeedsInput, pending).await
}

/// Replace old tool results with placeholders once the conversation exceeds
/// `budget` chars (keeps system prompt + first user message + recent tail).
///
/// Takes a slice and returns an owned trimmed CLONE: the stored history is
/// never mutated. Pairing is structural — messages are never added, removed
/// or reordered, only tool-result *contents* in the middle window are
/// replaced with placeholders, so an assistant `tool_calls` message is never
/// separated from its tool results (ids stay aligned). Debug builds assert
/// the pairing after trimming. Public for unit tests.
pub fn trimmed_clone(messages: &[Message], budget: usize) -> Vec<Message> {
    let total: usize = messages.iter().map(|m| m.chars()).sum();
    if total <= budget || messages.len() <= 6 {
        return messages.to_vec();
    }
    let mut out = messages.to_vec();
    let n = out.len();
    for m in out.iter_mut().take(n.saturating_sub(4)).skip(2) {
        if m.role == "tool" {
            if let Some(c) = m.content.take() {
                m.content = Some(trim_placeholder("tool", c.len()));
            }
        }
    }
    debug_assert!(tool_pairs_intact(&out), "trim separated tool_calls from results");
    out
}

/// Every assistant `tool_calls` id has a matching `tool` result later in the
/// history (OpenRouter rejects unanswered calls).
fn tool_pairs_intact(messages: &[Message]) -> bool {
    let mut pending: std::collections::HashSet<&str> = std::collections::HashSet::new();
    for m in messages {
        if m.role == "assistant" {
            if let Some(calls) = &m.tool_calls {
                for c in calls {
                    pending.insert(c.id.as_str());
                }
            }
        } else if m.role == "tool" {
            if let Some(id) = &m.tool_call_id {
                pending.remove(id.as_str());
            }
        }
    }
    pending.is_empty()
}

/// Unknown-tool error (legacy list of the pre-structured tools plus the
/// structured layer).
fn unknown_tool(other: &str) -> String {
    format!(
        "Unknown tool {other:?}; available: read_file, list_dir, search, edit_file, write_file, update_todos, git_diff, run_shell, build, ask_user, propose_plan, finish, run_tests, check, git_log, git_show, git_blame, find_files, github_search_issues, github_read_issue."
    )
}

/// Legacy in-place trim kept for unit tests: same window rule, mutates.
/// (The driver uses [`trimmed_clone`]; this entry point exists for the
/// trimming unit test.)
#[allow(dead_code)]
pub fn trim_messages(messages: &mut [Message], budget: usize) {
    let trimmed = trimmed_clone(messages, budget);
    if trimmed.len() == messages.len() {
        messages.clone_from_slice(&trimmed);
    }
}

pub(crate) enum ToolEffect {
    Continue { result_for_model: String },
    ReviewRequest { summary: String, commit_message: String },
}

/// Is the plan gate currently closed for this task? (`require_plan` set and
/// the plan not yet approved.)
fn plan_gated(store: &Store, task_id: i64) -> bool {
    store
        .get_task(task_id)
        .ok()
        .flatten()
        .map(|t| t.require_plan && !t.plan_approved)
        .unwrap_or(false)
}

pub(crate) async fn dispatch_tool(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    module_scope: &str,
    call: &ToolCall,
) -> ToolEffect {
    let args: serde_json::Value =
        serde_json::from_str(&call.function.arguments).unwrap_or(serde_json::Value::Null);
    ctx.emit(task_id, EVENT_TOOL_CALL,
        serde_json::json!({"name": call.function.name, "args": args_preview(&call.function.arguments)}),
    );
    let raw: String = match call.function.name.as_str() {
        "read_file" => {
            let path = arg_str(&args, "path").unwrap_or_default();
            if let Err(e) = check_scope(&path, module_scope) {
                format!("Scope error: {e}")
            } else {
                match ctx
                    .runner
                    .read(
                        slot,
                        &path,
                        args.get("start_line").and_then(|v| v.as_u64()).map(|v| v as usize),
                        args.get("max_lines").and_then(|v| v.as_u64()).map(|v| v as usize),
                    )
                    .await
                {
                    Ok(r) => {
                        let body = format!(
                            "{} ({} lines{})",
                            r.content,
                            r.total_lines,
                            if r.truncated { ", truncated" } else { "" }
                        );
                        with_loaded_rules_note(ctx, task_id, slot, &path, body).await
                    }
                    Err(e) => format!("read failed: {e}"),
                }
            }
        }
        "list_dir" => {
            let path = arg_str(&args, "path").unwrap_or_default();
            if !path.is_empty() {
                if let Err(e) = check_scope(&path, module_scope) {
                    return finish_dispatch(ctx, task_id, &call.function.name, &format!("Scope error: {e}"));
                }
            }
            match ctx.runner.list(slot, &path).await {
                Ok(r) => {
                    let probe = if path.is_empty() {
                        module_scope.replace(':', "/")
                    } else {
                        path.clone()
                    };
                    with_loaded_rules_note(ctx, task_id, slot, &probe, r.entries.join("\n")).await
                }
                Err(e) => format!("list failed: {e}"),
            }
        }
        "search" => {
            let pattern = arg_str(&args, "pattern").unwrap_or_default();
            let sub = arg_str(&args, "path").filter(|s| !s.is_empty());
            if let Some(p) = &sub {
                if let Err(e) = check_scope(p, module_scope) {
                    return finish_dispatch(ctx, task_id, &call.function.name, &format!("Scope error: {e}"));
                }
            }
            // Default the search root to the module scope when the model
            // passes none, so rg never scans the whole repo.
            let root = sub.or_else(|| Some(module_scope.replace(':', "/")));
            match ctx
                .runner
                .search(
                    slot,
                    &pattern,
                    root.as_deref(),
                    arg_str(&args, "glob").as_deref(),
                    args.get("fixed_string").and_then(|v| v.as_bool()).unwrap_or(false),
                )
                .await
            {
                Ok(r) => {
                    let probe = root.unwrap_or_default();
                    with_loaded_rules_note(ctx, task_id, slot, &probe, r.matches).await
                }
                Err(e) => format!("search failed: {e}"),
            }
        }
        "edit_file" => {
            if plan_gated(&ctx.store, task_id) {
                plan_gate_message().to_string()
            } else {
                let path = arg_str(&args, "path").unwrap_or_default();
                if let Err(e) = check_scope(&path, module_scope) {
                    format!("Scope error: {e}")
                } else {
                    match ctx
                        .runner
                        .edit(
                            slot,
                            &path,
                            &arg_str(&args, "old").unwrap_or_default(),
                            &arg_str(&args, "new").unwrap_or_default(),
                        )
                        .await
                    {
                        Ok(()) => {
                            with_loaded_rules_note(ctx, task_id, slot, &path, "edit applied".to_string()).await
                        }
                        Err(e) => format!("edit failed: {e}"),
                    }
                }
            }
        }
        "write_file" => {
            if plan_gated(&ctx.store, task_id) {
                plan_gate_message().to_string()
            } else {
                let path = arg_str(&args, "path").unwrap_or_default();
                if let Err(e) = check_scope(&path, module_scope) {
                    format!("Scope error: {e}")
                } else {
                    match ctx
                        .runner
                        .write(slot, &path, &arg_str(&args, "content").unwrap_or_default())
                        .await
                    {
                        Ok(()) => {
                            with_loaded_rules_note(ctx, task_id, slot, &path, "file written".to_string()).await
                        }
                        Err(e) => format!("write failed: {e}"),
                    }
                }
            }
        }
        "update_todos" => {
            let items_raw = args.get("items").cloned().unwrap_or(serde_json::Value::Null);
            let items: Vec<crate::models::TodoItem> = match serde_json::from_value(items_raw) {
                Ok(v) => v,
                Err(e) => {
                    return finish_dispatch(
                        ctx,
                        task_id,
                        &call.function.name,
                        &format!("update_todos rejected: bad items ({e}); pass items:[{{text, status: pending|in_progress|done}}]"),
                    );
                }
            };
            match validate_todos(&items) {
                Err(e) => {
                    return finish_dispatch(
                        ctx,
                        task_id,
                        &call.function.name,
                        &format!("update_todos rejected: {e}"),
                    );
                }
                Ok(valid) => {
                    let todos_json = serde_json::to_string(&valid).unwrap_or_else(|_| "[]".to_string());
                    if let Err(e) = ctx.store.set_todos(task_id, &todos_json) {
                        return finish_dispatch(
                            ctx,
                            task_id,
                            &call.function.name,
                            &format!("update_todos failed: {e}"),
                        );
                    }
                    ctx.emit(task_id, EVENT_TODOS,
                        serde_json::json!({"items": valid}),
                    );
                    "ok".to_string()
                }
            }
        }
        "git_diff" => match ctx.runner.diff(slot).await {
            Ok(d) => format!("{}\n{}", d.stat, d.diff),
            Err(e) => format!("diff failed: {e}"),
        },
        "run_shell" => {
            let command = arg_str(&args, "command").unwrap_or_default();
            // Plan gate first (best-effort mutation detector), then the
            // existing build/device blocklist.
            if plan_gated(&ctx.store, task_id) && shell_looks_mutating(&command) {
                plan_gate_message().to_string()
            } else {
                match validate_shell_command(&command) {
                    Err(msg) => format!("Rejected: {msg}"),
                    Ok(()) => match ctx
                        .runner
                        .exec(slot, &command, proto::ExecKind::Shell, 60)
                        .await
                    {
                        Ok(r) => exec_summary(&r),
                        Err(e) => format!("exec failed: {e}"),
                    },
                }
            }
        }
        "build" => {
            if plan_gated(&ctx.store, task_id) {
                plan_gate_message().to_string()
            } else if args.get("module").and_then(|v| v.as_str()).is_some() {
                // Structured `{module, tasks}` form: validated, run, and parsed.
                let parsed = crate::structured::dispatch_structured_build(ctx, task_id, slot, &args).await;
                return finish_dispatch(ctx, task_id, &call.function.name, &parsed);
            } else {
                // Legacy `{command}` string keeps the raw exec summary.
                let allowed = match crate::structured::parse_build_args(&args) {
                    Err(msg) => return finish_dispatch(ctx, task_id, &call.function.name, &format!("Rejected: {msg}")),
                    Ok(c) => c,
                };
                match exec_build_command(ctx, task_id, slot, &allowed).await {
                    BuildOutcome::Done(r) => exec_summary(&r),
                    BuildOutcome::Message(m) => {
                        return finish_dispatch(ctx, task_id, &call.function.name, &m);
                    }
                }
            }
        }
        "finish" => {
            let summary = arg_str(&args, "summary").unwrap_or_default();
            let commit_message = arg_str(&args, "commit_message").unwrap_or_default();
            // Validate first: violations return as a tool result so the
            // model can fix them and call `finish` again. `finish` is a
            // parking tool, but validation happens here because a sole
            // `finish` flows through normal dispatch and only parks via
            // ReviewRequest below. Issue-linked tasks additionally require
            // the ` (#<n>)` subject suffix.
            let task_issue = ctx.store.get_task(task_id).ok().flatten().and_then(|t| t.issue_number);
            let validation = match task_issue {
                Some(n) => validate_commit_message_for_issue(&commit_message, Some(n)),
                None => validate_commit_message(&commit_message),
            };
            if let Err(e) = validation {
                let msg = format!(
                    "finish rejected: {e}. Fix the commit message and call `finish` again."
                );
                return finish_dispatch(ctx, task_id, &call.function.name, &msg);
            }
            match ctx.runner.diff(slot).await {
                Ok(d) if d.diff.trim().is_empty() => {
                    return finish_dispatch(
                        ctx,
                        task_id,
                        &call.function.name,
                        "finish rejected: the diff is empty — make code changes first, then call `finish` again.",
                    );
                }
                Err(e) => {
                    return finish_dispatch(
                        ctx,
                        task_id,
                        &call.function.name,
                        &format!("finish rejected: could not read diff ({e}); retry `finish`"),
                    );
                }
                _ => {}
            }
            return ToolEffect::ReviewRequest {
                summary,
                commit_message,
            };
        }
        other => {
            // Structured tools (run_tests, check, git_*, find_files, github_*)
            // live in `structured.rs`. Mutating ones respect the plan gate.
            if matches!(other, "run_tests" | "check") && plan_gated(&ctx.store, task_id) {
                plan_gate_message().to_string()
            } else {
                let task = ctx.store.get_task(task_id).ok().flatten();
                match task {
                    Some(t) => match crate::structured::dispatch(ctx, task_id, slot, &t, other, &args).await {
                        Some(result) => result,
                        None => unknown_tool(other),
                    },
                    None => unknown_tool(other),
                }
            }
        },
    };
    finish_dispatch(ctx, task_id, &call.function.name, &raw)
}

/// Append a `[loaded rules: a/AGENTS.md, ...]` note when per-path auto-load
/// picked up new rules files during this tool call.
async fn with_loaded_rules_note(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    path: &str,
    body: String,
) -> String {
    let newly = ensure_rules_for_path(ctx, task_id, slot, path).await;
    if newly.is_empty() {
        body
    } else {
        format!("{body}\n[loaded rules: {}]", newly.join(", "))
    }
}

/// Truncate + scrub a tool result, emit the `tool_result` event, and return
/// the text fed back to the model.
fn finish_dispatch(ctx: &DriverCtx, task_id: i64, name: &str, raw: &str) -> ToolEffect {
    let (text, truncated) = truncate_result(raw);
    let text = scrub_secrets(&text);
    ctx.emit(task_id, EVENT_TOOL_RESULT,
        serde_json::json!({"name": name, "truncated": truncated, "result": text}),
    );
    ToolEffect::Continue {
        result_for_model: text,
    }
}

/// Confine a repo-relative path to the module scope (or the rules files).
pub(crate) fn check_scope(path: &str, module_scope: &str) -> Result<(), String> {
    let dir = module_scope.replace(':', "/");
    let dir = dir.trim_matches('/');
    let p = path.trim_start_matches('/');
    if p == "AGENTS.md" || *p == format!("{dir}/AGENTS.md") {
        return Ok(());
    }
    if dir.is_empty() || p == dir || p.starts_with(&format!("{dir}/")) {
        Ok(())
    } else {
        Err(format!(
            "path {path:?} is outside your scope `{dir}/`; stay inside the scope"
        ))
    }
}

fn exec_summary(r: &proto::ExecResponse) -> String {
    let mut s = format!(
        "exit={} timed_out={} killed={} ({}ms)",
        r.exit_code
            .map(|c| c.to_string())
            .unwrap_or_else(|| "killed".to_string()),
        r.timed_out,
        r.killed,
        r.duration_ms,
    );
    if !r.stdout.is_empty() {
        s.push_str("\n--- stdout ---\n");
        s.push_str(&r.stdout);
    }
    if !r.stderr.is_empty() {
        s.push_str("\n--- stderr ---\n");
        s.push_str(&r.stderr);
    }
    if r.truncated {
        s.push_str("\n[output truncated by runner]");
    }
    s
}

/// `finish` (sole call in its turn, already validated) → submits for human
/// review WITHOUT pushing:
///
/// 1. Commit in the sandbox (single squashed commit with the agent's
///    `commit_message`).
/// 2. Capture the runner diff for `final_diff` (capped ~50 KB).
/// 3. Bundle + import into the mirror as `refs/agent-wip/<id>` (reuses the
///    park WIP machinery); record `wip_sha`.
/// 4. Store `final_diff`, `commit_message`, `summary`; emit the `review`
///    event `{summary, commit_message, diff, truncated}`.
/// 5. Park as `review` with pending `{kind:"review",
///    tool_call_id:<finish id>}`.
///
/// NO push happens here — Approve pushes. Request-changes arrives as the
/// pending `finish` call's tool result on resume; the resumed checkout
/// restores the squashed WIP as uncommitted changes vs `base_sha`, so the
/// agent keeps editing and calls `finish` again.
async fn park_review(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    branch: &str,
    call: &ToolCall,
    summary: &str,
    commit_message: &str,
) -> TaskOutcome {
    // Runner diff FIRST (pre-commit working tree vs base) for final_diff.
    let review_diff = match ctx.runner.diff(slot).await {
        Ok(d) => {
            let full = if d.diff.trim().is_empty() {
                d.stat.clone()
            } else {
                format!("{}\n{}", d.stat, d.diff)
            };
            full
        }
        Err(e) => {
            // Validation already saw a diff earlier this turn; a transient
            // read failure now fails loudly rather than submitting blind.
            return fail(ctx, task_id, &format!("review diff read: {e}")).await;
        }
    };
    // Single squashed commit with the agent's message.
    let _sha = match ctx.runner.commit(slot, commit_message).await {
        Ok(r) => r.sha,
        Err(e) => return fail(ctx, task_id, &format!("runner commit: {e}")).await,
    };
    ctx.emit(task_id, EVENT_STATUS,
        serde_json::json!({"message": format!("committed {_sha}"), "sha": _sha}),
    );
    // Bundle → mirror WIP import (same machinery as park).
    let bundle = match ctx.runner.bundle(slot).await {
        Ok(b) => b,
        Err(e) => return fail(ctx, task_id, &format!("bundle download: {e}")).await,
    };
    let tmp = match write_temp_bundle(&bundle) {
        Ok(t) => t,
        Err(e) => return fail(ctx, task_id, &format!("temp bundle file: {e}")).await,
    };
    let wip_sha = match crate::git::import_wip_bundle(
        &ctx.config.mirror_dir,
        &tmp,
        branch,
        task_id,
    )
    .await
    {
        Ok(sha) => sha,
        Err(e) => return fail(ctx, task_id, &format!("wip bundle import: {e}")).await,
    };
    // Store the review snapshot + emit the review event, then park.
    let (final_diff, truncated) = cap_final_diff(&review_diff);
    let _ = ctx.store.set_review(task_id, &wip_sha, &final_diff, commit_message, summary);
    // A Stop arriving during commit/bundle is honored after import.
    if stop_requested(&ctx.store, task_id) {
        let pending = Pending::new(
            PENDING_STOPPED,
            None,
            serde_json::json!({"message": "stopped by operator; work kept"}),
        );
        return finish_park(ctx, task_id, TaskStatus::NeedsInput, pending, Some(&wip_sha));
    }
    ctx.emit(task_id, EVENT_REVIEW,
        serde_json::json!({
            "summary": summary,
            "commit_message": commit_message,
            "diff": final_diff,
            "truncated": truncated,
        }),
    );
    let pending = Pending::new(
        PENDING_REVIEW,
        Some(call.id.clone()),
        serde_json::json!({
            "summary": summary,
            "commit_message": commit_message,
        }),
    );
    finish_park(ctx, task_id, TaskStatus::Review, pending, Some(&wip_sha))
}
