//! Additional tests for `epipolar`: normalization, constraint enforcement,
//! Sampson scoring, RANSAC support rules, candidate poses, triangulation and
//! cheirality. The happy-path pose recovery lives in the inline test module;
//! these pin the edge cases and invariants those paths rely on.

use super::*;
use vision_core::camera::rodrigues_to_mat;

/// Project a world point into a camera at (R, t) as a calibrated bearing.
fn project(r: &Matrix3<f64>, t: &Vector3<f64>, p: Vector3<f64>) -> NPt {
    let c = r * p + *t;
    (c[0] / c[2], c[1] / c[2])
}

/// Deterministic pseudo-random scene in front of the first camera.
fn scene(n: usize, seed0: u64) -> Vec<Vector3<f64>> {
    let mut pts = Vec::new();
    let mut seed = seed0;
    let mut next = || {
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
        ((seed >> 33) as f64 / (1u64 << 31) as f64) - 0.5
    };
    for _ in 0..n {
        pts.push(Vector3::new(next() * 2.0, next() * 2.0, 3.0 + next() * 2.0));
    }
    pts
}

fn bearings(
    pts: &[Vector3<f64>],
    r: &Matrix3<f64>,
    t: &Vector3<f64>,
) -> (Vec<NPt>, Vec<NPt>) {
    let a = pts
        .iter()
        .map(|&p| project(&Matrix3::identity(), &Vector3::zeros(), p))
        .collect();
    let b = pts.iter().map(|&p| project(r, t, p)).collect();
    (a, b)
}

/// Fixed-seed Gaussian-ish noise (sum of uniforms) added to `b`.
fn add_noise(b: &[NPt], seed0: u64, amp: f64) -> Vec<NPt> {
    let mut seed = seed0;
    let mut u = || {
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
        ((seed >> 33) as f64 / (1u64 << 31) as f64) - 0.5
    };
    b.iter()
        .map(|&(x, y)| (x + (u() + u()) * amp, y + (u() + u()) * amp))
        .collect()
}

fn t_dir_dot(t1: &Vector3<f64>, t2: &Vector3<f64>) -> f64 {
    ((t1[0] / t1.norm()) * (t2[0] / t2.norm())
        + (t1[1] / t1.norm()) * (t2[1] / t2.norm())
        + (t1[2] / t1.norm()) * (t2[2] / t2.norm()))
    .abs()
}

fn max_r_err(r: &Matrix3<f64>, want: &Matrix3<f64>) -> f64 {
    (0..3)
        .map(|i| (0..3).map(move |j| (r[(i, j)] - want[(i, j)]).abs()))
        .flatten()
        .fold(0.0f64, f64::max)
}

// --- Hartley normalization -------------------------------------------------

#[test]
fn normalize_centers_points_and_scales_mean_distance_to_sqrt2() {
    let pts = vec![(1.0, 2.0), (3.0, 2.0), (1.0, 4.0), (3.0, 4.0), (-2.0, 0.5)];
    let (_, n) = normalize(&pts);
    let mx = n.iter().map(|p| p.0).sum::<f64>() / n.len() as f64;
    let my = n.iter().map(|p| p.1).sum::<f64>() / n.len() as f64;
    assert!(mx.abs() < 1e-12 && my.abs() < 1e-12, "centroid ({mx},{my})");
    let md = n.iter().map(|p| (p.0 * p.0 + p.1 * p.1).sqrt()).sum::<f64>() / n.len() as f64;
    assert!((md - (2.0f64).sqrt()).abs() < 1e-12, "mean dist {md}");
}

#[test]
fn normalize_transform_maps_original_points_to_normalized_ones() {
    let pts = vec![(0.3, -0.7), (1.2, 0.4), (-0.5, 0.9), (0.8, -1.1), (0.0, 0.2)];
    let (t, n) = normalize(&pts);
    for (i, &(x, y)) in pts.iter().enumerate() {
        let v = t * Vector3::new(x, y, 1.0);
        assert!((v[0] - n[i].0).abs() < 1e-12, "x mismatch at {i}");
        assert!((v[1] - n[i].1).abs() < 1e-12, "y mismatch at {i}");
        assert!((v[2] - 1.0).abs() < 1e-12);
    }
}

#[test]
fn normalize_a_single_point_lands_on_the_origin() {
    let (t, n) = normalize(&[(5.0, -3.0)]);
    assert_eq!(n, vec![(0.0, 0.0)]);
    // With zero spread the scale falls back to 1 rather than dividing by zero.
    assert!((t[(0, 0)] - 1.0).abs() < 1e-12 && (t[(1, 1)] - 1.0).abs() < 1e-12);
}

#[test]
fn normalize_identical_points_collapse_to_the_origin() {
    let (_, n) = normalize(&[(0.4, 0.4); 6]);
    assert!(n.iter().all(|&p| p.0.abs() < 1e-12 && p.1.abs() < 1e-12));
}

#[test]
fn normalize_empty_input_is_empty() {
    let (_, n) = normalize(&[]);
    assert!(n.is_empty());
}

// --- Eight-point algorithm ---------------------------------------------------

#[test]
fn eight_point_rejects_too_few_and_mismatched_inputs() {
    let pts = scene(10, 7);
    let (a, b) = bearings(&pts, &Matrix3::identity(), &Vector3::new(-0.3, 0.0, 0.0));
    assert!(essential_eight_point(&a[..7], &b[..7]).is_none());
    assert!(essential_eight_point(&a[..9], &b[..8]).is_none());
    assert!(essential_eight_point(&[], &[]).is_none());
}

#[test]
fn eight_point_with_exactly_eight_points_satisfies_the_epipolar_constraint() {
    let pts = scene(8, 11);
    let r = rodrigues_to_mat(Vector3::new(0.01, 0.04, -0.02));
    let t = Vector3::new(-0.3, 0.05, 0.0);
    let (a, b) = bearings(&pts, &r, &t);
    let e = essential_eight_point(&a, &b).expect("E from 8 points");
    for i in 0..8 {
        let x1 = Vector3::new(a[i].0, a[i].1, 1.0);
        let x2 = Vector3::new(b[i].0, b[i].1, 1.0);
        assert!(x2.dot(&(e * x1)).abs() < 1e-9, "residual at {i}");
    }
}

#[test]
fn eight_point_output_has_essential_singular_values() {
    let pts = scene(50, 21);
    let (a, b) = bearings(&pts, &Matrix3::identity(), &Vector3::new(-0.3, 0.02, 0.0));
    let e = essential_eight_point(&a, &b).expect("E");
    let s = e.svd_sorted().singular_values;
    assert!((s[0] - 1.0).abs() < 1e-9, "s0 {s:?}");
    assert!((s[1] - 1.0).abs() < 1e-9, "s1 {s:?}");
    assert!(s[2].abs() < 1e-9, "s2 {s:?}");
}

// --- Constraint enforcement --------------------------------------------------

#[test]
fn enforce_constraints_forces_singular_values_to_one_one_zero() {
    let m = Matrix3::new(0.3, -0.5, 0.8, 1.2, 0.1, -0.4, 0.6, 0.9, 0.2);
    let p = enforce_essential_constraints(&m);
    let s = p.svd_sorted().singular_values;
    assert!((s[0] - 1.0).abs() < 1e-9, "s {s:?}");
    assert!((s[1] - 1.0).abs() < 1e-9, "s {s:?}");
    assert!(s[2].abs() < 1e-9, "s {s:?}");
}

#[test]
fn enforce_constraints_is_idempotent_and_ignores_positive_scale() {
    let pts = scene(40, 31);
    let (a, b) = bearings(&pts, &Matrix3::identity(), &Vector3::new(-0.25, 0.0, 0.0));
    let e = essential_eight_point(&a, &b).expect("E");
    let once = enforce_essential_constraints(&e);
    let twice = enforce_essential_constraints(&once);
    let scaled = enforce_essential_constraints(&Matrix3::new(
        7.0 * e[(0, 0)], 7.0 * e[(0, 1)], 7.0 * e[(0, 2)],
        7.0 * e[(1, 0)], 7.0 * e[(1, 1)], 7.0 * e[(1, 2)],
        7.0 * e[(2, 0)], 7.0 * e[(2, 1)], 7.0 * e[(2, 2)],
    ));
    for i in 0..3 {
        for j in 0..3 {
            assert!((once[(i, j)] - twice[(i, j)]).abs() < 1e-12, "not idempotent");
            assert!((once[(i, j)] - scaled[(i, j)]).abs() < 1e-9, "scale changed result");
        }
    }
    // An essential matrix is a fixed point of the projection.
    assert!(max_r_err(&once, &e) < 1e-9);
}

// --- Sampson distance --------------------------------------------------------

#[test]
fn sampson_distance_grows_for_a_perturbed_correspondence() {
    let pts = scene(40, 41);
    let (a, b) = bearings(&pts, &Matrix3::identity(), &Vector3::new(-0.3, 0.0, 0.0));
    let e = essential_eight_point(&a, &b).expect("E");
    let exact = sampson_distance(&e, a[0], b[0]);
    // This motion (sideways translation, no rotation) has horizontal epipolar
    // lines: sliding b along x stays on the line and must not raise the error,
    // while any vertical step off the line must.
    let along = sampson_distance(&e, a[0], (b[0].0 + 0.05, b[0].1));
    let shifted = sampson_distance(&e, a[0], (b[0].0, b[0].1 + 0.05));
    let gross = sampson_distance(&e, a[0], (b[0].0 + 0.5, b[0].1 - 0.4));
    assert!(exact < 1e-12, "exact {exact}");
    assert!(along < 1e-12, "along-line shift should stay ~zero, got {along}");
    assert!(shifted > 1e-4, "off-line shift {shifted}");
    assert!(gross > 0.01, "gross {gross}");
}

#[test]
fn sampson_distance_is_symmetric_under_transpose_and_swap() {
    let pts = scene(40, 43);
    let (a, b) = bearings(&pts, &Matrix3::identity(), &Vector3::new(-0.3, 0.0, 0.0));
    let e = essential_eight_point(&a, &b).expect("E");
    let d1 = sampson_distance(&e, a[3], b[3]);
    let d2 = sampson_distance(&e.transpose(), b[3], a[3]);
    assert!((d1 - d2).abs() < 1e-15, "{d1} vs {d2}");
}

#[test]
fn sampson_distance_of_a_zero_matrix_is_max() {
    // No epipolar lines exist, so every correspondence is infinitely wrong.
    assert_eq!(sampson_distance(&Matrix3::zeros(), (0.1, 0.2), (0.3, 0.4)), f64::MAX);
}

// --- RANSAC -------------------------------------------------------------------

#[test]
fn ransac_rejects_too_few_mismatched_and_zero_iterations() {
    let pts = scene(20, 51);
    let (a, b) = bearings(&pts, &Matrix3::identity(), &Vector3::new(-0.3, 0.0, 0.0));
    assert!(find_essential_ransac(&a[..7], &b[..7], 100, 1e-4).is_none());
    assert!(find_essential_ransac(&a[..9], &b[..8], 100, 1e-4).is_none());
    assert!(find_essential_ransac(&a, &b, 0, 1e-4).is_none());
}

#[test]
fn ransac_is_deterministic_for_fixed_inputs() {
    let pts = scene(40, 53);
    let (a, b) = bearings(&pts, &Matrix3::identity(), &Vector3::new(-0.3, 0.02, 0.0));
    let r1 = find_essential_ransac(&a, &b, 150, 1e-5).expect("ransac 1");
    let r2 = find_essential_ransac(&a, &b, 150, 1e-5).expect("ransac 2");
    assert_eq!(r1.1, r2.1, "same seed must reproduce the same inliers");
    for i in 0..3 {
        for j in 0..3 {
            assert!((r1.0[(i, j)] - r2.0[(i, j)]).abs() < 1e-12);
        }
    }
}

#[test]
fn ransac_reports_failure_when_no_model_has_eight_inliers() {
    // Regression: with bearing noise and a threshold far below the noise, the
    // best sample fits only a handful of points. Returning that unsupported
    // model (an earlier version returned Some with as few as 1 inlier) lets
    // callers decompose a pose from almost no evidence.
    let pts = scene(40, 4242);
    let r = rodrigues_to_mat(Vector3::new(0.01, 0.04, 0.0));
    let t = Vector3::new(-0.3, 0.02, 0.0);
    let (a, b) = bearings(&pts, &r, &t);
    let noisy = add_noise(&b, 777, 1e-3);
    assert!(find_essential_ransac(&a, &noisy, 200, 3e-6).is_none());
    assert!(recover_pose(&a, &noisy, 200, 3e-6).is_none());
}

#[test]
fn ransac_excludes_gross_outliers_from_the_inlier_set() {
    let pts = scene(40, 61);
    let (mut a, mut b) = bearings(&pts, &Matrix3::identity(), &Vector3::new(-0.3, 0.02, 0.0));
    for i in 0..15 {
        a.push((0.9 - i as f64 * 0.05, -0.8 + i as f64 * 0.07));
        b.push((-0.9 + i as f64 * 0.06, 0.85 - i as f64 * 0.05));
    }
    let (_, inliers) = find_essential_ransac(&a, &b, 300, 1e-4).expect("ransac");
    assert!(inliers.len() >= 38, "kept {} of 40 good points", inliers.len());
    assert!(inliers.iter().all(|&i| i < 40), "an outlier was kept: {inliers:?}");
}

// --- Candidate poses & triangulation ------------------------------------------

#[test]
fn candidate_poses_are_four_orthonormal_poses_with_paired_translations() {
    let m = Matrix3::new(0.3, -0.5, 0.8, 1.2, 0.1, -0.4, 0.6, 0.9, 0.2);
    let e = enforce_essential_constraints(&m);
    let cands = candidate_poses(&e);
    assert_eq!(cands.len(), 4);
    for c in &cands {
        assert!((c.r.determinant() - 1.0).abs() < 1e-9, "not a rotation");
        let rr = c.r * c.r.transpose();
        for i in 0..3 {
            assert!((rr[(i, i)] - 1.0).abs() < 1e-9, "not orthonormal");
        }
        assert!((c.t.norm() - 1.0).abs() < 1e-9, "t not unit");
    }
    // Poses pair up as (R1, +/-t) and (R2, +/-t) with R1 != R2.
    assert!(max_r_err(&cands[0].r, &cands[1].r) < 1e-12);
    assert!(max_r_err(&cands[2].r, &cands[3].r) < 1e-12);
    assert!((cands[0].t[0] + cands[1].t[0]).abs() < 1e-12);
    assert!(max_r_err(&cands[0].r, &cands[2].r) > 0.1, "twisted pair must differ");
}

#[test]
fn triangulate_recovers_a_known_point() {
    let r = rodrigues_to_mat(Vector3::new(0.02, -0.03, 0.01));
    let t = Vector3::new(-0.4, 0.1, 0.05);
    let pose = RelPose { r, t };
    let p = Vector3::new(0.5, -0.3, 4.0);
    let a = project(&Matrix3::identity(), &Vector3::zeros(), p);
    let b = project(&r, &t, p);
    let got = triangulate_pair(&pose, a, b).expect("triangulation");
    for i in 0..3 {
        assert!((got[i] - p[i]).abs() < 1e-9, "coord {i}: {} vs {}", got[i], p[i]);
    }
    // Depth is also positive in the second camera.
    let p2 = r * got + t;
    assert!(p2[2] > 0.0);
}

#[test]
fn triangulate_parallel_rays_returns_none() {
    // Same bearing, camera shifted sideways: the rays never meet.
    let pose = RelPose { r: Matrix3::identity(), t: Vector3::new(1.0, 0.0, 0.0) };
    assert!(triangulate_pair(&pose, (0.0, 0.0), (0.0, 0.0)).is_none());
}

#[test]
fn triangulate_a_point_behind_the_first_camera_keeps_its_negative_depth() {
    // Triangulation itself does not judge cheirality; decompose_essential does.
    let r = rodrigues_to_mat(Vector3::new(0.02, -0.03, 0.01));
    let t = Vector3::new(-0.4, 0.1, 0.05);
    let pose = RelPose { r, t };
    let p = Vector3::new(0.2, 0.1, -3.0);
    let a = project(&Matrix3::identity(), &Vector3::zeros(), p);
    let b = project(&r, &t, p);
    let got = triangulate_pair(&pose, a, b).expect("triangulation");
    assert!(got[2] < 0.0, "expected negative depth, got {}", got[2]);
    assert!((got[2] - p[2]).abs() < 1e-9);
}

// --- Decomposition & full recovery --------------------------------------------

#[test]
fn decompose_selects_the_pose_matching_the_true_motion() {
    let pts = scene(60, 71);
    let r_true = rodrigues_to_mat(Vector3::new(-0.02, 0.06, 0.01));
    let t_true = Vector3::new(0.28, -0.04, 0.03);
    let (a, b) = bearings(&pts, &r_true, &t_true);
    let e = essential_eight_point(&a, &b).expect("E");
    let pose = decompose_essential(&e, &a, &b).expect("pose");
    assert!(max_r_err(&pose.r, &r_true) < 1e-6);
    assert!(t_dir_dot(&pose.t, &t_true) > 0.999);
}

#[test]
fn decompose_returns_none_for_empty_input_and_zero_matrix() {
    assert!(decompose_essential(&Matrix3::identity(), &[], &[]).is_none());
    assert!(decompose_essential(&Matrix3::zeros(), &[(0.1, 0.2)], &[(0.1, 0.2)]).is_none());
}

#[test]
fn recover_pose_tolerates_small_bearing_noise() {
    let pts = scene(50, 83);
    let r_true = rodrigues_to_mat(Vector3::new(0.01, 0.04, 0.0));
    let t_true = Vector3::new(-0.3, 0.02, 0.0);
    let (a, b) = bearings(&pts, &r_true, &t_true);
    let noisy = add_noise(&b, 99, 5e-4);
    let (pose, inliers) = recover_pose(&a, &noisy, 300, 2e-3).expect("pose");
    assert!(inliers.len() >= 40, "inliers {}", inliers.len());
    assert!(t_dir_dot(&pose.t, &t_true) > 0.98);
    assert!(max_r_err(&pose.r, &r_true) < 0.02);
}

#[test]
fn recover_pose_recovers_forward_motion() {
    let pts = scene(60, 97);
    let r_true = Matrix3::identity();
    let t_true = Vector3::new(0.02, -0.01, -0.35);
    let (a, b) = bearings(&pts, &r_true, &t_true);
    let (pose, inliers) = recover_pose(&a, &b, 300, 1e-6).expect("pose");
    assert!(inliers.len() > 50);
    assert!(t_dir_dot(&pose.t, &t_true) > 0.99);
}

#[test]
fn recover_pose_rejects_too_few_and_mismatched_inputs() {
    let pts = scene(10, 101);
    let (a, b) = bearings(&pts, &Matrix3::identity(), &Vector3::new(-0.3, 0.0, 0.0));
    assert!(recover_pose(&a[..5], &b[..5], 100, 1e-4).is_none());
    assert!(recover_pose(&a[..9], &b[..8], 100, 1e-4).is_none());
}
