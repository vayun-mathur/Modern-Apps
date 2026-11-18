//! Tests for the pure helpers in `http_range`.
use super::http_range::{fetch_range, file_size, parse_total_size, slice_range_body};

#[test]
fn total_from_content_range() {
    assert_eq!(parse_total_size(Some("bytes 0-0/155373816"), None).unwrap(), 155373816);
    assert_eq!(parse_total_size(Some("bytes 0-0/42"), Some("999")).unwrap(), 42);
}
#[test]
fn total_from_content_length_fallback() {
    assert_eq!(parse_total_size(None, Some("1234")).unwrap(), 1234);
    assert_eq!(parse_total_size(Some("bytes 0-0/*"), Some("77")).unwrap(), 77);
    assert_eq!(parse_total_size(Some("garbage"), Some("88")).unwrap(), 88);
    assert_eq!(parse_total_size(Some("bytes */500"), None).unwrap(), 500);
}
#[test]
fn total_missing_or_zero_is_err() {
    assert!(parse_total_size(None, None).is_err());
    assert!(parse_total_size(Some(""), Some("")).is_err());
    assert!(parse_total_size(Some("bytes 0-0/abc"), Some("xyz")).is_err());
    assert!(parse_total_size(None, Some("0")).is_err());
    // Zero from Content-Range does not fall back (original filter semantics).
    assert!(parse_total_size(Some("bytes 0-0/0"), Some("55")).is_err());
    assert!(parse_total_size(Some("bytes 0-0/12x"), None).is_err());
}
#[test]
fn slice_206_returns_body_verbatim() {
    assert_eq!(slice_range_body(206, vec![1, 2, 3], 0, 3).unwrap(), vec![1, 2, 3]);
    assert_eq!(slice_range_body(206, vec![9], 100, 50).unwrap(), vec![9]);
    assert_eq!(slice_range_body(206, vec![], 0, 10).unwrap(), Vec::<u8>::new());
}
#[test]
fn slice_200_extracts_window() {
    let body: Vec<u8> = (0..10).collect();
    assert_eq!(slice_range_body(200, body.clone(), 0, 4).unwrap(), vec![0, 1, 2, 3]);
    assert_eq!(slice_range_body(200, body.clone(), 3, 4).unwrap(), vec![3, 4, 5, 6]);
    // Whole file shorter than offset+len: still sliced from offset, not verbatim.
    assert_eq!(slice_range_body(200, body.clone(), 8, 100).unwrap(), vec![8, 9]);
    assert_eq!(slice_range_body(200, body.clone(), 0, 100).unwrap(), body);
    assert_eq!(slice_range_body(200, body.clone(), 2, 3).unwrap(), vec![2, 3, 4]);
}
#[test]
fn slice_200_past_end_is_empty() {
    assert_eq!(slice_range_body(200, vec![1, 2], 2, 5).unwrap(), Vec::<u8>::new());
    assert_eq!(slice_range_body(200, vec![1, 2], 100, 5).unwrap(), Vec::<u8>::new());
    assert_eq!(slice_range_body(200, vec![], 0, 5).unwrap(), Vec::<u8>::new());
}
#[test]
fn slice_unexpected_status_is_err() {
    for s in [0u16, 201, 301, 404, 416, 500] {
        let e = slice_range_body(s, vec![1], 0, 1).unwrap_err();
        assert!(e.contains(&s.to_string()), "{e}");
    }
}
#[test]
fn fetch_range_zero_len_never_touches_bridge() {
    assert_eq!(fetch_range("https://example.invalid/x", 0, 0).unwrap(), Vec::<u8>::new());
    assert_eq!(fetch_range("https://example.invalid/x", 99, 0).unwrap(), Vec::<u8>::new());
}
#[test]
fn bridge_calls_fail_cleanly_without_jvm() {
    // On host there is no initialised JNI bridge; both entry points must
    // surface an Err rather than panic.
    assert!(file_size("https://example.invalid/x.om").is_err());
    assert!(fetch_range("https://example.invalid/x.om", 0, 10).is_err());
}
