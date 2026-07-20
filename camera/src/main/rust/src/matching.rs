//! All-pairs feature matching, mirroring cv::detail::BestOf2NearestMatcher:
//! bidirectional 2-NN ratio matches (cross-checked) -> RANSAC homography ->
//! confidence = num_inliers / (8 + 0.3 * num_matches).

use crate::features::{match_features, Features};
use crate::geometry::{find_homography_ransac, transfer_inliers, Pt};
use nalgebra::Matrix3;
use rayon::prelude::*;
use std::collections::HashSet;

const RATIO: f32 = 0.85;
const RANSAC_ITERS: usize = 2000;
const RANSAC_THRESH: f32 = 5.0;
const MIN_MATCHES: usize = 6;

pub struct MatchInfo {
    pub src: usize,
    pub dst: usize,
    pub h: Matrix3<f64>,        // maps src -> dst
    pub inliers: Vec<(Pt, Pt)>, // (src pt, dst pt)
    pub confidence: f64,
}

/// Match one ordered pair (src -> dst). Returns None if too weak.
fn match_pair(src: usize, dst: usize, fs: &Features, fd: &Features) -> Option<MatchInfo> {
    let m12 = match_features(fs, fd, RATIO); // (i in src, j in dst)
    if m12.len() < MIN_MATCHES {
        return None;
    }
    let m21: HashSet<(usize, usize)> = match_features(fd, fs, RATIO)
        .into_iter()
        .map(|(j, i)| (i, j)) // normalize to (src, dst)
        .collect();
    // cross-check
    let mutual: Vec<(usize, usize)> = m12.into_iter().filter(|p| m21.contains(p)).collect();
    if mutual.len() < MIN_MATCHES {
        return None;
    }

    let a: Vec<Pt> = mutual.iter().map(|&(i, _)| (fs.kps[i].x, fs.kps[i].y)).collect();
    let b: Vec<Pt> = mutual.iter().map(|&(_, j)| (fd.kps[j].x, fd.kps[j].y)).collect();
    let (h, _n) = find_homography_ransac(&a, &b, RANSAC_ITERS, RANSAC_THRESH)?;
    let inl = transfer_inliers(&h, &a, &b, RANSAC_THRESH);
    let num_inliers = inl.len();
    if num_inliers < MIN_MATCHES {
        return None;
    }
    // Brown & Lowe confidence.
    let confidence = num_inliers as f64 / (8.0 + 0.3 * mutual.len() as f64);
    let inliers: Vec<(Pt, Pt)> = inl.iter().map(|&k| (a[k], b[k])).collect();
    Some(MatchInfo { src, dst, h, inliers, confidence })
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
        .par_iter()
        .filter_map(|&(i, j)| match_pair(i, j, &feats[i], &feats[j]))
        .collect()
}

/// leaveBiggestComponent: keep the largest set of images connected by matches
/// with confidence > `conf_thresh`. Returns the kept image indices (sorted).
pub fn biggest_component(n: usize, matches: &[MatchInfo], conf_thresh: f64) -> Vec<usize> {
    // union-find
    let mut parent: Vec<usize> = (0..n).collect();
    fn find(parent: &mut Vec<usize>, x: usize) -> usize {
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
