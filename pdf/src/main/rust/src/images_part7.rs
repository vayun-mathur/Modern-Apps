/// Rasterize display-space primitives into an RGBA8888 buffer.
///
/// Everything else in this crate turns PDF content into [`Prim`]s for the renderer to
/// paint; this goes the other way, for the one case that needs a *bitmap* of some
/// content rather than more primitives: a single tiling-pattern cell (§8.7.3.3).
/// Replicating one small bitmap over a hatched region costs a fixed amount of memory,
/// so the RASTER can be capped and the tile COUNT left alone — which is the whole point,
/// since capping the tile count is what left 99% of a hatched area blank.
///
/// `device_box` is the display-space rect the raster covers, and raster row 0 is its LOW
/// y edge — the same convention [`rasterize_shading`] uses, so the same unit-square
/// placement CTM (`[bw, 0, 0, bh, x0, y0] * base`) positions the result.
///
/// Coverage is antialiased with 2 vertical subsamples and analytic horizontal spans:
/// hatch and crosshatch cells are made of hairlines, and without coverage AA a thin
/// diagonal rule drops out of a small raster entirely rather than merely looking rough.
///
/// `Prim::Text`, `Prim::Image`, clip and group primitives are ignored. A pattern cell's
/// clip is the caller's cell rectangle, and there is no glyph rasterizer here — a cell
/// whose content is text or an image must still go through the primitive path.
///
/// Returns `None` for a degenerate box or a request over [`MAX_TILE_RASTER_BYTES`].
pub(crate) fn rasterize_prims_to_rgba(
    prims: &[Prim],
    device_box: [f64; 4],
    w: usize,
    h: usize,
) -> Option<Vec<u8>> {
    let bw = device_box[2] - device_box[0];
    let bh = device_box[3] - device_box[1];
    if w == 0 || h == 0 || !bw.is_finite() || !bh.is_finite() || bw.abs() < 1e-12 || bh.abs() < 1e-12 {
        return None;
    }
    if w.saturating_mul(h).saturating_mul(4) > MAX_TILE_RASTER_BYTES {
        return None;
    }
    // Accumulate PREMULTIPLIED so source-over compositing of overlapping translucent
    // fills is correct; un-premultiplied at the end because the Kotlin side's
    // `Bitmap.createBitmap(int[], ...)` expects straight alpha.
    let mut acc = vec![0u8; w * h * 4];
    let to_px = |x: f64| (x - device_box[0]) / bw * w as f64;
    // Raster row 0 is the TOP of the image (unit-square v=1, 8.9.5.2), so device-space
    // HIGH y maps to py 0. Mapping low y to row 0 mirrored every cell vertically.
    let to_py = |y: f64| (device_box[3] - y) / bh * h as f64;

    for prim in prims {
        let (argb, contours, even_odd) = match prim {
            Prim::Fill { argb, contours, even_odd, .. } => (
                *argb,
                contours
                    .iter()
                    .map(|c| c.iter().map(|&(x, y)| (x as f64, y as f64)).collect())
                    .collect::<Vec<Vec<(f64, f64)>>>(),
                *even_odd,
            ),
            Prim::Stroke { argb, width, pts, .. } => {
                // Expand the polyline to its outline: one quad per segment plus a square
                // at each vertex standing in for the join and the cap. Filled as ONE
                // nonzero path so overlaps union instead of compositing twice, which
                // would darken every join of a translucent stroke.
                let hw = (*width as f64 / 2.0).max(0.35);
                let mut quads: Vec<Vec<(f64, f64)>> = Vec::new();
                for seg in pts.windows(2) {
                    let (x0, y0) = (seg[0].0 as f64, seg[0].1 as f64);
                    let (x1, y1) = (seg[1].0 as f64, seg[1].1 as f64);
                    let (dx, dy) = (x1 - x0, y1 - y0);
                    let len = (dx * dx + dy * dy).sqrt();
                    if len < 1e-9 {
                        continue;
                    }
                    let (nx, ny) = (-dy / len * hw, dx / len * hw);
                    quads.push(vec![
                        (x0 + nx, y0 + ny), (x1 + nx, y1 + ny),
                        (x1 - nx, y1 - ny), (x0 - nx, y0 - ny),
                    ]);
                }
                for p in pts.iter() {
                    let (x, y) = (p.0 as f64, p.1 as f64);
                    quads.push(vec![
                        (x - hw, y - hw), (x + hw, y - hw), (x + hw, y + hw), (x - hw, y + hw),
                    ]);
                }
                // Nonzero winding cancels where two contours wind oppositely, so give
                // every quad the same orientation before unioning them.
                for q in quads.iter_mut() {
                    let area: f64 = (0..q.len())
                        .map(|i| {
                            let a = q[i];
                            let b = q[(i + 1) % q.len()];
                            a.0 * b.1 - b.0 * a.1
                        })
                        .sum();
                    if area < 0.0 {
                        q.reverse();
                    }
                }
                (*argb, quads, false)
            }
            // No glyph rasterizer here, and a cell's clip is the caller's business.
            _ => continue,
        };
        let alpha = ((argb >> 24) & 0xFF) as f64 / 255.0;
        if alpha <= 0.0 {
            continue;
        }
        // Edges in raster coordinates.
        let mut edges: Vec<(f64, f64, f64, f64)> = Vec::new();
        for c in &contours {
            if c.len() < 3 {
                continue;
            }
            for i in 0..c.len() {
                let a = c[i];
                let b = c[(i + 1) % c.len()];
                let (ax, ay) = (to_px(a.0), to_py(a.1));
                let (bx, by) = (to_px(b.0), to_py(b.1));
                if (ay - by).abs() > 1e-12 {
                    edges.push((ax, ay, bx, by));
                }
            }
        }
        if edges.is_empty() {
            continue;
        }
        const SS: usize = 2;
        let mut cov = vec![0f32; w];
        let mut xs: Vec<(f64, i32)> = Vec::new();
        for py in 0..h {
            cov.iter_mut().for_each(|c| *c = 0.0);
            for s in 0..SS {
                let yc = py as f64 + (s as f64 + 0.5) / SS as f64;
                xs.clear();
                for &(ax, ay, bx, by) in &edges {
                    // Half-open in y so a vertex shared by two edges is counted once.
                    let (lo, hi, dir) = if ay < by { (ay, by, 1) } else { (by, ay, -1) };
                    if yc < lo || yc >= hi {
                        continue;
                    }
                    let t = (yc - ay) / (by - ay);
                    xs.push((ax + t * (bx - ax), dir));
                }
                if xs.len() < 2 {
                    continue;
                }
                xs.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
                let mut wind = 0i32;
                for i in 0..xs.len() - 1 {
                    wind += xs[i].1;
                    let inside = if even_odd { (i as i32 + 1) % 2 != 0 } else { wind != 0 };
                    if !inside {
                        continue;
                    }
                    // Analytic horizontal coverage for the span, so a hairline narrower
                    // than a pixel still contributes its true fraction.
                    let (xa, xb) = (xs[i].0.max(0.0), xs[i + 1].0.min(w as f64));
                    if xb <= xa {
                        continue;
                    }
                    let weight = 1.0 / SS as f32;
                    for (px, cov_px) in cov.iter_mut().enumerate().take((xb.ceil() as usize).min(w)).skip(xa.floor() as usize) {
                        let l = xa.max(px as f64);
                        let r = xb.min(px as f64 + 1.0);
                        if r > l {
                            *cov_px += (r - l) as f32 * weight;
                        }
                    }
                }
            }
            let (sr, sg, sb) = (
                ((argb >> 16) & 0xFF) as f64,
                ((argb >> 8) & 0xFF) as f64,
                (argb & 0xFF) as f64,
            );
            for (px, cov_px) in cov.iter().enumerate().take(w) {
                let a = alpha * (cov_px.clamp(0.0, 1.0) as f64);
                if a <= 0.0 {
                    continue;
                }
                let o = (py * w + px) * 4;
                let inv = 1.0 - a;
                for (ch, sc) in [sr, sg, sb].iter().enumerate() {
                    acc[o + ch] = (sc * a + acc[o + ch] as f64 * inv).round().clamp(0.0, 255.0) as u8;
                }
                acc[o + 3] = (a * 255.0 + acc[o + 3] as f64 * inv).round().clamp(0.0, 255.0) as u8;
            }
        }
    }

    for px in acc.chunks_exact_mut(4) {
        let a = px[3];
        if a == 0 || a == 255 {
            continue;
        }
        for ch in px.iter_mut().take(3) {
            *ch = ((*ch as u32 * 255) / a as u32).min(255) as u8;
        }
    }
    Some(acc)
}

/// Apply a per-pixel soft-mask alpha (length `w*h`) to an RGBA buffer.
pub(crate) fn apply_smask(rgba: &mut [u8], smask: &Option<Vec<u8>>) {
    if let Some(alpha) = smask {
        let n = (rgba.len() / 4).min(alpha.len());
        for i in 0..n {
            rgba[i * 4 + 3] = alpha[i];
        }
    }
}

/// Multiply an explicit stencil `/Mask`'s alpha (§8.9.6.4) into an RGBA buffer.
/// Unlike [`apply_smask`] this COMBINES rather than replaces, so it composes with
/// whatever alpha the codec already produced. Absent entries keep the pixel.
pub(crate) fn apply_explicit_mask(rgba: &mut [u8], mask_alpha: &[u8]) {
    for (i, px) in rgba.chunks_exact_mut(4).enumerate() {
        let mv = mask_alpha.get(i).copied().unwrap_or(255) as u16;
        px[3] = ((px[3] as u16 * mv) / 255) as u8;
    }
}

pub(crate) fn apply_color_key_mask(rgba: &mut [u8], mask_ranges: &Option<Vec<(u8,u8)>>) {
    let ranges = match mask_ranges {
        Some(r) => r,
        None => return,
    };
    if ranges.is_empty() { return; }
    let pixel_count = rgba.len() / 4;
    // ncomp inferred from ranges len: could be 1,3,4
    let ncomp = ranges.len();
    for i in 0..pixel_count {
        let base = i*4;
        let r = rgba[base];
        let g = rgba[base+1];
        let b = rgba[base+2];
        // For 1-comp, use r (gray)
        let transparent = match ncomp {
            1 => {
                let (mn,mx) = ranges[0];
                r >= mn && r <= mx
            }
            3 => {
                let (r0,r1)=ranges.first().copied().unwrap_or((0,0));
                let (g0,g1)=ranges.get(1).copied().unwrap_or((0,0));
                let (b0,b1)=ranges.get(2).copied().unwrap_or((0,0));
                r>=r0 && r<=r1 && g>=g0 && g<=g1 && b>=b0 && b<=b1
            }
            _ => {
                // 4+ components (CMYK/DeviceN). We only have post-conversion RGB here,
                // so there is no honest way to test a CMYK range — the old code compared
                // the RED channel against the CYAN range, which could make large regions
                // wrongly transparent. The sample-domain path
                // (`apply_color_key_mask_samples`) handles these correctly; skip here.
                false
            }
        };
        if transparent {
            rgba[base+3]=0;
        }
    }
}

pub(crate) fn read_color_key_mask(doc: &Document, dict: &lopdf::Dictionary) -> Option<Vec<(u8,u8)>> {
    let mask_obj = dict.get(b"Mask").ok().and_then(|o| deref(doc, o))?;
    let arr = match mask_obj {
        Object::Array(a) => a,
        _ => return None,
    };
    if arr.len()%2!=0 { return None; }
    if arr.len()>20 { return None; } // sanity: up to 10 components (DeviceN)
    // §8.9.6.4: the ranges are INTEGER source sample values in 0..2^bpc-1, never
    // 0..1. The old `if v > 1.0 { v } else { v * 255.0 }` heuristic turned a
    // legitimate 8-bpc `/Mask [0 1]` (mask near-black only) into (0,255), i.e.
    // every pixel transparent — the whole image vanished.
    let bpc = if dict_true(doc, dict, b"ImageMask") {
        1u32
    } else {
        dict.get(b"BitsPerComponent").ok().and_then(|o| deref(doc, o)).and_then(num).unwrap_or(8.0) as u32
    }
    .clamp(1, 16);
    let maxval = ((1u64 << bpc) - 1).max(1);
    let mut out = Vec::with_capacity(arr.len()/2);
    for i in 0..arr.len()/2 {
        let mn = num(&arr[i*2]).unwrap_or(0.0);
        let mx = num(&arr[i*2+1]).unwrap_or(0.0);
        let to_u8 = |v: f64| -> u8 {
            ((v.max(0.0).round() as u64).min(maxval) * 255 / maxval) as u8
        };
        let mn_u = to_u8(mn.min(mx));
        let mx_u = to_u8(mn.max(mx));
        out.push((mn_u, mx_u));
    }
    Some(out)
}

/// Read a color-key `/Mask` array as raw per-component sample-value ranges
/// (i.e. in the image's 0..2^bpc-1 units, one `(min,max)` per component). This
/// is the form needed to mask against pre-conversion samples for CMYK/DeviceN.
pub(crate) fn read_color_key_ranges_raw(doc: &Document, dict: &lopdf::Dictionary) -> Option<Vec<(u32,u32)>> {
    let mask_obj = dict.get(b"Mask").ok().and_then(|o| deref(doc, o))?;
    let arr = match mask_obj {
        Object::Array(a) => a,
        _ => return None,
    };
    if arr.is_empty() || arr.len()%2!=0 || arr.len()>20 { return None; }
    let mut out = Vec::with_capacity(arr.len()/2);
    for i in 0..arr.len()/2 {
        let mn = num(&arr[i*2]).unwrap_or(0.0).max(0.0);
        let mx = num(&arr[i*2+1]).unwrap_or(0.0).max(0.0);
        out.push((mn.min(mx) as u32, mn.max(mx) as u32));
    }
    Some(out)
}

/// Apply color-key masking against pre-conversion samples. `comps` is
/// `w*h*ncomp` bytes already scaled to 0..255 by [`unpack_samples_to_bytes`];
/// `ranges_raw` holds one `(min,max)` per component in 0..2^bpc-1 units. A pixel
/// whose every component falls inside its range becomes fully transparent. This
/// masks correctly for CMYK/DeviceN, unlike the RGB-only post-conversion path.
pub(crate) fn apply_color_key_mask_samples(
    rgba: &mut [u8],
    comps: &[u8],
    ncomp: usize,
    ranges_raw: &[(u32,u32)],
    bpc: u32,
) {
    if ncomp == 0 || ranges_raw.len() != ncomp { return; }
    let bpc = bpc.clamp(1, 16);
    let maxval = ((1u64 << bpc) - 1).max(1);
    // Map each raw range bound through the SAME transform `unpack_samples_decimated`
    // applied to the samples, so the comparison is exact. A generic `v * 255 / maxval`
    // agrees with the unpacker for 1/2/4/8 bpc but NOT for 12 or 16, where the unpacker
    // keeps the high bits (`raw >> 4`, `raw >> 8`) — there the two disagreed by up to a
    // whole level, so a colour-key range could fail to catch the value it names.
    let scaled: Vec<(u8,u8)> = ranges_raw.iter().map(|&(mn,mx)| {
        let s = |v: u32| -> u8 {
            let v = (v as u64).min(maxval);
            match bpc {
                12 => (v >> 4) as u8,
                16 => (v >> 8) as u8,
                _ => (v * 255 / maxval) as u8,
            }
        };
        (s(mn), s(mx))
    }).collect();
    let px = rgba.len() / 4;
    for i in 0..px {
        let mut inside = true;
        for (c, &(mn, mx)) in scaled.iter().enumerate() {
            let v = comps.get(i*ncomp + c).copied().unwrap_or(0);
            if v < mn || v > mx { inside = false; break; }
        }
        if inside { rgba[i*4+3] = 0; }
    }
}

pub(crate) fn decode_jpeg_rgba(data: &[u8]) -> Option<(u32,u32,Vec<u8>)> {
    decode_jpeg_rgba_decoded(data, false)
}
