//! Rotation-model spherical stitching — a Rust translation of OpenCV's
//! `stitching_detail` pipeline used by `cv::Stitcher` (Brown & Lowe 2007):
//!   * `focalsFromHomography` / `estimateFocal`  (modules/stitching/.../autocalib.cpp)
//!   * `HomographyBasedEstimator` rotation chaining (motion_estimators.cpp)
//!   * `SphericalProjector::mapForward/mapBackward` (warpers.cpp / warpers_inl.hpp)
//!
//! This replaces planar homography chaining (which distorts badly for multi-image
//! panoramas) with per-image focal + rotation warped onto a common sphere.

use crate::imgbuf::Rgba;
use crate::warp::feather_weights;
use nalgebra::{Matrix3, Vector3};

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

/// Chain per-image rotations from consecutive homographies `pair[i]` = H_{i->i-1}
/// (rotation-only model H = K R_{i-1} R_iᵀ K⁻¹). R_0 = I.
pub fn estimate_rotations(pairs: &[Matrix3<f64>], k: &Matrix3<f64>) -> Vec<Matrix3<f64>> {
    let n = pairs.len();
    let k_inv = k.try_inverse().unwrap_or_else(Matrix3::identity);
    let mut rots = vec![Matrix3::<f64>::identity(); n];
    for i in 1..n {
        // M_i = K^-1 H_{i->i-1} K = R_{i-1} R_iᵀ  =>  R_i = M_iᵀ R_{i-1}
        let m = k_inv * pairs[i] * k;
        let r = m.transpose() * rots[i - 1];
        rots[i] = orthonormalize(&r);
    }
    rots
}

/// SphericalProjector state for one image: r_kinv = R K⁻¹, k_rinv = K Rᵀ.
struct Proj {
    r_kinv: Matrix3<f64>,
    k_rinv: Matrix3<f64>,
    scale: f64,
}

impl Proj {
    fn new(k: &Matrix3<f64>, r: &Matrix3<f64>, scale: f64) -> Option<Proj> {
        let k_inv = k.try_inverse()?;
        Some(Proj {
            r_kinv: r * k_inv,
            k_rinv: k * r.transpose(),
            scale,
        })
    }

    /// source (x,y) -> sphere (u,v). Spherical handles large vertical FOV (tall
    /// frames) far better than cylindrical. (OpenCV SphericalProjector::mapForward)
    fn forward(&self, x: f64, y: f64) -> (f64, f64) {
        let p = self.r_kinv * Vector3::new(x, y, 1.0);
        let u = self.scale * p.x.atan2(p.z);
        let denom = (p.x * p.x + p.y * p.y + p.z * p.z).sqrt();
        let ww = if denom > 1e-12 { p.y / denom } else { 0.0 };
        let v = self.scale * (std::f64::consts::PI - ww.clamp(-1.0, 1.0).acos());
        (u, v)
    }

    /// sphere (u,v) -> source (x,y); None if behind the camera.
    fn backward(&self, u: f64, v: f64) -> Option<(f64, f64)> {
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

/// Compute a projector's (u,v) bounds by walking the source-image border.
fn tile_bounds(proj: &Proj, w: usize, h: usize) -> (f64, f64, f64, f64) {
    let (mut u0, mut v0, mut u1, mut v1) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
    let step = 1usize.max(w.min(h) / 100);
    let mut consider = |x: usize, y: usize, u0: &mut f64, v0: &mut f64, u1: &mut f64, v1: &mut f64| {
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

/// Warp one image onto the sphere with camera intrinsics `k`/rotation `r` and
/// warp `scale`. Mirrors SphericalWarper::warp (buildMaps + remap).
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
                if let Some(c) = frame.sample(sx as f32, sy as f32) {
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

/// Warp all frames onto the common sphere and gain-compensated feather-blend.
/// `rotations[i]` and shared `k`/`scale` come from the estimator. Returns the
/// cropped panorama.
pub fn warp_spherical_and_blend(
    frames: &[Rgba],
    rotations: &[Matrix3<f64>],
    k: &Matrix3<f64>,
    scale: f64,
    gains: &[f32],
    max_canvas: usize,
) -> Option<Rgba> {
    if frames.is_empty() || frames.len() != rotations.len() {
        return None;
    }
    let projs: Vec<Proj> = frames
        .iter()
        .zip(rotations.iter())
        .filter_map(|(_, r)| Proj::new(k, r, scale))
        .collect();
    if projs.len() != frames.len() {
        return None;
    }

    // Global sphere bounds.
    let (mut gu0, mut gv0, mut gu1, mut gv1) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
    let mut tiles = Vec::with_capacity(frames.len());
    for (f, p) in frames.iter().zip(projs.iter()) {
        let b = tile_bounds(p, f.w, f.h);
        tiles.push(b);
        gu0 = gu0.min(b.0);
        gv0 = gv0.min(b.1);
        gu1 = gu1.max(b.2);
        gv1 = gv1.max(b.3);
    }
    if !gu0.is_finite() || !gu1.is_finite() {
        return None;
    }
    let cw = (gu1 - gu0).ceil() as i64 + 1;
    let ch = (gv1 - gv0).ceil() as i64 + 1;
    if cw <= 0 || ch <= 0 || cw as usize > max_canvas || ch as usize > max_canvas {
        return None;
    }
    let cw = cw as usize;
    let ch = ch as usize;

    let mut acc = vec![0f32; cw * ch * 3];
    let mut accw = vec![0f32; cw * ch];

    for (fi, f) in frames.iter().enumerate() {
        let p = &projs[fi];
        let gain = gains.get(fi).copied().unwrap_or(1.0);
        let wt = feather_weights(f.w, f.h);
        let (tu0, tv0, tu1, tv1) = tiles[fi];
        let x0 = ((tu0 - gu0).floor() as i64).max(0) as usize;
        let y0 = ((tv0 - gv0).floor() as i64).max(0) as usize;
        let x1 = (((tu1 - gu0).ceil() as i64).min(cw as i64 - 1)).max(0) as usize;
        let y1 = (((tv1 - gv0).ceil() as i64).min(ch as i64 - 1)).max(0) as usize;

        for cy in y0..=y1 {
            for cx in x0..=x1 {
                let u = cx as f64 + gu0;
                let v = cy as f64 + gv0;
                let (sx, sy) = match p.backward(u, v) {
                    Some(s) => s,
                    None => continue,
                };
                if let Some(c) = f.sample(sx as f32, sy as f32) {
                    if c[3] < 8.0 {
                        continue;
                    }
                    let ix = (sx as usize).min(f.w - 1);
                    let iy = (sy as usize).min(f.h - 1);
                    let w = wt[iy * f.w + ix];
                    if w <= 0.0 {
                        continue;
                    }
                    let idx = cy * cw + cx;
                    acc[idx * 3] += (c[0] * gain).min(255.0) * w;
                    acc[idx * 3 + 1] += (c[1] * gain).min(255.0) * w;
                    acc[idx * 3 + 2] += (c[2] * gain).min(255.0) * w;
                    accw[idx] += w;
                }
            }
        }
    }

    // Resolve + crop to covered bbox.
    let (mut minx, mut miny, mut maxx, mut maxy) = (cw, ch, 0usize, 0usize);
    let mut any = false;
    for y in 0..ch {
        for x in 0..cw {
            if accw[y * cw + x] > 0.0 {
                any = true;
                minx = minx.min(x);
                miny = miny.min(y);
                maxx = maxx.max(x);
                maxy = maxy.max(y);
            }
        }
    }
    if !any {
        return None;
    }
    let ow = maxx - minx + 1;
    let oh = maxy - miny + 1;
    let mut out = Rgba::new(ow, oh);
    for y in 0..oh {
        for x in 0..ow {
            let sidx = (y + miny) * cw + (x + minx);
            let w = accw[sidx];
            let didx = (y * ow + x) * 4;
            if w > 0.0 {
                out.px[didx] = (acc[sidx * 3] / w).round().clamp(0.0, 255.0) as u8;
                out.px[didx + 1] = (acc[sidx * 3 + 1] / w).round().clamp(0.0, 255.0) as u8;
                out.px[didx + 2] = (acc[sidx * 3 + 2] / w).round().clamp(0.0, 255.0) as u8;
                out.px[didx + 3] = 255;
            } else {
                out.px[didx + 3] = 0;
            }
        }
    }
    Some(out)
}
