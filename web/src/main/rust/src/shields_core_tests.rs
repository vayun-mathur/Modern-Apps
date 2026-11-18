//! Host unit tests for the `pub(crate)` core in `lib.rs` — the exact functions
//! the JNI wrappers delegate to. Assertions pin the JSON shapes and semantics
//! Kotlin sees from `ShieldsNative`, not just `adblock::Engine` behaviour.

use super::{
    apply_resources, check_handle, check_request, cosmetic_handle, cosmetic_resources_json,
    create_cache_handle, create_engine, create_engine_from_cache, create_handle, destroy_handle,
    hidden_class_id_json, hidden_handle, json_string_array, serialize_engine, serialize_handle,
};
use serde_json::{json, Value};

fn check_json(engine: &adblock::Engine, url: &str, source: &str, kind: &str) -> Value {
    let raw = check_request(engine, url, source, kind).expect("check must produce JSON");
    serde_json::from_str(&raw).expect("check JSON must parse")
}

fn cosmetic_json(engine: &adblock::Engine, url: &str) -> Value {
    let raw = cosmetic_resources_json(engine, url).expect("cosmetic must produce JSON");
    serde_json::from_str(&raw).expect("cosmetic JSON must parse")
}

fn hidden_json(engine: &adblock::Engine, classes: &str, ids: &str, exceptions: &str) -> Value {
    let raw = hidden_class_id_json(engine, classes, ids, exceptions)
        .expect("hidden selectors must produce JSON");
    serde_json::from_str(&raw).expect("hidden JSON must parse")
}

fn str_array(value: &Value) -> Vec<String> {
    value
        .as_array()
        .expect("expected JSON array")
        .iter()
        .map(|v| v.as_str().expect("expected string").to_string())
        .collect()
}

#[test]
fn blocked_domain_rule_reports_blocked_true() {
    let engine = create_engine("||ads.example.com^", "[]");
    let v = check_json(&engine, "https://ads.example.com/banner.js", "https://news.example/", "script");
    assert_eq!(v["blocked"], json!(true));
    assert_eq!(v["exception"], json!(false));
}

#[test]
fn unrelated_request_is_not_blocked() {
    let engine = create_engine("||ads.example.com^", "[]");
    let v = check_json(&engine, "https://cdn.example.com/app.js", "https://news.example/", "script");
    assert_eq!(v["blocked"], json!(false));
}

#[test]
fn empty_filters_block_nothing() {
    let engine = create_engine("", "[]");
    let v = check_json(&engine, "https://ads.example.com/x.js", "https://news.example/", "script");
    assert_eq!(v["blocked"], json!(false));
    assert_eq!(v["important"], json!(false));
    assert_eq!(v["exception"], json!(false));
}

#[test]
fn exception_precedence_reports_exception_and_not_blocked() {
    let engine = create_engine("||ads.example.com^\n@@||ads.example.com/allowed^", "[]");
    let v = check_json(
        &engine,
        "https://ads.example.com/allowed/banner.js",
        "https://news.example/",
        "script",
    );
    assert_eq!(v["exception"], json!(true));
    assert_eq!(v["blocked"], json!(false));
}

#[test]
fn important_rule_reports_important_true() {
    let engine = create_engine("||ads.example.com^$important", "[]");
    let v = check_json(&engine, "https://ads.example.com/x.js", "https://news.example/", "script");
    assert_eq!(v["blocked"], json!(true));
    assert_eq!(v["important"], json!(true));
}

#[test]
fn removeparam_rewritten_url_keeps_good_param_drops_tracking() {
    let engine = create_engine("||example.com^$removeparam=utm_source", "[]");
    let v = check_json(
        &engine,
        "https://example.com/p?utm_source=google&id=7",
        "https://news.example/",
        "document",
    );
    assert_eq!(v["blocked"], json!(false));
    let rewritten = v["rewritten"].as_str().expect("rewritten URL must be present");
    assert!(rewritten.contains("id=7"), "kept param must survive: {rewritten}");
    assert!(!rewritten.contains("utm_source"), "tracking param must be gone: {rewritten}");
}

#[test]
fn check_with_malformed_url_returns_none() {
    let engine = create_engine("||ads.example.com^", "[]");
    assert!(check_request(&engine, "not a url", "also not a url", "script").is_none());
    assert!(check_request(&engine, "", "", "script").is_none());
}

#[test]
fn cosmetic_hide_selector_is_reported_for_matching_domain() {
    let engine = create_engine("example.com##.adbox", "[]");
    let v = cosmetic_json(&engine, "https://example.com/article");
    assert!(str_array(&v["hide"]).iter().any(|s| s.contains(".adbox")), "hide: {v}");
    assert_eq!(v["generichide"], json!(false));
}

#[test]
fn cosmetic_on_unrelated_domain_has_empty_hide() {
    let engine = create_engine("example.com##.adbox", "[]");
    let v = cosmetic_json(&engine, "https://other.example/article");
    assert!(str_array(&v["hide"]).is_empty(), "hide must be empty: {v}");
}

#[test]
fn generichide_rule_sets_generichide_true() {
    // $generichide only takes effect as an exception network rule (adblock
    // rejects a non-exception $generichide); it suppresses generic hides.
    let engine = create_engine("##.promo\nexample.com##.specific\n@@||example.com^$generichide", "[]");
    let v = cosmetic_json(&engine, "https://example.com/article");
    assert_eq!(v["generichide"], json!(true), "cosmetic: {v}");
    assert!(str_array(&v["hide"]).iter().any(|s| s.contains(".specific")), "specific hide kept: {v}");
}

#[test]
fn hidden_class_id_selectors_include_generic_match() {
    let engine = create_engine("##.promo", "[]");
    let v = hidden_json(&engine, r#"["promo"]"#, "[]", "[]");
    assert!(str_array(&v).iter().any(|s| s.contains("promo")), "selectors: {v}");
}

#[test]
fn hidden_class_id_exceptions_suppress_selector() {
    let engine = create_engine("##.promo", "[]");
    let all = hidden_json(&engine, r#"["promo"]"#, "[]", "[]");
    assert!(!str_array(&all).is_empty());
    let suppressed = hidden_json(&engine, r#"["promo"]"#, "[]", r#"[".promo"]"#);
    assert!(
        !str_array(&suppressed).iter().any(|s| s.contains("promo")),
        "exception must suppress: {suppressed}"
    );
}

#[test]
fn hidden_class_id_with_unrelated_class_is_empty() {
    let engine = create_engine("##.promo", "[]");
    let v = hidden_json(&engine, r#"["unrelated"]"#, "[]", "[]");
    assert!(str_array(&v).is_empty(), "selectors: {v}");
}

#[test]
fn json_string_array_parses_valid_and_falls_back_to_empty_on_invalid() {
    assert_eq!(json_string_array(r#"["a","b"]"#), vec!["a".to_string(), "b".to_string()]);
    assert!(json_string_array("not json").is_empty());
    assert!(json_string_array(r#"{"a":1}"#).is_empty());
    assert!(json_string_array("[1,2]").is_empty());
    assert!(json_string_array("[]").is_empty());
}

#[test]
fn hidden_with_invalid_json_arrays_yields_empty() {
    let engine = create_engine("##.promo", "[]");
    let v = hidden_json(&engine, "not json", "{bad", "nope");
    assert!(str_array(&v).is_empty(), "selectors: {v}");
}

#[test]
fn invalid_resources_json_leaves_engine_working_and_blocking() {
    let engine = create_engine("||ads.example.com^", "not json");
    let v = check_json(&engine, "https://ads.example.com/x.js", "https://news.example/", "script");
    assert_eq!(v["blocked"], json!(true));
    let mut engine2 = create_engine("", "[]");
    apply_resources(&mut engine2, "not json");
    let engine3 = create_engine("||ads.example.com^", "{invalid");
    let v3 = check_json(&engine3, "https://ads.example.com/x.js", "https://news.example/", "script");
    assert_eq!(v3["blocked"], json!(true));
}

#[test]
fn valid_empty_resources_parse_and_engine_blocks() {
    let engine = create_engine("||ads.example.com^", "[]");
    let v = check_json(&engine, "https://ads.example.com/x.js", "https://news.example/", "script");
    assert_eq!(v["blocked"], json!(true));
}

#[test]
fn valid_redirect_resource_returns_redirect_body() {
    // Resource: name noop.txt, kind {"mime":"text/plain"}, content base64("hello").
    let resources = r#"[{"name":"noop.txt","aliases":[],"kind":{"mime":"text/plain"},"content":"aGVsbG8="}]"#;
    let engine = create_engine("||ads.example.com^$redirect=noop.txt", resources);
    let v = check_json(&engine, "https://ads.example.com/x.js", "https://news.example/", "script");
    let redirect = v["redirect"].as_str().expect("redirect body must be present");
    assert!(redirect.starts_with("data:text/plain;base64,"), "redirect: {redirect}");
    assert!(redirect.contains("aGVsbG8="), "redirect body must round-trip: {redirect}");
}

#[test]
fn scriptlet_injection_produces_script_when_resource_provided() {
    // Template scriptlet resource: `content` is base64 (per `Resource`), here
    // base64 of `window.__shields_noop = {{1}};`, injected with arg substitution.
    let resources = r#"[{"name":"noop.js","aliases":[],"kind":"template","content":"d2luZG93Ll9fc2hpZWxkc19ub29wID0ge3sxfX07"}]"#;
    let engine = create_engine("example.com##+js(noop, 42)", resources);
    let v = cosmetic_json(&engine, "https://example.com/article");
    let script = v["script"].as_str().expect("script must be a string");
    assert!(script.contains("__shields_noop"), "scriptlet must inject: {v}");
    assert!(script.contains("42"), "scriptlet arg must be substituted: {v}");
}

#[test]
fn serialize_then_create_from_cache_preserves_blocking() {
    let engine = create_engine("||ads.example.com^", "[]");
    let bytes = serialize_engine(&engine);
    assert!(!bytes.is_empty());
    let restored = create_engine_from_cache(&bytes, "[]").expect("cache must restore");
    let v = check_json(&restored, "https://ads.example.com/x.js", "https://news.example/", "script");
    assert_eq!(v["blocked"], json!(true));
    let v2 = check_json(&restored, "https://cdn.example.com/app.js", "https://news.example/", "script");
    assert_eq!(v2["blocked"], json!(false));
}

#[test]
fn create_from_cache_with_garbage_bytes_returns_none() {
    assert!(create_engine_from_cache(b"not a cache", "[]").is_none());
    assert!(create_engine_from_cache(&[], "[]").is_none());
}

#[test]
fn cosmetic_shape_has_all_expected_keys() {
    let engine = create_engine("example.com##.adbox", "[]");
    let v = cosmetic_json(&engine, "https://example.com/");
    for key in ["hide", "procedural", "exceptions", "script", "generichide"] {
        assert!(v.get(key).is_some(), "missing key {key} in {v}");
    }
    assert!(v["hide"].is_array());
    assert!(v["procedural"].is_array());
    assert!(v["exceptions"].is_array());
}

#[test]
fn check_shape_has_all_expected_keys() {
    let engine = create_engine("||ads.example.com^", "[]");
    let v = check_json(&engine, "https://ads.example.com/x.js", "https://news.example/", "script");
    for key in ["blocked", "important", "exception", "redirect", "rewritten"] {
        assert!(v.get(key).is_some(), "missing key {key} in {v}");
    }
}

#[test]
fn handle_dispatchers_round_trip_through_create_check_serialize_destroy() {
    let handle = create_handle(Some("||ads.example.com^\nexample.com##.adbox\n##.promo".to_string()), Some("[]".to_string()));
    assert_ne!(handle, 0);
    let raw = check_handle(handle, Some("https://ads.example.com/x.js".to_string()), Some("https://news.example/".to_string()), Some("script".to_string())).expect("check via handle");
    let v: Value = serde_json::from_str(&raw).unwrap();
    assert_eq!(v["blocked"], json!(true));
    let raw = cosmetic_handle(handle, Some("https://example.com/".to_string())).expect("cosmetic via handle");
    let v: Value = serde_json::from_str(&raw).unwrap();
    assert!(str_array(&v["hide"]).iter().any(|s| s.contains(".adbox")));
    let raw = hidden_handle(handle, Some(r#"["promo"]"#.to_string()), Some("[]".to_string()), Some("[]".to_string())).expect("hidden via handle");
    let v: Value = serde_json::from_str(&raw).unwrap();
    assert!(str_array(&v).iter().any(|s| s.contains("promo")));
    let bytes = serialize_handle(handle).expect("serialize via handle");
    assert!(!bytes.is_empty());
    let restored = create_cache_handle(bytes, Some("[]".to_string()));
    assert_ne!(restored, 0);
    let raw = check_handle(restored, Some("https://ads.example.com/x.js".to_string()), Some("https://news.example/".to_string()), Some("script".to_string())).expect("restored check");
    let v: Value = serde_json::from_str(&raw).unwrap();
    assert_eq!(v["blocked"], json!(true));
    destroy_handle(restored);
    destroy_handle(handle);
}

#[test]
fn handle_dispatchers_reject_null_handle_and_missing_args() {
    assert!(check_handle(0, Some("https://x.example/".to_string()), Some("https://y.example/".to_string()), Some("script".to_string())).is_none());
    assert!(cosmetic_handle(0, Some("https://x.example/".to_string())).is_none());
    assert!(hidden_handle(0, Some("[]".to_string()), Some("[]".to_string()), Some("[]".to_string())).is_none());
    assert!(serialize_handle(0).is_none());
    destroy_handle(0);
    assert_eq!(create_handle(None, Some("[]".to_string())), 0);
    assert_eq!(create_cache_handle(b"garbage".to_vec(), Some("[]".to_string())), 0);
    let handle = create_handle(Some("||ads.example.com^".to_string()), None);
    assert_ne!(handle, 0);
    assert!(check_handle(handle, None, Some("https://y.example/".to_string()), Some("script".to_string())).is_none());
    assert!(check_handle(handle, Some("https://ads.example.com/x.js".to_string()), None, Some("script".to_string())).is_none());
    assert!(cosmetic_handle(handle, None).is_none());
    assert!(hidden_handle(handle, None, Some("[]".to_string()), Some("[]".to_string())).is_none());
    assert!(hidden_handle(handle, Some("[]".to_string()), None, Some("[]".to_string())).is_none());
    destroy_handle(handle);
}

#[test]
fn hidden_id_selector_matches_generic_id_rule() {
    let engine = create_engine("###sponsored", "[]");
    let v = hidden_json(&engine, "[]", r#"["sponsored"]"#, "[]");
    assert!(str_array(&v).iter().any(|s| s.contains("sponsored")), "selectors: {v}");
}

#[test]
fn cosmetic_exception_selector_is_reported() {
    let engine = create_engine("example.com##.adbox\nexample.com#@#.adbox", "[]");
    let v = cosmetic_json(&engine, "https://example.com/article");
    assert!(str_array(&v["exceptions"]).iter().any(|s| s.contains(".adbox")), "exceptions: {v}");
}
