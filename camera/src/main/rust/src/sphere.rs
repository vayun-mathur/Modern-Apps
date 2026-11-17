//! Rotation-model spherical stitching — a Rust translation of OpenCV's
//! `stitching_detail` pipeline used by `cv::Stitcher` (Brown & Lowe 2007):
//!   * `focalsFromHomography` / `estimateFocal`  (modules/stitching/.../autocalib.cpp)
//!   * `HomographyBasedEstimator` rotation chaining (motion_estimators.cpp)
//!   * `SphericalProjector::mapForward/mapBackward` (warpers.cpp / warpers_inl.hpp)
//!
//! This replaces planar homography chaining (which distorts badly for multi-image
//! panoramas) with per-image focal + rotation warped onto a common sphere.

use crate::imgbuf::Rgba;
use crate::linalg::{Matrix3, Vector3};

/// OpenCV `focalsFromHomography` (autocalib.cpp): recover the two focal-length
/// estimates implied by a homography. Returns (f0, f1), each Some if valid.
pub fn focals_from_homography(h: &Matrix3<f64>) -> (Option<f64>, Option<f64>) {
    // Row-major access matching OpenCV's h[0..8].
    let hh = [
        h[(0, 0)], h[(0, 1)], h[(0, 2)],
        h[(1, 0)], h[(1, 1)], h[(1, 2)],
        h[(2, 0)], h[(2, 1)], h[(2, 2)],
    ];

    // f1 (from the "to" image)
    let mut f1: Option<f64> = None;
    {
        let d1 = hh[6] * hh[7];
        let d2 = (hh[7] - hh[6]) * (hh[7] + hh[6]);
        let mut v1 = -(hh[0] * hh[1] + hh[3] * hh[4]) / d1;
        let mut v2 = (hh[0] * hh[0] + hh[3] * hh[3] - hh[1] * hh[1] - hh[4] * hh[4]) / d2;
        if v1 < v2 {
            std::mem::swap(&mut v1, &mut v2);
        }
        if v1 > 0.0 && v2 > 0.0 {
            f1 = Some((if d1.abs() > d2.abs() { v1 } else { v2 }).sqrt());
        } else if v1 > 0.0 {
            f1 = Some(v1.sqrt());
        }
    }

    // f0 (from the "from" image)
    let mut f0: Option<f64> = None;
    {
        let d1 = hh[0] * hh[3] + hh[1] * hh[4];
        let d2 = hh[0] * hh[0] + hh[1] * hh[1] - hh[3] * hh[3] - hh[4] * hh[4];
        let mut v1 = -hh[2] * hh[5] / d1;
        let mut v2 = (hh[5] * hh[5] - hh[2] * hh[2]) / d2;
        if v1 < v2 {
            std::mem::swap(&mut v1, &mut v2);
        }
        if v1 > 0.0 && v2 > 0.0 {
            f0 = Some((if d1.abs() > d2.abs() { v1 } else { v2 }).sqrt());
        } else if v1 > 0.0 {
            f0 = Some(v1.sqrt());
        }
    }

    (sanitize(f0), sanitize(f1))
}

fn sanitize(f: Option<f64>) -> Option<f64> {
    f.filter(|v| v.is_finite() && *v > 1.0)
}

/// OpenCV `estimateFocal`: median of sqrt(f0*f1) over pairwise homographies that
/// yield both focals; falls back to (w + h) when nothing is estimable.
pub fn estimate_focal(pairs: &[Matrix3<f64>], w: usize, h: usize) -> f64 {
    let mut all = Vec::new();
    for hmat in pairs {
        let (f0, f1) = focals_from_homography(hmat);
        if let (Some(a), Some(b)) = (f0, f1) {
            all.push((a * b).sqrt());
        }
    }
    if all.is_empty() {
        return (w + h) as f64;
    }
    all.sort_by(|a, b| a.total_cmp(b));
    let m = all.len() / 2;
    if all.len() % 2 == 1 {
        all[m]
    } else {
        (all[m - 1] + all[m]) / 2.0
    }
}

pub fn k_matrix(f: f64, ppx: f64, ppy: f64) -> Matrix3<f64> {
    Matrix3::new(f, 0.0, ppx, 0.0, f, ppy, 0.0, 0.0, 1.0)
}

/// Nearest orthonormal matrix (SVD): R = U Vᵀ. Ensures a valid rotation.
pub(crate) fn orthonormalize(m: &Matrix3<f64>) -> Matrix3<f64> {
    let svd = m.svd(true, true);
    match (svd.u, svd.v_t) {
        (Some(u), Some(vt)) => u * vt,
        _ => Matrix3::identity(),
    }
}

/// SphericalProjector state for one image: r_kinv = R K⁻¹, k_rinv = K Rᵀ, rinv = Rᵀ, k.
/// Matches OpenCV ProjectorBase setCameraParams: rinv = R.t(), r_kinv = R*K.inv(), k_rinv = K*Rinv
pub struct Proj {
    pub r_kinv: Matrix3<f64>,
    pub k_rinv: Matrix3<f64>,
    pub rinv: Matrix3<f64>,
    pub k: Matrix3<f64>,
    pub scale: f64,
}

impl Proj {
    pub fn new(k: &Matrix3<f64>, r: &Matrix3<f64>, scale: f64) -> Option<Proj> {
        let k_inv = k.try_inverse()?;
        let rinv = r.transpose();
        Some(Proj {
            r_kinv: r * k_inv,
            k_rinv: k * rinv,
            rinv,
            k: *k,
            scale,
        })
    }

    /// source (x,y) -> sphere (u,v). Spherical handles large vertical FOV (tall
    /// frames) far better than cylindrical. (OpenCV SphericalProjector::mapForward)
    /// u = scale*atan2(x_,z_) v = scale*(PI-acos(y_/norm))
    pub fn forward(&self, x: f64, y: f64) -> (f64, f64) {
        let p = self.r_kinv * Vector3::new(x, y, 1.0);
        let u = self.scale * p.x.atan2(p.z);
        let denom = (p.x * p.x + p.y * p.y + p.z * p.z).sqrt();
        let ww = if denom > 1e-12 { p.y / denom } else { 0.0 };
        let v = self.scale * (std::f64::consts::PI - ww.clamp(-1.0, 1.0).acos());
        (u, v)
    }

    /// sphere (u,v) -> source (x,y); None if behind the camera.
    /// Matches OpenCV mapBackward inverse trig with k_rinv.
    pub fn backward(&self, u: f64, v: f64) -> Option<(f64, f64)> {
        let uu = u / self.scale;
        let vv = v / self.scale;
        let sinv = (std::f64::consts::PI - vv).sin();
        let x_ = sinv * uu.sin();
        let y_ = (std::f64::consts::PI - vv).cos();
        let z_ = sinv * uu.cos();
        let p = self.k_rinv * Vector3::new(x_, y_, z_);
        if p.z > 0.0 {
            Some((p.x / p.z, p.y / p.z))
        } else {
            None
        }
    }
}

/// Compute a projector's (u,v) bounds by walking the source-image border,
/// plus OpenCV pole check: if forward-projected pole inside src includes
/// v=0 or PI*scale. Matches SphericalWarper::detectResultRoi 375-416.
fn tile_bounds(proj: &Proj, w: usize, h: usize) -> (f64, f64, f64, f64) {
    let (mut u0, mut v0, mut u1, mut v1) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
    let step = 1usize.max(w.min(h) / 100);
    let consider = |x: usize, y: usize, u0: &mut f64, v0: &mut f64, u1: &mut f64, v1: &mut f64| {
        let (u, v) = proj.forward(x as f64, y as f64);
        if u.is_finite() && v.is_finite() {
            *u0 = u0.min(u);
            *v0 = v0.min(v);
            *u1 = u1.max(u);
            *v1 = v1.max(v);
        }
    };
    let mut x = 0;
    while x < w {
        consider(x, 0, &mut u0, &mut v0, &mut u1, &mut v1);
        consider(x, h - 1, &mut u0, &mut v0, &mut u1, &mut v1);
        x += step;
    }
    let mut y = 0;
    while y < h {
        consider(0, y, &mut u0, &mut v0, &mut u1, &mut v1);
        consider(w - 1, y, &mut u0, &mut v0, &mut u1, &mut v1);
        y += step;
    }

    // Pole handling like OpenCV SphericalWarper::detectResultRoi
    // OpenCV checks rinv: x = rinv[1] y=rinv[4] z=rinv[7] and x=rinv[1] y=-rinv[4] z=rinv[7]
    // i.e. second column of R^T = second row of R? Actually rinv = R.t() so rinv[1]=Rinv(0,1) etc.
    // We need to check if north/south pole projection falls inside src.
    // Pole in camera space: (0, 1, 0) and (0,-1,0) ??? Actually OpenCV code: (rinv[1], rinv[4], rinv[7]) corresponds to R^T * (0,1,0) ??? Let's replicate: check if K*R^T*[0,1,0] yields point inside image? Equivalent to mapBackward for pole?
    // Simpler: if forward map of pole (0,1,0) ray falls inside image, extend v bounds to include PI*scale or 0.
    // OpenCV logic: projector_.rinv[1] = R^T second column x? Wait rinv row-major: rinv[0]=Rinv(0,0) rinv[1]=Rinv(0,1) rinv[2]=Rinv(0,2) rinv[3]=Rinv(1,0) etc. So rinv[1]=R^T(0,1)=R(1,0), rinv[4]=R^T(1,1)=R(1,1), rinv[7]=R^T(2,1)=R(1,2)
    // That's first row? Let's follow accurately: setCameraParams Mat Rinv = R.t(), so rinv[1]=Rinv(0,1)=R(1,0), rinv[4]=Rinv(1,1)=R(1,1), rinv[7]=Rinv(2,1)=R(1,2). So vector (R(1,0),R(1,1),R(1,2)) = second row of R (y axis of camera rotation). That's the camera's up direction in world? Checking north pole.
    // The north pole ray in world is (0,1,0)?? In spherical projection, y_ = cos(PI - v) – north pole would be y=1 at v=0? Let's see: backward: u=0,v=0 -> PI -0 = PI, cos PI = -1 -> y_=-1 south? Maybe swapped. Anyway OpenCV checks y>0 and projects via K: x_ = K0*x +K1*y /z +K2 etc. If inside src, it includes v = PI*scale.
    // We can replicate by testing whether projecting world ray (0,1,0) and (0,-1,0) through K*R^T yields inside image.
    // Implementation: p_world north = (0,1,0) (?) Actually we test ray from camera after rotation? Let's approximate: if the camera looks near pole, the tile should include top/bottom.

    // Check north pole and south pole inclusion via map: try to forward project a ray approx?
    // OpenCV does: x=rinv[1] y=rinv[4] z=rinv[7] corresponds to R^T*(0,1,0) = second column of R^T = second row of R
    // Then computes image point x_ = K*x/y? Wait code: x_= (k0*x + k1*y)/z + k2 etc.
    // That's projection of world ray (x,y,z) onto image via K*R^T? Let's implement same.

    // north pole test vector (rinv second column)
    let rx = proj.rinv[(0, 1)];
    let ry = proj.rinv[(1, 1)];
    let rz = proj.rinv[(2, 1)];
    if ry > 0.0 {
        // Project via K
        let k = proj.k;
        let px = (k[(0, 0)] * rx + k[(0, 1)] * ry) / rz + k[(0, 2)];
        let py = k[(1, 1)] * ry / rz + k[(1, 2)];
        if px > 0.0 && px < w as f64 && py > 0.0 && py < h as f64 {
            // includes north pole – extend v to PI*scale
            let pole_v = std::f64::consts::PI * proj.scale;
            v0 = v0.min(pole_v);
            v1 = v1.max(pole_v);
            u0 = u0.min(0.0);
            u1 = u1.max(0.0);
        }
    }
    let rx = proj.rinv[(0, 1)];
    let ry = -proj.rinv[(1, 1)];
    let rz = proj.rinv[(2, 1)];
    if ry > 0.0 {
        let k = proj.k;
        let px = (k[(0, 0)] * rx + k[(0, 1)] * ry) / rz + k[(0, 2)];
        let py = k[(1, 1)] * ry / rz + k[(1, 2)];
        if px > 0.0 && px < w as f64 && py > 0.0 && py < h as f64 {
            let pole_v = 0.0;
            v0 = v0.min(pole_v);
            v1 = v1.max(pole_v);
            u0 = u0.min(0.0);
            u1 = u1.max(0.0);
        }
    }

    (u0, v0, u1, v1)
}

/// Cheap (u,v) bounds of a warped image (border walk only, no pixels), for
/// sizing the output canvas before committing to a full warp.
pub fn warped_bounds(fw: usize, fh: usize, k: &Matrix3<f64>, r: &Matrix3<f64>, scale: f64) -> Option<(f64, f64, f64, f64)> {
    let p = Proj::new(k, r, scale)?;
    let b = tile_bounds(&p, fw, fh);
    if b.0.is_finite() && b.1.is_finite() && b.2.is_finite() && b.3.is_finite() {
        Some(b)
    } else {
        None
    }
}

/// A single image warped onto the sphere: RGBA tile (alpha = coverage mask) and
/// its top-left corner in global sphere-pixel coordinates.
pub struct WarpedTile {
    pub img: Rgba,
    pub corner_x: i32,
    pub corner_y: i32,
}

/// Bilinear sample with BORDER_REFLECT_101 like OpenCV (instead of clamp)
/// for better edge quality in spheres.
fn sample_reflect_101(frame: &Rgba, fx: f32, fy: f32) -> Option<[f32; 4]> {
    // Mirror index reflect without repeating border
    let x0_f = fx.floor();
    let y0_f = fy.floor();
    let x0 = x0_f as isize;
    let y0 = y0_f as isize;
    let x1 = x0 + 1;
    let y1 = y0 + 1;
    let ax = fx - x0_f;
    let ay = fy - y0_f;

    fn refl(i: isize, n: usize) -> usize {
        let m = n as isize;
        if m <= 1 { return 0; }
        let mut v = i;
        while v < 0 || v >= m {
            if v < 0 { v = -v; } else { v = 2 * m - v - 2; }
        }
        v as usize
    }

    let x0r = refl(x0, frame.w);
    let x1r = refl(x1, frame.w);
    let y0r = refl(y0, frame.h);
    let y1r = refl(y1, frame.h);

    let c00 = frame.get(x0r, y0r);
    let c10 = frame.get(x1r, y0r);
    let c01 = frame.get(x0r, y1r);
    let c11 = frame.get(x1r, y1r);
    if c00[3] == 0 && c10[3] == 0 && c01[3] == 0 && c11[3] == 0 {
        return None;
    }
    let mut out = [0f32; 4];
    for k in 0..4 {
        let top = c00[k] as f32 * (1.0 - ax) + c10[k] as f32 * ax;
        let bot = c01[k] as f32 * (1.0 - ax) + c11[k] as f32 * ax;
        out[k] = top * (1.0 - ay) + bot * ay;
    }
    Some(out)
}

/// Warp one image onto the sphere with camera intrinsics `k`/rotation `r` and
/// warp `scale`. Mirrors SphericalWarper::warp (buildMaps + remap) with
/// BORDER_REFLECT_101 sampling (not clamp) and pole ROI already handled in tile_bounds.
pub fn warp_one(frame: &Rgba, k: &Matrix3<f64>, r: &Matrix3<f64>, scale: f64) -> Option<WarpedTile> {
    let p = Proj::new(k, r, scale)?;
    let (u0, v0, u1, v1) = tile_bounds(&p, frame.w, frame.h);
    if !u0.is_finite() || !u1.is_finite() {
        return None;
    }
    let cx = u0.floor() as i32;
    let cy = v0.floor() as i32;
    let tw = ((u1.ceil() as i32 - cx) + 1).max(1) as usize;
    let th = ((v1.ceil() as i32 - cy) + 1).max(1) as usize;
    if tw > 20000 || th > 20000 {
        return None;
    }
    let mut img = Rgba::new(tw, th);
    for ty in 0..th {
        for tx in 0..tw {
            let u = (cx + tx as i32) as f64;
            let v = (cy + ty as i32) as f64;
            if let Some((sx, sy)) = p.backward(u, v) {
                // Use reflect_101 sampling for spherical quality like OpenCV INTER_LINEAR BORDER_REFLECT
                if let Some(c) = sample_reflect_101(frame, sx as f32, sy as f32) {
                    if c[3] >= 8.0 {
                        img.set(tx, ty, [
                            c[0].round().clamp(0.0, 255.0) as u8,
                            c[1].round().clamp(0.0, 255.0) as u8,
                            c[2].round().clamp(0.0, 255.0) as u8,
                            255,
                        ]);
                    }
                }
            }
        }
    }
    Some(WarpedTile { img, corner_x: cx, corner_y: cy })
}

#[cfg(test)]
mod sphere_host_tests {
    use super::*;
    use crate::linalg::Matrix3;

    #[test]
    fn estimate_focal_falls_back_without_valid_pairs() {
        assert_eq!(estimate_focal(&[], 640, 480), 1120.0);
        let (f0, f1) = focals_from_homography(&Matrix3::identity());
        assert!(f0.is_none() && f1.is_none());
        assert_eq!(estimate_focal(&[Matrix3::identity()], 640, 480), 1120.0);
    }

    #[test]
    fn proj_center_round_trips() {
        let k = k_matrix(800.0, 320.0, 240.0);
        let r = Matrix3::identity();
        let proj = Proj::new(&k, &r, 800.0).expect("proj");
        let (u, v) = proj.forward(320.0, 240.0);
        assert!(u.is_finite() && v.is_finite());
        let (x, y) = proj.backward(u, v).expect("backward");
        assert!((x - 320.0).abs() < 1e-6, "x {x}");
        assert!((y - 240.0).abs() < 1e-6, "y {y}");
    }
}

#[cfg(test)]
#[path = "sphere_tests.rs"]
mod sphere_tests;
