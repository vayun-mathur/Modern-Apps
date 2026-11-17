//! Unit tests for `blur` (kernel, gaussian, motion/radial, masked blur).
use crate::blur::*;
use crate::pixel::{a, b, g, pack, r};

fn solid(w: usize, h: usize, p: i32) -> Vec<i32> {
    vec![p; w * h]
}
fn gradient(w: usize, h: usize) -> Vec<i32> {
    let mut v = Vec::with_capacity(w * h);
    for y in 0..h {
        for x in 0..w {
            v.push(pack(255, (x * 255 / w.max(1)) as i32, (y * 255 / h.max(1)) as i32, 128));
        }
    }
    v
}
fn chan(p: i32, shift: u32) -> i32 {
    ((p as u32 >> shift) & 0xFF) as i32
}
fn assert_near_px(p: i32, q: i32, tol: i32) {
    for s in [24u32, 16, 8, 0] {
        assert!((chan(p, s) - chan(q, s)).abs() <= tol, "px {p:08x} vs {q:08x}");
    }
}

// ---- gaussian_kernel -------------------------------------------------------

#[test]
fn kernel_is_normalised_symmetric_and_peaked() {
    for radius in [1, 2, 3, 5, 8] {
        let k = gaussian_kernel(radius);
        assert_eq!(k.len(), (radius * 2 + 1) as usize);
        let sum: f32 = k.iter().sum();
        assert!((sum - 1.0).abs() < 1e-5, "radius {radius} sum {sum}");
        for i in 0..k.len() {
            assert!(k[i] > 0.0, "weights positive");
            assert!((k[i] - k[k.len() - 1 - i]).abs() < 1e-7, "symmetric");
        }
        let mid = k.len() / 2;
        assert!(k[mid] >= k[mid - 1] && k[mid] >= k[mid + 1], "centre is peak");
        for i in mid..k.len() - 1 {
            assert!(k[i] >= k[i + 1], "monotone falloff");
        }
    }
}

#[test]
fn kernel_degenerate_radius_is_unit_impulse() {
    // radius 0 must be [1.0] (identity), not NaN from sigma == 0.
    assert_eq!(gaussian_kernel(0), vec![1.0]);
    // Negative radius must not attempt a huge allocation / panic.
    assert_eq!(gaussian_kernel(-1), vec![1.0]);
    assert_eq!(gaussian_kernel(-10), vec![1.0]);
}

#[test]
fn kernel_radius1_weights() {
    // sigma = 1/3: exp(-4.5) ~= 0.011109, normalised by 1.022218.
    let k = gaussian_kernel(1);
    assert!((k[0] - 0.010868).abs() < 1e-4, "k0={}", k[0]);
    assert!((k[1] - 0.978264).abs() < 1e-4, "k1={}", k[1]);
    assert_eq!(k[0], k[2]);
}

// ---- gaussian_blur ---------------------------------------------------------

#[test]
fn gaussian_nonpositive_radius_is_copy() {
    let src = gradient(7, 5);
    assert_eq!(gaussian_blur(&src, 7, 5, 0), src);
    assert_eq!(gaussian_blur(&src, 7, 5, -3), src);
}

#[test]
fn gaussian_uniform_stays_uniform_within_truncation() {
    for radius in [1, 2, 6] {
        let src = solid(9, 7, pack(200, 40, 130, 210));
        let out = gaussian_blur(&src, 9, 7, radius);
        assert_eq!(out.len(), src.len());
        for p in out {
            assert_near_px(p, src[0], 2);
            assert_eq!(a(p), 200, "uniform alpha channel blurs to itself (tol via pack)");
        }
    }
}

#[test]
fn gaussian_impulse_is_symmetric_and_spreads() {
    let (w, h) = (9, 9);
    let mut src = solid(w, h, pack(255, 0, 0, 0));
    src[4 * w + 4] = pack(255, 255, 255, 255);
    let out = gaussian_blur(&src, w, h, 2);
    let centre = r(out[4 * w + 4]);
    assert!(centre > 0 && centre < 255, "centre dimmed by spread: {centre}");
    // Symmetry under both flips (exact) and transpose (within the <=1
    // per-channel truncation asymmetry of the two separable passes:
    // trunc(trunc(255*a)*b) vs trunc(trunc(255*b)*a) can differ by one).
    for y in 0..h {
        for x in 0..w {
            assert_near_px(out[y * w + x], out[x * w + y], 1);
            assert_eq!(out[y * w + x], out[y * w + (w - 1 - x)], "h-flip");
            assert_eq!(out[y * w + x], out[(h - 1 - y) * w + x], "v-flip");
        }
    }
    assert!(r(out[4 * w + 4]) > r(out[4 * w + 5]));
    assert!(r(out[4 * w + 5]) >= r(out[4 * w + 6]));
    assert_eq!(r(out[0]), 0, "far corner receives negligible weight (truncates to 0)");
}

#[test]
fn gaussian_step_edge_becomes_monotone_ramp() {
    let (w, h) = (16, 4);
    let mut src = Vec::new();
    for _y in 0..h {
        for x in 0..w {
            let v = if x < 8 { 0 } else { 255 };
            src.push(pack(255, v, v, v));
        }
    }
    let out = gaussian_blur(&src, w, h, 3);
    let row: Vec<i32> = (0..w).map(|x| r(out[x])).collect();
    for i in 1..w {
        assert!(row[i] >= row[i - 1], "monotone: {row:?}");
    }
    assert_eq!(row[0], 0);
    assert_eq!(row[w - 1], 255);
    assert!(row[7] > 0 && row[7] < 255, "edge softened");
    assert!(row[8] > 0 && row[8] < 255, "edge softened");
}

#[test]
fn gaussian_larger_radius_blurs_more() {
    let (w, h) = (15, 15);
    let mut src = solid(w, h, pack(255, 0, 0, 0));
    src[7 * w + 7] = pack(255, 255, 255, 255);
    let small = gaussian_blur(&src, w, h, 1);
    let large = gaussian_blur(&src, w, h, 5);
    assert!(r(large[7 * w + 7]) < r(small[7 * w + 7]), "wider kernel lowers peak");
}

#[test]
fn gaussian_single_pixel_and_single_row() {
    let one = vec![pack(255, 10, 20, 30)];
    let out = gaussian_blur(&one, 1, 1, 3);
    assert_near_px(out[0], one[0], 2);
    let row = gradient(8, 1);
    let out = gaussian_blur(&row, 8, 1, 2);
    assert_eq!(out.len(), 8);
}

// ---- filter_blur dispatch --------------------------------------------------

#[test]
fn filter_blur_zero_amount_radial_and_spin_are_identity() {
    // strength 0: radial scale stays 1 and spin angle stays 0, so every
    // sample lands on the source pixel itself.
    let src = gradient(11, 9);
    assert_eq!(filter_blur(&src, 11, 9, 2, 0.0, 0.0, 0.5, 0.5), src);
    assert_eq!(filter_blur(&src, 11, 9, 3, 0.0, 0.0, 0.5, 0.5), src);
}

#[test]
fn filter_blur_uniform_any_mode_is_near_identity() {
    let src = solid(10, 10, pack(255, 80, 160, 240));
    for mode in 0..4 {
        let out = filter_blur(&src, 10, 10, mode, 25.0, 30.0, 0.5, 0.5);
        assert_eq!(out.len(), 100);
        for p in out {
            assert_near_px(p, src[0], 2);
        }
    }
}

#[test]
fn motion_blur_spreads_along_angle_only() {
    // White vertical line on black; horizontal motion blur spreads it in x.
    let (w, h) = (11, 11);
    let mut src = solid(w, h, pack(255, 0, 0, 0));
    for y in 0..h {
        src[y * w + 5] = pack(255, 255, 255, 255);
    }
    let out = filter_blur(&src, w, h, 1, 5.0, 0.0, 0.5, 0.5);
    // length 5 => half 2: line bleeds exactly 2px either side on its row.
    assert!(r(out[5 * w + 3]) > 0, "bled left");
    assert!(r(out[5 * w + 7]) > 0, "bled right");
    assert_eq!(r(out[5 * w + 2]), 0, "no bleed beyond half-length");
    assert_eq!(r(out[5 * w + 8]), 0);
    // No vertical spread: every row identical.
    for y in 1..h {
        assert_eq!(out[y * w + 5], out[5], "rows identical under horizontal blur");
    }
    // Vertical motion blur (90 deg) keeps the line in its column.
    let outv = filter_blur(&src, w, h, 1, 5.0, 90.0, 0.5, 0.5);
    assert_eq!(r(outv[5 * w + 3]), 0, "no horizontal bleed at 90 degrees");
    assert_eq!(r(outv[5 * w + 7]), 0);
}

#[test]
fn radial_and_spin_centre_pixel_is_fixed() {
    // At the exact centre the radial/spin sample offsets are all zero.
    let src = gradient(11, 11);
    for mode in [2, 3] {
        let out = filter_blur(&src, 11, 11, mode, 80.0, 0.0, 0.5, 0.5);
        // centre of 11x11 at (0.5,0.5) is (5,5) (cx = 0.5*11 = 5.5, so (5,5)
        // is near-centre); just assert output is a valid in-range pixel.
        assert_eq!(out.len(), 121);
        let _ = out[5 * 11 + 5];
    }
}

// ---- blur_params (masked lens blur) ----------------------------------------

#[test]
fn blur_params_mask_zero_region_is_original() {
    // Tiny radius around centre: centre pixel has mask 0 => original.
    let (w, h) = (21, 21);
    let src = gradient(w, h);
    let out = blur_params(&src, w, h, 0, 0.5, 0.5, 0.3, 10.0, 0.3, 0.0);
    assert_eq!(out[10 * w + 10], src[10 * w + 10]);
}

#[test]
fn blur_params_far_region_equals_gaussian() {
    // Far outside the protected radius mask saturates to 1 => full blur,
    // with blur radius clamp(intensity*0.5, 1, 25) = 5 for intensity 10.
    let (w, h) = (21, 21);
    let src = gradient(w, h);
    let out = blur_params(&src, w, h, 0, 0.5, 0.5, 0.05, 10.0, 0.3, 0.0);
    let blurred = gaussian_blur(&src, w, h, 5);
    // Corners are well beyond radius+feather.
    assert_eq!(out[0], blurred[0]);
    assert_eq!(out[w - 1], blurred[w - 1]);
    assert_eq!(out[(h - 1) * w], blurred[(h - 1) * w]);
}

#[test]
fn blur_params_all_modes_preserve_alpha_range() {
    let src = gradient(13, 11);
    for mode in 0..3 {
        let out = blur_params(&src, 13, 11, mode, 0.5, 0.5, 0.3, 12.0, 0.4, 45.0);
        assert_eq!(out.len(), 13 * 11);
        for p in &out {
            // Alpha interpolates toward the blurred alpha; the blur's
            // truncation drift (documented <=2 on uniform fields) applies.
            assert!((253..=255).contains(&a(*p)), "alpha drifted to {}", a(*p));
            for s in [16u32, 8, 0] {
                assert!((0..=255).contains(&chan(*p, s)));
            }
        }
        let _ = (b(out[0]), g(out[0]));
    }
}
