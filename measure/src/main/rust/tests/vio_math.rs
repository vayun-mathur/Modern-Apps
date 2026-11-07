//! Host-side integration tests for the `measure_vio` pure math pipeline.
//!
//! The per-module unit tests live inline next to the code; these tests pin the
//! public API end to end on the host. Everything here avoids the Android-gated
//! JNI session map in `lib.rs`: a known 3D point is projected into synthetic
//! views and must triangulate back within a tight epsilon, epipolar residuals
//! must vanish for exact correspondences, and the `VioSession` query helpers
//! are checked for centring, clipping and initialising-state gating.

use measure_vio::align::{align_visual_inertial, refine_gravity};
use measure_vio::epipolar::{essential_eight_point, recover_pose, sampson_distance, NPt};
use measure_vio::plane::fit_plane_ransac;
use measure_vio::preintegrate::{preintegrate, ImuSample, GRAVITY};
use measure_vio::triangulate::{triangulate, CameraPose};
use measure_vio::vio::{Intrinsics, Quality, VioSession};
use vision_core::camera::rodrigues_to_mat;
use vision_core::imgbuf::Gray;
use vision_core::linalg::Vector3;

fn intr() -> Intrinsics {
    Intrinsics { fx: 500.0, fy: 500.0, cx: 320.0, cy: 240.0 }
}

fn project(pose: &CameraPose, p: Vector3<f64>) -> NPt {
    let c = pose.r * p + pose.t;
    (c[0] / c[2], c[1] / c[2])
}

/// Twenty non-coplanar bearings from a sideways step plus a small yaw, with
/// the true translation returned for direction checks.
fn grid_scene() -> (Vec<NPt>, Vec<NPt>, Vector3<f64>) {
    let a = CameraPose::identity();
    let t_true = Vector3::new(-0.25, 0.03, 0.01);
    let b = CameraPose { r: rodrigues_to_mat(Vector3::new(0.0, 0.06, 0.0)), t: t_true };
    let mut aa = Vec::new();
    let mut bb = Vec::new();
    for ix in 0..5 {
        for iy in 0..4 {
            // NOT an affine function of (ix, iy): an earlier version's
            // `3.0 + (ix + iy) * 0.25` put all twenty points on one plane,
            // where the eight-point problem is degenerate (any of the
            // plane's homography-compatible E matrices fits, and Sampson
            // residuals against the true motion are not small).
            let depth = 3.0 + ((ix * 3 + iy * 7) % 4) as f64 * 0.45 + (ix * iy) as f64 * 0.05;
            let p = Vector3::new(ix as f64 * 0.5 - 1.0, iy as f64 * 0.5 - 0.75, depth);
            aa.push(project(&a, p));
            bb.push(project(&b, p));
        }
    }
    (aa, bb, t_true)
}

#[test]
fn triangulate_known_point_with_yawed_second_view() {
    let a = CameraPose::identity();
    let b = CameraPose {
        r: rodrigues_to_mat(Vector3::new(0.0, 0.2, 0.0)),
        t: Vector3::new(-0.3, 0.05, 0.0),
    };
    let truth = Vector3::new(0.4, -0.2, 5.0);
    let views = vec![(a.clone(), project(&a, truth)), (b.clone(), project(&b, truth))];
    let got = triangulate(&views).expect("well-posed pair must triangulate");
    assert!((got - truth).norm() < 1e-6, "error was {}", (got - truth).norm());
}

#[test]
fn epipolar_residuals_are_tiny_for_exact_correspondences() {
    let (a, b, _) = grid_scene();
    let e = essential_eight_point(&a, &b).expect("twenty points must yield a matrix");
    for i in 0..a.len() {
        assert!(sampson_distance(&e, a[i], b[i]) < 1e-10, "pair {i} must agree with E");
    }
}

#[test]
fn epipolar_pose_recovery_finds_the_translation_direction() {
    let (a, b, t_true) = grid_scene();
    let (pose, inliers) = recover_pose(&a, &b, 200, 1e-6).expect("pose must recover");
    assert!(inliers.len() >= 18, "inliers: {}", inliers.len());
    let est = pose.t / pose.t.norm();
    let want = t_true / t_true.norm();
    assert!(est.dot(&want).abs() > 0.98, "recovered direction must match the step");
}

#[test]
fn plane_fit_recovers_a_noisy_floor() {
    let mut seed = 4242u64;
    let mut next = || {
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
        ((seed >> 33) as f64 / (1u64 << 31) as f64) - 0.5
    };
    let pts: Vec<Vector3<f64>> = (0..80)
        .map(|_| Vector3::new(next() * 4.0, next() * 4.0, -1.5 + next() * 0.004))
        .collect();
    let plane = fit_plane_ransac(&pts, 200, 0.02, None, 0.3).expect("floor must fit");
    assert!(plane.normal[2].abs() > 0.99, "normal: {:?}", plane.normal);
    assert!(plane.distance(&Vector3::new(0.0, 0.0, -1.5)).abs() < 0.02);
    let up = Vector3::new(0.0, 0.0, 1.0);
    assert!(plane.tilt_from(&up) < 0.05);
    let q = plane.project(&Vector3::new(1.0, 2.0, 5.0));
    assert!(plane.distance(&q).abs() < 1e-9);
}

fn imu_series(n: usize, accel: Vector3<f64>) -> Vec<ImuSample> {
    (0..n)
        .map(|i| ImuSample {
            t_ns: i as i64 * 2_500_000,
            gyro: Vector3::zeros(),
            accel,
        })
        .collect()
}

#[test]
fn preintegration_matches_constant_acceleration_kinematics() {
    let samples = imu_series(401, Vector3::new(2.0, 0.0, 0.0));
    let pre = preintegrate(&samples, Vector3::zeros(), Vector3::zeros());
    assert!((pre.dt - 1.0).abs() < 1e-6, "dt: {}", pre.dt);
    assert!((pre.delta_v[0] - 2.0).abs() < 1e-6);
    assert!((pre.delta_p[0] - 1.0).abs() < 1e-3);
}

#[test]
fn preintegration_identity_and_bias_cases() {
    let empty = preintegrate(&[], Vector3::zeros(), Vector3::zeros());
    assert_eq!(empty.sample_count, 0);
    assert!(empty.dt.abs() < 1e-12);
    let bias = Vector3::new(0.5, -0.2, 0.1);
    let samples = imu_series(401, bias);
    let cancelled = preintegrate(&samples, Vector3::zeros(), bias);
    assert!(cancelled.delta_v.norm() < 1e-9);
}

#[test]
fn align_helpers_reject_degenerate_input_and_normalise_gravity() {
    assert!(align_visual_inertial(&[], &[]).is_none());
    let g = refine_gravity(Vector3::new(1.0, 2.0, -9.0));
    assert!((g.norm() - GRAVITY).abs() < 1e-9);
    assert!(g[2] < 0.0);
}

#[test]
fn project_to_screen_centers_clips_and_rejects_behind() {
    let s = VioSession::new(intr());
    let centre = s.project_to_screen(&[Vector3::new(0.0, 0.0, 2.0)], 640.0, 480.0);
    assert_eq!(centre.len(), 1);
    assert!((centre[0].0 - 0.5).abs() < 1e-6);
    assert!((centre[0].1 - 0.5).abs() < 1e-6);
    assert!(centre[0].2);
    let behind = s.project_to_screen(&[Vector3::new(0.0, 0.0, -1.0)], 640.0, 480.0);
    assert!(!behind[0].2);
    let outside = s.project_to_screen(&[Vector3::new(10.0, 0.0, 1.0)], 640.0, 480.0);
    assert!(!outside[0].2);
}

#[test]
fn blank_frames_keep_a_new_session_initialising_and_unmeasurable() {
    let mut s = VioSession::new(intr());
    let gray = Gray::new(320, 240);
    assert_eq!(s.push_frame(&gray, 0), Quality::Initialising);
    assert_eq!(s.push_frame(&gray, 33_000_000), Quality::Initialising);
    assert_eq!(s.landmark_count(), 0);
    assert!(!s.has_plane());
    assert!(s.ray_to_world(160.0, 120.0).is_none());
}

