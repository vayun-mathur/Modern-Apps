//! Exposure compensation. `compensate` implements BlocksGainCompensator
//! (exposure_compensate.cpp): each warped image is split into a block grid, a
//! per-block gain is solved from pairwise block overlaps (same normal equations
//! as GainCompensator but over blocks), and the block gains are bilinearly
//! upsampled to a smooth per-pixel gain map applied by the blender.

use crate::sphere::WarpedTile;
use nalgebra::{DMatrix, DVector};

const ALPHA: f64 = 0.01;
const BETA: f64 = 100.0;
const MAX_NODES: usize = 640; // bound the dense solve

#[inline]
fn luma(c: [u8; 4]) -> f64 {
    0.299 * c[0] as f64 + 0.587 * c[1] as f64 + 0.114 * c[2] as f64
}

/// Per-tile per-pixel gain maps (len = tile.img.w*tile.img.h each).
pub fn compensate(tiles: &[WarpedTile]) -> Vec<Vec<f32>> {
    let num = tiles.len();
    if num == 0 {
        return Vec::new();
    }
    // Block grid per tile, sized so the total node count stays bounded.
    let g = ((MAX_NODES as f64 / num as f64).sqrt().floor() as usize).clamp(1, 8);
    let per_tile = g * g;
    let total = num * per_tile;
    let node = |t: usize, bx: usize, by: usize| t * per_tile + by * g + bx;

    // block index of a local pixel in a tile
    let block_of = |t: usize, lx: usize, ly: usize| -> (usize, usize) {
        let bx = (lx * g / tiles[t].img.w.max(1)).min(g - 1);
        let by = (ly * g / tiles[t].img.h.max(1)).min(g - 1);
        (bx, by)
    };

    // Pairwise block-overlap accumulation.
    let mut nmat = vec![0f64; total * total];
    let mut sum = vec![0f64; total * total]; // sum[p*total+q] = Σ intensity of node p's tile over overlap with node q's tile

    for i in 0..num {
        for j in (i + 1)..num {
            let ti = &tiles[i];
            let tj = &tiles[j];
            let x0 = ti.corner_x.max(tj.corner_x);
            let y0 = ti.corner_y.max(tj.corner_y);
            let x1 = (ti.corner_x + ti.img.w as i32).min(tj.corner_x + tj.img.w as i32);
            let y1 = (ti.corner_y + ti.img.h as i32).min(tj.corner_y + tj.img.h as i32);
            if x0 >= x1 || y0 >= y1 {
                continue;
            }
            for gy in y0..y1 {
                for gx in x0..x1 {
                    let (lix, liy) = ((gx - ti.corner_x) as usize, (gy - ti.corner_y) as usize);
                    let (ljx, ljy) = ((gx - tj.corner_x) as usize, (gy - tj.corner_y) as usize);
                    let ca = ti.img.get(lix, liy);
                    let cb = tj.img.get(ljx, ljy);
                    if ca[3] == 0 || cb[3] == 0 {
                        continue;
                    }
                    let (bix, biy) = block_of(i, lix, liy);
                    let (bjx, bjy) = block_of(j, ljx, ljy);
                    let p = node(i, bix, biy);
                    let q = node(j, bjx, bjy);
                    nmat[p * total + q] += 1.0;
                    nmat[q * total + p] += 1.0;
                    sum[p * total + q] += luma(ca);
                    sum[q * total + p] += luma(cb);
                }
            }
        }
    }

    // Build normal equations A·g = b over all blocks.
    let mut a = DMatrix::<f64>::zeros(total, total);
    let mut b = DVector::<f64>::zeros(total);
    for p in 0..total {
        // regularization so isolated blocks default to gain 1
        a[(p, p)] += 1e-3;
        b[p] += 1e-3;
        for q in 0..total {
            let n = nmat[p * total + q];
            if n <= 0.0 {
                continue;
            }
            let ipq = sum[p * total + q] / n; // mean intensity of p over overlap
            let iqp = sum[q * total + p] / n; // mean intensity of q over overlap
            b[p] += BETA * n;
            a[(p, p)] += BETA * n;
            a[(p, p)] += 2.0 * ALPHA * ipq * ipq * n;
            a[(p, q)] -= 2.0 * ALPHA * ipq * iqp * n;
        }
    }

    let block_gains: Vec<f64> = match a.lu().solve(&b) {
        Some(g) => (0..total).map(|i| if g[i].is_finite() && g[i] > 0.0 { g[i] } else { 1.0 }).collect(),
        None => vec![1.0; total],
    };

    // Bilinearly upsample each tile's g×g block gains to a per-pixel map.
    tiles
        .iter()
        .enumerate()
        .map(|(t, tile)| {
            let w = tile.img.w;
            let h = tile.img.h;
            let mut map = vec![1.0f32; w * h];
            for y in 0..h {
                // block-center coords
                let fy = (y as f64 * g as f64 / h as f64 - 0.5).clamp(0.0, g as f64 - 1.0);
                let by0 = fy.floor() as usize;
                let by1 = (by0 + 1).min(g - 1);
                let ay = (fy - by0 as f64) as f32;
                for x in 0..w {
                    let fx = (x as f64 * g as f64 / w as f64 - 0.5).clamp(0.0, g as f64 - 1.0);
                    let bx0 = fx.floor() as usize;
                    let bx1 = (bx0 + 1).min(g - 1);
                    let ax = (fx - bx0 as f64) as f32;
                    let g00 = block_gains[node(t, bx0, by0)] as f32;
                    let g10 = block_gains[node(t, bx1, by0)] as f32;
                    let g01 = block_gains[node(t, bx0, by1)] as f32;
                    let g11 = block_gains[node(t, bx1, by1)] as f32;
                    let top = g00 * (1.0 - ax) + g10 * ax;
                    let bot = g01 * (1.0 - ax) + g11 * ax;
                    map[y * w + x] = (top * (1.0 - ay) + bot * ay).clamp(0.25, 4.0);
                }
            }
            map
        })
        .collect()
}
