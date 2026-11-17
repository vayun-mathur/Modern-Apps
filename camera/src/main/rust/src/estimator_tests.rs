//! Tests for estimator.rs: camera estimation with no / weak / chained
//! matches, checking focal fallback and rotation validity.
fn mdiff(a: &Matrix3<f64>, b: &Matrix3<f64>) -> f64 {
    let mut s = 0.0f64;
    for r in 0..3 { for c in 0..3 { let d = a[(r, c)] - b[(r, c)]; s += d * d; } }
    s.sqrt()
}


use super::*;
use crate::linalg::Matrix3;

fn approx(a: f64, b: f64, tol: f64) -> bool {
    (a - b).abs() <= tol
}

fn assert_rotation(r: &Matrix3<f64>) {
    assert!(r.determinant().abs() > 0.5, "near-singular rotation, det {}", r.determinant());
    let p = *r * r.transpose();
    for i in 0..3 {
        for j in 0..3 {
            let want = if i == j { 1.0 } else { 0.0 };
            assert!(approx(p[(i, j)], want, 1e-6), "orthonormal [{i}{j}] {}", p[(i, j)]);
        }
    }
}

fn mi(src: usize, dst: usize, h: Matrix3<f64>, inl: usize, conf: f64) -> MatchInfo {
    MatchInfo { src, dst, h, inliers: Vec::new(), confidence: conf, num_inliers: inl }
}

#[test]
fn estimate_cameras_single_image_gets_identity() {
    let cams = estimate_cameras(1, 640, 480, &[]);
    assert_eq!(cams.len(), 1);
    assert!(approx(mdiff(&(cams[0].r), &(Matrix3::identity())), 0.0, 1e-12));
    assert!(approx(cams[0].focal, 1120.0, 1e-9), "fallback focal w+h");
    assert!(approx(cams[0].ppx, 320.0, 1e-12) && approx(cams[0].ppy, 240.0, 1e-12));
}

#[test]
fn estimate_cameras_disconnected_images_all_identity() {
    let cams = estimate_cameras(3, 640, 480, &[]);
    assert_eq!(cams.len(), 3);
    for c in &cams {
        assert!(approx(mdiff(&(c.r), &(Matrix3::identity())), 0.0, 1e-9));
        assert!(approx(c.focal, 1120.0, 1e-9));
        assert_rotation(&c.r);
    }
}

#[test]
fn estimate_cameras_identity_homography_chain_keeps_identity() {
    // Identity homographies carry no focal, so the fallback focal applies
    // and CalcRotation yields identity relative rotations.
    let matches = vec![mi(0, 1, Matrix3::identity(), 50, 0.9), mi(1, 2, Matrix3::identity(), 40, 0.9)];
    let cams = estimate_cameras(3, 640, 480, &matches);
    assert_eq!(cams.len(), 3);
    for c in &cams {
        assert!(approx(mdiff(&(c.r), &(Matrix3::identity())), 0.0, 1e-6), "identity chain must stay identity");
        assert_rotation(&c.r);
    }
}

#[test]
fn estimate_cameras_root_is_middle_image_with_identity() {
    // Root is n/2; with no matches every camera (root included) is identity,
    // and the root index for 4 cameras is 2.
    let cams = estimate_cameras(4, 800, 600, &[]);
    assert_eq!(cams.len(), 4);
    assert!(approx(mdiff(&(cams[2].r), &(Matrix3::identity())), 0.0, 1e-12));
    assert!(approx(cams[0].focal, 1400.0, 1e-9), "w+h fallback");
    assert!(approx(cams[0].ppx, 400.0, 1e-12) && approx(cams[0].ppy, 300.0, 1e-12));
}

#[test]
fn estimate_cameras_singular_homography_does_not_panic() {
    let zero = Matrix3::new(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    let matches = vec![mi(0, 1, zero, 10, 0.5)];
    let cams = estimate_cameras(2, 320, 240, &matches);
    assert_eq!(cams.len(), 2);
    for c in &cams {
        assert!(c.focal.is_finite() && c.focal > 1.0);
        assert_rotation(&c.r);
    }
}
