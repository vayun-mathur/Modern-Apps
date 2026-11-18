//! Tests for the hand-rolled std-only JSON codec (`json_escape`,
//! `to_json_std`, `from_json_std`, `parse_json_string`), `collect_group`
//! depth handling, and the JNI dispatch helpers. These target the lines
//! the KDBX round-trip suite in `tests.rs` does not reach.

use super::*;
use keepass::db::{fields, Database, GroupId};
use keepass::DatabaseKey;

const PW: &str = "correct horse battery staple";

fn parse(s: &str) -> Option<Vec<EntryMap>> {
    from_json_std(s)
}

fn one(key: &str, value: &str) -> EntryMap {
    let mut m = EntryMap::new();
    m.insert(key.into(), value.into());
    m
}

// ---------------------------------------------------------------------------
// json_escape / to_json_std
// ---------------------------------------------------------------------------

#[test]
fn escape_covers_quote_backslash_and_short_escapes() {
    let m = one("k\"\\", "a\"b\\c\nd\re\tf");
    let js = to_json_std(&[m]).unwrap();
    assert_eq!(js, r#"[{"k\"\\":"a\"b\\c\nd\re\tf"}]"#);
    assert_eq!(parse(&js).unwrap()[0]["k\"\\"], "a\"b\\c\nd\re\tf");
}

#[test]
fn escape_control_chars_as_unicode_and_roundtrips() {
    let value: String = (0u32..=0x1f).filter_map(char::from_u32).collect();
    let js = to_json_std(&[one("k", &value)]).unwrap();
    assert!(js.contains("\\u0000"), "nul escaped: {js}");
    assert!(js.contains("\\u001f"), "unit separator escaped: {js}");
    assert!(js.contains("\\u0008"), "backspace escaped as unicode: {js}");
    assert!(js.contains("\\u000c"), "form feed escaped as unicode: {js}");
    assert!(!js.chars().any(|c| c.is_control()), "no raw controls: {js:?}");
    assert_eq!(parse(&js).unwrap()[0]["k"], value);
}

#[test]
fn to_json_empty_and_sorted_keys() {
    assert_eq!(to_json_std(&[]).unwrap(), "[]");
    let mut m = EntryMap::new();
    m.insert("zebra".into(), "1".into());
    m.insert("apple".into(), "2".into());
    m.insert("Title".into(), "t".into());
    // BTreeMap iteration order is sorted, so exported JSON keys are sorted.
    assert_eq!(to_json_std(&[m]).unwrap(), r#"[{"Title":"t","apple":"2","zebra":"1"}]"#);
}

// ---------------------------------------------------------------------------
// parse_json_string: escapes
// ---------------------------------------------------------------------------

#[test]
fn parse_all_simple_escapes() {
    let parsed = parse(r#"[{"k":"\" \\ \/ \n \r \t \b \f"}]"#).unwrap();
    assert_eq!(parsed[0]["k"], "\" \\ / \n \r \t \u{08} \u{0c}");
}

#[test]
fn parse_unicode_bmp_escapes() {
    let parsed = parse(r#"[{"k":"café A €"}]"#).unwrap();
    assert_eq!(parsed[0]["k"], "café A €");
    let parsed = parse(r#"[{"café":"v"}]"#).unwrap();
    assert_eq!(parsed[0]["café"], "v");
}

#[test]
fn parse_surrogate_pair_combines_to_astral_char() {
    // 🔑 U+1F511 arrives from org.json-style escapers as a surrogate pair.
    let parsed = parse(r#"[{"k":"\uD83D\uDD11"}]"#).unwrap();
    assert_eq!(parsed[0]["k"], "\u{1f511}");
    // The pair also survives a full vault export/import round trip.
    let bytes = export_kdbx(PW, r#"[{"Title":"key","Password":"\uD83D\uDD11"}]"#).unwrap();
    let out = import_kdbx(PW, &bytes).unwrap();
    assert!(out.contains('\u{1f511}'), "emoji survived vault: {out}");
    assert_eq!(parse(&out).unwrap()[0]["Password"], "\u{1f511}");
}

#[test]
fn parse_rejects_invalid_unicode_escapes() {
    for bad in [
        r#"[{"k":"\uZZZZ"}]"#,       // non-hex digits
        r#"[{"k":"\u00G0"}]"#,       // one bad hex digit
        r#"[{"k":"\u12"}]"#,         // truncated hex (quote inside digits)
        r#"[{"k":"\uD800"}]"#,       // lone high surrogate
        r#"[{"k":"\uDC00"}]"#,       // lone low surrogate
        r#"[{"k":"\uD800abcd"}]"#,   // high surrogate, no \u escape after
        r#"[{"k":"\uD800A"}]"#,  // high surrogate, non-surrogate after
        r#"[{"k":"\uD800\uZZZZ"}]"#, // high surrogate, invalid low hex
    ] {
        assert_eq!(parse(bad), None, "must reject {bad}");
    }
    // Unterminated \u at end of input.
    assert_eq!(parse("[{\"k\":\"\\u1"), None);
}

#[test]
fn parse_rejects_unknown_escape_and_trailing_backslash() {
    assert_eq!(parse(r#"[{"k":"a\xb"}]"#), None);
    assert_eq!(parse(r#"[{"k":"a\ab"}]"#), None);
    assert_eq!(parse("[{\"k\":\"abc\\"), None);
    assert_eq!(parse(r#"[{"k":"unterminated}]"#), None);
}

// ---------------------------------------------------------------------------
// from_json_std: shapes, whitespace, duplicates
// ---------------------------------------------------------------------------

#[test]
fn parse_empty_array_and_empty_objects() {
    assert_eq!(parse("[]").unwrap().len(), 0);
    assert_eq!(parse("[{}]").unwrap(), vec![EntryMap::new()]);
    assert_eq!(parse("[{},{}]").unwrap().len(), 2);
    assert_eq!(parse(" [ ] ").unwrap().len(), 0);
}

#[test]
fn parse_whitespace_variants() {
    let parsed = parse(" \t\r\n[ { \"k\" : \"v\" } , { \"a\" : \"b\" } ]\n").unwrap();
    assert_eq!(parsed.len(), 2);
    assert_eq!(parsed[0]["k"], "v");
    assert_eq!(parsed[1]["a"], "b");
}

#[test]
fn parse_duplicate_keys_last_wins_and_escaped_keys() {
    let parsed = parse(r#"[{"k":"first","k":"second"}]"#).unwrap();
    assert_eq!(parsed[0]["k"], "second");
    let parsed = parse(r#"[{"a\"b\n":"v"}]"#).unwrap();
    assert_eq!(parsed[0]["a\"b\n"], "v");
}

#[test]
fn parse_rejects_non_string_values_and_nesting() {
    for bad in [
        r#"[{"k":123}]"#, r#"[{"k":true}]"#, r#"[{"k":null}]"#,
        r#"[{"k":[]}]"#, r#"[{"k":{}}]"#, r#"[{"k":["x"]}]"#,
        r#"["just a string"]"#, r#"[123]"#, r#"[[{"k":"v"}]]"#,
    ] {
        assert_eq!(parse(bad), None, "must reject {bad}");
    }
}

#[test]
fn parse_rejects_malformed_shapes() {
    for bad in [
        "", "   ", "{}", "x", "[",
        r#"[{"k" "v"}]"#,          // missing colon
        r#"[{"k":"v" "a":"b"}]"#,  // missing comma between pairs
        r#"[{"k":"v"} {"a":"b"}]"#, // missing comma between objects
        r#"[{"k":"v"]"#,           // missing closing brace
        r#"[{"k":"v"}"#,           // missing closing bracket
        r#"[{"k":"v""#,            // truncated after value
        r#"[{"k":"v"},]"#,         // trailing comma
        r#"{ "k":"v" }"#,          // object without array
    ] {
        assert_eq!(parse(bad), None, "must reject {bad:?}");
    }
}

#[test]
fn parse_rejects_trailing_garbage_but_allows_trailing_whitespace() {
    assert_eq!(parse(r#"[{"k":"v"}] garbage"#), None);
    assert_eq!(parse(r#"[] garbage"#), None);
    assert_eq!(parse(r#"[{"k":"v"}]]"#), None);
    assert!(parse("[{\"k\":\"v\"}] \t\n").is_some());
}

// ---------------------------------------------------------------------------
// collect_group / vault-level behaviour
// ---------------------------------------------------------------------------

#[test]
fn collect_group_with_unknown_id_collects_nothing() {
    let db = Database::new();
    let mut out: Vec<EntryMap> = Vec::new();
    collect_group(&db, GroupId::new(), &mut out);
    assert!(out.is_empty());
}

#[test]
fn deeply_nested_groups_three_levels_are_flattened() {
    let mut db = Database::new();
    db.root_mut().add_entry().set_unprotected(fields::TITLE, "L0");
    let l1 = db.root_mut().add_group().edit(|g| g.name = "L1".into()).id();
    db.group_mut(l1).unwrap().add_entry().set_unprotected(fields::TITLE, "L1Entry");
    let l2 = db.group_mut(l1).unwrap().add_group().edit(|g| g.name = "L2".into()).id();
    db.group_mut(l2).unwrap().add_entry().set_unprotected(fields::TITLE, "L2Entry");
    let l3 = db.group_mut(l2).unwrap().add_group().edit(|g| g.name = "L3".into()).id();
    db.group_mut(l3).unwrap().add_entry().set_unprotected(fields::TITLE, "L3Entry");
    let mut bytes = Vec::new();
    db.save(&mut bytes, DatabaseKey::new().with_password(PW)).unwrap();
    let parsed = parse(&import_kdbx(PW, &bytes).unwrap()).unwrap();
    let titles: Vec<&str> = parsed.iter().filter_map(|e| e.get("Title").map(|s| s.as_str())).collect();
    assert_eq!(parsed.len(), 4);
    for t in ["L0", "L1Entry", "L2Entry", "L3Entry"] {
        assert!(titles.contains(&t), "missing {t}: {titles:?}");
    }
}

#[test]
fn empty_fields_and_password_only_entries_roundtrip() {
    let bytes = export_kdbx(PW, "[{}]").unwrap();
    let parsed = parse(&import_kdbx(PW, &bytes).unwrap()).unwrap();
    assert_eq!(parsed, vec![EntryMap::new()]);
    let bytes = export_kdbx(PW, r#"[{"Password":"only-secret"}]"#).unwrap();
    let parsed = parse(&import_kdbx(PW, &bytes).unwrap()).unwrap();
    assert_eq!(parsed.len(), 1);
    assert_eq!(parsed[0].len(), 1);
    assert_eq!(parsed[0]["Password"], "only-secret");
}

#[test]
fn custom_fields_come_back_in_sorted_key_order() {
    let bytes = export_kdbx(PW, r#"[{"zebra":"1","Title":"t","apple":"2"}]"#).unwrap();
    let out = import_kdbx(PW, &bytes).unwrap();
    let title = out.find("\"Title\"").unwrap();
    let apple = out.find("\"apple\"").unwrap();
    let zebra = out.find("\"zebra\"").unwrap();
    assert!(title < apple && apple < zebra, "sorted keys in output: {out}");
}

// ---------------------------------------------------------------------------
// JNI dispatch helpers
// ---------------------------------------------------------------------------

#[test]
fn jni_dispatch_maps_none_inputs_to_none() {
    assert_eq!(import_for_jni(None, Some(vec![1, 2, 3])), None);
    assert_eq!(import_for_jni(Some(PW.into()), None), None);
    assert_eq!(import_for_jni(None, None), None);
    assert_eq!(export_for_jni(None, Some("[]".into())), None);
    assert_eq!(export_for_jni(Some(PW.into()), None), None);
    assert_eq!(export_for_jni(None, None), None);
}

#[test]
fn jni_dispatch_roundtrips_and_propagates_failures() {
    let bytes = export_for_jni(Some(PW.into()), Some(r#"[{"Title":"x","Password":"p"}]"#.into())).expect("export via dispatch");
    let out = import_for_jni(Some(PW.into()), Some(bytes)).expect("import via dispatch");
    assert_eq!(parse(&out).unwrap()[0]["Title"], "x");
    // Bad JSON and wrong password both propagate as None (Java null).
    assert_eq!(export_for_jni(Some(PW.into()), Some("not json".into())), None);
    let bytes = export_kdbx(PW, "[]").unwrap();
    assert_eq!(import_for_jni(Some("wrong".into()), Some(bytes)), None);
}
