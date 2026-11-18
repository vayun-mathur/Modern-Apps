//! Tests for the pure helpers behind the JNI glue in `lib.rs`.
//! The `extern "system"` entry points themselves need a JVM `Env` and are
//! pure JNI glue — they forward to the engine functions tested elsewhere.
use super::*;

#[test]
fn poses_json_lists_every_preset_id_once() {
    let json = poses_json();
    let ids: Vec<String> = serde_json::from_str(&json).expect("poses json is a string array");
    let expected: Vec<String> = crate::pose::preset_poses()
        .iter()
        .map(|p| p.id.clone())
        .collect();
    assert_eq!(ids, expected);
    assert!(ids.iter().any(|i| i == "idle"));
    assert!(ids.iter().any(|i| i == "wave"));
    let mut dedup = ids.clone();
    dedup.sort();
    dedup.dedup();
    assert_eq!(dedup.len(), ids.len(), "preset ids are unique");
}

#[test]
fn host_read_fd_and_close_fd_are_safe_noops() {
    // Non-Android stub: never fabricates bytes, close is a no-op for any fd.
    assert!(read_fd(-1, 0, 10).is_none());
    assert!(read_fd(0, 0, 10).is_none());
    close_fd(-1);
    close_fd(0);
}
