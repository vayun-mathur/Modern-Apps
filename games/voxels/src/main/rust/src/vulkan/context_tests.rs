//! Non-Android window stubs and extension list (CPU only).

use super::*;

#[test]
fn native_window_stubs_are_inert_off_android() {
    // On this (non-Android) test host the stubs must never dereference their arguments.
    let w = unsafe { ANativeWindow_fromSurface(std::ptr::null_mut(), std::ptr::null_mut()) };
    assert!(w.is_null());
    unsafe { ANativeWindow_acquire(w) };
    unsafe { ANativeWindow_release(w) };
    unsafe { ANativeWindow_release(std::ptr::null_mut()) };
}

#[test]
fn required_extensions_are_the_surface_pair() {
    let exts = VulkanContext::required_instance_extensions();
    assert_eq!(exts.len(), 2);
    let names: Vec<String> = exts.iter().map(|e| e.to_string_lossy().into_owned()).collect();
    assert!(names.iter().any(|n| n.contains("surface")), "{names:?}");
    assert!(names.iter().any(|n| n.contains("android")), "{names:?}");
}
