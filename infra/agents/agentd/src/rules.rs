//! `AGENTS.md` auto-load: per-directory rules fetched through the runner.
//!
//! Rules live in `tasks.rules_json` (path → content, or null for "no file" so
//! missing files are never re-read) and appear in the system prompt sorted by
//! path, after root/module rules and before plan/todos. They survive
//! park/resume (DB) and are never re-read.
//!
//! Entry points:
//! * [`load_session_rules`] — at session start: root `AGENTS.md`,
//!   `<scope>/AGENTS.md`, and `.llms/repo-map.md` when present.
//! * [`ensure_rules_for_path`] — on any tool touching a path: walk ancestor
//!   dirs root → target dir and `runner.read` each `<dir>/AGENTS.md` not yet
//!   in `rules_json`. Returns the newly loaded paths for the tool-result note.
//!   The parallel worker's git/find tools call this too.
//!
//! Caps: 16 KiB per file, 48 KiB total (each truncated with a note).

use std::collections::BTreeMap;

use crate::agent::DriverCtx;

/// Max bytes kept per rules file.
pub const RULES_PER_FILE_BYTES: usize = 16 * 1024;
/// Max total bytes across all stored rules.
pub const RULES_TOTAL_BYTES: usize = 48 * 1024;

/// Sorted `(path, content)` pairs of loaded (non-null) rules for the system
/// prompt. `rules_json` may be absent (no rules yet).
pub fn sorted_loaded_rules(rules_json: Option<&str>) -> Vec<(String, String)> {
    let Some(raw) = rules_json else {
        return Vec::new();
    };
    let map: BTreeMap<String, Option<String>> = match serde_json::from_str(raw) {
        Ok(m) => m,
        Err(_) => return Vec::new(),
    };
    map.into_iter()
        .filter_map(|(p, c)| c.map(|content| (p, content)))
        .collect()
}

/// Current total bytes of stored (non-null) rule contents.
#[allow(dead_code)]
fn stored_bytes(rules_json: Option<&str>) -> usize {
    sorted_loaded_rules(rules_json)
        .iter()
        .map(|(_, c)| c.len())
        .sum()
}

/// Truncate one rules file to the per-file cap with a note.
fn cap_file(content: &str, path: &str) -> String {
    if content.len() <= RULES_PER_FILE_BYTES {
        return content.to_string();
    }
    let mut end = RULES_PER_FILE_BYTES;
    while end > 0 && !content.is_char_boundary(end) {
        end -= 1;
    }
    format!(
        "{}\n… [rules file {path} capped at {RULES_PER_FILE_BYTES} bytes] …",
        &content[..end]
    )
}

/// Ancestor directories from the repo root down to (and including) the target
/// file's parent dir. `"notes/sub/file.txt"` → `["notes", "notes/sub"]`.
/// Root-level files (`"AGENTS.md"`) → `[]`. Pure; unit-tested.
pub fn ancestor_dirs(path: &str) -> Vec<String> {
    let p = path.trim_start_matches('/');
    let Some(slash) = p.rfind('/') else {
        return Vec::new();
    };
    let dir = &p[..slash];
    if dir.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::new();
    let mut cur = String::new();
    for seg in dir.split('/') {
        if seg.is_empty() || seg == "." {
            continue;
        }
        if seg == ".." {
            continue; // paths are validated repo-relative elsewhere; skip
        }
        if !cur.is_empty() {
            cur.push('/');
        }
        cur.push_str(seg);
        out.push(cur.clone());
    }
    out
}

/// Read `rules_json` paths already known (loaded or known-missing).
fn known_paths(store: &crate::store::Store, task_id: i64) -> BTreeMap<String, Option<String>> {
    let task = store.get_task(task_id).ok().flatten();
    let raw = task.as_ref().and_then(|t| t.rules_json.as_deref());
    match raw {
        Some(s) => serde_json::from_str(s).unwrap_or_default(),
        None => BTreeMap::new(),
    }
}

/// At session start: load root `AGENTS.md`, `<scope>/AGENTS.md`, and
/// `.llms/repo-map.md` when present. Returns `(root_rules, module_rules)`.
/// (The driver still needs these two separately for the prompt's fixed root /
/// module sections; the same contents are also merged into `rules_json` for
/// the sorted `extra_rules` section — dedupe is by path there.)
///
/// Missing files map to `None` and are stored as null so they are not re-read.
pub async fn load_session_rules(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    module_scope: &str,
) -> (Option<String>, Option<String>) {
    let dir = module_scope.replace(':', "/");
    let dir = dir.trim_matches('/');
    let module_path = if dir.is_empty() {
        "AGENTS.md".to_string()
    } else {
        format!("{dir}/AGENTS.md")
    };
    let known = known_paths(&ctx.store, task_id);
    let mut fresh: BTreeMap<String, Option<String>> = BTreeMap::new();
    let mut root_rules: Option<String> = None;
    let mut module_rules: Option<String> = None;
    for path in ["AGENTS.md", module_path.as_str(), ".llms/repo-map.md"] {
        if known.contains_key(path) {
            let v = known.get(path).cloned().flatten();
            if path == "AGENTS.md" {
                root_rules = v.clone();
            }
            if path == module_path.as_str() {
                module_rules = v.clone();
            }
            continue;
        }
        let content = ctx
            .runner
            .read(slot, path, None, Some(400))
            .await
            .ok()
            .map(|r| cap_file(&r.content, path));
        if path == "AGENTS.md" {
            root_rules = content.clone();
        }
        if path == module_path.as_str() {
            module_rules = content.clone();
        }
        fresh.insert(path.to_string(), content);
    }
    // Merge with the total cap enforced (session files count too).
    let mut merged = known;
    let mut total_bytes: usize = merged.values().flatten().map(|c| c.len()).sum();
    for (path, content) in fresh {
        if merged.contains_key(&path) {
            continue;
        }
        match content {
            None => {
                merged.insert(path, None);
            }
            Some(c) => {
                if total_bytes >= RULES_TOTAL_BYTES {
                    merged.insert(
                        path.clone(),
                        Some(format!(
                            "… [rules total cap {RULES_TOTAL_BYTES} bytes reached; {path} skipped] …"
                        )),
                    );
                    continue;
                }
                let mut c = c;
                let room = RULES_TOTAL_BYTES.saturating_sub(total_bytes);
                if c.len() > room {
                    let mut end = room;
                    while end > 0 && !c.is_char_boundary(end) {
                        end -= 1;
                    }
                    c.truncate(end);
                    c.push_str("\n… [rules total cap reached; truncated] …");
                }
                total_bytes += c.len();
                merged.insert(path, Some(c));
            }
        }
    }
    // Persist (merge_rules keeps existing entries on conflict).
    let _ = ctx.store.merge_rules(task_id, &merged);
    (root_rules, module_rules)
}

/// On any tool touching `path`: walk ancestor dirs root → target dir and
/// `runner.read` each `<dir>/AGENTS.md` not yet in `rules_json` (store null
/// when missing so it is not re-read). Returns the newly loaded paths for the
/// tool-result `[loaded rules: …]` note.
///
/// `slot` is the runner slot id (same as the worker index). Exposed `pub` for
/// the parallel worker's git/find tools.
pub async fn ensure_rules_for_path(
    ctx: &DriverCtx,
    task_id: i64,
    slot: u32,
    path: &str,
) -> Vec<String> {
    let known = known_paths(&ctx.store, task_id);
    let mut total_bytes: usize = known.values().flatten().map(|c| c.len()).sum();
    let mut fresh: BTreeMap<String, Option<String>> = BTreeMap::new();
    let mut newly: Vec<String> = Vec::new();
    for dir in ancestor_dirs(path) {
        let rules_path = format!("{dir}/AGENTS.md");
        if known.contains_key(&rules_path) || fresh.contains_key(&rules_path) {
            continue;
        }
        let content = ctx
            .runner
            .read(slot, &rules_path, None, Some(400))
            .await
            .ok()
            .map(|r| cap_file(&r.content, &rules_path));
        match content {
            None => {
                fresh.insert(rules_path.clone(), None);
            }
            Some(c) => {
                if total_bytes >= RULES_TOTAL_BYTES {
                    fresh.insert(
                        rules_path.clone(),
                        Some("… [rules total cap reached; skipped] …".to_string()),
                    );
                } else {
                    let mut c = c;
                    let room = RULES_TOTAL_BYTES.saturating_sub(total_bytes);
                    if c.len() > room {
                        let mut end = room;
                        while end > 0 && !c.is_char_boundary(end) {
                            end -= 1;
                        }
                        c.truncate(end);
                        c.push_str("\n… [rules total cap reached; truncated] …");
                    }
                    total_bytes += c.len();
                    fresh.insert(rules_path.clone(), Some(c));
                }
                newly.push(rules_path.clone());
            }
        }
    }
    if !fresh.is_empty() {
        let _ = ctx.store.merge_rules(task_id, &fresh);
    }
    newly
}
