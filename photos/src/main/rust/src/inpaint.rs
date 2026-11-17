//! Port of `ContentAwareData.kt`: content-aware fill. Small/medium holes use
//! onion-peel exemplar synthesis; very large holes (>20000 px) fall back to
//! Jacobi diffusion.

/// Port of `inpaintBitmap`. `hole_mask` is normalized (>=0.5 => hole) at
/// `mask_w` x `mask_h`; returns the filled ARGB buffer.
pub fn inpaint(
    px_in: &[i32],
    w: usize,
    h: usize,
    hole_mask: &[f32],
    mask_w: usize,
    mask_h: usize,
    passes: i32,
) -> Vec<i32> {
    let mut px = px_in.to_vec();

    // Degenerate geometry / undersized mask: no hole information to act
    // on. (Previously mask_w == 0 made the coordinate clamp panic with
    // min > max, and a short mask indexed out of bounds.)
    if w == 0
        || h == 0
        || mask_w == 0
        || mask_h == 0
        || hole_mask.len() < mask_w.saturating_mul(mask_h)
    {
        return px;
    }

    let mut hole = vec![false; w * h];
    let mut hole_count: i64 = 0;
    let mut min_x = w as i32;
    let mut min_y = h as i32;
    let mut max_x = -1i32;
    let mut max_y = -1i32;
    for y in 0..h {
        let my = ((y as i64 * mask_h as i64 / h as i64) as i32).clamp(0, mask_h as i32 - 1);
        for x in 0..w {
            let mx = ((x as i64 * mask_w as i64 / w as i64) as i32).clamp(0, mask_w as i32 - 1);
            if hole_mask[(my as usize) * mask_w + mx as usize] >= 0.5f32 {
                hole[y * w + x] = true;
                hole_count += 1;
                if (x as i32) < min_x {
                    min_x = x as i32;
                }
                if (x as i32) > max_x {
                    max_x = x as i32;
                }
                if (y as i32) < min_y {
                    min_y = y as i32;
                }
                if (y as i32) > max_y {
                    max_y = y as i32;
                }
            }
        }
    }
    if max_x < min_x {
        return px;
    }

    if hole_count > 20000 {
        // Expand the box by one pixel so the diffusion has a ring of
        // known (non-hole) pixels as Dirichlet boundary values. With the
        // tight hole bbox, a rectangular hole's box contains only hole
        // pixels and Jacobi averaging could never import any colour.
        let dx0 = (min_x - 1).max(0);
        let dy0 = (min_y - 1).max(0);
        let dx1 = (max_x + 1).min(w as i32 - 1);
        let dy1 = (max_y + 1).min(h as i32 - 1);
        diffuse_fill(&mut px, w, h, &hole, dx0, dy0, dx1, dy1, passes);
    } else {
        exemplar_fill(&mut px, w, h, &mut hole, min_x, min_y, max_x, max_y);
    }
    px
}

#[inline]
fn diff(a: i32, b: i32) -> i32 {
    let dr = (((a as u32) >> 16) & 0xFF) as i32 - (((b as u32) >> 16) & 0xFF) as i32;
    let dg = (((a as u32) >> 8) & 0xFF) as i32 - (((b as u32) >> 8) & 0xFF) as i32;
    let db = ((a as u32) & 0xFF) as i32 - ((b as u32) & 0xFF) as i32;
    dr * dr + dg * dg + db * db
}

/// Onion-peel exemplar synthesis over the hole's bounding box.
#[allow(unused_assignments)] // `remaining` mirrors the Kotlin control flow verbatim.
fn exemplar_fill(
    px: &mut [i32],
    w: usize,
    h: usize,
    hole: &mut [bool],
    min_x: i32,
    min_y: i32,
    max_x: i32,
    max_y: i32,
) {
    let patch_r: i32 = 1;
    let search_r: i32 = 12;
    let idx = |x: i32, y: i32| -> usize { (y * w as i32 + x) as usize };

    let mut remaining = true;
    let mut guard = (max_x - min_x + 1) * (max_y - min_y + 1) + 8;
    while remaining && guard > 0 {
        guard -= 1;
        remaining = false;
        // Collect current boundary hole pixels (adjacent to a known pixel).
        let mut boundary: Vec<i32> = Vec::new();
        for y in min_y..=max_y {
            for x in min_x..=max_x {
                if !hole[idx(x, y)] {
                    continue;
                }
                let nb = (x > 0 && !hole[idx(x - 1, y)])
                    || (x < w as i32 - 1 && !hole[idx(x + 1, y)])
                    || (y > 0 && !hole[idx(x, y - 1)])
                    || (y < h as i32 - 1 && !hole[idx(x, y + 1)]);
                if nb {
                    boundary.push(idx(x, y) as i32);
                }
            }
        }
        if boundary.is_empty() {
            break;
        }
        remaining = true;
        let mut new_colors = vec![0i32; boundary.len()];
        for bi in 0..boundary.len() {
            let p = boundary[bi];
            let pxc = p % w as i32;
            let pyc = p / w as i32;
            let mut best_q: i32 = -1;
            let mut best_score = i32::MAX;
            let sx0 = (pxc - search_r).max(patch_r);
            let sx1 = (pxc + search_r).min(w as i32 - 1 - patch_r);
            let sy0 = (pyc - search_r).max(patch_r);
            let sy1 = (pyc + search_r).min(h as i32 - 1 - patch_r);
            let mut qy = sy0;
            while qy <= sy1 {
                let mut qx = sx0;
                while qx <= sx1 {
                    if !hole[idx(qx, qy)] {
                        let mut score = 0i32;
                        let mut valid = true;
                        let mut oy = -patch_r;
                        'outer: while oy <= patch_r {
                            let mut ox = -patch_r;
                            while ox <= patch_r {
                                let pnx = pxc + ox;
                                let pny = pyc + oy;
                                let qnx = qx + ox;
                                let qny = qy + oy;
                                if pnx >= 0
                                    && pnx < w as i32
                                    && pny >= 0
                                    && pny < h as i32
                                    && !hole[idx(pnx, pny)]
                                {
                                    if hole[idx(qnx, qny)] {
                                        valid = false;
                                        break 'outer;
                                    }
                                    score += diff(px[idx(pnx, pny)], px[idx(qnx, qny)]);
                                }
                                ox += 1;
                            }
                            oy += 1;
                        }
                        if valid && score < best_score {
                            best_score = score;
                            best_q = idx(qx, qy) as i32;
                        }
                    }
                    qx += 1;
                }
                qy += 1;
            }
            new_colors[bi] = if best_q >= 0 {
                px[best_q as usize]
            } else {
                px[p as usize]
            };
        }
        // Commit this peel layer.
        for bi in 0..boundary.len() {
            let p = boundary[bi] as usize;
            px[p] = new_colors[bi];
            hole[p] = false;
        }
    }
    // Safety: anything still marked (shouldn't happen) gets a neutral gray.
    for y in min_y..=max_y {
        for x in min_x..=max_x {
            if hole[idx(x, y)] {
                px[idx(x, y)] = (px[idx(x, y)] & -0x1000000i32) | 0x808080;
            }
        }
    }
}

/// Cheap Jacobi diffusion fallback for very large holes.
fn diffuse_fill(
    px: &mut [i32],
    w: usize,
    _h: usize,
    hole: &[bool],
    min_x: i32,
    min_y: i32,
    max_x: i32,
    max_y: i32,
    passes: i32,
) {
    let bw = (max_x - min_x + 1) as usize;
    let bh = (max_y - min_y + 1) as usize;
    let mut r = vec![0f32; bw * bh];
    let mut g = vec![0f32; bw * bh];
    let mut b = vec![0f32; bw * bh];
    let mut hole_box = vec![false; bw * bh];
    for y in 0..bh {
        for x in 0..bw {
            let gi = (min_y as usize + y) * w + (min_x as usize + x);
            let c = px[gi];
            let bi = y * bw + x;
            r[bi] = (((c as u32) >> 16) & 0xFF) as f32;
            g[bi] = (((c as u32) >> 8) & 0xFF) as f32;
            b[bi] = ((c as u32) & 0xFF) as f32;
            hole_box[bi] = hole[gi];
        }
    }
    let mut nr = r.clone();
    let mut ng = g.clone();
    let mut nb = b.clone();
    let s = |a: &[f32], x: i32, y: i32| -> f32 {
        let cy = y.clamp(0, bh as i32 - 1) as usize;
        let cx = x.clamp(0, bw as i32 - 1) as usize;
        a[cy * bw + cx]
    };
    for _ in 0..passes {
        for y in 0..bh {
            for x in 0..bw {
                let bi = y * bw + x;
                if !hole_box[bi] {
                    continue;
                }
                let xi = x as i32;
                let yi = y as i32;
                nr[bi] = (s(&r, xi - 1, yi) + s(&r, xi + 1, yi) + s(&r, xi, yi - 1) + s(&r, xi, yi + 1)) / 4f32;
                ng[bi] = (s(&g, xi - 1, yi) + s(&g, xi + 1, yi) + s(&g, xi, yi - 1) + s(&g, xi, yi + 1)) / 4f32;
                nb[bi] = (s(&b, xi - 1, yi) + s(&b, xi + 1, yi) + s(&b, xi, yi - 1) + s(&b, xi, yi + 1)) / 4f32;
            }
        }
        r.copy_from_slice(&nr);
        g.copy_from_slice(&ng);
        b.copy_from_slice(&nb);
    }
    for y in 0..bh {
        for x in 0..bw {
            let bi = y * bw + x;
            if !hole_box[bi] {
                continue;
            }
            let gi = (min_y as usize + y) * w + (min_x as usize + x);
            px[gi] = (px[gi] & -0x1000000i32)
                | ((r[bi] as i32).clamp(0, 255) << 16)
                | ((g[bi] as i32).clamp(0, 255) << 8)
                | (b[bi] as i32).clamp(0, 255);
        }
    }
}
