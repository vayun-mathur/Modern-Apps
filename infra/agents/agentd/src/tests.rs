//! Tests: unit tables + in-process integration (mock OpenRouter + mock
//! runner + temp git repos).
//!
//! Run: `cargo test -p agentd`.

#[cfg(test)]
mod unit {
    use crate::auth::{origin_allowed, session_token_from_cookie, Auth};
    use crate::models::{
        branch_name, slugify, truncate_result, trim_placeholder, validate_commit_message,
        RESULT_TRUNCATE_BYTES,
    };
    use crate::openrouter::{parse_chat_response, Message};
    use crate::agent::trim_messages;
    use crate::store::Store;
    use crate::tools::{validate_build_command, validate_shell_command};

    fn test_store() -> (Store, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        (store, dir)
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
    }

    #[test]
    fn store_roundtrip_and_restart_recovery() {
        let (store, _dir) = test_store();
        let id = store.create_task("t", "p", "notes", "m", 60, 2.0).unwrap();
        store.set_branch(id, "agent/1-t").unwrap();
        let claimed = store.claim_queued(0).unwrap().unwrap();
        assert_eq!(claimed.id, id);
        assert!(store.next_queued().unwrap().is_none());
        // Second claim loses (already running).
        store.create_task("t2", "p", "notes", "m", 60, 2.0).unwrap();
        let n = store.mark_interrupted_running_failed().unwrap();
        assert_eq!(n, 1);
        let t = store.get_task(id).unwrap().unwrap();
        assert_eq!(t.status, crate::models::TaskStatus::Failed);
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
    fn secrets_scrubbed() {
        let s = crate::agent::scrub_secrets("use Bearer secret-token-123 now");
        assert!(!s.contains("secret-token-123"), "{s}");
        assert!(s.contains("[redacted]"));
    }
}
