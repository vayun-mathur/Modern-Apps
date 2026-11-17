//! Tests for bundle.rs private helpers: K inverse, camera perturbation,
//! geodesic increments, and ray residuals; plus bundle_adjust no-op paths.
fn mdiff(a: &Matrix3<f64>, b: &Matrix3<f64>) -> f64 {
    let mut s = 0.0f64;
    for r in 0..3 { for c in 0..3 { let d = a[(r, c)] - b[(r, c)]; s += d * d; } }
    s.sqrt()
}


use super::*;
use crate::linalg::Matrix3;

fn approx(a: f64, b: f64) -> bool {
    (a - b).abs() <= 1e-9
}

fn cam(focal: f64) -> CameraParams {
    CameraParams { focal, aspect: 1.0, ppx: 320.0, ppy: 240.0, r: Matrix3::identity() }
}

#[test]
fn k_inv_undoes_camera_k() {
    let c = cam(640.0);
    let prod = k_inv(c.focal, c.ppx, c.ppy) * c.k();
    for r in 0..3 {
        for cc in 0..3 {
            let want = if r == cc { 1.0 } else { 0.0 };
            assert!(approx(prod[(r, cc)], want), "[{r}{cc}] {}", prod[(r, cc)]);
        }
    }
}

#[test]
fn perturb_focal_only_changes_focal() {
    let base = cam(500.0);
    let p = perturb(&base, 0, 2.5);
    assert!(approx(p.focal, 502.5));
    assert!(approx(p.ppx, base.ppx) && approx(p.ppy, base.ppy));
    assert!(approx(mdiff(&(p.r), &(base.r)), 0.0), "rotation untouched");
}

#[test]
fn perturb_rotation_axes_rotate_and_keep_focal() {
    let base = cam(500.0);
    for axis in 1..=3 {
        let p = perturb(&base, axis, 0.01);
        assert!(approx(p.focal, 500.0));
        assert!(approx(p.r.determinant(), 1.0), "still a rotation");
        assert!(mdiff(&(p.r), &(base.r)) > 1e-6, "axis {axis} must move R");
    }
    // Zero delta leaves the camera unchanged on every parameter.
    for j in 0..4 {
        let p = perturb(&base, j, 0.0);
        assert!(approx(p.focal, base.focal));
        assert!(approx(mdiff(&(p.r), &(base.r)), 0.0));
    }
}

#[test]
fn apply_increment_zero_vector_is_identity() {
    let cams = vec![cam(400.0), cam(600.0)];
    let x = DVector::zeros(8);
    let out = apply_increment(&cams, &x);
    for (a, b) in out.iter().zip(cams.iter()) {
        assert!(approx(a.focal, b.focal));
        assert!(approx(mdiff(&(a.r), &(b.r)), 0.0));
        assert!(approx(a.aspect, 1.0), "increment normalises aspect to 1");
    }
}

#[test]
fn apply_increment_focal_slot_moves_only_that_camera() {
    let cams = vec![cam(400.0), cam(600.0)];
    let mut x = DVector::zeros(8);
    x[4] = 25.0; // camera 1 focal slot
    let out = apply_increment(&cams, &x);
    assert!(approx(out[0].focal, 400.0));
    assert!(approx(out[1].focal, 625.0));
}

#[test]
fn edge_res_is_zero_for_identical_cameras_and_points() {
    let c = cam(800.0);
    let corr = vec![((100.0f32, 120.0f32), (100.0f32, 120.0f32)), ((0.0, 0.0), (0.0, 0.0))];
    let r = edge_res(&c, &c, &corr);
    assert_eq!(r.len(), 6);
    assert!(r.iter().all(|v| approx(*v, 0.0)), "same rays: {r:?}");
}

#[test]
fn edge_res_grows_when_second_point_moves() {
    let c = cam(800.0);
    let same = edge_res(&c, &c, &[((100.0f32, 100.0f32), (100.0, 100.0))]);
    let moved = edge_res(&c, &c, &[((100.0f32, 100.0f32), (140.0, 100.0))]);
    let n_same: f64 = same.iter().map(|v| v * v).sum();
    let n_moved: f64 = moved.iter().map(|v| v * v).sum();
    assert!(n_moved > n_same + 1.0, "moved point must raise residual ({n_moved} vs {n_same})");
    assert_eq!(total_cost(&[c.clone(), c.clone()], &[Edge { i: 0, j: 1, corr: vec![((1.0f32, 1.0f32), (1.0, 1.0))] }]), 0.0);
}

#[test]
fn bundle_adjust_noops_without_enough_cameras_or_edges() {
    // Fewer than two cameras.
    let mut one = vec![cam(500.0)];
    bundle_adjust(&mut one, &[]);
    assert!(approx(one[0].focal, 500.0));
    // Two cameras but no matches at all.
    let mut two = vec![cam(500.0), cam(500.0)];
    bundle_adjust(&mut two, &[]);
    assert!(two.iter().all(|c| approx(c.focal, 500.0)));
    // Matches below the bundle confidence threshold are ignored.
    let weak = MatchInfo {
        src: 0,
        dst: 1,
        h: Matrix3::identity(),
        inliers: vec![((0.0f32, 0.0f32), (5.0, 5.0))],
        confidence: BUNDLE_CONF_THRESH, // not > thresh
        num_inliers: 1,
    };
    bundle_adjust(&mut two, &[weak]);
    assert!(two.iter().all(|c| approx(c.focal, 500.0)), "sub-threshold edge must not move cameras");
}

#[test]
fn bundle_adjust_keeps_consistent_cameras_nearly_fixed() {
    // Perfectly consistent correspondences (identical cameras/points) have
    // zero residual; LM must not wander away from the optimum.
    let corr: Vec<(Pt, Pt)> = (0..6)
        .map(|i| {
            let p = (50.0 + 30.0 * i as f32, 60.0 + 17.0 * i as f32);
            (p, p)
        })
        .collect();
    let m = MatchInfo {
        src: 0,
        dst: 1,
        h: Matrix3::identity(),
        inliers: corr,
        confidence: 0.9,
        num_inliers: 6,
    };
    let mut cams = vec![cam(700.0), cam(700.0)];
    bundle_adjust(&mut cams, &[m]);
    for c in &cams {
        assert!((c.focal - 700.0).abs() < 1.0, "focal drifted to {}", c.focal);
        assert!(mdiff(&(c.r), &(Matrix3::identity())) < 1e-3, "rotation drifted");
    }
}
