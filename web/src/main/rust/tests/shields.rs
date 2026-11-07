//! Host-side integration tests for the `web_shields` Brave Shields engine.
//!
//! Seam note: `src/lib.rs` is a thin JNI wrapper around Brave's `adblock`
//! `Engine` — every `native*` entry point only moves JSON strings across the
//! boundary, and the two pure helpers (`json_string_array`, `apply_resources`)
//! are private to the crate. These tests therefore pin the `Engine`
//! filter-syntax semantics the wrappers forward as JSON (network blocking,
//! exception precedence, `$important`, `$removeparam` rewrites, cosmetic
//! rules, class/id mapping, cache serialize round-trips and the
//! invalid-resources fallback) without crossing JNI. If a test here fails,
//! the Kotlin-visible behaviour behind `ShieldsNative` changed too.

use adblock::request::Request;
use adblock::resources::Resource;
use adblock::Engine;
use std::collections::HashSet;

fn engine_with(filters: &str) -> Engine {
    Engine::new_with_list_text(filters.to_string())
}

/// Mirrors `nativeCheck`: build the request exactly as the JNI layer does and
/// return `(blocked, important, has_exception)`.
fn check(url: &str, source: &str, kind: &str, engine: &Engine) -> (bool, bool, bool) {
    let url = url.to_string();
    let source = source.to_string();
    let kind = kind.to_string();
    let request = Request::new(&url, &source, &kind, "get").expect("test urls must parse");
    let result = engine.check_network_request(&request);
    (result.should_block(), result.important, result.exception.is_some())
}

#[test]
fn blocks_request_matching_a_domain_rule() {
    let engine = engine_with("||ads.example.com^");
    let (blocked, _, _) =
        check("https://ads.example.com/banner.js", "https://news.example/", "script", &engine);
    assert!(blocked);
}

#[test]
fn allows_unrelated_request() {
    let engine = engine_with("||ads.example.com^");
    let (blocked, _, _) =
        check("https://cdn.example.com/app.js", "https://news.example/", "script", &engine);
    assert!(!blocked);
}

#[test]
fn empty_filter_list_blocks_nothing() {
    let engine = engine_with("");
    let (blocked, _, _) =
        check("https://ads.example.com/x.js", "https://news.example/", "script", &engine);
    assert!(!blocked);
}

#[test]
fn exception_rule_lifts_the_block() {
    let engine = engine_with("||ads.example.com^\n@@||ads.example.com/allowed^");
    let (blocked, _, has_exception) = check(
        "https://ads.example.com/allowed/banner.js",
        "https://news.example/",
        "script",
        &engine,
    );
    assert!(!blocked);
    assert!(has_exception);
}

#[test]
fn important_flag_is_reported() {
    let engine = engine_with("||ads.example.com^$important");
    let (_, important, _) =
        check("https://ads.example.com/x.js", "https://news.example/", "script", &engine);
    assert!(important);
}

#[test]
fn removeparam_rewrites_tracking_urls_without_blocking() {
    let engine = engine_with("||example.com^$removeparam=utm_source");
    let url = "https://example.com/p?utm_source=google&id=7".to_string();
    let source = "https://news.example/".to_string();
    // A typeless $removeparam rule applies to document/subdocument/XHR
    // requests only (adblock's NetworkFilterMask default for IS_REMOVEPARAM);
    // a script request is legitimately not rewritten by it.
    let kind = "document".to_string();
    let request = Request::new(&url, &source, &kind, "get").expect("test urls must parse");
    let result = engine.check_network_request(&request);
    assert!(!result.should_block());
    let rewritten = result.rewritten_url.expect("removeparam must rewrite the url");
    assert!(rewritten.contains("id=7"), "kept params must survive: {rewritten}");
    assert!(!rewritten.contains("utm_source"), "tracking param must be gone: {rewritten}");
}

#[test]
fn cosmetic_filter_yields_hide_selector() {
    let engine = engine_with("example.com##.adbox");
    let res = engine.url_cosmetic_resources("https://example.com/article");
    assert!(res.hide_selectors.contains(".adbox"));
}

#[test]
fn generic_cosmetic_rule_matches_an_observed_class() {
    let engine = engine_with("##.promo");
    let selectors = engine.hidden_class_id_selectors(
        vec!["promo".to_string()],
        Vec::<String>::new(),
        &HashSet::new(),
    );
    assert!(selectors.iter().any(|s| s.contains("promo")));
}

#[test]
fn serialize_deserialize_roundtrip_preserves_blocking() {
    let engine = engine_with("||ads.example.com^");
    let bytes = engine.serialize();
    let mut restored = Engine::default();
    restored.deserialize(&bytes).expect("cache must deserialize");
    let (blocked, _, _) =
        check("https://ads.example.com/x.js", "https://news.example/", "script", &restored);
    assert!(blocked);
}

#[test]
fn invalid_resources_json_leaves_the_engine_working() {
    let bad: Result<Vec<Resource>, _> = serde_json::from_str("not json");
    assert!(bad.is_err());
    let empty: Vec<Resource> = serde_json::from_str("[]").expect("empty resources must parse");
    let mut engine = engine_with("||ads.example.com^");
    engine.use_resources(empty);
    let (blocked, _, _) =
        check("https://ads.example.com/x.js", "https://news.example/", "script", &engine);
    assert!(blocked);
}

