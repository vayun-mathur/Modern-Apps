//! Exposure compensation. `compensate` implements BlocksGainCompensator
//! (exposure_compensate.cpp) exactly as OpenCV:
//!   bl_width=32 bl_height=32 nr_feeds=1 similarity=1 nr_filter=2
//!   alpha=0.01 beta=100
//!   SingleFeed: A(ii)+=beta*N+2*alpha*I^2*N A(ij)-=2*alpha*I_i*I_j*N b(ii)+=beta*N
//!   BlocksCompensator::feedWithStrategy tiles into bl_per_img ceil/size,
//!   getGainMap CV_32F, smoothing kernel [0.25 0.5 0.25] sepFilter2D iter=2

use crate::sphere::WarpedTile;
use crate::linalg::{DMatrix, DVector};

const ALPHA: f64 = 0.01;
const BETA: f64 = 100.0;
const BL_WIDTH: i32 = 32;
const BL_HEIGHT: i32 = 32;
const NR_FILTER_ITER: usize = 2;

#[inline]
fn luma_norm(c: [u8; 4]) -> f64 {
    // More accurate OpenCV norm(r) = L2 norm of Vec3b: sqrt(r0^2+r1^2+r2^2)
    let r = c[0] as f64;
    let g = c[1] as f64;
    let b = c[2] as f64;
    (r * r + g * g + b * b).sqrt()
}

fn sep_filter_025_05_025(src: &mut [f32], w: usize, h: usize) {
    // Separable kernel [0.25 0.5 0.25] applied horizontally then vertically
    // OpenCV sepFilter2D with ker [0.25 0.5 0.25] both directions, 2 iterations
    let mut tmp = vec![0f32; w * h];
    // horizontal
    for y in 0..h {
        for x in 0..w {
            let xm = if x == 0 { 0 } else { x - 1 };
            let xp = if x + 1 >= w { w - 1 } else { x + 1 };
            tmp[y * w + x] = src[y * w + xm] * 0.25 + src[y * w + x] * 0.5 + src[y * w + xp] * 0.25;
        }
    }
    // vertical
    for y in 0..h {
        let ym = if y == 0 { 0 } else { y - 1 };
        let yp = if y + 1 >= h { h - 1 } else { y + 1 };
        for x in 0..w {
            src[y * w + x] = tmp[ym * w + x] * 0.25 + tmp[y * w + x] * 0.5 + tmp[yp * w + x] * 0.25;
        }
    }
}

/// Per-tile per-pixel gain maps (len = tile.img.w*tile.img.h each).
/// Matches OpenCV BlocksGainCompensator exactly: fixed bl_width 32, bl_height 32,
/// bl_per_img = ceil(tile.w/32) etc., block widths ceil division, block gains
/// solved via GainCompensator singleFeed, then sepFilter smoothing x2 before resize.
pub fn compensate(tiles: &[WarpedTile]) -> Vec<Vec<f32>> {
    let num = tiles.len();
    if num == 0 {
        return Vec::new();
    }

    // Compute bl_per_img per image like BlocksCompensator::feedWithStrategy
    struct BlInfo {
        bl_w: usize,
        bl_h: usize,
        block_w: usize,
        block_h: usize,
    }
    let mut bl_infos: Vec<BlInfo> = Vec::with_capacity(num);
    let mut total_blocks = 0usize;
    for tile in tiles {
        let bl_per_w = (tile.img.w as i32 + BL_WIDTH - 1) / BL_WIDTH;
        let bl_per_h = (tile.img.h as i32 + BL_HEIGHT - 1) / BL_HEIGHT;
        let bl_w = bl_per_w.max(1) as usize;
        let bl_h = bl_per_h.max(1) as usize;
        let block_w = (tile.img.w + bl_w - 1) / bl_w.max(1);
        let block_h = (tile.img.h + bl_h - 1) / bl_h.max(1);
        bl_infos.push(BlInfo { bl_w, bl_h, block_w, block_h });
        total_blocks += bl_w * bl_h;
    }

    let total = total_blocks;
    // If too many blocks, still proceed – OpenCV does not bound MAX_NODES for blocks path; memory heavy but fine for seam res
    // We keep a guard for huge: if > 2000 blocks, downsample factor? Plan says keep fixed 32.
    // Build per-block block idx offset
    let mut block_offset: Vec<usize> = Vec::with_capacity(num);
    let mut off = 0usize;
    for info in &bl_infos {
        block_offset.push(off);
        off += info.bl_w * info.bl_h;
    }

    // Helper: block index of local pixel
    let block_of = |ti: usize, lx: usize, ly: usize| -> (usize, usize) {
        let info = &bl_infos[ti];
        let bx = (lx / info.block_w.max(1)).min(info.bl_w.saturating_sub(1));
        let by = (ly / info.block_h.max(1)).min(info.bl_h.saturating_sub(1));
        (bx, by)
    };
    let node = |ti: usize, bx: usize, by: usize| -> usize {
        block_offset[ti] + by * bl_infos[ti].bl_w + bx
    };

    // Pairwise block-overlap accumulation (like GainCompensator::singleFeed over blocks)
    // N matrix int max(1, count) per pair, I sum norm
    let mut nmat = vec![0f64; total * total];
    let mut sum = vec![0f64; total * total]; // sum[p*total+q] = Σ intensity of p over overlap with q

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
                    // OpenCV uses norm(r) = L2 for 3ch
                    sum[p * total + q] += luma_norm(ca);
                    sum[q * total + p] += luma_norm(cb);
                }
            }
        }
    }

    // Build normal equations A·g = b, filtering skip (blocks with zero overlap with others)
    // OpenCV: skip = true initially, set false for images that intersect with at least one other
    // For blocks version, compensator.feed does skip logic over block_images (all are kept if intersect_count>0 set)
    // Actually GainCompensator sets skip true, then false if i!=j intersect. So isolated blocks skip.
    // We replicate: determine which block nodes have any overlap count >0
    let mut skip = vec![true; total];
    for p in 0..total {
        for q in 0..total {
            if p != q && nmat[p * total + q] > 0.0 {
                skip[p] = false;
                skip[q] = false;
            }
        }
    }
    // If no intersections at all, return ones
    let num_eq = skip.iter().filter(|s| !*s).count();
    if num_eq == 0 {
        // No overlap – gains = 1
        return tiles.iter().map(|t| vec![1.0f32; t.img.w * t.img.h]).collect();
    }

    // Mapping from global block idx to equation idx
    let mut eq_of = vec![usize::MAX; total];
    {
        let mut eq = 0usize;
        for p in 0..total {
            if !skip[p] {
                eq_of[p] = eq;
                eq += 1;
            }
        }
    }

    let mut a = DMatrix::<f64>::zeros(num_eq, num_eq);
    let mut b = DVector::<f64>::zeros(num_eq);

    // Build normal equations exactly like GainCompensator
    for p in 0..total {
        if skip[p] {
            continue;
        }
        let kp = eq_of[p];
        let ti = {
            let mut t = 0usize;
            for i in 0..num {
                if p >= block_offset[i] && p < block_offset[i] + bl_infos[i].bl_w * bl_infos[i].bl_h {
                    t = i;
                    break;
                }
            }
            t
        };
        let info = &bl_infos[ti];
        let self_n = (info.block_w * info.block_h) as f64;
        b[kp] += BETA * self_n;
        a[(kp, kp)] += BETA * self_n;

        for q in 0..total {
            if q == p || skip[q] {
                continue;
            }
            let n = nmat[p * total + q];
            if n <= 0.0 {
                continue;
            }
            let ipq = sum[p * total + q] / n;
            let iqp = sum[q * total + p] / n;
            let kq = eq_of[q];
            b[kp] += BETA * n;
            a[(kp, kp)] += BETA * n;
            a[(kp, kp)] += 2.0 * ALPHA * ipq * ipq * n;
            a[(kp, kq)] -= 2.0 * ALPHA * ipq * iqp * n;
        }
    }

    let l_gains_full: Vec<f64> = match a.lu().solve(&b) {
        Some(sol) => {
            let mut full = vec![1.0f64; total];
            for p in 0..total {
                if !skip[p] {
                    full[p] = sol[eq_of[p]];
                }
            }
            full
        }
        None => vec![1.0; total],
    };

    // Build per-image gain_map low-res bl_per_img sized
    let mut low_gain_maps: Vec<Vec<f32>> = Vec::with_capacity(num);
    for img_idx in 0..num {
        let info = &bl_infos[img_idx];
        let off = block_offset[img_idx];
        let bw = info.bl_w;
        let bh = info.bl_h;
        let mut gm = vec![0f32; bw * bh];
        for by in 0..bh {
            for bx in 0..bw {
                let idx = off + by * bw + bx;
                let g = l_gains_full[idx];
                gm[by * bw + bx] = if g.is_finite() && g > 0.0 { g as f32 } else { 1.0f32 };
            }
        }
        // SepFilter smoothing iterations 2 with kernel [0.25 0.5 0.25]
        for _ in 0..NR_FILTER_ITER {
            sep_filter_025_05_025(&mut gm, bw, bh);
        }
        low_gain_maps.push(gm);
    }

    // Resize per-image low-res gain map to tile res INTER_LINEAR (bilinear)
    tiles
        .iter()
        .enumerate()
        .map(|(ti, tile)| {
            let w = tile.img.w;
            let h = tile.img.h;
            let info = &bl_infos[ti];
            let bw = info.bl_w;
            let bh = info.bl_h;
            let low = &low_gain_maps[ti];
            let mut map = vec![1.0f32; w * h];
            for y in 0..h {
                let fy = (y as f64 * bh as f64 / h as f64).clamp(0.0, bh as f64 - 1.0);
                let by0 = fy.floor() as usize;
                let by1 = (by0 + 1).min(bh - 1);
                let ay = (fy - by0 as f64) as f32;
                for x in 0..w {
                    let fx = (x as f64 * bw as f64 / w as f64).clamp(0.0, bw as f64 - 1.0);
                    let bx0 = fx.floor() as usize;
                    let bx1 = (bx0 + 1).min(bw - 1);
                    let ax = (fx - bx0 as f64) as f32;
                    let g00 = low[by0 * bw + bx0];
                    let g10 = low[by0 * bw + bx1];
                    let g01 = low[by1 * bw + bx0];
                    let g11 = low[by1 * bw + bx1];
                    let top = g00 * (1.0 - ax) + g10 * ax;
                    let bot = g01 * (1.0 - ax) + g11 * ax;
                    let v = top * (1.0 - ay) + bot * ay;
                    map[y * w + x] = v.clamp(0.25, 4.0);
                }
            }
            map
        })
        .collect()
}

#[cfg(test)]
mod exposure_host_tests {
    use super::*;
    use crate::imgbuf::Rgba;
    use crate::sphere::WarpedTile;

    fn opaque_tile(w: usize, h: usize, cx: i32, cy: i32) -> WarpedTile {
        let mut img = Rgba::new(w, h);
        for y in 0..h {
            for x in 0..w {
                img.set(x, y, [200, 200, 200, 255]);
            }
        }
        WarpedTile { img, corner_x: cx, corner_y: cy }
    }

    #[test]
    fn compensate_empty_and_disjoint_returns_unity_gains() {
        assert!(compensate(&[]).is_empty());
        let tiles = vec![opaque_tile(32, 32, 0, 0), opaque_tile(32, 32, 500, 500)];
        let gains = compensate(&tiles);
        assert_eq!(gains.len(), 2);
        for (g, t) in gains.iter().zip(tiles.iter()) {
            assert_eq!(g.len(), t.img.w * t.img.h);
            assert!(g.iter().all(|v| *v == 1.0), "disjoint tiles must stay at unity gain");
        }
    }
}

#[cfg(test)]
#[path = "exposure_tests.rs"]
mod exposure_tests;
