//! Tests for the surface pre-rotation mapping.
use super::*;
use ash::vk;

#[test]
fn pre_rotation_maps_each_transform() {
    use vk::SurfaceTransformFlagsKHR as T;
    assert_eq!(pre_rotation_for(T::IDENTITY), (false, 0.0));
    assert_eq!(
        pre_rotation_for(T::ROTATE_90),
        (true, std::f32::consts::FRAC_PI_2)
    );
    assert_eq!(pre_rotation_for(T::ROTATE_180), (false, std::f32::consts::PI));
    let (swap, rot) = pre_rotation_for(T::ROTATE_270);
    assert!(swap);
    assert!((rot - std::f32::consts::FRAC_PI_2 * 3.0).abs() < 1e-7);
    // Anything else (mirror etc.) is treated as no rotation.
    assert_eq!(pre_rotation_for(T::HORIZONTAL_MIRROR), (false, 0.0));
    assert_eq!(pre_rotation_for(vk::SurfaceTransformFlagsKHR::empty()), (false, 0.0));
}

fn fmt(f: vk::Format) -> vk::SurfaceFormatKHR {
    vk::SurfaceFormatKHR { format: f, color_space: vk::ColorSpaceKHR::SRGB_NONLINEAR }
}

fn caps(min: u32, max: u32, cur: vk::Extent2D) -> vk::SurfaceCapabilitiesKHR {
    vk::SurfaceCapabilitiesKHR {
        min_image_count: min,
        max_image_count: max,
        current_extent: cur,
        ..Default::default()
    }
}

#[test]
fn choose_format_prefers_unorm_and_avoids_srgb_first() {
    assert!(choose_format(&[]).is_none());
    let srgb_first = [fmt(vk::Format::B8G8R8A8_SRGB), fmt(vk::Format::R8G8B8A8_UNORM)];
    assert!(choose_format(&srgb_first).expect("choice").format == vk::Format::R8G8B8A8_UNORM);
    let with_b8 = [fmt(vk::Format::R8G8B8A8_UNORM), fmt(vk::Format::B8G8R8A8_UNORM)];
    assert!(choose_format(&with_b8).expect("choice").format == vk::Format::B8G8R8A8_UNORM, "B8 is preferred over R8");
    // Only SRGB offered: keep it (only option, no invented format).
    let only_srgb = [fmt(vk::Format::B8G8R8A8_SRGB)];
    assert!(choose_format(&only_srgb).expect("choice").format == vk::Format::B8G8R8A8_SRGB);
    let plain = [fmt(vk::Format::R8G8B8A8_UNORM)];
    assert!(choose_format(&plain).expect("choice").format == vk::Format::R8G8B8A8_UNORM);
}

#[test]
fn choose_present_mode_extent_and_image_count() {
    use vk::PresentModeKHR as M;
    assert!(choose_present_mode(&[M::FIFO, M::MAILBOX]) == M::MAILBOX);
    assert!(choose_present_mode(&[M::FIFO]) == M::FIFO);
    assert!(choose_present_mode(&[]) == M::FIFO);
    let fixed = caps(2, 0, vk::Extent2D { width: 800, height: 600 });
    assert!(choose_extent(&fixed, 1, 1) == vk::Extent2D { width: 800, height: 600 });
    let any = caps(2, 0, vk::Extent2D { width: u32::MAX, height: u32::MAX });
    assert!(choose_extent(&any, 640, 480) == vk::Extent2D { width: 640, height: 480 });
    assert!(choose_extent(&any, 0, 0) == vk::Extent2D { width: 1, height: 1 });
    assert_eq!(choose_image_count(&caps(2, 0, vk::Extent2D::default())), 3);
    assert_eq!(choose_image_count(&caps(1, 2, vk::Extent2D::default())), 2);
    assert_eq!(choose_image_count(&caps(2, 4, vk::Extent2D::default())), 3);
}
