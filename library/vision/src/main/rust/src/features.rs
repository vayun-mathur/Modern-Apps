//! Oriented-FAST + rotated-BRIEF (ORB-style) with image pyramid,
//! matching OpenCV's ORB::create defaults from features2d.hpp:460:
//!   nfeatures=500 scaleFactor=1.2 nlevels=8 edgeThreshold=31 firstLevel=0
//!   WTA_K=2 HARRIS_SCORE patchSize=31 fastThreshold=20
//! Distribution per level weighted by scale^1/sum (like OpenCV computeKeyPoints
//! ndesiredFeaturesPerScale = nfeatures*(1-factor)/(1-factor^nlevels)), top Harris
//! per level. Keeps single-scale fallback for tiny inputs.

use crate::imgbuf::{Gray, harris_scores};
use std::cmp::Reverse;

pub struct KeyPoint {
    pub x: f32,
    pub y: f32,
    pub angle: f32,
    pub score: f32, // Harris response used for ranking
}

pub struct Features {
    pub kps: Vec<KeyPoint>,
    pub desc: Vec<[u8; 32]>, // 256-bit BRIEF
    pub w: usize,           // original (full) image width for matcher centering
    pub h: usize,
}

/// ORB create defaults matching OpenCV
pub const ORB_DEFAULT_NFEATURES: usize = 500;
pub const ORB_SCALE_FACTOR: f32 = 1.2;
pub const ORB_NLEVELS: usize = 8;
pub const ORB_EDGE_THRESHOLD: i32 = 31;
pub const ORB_PATCH_SIZE: i32 = 31;
pub const ORB_FAST_THRESHOLD: i32 = 20;

// Bresenham circle of radius 3 (16 pixels), clockwise from top.
const CIRCLE: [(i32, i32); 16] = [
    (0, -3), (1, -3), (2, -2), (3, -1), (3, 0), (3, 1), (2, 2), (1, 3),
    (0, 3), (-1, 3), (-2, 2), (-3, 1), (-3, 0), (-3, -1), (-2, -2), (-1, -3),
];

const PATCH_R: i32 = ORB_PATCH_SIZE / 2; // 15 for 31 patch
const BRIEF_SPAN: i32 = 13; // keeps pairs inside 31x31 patch

/// Deterministic BRIEF sampling pattern: 256 point pairs within the patch.
/// OpenCV makeRandomPattern uses RNG uniform(-patchSize/2, patchSize/2+1)
fn brief_pattern() -> Vec<((i32, i32), (i32, i32))> {
    let mut state: u64 = 0x9E3779B97F4A7C15;
    let mut next = || {
        state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((state >> 33) as i64) as i32
    };
    let span = BRIEF_SPAN;
    let mut pairs = Vec::with_capacity(256);
    for _ in 0..256 {
        let ax = (next().rem_euclid(2 * span + 1)) - span;
        let ay = (next().rem_euclid(2 * span + 1)) - span;
        let bx = (next().rem_euclid(2 * span + 1)) - span;
        let by = (next().rem_euclid(2 * span + 1)) - span;
        pairs.push(((ax, ay), (bx, by)));
    }
    pairs
}

#[inline]
fn fast_score(g: &Gray, x: i32, y: i32) -> i32 {
    let c = g.at(x as usize, y as usize) as i32;
    let mut s = 0;
    for &(dx, dy) in CIRCLE.iter() {
        let p = g.at((x + dx) as usize, (y + dy) as usize) as i32;
        s += (p - c).abs();
    }
    s
}

#[inline]
fn is_corner(g: &Gray, x: i32, y: i32, t: i32) -> bool {
    let c = g.at(x as usize, y as usize) as i32;
    // High-speed rejection test on pixels 1,5,9,13 (indices 0,4,8,12).
    let p0 = g.at((x + CIRCLE[0].0) as usize, (y + CIRCLE[0].1) as usize) as i32;
    let p8 = g.at((x + CIRCLE[8].0) as usize, (y + CIRCLE[8].1) as usize) as i32;
    let p4 = g.at((x + CIRCLE[4].0) as usize, (y + CIRCLE[4].1) as usize) as i32;
    let p12 = g.at((x + CIRCLE[12].0) as usize, (y + CIRCLE[12].1) as usize) as i32;
    let brighter = |p: i32| p > c + t;
    let darker = |p: i32| p < c - t;
    let nb = brighter(p0) as i32 + brighter(p4) as i32 + brighter(p8) as i32 + brighter(p12) as i32;
    let nd = darker(p0) as i32 + darker(p4) as i32 + darker(p8) as i32 + darker(p12) as i32;
    if nb < 3 && nd < 3 {
        return false;
    }
    // Full contiguous-9 test around the ring (wrap-around).
    let mut vals = [0i32; 16];
    for k in 0..16 {
        vals[k] = g.at((x + CIRCLE[k].0) as usize, (y + CIRCLE[k].1) as usize) as i32;
    }
    for start in 0..16 {
        let mut all_b = true;
        let mut all_d = true;
        for j in 0..9 {
            let v = vals[(start + j) % 16];
            if v <= c + t { all_b = false; }
            if v >= c - t { all_d = false; }
        }
        if all_b || all_d {
            return true;
        }
    }
    false
}

fn orientation(g: &Gray, x: i32, y: i32) -> f32 {
    // Intensity centroid in circular patch radius 15 (ORB_ICAngle)
    let mut m01 = 0i64;
    let mut m10 = 0i64;
    for dy in -PATCH_R..=PATCH_R {
        for dx in -PATCH_R..=PATCH_R {
            if dx * dx + dy * dy > PATCH_R * PATCH_R {
                continue;
            }
            let v = g.at((x + dx) as usize, (y + dy) as usize) as i64;
            m10 += dx as i64 * v;
            m01 += dy as i64 * v;
        }
    }
    (m01 as f32).atan2(m10 as f32)
}

/// Single-level detection: FAST corners, grid NMS, Harris scoring.
fn detect_level(g: &Gray, threshold: i32, edge_thresh: i32, max_per_level: usize) -> Vec<(i32, i32, f32)> {
    // FAST detection
    let border = (PATCH_R + 1).max(edge_thresh + 1);
    if g.w as i32 <= 2 * border || g.h as i32 <= 2 * border {
        return Vec::new();
    }
    let mut cand: Vec<(i32, i32)> = Vec::new();
    for y in border..(g.h as i32 - border) {
        for x in border..(g.w as i32 - border) {
            if is_corner(g, x, y, threshold) {
                cand.push((x, y));
            }
        }
    }
    if cand.is_empty() {
        return Vec::new();
    }
    // Grid-bucketed NMS first to avoid clustering, then Harris scoring top N
    let cell = 8i32;
    let grid_w = (g.w as i32 / cell) + 1;
    let grid_h = (g.h as i32 / cell) + 1;
    let mut occupied = vec![false; (grid_w * grid_h) as usize];
    let mut filtered: Vec<(i32, i32)> = Vec::new();
    // Sort by fast_score for grid distribution
    let mut with_score: Vec<(i32, i32, i32)> = cand.iter().map(|&(x, y)| (x, y, fast_score(g, x, y))).collect();
    with_score.sort_by_key(|&(_, _, s)| Reverse(s));
    for (x, y, _s) in with_score {
        let cx = x / cell;
        let cy = y / cell;
        let idx = (cy * grid_w + cx) as usize;
        if occupied[idx] {
            continue;
        }
        occupied[idx] = true;
        filtered.push((x, y));
    }
    // Harris response scoring (HARRIS_BLOCK_SIZE 7, K 0.04) – matches OpenCV
    let h_scores = harris_scores(g, &filtered);
    let mut scored: Vec<(i32, i32, f32)> = filtered.into_iter().zip(h_scores).map(|((x, y), s)| (x, y, s)).collect();
    scored.sort_by(|a, b| b.2.total_cmp(&a.2));
    if scored.len() > max_per_level {
        scored.truncate(max_per_level);
    }
    scored
}

pub fn detect_and_describe(g: &Gray, max_features: usize, threshold: i32) -> Features {
    detect_and_describe_orb(g, max_features, threshold)
}

/// ORB pyramid detection matching OpenCV's detectAndCompute:
///
/// - Builds nlevels pyramid scaleFactor 1.2
/// - Edge threshold 31 border, FAST threshold 20
/// - Distributes nfeatures across levels via ndesired = nfeatures*(1-factor)/(1-factor^nlevels)
/// - Gaussian blur per level (cheap 3x3)
/// - BRIEF on smoothed patch, rotated by orientation
///
/// Keeps same signature (max_features, threshold) for compatibility but uses ORB defaults
/// internally; max_features caps at input value (3000), distributed.
pub fn detect_and_describe_orb(g: &Gray, max_features: usize, threshold: i32) -> Features {
    let (orig_w, orig_h) = (g.w, g.h);
    let fast_thr = threshold.clamp(1, 100); // use caller's threshold but clamp

    // Build pyramid: level 0 = original, each level downscaled by scaleFactor
    let nlevels = ORB_NLEVELS.clamp(1, 8);
    let scale_factor = ORB_SCALE_FACTOR;

    let mut pyramid: Vec<Gray> = Vec::with_capacity(nlevels);
    let mut scales: Vec<f32> = Vec::with_capacity(nlevels);
    pyramid.push(g.clone());
    scales.push(1.0);

    let mut cur_w = orig_w as f32;
    let mut cur_h = orig_h as f32;
    for level in 1..nlevels {
        cur_w /= scale_factor;
        cur_h /= scale_factor;
        let nw = cur_w.round() as usize;
        let nh = cur_h.round() as usize;
        if nw < ORB_EDGE_THRESHOLD as usize * 2 + 10 || nh < ORB_EDGE_THRESHOLD as usize * 2 + 10 {
            break;
        }
        let prev = &pyramid[level - 1];
        let resized = prev.resized(nw, nh);
        // Gaussian blur before FAST (OpenCV does PyrDown which blurs)
        let blurred = resized.gaussian_blur_3x3();
        pyramid.push(blurred);
        scales.push(scale_factor.powi(level as i32));
    }

    let actual_levels = pyramid.len();
    // Desired features per level: like ORB_Impl computeKeyPoints
    // factor = 1/scaleFactor, ndesiredPerScale = nfeatures*(1-factor)/(1-factor^nlevels)
    let factor = 1.0 / scale_factor as f64;
    let nfeatures = max_features.max(1);
    let denom = 1.0 - factor.powi(actual_levels as i32);
    let ndesired_per_scale = if denom.abs() > 1e-9 {
        nfeatures as f64 * (1.0 - factor) / denom
    } else {
        nfeatures as f64 / actual_levels as f64
    };

    let mut nfeatures_per_level = Vec::with_capacity(actual_levels);
    let mut sum = 0usize;
    for _level in 0..actual_levels - 1 {
        let cnt = ndesired_per_scale.round() as usize;
        nfeatures_per_level.push(cnt);
        sum += cnt;
    }
    let last = nfeatures.saturating_sub(sum);
    nfeatures_per_level.push(last);

    // Detect per level
    let pattern = brief_pattern();
    let mut all_kps: Vec<KeyPoint> = Vec::with_capacity(nfeatures);
    let mut all_desc: Vec<[u8; 32]> = Vec::with_capacity(nfeatures);

    for (level, pg) in pyramid.iter().enumerate() {
        let wanted = nfeatures_per_level[level];
        if wanted == 0 {
            continue;
        }
        let level_pts = detect_level(pg, fast_thr, ORB_EDGE_THRESHOLD, wanted * 2);
        // Trim to wanted after Harris already sorted inside detect_level
        let keep = level_pts.into_iter().take(wanted).collect::<Vec<_>>();
        let scale = scales[level];
        for (x, y, harris) in keep {
            // Orientation in pyramid coords, then remap to original scale for kp pos
            let angle = orientation(pg, x, y);
            let (sin, cos) = angle.sin_cos();
            let mut d = [0u8; 32];
            for (bit, &((ax, ay), (bx, by))) in pattern.iter().enumerate() {
                let rax = (ax as f32 * cos - ay as f32 * sin).round() as i32;
                let ray = (ax as f32 * sin + ay as f32 * cos).round() as i32;
                let rbx = (bx as f32 * cos - by as f32 * sin).round() as i32;
                let rby = (bx as f32 * sin + by as f32 * cos).round() as i32;
                let pa = sample_clamped(pg, x + rax, y + ray);
                let pb = sample_clamped(pg, x + rbx, y + rby);
                if pa < pb {
                    d[bit / 8] |= 1 << (bit % 8);
                }
            }
            // Remap kp to original image coords
            let orig_x = x as f32 * scale;
            let orig_y = y as f32 * scale;
            all_kps.push(KeyPoint { x: orig_x, y: orig_y, angle, score: harris });
            all_desc.push(d);
        }
    }

    // Global top-Harris cap to max_features
    if all_kps.len() > nfeatures {
        let mut idx: Vec<usize> = (0..all_kps.len()).collect();
        idx.sort_by(|&a, &b| all_kps[b].score.total_cmp(&all_kps[a].score));
        idx.truncate(nfeatures);
        idx.sort_unstable();
        let kps: Vec<KeyPoint> = idx.iter().map(|&i| KeyPoint { x: all_kps[i].x, y: all_kps[i].y, angle: all_kps[i].angle, score: all_kps[i].score }).collect();
        let desc: Vec<[u8; 32]> = idx.iter().map(|&i| all_desc[i]).collect();
        return Features { kps, desc, w: orig_w, h: orig_h };
    }

    Features { kps: all_kps, desc: all_desc, w: orig_w, h: orig_h }
}

#[inline]
fn sample_clamped(g: &Gray, x: i32, y: i32) -> i32 {
    let xx = x.clamp(0, g.w as i32 - 1) as usize;
    let yy = y.clamp(0, g.h as i32 - 1) as usize;
    g.at(xx, yy) as i32
}

#[inline]
fn hamming(a: &[u8; 32], b: &[u8; 32]) -> u32 {
    let mut d = 0u32;
    for i in 0..32 {
        d += (a[i] ^ b[i]).count_ones();
    }
    d
}

/// Brute-force match a->b with Lowe ratio test. Returns (index_in_a, index_in_b).
pub fn match_features(a: &Features, b: &Features, ratio: f32) -> Vec<(usize, usize)> {
    let mut out = Vec::new();
    if a.desc.is_empty() || b.desc.is_empty() {
        return out;
    }
    for (i, da) in a.desc.iter().enumerate() {
        let mut best = u32::MAX;
        let mut second = u32::MAX;
        let mut best_j = usize::MAX;
        for (j, db) in b.desc.iter().enumerate() {
            let d = hamming(da, db);
            if d < best {
                second = best;
                best = d;
                best_j = j;
            } else if d < second {
                second = d;
            }
        }
        if best_j != usize::MAX && (best as f32) < ratio * (second as f32) {
            out.push((i, best_j));
        }
    }
    out
}

#[cfg(test)] #[path = "features_tests.rs"] mod features_tests;
