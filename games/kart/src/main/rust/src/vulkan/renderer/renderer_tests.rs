//! Tests for the Vulkan renderer's pure sun-direction helper and caps.
use super::*;

#[test]
fn normalize_guards_zero_and_scales_to_unit() {
    assert_eq!(normalize([0.0; 3]), [0.0; 3]);
    let n = normalize([0.35, -1.0, 0.45]);
    assert!((n.iter().map(|v| v * v).sum::<f32>() - 1.0).abs() < 1e-5, "{n:?}");
    assert!(n[1] < 0.0);
    let tiny = normalize([1e-9, 0.0, 0.0]);
    assert!(tiny.iter().all(|v| v.is_finite()), "{tiny:?}");
    assert_eq!(TRACK_VERT_CAP, 65_536);
    assert_eq!(DYNAMIC_VERT_CAP, 8_192);
}
