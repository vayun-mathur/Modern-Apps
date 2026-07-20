//! Camera parameters and helpers, mirroring cv::detail::CameraParams and the
//! Rodrigues rotation conversions used by the estimators / bundle adjuster.

use nalgebra::{Matrix3, Vector3};

#[derive(Clone)]
pub struct CameraParams {
    pub focal: f64,
    pub aspect: f64,
    pub ppx: f64,
    pub ppy: f64,
    pub r: Matrix3<f64>, // rotation
}

impl CameraParams {
    pub fn k(&self) -> Matrix3<f64> {
        Matrix3::new(
            self.focal, 0.0, self.ppx,
            0.0, self.focal * self.aspect, self.ppy,
            0.0, 0.0, 1.0,
        )
    }
}

/// Rodrigues: rotation vector -> matrix (cv::Rodrigues).
pub fn rodrigues_to_mat(rvec: Vector3<f64>) -> Matrix3<f64> {
    let theta = rvec.norm();
    if theta < 1e-12 {
        return Matrix3::identity();
    }
    let r = rvec / theta;
    let (s, c) = theta.sin_cos();
    let c1 = 1.0 - c;
    let (x, y, z) = (r.x, r.y, r.z);
    Matrix3::new(
        c + x * x * c1,       x * y * c1 - z * s,   x * z * c1 + y * s,
        y * x * c1 + z * s,   c + y * y * c1,       y * z * c1 - x * s,
        z * x * c1 - y * s,   z * y * c1 + x * s,   c + z * z * c1,
    )
}

/// Rodrigues: rotation matrix -> vector (cv::Rodrigues inverse).
pub fn mat_to_rodrigues(m: &Matrix3<f64>) -> Vector3<f64> {
    // Force orthonormal via SVD first for stability.
    let r = {
        let svd = m.svd(true, true);
        match (svd.u, svd.v_t) {
            (Some(u), Some(vt)) => u * vt,
            _ => *m,
        }
    };
    let rx = r[(2, 1)] - r[(1, 2)];
    let ry = r[(0, 2)] - r[(2, 0)];
    let rz = r[(1, 0)] - r[(0, 1)];
    let s = ((rx * rx + ry * ry + rz * rz) * 0.25).sqrt();
    let trace = r[(0, 0)] + r[(1, 1)] + r[(2, 2)];
    let c = ((trace - 1.0) * 0.5).clamp(-1.0, 1.0);
    let theta = c.acos();
    if s < 1e-9 {
        if theta < 1e-9 {
            return Vector3::zeros();
        }
        // theta ~ pi: extract axis from diagonal
        let xx = (r[(0, 0)] + 1.0) * 0.5;
        let yy = (r[(1, 1)] + 1.0) * 0.5;
        let zz = (r[(2, 2)] + 1.0) * 0.5;
        let ax = xx.max(0.0).sqrt();
        let ay = yy.max(0.0).sqrt() * if r[(0, 1)] < 0.0 { -1.0 } else { 1.0 };
        let az = zz.max(0.0).sqrt() * if r[(0, 2)] < 0.0 { -1.0 } else { 1.0 };
        return Vector3::new(ax, ay, az) * theta;
    }
    let mult = theta / (2.0 * s);
    Vector3::new(rx, ry, rz) * mult
}
