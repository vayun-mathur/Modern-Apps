//! Host tests for GLES renderer helpers and its failure path.
use super::*;

#[test]
fn flatten_and_normalize_edge_cases() {
    let zero = [[0.0; 4]; 4];
    assert_eq!(flatten(&zero), [0.0; 16]);
    let m = glam::Mat4::from_translation(glam::Vec3::new(1.0, 2.0, 3.0)).to_cols_array_2d();
    let f = flatten(&m);
    // Translation lives in the fourth column (indices 12..14).
    assert_eq!([f[12], f[13], f[14]], [1.0, 2.0, 3.0]);
    assert_eq!(normalize([0.0, -5.0, 0.0]), [0.0, -1.0, 0.0]);
    let n = normalize([1e-12, 0.0, 0.0]);
    assert!(n.iter().all(|v| v.is_finite()));
}

#[test]
fn gles_renderer_new_fails_cleanly_without_a_window_on_host() {
    let r = unsafe { GlesRenderer::new(std::ptr::null_mut(), 640, 480) };
    assert!(r.is_err(), "host without EGL/window must fail cleanly");
    let r = unsafe { GlesRenderer::new(std::ptr::null_mut(), 0, 0) };
    assert!(r.is_err());
}
