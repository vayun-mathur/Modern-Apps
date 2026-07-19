//! HomographyBasedEstimator (motion_estimators.cpp): estimate a shared focal
//! from pairwise homographies, then chain per-image rotations along a maximum
//! spanning tree of match confidences rooted at the central image.

use crate::camera::CameraParams;
use crate::matching::MatchInfo;
use crate::sphere::{estimate_focal, k_matrix, orthonormalize};
use nalgebra::Matrix3;

pub fn estimate_cameras(n: usize, w: usize, h: usize, matches: &[MatchInfo]) -> Vec<CameraParams> {
    let hs: Vec<Matrix3<f64>> = matches.iter().map(|m| m.h).collect();
    let focal = estimate_focal(&hs, w, h);
    let ppx = w as f64 / 2.0;
    let ppy = h as f64 / 2.0;
    let k = k_matrix(focal, ppx, ppy);
    let k_inv = k.try_inverse().unwrap_or_else(Matrix3::identity);

    // adjacency: node -> Vec<(neighbor, confidence, H_node->neighbor)>
    let mut adj: Vec<Vec<(usize, f64, Matrix3<f64>)>> = vec![Vec::new(); n];
    for m in matches {
        adj[m.src].push((m.dst, m.confidence, m.h));
        if let Some(hinv) = m.h.try_inverse() {
            adj[m.dst].push((m.src, m.confidence, hinv));
        }
    }

    let mut cams: Vec<CameraParams> = (0..n)
        .map(|_| CameraParams { focal, aspect: 1.0, ppx, ppy, r: Matrix3::identity() })
        .collect();

    // Prim's maximum spanning tree from the central node.
    let root = n / 2;
    let mut in_tree = vec![false; n];
    in_tree[root] = true;
    cams[root].r = Matrix3::identity();
    let mut added = 1;
    while added < n {
        // find the highest-confidence edge from a tree node to a non-tree node
        let mut best: Option<(usize, usize, Matrix3<f64>, f64)> = None; // (u, v, H_u->v, conf)
        for u in 0..n {
            if !in_tree[u] {
                continue;
            }
            for &(v, conf, huv) in &adj[u] {
                if in_tree[v] {
                    continue;
                }
                if best.map_or(true, |(_, _, _, bc)| conf > bc) {
                    best = Some((u, v, huv, conf));
                }
            }
        }
        match best {
            Some((u, v, huv, _)) => {
                // OpenCV CalcRotation: R = K_from^-1 * H_{from->to}^-1 * K_to;
                //                      cameras[to].R = cameras[from].R * R
                let rel = huv
                    .try_inverse()
                    .map(|hinv| k_inv * hinv * k)
                    .unwrap_or_else(Matrix3::identity);
                cams[v].r = orthonormalize(&(cams[u].r * rel));
                in_tree[v] = true;
                added += 1;
            }
            None => break, // disconnected remainder (shouldn't happen after biggest_component)
        }
    }
    cams
}
