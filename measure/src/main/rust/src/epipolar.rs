//! Two-view epipolar geometry: essential matrix estimation and pose recovery.
//!
//! This is the piece the panorama stitcher deliberately does not have. Stitching
//! assumes the camera only rotates, so a homography is sufficient and translation is
//! discarded. Measuring requires exactly the translation that stitching throws away,
//! so this module recovers `(R, t)` from the essential matrix instead.
//!
//! All points here are **calibrated bearings** — pixel coordinates already multiplied
//! by `K⁻¹` and undistorted — which is why the fundamental and essential matrices
//! coincide and no intrinsics appear below.

use vision_core::geometry::Lcg;
use vision_core::linalg::{DMatrix, Matrix3, Vector3};

/// A calibrated image point on the `z = 1` plane.
pub type NPt = (f64, f64);

/// Relative pose of the second camera in the first camera's frame.
///
/// `t` is a **unit** vector: a single pair of views fixes direction but not
/// magnitude, so metric scale has to come from the IMU (see `align`).
#[derive(Clone, Debug)]
pub struct RelPose {
    pub r: Matrix3<f64>,
    pub t: Vector3<f64>,
}

/// Isotropic (Hartley) normalization, as in the homography DLT: conditions the
/// linear system so the smallest singular vector is meaningful.
fn normalize(pts: &[NPt]) -> (Matrix3<f64>, Vec<NPt>) {
    let n = pts.len().max(1) as f64;
    let (mut mx, mut my) = (0.0, 0.0);
    for &(x, y) in pts {
        mx += x;
        my += y;
    }
    mx /= n;
    my /= n;
    let mut mean_dist = 0.0;
    for &(x, y) in pts {
        let dx = x - mx;
        let dy = y - my;
        mean_dist += (dx * dx + dy * dy).sqrt();
    }
    mean_dist /= n;
    let s = if mean_dist > 1e-12 { (2.0f64).sqrt() / mean_dist } else { 1.0 };
    let t = Matrix3::new(s, 0.0, -s * mx, 0.0, s, -s * my, 0.0, 0.0, 1.0);
    let out = pts.iter().map(|&(x, y)| (s * (x - mx), s * (y - my))).collect();
    (t, out)
}

/// Essential matrix from >= 8 correspondences via the normalized eight-point
/// algorithm, with the rank-2 and equal-singular-value constraints enforced.
pub fn essential_eight_point(a: &[NPt], b: &[NPt]) -> Option<Matrix3<f64>> {
    if a.len() < 8 || a.len() != b.len() {
        return None;
    }
    let (ta, na) = normalize(a);
    let (tb, nb) = normalize(b);

    // Each correspondence contributes one row of the epipolar constraint
    // b^T E a = 0, expanded over the nine unknowns of E.
    let mut m = DMatrix::<f64>::zeros(a.len(), 9);
    for i in 0..a.len() {
        let (x1, y1) = na[i];
        let (x2, y2) = nb[i];
        m[(i, 0)] = x2 * x1;
        m[(i, 1)] = x2 * y1;
        m[(i, 2)] = x2;
        m[(i, 3)] = y2 * x1;
        m[(i, 4)] = y2 * y1;
        m[(i, 5)] = y2;
        m[(i, 6)] = x1;
        m[(i, 7)] = y1;
        m[(i, 8)] = 1.0;
    }
    let vt = m.svd(false, true).v_t?;
    let h = vt.row(8); // smallest singular vector
    let e_norm = Matrix3::new(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], h[8]);

    // Undo the normalization: E = Tb^T * E_norm * Ta
    let e = tb.transpose() * e_norm * ta;
    Some(enforce_essential_constraints(&e))
}

/// Projects an arbitrary 3x3 onto the essential manifold by forcing its singular
/// values to `(1, 1, 0)`. Without this the recovered rotation is not orthonormal.
pub fn enforce_essential_constraints(e: &Matrix3<f64>) -> Matrix3<f64> {
    let svd = e.svd_sorted();
    match (svd.u, svd.v_t) {
        (Some(u), Some(vt)) => {
            let d = Matrix3::new(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0);
            u * d * vt
        }
        _ => *e,
    }
}

/// First-order geometric (Sampson) error of a correspondence under `e`.
///
/// Preferred over the raw algebraic residual `b^T E a` for RANSAC scoring because
/// the algebraic value scales with point magnitude and would bias the inlier set.
pub fn sampson_distance(e: &Matrix3<f64>, a: NPt, b: NPt) -> f64 {
    let x1 = Vector3::new(a.0, a.1, 1.0);
    let x2 = Vector3::new(b.0, b.1, 1.0);
    let ex1 = e * x1;
    let etx2 = e.transpose() * x2;
    let num = x2.dot(&ex1);
    let den = ex1[0] * ex1[0] + ex1[1] * ex1[1] + etx2[0] * etx2[0] + etx2[1] * etx2[1];
    if den < 1e-15 {
        return f64::MAX;
    }
    num * num / den
}

/// RANSAC essential matrix. `thresh` is a Sampson distance in calibrated units,
/// so a pixel threshold should be divided by the focal length before being passed in.
///
/// Returns the refit matrix and the inlier indices.
pub fn find_essential_ransac(
    a: &[NPt],
    b: &[NPt],
    iters: usize,
    thresh: f64,
) -> Option<(Matrix3<f64>, Vec<usize>)> {
    let n = a.len();
    if n < 8 || n != b.len() {
        return None;
    }
    let thr2 = thresh * thresh;
    let mut rng = Lcg::new(0x5EED ^ n as u64);
    let mut best: Option<Matrix3<f64>> = None;
    let mut best_inliers: Vec<usize> = Vec::new();

    for _ in 0..iters {
        let mut idx = [0usize; 8];
        let mut ok = true;
        for k in 0..8 {
            idx[k] = rng.next_usize(n);
            for j in 0..k {
                if idx[j] == idx[k] {
                    ok = false;
                }
            }
        }
        if !ok {
            continue;
        }
        let sa: Vec<NPt> = idx.iter().map(|&i| a[i]).collect();
        let sb: Vec<NPt> = idx.iter().map(|&i| b[i]).collect();
        let e = match essential_eight_point(&sa, &sb) {
            Some(e) => e,
            None => continue,
        };
        let mut inliers = Vec::new();
        for i in 0..n {
            if sampson_distance(&e, a[i], b[i]) < thr2 {
                inliers.push(i);
            }
        }
        if inliers.len() > best_inliers.len() {
            best_inliers = inliers;
            best = Some(e);
        }
    }

    if best_inliers.len() < 8 {
        // A model backed by fewer points than the minimal 8-point sample is
        // not a RANSAC result (its "inliers" may be a handful of points the
        // projected sample happens to fit). Report failure instead of handing
        // callers an unsupported matrix and pose.
        return None;
    }
    let ia: Vec<NPt> = best_inliers.iter().map(|&i| a[i]).collect();
    let ib: Vec<NPt> = best_inliers.iter().map(|&i| b[i]).collect();
    let refined = essential_eight_point(&ia, &ib).or(best)?;
    Some((refined, best_inliers))
}

/// The four candidate poses encoded by an essential matrix.
fn candidate_poses(e: &Matrix3<f64>) -> Vec<RelPose> {
    let svd = e.svd_sorted();
    let (u, vt) = match (svd.u, svd.v_t) {
        (Some(u), Some(vt)) => (u, vt),
        _ => return Vec::new(),
    };
    // Keep both factors right-handed, otherwise R comes out as a reflection.
    let mut u = u;
    let mut vt = vt;
    if u.determinant() < 0.0 {
        u = u * Matrix3::new(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, -1.0);
    }
    if vt.determinant() < 0.0 {
        vt = Matrix3::new(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, -1.0) * vt;
    }
    let w = Matrix3::new(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0);
    let r1 = u * w * vt;
    let r2 = u * w.transpose() * vt;
    let t = u.column(2);
    vec![
        RelPose { r: r1, t },
        RelPose { r: r1, t: -t },
        RelPose { r: r2, t },
        RelPose { r: r2, t: -t },
    ]
}

/// Triangulate one correspondence against the identity-pose first camera and
/// return the point in the first camera's frame, or `None` if degenerate.
fn triangulate_pair(pose: &RelPose, a: NPt, b: NPt) -> Option<Vector3<f64>> {
    // Rows of the DLT system for P1 = [I|0] and P2 = [R|t].
    let mut m = DMatrix::<f64>::zeros(4, 4);
    // x1 cross (P1 X) = 0
    m[(0, 0)] = -1.0;
    m[(0, 2)] = a.0;
    m[(1, 1)] = -1.0;
    m[(1, 2)] = a.1;
    // x2 cross (P2 X) = 0
    let r = &pose.r;
    let t = &pose.t;
    for c in 0..3 {
        m[(2, c)] = b.0 * r[(2, c)] - r[(0, c)];
        m[(3, c)] = b.1 * r[(2, c)] - r[(1, c)];
    }
    m[(2, 3)] = b.0 * t[2] - t[0];
    m[(3, 3)] = b.1 * t[2] - t[1];

    let vt = m.svd(false, true).v_t?;
    let h = vt.row(3);
    if h[3].abs() < 1e-12 {
        return None;
    }
    Some(Vector3::new(h[0] / h[3], h[1] / h[3], h[2] / h[3]))
}

/// Pick the physically realisable pose: the one putting the most points in front
/// of both cameras. The essential matrix admits four solutions and only this
/// cheirality test distinguishes them.
pub fn decompose_essential(e: &Matrix3<f64>, a: &[NPt], b: &[NPt]) -> Option<RelPose> {
    let mut best: Option<(usize, RelPose)> = None;
    for pose in candidate_poses(e) {
        let mut in_front = 0usize;
        for i in 0..a.len().min(b.len()) {
            let p = match triangulate_pair(&pose, a[i], b[i]) {
                Some(p) => p,
                None => continue,
            };
            if p[2] <= 0.0 {
                continue;
            }
            // Also require positive depth in the second camera.
            let p2 = pose.r * p + pose.t;
            if p2[2] > 0.0 {
                in_front += 1;
            }
        }
        if best.as_ref().is_none_or(|(n, _)| in_front > *n) {
            best = Some((in_front, pose));
        }
    }
    best.and_then(|(n, p)| if n > 0 { Some(p) } else { None })
}

/// Convenience: essential matrix, inliers, and the recovered pose in one call.
pub fn recover_pose(
    a: &[NPt],
    b: &[NPt],
    iters: usize,
    thresh: f64,
) -> Option<(RelPose, Vec<usize>)> {
    let (e, inliers) = find_essential_ransac(a, b, iters, thresh)?;
    let ia: Vec<NPt> = inliers.iter().map(|&i| a[i]).collect();
    let ib: Vec<NPt> = inliers.iter().map(|&i| b[i]).collect();
    let pose = decompose_essential(&e, &ia, &ib)?;
    Some((pose, inliers))
}

#[cfg(test)] #[path = "epipolar_tests.rs"] mod epipolar_tests;

#[cfg(test)]
mod tests {
    use super::*;
    use vision_core::camera::rodrigues_to_mat;

    /// Project a world point into a camera at (R, t) as a calibrated bearing.
    fn project(r: &Matrix3<f64>, t: &Vector3<f64>, p: Vector3<f64>) -> NPt {
        let c = r * p + *t;
        (c[0] / c[2], c[1] / c[2])
    }

    fn synthetic_scene() -> Vec<Vector3<f64>> {
        let mut pts = Vec::new();
        let mut seed = 12345u64;
        let mut next = || {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
            ((seed >> 33) as f64 / (1u64 << 31) as f64) - 0.5
        };
        for _ in 0..60 {
            pts.push(Vector3::new(next() * 2.0, next() * 2.0, 3.0 + next() * 2.0));
        }
        pts
    }

    #[test]
    fn recovers_pure_sideways_translation() {
        let pts = synthetic_scene();
        let r_true = Matrix3::identity();
        let t_true = Vector3::new(-0.3, 0.0, 0.0);

        let a: Vec<NPt> = pts
            .iter()
            .map(|&p| project(&Matrix3::identity(), &Vector3::zeros(), p))
            .collect();
        let b: Vec<NPt> = pts.iter().map(|&p| project(&r_true, &t_true, p)).collect();

        let (pose, inliers) = recover_pose(&a, &b, 200, 1e-6).expect("pose");
        assert!(inliers.len() > 50, "expected most points to be inliers, got {}", inliers.len());

        // Translation is only known up to scale, so compare directions.
        let tn = pose.t.norm();
        let dir = Vector3::new(pose.t[0] / tn, pose.t[1] / tn, pose.t[2] / tn);
        let expect = Vector3::new(-1.0, 0.0, 0.0);
        let dot = (dir.dot(&expect)).abs();
        assert!(dot > 0.99, "translation direction off: {dot}");

        // Rotation should be near identity.
        for i in 0..3 {
            for j in 0..3 {
                let want = if i == j { 1.0 } else { 0.0 };
                assert!(
                    (pose.r[(i, j)] - want).abs() < 1e-3,
                    "R[{i}][{j}] = {} want {want}",
                    pose.r[(i, j)]
                );
            }
        }
    }

    #[test]
    fn recovers_combined_rotation_and_translation() {
        let pts = synthetic_scene();
        let r_true = rodrigues_to_mat(Vector3::new(0.02, 0.05, -0.01));
        let t_true = Vector3::new(-0.25, 0.05, 0.02);

        let a: Vec<NPt> = pts
            .iter()
            .map(|&p| project(&Matrix3::identity(), &Vector3::zeros(), p))
            .collect();
        let b: Vec<NPt> = pts.iter().map(|&p| project(&r_true, &t_true, p)).collect();

        let (pose, _) = recover_pose(&a, &b, 300, 1e-6).expect("pose");

        let tn = pose.t.norm();
        let ttn = t_true.norm();
        let dot = ((pose.t[0] / tn) * (t_true[0] / ttn)
            + (pose.t[1] / tn) * (t_true[1] / ttn)
            + (pose.t[2] / tn) * (t_true[2] / ttn))
            .abs();
        assert!(dot > 0.99, "translation direction off: {dot}");

        for i in 0..3 {
            for j in 0..3 {
                assert!(
                    (pose.r[(i, j)] - r_true[(i, j)]).abs() < 5e-3,
                    "R[{i}][{j}] = {} want {}",
                    pose.r[(i, j)],
                    r_true[(i, j)]
                );
            }
        }
    }

    #[test]
    fn pure_rotation_yields_no_usable_translation() {
        // The degenerate case the UI must coach the user out of: with no baseline
        // the translation direction is unconstrained, so scale can never be found.
        let pts = synthetic_scene();
        let r_true = rodrigues_to_mat(Vector3::new(0.0, 0.08, 0.0));
        let t_true = Vector3::zeros();

        let a: Vec<NPt> = pts
            .iter()
            .map(|&p| project(&Matrix3::identity(), &Vector3::zeros(), p))
            .collect();
        let b: Vec<NPt> = pts.iter().map(|&p| project(&r_true, &t_true, p)).collect();

        // Rotation must still come out right even though translation is meaningless.
        if let Some((pose, _)) = recover_pose(&a, &b, 300, 1e-6) {
            for i in 0..3 {
                for j in 0..3 {
                    assert!(
                        (pose.r[(i, j)] - r_true[(i, j)]).abs() < 1e-2,
                        "rotation should survive a zero baseline"
                    );
                }
            }
        }
    }

    #[test]
    fn sampson_distance_is_zero_for_exact_correspondences() {
        let pts = synthetic_scene();
        let r_true = rodrigues_to_mat(Vector3::new(0.01, 0.03, 0.0));
        let t_true = Vector3::new(-0.2, 0.0, 0.0);
        let a: Vec<NPt> = pts
            .iter()
            .map(|&p| project(&Matrix3::identity(), &Vector3::zeros(), p))
            .collect();
        let b: Vec<NPt> = pts.iter().map(|&p| project(&r_true, &t_true, p)).collect();
        let e = essential_eight_point(&a, &b).expect("E");
        for i in 0..a.len() {
            assert!(
                sampson_distance(&e, a[i], b[i]) < 1e-12,
                "exact correspondence should have ~zero Sampson error"
            );
        }
    }
}

