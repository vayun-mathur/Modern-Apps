//! Tests for wave.rs: wave correction must keep rotations valid and leave
//! already-level camera sets (nearly) unchanged.
fn mdiff(a: &Matrix3<f64>, b: &Matrix3<f64>) -> f64 {
    let mut s = 0.0f64;
    for r in 0..3 { for c in 0..3 { let d = a[(r, c)] - b[(r, c)]; s += d * d; } }
    s.sqrt()
}


use super::*;
use crate::camera::rodrigues_to_mat;
use crate::linalg::Vector3;

fn approx(a: f64, b: f64, tol: f64) -> bool {
    (a - b).abs() <= tol
}

fn cam(r: Matrix3<f64>) -> CameraParams {
    CameraParams { focal: 800.0, aspect: 1.0, ppx: 320.0, ppy: 240.0, r }
}

fn assert_rotation(r: &Matrix3<f64>) {
    assert!(approx(r.determinant(), 1.0, 1e-9), "det {}", r.determinant());
    let p = *r * r.transpose();
    for i in 0..3 {
        for j in 0..3 {
            let want = if i == j { 1.0 } else { 0.0 };
            assert!(approx(p[(i, j)], want, 1e-9), "orthonormal [{i}{j}]");
        }
    }
}

#[test]
fn wave_correct_empty_and_single_are_noops() {
    let mut none: Vec<CameraParams> = Vec::new();
    wave_correct_horizontal(&mut none);
    assert!(none.is_empty());
    let r = rodrigues_to_mat(Vector3::new(0.1, 0.2, 0.3));
    let mut one = vec![cam(r)];
    wave_correct_horizontal(&mut one);
    assert!(approx(mdiff(&(one[0].r), &(r)), 0.0, 1e-12));
}

#[test]
fn wave_correct_identity_cameras_stay_rotations() {
    let mut cams = vec![cam(Matrix3::identity()), cam(Matrix3::identity()), cam(Matrix3::identity())];
    wave_correct_horizontal(&mut cams);
    for c in &cams {
        assert_rotation(&c.r);
        assert!(approx(c.focal, 800.0, 1e-12), "focal untouched");
        assert!(approx(c.ppx, 320.0, 1e-12) && approx(c.ppy, 240.0, 1e-12));
    }
}

#[test]
fn wave_correct_applies_one_common_rotation_to_all() {
    // The correction left-multiplies every camera by the same R_wave, so
    // relative rotations between cameras are preserved exactly.
    let rs: Vec<Matrix3<f64>> = (0..4)
        .map(|i| rodrigues_to_mat(Vector3::new(0.0, 0.08 * i as f64, 0.01 * i as f64)))
        .collect();
    let rel_before: Vec<Matrix3<f64>> = rs.windows(2).map(|w| w[0].transpose() * w[1]).collect();
    let mut cams: Vec<CameraParams> = rs.iter().map(|&r| cam(r)).collect();
    wave_correct_horizontal(&mut cams);
    for c in &cams {
        assert_rotation(&c.r);
    }
    for (i, rel) in rel_before.iter().enumerate() {
        let after = cams[i].r.transpose() * cams[i + 1].r;
        assert!(approx(mdiff(&(after), &(rel)), 0.0, 1e-9), "relative rotation {i} changed");
    }
}

#[test]
fn wave_correct_is_idempotent_up_to_tolerance() {
    let mut cams: Vec<CameraParams> = (0..3)
        .map(|i| cam(rodrigues_to_mat(Vector3::new(0.02 * i as f64, 0.15 * i as f64, 0.0))))
        .collect();
    wave_correct_horizontal(&mut cams);
    let once: Vec<Matrix3<f64>> = cams.iter().map(|c| c.r).collect();
    wave_correct_horizontal(&mut cams);
    for (c, before) in cams.iter().zip(once.iter()) {
        assert!(mdiff(&(c.r), &(before)) < 1e-6, "second pass moved cameras");
        assert_rotation(&c.r);
    }
}
