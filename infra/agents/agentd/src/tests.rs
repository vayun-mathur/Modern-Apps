//! Tests: unit tables + in-process integration (mock OpenRouter + mock
//! runner + temp git repos).
//!
//! Run: `cargo test -p agentd`.

#[cfg(test)]
mod unit {
    use crate::auth::{origin_allowed, session_token_from_cookie, Auth};
    use crate::models::{
        branch_name, cap_final_diff, cap_plan_markdown, plan_gate_message, render_todos, slugify,
        truncate_result, trim_placeholder, validate_commit_message,
        validate_commit_message_for_issue, validate_todos, TodoItem,
        FINAL_DIFF_CAP_BYTES, MAX_TODOS, MAX_TODO_CHARS, PLAN_MD_CAP_BYTES,
        RESULT_TRUNCATE_BYTES, INBOX_PLAN_APPROVE, INBOX_PLAN_CHANGES, INBOX_REVIEW_CHANGES,
        PENDING_PLAN, PENDING_REVIEW,
    };
    use crate::openrouter::{parse_chat_response, Message};
    use crate::agent::{format_pending_answer, trim_messages};
    use crate::markdown::render_markdown;
    use crate::models::{
        Event, Pending, Task, TaskStatus, EVENT_ASSISTANT, EVENT_STATUS, EVENT_TODOS,
        EVENT_TOOL_CALL, EVENT_TOOL_RESULT, EVENT_USER_MESSAGE, PENDING_BUDGET, PENDING_CHAT,
        PENDING_QUESTION,
    };
    use crate::ui::{
        dashboard_title, render_cards, render_dashboard_page, render_diff_lines, render_panel,
        render_task_page, render_timeline_events,
    };
    use crate::rules::ancestor_dirs;
    use crate::store::{InboxRow, Store};
    use crate::tools::{shell_looks_mutating, validate_build_command, validate_shell_command};

    fn test_store() -> (Store, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        (store, dir)
    }

    #[test]
    fn markdown_safe_subset() {
        // Basic blocks + inline formatting.
        let html = render_markdown("Hello **bold** and *italic* with `code`.");
        assert!(html.contains("<strong>bold</strong>"), "bold: {html}");
        assert!(html.contains("<em>italic</em>"), "italic: {html}");
        assert!(html.contains("<code>code</code>"), "code: {html}");
        // Fenced code block is escaped.
        let html = render_markdown("```rust\nlet x = a < b;\n```");
        assert!(html.contains("<pre"), "pre: {html}");
        assert!(html.contains("a &lt; b"), "escaped: {html}");
        assert!(!html.contains("let x = a < b;"), "no raw angle: {html}");
        // Lists.
        let html = render_markdown("- one\n- two\n");
        assert!(html.contains("<ul>") && html.contains("<li>one</li>"), "ul: {html}");
        let html = render_markdown("1. one\n2. two\n");
        assert!(html.contains("<ol>"), "ol: {html}");
        // Safe links kept, javascript: dropped to text.
        let html = render_markdown("[site](https://example.com/a?b=1)");
        assert!(html.contains("<a href=\"https://example.com/a?b=1\""), "link: {html}");
        let html = render_markdown("[x](javascript:alert(1))");
        assert!(!html.contains("<a"), "no anchor for javascript:: {html}");
        assert!(!html.contains("javascript:"), "no scheme text: {html}");
        assert!(html.contains("x"), "link text survives: {html}");
    }

    #[test]
    fn markdown_xss_escaped() {
        // Raw HTML (block + inline) is escaped, never passed through.
        let html = render_markdown("<script>alert(1)</script>");
        assert!(!html.contains("<script>"), "no raw script: {html}");
        assert!(html.contains("&lt;script&gt;"), "escaped: {html}");
        let html = render_markdown("a <b>bold</b> move");
        assert!(!html.contains("<b>"), "no raw b: {html}");
        assert!(html.contains("&lt;b&gt;bold&lt;/b&gt;"), "escaped inline: {html}");
        // Event handlers / img onerror never become elements.
        let html = render_markdown("<img src=x onerror=alert(1)>");
        assert!(!html.contains("<img"), "no img: {html}");
        assert!(html.contains("&lt;img"), "escaped: {html}");
        // Markdown image syntax renders alt text only (no external fetch).
        let html = render_markdown("![alt](https://example.com/i.png)");
        assert!(!html.contains("<img"), "no img element: {html}");
        assert!(html.contains("alt"), "alt text: {html}");
    }

    #[test]
    fn build_allowlist_accepts() {
        for cmd in [
            "./gradlew :notes:compileDevKotlin",
            "./gradlew :games:logicgate:lint --console=plain",
            "./gradlew :notes:testDebugUnitTest --stacktrace --info",
            "./gradlew :backup:detekt",
            "./gradlew :notes:checkMetadata",
            "./gradlew :notes:lintDev",
            "./gradlew :notes:cargoBuildNativeDebug",
            "./gradlew :notes:metadata",
            "./gradlew :my-app:testFooUnitTest",
            "./check notes",
            "./check games/logicgate",
            "./gradlew :a:b:c:testBarUnitTest --console=plain",
        ] {
            assert!(validate_build_command(cmd).is_ok(), "should accept {cmd:?}");
        }
    }

    #[test]
    fn build_allowlist_rejects() {
        for cmd in [
            "",
            "./gradlew :notes:assembleDebug",
            "./gradlew :notes:test",
            "./gradlew :notes:testDebug",
            "./gradlew :Notes:lint",
            "./gradlew notes:lint",
            "./gradlew :notes:lint --daemon",
            "./gradlew :notes:lint && ./gradlew :notes:detekt",
            "./gradlew :notes:lint; rm -rf /",
            "./gradlew :notes:lint | tee x",
            "./gradlew :notes:lint > out",
            "./gradlew :notes:lint $(whoami)",
            "./check all",
            "./check",
            "./check notes backup",
            "./install notes",
            "adb shell ls",
            "cargo test",
            "rm -rf /",
            "./gradlew",
            "gradlew :notes:lint; evil",
        ] {
            assert!(validate_build_command(cmd).is_err(), "should reject {cmd:?}");
        }
        // Error messages name the allowed forms.
        let err = validate_build_command("./gradlew :notes:assembleDebug").unwrap_err();
        assert!(err.contains("compileDevKotlin"), "helpful: {err}");
    }

    #[test]
    fn shell_rejection_table() {
        for cmd in [
            "./gradlew :notes:lint",
            "GRADLEW build",
            "./check notes",
            "./install notes",
            "adb devices",
            "sudo adb shell",
            "cargo build",
            "cargo test --all",
            "cargo run -p foo",
            "cargo check",
            "cargo clippy -- -D warnings",
            "echo hi | cargo test",
        ] {
            assert!(validate_shell_command(cmd).is_err(), "should reject {cmd:?}");
        }
        for cmd in [
            "ls notes/",
            "echo hello",
            "grep -r foo notes/ | head",
            "python3 scripts/x.py",
            "git status",
            "rg pattern notes/ -l",
        ] {
            assert!(validate_shell_command(cmd).is_ok(), "should allow {cmd:?}");
        }
        let err = validate_shell_command("./gradlew :notes:lint").unwrap_err();
        assert!(err.contains("build"), "points to build tool: {err}");
    }

    #[test]
    fn commit_message_validation() {
        assert!(validate_commit_message("notes: fix editor crash\n\nLong body here.").is_ok());
        assert!(validate_commit_message("games/logicgate: add level\n\nBody text.").is_ok());
        assert!(validate_commit_message("x").is_err()); // no scope
        assert!(validate_commit_message("Notes: fix\n\nBody.").is_err()); // uppercase scope
        assert!(validate_commit_message("notes: \n\nBody.").is_err()); // empty desc
        assert!(validate_commit_message("notes: only one line").is_err()); // no body
        assert!(validate_commit_message("").is_err());
        let long = format!("notes: {}\n\nBody.", "x".repeat(80));
        assert!(validate_commit_message(&long).is_err()); // first line > 80
    }

    #[test]
    fn commit_message_issue_suffix() {
        // No linked issue: plain validation, suffix not required.
        assert!(validate_commit_message_for_issue("notes: fix editor crash\n\nBody.", None).is_ok());
        // Linked issue: suffix required, helpful error otherwise.
        assert!(validate_commit_message_for_issue("notes: fix widget (#903)\n\nBody.", Some(903)).is_ok());
        assert!(validate_commit_message_for_issue("games/logicgate: add level (#12)\n\nBody.", Some(12)).is_ok());
        let err = validate_commit_message_for_issue("notes: fix editor crash\n\nBody.", Some(903)).unwrap_err();
        assert!(err.contains("(#903)"), "helpful: {err}");
        // Wrong number or placement rejected.
        assert!(validate_commit_message_for_issue("notes: fix (#904)\n\nBody.", Some(903)).is_err());
        assert!(validate_commit_message_for_issue("notes: (#903) fix\n\nBody.", Some(903)).is_err());
        // Suffix does not excuse other violations.
        assert!(validate_commit_message_for_issue("notes: fix (#903)", Some(903)).is_err()); // no body
        assert!(validate_commit_message_for_issue("x", Some(903)).is_err());
    }

    #[test]
    fn branch_slug_generation() {
        assert_eq!(branch_name(7, "Fix editor crash!"), "agent/7-fix-editor-crash");
        assert_eq!(branch_name(1, "!!!"), "agent/1-task");
        assert_eq!(branch_name(2, "games/logicgate: new level"), "agent/2-games-logicgate-new-level");
        let slug = slugify(&"a".repeat(200));
        assert!(slug.len() <= 48, "slug capped: {slug}");
        // Only safe ref chars.
        for b in [3, 4] {
            let name = branch_name(b, "Héllo wörld @$% & stuff");
            assert!(
                name.chars().all(|c| c.is_ascii_alphanumeric() || c == '/' || c == '-'),
                "{name}"
            );
        }
        // `git check-ref-format`-ish: no `..`, no trailing `-`.
        assert!(!branch_name(5, "trailing---").ends_with('-'));
    }

    #[test]
    fn truncation_and_trimming() {
        let (short, trunc) = truncate_result("hello");
        assert_eq!(short, "hello");
        assert!(!trunc);
        let big = "x".repeat(RESULT_TRUNCATE_BYTES + 1000);
        let (t, trunc) = truncate_result(&big);
        assert!(trunc);
        assert!(t.len() < big.len());
        assert!(t.contains("truncated"));
        // Char-boundary safety with multibyte text.
        let uni = "é".repeat(RESULT_TRUNCATE_BYTES + 10);
        let (u, _) = truncate_result(&uni);
        assert!(u.contains("truncated"));
        let ph = trim_placeholder("build", 9999);
        assert!(ph.contains("build") && ph.contains("9999"));

        // Context trimming: middle tool results become placeholders.
        let mut msgs = vec![Message::system("s".into()), Message::user("u".into())];
        for i in 0..8 {
            msgs.push(Message::tool_result(format!("c{i}"), "y".repeat(5000)));
        }
        msgs.push(Message::user("last".into()));
        trim_messages(&mut msgs, 10_000);
        let trimmed = msgs
            .iter()
            .filter(|m| {
                m.content
                    .as_ref()
                    .map(|c| c.contains("trimmed"))
                    .unwrap_or(false)
            })
            .count();
        assert!(trimmed >= 2, "old results trimmed, got {trimmed}");
        // Tail preserved.
        assert!(msgs.last().unwrap().content.as_ref().unwrap().contains("last"));
    }

    #[test]
    fn argon2_login_and_session_lifecycle() {
        use argon2::{Argon2, PasswordHasher, password_hash::SaltString};
        let salt = SaltString::from_b64("AAAAAAAAAAAAAAAAAAAAAA").unwrap();
        let hash = Argon2::default()
            .hash_password(b"correct horse", &salt)
            .unwrap()
            .to_string();
        let (store, _dir) = test_store();
        let auth = Auth::new(store, hash);
        assert!(auth.verify_password("correct horse"));
        assert!(!auth.verify_password("wrong"));
        let token = auth.issue_session().unwrap();
        assert!(auth.check_token(&token));
        assert!(!auth.check_token("bogus"));
        auth.revoke_token(&token);
        assert!(!auth.check_token(&token));
    }

    #[test]
    fn origin_check_table() {
        assert!(origin_allowed(Some("https://agents.maproj.org"), "https://agents.maproj.org"));
        assert!(!origin_allowed(None, "https://agents.maproj.org"));
        assert!(!origin_allowed(Some("https://evil.test"), "https://agents.maproj.org"));
        assert!(!origin_allowed(Some("https://agents.maproj.org.evil.test"), "https://agents.maproj.org"));
        assert!(!origin_allowed(Some("http://agents.maproj.org"), "https://agents.maproj.org"));
    }

    #[test]
    fn cookie_parse() {
        assert_eq!(
            session_token_from_cookie("a=1; agentd_session=tok123; b=2"),
            Some("tok123".to_string())
        );
        assert_eq!(session_token_from_cookie("agentd_session=; a=1"), None);
        assert_eq!(session_token_from_cookie("other=1"), None);
    }

    // ---- OpenRouter response fixtures ----

    fn tool_call_fixture() -> &'static [u8] {
        br#"{
          "id": "gen-1", "choices": [{
            "message": {
              "role": "assistant",
              "content": "Reading the file.",
              "tool_calls": [{
                "id": "call_1", "type": "function",
                "function": {"name": "read_file", "arguments": "{\"path\": \"notes/x.kt\"}"}
              }]
            }
          }],
          "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120, "cost": 0.0003}
        }"#
    }

    #[test]
    fn assistant_text_constructor() {
        let m = Message::assistant_text("hi".into());
        assert_eq!(m.role, "assistant");
    }

    #[test]
    fn openrouter_parses_tool_calls_and_cost() {
        let turn = parse_chat_response(tool_call_fixture()).unwrap();
        assert_eq!(turn.text, "Reading the file.");
        assert_eq!(turn.tool_calls.len(), 1);
        assert_eq!(turn.tool_calls[0].function.name, "read_file");
        assert_eq!(turn.usage.prompt_tokens, 100);
        assert!((turn.usage.cost_usd() - 0.0003).abs() < 1e-9);
    }

    #[test]
    fn openrouter_parses_text_only_and_missing_cost() {
        let body = br#"{"choices": [{"message": {"role": "assistant", "content": "done"}}],
          "usage": {"prompt_tokens": 5, "completion_tokens": 2}}"#;
        let turn = parse_chat_response(body).unwrap();
        assert_eq!(turn.text, "done");
        assert!(turn.tool_calls.is_empty());
        assert_eq!(turn.usage.cost_usd(), 0.0);
    }

    #[test]
    fn openrouter_parses_total_cost_and_parts_array() {
        let body = br#"{"choices": [{"message": {"role": "assistant",
          "content": [{"type": "text", "text": "hel"}, {"type": "text", "text": "lo"}]}}],
          "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_cost": 0.001}}"#;
        let turn = parse_chat_response(body).unwrap();
        assert_eq!(turn.text, "hello");
        assert!((turn.usage.cost_usd() - 0.001).abs() < 1e-9);
    }

    #[test]
    fn openrouter_parses_errors_and_empty_choices() {
        let body = br#"{"error": {"message": "insufficient credits", "code": 402}}"#;
        assert!(parse_chat_response(body).is_err());
        let body = br#"{"choices": []}"#;
        assert!(parse_chat_response(body).is_err());
        assert!(parse_chat_response(b"not json").is_err());
    }

    #[test]
    fn system_prompt_injects_scope_and_rules() {
        let p = crate::prompt::system_prompt("notes", Some("ROOT-RULES"), Some("MODULE-RULES"));
        assert!(p.contains("notes"));
        assert!(p.contains("ROOT-RULES"));
        assert!(p.contains("MODULE-RULES"));
        assert!(p.contains("finish"));
        // Product default: Plan first ON.
        assert!(p.contains("Plan first is ON"));
        let off = crate::prompt::build_system_prompt(
            "notes", &[], Some("R"), Some("M"), "", "", false, false,
        );
        assert!(off.contains("Plan first is OFF"));
        let approved = crate::prompt::build_system_prompt(
            "notes", &[], Some("R"), Some("M"), "my plan", "", true, true,
        );
        assert!(approved.contains("plan was approved"));
        assert!(approved.contains("my plan"));
    }

    #[test]
    fn store_roundtrip_and_claim() {
        let (store, _dir) = test_store();
        let id = store.create_task("t", "p", "notes", "m", 60, 2.0).unwrap();
        store.set_branch(id, "agent/1-t").unwrap();
        let claimed = store.claim_queued(0).unwrap().unwrap();
        assert_eq!(claimed.id, id);
        assert!(store.next_queued().unwrap().is_none());
        // Second claim loses (already running).
        store.create_task("t2", "p", "notes", "m", 60, 2.0).unwrap();
        // Events append with increasing seq.
        store.push_event(id, "status", &serde_json::json!({"a": 1})).unwrap();
        store.push_event(id, "status", &serde_json::json!({"a": 2})).unwrap();
        let evs = store.list_events(id, None).unwrap();
        assert_eq!(evs.len(), 2);
        assert_eq!(evs[0].seq, 0);
        assert_eq!(evs[1].seq, 1);
        let after = store.list_events(id, Some(0)).unwrap();
        assert_eq!(after.len(), 1);
    }

    #[test]
    fn triage_kv_and_task_roundtrip() {
        use crate::models::{TASK_KIND_TASK, TASK_KIND_TRIAGE};
        let (store, _dir) = test_store();

        // kv: missing → set → get → overwrite.
        assert_eq!(store.kv_get("triage_watermark").unwrap(), None);
        store.kv_set("triage_watermark", "2026-11-05T00:00:00Z").unwrap();
        assert_eq!(
            store.kv_get("triage_watermark").unwrap(),
            Some("2026-11-05T00:00:00Z".to_string())
        );
        store.kv_set("triage_watermark", "2026-11-06T00:00:00Z").unwrap();
        assert_eq!(
            store.kv_get("triage_watermark").unwrap(),
            Some("2026-11-06T00:00:00Z".to_string())
        );

        // Normal tasks default to kind='task'.
        let plain = store.create_task("t", "p", "notes", "m", 60, 2.0).unwrap();
        assert_eq!(store.get_task(plain).unwrap().unwrap().kind, TASK_KIND_TASK);

        // Triage task: kind, budget, plan gate off, issue link.
        let tri = store
            .create_triage_task(903, "#903 widgets", "prompt", "weather", "m")
            .unwrap();
        let task = store.get_task(tri).unwrap().unwrap();
        assert_eq!(task.kind, TASK_KIND_TRIAGE);
        assert_eq!(task.budget_steps, 40);
        assert!((task.budget_usd - 0.50).abs() < 1e-9);
        assert!(!task.require_plan);
        assert_eq!(task.issue_number, Some(903));

        // triage rows: missing → insert true → insert false (idempotent) →
        // get → set_result → recent ordering.
        assert!(store.triage_get(903).unwrap().is_none());
        assert!(store.triage_insert(903, tri).unwrap());
        assert!(!store.triage_insert(903, tri).unwrap());
        let row = store.triage_get(903).unwrap().unwrap();
        assert_eq!(row.issue_number, 903);
        assert_eq!(row.task_id, tri);
        assert_eq!(row.status, "queued");
        store
            .triage_set_result(903, "done", Some(r#"{"kind":"bug"}"#))
            .unwrap();
        let row = store.triage_get(903).unwrap().unwrap();
        assert_eq!(row.status, "done");
        assert_eq!(row.result_json, Some(r#"{"kind":"bug"}"#.to_string()));

        let tri2 = store
            .create_triage_task(904, "#904 other", "prompt", "", "m")
            .unwrap();
        assert!(store.triage_insert(904, tri2).unwrap());
        let recent = store.triage_recent(10).unwrap();
        assert_eq!(recent.len(), 2);
        assert_eq!(recent[0].issue_number, 904);
        assert_eq!(recent[1].issue_number, 903);
        assert_eq!(store.triage_recent(1).unwrap().len(), 1);
    }

    #[test]
    fn history_persisted_and_trimming_never_mutates_stored() {
        use crate::models::Pending;
        let (store, _dir) = test_store();
        let id = store.create_task("t", "p", "notes", "m", 60, 2.0).unwrap();
        let msgs = vec![
            Message::user("Task: t\n\np".into()),
            Message::assistant_calls(
                Some("reading".into()),
                vec![crate::openrouter::ToolCall {
                    id: "c1".into(),
                    kind: "function".into(),
                    function: crate::openrouter::FunctionCall {
                        name: "read_file".into(),
                        arguments: "{}".into(),
                    },
                }],
            ),
            Message::tool_result("c1".into(), "content here".into()),
        ];
        for m in &msgs {
            store.append_message(id, m).unwrap();
        }
        let loaded = store.load_messages(id).unwrap();
        assert_eq!(loaded.len(), 3);
        assert_eq!(loaded[0].role, "user");
        // Clone-trim: the stored rows are untouched.
        let big: Vec<Message> = (0..10)
            .map(|i| Message::tool_result(format!("cx{i}"), "y".repeat(5000)))
            .collect();
        let mut wide = loaded.clone();
        wide.extend(big);
        let before: Vec<String> = store
            .load_messages(id)
            .unwrap()
            .iter()
            .map(|m| m.content.clone().unwrap_or_default())
            .collect();
        let trimmed = crate::agent::trimmed_clone(&wide, 10_000);
        assert!(trimmed.len() == wide.len(), "trim never drops messages");
        let after: Vec<String> = store
            .load_messages(id)
            .unwrap()
            .iter()
            .map(|m| m.content.clone().unwrap_or_default())
            .collect();
        assert_eq!(before, after, "stored history never mutated");
        // Pairing: every tool_calls id still has its result in the clone.
        let mut calls = std::collections::HashSet::new();
        let mut results = std::collections::HashSet::new();
        for m in &trimmed {
            if m.role == "assistant" {
                if let Some(cs) = &m.tool_calls {
                    for c in cs {
                        calls.insert(c.id.clone());
                    }
                }
            }
            if m.role == "tool" {
                if let Some(tid) = &m.tool_call_id {
                    results.insert(tid.clone());
                }
            }
        }
        for c in &calls {
            assert!(results.contains(c), "unanswered call {c} after trim");
        }
        // Pending round-trips through the row.
        let p = Pending::new("question", Some("c9".into()), serde_json::json!({"question": "q?"}));
        let eff = store
            .park_task(id, crate::models::TaskStatus::NeedsInput, &p, None)
            .unwrap();
        assert_eq!(eff, crate::models::TaskStatus::NeedsInput);
        let t = store.get_task(id).unwrap().unwrap();
        assert_eq!(t.pending.unwrap().kind, "question");
        assert!(t.slot.is_none());
        let _ = Pending::new("x", None, serde_json::Value::Null);
    }

    #[test]
    fn park_task_lost_wakeup_requeues() {
        use crate::models::{Pending, TaskStatus};
        let (store, _dir) = test_store();
        let id = store.create_task("t", "p", "notes", "m", 60, 2.0).unwrap();
        // Message inserted mid-park (before park_task runs): re-queues.
        store.inbox_post_kind(id, "answer!", "").unwrap();
        let p = Pending::new("question", Some("c1".into()), serde_json::json!({}));
        let eff = store.park_task(id, TaskStatus::NeedsInput, &p, None).unwrap();
        assert_eq!(eff, TaskStatus::Queued, "inbox message forces re-queue");
        // Draining consumes each row exactly once.
        let rows = store.drain_inbox(id).unwrap();
        assert_eq!(rows, vec!["answer!".to_string()]);
        assert!(store.drain_inbox(id).unwrap().is_empty());
    }

    #[test]
    fn inbox_post_requeues_parked_and_succeeded() {
        use crate::models::{Pending, TaskStatus};
        let (store, _dir) = test_store();
        let id = store.create_task("t", "p", "notes", "m", 60, 2.0).unwrap();
        let p = Pending::new("chat", None, serde_json::json!({}));
        store.park_task(id, TaskStatus::NeedsInput, &p, None).unwrap();
        assert!(store.inbox_post_kind(id, "hello", "").unwrap());
        assert_eq!(store.get_task(id).unwrap().unwrap().status, TaskStatus::Queued);
        // Running tasks: inbox stored, no re-queue (driver drains it).
        store.update_status(id, TaskStatus::Running, None, None).unwrap();
        store.drain_inbox(id).unwrap();
        assert!(!store.inbox_post_kind(id, "steer", "").unwrap());
        assert_eq!(store.get_task(id).unwrap().unwrap().status, TaskStatus::Running);
        // Terminal tasks: no re-queue.
        store.update_status(id, TaskStatus::Discarded, None, None).unwrap();
        assert!(!store.inbox_post_kind(id, "late", "").unwrap());
    }

    #[test]
    fn restart_recovery_marks_only_running() {
        use crate::models::{Pending, TaskStatus};
        let (store, _dir) = test_store();
        let running = store.create_task("r", "p", "notes", "m", 60, 2.0).unwrap();
        store.claim_queued(0).unwrap();
        let parked = store.create_task("p", "p", "notes", "m", 60, 2.0).unwrap();
        let p = Pending::new("question", Some("c1".into()), serde_json::json!({}));
        store.park_task(parked, TaskStatus::NeedsInput, &p, None).unwrap();
        // Only the `running` row is an orphan.
        let orphans = store.interrupted_running_ids().unwrap();
        assert_eq!(orphans, vec![running]);
        // Synthetic results cover exactly the unanswered calls.
        store
            .append_message(
                running,
                &Message::assistant_calls(
                    None,
                    vec![crate::openrouter::ToolCall {
                        id: "cx".into(),
                        kind: "function".into(),
                        function: crate::openrouter::FunctionCall {
                            name: "read_file".into(),
                            arguments: "{}".into(),
                        },
                    }],
                ),
            )
            .unwrap();
        let hist = store.load_messages(running).unwrap();
        let answered: Vec<&str> = hist
            .iter()
            .filter(|m| m.role == "tool")
            .filter_map(|m| m.tool_call_id.as_deref())
            .collect();
        assert!(answered.is_empty());
        let _ = parked;
    }

    #[test]
    fn secrets_scrubbed() {
        let s = crate::agent::scrub_secrets("use Bearer secret-token-123 now");
        assert!(!s.contains("secret-token-123"), "{s}");
        assert!(s.contains("[redacted]"));
    }

    #[test]
    fn shell_mutation_detector_table() {
        // Rejected (mutating) patterns.
        for cmd in [
            "echo hi > out.txt",
            "cat a >> b",
            "echo x 2> err",
            "cmd1 | tee log.txt",
            "tee newfile",
            "sed -i 's/a/b/' notes/f.kt",
            "sed --in-place 's/a/b/' f",
            "mv a b",
            "sudo mv a b",
            "cp a b",
            "rm -rf notes/tmp",
            "touch newfile",
            "mkdir -p notes/sub",
            "git add notes/f.kt",
            "git commit -m 'x'",
            "git checkout -b feat",
            "git -C notes apply fix.patch",
            "git clean -fd",
            "git reset --hard HEAD",
            "git stash push",
            "echo hi; rm x",
        ] {
            assert!(shell_looks_mutating(cmd), "should look mutating: {cmd:?}");
        }
        // Allowed (read-only) commands.
        for cmd in [
            "ls notes/",
            "echo hello",
            "git status",
            "git diff --stat",
            "git log --oneline -5",
            "git show HEAD:notes/f.kt",
            "git blame notes/f.kt",
            "rg pattern notes/ -l",
            "grep -r foo notes/ | head",
            "guarantee availability",
            "cat notes/file.txt",
            "remember to teethe brush",
            // Documented gap: this detector is best-effort. A model that
            // really wants to mutate can do it through an allowed command
            // (python/perl apply-patch, etc.). The plan gate keeps honest
            // agents on the plan-first path; it is not a sandbox.
        ] {
            assert!(!shell_looks_mutating(cmd), "should look read-only: {cmd:?}");
        }
    }

    #[test]
    fn todos_validation_and_rendering() {
        assert_eq!(plan_gate_message(), "Propose a plan with propose_plan and wait for approval before editing or building.");
        let ok = vec![
            TodoItem { text: "write code".to_string(), status: "pending".to_string() },
            TodoItem { text: "run build".to_string(), status: "in_progress".to_string() },
            TodoItem { text: "done thing".to_string(), status: "done".to_string() },
        ];
        assert!(validate_todos(&ok).is_ok());
        let rendered = render_todos(Some(&serde_json::to_string(&ok).unwrap()));
        assert!(rendered.contains("[ ] write code"), "{rendered}");
        assert!(rendered.contains("[>] run build"), "{rendered}");
        assert!(rendered.contains("[x] done thing"), "{rendered}");
        assert_eq!(render_todos(None), "");
        assert_eq!(render_todos(Some("[]")), "");
        assert_eq!(render_todos(Some("not json")), "");
        // Too many items.
        let many: Vec<TodoItem> = (0..=MAX_TODOS)
            .map(|i| TodoItem { text: format!("t{i}"), status: "pending".to_string() })
            .collect();
        assert!(validate_todos(&many).is_err());
        assert!(validate_todos(&many[..MAX_TODOS]).is_ok());
        // Bad status / long text / empty text.
        assert!(validate_todos(&[TodoItem { text: "t".to_string(), status: "bogus".to_string() }]).is_err());
        assert!(validate_todos(&[TodoItem { text: "x".repeat(MAX_TODO_CHARS + 1), status: "pending".to_string() }]).is_err());
        assert!(validate_todos(&[TodoItem { text: "".to_string(), status: "pending".to_string() }]).is_err());
        assert!(validate_todos(&[TodoItem { text: "   ".to_string(), status: "done".to_string() }]).is_err());
    }

    #[test]
    fn final_diff_and_plan_caps() {
        let (short, trunc) = cap_final_diff("abc");
        assert_eq!(short, "abc");
        assert!(!trunc);
        let big = "d".repeat(FINAL_DIFF_CAP_BYTES + 500);
        let (capped, trunc) = cap_final_diff(&big);
        assert!(trunc);
        assert!(capped.contains("diff truncated"));
        assert!(capped.len() < big.len());
        let (p, trunc) = cap_plan_markdown("plan");
        assert_eq!(p, "plan");
        assert!(!trunc);
        let big_plan = "p".repeat(PLAN_MD_CAP_BYTES + 10);
        let (capped, trunc) = cap_plan_markdown(&big_plan);
        assert!(trunc && capped.contains("plan truncated"));
        // Multibyte boundary safety.
        let uni = "é".repeat(FINAL_DIFF_CAP_BYTES + 5);
        let (u, _) = cap_final_diff(&uni);
        assert!(u.contains("diff truncated"));
    }

    #[test]
    fn rules_ancestor_walk() {
        assert!(ancestor_dirs("AGENTS.md").is_empty());
        assert!(ancestor_dirs("notes/file.txt") == vec!["notes".to_string()]);
        assert_eq!(
            ancestor_dirs("notes/sub/deep/f.kt"),
            vec!["notes".to_string(), "notes/sub".to_string(), "notes/sub/deep".to_string()]
        );
        assert!(ancestor_dirs("/notes/file.txt") == vec!["notes".to_string()]);
        assert!(ancestor_dirs("single").is_empty());
    }

    #[test]
    fn pending_answer_formatting() {
        use crate::models::Pending;
        let row = |text: &str, kind: &str| InboxRow {
            text: text.to_string(),
            kind: kind.to_string(),
        };
        // Plan approve.
        let p = Pending::new(PENDING_PLAN, Some("c1".into()), serde_json::json!({}));
        assert_eq!(
            format_pending_answer(&p, &[row("approved", INBOX_PLAN_APPROVE)]),
            "Plan approved. Proceed."
        );
        // Plan changes (plan stays unapproved).
        assert_eq!(
            format_pending_answer(&p, &[row("add tests", INBOX_PLAN_CHANGES)]),
            "User requested changes to the plan: add tests"
        );
        // Review changes.
        let r = Pending::new(PENDING_REVIEW, Some("c9".into()), serde_json::json!({}));
        assert_eq!(
            format_pending_answer(&r, &[row("fix naming", INBOX_REVIEW_CHANGES)]),
            "User reviewed the change and requested: fix naming"
        );
        // Plain ask_user answer passes through raw.
        let q = Pending::new("question", Some("c2".into()), serde_json::json!({}));
        assert_eq!(
            format_pending_answer(&q, &[row("hello world", "")]),
            "hello world"
        );
    }

    #[test]
    fn store_plan_todos_rules_review_roundtrip() {
        use crate::models::TaskStatus;
        let (store, _dir) = test_store();
        let id = store.create_task("t", "p", "notes", "m", 60, 2.0).unwrap();
        // Plan gate flag (new-task "Plan first" checkbox).
        store.set_plan_gate(id, true).unwrap();
        let t = store.get_task(id).unwrap().unwrap();
        assert!(t.require_plan && !t.plan_approved);
        assert_eq!(t.status, TaskStatus::Queued);
        // Plan text + approval.
        store.set_plan_md(id, "## plan\n1. do things").unwrap();
        store.approve_plan(id).unwrap();
        let t = store.get_task(id).unwrap().unwrap();
        assert_eq!(t.plan_md.as_deref(), Some("## plan\n1. do things"));
        assert!(t.plan_approved);
        // Todos.
        let items = vec![TodoItem { text: "a".to_string(), status: "done".to_string() }, TodoItem { text: "b".to_string(), status: "pending".to_string() }];
        store.set_todos(id, &serde_json::to_string(&items).unwrap()).unwrap();
        let t = store.get_task(id).unwrap().unwrap();
        assert!(t.todos_json.unwrap().contains("\"text\":\"a\""));
        // Rules merge: existing entries win, nulls record known-missing.
        let mut first = std::collections::BTreeMap::new();
        first.insert("AGENTS.md".to_string(), Some("ROOT".to_string()));
        first.insert("notes/nope/AGENTS.md".to_string(), None);
        store.merge_rules(id, &first).unwrap();
        let mut second = std::collections::BTreeMap::new();
        second.insert("AGENTS.md".to_string(), Some("CHANGED".to_string()));
        second.insert("notes/sub/AGENTS.md".to_string(), Some("SUB".to_string()));
        store.merge_rules(id, &second).unwrap();
        let t = store.get_task(id).unwrap().unwrap();
        let map: serde_json::Value = serde_json::from_str(&t.rules_json.unwrap()).unwrap();
        assert_eq!(map["AGENTS.md"], serde_json::Value::String("ROOT".to_string()));
        assert!(map["notes/nope/AGENTS.md"].is_null());
        assert_eq!(map["notes/sub/AGENTS.md"], serde_json::Value::String("SUB".to_string()));
        // Review snapshot + push bookkeeping.
        store.set_review(id, "wipsha", "diff --git ...", "notes: x\n\nBody.", "summ").unwrap();
        let t = store.get_task(id).unwrap().unwrap();
        assert_eq!(t.wip_sha.as_deref(), Some("wipsha"));
        assert_eq!(t.final_diff.as_deref(), Some("diff --git ..."));
        store.set_pushed(id, "pushedsha").unwrap();
        let t = store.get_task(id).unwrap().unwrap();
        assert!(t.pushed);
        assert_eq!(t.pushed_sha.as_deref(), Some("pushedsha"));
        assert_eq!(t.base_sha.as_deref(), Some("pushedsha"));
        assert!(t.wip_sha.is_none() && t.pending.is_none());
        // Tagged inbox rows round-trip with kinds.
        store.inbox_post_kind(id, "approved", INBOX_PLAN_APPROVE).unwrap();
        store.inbox_post_kind(id, "plain", "").unwrap();
        let rows = store.drain_inbox_tagged(id).unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].kind, INBOX_PLAN_APPROVE);
        assert_eq!(rows[1].kind, "");
        assert!(store.drain_inbox(id).unwrap().is_empty());
    }

    #[test]
    fn approve_claim_is_exclusive_and_holds_messages() {
        use crate::models::TaskStatus;
        let (store, _dir) = test_store();
        let id = store.create_task("t", "p", "notes", "m", 60, 2.0).unwrap();
        store.update_status(id, TaskStatus::Review, None, None).unwrap();
        assert!(store.claim_approve(id).unwrap(), "first approve claims");
        assert!(!store.claim_approve(id).unwrap(), "double click is refused");
        // A message mid-approve is kept but must not re-queue the task.
        assert!(!store.inbox_post_kind(id, "one more thing", "").unwrap());
        assert_eq!(store.get_task(id).unwrap().unwrap().status, TaskStatus::Review);
        // Approve succeeds; finishing re-queues so the message becomes a follow-up.
        store.update_status(id, TaskStatus::Succeeded, None, None).unwrap();
        store.finish_approve(id).unwrap();
        assert_eq!(store.get_task(id).unwrap().unwrap().status, TaskStatus::Queued);
        assert!(!store.claim_approve(id).unwrap(), "not in review any more");
        // Stale claims from a crash are cleared at startup.
        let id2 = store.create_task("t2", "p", "notes", "m", 60, 2.0).unwrap();
        store.update_status(id2, TaskStatus::Review, None, None).unwrap();
        assert!(store.claim_approve(id2).unwrap());
        assert_eq!(store.clear_stale_approving().unwrap(), 1);
        assert!(store.claim_approve(id2).unwrap());
    }

    // ---- chat UI (ui.rs presentation) ----

    fn ui_task(status: TaskStatus, pending: Option<Pending>) -> Task {
        Task {
            id: 1,
            title: "Test task".to_string(),
            prompt: "do things".to_string(),
            module_scope: "notes".to_string(),
            model: "test/model".to_string(),
            kind: crate::models::TASK_KIND_TASK.to_string(),
            status,
            slot: None,
            branch: "agent/1-test-task".to_string(),
            base_sha: None,
            commit_sha: None,
            pushed: false,
            created_at: 0,
            updated_at: 0,
            started_at: None,
            finished_at: None,
            steps: 3,
            prompt_tokens: 100,
            completion_tokens: 50,
            cost_usd: 0.001,
            budget_steps: 60,
            budget_usd: 2.0,
            error: None,
            summary: None,
            pending,
            todos_json: None,
            rules_json: None,
            require_plan: true,
            plan_approved: false,
            wip_sha: None,
            pushed_sha: None,
            final_diff: None,
            commit_message: None,
            issue_number: None,
            plan_md: None,
            stop_requested: false,
        }
    }

    fn ui_event(seq: i64, kind: &str, body: serde_json::Value) -> Event {
        Event { id: seq, task_id: 1, seq, ts: 0, kind: kind.to_string(), body }
    }

    #[test]
    fn ui_question_card_option_forms_post_to_messages() {
        let p = Pending::new(
            PENDING_QUESTION,
            Some("c1".into()),
            serde_json::json!({
                "question": "Pick **one**?",
                "options": ["alpha", "beta"],
                "multi_select": true,
            }),
        );
        let html = render_cards(&ui_task(TaskStatus::NeedsInput, Some(p))).into_string();
        // One form per option, each posting its text to /messages.
        assert!(html.contains("action=\"/tasks/1/messages\""), "messages action: {html}");
        assert!(html.contains("value=\"alpha\""), "alpha option: {html}");
        assert!(html.contains("value=\"beta\""), "beta option: {html}");
        assert!(html.contains("Waiting for you (question)"), "park marker: {html}");
        // Multi-select checkboxes (joined server-side) + free-text box.
        assert!(html.contains("name=\"options\""), "checkboxes: {html}");
        assert!(html.contains("<textarea"), "free text: {html}");
        // Question text through the safe markdown subset.
        assert!(html.contains("<strong>one</strong>"), "markdown: {html}");
    }

    #[test]
    fn ui_plan_card_approve_and_changes_forms() {
        let p = Pending::new(
            PENDING_PLAN,
            Some("c2".into()),
            serde_json::json!({"plan_markdown": "## Plan\nDo **things** first."}),
        );
        let html = render_cards(&ui_task(TaskStatus::NeedsInput, Some(p))).into_string();
        assert!(html.contains("action=\"/tasks/1/plan/approve\""), "approve: {html}");
        assert!(html.contains("action=\"/tasks/1/plan/changes\""), "changes: {html}");
        assert!(html.contains("Waiting for you (plan)"), "park marker: {html}");
        assert!(html.contains("<strong>things</strong>"), "plan markdown: {html}");
    }

    #[test]
    fn ui_review_card_diff_classes_and_actions() {
        let mut t = ui_task(TaskStatus::Review, None);
        t.summary = Some("Changed the greeting.".to_string());
        t.commit_message = Some("notes: change greeting\n\nBody.".to_string());
        t.final_diff = Some(
            "@@ -1,2 +1,2 @@\n ctx\n-old line\n+new line\n more\n".to_string(),
        );
        let html = render_cards(&t).into_string();
        assert!(html.contains("class=\"del\""), "removed line class: {html}");
        assert!(html.contains("class=\"add\""), "added line class: {html}");
        assert!(html.contains("action=\"/tasks/1/approve\""), "approve: {html}");
        assert!(html.contains("action=\"/tasks/1/discard\""), "discard: {html}");
        assert!(html.contains("Approve &amp; push"), "approve label: {html}");
        assert!(html.contains("notes: change greeting"), "commit message: {html}");
        assert!(html.contains("Changed the greeting."), "summary: {html}");
    }

    #[test]
    fn ui_assistant_and_question_xss_escaped() {
        let events = vec![
            ui_event(0, EVENT_ASSISTANT, serde_json::json!({"text": "<script>alert(1)</script>"})),
            ui_event(1, EVENT_ASSISTANT, serde_json::json!({"text": "[x](javascript:alert(1))"})),
            ui_event(2, EVENT_USER_MESSAGE, serde_json::json!({"text": "<b>hi</b>"})),
        ];
        let html = render_timeline_events(&events).into_string();
        assert!(!html.contains("<script"), "no script element: {html}");
        assert!(!html.contains("javascript:"), "no js scheme: {html}");
        assert!(!html.contains("<a"), "no anchor: {html}");
        assert!(html.contains("&lt;script&gt;"), "raw html escaped: {html}");
        assert!(html.contains("&lt;b&gt;hi&lt;/b&gt;"), "user text escaped: {html}");
        // A hostile question payload is escaped the same way.
        let p = Pending::new(
            PENDING_QUESTION,
            Some("c9".into()),
            serde_json::json!({"question": "<img src=x onerror=alert(1)>?", "options": []}),
        );
        let card = render_cards(&ui_task(TaskStatus::NeedsInput, Some(p))).into_string();
        assert!(!card.contains("<img"), "no img: {card}");
        assert!(card.contains("&lt;img"), "escaped: {card}");
    }

    #[test]
    fn ui_tool_call_result_merge_and_caps() {
        let args = serde_json::json!({"path": "notes/file.txt"}).to_string();
        let events = vec![
            ui_event(0, EVENT_TOOL_CALL, serde_json::json!({"name": "read_file", "args": args})),
            ui_event(1, EVENT_TOOL_RESULT, serde_json::json!({"name": "read_file", "truncated": false, "result": "hello\n"})),
            // Orphan result (no preceding same-name call) renders on its own.
            ui_event(2, EVENT_TOOL_RESULT, serde_json::json!({"name": "build", "truncated": true, "result": "log tail"})),
            // A still-running call (no result follows) shows the chip.
            ui_event(3, EVENT_TOOL_CALL, serde_json::json!({"name": "run_tests", "args": "{}"})),
        ];
        let html = render_timeline_events(&events).into_string();
        assert_eq!(html.matches("details class=\"tool\"").count(), 3, "merged + orphan + running: {html}");
        assert!(html.contains("▸ read_file"), "chip: {html}");
        assert!(html.contains("hello"), "merged result inside: {html}");
        assert!(html.contains("(running…)"), "pending call: {html}");
        assert!(html.contains("[result truncated]"), "flag surfaced: {html}");
        // Long args cap the chip but keep a longer escaped copy inside.
        let long_args = "x".repeat(200);
        let events = vec![ui_event(0, EVENT_TOOL_CALL, serde_json::json!({"name": "build", "args": long_args}))];
        let html = render_timeline_events(&events).into_string();
        assert!(html.contains("…"), "chip capped: {html}");
        assert!(html.contains(&"x".repeat(200)), "full args inside: {html}");
    }

    #[test]
    fn ui_todos_panel_only() {
        let events = vec![ui_event(
            0,
            EVENT_TODOS,
            serde_json::json!({"items": [{"text": "edit greeting", "status": "in_progress"}]}),
        )];
        let timeline = render_timeline_events(&events).into_string();
        assert!(!timeline.contains("edit greeting"), "todos not in timeline: {timeline}");
        let mut t = ui_task(TaskStatus::Running, None);
        t.todos_json = Some(
            serde_json::to_string(&[
                crate::models::TodoItem { text: "edit greeting".to_string(), status: "in_progress".to_string() },
                crate::models::TodoItem { text: "run build".to_string(), status: "done".to_string() },
            ])
            .unwrap(),
        );
        let panel = render_panel(&t, "vayun-mathur/Modern-Apps").into_string();
        assert!(panel.contains("edit greeting"), "todo in panel: {panel}");
        assert!(panel.contains("▶"), "in-progress marker: {panel}");
        assert!(panel.contains("run build"), "done todo: {panel}");
        assert!(panel.contains("git fetch origin agent/1-test-task"), "fetch line: {panel}");
    }

    #[test]
    fn ui_pages_have_no_inline_script_or_style() {
        let t = ui_task(TaskStatus::Running, None);
        let task_html = render_task_page(&t, &[], false, "vayun-mathur/Modern-Apps").into_string();
        assert!(task_html.contains("<script src=\"/static/app.js\""), "bundled script: {task_html}");
        assert_eq!(task_html.matches("<script").count(), 1, "no inline script: {task_html}");
        assert!(!task_html.contains("<style"), "no style block: {task_html}");
        assert!(!task_html.contains("style="), "no inline style: {task_html}");
        assert!(task_html.contains("Stop"), "stop while running: {task_html}");
        let dash = render_dashboard_page(&[], false).into_string();
        assert_eq!(dash.matches("<script").count(), 1, "dashboard script: {dash}");
        assert!(!dash.contains("<style"), "dashboard style: {dash}");
        // Composer follow-up placeholder on succeeded tasks.
        let done_html = render_task_page(
            &ui_task(TaskStatus::Succeeded, None),
            &[],
            false,
            "vayun-mathur/Modern-Apps",
        )
        .into_string();
        assert!(done_html.contains("Follow-up"), "follow-up composer: {done_html}");
        // Parked cards exist for the textless kinds too.
        for kind in [PENDING_CHAT, PENDING_BUDGET] {
            let p = Pending::new(kind, None, serde_json::json!({"message": "m"}));
            let card = render_cards(&ui_task(TaskStatus::NeedsInput, Some(p))).into_string();
            assert!(card.contains("Waiting for you"), "explanation for {kind}: {card}");
        }
        // Diff viewer classes escape hostile content.
        let diff = render_diff_lines("+<script>alert(1)</script>\n-foo").into_string();
        assert!(!diff.contains("<script"), "diff escaped: {diff}");
        assert!(diff.contains("class=\"add\"") && diff.contains("class=\"del\""), "classes: {diff}");
    }

    #[test]
    fn ui_dashboard_grouping_and_title_count() {
        let mut needs_q = ui_task(TaskStatus::NeedsInput, Some(Pending::new(
            PENDING_QUESTION, Some("c1".into()),
            serde_json::json!({"question": "Which color?"}),
        )));
        needs_q.id = 1;
        let mut review = ui_task(TaskStatus::Review, None);
        review.id = 2;
        review.summary = Some("All done here.".to_string());
        let mut running = ui_task(TaskStatus::Running, None);
        running.id = 3;
        let mut succeeded = ui_task(TaskStatus::Succeeded, None);
        succeeded.id = 4;
        let tasks = vec![needs_q, review, running, succeeded];
        assert_eq!(dashboard_title(0), "agentd");
        assert_eq!(dashboard_title(2), "(2) agentd");
        let html = render_dashboard_page(&tasks, false).into_string();
        assert!(html.contains("<title>(2) agentd</title>"), "title count: {html}");
        assert!(html.contains("Needs you (2)"), "section first: {html}");
        let needs_pos = html.find("Needs you").unwrap();
        let running_pos = html.find(">Running<").unwrap();
        let recent_pos = html.find(">Recent<").unwrap();
        assert!(needs_pos < running_pos && running_pos < recent_pos, "section order: {html}");
        assert!(html.contains("question: Which color?"), "question reason: {html}");
        assert!(html.contains("review: All done here."), "review reason: {html}");
    }

    #[test]
    fn ui_status_events_capped_and_muted() {
        let events = vec![
            ui_event(0, EVENT_STATUS, serde_json::json!({"message": "task queued"})),
            ui_event(1, "mystery_kind", serde_json::json!({"x": 1})),
        ];
        let html = render_timeline_events(&events).into_string();
        assert!(html.contains("status-line"), "muted status line: {html}");
        assert!(html.contains("task queued"), "message kept: {html}");
    }
}
