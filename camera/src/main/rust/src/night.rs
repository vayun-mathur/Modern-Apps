//! Night mode: align a hand-held burst to the first frame (ORB + RANSAC
//! homography) and temporally average with deghosting to reduce noise.

use crate::features::{detect_and_describe, match_features};
use crate::geometry::{find_homography_ransac, Pt};
use crate::imgbuf::{to_gray, Rgba};
use crate::linalg::{Matrix3, Vector3};

// Tuned for low-light: lower FAST threshold, more features, more RANSAC,
// lower min-inliers. Registration runs at reduced res for speed.
const MAX_FEATURES: usize = 2000;
const FAST_THRESHOLD: i32 = 12;
const RATIO: f32 = 0.78;
const RANSAC_ITERS: usize = 500;
const RANSAC_THRESH: f32 = 3.0;
const MIN_INLIERS: usize = 12;
const REG_MAX_MP: f64 = 0.8; // registration resolution
const GHOST_THRESH_SUM: i32 = 110; // sum |Rdiff|+|Gdiff|+|Bdiff| above -> ghost, skip

#[inline]
fn apply(h: &Matrix3<f64>, x: f64, y: f64) -> (f64, f64) {
    let v = h * Vector3::new(x, y, 1.0);
    if v.z.abs() < 1e-12 {
        (v.x, v.y)
    } else {
        (v.x / v.z, v.y / v.z)
    }
}

/// Scale a homography estimated at reg_scale to full resolution:
/// H_full = S^-1 * H_small * S  where S=diag(s,s,1)
fn scale_homography(h: &Matrix3<f64>, s: f64) -> Matrix3<f64> {
    if (s - 1.0).abs() < 1e-6 {
        return *h;
    }
    // Derived: [[h00, h01, h02/s], [h10, h11, h12/s], [h20*s, h21*s, h22]]
    Matrix3::new(
        h[(0, 0)],
        h[(0, 1)],
        h[(0, 2)] / s,
        h[(1, 0)],
        h[(1, 1)],
        h[(1, 2)] / s,
        h[(2, 0)] * s,
        h[(2, 1)] * s,
        h[(2, 2)],
    )
}

pub fn align_and_merge(frames: &[Rgba]) -> Option<Rgba> {
    let n = frames.len();
    if n == 0 {
        return None;
    }
    if n == 1 {
        return Some(frames[0].clone());
    }
    let reff = &frames[0];
    let w = reff.w;
    let h = reff.h;
    if w == 0 || h == 0 {
        return None;
    }

    // Registration scale: downscale large frames for feature detection.
    let area = w as f64 * h as f64;
    let reg_scale = (REG_MAX_MP * 1e6 / area).sqrt().min(1.0);
    let (ref_small, ref_s) = if reg_scale < 0.999 {
        let rw = ((w as f64 * reg_scale).round() as usize).max(1);
        let rh = ((h as f64 * reg_scale).round() as usize).max(1);
        (reff.resized(rw, rh), reg_scale)
    } else {
        (reff.clone(), 1.0)
    };
    let ref_feat = detect_and_describe(&to_gray(&ref_small), MAX_FEATURES, FAST_THRESHOLD);

    let mut acc = vec![0f32; w * h * 3];
    let mut cnt = vec![0f32; w * h];

    // Reference pixels always contribute.
    for yy in 0..h {
        for xx in 0..w {
            let rc = reff.get(xx, yy);
            let idx = yy * w + xx;
            acc[idx * 3] = rc[0] as f32;
            acc[idx * 3 + 1] = rc[1] as f32;
            acc[idx * 3 + 2] = rc[2] as f32;
            cnt[idx] = 1.0;
        }
    }

    for f in frames.iter().skip(1) {
        // Feature detection at reduced resolution.
        let (cur_small, s) = if ref_s < 0.999 {
            let rw = ((f.w as f64 * ref_s).round() as usize).max(1);
            let rh = ((f.h as f64 * ref_s).round() as usize).max(1);
            // Guard dimension mismatch (should match reg scale but keep safe)
            if rw * rh == 0 {
                (f.clone(), 1.0)
            } else {
                (f.resized(rw, rh), ref_s)
            }
        } else {
            (f.clone(), 1.0)
        };
        let feat = detect_and_describe(&to_gray(&cur_small), MAX_FEATURES, FAST_THRESHOLD);
        let matches = match_features(&feat, &ref_feat, RATIO);
        if matches.len() < MIN_INLIERS {
            continue;
        }
        let a: Vec<Pt> = matches.iter().map(|&(ia, _)| (feat.kps[ia].x, feat.kps[ia].y)).collect();
        let b: Vec<Pt> = matches.iter().map(|&(_, ib)| (ref_feat.kps[ib].x, ref_feat.kps[ib].y)).collect();
        let h_small = match find_homography_ransac(&a, &b, RANSAC_ITERS, RANSAC_THRESH) {
            Some((hm, inl)) if inl >= MIN_INLIERS => hm,
            _ => continue,
        };
        let h_to_ref = scale_homography(&h_small, s);
        let h_inv = match h_to_ref.try_inverse() {
            Some(m) => m,
            None => continue,
        };
        // Warp and accumulate with deghosting against reference.
        for y in 0..h {
            for x in 0..w {
                let (sx, sy) = apply(&h_inv, x as f64, y as f64);
                if let Some(c) = f.sample(sx as f32, sy as f32) {
                    // Deghost: skip samples far from reference to avoid moving-object ghosts.
                    let rp = reff.get(x, y);
                    let diff = (c[0] as i32 - rp[0] as i32).abs()
                        + (c[1] as i32 - rp[1] as i32).abs()
                        + (c[2] as i32 - rp[2] as i32).abs();
                    if diff > GHOST_THRESH_SUM {
                        continue;
                    }
                    let idx = y * w + x;
                    acc[idx * 3] += c[0];
                    acc[idx * 3 + 1] += c[1];
                    acc[idx * 3 + 2] += c[2];
                    cnt[idx] += 1.0;
                }
            }
        }
    }

    let mut out = Rgba::new(w, h);
    for i in 0..w * h {
        let c = cnt[i];
        let d = i * 4;
        if c > 0.0 {
            out.px[d] = (acc[i * 3] / c).round().clamp(0.0, 255.0) as u8;
            out.px[d + 1] = (acc[i * 3 + 1] / c).round().clamp(0.0, 255.0) as u8;
            out.px[d + 2] = (acc[i * 3 + 2] / c).round().clamp(0.0, 255.0) as u8;
            out.px[d + 3] = 255;
        } else {
            let rc = reff.get(i % w, i / w);
            out.px[d] = rc[0];
            out.px[d + 1] = rc[1];
            out.px[d + 2] = rc[2];
            out.px[d + 3] = 255;
        }
    }
    Some(out)
}

#[cfg(test)]
#[path = "night_tests.rs"]
mod night_tests;
