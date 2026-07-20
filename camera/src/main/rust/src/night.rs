//! Night mode: align a hand-held burst to the first frame (ORB + RANSAC
//! homography) and temporally average to reduce noise.

use crate::features::{detect_and_describe, match_features};
use crate::geometry::{find_homography_ransac, Pt};
use crate::imgbuf::{to_gray, Rgba};
use nalgebra::{Matrix3, Vector3};

const MAX_FEATURES: usize = 1200;
const FAST_THRESHOLD: i32 = 20;
const RATIO: f32 = 0.8;
const RANSAC_ITERS: usize = 400;
const RANSAC_THRESH: f32 = 3.0;
const MIN_INLIERS: usize = 20;

#[inline]
fn apply(h: &Matrix3<f64>, x: f64, y: f64) -> (f64, f64) {
    let v = h * Vector3::new(x, y, 1.0);
    if v.z.abs() < 1e-12 { (v.x, v.y) } else { (v.x / v.z, v.y / v.z) }
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
    let ref_feat = detect_and_describe(&to_gray(reff), MAX_FEATURES, FAST_THRESHOLD);

    let mut acc = vec![0f32; w * h * 3];
    let mut cnt = vec![0f32; w * h];

    for (i, f) in frames.iter().enumerate() {
        // homography mapping this frame -> reference
        let h_to_ref = if i == 0 {
            Matrix3::identity()
        } else {
            let feat = detect_and_describe(&to_gray(f), MAX_FEATURES, FAST_THRESHOLD);
            let matches = match_features(&feat, &ref_feat, RATIO);
            if matches.len() < MIN_INLIERS {
                continue; // too different; skip rather than ghost
            }
            let a: Vec<Pt> = matches.iter().map(|&(ia, _)| (feat.kps[ia].x, feat.kps[ia].y)).collect();
            let b: Vec<Pt> = matches.iter().map(|&(_, ib)| (ref_feat.kps[ib].x, ref_feat.kps[ib].y)).collect();
            match find_homography_ransac(&a, &b, RANSAC_ITERS, RANSAC_THRESH) {
                Some((hm, inl)) if inl >= MIN_INLIERS => hm,
                _ => continue,
            }
        };
        let h_inv = match h_to_ref.try_inverse() {
            Some(m) => m,
            None => continue,
        };
        for y in 0..h {
            for x in 0..w {
                let (sx, sy) = apply(&h_inv, x as f64, y as f64);
                if let Some(c) = f.sample(sx as f32, sy as f32) {
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
            // fall back to the reference pixel if nothing aligned here
            let rc = reff.get(i % w, i / w);
            out.px[d] = rc[0];
            out.px[d + 1] = rc[1];
            out.px[d + 2] = rc[2];
            out.px[d + 3] = 255;
        }
    }
    Some(out)
}
