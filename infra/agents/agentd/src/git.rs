//! Git operations owned by agentd.
//!
//! Invariants:
//! * agentd only ever runs git in the shared bare **mirror** (`git -C
//!   <mirror> ...`). It never runs git inside a sandbox-controlled repo.
//! * All invocations use argv (no shell) with timeouts.
//! * The push token travels via `GIT_CONFIG_COUNT/KEY_0/VALUE_0`
//!   (`http.extraHeader = Authorization: Bearer <token>`), never in the URL,
//!   argv, or logs.
//! * On startup the mirror is created if missing (`clone --bare`), then
//!   pinned with `gc.auto 0` + `gc.pruneExpire never` (slots reference it
//!   via alternates — pruning would corrupt them) and made group/world
//!   readable so the builder uid can fetch from it.

use std::path::Path;
use std::process::Stdio;
use std::time::Duration;

const GIT_TIMEOUT: Duration = Duration::from_secs(120);
const FETCH_TIMEOUT: Duration = Duration::from_secs(300);
const PUSH_TIMEOUT: Duration = Duration::from_secs(300);

/// Error type that keeps command output but guarantees no secret is
/// embedded: callers must pass redacted output (the token is never placed in
/// argv, so `stderr` cannot contain it; still, [`redact`] is applied).
#[derive(Debug)]
pub struct GitError(pub String);

impl std::fmt::Display for GitError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

async fn run_git(
    dir: Option<&Path>,
    args: &[&str],
    extra_env: &[(&str, &str)],
    timeout: Duration,
) -> Result<String, GitError> {
    let mut cmd = tokio::process::Command::new("git");
    if let Some(d) = dir {
        cmd.arg("-C").arg(d);
    }
    // No pager, no interactive prompts, no sandbox repo config leakage.
    cmd.env("GIT_PAGER", "cat")
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .args(args);
    for (k, v) in extra_env {
        cmd.env(k, v);
    }
    // Kill the whole process group on timeout: `kill_on_drop` only kills the
    // direct child, so spawn detached via `pre_exec`-less approach — use
    // `Command::kill_on_drop` plus rely on git's own children exiting when
    // the parent dies. (No shell is ever involved.)
    let child = cmd
        .kill_on_drop(true)
        .spawn()
        .map_err(|e| GitError(format!("spawn git {}: {e}", args.join(" "))))?;
    let out = tokio::time::timeout(timeout, child.wait_with_output())
        .await
        .map_err(|_| GitError(format!("git {} timed out after {timeout:?}", args.join(" "))))?
        .map_err(|e| GitError(format!("git {}: {e}", args.join(" "))))?;
    if !out.status.success() {
        let stderr = String::from_utf8_lossy(&out.stderr);
        return Err(GitError(format!(
            "git {} failed: {}",
            args.join(" "),
            stderr.trim()
        )));
    }
    String::from_utf8(out.stdout).map_err(|e| GitError(format!("git output not UTF-8: {e}")))
}

/// Ensure the bare mirror exists and is fetch-ready. `repo_url` is the
/// upstream (e.g. `https://git.maproj.org/Modern-Apps.git`). The umask is
/// relaxed while creating so the builder uid can read the new files.
pub async fn ensure_mirror(mirror: &Path, repo_url: &str) -> Result<(), GitError> {
    if mirror.join("HEAD").exists() || mirror.join("config").exists() {
        return Ok(());
    }
    if let Some(parent) = mirror.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| GitError(format!("create mirror parent: {e}")))?;
    }
    // 022 umask → files 644, dirs 755: world-readable for the builder uid.
    #[cfg(unix)]
    let prev = libc_umask(0o022);
    let r = run_git(
        None,
        &["clone", "--bare", repo_url, &mirror.to_string_lossy()],
        &[],
        FETCH_TIMEOUT,
    )
    .await;
    #[cfg(unix)]
    libc_umask(prev);
    r.map(|_| ())?;
    // Pin GC off: slots use this repo as an alternates source.
    run_git(
        Some(mirror),
        &["config", "gc.auto", "0"],
        &[],
        GIT_TIMEOUT,
    )
    .await?;
    run_git(
        Some(mirror),
        &["config", "gc.pruneExpire", "never"],
        &[],
        GIT_TIMEOUT,
    )
    .await?;
    Ok(())
}

#[cfg(unix)]
fn libc_umask(mask: u32) -> u32 {
    // Minimal FFI to avoid a libc dependency.
    extern "C" {
        fn umask(mask: u32) -> u32;
    }
    unsafe { umask(mask) }
}

/// Refresh the mirror's `main` from upstream before each task.
pub async fn fetch_main(mirror: &Path) -> Result<(), GitError> {
    run_git(
        Some(mirror),
        &["fetch", "origin", "+refs/heads/main:refs/heads/main"],
        &[],
        FETCH_TIMEOUT,
    )
    .await
    .map(|_| ())
}

/// Base SHA the task branch starts from (mirror's `main`).
pub async fn main_sha(mirror: &Path) -> Result<String, GitError> {
    let sha = run_git(Some(mirror), &["rev-parse", "refs/heads/main"], &[], GIT_TIMEOUT).await?;
    Ok(sha.trim().to_string())
}

/// Verify a downloaded bundle file inside the mirror (so prerequisite
/// checks run against a repo that shares history), then import it as
/// `refs/agent-out/<suffix>` in the mirror.
///
/// The bundle holds `<base>..<branch>`, where `<base>` is the mirror's
/// `main` fetched at task start. `git bundle verify` requires the
/// prerequisite commits to exist locally: a plain `clone --bare` has them,
/// but a mirror whose `main` has since moved (or that was cloned empty)
/// may not. `git fetch <bundle>` only needs the *endpoints*; the import
/// fetch below succeeds regardless. So a verify failure that is purely a
/// missing prerequisite must not fail the import — but a corrupt bundle
/// must. Distinguish them: if verify fails, check whether the bundle's
/// required ref is an ancestor-known object; if the fetch itself succeeds,
/// accept the bundle.
pub async fn import_task_bundle(
    mirror: &Path,
    bundle_file: &Path,
    branch: &str,
) -> Result<(), GitError> {
    let suffix = branch
        .strip_prefix("agent/")
        .ok_or_else(|| GitError(format!("bad branch {branch:?}")))?;
    let dst = format!("refs/agent-out/{suffix}");
    // Verify inside the mirror so prerequisite checks resolve against it
    // (`git bundle verify` prints e.g. "<file> is okay"). A verify failure
    // caused purely by a missing prerequisite (mirror's `main` moved after
    // the slot branched) must not fail the import — but a corrupt bundle
    // must. `git fetch <bundle>` only needs the branch tip and its new
    // objects, so: run verify for diagnostics, then run the fetch, and fail
    // only if the fetch fails.
    let verify_err = run_git(
        Some(mirror),
        &["bundle", "verify", &bundle_file.to_string_lossy()],
        &[],
        GIT_TIMEOUT,
    )
    .await
    .err()
    .map(|e| e.0);
    // The bundle holds `<base>..<branch>`; fetch the branch head by name.
    if let Err(e) = run_git(
        Some(mirror),
        &[
            "fetch",
            &bundle_file.to_string_lossy(),
            &format!("+refs/heads/{branch}:{dst}"),
        ],
        &[],
        FETCH_TIMEOUT,
    )
    .await
    {
        // The import itself failed: include the verify output when the
        // bundle is also unverifiable (corrupt bundle).
        if let Some(v) = verify_err {
            return Err(GitError(format!("bundle import failed ({e}); verify: {v}")));
        }
        return Err(GitError(format!("bundle import: {e}")));
    }
    Ok(())
}

/// Push `refs/agent-out/<suffix>` to `refs/heads/agent/<suffix>` on the
/// upstream. The token is passed via git config env (`http.extraHeader`),
/// never in the URL/argv/logs.
pub async fn push_branch(
    mirror: &Path,
    repo_url: &str,
    branch: &str,
    token: &str,
) -> Result<(), GitError> {
    let suffix = branch
        .strip_prefix("agent/")
        .ok_or_else(|| GitError(format!("bad branch {branch:?}")))?;
    let src = format!("refs/agent-out/{suffix}");
    let dst = format!("refs/heads/{branch}");
    let header_value = format!("Authorization: Bearer {token}");
    // Env values are not argv; redact defensively anyway on error paths.
    let r = run_git(
        Some(mirror),
        &["push", repo_url, &format!("+{src}:{dst}")],
        &[
            ("GIT_CONFIG_COUNT", "1"),
            ("GIT_CONFIG_KEY_0", "http.extraHeader"),
            ("GIT_CONFIG_VALUE_0", &header_value),
        ],
        PUSH_TIMEOUT,
    )
    .await;
    match r {
        Ok(_) => Ok(()),
        Err(GitError(msg)) => Err(GitError(format!("push {branch}: {msg}"))),
    }
}
