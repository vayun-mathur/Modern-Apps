//! Command allowlists: which `build` invocations agentd forwards to the
//! runner, and which `run_shell` commands it rejects.
//!
//! Gradle builds in this repo go through `./gradlew :<path>:<task>` or the
//! `./check <module>` wrapper. Raw build-ish commands (`cargo build`,
//! `adb`, …) are rejected from `run_shell` with a pointer to `build`.

use std::collections::HashSet;

/// Gradle tasks the `build` tool may run (exact allowlist).
const ALLOWED_TASKS: &[&str] = &[
    "compileDevKotlin",
    "lint",
    "lintDev",
    "checkMetadata",
    "detekt",
    "metadata",
];

/// Extra flags allowed after the task list.
const ALLOWED_FLAGS: &[&str] = &["--console=plain", "--stacktrace", "--info"];

fn is_test_unit_task(task: &str) -> bool {
    // `test[A-Za-z]*UnitTest`, e.g. `testDebugUnitTest`.
    let rest = match task.strip_prefix("test") {
        Some(r) => r,
        None => return false,
    };
    let body = match rest.strip_suffix("UnitTest") {
        Some(b) => b,
        None => return false,
    };
    body.chars().all(|c| c.is_ascii_alphabetic())
}

fn is_cargo_build_task(task: &str) -> bool {
    // `cargoBuild[A-Za-z0-9_]*`, e.g. `cargoBuildNativeDebug`.
    match task.strip_prefix("cargoBuild") {
        Some(rest) => {
            rest.chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_')
        }
        None => false,
    }
}

fn task_allowed(task: &str) -> bool {
    ALLOWED_TASKS.contains(&task) || is_test_unit_task(task) || is_cargo_build_task(task)
}

/// Whether a bare Gradle task name passes the allowlist.
/// `pub(crate)` so `structured.rs` validates structured `build{module, tasks}`
/// parts against this same allowlist.
pub(crate) fn build_task_ok(task: &str) -> bool {
    task_allowed(task)
}

/// Whether a `test<Variant>UnitTest` task name is allowed.
/// `pub(crate)` for `structured::run_tests_command`.
pub(crate) fn test_unit_task_ok(task: &str) -> bool {
    is_test_unit_task(task)
}

/// Module dirs the structured `build`/`run_tests`/`check` tools accept.
/// `pub(crate)` so `structured.rs` validates against the same allowlist.
pub(crate) fn module_path_ok(path: &str) -> bool {
    // `[a-z0-9-]+(:[a-z0-9-]+)*`, e.g. `notes`, `games:logicgate`.
    !path.is_empty()
        && path
            .split(':')
            .all(|seg| !seg.is_empty() && seg.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-'))
}

pub fn build_help() -> &'static str {
    "Allowed builds: `./gradlew :<module path>:<task> [...] [--console=plain --stacktrace --info]` \
     where module path matches `[a-z0-9-]+(:[a-z0-9-]+)*` (e.g. `notes`, `games:logicgate`) and task is one of \
     `compileDevKotlin`, `lint`, `lintDev`, `checkMetadata`, `detekt`, `metadata`, `test*UnitTest`, `cargoBuild*`; \
     or `./check <module>` (a single module name, not `all`)."
}

/// Validate a `build` command. Returns the exact command to forward when
/// allowed, else a helpful rejection naming what is allowed.
pub fn validate_build_command(cmd: &str) -> Result<String, String> {
    let cmd = cmd.trim();
    if cmd.is_empty() {
        return Err(format!("Empty build command. {}", build_help()));
    }
    // No shell metacharacters: one program invocation only.
    for meta in ["&&", "||", ";", "|", "$(", "`", "$", ">", "<", "\n"] {
        if cmd.contains(meta) {
            return Err(format!(
                "Build commands must be a single invocation (found {meta:?}). {}",
                build_help()
            ));
        }
    }
    // `./check <module>` form.
    if let Some(rest) = cmd.strip_prefix("./check") {
        let args: Vec<&str> = rest.split_whitespace().collect();
        if args.len() == 1 && module_path_ok(&args[0].replace('/', ":")) && args[0] != "all" {
            return Ok(cmd.to_string());
        }
        return Err(format!(
            "`./check` takes exactly one module (e.g. `./check notes`), never `all`. {}",
            build_help()
        ));
    }
    // `./gradlew :path:task ...` form.
    let parts: Vec<&str> = cmd.split_whitespace().collect();
    if parts.len() < 2 || (parts[0] != "./gradlew" && parts[0] != "gradlew") {
        return Err(format!(
            "Builds must start with `./gradlew` or be `./check <module>`. {}",
            build_help()
        ));
    }
    let mut seen_task = false;
    for arg in &parts[1..] {
        if let Some(flag) = arg.strip_prefix("--") {
            let _ = flag;
            if !ALLOWED_FLAGS.contains(arg) {
                return Err(format!("Flag {arg:?} is not allowed. {}", build_help()));
            }
            continue;
        }
        if let Some(target) = arg.strip_prefix(':') {
            // `:path:task`
            let mut segs: Vec<&str> = target.split(':').collect();
            let task = segs.pop().unwrap_or("");
            let path = segs.join(":");
            if !module_path_ok(&path) {
                return Err(format!("Bad module path {path:?}. {}", build_help()));
            }
            if !task_allowed(task) {
                return Err(format!("Task {task:?} is not allowed. {}", build_help()));
            }
            seen_task = true;
        } else {
            return Err(format!("Unexpected argument {arg:?}. {}", build_help()));
        }
    }
    if !seen_task {
        return Err(format!("No `:path:task` target found. {}", build_help()));
    }
    Ok(cmd.to_string())
}

/// Patterns rejected from `run_shell` (case-insensitive substring match on
/// the command): anything that should go through the `build` tool instead,
/// plus device flashing.
const SHELL_BLOCKED: &[(&str, &str)] = &[
    ("gradlew", "Gradle builds must use the `build` tool, not `run_shell`."),
    ("./check", "Static checks must use the `build` tool (`./check <module>`), not `run_shell`."),
    ("./install", "Installs are not available to agents; verify with the `build` tool instead."),
    ("adb", "`adb` is not available to agents; verify with the `build` tool instead."),
];

fn shell_blocked_cargo(cmd_lower: &str) -> bool {
    // `cargo (build|test|run|check|clippy)` as a command word.
    let mut tokens = cmd_lower
        .split(|c: char| c.is_whitespace() || c == ';' || c == '&' || c == '|' || c == '(')
        .filter(|t| !t.is_empty());
    while let Some(tok) = tokens.next() {
        let tok = tok.trim_matches(|c: char| c == '"' || c == '\'' || c == '(');
        if tok == "cargo" {
            if let Some(sub) = tokens.next() {
                if matches!(sub, "build" | "test" | "run" | "check" | "clippy") {
                    return true;
                }
            }
        }
    }
    false
}

/// Validate a `run_shell` command. `Ok(())` = forward to the runner;
/// `Err(msg)` = return `msg` to the model as the tool result.
pub fn validate_shell_command(cmd: &str) -> Result<(), String> {
    let lower = cmd.to_lowercase();
    for (pat, msg) in SHELL_BLOCKED {
        if lower.contains(pat) {
            return Err(format!("{msg} {}", build_help()));
        }
    }
    if shell_blocked_cargo(&lower) {
        return Err(format!(
            "`cargo build/test/run/check/clippy` must use the `build` tool, not `run_shell`. {}",
            build_help()
        ));
    }
    let _ = HashSet::<u8>::new();
    Ok(())
}

/// Best-effort detector for file-mutating `run_shell` commands, used ONLY by
/// the plan gate (`require_plan && !plan_approved`): when it returns `true`
/// the command is rejected with the "propose a plan first" message.
///
/// This is deliberately conservative and documented as incomplete: a
/// determined model can mutate the tree through allowed commands (e.g.
/// `python3 -c "open(...).write(...)"`, `git apply`, `perl -i`). The gate
/// keeps honest agents on the plan-first path; it is not a sandbox.
///
/// Rejected patterns (case-insensitive substring / token match):
/// * output redirection: `>`, `>>` (also `>|`, `&>`, `2>`);
/// * `sed -i` / `sed --in-place`;
/// * `tee` as a command word;
/// * `mv`, `cp`, `rm`, `touch`, `mkdir`, `ln` as command words;
/// * `git` write subcommands: `add`, `commit`, `checkout`, `apply`, `mv`,
///   `rm`, `clean`, `reset`, `restore`, `revert`, `merge`, `rebase`,
///   `cherry-pick`, `stash`.
pub fn shell_looks_mutating(cmd: &str) -> bool {
    // Redirection operators (cheap substring scan; `>` also covers `>>`,
    // `>|`, `&>`, `2>`).
    if cmd.contains('>') {
        return true;
    }
    let lower = cmd.to_lowercase();
    if lower.contains("sed -i") || lower.contains("sed --in-place") {
        return true;
    }
    // Command-word scan: split on shell separators so `echo hi | tee x`
    // catches `tee` but `guarantee` does not match `tee`.
    let words: Vec<&str> = lower
        .split(|c: char| {
            c.is_whitespace() || matches!(c, ';' | '&' | '|' | '(' | ')' | '<')
        })
        .filter(|t| !t.is_empty())
        .collect();
    let mut words = words.into_iter().peekable();
    while let Some(tok) = words.next() {
        let tok = tok.trim_matches(|c: char| c == '"' || c == '\'' || c == '`');
        if matches!(
            tok,
            "tee" | "mv" | "cp" | "rm" | "touch" | "mkdir" | "ln" | "rmdir" | "unlink"
        ) {
            return true;
        }
        if tok == "git" {
            // Skip flags (`-C dir`) to find the subcommand. Note `lower` is
            // already lowercased, so `-C` arrives as `-c`.
            let mut sub: Option<String> = None;
            while let Some(n) = words.peek().map(|s| s.to_string()) {
                if n.starts_with('-') {
                    words.next();
                    // `-C`/`--git-dir`/`--work-tree` take a value.
                    if n == "-c" || n == "--git-dir" || n == "--work-tree" {
                        words.next();
                    }
                    continue;
                }
                sub = Some(n);
                break;
            }
            if let Some(s) = sub.as_deref() {
                if matches!(
                    s,
                    "add"
                        | "commit"
                        | "checkout"
                        | "switch"
                        | "apply"
                        | "mv"
                        | "rm"
                        | "clean"
                        | "reset"
                        | "restore"
                        | "revert"
                        | "merge"
                        | "rebase"
                        | "cherry-pick"
                        | "cherry"
                        | "stash"
                        | "am"
                ) {
                    return true;
                }
            }
        }
    }
    false
}
