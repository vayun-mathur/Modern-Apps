//! agent-runner: HTTP sandbox API served from inside the builder container.
//!
//! Implements every route in `proto` exactly. See `proto/src/lib.rs` for the
//! wire contract. No auth: this server only listens on the internal Docker
//! network and itself runs inside the untrusted sandbox.

use std::collections::HashMap;
use std::os::unix::process::CommandExt;
use std::path::{Component, Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use axum::body::Bytes;
use axum::extract::{Path as AxumPath, State};
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Json};
use axum::routing::{get, post};
use axum::Router;
use proto::{
    CommitRequest, CommitResponse, DiffResponse, EditRequest, ErrorBody, ExecKind, ExecRequest,
    ExecResponse, HealthResponse, KillResponse, ListRequest, ListResponse, PrepareRequest,
    PrepareResponse, ReadRequest, ReadResponse, SearchRequest, SearchResponse, WriteRequest,
    AGENT_BRANCH_PREFIX, RUNNER_PORT,
};
use tokio::sync::{Mutex, Semaphore};

/// Stdout/stderr cap per stream for exec/search (head + tail kept).
pub const OUTPUT_CAP_BYTES: usize = 64 * 1024;
/// Cap for `diff.stat`.
pub const DIFF_STAT_CAP_BYTES: usize = 32 * 1024;
/// Cap for `diff.diff`.
pub const DIFF_CAP_BYTES: usize = 256 * 1024;
/// Default line cap for reads.
pub const READ_DEFAULT_MAX_LINES: usize = 2000;
/// Hard ceiling for reads even when the caller asks for more.
pub const READ_HARD_MAX_LINES: usize = 100_000;
/// Max entries returned by list.
pub const LIST_MAX_ENTRIES: usize = 2000;
/// Bounded time for `./gradlew --stop` after a killed/timed-out build.
const GRADLE_STOP_TIMEOUT: Duration = Duration::from_secs(120);
/// Timeout for individual git invocations.
const GIT_TIMEOUT: Duration = Duration::from_secs(300);
/// Timeout for ripgrep searches.
const SEARCH_TIMEOUT: Duration = Duration::from_secs(120);
/// Grace period to drain stdio after the process exits (a backgrounded
/// grandchild can hold the pipes open; don't wait forever).
const STDIO_DRAIN_GRACE: Duration = Duration::from_secs(10);
/// Name of the file (inside `<slot>/.git/`) recording the branch base.
const AGENT_BASE_FILE: &str = "agent-base";

/// Error with an HTTP status, rendered as [`ErrorBody`] JSON.
#[derive(Debug)]
pub struct ApiError {
    pub status: StatusCode,
    pub message: String,
}

impl ApiError {
    fn new(status: StatusCode, message: impl Into<String>) -> Self {
        Self {
            status,
            message: message.into(),
        }
    }

    fn bad_request(message: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, message)
    }

    fn not_found(message: impl Into<String>) -> Self {
        Self::new(StatusCode::NOT_FOUND, message)
    }

    fn conflict(message: impl Into<String>) -> Self {
        Self::new(StatusCode::CONFLICT, message)
    }

    fn internal(message: impl Into<String>) -> Self {
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, message)
    }
}

type ErrTuple = (StatusCode, Json<ErrorBody>);

fn to_err_tuple(e: ApiError) -> ErrTuple {
    (e.status, Json(ErrorBody { error: e.message }))
}

struct RunningEntry {
    pgid: i32,
    killed: Arc<AtomicBool>,
    is_build: bool,
}

pub struct AppState {
    pub slots_dir: PathBuf,
    pub mirror: PathBuf,
    pub num_slots: u32,
    build_sem: Arc<Semaphore>,
    build_running: AtomicBool,
    running: Mutex<HashMap<u32, RunningEntry>>,
    build_slot: Mutex<Option<u32>>,
}

impl AppState {
    pub fn new(slots_dir: PathBuf, mirror: PathBuf, num_slots: u32) -> Self {
        Self {
            slots_dir,
            mirror,
            num_slots: num_slots.max(1),
            build_sem: Arc::new(Semaphore::new(1)),
            build_running: AtomicBool::new(false),
            running: Mutex::new(HashMap::new()),
            build_slot: Mutex::new(None),
        }
    }

    pub fn from_env() -> Self {
        let num_slots = std::env::var("RUNNER_SLOTS")
            .ok()
            .and_then(|v| v.parse::<u32>().ok())
            .unwrap_or(4);
        let slots_dir = std::env::var("RUNNER_SLOTS_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from("/builds/slots"));
        let mirror = std::env::var("RUNNER_MIRROR")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from("/mirror/Modern-Apps.git"));
        Self::new(slots_dir, mirror, num_slots)
    }

    pub fn listen_addr() -> String {
        std::env::var("RUNNER_LISTEN").unwrap_or_else(|_| format!("0.0.0.0:{RUNNER_PORT}"))
    }
}

/// Parse a `{slot}` path param: integer in `0..num_slots`, else 404.
pub fn parse_slot(state: &AppState, raw: &str) -> Result<u32, ApiError> {
    match raw.parse::<u32>() {
        Ok(n) if n < state.num_slots => Ok(n),
        _ => Err(ApiError::not_found(format!(
            "unknown slot {raw:?}; valid slots are 0..{}",
            state.num_slots
        ))),
    }
}

pub fn slot_root(state: &AppState, slot: u32) -> PathBuf {
    state.slots_dir.join(format!("slot-{slot}"))
}

/// Resolve a request path relative to the slot root.
///
/// Rejects absolute paths, `..` escapes, NUL bytes, anything under `.git/`,
/// and symlinks (on existing ancestors) that resolve outside the slot root.
pub fn resolve_in_slot(slot_root: &Path, rel: &str) -> Result<PathBuf, String> {
    if rel.is_empty() {
        return Err("empty path".to_string());
    }
    if rel.contains('\0') {
        return Err("path contains NUL byte".to_string());
    }
    let rel_path = Path::new(rel);
    if rel_path.is_absolute() {
        return Err(format!("absolute paths are rejected: {rel:?}"));
    }
    // Lexical normalization: collapse `.`, reject escaping `..`.
    let mut parts: Vec<String> = Vec::new();
    for comp in rel_path.components() {
        match comp {
            Component::Prefix(_) | Component::RootDir => {
                return Err(format!("absolute paths are rejected: {rel:?}"));
            }
            Component::CurDir => {}
            Component::ParentDir => {
                if parts.pop().is_none() {
                    return Err(format!("path escapes the slot root: {rel:?}"));
                }
            }
            Component::Normal(c) => parts.push(c.to_string_lossy().into_owned()),
        }
    }
    if parts.is_empty() {
        return Err(format!("path resolves to the slot root itself: {rel:?}"));
    }
    if parts[0] == ".git" {
        return Err("paths under .git/ are rejected".to_string());
    }
    let mut resolved = slot_root.to_path_buf();
    for p in &parts {
        resolved.push(p);
    }
    // Symlink check: canonicalize the longest existing prefix (the slot root
    // itself if nothing exists yet) and require it to stay inside the
    // canonical slot root. This covers new files created through a symlinked
    // directory as well as existing symlinked files.
    let canonical_root = slot_root
        .canonicalize()
        .map_err(|e| format!("slot root is not usable: {e}"))?;
    let mut probe = resolved.clone();
    loop {
        if probe.exists() {
            let canon = probe
                .canonicalize()
                .map_err(|e| format!("cannot canonicalize {probe:?}: {e}"))?;
            if !canon.starts_with(&canonical_root) {
                return Err(format!("path resolves outside the slot root: {rel:?}"));
            }
            break;
        }
        if !probe.pop() {
            break;
        }
    }
    Ok(resolved)
}

/// Validate an agent branch name: `agent/` prefix + safe ref name.
pub fn validate_branch(branch: &str) -> Result<(), String> {
    if !branch.starts_with(AGENT_BRANCH_PREFIX) {
        return Err(format!(
            "branch must start with {AGENT_BRANCH_PREFIX:?}, got {branch:?}"
        ));
    }
    if branch.contains('\0') || branch.chars().any(|c| c.is_control()) {
        return Err("branch contains control characters".to_string());
    }
    for bad in ["..", "@{", "//", "~", "^", ":", "?", "*", "[", "\\"] {
        if branch.contains(bad) {
            return Err(format!("branch contains forbidden sequence {bad:?}"));
        }
    }
    if branch.starts_with('/') || branch.starts_with('.') {
        return Err("branch has a forbidden leading character".to_string());
    }
    if branch.ends_with('/') || branch.ends_with('.') || branch.ends_with(".lock") {
        return Err("branch has a forbidden trailing component".to_string());
    }
    for comp in branch.split('/') {
        if comp.is_empty() || comp.starts_with('.') {
            return Err(format!("branch has a forbidden component in {branch:?}"));
        }
        if comp == "agent" && branch == "agent/" {
            return Err("branch name is empty after the agent/ prefix".to_string());
        }
        if !comp
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.'))
        {
            return Err(format!(
                "branch contains a forbidden character in {branch:?}"
            ));
        }
    }
    Ok(())
}

/// Keep head + tail of `data` over `cap` bytes, with a marker line.
pub fn truncate_bytes(data: &[u8], cap: usize) -> (String, bool) {
    if data.len() <= cap {
        return (String::from_utf8_lossy(data).into_owned(), false);
    }
    let half = cap / 2;
    let marker = format!("\n... [truncated {} bytes] ...\n", data.len() - cap);
    let mut out = String::from_utf8_lossy(&data[..half]).into_owned();
    out.push_str(&marker);
    out.push_str(&String::from_utf8_lossy(&data[data.len() - half..]));
    (out, true)
}

/// Replace exactly one occurrence of `old` with `new`.
pub fn apply_edit(content: &str, old: &str, new: &str) -> Result<String, String> {
    if old.is_empty() {
        return Err("`old` must not be empty".to_string());
    }
    let count = content.matches(old).count();
    if count != 1 {
        return Err(format!(
            "`old` matched {count} times (expected exactly 1); refusing to edit"
        ));
    }
    Ok(content.replacen(old, new, 1))
}

fn read_base_sha(slot: &Path) -> Result<String, ApiError> {
    let base_file = slot.join(".git").join(AGENT_BASE_FILE);
    let raw = std::fs::read_to_string(&base_file).map_err(|_| {
        ApiError::conflict("slot has no recorded base; run prepare first".to_string())
    })?;
    let sha = raw.trim().to_string();
    if sha.is_empty() {
        return Err(ApiError::conflict(
            "slot has no recorded base; run prepare first".to_string(),
        ));
    }
    Ok(sha)
}

fn git_args(mirror: &Path, slot: Option<&Path>, args: &[&str]) -> Vec<String> {
    let mut out = vec![
        "-c".to_string(),
        format!("safe.directory={}", mirror.display()),
    ];
    if let Some(slot) = slot {
        out.push("-c".to_string());
        out.push(format!("safe.directory={}", slot.display()));
        out.push("-C".to_string());
        out.push(slot.display().to_string());
    }
    out.extend(args.iter().map(|s| s.to_string()));
    out
}

async fn run_git(
    mirror: &Path,
    slot: Option<&Path>,
    args: &[&str],
) -> Result<std::process::Output, ApiError> {
    let argv = git_args(mirror, slot, args);
    let mut cmd = tokio::process::Command::new("git");
    cmd.args(&argv);
    cmd.stdin(Stdio::null());
    cmd.stdout(Stdio::piped());
    cmd.stderr(Stdio::piped());
    cmd.kill_on_drop(true);
    let child = cmd.spawn().map_err(|e| {
        ApiError::internal(format!("failed to spawn git {}: {e}", args.join(" ")))
    })?;
    let status_str = args.join(" ");
    let pid = child.id();
    match tokio::time::timeout(GIT_TIMEOUT, child.wait_with_output()).await {
        Ok(Ok(out)) => Ok(out),
        Ok(Err(e)) => Err(ApiError::internal(format!(
            "failed to wait for git {status_str}: {e}"
        ))),
        Err(_) => {
            // wait_with_output consumed the Child; kill by pid explicitly
            // (kill_on_drop cannot fire since the Child was moved). These are
            // leaf git invocations, so signalling the pid is sufficient.
            if let Some(pid) = pid {
                unsafe {
                    libc::kill(pid as i32, libc::SIGKILL);
                }
            }
            Err(ApiError::internal(format!(
                "git {status_str} timed out after {}s",
                GIT_TIMEOUT.as_secs()
            )))
        }
    }
}

fn check_git_output(out: &std::process::Output, what: &str) -> Result<String, ApiError> {
    if out.status.success() {
        Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
    } else {
        Err(ApiError::internal(format!(
            "{what} failed: {}",
            String::from_utf8_lossy(&out.stderr).trim()
        )))
    }
}

/// (Re)create the slot checkout on a fresh branch from the mirror's main.
pub async fn prepare_slot(
    state: &Arc<AppState>,
    slot: u32,
    branch: &str,
) -> Result<String, ApiError> {
    validate_branch(branch).map_err(ApiError::bad_request)?;
    let slot_path = slot_root(state, slot);
    let is_repo = slot_path.join(".git").is_dir();
    if !is_repo {
        if slot_path.exists() {
            if slot_path.is_dir() {
                std::fs::remove_dir_all(&slot_path).map_err(|e| {
                    ApiError::internal(format!("failed to clear {slot_path:?}: {e}"))
                })?;
            } else {
                std::fs::remove_file(&slot_path).map_err(|e| {
                    ApiError::internal(format!("failed to clear {slot_path:?}: {e}"))
                })?;
            }
        }
        if let Some(parent) = slot_path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| ApiError::internal(format!("failed to create slots dir: {e}")))?;
        }
        let out = run_git(
            &state.mirror,
            None,
            &[
                "clone",
                "--shared",
                &state.mirror.display().to_string(),
                &slot_path.display().to_string(),
            ],
        )
        .await?;
        check_git_output(&out, "git clone")?;
    } else {
        // Tear down leftover state first so the checkout below cannot fail on
        // untracked files shadowing the target branch.
        let out = run_git(&state.mirror, Some(&slot_path), &["reset", "--hard", "HEAD"]).await?;
        check_git_output(&out, "git reset")?;
        let out = run_git(&state.mirror, Some(&slot_path), &["clean", "-fd"]).await?;
        check_git_output(&out, "git clean")?;
    }
    let out = run_git(&state.mirror, Some(&slot_path), &["fetch", "origin"]).await?;
    check_git_output(&out, "git fetch")?;
    let out = run_git(
        &state.mirror,
        Some(&slot_path),
        &["rev-parse", "--verify", "origin/main"],
    )
    .await?;
    if !out.status.success() {
        return Err(ApiError::internal(
            "mirror has no origin/main; cannot prepare slot".to_string(),
        ));
    }
    let out = run_git(
        &state.mirror,
        Some(&slot_path),
        &["checkout", "-B", branch, "origin/main"],
    )
    .await?;
    check_git_output(&out, "git checkout")?;
    let out = run_git(
        &state.mirror,
        Some(&slot_path),
        &["reset", "--hard", "origin/main"],
    )
    .await?;
    check_git_output(&out, "git reset")?;
    // `clean -fd` without `-x`: drop untracked work but keep ignored
    // build outputs (build/, target/, .gradle/) warm.
    let out = run_git(&state.mirror, Some(&slot_path), &["clean", "-fd"]).await?;
    check_git_output(&out, "git clean")?;
    let out = run_git(
        &state.mirror,
        Some(&slot_path),
        &["config", "user.name", "maproj agent"],
    )
    .await?;
    check_git_output(&out, "git config user.name")?;
    let out = run_git(
        &state.mirror,
        Some(&slot_path),
        &["config", "user.email", "agents@maproj.org"],
    )
    .await?;
    check_git_output(&out, "git config user.email")?;
    let out = run_git(&state.mirror, Some(&slot_path), &["rev-parse", "HEAD"]).await?;
    let base_sha = check_git_output(&out, "git rev-parse")?;
    std::fs::write(
        slot_path.join(".git").join(AGENT_BASE_FILE),
        format!("{base_sha}\n"),
    )
    .map_err(|e| ApiError::internal(format!("failed to record base sha: {e}")))?;
    Ok(base_sha)
}

fn kill_process_group(pgid: i32) {
    // ESRCH (no such group) just means nothing was running.
    unsafe {
        libc::killpg(pgid, libc::SIGKILL);
    }
}

/// Run `bash -lc <command>` in the slot root, in its own process group.
pub async fn exec_in_slot(
    state: &Arc<AppState>,
    slot: u32,
    req: &ExecRequest,
) -> Result<ExecResponse, ApiError> {
    let slot_path = slot_root(state, slot);
    if !slot_path.is_dir() {
        return Err(ApiError::not_found(
            "slot is not prepared; run prepare first".to_string(),
        ));
    }
    let mut queued_ms = 0u64;

    // Build commands serialize behind the single build permit.
    let _permit = match req.kind {
        ExecKind::Build => {
            let wait_start = Instant::now();
            let permit = state
                .build_sem
                .clone()
                .acquire_owned()
                .await
                .map_err(|e| ApiError::internal(format!("build semaphore closed: {e}")))?;
            queued_ms = wait_start
                .elapsed()
                .as_millis()
                .min(u128::from(u64::MAX)) as u64;
            state.build_running.store(true, Ordering::SeqCst);
            *state.build_slot.lock().await = Some(slot);
            Some(permit)
        }
        ExecKind::Shell => None,
    };

    let result = run_command(state, slot, &slot_path, req).await;

    if _permit.is_some() {
        state.build_running.store(false, Ordering::SeqCst);
        *state.build_slot.lock().await = None;
        drop(_permit);
    }

    let mut resp = result?;
    resp.queued_ms = queued_ms;
    Ok(resp)
}

/// Drain an async reader, bounded by a deadline; returns bytes + whether the
/// deadline cut the drain short (a backgrounded grandchild can hold pipes).
async fn drain_bounded<R>(mut reader: R, deadline: Instant) -> (Vec<u8>, bool)
where
    R: tokio::io::AsyncRead + Unpin,
{
    let mut buf = Vec::new();
    let mut chunk = [0u8; 8192];
    loop {
        let now = Instant::now();
        if now >= deadline {
            return (buf, true);
        }
        tokio::select! {
            res = tokio::io::AsyncReadExt::read(&mut reader, &mut chunk) => {
                match res {
                    Ok(0) => return (buf, false),
                    Ok(n) => buf.extend_from_slice(&chunk[..n]),
                    Err(_) => return (buf, true),
                }
            }
            _ = tokio::time::sleep_until(tokio::time::Instant::from_std(deadline)) => {
                return (buf, true);
            }
        }
    }
}

async fn run_command(
    state: &Arc<AppState>,
    slot: u32,
    slot_path: &Path,
    req: &ExecRequest,
) -> Result<ExecResponse, ApiError> {
    let started = Instant::now();
    let use_nice = req.kind == ExecKind::Shell && Path::new("/usr/bin/nice").exists();
    let mut std_cmd = std::process::Command::new(if use_nice { "/usr/bin/nice" } else { "bash" });
    if use_nice {
        std_cmd.arg("-n").arg("10").arg("bash");
    }
    std_cmd
        .arg("-lc")
        .arg(&req.command)
        .current_dir(slot_path)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .process_group(0);

    let mut child = tokio::process::Command::from(std_cmd)
        .kill_on_drop(true)
        .spawn()
        .map_err(|e| ApiError::internal(format!("failed to spawn command: {e}")))?;
    // With `process_group(0)` the child is its own group leader, so its pid
    // doubles as the pgid we signal for timeout/cancel.
    let pgid = child.id().map(|id| id as i32).ok_or_else(|| {
        ApiError::internal("spawned command has no pid".to_string())
    })?;
    let killed_flag = Arc::new(AtomicBool::new(false));
    {
        let mut running = state.running.lock().await;
        running.insert(
            slot,
            RunningEntry {
                pgid,
                killed: killed_flag.clone(),
                is_build: req.kind == ExecKind::Build,
            },
        );
    }

    let stdout = child.stdout.take().ok_or_else(|| {
        ApiError::internal("spawned command has no stdout pipe".to_string())
    })?;
    let stderr = child.stderr.take().ok_or_else(|| {
        ApiError::internal("spawned command has no stderr pipe".to_string())
    })?;
    // Drain concurrently: a child writing more than the pipe buffer must not
    // block forever while we wait for its exit.
    let drain_deadline = Instant::now()
        + Duration::from_secs(req.timeout_secs)
        + STDIO_DRAIN_GRACE;
    let out_task = tokio::spawn(drain_bounded(stdout, drain_deadline));
    let err_task = tokio::spawn(drain_bounded(stderr, drain_deadline));

    // Wait for exit or timeout; on timeout SIGKILL the whole process group
    // (daemons that double-fork out of the group are handled separately for
    // builds via `./gradlew --stop` below).
    let mut timed_out = false;
    let wait_status = tokio::select! {
        status = child.wait() => status.map_err(|e| {
            ApiError::internal(format!("failed to wait for command: {e}"))
        })?,
        _ = tokio::time::sleep(Duration::from_secs(req.timeout_secs)) => {
            timed_out = true;
            kill_process_group(pgid);
            child.wait().await.map_err(|e| {
                ApiError::internal(format!("failed to reap timed-out command: {e}"))
            })?
        }
    };
    let (out_buf, out_cut) = out_task.await.unwrap_or_default();
    let (err_buf, err_cut) = err_task.await.unwrap_or_default();

    let was_killed = killed_flag.load(Ordering::SeqCst);
    {
        let mut running = state.running.lock().await;
        running.remove(&slot);
    }

    let exit_code = if timed_out || was_killed {
        None
    } else {
        wait_status.code()
    };
    let (stdout_str, stdout_trunc) = truncate_bytes(&out_buf, OUTPUT_CAP_BYTES);
    let (stderr_str, stderr_trunc) = truncate_bytes(&err_buf, OUTPUT_CAP_BYTES);

    // Gradle/Kotlin daemons detach from the process group, so killpg alone
    // does not free their RAM. Ask Gradle to stop them after a killed build.
    if req.kind == ExecKind::Build && (timed_out || was_killed) {
        gradle_stop(slot_path).await;
    }

    Ok(ExecResponse {
        exit_code,
        stdout: stdout_str,
        stderr: stderr_str,
        truncated: stdout_trunc || stderr_trunc || out_cut || err_cut,
        timed_out,
        killed: was_killed && !timed_out,
        queued_ms: 0,
        duration_ms: started.elapsed().as_millis().min(u128::from(u64::MAX)) as u64,
    })
}

async fn gradle_stop(slot_path: &Path) {
    if !slot_path.join("gradlew").exists() {
        return;
    }
    let mut cmd = tokio::process::Command::new("bash");
    cmd.arg("-lc")
        .arg("./gradlew --stop")
        .current_dir(slot_path)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true);
    if let Ok(mut child) = cmd.spawn() {
        let _ = tokio::time::timeout(GRADLE_STOP_TIMEOUT, child.wait()).await;
    }
}

pub async fn cancel_slot(state: &Arc<AppState>, slot: u32) -> bool {
    let running = state.running.lock().await;
    match running.get(&slot) {
        Some(entry) => {
            entry.killed.store(true, Ordering::SeqCst);
            kill_process_group(entry.pgid);
            true
        }
        None => false,
    }
}

pub async fn kill_build(state: &Arc<AppState>) -> bool {
    let slot = { *state.build_slot.lock().await };
    match slot {
        Some(slot) => {
            let running = state.running.lock().await;
            match running.get(&slot) {
                Some(entry) if entry.is_build => {
                    entry.killed.store(true, Ordering::SeqCst);
                    kill_process_group(entry.pgid);
                    true
                }
                _ => false,
            }
        }
        None => false,
    }
}

pub async fn read_slot_file(
    state: &Arc<AppState>,
    slot: u32,
    req: &ReadRequest,
) -> Result<ReadResponse, ApiError> {
    let root = slot_root(state, slot);
    let path = resolve_in_slot(&root, &req.path).map_err(ApiError::not_found)?;
    if path.is_dir() {
        return Err(ApiError::bad_request(format!(
            "{:?} is a directory",
            req.path
        )));
    }
    let content = std::fs::read(&path)
        .map_err(|e| ApiError::not_found(format!("cannot read {:?}: {e}", req.path)))?;
    let text = String::from_utf8_lossy(&content).into_owned();
    let lines: Vec<&str> = text.lines().collect();
    let total_lines = lines.len();
    let start = req.start_line.unwrap_or(1).max(1);
    let max_lines = req
        .max_lines
        .unwrap_or(READ_DEFAULT_MAX_LINES)
        .min(READ_HARD_MAX_LINES);
    let selected: Vec<&str> = if start > lines.len() {
        Vec::new()
    } else {
        lines[(start - 1)..].iter().take(max_lines).copied().collect()
    };
    let truncated = lines.len() > (start - 1) + selected.len();
    Ok(ReadResponse {
        content: selected.join("\n"),
        total_lines,
        truncated,
    })
}

pub async fn write_slot_file(
    state: &Arc<AppState>,
    slot: u32,
    req: &WriteRequest,
) -> Result<(), ApiError> {
    let root = slot_root(state, slot);
    let path = resolve_in_slot(&root, &req.path).map_err(ApiError::bad_request)?;
    if path.is_dir() {
        return Err(ApiError::bad_request(format!(
            "{:?} is a directory",
            req.path
        )));
    }
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| ApiError::internal(format!("cannot create parent dirs: {e}")))?;
    }
    std::fs::write(&path, &req.content)
        .map_err(|e| ApiError::internal(format!("cannot write {:?}: {e}", req.path)))?;
    Ok(())
}

pub async fn edit_slot_file(
    state: &Arc<AppState>,
    slot: u32,
    req: &EditRequest,
) -> Result<(), ApiError> {
    let root = slot_root(state, slot);
    let path = resolve_in_slot(&root, &req.path).map_err(ApiError::bad_request)?;
    let content = std::fs::read_to_string(&path)
        .map_err(|e| ApiError::not_found(format!("cannot read {:?}: {e}", req.path)))?;
    let updated = apply_edit(&content, &req.old, &req.new).map_err(ApiError::conflict)?;
    std::fs::write(&path, updated)
        .map_err(|e| ApiError::internal(format!("cannot write {:?}: {e}", req.path)))?;
    Ok(())
}

pub async fn list_slot_dir(
    state: &Arc<AppState>,
    slot: u32,
    req: &ListRequest,
) -> Result<ListResponse, ApiError> {
    let root = slot_root(state, slot);
    let dir: PathBuf = if req.path.is_empty() || req.path == "." {
        root.clone()
    } else {
        resolve_in_slot(&root, &req.path).map_err(ApiError::bad_request)?
    };
    let read_dir =
        std::fs::read_dir(&dir).map_err(|e| ApiError::not_found(format!("cannot list: {e}")))?;
    let mut entries: Vec<String> = Vec::new();
    for entry in read_dir {
        let entry = entry.map_err(|e| ApiError::internal(format!("cannot list: {e}")))?;
        let name = entry.file_name().to_string_lossy().into_owned();
        let file_type = entry
            .file_type()
            .map_err(|e| ApiError::internal(format!("cannot stat entry: {e}")))?;
        if file_type.is_dir() {
            entries.push(format!("{name}/"));
        } else {
            entries.push(name);
        }
        if entries.len() >= LIST_MAX_ENTRIES {
            break;
        }
    }
    entries.sort();
    // `truncated` is approximate: we stopped early at the cap.
    let truncated = entries.len() >= LIST_MAX_ENTRIES;
    Ok(ListResponse { entries, truncated })
}

const SEARCH_EXCLUDES: &[&str] = &[
    "!build/",
    "!target/",
    "!.gradle/",
    "!.kotlin/",
    "!metadata_data/photos/",
    "!third_party/",
    "!*.onnx",
    "!*.log",
];

pub async fn search_slot(
    state: &Arc<AppState>,
    slot: u32,
    req: &SearchRequest,
) -> Result<SearchResponse, ApiError> {
    let root = slot_root(state, slot);
    let cwd: PathBuf = match &req.path {
        Some(p) if !p.is_empty() && *p != "." => {
            resolve_in_slot(&root, p).map_err(ApiError::bad_request)?
        }
        _ => root.clone(),
    };
    if !cwd.exists() {
        return Err(ApiError::not_found(
            "search path does not exist".to_string(),
        ));
    }
    let mut cmd = tokio::process::Command::new("rg");
    cmd.arg("--line-number")
        .arg("--no-heading")
        .arg("--max-columns")
        .arg("300");
    if req.fixed_string {
        cmd.arg("--fixed-strings");
    }
    if let Some(glob) = &req.glob {
        cmd.arg("--glob").arg(glob);
    }
    for excl in SEARCH_EXCLUDES {
        cmd.arg("--glob").arg(excl);
    }
    cmd.arg("--").arg(&req.pattern);
    if cwd != root {
        let rel = cwd
            .strip_prefix(&root)
            .map_err(|e| ApiError::internal(format!("search path escaped slot root: {e}")))?;
        cmd.arg(rel);
    }
    cmd.current_dir(&root)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    let child = cmd.spawn().map_err(|e| {
        ApiError::internal(format!("failed to spawn rg (is ripgrep installed?): {e}"))
    })?;
    let rg_pid = child.id();
    let out = match tokio::time::timeout(SEARCH_TIMEOUT, child.wait_with_output()).await {
        Ok(Ok(out)) => out,
        Ok(Err(e)) => return Err(ApiError::internal(format!("failed to wait for rg: {e}"))),
        Err(_) => {
            // wait_with_output consumed the Child; kill by pid (leaf process).
            if let Some(pid) = rg_pid {
                unsafe {
                    libc::kill(pid as i32, libc::SIGKILL);
                }
            }
            return Err(ApiError::internal("rg search timed out".to_string()));
        }
    };
    match out.status.code() {
        // 0 = matches, 1 = no matches.
        Some(0) | Some(1) => {}
        // The include glob filtered out every file: not a failure, just no
        // matches. (Other exit-2 causes, e.g. a bad regex, still 500 below.)
        Some(2)
            if String::from_utf8_lossy(&out.stderr).contains("No files were searched") =>
        {
            return Ok(SearchResponse {
                matches: String::new(),
                truncated: false,
            });
        }
        other => {
            return Err(ApiError::internal(format!(
                "rg failed (exit {other:?}): {}",
                String::from_utf8_lossy(&out.stderr).trim()
            )));
        }
    }
    let (matches, truncated) = truncate_bytes(&out.stdout, OUTPUT_CAP_BYTES);
    Ok(SearchResponse { matches, truncated })
}

pub async fn diff_slot(state: &Arc<AppState>, slot: u32) -> Result<DiffResponse, ApiError> {
    let slot_path = slot_root(state, slot);
    if !slot_path.join(".git").is_dir() {
        return Err(ApiError::not_found(
            "slot is not prepared; run prepare first".to_string(),
        ));
    }
    let base_sha = read_base_sha(&slot_path)?;
    let out = run_git(&state.mirror, Some(&slot_path), &["add", "-A"]).await?;
    check_git_output(&out, "git add")?;
    let out = run_git(
        &state.mirror,
        Some(&slot_path),
        &["diff", "--no-color", "--stat", &base_sha, "--"],
    )
    .await?;
    let stat_raw = check_git_output(&out, "git diff --stat")?;
    let out = run_git(
        &state.mirror,
        Some(&slot_path),
        &["diff", "--no-color", &base_sha, "--"],
    )
    .await?;
    let diff_raw = check_git_output(&out, "git diff")?;
    let (stat, stat_trunc) = truncate_bytes(stat_raw.as_bytes(), DIFF_STAT_CAP_BYTES);
    let (diff, diff_trunc) = truncate_bytes(diff_raw.as_bytes(), DIFF_CAP_BYTES);
    Ok(DiffResponse {
        stat,
        diff,
        truncated: stat_trunc || diff_trunc,
    })
}

pub async fn commit_slot(
    state: &Arc<AppState>,
    slot: u32,
    req: &CommitRequest,
) -> Result<String, ApiError> {
    let slot_path = slot_root(state, slot);
    if !slot_path.join(".git").is_dir() {
        return Err(ApiError::not_found(
            "slot is not prepared; run prepare first".to_string(),
        ));
    }
    let out = run_git(&state.mirror, Some(&slot_path), &["add", "-A"]).await?;
    check_git_output(&out, "git add")?;
    let out = run_git(
        &state.mirror,
        Some(&slot_path),
        &["status", "--porcelain"],
    )
    .await?;
    let status = check_git_output(&out, "git status")?;
    if status.is_empty() {
        return Err(ApiError::conflict("nothing to commit".to_string()));
    }
    let out = run_git(
        &state.mirror,
        Some(&slot_path),
        &["commit", "-m", &req.message],
    )
    .await?;
    check_git_output(&out, "git commit")?;
    let out = run_git(&state.mirror, Some(&slot_path), &["rev-parse", "HEAD"]).await?;
    check_git_output(&out, "git rev-parse")
}

pub async fn bundle_slot(state: &Arc<AppState>, slot: u32) -> Result<(String, Vec<u8>), ApiError> {
    let slot_path = slot_root(state, slot);
    if !slot_path.join(".git").is_dir() {
        return Err(ApiError::not_found(
            "slot is not prepared; run prepare first".to_string(),
        ));
    }
    let base_sha = read_base_sha(&slot_path)?;
    let out = run_git(
        &state.mirror,
        Some(&slot_path),
        &["branch", "--show-current"],
    )
    .await?;
    let branch = check_git_output(&out, "git branch --show-current")?;
    if branch.is_empty() {
        return Err(ApiError::conflict(
            "slot is not on a branch; run prepare first".to_string(),
        ));
    }
    let out = run_git(&state.mirror, Some(&slot_path), &["rev-parse", "HEAD"]).await?;
    let head = check_git_output(&out, "git rev-parse")?;
    if head == base_sha {
        return Err(ApiError::conflict(
            "no commits on the branch; nothing to bundle".to_string(),
        ));
    }
    let range = format!("{base_sha}..{branch}");
    let out = run_git(
        &state.mirror,
        Some(&slot_path),
        &["bundle", "create", "-", &range],
    )
    .await?;
    if !out.status.success() {
        return Err(ApiError::conflict(format!(
            "git bundle failed: {}",
            String::from_utf8_lossy(&out.stderr).trim()
        )));
    }
    Ok((branch, out.stdout))
}

// ---------------------------------------------------------------------------
// HTTP layer
// ---------------------------------------------------------------------------

fn parse_json<T: serde::de::DeserializeOwned>(body: &Bytes) -> Result<T, ApiError> {
    serde_json::from_slice(body)
        .map_err(|e| ApiError::bad_request(format!("invalid JSON body: {e}")))
}

async fn h_prepare(
    State(state): State<Arc<AppState>>,
    AxumPath(slot_raw): AxumPath<String>,
    body: Bytes,
) -> Result<Json<PrepareResponse>, ErrTuple> {
    let slot = parse_slot(&state, &slot_raw).map_err(to_err_tuple)?;
    let req: PrepareRequest = parse_json(&body).map_err(to_err_tuple)?;
    let base_sha = prepare_slot(&state, slot, &req.branch)
        .await
        .map_err(to_err_tuple)?;
    Ok(Json(PrepareResponse { base_sha }))
}

async fn h_exec(
    State(state): State<Arc<AppState>>,
    AxumPath(slot_raw): AxumPath<String>,
    body: Bytes,
) -> Result<Json<ExecResponse>, ErrTuple> {
    let slot = parse_slot(&state, &slot_raw).map_err(to_err_tuple)?;
    let req: ExecRequest = parse_json(&body).map_err(to_err_tuple)?;
    let resp = exec_in_slot(&state, slot, &req).await.map_err(to_err_tuple)?;
    Ok(Json(resp))
}

async fn h_read(
    State(state): State<Arc<AppState>>,
    AxumPath(slot_raw): AxumPath<String>,
    body: Bytes,
) -> Result<Json<ReadResponse>, ErrTuple> {
    let slot = parse_slot(&state, &slot_raw).map_err(to_err_tuple)?;
    let req: ReadRequest = parse_json(&body).map_err(to_err_tuple)?;
    let resp = read_slot_file(&state, slot, &req).await.map_err(to_err_tuple)?;
    Ok(Json(resp))
}

async fn h_write(
    State(state): State<Arc<AppState>>,
    AxumPath(slot_raw): AxumPath<String>,
    body: Bytes,
) -> Result<Json<serde_json::Value>, ErrTuple> {
    let slot = parse_slot(&state, &slot_raw).map_err(to_err_tuple)?;
    let req: WriteRequest = parse_json(&body).map_err(to_err_tuple)?;
    write_slot_file(&state, slot, &req).await.map_err(to_err_tuple)?;
    Ok(Json(serde_json::json!({})))
}

async fn h_edit(
    State(state): State<Arc<AppState>>,
    AxumPath(slot_raw): AxumPath<String>,
    body: Bytes,
) -> Result<Json<serde_json::Value>, ErrTuple> {
    let slot = parse_slot(&state, &slot_raw).map_err(to_err_tuple)?;
    let req: EditRequest = parse_json(&body).map_err(to_err_tuple)?;
    edit_slot_file(&state, slot, &req).await.map_err(to_err_tuple)?;
    Ok(Json(serde_json::json!({})))
}

async fn h_list(
    State(state): State<Arc<AppState>>,
    AxumPath(slot_raw): AxumPath<String>,
    body: Bytes,
) -> Result<Json<ListResponse>, ErrTuple> {
    let slot = parse_slot(&state, &slot_raw).map_err(to_err_tuple)?;
    let req: ListRequest = parse_json(&body).map_err(to_err_tuple)?;
    let resp = list_slot_dir(&state, slot, &req).await.map_err(to_err_tuple)?;
    Ok(Json(resp))
}

async fn h_search(
    State(state): State<Arc<AppState>>,
    AxumPath(slot_raw): AxumPath<String>,
    body: Bytes,
) -> Result<Json<SearchResponse>, ErrTuple> {
    let slot = parse_slot(&state, &slot_raw).map_err(to_err_tuple)?;
    let req: SearchRequest = parse_json(&body).map_err(to_err_tuple)?;
    let resp = search_slot(&state, slot, &req).await.map_err(to_err_tuple)?;
    Ok(Json(resp))
}

async fn h_diff(
    State(state): State<Arc<AppState>>,
    AxumPath(slot_raw): AxumPath<String>,
) -> Result<Json<DiffResponse>, ErrTuple> {
    let slot = parse_slot(&state, &slot_raw).map_err(to_err_tuple)?;
    let resp = diff_slot(&state, slot).await.map_err(to_err_tuple)?;
    Ok(Json(resp))
}

async fn h_commit(
    State(state): State<Arc<AppState>>,
    AxumPath(slot_raw): AxumPath<String>,
    body: Bytes,
) -> Result<Json<CommitResponse>, ErrTuple> {
    let slot = parse_slot(&state, &slot_raw).map_err(to_err_tuple)?;
    let req: CommitRequest = parse_json(&body).map_err(to_err_tuple)?;
    let sha = commit_slot(&state, slot, &req).await.map_err(to_err_tuple)?;
    Ok(Json(CommitResponse { sha }))
}

async fn h_bundle(
    State(state): State<Arc<AppState>>,
    AxumPath(slot_raw): AxumPath<String>,
) -> Result<impl IntoResponse, ErrTuple> {
    let slot = parse_slot(&state, &slot_raw).map_err(to_err_tuple)?;
    let (_branch, bytes) = bundle_slot(&state, slot).await.map_err(to_err_tuple)?;
    Ok((
        StatusCode::OK,
        [(header::CONTENT_TYPE, "application/octet-stream")],
        bytes,
    ))
}

async fn h_cancel(
    State(state): State<Arc<AppState>>,
    AxumPath(slot_raw): AxumPath<String>,
) -> Result<Json<KillResponse>, ErrTuple> {
    let slot = parse_slot(&state, &slot_raw).map_err(to_err_tuple)?;
    let killed = cancel_slot(&state, slot).await;
    Ok(Json(KillResponse { killed }))
}

async fn h_builds_kill(State(state): State<Arc<AppState>>) -> Json<KillResponse> {
    let killed = kill_build(&state).await;
    Json(KillResponse { killed })
}

async fn h_health(State(state): State<Arc<AppState>>) -> Json<HealthResponse> {
    Json(HealthResponse {
        slots: state.num_slots,
        build_running: state.build_running.load(Ordering::SeqCst),
    })
}

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/v1/slots/:slot/prepare", post(h_prepare))
        .route("/v1/slots/:slot/exec", post(h_exec))
        .route("/v1/slots/:slot/read", post(h_read))
        .route("/v1/slots/:slot/write", post(h_write))
        .route("/v1/slots/:slot/edit", post(h_edit))
        .route("/v1/slots/:slot/list", post(h_list))
        .route("/v1/slots/:slot/search", post(h_search))
        .route("/v1/slots/:slot/diff", post(h_diff))
        .route("/v1/slots/:slot/commit", post(h_commit))
        .route("/v1/slots/:slot/bundle", get(h_bundle))
        .route("/v1/slots/:slot/cancel", post(h_cancel))
        .route("/v1/builds/kill", post(h_builds_kill))
        .route("/v1/health", get(h_health))
        .with_state(state)
}

pub async fn run() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let state = Arc::new(AppState::from_env());
    std::fs::create_dir_all(&state.slots_dir)?;
    let addr = AppState::listen_addr();
    let listener = tokio::net::TcpListener::bind(&addr).await?;
    eprintln!(
        "agent-runner: listening on {addr} (slots={} dir={} mirror={})",
        state.num_slots,
        state.slots_dir.display(),
        state.mirror.display()
    );
    axum::serve(listener, router(state)).await?;
    Ok(())
}

#[cfg(test)]
mod tests;
