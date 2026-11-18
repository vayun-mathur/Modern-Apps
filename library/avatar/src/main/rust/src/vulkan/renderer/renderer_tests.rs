//! Host failure-path tests for the Vulkan renderer constructor.
use super::*;

#[test]
fn renderer_new_fails_cleanly_without_a_window_on_host() {
    let r = unsafe { YuuRenderer::new(std::ptr::null_mut(), 640, 480, 0, 0) };
    assert!(r.is_err(), "host without Vulkan/window must fail cleanly");
    // Negative / zero window sizes are clamped, still fail cleanly (no panic).
    let r = unsafe { YuuRenderer::new(std::ptr::null_mut(), -5, 0, 0, 0) };
    assert!(r.is_err());
    let r = unsafe { YuuRenderer::new(std::ptr::null_mut(), 1280, 720, 800, 600) };
    assert!(r.is_err());
}
