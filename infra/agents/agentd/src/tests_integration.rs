//! In-process integration tests: mock OpenRouter + mock runner (real temp
//! git repos, real bundles) + the real agentd HTTP server + scheduler.
//!
//! Covers: full success flow (read → edit → build → finish, branch pushed
//! to the temp remote, token absent from logs/events), unauthenticated POST
//! rejection, wrong-Origin rejection, and release-signal build preemption.

#[cfg(test)]
mod integration {
    use std::collections::VecDeque;
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

    struct SlotState {
        dir: PathBuf,
        branch: String,
        base_sha: String,
    }

    #[derive(Clone)]
    struct MockRunner {
        slots: Arc<Mutex<std::collections::HashMap<u32, SlotState>>>,
        seed_remote: PathBuf,
        build_seen: Arc<Mutex<Vec<String>>>,
        build_should_preempt: bool,
        /// Unique per-harness scratch dir. Slot checkouts live under it so
        /// parallel tests (same process, distinct harnesses) never share a
        /// slot directory. (An earlier revision used a fixed
        /// `$TMPDIR/mockslot-{pid}-{slot}` path; two harnesses preparing the
        /// same slot concurrently deleted each other's checkout mid-task.)
        scratch: PathBuf,
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
        // Per-harness scratch dir: parallel tests (same process, distinct
        // harnesses) must never share a slot checkout. A fixed
        // `$TMPDIR/mockslot-{pid}-{slot}` path let two harnesses preparing
        // the same slot concurrently delete each other's checkout mid-task.
        let dir = st.scratch.join(format!("slot-{slot}"));
        std::fs::create_dir_all(&st.scratch).unwrap();
        let _ = std::fs::remove_dir_all(&dir);
        git_no_cwd(
            &["clone", st.seed_remote.to_str().unwrap(), dir.to_str().unwrap()],
            &st.scratch,
        )
        .await;
        git(&dir, &["config", "user.email", "t@t"]).await;
        git(&dir, &["config", "user.name", "t"]).await;
        git(&dir, &["checkout", "-b", &branch]).await;
        let base = git(&dir, &["rev-parse", "HEAD"]).await;
        st.slots.lock().unwrap().insert(
            slot,
            SlotState { dir, branch, base_sha: base.trim().to_string() },
        );
        (
            StatusCode::OK,
            axum::Json(serde_json::json!({"base_sha": base.trim()})),
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
        AxPath(_slot): AxPath<u32>,
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
        // Fake build success; run `true`-equivalent by touching nothing.
        axum::Json(serde_json::json!({
            "exit_code": 0, "stdout": format!("ok: {cmd}"),
            "stderr": "", "truncated": false, "timed_out": false,
            "killed": false, "queued_ms": 0, "duration_ms": 5
        }))
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

    async fn serve_mock_runner(seed_remote: PathBuf, preempt: bool, scratch: PathBuf) -> (String, MockRunner) {
        std::fs::create_dir_all(&scratch).unwrap();
        let st = MockRunner {
            slots: Arc::new(Mutex::new(Default::default())),
            seed_remote,
            build_seen: Arc::new(Mutex::new(Vec::new())),
            build_should_preempt: preempt,
            scratch,
        };
        let app: axum::Router = axum::Router::new()
            .route("/v1/slots/:slot/prepare", post(mock_prepare))
            .route("/v1/slots/:slot/read", post(mock_read))
            .route("/v1/slots/:slot/write", post(mock_write))
            .route("/v1/slots/:slot/edit", post(mock_edit))
            .route("/v1/slots/:slot/exec", post(mock_exec))
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

    // ---------- harness: real agentd server pointed at the mocks ----------

    struct Harness {
        addr: SocketAddr,
        client: reqwest::Client,
        origin: String,
        _tmp: tempfile::TempDir,
        _remote: PathBuf,
        runner_seen: MockRunner,
        cookie: String,
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
        let (or_url, _or_seen) = serve_mock_or(script).await;
        let (runner_url, runner_seen) = serve_mock_runner(remote.clone(), preempt, tmp.path().join("slots")).await;

        // agentd's own mirror: a real bare clone of the temp remote.
        let mirror = tmp.path().join("mirror.git");
        git_no_cwd(
            &["clone", "--bare", remote.to_str().unwrap(), mirror.to_str().unwrap()],
            tmp.path(),
        )
        .await;

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
            test: crate::config::TestTuning {
                poll_ms: Some(20),
                build_wait_secs: Some(2),
                context_chars: None,
            },
            test_skip_release_wait: skip_wait,
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
            cookie,
        }
    }

    fn url(h: &Harness, path: &str) -> String {
        format!("http://{}{}", h.addr, path)
    }

    async fn wait_status(h: &Harness, id: i64, timeout: std::time::Duration) {
        let start = std::time::Instant::now();
        loop {
            // Poll the task page for the terminal pill. NOTE: the page's CSS
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
            for s in ["succeeded", "failed", "cancelled"] {
                let span = format!("pill {s}\">{s}</span>");
                if html.contains(&span) {
                    return;
                }
            }
            if start.elapsed() > timeout {
                panic!("task {id} did not finish in {timeout:?}; html tail: {}", &html[html.len().saturating_sub(500)..]);
            }
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }
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
        let branch = format!("agent/{id}-change-greeting");
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
        assert!(log.contains("notes: change greeting"), "commit msg: {log}");
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
        // release" to the model (and call the runner's builds/kill).
        let h = spawn_harness_full(
            vec![
                tool_resp(
                    "c1",
                    "build",
                    serde_json::json!({"command": "./gradlew :notes:lint"}),
                    "try build",
                ),
                // After preemption the model gets the message as a tool
                // result; end with text-only turns so the task fails with a
                // clear reason (or finishes — either way the message must
                // appear in the log).
                serde_json::json!({
                    "choices": [{"message": {"role": "assistant", "content": "retrying"}}],
                    "usage": {"prompt_tokens": 1, "completion_tokens": 1}
                }),
                serde_json::json!({
                    "choices": [{"message": {"role": "assistant", "content": "retrying again"}}],
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
        let _ = wait_status(&h, id, std::time::Duration::from_secs(60)).await;
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
}
