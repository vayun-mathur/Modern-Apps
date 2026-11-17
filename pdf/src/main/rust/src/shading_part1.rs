/// Type 6 (Coons) / Type 7 (tensor-product) patch mesh. Patches are subdivided
/// on a grid using the real boundary Bézier curves and bilinear corner-color
/// interpolation.
fn parse_type6_7(
    data: &[u8],
    shading_type: i64,
    // (bits per flag, bits per coordinate, bits per component)
    bps: (u32, u32, u32),
    ncomp: usize,
    decode: &[f64],
    emit: Emit,
) {
    let (bps_flag, bps_coord, bps_comp) = bps;
    let n_pts = if shading_type == 6 { 12 } else { 16 };
    let mut br = BitReader::new(data);
    // Previous patch boundary (12 boundary points) + corner colors, for
    // edge-sharing when flag != 0.
    let mut prev_pts: Vec<(f64, f64)> = Vec::new();
    let mut prev_cols: Vec<Vec<f64>> = Vec::new();
    let mut emitted = 0usize;
    let mut patches = 0usize;

    let read_point = |br: &mut BitReader| -> Option<(f64, f64)> {
        let rx = br.read(bps_coord)?;
        let ry = br.read(bps_coord)?;
        Some((
            map_val(rx, decode[0], decode[1], bps_coord),
            map_val(ry, decode[2], decode[3], bps_coord),
        ))
    };
    let read_color = |br: &mut BitReader| -> Option<Vec<f64>> {
        let mut col = Vec::with_capacity(ncomp);
        for c in 0..ncomp {
            let cmin = decode.get(4 + c * 2).copied().unwrap_or(0.0);
            let cmax = decode.get(4 + c * 2 + 1).copied().unwrap_or(1.0);
            col.push(map_val(br.read(bps_comp)?, cmin, cmax, bps_comp));
        }
        Some(col)
    };

    loop {
        if patches >= MAX_SHADING_PATCHES || emitted >= MAX_SHADING_TRIANGLES {
            break;
        }
        if br.remaining_bits() < bps_flag as usize {
            break;
        }
        let flag = match br.read(bps_flag) {
            Some(f) => f,
            None => break,
        };

        // 12 boundary control points (p1..p12) and 4 corner colors for this patch.
        // Type 7 additionally has 4 interior control points (p13..p16).
        let mut pts: Vec<(f64, f64)> = Vec::with_capacity(12);
        let mut interior: Vec<(f64, f64)> = Vec::with_capacity(4);
        let mut cols: Vec<Vec<f64>> = Vec::with_capacity(4);

        if flag == 0 || prev_pts.len() < 12 || prev_cols.len() < 4 {
            // Full patch: read all points + 4 colors.
            let mut all = Vec::with_capacity(n_pts);
            let mut ok = true;
            for _ in 0..n_pts {
                match read_point(&mut br) {
                    Some(p) => all.push(p),
                    None => { ok = false; break; }
                }
            }
            if !ok || all.len() < 12 {
                break;
            }
            pts = all[0..12].to_vec(); // p1..p12 boundary points
            if shading_type == 7 && all.len() >= 16 {
                interior = all[12..16].to_vec(); // p13..p16 tensor interior
            }
            let mut cok = true;
            for _ in 0..4 {
                match read_color(&mut br) {
                    Some(c) => cols.push(c),
                    None => { cok = false; break; }
                }
            }
            if !cok {
                break;
            }
        } else {
            // Shared-edge patch: 8 new points (coons) / 12 (tensor) + 2 colors.
            // The shared edge (4 pts, 2 colors) comes from the previous patch,
            // selected by flag (1/2/3 => which previous edge).
            let (shared_pts, shared_c0, shared_c1) = shared_edge(&prev_pts, &prev_cols, flag);
            let new_pts_count = if shading_type == 6 { 8 } else { 12 };
            let mut new_pts = Vec::with_capacity(new_pts_count);
            let mut ok = true;
            for _ in 0..new_pts_count {
                match read_point(&mut br) {
                    Some(p) => new_pts.push(p),
                    None => { ok = false; break; }
                }
            }
            if !ok {
                break;
            }
            // Boundary = shared edge (4) + next 8 new boundary points; for tensor
            // the final 4 new points are the interior control points.
            if shading_type == 7 && new_pts.len() >= 12 {
                interior = new_pts[8..12].to_vec();
            }
            pts.extend_from_slice(&shared_pts);
            pts.extend(new_pts.into_iter().take(8));
            if pts.len() < 12 {
                break;
            }
            let c2;
            let c3;
            if let Some(c) = read_color(&mut br) { c2 = Some(c); } else { break; }
            if let Some(c) = read_color(&mut br) { c3 = Some(c); } else { break; }
            cols.push(shared_c0);
            cols.push(shared_c1);
            cols.push(c2.unwrap());
            cols.push(c3.unwrap());
        }

        // Each patch's data occupies a whole number of bytes; trailing padding
        // bits in the last byte are ignored (ISO 32000-1 8.7.4.5.7).
        br.align();

        // Corners of the boundary loop: p1=pts[0], p4=pts[3], p7=pts[6], p10=pts[9].
        let e_left = [pts[0], pts[1], pts[2], pts[3]]; // C00 -> C01
        let e_top = [pts[3], pts[4], pts[5], pts[6]]; // C01 -> C11
        let e_right = [pts[6], pts[7], pts[8], pts[9]]; // C11 -> C10
        let e_bottom = [pts[9], pts[10], pts[11], pts[0]]; // C10 -> C00
        let c00 = cols.first().cloned().unwrap_or_default();
        let c01 = cols.get(1).cloned().unwrap_or_default();
        let c11 = cols.get(2).cloned().unwrap_or_default();
        let c10 = cols.get(3).cloned().unwrap_or_default();

        // Subdivide the patch surface on an N×N grid. Coons (Type 6) uses the
        // bilinearly-blended boundary surface; tensor (Type 7) uses the full
        // bicubic Bézier defined by the 12 boundary + 4 interior control points.
        const N: usize = 8;
        // Build the 4×4 tensor control grid P[i][j] from the PDF point ordering.
        let tensor_grid: Option<[[(f64, f64); 4]; 4]> = if shading_type == 7 && interior.len() == 4 {
            let mut p = [[(0.0, 0.0); 4]; 4];
            p[0][0] = pts[0];  p[0][1] = pts[1];  p[0][2] = pts[2];  p[0][3] = pts[3];
            p[1][3] = pts[4];  p[2][3] = pts[5];  p[3][3] = pts[6];  p[3][2] = pts[7];
            p[3][1] = pts[8];  p[3][0] = pts[9];  p[2][0] = pts[10]; p[1][0] = pts[11];
            p[1][1] = interior[0]; p[1][2] = interior[1]; p[2][2] = interior[2]; p[2][1] = interior[3];
            Some(p)
        } else {
            None
        };
        let bern = |t: f64| -> [f64; 4] {
            let mt = 1.0 - t;
            [mt*mt*mt, 3.0*t*mt*mt, 3.0*t*t*mt, t*t*t]
        };
        let surf = |u: f64, v: f64| -> (f64, f64) {
            if let Some(p) = tensor_grid {
                let bu = bern(u);
                let bv = bern(v);
                let mut sx = 0.0;
                let mut sy = 0.0;
                for i in 0..4 {
                    for j in 0..4 {
                        let w = bu[i] * bv[j];
                        sx += w * p[i][j].0;
                        sy += w * p[i][j].1;
                    }
                }
                return (sx, sy);
            }
            let left = bezier(e_left, v);
            let right = {
                // u=1 edge from C10(v=0) to C11(v=1): reverse e_right (C11->C10)
                let rp = [e_right[3], e_right[2], e_right[1], e_right[0]];
                bezier(rp, v)
            };
            let bottom = {
                // v=0 edge from C00(u=0) to C10(u=1): reverse e_bottom (C10->C00)
                let bp = [e_bottom[3], e_bottom[2], e_bottom[1], e_bottom[0]];
                bezier(bp, u)
            };
            let top = bezier(e_top, u); // v=1 edge C01->C11
            let c00p = e_left[0];
            let c01p = e_left[3];
            let c11p = e_top[3];
            let c10p = e_bottom[0];
            let sx = (1.0 - u) * left.0 + u * right.0 + (1.0 - v) * bottom.0 + v * top.0
                - ((1.0 - u) * (1.0 - v) * c00p.0 + u * (1.0 - v) * c10p.0 + (1.0 - u) * v * c01p.0 + u * v * c11p.0);
            let sy = (1.0 - u) * left.1 + u * right.1 + (1.0 - v) * bottom.1 + v * top.1
                - ((1.0 - u) * (1.0 - v) * c00p.1 + u * (1.0 - v) * c10p.1 + (1.0 - u) * v * c01p.1 + u * v * c11p.1);
            (sx, sy)
        };
        let color_at = |u: f64, v: f64| -> Vec<f64> {
            let n = ncomp;
            (0..n)
                .map(|k| {
                    let a = c00.get(k).copied().unwrap_or(0.0);
                    let b = c10.get(k).copied().unwrap_or(0.0);
                    let c = c01.get(k).copied().unwrap_or(0.0);
                    let d = c11.get(k).copied().unwrap_or(0.0);
                    (1.0 - u) * (1.0 - v) * a + u * (1.0 - v) * b + (1.0 - u) * v * c + u * v * d
                })
                .collect()
        };
        let mut grid: Vec<Vec<Vertex>> = Vec::with_capacity(N + 1);
        for iv in 0..=N {
            let v = iv as f64 / N as f64;
            let mut row = Vec::with_capacity(N + 1);
            for iu in 0..=N {
                let u = iu as f64 / N as f64;
                let (x, y) = surf(u, v);
                row.push(Vertex { x, y, color: color_at(u, v) });
            }
            grid.push(row);
        }
        for iv in 0..N {
            for iu in 0..N {
                let a = &grid[iv][iu];
                let b = &grid[iv][iu + 1];
                let c = &grid[iv + 1][iu];
                let d = &grid[iv + 1][iu + 1];
                emit(a, b, c);
                emit(c, b, d);
                emitted += 2;
            }
        }

        prev_pts = pts;
        prev_cols = cols;
        patches += 1;
    }
}

/// Select the shared edge (4 control points + 2 corner colors) from the
/// previous patch for a flag-1/2/3 continuation, per ISO 32000 Table 85 (Coons)
/// / Table 86 (tensor). The new patch's first edge (p1..p4, colors c1,c2) is the
/// previous patch's edge in FORWARD order so the patches join without twisting:
///   flag 1 -> prev p4,p5,p6,p7 & colors c2,c3
///   flag 2 -> prev p7,p8,p9,p10 & colors c3,c4
///   flag 3 -> prev p10,p11,p12,p1 & colors c4,c1
fn shared_edge(prev_pts: &[(f64, f64)], prev_cols: &[Vec<f64>], flag: u64) -> ([(f64, f64); 4], Vec<f64>, Vec<f64>) {
    // Boundary points p1..p12 = index 0..11; corner colors c1..c4 = index 0..3.
    let (ia, ib, ic, id, ca, cb) = match flag {
        1 => (3usize, 4, 5, 6, 1usize, 2usize),
        2 => (6usize, 7, 8, 9, 2usize, 3usize),
        _ => (9usize, 10, 11, 0, 3usize, 0usize),
    };
    let g = |i: usize| prev_pts.get(i).copied().unwrap_or((0.0, 0.0));
    let col = |i: usize| prev_cols.get(i).cloned().unwrap_or_default();
    ([g(ia), g(ib), g(ic), g(id)], col(ca), col(cb))
}

/// Rasterize a single Gouraud triangle into the RGBA buffer over `bounds`.
fn fill_tri(
    rgba: &mut [u8],
    w: usize,
    h: usize,
    bounds: &[f64; 4],
    v: (&Vertex, &Vertex, &Vertex),
    colors: (u32, u32, u32),
) {
    let (v0, v1, v2) = v;
    let (c0, c1, c2) = colors;
    let (x0, y0) = (v0.x, v0.y);
    let (x1, y1) = (v1.x, v1.y);
    let (x2, y2) = (v2.x, v2.y);
    // A non-finite vertex is not merely unpaintable, it paints EVERYTHING: the
    // scanline bounds below collapse to the full raster (`NaN.max(0.0)` is 0 and
    // `NaN.min(w)` is w), and every barycentric rejection test `a < -1e-6` is false
    // for NaN, so the whole buffer is filled opaque. Bail before that.
    if ![x0, y0, x1, y1, x2, y2].iter().all(|c| c.is_finite()) {
        return;
    }
    let bw = bounds[2] - bounds[0];
    let bh = bounds[3] - bounds[1];
    let to_px = |x: f64| (x - bounds[0]) / bw * w as f64;
    // Raster row 0 is the TOP of the image (unit-square v=1, ISO 32000-1 8.9.5.2), so the
    // HIGH-y edge of `bounds` maps to py 0. Mapping bounds[1] to row 0 instead mirrored
    // every mesh shading vertically, because `placement_ctm`'s `d` is positive. Must stay
    // consistent with the `fy` inverse below.
    let to_py = |y: f64| (bounds[3] - y) / bh * h as f64;
    let min_x = x0.min(x1).min(x2);
    let max_x = x0.max(x1).max(x2);
    let min_y = y0.min(y1).min(y2);
    let max_y = y0.max(y1).max(y2);
    let px0 = to_px(min_x).floor().max(0.0) as i32;
    let px1 = to_px(max_x).ceil().min(w as f64) as i32;
    // `to_py` now DECREASES with y, so the triangle's max_y gives the smaller row index.
    // Swapping these is not cosmetic: with them the wrong way round py0 > py1 and the
    // scanline loop below never executes, so every mesh shading would render empty.
    let py0 = to_py(max_y).floor().max(0.0) as i32;
    let py1 = to_py(min_y).ceil().min(h as f64) as i32;
    let denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
    if denom.abs() < 1e-12 {
        return;
    }
    for py in py0..py1 {
        for px in px0..px1 {
            let fx = bounds[0] + (px as f64 + 0.5) / w as f64 * bw;
            let fy = bounds[3] - (py as f64 + 0.5) / h as f64 * bh;
            let a = ((y1 - y2) * (fx - x2) + (x2 - x1) * (fy - y2)) / denom;
            let b = ((y2 - y0) * (fx - x2) + (x0 - x2) * (fy - y2)) / denom;
            let c = 1.0 - a - b;
            if a < -1e-6 || b < -1e-6 || c < -1e-6 {
                continue;
            }
            // `.round()`, not truncation: `c` is formed as `1 - a - b`, so a+b+c can be
            // 0.9999999999999999 and a flat white triangle summed to 254.99999999999997,
            // which `as u8` truncated to 254. The clamp covers the -1e-6 slack above.
            let r = ((((c0 >> 16) & 0xFF) as f64 * a + ((c1 >> 16) & 0xFF) as f64 * b + ((c2 >> 16) & 0xFF) as f64 * c).round()).clamp(0.0, 255.0) as u8;
            let g = ((((c0 >> 8) & 0xFF) as f64 * a + ((c1 >> 8) & 0xFF) as f64 * b + ((c2 >> 8) & 0xFF) as f64 * c).round()).clamp(0.0, 255.0) as u8;
            let bl = (((c0 & 0xFF) as f64 * a + (c1 & 0xFF) as f64 * b + (c2 & 0xFF) as f64 * c).round()).clamp(0.0, 255.0) as u8;
            let idx = (py as usize * w + px as usize) * 4;
            rgba[idx] = r;
            rgba[idx + 1] = g;
            rgba[idx + 2] = bl;
            rgba[idx + 3] = 255;
        }
    }
}
