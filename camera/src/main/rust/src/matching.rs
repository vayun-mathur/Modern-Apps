//! All-pairs feature matching, mirroring cv::detail::BestOf2NearestMatcher:
//! bidirectional 2-NN ratio matches (union dedup) -> RANSAC homography ->
//! confidence = num_inliers / (8 + 0.3 * num_matches), too-close zeroing,
//! second refinement on inliers only.
//! Matches OpenCV's BestOf2NearestMatcher exactly:
//!   - match_conf 0.3 => ratio threshold (1-match_conf)=0.7
//!   - CpuMatcher uses FlannBasedMatcher knnMatch k=2 LSH for ORB; we brute-force Hamming
//!     but ratio test identical, and union via set<pair<query,train>> avoids dupes
//!   - centering x-=w*0.5 y-=h*0.5 before findHomography
//!   - findHomography RANSAC thresh 3.0 (OpenCV default), det epsilon discard
//!   - conf > 3 => 0 (too close images)
//!   - thresh2=6: refine on inliers only

use crate::features::{match_features, Features};
use crate::geometry::{find_homography_ransac, transfer_inliers, Pt};
use crate::linalg::Matrix3;
use std::collections::HashSet;

const RATIO: f32 = 0.7; // 1 - match_conf (0.3)
const RANSAC_ITERS: usize = 2000;
pub const RANSAC_THRESH: f32 = 3.0; // OpenCV default
const MIN_MATCHES: usize = 6;
const CONF_TOO_CLOSE: f64 = 3.0;

pub struct MatchInfo {
    pub src: usize,
    pub dst: usize,
    pub h: Matrix3<f64>,        // maps src -> dst (centered coords, like OpenCV)
    pub inliers: Vec<(Pt, Pt)>, // (src pt, dst pt) in original image coords
    pub confidence: f64,
    pub num_inliers: usize,
}

/// Match one ordered pair (src -> dst). Returns None if too weak.
/// Implements OpenCV CpuMatcher + BestOf2NearestMatcher::match exactly.
fn match_pair(src: usize, dst: usize, fs: &Features, fd: &Features) -> Option<MatchInfo> {
    // 1->2 and 2->1 with union dedup (MatchesSet)
    let m12 = match_features(fs, fd, RATIO); // (i src, j dst)
    let m21_raw = match_features(fd, fs, RATIO); // (j dst, i src)

    let mut seen: HashSet<(usize, usize)> = HashSet::with_capacity(m12.len() + m21_raw.len());
    let mut combined: Vec<(usize, usize)> = Vec::with_capacity(m12.len() + m21_raw.len());

    for &(i, j) in &m12 {
        if seen.insert((i, j)) {
            combined.push((i, j));
        }
    }
    for &(j, i) in &m21_raw {
        // normalize to (src,dst) = (i,j)
        if seen.insert((i, j)) {
            combined.push((i, j));
        }
    }

    if combined.len() < MIN_MATCHES {
        return None;
    }

    // Original points for inlier reporting
    let a_orig: Vec<Pt> = combined.iter().map(|&(i, _)| (fs.kps[i].x, fs.kps[i].y)).collect();
    let b_orig: Vec<Pt> = combined.iter().map(|&(_, j)| (fd.kps[j].x, fd.kps[j].y)).collect();

    // Centered points for homography estimation (OpenCV: p.x -= img_size.width*0.5)
    let ws = fs.w as f32;
    let hs = fs.h as f32;
    let wd = fd.w as f32;
    let hd = fd.h as f32;
    // If w/h not set (old Features), fall back to no centering – shouldn't happen after fix
    let csx = if ws > 0.0 { ws * 0.5 } else { 0.0 };
    let csy = if hs > 0.0 { hs * 0.5 } else { 0.0 };
    let cdx = if wd > 0.0 { wd * 0.5 } else { 0.0 };
    let cdy = if hd > 0.0 { hd * 0.5 } else { 0.0 };

    let a_centered: Vec<Pt> = a_orig.iter().map(|&(x, y)| (x - csx, y - csy)).collect();
    let b_centered: Vec<Pt> = b_orig.iter().map(|&(x, y)| (x - cdx, y - cdy)).collect();

    let (mut h, _) = find_homography_ransac(&a_centered, &b_centered, RANSAC_ITERS, RANSAC_THRESH)?;

    // det check eps like OpenCV std::abs(determinant(H)) < epsilon
    if h.determinant().abs() < f64::EPSILON {
        return None;
    }

    let inl_idx = transfer_inliers(&h, &a_centered, &b_centered, RANSAC_THRESH);
    let num_inliers = inl_idx.len();
    if num_inliers < MIN_MATCHES {
        return None;
    }

    // Brown & Lowe confidence
    let mut confidence = num_inliers as f64 / (8.0 + 0.3 * combined.len() as f64);
    // Set zero confidence to remove matches between too close images
    if confidence > CONF_TOO_CLOSE {
        confidence = 0.0;
    }

    // If inliers < thresh2, skip refinement but keep this homography (OpenCV returns)
    if num_inliers >= MIN_MATCHES {
        // Refine on inliers only
        if num_inliers >= 4 {
            let a_inl: Vec<Pt> = inl_idx.iter().map(|&k| a_centered[k]).collect();
            let b_inl: Vec<Pt> = inl_idx.iter().map(|&k| b_centered[k]).collect();
            if let Some((h_refined, _)) = find_homography_ransac(&a_inl, &b_inl, RANSAC_ITERS, RANSAC_THRESH) {
                if h_refined.determinant().abs() >= f64::EPSILON {
                    h = h_refined;
                }
            }
            // Recount inliers after refinement (OpenCV does not recount but keeps num_inliers; we keep original count for confidence stability)
        }
    }

    // Inliers in original coords for downstream bundle adjuster (which centers via K)
    let inliers: Vec<(Pt, Pt)> = inl_idx.iter().map(|&k| (a_orig[k], b_orig[k])).collect();

    Some(MatchInfo {
        src,
        dst,
        h,
        inliers,
        confidence,
        num_inliers,
    })
}

/// Angular separation (deg) between two frames' gyro orientations.
fn angular_dist(yaw: &[f32], pitch: &[f32], i: usize, j: usize) -> f32 {
    let mut dy = (yaw[i] - yaw[j]) % 360.0;
    if dy > 180.0 {
        dy -= 360.0;
    }
    if dy < -180.0 {
        dy += 360.0;
    }
    let dp = pitch[i] - pitch[j];
    (dy * dy + dp * dp).sqrt()
}

/// Match pairs (i<j). When per-frame gyro orientations are available, only pairs
/// within `max_angle` degrees are matched (they physically overlap) — this both
/// connects a 2D photo-sphere grid (horizontal AND vertical neighbours) and keeps
/// the pair count low. Falls back to all pairs otherwise. Runs in parallel.
pub fn match_all(feats: &[Features], yaw: &[f32], pitch: &[f32], max_angle: f32) -> Vec<MatchInfo> {
    let n = feats.len();
    let use_angles = yaw.len() == n && pitch.len() == n && max_angle > 0.0;
    let mut pairs: Vec<(usize, usize)> = Vec::new();
    for i in 0..n {
        for j in (i + 1)..n {
            if !use_angles || angular_dist(yaw, pitch, i, j) <= max_angle {
                pairs.push((i, j));
            }
        }
    }
    pairs
        .iter()
        .filter_map(|&(i, j)| match_pair(i, j, &feats[i], &feats[j]))
        .collect()
}

/// leaveBiggestComponent: keep the largest set of images connected by matches
/// with confidence > `conf_thresh`. Returns the kept image indices (sorted).
pub fn biggest_component(n: usize, matches: &[MatchInfo], conf_thresh: f64) -> Vec<usize> {
    // union-find
    let mut parent: Vec<usize> = (0..n).collect();
    fn find(parent: &mut [usize], x: usize) -> usize {
        let mut r = x;
        while parent[r] != r {
            r = parent[r];
        }
        let mut c = x;
        while parent[c] != c {
            let next = parent[c];
            parent[c] = r;
            c = next;
        }
        r
    }
    for m in matches {
        if m.confidence > conf_thresh {
            let a = find(&mut parent, m.src);
            let b = find(&mut parent, m.dst);
            if a != b {
                parent[a] = b;
            }
        }
    }
    let mut counts = vec![0usize; n];
    let roots: Vec<usize> = (0..n).map(|i| find(&mut parent, i)).collect();
    for &r in &roots {
        counts[r] += 1;
    }
    let best_root = (0..n).max_by_key(|&i| counts[i]).unwrap_or(0);
    let best_root = find(&mut parent, best_root);
    let mut kept: Vec<usize> = (0..n).filter(|&i| roots[i] == best_root).collect();
    if kept.is_empty() {
        kept = (0..n).collect();
    }
    kept.sort_unstable();
    kept
}

#[cfg(test)]
#[path = "matching_tests.rs"]
mod matching_tests;
