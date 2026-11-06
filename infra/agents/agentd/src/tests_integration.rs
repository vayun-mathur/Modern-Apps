//! In-process integration tests: mock OpenRouter + mock runner (real temp
//! git repos, real bundles) + the real agentd HTTP server + scheduler.
//!
//! Covers: full success flow (read → edit → build → finish, branch pushed
//! to the temp remote, token absent from logs/events), unauthenticated POST
//! rejection, wrong-Origin rejection, and release-signal build preemption.

#[cfg(test)]
mod integration {
    use std::collections::{HashMap, VecDeque};
    use std::net::SocketAddr;
    use std::path::{Path, PathBuf};
    use std::process::Stdio;
    use std::sync::{Arc, Mutex};

    use axum::extract::{Path as AxPath, State};
    use axum::http::{header, HeaderMap, StatusCode};
    use axum::response::IntoResponse;
    use axum::routing::{get, post};
    use tokio::process::Command as TokioCommand;

    use crate::state::AppState;
    use crate::store::Store;

    // ---------- tiny git helper (argv only, no shell) ----------

    async fn git(dir: &Path, args: &[&str]) -> String {
        let out = TokioCommand::new("git")
            .arg("-C")
            .arg(dir)
            .args(args)
            .env("GIT_PAGER", "cat")
            .env("GIT_CONFIG_NOSYSTEM", "1")
            .env("GIT_CONFIG_GLOBAL", "/dev/null")
            .stdin(Stdio::null())
            .output()
            .await
            .expect("spawn git");
        assert!(
            out.status.success(),
            "git {} failed: {}",
            args.join(" "),
            String::from_utf8_lossy(&out.stderr)
        );
        String::from_utf8(out.stdout).unwrap()
    }

    async fn git_no_cwd(args: &[&str], workdir: &Path) -> String {
        let out = TokioCommand::new("git")
            .args(args)
            .current_dir(workdir)
            .env("GIT_PAGER", "cat")
            .env("GIT_CONFIG_NOSYSTEM", "1")
            .env("GIT_CONFIG_GLOBAL", "/dev/null")
            .stdin(Stdio::null())
            .output()
            .await
            .expect("spawn git");
        assert!(
            out.status.success(),
            "git {} failed: {}",
            args.join(" "),
            String::from_utf8_lossy(&out.stderr)
        );
        String::from_utf8(out.stdout).unwrap()
    }

    /// Build a temp bare "remote" with one commit (`main` + AGENTS.md files).
    async fn init_remote(tmp: &Path) -> PathBuf {
        let remote = tmp.join("remote.git");
        git_no_cwd(
            &[
                "init",
                "--bare",
                "-b",
                "main",
                remote.to_str().unwrap(),
            ],
            tmp,
        )
        .await;
        let work = tmp.join("seed");
        git_no_cwd(
            &[
                "clone",
                remote.to_str().unwrap(),
                work.to_str().unwrap(),
            ],
            tmp,
        )
        .await;
        git_no_cwd(&["config", "user.email", "t@t"], &work).await;
        git_no_cwd(&["config", "user.name", "t"], &work).await;
        std::fs::create_dir_all(work.join("notes")).unwrap();
        std::fs::write(work.join("AGENTS.md"), "ROOT RULES FOR TEST\n").unwrap();
        std::fs::write(work.join("notes/AGENTS.md"), "NOTES MODULE RULES\n").unwrap();
        std::fs::write(work.join("notes/file.txt"), "hello\n").unwrap();
        // A committed JUnit report with one failure, so `run_tests` has XML
        // to parse in every slot checkout. Committed → never pollutes diffs.
        let xml_dir = work.join("notes/build/test-results/testDevUnitTest");
        std::fs::create_dir_all(&xml_dir).unwrap();
        std::fs::write(
            xml_dir.join("TEST-com.example.GreetingTest.xml"),
            r#"<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.GreetingTest" tests="3" skipped="0" failures="1" errors="0">
  <testcase name="saysHello" classname="com.example.GreetingTest" time="0.01"/>
  <testcase name="shoutsGreeting" classname="com.example.GreetingTest" time="0.02">
    <failure message="expected:&lt;HELLO&gt; but was:&lt;hello&gt;">AssertionFailedError: expected:&lt;HELLO&gt; but was:&lt;hello&gt;
	at com.example.GreetingTest.shoutsGreeting(GreetingTest.kt:42)
</failure>
  </testcase>
  <testcase name="emptyIsEmpty" classname="com.example.GreetingTest" time="0.0"/>
</testsuite>
"#,
        )
        .unwrap();
        git_no_cwd(&["add", "-A"], &work).await;
        git_no_cwd(&["commit", "-m", "seed commit"], &work).await;
        git_no_cwd(&["push", "origin", "main"], &work).await;
        remote
    }

    // ---------- mock OpenRouter (scripted responses) ----------

    #[derive(Clone)]
    struct MockOr {
        script: Arc<Mutex<VecDeque<serde_json::Value>>>,
        seen: Arc<Mutex<Vec<serde_json::Value>>>,
    }

    async fn mock_or_chat(
        State(st): State<MockOr>,
        headers: HeaderMap,
        body: axum::Json<serde_json::Value>,
    ) -> impl IntoResponse {
        // `usage: {include: true}` must be requested.
        let usage_ok = body.get("usage").and_then(|u| u.get("include")).and_then(|v| v.as_bool()).unwrap_or(false);
        assert!(usage_ok, "agentd must send usage:{{include:true}}");
        assert!(
            headers.contains_key(header::AUTHORIZATION),
            "auth header present"
        );
        st.seen.lock().unwrap().push(body.0.clone());
        let next = st.script.lock().unwrap().pop_front().unwrap_or_else(|| {
            serde_json::json!({
                "choices": [{"message": {"role": "assistant", "content": "stuck"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1}
            })
        });
        axum::Json(next)
    }

    fn tool_resp(id: &str, name: &str, args: serde_json::Value, text: &str) -> serde_json::Value {
        serde_json::json!({
            "choices": [{"message": {
                "role": "assistant", "content": text,
                "tool_calls": [{
                    "id": id, "type": "function",
                    "function": {"name": name, "arguments": serde_json::to_string(&args).unwrap()}
                }]
            }}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 5, "cost": 0.0001}
        })
    }

    fn success_script() -> Vec<serde_json::Value> {
        vec![
            tool_resp("c1", "read_file", serde_json::json!({"path": "notes/file.txt"}), "reading"),
            tool_resp(
                "c2",
                "edit_file",
                serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "hello world\n"}),
                "editing",
            ),
            tool_resp(
                "c3",
                "build",
                serde_json::json!({"command": "./gradlew :notes:compileDevKotlin"}),
                "building",
            ),
            tool_resp(
                "c4",
                "finish",
                serde_json::json!({
                    "summary": "Changed the greeting.",
                    "commit_message": "notes: change greeting\n\nSwitched the greeting text per the task."
                }),
                "finishing",
            ),
        ]
    }

    async fn serve_mock_or(script: Vec<serde_json::Value>) -> (String, MockOr) {
        let st = MockOr {
            script: Arc::new(Mutex::new(script.into())),
            seen: Arc::new(Mutex::new(Vec::new())),
        };
        let app = axum::Router::new()
            .route("/chat/completions", post(mock_or_chat))
            .with_state(st.clone());
        let lis = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = lis.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(lis, app).await.unwrap() });
        (format!("http://{addr}"), st)
    }

    // ---------- mock runner (real git checkout per slot) ----------

    /// One canned exec response: commands containing `pattern` get
    /// (`exit_code`, `stdout`, `stderr`).
    type CannedExec = (String, i32, String, String);

    struct SlotState {
        dir: PathBuf,
        branch: String,
        base_sha: String,
    }

    #[derive(Clone)]
    struct MockRunner {
        slots: Arc<Mutex<std::collections::HashMap<u32, SlotState>>>,
        /// Repo slots clone from (agentd's mirror in the harness, so WIP refs
        /// imported by agentd are fetchable) and record of every prepare as
        /// `(slot, branch)` — resume may migrate a task to another slot.
        clone_source: PathBuf,
        prepares: Arc<Mutex<Vec<(u32, String)>>>,
        build_seen: Arc<Mutex<Vec<String>>>,
        build_should_preempt: bool,
        /// Canned exec responses, first-match wins: `(substring, exit_code,
        /// stdout, stderr)`. Checked before the default success response so
        /// tests can simulate build/test failures per command pattern.
        canned_exec: Arc<Mutex<Vec<CannedExec>>>,
        /// Unique per-harness scratch dir. Slot checkouts live under it so
        /// parallel tests (same process, distinct harnesses) never share a
        /// slot directory. (An earlier revision used a fixed
        /// `$TMPDIR/mockslot-{pid}-{slot}` path; two harnesses preparing the
        /// same slot concurrently deleted each other's checkout mid-task.)
        scratch: PathBuf,
    }

    impl MockRunner {
        /// Add a canned exec response for commands containing `pattern`.
        fn canned(&self, pattern: &str, exit_code: i32, stdout: &str, stderr: &str) {
            self.canned_exec.lock().unwrap().push((
                pattern.to_string(),
                exit_code,
                stdout.to_string(),
                stderr.to_string(),
            ));
        }
    }

    #[allow(dead_code)]
    fn parse_slot(path: &str, suffix: &str) -> Option<u32> {
        // "/v1/slots/{slot}/{suffix}"
        let rest = path.strip_prefix("/v1/slots/")?;
        let (slot, tail) = rest.split_once('/')?;
        if !tail.starts_with(suffix) {
            return None;
        }
        slot.parse().ok()
    }
    async fn mock_prepare(
        State(st): State<MockRunner>,
        AxPath(slot): AxPath<u32>,
        body: axum::Json<serde_json::Value>,
    ) -> impl IntoResponse {
        let branch = body.get("branch").and_then(|v| v.as_str()).unwrap_or("").to_string();
        assert!(branch.starts_with("agent/"), "branch prefix");
        // Record which slot each prepare lands on (resume may migrate slots).
        st.prepares.lock().unwrap().push((slot, branch.clone()));
        // Per-harness scratch dir: parallel tests (same process, distinct
        // harnesses) must never share a slot checkout. A fixed
        // `$TMPDIR/mockslot-{pid}-{slot}` path let two harnesses preparing
        // the same slot concurrently delete each other's checkout mid-task.
        let dir = st.scratch.join(format!("slot-{slot}"));
        std::fs::create_dir_all(&st.scratch).unwrap();
        let _ = std::fs::remove_dir_all(&dir);
        git_no_cwd(
            &["clone", st.clone_source.to_str().unwrap(), dir.to_str().unwrap()],
            &st.scratch,
        )
        .await;
        git(&dir, &["config", "user.email", "t@t"]).await;
        git(&dir, &["config", "user.name", "t"]).await;
        // Resume branch: fetch the WIP ref from the clone source (agentd's
        // mirror) into the slot, check out the WIP sha, then reset --soft
        // the base so the work reappears as uncommitted changes. Fresh
        // prepare: new branch from main.
        let base = if let Some(resume) = body.get("resume") {
            let base_sha = resume.get("base_sha").and_then(|v| v.as_str()).unwrap_or("").to_string();
            assert!(!base_sha.is_empty(), "resume needs base_sha");
            if let (Some(wip_ref), Some(wip_sha)) = (
                resume.get("wip_ref").and_then(|v| v.as_str()),
                resume.get("wip_sha").and_then(|v| v.as_str()),
            ) {
                // Explicit fetch of the exact ref (plain `fetch origin` would
                // not pick up `refs/agent-wip/*`).
                git(&dir, &["fetch", "origin", &format!("+{wip_ref}:{wip_ref}")]).await;
                let got = git(&dir, &["rev-parse", wip_ref]).await;
                assert_eq!(got.trim(), wip_sha, "WIP ref sha must match");
                git(&dir, &["checkout", "-B", &branch, wip_sha]).await;
                git(&dir, &["reset", "--soft", &base_sha]).await;
            } else {
                git(&dir, &["checkout", "-B", &branch, &base_sha]).await;
            }
            // `.git/agent-base` records the original base, like the runner.
            std::fs::write(dir.join(".git/agent-base"), &base_sha).unwrap();
            base_sha
        } else {
            git(&dir, &["checkout", "-b", &branch]).await;
            let base = git(&dir, &["rev-parse", "HEAD"]).await;
            base.trim().to_string()
        };
        st.slots.lock().unwrap().insert(
            slot,
            SlotState { dir, branch, base_sha: base.clone() },
        );
        (
            StatusCode::OK,
            axum::Json(serde_json::json!({"base_sha": base})),
        )
    }

    async fn mock_read(
        State(st): State<MockRunner>,
        AxPath(slot): AxPath<u32>,
        body: axum::Json<serde_json::Value>,
    ) -> impl IntoResponse {
        let dir = st.slots.lock().unwrap().get(&slot).unwrap().dir.clone();
        let rel = body.get("path").and_then(|v| v.as_str()).unwrap_or("");
        let content = std::fs::read_to_string(dir.join(rel)).unwrap_or_else(|_| "MISSING".into());
        let total = content.lines().count();
        axum::Json(serde_json::json!({"content": content, "total_lines": total, "truncated": false}))
    }

    async fn mock_write(
        State(st): State<MockRunner>,
        AxPath(slot): AxPath<u32>,
        body: axum::Json<serde_json::Value>,
    ) -> impl IntoResponse {
        let dir = st.slots.lock().unwrap().get(&slot).unwrap().dir.clone();
        let rel = body.get("path").and_then(|v| v.as_str()).unwrap_or("");
        let content = body.get("content").and_then(|v| v.as_str()).unwrap_or("");
        let p = dir.join(rel);
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(p, content).unwrap();
        (StatusCode::OK, axum::Json(serde_json::json!({})))
    }

    async fn mock_edit(
        State(st): State<MockRunner>,
        AxPath(slot): AxPath<u32>,
        body: axum::Json<serde_json::Value>,
    ) -> impl IntoResponse {
        let dir = st.slots.lock().unwrap().get(&slot).unwrap().dir.clone();
        let rel = body.get("path").and_then(|v| v.as_str()).unwrap_or("");
        let old = body.get("old").and_then(|v| v.as_str()).unwrap_or("");
        let new = body.get("new").and_then(|v| v.as_str()).unwrap_or("");
        let p = dir.join(rel);
        let content = std::fs::read_to_string(&p).unwrap();
        if content.matches(old).count() != 1 {
            return (
                StatusCode::CONFLICT,
                axum::Json(serde_json::json!({"error": "old must occur exactly once"})),
            );
        }
        std::fs::write(p, content.replacen(old, new, 1)).unwrap();
        (StatusCode::OK, axum::Json(serde_json::json!({})))
    }

    async fn mock_exec(
        State(st): State<MockRunner>,
        AxPath(slot): AxPath<u32>,
        body: axum::Json<serde_json::Value>,
    ) -> impl IntoResponse {
        let cmd = body.get("command").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let kind = body.get("kind").and_then(|v| v.as_str()).unwrap_or("").to_string();
        st.build_seen.lock().unwrap().push(cmd.clone());
        if st.build_should_preempt && kind == "build" {
            // Simulate a release kill: killed=true like the real runner.
            return axum::Json(serde_json::json!({
                "exit_code": null, "stdout": "", "stderr": "killed",
                "truncated": false, "timed_out": false, "killed": true,
                "queued_ms": 0, "duration_ms": 5
            }));
        }
        {
            let canned = st.canned_exec.lock().unwrap().clone();
            for (pattern, exit_code, stdout, stderr) in &canned {
                if cmd.contains(pattern.as_str()) {
                    return axum::Json(serde_json::json!({
                        "exit_code": exit_code, "stdout": stdout, "stderr": stderr,
                        "truncated": false, "timed_out": false, "killed": false,
                        "queued_ms": 0, "duration_ms": 5
                    }));
                }
            }
        }
        if kind == "shell" {
            // Bind the dir in its own statement: holding the std MutexGuard
            // across the `.await` below would make the future !Send and break
            // axum's Handler bound.
            let dir = st.slots.lock().unwrap().get(&slot).map(|s| s.dir.clone());
            if let Some(dir) = dir {
                // Only allow the read-only commands agentd's structured tools
                // build (defense in depth: the harness never runs arbitrary
                // shell from these tests).
                let allowed = cmd.starts_with("git log ")
                    || cmd.starts_with("git show ")
                    || cmd.starts_with("git blame ")
                    || cmd.starts_with("rg --files ");
                if allowed {
                    let out = TokioCommand::new("bash")
                        .arg("-lc")
                        .arg(&cmd)
                        .current_dir(&dir)
                        .env("GIT_PAGER", "cat")
                        .env("GIT_CONFIG_NOSYSTEM", "1")
                        .env("GIT_CONFIG_GLOBAL", "/dev/null")
                        .stdin(Stdio::null())
                        .output()
                        .await
                        .unwrap();
                    return axum::Json(serde_json::json!({
                        "exit_code": out.status.code().unwrap_or(1),
                        "stdout": String::from_utf8_lossy(&out.stdout),
                        "stderr": String::from_utf8_lossy(&out.stderr),
                        "truncated": false, "timed_out": false, "killed": false,
                        "queued_ms": 0, "duration_ms": 5
                    }));
                }
            }
        }
        // Fake build success; run `true`-equivalent by touching nothing.
        axum::Json(serde_json::json!({
            "exit_code": 0, "stdout": format!("ok: {cmd}"),
            "stderr": "", "truncated": false, "timed_out": false,
            "killed": false, "queued_ms": 0, "duration_ms": 5
        }))
    }

    /// `list` backed by the real slot checkout (mirrors the runner: one
    /// level, dirs end with `/`, sorted). Needed by `run_tests`, which lists
    /// the JUnit XML dir.
    async fn mock_list(
        State(st): State<MockRunner>,
        AxPath(slot): AxPath<u32>,
        body: axum::Json<serde_json::Value>,
    ) -> impl IntoResponse {
        let dir = st.slots.lock().unwrap().get(&slot).unwrap().dir.clone();
        let rel = body.get("path").and_then(|v| v.as_str()).unwrap_or("");
        let target = dir.join(rel);
        let mut entries: Vec<String> = Vec::new();
        if let Ok(rd) = std::fs::read_dir(&target) {
            for entry in rd.flatten() {
                let name = entry.file_name().to_string_lossy().to_string();
                if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                    entries.push(format!("{name}/"));
                } else {
                    entries.push(name);
                }
            }
        }
        entries.sort();
        axum::Json(serde_json::json!({"entries": entries, "truncated": false}))
    }

    async fn mock_diff(
        State(st): State<MockRunner>,
        AxPath(slot): AxPath<u32>,
        _body: axum::Json<serde_json::Value>,
    ) -> impl IntoResponse {
        let dir = st.slots.lock().unwrap().get(&slot).unwrap().dir.clone();
        git(&dir, &["add", "-A"]).await;
        let diff = git(&dir, &["diff", "--cached"]).await;
        let stat = git(&dir, &["diff", "--cached", "--stat"]).await;
        axum::Json(serde_json::json!({"stat": stat, "diff": diff, "truncated": false}))
    }

    async fn mock_commit(
        State(st): State<MockRunner>,
        AxPath(slot): AxPath<u32>,
        body: axum::Json<serde_json::Value>,
    ) -> impl IntoResponse {
        let dir = st.slots.lock().unwrap().get(&slot).unwrap().dir.clone();
        let msg = body.get("message").and_then(|v| v.as_str()).unwrap_or("").to_string();
        git(&dir, &["add", "-A"]).await;
        // Identity comes from the slot repo's local config (set at prepare),
        // mirroring the real runner where the sandbox user has its own git
        // identity — agentd never injects one.
        let out = TokioCommand::new("git")
            .arg("-C")
            .arg(&dir)
            .arg("commit")
            .arg("-m")
            .arg(&msg)
            .env("GIT_PAGER", "cat")
            .env("GIT_CONFIG_NOSYSTEM", "1")
            .env("GIT_CONFIG_GLOBAL", "/dev/null")
            .stdin(Stdio::null())
            .output()
            .await
            .unwrap();
        if !out.status.success() {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                axum::Json(serde_json::json!({"error": format!("commit failed: {}", String::from_utf8_lossy(&out.stderr))})),
            );
        }
        let sha = git(&dir, &["rev-parse", "HEAD"]).await;
        (StatusCode::OK, axum::Json(serde_json::json!({"sha": sha.trim()})))
    }

    async fn mock_bundle(
        State(st): State<MockRunner>,
        AxPath(slot): AxPath<u32>,
    ) -> impl IntoResponse {
        let (dir, branch, base) = {
            let slots = st.slots.lock().unwrap();
            let s = slots.get(&slot).unwrap();
            (s.dir.clone(), s.branch.clone(), s.base_sha.clone())
        };
        let out = TokioCommand::new("git")
            .arg("-C")
            .arg(&dir)
            .arg("bundle")
            .arg("create")
            .arg("-")
            .arg(format!("{base}..{branch}"))
            .env("GIT_PAGER", "cat")
            .stdin(Stdio::null())
            .output()
            .await
            .unwrap();
        assert!(out.status.success());
        (
            StatusCode::OK,
            [(header::CONTENT_TYPE, "application/octet-stream")],
            out.stdout,
        )
    }

    async fn serve_mock_runner(clone_source: PathBuf, preempt: bool, scratch: PathBuf) -> (String, MockRunner) {
        std::fs::create_dir_all(&scratch).unwrap();
        let st = MockRunner {
            slots: Arc::new(Mutex::new(Default::default())),
            clone_source,
            prepares: Arc::new(Mutex::new(Vec::new())),
            build_seen: Arc::new(Mutex::new(Vec::new())),
            build_should_preempt: preempt,
            canned_exec: Arc::new(Mutex::new(Vec::new())),
            scratch,
        };
        let app: axum::Router = axum::Router::new()
            .route("/v1/slots/:slot/prepare", post(mock_prepare))
            .route("/v1/slots/:slot/read", post(mock_read))
            .route("/v1/slots/:slot/write", post(mock_write))
            .route("/v1/slots/:slot/edit", post(mock_edit))
            .route("/v1/slots/:slot/exec", post(mock_exec))
            .route("/v1/slots/:slot/list", post(mock_list))
            .route("/v1/slots/:slot/diff", post(mock_diff))
            .route("/v1/slots/:slot/commit", post(mock_commit))
            .route("/v1/slots/:slot/bundle", get(mock_bundle))
            .route(
                "/v1/slots/:slot/cancel",
                post(|_: AxPath<u32>| async {
                    axum::Json(serde_json::json!({"killed": false}))
                }),
            )
            .route(
                "/v1/builds/kill",
                post(|| async { axum::Json(serde_json::json!({"killed": true})) }),
            )
            .with_state(st.clone());
        let lis = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = lis.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(lis, app).await.unwrap() });
        (format!("http://{addr}"), st)
    }

    // ---------- mock GitHub (recorded fixtures + recorded POSTs) ----------

    #[derive(Debug, Clone)]
    struct RecordedGhRequest {
        method: String,
        path: String,
    }

    #[derive(Clone, Default)]
    struct MockGitHub {
        recorded: Arc<Mutex<Vec<RecordedGhRequest>>>,
        responses: Arc<Mutex<HashMap<String, GhFixture>>>,
    }

    /// A recorded fixture served at one mock-GitHub path: HTTP status + body.
    type GhFixture = (u16, Vec<u8>);

    impl MockGitHub {
        /// Serve `body` at `path` with HTTP 200.
        fn respond(&self, path: &str, body: impl Into<Vec<u8>>) {
            self.responses
                .lock()
                .unwrap()
                .insert(path.to_string(), (200, body.into()));
        }

        fn requests(&self) -> Vec<RecordedGhRequest> {
            self.recorded.lock().unwrap().clone()
        }
    }

    async fn mock_github_handler(
        State(st): State<MockGitHub>,
        req: axum::http::Request<axum::body::Body>,
    ) -> impl IntoResponse {
        let (parts, _body) = req.into_parts();
        st.recorded.lock().unwrap().push(RecordedGhRequest {
            method: parts.method.to_string(),
            path: parts.uri.path().to_string(),
        });
        // Write endpoints take precedence over recorded GET fixtures sharing
        // the same path (e.g. the comments list).
        let path = parts.uri.path().to_string();
        if parts.method == axum::http::Method::POST && path.ends_with("/comments") {
            return (
                StatusCode::CREATED,
                [(header::CONTENT_TYPE, "application/json")],
                br#"{"html_url":"https://github.com/vayun-mathur/Modern-Apps/issues/903#issuecomment-9"}"#.to_vec(),
            )
                .into_response();
        }
        if parts.method == axum::http::Method::POST && path.ends_with("/labels") {
            return (
                StatusCode::OK,
                [(header::CONTENT_TYPE, "application/json")],
                br#"[{"name":"solved for next release"}]"#.to_vec(),
            )
                .into_response();
        }
        let found = st
            .responses
            .lock()
            .unwrap()
            .get(parts.uri.path())
            .cloned();
        match found {
            Some((status, body)) => (
                StatusCode::from_u16(status).unwrap_or(StatusCode::OK),
                [(header::CONTENT_TYPE, "application/json")],
                body,
            )
                .into_response(),
            None => (StatusCode::NOT_FOUND, "no mock for path").into_response(),
        }
    }

    async fn serve_mock_github() -> (String, MockGitHub) {
        let st = MockGitHub::default();
        // Recorded fixtures for issue #903 (weather/Celsius) + search.
        st.respond(
            "/repos/vayun-mathur/Modern-Apps/issues/903",
            include_str!("testdata/github/issue_903.json"),
        );
        st.respond(
            "/repos/vayun-mathur/Modern-Apps/issues/903/comments",
            include_str!("testdata/github/comments_903.json"),
        );
        st.respond("/search/issues", include_str!("testdata/github/search_weather.json"));
        st.respond(
            "/repos/vayun-mathur/Modern-Apps/issues",
            include_str!("testdata/github/list_weather.json"),
        );
        let app = axum::Router::new()
            .fallback(mock_github_handler)
            .with_state(st.clone());
        let lis = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = lis.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(lis, app).await.unwrap() });
        (format!("http://{addr}"), st)
    }

    // ---------- harness: real agentd server pointed at the mocks ----------

    struct Harness {
        addr: SocketAddr,
        client: reqwest::Client,
        origin: String,
        _tmp: tempfile::TempDir,
        _remote: PathBuf,
        runner_seen: MockRunner,
        or_seen: MockOr,
        github_seen: MockGitHub,
        cookie: String,
        store: Store,
    }

    async fn spawn_harness(script: Vec<serde_json::Value>) -> Harness {
        spawn_harness_opts(script, false).await
    }

    async fn spawn_harness_opts(script: Vec<serde_json::Value>, preempt: bool) -> Harness {
        spawn_harness_full(script, preempt, false).await
    }

    /// `skip_wait`: when true, the release-signal wait is skipped and the
    /// build is preempted immediately (fast deterministic test).
    async fn spawn_harness_full(
        script: Vec<serde_json::Value>,
        preempt: bool,
        skip_wait: bool,
    ) -> Harness {
        let tmp = tempfile::tempdir().unwrap();
        let remote = init_remote(tmp.path()).await;
        // agentd's own mirror: a real bare clone of the temp remote. The
        // mock runner clones/fetches from THIS mirror (not the seed remote),
        // so WIP refs agentd imports at park time are fetchable at resume.
        let mirror = tmp.path().join("mirror.git");
        git_no_cwd(
            &["clone", "--bare", remote.to_str().unwrap(), mirror.to_str().unwrap()],
            tmp.path(),
        )
        .await;
        let (or_url, or_seen) = serve_mock_or(script).await;
        let (runner_url, runner_seen) = serve_mock_runner(mirror.clone(), preempt, tmp.path().join("slots")).await;
        let (github_url, github_seen) = serve_mock_github().await;

        let state_dir = tmp.path().join("state");
        std::fs::create_dir_all(&state_dir).unwrap();
        let store = Store::open(&state_dir).unwrap();

        // Password hash for "s3cret".
        let hash = {
            use argon2::{Argon2, PasswordHasher, password_hash::SaltString};
            let salt = SaltString::from_b64("AAAAAAAAAAAAAAAAAAAAAA").unwrap();
            Argon2::default().hash_password(b"s3cret", &salt).unwrap().to_string()
        };
        let origin = "https://agents.example.test".to_string();
        let cfg = crate::config::Config {
            openrouter_api_key: "test-key".into(),
            openrouter_base_url: or_url,
            password_hash: hash,
            git_push_token: "PUSH-TOKEN-SHOULD-NEVER-LEAK".into(),
            repo_url: remote.to_str().unwrap().to_string(),
            public_origin: origin.clone(),
            default_model: "test/model".into(),
            runner_url,
            state_dir: state_dir.clone(),
            mirror_dir: mirror.clone(),
            release_signal: tmp.path().join("signal-release"),
            listen: "127.0.0.1:0".into(),
            slots: 2,
            github_repo: "vayun-mathur/Modern-Apps".into(),
            github_api_base: github_url.clone(),
            github_token: Some("test-github-token".into()),
            test: crate::config::TestTuning {
                poll_ms: Some(20),
                build_wait_secs: Some(2),
                context_chars: None,
            },
            test_skip_release_wait: skip_wait,
            triage_enabled: false,
            triage_interval_secs: 120,
        };
        let state = AppState {
            config: Arc::new(cfg.clone()),
            store: store.clone(),
            auth: crate::auth::Auth::new(store.clone(), cfg.password_hash.clone()),
            runner: crate::runner::Runner::new(cfg.runner_url.clone()),
            openrouter: crate::openrouter::OpenRouter::new(
                cfg.openrouter_base_url.clone(),
                cfg.openrouter_api_key.clone(),
            ),
            events_tx: {
                let (tx, _) = tokio::sync::broadcast::channel(64);
                tx
            },
        };
        // Scheduler workers (2 slots).
        let _ = crate::scheduler::spawn_workers(state.driver(), 2);
        let app = crate::ui::router(state);
        let lis = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = lis.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(lis, app).await.unwrap() });

        let client = reqwest::Client::builder().redirect(reqwest::redirect::Policy::none()).build().unwrap();
        // Log in.
        let resp = client
            .post(format!("http://{addr}/login"))
            .form(&[("password", "s3cret")])
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::FOUND);
        let cookie = resp
            .headers()
            .get(header::SET_COOKIE)
            .unwrap()
            .to_str()
            .unwrap()
            .split(';')
            .next()
            .unwrap()
            .to_string();
        assert!(cookie.starts_with("agentd_session="));
        Harness {
            addr,
            client,
            origin,
            _tmp: tmp,
            _remote: remote,
            runner_seen,
            or_seen,
            github_seen,
            cookie,
            store,
        }
    }

    fn url(h: &Harness, path: &str) -> String {
        format!("http://{}{}", h.addr, path)
    }

    async fn create_task(h: &Harness, title: &str, prompt: &str) -> i64 {
        create_task_opts(h, title, prompt, false).await
    }

    /// Create a task, optionally with the "Plan first" checkbox checked
    /// (`require_plan=1` in the form body).
    async fn create_task_opts(h: &Harness, title: &str, prompt: &str, require_plan: bool) -> i64 {
        let mut form = vec![
            ("title", title),
            ("module_scope", "notes"),
            ("model", "test/model"),
            ("prompt", prompt),
            ("budget_steps", "60"),
            ("budget_usd", "2.0"),
        ];
        if require_plan {
            form.push(("require_plan", "1"));
        }
        let resp = h
            .client
            .post(url(h, "/tasks"))
            .header(header::COOKIE, &h.cookie)
            .header(header::ORIGIN, &h.origin)
            .form(&form)
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::FOUND, "task created");
        let loc = resp.headers().get(header::LOCATION).unwrap().to_str().unwrap().to_string();
        loc.rsplit('/').next().unwrap().parse().unwrap()
    }

    /// Create a task from the start-from-issue form: blank title/prompt plus
    /// an issue number (prefilled from the mock GitHub). Returns the raw
    /// response so tests can assert non-FOUND statuses too.
    async fn create_task_from_issue_raw(
        h: &Harness,
        title: &str,
        prompt: &str,
        module_scope: &str,
        issue_number: &str,
    ) -> reqwest::Response {
        h.client
            .post(url(h, "/tasks"))
            .header(header::COOKIE, &h.cookie)
            .header(header::ORIGIN, &h.origin)
            .form(&[
                ("title", title),
                ("module_scope", module_scope),
                ("model", "test/model"),
                ("prompt", prompt),
                ("budget_steps", "60"),
                ("budget_usd", "2.0"),
                ("issue_number", issue_number),
            ])
            .send()
            .await
            .unwrap()
    }

    async fn create_task_from_issue(h: &Harness, title: &str, prompt: &str, issue: u64) -> i64 {
        let resp = create_task_from_issue_raw(h, title, prompt, "notes", &issue.to_string()).await;
        assert_eq!(resp.status(), StatusCode::FOUND, "task created from issue");
        let loc = resp.headers().get(header::LOCATION).unwrap().to_str().unwrap().to_string();
        loc.rsplit('/').next().unwrap().parse().unwrap()
    }

    async fn post_message(h: &Harness, id: i64, text: &str) -> StatusCode {
        h.client
            .post(url(h, &format!("/tasks/{id}/messages")))
            .header(header::COOKIE, &h.cookie)
            .header(header::ORIGIN, &h.origin)
            .form(&[("text", text)])
            .send()
            .await
            .unwrap()
            .status()
    }

    /// POST to an empty-body action route (plan approve, review approve,
    /// stop, discard). Axum `Form` extractors on sibling routes need a body;
    /// these routes take none, so send none.
    async fn post_action(h: &Harness, id: i64, action: &str) -> StatusCode {
        h.client
            .post(url(h, &format!("/tasks/{id}/{action}")))
            .header(header::COOKIE, &h.cookie)
            .header(header::ORIGIN, &h.origin)
            .send()
            .await
            .unwrap()
            .status()
    }

    async fn post_plan_changes(h: &Harness, id: i64, text: &str) -> StatusCode {
        h.client
            .post(url(h, &format!("/tasks/{id}/plan/changes")))
            .header(header::COOKIE, &h.cookie)
            .header(header::ORIGIN, &h.origin)
            .form(&[("text", text)])
            .send()
            .await
            .unwrap()
            .status()
    }

    /// Does `refs/heads/<branch>` exist on the temp remote?
    async fn remote_has_branch(h: &Harness, branch: &str) -> bool {
        TokioCommand::new("git")
            .arg("--git-dir")
            .arg(h._remote.clone())
            .arg("rev-parse")
            .arg("--verify")
            .arg(format!("refs/heads/{branch}"))
            .output()
            .await
            .map(|o| o.status.success())
            .unwrap_or(false)
    }

    /// Show a file at a remote branch (`None` when missing).
    async fn remote_show(h: &Harness, branch: &str, path: &str) -> Option<String> {
        let out = TokioCommand::new("git")
            .arg("--git-dir")
            .arg(h._remote.clone())
            .arg("show")
            .arg(format!("refs/heads/{branch}:{path}"))
            .output()
            .await
            .unwrap();
        if out.status.success() {
            Some(String::from_utf8(out.stdout).unwrap())
        } else {
            None
        }
    }

    /// One-line log subjects on the remote branch after `main`.
    async fn remote_log(h: &Harness, branch: &str) -> String {
        let out = TokioCommand::new("git")
            .arg("--git-dir")
            .arg(h._remote.clone())
            .arg("log")
            .arg("--format=%s")
            .arg(format!("main..refs/heads/{branch}"))
            .output()
            .await
            .unwrap();
        assert!(out.status.success(), "branch {branch} on remote");
        String::from_utf8(out.stdout).unwrap()
    }

    async fn task_html(h: &Harness, id: i64) -> String {
        h.client
            .get(url(h, &format!("/tasks/{id}")))
            .header(header::COOKIE, &h.cookie)
            .send()
            .await
            .unwrap()
            .text()
            .await
            .unwrap()
    }

    /// Ask-answer-resume: `ask_user` parks as needs_input, the POSTed answer
    /// becomes the tool result in the next model request (right position),
    /// edits survive, and `finish` parks as review (no push) → approve pushes
    /// → succeeded.
    #[tokio::test]
    async fn ask_user_answer_resume_finish() {
        let h = spawn_harness(vec![
            tool_resp("c1", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "hello world\n"}), "editing"),
            tool_resp(
                "c2",
                "ask_user",
                serde_json::json!({"question": "Which greeting?", "options": ["hello world", "hi"]}),
                "asking",
            ),
            // After resume: the tool result for c2 (the answer) must be in
            // this request, then the model finishes.
            tool_resp(
                "c3",
                "finish",
                serde_json::json!({
                    "summary": "Changed the greeting per answer.",
                    "commit_message": "notes: change greeting\n\nSwitched the greeting text per the task."
                }),
                "finishing",
            ),
        ])
        .await;
        let id = create_task(&h, "Ask flow", "Edit the file, ask which greeting, then finish.").await;

        // Park as needs_input(question); the page shows the question + options.
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        assert!(html.contains("pill needs_input\">needs_input<"), "parked; tail: {}", &html[html.len().saturating_sub(800)..]);
        assert!(html.contains("Which greeting?"), "question shown");
        assert!(html.contains("hello world"), "option shown");

        // Answer via the option-button shape (form posts option text).
        assert_eq!(post_message(&h, id, "hello world").await, StatusCode::FOUND);
        // `finish` parks as review (no push yet).
        wait_review(&h, id, std::time::Duration::from_secs(60)).await;
        let branch = format!("agent/{id}-ask-flow");
        assert!(!remote_has_branch(&h, &branch).await, "no push before approve");
        let html = task_html(&h, id).await;
        assert!(html.contains("pill review\">review<"), "in review");
        // Approve → push → succeeded.
        assert_eq!(post_action(&h, id, "approve").await, StatusCode::FOUND);
        wait_status(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        assert!(html.contains("pill succeeded\">succeeded<"), "approved to success");

        // The request AFTER the park carries the tool result with the answer
        // in the right position: last message, role=tool, id=c2.
        let seen = h.or_seen.seen.lock().unwrap().clone();
        assert!(seen.len() >= 3, "model called again after resume: {}", seen.len());
        let resumed_req = &seen[2];
        let msgs = resumed_req.get("messages").and_then(|v| v.as_array()).unwrap();
        let last = msgs.last().unwrap();
        assert_eq!(last.get("role").and_then(|v| v.as_str()), Some("tool"));
        assert_eq!(last.get("tool_call_id").and_then(|v| v.as_str()), Some("c2"));
        assert!(
            last.get("content").and_then(|v| v.as_str()).unwrap_or("").contains("hello world"),
            "answer in tool result: {last}"
        );

        // The branch was pushed with the edit intact.
        let branch = format!("agent/{id}-ask-flow");
        let out = TokioCommand::new("git")
            .arg("--git-dir")
            .arg(h._remote.clone())
            .arg("show")
            .arg(format!("refs/heads/{branch}:notes/file.txt"))
            .output()
            .await
            .unwrap();
        assert!(out.status.success(), "branch pushed");
        assert!(String::from_utf8(out.stdout).unwrap().contains("hello world"));
    }

    /// Steering while running: a message posted mid-run appears as a user
    /// message in a subsequent model request.
    #[tokio::test]
    async fn steering_while_running() {
        let h = spawn_harness(vec![
            tool_resp("c1", "read_file", serde_json::json!({"path": "notes/file.txt"}), "reading"),
            tool_resp("c2", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "hello steered\n"}), "editing"),
            tool_resp(
                "c3",
                "finish",
                serde_json::json!({
                    "summary": "Steered edit.",
                    "commit_message": "notes: steered edit\n\nBody here."
                }),
                "finishing",
            ),
        ])
        .await;
        let id = create_task(&h, "Steer me", "Read, edit, finish.").await;
        // Post immediately: the driver's first inbox drain happens after its
        // first turn's tool results (claim poll + git clone + reads come
        // first), so this steering is guaranteed to be picked up for a later
        // request — deterministically, with no sleep.
        assert_eq!(post_message(&h, id, "steer: prefer exclamation").await, StatusCode::FOUND);
        // `finish` parks as review; approve to push.
        wait_review(&h, id, std::time::Duration::from_secs(60)).await;
        assert_eq!(post_action(&h, id, "approve").await, StatusCode::FOUND);
        wait_status(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        assert!(html.contains("pill succeeded\">succeeded<"));
        let seen = h.or_seen.seen.lock().unwrap().clone();
        let mut found = false;
        for req in &seen {
            let msgs = req.get("messages").and_then(|v| v.as_array()).unwrap();
            for m in msgs {
                if m.get("role").and_then(|v| v.as_str()) == Some("user")
                    && m.get("content").and_then(|v| v.as_str()).unwrap_or("").contains("steer: prefer exclamation")
                {
                    found = true;
                }
            }
        }
        assert!(found, "steering message reached the model");
    }

    /// Stop → needs_input(stopped) → message → resumes with edits kept.
    ///
    /// Deterministic by stopping immediately after create: the flag persists
    /// in the DB, so whenever the driver first observes it (queued or
    /// mid-run) it parks as stopped; the resume then replays the script to
    /// success. (Stopping after N turns is inherently racy — mock
    /// round-trips take milliseconds.)
    #[tokio::test]
    async fn stop_then_resume_keeps_edits() {
        let h = spawn_harness(vec![
            tool_resp("c1", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "hello stopped\n"}), "editing"),
            tool_resp("c2", "read_file", serde_json::json!({"path": "notes/file.txt"}), "reading"),
            tool_resp("c3", "read_file", serde_json::json!({"path": "notes/file.txt"}), "reading"),
            tool_resp(
                "c4",
                "finish",
                serde_json::json!({
                    "summary": "Done after stop.",
                    "commit_message": "notes: resume after stop\n\nBody here."
                }),
                "finishing",
            ),
        ])
        .await;
        let id = create_task(&h, "Stop flow", "Edit, then keep reading, then finish.").await;
        // Stop immediately (flag persists; the driver parks as stopped
        // whenever it first observes it — queued or mid-run).
        let resp = h
            .client
            .post(url(&h, &format!("/tasks/{id}/stop")))
            .header(header::COOKIE, &h.cookie)
            .header(header::ORIGIN, &h.origin)
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::FOUND);
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        assert!(html.contains("stopped"), "stopped park visible; tail: {}", &html[html.len().saturating_sub(1500)..]);
        // Resume with a message; the edit must survive the park/resume.
        assert_eq!(post_message(&h, id, "continue please").await, StatusCode::FOUND);
        // Script ends in `finish` → review; approve to push.
        wait_review(&h, id, std::time::Duration::from_secs(60)).await;
        assert_eq!(post_action(&h, id, "approve").await, StatusCode::FOUND);
        wait_status(&h, id, std::time::Duration::from_secs(60)).await;
        let branch = format!("agent/{id}-stop-flow");
        let out = TokioCommand::new("git")
            .arg("--git-dir")
            .arg(h._remote.clone())
            .arg("show")
            .arg(format!("refs/heads/{branch}:notes/file.txt"))
            .output()
            .await
            .unwrap();
        assert!(out.status.success(), "branch pushed");
        assert!(String::from_utf8(out.stdout).unwrap().contains("hello stopped"), "edit survived stop/resume");
    }

    /// Multi-call turn with ask_user → error result, no park.
    #[tokio::test]
    async fn mixed_ask_user_turn_rejected_without_park() {
        let h = spawn_harness(vec![
            serde_json::json!({
                "choices": [{"message": {
                    "role": "assistant", "content": "two calls",
                    "tool_calls": [
                        {"id": "m1", "type": "function", "function": {"name": "read_file", "arguments": "{\"path\": \"notes/file.txt\"}"}},
                        {"id": "m2", "type": "function", "function": {"name": "ask_user", "arguments": "{\"question\": \"Q?\"}"}}
                    ]
                }}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5}
            }),
            // After the rejection the model text-replies → parks as chat.
            serde_json::json!({
                "choices": [{"message": {"role": "assistant", "content": "understood, continuing alone"}}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5}
            }),
        ])
        .await;
        let id = create_task(&h, "Mixed turn", "Do things.").await;
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        // Parked as chat (text-only), NOT as a question.
        assert!(html.contains("pill needs_input\">needs_input<"));
        assert!(!html.contains("Waiting for you (question)"), "no question park; tail: {}", &html[html.len().saturating_sub(1500)..]);
        assert!(html.contains("must be the only call in its turn"), "error surfaced");
    }

    /// Text-only turn parks as chat (nudge-then-fail retired).
    #[tokio::test]
    async fn text_only_parks_as_chat() {
        let h = spawn_harness(vec![serde_json::json!({
            "choices": [{"message": {"role": "assistant", "content": "I need guidance"}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1}
        })])
        .await;
        let id = create_task(&h, "Chat park", "Say hi.").await;
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        assert!(html.contains("pill needs_input\">needs_input<"));
        assert!(html.contains("Waiting for you (chat)"), "chat park; tail: {}", &html[html.len().saturating_sub(1500)..]);
    }

    /// Tiny budget → budget park; answering extends +20 steps / +$1.00.
    #[tokio::test]
    async fn budget_park_and_extension() {
        let h = spawn_harness(vec![
            tool_resp("c1", "read_file", serde_json::json!({"path": "notes/file.txt"}), "reading"),
            tool_resp("c2", "read_file", serde_json::json!({"path": "notes/file.txt"}), "reading"),
            tool_resp("c3", "read_file", serde_json::json!({"path": "notes/file.txt"}), "reading"),
            tool_resp("c4", "read_file", serde_json::json!({"path": "notes/file.txt"}), "reading"),
            serde_json::json!({
                "choices": [{"message": {"role": "assistant", "content": "still going"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1}
            }),
        ])
        .await;
        // Create with budget_steps=2 via the raw form.
        let resp = h
            .client
            .post(url(&h, "/tasks"))
            .header(header::COOKIE, &h.cookie)
            .header(header::ORIGIN, &h.origin)
            .form(&[
                ("title", "Budget flow"),
                ("module_scope", "notes"),
                ("model", "test/model"),
                ("prompt", "Read a lot."),
                ("budget_steps", "2"),
                ("budget_usd", "2.0"),
            ])
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::FOUND);
        let loc = resp.headers().get(header::LOCATION).unwrap().to_str().unwrap().to_string();
        let id: i64 = loc.rsplit('/').next().unwrap().parse().unwrap();
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        assert!(html.contains("Waiting for you (budget)"), "budget park; tail: {}", &html[html.len().saturating_sub(1500)..]);
        // Answer → resume grants +20 steps / +$1.00 (2 → 22 steps).
        assert_eq!(post_message(&h, id, "keep going").await, StatusCode::FOUND);
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        assert!(html.contains("22"), "budget extended to 22 steps; tail: {}", &html[html.len().saturating_sub(1500)..]);
    }

    /// Discard is terminal and removes the WIP ref.
    #[tokio::test]
    async fn discard_task_terminal() {
        let h = spawn_harness(vec![serde_json::json!({
            "choices": [{"message": {"role": "assistant", "content": "waiting"}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1}
        })])
        .await;
        let id = create_task(&h, "Discard me", "Say hi.").await;
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let resp = h
            .client
            .post(url(&h, &format!("/tasks/{id}/discard")))
            .header(header::COOKIE, &h.cookie)
            .header(header::ORIGIN, &h.origin)
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::FOUND);
        let html = task_html(&h, id).await;
        assert!(html.contains("pill discarded\">discarded<"), "discarded; tail: {}", &html[html.len().saturating_sub(800)..]);
    }

    async fn wait_for(h: &Harness, id: i64, statuses: &[&str], timeout: std::time::Duration) {
        let start = std::time::Instant::now();
        loop {
            // Poll the task page for a status pill. NOTE: the page's CSS
            // block contains `.pill.failed` etc. selectors, so match the full
            // rendered span `pill {s}">{s}</span>` — never the bare class.
            let html = h
                .client
                .get(url(h, &format!("/tasks/{id}")))
                .header(header::COOKIE, &h.cookie)
                .send()
                .await
                .unwrap()
                .text()
                .await
                .unwrap();
            for s in statuses {
                let span = format!("pill {s}\">{s}</span>");
                if html.contains(&span) {
                    return;
                }
            }
            if start.elapsed() > timeout {
                panic!("task {id} did not reach {statuses:?} in {timeout:?}; html tail: {}", &html[html.len().saturating_sub(500)..]);
            }
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }
    }

    async fn wait_status(h: &Harness, id: i64, timeout: std::time::Duration) {
        wait_for(h, id, &["succeeded", "failed", "discarded", "cancelled"], timeout).await;
    }

    async fn wait_parked(h: &Harness, id: i64, timeout: std::time::Duration) {
        wait_for(h, id, &["needs_input", "review"], timeout).await;
    }

    async fn wait_review(h: &Harness, id: i64, timeout: std::time::Duration) {
        wait_for(h, id, &["review"], timeout).await;
    }

    #[tokio::test]
    async fn full_success_flow_pushes_branch() {
        let h = spawn_harness(success_script()).await;
        let resp = h
            .client
            .post(url(&h, "/tasks"))
            .header(header::COOKIE, &h.cookie)
            .header(header::ORIGIN, &h.origin)
            .form(&[
                ("title", "Change greeting"),
                ("module_scope", "notes"),
                ("model", "test/model"),
                ("prompt", "Change the greeting text."),
                ("budget_steps", "60"),
                ("budget_usd", "2.0"),
            ])
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::FOUND, "task created");
        let loc = resp.headers().get(header::LOCATION).unwrap().to_str().unwrap().to_string();
        let id: i64 = loc.rsplit('/').next().unwrap().parse().unwrap();

        // `finish` parks as review WITHOUT pushing; the branch must not exist
        // on the remote yet.
        let branch = format!("agent/{id}-change-greeting");
        wait_review(&h, id, std::time::Duration::from_secs(60)).await;
        assert!(!remote_has_branch(&h, &branch).await, "no push before approve");
        // Review card shows summary, commit message, and the diff.
        let html = task_html(&h, id).await;
        assert!(html.contains("pill review\">review<"), "in review");
        assert!(html.contains("Changed the greeting."), "summary shown");
        assert!(html.contains("notes: change greeting"), "commit message shown");
        assert!(html.contains("hello world"), "diff shown");
        assert!(html.contains("Approve &amp; push") || html.contains("Approve & push"), "approve button");
        // Approve → push → succeeded with the agent's message as the single commit.
        assert_eq!(post_action(&h, id, "approve").await, StatusCode::FOUND);
        wait_status(&h, id, std::time::Duration::from_secs(60)).await;
        // Task page shows success.
        let html = h
            .client
            .get(url(&h, &format!("/tasks/{id}")))
            .header(header::COOKIE, &h.cookie)
            .send()
            .await
            .unwrap()
            .text()
            .await
            .unwrap();
        assert!(html.contains("pill succeeded\">succeeded<"), "task succeeded; html tail: {}", &html[html.len().saturating_sub(800)..]);
        assert!(html.contains("agent/"), "branch shown");
        // The branch exists in the temp remote with the expected commit.
        let log = remote_log(&h, &branch).await;
        assert!(log.contains("notes: change greeting"), "commit msg: {log}");
        assert_eq!(log.lines().count(), 1, "single squashed commit: {log}");
        // Token never appears in events (SSE backlog) or any page.
        // NOTE: `/tasks/{id}/events` is an infinite SSE stream (15s
        // keep-alives), so `.text()` on it would hang forever. Read it with
        // `?after=` bounded ... instead: assert on the DB-backed backlog via
        // the HTML page's rendered events (already covered) plus a direct
        // events query below with a timeout-guarded raw socket read.
        for path in [format!("/tasks/{id}"), "/".to_string()] {
            let body = h
                .client
                .get(url(&h, &path))
                .header(header::COOKIE, &h.cookie)
                .header(header::ACCEPT, "text/html")
                .send()
                .await
                .unwrap()
                .text()
                .await
                .unwrap();
            assert!(!body.contains("PUSH-TOKEN-SHOULD-NEVER-LEAK"), "token leaked in {path}");
        }
        {
            // Bounded SSE read: the backlog frames arrive immediately; the
            // stream then idles on keep-alives, so cap the read at 10s.
            use tokio::io::AsyncReadExt;
            let stream = tokio::net::TcpStream::connect(h.addr).await.unwrap();
            let (mut reader, mut writer) = stream.into_split();
            let req = format!(
                "GET /tasks/{id}/events HTTP/1.1\r\nHost: {}\r\nCookie: {}\r\nAccept: text/event-stream\r\nConnection: close\r\n\r\n",
                h.addr, h.cookie
            );
            use tokio::io::AsyncWriteExt;
            writer.write_all(req.as_bytes()).await.unwrap();
            let mut buf = vec![0u8; 1_000_000];
            let mut total = 0usize;
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
            loop {
                let remaining = deadline.saturating_duration_since(std::time::Instant::now());
                if remaining.is_zero() {
                    break;
                }
                match tokio::time::timeout(
                    remaining,
                    reader.read(&mut buf[total..]),
                )
                .await
                {
                    Ok(Ok(0)) => break,
                    Ok(Ok(n)) => {
                        total += n;
                        if total >= buf.len() {
                            break;
                        }
                    }
                    _ => break,
                }
            }
            let body = String::from_utf8_lossy(&buf[..total]);
            assert!(!body.contains("PUSH-TOKEN-SHOULD-NEVER-LEAK"), "token leaked in events stream");
            assert!(body.contains("data:"), "expected SSE data frames, got {body:?}");
        }
        // Runner saw the build command.
        let seen = h.runner_seen.build_seen.lock().unwrap();
        assert!(seen.iter().any(|c| c.contains(":notes:compileDevKotlin")), "build forwarded: {seen:?}");
    }

    #[tokio::test]
    async fn unauthenticated_post_rejected() {
        let h = spawn_harness(vec![]).await;
        let resp = h
            .client
            .post(url(&h, "/tasks"))
            .header(header::ORIGIN, &h.origin)
            .form(&[
                ("title", "x"),
                ("module_scope", "notes"),
                ("model", "m"),
                ("prompt", "y"),
                ("budget_steps", "60"),
                ("budget_usd", "2.0"),
            ])
            .send()
            .await
            .unwrap();
        // Redirect to /login (not queued).
        assert_eq!(resp.status(), StatusCode::FOUND);
        assert_eq!(resp.headers().get(header::LOCATION).unwrap(), "/login");
    }

    #[tokio::test]
    async fn wrong_origin_rejected() {
        let h = spawn_harness(vec![]).await;
        let resp = h
            .client
            .post(url(&h, "/tasks"))
            .header(header::COOKIE, &h.cookie)
            .header(header::ORIGIN, "https://evil.example.test")
            .form(&[
                ("title", "x"),
                ("module_scope", "notes"),
                ("model", "m"),
                ("prompt", "y"),
                ("budget_steps", "60"),
                ("budget_usd", "2.0"),
            ])
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::FORBIDDEN);
        // Missing Origin also rejected.
        let resp = h
            .client
            .post(url(&h, "/tasks"))
            .header(header::COOKIE, &h.cookie)
            .form(&[
                ("title", "x"),
                ("module_scope", "notes"),
                ("model", "m"),
                ("prompt", "y"),
                ("budget_steps", "60"),
                ("budget_usd", "2.0"),
            ])
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::FORBIDDEN);
    }

    #[tokio::test]
    async fn release_signal_preempts_build() {
        // The harness holds the release-signal file present and skips the
        // 60-minute wait; agentd must surface "preempted by a production
        // release" to the model (and call the runner's builds/kill). The
        // trailing text-only turn now parks as needs_input(chat) instead of
        // failing (nudge-then-fail is retired).
        let h = spawn_harness_full(
            vec![
                tool_resp(
                    "c1",
                    "build",
                    serde_json::json!({"command": "./gradlew :notes:lint"}),
                    "try build",
                ),
                serde_json::json!({
                    "choices": [{"message": {"role": "assistant", "content": "retrying"}}],
                    "usage": {"prompt_tokens": 1, "completion_tokens": 1}
                }),
            ],
            false,
            true,
        )
        .await;
        // Hold the release signal for the whole task (harness points
        // AGENTD_RELEASE_SIGNAL at tmp/signal-release).
        std::fs::write(h._tmp.path().join("signal-release"), "1").unwrap();

        let resp = h
            .client
            .post(url(&h, "/tasks"))
            .header(header::COOKIE, &h.cookie)
            .header(header::ORIGIN, &h.origin)
            .form(&[
                ("title", "Build during release"),
                ("module_scope", "notes"),
                ("model", "test/model"),
                ("prompt", "Run a build."),
                ("budget_steps", "60"),
                ("budget_usd", "2.0"),
            ])
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::FOUND);
        let loc = resp.headers().get(header::LOCATION).unwrap().to_str().unwrap().to_string();
        let id: i64 = loc.rsplit('/').next().unwrap().parse().unwrap();
        let _ = wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let html = h
            .client
            .get(url(&h, &format!("/tasks/{id}")))
            .header(header::COOKIE, &h.cookie)
            .send()
            .await
            .unwrap()
            .text()
            .await
            .unwrap();
        assert!(
            html.contains("preempted by a production release"),
            "preemption surfaced; tail: {}",
            &html[html.len().saturating_sub(2000)..]
        );
        assert!(
            html.contains("pill needs_input\">needs_input<"),
            "text-only turn parked as chat; tail: {}",
            &html[html.len().saturating_sub(2000)..]
        );
    }

    /// Chat UI over HTTP: static assets carry JS/CSS content types, the four
    /// fragment routes 401 without auth, a parked (chat) task shows up under
    /// "Needs you" with a `(1)` title count, and the task page has no inline
    /// script/style plus a CSP without 'unsafe-inline'.
    #[tokio::test]
    async fn ui_fragments_dashboard_and_static() {
        let h = spawn_harness(vec![serde_json::json!({
            "choices": [{"message": {"role": "assistant", "content": "hello, need guidance"}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1}
        })])
        .await;
        // Static assets: correct content types, real bodies.
        let resp = h.client.get(url(&h, "/static/app.js")).send().await.unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        let ct = resp.headers().get(header::CONTENT_TYPE).unwrap().to_str().unwrap().to_string();
        assert!(ct.starts_with("application/javascript"), "js type: {ct}");
        let js = resp.text().await.unwrap();
        assert!(js.contains("EventSource"), "live-update script served");
        let resp = h.client.get(url(&h, "/static/app.css")).send().await.unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        let ct = resp.headers().get(header::CONTENT_TYPE).unwrap().to_str().unwrap().to_string();
        assert!(ct.starts_with("text/css"), "css type: {ct}");
        // Fragments require auth: no cookie → 401 (never a login redirect
        // the script would swap into the page).
        for path in [
            "/tasks/1/timeline",
            "/tasks/1/panel",
            "/tasks/1/cards",
            "/fragments/dashboard",
        ] {
            let r = h.client.get(url(&h, path)).send().await.unwrap();
            assert_eq!(r.status(), StatusCode::UNAUTHORIZED, "{path} needs auth");
        }
        // Park a task as chat (needs_input) → dashboard counts it.
        let id = create_task(&h, "Chat UI", "Say hi.").await;
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let dash = h
            .client
            .get(url(&h, "/"))
            .header(header::COOKIE, &h.cookie)
            .send()
            .await
            .unwrap()
            .text()
            .await
            .unwrap();
        assert!(dash.contains("<title>(1) agentd</title>"), "title count; head: {}", &dash[..dash.len().min(600)]);
        assert!(dash.contains("Needs you (1)"), "needs-you section");
        assert!(dash.contains("replied — waiting for you"), "chat reason");
        // Authed fragments render server markup.
        let body = h
            .client
            .get(url(&h, &format!("/tasks/{id}/timeline")))
            .header(header::COOKIE, &h.cookie)
            .send()
            .await
            .unwrap()
            .text()
            .await
            .unwrap();
        assert!(body.contains("bubble assistant"), "timeline fragment; body: {body}");
        let body = h
            .client
            .get(url(&h, &format!("/tasks/{id}/panel")))
            .header(header::COOKIE, &h.cookie)
            .send()
            .await
            .unwrap()
            .text()
            .await
            .unwrap();
        assert!(body.contains("Todos"), "panel fragment; body: {body}");
        let body = h
            .client
            .get(url(&h, &format!("/tasks/{id}/cards")))
            .header(header::COOKIE, &h.cookie)
            .send()
            .await
            .unwrap()
            .text()
            .await
            .unwrap();
        assert!(body.contains("Waiting for you (chat)"), "cards fragment; body: {body}");
        // Full task page: bundled script only, no inline script/style, and
        // the CSP no longer allows 'unsafe-inline'.
        let resp = h
            .client
            .get(url(&h, &format!("/tasks/{id}")))
            .header(header::COOKIE, &h.cookie)
            .send()
            .await
            .unwrap();
        let csp = resp.headers().get(header::CONTENT_SECURITY_POLICY).unwrap().to_str().unwrap().to_string();
        assert!(!csp.contains("unsafe-inline"), "no unsafe-inline: {csp}");
        assert!(csp.contains("script-src 'self'"), "script self: {csp}");
        let html = resp.text().await.unwrap();
        assert!(html.contains("<script src=\"/static/app.js\""), "bundled script tag");
        assert_eq!(html.matches("<script").count(), 1, "no inline script");
        assert!(!html.contains("<style"), "no style block");
        assert!(!html.contains("style="), "no inline style");
    }

    /// Multi-select question: answering via the checkboxes form (repeated
    /// `options` values, no `text`) joins into one message that becomes the
    /// pending `ask_user` tool result.
    #[tokio::test]
    async fn ui_multiselect_question_posts_joined() {
        let h = spawn_harness(vec![
            tool_resp(
                "c1",
                "ask_user",
                serde_json::json!({"question": "Pick colors", "options": ["red", "blue"], "multi_select": true}),
                "asking",
            ),
            serde_json::json!({
                "choices": [{"message": {"role": "assistant", "content": "thanks"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1}
            }),
        ])
        .await;
        let id = create_task(&h, "Multi", "Ask me.").await;
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        assert!(html.contains("name=\"options\""), "checkboxes form; tail: {}", &html[html.len().saturating_sub(1500)..]);
        // Answer with checkboxes only (no text field): repeated `options`.
        let resp = h
            .client
            .post(url(&h, &format!("/tasks/{id}/messages")))
            .header(header::COOKIE, &h.cookie)
            .header(header::ORIGIN, &h.origin)
            .form(&[("options", "red"), ("options", "blue")])
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::FOUND);
        // Text-only turn parks as chat; the resumed request carries the
        // joined answer as the c1 tool result.
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let seen = h.or_seen.seen.lock().unwrap().clone();
        assert!(seen.len() >= 2, "model called again: {}", seen.len());
        let msgs = seen[1].get("messages").and_then(|v| v.as_array()).unwrap().clone();
        let last = msgs.last().unwrap();
        assert_eq!(last.get("role").and_then(|v| v.as_str()), Some("tool"));
        assert_eq!(last.get("tool_call_id").and_then(|v| v.as_str()), Some("c1"));
        assert!(
            last.get("content").and_then(|v| v.as_str()).unwrap_or("").contains("red, blue"),
            "joined answer in tool result: {last}"
        );
    }

    #[tokio::test]
    async fn healthz_and_security_headers() {
        let h = spawn_harness(vec![]).await;
        let resp = h.client.get(url(&h, "/healthz")).send().await.unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        assert_eq!(resp.text().await.unwrap(), "ok");
        let resp = h
            .client
            .get(url(&h, "/login"))
            .send()
            .await
            .unwrap();
        let headers = resp.headers();
        assert!(headers.contains_key(header::CONTENT_SECURITY_POLICY));
        assert_eq!(headers.get("x-content-type-options").unwrap(), "nosniff");
        assert_eq!(headers.get("x-frame-options").unwrap(), "DENY");
    }

    /// Structured `build{module, tasks}` failure: the parsed `file:line:
    /// error` diagnostic (not raw gradle output) reaches the model — checked
    /// on the next mock-OpenRouter request body.
    #[tokio::test]
    async fn structured_build_failure_returns_parsed_diagnostics() {
        let h = spawn_harness(vec![
            tool_resp(
                "c1",
                "build",
                serde_json::json!({"module": "notes", "tasks": ["compileDevKotlin"]}),
                "building",
            ),
            // Text-only turn parks as chat; the tool result is in request 2.
            serde_json::json!({
                "choices": [{"message": {"role": "assistant", "content": "build is broken"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1}
            }),
        ])
        .await;
        h.runner_seen.canned(
            ":notes:compileDevKotlin",
            1,
            "e: notes/src/main/java/notes/Main.kt:10:5 Type mismatch: inferred type is String but Int was expected\n> Task :notes:compileDevKotlin FAILED\n",
            "",
        );
        let id = create_task(&h, "Structured build", "Build notes.").await;
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;

        let seen = h.or_seen.seen.lock().unwrap().clone();
        assert!(seen.len() >= 2, "model called again: {}", seen.len());
        let body = serde_json::to_string(&seen[1]).unwrap();
        assert!(body.contains("notes/src/main/java/notes/Main.kt:10:5"), "parsed location: {body}");
        assert!(body.contains("Type mismatch"), "parsed message: {body}");
        assert!(!body.contains("> Task :notes:compileDevKotlin FAILED"), "no raw gradle noise: {body}");
    }

    /// `run_tests{module}` failure: reads the seeded JUnit XML via list+read
    /// and surfaces the failing test name to the model.
    #[tokio::test]
    async fn structured_run_tests_failure_shows_failing_test() {
        let h = spawn_harness(vec![
            tool_resp("c1", "run_tests", serde_json::json!({"module": "notes"}), "testing"),
            serde_json::json!({
                "choices": [{"message": {"role": "assistant", "content": "tests are red"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1}
            }),
        ])
        .await;
        // The gradle run itself fails; XML on disk carries the details.
        h.runner_seen.canned(
            ":notes:testDevUnitTest",
            1,
            "FAILURE: Build failed with an exception.\n> Task :notes:testDevUnitTest FAILED\n* What went wrong:\nThere were failing tests.",
            "",
        );
        let id = create_task(&h, "Structured tests", "Run notes tests.").await;
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;

        let seen = h.or_seen.seen.lock().unwrap().clone();
        assert!(seen.len() >= 2, "model called again: {}", seen.len());
        let body = serde_json::to_string(&seen[1]).unwrap();
        assert!(body.contains("shoutsGreeting"), "failing test name: {body}");
        assert!(body.contains("tests: FAILED"), "test summary: {body}");
    }

    /// `git_log{}` runs real `git log` in the mock slot checkout (seed
    /// commit visible).
    #[tokio::test]
    async fn structured_git_log_reads_slot_history() {
        let h = spawn_harness(vec![
            tool_resp("c1", "git_log", serde_json::json!({}), "logging"),
            serde_json::json!({
                "choices": [{"message": {"role": "assistant", "content": "history seen"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1}
            }),
        ])
        .await;
        let id = create_task(&h, "Git log", "Show history.").await;
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;

        let seen = h.or_seen.seen.lock().unwrap().clone();
        assert!(seen.len() >= 2, "model called again: {}", seen.len());
        let body = serde_json::to_string(&seen[1]).unwrap();
        assert!(body.contains("seed commit"), "slot history: {body}");
    }

    /// `github_read_issue{number: 903}` returns the recorded fixture text.
    #[tokio::test]
    async fn structured_github_read_issue_returns_fixture_text() {
        let h = spawn_harness(vec![
            tool_resp("c1", "github_read_issue", serde_json::json!({"number": 903}), "reading"),
            serde_json::json!({
                "choices": [{"message": {"role": "assistant", "content": "issue seen"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1}
            }),
        ])
        .await;
        let id = create_task(&h, "Read issue", "Read issue 903.").await;
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;

        let seen = h.or_seen.seen.lock().unwrap().clone();
        assert!(seen.len() >= 2, "model called again: {}", seen.len());
        let body = serde_json::to_string(&seen[1]).unwrap();
        assert!(body.contains("Celsius"), "issue body: {body}");
        assert!(body.contains("903"), "issue number: {body}");
        // The mock GitHub saw the read (token sent server-side only).
        let reqs = h.github_seen.requests();
        assert!(
            reqs.iter().any(|r| r.method == "GET" && r.path.ends_with("/issues/903")),
            "issue fetched: {reqs:?}"
        );
    }

    /// Start-from-issue form: blank title/prompt + issue #903 prefills from
    /// the mock GitHub, stores issue_number (shown on the task page), and
    /// the driver's first request carries the issue text.
    #[tokio::test]
    async fn start_from_issue_prefills_and_stores_number() {
        let h = spawn_harness(vec![serde_json::json!({
            "choices": [{"message": {"role": "assistant", "content": "working on it"}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1}
        })])
        .await;
        let id = create_task_from_issue(&h, "", "", 903).await;
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        assert!(html.contains("#903"), "prefilled title + issue badge; tail: {}", &html[html.len().saturating_sub(1500)..]);

        let seen = h.or_seen.seen.lock().unwrap().clone();
        assert!(!seen.is_empty(), "driver ran");
        let first = serde_json::to_string(&seen[0]).unwrap();
        assert!(first.contains("Celsius"), "prefilled prompt carries issue body: {first}");
        assert!(first.contains("(#903)"), "prompt notes the suffix convention: {first}");
    }

    /// `finish` without the ` (#<n>)` suffix is rejected on an issue-linked
    /// task (tool error, task keeps running); with the suffix it succeeds.
    #[tokio::test]
    async fn finish_without_issue_suffix_rejected_then_accepted() {
        let h = spawn_harness(vec![
            tool_resp("c1", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "hello issue\n"}), "editing"),
            tool_resp(
                "c2",
                "finish",
                serde_json::json!({
                    "summary": "Fixed the thing.",
                    "commit_message": "notes: fix the thing\n\nBody without suffix."
                }),
                "finishing without suffix",
            ),
            tool_resp(
                "c3",
                "finish",
                serde_json::json!({
                    "summary": "Fixed the thing.",
                    "commit_message": "notes: fix the thing (#903)\n\nBody with suffix."
                }),
                "finishing with suffix",
            ),
        ])
        .await;
        let id = create_task_from_issue(&h, "", "", 903).await;
        // finish parks for review; approve pushes.
        wait_review(&h, id, std::time::Duration::from_secs(60)).await;
        assert_eq!(post_action(&h, id, "approve").await, StatusCode::FOUND);
        wait_status(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        assert!(html.contains("pill succeeded\">succeeded<"), "finished after suffix fix; tail: {}", &html[html.len().saturating_sub(1500)..]);
        // Approve's post-push hook commented on the issue and labelled it.
        let reqs = h.github_seen.requests();
        let posts: Vec<String> = reqs
            .iter()
            .filter(|r| r.method == "POST")
            .map(|r| r.path.clone())
            .collect();
        assert!(posts.iter().any(|p| p.ends_with("/issues/903/comments")), "comment posted: {posts:?}");
        assert!(posts.iter().any(|p| p.ends_with("/issues/903/labels")), "label added: {posts:?}");
        assert!(html.contains("commented on issue"), "hook status surfaced on the task page");
        // The rejection surfaced the suffix rule to the model.
        let seen = h.or_seen.seen.lock().unwrap().clone();
        let all = serde_json::to_string(&seen).unwrap();
        assert!(all.contains("(#903)"), "suffix rule surfaced: {all}");
        // The pushed commit carries the suffix (discover the agent branch —
        // its slug derives from the prefilled issue title).
        let out = TokioCommand::new("git")
            .arg("--git-dir")
            .arg(h._remote.clone())
            .arg("branch")
            .arg("--list")
            .arg("agent/*")
            .arg("--format=%(refname:short)")
            .output()
            .await
            .unwrap();
        assert!(out.status.success());
        let branches = String::from_utf8(out.stdout).unwrap();
        let branch: String = branches.lines().next().unwrap_or("").trim().to_string();
        assert!(!branch.is_empty(), "an agent branch was pushed: {branches:?}");
        let out = TokioCommand::new("git")
            .arg("--git-dir")
            .arg(h._remote.clone())
            .arg("log")
            .arg("--format=%s")
            .arg(format!("main..refs/heads/{branch}"))
            .output()
            .await
            .unwrap();
        assert!(out.status.success(), "branch {branch} pushed");
        let log = String::from_utf8(out.stdout).unwrap();
        assert!(log.contains("notes: fix the thing (#903)"), "suffix in commit: {log}");
    }

    /// Plan gate: a `require_plan` task rejects `edit_file` before approval;
    /// `propose_plan` parks `needs_input(plan)`; approve resumes with "Plan
    /// approved" and edits are allowed; the changes path keeps the gate shut.
    #[tokio::test]
    async fn plan_gate_approve_and_changes() {
        let h = spawn_harness(vec![
            // Turn 1: edit before approval → gate error (stays in-loop).
            tool_resp("p0", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "hi\n"}), "trying edit first"),
            // Turn 2: propose the plan (sole call) → parks needs_input(plan).
            tool_resp("p1", "propose_plan", serde_json::json!({"plan_markdown": "## Plan\n1. edit greeting\n2. build"}), "planning"),
            // Turn 3 (after approve): edit now allowed, then finish.
            tool_resp("p2", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "hello planned\n"}), "editing"),
            tool_resp(
                "p3",
                "finish",
                serde_json::json!({
                    "summary": "Planned edit.",
                    "commit_message": "notes: planned edit\n\nBody here."
                }),
                "finishing",
            ),
        ])
        .await;
        let id = create_task_opts(&h, "Plan flow", "Edit the greeting, plan first.", true).await;

        // Parks as needs_input(plan); the page shows the plan + buttons.
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        assert!(html.contains("Waiting for you (plan)"), "plan park; tail: {}", &html[html.len().saturating_sub(1500)..]);
        assert!(html.contains("edit greeting"), "plan shown");

        // The gate error went to the model as the edit's tool result.
        let seen = h.or_seen.seen.lock().unwrap().clone();
        let mut found_gate = false;
        for req in &seen {
            for m in req.get("messages").and_then(|v| v.as_array()).unwrap() {
                if m.get("role").and_then(|v| v.as_str()) == Some("tool")
                    && m.get("tool_call_id").and_then(|v| v.as_str()) == Some("p0")
                    && m.get("content").and_then(|v| v.as_str()).unwrap_or("").contains("Propose a plan with propose_plan")
                {
                    found_gate = true;
                }
            }
        }
        assert!(found_gate, "gate error delivered for pre-approval edit");

        // Approve → resumes with "Plan approved. Proceed." → edit → finish.
        assert_eq!(post_action(&h, id, "plan/approve").await, StatusCode::FOUND);
        wait_review(&h, id, std::time::Duration::from_secs(60)).await;
        let seen = h.or_seen.seen.lock().unwrap().clone();
        let mut found_approved = false;
        for req in &seen {
            for m in req.get("messages").and_then(|v| v.as_array()).unwrap() {
                if m.get("role").and_then(|v| v.as_str()) == Some("tool")
                    && m.get("tool_call_id").and_then(|v| v.as_str()) == Some("p1")
                    && m.get("content").and_then(|v| v.as_str()).unwrap_or("").contains("Plan approved. Proceed.")
                {
                    found_approved = true;
                }
            }
        }
        assert!(found_approved, "approval result reached the model");
        // Review → approve → pushed with the edit.
        assert_eq!(post_action(&h, id, "approve").await, StatusCode::FOUND);
        wait_status(&h, id, std::time::Duration::from_secs(60)).await;
        let branch = format!("agent/{id}-plan-flow");
        let content = remote_show(&h, &branch, "notes/file.txt").await.unwrap();
        assert!(content.contains("hello planned"), "edit landed after approval");
    }

    /// Changes path in isolation: request changes → the pending propose_plan
    /// result contains the feedback and the plan stays unapproved (edits still
    /// gated), then a re-proposed plan can be approved.
    #[tokio::test]
    async fn plan_changes_keeps_gate_shut() {
        let h = spawn_harness(vec![
            tool_resp("q1", "propose_plan", serde_json::json!({"plan_markdown": "## v1\n1. step"}), "planning"),
            // After changes: try an edit (still gated), then re-propose.
            tool_resp("q2", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "hi\n"}), "still gated?"),
            tool_resp("q3", "propose_plan", serde_json::json!({"plan_markdown": "## v2\n1. revised step"}), "revising"),
            tool_resp("q4", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "hello revised\n"}), "editing"),
            tool_resp(
                "q5",
                "finish",
                serde_json::json!({
                    "summary": "Revised edit.",
                    "commit_message": "notes: revised edit\n\nBody here."
                }),
                "finishing",
            ),
        ])
        .await;
        let id = create_task_opts(&h, "Plan changes", "Propose, revise, edit.", true).await;
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;

        // Request changes → resumes; the pending result contains feedback.
        assert_eq!(post_plan_changes(&h, id, "revise: be more specific").await, StatusCode::FOUND);
        // Re-proposed plan parks again as needs_input(plan).
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        assert!(html.contains("Waiting for you (plan)"), "re-proposed plan parks");
        assert!(html.contains("revised step"), "v2 shown");

        // The resumed request carried "User requested changes to the plan: …".
        let seen = h.or_seen.seen.lock().unwrap().clone();
        let mut found_changes = false;
        let mut found_gate = false;
        for req in &seen {
            let body = serde_json::to_string(req).unwrap();
            if body.contains("User requested changes to the plan: revise: be more specific") {
                found_changes = true;
            }
            if body.contains("Propose a plan with propose_plan") {
                found_gate = true;
            }
        }
        assert!(found_changes, "changes feedback reached the model");
        assert!(found_gate, "edit stayed gated after changes");

        // Approve v2 → edit allowed → finish → review → approve.
        assert_eq!(post_action(&h, id, "plan/approve").await, StatusCode::FOUND);
        wait_review(&h, id, std::time::Duration::from_secs(60)).await;
        assert_eq!(post_action(&h, id, "approve").await, StatusCode::FOUND);
        wait_status(&h, id, std::time::Duration::from_secs(60)).await;
        let branch = format!("agent/{id}-plan-changes");
        let content = remote_show(&h, &branch, "notes/file.txt").await.unwrap();
        assert!(content.contains("hello revised"));
    }

    /// `update_todos` stores the list, emits the event, and the next request's
    /// system message contains the todos (volatile tail) after the rules.
    #[tokio::test]
    async fn todos_stored_and_in_system_prompt() {
        let h = spawn_harness(vec![
            tool_resp(
                "t1",
                "update_todos",
                serde_json::json!({"items": [
                    {"text": "edit greeting", "status": "in_progress"},
                    {"text": "run build", "status": "pending"},
                ]}),
                "tracking",
            ),
            tool_resp("t2", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "hello todos\n"}), "editing"),
            tool_resp(
                "t3",
                "finish",
                serde_json::json!({
                    "summary": "Todo edit.",
                    "commit_message": "notes: todo edit\n\nBody here."
                }),
                "finishing",
            ),
        ])
        .await;
        let id = create_task(&h, "Todos flow", "Track todos, edit, finish.").await;
        wait_review(&h, id, std::time::Duration::from_secs(60)).await;

        // The request AFTER the todos update carries them in the system msg.
        let seen = h.or_seen.seen.lock().unwrap().clone();
        assert!(seen.len() >= 2, "at least two model calls");
        let second = &seen[1];
        let msgs = second.get("messages").and_then(|v| v.as_array()).unwrap();
        let system = msgs.first().unwrap();
        let content = system.get("content").and_then(|v| v.as_str()).unwrap_or("");
        assert!(content.contains("Todo list:"), "todos section present");
        assert!(content.contains("edit greeting"), "todo text present");
        assert!(content.contains("[>]"), "in_progress marker");
        // Ordering: rules (root AGENTS.md content) before plan/todos tail.
        let root_pos = content.find("ROOT RULES FOR TEST");
        let todos_pos = content.find("Todo list:");
        assert!(root_pos.is_some() && todos_pos.is_some(), "both present");
        assert!(root_pos.unwrap() < todos_pos.unwrap(), "rules before todos");
        // The update_todos tool result was "ok".
        let mut found_ok = false;
        for req in seen.iter().skip(1) {
            for m in req.get("messages").and_then(|v| v.as_array()).unwrap() {
                if m.get("role").and_then(|v| v.as_str()) == Some("tool")
                    && m.get("tool_call_id").and_then(|v| v.as_str()) == Some("t1")
                {
                    let c = m.get("content").and_then(|v| v.as_str()).unwrap_or("");
                    assert_eq!(c, "ok");
                    found_ok = true;
                }
            }
        }
        assert!(found_ok, "update_todos result ok in history");
    }

    /// Review round-trip: finish → review (final_diff stored, no push) →
    /// request changes → resume restores edits, finish again → approve →
    /// single-commit push with the agent's message.
    #[tokio::test]
    async fn review_request_changes_then_approve() {
        let h = spawn_harness(vec![
            tool_resp("r1", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "hello v1\n"}), "editing"),
            tool_resp(
                "r2",
                "finish",
                serde_json::json!({
                    "summary": "First attempt.",
                    "commit_message": "notes: first attempt\n\nBody one."
                }),
                "finishing",
            ),
            // After request-changes resume: edit again, finish again.
            tool_resp("r3", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello v1\n", "new": "hello v2\n"}), "editing"),
            tool_resp(
                "r4",
                "finish",
                serde_json::json!({
                    "summary": "Second attempt.",
                    "commit_message": "notes: second attempt\n\nBody two."
                }),
                "finishing",
            ),
        ])
        .await;
        let id = create_task(&h, "Review flow", "Edit, finish, revise, finish.").await;
        let branch = format!("agent/{id}-review-flow");

        // First finish → review, no push, final_diff contains the change.
        wait_review(&h, id, std::time::Duration::from_secs(60)).await;
        assert!(!remote_has_branch(&h, &branch).await, "no push before approve");
        let html = task_html(&h, id).await;
        assert!(html.contains("First attempt."), "summary in card");
        assert!(html.contains("hello v1"), "diff in card");

        // Request changes = normal message in review → resume; the pending
        // finish result formats the feedback.
        assert_eq!(post_message(&h, id, "use v2 wording instead").await, StatusCode::FOUND);
        wait_review(&h, id, std::time::Duration::from_secs(60)).await;
        let html = task_html(&h, id).await;
        assert!(html.contains("Second attempt."), "second review snapshot");

        let seen = h.or_seen.seen.lock().unwrap().clone();
        let mut found_review_feedback = false;
        for req in &seen {
            let body = serde_json::to_string(req).unwrap();
            if body.contains("User reviewed the change and requested: use v2 wording instead") {
                found_review_feedback = true;
            }
        }
        assert!(found_review_feedback, "request-changes feedback reached the model");

        // Approve → one commit with the agent's (second) message.
        assert_eq!(post_action(&h, id, "approve").await, StatusCode::FOUND);
        wait_status(&h, id, std::time::Duration::from_secs(60)).await;
        let log = remote_log(&h, &branch).await;
        assert!(log.contains("notes: second attempt"), "second message pushed: {log}");
        assert_eq!(log.lines().count(), 1, "single squashed commit");
        let content = remote_show(&h, &branch, "notes/file.txt").await.unwrap();
        assert!(content.contains("hello v2"));
    }

    /// Follow-up after `succeeded` resumes from `pushed_sha` with no WIP, and
    /// the next finish/approve fast-forwards the remote branch.
    #[tokio::test]
    async fn followup_after_succeeded_fast_forwards() {
        let h = spawn_harness(vec![
            tool_resp("f1", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "hello one\n"}), "editing"),
            tool_resp(
                "f2",
                "finish",
                serde_json::json!({
                    "summary": "First.",
                    "commit_message": "notes: first change\n\nBody one."
                }),
                "finishing",
            ),
            // Follow-up claim: edit again, finish again.
            tool_resp("f3", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello one\n", "new": "hello two\n"}), "editing"),
            tool_resp(
                "f4",
                "finish",
                serde_json::json!({
                    "summary": "Second.",
                    "commit_message": "notes: second change\n\nBody two."
                }),
                "finishing",
            ),
        ])
        .await;
        let id = create_task(&h, "Followup flow", "Edit, finish, then follow up.").await;
        let branch = format!("agent/{id}-followup-flow");

        wait_review(&h, id, std::time::Duration::from_secs(60)).await;
        assert_eq!(post_action(&h, id, "approve").await, StatusCode::FOUND);
        wait_status(&h, id, std::time::Duration::from_secs(60)).await;
        assert_eq!(remote_log(&h, &branch).await.lines().count(), 1);

        // Follow-up message re-queues the succeeded task; the agent edits and
        // finishes again; approve fast-forwards (2 commits total).
        assert_eq!(post_message(&h, id, "also change it to two").await, StatusCode::FOUND);
        wait_review(&h, id, std::time::Duration::from_secs(60)).await;
        assert_eq!(post_action(&h, id, "approve").await, StatusCode::FOUND);
        wait_status(&h, id, std::time::Duration::from_secs(60)).await;
        let log = remote_log(&h, &branch).await;
        assert_eq!(log.lines().count(), 2, "fast-forwarded: {log}");
        assert!(log.contains("notes: second change"));
        let content = remote_show(&h, &branch, "notes/file.txt").await.unwrap();
        assert!(content.contains("hello two"));
    }

    /// Discard from review deletes the local WIP ref and is terminal; the
    /// remote branch (never pushed) does not exist.
    #[tokio::test]
    async fn discard_from_review_deletes_wip() {
        let h = spawn_harness(vec![
            tool_resp("d1", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "hello doomed\n"}), "editing"),
            tool_resp(
                "d2",
                "finish",
                serde_json::json!({
                    "summary": "Doomed.",
                    "commit_message": "notes: doomed change\n\nBody."
                }),
                "finishing",
            ),
        ])
        .await;
        let id = create_task(&h, "Discard flow", "Edit, finish, discard.").await;
        let branch = format!("agent/{id}-discard-flow");
        wait_review(&h, id, std::time::Duration::from_secs(60)).await;
        assert_eq!(post_action(&h, id, "discard").await, StatusCode::FOUND);
        let html = task_html(&h, id).await;
        assert!(html.contains("pill discarded\">discarded<"), "discarded");
        assert!(!remote_has_branch(&h, &branch).await, "never pushed");
    }

    /// AGENTS.md auto-load: reading `notes/sub/file` loads
    /// `notes/sub/AGENTS.md`; the next request's system message contains it
    /// after root/module rules; a second read does not reload.
    #[tokio::test]
    async fn nested_rules_loaded_on_first_touch() {
        // The seed remote gains notes/sub/AGENTS.md + a file beneath it.
        // init_remote is fixed; add the nested files via a follow-up commit
        // through the harness remote before the agent starts. The harness
        // builds its own remote, so instead: create the files in the mock
        // slot? No — rules load through runner.read from the slot checkout,
        // which clones the harness mirror (seeded from the remote). Push the
        // nested files to the remote BEFORE spawning agentd's mirror... but
        // spawn_harness owns remote creation. Simplest: extend the mock
        // runner's prepare to write the nested files into every slot checkout
        // (they are part of the "repo" for this test). That needs mock
        // support — instead, drive it through git: the slot checkout is a
        // clone of the mirror; the mirror is a clone of the remote. We add
        // the files to the REMOTE via a temp clone after spawn? The mirror
        // fetches origin/main only at task start (`fetch_main` + prepare from
        // main), so committing to the remote BEFORE creating the task works:
        // mirror fetch picks it up, slot clones the mirror.
        let h = spawn_harness(vec![
            tool_resp("g1", "read_file", serde_json::json!({"path": "notes/sub/file.txt"}), "reading"),
            tool_resp("g2", "read_file", serde_json::json!({"path": "notes/sub/file.txt"}), "reading again"),
            tool_resp("g3", "read_file", serde_json::json!({"path": "notes/file.txt"}), "reading root file"),
            serde_json::json!({
                "choices": [{"message": {"role": "assistant", "content": "done looking"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1}
            }),
        ])
        .await;
        // Add nested rules to the remote (mirror fetches at task start).
        {
            let work = h._tmp.path().join("subseed");
            git_no_cwd(&["clone", h._remote.to_str().unwrap(), work.to_str().unwrap()], h._tmp.path()).await;
            git_no_cwd(&["config", "user.email", "t@t"], &work).await;
            git_no_cwd(&["config", "user.name", "t"], &work).await;
            std::fs::create_dir_all(work.join("notes/sub")).unwrap();
            std::fs::write(work.join("notes/sub/AGENTS.md"), "SUB RULES: frobnicate carefully\n").unwrap();
            std::fs::write(work.join("notes/sub/file.txt"), "sub content\n").unwrap();
            git_no_cwd(&["add", "-A"], &work).await;
            git_no_cwd(&["commit", "-m", "add sub rules"], &work).await;
            git_no_cwd(&["push", "origin", "main"], &work).await;
        }
        let id = create_task(&h, "Rules flow", "Read the nested files.").await;
        wait_parked(&h, id, std::time::Duration::from_secs(60)).await;

        let seen = h.or_seen.seen.lock().unwrap().clone();
        assert!(seen.len() >= 3, "three reads + chat park = 4 requests, got {}", seen.len());
        // Request 2 (after first read): system msg contains SUB RULES after
        // root and module rules.
        let sys2 = seen[1].get("messages").and_then(|v| v.as_array()).unwrap()[0]
            .get("content").and_then(|v| v.as_str()).unwrap_or("");
        let root_pos = sys2.find("ROOT RULES FOR TEST").expect("root rules present");
        let module_pos = sys2.find("NOTES MODULE RULES").expect("module rules present");
        let sub_pos = sys2.find("SUB RULES").expect("nested rules loaded after first touch");
        assert!(root_pos < sub_pos && module_pos < sub_pos, "nested rules after root/module");
        // The first read's tool result noted the load.
        let mut noted = false;
        for m in seen[1].get("messages").and_then(|v| v.as_array()).unwrap() {
            if m.get("role").and_then(|v| v.as_str()) == Some("tool")
                && m.get("tool_call_id").and_then(|v| v.as_str()) == Some("g1")
            {
                let c = m.get("content").and_then(|v| v.as_str()).unwrap_or("");
                if c.contains("[loaded rules: notes/sub/AGENTS.md]") {
                    noted = true;
                }
            }
        }
        assert!(noted, "first read noted [loaded rules: notes/sub/AGENTS.md]");
        // Second read of the same dir: no reload note (already in rules_json).
        let mut reloaded = false;
        'outer: for req in seen.iter().skip(2) {
            for m in req.get("messages").and_then(|v| v.as_array()).unwrap() {
                if m.get("role").and_then(|v| v.as_str()) == Some("tool")
                    && m.get("tool_call_id").and_then(|v| v.as_str()) == Some("g2")
                {
                    let c = m.get("content").and_then(|v| v.as_str()).unwrap_or("");
                    if c.contains("[loaded rules:") {
                        reloaded = true;
                    }
                    break 'outer;
                }
            }
        }
        assert!(!reloaded, "second read does not reload");
    }

    /// Triage: a triage task reads code, gets refused for edit_file, then
    /// `finish_triage` fixes only the type + app labels and posts one marked
    /// comment; version / release labels are never touched.
    #[tokio::test]
    async fn triage_task_fixes_labels_and_comments() {
        let h = spawn_harness(vec![
            tool_resp("t1", "read_file", serde_json::json!({"path": "notes/file.txt"}), "looking"),
            tool_resp("t2", "edit_file", serde_json::json!({"path": "notes/file.txt", "old": "hello\n", "new": "x\n"}), "try edit"),
            tool_resp("t3", "finish_triage", serde_json::json!({
                "type": "bug", "app": "weather", "confidence": "high",
                "analysis_markdown": "The widget ignores the unit preference in `weather/Widget.kt:42`."
            }), "done"),
        ])
        .await;
        // Issue currently mislabeled as an enhancement for notes.
        let mut issue: serde_json::Value =
            serde_json::from_str(include_str!("testdata/github/issue_903.json")).unwrap();
        issue["labels"] = serde_json::json!([
            {"name": "enhancement"}, {"name": "notes"}, {"name": "2.6.12"}, {"name": "solved for next release"}
        ]);
        h.github_seen.respond("/repos/vayun-mathur/Modern-Apps/issues/903", issue.to_string());
        let id = h.store.create_triage_task(903, "#903 widgets", "triage", "weather", "test/model").unwrap();
        assert!(h.store.triage_insert(903, id).unwrap());
        wait_status(&h, id, std::time::Duration::from_secs(60)).await;
        let task = h.store.get_task(id).unwrap().unwrap();
        assert_eq!(task.status, crate::models::TaskStatus::Succeeded, "summary/error: {:?} {:?}", task.summary, task.error);

        let seen = serde_json::to_string(&h.or_seen.seen.lock().unwrap().clone()).unwrap();
        assert!(seen.contains("not available during triage"), "edit_file refused");

        let reqs = h.github_seen.requests();
        let writes: Vec<String> = reqs
            .iter()
            .filter(|r| r.method != "GET")
            .map(|r| format!("{} {}", r.method, r.path))
            .collect();
        assert!(writes.iter().any(|w| w == "DELETE /repos/vayun-mathur/Modern-Apps/issues/903/labels/enhancement"), "{writes:?}");
        assert!(writes.iter().any(|w| w == "DELETE /repos/vayun-mathur/Modern-Apps/issues/903/labels/notes"), "{writes:?}");
        assert!(writes.iter().any(|w| w == "POST /repos/vayun-mathur/Modern-Apps/issues/903/labels"), "{writes:?}");
        assert_eq!(writes.iter().filter(|w| w.ends_with("/issues/903/comments")).count(), 1, "{writes:?}");
        assert!(!writes.iter().any(|w| w.contains("2.6.12") || w.contains("solved")), "other labels untouched: {writes:?}");
        let row = h.store.triage_get(903).unwrap().unwrap();
        assert_eq!(row.status, "done");
        let result: serde_json::Value = serde_json::from_str(row.result_json.as_deref().unwrap()).unwrap();
        assert_eq!(result["type"], "bug");
        assert_eq!(result["app"], "weather");

        // Dashboard lists it with a fix button; the button creates a normal task.
        let dash = h.client.get(url(&h, "/")).header(header::COOKIE, &h.cookie).send().await.unwrap().text().await.unwrap();
        assert!(dash.contains("/triage/903/fix"), "triage row on dashboard");
        let resp = h.client.post(url(&h, "/triage/903/fix")).header(header::COOKIE, &h.cookie).header(header::ORIGIN, &h.origin).send().await.unwrap();
        assert!(resp.status().is_redirection(), "fix route redirects: {}", resp.status());
        let loc = resp.headers().get(header::LOCATION).unwrap().to_str().unwrap().to_string();
        let fix_id: i64 = loc.rsplit('/').next().unwrap().parse().unwrap();
        let fix = h.store.get_task(fix_id).unwrap().unwrap();
        assert_eq!(fix.kind, crate::models::TASK_KIND_TASK);
        assert_eq!(fix.issue_number, Some(903));
        assert!(fix.require_plan);
    }
}
