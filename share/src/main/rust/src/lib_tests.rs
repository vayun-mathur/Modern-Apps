//! Tests for the pure helpers in `lib.rs`: the file-list JSON codec
//! (PROTOCOL_CONTRACT.md §6) and the global session registry.

use super::{file_list_json, json_escape, parse_file_list_json, sessions, with_session};
use crate::payload::FileMeta;
use crate::session::{Role, Session};

fn meta(name: &str, size: u64, mime: &str) -> FileMeta {
    FileMeta { name: name.to_string(), size_bytes: size, mime_type: mime.to_string(), payload_id: 0 }
}

#[test]
fn json_escape_quotes_backslashes_only() {
    assert_eq!(json_escape("plain.txt"), "plain.txt");
    assert_eq!(json_escape("a\"b"), "a\\\"b");
    assert_eq!(json_escape("a\\b"), "a\\\\b");
    assert_eq!(json_escape("\\\""), "\\\\\\\"");
}

#[test]
fn file_list_json_writes_the_contract_shape() {
    assert_eq!(file_list_json(&[]), "[]");
    assert_eq!(
        file_list_json(&[meta("a.txt", 12, "text/plain")]),
        "[{\"name\":\"a.txt\",\"sizeBytes\":12,\"mimeType\":\"text/plain\"}]"
    );
    assert_eq!(
        file_list_json(&[meta("a.txt", 1, "text/plain"), meta("b.bin", 2, "")]),
        "[{\"name\":\"a.txt\",\"sizeBytes\":1,\"mimeType\":\"text/plain\"},{\"name\":\"b.bin\",\"sizeBytes\":2,\"mimeType\":\"\"}]"
    );
}

#[test]
fn parse_file_list_json_reads_the_contract_shape() {
    assert_eq!(parse_file_list_json("[]"), Some(Vec::new()));
    assert_eq!(parse_file_list_json("  [ ] "), Some(Vec::new()));
    let parsed = parse_file_list_json(
        "[{\"name\":\"a.txt\",\"sizeBytes\":12,\"mimeType\":\"text/plain\"}]",
    )
    .expect("parses");
    assert_eq!(parsed, vec![meta("a.txt", 12, "text/plain")]);
    // A missing mimeType defaults to empty; order of keys is irrelevant
    // to the field extraction.
    let parsed = parse_file_list_json("[{\"sizeBytes\":7,\"name\":\"b.bin\"}]").expect("parses");
    assert_eq!(parsed, vec![meta("b.bin", 7, "")]);
}

#[test]
fn parse_file_list_json_rejects_malformed_input() {
    assert_eq!(parse_file_list_json("not json"), None);
    assert_eq!(parse_file_list_json("[{\"sizeBytes\":1}]"), None, "no name");
    assert_eq!(parse_file_list_json("[{\"name\":\"a\"}]"), None, "no size");
    assert_eq!(parse_file_list_json(""), None);
}

#[test]
fn the_file_list_codec_round_trips_awkward_names() {
    // Names with quotes and backslashes are legal file names; the
    // encoder escapes them, so the parser must unescape them or the
    // receiver announces a different name than the sender sent.
    let files = vec![
        meta("my \"quoted\" file.txt", 3, "text/plain"),
        meta("back\\slash.bin", 4, "application/octet-stream"),
        meta("plain.dat", 5, ""),
        meta("a},b.txt", 6, "text/plain"),
    ];
    let json = file_list_json(&files);
    assert_eq!(parse_file_list_json(&json), Some(files));
}

#[test]
fn with_session_applies_to_a_live_handle_and_falls_back_for_a_dead_one() {
    const HANDLE: i64 = 0x5eed_0001;
    let expected = Session::new(Role::Responder, "Registry".to_string(), Vec::new(), String::new()).query_state();
    sessions()
        .lock()
        .unwrap()
        .insert(HANDLE, Session::new(Role::Responder, "Registry".to_string(), Vec::new(), String::new()));
    assert_eq!(with_session(HANDLE, -9, |s| s.query_state()), expected);
    assert_eq!(with_session(HANDLE + 1, -9, |s| s.query_state()), -9);
    // The closure really receives the stored session, mutable.
    with_session(HANDLE, (), |s| {
        let _ = s.outbound_drain();
    });
    sessions().lock().unwrap().remove(&HANDLE);
    assert_eq!(with_session(HANDLE, -9, |s| s.query_state()), -9);
}
