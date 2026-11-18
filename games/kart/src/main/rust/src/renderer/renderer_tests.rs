//! Host failure-path tests for the backend dispatch seam.
use super::*;

#[test]
fn select_fails_cleanly_without_a_window_on_host() {
    let r = unsafe { Renderer::select(std::ptr::null_mut(), 640, 480, 640, 480) };
    assert!(r.is_err(), "both backends must fail cleanly on host");
    if let Err(e) = r {
        assert!(e.contains("no renderer"), "combined error: {e}");
    }
}

#[test]
fn try_vulkan_fails_cleanly_without_a_window_on_host() {
    let r = unsafe { crate::engine::try_vulkan(std::ptr::null_mut(), 640, 480, 640, 480) };
    assert!(r.is_err(), "Vulkan must fail cleanly on host");
}
