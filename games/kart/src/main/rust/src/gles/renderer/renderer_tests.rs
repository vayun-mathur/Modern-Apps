//! Host tests for GLES renderer helpers and its failure path.
use super::*;

#[test]
fn flatten_and_normalize_edge_cases() {
    assert_eq!(flatten(&[[0.0; 4]; 4]), [0.0; 16]);
    let m = glam::Mat4::from_translation(glam::Vec3::new(4.0, 5.0, 6.0)).to_cols_array_2d();
    let f = flatten(&m);
    assert_eq!([f[12], f[13], f[14]], [4.0, 5.0, 6.0]);
    assert_eq!(normalize([0.0, -5.0, 0.0]), [0.0, -1.0, 0.0]);
    let n = normalize([1e-12, 0.0, 0.0]);
    assert!(n.iter().all(|v| v.is_finite()));
    // Fixed lighting constants are sane.
    assert!(SKY.iter().all(|v| (0.0..=1.0).contains(v)));
    assert_eq!(TRACK_VERT_CAP, 65_536);
    assert_eq!(DYNAMIC_VERT_CAP, 8_192);
}

#[test]
fn gles_renderer_new_fails_cleanly_without_a_window_on_host() {
    let r = unsafe { GlesRenderer::new(std::ptr::null_mut(), 640, 480) };
    assert!(r.is_err(), "host without EGL/window must fail cleanly");
    let r = unsafe { GlesRenderer::new(std::ptr::null_mut(), 0, 0) };
    assert!(r.is_err());
}
