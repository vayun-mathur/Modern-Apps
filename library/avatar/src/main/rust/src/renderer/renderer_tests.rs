//! Host failure-path tests for the backend dispatch seam.
use super::*;

#[test]
fn vulkan_only_and_select_fail_cleanly_without_a_window_on_host() {
    let r = unsafe { Renderer::try_vulkan_only(std::ptr::null_mut(), 640, 480, 640, 480) };
    assert!(r.is_err(), "Vulkan-only must fail cleanly on host");
    if let Err(e) = r {
        assert!(!e.is_empty(), "error must describe the failure");
    }
    let r = unsafe { Renderer::select(std::ptr::null_mut(), 640, 480, 640, 480) };
    assert!(r.is_err(), "both backends must fail cleanly on host");
    if let Err(e) = r {
        assert!(e.contains("no renderer"), "combined error: {e}");
    }
}
