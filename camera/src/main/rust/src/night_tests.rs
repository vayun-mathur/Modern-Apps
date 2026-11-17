//! Tests for night.rs private helpers (homography application / rescaling)
//! and the public burst-merge entry point on synthetic frames.

use super::*;
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
