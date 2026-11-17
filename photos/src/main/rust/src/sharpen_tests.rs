//! Unit tests for `sharpen::unsharp`.
use crate::pixel::{a, b, g, pack, r};
use crate::sharpen::unsharp;

fn checker(w: usize, h: usize) -> Vec<i32> {
    let mut v = Vec::with_capacity(w * h);
    for y in 0..h {
        for x in 0..w {
            let c = ((x * 37 + y * 91) % 256) as i32;
            v.push(pack(255, c, 255 - c, (c * 2) % 256));
        }
    }
    v
}
fn step_image(w: usize, h: usize, lo: i32, hi: i32) -> Vec<i32> {
    let mut v = Vec::with_capacity(w * h);
    for _y in 0..h {
        for x in 0..w {
            let c = if x < w / 2 { lo } else { hi };
            v.push(pack(255, c, c, c));
        }
    }
    v
}

#[test]
fn amount_zero_is_identity_for_any_radius_and_threshold() {
    let src = checker(13, 11);
    for (radius, thr) in [(0.0f32, 0i32), (0.4, 0), (2.0, 3), (10.0, 255), (1.5, -1)] {
        assert_eq!(unsharp(&src, 13, 11, 0.0, radius, thr), src, "r={radius} thr={thr}");
    }
}

#[test]
fn threshold_at_or_above_max_diff_is_identity() {
    // Channel diffs are in -255..=255, so |diff| > 255 is never true and
    // sharpen_channel always returns the original channel.
    let src = checker(15, 13);
    assert_eq!(unsharp(&src, 15, 13, 100.0, 2.0, 255), src);
    assert_eq!(unsharp(&src, 15, 13, 500.0, 4.0, 1000), src);
}

#[test]
fn unsharp_enhances_step_edge_contrast() {
    // Dark side of an edge darkens, bright side brightens.
    let (w, h) = (16, 8);
    let src = step_image(w, h, 100, 200);
    let out = unsharp(&src, w, h, 100.0, 2.0, 0);
    let y = 4;
    assert!(r(out[y * w + 7]) < 100, "dark side: {}", r(out[y * w + 7]));
    assert!(r(out[y * w + 8]) > 200, "bright side: {}", r(out[y * w + 8]));
    // Far from the edge the (locally flat) image is essentially unchanged.
    assert!((r(out[y * w + 1]) - 100).abs() <= 3);
    assert!((r(out[y * w + 14]) - 200).abs() <= 3);
}

#[test]
fn unsharp_output_is_clamped_to_byte_range() {
    let (w, h) = (16, 8);
    let src = step_image(w, h, 0, 255);
    let out = unsharp(&src, w, h, 1000.0, 3.0, 0);
    for p in &out {
        for f in [r, g, b] {
            assert!((0..=255).contains(&f(*p)));
        }
    }
    // Extreme overshoot clamps instead of wrapping.
    assert_eq!(r(out[4 * w + 7]), 0);
    assert_eq!(r(out[4 * w + 8]), 255);
}

#[test]
fn unsharp_never_sharpens_alpha() {
    // Alpha varies spatially (so its blur differs) but must pass through.
    let (w, h) = (12, 10);
    let mut src = Vec::with_capacity(w * h);
    for y in 0..h {
        for x in 0..w {
            src.push(pack(((x * 20 + y * 7) % 256) as i32, 120, 60, 200));
        }
    }
    let out = unsharp(&src, w, h, 120.0, 3.0, 0);
    for i in 0..w * h {
        assert_eq!(a(out[i]), a(src[i]), "alpha preserved at {i}");
    }
}

#[test]
fn unsharp_radius_rounds_to_nearest_int_with_min_one() {
    let src = checker(14, 12);
    // 0.1 and 1.4 both resolve to radius 1; 0.5 rounds half-up (Kotlin
    // roundToInt: floor(x+0.5)) to 1 as well.
    let base = unsharp(&src, 14, 12, 60.0, 1.0, 0);
    assert_eq!(unsharp(&src, 14, 12, 60.0, 0.1, 0), base);
    assert_eq!(unsharp(&src, 14, 12, 60.0, 1.4, 0), base);
    assert_eq!(unsharp(&src, 14, 12, 60.0, 0.5, 0), base);
    // A materially larger radius gives a different blur, hence different output.
    let wide = unsharp(&src, 14, 12, 60.0, 4.6, 0);
    assert_ne!(wide, base);
}

#[test]
fn unsharp_flat_image_stays_near_flat() {
    let src = vec![pack(255, 90, 140, 190); 10 * 10];
    let out = unsharp(&src, 10, 10, 80.0, 2.0, 0);
    for p in &out {
        assert!((r(*p) - 90).abs() <= 3);
        assert!((g(*p) - 140).abs() <= 3);
        assert!((b(*p) - 190).abs() <= 3);
        assert_eq!(a(*p), 255);
    }
}

#[test]
fn unsharp_preserves_dimensions_nonsquare() {
    let src = checker(3, 17);
    let out = unsharp(&src, 3, 17, 50.0, 2.0, 1);
    assert_eq!(out.len(), 3 * 17);
}
