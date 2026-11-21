//! Tests for night.rs private helpers (homography application / rescaling)
//! and the public burst-merge entry point on synthetic frames.

use super::*;
use crate::imgbuf::Gray;
use crate::linalg::Matrix3;

fn approx(a: f64, b: f64) -> bool {
    (a - b).abs() <= 1e-9
}

#[test]
fn apply_identity_and_translation_homographies() {
    let (x, y) = apply(&Matrix3::identity(), 3.0, -2.0);
    assert!(approx(x, 3.0) && approx(y, -2.0));
    let t = Matrix3::new(1.0, 0.0, 5.0, 0.0, 1.0, -3.0, 0.0, 0.0, 1.0);
    let (x, y) = apply(&t, 1.0, 1.0);
    assert!(approx(x, 6.0) && approx(y, -2.0));
}

#[test]
fn apply_normalises_by_z() {
    // Uniform scale of the homogeneous vector must not move the point.
    let s = Matrix3::new(2.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 2.0);
    let (x, y) = apply(&s, 4.0, 6.0);
    assert!(approx(x, 4.0) && approx(y, 6.0));
}

#[test]
fn scale_homography_unit_scale_is_identity_operation() {
    let h = Matrix3::new(1.0, 0.1, 7.0, -0.2, 1.0, -4.0, 0.001, 0.002, 1.0);
    let out = scale_homography(&h, 1.0);
    for r in 0..3 {
        for c in 0..3 {
            assert!(approx(out[(r, c)], h[(r, c)]), "[{r}{c}]");
        }
    }
}

#[test]
fn scale_homography_matches_sandwich_formula() {
    // H_full = S^-1 H S with S = diag(s,s,1), s = 0.5.
    let h = Matrix3::new(1.0, 0.1, 7.0, -0.2, 1.0, -4.0, 0.001, 0.002, 1.0);
    let out = scale_homography(&h, 0.5);
    assert!(approx(out[(0, 2)], 7.0 / 0.5));
    assert!(approx(out[(1, 2)], -4.0 / 0.5));
    assert!(approx(out[(2, 0)], 0.001 * 0.5));
    assert!(approx(out[(2, 1)], 0.002 * 0.5));
    assert!(approx(out[(0, 0)], 1.0) && approx(out[(2, 2)], 1.0));
}

#[test]
fn scale_homography_keeps_point_mapping_equivalent() {
    // A point mapped at small scale then upscaled equals mapping at full
    // scale with the rescaled homography.
    let h = Matrix3::new(1.0, 0.0, 3.0, 0.0, 1.0, -2.0, 0.0, 0.0, 1.0);
    let s = 0.25;
    let (sx, sy) = apply(&h, 8.0, 8.0);
    let (fx, fy) = apply(&scale_homography(&h, s), 8.0 / s, 8.0 / s);
    assert!(approx(fx, sx / s) && approx(fy, sy / s), "({fx},{fy}) vs ({},{})", sx / s, sy / s);
}

fn solid(w: usize, h: usize, c: [u8; 4]) -> Rgba {
    let mut img = Rgba::new(w, h);
    for y in 0..h {
        for x in 0..w {
            img.set(x, y, c);
        }
    }
    img
}

#[test]
fn align_and_merge_degenerate_inputs() {
    assert!(align_and_merge(&[]).is_none());
    let one = solid(6, 4, [12, 34, 56, 255]);
    let out = align_and_merge(&[one.clone()]).expect("single frame passes through");
    assert_eq!(out.px, one.px);
}

#[test]
fn align_and_merge_identical_frames_returns_same_image() {
    let f = solid(24, 18, [90, 80, 70, 255]);
    let out = align_and_merge(&[f.clone(), f.clone(), f.clone()]).expect("merge");
    assert_eq!((out.w, out.h), (24, 18));
    assert!(out.px.chunks_exact(4).all(|p| p == [90, 80, 70, 255]));
}

fn gray_checkerboard(w: usize, h: usize) -> Gray {
    let mut g = Gray::new(w, h);
    for y in 0..h {
        for x in 0..w {
            g.px[y * w + x] = if (x + y) % 2 == 0 { 255 } else { 0 };
        }
    }
    g
}

/// 3x3 box blur with clamped edges: a stand-in for a defocused frame.
fn blurred(g: &Gray) -> Gray {
    let mut out = Gray::new(g.w, g.h);
    for y in 0..g.h {
        for x in 0..g.w {
            let mut sum = 0u32;
            for dy in -1i32..=1 {
                for dx in -1i32..=1 {
                    let xx = (x as i32 + dx).clamp(0, g.w as i32 - 1) as usize;
                    let yy = (y as i32 + dy).clamp(0, g.h as i32 - 1) as usize;
                    sum += g.px[yy * g.w + xx] as u32;
                }
            }
            out.px[y * g.w + x] = (sum / 9) as u8;
        }
    }
    out
}

#[test]
fn sharpest_frame_is_not_the_blurred_one() {
    let sharp = gray_checkerboard(24, 18);
    let blurry = blurred(&sharp);
    assert!(laplacian_variance(&sharp) > laplacian_variance(&blurry));
    assert_eq!(laplacian_variance(&Gray::new(8, 8)), 0.0, "flat scores zero");
    assert_eq!(pick_sharpest_index(&[blurry.clone(), sharp.clone()]), 1);
    assert_eq!(pick_sharpest_index(&[sharp, blurry]), 0);
    // No sharpness signal anywhere: earliest frame wins.
    assert_eq!(pick_sharpest_index(&[Gray::new(8, 8), Gray::new(8, 8)]), 0);
}

#[test]
fn align_and_merge_does_not_pick_blurry_first_frame_as_reference() {
    let blurry = solid(24, 18, [100, 100, 100, 255]);
    let mut sharp = Rgba::new(24, 18);
    for y in 0..18 {
        for x in 0..24 {
            let v = if (x / 2 + y / 2) % 2 == 0 { 40 } else { 220 };
            sharp.set(x, y, [v, v, v, 255]);
        }
    }
    // Frame 0 is the blurry one; the sharp frame must become the reference,
    // so the output is the sharp frame, not the flat first frame.
    let out = align_and_merge(&[blurry, sharp.clone()]).expect("merge");
    assert_eq!(out.px, sharp.px);
}

#[test]
fn ghost_threshold_is_luma_adaptive_with_dark_floor() {
    assert_eq!(ghost_threshold_sum([0, 0, 0, 255]), 28, "dark floor 25% of 110");
    assert_eq!(ghost_threshold_sum([255, 255, 255, 255]), 110, "full legacy value at white");
    let dark = ghost_threshold_sum([12, 12, 12, 255]);
    let mid = ghost_threshold_sum([128, 128, 128, 255]);
    assert!(dark < mid && mid < 110, "monotonic in luma: {dark} {mid}");
}

#[test]
fn trimmed_mean_by_sample_count() {
    assert_eq!(trimmed_mean(100.0, 100.0, 100.0, 1), 100.0);
    assert_eq!(trimmed_mean(300.0, 100.0, 200.0, 2), 150.0, "two samples: mean");
    assert_eq!(trimmed_mean(390.0, 90.0, 200.0, 3), 100.0, "three samples: median");
    // Four samples 100,100,100,255: drop min+max, average the middle two.
    assert_eq!(trimmed_mean(555.0, 100.0, 255.0, 4), 100.0);
}

fn identities(n: usize) -> Vec<Option<Matrix3<f64>>> {
    vec![Some(Matrix3::identity()); n]
}

#[test]
fn merge_identical_frames_preserves_image() {
    let mut f = Rgba::new(16, 12);
    for y in 0..12 {
        for x in 0..16 {
            let v = ((x * 11 + y * 7) % 251) as u8;
            f.set(x, y, [v, v, v, 255]);
        }
    }
    let frames = vec![f.clone(), f.clone(), f.clone(), f.clone()];
    let out = merge_with_homographies(&frames, 0, &identities(4)).expect("merge");
    assert_eq!(out.px, f.px, "identical frames: trimmed mean is the frame");
}

#[test]
fn merge_rejects_single_flash_frame_via_trimming() {
    // Baseline 200 with one frame flashed to 230 in a block. The flash
    // passes deghosting (diff 90 <= adaptive threshold 92 at luma 200), so
    // only the trimmed mean can reject it: the plain mean would give 208.
    let base = solid(16, 12, [200, 200, 200, 255]);
    let mut flash = base.clone();
    for y in 4..8 {
        for x in 4..8 {
            flash.set(x, y, [230, 230, 230, 255]);
        }
    }
    let frames = vec![base.clone(), base.clone(), base.clone(), flash];
    let out = merge_with_homographies(&frames, 0, &identities(4)).expect("merge");
    assert_eq!(out.get(5, 5), [200, 200, 200, 255], "flash block trimmed away");
    assert_eq!(out.get(0, 0), [200, 200, 200, 255]);
}

#[test]
fn merge_three_frames_uses_median() {
    // 200/210/230 all pass deghosting vs the 200 reference; the median
    // (210), not the mean (213), wins with three samples.
    let frames = vec![
        solid(8, 6, [200, 200, 200, 255]),
        solid(8, 6, [210, 210, 210, 255]),
        solid(8, 6, [230, 230, 230, 255]),
    ];
    let out = merge_with_homographies(&frames, 0, &identities(3)).expect("merge");
    assert_eq!(out.get(2, 2), [210, 210, 210, 255]);
}

#[test]
fn merge_dark_region_small_change_still_merges() {
    // Dark pixels: 12 -> 20 is a summed diff of 24, below both the old
    // fixed 110 and the adaptive threshold at luma 12 (31), so the second
    // frame still contributes and the output is the two-frame mean.
    assert!(24 < ghost_threshold_sum([12, 12, 12, 255]));
    let frames = vec![solid(8, 6, [12, 12, 12, 255]), solid(8, 6, [20, 20, 20, 255])];
    let out = merge_with_homographies(&frames, 0, &identities(2)).expect("merge");
    assert_eq!(out.get(2, 2), [16, 16, 16, 255]);
}

#[test]
fn align_and_merge_output_size_follows_reference_frame() {
    let a = solid(10, 8, [10, 10, 10, 255]);
    let b = solid(10, 8, [20, 20, 20, 255]);
    let out = align_and_merge(&[a, b]).expect("merge");
    assert_eq!((out.w, out.h), (10, 8));
    // Every output pixel is an average of contributing frames, hence lies
    // between the two input brightnesses and is fully opaque.
    for p in out.px.chunks_exact(4) {
        assert_eq!(p[3], 255);
        assert!(p[0] >= 10 && p[0] <= 20, "channel {p:?} outside input range");
    }
}
