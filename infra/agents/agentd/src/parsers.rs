#![allow(dead_code)]
//! Parsers for build/test output.
//!
//! The harness runs `./gradlew ...` / `./check <module>` in a sandbox and
//! must return compact structured results to the model. This module turns raw
//! stdout/stderr into [`BuildSummary`] / [`TestSummary`] and renders both in
//! a compact model-friendly form (see [`render_build_for_model`] and
//! [`render_tests_for_model`]).
//!
//! Supported inputs (see `src/testdata/parsers/` for real captured samples):
//! - Kotlin compiler `e:` / `w:` lines and detekt console findings (same shape
//!   plus a trailing `[RuleId]`).
//! - javac `path:line: error: msg` lines.
//! - rustc/cargo `error[..]: msg` headers with a following ` --> path:line:col`
//!   location, and `warning:` headers with locations.
//! - Gradle `> Task :x:y FAILED` lines and the `* What went wrong:` block.
//! - Android lint text output (`path:line: Error|Warning: msg [IssueId]`).
//! - The repo's custom `./check` wrapper echoes (module/task lines plus
//!   `Warning: ... reported failures` footers).

use std::collections::HashSet;

// ---------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------

/// Severity of a single diagnostic.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Severity {
    Error,
    Warning,
}

/// Tool that produced a diagnostic.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Tool {
    Kotlin,
    Java,
    Rust,
    Gradle,
    Detekt,
    Lint,
    Other,
}

/// A single compiler / linter / task diagnostic.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Diagnostic {
    /// Error or warning.
    pub severity: Severity,
    /// Tool that produced this diagnostic.
    pub tool: Tool,
    /// Repo-relative path when one could be recovered, else `None`.
    pub file: Option<String>,
    /// 1-based line number, when known.
    pub line: Option<u32>,
    /// 1-based column number, when known.
    pub col: Option<u32>,
    /// Human-readable message.
    pub message: String,
    /// Machine-readable code (`E0308`, `MagicNumber`, `ToastUsage`, ...).
    pub code: Option<String>,
}

/// Summary of one `./gradlew ...` / `./check ...` invocation.
#[derive(Debug, Clone)]
pub struct BuildSummary {
    /// True when the invocation succeeded (exit code 0 and no `FAILED` task).
    pub success: bool,
    /// Gradle task paths that reported `FAILED`, in encounter order.
    pub failed_tasks: Vec<String>,
    /// Text of the `* What went wrong:` block, when present.
    pub what_went_wrong: Option<String>,
    /// Deduplicated diagnostics, errors before warnings, capped.
    pub diagnostics: Vec<Diagnostic>,
    /// Last ~60 lines of combined output.
    pub log_tail: String,
}

/// Maximum diagnostics kept on a [`BuildSummary`].
pub const MAX_DIAGNOSTICS: usize = 50;
/// Maximum failures kept on a [`TestSummary`].
pub const MAX_FAILURES: usize = 20;
/// Lines of combined output kept in [`BuildSummary::log_tail`].
pub const LOG_TAIL_LINES: usize = 60;
/// Lines of a test trace kept in [`TestFailure::trace`].
pub const TRACE_LINES: usize = 15;
/// Rendering budget for [`render_build_for_model`] / [`render_tests_for_model`].
pub const RENDER_BUDGET_BYTES: usize = 6 * 1024;

// ---------------------------------------------------------------------------
// Path handling
// ---------------------------------------------------------------------------

/// Strip everything up to and including a `/builds/slots/slot-N/` marker or
/// the given repo-root prefix, so absolute sandbox paths become repo-relative.
///
/// Returns `None` when `path` is empty; otherwise returns the relativized
/// path (or the original when neither marker matches).
pub fn relativize(path: &str, repo_root: &str) -> Option<String> {
    let path = path.trim();
    if path.is_empty() {
        return None;
    }
    // Sandbox slot marker: /builds/slots/slot-3/<repo-path...>.
    if let Some(idx) = path.find("/builds/slots/slot-") {
        let rest = &path[idx + "/builds/slots/".len()..];
        if let Some(slash) = rest.find('/') {
            let rel = rest[slash + 1..].trim_start_matches('/');
            if !rel.is_empty() {
                return Some(rel.to_string());
            }
        }
    }
    let root = repo_root.trim_end_matches('/');
    if !root.is_empty() {
        if let Some(stripped) = path.strip_prefix(root) {
            let rel = stripped.trim_start_matches('/');
            if !rel.is_empty() {
                return Some(rel.to_string());
            }
        }
        // `file://` URIs embed the absolute path; compare without the scheme.
        if let Some(without_scheme) = path.strip_prefix("file://") {
            if let Some(stripped) = without_scheme.strip_prefix(root) {
                let rel = stripped.trim_start_matches('/');
                if !rel.is_empty() {
                    return Some(rel.to_string());
                }
            }
        }
    }
    Some(path.to_string())
}

/// Split `path:line:col` / `path:line` suffixes off a location string.
///
/// Returns the path part plus optional line/column. Only trailing numeric
/// components are consumed, so Windows drive prefixes and ports are safe.
fn split_location(s: &str) -> (&str, Option<u32>, Option<u32>) {
    let s = s.trim();
    let mut parts: Vec<&str> = s.rsplitn(3, ':').collect();
    // rsplitn yields tail-first; reverse for head-first handling below.
    parts.reverse();
    match parts.as_slice() {
        [path, line, col] => {
            if let (Ok(line), Ok(col)) = (line.parse::<u32>(), col.parse::<u32>()) {
                return (path, Some(line), Some(col));
            }
            if let Ok(line) = line.parse::<u32>() {
                // `path:line:non-numeric` — keep col absent, path keeps suffix.
                let path = &s[..s.len() - col.len() - 1];
                return (path, Some(line), None);
            }
            (s, None, None)
        }
        [path, line] => {
            if let Ok(line) = line.parse::<u32>() {
                return (path, Some(line), None);
            }
            (s, None, None)
        }
        _ => (s, None, None),
    }
}

fn parse_u32(s: &str) -> Option<u32> {
    s.trim().parse::<u32>().ok()
}

// ---------------------------------------------------------------------------
// Build output parsing
// ---------------------------------------------------------------------------

/// Parse one Gradle/Kotlin/Java/Rust/detekt/lint/`check` invocation.
///
/// `stdout` and `stderr` are the captured streams, `exit_code` the process
/// exit code (`None` when unknown, e.g. killed), and `repo_root` the repo
/// checkout prefix used to relativize absolute file paths.
pub fn parse_build_output(
    stdout: &str,
    stderr: &str,
    exit_code: Option<i32>,
    repo_root: &str,
) -> BuildSummary {
    let mut failed_tasks: Vec<String> = Vec::new();
    let mut diagnostics: Vec<Diagnostic> = Vec::new();

    let combined = format!("{stdout}\n{stderr}");
    let lines: Vec<&str> = combined.lines().collect();

    // Pass 1: line-oriented diagnostics (kotlinc/detekt, javac, lint, rustc
    // headers) plus `> Task ... FAILED` markers.
    let mut i = 0;
    while i < lines.len() {
        let line = lines[i];
        let trimmed = line.trim();
        if let Some(task) = parse_failed_task(trimmed) {
            if !failed_tasks.contains(&task) {
                failed_tasks.push(task);
            }
        } else if let Some(d) = parse_kotlin_or_detekt(trimmed, repo_root) {
            diagnostics.push(d);
        } else if let Some(d) = parse_javac(trimmed, repo_root) {
            diagnostics.push(d);
        } else if let Some(d) = parse_lint_text(trimmed, repo_root) {
            diagnostics.push(d);
        } else if let Some((severity, code, message)) = parse_rustc_header(trimmed) {
            // rustc location follows on a ` --> path:line:col` line; scan a
            // few lines ahead so multi-line headers still attach.
            let mut location: Option<(String, Option<u32>, Option<u32>)> = None;
            for ahead in lines.iter().skip(i + 1).take(6) {
                let t = ahead.trim();
                if let Some(rest) = t.strip_prefix("--> ") {
                    let (path, line_no, col) = split_location(rest);
                    location = Some((path.to_string(), line_no, col));
                    break;
                }
                // A new diagnostic header ends the search.
                if parse_rustc_header(t).is_some()
                    || parse_kotlin_or_detekt(t, "").is_some()
                    || t.starts_with("error: could not compile")
                {
                    break;
                }
            }
            let (file, line_no, col) = match location {
                Some((p, l, c)) => (relativize(&p, repo_root), l, c),
                None => (None, None, None),
            };
            diagnostics.push(Diagnostic {
                severity,
                tool: Tool::Rust,
                file,
                line: line_no,
                col,
                message,
                code,
            });
        } else if trimmed.starts_with("error: could not compile") {
            diagnostics.push(Diagnostic {
                severity: Severity::Error,
                tool: Tool::Rust,
                file: None,
                line: None,
                col: None,
                message: trimmed.to_string(),
                code: None,
            });
        }
        i += 1;
    }

    // Pass 2: the `* What went wrong:` block (ends at `* Try:` or blank-adjacent header).
    let what_went_wrong = extract_what_went_wrong(&lines);

    // Success: exit code 0 (or unknown-but-no-failure-signal) with no FAILED task.
    let mut success = failed_tasks.is_empty() && what_went_wrong.is_none();
    if let Some(code) = exit_code {
        success = code == 0 && failed_tasks.is_empty();
    }

    // If the build failed but no diagnostic was recovered, surface the
    // `* What went wrong:` text (or the tail) as a Gradle diagnostic so the
    // model still gets something actionable.
    if !success && diagnostics.is_empty() {
        if let Some(block) = what_went_wrong.clone() {
            let first = block.lines().next().unwrap_or(&block).trim().to_string();
            if !first.is_empty() {
                diagnostics.push(Diagnostic {
                    severity: Severity::Error,
                    tool: Tool::Gradle,
                    file: None,
                    line: None,
                    col: None,
                    message: first,
                    code: None,
                });
            }
        }
    }

    diagnostics = dedup_sort_cap(diagnostics);

    let tail: Vec<&str> = combined.lines().rev().take(LOG_TAIL_LINES).collect();
    let tail: Vec<&str> = tail.into_iter().rev().collect();
    let log_tail = tail.join("\n");

    BuildSummary {
        success,
        failed_tasks,
        what_went_wrong,
        diagnostics,
        log_tail,
    }
}

/// `> Task :calculator:compileDevKotlin FAILED` -> `Some(":calculator:compileDevKotlin")`.
fn parse_failed_task(trimmed: &str) -> Option<String> {
    let rest = trimmed.strip_prefix("> Task ")?;
    let task = rest.strip_suffix(" FAILED")?;
    if task.is_empty() || task.contains(' ') {
        return None;
    }
    Some(task.to_string())
}

/// kotlinc (`e:`/`w:`) and detekt console lines.
///
/// kotlinc: `e: file:///abs/File.kt:12:5 message`
/// detekt:  `w: /abs/File.kt:27:5 message. [RuleId]`
fn parse_kotlin_or_detekt(trimmed: &str, repo_root: &str) -> Option<Diagnostic> {
    let (severity, rest) = if let Some(rest) = trimmed.strip_prefix("e: ") {
        (Severity::Error, rest)
    } else if let Some(rest) = trimmed.strip_prefix("w: ") {
        (Severity::Warning, rest)
    } else {
        return None;
    };
    // detekt appends ` [RuleId]`; kotlinc messages never end with that shape
    // (bracketed single token, no spaces) — treat it as the code.
    let (rest, detekt_code) = match rest.rfind(" [") {
        Some(idx) if rest.ends_with(']') => {
            let candidate = &rest[idx + 2..rest.len() - 1];
            if !candidate.is_empty() && !candidate.contains([' ', '\t', ':']) {
                (rest[..idx].trim_end(), Some(candidate.to_string()))
            } else {
                (rest, None)
            }
        }
        _ => (rest, None),
    };
    // Location is `path:line:col message...`; the path itself may contain
    // colons (URI scheme), so scan for the first `:line:col ` numeric pair.
    let (path, line_no, col, message) = split_prefixed_location(rest)?;
    let tool = if detekt_code.is_some() {
        Tool::Detekt
    } else {
        Tool::Kotlin
    };
    Some(Diagnostic {
        severity,
        tool,
        file: relativize(path, repo_root),
        line: line_no,
        col,
        message: message.to_string(),
        code: detekt_code,
    })
}

/// Scan `path:line:col message` / `path:line message` where path may contain
/// colons (e.g. `file:///...`). Returns path, line, col, message.
fn split_prefixed_location(s: &str) -> Option<(&str, Option<u32>, Option<u32>, &str)> {
    // Try line+col first, then line only; the message follows a space.
    let bytes = s.as_bytes();
    let mut colon_positions: Vec<usize> = Vec::new();
    for (idx, b) in bytes.iter().enumerate() {
        if *b == b':' {
            colon_positions.push(idx);
        }
    }
    for &first in &colon_positions {
        let after_first = &s[first + 1..];
        // line digits up to next colon or space.
        let mut line_end = after_first.len();
        for (idx, b) in after_first.bytes().enumerate() {
            if !b.is_ascii_digit() {
                line_end = idx;
                break;
            }
        }
        if line_end == 0 {
            continue;
        }
        let line_no = parse_u32(&after_first[..line_end])?;
        let rest = &after_first[line_end..];
        if let Some(after_colon) = rest.strip_prefix(':') {
            let mut col_end = after_colon.len();
            for (idx, b) in after_colon.bytes().enumerate() {
                if !b.is_ascii_digit() {
                    col_end = idx;
                    break;
                }
            }
            if col_end > 0 && after_colon[col_end..].starts_with(' ') {
                let col = parse_u32(&after_colon[..col_end])?;
                let message = after_colon[col_end..].trim_start();
                if message.is_empty() {
                    continue;
                }
                return Some((s[..first].trim(), Some(line_no), Some(col), message));
            }
        }
        if rest.starts_with(' ') {
            let message = rest.trim_start();
            if message.is_empty() {
                continue;
            }
            return Some((s[..first].trim(), Some(line_no), None, message));
        }
    }
    None
}

/// javac: `/abs/File.java:12: error: message` (also `warning:`).
fn parse_javac(trimmed: &str, repo_root: &str) -> Option<Diagnostic> {
    // Find `: error: ` / `: warning: ` separator; the head must end with a line number.
    for marker in [": error: ", ": warning: "] {
        if let Some(idx) = trimmed.find(marker) {
            let (head, message) = trimmed.split_at(idx);
            let message = message[marker.len() - 1..].trim_start_matches(": ").trim();
            let severity = if marker.contains("error") {
                Severity::Error
            } else {
                Severity::Warning
            };
            // Head is `path:line` possibly with `path:line:col`? javac prints
            // `path:line: error:` — head ends `:line`.
            if let Some(colon) = head.rfind(':') {
                let (path, line_s) = head.split_at(colon);
                if let Some(line_no) = parse_u32(&line_s[1..]) {
                    return Some(Diagnostic {
                        severity,
                        tool: Tool::Java,
                        file: relativize(path.trim(), repo_root),
                        line: Some(line_no),
                        col: None,
                        message: message.to_string(),
                        code: None,
                    });
                }
            }
        }
    }
    None
}

/// Android lint text output: `path:line: Error: msg [IssueId]`
/// (also `Warning:`, optional `:col`, optional `[Id]`, optional
/// ` from vendor` suffix on the id).
fn parse_lint_text(trimmed: &str, repo_root: &str) -> Option<Diagnostic> {
    for marker in [": Error: ", ": Warning: "] {
        if let Some(idx) = trimmed.find(marker) {
            let (head, message) = trimmed.split_at(idx);
            let mut message = message[marker.len() - 1..].trim_start_matches(": ").trim();
            let severity = if marker.contains("Error") {
                Severity::Error
            } else {
                Severity::Warning
            };
            // Trailing `[IssueId]` or `[IssueId from vendor]`.
            let mut code: Option<String> = None;
            if message.ends_with(']') {
                if let Some(open) = message.rfind(" [") {
                    let id_part = &message[open + 2..message.len() - 1];
                    let id = id_part.split_whitespace().next().unwrap_or("");
                    if !id.is_empty() && !id.contains(':') {
                        code = Some(id.to_string());
                    }
                    message = message[..open].trim_end();
                }
            }
            let (path, line_no, col) = split_location(head);
            if line_no.is_none() {
                continue;
            }
            // Guard: lint lines are bare paths, not `e:`/`w:` prefixed and not
            // rustc headers — callers try lint after those, so just check the
            // head looks like a file path.
            if path.is_empty() || path.contains("://") && !path.starts_with('/') && !path.starts_with("file:") {
                return None;
            }
            return Some(Diagnostic {
                severity,
                tool: Tool::Lint,
                file: relativize(path, repo_root),
                line: line_no,
                col,
                message: message.to_string(),
                code,
            });
        }
    }
    None
}

/// rustc/cargo header: `error[E0308]: msg`, `error: msg`, `warning: msg`.
/// Returns severity, optional code, message.
fn parse_rustc_header(trimmed: &str) -> Option<(Severity, Option<String>, String)> {
    let (severity, rest) = if let Some(rest) = trimmed.strip_prefix("error") {
        (Severity::Error, rest)
    } else if let Some(rest) = trimmed.strip_prefix("warning") {
        (Severity::Warning, rest)
    } else {
        return None;
    };
    // rustc emits both `error: msg` and `error[E0308]: msg`; reject words
    // like `errorLine1=`.
    let (e_code, rest) = if let Some(after_bracket) = rest.strip_prefix('[') {
        // `error[E0308]: msg` — skip to the closing bracket, require `:` after.
        let end = after_bracket.find(']')?;
        let code = after_bracket[..end].trim().to_string();
        let tail = after_bracket[end + 1..].strip_prefix(':')?;
        let code = if code.is_empty() { None } else { Some(code) };
        (code, tail)
    } else {
        (None, rest.strip_prefix(':')?)
    };
    let rest = rest.trim_start();
    // Optional `[unused_imports]`-style code prefix (E-codes consumed above).
    let (code, message) = if let Some(after_bracket) = rest.strip_prefix('[') {
        if let Some(end) = after_bracket.find(']') {
            let code = after_bracket[..end].trim();
            let msg = after_bracket[end + 1..].trim_start_matches(':').trim();
            let code = if code.is_empty() {
                None
            } else {
                Some(code.to_string())
            };
            (code, msg.to_string())
        } else {
            (None, rest.to_string())
        }
    } else {
        (None, rest.to_string())
    };
    let code = e_code.or(code);
    if message.is_empty() {
        return None;
    }
    Some((severity, code, message))
}

/// Extract the `* What went wrong:` block (up to `* Try:` or the next
/// `* `-header / blank-line-then-header).
fn extract_what_went_wrong(lines: &[&str]) -> Option<String> {
    let start = lines
        .iter()
        .position(|l| l.trim() == "* What went wrong:")?;
    let mut block: Vec<&str> = Vec::new();
    for line in lines.iter().skip(start + 1) {
        let trimmed = line.trim();
        if trimmed == "* Try:" {
            break;
        }
        // A new `* Header:` ends the block (but `> ...` detail lines belong).
        if trimmed.starts_with("* ") && trimmed.ends_with(':') {
            break;
        }
        block.push(line);
    }
    // Trim leading/trailing blank lines.
    while block.first().is_some_and(|l| l.trim().is_empty()) {
        block.remove(0);
    }
    while block.last().is_some_and(|l| l.trim().is_empty()) {
        block.pop();
    }
    // De-indent the common `> ` / two-space Gradle continuation prefix.
    let cleaned: Vec<String> = block
        .iter()
        .map(|l| {
            let t = l.trim_start();
            t.strip_prefix("> ").unwrap_or(t).to_string()
        })
        .collect();
    let text = cleaned.join("\n").trim().to_string();
    if text.is_empty() {
        None
    } else {
        Some(text)
    }
}

/// Dedup (exact), errors before warnings, stable, capped at [`MAX_DIAGNOSTICS`].
fn dedup_sort_cap(mut diagnostics: Vec<Diagnostic>) -> Vec<Diagnostic> {
    let mut seen: HashSet<Diagnostic> = HashSet::new();
    diagnostics.retain(|d| seen.insert(d.clone()));
    diagnostics.sort_by_key(|d| match d.severity {
        Severity::Error => 0,
        Severity::Warning => 1,
    });
    diagnostics.truncate(MAX_DIAGNOSTICS);
    diagnostics
}

// ---------------------------------------------------------------------------
// JUnit XML
// ---------------------------------------------------------------------------

/// A single failed or errored test case.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TestFailure {
    /// Fully-qualified class name.
    pub class: String,
    /// Test method name.
    pub name: String,
    /// Short failure message (`message` attribute or first line).
    pub message: String,
    /// First ~15 lines of the stack trace / failure body.
    pub trace: String,
}

/// Summary across one or more JUnit XML reports.
#[derive(Debug, Clone, Default)]
pub struct TestSummary {
    /// Passed test cases.
    pub passed: u32,
    /// Failed test cases (`<failure>`).
    pub failed: u32,
    /// Skipped test cases.
    pub skipped: u32,
    /// Errored test cases (`<error>`).
    pub errors: u32,
    /// Failure details, capped at [`MAX_FAILURES`].
    pub failures: Vec<TestFailure>,
}

/// Parse one JUnit XML report (Gradle `TEST-*.xml` shape).
///
/// Hand-rolled attribute scanner over `testsuite` / `testcase` / `failure` /
/// `error` / `skipped` tags — no XML dependency, tolerant of entity escapes
/// (`&lt;` etc.) and CDATA-ish bodies. Returns `Err` with a message when the
/// document has no parseable `testsuite`.
pub fn parse_junit_xml(xml: &str) -> Result<TestSummary, String> {
    let mut summary = TestSummary::default();

    if find_open_tag(xml, "testsuite").is_none() {
        return Err("no <testsuite> element found".to_string());
    }

    let mut rest = xml;
    // Walk each <testcase ...>...</testcase> or self-closed <testcase .../>.
    while let Some(case_start) = rest.find("<testcase") {
        let after = &rest[case_start..];
        let tag_end = after.find('>').ok_or("malformed <testcase> tag")?;
        let open_tag = &after[..tag_end];
        let self_closed = open_tag.trim_end().ends_with('/');
        let attrs = parse_attrs(open_tag);
        let name = attrs.get("name").cloned().unwrap_or_default();
        let class = attrs
            .get("classname")
            .cloned()
            .or_else(|| attrs.get("class").cloned())
            .unwrap_or_default();

        let (body, consumed) = if self_closed {
            ("", tag_end + 1)
        } else if let Some(close) = after.find("</testcase>") {
            (&after[tag_end + 1..close], close + "</testcase>".len())
        } else {
            return Err(format!("unclosed <testcase> for {name:?}"));
        };

        if body.contains("<skipped") {
            summary.skipped += 1;
        } else if let Some((kind, failure)) = parse_failure_body(body, &class, &name) {
            match kind {
                FailureKind::Failure => summary.failed += 1,
                FailureKind::Error => summary.errors += 1,
            }
            if summary.failures.len() < MAX_FAILURES {
                summary.failures.push(failure);
            }
        } else {
            summary.passed += 1;
        }
        rest = &after[consumed..];
    }

    // Adopt suite-level totals when the report enumerates no cases but
    // carries counters (e.g. aggregated reports).
    if summary.passed + summary.failed + summary.skipped + summary.errors == 0 {
        if let Some(tag) = find_open_tag(xml, "testsuite") {
            let attrs = parse_attrs(&tag);
            let tests = attrs.get("tests").and_then(|s| s.parse::<u32>().ok());
            let failures = attrs.get("failures").and_then(|s| s.parse::<u32>().ok());
            let errors = attrs.get("errors").and_then(|s| s.parse::<u32>().ok());
            let skipped = attrs.get("skipped").and_then(|s| s.parse::<u32>().ok());
            if tests.is_some() || failures.is_some() || errors.is_some() || skipped.is_some() {
                summary.failed = failures.unwrap_or(0);
                summary.errors = errors.unwrap_or(0);
                summary.skipped = skipped.unwrap_or(0);
                summary.passed = tests
                    .unwrap_or(0)
                    .saturating_sub(summary.failed + summary.errors + summary.skipped);
            }
        }
    }

    Ok(summary)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum FailureKind {
    Failure,
    Error,
}

/// Extract `<failure>` / `<error>` from a testcase body.
fn parse_failure_body(body: &str, class: &str, name: &str) -> Option<(FailureKind, TestFailure)> {
    for (tag, kind) in [("failure", FailureKind::Failure), ("error", FailureKind::Error)] {
        if let Some(start) = body.find(&format!("<{tag}")) {
            let after = &body[start..];
            let tag_end = after.find('>')?;
            let open_tag = &after[..tag_end];
            let self_closed = open_tag.trim_end().ends_with('/');
            let attrs = parse_attrs(open_tag);
            let close_tag = format!("</{tag}>");
            let inner = if self_closed {
                String::new()
            } else if let Some(close) = after.find(&close_tag) {
                after[tag_end + 1..close].to_string()
            } else {
                String::new()
            };
            let inner = xml_unescape(&inner);
            let message = attrs
                .get("message")
                .map(|m| xml_unescape(m))
                .filter(|m| !m.trim().is_empty())
                .unwrap_or_else(|| inner.lines().next().unwrap_or("").trim().to_string());
            let trace: Vec<&str> = inner.lines().take(TRACE_LINES).collect();
            return Some((
                kind,
                TestFailure {
                    class: class.to_string(),
                    name: name.to_string(),
                    message,
                    trace: trace.join("\n"),
                },
            ));
        }
    }
    None
}

/// Merge several [`TestSummary`] values (one per XML file) into one.
/// Counters add up; failure lists concatenate, capped at [`MAX_FAILURES`].
pub fn merge_test_summaries(summaries: &[TestSummary]) -> TestSummary {
    let mut merged = TestSummary::default();
    for s in summaries {
        merged.passed += s.passed;
        merged.failed += s.failed;
        merged.skipped += s.skipped;
        merged.errors += s.errors;
        for f in &s.failures {
            if merged.failures.len() >= MAX_FAILURES {
                break;
            }
            merged.failures.push(f.clone());
        }
    }
    merged
}

// --- minimal XML helpers (attribute/tag scanning, entity unescaping) ---

/// Find the first opening `<name ...>` tag text (without closing `>`).
fn find_open_tag(xml: &str, name: &str) -> Option<String> {
    let open = format!("<{name}");
    let start = xml.find(&open)?;
    let after = &xml[start..];
    // Reject `<testsuites>` when looking for `<testsuite`.
    let next = after[open.len()..].chars().next()?;
    if !(next == ' ' || next == '\t' || next == '\n' || next == '\r' || next == '/' || next == '>')
    {
        // Search past this false hit.
        return find_open_tag(&after[open.len()..], name);
    }
    let end = after.find('>')?;
    Some(after[..end].to_string())
}

/// Parse `key="value"` (double or single quotes) pairs from a tag string.
fn parse_attrs(tag: &str) -> std::collections::HashMap<String, String> {
    let mut attrs = std::collections::HashMap::new();
    let bytes = tag.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        // Skip to attribute name start.
        while i < bytes.len() && !(bytes[i].is_ascii_alphabetic() || bytes[i] == b'_') {
            i += 1;
        }
        let name_start = i;
        while i < bytes.len() && (bytes[i].is_ascii_alphanumeric() || bytes[i] == b'_' || bytes[i] == b'-' || bytes[i] == b':' || bytes[i] == b'.') {
            i += 1;
        }
        if i == name_start {
            break;
        }
        let name = &tag[name_start..i];
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        if i >= bytes.len() || bytes[i] != b'=' {
            continue;
        }
        i += 1;
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        if i >= bytes.len() {
            break;
        }
        let quote = bytes[i];
        if quote != b'"' && quote != b'\'' {
            continue;
        }
        i += 1;
        let value_start = i;
        while i < bytes.len() && bytes[i] != quote {
            i += 1;
        }
        let value = tag[value_start..i.min(bytes.len())].to_string();
        if i < bytes.len() {
            i += 1;
        }
        if name != "testcase"
            && name != "testsuite"
            && name != "failure"
            && name != "error"
            && name != "skipped"
            && name != "system-out"
            && name != "system-err"
            && name != "properties"
        {
            attrs.insert(name.to_string(), value);
        }
    }
    attrs
}

/// Unescape the five predefined XML entities plus numeric character refs.
fn xml_unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(amp) = rest.find('&') {
        out.push_str(&rest[..amp]);
        let tail = &rest[amp..];
        let semi = tail.find(';');
        match semi {
            Some(end) if end < 12 => {
                let entity = &tail[..end + 1];
                match entity {
                    "&lt;" => out.push('<'),
                    "&gt;" => out.push('>'),
                    "&amp;" => out.push('&'),
                    "&quot;" => out.push('"'),
                    "&apos;" => out.push('\''),
                    _ if entity.starts_with("&#") => {
                        let num = &entity[2..entity.len() - 1];
                        let code = if let Some(hex) = num.strip_prefix('x').or_else(|| num.strip_prefix('X')) {
                            u32::from_str_radix(hex, 16).ok()
                        } else {
                            num.parse::<u32>().ok()
                        };
                        match code.and_then(char::from_u32) {
                            Some(ch) => out.push(ch),
                            None => out.push_str(entity),
                        }
                    }
                    _ => out.push_str(entity),
                }
                rest = &tail[end + 1..];
            }
            _ => {
                out.push('&');
                rest = &tail[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

// ---------------------------------------------------------------------------
// Model rendering
// ---------------------------------------------------------------------------

fn tool_label(tool: Tool) -> &'static str {
    match tool {
        Tool::Kotlin => "kotlinc",
        Tool::Java => "javac",
        Tool::Rust => "rustc",
        Tool::Gradle => "gradle",
        Tool::Detekt => "detekt",
        Tool::Lint => "lint",
        Tool::Other => "other",
    }
}

fn format_diagnostic(d: &Diagnostic) -> String {
    let sev = match d.severity {
        Severity::Error => "error",
        Severity::Warning => "warning",
    };
    let loc = match (&d.file, d.line, d.col) {
        (Some(f), Some(l), Some(c)) => format!("{f}:{l}:{c}"),
        (Some(f), Some(l), None) => format!("{f}:{l}"),
        (Some(f), None, _) => f.clone(),
        (None, _, _) => tool_label(d.tool).to_string(),
    };
    let code = d
        .code
        .as_ref()
        .map(|c| format!(" [{c}]"))
        .unwrap_or_default();
    // Keep one-line: collapse internal newlines in the message.
    let message: Vec<&str> = d.message.split_whitespace().collect();
    format!("{loc}: {sev}{code}: {}", message.join(" "))
}

fn truncate_to_budget(s: &str, budget: usize) -> String {
    if s.len() <= budget {
        return s.to_string();
    }
    // Cut at a line boundary below the budget when possible.
    let mut end = budget;
    while end > 0 && !s.is_char_boundary(end) {
        end -= 1;
    }
    let cut = s[..end].rfind('\n').unwrap_or(end).min(s.len());
    let mut out = s[..cut].to_string();
    out.push_str("\n…(truncated)");
    out
}

/// Compact model-friendly rendering of a [`BuildSummary`].
///
/// Status line, failed tasks, diagnostics (`file:line:col: error: msg`),
/// what-went-wrong, then the tail only when no diagnostics were found.
/// Kept under ~6 KB.
pub fn render_build_for_model(summary: &BuildSummary) -> String {
    let mut out = String::new();
    out.push_str(if summary.success {
        "build: OK\n"
    } else {
        "build: FAILED\n"
    });
    if !summary.failed_tasks.is_empty() {
        out.push_str("failed tasks:\n");
        for task in &summary.failed_tasks {
            out.push_str(&format!("  {task}\n"));
        }
    }
    let mut errors = 0;
    let mut warnings = 0;
    for d in &summary.diagnostics {
        match d.severity {
            Severity::Error => errors += 1,
            Severity::Warning => warnings += 1,
        }
    }
    if errors + warnings > 0 {
        out.push_str(&format!("diagnostics ({errors} errors, {warnings} warnings):\n"));
        for d in &summary.diagnostics {
            out.push_str(&format!("  {}\n", format_diagnostic(d)));
        }
    }
    if let Some(block) = &summary.what_went_wrong {
        out.push_str("what went wrong:\n");
        for line in block.lines().take(10) {
            out.push_str(&format!("  {line}\n"));
        }
    }
    if summary.diagnostics.is_empty() && !summary.log_tail.trim().is_empty() {
        out.push_str("tail:\n");
        let lines: Vec<&str> = summary.log_tail.lines().collect();
        let start = lines.len().saturating_sub(20);
        for line in &lines[start..] {
            out.push_str(&format!("  {line}\n"));
        }
    }
    truncate_to_budget(&out, RENDER_BUDGET_BYTES)
}

/// Compact model-friendly rendering of a [`TestSummary`]. Kept under ~6 KB.
pub fn render_tests_for_model(summary: &TestSummary) -> String {
    let mut out = String::new();
    let total = summary.passed + summary.failed + summary.skipped + summary.errors;
    if summary.failed == 0 && summary.errors == 0 {
        out.push_str(&format!(
            "tests: OK ({total} passed, {} skipped)\n",
            summary.skipped
        ));
    } else {
        out.push_str(&format!(
            "tests: FAILED ({} passed, {} failed, {} errors, {} skipped, {} total)\n",
            summary.passed, summary.failed, summary.errors, summary.skipped, total
        ));
        out.push_str("failures:\n");
        for f in &summary.failures {
            out.push_str(&format!("  {}.{}: {}\n", f.class, f.name, single_line(&f.message, 300)));
            for line in f.trace.lines().skip(1).take(5) {
                let line = line.trim();
                if !line.is_empty() {
                    out.push_str(&format!("    {line}\n"));
                }
            }
        }
    }
    truncate_to_budget(&out, RENDER_BUDGET_BYTES)
}

fn single_line(s: &str, max: usize) -> String {
    let joined: Vec<&str> = s.split_whitespace().collect();
    let joined = joined.join(" ");
    if joined.len() <= max {
        joined
    } else {
        let mut end = max;
        while end > 0 && !joined.is_char_boundary(end) {
            end -= 1;
        }
        format!("{}…", &joined[..end])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn testdata(name: &str) -> (String, String) {
        let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        dir.push("src");
        dir.push("testdata");
        dir.push("parsers");
        let read_opt = |suffix: &str| {
            let mut p = dir.clone();
            p.push(format!("{name}.{suffix}"));
            std::fs::read_to_string(&p).unwrap_or_default()
        };
        (read_opt("stdout.txt"), read_opt("stderr.txt"))
    }

    fn xml_fixture(name: &str) -> String {
        let mut p = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        p.push("src");
        p.push("testdata");
        p.push("parsers");
        p.push(name);
        std::fs::read_to_string(&p).unwrap_or_else(|e| panic!("missing fixture {name}: {e}"))
    }

    const ROOT: &str = "/Users/vayun/Documents/Modern-Apps-wt/isess-parsers";

    #[test]
    fn kotlin_error_is_parsed() {
        let (out, err) = testdata("kotlin_error");
        let summary = parse_build_output(&out, &err, Some(1), ROOT);
        assert!(!summary.success);
        assert_eq!(summary.failed_tasks, vec![":calculator:compileDevKotlin"]);
        assert!(summary.what_went_wrong.is_some());
        let diag = summary
            .diagnostics
            .iter()
            .find(|d| d.tool == Tool::Kotlin && d.severity == Severity::Error)
            .expect("kotlin error diagnostic");
        assert_eq!(
            diag.file.as_deref(),
            Some("calculator/src/main/java/com/vayunmathur/calculator/util/Units.kt")
        );
        assert_eq!(diag.line, Some(801));
        assert_eq!(diag.col, Some(30));
        assert!(diag.message.contains("Initializer type mismatch"));
    }

    #[test]
    fn kotlin_success_has_no_errors() {
        let (out, err) = testdata("kotlin_success");
        let summary = parse_build_output(&out, &err, Some(0), ROOT);
        assert!(summary.success);
        assert!(summary.failed_tasks.is_empty());
        // A clean build still carries kotlinc deprecation warnings from
        // upstream modules — success means no *errors*, not no diagnostics.
        assert!(summary
            .diagnostics
            .iter()
            .all(|d| d.severity == Severity::Warning));
        let deprecation = summary
            .diagnostics
            .iter()
            .find(|d| d.tool == Tool::Kotlin && d.message.contains("deprecated"))
            .expect("deprecation warning");
        assert_eq!(
            deprecation.file.as_deref(),
            Some("sdk/games/src/main/java/com/vayunmathur/sdk/games/GameHubClient.kt")
        );
        assert_eq!(deprecation.line, Some(197));
        assert!(summary.what_went_wrong.is_none());
        assert!(summary.log_tail.contains("BUILD SUCCESSFUL"));
    }

    #[test]
    fn unittest_failure_console_is_parsed() {
        let (out, err) = testdata("unittest_failure");
        let summary = parse_build_output(&out, &err, Some(1), ROOT);
        assert!(!summary.success);
        assert_eq!(summary.failed_tasks, vec![":calculator:testDevUnitTest"]);
        let block = summary.what_went_wrong.expect("what went wrong");
        assert!(block.contains("There were failing tests"));
    }

    #[test]
    fn rustc_error_and_warning_are_parsed() {
        let (out, err) = testdata("rustc_error");
        let summary = parse_build_output(&out, &err, Some(101), ROOT);
        assert!(!summary.success);
        assert_eq!(
            summary.failed_tasks,
            vec![":euicc:cargoBuild_euicc_arm64_v8a"]
        );
        let error = summary
            .diagnostics
            .iter()
            .find(|d| d.tool == Tool::Rust && d.severity == Severity::Error)
            .expect("rustc error");
        assert_eq!(error.code.as_deref(), Some("E0308"));
        assert_eq!(error.file.as_deref(), Some("euicc/src/main/rust/src/base64.rs"));
        assert_eq!(error.line, Some(96));
        assert_eq!(error.col, Some(37));
        let warning = summary
            .diagnostics
            .iter()
            .find(|d| {
                d.tool == Tool::Rust
                    && d.severity == Severity::Warning
                    && d.message.contains("unused imports")
            })
            .expect("rustc warning");
        assert_eq!(
            warning.file.as_deref(),
            Some("euicc/src/main/rust/src/download.rs")
        );
        assert_eq!(warning.line, Some(14));
        assert_eq!(warning.col, Some(16));
        // Errors sort before warnings.
        let first_error = summary
            .diagnostics
            .iter()
            .position(|d| d.severity == Severity::Error)
            .unwrap();
        let first_warning = summary
            .diagnostics
            .iter()
            .position(|d| d.severity == Severity::Warning)
            .unwrap();
        assert!(first_error < first_warning);
    }

    #[test]
    fn detekt_findings_are_parsed() {
        let (out, err) = testdata("detekt");
        let summary = parse_build_output(&out, &err, Some(1), ROOT);
        assert!(!summary.success);
        assert_eq!(summary.failed_tasks, vec![":calculator:detekt"]);
        let magic = summary
            .diagnostics
            .iter()
            .find(|d| d.code.as_deref() == Some("MagicNumber"))
            .expect("MagicNumber finding");
        assert_eq!(magic.tool, Tool::Detekt);
        assert_eq!(magic.severity, Severity::Error);
        assert_eq!(
            magic.file.as_deref(),
            Some("calculator/src/main/java/com/vayunmathur/calculator/widget/CalculatorKeyAction.kt")
        );
        assert_eq!(magic.line, Some(132));
        assert!(summary.diagnostics.iter().any(|d| d.tool == Tool::Detekt
            && d.code.as_deref() == Some("UndocumentedPublicClass")));
    }

    #[test]
    fn lint_error_is_parsed() {
        let (out, err) = testdata("lint_error");
        let summary = parse_build_output(&out, &err, Some(1), ROOT);
        assert!(!summary.success);
        assert!(summary.failed_tasks.contains(&":calculator:lintDev".to_string()));
        let toast = summary
            .diagnostics
            .iter()
            .find(|d| d.code.as_deref() == Some("ToastUsage"))
            .expect("ToastUsage finding");
        assert_eq!(toast.tool, Tool::Lint);
        assert_eq!(toast.severity, Severity::Error);
        assert_eq!(
            toast.file.as_deref(),
            Some("calculator/src/main/java/com/vayunmathur/calculator/util/UnitsModel.kt")
        );
        assert_eq!(toast.line, Some(26));
    }

    #[test]
    fn check_wrapper_output_is_parsed() {
        let (out, err) = testdata("check_calculator");
        let summary = parse_build_output(&out, &err, Some(1), ROOT);
        assert!(!summary.success);
        assert!(summary.failed_tasks.iter().any(|t| t.contains("detekt")));
        assert!(!summary.diagnostics.is_empty());
    }

    #[test]
    fn synthetic_javac_shapes_are_parsed() {
        // SYNTHETIC: repo is all-Kotlin; javac errors do not occur naturally.
        let mut p = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        p.push("src");
        p.push("testdata");
        p.push("parsers");
        p.push("javac_error.synthetic.txt");
        let combined = std::fs::read_to_string(&p).expect("synthetic javac fixture");
        // Fixture file carries a `#` header; parse body after it.
        let body: Vec<&str> = combined
            .lines()
            .filter(|l| !l.starts_with('#'))
            .collect();
        let body = body.join("\n");
        let summary = parse_build_output(&body, "", Some(1), ROOT);
        assert!(!summary.success);
        let error = summary
            .diagnostics
            .iter()
            .find(|d| d.tool == Tool::Java && d.severity == Severity::Error)
            .expect("javac error");
        assert_eq!(
            error.file.as_deref(),
            Some("library/src/main/java/com/vayunmathur/library/util/LegacyBridge.java")
        );
        assert_eq!(error.line, Some(42));
        assert!(summary
            .diagnostics
            .iter()
            .any(|d| d.tool == Tool::Java && d.severity == Severity::Warning));
    }

    #[test]
    fn slot_marker_paths_relativize() {
        let file = "/builds/slots/slot-3/Modern-Apps/calculator/src/main/java/App.kt";
        assert_eq!(
            relativize(file, "/Users/vayun/Documents/Modern-Apps-wt/isess-parsers"),
            Some("Modern-Apps/calculator/src/main/java/App.kt".to_string())
        );
    }

    #[test]
    fn junit_failure_xml_is_parsed() {
        let xml = xml_fixture("junit_failure.xml");
        let summary = parse_junit_xml(&xml).expect("parse failure xml");
        assert_eq!(summary.failed, 1);
        assert_eq!(summary.errors, 0);
        assert_eq!(summary.passed, 35);
        assert_eq!(summary.failures.len(), 1);
        let f = &summary.failures[0];
        assert_eq!(f.class, "com.vayunmathur.calculator.util.ExpressionTest");
        assert_eq!(f.name, "multiplicationBindsTighterThanAddition");
        assert!(f.message.contains("999.0"));
        assert!(f.trace.lines().count() <= TRACE_LINES);
    }

    #[test]
    fn junit_pass_xml_is_parsed() {
        let xml = xml_fixture("junit_pass.xml");
        let summary = parse_junit_xml(&xml).expect("parse pass xml");
        assert_eq!(summary.failed, 0);
        assert_eq!(summary.errors, 0);
        assert_eq!(summary.passed, 22);
        assert!(summary.failures.is_empty());
    }

    #[test]
    fn junit_merge_adds_counters_and_caps_failures() {
        let a = TestSummary {
            passed: 10,
            failed: 2,
            skipped: 1,
            errors: 1,
            failures: vec![
                TestFailure {
                    class: "A".into(),
                    name: "t1".into(),
                    message: "m".into(),
                    trace: "t".into(),
                },
                TestFailure {
                    class: "A".into(),
                    name: "t2".into(),
                    message: "m".into(),
                    trace: "t".into(),
                },
            ],
        };
        let b = TestSummary {
            passed: 5,
            failed: 1,
            skipped: 0,
            errors: 0,
            failures: vec![TestFailure {
                class: "B".into(),
                name: "t3".into(),
                message: "m".into(),
                trace: "t".into(),
            }],
        };
        let merged = merge_test_summaries(&[a, b]);
        assert_eq!(merged.passed, 15);
        assert_eq!(merged.failed, 3);
        assert_eq!(merged.skipped, 1);
        assert_eq!(merged.errors, 1);
        assert_eq!(merged.failures.len(), 3);
    }

    #[test]
    fn junit_rejects_garbage() {
        assert!(parse_junit_xml("not xml at all").is_err());
        assert!(parse_junit_xml("<testsuites></testsuites>").is_err());
    }

    #[test]
    fn render_build_stays_under_budget() {
        let (out, err) = testdata("lint_error");
        let summary = parse_build_output(&out, &err, Some(1), ROOT);
        let rendered = render_build_for_model(&summary);
        assert!(rendered.len() <= RENDER_BUDGET_BYTES);
        assert!(rendered.contains("build: FAILED"));
        assert!(rendered.contains("ToastUsage"));
    }

    #[test]
    fn render_tests_stays_under_budget() {
        let xml = xml_fixture("junit_failure.xml");
        let summary = parse_junit_xml(&xml).expect("parse");
        let rendered = render_tests_for_model(&summary);
        assert!(rendered.len() <= RENDER_BUDGET_BYTES);
        assert!(rendered.contains("tests: FAILED"));
        assert!(rendered.contains("multiplicationBindsTighterThanAddition"));
    }

    #[test]
    fn render_success_omits_tail() {
        let (out, err) = testdata("kotlin_success");
        let summary = parse_build_output(&out, &err, Some(0), ROOT);
        let rendered = render_build_for_model(&summary);
        assert!(rendered.starts_with("build: OK"));
        assert!(!rendered.contains("tail:"));
    }
}
