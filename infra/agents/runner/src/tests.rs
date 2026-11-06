//! Tests for agent-runner: confinement, edit counts, truncation, timeout +
//! process-group kill, build-semaphore serialization, and the full
//! prepare -> write -> diff -> commit -> bundle flow against a temp bare
//! "mirror" repo.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use super::*;
use proto::{EditRequest, ExecKind, ExecRequest, ReadRequest, ResumeSpec, WriteRequest};

fn test_state(slots_dir: &Path, mirror: &Path, num_slots: u32) -> Arc<AppState> {
    Arc::new(AppState::new(
        slots_dir.to_path_buf(),
        mirror.to_path_buf(),
        num_slots,
    ))
}

fn make_slot_root() -> tempfile::TempDir {
    tempfile::tempdir().expect("tempdir")
}

fn init_slot_root(dir: &tempfile::TempDir, slot: u32) -> PathBuf {
    let root = dir.path().join(format!("slot-{slot}"));
    std::fs::create_dir_all(&root).unwrap();
    root
}

// ---------------------------------------------------------------------------
// Path confinement
// ---------------------------------------------------------------------------

#[test]
fn confinement_rejects_absolute_paths() {
    let dir = make_slot_root();
    let root = init_slot_root(&dir, 0);
    assert!(resolve_in_slot(&root, "/etc/passwd").is_err());
    assert!(resolve_in_slot(&root, "/builds/slots/slot-0/x").is_err());
}

#[test]
fn confinement_rejects_dotdot_escapes() {
    let dir = make_slot_root();
    let root = init_slot_root(&dir, 0);
    assert!(resolve_in_slot(&root, "../other").is_err());
    assert!(resolve_in_slot(&root, "a/../../..").is_err());
    assert!(resolve_in_slot(&root, "a/b/../../../../etc").is_err());
    // `..` that stays inside is fine.
    let ok = resolve_in_slot(&root, "a/../b").expect("a/../b stays inside");
    assert_eq!(ok, root.join("b"));
}

#[test]
fn confinement_rejects_git_dir() {
    let dir = make_slot_root();
    let root = init_slot_root(&dir, 0);
    assert!(resolve_in_slot(&root, ".git/config").is_err());
    assert!(resolve_in_slot(&root, ".git").is_err());
    assert!(resolve_in_slot(&root, "a/.git/hooks").is_ok());
}

#[test]
fn confinement_rejects_empty_and_nul() {
    let dir = make_slot_root();
    let root = init_slot_root(&dir, 0);
    assert!(resolve_in_slot(&root, "").is_err());
    assert!(resolve_in_slot(&root, "a\0b").is_err());
}

#[cfg(unix)]
#[test]
fn confinement_rejects_symlink_escape() {
    use std::os::unix::fs::symlink;
    let dir = make_slot_root();
    let root = init_slot_root(&dir, 0);
    let outside = dir.path().join("outside");
    std::fs::create_dir_all(&outside).unwrap();
    std::fs::write(outside.join("secret.txt"), "secret").unwrap();
    // Symlink inside the slot pointing outside.
    symlink(&outside, root.join("link")).unwrap();
    // Direct traversal through the symlink.
    assert!(resolve_in_slot(&root, "link/secret.txt").is_err());
    // A new file created through a symlinked directory would land outside.
    assert!(resolve_in_slot(&root, "link/newfile.txt").is_err());
    // And a symlink directly at the requested path.
    symlink(outside.join("secret.txt"), root.join("evil")).unwrap();
    assert!(resolve_in_slot(&root, "evil").is_err());
    // Benign symlink staying inside is allowed.
    std::fs::create_dir_all(root.join("real")).unwrap();
    std::fs::write(root.join("real/ok.txt"), "ok").unwrap();
    symlink(root.join("real"), root.join("good")).unwrap();
    assert!(resolve_in_slot(&root, "good/ok.txt").is_ok());
}

// ---------------------------------------------------------------------------
// Branch validation
// ---------------------------------------------------------------------------

#[test]
fn branch_validation() {
    assert!(validate_branch("agent/foo").is_ok());
    assert!(validate_branch("agent/foo-bar_baz/qux").is_ok());
    assert!(validate_branch("main").is_err());
    assert!(validate_branch("agent/").is_err());
    assert!(validate_branch("agent/../x").is_err());
    assert!(validate_branch("agent/a..b").is_err());
    assert!(validate_branch("agent/a b").is_err());
    assert!(validate_branch("agent/.hidden").is_err());
    assert!(validate_branch("agent/a.lock").is_err());
    assert!(validate_branch("agent/a:b").is_err());
}

// ---------------------------------------------------------------------------
// Edit match counts
// ---------------------------------------------------------------------------

#[test]
fn edit_requires_exactly_one_match() {
    assert_eq!(
        apply_edit("aaa bbb", "bbb", "ccc").unwrap(),
        "aaa ccc".to_string()
    );
    assert!(apply_edit("aaa", "zzz", "ccc").is_err()); // zero matches
    assert!(apply_edit("x x x", "x", "y").is_err()); // N matches
    assert!(apply_edit("aaa", "", "ccc").is_err()); // empty old
}

// ---------------------------------------------------------------------------
// Output truncation
// ---------------------------------------------------------------------------

#[test]
fn truncate_bytes_keeps_head_and_tail() {
    let cap = 64usize;
    let data: Vec<u8> = (0..200u32).map(|i| b'a' + (i % 26) as u8).collect();
    let (s, truncated) = truncate_bytes(&data, cap);
    assert!(truncated);
    assert!(s.contains("truncated"));
    // Head and tail survive.
    assert!(s.starts_with("abcdef"));
    let tail: Vec<u8> = data[data.len() - cap / 2..].to_vec();
    assert!(s.ends_with(std::str::from_utf8(&tail).unwrap()));
    let (s2, t2) = truncate_bytes(b"small", cap);
    assert!(!t2);
    assert_eq!(s2, "small");
}

// ---------------------------------------------------------------------------
// Exec helpers: need a prepared slot root dir (plain dir, not git).
// ---------------------------------------------------------------------------

fn exec_state(dir: &tempfile::TempDir, num_slots: u32) -> Arc<AppState> {
    // Mirror unused for exec tests; point at the temp dir.
    test_state(dir.path(), dir.path(), num_slots)
}

#[tokio::test]
async fn exec_captures_output_and_exit_code() {
    let dir = make_slot_root();
    let state = exec_state(&dir, 2);
    init_slot_root(&dir, 0);
    let resp = exec_in_slot(
        &state,
        0,
        &ExecRequest {
            command: "echo hello-out; echo hello-err >&2; exit 3".to_string(),
            kind: ExecKind::Shell,
            timeout_secs: 30,
        },
    )
    .await
    .unwrap();
    assert_eq!(resp.exit_code, Some(3));
    assert!(resp.stdout.contains("hello-out"));
    assert!(resp.stderr.contains("hello-err"));
    assert!(!resp.timed_out && !resp.killed && !resp.truncated);
}

#[tokio::test]
async fn exec_truncates_large_output() {
    let dir = make_slot_root();
    let state = exec_state(&dir, 2);
    init_slot_root(&dir, 0);
    let resp = exec_in_slot(
        &state,
        0,
        &ExecRequest {
            command: "yes OUT | head -c 200000".to_string(),
            kind: ExecKind::Shell,
            timeout_secs: 30,
        },
    )
    .await
    .unwrap();
    assert!(resp.truncated, "expected truncated output");
    assert!(resp.stdout.len() < 200_000);
}

#[tokio::test]
async fn exec_timeout_kills_whole_process_group() {
    let dir = make_slot_root();
    let state = exec_state(&dir, 2);
    let root = init_slot_root(&dir, 0);
    let heartbeat = dir.path().join("heartbeat.log");
    // A background grandchild appends heartbeats forever; the foreground
    // sleep keeps the leader alive past the 2s timeout. If killpg kills the
    // whole group, heartbeats stop the moment exec returns; if only the
    // leader died, they keep growing.
    let resp = exec_in_slot(
        &state,
        0,
        &ExecRequest {
            command: format!(
                "(while true; do date +%s >> {}; sleep 0.2; done) & sleep 30",
                heartbeat.display()
            ),
            kind: ExecKind::Shell,
            timeout_secs: 2,
        },
    )
    .await
    .unwrap();
    assert!(resp.timed_out, "expected timed_out");
    assert_eq!(resp.exit_code, None);
    assert!(root.exists());
    // The whole process group must die: after exec returns, the heartbeat
    // file stops growing. A grandchild that survived the killpg would keep
    // appending (it loops forever with no exit condition).
    let size_after = std::fs::metadata(&heartbeat).unwrap().len();
    tokio::time::sleep(Duration::from_secs(2)).await;
    let size_later = std::fs::metadata(&heartbeat).unwrap().len();
    assert_eq!(
        size_after, size_later,
        "background grandchild survived the process-group kill \
         (heartbeat grew from {size_after} to {size_later} bytes)"
    );
}

#[tokio::test]
async fn exec_cancel_kills_running_shell() {
    let dir = make_slot_root();
    let state = exec_state(&dir, 2);
    init_slot_root(&dir, 0);
    let state2 = state.clone();
    let handle = tokio::spawn(async move {
        exec_in_slot(
            &state2,
            0,
            &ExecRequest {
                command: "sleep 60".to_string(),
                kind: ExecKind::Shell,
                timeout_secs: 120,
            },
        )
        .await
        .unwrap()
    });
    // Wait until the exec registers itself, then cancel.
    let mut saw_running = false;
    for _ in 0..100 {
        {
            if state.running.lock().await.contains_key(&0) {
                saw_running = true;
                break;
            }
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    assert!(saw_running, "exec never registered as running");
    assert!(cancel_slot(&state, 0).await);
    let resp = handle.await.unwrap();
    assert!(resp.killed, "expected killed, got {resp:?}");
    assert_eq!(resp.exit_code, None);
    assert!(!cancel_slot(&state, 0).await, "nothing left to cancel");
}

#[tokio::test]
async fn exec_build_serializes_and_reports_queue_time() {
    let dir = make_slot_root();
    let state = exec_state(&dir, 2);
    init_slot_root(&dir, 0);
    init_slot_root(&dir, 1);
    let mk = |cmd: &str| ExecRequest {
        command: cmd.to_string(),
        kind: ExecKind::Build,
        timeout_secs: 60,
    };
    let s1 = state.clone();
    let s2 = state.clone();
    let t0 = Instant::now();
    let req1 = mk("date +%s%N; sleep 3");
    let req2 = mk("date +%s%N");
    let (r1, r2) = tokio::join!(
        exec_in_slot(&s1, 0, &req1),
        async {
            // Start the second build slightly later so it must queue.
            tokio::time::sleep(Duration::from_millis(300)).await;
            exec_in_slot(&s2, 1, &req2).await.unwrap()
        }
    );
    let r1 = r1.unwrap();
    assert_eq!(r1.exit_code, Some(0));
    // Serialized: total wall time is at least the first build's sleep.
    assert!(
        t0.elapsed() >= Duration::from_secs(3),
        "builds overlapped: {:?}",
        t0.elapsed()
    );
    assert!(
        r2.queued_ms >= 2000,
        "second build should have queued ~3s, got {}ms",
        r2.queued_ms
    );
    // Health flag clears afterwards.
    assert!(!state.build_running.load(Ordering::SeqCst));
}

#[tokio::test]
async fn exec_build_kill_endpoint_kills_inflight_build() {
    let dir = make_slot_root();
    let state = exec_state(&dir, 2);
    init_slot_root(&dir, 0);
    let s2 = state.clone();
    let handle = tokio::spawn(async move {
        exec_in_slot(
            &s2,
            0,
            &ExecRequest {
                command: "sleep 60".to_string(),
                kind: ExecKind::Build,
                timeout_secs: 120,
            },
        )
        .await
        .unwrap()
    });
    for _ in 0..100 {
        if state.build_running.load(Ordering::SeqCst) {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    assert!(state.build_running.load(Ordering::SeqCst));
    assert!(kill_build(&state).await);
    let resp = handle.await.unwrap();
    assert!(resp.killed, "expected killed, got {resp:?}");
    assert!(!kill_build(&state).await);
}

#[tokio::test]
async fn exec_invalid_slot_is_404() {
    let dir = make_slot_root();
    let state = exec_state(&dir, 2);
    assert!(parse_slot(&state, "2").is_err());
    assert!(parse_slot(&state, "99").is_err());
    assert!(parse_slot(&state, "-1").is_err());
    assert!(parse_slot(&state, "abc").is_err());
    assert_eq!(parse_slot(&state, "1").unwrap(), 1);
}

// ---------------------------------------------------------------------------
// Full git flow against a temp bare "mirror" repo.
// ---------------------------------------------------------------------------

fn git(dir: &Path, args: &[&str]) {
    let status = std::process::Command::new("git")
        .args(args)
        .current_dir(dir)
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_CONFIG_SYSTEM", "/dev/null")
        .status()
        .expect("spawn git");
    assert!(status.success(), "git {args:?} failed in {dir:?}");
}

fn git_out(dir: &Path, args: &[&str]) -> String {
    let out = std::process::Command::new("git")
        .args(args)
        .current_dir(dir)
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_CONFIG_SYSTEM", "/dev/null")
        .output()
        .expect("spawn git");
    assert!(out.status.success(), "git {args:?} failed: {out:?}");
    String::from_utf8(out.stdout).unwrap().trim().to_string()
}

/// Build a temp bare mirror with one commit on `main`, plus a surrounding
/// state with isolated slots/mirror dirs.
struct GitFlow {
    _tmp: tempfile::TempDir,
    state: Arc<AppState>,
}

fn setup_git_flow() -> GitFlow {
    let tmp = tempfile::tempdir().expect("tempdir");
    let seed = tmp.path().join("seed");
    std::fs::create_dir_all(&seed).unwrap();
    git(&seed, &["init", "-b", "main"]);
    git(&seed, &["config", "user.email", "test@example.com"]);
    git(&seed, &["config", "user.name", "test"]);
    std::fs::write(seed.join("README.md"), "hello\n").unwrap();
    // Tracked .gitignore, like the real repo's: ignored build outputs must
    // survive prepare's `git clean -fd` (which keeps ignored files).
    std::fs::write(seed.join(".gitignore"), "build/\n").unwrap();
    git(&seed, &["add", "-A"]);
    git(&seed, &["commit", "-m", "initial"]);
    let mirror = tmp.path().join("mirror.git");
    let status = std::process::Command::new("git")
        .args([
            "clone",
            "--bare",
            seed.to_str().unwrap(),
            mirror.to_str().unwrap(),
        ])
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_CONFIG_SYSTEM", "/dev/null")
        .status()
        .unwrap();
    assert!(status.success());
    let slots = tmp.path().join("slots");
    std::fs::create_dir_all(&slots).unwrap();
    let state = Arc::new(AppState::new(slots, mirror, 2));
    GitFlow { _tmp: tmp, state }
}

#[tokio::test]
async fn gitflow_prepare_write_diff_commit_bundle() {
    let flow = setup_git_flow();
    let state = &flow.state;

    // prepare
    let base = prepare_slot(state, 0, "agent/test-flow", None).await.unwrap();
    assert_eq!(base.len(), 40);
    // prepare is idempotent
    let base2 = prepare_slot(state, 0, "agent/test-flow", None).await.unwrap();
    assert_eq!(base, base2);
    // bad branch rejected
    assert!(prepare_slot(state, 0, "main", None).await.is_err());

    // clean -fd keeps ignored build outputs: an ignored build/ file created
    // after prepare survives the *next* prepare (warm caches persist).
    let slot = slot_root(state, 0);
    std::fs::create_dir_all(slot.join("build")).unwrap();
    std::fs::write(slot.join("build/warm.txt"), "warm").unwrap();
    prepare_slot(state, 0, "agent/test-flow", None).await.unwrap();
    assert!(
        slot.join("build/warm.txt").exists(),
        "ignored build/ output must survive prepare"
    );

    // write + edit + read
    write_slot_file(
        state,
        0,
        &WriteRequest {
            path: "notes/todo.txt".to_string(),
            content: "line1\nline2 needle\nline3\n".to_string(),
        },
    )
    .await
    .unwrap();
    super::edit_slot_file(
        state,
        0,
        &EditRequest {
            path: "notes/todo.txt".to_string(),
            old: "line2 needle".to_string(),
            new: "line2 done".to_string(),
        },
    )
    .await
    .unwrap();
    let read = super::read_slot_file(
        state,
        0,
        &ReadRequest {
            path: "notes/todo.txt".to_string(),
            start_line: None,
            max_lines: None,
        },
    )
    .await
    .unwrap();
    assert!(read.content.contains("line2 done"));
    assert_eq!(read.total_lines, 3);
    assert!(!read.truncated);

    // zero-match and multi-match edits are 409s.
    let e0 = super::edit_slot_file(
        state,
        0,
        &EditRequest {
            path: "notes/todo.txt".to_string(),
            old: "nope".to_string(),
            new: "x".to_string(),
        },
    )
    .await;
    assert_eq!(e0.unwrap_err().status, StatusCode::CONFLICT);
    std::fs::write(slot.join("multi.txt"), "a a a").unwrap();
    let e1 = super::edit_slot_file(
        state,
        0,
        &EditRequest {
            path: "multi.txt".to_string(),
            old: "a".to_string(),
            new: "b".to_string(),
        },
    )
    .await;
    assert_eq!(e1.unwrap_err().status, StatusCode::CONFLICT);

    // read paging
    let page = super::read_slot_file(
        state,
        0,
        &ReadRequest {
            path: "notes/todo.txt".to_string(),
            start_line: Some(2),
            max_lines: Some(1),
        },
    )
    .await
    .unwrap();
    assert_eq!(page.content, "line2 done");
    assert!(page.truncated);

    // list
    let list = super::list_slot_dir(
        state,
        0,
        &proto::ListRequest {
            path: ".".to_string(),
        },
    )
    .await
    .unwrap();
    assert!(list.entries.iter().any(|e| e == "notes/"));

    // diff includes untracked files (multi.txt is untracked).
    let diff = diff_slot(state, 0).await.unwrap();
    assert!(diff.diff.contains("todo.txt"), "diff: {}", diff.diff);
    assert!(diff.diff.contains("multi.txt"), "diff: {}", diff.diff);

    // commit refuses empty, succeeds with changes.
    std::fs::remove_file(slot.join("multi.txt")).unwrap();
    std::fs::remove_file(slot.join("build/warm.txt")).unwrap();
    let sha = commit_slot(
        state,
        0,
        &proto::CommitRequest {
            message: "agent work".to_string(),
        },
    )
    .await
    .unwrap();
    assert_eq!(sha.len(), 40);
    let empty = commit_slot(
        state,
        0,
        &proto::CommitRequest {
            message: "nothing".to_string(),
        },
    )
    .await;
    assert_eq!(empty.unwrap_err().status, StatusCode::CONFLICT);

    // Bundle verifies, and it imports into a repo holding the base commit.
    // (`base..branch` bundles are thin packs whose prerequisite is the base;
    // agentd imports into the mirror, which has it. Verify from the slot, and
    // fetch into a clone of the mirror — a fresh empty repo correctly fails
    // with "lacks these prerequisite commits".)
    let (_branch, bytes) = bundle_slot(state, 0).await.unwrap();
    assert!(!bytes.is_empty());
    let bundle_path = state.slots_dir.join("test.bundle");
    std::fs::write(&bundle_path, &bytes).unwrap();
    let verify = std::process::Command::new("git")
        .args(["bundle", "verify", bundle_path.to_str().unwrap()])
        .current_dir(&slot)
        .output()
        .unwrap();
    assert!(
        verify.status.success(),
        "git bundle verify failed: {verify:?}"
    );
    let clone_dir = tempfile::tempdir().unwrap();
    git(
        clone_dir.path(),
        &[
            "clone",
            state.mirror.to_str().unwrap(),
            "imported",
        ],
    );
    let imported = clone_dir.path().join("imported");
    git(
        &imported,
        &[
            "fetch",
            bundle_path.to_str().unwrap(),
            "agent/test-flow:refs/heads/fetched",
        ],
    );
    let fetched = git_out(&imported, &["rev-parse", "fetched"]);
    assert_eq!(fetched, sha);

    // bundle with no commits is 409.
    prepare_slot(state, 1, "agent/empty-branch", None).await.unwrap();
    let b2 = bundle_slot(state, 1).await;
    assert_eq!(b2.unwrap_err().status, StatusCode::CONFLICT);
}

#[tokio::test]
async fn search_respects_excludes_and_globs() {
    let rg_available = std::process::Command::new("rg")
        .arg("--version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false);
    if !rg_available {
        eprintln!("skipping search test: rg not installed");
        return;
    }
    let dir = make_slot_root();
    let state = exec_state(&dir, 2);
    let root = init_slot_root(&dir, 0);
    std::fs::write(root.join("hit.kt"), "needle here\n").unwrap();
    std::fs::create_dir_all(root.join("build")).unwrap();
    std::fs::write(root.join("build/hit.kt"), "needle here\n").unwrap();
    let resp = super::search_slot(
        &state,
        0,
        &proto::SearchRequest {
            pattern: "needle".to_string(),
            path: None,
            glob: None,
            fixed_string: false,
        },
    )
    .await
    .unwrap();
    assert!(resp.matches.contains("hit.kt:1"), "got: {}", resp.matches);
    assert!(
        !resp.matches.contains("build/"),
        "build/ must be excluded: {}",
        resp.matches
    );
    // glob filter
    let resp2 = super::search_slot(
        &state,
        0,
        &proto::SearchRequest {
            pattern: "needle".to_string(),
            path: None,
            glob: Some("*.java".to_string()),
            fixed_string: false,
        },
    )
    .await
    .unwrap();
    assert!(!resp2.matches.contains("hit.kt"), "glob must filter: {}", resp2.matches);
    // fixed strings: regex chars are literal.
    std::fs::write(root.join("re.txt"), "a.c\n").unwrap();
    let resp3 = super::search_slot(
        &state,
        0,
        &proto::SearchRequest {
            pattern: "a.c".to_string(),
            path: None,
            glob: Some("re.txt".to_string()),
            fixed_string: true,
        },
    )
    .await
    .unwrap();
    assert!(resp3.matches.contains("re.txt"), "got: {}", resp3.matches);
}

// ---------------------------------------------------------------------------
// Resume: park on one slot, resume on another via refs/agent-wip/N.
// ---------------------------------------------------------------------------

/// Park the slot's current branch: commit everything as a WIP commit, bundle
/// `base..branch`, and import the bundle into the mirror under
/// `refs/agent-wip/<n>` — the same way agentd stores parked work. Returns
/// `(base_sha, wip_sha)`.
fn park_slot_to_wip_ref(state: &Arc<AppState>, slot: u32, wip_n: u32) -> (String, String) {
    let slot_path = slot_root(state, slot);
    let base_sha = std::fs::read_to_string(slot_path.join(".git").join(AGENT_BASE_FILE))
        .expect("agent-base recorded")
        .trim()
        .to_string();
    let branch = git_out(&slot_path, &["branch", "--show-current"]);
    git(&slot_path, &["add", "-A"]);
    git(&slot_path, &["commit", "-m", "WIP"]);
    let wip_sha = git_out(&slot_path, &["rev-parse", "HEAD"]);
    // Bundle base..branch the way the runner's bundle route does, then import
    // into the mirror under refs/agent-wip/<n> the way agentd does.
    let range = format!("{base_sha}..{branch}");
    let bundle_bytes = std::process::Command::new("git")
        .args(["bundle", "create", "-", &range])
        .current_dir(&slot_path)
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_CONFIG_SYSTEM", "/dev/null")
        .output()
        .expect("git bundle create");
    assert!(bundle_bytes.status.success(), "git bundle create failed: {bundle_bytes:?}");
    assert!(!bundle_bytes.stdout.is_empty());
    let bundle_path = state.slots_dir.join(format!("park-{wip_n}.bundle"));
    std::fs::write(&bundle_path, &bundle_bytes.stdout).unwrap();
    let wip_ref = format!("refs/agent-wip/{wip_n}");
    let fetch_refspec = format!("+refs/heads/{branch}:{wip_ref}");
    git(&state.mirror, &["fetch", bundle_path.to_str().unwrap(), fetch_refspec.as_str()]);
    (base_sha, wip_sha)
}

fn resume_spec(base_sha: &str, wip_n: u32, wip_sha: &str) -> ResumeSpec {
    ResumeSpec {
        base_sha: base_sha.to_string(),
        wip_ref: Some(format!("refs/agent-wip/{wip_n}")),
        wip_sha: Some(wip_sha.to_string()),
    }
}

#[tokio::test]
async fn resume_park_round_trip_on_different_slot() {
    let flow = setup_git_flow();
    let state = &flow.state;

    // Prepare slot 0 fresh, then make changes incl. a new untracked file.
    let base = prepare_slot(state, 0, "agent/parked", None).await.unwrap();
    let slot0 = slot_root(state, 0);
    std::fs::write(slot0.join("README.md"), "hello modified\n").unwrap();
    std::fs::write(slot0.join("brand-new.txt"), "brand new\n").unwrap();
    std::fs::create_dir_all(slot0.join("notes")).unwrap();
    std::fs::write(slot0.join("notes/todo.txt"), "todo one\n").unwrap();

    // Commit as WIP, bundle, import into the mirror as refs/agent-wip/7.
    let (park_base, wip_sha) = park_slot_to_wip_ref(state, 0, 7);
    assert_eq!(park_base, base);

    // Resume onto slot 1 under a new branch name.
    let spec = resume_spec(&base, 7, &wip_sha);
    let resumed = prepare_slot(state, 1, "agent/resumed", Some(&spec)).await.unwrap();
    assert_eq!(resumed, base);
    let slot1 = slot_root(state, 1);

    // /diff shows exactly the original changes vs base, including the new file.
    let diff = diff_slot(state, 1).await.unwrap();
    assert!(diff.diff.contains("README.md"), "diff: {}", diff.diff);
    assert!(
        diff.diff.contains("brand-new.txt"),
        "untracked file must survive resume: {}",
        diff.diff
    );
    assert!(diff.diff.contains("notes/todo.txt"), "diff: {}", diff.diff);
    assert!(std::fs::read_to_string(slot1.join("brand-new.txt")).unwrap() == "brand new\n");

    // agent-base records the original base, HEAD is soft-reset onto base.
    let recorded = std::fs::read_to_string(slot1.join(".git").join(AGENT_BASE_FILE))
        .unwrap()
        .trim()
        .to_string();
    assert_eq!(recorded, base);
    assert_eq!(git_out(&slot1, &["rev-parse", "HEAD"]), base);

    // Commit + bundle on slot 1 produce a single commit base..branch.
    let sha = commit_slot(state, 1, &proto::CommitRequest { message: "resumed work".to_string() })
        .await
        .unwrap();
    let count = git_out(&slot1, &["rev-list", "--count", format!("{base}..HEAD").as_str()]);
    assert_eq!(count, "1", "expected a single commit on top of base");
    let (_branch, bytes) = bundle_slot(state, 1).await.unwrap();
    assert!(!bytes.is_empty());
    // The re-committed tree matches the parked WIP tree.
    let wip_tree = git_out(&slot0, &["rev-parse", format!("{wip_sha}^{{tree}}").as_str()]);
    let new_tree = git_out(&slot1, &["rev-parse", format!("{sha}^{{tree}}").as_str()]);
    assert_eq!(new_tree, wip_tree);
}

#[tokio::test]
async fn resume_into_same_slot_clears_leftovers() {
    let flow = setup_git_flow();
    let state = &flow.state;

    let base = prepare_slot(state, 0, "agent/parked", None).await.unwrap();
    let slot0 = slot_root(state, 0);
    std::fs::write(slot0.join("work.txt"), "parked work\n").unwrap();
    let (park_base, wip_sha) = park_slot_to_wip_ref(state, 0, 3);
    assert_eq!(park_base, base);

    // Slot 0 now has leftovers from "another task": an unrelated branch,
    // stray untracked files, and uncommitted modifications.
    git(&slot0, &["checkout", "-B", "agent/other-task"]);
    std::fs::write(slot0.join("leftover.txt"), "stale\n").unwrap();
    std::fs::write(slot0.join("README.md"), "stale modification\n").unwrap();

    let spec = resume_spec(&base, 3, &wip_sha);
    let resumed = prepare_slot(state, 0, "agent/resumed-here", Some(&spec)).await.unwrap();
    assert_eq!(resumed, base);

    // Leftovers are gone, parked work is restored.
    assert!(!slot0.join("leftover.txt").exists());
    assert_eq!(std::fs::read_to_string(slot0.join("work.txt")).unwrap(), "parked work\n");
    assert_eq!(std::fs::read_to_string(slot0.join("README.md")).unwrap(), "hello\n");
    assert_eq!(git_out(&slot0, &["rev-parse", "HEAD"]), base);
    let diff = diff_slot(state, 0).await.unwrap();
    assert!(diff.diff.contains("work.txt"), "diff: {}", diff.diff);
}

#[tokio::test]
async fn resume_wip_sha_mismatch_is_409() {
    let flow = setup_git_flow();
    let state = &flow.state;

    let base = prepare_slot(state, 0, "agent/parked", None).await.unwrap();
    let slot0 = slot_root(state, 0);
    std::fs::write(slot0.join("work.txt"), "parked work\n").unwrap();
    let (_park_base, _wip_sha) = park_slot_to_wip_ref(state, 0, 11);

    // Claim a sha the ref does not resolve to.
    let bogus = "dddddddddddddddddddddddddddddddddddddddd";
    let spec = resume_spec(&base, 11, bogus);
    let err = prepare_slot(state, 1, "agent/resume-bad", Some(&spec)).await.unwrap_err();
    assert_eq!(err.status, StatusCode::CONFLICT, "mismatch must be 409");
    assert!(err.message.contains("expected wip_sha"), "clear error message, got: {}", err.message);
    // Slot must not be left claiming the wrong commit: HEAD is the base, not
    // the bogus sha, and no branch tracks the bogus content.
    let slot1 = slot_root(state, 1);
    assert_ne!(git_out(&slot1, &["rev-parse", "HEAD"]), bogus);
}

#[tokio::test]
async fn resume_without_wip_checks_out_base_even_after_main_moves() {
    let flow = setup_git_flow();
    let state = &flow.state;

    // Record today's base, then move mirror main on.
    let base = prepare_slot(state, 0, "agent/first", None).await.unwrap();
    {
        let work = tempfile::tempdir().unwrap();
        git(work.path(), &["clone", state.mirror.to_str().unwrap(), "w"]);
        let w = work.path().join("w");
        git(&w, &["config", "user.email", "test@example.com"]);
        git(&w, &["config", "user.name", "test"]);
        git(&w, &["checkout", "main"]);
        std::fs::write(w.join("moved-on.txt"), "new main\n").unwrap();
        git(&w, &["add", "-A"]);
        git(&w, &["commit", "-m", "main moves on"]);
        git(&w, &["push", "origin", "main"]);
    }

    let spec = ResumeSpec { base_sha: base.clone(), wip_ref: None, wip_sha: None };
    let resumed = prepare_slot(state, 1, "agent/clean-resume", Some(&spec)).await.unwrap();
    assert_eq!(resumed, base);
    let slot1 = slot_root(state, 1);
    assert_eq!(git_out(&slot1, &["rev-parse", "HEAD"]), base);
    assert!(!slot1.join("moved-on.txt").exists());
    assert_eq!(
        std::fs::read_to_string(slot1.join(".git").join(AGENT_BASE_FILE)).unwrap().trim(),
        base
    );
    // Nothing to commit: clean checkout at base.
    let empty =
        commit_slot(state, 1, &proto::CommitRequest { message: "nothing".to_string() }).await;
    assert_eq!(empty.unwrap_err().status, StatusCode::CONFLICT);
}

#[tokio::test]
async fn resume_rejects_invalid_ref_and_sha_formats() {
    let flow = setup_git_flow();
    let state = &flow.state;
    let base = prepare_slot(state, 0, "agent/first", None).await.unwrap();

    // Bad base_sha.
    for bad_base in ["", "xyz", "abc123", "g".repeat(40).as_str()] {
        let spec = ResumeSpec { base_sha: bad_base.to_string(), wip_ref: None, wip_sha: None };
        let err = prepare_slot(state, 1, "agent/bad", Some(&spec)).await.unwrap_err();
        assert_eq!(err.status, StatusCode::BAD_REQUEST, "base {bad_base:?}");
    }
    // Bad wip_ref shapes.
    for bad_ref in [
        "refs/agent-wip/",
        "refs/agent-wip/abc",
        "refs/agent-wip/1/../2",
        "refs/heads/main",
        "refs/agent-wip//7",
        "agent-wip/7",
    ] {
        let spec = ResumeSpec {
            base_sha: base.clone(),
            wip_ref: Some(bad_ref.to_string()),
            wip_sha: Some("a".repeat(40)),
        };
        let err = prepare_slot(state, 1, "agent/bad", Some(&spec)).await.unwrap_err();
        assert_eq!(err.status, StatusCode::BAD_REQUEST, "ref {bad_ref:?}");
    }
    // Bad wip_sha.
    let spec = ResumeSpec {
        base_sha: base.clone(),
        wip_ref: Some("refs/agent-wip/7".to_string()),
        wip_sha: Some("not-a-sha".to_string()),
    };
    let err = prepare_slot(state, 1, "agent/bad", Some(&spec)).await.unwrap_err();
    assert_eq!(err.status, StatusCode::BAD_REQUEST);
    // wip_ref without wip_sha and vice versa.
    let spec = ResumeSpec {
        base_sha: base.clone(),
        wip_ref: Some("refs/agent-wip/7".to_string()),
        wip_sha: None,
    };
    assert_eq!(
        prepare_slot(state, 1, "agent/bad", Some(&spec)).await.unwrap_err().status,
        StatusCode::BAD_REQUEST
    );
    let spec = ResumeSpec { base_sha: base.clone(), wip_ref: None, wip_sha: Some("a".repeat(40)) };
    assert_eq!(
        prepare_slot(state, 1, "agent/bad", Some(&spec)).await.unwrap_err().status,
        StatusCode::BAD_REQUEST
    );
}

#[test]
fn legacy_prepare_request_without_resume_still_deserializes() {
    let req: proto::PrepareRequest =
        serde_json::from_str(r#"{"branch": "agent/x"}"#).expect("legacy JSON");
    assert_eq!(req.branch, "agent/x");
    assert!(req.resume.is_none());
    // And a request with resume parses too.
    let req: proto::PrepareRequest = serde_json::from_str(
        r#"{"branch": "agent/x", "resume": {"base_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "wip_ref": "refs/agent-wip/7", "wip_sha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}"#,
    )
    .expect("resume JSON");
    let spec = req.resume.expect("resume present");
    assert_eq!(spec.wip_ref.as_deref(), Some("refs/agent-wip/7"));
}
