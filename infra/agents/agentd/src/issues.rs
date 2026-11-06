//! GitHub glue for issue-linked tasks: fetching an issue at task creation,
//! and posting the after-push comment + label.
//!
//! The sandbox never sees the token: [`github_client`] reads it from the
//! process environment (`AGENTD_GITHUB_TOKEN`) in agentd, and all calls are
//! plain read/write through [`crate::github::GitHub`].
//!
//! [`after_push`] is the hook the parallel worker's `hooks.rs` will call
//! from the Approve path (it does not exist on this branch yet):
//!
//! ```ignore
//! // hooks.rs (other worker):
//! let issue_note = crate::issues::after_push(&ctx, &task).await;
//! ```
//!
//! It never panics and never fails the push: failures are logged and
//! returned as [`AfterPush::Failed`] for the caller to surface (e.g. as a
//! `status` event and on the review UI).

use crate::config::Config;
use crate::github::{GitHub, Issue, SOLVED_LABEL};

/// Build a [`GitHub`] client from config: repo from `AGENTD_GITHUB_REPO`
/// (default `vayun-mathur/Modern-Apps`), API base from `AGENTD_GITHUB_API`
/// (default `https://api.github.com`, overridden in tests to a mock
/// server), token from `Config::github_token` (`AGENTD_GITHUB_TOKEN`).
pub fn github_client(config: &Config) -> Result<GitHub, crate::github::Error> {
    GitHub::with_base_url(&config.github_api_base, &config.github_repo, config.github_token.clone())
}

/// Fetch one issue for the start-from-issue flow.
pub async fn fetch_issue(config: &Config, number: u64) -> Result<Issue, crate::github::Error> {
    github_client(config)?.read_issue(number).await
}

/// Whether a token is configured (after_push no-ops without one).
// Called from the merge-time `hooks.rs` Approve path; unused until then.
pub fn token_configured(config: &Config) -> bool {
    config.github_token.as_ref().is_some_and(|t| !t.trim().is_empty())
}

/// Outcome of [`after_push`], for the caller to surface in the UI.
// Called from the merge-time `hooks.rs` Approve path; unused until then.
#[derive(Debug, Clone)]
pub enum AfterPush {
    /// Comment posted and label added (holds the comment URL).
    Posted { comment_url: String },
    /// No linked issue: nothing to do.
    NoIssue,
    /// No `AGENTD_GITHUB_TOKEN` configured: skipped.
    NoToken,
    /// A step failed; the push itself already succeeded. The string is
    /// human-readable for a `status` event.
    Failed { message: String },
}

/// Comment body for a pushed fix. Marked as agent-written.
// Called from the merge-time `hooks.rs` Approve path; unused until then.
pub fn after_push_comment(task_title: &str, branch: &str, sha: &str, summary: &str) -> String {
    let short: String = sha.chars().take(12).collect();
    format!(
        "🤖 Agent fix pushed for \"{task_title}\".\n\n\
         Branch: `{branch}`\n\
         Commit: `{short}`\n\
         Upstream: https://git.maproj.org/Modern-Apps.git (`{branch}`)\n\n\
         {summary}\n\n\
         _Posted by the agent after operator approval._"
    )
}

/// Post-push hook: if the task links a GitHub issue and a token is
/// configured, post a comment (summary, branch, short sha, upstream link,
/// marked as from the agent) and add the `solved for next release` label.
///
/// Never panics; failures are logged and returned as [`AfterPush::Failed`]
/// (the caller emits a `status` event). Failures never block the push.
// Called from the merge-time `hooks.rs` Approve path; unused until then.
pub async fn after_push(
    ctx: &crate::agent::DriverCtx,
    task: &crate::models::Task,
) -> AfterPush {
    let Some(issue_number) = task.issue_number.filter(|n| *n > 0) else {
        return AfterPush::NoIssue;
    };
    let issue_number = issue_number as u64;
    if !token_configured(&ctx.config) {
        tracing::info!(
            "after_push: task {} links issue #{issue_number} but no github token is set; skipping comment",
            task.id
        );
        return AfterPush::NoToken;
    }
    let sha = task
        .pushed_sha
        .clone()
        .or_else(|| task.commit_sha.clone())
        .unwrap_or_else(|| "(unknown sha)".to_string());
    let summary = task.summary.clone().unwrap_or_default();
    let body = after_push_comment(&task.title, &task.branch, &sha, &summary);
    let gh = match github_client(&ctx.config) {
        Ok(gh) => gh,
        Err(e) => {
            let message = format!("GitHub comment for issue #{issue_number} skipped: {e}");
            tracing::warn!("after_push task {}: {message}", task.id);
            return AfterPush::Failed { message };
        }
    };
    let comment_url = match gh.post_comment(issue_number, &body).await {
        Ok(url) => url,
        Err(e) => {
            let message = format!("GitHub comment on issue #{issue_number} failed: {e}");
            tracing::warn!("after_push task {}: {message}", task.id);
            return AfterPush::Failed { message };
        }
    };
    if let Err(e) = gh.add_labels(issue_number, &[SOLVED_LABEL.to_string()]).await {
        let message = format!(
            "comment posted on issue #{issue_number} ({comment_url}) but adding label {SOLVED_LABEL:?} failed: {e}"
        );
        tracing::warn!("after_push task {}: {message}", task.id);
        return AfterPush::Failed { message };
    }
    tracing::info!(
        "after_push task {}: commented on issue #{issue_number} and added {SOLVED_LABEL:?}",
        task.id
    );
    AfterPush::Posted { comment_url }
}

/// Prefill for the start-from-issue form: `(title, prompt, module_scope)`.
/// Form values win over issue-derived ones when the user typed them — the
/// caller implements that; this returns the issue-derived triple.
pub fn prefill_from_issue(issue: &Issue) -> (String, String, Option<String>) {
    crate::github::task_from_issue(issue)
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::response::IntoResponse as _;
    use std::collections::HashMap;
    use std::sync::{Arc, Mutex};

    const ISSUE_903: &str = include_str!("testdata/github/issue_903.json");
    const COMMENTS_903: &str = include_str!("testdata/github/comments_903.json");

    #[derive(Debug, Clone)]
    struct Recorded {
        method: String,
        path: String,
        body: Vec<u8>,
    }

    #[derive(Clone, Default)]
    struct Mock {
        recorded: Arc<Mutex<Vec<Recorded>>>,
        responses: Arc<Mutex<HashMap<String, String>>>,
        /// When true, write endpoints (comment/label POSTs) return 500.
        fail_writes: Arc<std::sync::atomic::AtomicBool>,
    }

    impl Mock {
        fn requests(&self) -> Vec<Recorded> {
            self.recorded.lock().unwrap().clone()
        }
    }

    async fn handler(
        axum::extract::State(st): axum::extract::State<Mock>,
        req: axum::http::Request<axum::body::Body>,
    ) -> impl axum::response::IntoResponse {
        use axum::http::StatusCode;
        let (parts, body) = req.into_parts();
        let body = axum::body::to_bytes(body, 1024 * 1024).await.unwrap_or_default().to_vec();
        st.recorded.lock().unwrap().push(Recorded {
            method: parts.method.to_string(),
            path: parts.uri.path().to_string(),
            body,
        });
        let path = parts.uri.path().to_string();
        // Write endpoints take precedence over recorded GET fixtures sharing
        // the same path (e.g. the comments list).
        if parts.method == axum::http::Method::POST && path.ends_with("/comments") {
            if st.fail_writes.load(std::sync::atomic::Ordering::SeqCst) {
                return (StatusCode::INTERNAL_SERVER_ERROR, "mock write failure".to_string())
                    .into_response();
            }
            return (
                StatusCode::CREATED,
                r#"{"html_url":"https://github.com/vayun-mathur/Modern-Apps/issues/903#issuecomment-9"}"#.to_string(),
            )
                .into_response();
        }
        if parts.method == axum::http::Method::POST && path.ends_with("/labels") {
            if st.fail_writes.load(std::sync::atomic::Ordering::SeqCst) {
                return (StatusCode::INTERNAL_SERVER_ERROR, "mock write failure".to_string())
                    .into_response();
            }
            return (StatusCode::OK, r#"[{"name":"solved for next release"}]"#.to_string())
                .into_response();
        }
        if let Some(found) = st.responses.lock().unwrap().get(parts.uri.path()) {
            return (StatusCode::OK, found.clone()).into_response();
        }
        (StatusCode::NOT_FOUND, "no mock".to_string()).into_response()
    }

    fn test_config(base: &str, token: Option<&str>) -> Config {
        Config {
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
            github_repo: "vayun-mathur/Modern-Apps".into(),
            github_api_base: base.to_string(),
            github_token: token.map(str::to_string),
            test: Default::default(),
            test_skip_release_wait: false,
            triage_enabled: true,
            triage_interval_secs: 120,
        }
    }

    fn test_task(issue_number: Option<i64>) -> crate::models::Task {
        crate::models::Task {
            id: 7,
            title: "Fix widgets".into(),
            prompt: "p".into(),
            module_scope: "weather".into(),
            model: "m".into(),
            kind: crate::models::TASK_KIND_TASK.to_string(),
            status: crate::models::TaskStatus::Succeeded,
            slot: None,
            branch: "agent/7-fix-widgets".into(),
            base_sha: None,
            commit_sha: Some("abc123def456".into()),
            pushed: true,
            created_at: 0,
            updated_at: 0,
            started_at: None,
            finished_at: None,
            steps: 1,
            prompt_tokens: 0,
            completion_tokens: 0,
            cost_usd: 0.0,
            budget_steps: 60,
            budget_usd: 2.0,
            error: None,
            summary: Some("Fixed the unit handling.".into()),
            pending: None,
            todos_json: None,
            plan_md: None,
            rules_json: None,
            require_plan: false,
            plan_approved: false,
            wip_sha: None,
            pushed_sha: Some("abc123def456789".into()),
            final_diff: None,
            commit_message: None,
            issue_number,
            stop_requested: false,
        }
    }

    fn test_ctx(config: Config) -> crate::agent::DriverCtx {
        let dir = tempfile::tempdir().unwrap();
        let store = crate::store::Store::open(dir.path()).unwrap();
        // Keep the tempdir alive for the ctx lifetime in these tests by
        // leaking: the store holds an open sqlite file, not the path.
        std::mem::forget(dir);
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

    async fn spawn_mock() -> (String, Mock) {
        let mock = Mock::default();
        mock.responses.lock().unwrap().insert(
            "/repos/vayun-mathur/Modern-Apps/issues/903".into(),
            ISSUE_903.to_string(),
        );
        mock.responses.lock().unwrap().insert(
            "/repos/vayun-mathur/Modern-Apps/issues/903/comments".into(),
            COMMENTS_903.to_string(),
        );
        let app = axum::Router::new().fallback(handler).with_state(mock.clone());
        let lis = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = lis.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(lis, app).await.unwrap() });
        (format!("http://{addr}"), mock)
    }

    #[test]
    fn comment_body_marks_agent_summary_branch_sha_and_link() {
        let body = after_push_comment("Fix widgets", "agent/7-fix-widgets", "abc123def456789", "Fixed units.");
        assert!(body.contains("agent/7-fix-widgets"), "{body}");
        assert!(body.contains("abc123def456"), "{body}");
        assert!(body.contains("https://git.maproj.org/Modern-Apps.git"), "{body}");
        assert!(body.contains("Fixed units."), "{body}");
        assert!(body.contains("agent"), "{body}");
    }

    #[tokio::test]
    async fn after_push_posts_comment_and_label() {
        let (base, mock) = spawn_mock().await;
        let ctx = test_ctx(test_config(&base, Some("tok")));
        let out = after_push(&ctx, &test_task(Some(903))).await;
        let url = match out {
            AfterPush::Posted { comment_url } => comment_url,
            other => panic!("expected Posted, got {other:?}"),
        };
        assert!(url.contains("issuecomment"), "{url}");
        let reqs = mock.requests();
        let comment = reqs
            .iter()
            .find(|r| r.method == "POST" && r.path.ends_with("/issues/903/comments"))
            .expect("comment POST");
        let v: serde_json::Value = serde_json::from_slice(&comment.body).unwrap();
        let text = v.get("body").and_then(|b| b.as_str()).unwrap_or("");
        assert!(text.contains("agent/7-fix-widgets"), "{text}");
        assert!(text.contains("abc123def456"), "{text}");
        assert!(text.contains("Fixed the unit handling."), "{text}");
        let labels = reqs
            .iter()
            .find(|r| r.method == "POST" && r.path.ends_with("/issues/903/labels"))
            .expect("labels POST");
        let v: serde_json::Value = serde_json::from_slice(&labels.body).unwrap();
        assert_eq!(v, serde_json::json!({"labels": ["solved for next release"]}));
    }

    #[tokio::test]
    async fn after_push_noops_without_token_and_without_issue() {
        let (base, mock) = spawn_mock().await;
        // No token: no HTTP at all.
        let ctx = test_ctx(test_config(&base, None));
        assert!(matches!(after_push(&ctx, &test_task(Some(903))).await, AfterPush::NoToken));
        assert!(mock.requests().is_empty(), "no requests without token");
        // No linked issue: nothing to do (even with a token).
        let ctx = test_ctx(test_config(&base, Some("tok")));
        assert!(matches!(after_push(&ctx, &test_task(None)).await, AfterPush::NoIssue));
        assert!(mock.requests().is_empty(), "no requests without issue");
    }

    #[tokio::test]
    async fn after_push_failure_is_surfaced_not_raised() {
        let (base, mock) = spawn_mock().await;
        mock.fail_writes.store(true, std::sync::atomic::Ordering::SeqCst);
        let ctx = test_ctx(test_config(&base, Some("tok")));
        match after_push(&ctx, &test_task(Some(903))).await {
            AfterPush::Failed { message } => assert!(message.contains("#903"), "{message}"),
            other => panic!("expected Failed, got {other:?}"),
        }
    }
}
