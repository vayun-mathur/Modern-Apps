//! Tests for the pipeline-cache location: the knob wins, and without
//! it a non-Android host has no code-cache directory to point at.

use super::path;
use std::path::PathBuf;

#[test]
fn the_cache_dir_knob_names_the_cache_file() {
    std::env::set_var("MODELRUNNER_CACHE_DIR", "/tmp/modelrunner-test-cache");
    let got = path();
    std::env::remove_var("MODELRUNNER_CACHE_DIR");
    assert_eq!(got, Some(PathBuf::from("/tmp/modelrunner-test-cache/modelrunner_pipelines.bin")));
}

#[test]
fn without_the_knob_a_host_has_no_cache_path() {
    std::env::remove_var("MODELRUNNER_CACHE_DIR");
    assert_eq!(path(), None);
}
