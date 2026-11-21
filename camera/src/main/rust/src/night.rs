//! Night mode: align a hand-held burst to the sharpest frame (ORB + RANSAC
//! homography) and robustly average with deghosting to reduce noise.

use crate::features::{detect_and_describe, match_features};
use crate::geometry::{find_homography_ransac, Pt};
use crate::imgbuf::{to_gray, Gray, Rgba};
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
/// Deghost ceiling at full luma: sum |Rdiff|+|Gdiff|+|Bdiff| above -> ghost.
const GHOST_THRESH_SUM: i32 = 110;
/// Fraction of `GHOST_THRESH_SUM` still allowed at zero luma.
///
/// The deghost allowance scales with reference luma (a Weber-like relative
/// contrast rule: bright pixels can differ by more in absolute counts for the
/// same perceived change). Without a floor, near-black pixels would have a
/// ~0 allowance and ordinary read noise / bilinear resampling error (a few
/// counts per channel) would reject every contributing sample, eating the
/// dark-region signal the burst exists to recover. 0.25 gives 28 counts
/// summed over RGB (~9/channel) at black: above that noise floor, well below
/// a moving-edge step (typically >=15/channel, >=45 summed).
const GHOST_DARK_FLOOR_FRAC: f32 = 0.25;

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

/// Variance of the 4-neighbour Laplacian of a gray image: the classic
/// focus measure (Pech-Pacheco et al.). Sharp images have strong, varied
/// second derivatives; defocus/motion blur is a low-pass that collapses
/// both the magnitude and the spread, so variance (not just mean |Lap|)
/// separates them and is invariant to a constant brightness offset.
/// Borders are excluded (no full neighbourhood); <3x3 images score 0.
fn laplacian_variance(gray: &Gray) -> f64 {
    if gray.w < 3 || gray.h < 3 {
        return 0.0;
    }
    let (w, h) = (gray.w, gray.h);
    let mut sum = 0f64;
    let mut sum_sq = 0f64;
    for y in 1..h - 1 {
        for x in 1..w - 1 {
            let c = gray.px[y * w + x] as i32;
            let lap = 4 * c
                - gray.px[y * w + x - 1] as i32
                - gray.px[y * w + x + 1] as i32
                - gray.px[(y - 1) * w + x] as i32
                - gray.px[(y + 1) * w + x] as i32;
            let v = lap as f64;
            sum += v;
            sum_sq += v * v;
        }
    }
    let n = ((w - 2) * (h - 2)) as f64;
    let mean = sum / n;
    (sum_sq / n - mean * mean).max(0.0)
}

/// Index of the sharpest gray frame; ties keep the earliest frame (a flat
/// burst has no sharpness signal, and frame order is the least surprising
/// fallback).
fn pick_sharpest_index(grays: &[Gray]) -> usize {
    let mut best = 0usize;
    let mut best_score = f64::NEG_INFINITY;
    for (i, g) in grays.iter().enumerate() {
        let score = laplacian_variance(g);
        if score > best_score {
            best_score = score;
            best = i;
        }
    }
    best
}

/// Luma-adaptive deghost allowance (summed absolute RGB difference).
/// Scales `GHOST_THRESH_SUM` linearly from the dark floor at black to the
/// full legacy value at white, using Rec.601 luma (same weights as
/// `to_gray`).
fn ghost_threshold_sum(ref_px: [u8; 4]) -> i32 {
    let luma = (ref_px[0] as u32 * 77 + ref_px[1] as u32 * 150 + ref_px[2] as u32 * 29) >> 8;
    let frac = GHOST_DARK_FLOOR_FRAC + (1.0 - GHOST_DARK_FLOOR_FRAC) * (luma as f32 / 255.0);
    (GHOST_THRESH_SUM as f32 * frac).round() as i32
}

/// Robust per-channel combination of the samples gathered at one pixel:
/// one sample is itself, two are averaged, three take the median (which
/// discards a single outlier outright), and four or more take a trimmed
/// mean dropping the min and max. Dropping only the extremes needs no
/// sort: the result is `(sum - min - max) / (count - 2)`, so gathering
/// stays O(count) with no per-pixel allocation or O(k log k) sort.
fn trimmed_mean(sum: f32, min: f32, max: f32, count: usize) -> f32 {
    match count {
        0 => 0.0,
        1 => sum,
        2 => sum * 0.5,
        3 => sum - min - max,
        _ => (sum - min - max) / (count - 2) as f32,
    }
}

/// Merge frames already related to `frames[ref_idx]` by `inv_homographies`
/// (entry `i` maps reference coordinates into frame `i`'s coordinates;
/// `None`, and the reference itself, need no warp). Split from
/// `align_and_merge` so the merge semantics are testable with identity
/// warps, without depending on feature detection succeeding.
fn merge_with_homographies(
    frames: &[Rgba],
    ref_idx: usize,
    inv_homographies: &[Option<Matrix3<f64>>],
) -> Option<Rgba> {
    let reff = frames.get(ref_idx)?;
    let (w, h) = (reff.w, reff.h);
    if w == 0 || h == 0 {
        return None;
    }
    let mut out = Rgba::new(w, h);
    for y in 0..h {
        for x in 0..w {
            let rp = reff.get(x, y);
            let thresh = ghost_threshold_sum(rp);
            // Per-channel running sum/min/max/count for the trimmed mean.
            let mut sum = [rp[0] as f32, rp[1] as f32, rp[2] as f32];
            let mut min = sum;
            let mut max = sum;
            let mut count = 1usize;
            for (i, f) in frames.iter().enumerate() {
                if i == ref_idx {
                    continue;
                }
                let Some(h_inv) = inv_homographies.get(i).copied().flatten() else {
                    continue;
                };
                let (sx, sy) = apply(&h_inv, x as f64, y as f64);
                let Some(c) = f.sample(sx as f32, sy as f32) else {
                    continue;
                };
                // Deghost: skip samples far from the reference so a moving
                // object (or a flash) cannot smear into the stack. The
                // allowance is luma-adaptive; see `ghost_threshold_sum`.
                let diff = (c[0] - rp[0] as f32).abs()
                    + (c[1] - rp[1] as f32).abs()
                    + (c[2] - rp[2] as f32).abs();
                if diff > thresh as f32 {
                    continue;
                }
                for ch in 0..3 {
                    sum[ch] += c[ch];
                    min[ch] = min[ch].min(c[ch]);
                    max[ch] = max[ch].max(c[ch]);
                }
                count += 1;
            }
            let mut px = [0u8; 4];
            for ch in 0..3 {
                px[ch] = trimmed_mean(sum[ch], min[ch], max[ch], count)
                    .round()
                    .clamp(0.0, 255.0) as u8;
            }
            px[3] = 255;
            out.set(x, y, px);
        }
    }
    Some(out)
}

pub fn align_and_merge(frames: &[Rgba]) -> Option<Rgba> {
    let n = frames.len();
    if n == 0 {
        return None;
    }
    if n == 1 {
        return Some(frames[0].clone());
    }
    if frames[0].w == 0 || frames[0].h == 0 {
        return None;
    }

    // Registration-resolution copies of every frame. Sharpness is judged
    // at this resolution (the frames registration actually sees): full-res
    // sensor noise inflates Laplacian variance roughly uniformly and would
    // cost a full-res pass per frame, while blur that matters for choosing
    // a reference survives the downscale to ~0.8 MP.
    let area = frames[0].w as f64 * frames[0].h as f64;
    let reg_scale = (REG_MAX_MP * 1e6 / area).sqrt().min(1.0);
    let mut smalls: Vec<Rgba> = Vec::with_capacity(n);
    let mut small_grays: Vec<Gray> = Vec::with_capacity(n);
    for f in frames {
        let small = if reg_scale < 0.999 {
            let rw = ((f.w as f64 * reg_scale).round() as usize).max(1);
            let rh = ((f.h as f64 * reg_scale).round() as usize).max(1);
            f.resized(rw, rh)
        } else {
            f.clone()
        };
        small_grays.push(to_gray(&small));
        smalls.push(small);
    }

    // Sharpest-frame reference, chosen inside the merge so the Kotlin/JNI
    // session API (newNightSession/addNightRgbaFrame/mergeNight) needs no
    // new call: by merge time every frame is already here.
    let ref_idx = pick_sharpest_index(&small_grays);
    let reff = &frames[ref_idx];
    if reff.w == 0 || reff.h == 0 {
        return None;
    }
    let ref_feat = detect_and_describe(&small_grays[ref_idx], MAX_FEATURES, FAST_THRESHOLD);

    // Estimate each other frame's warp into reference coordinates.
    let mut inv_homographies: Vec<Option<Matrix3<f64>>> = vec![None; n];
    for (i, f) in frames.iter().enumerate() {
        if i == ref_idx {
            continue;
        }
        let feat = detect_and_describe(&small_grays[i], MAX_FEATURES, FAST_THRESHOLD);
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
        let s = if f.w > 0 { smalls[i].w as f64 / f.w as f64 } else { 1.0 };
        let h_to_ref = scale_homography(&h_small, s);
        if let Some(h_inv) = h_to_ref.try_inverse() {
            inv_homographies[i] = Some(h_inv);
        }
    }

    merge_with_homographies(frames, ref_idx, &inv_homographies)
}

#[cfg(test)]
#[path = "night_tests.rs"]
mod night_tests;
