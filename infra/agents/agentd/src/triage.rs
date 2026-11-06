//! Automatic GitHub issue triage (backend half).
//!
//! A background poller ([`poll_once`], driven by [`spawn_poller`]) lists new
//! issues after a `triage_watermark` stored in the `kv` table (first start
//! stores "now" and triages nothing: no backfill) and queues one
//! `triage`-kind task per issue. [`drive_triage`] runs the
//! agent with read-only tools and ends with `finish_triage`; agentd then
//! applies the result via [`apply_triage`]: it fixes only the type label
//! (`bug` / `enhancement`) and the app label, and posts the analysis as a
//! marked comment ([`TRIAGE_MARKER`]).
//!
//! Without a GitHub token, [`apply_triage`] records nothing remotely
//! (`skipped_writes`); the result still lands in the `triage` table for the
//! UI.

use serde_json::Value;

use crate::github::{GitHub, ALL_APP_LABELS};

/// Hidden marker identifying agentd triage comments (idempotency).
pub const TRIAGE_MARKER: &str = "<!-- agentd-triage -->";
/// `kv` key for the poll watermark (RFC3339 UTC).
pub const WATERMARK_KEY: &str = "triage_watermark";
/// Issues fetched per poll.
pub const POLL_PAGE_SIZE: u32 = 50;
/// Max bytes of `analysis_markdown` accepted from `finish_triage`.
pub const ANALYSIS_CAP_BYTES: usize = 12 * 1024;

/// Outcome kind of a triage.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TriageKind {
    Bug,
    Feature,
}

/// Triage confidence.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TriageConfidence {
    High,
    Medium,
    Low,
}

/// Validated `finish_triage` arguments.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TriageResult {
    pub kind: TriageKind,
    pub app: String,
    pub confidence: TriageConfidence,
    pub analysis_markdown: String,
    pub duplicate_of: Option<u64>,
}

/// Validate `finish_triage{type, app, confidence, analysis_markdown,
/// duplicate_of?}` args. Enums are exact lowercase; `app` must be non-empty;
/// the analysis is capped at 12 KB.
pub fn parse_finish_triage(args: &Value) -> Result<TriageResult, String> {
    let get = |k: &str| {
        args.get(k)
            .and_then(|v| v.as_str())
            .ok_or_else(|| format!("finish_triage: missing or non-string {k:?}"))
    };
    let kind = match get("type")? {
        "bug" => TriageKind::Bug,
        "feature" => TriageKind::Feature,
        other => return Err(format!("finish_triage: bad type {other:?}: must be bug|feature")),
    };
    let app = get("app")?;
    if app.trim().is_empty() {
        return Err("finish_triage: app must be non-empty".to_string());
    }
    let confidence = match get("confidence")? {
        "high" => TriageConfidence::High,
        "medium" => TriageConfidence::Medium,
        "low" => TriageConfidence::Low,
        other => {
            return Err(format!(
                "finish_triage: bad confidence {other:?}: must be high|medium|low"
            ));
        }
    };
    let analysis = get("analysis_markdown")?;
    if analysis.len() > ANALYSIS_CAP_BYTES {
        return Err(format!(
            "finish_triage: analysis_markdown is {} bytes (limit {ANALYSIS_CAP_BYTES})",
            analysis.len()
        ));
    }
    let duplicate_of = match args.get("duplicate_of") {
        None | Some(Value::Null) => None,
        Some(Value::Number(n)) => match n.as_u64() {
            Some(v) if v > 0 => Some(v),
            _ => {
                return Err(
                    "finish_triage: duplicate_of must be a positive issue number".to_string(),
                );
            }
        },
        Some(other) => {
            return Err(format!(
                "finish_triage: duplicate_of must be an issue number, got {other}"
            ));
        }
    };
    Ok(TriageResult {
        kind,
        app: app.trim().to_string(),
        confidence,
        analysis_markdown: analysis.to_string(),
        duplicate_of,
    })
}

/// Plan label adds/removes for a triage result.
///
/// * Low confidence or an unknown `app` (not in `app_names`) → no changes.
/// * Type label (`bug` / `enhancement`): exactly the matching one.
/// * App label (any of `app_names`): exactly the result's app.
/// * Everything else (version labels, `solved for next release`, ...) is
///   never added or removed.
///
/// Matching is case-insensitive but emitted names are canonical: the `type`
/// label in lowercase, the app label exactly as listed in `app_names`.
pub fn plan_label_changes(
    current: &[String],
    result: &TriageResult,
    app_names: &[&str],
) -> (Vec<String>, Vec<String>) {
    if result.confidence == TriageConfidence::Low {
        return (Vec::new(), Vec::new());
    }
    let app_canonical = app_names
        .iter()
        .find(|a| a.eq_ignore_ascii_case(&result.app))
        .copied();
    let Some(app_canonical) = app_canonical else {
        return (Vec::new(), Vec::new());
    };
    let want_type = match result.kind {
        TriageKind::Bug => "bug",
        TriageKind::Feature => "enhancement",
    };

    let has = |name: &str| current.iter().any(|l| l.eq_ignore_ascii_case(name));
    let is_type = |l: &str| l.eq_ignore_ascii_case("bug") || l.eq_ignore_ascii_case("enhancement");
    let is_app = |l: &str| app_names.iter().any(|a| a.eq_ignore_ascii_case(l));

    let mut add = Vec::new();
    let mut remove = Vec::new();
    if !has(want_type) {
        add.push(want_type.to_string());
    }
    for l in current {
        if is_type(l) && !l.eq_ignore_ascii_case(want_type) {
            remove.push(l.clone());
        }
    }
    // Case-insensitive presence: GitHub labels are case-insensitive, so an
    // existing `Weather` already satisfies `weather` (no churn).
    if !has(app_canonical) {
        add.push(app_canonical.to_string());
    }
    for l in current {
        if is_app(l) && !l.eq_ignore_ascii_case(app_canonical) {
            remove.push(l.clone());
        }
    }
    (add, remove)
}

/// Render the triage comment: AI-generated header, type/app line, label
/// summary, analysis section (root-cause for bugs, design suggestions for
/// features), optional duplicate pointer, then [`TRIAGE_MARKER`].
pub fn render_comment(result: &TriageResult, add: &[String], remove: &[String]) -> String {
    let kind_s = match result.kind {
        TriageKind::Bug => "bug",
        TriageKind::Feature => "feature",
    };
    let conf_s = match result.confidence {
        TriageConfidence::High => "high",
        TriageConfidence::Medium => "medium",
        TriageConfidence::Low => "low",
    };
    let mut s = "🤖 **Automated triage** (AI-generated; may be wrong)\n\n".to_string();
    s.push_str(&format!(
        "Type: {kind_s} · App: {} · Confidence: {conf_s}\n\n",
        result.app
    ));
    if add.is_empty() && remove.is_empty() {
        if result.confidence == TriageConfidence::Low {
            s.push_str("Labels left unchanged (low confidence).\n\n");
        } else {
            s.push_str("Labels already correct.\n\n");
        }
    } else {
        let mut parts: Vec<String> = add.iter().map(|l| format!("+{l}")).collect();
        parts.extend(remove.iter().map(|l| format!("−{l}")));
        s.push_str(&format!("Labels updated: {}\n\n", parts.join(" ")));
    }
    match result.kind {
        TriageKind::Bug => s.push_str("### Root cause analysis\n\n"),
        TriageKind::Feature => s.push_str("### Design suggestions\n\n"),
    }
    s.push_str(&result.analysis_markdown);
    if !result.analysis_markdown.ends_with('\n') {
        s.push('\n');
    }
    if let Some(n) = result.duplicate_of {
        s.push_str(&format!("\nPossible duplicate of #{n}.\n"));
    }
    s.push_str(&format!("\n{TRIAGE_MARKER}\n"));
    s
}

/// Outcome of [`apply_triage`].
#[derive(Debug, Clone)]
pub struct ApplyOutcome {
    pub labels_added: Vec<String>,
    pub labels_removed: Vec<String>,
    pub comment_url: Option<String>,
    pub skipped_writes: bool,
    pub error: Option<String>,
}

/// Apply a triage result to an issue: re-fetch labels, plan changes, remove
/// wrong labels one by one, add correct ones in one call, then post the
/// marked comment. Without a token nothing is sent (`skipped_writes`).
/// Failures are captured in `error`, never raised.
pub async fn apply_triage(gh: &GitHub, issue: u64, result: &TriageResult) -> ApplyOutcome {
    let mut out = ApplyOutcome {
        labels_added: Vec::new(),
        labels_removed: Vec::new(),
        comment_url: None,
        skipped_writes: false,
        error: None,
    };
    if !gh.has_token() {
        out.skipped_writes = true;
        return out;
    }
    let current = match gh.read_issue(issue).await {
        Ok(full) => full.labels,
        Err(e) => {
            out.error = Some(format!("triage: re-reading issue #{issue} failed: {e}"));
            return out;
        }
    };
    let (add, remove) = plan_label_changes(&current, result, ALL_APP_LABELS);
    for name in &remove {
        if let Err(e) = gh.remove_label(issue, name).await {
            out.error = Some(format!("triage: removing label {name:?} on #{issue} failed: {e}"));
            return out;
        }
        out.labels_removed.push(name.clone());
    }
    if !add.is_empty() {
        if let Err(e) = gh.add_labels(issue, &add).await {
            out.error = Some(format!("triage: adding labels on #{issue} failed: {e}"));
            return out;
        }
        out.labels_added = add.clone();
    }
    let body = render_comment(result, &add, &remove);
    match gh.post_comment(issue, &body).await {
        Ok(url) => out.comment_url = Some(url),
        Err(e) => out.error = Some(format!("triage: commenting on #{issue} failed: {e}")),
    }
    out
}

/// Current UTC time as RFC3339 `Z` (seconds precision).
fn now_rfc3339_utc() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    // Days → civil date (Howard Hinnant's algorithm), then HMS.
    let days = (secs / 86_400) as i64;
    let secs_of_day = (secs % 86_400) as i64;
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as i64;
    let m = if mp < 10 { mp as i64 + 3 } else { mp as i64 - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!(
        "{y:04}-{:02}-{:02}T{:02}:{:02}:{:02}Z",
        m,
        d,
        secs_of_day / 3_600,
        (secs_of_day % 3_600) / 60,
        secs_of_day % 60
    )
}

/// One poll: list issues newer than the watermark and queue a triage task per
/// untriaged one. First start stores "now" and queues nothing (no backfill).
/// Returns the number of tasks queued.
pub async fn poll_once(ctx: &crate::agent::DriverCtx) -> Result<usize, String> {
    let gh = crate::issues::github_client(&ctx.config).map_err(|e| e.to_string())?;
    let watermark = ctx.store.kv_get(WATERMARK_KEY)?;
    let Some(watermark) = watermark else {
        ctx.store.kv_set(WATERMARK_KEY, &now_rfc3339_utc())?;
        return Ok(0);
    };
    let issues = gh
        .list_issues_since(&watermark, POLL_PAGE_SIZE)
        .await
        .map_err(|e| e.to_string())?;
    let mut queued = 0;
    let mut max_created = watermark.clone();
    for summary in &issues {
        if crate::github::normalize_rfc3339(&summary.created_at) > max_created {
            max_created = summary.created_at.clone();
        }
        if ctx.store.triage_get(summary.number)?.is_some() {
            continue;
        }
        if gh
            .issue_has_marker(summary.number, TRIAGE_MARKER)
            .await
            .map_err(|e| e.to_string())?
        {
            continue;
        }
        // Prompt content is informational; [`drive_triage`]
        // re-reads the issue and runs `finish_triage`. The issue number is
        // stored on the row so the worker can find it.
        let title = format!("#{} {}", summary.number, summary.title);
        let title: String = if title.chars().count() > 80 {
            title.chars().take(80).collect()
        } else {
            title
        };
        let module = crate::github::app_module_for_labels(&summary.labels).unwrap_or_default();
        let prompt = format!(
            "Triage GitHub issue #{}: {}\nLabels: {}\n\nRead the issue via github_read_issue, investigate the repo read-only, then end with finish_triage.",
            summary.number,
            summary.title,
            if summary.labels.is_empty() {
                "(none)".to_string()
            } else {
                summary.labels.join(", ")
            },
        );
        let model = ctx.config.default_model.clone();
        let task_id = ctx.store.create_triage_task(
            summary.number,
            &title,
            &prompt,
            &module,
            &model,
        )?;
        if ctx.store.triage_insert(summary.number, task_id)? {
            queued += 1;
        }
    }
    if max_created > watermark {
        ctx.store.kv_set(WATERMARK_KEY, &max_created)?;
    }
    Ok(queued)
}

/// Tools a triage task may call: read-only repo + history + issues, plus the
/// terminal `finish_triage`. Everything else is refused.
pub const TRIAGE_TOOLS: &[&str] = &[
    "read_file",
    "list_dir",
    "search",
    "find_files",
    "git_log",
    "git_show",
    "git_blame",
    "github_read_issue",
    "github_search_issues",
    "finish_triage",
];

fn finish_triage_def() -> crate::openrouter::ToolDef {
    crate::openrouter::ToolDef {
        kind: "function",
        function: crate::openrouter::FunctionDef {
            name: "finish_triage",
            description: "Submit the triage result. Must be the ONLY tool call in its turn. agentd fixes the issue's type and app labels (unless confidence is low) and posts analysis_markdown as a comment.",
            parameters: serde_json::json!({
                "type": "object",
                "properties": {
                    "type": {"type": "string", "enum": ["bug", "feature"]},
                    "app": {"type": "string", "description": "App label, e.g. weather, logicgate"},
                    "confidence": {"type": "string", "enum": ["high", "medium", "low"]},
                    "analysis_markdown": {"type": "string", "description": "Root cause analysis (bug) or design suggestions (feature), markdown, <= ~500 words"},
                    "duplicate_of": {"type": "integer", "description": "Issue number this duplicates (optional)"}
                },
                "required": ["type", "app", "confidence", "analysis_markdown"],
                "additionalProperties": false,
            }),
        },
    }
}

fn triage_tool_defs() -> Vec<crate::openrouter::ToolDef> {
    let mut defs: Vec<_> = crate::openrouter::tool_defs()
        .into_iter()
        .filter(|d| TRIAGE_TOOLS.contains(&d.function.name))
        .collect();
    defs.push(finish_triage_def());
    defs
}

fn triage_system_prompt(issue_text: &str, root_rules: Option<&str>, module_rules: Option<&str>) -> String {
    format!(
        r#"You triage GitHub issues for the Modern-Apps monorepo (Android apps in Kotlin plus Rust). You have READ-ONLY tools over a checkout of `main`: read_file, list_dir, search, find_files, git_log, git_show, git_blame, github_read_issue, github_search_issues. You cannot edit, build or run anything.

Your job for the issue below:
1. Decide the type: `bug` (something broken) or `feature` (a request for new or changed behaviour). The issue title usually starts with `[bug]:` or `[feat]:`, but judge from the body; reporters pick the wrong one sometimes.
2. Decide the app. The issue form's `### App Name` section is a hint, not proof. Valid app labels: {apps}.
3. Investigate the relevant code (start at the app's module dir; read its AGENTS.md if useful; keep it focused).
4. Write a concise analysis in markdown (<= ~500 words):
   - bug: a root cause analysis — the most likely cause with `path/File.kt:line` references, why it produces the reported behaviour, and how to verify and fix it.
   - feature: design suggestions — affected modules/files, a concrete approach, UX and edge cases, risks.
   Be honest: if you could not pin it down, say what you checked and what is uncertain, and use confidence `low`.
5. Optionally use github_search_issues to spot an obvious duplicate.
6. End by calling `finish_triage` ALONE in its turn.

Repo rules (root AGENTS.md, for context):
```
{root}
```

App module rules:
```
{module}
```

The issue:
{issue}
"#,
        apps = ALL_APP_LABELS.join(", "),
        root = root_rules.unwrap_or("(unavailable)"),
        module = module_rules.unwrap_or("(none)"),
        issue = issue_text,
    )
}

fn summarize(result: &TriageResult, out: &ApplyOutcome) -> String {
    let kind = match result.kind {
        TriageKind::Bug => "bug",
        TriageKind::Feature => "feature",
    };
    let mut labels: Vec<String> = out.labels_added.iter().map(|l| format!("+{l}")).collect();
    labels.extend(out.labels_removed.iter().map(|l| format!("−{l}")));
    let labels = if labels.is_empty() { "labels unchanged".to_string() } else { format!("labels {}", labels.join(" ")) };
    let comment = if out.comment_url.is_some() {
        "commented"
    } else if out.skipped_writes {
        "no token: GitHub not updated"
    } else {
        "comment failed"
    };
    format!("{kind} · {} · {labels} · {comment}", result.app)
}

/// Drive one triage task: prepare a read-only checkout of `main`, run the
/// model with [`TRIAGE_TOOLS`] until it calls `finish_triage`, then apply the
/// result to GitHub. Unattended: text-only turns get one nudge, then fail;
/// budget exhaustion fails. Never parks, never edits, never pushes.
pub async fn drive_triage(
    ctx: &crate::agent::DriverCtx,
    task: &crate::models::Task,
    slot: u32,
    branch: &str,
) -> crate::agent::TaskOutcome {
    use crate::agent::{append_persisted, dispatch_tool, fail, trimmed_clone, ToolEffect};
    use crate::models::{EVENT_ASSISTANT, EVENT_STATUS, EVENT_TOOL_RESULT, TaskStatus};
    use crate::openrouter::Message;

    let task_id = task.id;
    let Some(issue_number) = task.issue_number else {
        return fail(ctx, task_id, "triage task has no issue number").await;
    };
    let issue_number = issue_number as u64;
    if let Err(e) = crate::git::fetch_main(&ctx.config.mirror_dir).await {
        return fail(ctx, task_id, &format!("mirror fetch failed: {e}")).await;
    }
    if let Err(e) = ctx.runner.prepare(slot, branch).await {
        return fail(ctx, task_id, &format!("runner prepare: {e}")).await;
    }
    let gh = match crate::issues::github_client(&ctx.config) {
        Ok(g) => g,
        Err(e) => return fail(ctx, task_id, &format!("github client: {e}")).await,
    };
    let issue_text = match gh.read_issue(issue_number).await {
        Ok(issue) => crate::github::render_issue_for_model(&issue),
        Err(e) => return fail(ctx, task_id, &format!("read issue #{issue_number}: {e}")).await,
    };
    let (root, module) =
        crate::rules::load_session_rules(ctx, task_id, slot, &task.module_scope).await;
    let system = triage_system_prompt(&issue_text, root.as_deref(), module.as_deref());
    let mut messages = vec![Message::system(system)];
    append_persisted(ctx, task_id, &mut messages, Message::user(format!("Triage issue #{issue_number}.")));
    let tools = triage_tool_defs();
    // Read tools see the whole repo: "" is the repo-wide scope.
    let scope = "";
    let mut nudged = false;

    loop {
        let cur = match ctx.store.get_task(task_id).ok().flatten() {
            Some(t) => t,
            None => return fail(ctx, task_id, "task row vanished").await,
        };
        if cur.stop_requested || cur.status == TaskStatus::Discarded {
            return fail(ctx, task_id, "triage stopped by operator").await;
        }
        if cur.steps >= cur.budget_steps || cur.cost_usd >= cur.budget_usd {
            let _ = ctx.store.triage_set_result(issue_number, "failed", None);
            return fail(ctx, task_id, "triage budget exhausted").await;
        }
        let send = trimmed_clone(&messages, ctx.config.context_chars());
        let turn = match ctx.openrouter.chat(&task.model, &send, &tools).await {
            Ok(t) => t,
            Err(e) => {
                let _ = ctx.store.triage_set_result(issue_number, "failed", None);
                return fail(ctx, task_id, &format!("OpenRouter: {e}")).await;
            }
        };
        let _ = ctx.store.add_usage(task_id, 1, turn.usage.prompt_tokens, turn.usage.completion_tokens, turn.usage.cost_usd());
        ctx.emit(task_id, EVENT_ASSISTANT, serde_json::json!({
            "text": crate::agent::scrub_secrets(&turn.text),
            "prompt_tokens": turn.usage.prompt_tokens,
            "completion_tokens": turn.usage.completion_tokens,
            "cost_usd": turn.usage.cost_usd(),
        }));
        append_persisted(ctx, task_id, &mut messages, Message::assistant_calls(
            if turn.text.is_empty() { None } else { Some(turn.text.clone()) },
            turn.tool_calls.clone(),
        ));
        if turn.tool_calls.is_empty() {
            if nudged {
                let _ = ctx.store.triage_set_result(issue_number, "failed", None);
                return fail(ctx, task_id, "triage ended without finish_triage").await;
            }
            nudged = true;
            append_persisted(ctx, task_id, &mut messages, Message::user(
                "Keep going with the read-only tools, then call finish_triage alone.".to_string(),
            ));
            continue;
        }
        let sole = turn.tool_calls.len() == 1;
        for call in &turn.tool_calls {
            let name = call.function.name.as_str();
            let result = if name == "finish_triage" {
                if !sole {
                    "finish_triage must be the only call in its turn; re-issue it alone.".to_string()
                } else {
                    let args: Value = serde_json::from_str(&call.function.arguments).unwrap_or(Value::Null);
                    match parse_finish_triage(&args) {
                        Err(e) => format!("finish_triage rejected: {e}"),
                        Ok(result) => {
                            let out = apply_triage(&gh, issue_number, &result).await;
                            let summary = summarize(&result, &out);
                            if let Some(err) = &out.error {
                                ctx.emit(task_id, EVENT_STATUS, serde_json::json!({"message": format!("triage GitHub update error: {err}")}));
                            }
                            let record = serde_json::json!({
                                "type": match result.kind { TriageKind::Bug => "bug", TriageKind::Feature => "feature" },
                                "app": result.app,
                                "confidence": match result.confidence { TriageConfidence::High => "high", TriageConfidence::Medium => "medium", TriageConfidence::Low => "low" },
                                "labels_added": out.labels_added,
                                "labels_removed": out.labels_removed,
                                "comment_url": out.comment_url,
                                "skipped_writes": out.skipped_writes,
                                "error": out.error,
                                "duplicate_of": result.duplicate_of,
                            });
                            let status = if out.error.is_some() { "failed" } else { "done" };
                            let _ = ctx.store.triage_set_result(issue_number, status, Some(&record.to_string()));
                            append_persisted(ctx, task_id, &mut messages, Message::tool_result(call.id.clone(), "submitted".to_string()));
                            ctx.emit(task_id, EVENT_STATUS, serde_json::json!({"message": format!("triage: {summary}")}));
                            let _ = ctx.store.update_status(task_id, TaskStatus::Succeeded, None, Some(&summary));
                            return crate::agent::TaskOutcome::Parked;
                        }
                    }
                }
            } else if TRIAGE_TOOLS.contains(&name) {
                match dispatch_tool(ctx, task_id, slot, scope, call).await {
                    ToolEffect::Continue { result_for_model } => {
                        append_persisted(ctx, task_id, &mut messages, Message::tool_result(call.id.clone(), result_for_model));
                        continue;
                    }
                    ToolEffect::ReviewRequest { .. } => "not available during triage".to_string(),
                }
            } else {
                format!("{name} is not available during triage (read-only tools and finish_triage only)")
            };
            ctx.emit(task_id, EVENT_TOOL_RESULT, serde_json::json!({"name": name, "truncated": false, "result": result}));
            append_persisted(ctx, task_id, &mut messages, Message::tool_result(call.id.clone(), result));
        }
    }
}

/// Spawn the background triage poller. `AGENTD_TRIAGE=0` disables it;
/// `AGENTD_TRIAGE_INTERVAL_SECS` sets the period (default 120s). Errors are
/// logged and retried on the next tick.
pub fn spawn_poller(ctx: crate::agent::DriverCtx) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        if !ctx.config.triage_enabled {
            tracing::info!("triage poller disabled (AGENTD_TRIAGE=0)");
            return;
        }
        let interval = ctx.config.triage_interval_secs;
        tracing::info!("triage poller starting (every {interval}s)");
        loop {
            match poll_once(&ctx).await {
                Ok(0) => {}
                Ok(n) => tracing::info!("triage poller queued {n} task(s)"),
                Err(e) => tracing::warn!("triage poll: {e} (retrying next tick)"),
            }
            tokio::time::sleep(std::time::Duration::from_secs(interval)).await;
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bug_weather_high() -> TriageResult {
        TriageResult {
            kind: TriageKind::Bug,
            app: "weather".to_string(),
            confidence: TriageConfidence::High,
            analysis_markdown: "The widget reads the wrong field.".to_string(),
            duplicate_of: None,
        }
    }

    fn result(kind: TriageKind, app: &str, conf: TriageConfidence) -> TriageResult {
        TriageResult {
            kind,
            app: app.to_string(),
            confidence: conf,
            analysis_markdown: "analysis".to_string(),
            duplicate_of: None,
        }
    }

    fn apps() -> Vec<&'static str> {
        vec!["weather", "notes", "chess"]
    }

    fn s(v: &[&str]) -> Vec<String> {
        v.iter().map(|x| x.to_string()).collect()
    }

    // ---- parse_finish_triage ----

    fn args(kind: &str, app: &str, conf: &str) -> Value {
        serde_json::json!({
            "type": kind, "app": app, "confidence": conf,
            "analysis_markdown": "some analysis",
        })
    }

    #[test]
    fn parse_accepts_bug_and_feature() {
        let r = parse_finish_triage(&args("bug", "weather", "high")).unwrap();
        assert_eq!(r.kind, TriageKind::Bug);
        assert_eq!(r.app, "weather");
        assert_eq!(r.confidence, TriageConfidence::High);
        assert_eq!(r.analysis_markdown, "some analysis");
        assert_eq!(r.duplicate_of, None);

        let mut a = args("feature", "notes", "medium");
        a["duplicate_of"] = serde_json::json!(123);
        let r = parse_finish_triage(&a).unwrap();
        assert_eq!(r.kind, TriageKind::Feature);
        assert_eq!(r.duplicate_of, Some(123));
        // App is trimmed.
        let r = parse_finish_triage(&args("bug", "  chess  ", "low")).unwrap();
        assert_eq!(r.app, "chess");
    }

    #[test]
    fn parse_rejects_bad_enums_and_shapes() {
        assert!(parse_finish_triage(&args("BUG", "weather", "high")).is_err());
        assert!(parse_finish_triage(&args("task", "weather", "high")).is_err());
        assert!(parse_finish_triage(&args("bug", "weather", "HIGH")).is_err());
        assert!(parse_finish_triage(&args("bug", "weather", "maybe")).is_err());
        assert!(parse_finish_triage(&args("bug", "", "high")).is_err());
        assert!(parse_finish_triage(&args("bug", "   ", "high")).is_err());
        // Missing keys.
        assert!(parse_finish_triage(&serde_json::json!({"type": "bug"})).is_err());
        // Non-string type.
        assert!(parse_finish_triage(&serde_json::json!(
            {"type": 1, "app": "w", "confidence": "high", "analysis_markdown": "a"}
        ))
        .is_err());
        // Oversize analysis.
        let mut a = args("bug", "weather", "high");
        a["analysis_markdown"] = Value::String("x".repeat(ANALYSIS_CAP_BYTES + 1));
        assert!(parse_finish_triage(&a).is_err());
        let mut a = args("bug", "weather", "high");
        a["analysis_markdown"] = Value::String("x".repeat(ANALYSIS_CAP_BYTES));
        assert!(parse_finish_triage(&a).is_ok());
        // Bad duplicate_of.
        for dup in [
            serde_json::json!(0),
            serde_json::json!(-5),
            serde_json::json!("12"),
            serde_json::json!(1.5),
        ] {
            let mut a = args("bug", "weather", "high");
            a["duplicate_of"] = dup;
            assert!(parse_finish_triage(&a).is_err(), "dup={}", a["duplicate_of"]);
        }
    }

    // ---- plan_label_changes ----

    #[test]
    fn plan_fixes_type_and_app_labels() {
        let (add, remove) = plan_label_changes(&s(&["enhancement", "notes"]), &bug_weather_high(), &apps());
        assert_eq!(add, s(&["bug", "weather"]));
        assert_eq!(remove, s(&["enhancement", "notes"]));
    }

    #[test]
    fn plan_already_correct_is_noop() {
        let (add, remove) =
            plan_label_changes(&s(&["bug", "weather"]), &bug_weather_high(), &apps());
        assert!(add.is_empty() && remove.is_empty());
        // Extra non-type/non-app labels don't trigger changes either.
        let (add, remove) = plan_label_changes(
            &s(&["bug", "weather", "2.6.12", "solved for next release"]),
            &bug_weather_high(),
            &apps(),
        );
        assert!(add.is_empty() && remove.is_empty());
    }

    #[test]
    fn plan_never_touches_versions_or_solved() {
        let (add, remove) = plan_label_changes(
            &s(&["enhancement", "notes", "2.6.12", "solved for next release"]),
            &bug_weather_high(),
            &apps(),
        );
        assert_eq!(add, s(&["bug", "weather"]));
        assert_eq!(remove, s(&["enhancement", "notes"]));
        assert!(!add.iter().chain(remove.iter()).any(|l| l == "2.6.12" || l == "solved for next release"));
    }

    #[test]
    fn plan_low_confidence_or_unknown_app_is_noop() {
        let low = result(TriageKind::Bug, "weather", TriageConfidence::Low);
        let (add, remove) = plan_label_changes(&s(&["enhancement", "notes"]), &low, &apps());
        assert!(add.is_empty() && remove.is_empty());

        let unknown = result(TriageKind::Bug, "notanapp", TriageConfidence::High);
        let (add, remove) =
            plan_label_changes(&s(&["enhancement", "notes"]), &unknown, &apps());
        assert!(add.is_empty() && remove.is_empty());
    }

    #[test]
    fn plan_case_handling() {
        // Current labels in odd case still match; emitted names are canonical.
        let (add, remove) =
            plan_label_changes(&s(&["BUG", "Weather"]), &bug_weather_high(), &apps());
        assert!(add.is_empty(), "add={add:?}");
        assert!(remove.is_empty(), "remove={remove:?}");

        let (add, remove) =
            plan_label_changes(&s(&["ENHANCEMENT", "NoTeS"]), &bug_weather_high(), &apps());
        assert_eq!(add, s(&["bug", "weather"]));
        assert_eq!(remove, s(&["ENHANCEMENT", "NoTeS"]));

        // Result app in odd case resolves to the canonical app_names entry.
        let mixed = result(TriageKind::Feature, "WeAtHeR", TriageConfidence::High);
        let (add, remove) = plan_label_changes(&s(&["bug", "notes"]), &mixed, &apps());
        assert_eq!(add, s(&["enhancement", "weather"]));
        assert_eq!(remove, s(&["bug", "notes"]));
    }

    #[test]
    fn plan_feature_wants_enhancement() {
        let feat = result(TriageKind::Feature, "notes", TriageConfidence::Medium);
        let (add, remove) = plan_label_changes(&s(&["bug"]), &feat, &apps());
        assert_eq!(add, s(&["enhancement", "notes"]));
        assert_eq!(remove, s(&["bug"]));
    }

    #[test]
    fn plan_missing_only_one_side() {
        // Right type, wrong app: only the app changes.
        let (add, remove) =
            plan_label_changes(&s(&["bug", "notes"]), &bug_weather_high(), &apps());
        assert_eq!(add, s(&["weather"]));
        assert_eq!(remove, s(&["notes"]));
        // Right app, wrong type: only the type changes.
        let (add, remove) =
            plan_label_changes(&s(&["enhancement", "weather"]), &bug_weather_high(), &apps());
        assert_eq!(add, s(&["bug"]));
        assert_eq!(remove, s(&["enhancement"]));
    }

    // ---- render_comment ----

    #[test]
    fn render_comment_shapes() {
        let body = render_comment(
            &bug_weather_high(),
            &["bug".to_string(), "weather".to_string()],
            &["enhancement".to_string(), "notes".to_string()],
        );
        assert!(body.contains("🤖 **Automated triage** (AI-generated; may be wrong)"), "{body}");
        assert!(body.contains("Type: bug · App: weather"), "{body}");
        assert!(body.contains("Labels updated: +bug +weather −enhancement −notes"), "{body}");
        assert!(body.contains("### Root cause analysis"), "{body}");
        assert!(body.contains("The widget reads the wrong field."), "{body}");
        assert!(!body.contains("Possible duplicate"), "{body}");
        assert!(body.contains(TRIAGE_MARKER), "{body}");
        assert!(body.trim_end().ends_with(TRIAGE_MARKER), "{body}");
    }

    #[test]
    fn render_comment_variants() {
        // Feature → design suggestions + duplicate pointer.
        let mut feat = result(TriageKind::Feature, "notes", TriageConfidence::Medium);
        feat.analysis_markdown = "Consider a sidebar.".to_string();
        feat.duplicate_of = Some(42);
        let body = render_comment(&feat, &[], &[]);
        assert!(body.contains("### Design suggestions"), "{body}");
        assert!(body.contains("Possible duplicate of #42"), "{body}");
        assert!(body.contains("Labels already correct."), "{body}");

        // Low confidence → unchanged note.
        let low = result(TriageKind::Bug, "weather", TriageConfidence::Low);
        let body = render_comment(&low, &[], &[]);
        assert!(body.contains("Labels left unchanged (low confidence)."), "{body}");
        assert!(!body.contains("Labels already correct."), "{body}");
    }

    // ---- apply_triage + poll_once (mock GitHub) ----

    #[derive(Debug, Clone)]
    struct Recorded {
        method: String,
        path: String,
        body: Vec<u8>,
    }

    #[derive(Clone, Default)]
    struct Mock {
        recorded: std::sync::Arc<std::sync::Mutex<Vec<Recorded>>>,
        responses: std::sync::Arc<std::sync::Mutex<Responses>>,
    }

    /// Mock path → (status, body).
    type Responses = std::collections::HashMap<String, (u16, Vec<u8>)>;

    impl Mock {
        fn respond(&self, path: &str, status: u16, body: impl Into<Vec<u8>>) {
            self.responses
                .lock()
                .unwrap()
                .insert(path.to_string(), (status, body.into()));
        }

        fn requests(&self) -> Vec<Recorded> {
            self.recorded.lock().unwrap().clone()
        }
    }

    async fn handler(
        axum::extract::State(st): axum::extract::State<Mock>,
        req: axum::http::Request<axum::body::Body>,
    ) -> impl axum::response::IntoResponse {
        use axum::http::StatusCode;
        use axum::response::IntoResponse;
        let (parts, body) = req.into_parts();
        let body = axum::body::to_bytes(body, 4 * 1024 * 1024)
            .await
            .unwrap_or_default()
            .to_vec();
        let path = parts.uri.path().to_string();
        st.recorded.lock().unwrap().push(Recorded {
            method: parts.method.to_string(),
            path: path.clone(),
            body,
        });
        // POST to a `/comments` path is a comment write: return the posted
        // shape even when the GET fixture for the same path is `[]`.
        if parts.method == axum::http::Method::POST && path.ends_with("/comments") {
            return (
                StatusCode::CREATED,
                [("content-type", "application/json")],
                r#"{"html_url":"https://github.com/x/y/issues/1#issuecomment-9"}"#.as_bytes().to_vec(),
            )
                .into_response();
        }
        let found = st.responses.lock().unwrap().get(&path).cloned();
        match found {
            Some((status, body)) => (
                StatusCode::from_u16(status).unwrap_or(StatusCode::OK),
                [("content-type", "application/json")],
                body,
            )
                .into_response(),
            // DELETE of an unknown label path → 404 (already gone → Ok).
            None if parts.method == axum::http::Method::DELETE => {
                (StatusCode::NOT_FOUND, "no such label").into_response()
            }
            None => (StatusCode::NOT_FOUND, "no mock").into_response(),
        }
    }

    async fn spawn_mock(mock: Mock) -> String {
        let app = axum::Router::new().fallback(handler).with_state(mock);
        let lis = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = lis.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(lis, app).await.unwrap() });
        format!("http://{addr}")
    }

    const REPO: &str = "test-owner/test-repo";

    fn issue_path(n: u64, suffix: &str) -> String {
        format!("/repos/{REPO}/issues/{n}{suffix}")
    }

    fn issue_body(n: u64, labels: &[&str], created: &str) -> Vec<u8> {
        serde_json::to_vec(&serde_json::json!({
            "number": n, "title": format!("Issue {n}"), "state": "open",
            "labels": labels.iter().map(|l| serde_json::json!({"name": l})).collect::<Vec<_>>(),
            "user": {"login": "someone"}, "created_at": created, "body": "body",
        }))
        .unwrap()
    }

    fn gh(base: &str, token: Option<&str>) -> GitHub {
        GitHub::with_base_url(base, REPO, token.map(str::to_string)).unwrap()
    }

    fn test_config(base: &str, token: Option<&str>) -> crate::config::Config {
        crate::config::Config {
            openrouter_api_key: "k".into(),
            openrouter_base_url: "http://127.0.0.1:9".into(),
            password_hash: "h".into(),
            git_push_token: "g".into(),
            repo_url: "r".into(),
            public_origin: "o".into(),
            default_model: "m".into(),
            runner_url: "http://127.0.0.1:9".into(),
            state_dir: std::path::PathBuf::from("/tmp"),
            mirror_dir: std::path::PathBuf::from("/tmp"),
            release_signal: std::path::PathBuf::from("/tmp/signal"),
            listen: "127.0.0.1:0".into(),
            slots: 1,
            github_repo: REPO.into(),
            github_api_base: base.to_string(),
            github_token: token.map(str::to_string),
            test: Default::default(),
            test_skip_release_wait: false,
            triage_enabled: true,
            triage_interval_secs: 120,
        }
    }

    fn test_ctx(config: crate::config::Config, dir: &tempfile::TempDir) -> crate::agent::DriverCtx {
        use std::sync::Arc;
        let store = crate::store::Store::open(dir.path()).unwrap();
        let (events_tx, _) = tokio::sync::broadcast::channel(8);
        crate::agent::DriverCtx {
            config: Arc::new(config),
            store,
            runner: crate::runner::Runner::new("http://127.0.0.1:9".into()),
            openrouter: crate::openrouter::OpenRouter::new(
                "http://127.0.0.1:9".into(),
                "k".into(),
            ),
            events_tx,
        }
    }

    #[tokio::test]
    async fn apply_triage_corrects_labels_and_comments() {
        let mock = Mock::default();
        mock.respond(
            &issue_path(1, ""),
            200,
            issue_body(1, &["enhancement", "notes", "2.6.12", "solved for next release"], "2026-11-06T00:00:00Z"),
        );
        mock.respond(&issue_path(1, "/comments"), 200, b"[]".to_vec());
        mock.respond(&issue_path(1, "/labels"), 200, b"[]".to_vec());
        let base = spawn_mock(mock.clone()).await;
        let gh = gh(&base, Some("tok"));

        let out = apply_triage(&gh, 1, &bug_weather_high()).await;
        assert!(out.error.is_none(), "{out:?}");
        assert!(!out.skipped_writes);
        assert_eq!(out.labels_added, s(&["bug", "weather"]));
        assert_eq!(out.labels_removed, s(&["enhancement", "notes"]));
        assert!(out.comment_url.as_deref().is_some_and(|u| u.contains("issuecomment")));

        let reqs = mock.requests();
        let deletes: Vec<&Recorded> = reqs.iter().filter(|r| r.method == "DELETE").collect();
        assert_eq!(deletes.len(), 2);
        assert!(deletes.iter().any(|r| r.path.ends_with("/labels/enhancement")));
        assert!(deletes.iter().any(|r| r.path.ends_with("/labels/notes")));
        assert!(reqs.iter().all(|r| !r.path.contains("2.6.12")), "versions untouched");
        assert!(reqs.iter().all(|r| !r.path.contains("solved%20for")), "solved untouched");

        let add = reqs
            .iter()
            .find(|r| r.method == "POST" && r.path.ends_with("/issues/1/labels"))
            .expect("labels POST");
        let v: Value = serde_json::from_slice(&add.body).unwrap();
        assert_eq!(v, serde_json::json!({"labels": ["bug", "weather"]}));

        let comment = reqs
            .iter()
            .find(|r| r.method == "POST" && r.path.ends_with("/issues/1/comments"))
            .expect("comment POST");
        let v: Value = serde_json::from_slice(&comment.body).unwrap();
        let text = v.get("body").and_then(|b| b.as_str()).unwrap_or("");
        assert!(text.contains(TRIAGE_MARKER), "{text}");
        assert!(text.contains("Labels updated:"), "{text}");
        // Re-fetch of the issue happened first (read-before-write).
        assert_eq!(reqs[0].method, "GET");
    }

    #[tokio::test]
    async fn apply_triage_without_token_skips_writes() {
        let offline = GitHub::with_base_url("http://127.0.0.1:9", REPO, None).unwrap();
        let out = apply_triage(&offline, 1, &bug_weather_high()).await;
        assert!(out.skipped_writes);
        assert!(out.labels_added.is_empty() && out.labels_removed.is_empty());
        assert!(out.comment_url.is_none());
        assert!(out.error.is_none());
    }

    fn list_body() -> Vec<u8> {
        serde_json::to_vec(&serde_json::json!([
            {"number": 800, "title": "Old", "state": "open", "labels": [],
             "comments": 0, "created_at": "2026-10-01T00:00:00Z",
             "updated_at": "2026-10-01T00:00:00Z"},
            {"number": 901, "title": "Fresh PR", "state": "open", "labels": [],
             "comments": 0, "created_at": "2026-11-06T00:00:00Z",
             "updated_at": "2026-11-06T00:00:00Z",
             "pull_request": {"url": "https://api.github.com/x"}},
            {"number": 902, "title": "Fresh issue", "state": "open",
             "labels": [{"name": "bug"}, {"name": "weather"}], "comments": 0,
             "created_at": "2026-11-06T01:00:00Z",
             "updated_at": "2026-11-06T01:00:00Z"},
            {"number": 903, "title": "Marked already", "state": "open",
             "labels": [], "comments": 1,
             "created_at": "2026-11-06T02:00:00Z",
             "updated_at": "2026-11-06T02:00:00Z"},
        ]))
        .unwrap()
    }

    #[tokio::test]
    async fn poll_once_queues_and_advances_watermark() {
        let mock = Mock::default();
        mock.respond(&format!("/repos/{REPO}/issues"), 200, list_body());
        mock.respond(&issue_path(902, "/comments"), 200, b"[]".to_vec());
        mock.respond(
            &issue_path(903, "/comments"),
            200,
            format!(r#"[{{"id":1,"user":{{"login":"a"}},"created_at":"2026-11-06T02:00:00Z","body":"done {TRIAGE_MARKER}"}}]"#)
                .into_bytes(),
        );
        let base = spawn_mock(mock.clone()).await;

        let dir = tempfile::tempdir().unwrap();
        let ctx = test_ctx(test_config(&base, None), &dir);

        // First poll: no watermark → set to now, queue nothing (no backfill).
        assert_eq!(poll_once(&ctx).await.unwrap(), 0);
        let wm = ctx.store.kv_get(WATERMARK_KEY).unwrap().unwrap();
        assert!(wm.ends_with('Z'), "{wm}");
        // Override the fresh watermark with an old one to simulate new issues.
        ctx.store.kv_set(WATERMARK_KEY, "2026-11-05T00:00:00Z").unwrap();

        // Second poll: #902 queued (PR filtered, #800 stale, #903 marked).
        assert_eq!(poll_once(&ctx).await.unwrap(), 1);
        let row = ctx.store.triage_get(902).unwrap().expect("triage row");
        let task = ctx.store.get_task(row.task_id).unwrap().unwrap();
        assert_eq!(task.kind, crate::models::TASK_KIND_TRIAGE);
        assert_eq!(task.issue_number, Some(902));
        assert_eq!(task.module_scope, "weather");
        assert!(ctx.store.triage_get(903).unwrap().is_none(), "marked issue skipped");
        // Watermark advanced past #903 (max created_at seen, even for skipped).
        assert_eq!(
            ctx.store.kv_get(WATERMARK_KEY).unwrap().unwrap(),
            "2026-11-06T02:00:00Z"
        );

        // Third poll: row exists → nothing new.
        assert_eq!(poll_once(&ctx).await.unwrap(), 0);
    }
}
