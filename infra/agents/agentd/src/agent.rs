//! The OpenRouter agent loop: one task, one slot, until `finish`/budget.
//!
//! Flow per task:
//! 1. `fetch origin main` in the mirror; runner `prepare`s the slot branch.
//! 2. Fetch root + module `AGENTS.md` through the runner; build the system
//!    prompt; seed the conversation with it and the task text.
//! 3. Loop: POST OpenRouter chat-completions → dispatch tool calls to the
//!    runner (allowlist validation, truncation, release-preemption for
//!    `build`) → append results. Every turn is persisted as an event and
//!    broadcast to SSE subscribers.
//! 4. On `finish{summary, commit_message}`: validate (non-empty diff,
//!    commit-message format; violations come back as a tool result so the
//!    model can retry), runner `/commit`, download `/bundle`,
//!    `bundle verify`, import into the mirror, push upstream.
//! 5. Terminal statuses: `succeeded` / `failed(reason)` / `cancelled`.

use std::sync::Arc;

use tokio::sync::broadcast;

use crate::config::Config;
use crate::models::{
    branch_name, truncate_result, trim_placeholder, validate_commit_message, Event,
};
use crate::openrouter::{tool_defs, AssistantTurn, Message, OpenRouter, ToolCall};
use crate::prompt::system_prompt;
use crate::runner::Runner;
use crate::store::Store;
use crate::tools::{validate_build_command, validate_shell_command};

/// Outcome of running one task to a terminal state.
#[derive(Debug)]
pub enum TaskOutcome {
    Succeeded {
        commit_sha: String,
        pushed: bool,
        summary: String,
    },
    Failed {
        reason: String,
    },
    Cancelled,
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

fn is_cancelled(store: &Store, task_id: i64) -> bool {
    use crate::models::TaskStatus;
    store
        .get_task(task_id)
        .ok()
        .flatten()
        .map(|t| t.status == TaskStatus::Cancelled)
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
    ctx.emit(
        task_id,
        "status",
        serde_json::json!({"message": "a production release build is running; waiting for it to finish (up to 60 min)"}),
    );
    loop {
        if !release_active(&ctx.config) {
            return Ok(());
        }
        if is_cancelled(&ctx.store, task_id) {
            return Err("task cancelled".to_string());
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

/// Read `AGENTS.md` files for the prompt. Missing files → `None` (the prompt
/// renders "(unavailable)").
async fn fetch_rules(
    runner: &Runner,
    slot: u32,
    module_scope: &str,
) -> (Option<String>, Option<String>) {
    let root = runner
        .read(slot, "AGENTS.md", None, Some(400))
        .await
        .ok()
        .map(|r| r.content);
    // Scope forms: `games/logicgate` or `games:logicgate` → dir form.
    let dir = module_scope.replace(':', "/");
    let module_path = format!("{}/AGENTS.md", dir.trim_matches('/'));
    let module = if module_path == "AGENTS.md" {
        None
    } else {
        runner
            .read(slot, &module_path, None, Some(400))
            .await
            .ok()
            .map(|r| r.content)
    };
    (root, module)
}

/// Run one task to completion. `slot` is the scheduler worker index (also
/// the runner slot id).
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
    let model = task.model.clone();
    let module_scope = if task.module_scope.is_empty() {
        "notes".to_string()
    } else {
        task.module_scope.clone()
    };
    let budget_steps = task.budget_steps;
    let budget_usd = task.budget_usd;

    ctx.emit(
        task_id,
        "status",
        serde_json::json!({"message": format!("preparing slot {slot} on {branch}")}),
    );

    // 1. Mirror refresh + slot prepare.
    if let Err(e) = crate::git::fetch_main(&ctx.config.mirror_dir).await {
        return fail(&ctx, task_id, &format!("mirror fetch failed: {e}")).await;
    }
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

    // 2. Rules + system prompt.
    let (root_rules, module_rules) = fetch_rules(&ctx.runner, slot, &module_scope).await;
    let system = system_prompt(
        &module_scope,
        root_rules.as_deref(),
        module_rules.as_deref(),
    );
    let mut messages = vec![
        Message::system(system),
        Message::user(format!("Task: {}\n\n{}", task.title, task.prompt)),
    ];
    let tools = tool_defs();
    ctx.emit(
        task_id,
        "status",
        serde_json::json!({"message": "agent loop started", "model": model}),
    );

    // 3. Main loop.
    let mut text_only_streak = 0u32;
    let mut nudged = false;
    loop {
        if is_cancelled(&ctx.store, task_id) {
            ctx.emit(
                task_id,
                "status",
                serde_json::json!({"message": "task cancelled"}),
            );
            return TaskOutcome::Cancelled;
        }
        let cur = match ctx.store.get_task(task_id).ok().flatten() {
            Some(t) => t,
            None => {
                return TaskOutcome::Failed {
                    reason: "task row vanished".to_string(),
                }
            }
        };
        if cur.steps >= budget_steps {
            return fail(
                &ctx,
                task_id,
                &format!("step budget exhausted ({} steps)", cur.steps),
            )
            .await;
        }
        if cur.cost_usd >= budget_usd {
            return fail(
                &ctx,
                task_id,
                &format!("cost budget exhausted (${:.4})", cur.cost_usd),
            )
            .await;
        }
        trim_messages(&mut messages, ctx.config.context_chars());

        // Model turn.
        let turn: AssistantTurn = match ctx.openrouter.chat(&model, &messages, &tools).await {
            Ok(t) => t,
            Err(e) => return fail(&ctx, task_id, &format!("OpenRouter: {e}")).await,
        };
        let _ = ctx.store.add_usage(
            task_id,
            1,
            turn.usage.prompt_tokens,
            turn.usage.completion_tokens,
            turn.usage.cost_usd(),
        );
        ctx.emit(
            task_id,
            "assistant",
            serde_json::json!({
                "text": scrub_secrets(&turn.text),
                "prompt_tokens": turn.usage.prompt_tokens,
                "completion_tokens": turn.usage.completion_tokens,
                "cost_usd": turn.usage.cost_usd(),
            }),
        );
        messages.push(Message::assistant_calls(
            if turn.text.is_empty() {
                None
            } else {
                Some(turn.text.clone())
            },
            turn.tool_calls.clone(),
        ));

        if turn.tool_calls.is_empty() {
            text_only_streak += 1;
            if text_only_streak >= 2 {
                if !nudged {
                    nudged = true;
                    text_only_streak = 0;
                    let nudge = "You returned text without a tool call. Please continue with tool calls (or `finish` with a diff and commit message).";
                    messages.push(Message::user(nudge.to_string()));
                    ctx.emit(task_id, "status", serde_json::json!({"message": nudge}));
                    continue;
                }
                return fail(
                    &ctx,
                    task_id,
                    "model returned text with no tool call twice in a row",
                )
                .await;
            }
            messages.push(Message::user(
                "Noted. Please continue with tool calls toward the goal.".to_string(),
            ));
            continue;
        }
        text_only_streak = 0;

        // Dispatch tool calls sequentially, in model order.
        for call in &turn.tool_calls {
            if is_cancelled(&ctx.store, task_id) {
                ctx.emit(
                    task_id,
                    "status",
                    serde_json::json!({"message": "task cancelled"}),
                );
                return TaskOutcome::Cancelled;
            }
            match dispatch_tool(&ctx, task_id, slot, &module_scope, call).await {
                ToolEffect::Continue { result_for_model } => {
                    messages.push(Message::tool_result(call.id.clone(), result_for_model));
                }
                ToolEffect::FinishRequest {
                    summary,
                    commit_message,
                } => {
                    return finish_task(&ctx, task_id, slot, &branch, &summary, &commit_message)
                        .await;
                }
            }
        }
    }
}

async fn fail(ctx: &DriverCtx, task_id: i64, reason: &str) -> TaskOutcome {
    ctx.emit(
        task_id,
        "status",
        serde_json::json!({"message": reason}),
    );
    TaskOutcome::Failed {
        reason: reason.to_string(),
    }
}

/// Replace old tool results with placeholders once the conversation exceeds
/// `budget` chars (keeps system prompt + first user message + recent tail).
/// Public for unit tests.
pub fn trim_messages(messages: &mut [Message], budget: usize) {
    let total: usize = messages.iter().map(|m| m.chars()).sum();
    if total <= budget {
        return;
    }
    // Keep first two (system + task) and last four; trim tool results in the
    // middle window.
    if messages.len() <= 6 {
        return;
    }
    let n = messages.len();
    for m in messages.iter_mut().take(n.saturating_sub(4)).skip(2) {
        if m.role == "tool" {
            if let Some(c) = m.content.take() {
                m.content = Some(trim_placeholder("tool", c.len()));
            }
        }
    }
}

enum ToolEffect {
    Continue { result_for_model: String },
    FinishRequest { summary: String, commit_message: String },
}

async fn dispatch_tool(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    module_scope: &str,
    call: &ToolCall,
) -> ToolEffect {
    let args: serde_json::Value =
        serde_json::from_str(&call.function.arguments).unwrap_or(serde_json::Value::Null);
    ctx.emit(
        task_id,
        "tool_call",
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
                    Ok(r) => format!(
                        "{} ({} lines{})",
                        r.content,
                        r.total_lines,
                        if r.truncated { ", truncated" } else { "" }
                    ),
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
                Ok(r) => r.entries.join("\n"),
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
                Ok(r) => r.matches,
                Err(e) => format!("search failed: {e}"),
            }
        }
        "edit_file" => {
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
                    Ok(()) => "edit applied".to_string(),
                    Err(e) => format!("edit failed: {e}"),
                }
            }
        }
        "write_file" => {
            let path = arg_str(&args, "path").unwrap_or_default();
            if let Err(e) = check_scope(&path, module_scope) {
                format!("Scope error: {e}")
            } else {
                match ctx
                    .runner
                    .write(slot, &path, &arg_str(&args, "content").unwrap_or_default())
                    .await
                {
                    Ok(()) => "file written".to_string(),
                    Err(e) => format!("write failed: {e}"),
                }
            }
        }
        "git_diff" => match ctx.runner.diff(slot).await {
            Ok(d) => format!("{}\n{}", d.stat, d.diff),
            Err(e) => format!("diff failed: {e}"),
        },
        "run_shell" => {
            let command = arg_str(&args, "command").unwrap_or_default();
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
        "build" => {
            let command = arg_str(&args, "command").unwrap_or_default();
            let allowed = match validate_build_command(&command) {
                Err(msg) => return finish_dispatch(ctx, task_id, &call.function.name, &format!("Rejected: {msg}")),
                Ok(c) => c,
            };
            // Release preemption: while the signal file exists, wait (up to
            // 60 min). If it appeared mid-build, kill the runner build.
            if release_active(&ctx.config) {
                match wait_for_release_clear(ctx, task_id, &allowed).await {
                    Ok(()) => {}
                    Err(msg) => return finish_dispatch(ctx, task_id, &call.function.name, &msg),
                }
            }
            match ctx
                .runner
                .exec(slot, &allowed, proto::ExecKind::Build, 20 * 60)
                .await
            {
                Ok(r) => {
                    if r.killed && release_active(&ctx.config) {
                        let _ = ctx.runner.kill_build().await;
                        "build preempted by a production release; retry later".to_string()
                    } else {
                        exec_summary(&r)
                    }
                }
                Err(e) => format!("build failed: {e}"),
            }
        }
        "finish" => {
            let summary = arg_str(&args, "summary").unwrap_or_default();
            let commit_message = arg_str(&args, "commit_message").unwrap_or_default();
            // Validate first: violations return as a tool result so the
            // model can fix them and call `finish` again.
            if let Err(e) = validate_commit_message(&commit_message) {
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
            return ToolEffect::FinishRequest {
                summary,
                commit_message,
            };
        }
        other => format!(
            "Unknown tool {other:?}; available: read_file, list_dir, search, edit_file, write_file, git_diff, run_shell, build, finish."
        ),
    };
    finish_dispatch(ctx, task_id, &call.function.name, &raw)
}

/// Truncate + scrub a tool result, emit the `tool_result` event, and return
/// the text fed back to the model.
fn finish_dispatch(ctx: &DriverCtx, task_id: i64, name: &str, raw: &str) -> ToolEffect {
    let (text, truncated) = truncate_result(raw);
    let text = scrub_secrets(&text);
    ctx.emit(
        task_id,
        "tool_result",
        serde_json::json!({"name": name, "truncated": truncated, "result": text}),
    );
    ToolEffect::Continue {
        result_for_model: text,
    }
}

/// Confine a repo-relative path to the module scope (or the rules files).
fn check_scope(path: &str, module_scope: &str) -> Result<(), String> {
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

async fn finish_task(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    branch: &str,
    summary: &str,
    commit_message: &str,
) -> TaskOutcome {
    // Commit in the sandbox.
    let sha = match ctx.runner.commit(slot, commit_message).await {
        Ok(r) => r.sha,
        Err(e) => return fail(ctx, task_id, &format!("runner commit: {e}")).await,
    };
    ctx.emit(
        task_id,
        "status",
        serde_json::json!({"message": format!("committed {sha}"), "sha": sha}),
    );
    // Bundle → mirror → push.
    let bundle = match ctx.runner.bundle(slot).await {
        Ok(b) => b,
        Err(e) => return fail(ctx, task_id, &format!("bundle download: {e}")).await,
    };
    let tmp = match tempfile::Builder::new().prefix("agentd-bundle-").tempfile() {
        Ok(f) => f.into_temp_path(),
        Err(e) => return fail(ctx, task_id, &format!("temp bundle file: {e}")).await,
    };
    if let Err(e) = std::fs::write(&tmp, &bundle) {
        return fail(ctx, task_id, &format!("write bundle: {e}")).await;
    }
    if let Err(e) = crate::git::import_task_bundle(&ctx.config.mirror_dir, &tmp, branch).await {
        return fail(ctx, task_id, &format!("bundle import: {e}")).await;
    }
    match crate::git::push_branch(
        &ctx.config.mirror_dir,
        &ctx.config.repo_url,
        branch,
        &ctx.config.git_push_token,
    )
    .await
    {
        Ok(()) => {
            let _ = ctx.store.set_finished_commit(task_id, &sha, true, summary);
            ctx.emit(
                task_id,
                "status",
                serde_json::json!({"message": format!("pushed {branch}"), "sha": sha}),
            );
            TaskOutcome::Succeeded {
                commit_sha: sha,
                pushed: true,
                summary: summary.to_string(),
            }
        }
        Err(e) => {
            let _ = ctx.store.set_finished_commit(task_id, &sha, false, summary);
            fail(
                ctx,
                task_id,
                &format!("push {branch} failed: {e} (commit {sha} kept in mirror)"),
            )
            .await
        }
    }
}
