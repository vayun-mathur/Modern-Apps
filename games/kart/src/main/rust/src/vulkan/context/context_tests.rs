//! Host-side tests for Vulkan bootstrap helpers that need no device.
use super::*;

#[test]
fn required_extensions_are_surface_and_android_surface() {
    let exts = VulkanContext::required_instance_extensions();
    let names: Vec<String> = exts
        .iter()
        .map(|s| s.to_string_lossy().into_owned())
        .collect();
    assert_eq!(names.len(), 2);
    assert!(names.iter().any(|n| n.contains("VK_KHR_surface")), "{names:?}");
    assert!(names.iter().any(|n| n.contains("android_surface")), "{names:?}");
}

#[test]
fn host_window_stubs_are_null_and_noop() {
    #[cfg(not(target_os = "android"))]
    unsafe {
        assert!(ANativeWindow_fromSurface(std::ptr::null_mut(), std::ptr::null_mut()).is_null());
        ANativeWindow_acquire(std::ptr::null_mut());
        ANativeWindow_release(std::ptr::null_mut());
    }
}

#[test]
fn context_new_fails_cleanly_without_a_window_on_host() {
    let r = unsafe { VulkanContext::new(std::ptr::null_mut(), 640, 480) };
    assert!(r.is_err(), "host without a live window must fail cleanly");
}
