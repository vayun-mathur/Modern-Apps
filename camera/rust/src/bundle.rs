//! BundleAdjusterRay with geodesic Levenberg–Marquardt, using **block-sparse**
//! normal equations exactly as the structure demands: each correspondence's
//! residual depends only on its two cameras' 8 parameters, so JᵀJ and Jᵀr are
//! accumulated from per-edge 8-column blocks instead of a dense
//! (3·matches × 4·images) Jacobian. Rotations are optimized on SO(3)
//! (R ← exp(ω)·R, tangent re-linearized to 0 each iteration).

use crate::camera::{rodrigues_to_mat, CameraParams};
use crate::geometry::Pt;
use crate::matching::MatchInfo;
use nalgebra::{DMatrix, DVector, Matrix3, Vector3};
use rayon::prelude::*;

const CONF_THRESH: f64 = 1.0;
const MAX_ITERS: usize = 100;
const STEP: f64 = 1e-4;

struct Edge {
    i: usize,
    j: usize,
    corr: Vec<(Pt, Pt)>,
}

fn k_inv(focal: f64, ppx: f64, ppy: f64) -> Matrix3<f64> {
    Matrix3::new(
        1.0 / focal, 0.0, -ppx / focal,
        0.0, 1.0 / focal, -ppy / focal,
        0.0, 0.0, 1.0,
    )
}

/// A camera perturbed by a single tangent parameter (0 = focal, 1..3 = ω axis).
fn perturb(base: &CameraParams, j: usize, delta: f64) -> CameraParams {
    if j == 0 {
        CameraParams { focal: base.focal + delta, ..base.clone() }
    } else {
        let mut omega = Vector3::zeros();
        omega[j - 1] = delta;
        CameraParams { r: rodrigues_to_mat(omega) * base.r, ..base.clone() }
    }
}

/// Whole-camera geodesic increment: f += δf, R ← exp(ω)·R.
fn apply_increment(base: &[CameraParams], x: &DVector<f64>) -> Vec<CameraParams> {
    base.iter()
        .enumerate()
        .map(|(c, cam)| {
            let omega = Vector3::new(x[4 * c + 1], x[4 * c + 2], x[4 * c + 3]);
            CameraParams {
                focal: cam.focal + x[4 * c],
                aspect: 1.0,
                ppx: cam.ppx,
                ppy: cam.ppy,
                r: rodrigues_to_mat(omega) * cam.r,
            }
        })
        .collect()
}

/// Ray residuals (3 per correspondence) for one camera pair.
fn edge_res(ci: &CameraParams, cj: &CameraParams, corr: &[(Pt, Pt)]) -> Vec<f64> {
    let h1 = ci.r * k_inv(ci.focal, ci.ppx, ci.ppy);
    let h2 = cj.r * k_inv(cj.focal, cj.ppx, cj.ppy);
    let mult = (ci.focal * cj.focal).abs().sqrt();
    let mut out = vec![0.0f64; corr.len() * 3];
    for (idx, &(p1, p2)) in corr.iter().enumerate() {
        let mut r1 = h1 * Vector3::new(p1.0 as f64, p1.1 as f64, 1.0);
        let l1 = r1.norm();
        if l1 > 1e-12 {
            r1 /= l1;
        }
        let mut r2 = h2 * Vector3::new(p2.0 as f64, p2.1 as f64, 1.0);
        let l2 = r2.norm();
        if l2 > 1e-12 {
            r2 /= l2;
        }
        out[3 * idx] = mult * (r1.x - r2.x);
        out[3 * idx + 1] = mult * (r1.y - r2.y);
        out[3 * idx + 2] = mult * (r1.z - r2.z);
    }
    out
}

fn total_cost(cams: &[CameraParams], edges: &[Edge]) -> f64 {
    edges
        .par_iter()
        .map(|e| edge_res(&cams[e.i], &cams[e.j], &e.corr).iter().map(|v| v * v).sum::<f64>())
        .sum()
}

pub fn bundle_adjust(cams: &mut Vec<CameraParams>, matches: &[MatchInfo]) {
    let n = cams.len();
    if n < 2 {
        return;
    }
    let edges: Vec<Edge> = matches
        .iter()
        .filter(|m| m.confidence > CONF_THRESH && !m.inliers.is_empty())
        .map(|m| Edge { i: m.src, j: m.dst, corr: m.inliers.clone() })
        .collect();
    if edges.is_empty() {
        return;
    }
    let nparams = 4 * n;
    let mut cost = total_cost(cams, &edges);
    let mut lambda = 1.0;

    for _ in 0..MAX_ITERS {
        // Block-sparse normal equations, accumulated per edge in parallel.
        let (jtj, jtr) = edges
            .par_iter()
            .map(|e| {
                let ca = &cams[e.i];
                let cb = &cams[e.j];
                let r = edge_res(ca, cb, &e.corr);
                let m = r.len();
                // 8 local Jacobian columns (cam i: 0..4, cam j: 4..8).
                let mut jb: Vec<Vec<f64>> = Vec::with_capacity(8);
                for c in 0..8 {
                    let (pj, is_i) = (c % 4, c < 4);
                    let (rp, rm) = if is_i {
                        (edge_res(&perturb(ca, pj, STEP), cb, &e.corr),
                         edge_res(&perturb(ca, pj, -STEP), cb, &e.corr))
                    } else {
                        (edge_res(ca, &perturb(cb, pj, STEP), &e.corr),
                         edge_res(ca, &perturb(cb, pj, -STEP), &e.corr))
                    };
                    let mut col = vec![0.0f64; m];
                    for t in 0..m {
                        col[t] = (rp[t] - rm[t]) / (2.0 * STEP);
                    }
                    jb.push(col);
                }
                let gcol = [
                    4 * e.i, 4 * e.i + 1, 4 * e.i + 2, 4 * e.i + 3,
                    4 * e.j, 4 * e.j + 1, 4 * e.j + 2, 4 * e.j + 3,
                ];
                let mut jtj = DMatrix::<f64>::zeros(nparams, nparams);
                let mut jtr = DVector::<f64>::zeros(nparams);
                for c1 in 0..8 {
                    let g1 = gcol[c1];
                    let mut s = 0.0;
                    for t in 0..m {
                        s += jb[c1][t] * r[t];
                    }
                    jtr[g1] += s;
                    for c2 in 0..8 {
                        let g2 = gcol[c2];
                        let mut s2 = 0.0;
                        for t in 0..m {
                            s2 += jb[c1][t] * jb[c2][t];
                        }
                        jtj[(g1, g2)] += s2;
                    }
                }
                (jtj, jtr)
            })
            .reduce(
                || (DMatrix::<f64>::zeros(nparams, nparams), DVector::<f64>::zeros(nparams)),
                |(mut a, mut b), (ja, jb)| {
                    a += ja;
                    b += jb;
                    (a, b)
                },
            );

        let mut improved = false;
        for _ in 0..10 {
            let mut a = jtj.clone();
            for d in 0..nparams {
                let diag = a[(d, d)];
                a[(d, d)] = diag + lambda * diag.max(1e-6);
            }
            let dx = match a.clone().lu().solve(&(-&jtr)) {
                Some(v) => v,
                None => break,
            };
            let trial = apply_increment(cams, &dx);
            let cost_new = total_cost(&trial, &edges);
            if cost_new < cost {
                *cams = trial;
                cost = cost_new;
                lambda = (lambda * 0.1).max(1e-12);
                improved = true;
                break;
            } else {
                lambda = (lambda * 10.0).min(1e12);
            }
        }
        if !improved {
            break;
        }
    }
}
