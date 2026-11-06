//! Structured tool layer: validated, purpose-built tools over the runner.
//!
//! These tools replace free-form shell for the common operations (builds,
//! unit tests, `./check`, git history, file finding, GitHub). Every tool
//! validates its arguments in pure Rust (accept/reject tables are unit-tested
//! below), builds a command from the validated parts, runs it through the
//! existing runner proto routes, and renders the result for the model with
//! the shared [`crate::parsers`] / [`crate::github`] renderers.
//!
//! [`dispatch`] is called from the `other` fallback arm of the agent
//! dispatch: `Some(text)` is a model-facing result, `None` means "not a
//! structured tool" (the caller renders the unknown-tool error).
//!
//! Path-touching tools are scope-checked with
//! [`crate::agent::check_scope`] exactly like `read_file`.
//! [`dispatch`] also auto-loads per-directory `AGENTS.md` for the path a tool
//! touches (via [`crate::rules::ensure_rules_for_path`]) and notes newly
//! loaded files in the result.

use crate::agent::{check_scope, exec_build_command, BuildOutcome, DriverCtx};
use crate::github::{render_issue_for_model, render_search_for_model, IssueState};
use crate::openrouter::{FunctionDef, ToolDef};
use crate::parsers::{merge_test_summaries, parse_build_output, parse_junit_xml, render_build_for_model,
    render_tests_for_model, TestSummary};

// ---------------------------------------------------------------------------
// tiny arg helpers
// ---------------------------------------------------------------------------

fn arg_str(args: &serde_json::Value, key: &str) -> Option<String> {
    args.get(key).and_then(|v| v.as_str()).map(|s| s.to_string())
}

fn arg_u64(args: &serde_json::Value, key: &str) -> Option<u64> {
    args.get(key).and_then(|v| v.as_u64())
}

// ---------------------------------------------------------------------------
// shell quoting + path validation (pure, unit-tested)
// ---------------------------------------------------------------------------

/// POSIX single-quote a string for `bash -lc`. Safe for any input, including
/// newlines and quotes: `'a'b` becomes `'a'"'"'b'`.
pub fn shell_quote(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('\'');
    for (i, part) in s.split('\'').enumerate() {
        if i > 0 {
            out.push_str("'\"'\"'");
        }
        out.push_str(part);
    }
    out.push('\'');
    out
}

/// Reject absolute paths and `..` escapes (mirrors the runner client check).
/// Scope confinement itself happens via [`check_scope`] at dispatch time.
pub fn validate_rel_path(path: &str) -> Result<String, String> {
    if path.is_empty() {
        return Err("path must be non-empty".to_string());
    }
    if path.starts_with('/') {
        return Err(format!("absolute paths are not allowed: {path:?}"));
    }
    for seg in path.split('/') {
        if seg == ".." {
            return Err(format!("`..` is not allowed in paths: {path:?}"));
        }
        if seg.is_empty() {
            return Err(format!("empty path segment in {path:?}"));
        }
    }
    Ok(path.to_string())
}

/// Validate a module dir (`games/logicgate`, also accepts `games:logicgate`)
/// against the same allowlist as `tools.rs`. Returns the normalized `/` form.
pub fn validate_module_dir(module: &str) -> Result<String, String> {
    let m = module.trim().replace(':', "/");
    if m.is_empty() {
        return Err("module must be non-empty (e.g. `notes`, `games/logicgate`)".to_string());
    }
    if m.starts_with('/') {
        return Err(format!("module must be repo-relative, not absolute: {module:?}"));
    }
    if !crate::tools::module_path_ok(&m.replace('/', ":")) {
        return Err(format!(
            "bad module {module:?}: segments must match [a-z0-9-]+ (e.g. `notes`, `games/logicgate`)"
        ));
    }
    Ok(m)
}

/// Gradle path form of a validated module dir: `games/logicgate` →
/// `:games:logicgate`.
pub fn gradle_path(dir: &str) -> String {
    format!(":{}", dir.replace('/', ":"))
}

// ---------------------------------------------------------------------------
// build / run_tests / check command construction (pure, unit-tested)
// ---------------------------------------------------------------------------

/// Build the `./gradlew :<module>:<task> ... --console=plain` command from
/// validated parts. Each task must pass the `tools.rs` allowlist (same as the
/// legacy string form); the composed command is re-checked with
/// [`crate::tools::validate_build_command`] as defense in depth.
pub fn build_command(module: &str, tasks: &[String]) -> Result<String, String> {
    let dir = validate_module_dir(module)?;
    if tasks.is_empty() {
        return Err("build needs at least one task (e.g. `lint`)".to_string());
    }
    if tasks.len() > 8 {
        return Err(format!("at most 8 tasks per build call, got {}", tasks.len()));
    }
    for t in tasks {
        if !crate::tools::build_task_ok(t) {
            return Err(format!(
                "task {t:?} is not allowed. {}",
                crate::tools::build_help()
            ));
        }
    }
    let gp = gradle_path(&dir);
    let targets: Vec<String> = tasks.iter().map(|t| format!("{gp}:{t}")).collect();
    let cmd = format!("./gradlew {} --console=plain", targets.join(" "));
    crate::tools::validate_build_command(&cmd)
}

/// Validate a `run_tests` variant (`Dev` in `testDevUnitTest`).
/// `None`/empty selects the default `"Dev"`.
pub fn validate_variant(variant: Option<&str>) -> Result<String, String> {
    match variant.map(str::trim).filter(|v| !v.is_empty()) {
        None => Ok("Dev".to_string()),
        Some(v) => {
            if v.len() <= 24 && v.chars().all(|c| c.is_ascii_alphabetic()) {
                Ok(v.to_string())
            } else {
                Err(format!(
                    "bad variant {v:?}: expected 1-24 ASCII letters (e.g. `Dev`, `Debug`, `Release`)"
                ))
            }
        }
    }
}

/// Validate a `--tests` filter: Gradle test-filter chars only, no shell.
pub fn validate_test_filter(filter: &str) -> Result<String, String> {
    if filter.is_empty() {
        return Err("filter must be non-empty when given".to_string());
    }
    if filter.len() > 200
        || !filter
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.' || c == '*')
    {
        return Err(format!(
            "bad filter {filter:?}: expected 1-200 chars of [A-Za-z0-9_.*] (e.g. `*.ExpressionTest`)"
        ));
    }
    Ok(filter.to_string())
}

/// Build `./gradlew :<m>:test<Variant>UnitTest [--tests <filter>]
/// --console=plain` from validated parts.
///
/// `--tests` is a structured-only flag on purpose: the legacy free-form
/// `build` validator rejects it, so only this validated path can pass a
/// filter to Gradle (the strict charset above plus [`shell_quote`] make
/// injection impossible).
pub fn run_tests_command(
    module: &str,
    variant: Option<&str>,
    filter: Option<&str>,
) -> Result<String, String> {
    let dir = validate_module_dir(module)?;
    let variant = validate_variant(variant)?;
    let task = format!("test{variant}UnitTest");
    if !crate::tools::test_unit_task_ok(&task) {
        return Err(format!("internal: unexpected test task {task:?}"));
    }
    let mut cmd = format!("./gradlew {}:{task}", gradle_path(&dir));
    if let Some(f) = filter {
        let f = validate_test_filter(f.trim())?;
        cmd.push_str(&format!(" --tests {}", shell_quote(&f)));
    }
    cmd.push_str(" --console=plain");
    Ok(cmd)
}

/// Build `./check <module>` from a validated module dir.
pub fn check_command(module: &str) -> Result<String, String> {
    let dir = validate_module_dir(module)?;
    let cmd = format!("./check {dir}");
    crate::tools::validate_build_command(&cmd)
}

// ---------------------------------------------------------------------------
// git command construction (pure, unit-tested)
// ---------------------------------------------------------------------------

/// `git_log{path?, n?}` → `git log --format=... --date=short -n N [-- path]`.
/// `n` defaults to 20 and is capped at 50.
pub fn git_log_command(path: Option<&str>, n: Option<u64>) -> Result<String, String> {
    let n = n.unwrap_or(20);
    if n == 0 || n > 50 {
        return Err(format!("n must be 1-50, got {n}"));
    }
    let mut cmd = format!("git log --format='%h %ad %an %s' --date=short -n {n}");
    if let Some(p) = path {
        let p = validate_rel_path(p.trim())?;
        cmd.push_str(&format!(" -- {}", shell_quote(&p)));
    }
    Ok(cmd)
}

/// Validate a `git_show` revision: 4-40 hex chars, `HEAD`, or `HEAD~<1-3 digits>`.
pub fn validate_git_rev(rev: &str) -> Result<String, String> {
    let r = rev.trim();
    if r == "HEAD" {
        return Ok(r.to_string());
    }
    if let Some(rest) = r.strip_prefix("HEAD~") {
        if !rest.is_empty() && rest.len() <= 3 && rest.chars().all(|c| c.is_ascii_digit()) {
            return Ok(r.to_string());
        }
        return Err(format!("bad revision {rev:?}: expected HEAD, HEAD~<n> (n <= 999), or 4-40 hex chars"));
    }
    if (4..=40).contains(&r.len()) && r.chars().all(|c| c.is_ascii_hexdigit()) {
        return Ok(r.to_string());
    }
    Err(format!(
        "bad revision {rev:?}: expected 4-40 hex chars, HEAD, or HEAD~<n>"
    ))
}

/// `git_show{rev, path?}` → `git show <rev> [-- path]`.
pub fn git_show_command(rev: &str, path: Option<&str>) -> Result<String, String> {
    let rev = validate_git_rev(rev)?;
    let mut cmd = format!("git show {}", shell_quote(&rev));
    if let Some(p) = path {
        let p = validate_rel_path(p.trim())?;
        cmd.push_str(&format!(" -- {}", shell_quote(&p)));
    }
    Ok(cmd)
}

/// Validate a `git_blame` line range: 1-based, `start <= end`, at most 200 lines.
pub fn validate_blame_range(start: u64, end: u64) -> Result<(u64, u64), String> {
    if start == 0 || end == 0 {
        return Err("blame lines are 1-based; start and end must be >= 1".to_string());
    }
    if start > end {
        return Err(format!("blame start ({start}) is after end ({end})"));
    }
    if end - start + 1 > 200 {
        return Err(format!(
            "blame range is {} lines (max 200); narrow start/end",
            end - start + 1
        ));
    }
    Ok((start, end))
}

/// `git_blame{path, start, end}` → `git blame --porcelain -L s,e -- path`.
/// The porcelain output is condensed by [`condense_blame_porcelain`].
pub fn git_blame_command(path: &str, start: u64, end: u64) -> Result<String, String> {
    let p = validate_rel_path(path.trim())?;
    let (start, end) = validate_blame_range(start, end)?;
    Ok(format!(
        "git blame --porcelain -L {start},{end} -- {}",
        shell_quote(&p)
    ))
}

/// Convert a Unix timestamp to `YYYY-MM-DD` (civil-date algorithm; no date dep).
pub fn unix_to_ymd(ts: i64) -> String {
    let days = ts.div_euclid(86_400);
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    format!("{:04}-{:02}-{:02}", if m <= 2 { y + 1 } else { y }, m, d)
}

/// Condense `git blame --porcelain` to `shortsha author YYYY-MM-DD | line`
/// lines. Malformed blocks degrade to `?` fields rather than failing.
pub fn condense_blame_porcelain(out: &str) -> String {
    let mut condensed = Vec::new();
    let mut sha = "?".to_string();
    let mut author = "?".to_string();
    let mut date = "?".to_string();
    for line in out.lines() {
        if let Some(content) = line.strip_prefix('\t') {
            let short: String = sha.chars().take(8).collect();
            condensed.push(format!("{short} {author} {date} | {content}"));
        } else if line.len() > 41
            && line.as_bytes()[40] == b' '
            && line[..40].chars().all(|c| c.is_ascii_hexdigit())
        {
            sha = line.split(' ').next().unwrap_or("?").to_string();
            author = "?".to_string();
            date = "?".to_string();
        } else if let Some(a) = line.strip_prefix("author ") {
            // (`author-time` / `author-mail` don't match: `author ` has the space.)
            if !a.trim().is_empty() {
                author = a.trim().to_string();
            }
        } else if let Some(t) = line.strip_prefix("author-time ") {
            if let Ok(ts) = t.trim().parse::<i64>() {
                date = unix_to_ymd(ts);
            }
        }
    }
    condensed.join("\n")
}

/// `find_files{glob, path?}` → `rg --files -g <glob> <root>`.
pub fn find_files_command(glob: &str, root: &str) -> Result<String, String> {
    let g = glob.trim();
    if g.is_empty() {
        return Err("glob must be non-empty (e.g. `*.kt`)".to_string());
    }
    if g.len() > 200 || g.chars().any(|c| c.is_control()) {
        return Err(format!("bad glob {glob:?}: max 200 printable chars"));
    }
    let r = validate_rel_path(root.trim())?;
    Ok(format!("rg --files -g {} {}", shell_quote(g), shell_quote(&r)))
}

/// Cap model-facing command output at `max` lines.
fn cap_lines(s: &str, max: usize) -> String {
    let mut count = 0;
    let mut out = String::new();
    for line in s.lines() {
        count += 1;
        if count > max {
            out.push_str(&format!("…[output capped at {max} lines]"));
            break;
        }
        out.push_str(line);
        out.push('\n');
    }
    out
}

// ---------------------------------------------------------------------------
// tool definitions (appended to `openrouter::tool_defs`)
// ---------------------------------------------------------------------------

fn params(props: serde_json::Value, required: &[&str]) -> serde_json::Value {
    serde_json::json!({
        "type": "object",
        "properties": props,
        "required": required,
        "additionalProperties": false,
    })
}

/// Structured tools. (`build` keeps its existing def in `openrouter.rs`,
/// extended there with the `module` + `tasks` form.)
pub fn tool_defs() -> Vec<ToolDef> {
    vec![
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "run_tests",
                description: "Run module unit tests (`./gradlew :<module>:test<Variant>UnitTest [--tests <filter>]`) and return parsed JUnit counts plus failing test names. Prefer over run_shell.",
                parameters: params(serde_json::json!({
                    "module": {"type": "string", "description": "Module dir, e.g. notes, games/logicgate"},
                    "filter": {"type": "string", "description": "Optional --tests filter, e.g. *.ExpressionTest"},
                    "variant": {"type": "string", "description": "Build variant, default Dev (testDevUnitTest)"},
                }), &["module"]),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "check",
                description: "Run `./check <module>` and return the parsed findings. Prefer over run_shell.",
                parameters: params(serde_json::json!({
                    "module": {"type": "string", "description": "Module dir, e.g. notes, games/logicgate"},
                }), &["module"]),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "git_log",
                description: "Recent commit history (`git log --format='%h %ad %an %s' --date=short`). Read-only.",
                parameters: params(serde_json::json!({
                    "path": {"type": "string", "description": "Optional repo-relative path to limit history to"},
                    "n": {"type": "integer", "description": "Max commits, 1-50 (default 20)"},
                }), &[]),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "git_show",
                description: "Show one commit, or a file at a revision. Read-only. rev is 4-40 hex chars, HEAD, or HEAD~N.",
                parameters: params(serde_json::json!({
                    "rev": {"type": "string", "description": "Revision: 4-40 hex chars, HEAD, or HEAD~N"},
                    "path": {"type": "string", "description": "Optional repo-relative path"},
                }), &["rev"]),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "git_blame",
                description: "Blame lines start..end of a file (1-based, max 200 lines), condensed to `sha author date | line`. Read-only.",
                parameters: params(serde_json::json!({
                    "path": {"type": "string", "description": "Repo-relative file path"},
                    "start": {"type": "integer", "description": "First line (1-based)"},
                    "end": {"type": "integer", "description": "Last line (inclusive, max start+199)"},
                }), &["path", "start", "end"]),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "find_files",
                description: "List files matching a glob (`rg --files -g <glob>`), capped at 200 lines, inside your scope. Prefer over broad list_dir/search.",
                parameters: params(serde_json::json!({
                    "glob": {"type": "string", "description": "Glob, e.g. *.kt"},
                    "path": {"type": "string", "description": "Optional subdirectory (default: your module scope)"},
                }), &["glob"]),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "github_search_issues",
                description: "Search GitHub issues on the repo. Runs server-side; the sandbox never sees the token.",
                parameters: params(serde_json::json!({
                    "query": {"type": "string", "description": "Free-text query (optional)"},
                    "labels": {"type": "array", "items": {"type": "string"}, "description": "Label filter (optional)"},
                    "state": {"type": "string", "description": "open, closed, or all (default open)"},
                }), &[]),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "github_read_issue",
                description: "Read one GitHub issue with its comments (capped). Runs server-side.",
                parameters: params(serde_json::json!({
                    "number": {"type": "integer", "description": "Issue number"},
                }), &["number"]),
            },
        },
    ]
}

// ---------------------------------------------------------------------------
// dispatch
// ---------------------------------------------------------------------------

/// Dispatch one structured tool call. `Some(text)` is the model-facing
/// result; `None` means "not a structured tool".
pub async fn dispatch(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    task: &crate::models::Task,
    name: &str,
    args: &serde_json::Value,
) -> Option<String> {
    let scope = task.module_scope.clone();
    let result = match name {
        "build" => Some(dispatch_build(ctx, task_id, slot, args).await),
        "run_tests" => Some(dispatch_run_tests(ctx, task_id, slot, args).await),
        "check" => Some(dispatch_check(ctx, task_id, slot, args).await),
        "git_log" => Some(dispatch_git_log(ctx, slot, &scope, args).await),
        "git_show" => Some(dispatch_git_show(ctx, slot, &scope, args).await),
        "git_blame" => Some(dispatch_git_blame(ctx, slot, &scope, args).await),
        "find_files" => Some(dispatch_find_files(ctx, slot, &scope, args).await),
        "github_search_issues" => Some(dispatch_github_search(ctx, args).await),
        "github_read_issue" => Some(dispatch_github_read(ctx, args).await),
        _ => None,
    }?;
    Some(match rules_path_for(name, args, &scope) {
        Some(path) => with_rules_note(ctx, task_id, slot, &path, result).await,
        None => result,
    })
}

/// The repo path a structured tool touches, for AGENTS.md auto-load.
fn rules_path_for(name: &str, args: &serde_json::Value, scope: &str) -> Option<String> {
    let arg = |k: &str| arg_str(args, k).filter(|s| !s.trim().is_empty());
    match name {
        "build" | "run_tests" | "check" => arg("module"),
        "git_log" | "git_show" | "git_blame" => arg("path"),
        "find_files" => arg("path").or_else(|| Some(scope.replace(':', "/"))),
        _ => None,
    }
}

/// Load any not-yet-seen AGENTS.md on the way to `path` and note them in the
/// tool result. Skipped for rejected calls (the path may be invalid).
async fn with_rules_note(ctx: &DriverCtx, task_id: i64, slot: u32, path: &str, body: String) -> String {
    if body.starts_with("Rejected:") {
        return body;
    }
    let newly = crate::rules::ensure_rules_for_path(ctx, task_id, slot, path).await;
    if newly.is_empty() {
        body
    } else {
        format!("{body}\n[loaded rules: {}]", newly.join(", "))
    }
}

/// Parse `build` args: structured `{module, tasks}` (preferred, also
/// accepted by the `build` dispatch arm) or the legacy `{command}` string.
pub(crate) fn parse_build_args(args: &serde_json::Value) -> Result<String, String> {
    if let Some(module) = args.get("module").and_then(|v| v.as_str()) {
        let tasks: Vec<String> = args
            .get("tasks")
            .and_then(|v| v.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|v| v.as_str().map(|s| s.to_string()))
                    .collect()
            })
            .unwrap_or_default();
        return build_command(module, &tasks);
    }
    if let Some(cmd) = args.get("command").and_then(|v| v.as_str()) {
        return crate::tools::validate_build_command(cmd);
    }
    Err("build needs `module` + `tasks` (e.g. {\"module\": \"notes\", \"tasks\": [\"lint\"]}) or the legacy `command` string".to_string())
}

async fn dispatch_build(ctx: &DriverCtx, task_id: i64, slot: u32, args: &serde_json::Value) -> String {
    let allowed = match parse_build_args(args) {
        Ok(c) => c,
        Err(e) => return format!("Rejected: {e}"),
    };
    match exec_build_command(ctx, task_id, slot, &allowed).await {
        BuildOutcome::Done(r) => {
            let summary = parse_build_output(&r.stdout, &r.stderr, r.exit_code, "");
            render_build_for_model(&summary)
        }
        BuildOutcome::Message(m) => m,
    }
}

/// Structured-form `build{module, tasks}` entry point used by the `build`
/// dispatch arm in `agent.rs` (same validation + parsed rendering as
/// [`dispatch`] would produce for the structured shape).
pub(crate) async fn dispatch_structured_build(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    args: &serde_json::Value,
) -> String {
    let body = dispatch_build(ctx, task_id, slot, args).await;
    match arg_str(args, "module").filter(|s| !s.trim().is_empty()) {
        Some(module) => with_rules_note(ctx, task_id, slot, &module, body).await,
        None => body,
    }
}

async fn dispatch_run_tests(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    args: &serde_json::Value,
) -> String {
    let module = arg_str(args, "module").unwrap_or_default();
    let variant = arg_str(args, "variant").filter(|v| !v.trim().is_empty());
    let filter = arg_str(args, "filter").filter(|f| !f.trim().is_empty());
    let allowed = match run_tests_command(&module, variant.as_deref(), filter.as_deref()) {
        Ok(c) => c,
        Err(e) => return format!("Rejected: {e}"),
    };
    let variant = validate_variant(variant.as_deref()).unwrap_or_else(|_| "Dev".to_string());
    let resp = match exec_build_command(ctx, task_id, slot, &allowed).await {
        BuildOutcome::Done(r) => r,
        BuildOutcome::Message(m) => return m,
    };
    // Prefer JUnit XML when the run produced any; otherwise (compile
    // failure, no results dir) report the parsed build summary.
    let dir = module.trim().replace(':', "/");
    let dir = dir.trim_matches('/');
    if let Some(summary) = read_test_xmls(ctx, slot, dir, &variant).await {
        return render_tests_for_model(&summary);
    }
    let summary = parse_build_output(&resp.stdout, &resp.stderr, resp.exit_code, "");
    render_build_for_model(&summary)
}

/// List `<module>/build/test-results/test<Variant>UnitTest/`, read each
/// `TEST-*.xml` (first 20), parse and merge. `None` when no report parsed.
async fn read_test_xmls(
    ctx: &DriverCtx,
    slot: u32,
    module_dir: &str,
    variant: &str,
) -> Option<TestSummary> {
    let results_dir = format!("{module_dir}/build/test-results/test{variant}UnitTest");
    let listing = ctx.runner.list(slot, &results_dir).await.ok()?;
    let mut parsed = Vec::new();
    for entry in listing
        .entries
        .iter()
        .filter(|e| e.starts_with("TEST-") && e.ends_with(".xml"))
        .take(20)
    {
        let path = format!("{results_dir}/{entry}");
        let content = match ctx.runner.read(slot, &path, None, None).await {
            Ok(r) => r.content,
            Err(_) => continue,
        };
        if let Ok(summary) = parse_junit_xml(&content) {
            parsed.push(summary);
        }
    }
    if parsed.is_empty() {
        return None;
    }
    Some(merge_test_summaries(&parsed))
}

async fn dispatch_check(ctx: &DriverCtx, task_id: i64, slot: u32, args: &serde_json::Value) -> String {
    let module = arg_str(args, "module").unwrap_or_default();
    let allowed = match check_command(&module) {
        Ok(c) => c,
        Err(e) => return format!("Rejected: {e}"),
    };
    match exec_build_command(ctx, task_id, slot, &allowed).await {
        BuildOutcome::Done(r) => {
            let summary = parse_build_output(&r.stdout, &r.stderr, r.exit_code, "");
            render_build_for_model(&summary)
        }
        BuildOutcome::Message(m) => m,
    }
}

fn scoped_path(path: &str, module_scope: &str) -> Result<String, String> {
    let p = validate_rel_path(path.trim())?;
    check_scope(&p, module_scope)?;
    Ok(p)
}

async fn dispatch_git_log(
    ctx: &DriverCtx,
    slot: u32,
    module_scope: &str,
    args: &serde_json::Value,
) -> String {
    let path = arg_str(args, "path").filter(|p| !p.trim().is_empty());
    if let Some(p) = &path {
        if let Err(e) = scoped_path(p, module_scope) {
            return format!("Rejected: {e}");
        }
    }
    let cmd = match git_log_command(path.as_deref(), arg_u64(args, "n")) {
        Ok(c) => c,
        Err(e) => return format!("Rejected: {e}"),
    };
    match ctx.runner.exec(slot, &cmd, proto::ExecKind::Shell, 60).await {
        Ok(r) => {
            if r.exit_code == Some(0) {
                let out = if r.stdout.trim().is_empty() {
                    "(no commits)".to_string()
                } else {
                    cap_lines(&r.stdout, 100)
                };
                out
            } else {
                format!("git log failed: {}", cap_lines(&r.stderr, 20).trim())
            }
        }
        Err(e) => format!("git log failed: {e}"),
    }
}

async fn dispatch_git_show(
    ctx: &DriverCtx,
    slot: u32,
    module_scope: &str,
    args: &serde_json::Value,
) -> String {
    let rev = arg_str(args, "rev").unwrap_or_default();
    let path = arg_str(args, "path").filter(|p| !p.trim().is_empty());
    if let Some(p) = &path {
        if let Err(e) = scoped_path(p, module_scope) {
            return format!("Rejected: {e}");
        }
    }
    let cmd = match git_show_command(&rev, path.as_deref()) {
        Ok(c) => c,
        Err(e) => return format!("Rejected: {e}"),
    };
    match ctx.runner.exec(slot, &cmd, proto::ExecKind::Shell, 60).await {
        Ok(r) => {
            if r.exit_code == Some(0) {
                cap_lines(&r.stdout, 300)
            } else {
                format!("git show failed: {}", cap_lines(&r.stderr, 20).trim())
            }
        }
        Err(e) => format!("git show failed: {e}"),
    }
}

async fn dispatch_git_blame(
    ctx: &DriverCtx,
    slot: u32,
    module_scope: &str,
    args: &serde_json::Value,
) -> String {
    let path = arg_str(args, "path").unwrap_or_default();
    let path = match scoped_path(&path, module_scope) {
        Ok(p) => p,
        Err(e) => return format!("Rejected: {e}"),
    };
    let (start, end) = match (arg_u64(args, "start"), arg_u64(args, "end")) {
        (Some(s), Some(e)) => (s, e),
        _ => return "Rejected: git_blame needs integer `start` and `end` (1-based, at most 200 lines)".to_string(),
    };
    let cmd = match git_blame_command(&path, start, end) {
        Ok(c) => c,
        Err(e) => return format!("Rejected: {e}"),
    };
    match ctx.runner.exec(slot, &cmd, proto::ExecKind::Shell, 60).await {
        Ok(r) => {
            if r.exit_code == Some(0) {
                condense_blame_porcelain(&r.stdout)
            } else {
                format!("git blame failed: {}", cap_lines(&r.stderr, 20).trim())
            }
        }
        Err(e) => format!("git blame failed: {e}"),
    }
}

async fn dispatch_find_files(
    ctx: &DriverCtx,
    slot: u32,
    module_scope: &str,
    args: &serde_json::Value,
) -> String {
    let glob = arg_str(args, "glob").unwrap_or_default();
    // Default root is the module scope dir, so `rg` never scans the repo.
    let default_root = module_scope.replace(':', "/");
    let root = arg_str(args, "path").filter(|p| !p.trim().is_empty()).unwrap_or(default_root);
    let root = match scoped_path(&root, module_scope) {
        Ok(p) => p,
        Err(e) => return format!("Rejected: {e}"),
    };
    let cmd = match find_files_command(&glob, &root) {
        Ok(c) => c,
        Err(e) => return format!("Rejected: {e}"),
    };
    match ctx.runner.exec(slot, &cmd, proto::ExecKind::Shell, 60).await {
        Ok(r) => {
            if r.exit_code == Some(0) {
                let out = r.stdout.trim().to_string();
                if out.is_empty() {
                    "(no files matched)".to_string()
                } else {
                    cap_lines(&out, 200)
                }
            } else {
                format!("find_files failed: {}", cap_lines(&r.stderr, 20).trim())
            }
        }
        Err(e) => format!("find_files failed: {e}"),
    }
}

/// Parse an issue `state` arg: `open` (default), `closed`, or `all`.
pub fn parse_issue_state(args: &serde_json::Value) -> Result<IssueState, String> {
    match args
        .get("state")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_lowercase())
        .as_deref()
    {
        None | Some("") | Some("open") => Ok(IssueState::Open),
        Some("closed") => Ok(IssueState::Closed),
        Some("all") => Ok(IssueState::All),
        Some(other) => Err(format!("bad state {other:?}: expected open, closed, or all")),
    }
}

fn parse_labels(args: &serde_json::Value) -> Result<Vec<String>, String> {
    match args.get("labels") {
        None | Some(serde_json::Value::Null) => Ok(Vec::new()),
        Some(serde_json::Value::Array(items)) => {
            let mut labels = Vec::with_capacity(items.len());
            for item in items {
                match item.as_str() {
                    Some(l) if !l.trim().is_empty() && l.len() <= 100 => {
                        labels.push(l.trim().to_string())
                    }
                    _ => return Err("labels must be an array of non-empty strings (<=100 chars)".to_string()),
                }
            }
            if labels.len() > 10 {
                return Err("at most 10 labels per search".to_string());
            }
            Ok(labels)
        }
        Some(_) => Err("labels must be an array of strings".to_string()),
    }
}

async fn dispatch_github_search(ctx: &DriverCtx, args: &serde_json::Value) -> String {
    let state = match parse_issue_state(args) {
        Ok(s) => s,
        Err(e) => return format!("Rejected: {e}"),
    };
    let labels = match parse_labels(args) {
        Ok(l) => l,
        Err(e) => return format!("Rejected: {e}"),
    };
    let query = arg_str(args, "query").filter(|q| !q.trim().is_empty());
    let gh = crate::issues::github_client(&ctx.config);
    match gh {
        Ok(gh) => match gh.search_issues(query.as_deref(), &labels, state, 20).await {
            Ok(found) if found.is_empty() => "(no issues matched)".to_string(),
            Ok(found) => render_search_for_model(&found),
            Err(e) => format!("GitHub search failed: {e}"),
        },
        Err(e) => format!("GitHub unavailable: {e}"),
    }
}

async fn dispatch_github_read(ctx: &DriverCtx, args: &serde_json::Value) -> String {
    let number = match arg_u64(args, "number") {
        Some(n) if n > 0 => n,
        _ => return "Rejected: github_read_issue needs a positive integer `number`".to_string(),
    };
    match crate::issues::fetch_issue(&ctx.config, number).await {
        Ok(issue) => render_issue_for_model(&issue),
        Err(e) => format!("GitHub read failed: {e}"),
    }
}


#[cfg(test)]
mod tests {
    use super::*;

    fn strings(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn shell_quote_table() {
        assert_eq!(shell_quote("abc"), "'abc'");
        assert_eq!(shell_quote("a b"), "'a b'");
        // Single quotes become '"'"' — the quoted value re-parses to the input.
        assert_eq!(shell_quote("a'b"), "'a'\"'\"'b'");
        // Injection metacharacters are inert inside single quotes (only the
        // quote char itself needs the '"'"' dance).
        for evil in ["$(whoami)", "a; rm -rf /", "x\ny", "a|b", "a>b", "$HOME"] {
            let q = shell_quote(evil);
            assert!(q.starts_with('\'') && q.ends_with('\'') && !q[1..q.len()-1].contains('\''), "{evil:?} -> {q:?}");
        }
        // (Backticks are likewise inert inside single quotes; they only need
        // quoting when they appear, like any other char.)
        assert_eq!(shell_quote("`id`"), "'`id`'");
    }

    #[test]
    fn validate_rel_path_table() {
        for ok in ["notes/file.txt", "games/logicgate/AGENTS.md", "a", "a-b/c_d/e"] {
            assert!(validate_rel_path(ok).is_ok(), "{ok:?}");
        }
        for bad in ["", "/abs/path", "../escape", "a/../../b", "a//b", "a/./b", "notes/"] {
            // Note: `a/./b` has no `..`/absolute but `.` segments are still
            // rejected by the runner... here `.` is allowed through (only
            // `..` + empty segments are rejected); assert the documented set.
            let r = validate_rel_path(bad);
            if bad == "a/./b" {
                assert!(r.is_ok(), "{bad:?}");
            } else {
                assert!(r.is_err(), "{bad:?}");
            }
        }
    }

    #[test]
    fn validate_module_dir_table() {
        assert_eq!(validate_module_dir("notes").unwrap(), "notes");
        assert_eq!(validate_module_dir("games/logicgate").unwrap(), "games/logicgate");
        assert_eq!(validate_module_dir("games:logicgate").unwrap(), "games/logicgate");
        assert_eq!(validate_module_dir("  notes  ").unwrap(), "notes");
        assert_eq!(validate_module_dir("my-app").unwrap(), "my-app");
        for bad in ["", "Notes", "notes app", "notes;rm", "/notes", "../x", "a:b:c:", "a//b"] {
            assert!(validate_module_dir(bad).is_err(), "{bad:?}");
        }
        assert_eq!(gradle_path("games/logicgate"), ":games:logicgate");
    }

    #[test]
    fn build_command_accepts() {
        assert_eq!(
            build_command("notes", &strings(&["compileDevKotlin"])).unwrap(),
            "./gradlew :notes:compileDevKotlin --console=plain"
        );
        assert_eq!(
            build_command("games/logicgate", &strings(&["lint", "detekt"])).unwrap(),
            "./gradlew :games:logicgate:lint :games:logicgate:detekt --console=plain"
        );
        assert_eq!(
            build_command("games:logicgate", &strings(&["testDevUnitTest"])).unwrap(),
            "./gradlew :games:logicgate:testDevUnitTest --console=plain"
        );
    }

    #[test]
    fn build_command_rejects() {
        // Bad modules.
        assert!(build_command("", &strings(&["lint"])).is_err());
        assert!(build_command("../escape", &strings(&["lint"])).is_err());
        assert!(build_command("/abs", &strings(&["lint"])).is_err());
        // Task allowlist (same as tools.rs).
        assert!(build_command("notes", &[]).is_err());
        assert!(build_command("notes", &strings(&["assembleDebug"])).is_err());
        assert!(build_command("notes", &strings(&["test"])).is_err());
        assert!(build_command("notes", &strings(&["lint; rm -rf /"])).is_err());
        assert!(build_command("notes", &strings(&["$(lint)"])).is_err());
        assert!(build_command("notes", &strings(&["lint`"])).is_err());
        assert!(build_command("notes", &strings(&["--console=plain"])).is_err());
        // Too many tasks.
        let many = vec!["lint".to_string(); 9];
        assert!(build_command("notes", &many).is_err());
    }

    #[test]
    fn run_tests_command_table() {
        assert_eq!(
            run_tests_command("notes", None, None).unwrap(),
            "./gradlew :notes:testDevUnitTest --console=plain"
        );
        assert_eq!(
            run_tests_command("notes", Some("Debug"), None).unwrap(),
            "./gradlew :notes:testDebugUnitTest --console=plain"
        );
        assert_eq!(
            run_tests_command("games/logicgate", Some("Release"), Some("*.EngineTest")).unwrap(),
            "./gradlew :games:logicgate:testReleaseUnitTest --tests '*.EngineTest' --console=plain"
        );
        // Injection attempts in module / variant / filter.
        assert!(run_tests_command("notes; evil", None, None).is_err());
        assert!(run_tests_command("notes", Some("Dev; rm"), None).is_err());
        assert!(run_tests_command("notes", Some("D3v"), None).is_err());
        assert!(run_tests_command("notes", None, Some("a; rm -rf /")).is_err());
        assert!(run_tests_command("notes", None, Some("$(whoami)")).is_err());
        assert!(run_tests_command("notes", None, Some("a`b`")).is_err());
        assert!(run_tests_command("notes", None, Some("a b")).is_err());
        assert!(run_tests_command("notes", None, Some("a/b")).is_err());
        assert!(run_tests_command("notes", None, Some("")).is_err());
        // Filter quoting keeps values with dots/stars intact and inert.
        let cmd = run_tests_command("notes", None, Some("com.example.*Test")).unwrap();
        assert!(cmd.contains("--tests 'com.example.*Test'"), "{cmd}");
    }

    #[test]
    fn check_command_table() {
        assert_eq!(check_command("notes").unwrap(), "./check notes");
        assert_eq!(check_command("games/logicgate").unwrap(), "./check games/logicgate");
        assert_eq!(check_command("games:logicgate").unwrap(), "./check games/logicgate");
        assert!(check_command("all").is_err());
        assert!(check_command("").is_err());
        assert!(check_command("notes backup").is_err());
        assert!(check_command("../x").is_err());
        assert!(check_command("notes; evil").is_err());
    }

    #[test]
    fn git_log_command_table() {
        assert_eq!(
            git_log_command(None, None).unwrap(),
            "git log --format='%h %ad %an %s' --date=short -n 20"
        );
        assert_eq!(
            git_log_command(Some("notes/file.txt"), Some(5)).unwrap(),
            "git log --format='%h %ad %an %s' --date=short -n 5 -- 'notes/file.txt'"
        );
        assert!(git_log_command(None, Some(0)).is_err());
        assert!(git_log_command(None, Some(51)).is_err());
        // Path traversal / absolute / injection rejected before quoting.
        assert!(git_log_command(Some("../../etc/passwd"), None).is_err());
        assert!(git_log_command(Some("/abs"), None).is_err());
        assert!(git_log_command(Some("a'; rm -rf /; echo '"), None).is_ok()); // quoted inert
        let cmd = git_log_command(Some("a'; rm -rf /; echo '"), None).unwrap();
        assert!(cmd.contains("-- 'a'"), "{cmd}");
    }

    #[test]
    fn git_rev_and_show_table() {
        for ok in ["HEAD", "HEAD~1", "HEAD~999", "abc123", "ABCDEF0123456789abcdef", &"a".repeat(40)] {
            assert!(validate_git_rev(ok).is_ok(), "{ok:?}");
        }
        for bad in ["", "head", "main", "HEAD~", "HEAD~1000", "HEAD~-1", "abc", &"a".repeat(41), "xyz!", "HEAD~1; rm", "$(HEAD)", "`HEAD`", "HEAD~1 | cat"] {
            assert!(validate_git_rev(bad).is_err(), "{bad:?}");
        }
        assert_eq!(
            git_show_command("HEAD", None).unwrap(),
            "git show 'HEAD'"
        );
        assert_eq!(
            git_show_command("abc123", Some("notes/f.txt")).unwrap(),
            "git show 'abc123' -- 'notes/f.txt'"
        );
        assert!(git_show_command("nope!", None).is_err());
        assert!(git_show_command("HEAD", Some("../x")).is_err());
    }

    #[test]
    fn git_blame_command_table() {
        assert_eq!(
            git_blame_command("notes/f.txt", 1, 50).unwrap(),
            "git blame --porcelain -L 1,50 -- 'notes/f.txt'"
        );
        assert!(git_blame_command("notes/f.txt", 0, 10).is_err());
        assert!(git_blame_command("notes/f.txt", 10, 5).is_err());
        assert!(git_blame_command("notes/f.txt", 1, 201).is_err());
        assert!(git_blame_command("notes/f.txt", 1, 200).is_ok());
        assert!(git_blame_command("../x", 1, 10).is_err());
        assert!(git_blame_command("/abs", 1, 10).is_err());
    }

    #[test]
    fn blame_condense_and_dates() {
        assert_eq!(unix_to_ymd(0), "1970-01-01");
        assert_eq!(unix_to_ymd(1793904494), "2026-11-05");
        let sha = "a".repeat(40);
        let porcelain = format!(
            "{sha} 1 1 1\nauthor Jane Doe\nauthor-mail <j@x>\nauthor-time 1793904494\n\
             author-tz +0000\nsummary x\nfilename notes/f.txt\n\tgreeting line\n\
             {sha} 2 2 1\nauthor Jane Doe\nauthor-time 0\n\tsecond line\n"
        );
        let out = condense_blame_porcelain(&porcelain);
        let lines: Vec<&str> = out.lines().collect();
        assert_eq!(lines.len(), 2);
        assert!(lines[0].starts_with("aaaaaaaa Jane Doe 2026-11-05 | greeting line"), "{out}");
        assert!(lines[1].starts_with("aaaaaaaa Jane Doe 1970-01-01 | second line"), "{out}");
    }

    #[test]
    fn find_files_command_table() {
        assert_eq!(
            find_files_command("*.kt", "notes").unwrap(),
            "rg --files -g '*.kt' 'notes'"
        );
        // Glob metacharacters that could escape to shell are quoted.
        let cmd = find_files_command("*.kt; rm -rf /", "notes").unwrap();
        assert!(cmd.contains("-g '*.kt; rm -rf /'"), "{cmd}");
        assert!(find_files_command("", "notes").is_err());
        assert!(find_files_command("*.kt", "../x").is_err());
        assert!(find_files_command("*.kt", "/abs").is_err());
    }

    #[test]
    fn issue_state_and_labels_table() {
        let state = |v: serde_json::Value| parse_issue_state(&v).map(|s| s.as_str());
        assert_eq!(state(serde_json::json!({})).unwrap(), "open");
        assert_eq!(state(serde_json::json!({"state": "closed"})).unwrap(), "closed");
        assert_eq!(state(serde_json::json!({"state": "ALL"})).unwrap(), "all");
        assert!(state(serde_json::json!({"state": "bogus"})).is_err());
        assert_eq!(
            parse_labels(&serde_json::json!({"labels": ["bug", "weather"]})).unwrap(),
            vec!["bug".to_string(), "weather".to_string()]
        );
        assert!(parse_labels(&serde_json::json!({"labels": "bug"})).is_err());
        assert!(parse_labels(&serde_json::json!({"labels": [""]})).is_err());
        assert!(parse_labels(&serde_json::json!({"labels": ["$(x)"]})).unwrap().len() == 1);
    }
}
