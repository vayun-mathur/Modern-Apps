/// Split a polyline into the "on" runs of a dash pattern (§8.4.3.6).
fn apply_dash(pts: &[(f64, f64)], dash: &[f32], phase: f32) -> Vec<Vec<(f64, f64)>> {
    let pat: Vec<f64> = dash.iter().map(|v| *v as f64).filter(|v| *v >= 0.0).collect();
    if pat.is_empty() || pat.iter().all(|v| *v <= 0.0) {
        return vec![pts.to_vec()];
    }
    let total: f64 = pat.iter().sum();
    let mut idx = 0usize;
    let mut on = true;
    let mut rem = pat[0];
    let mut ph = (phase as f64).rem_euclid(total * if pat.len() % 2 == 1 { 2.0 } else { 1.0 });
    while ph > 0.0 {
        if ph >= rem {
            ph -= rem;
            idx = (idx + 1) % pat.len();
            on = !on;
            rem = pat[idx];
        } else {
            rem -= ph;
            ph = 0.0;
        }
    }
    let mut out: Vec<Vec<(f64, f64)>> = Vec::new();
    let mut cur: Vec<(f64, f64)> = Vec::new();
    if on {
        if let Some(p) = pts.first() {
            cur.push(*p);
        }
    }
    for seg in pts.windows(2) {
        let (x0s, y0s) = seg[0];
        let (x1, y1) = seg[1];
        let seg_len = ((x1 - x0s).powi(2) + (y1 - y0s).powi(2)).sqrt();
        if seg_len < 1e-12 {
            continue;
        }
        let (ux, uy) = ((x1 - x0s) / seg_len, (y1 - y0s) / seg_len);
        let (mut x0, mut y0) = (x0s, y0s);
        let mut left = seg_len;
        while left > 1e-12 {
            if rem <= 1e-12 {
                idx = (idx + 1) % pat.len();
                on = !on;
                rem = pat[idx].max(1e-9);
                if on {
                    cur = vec![(x0, y0)];
                } else if cur.len() >= 2 {
                    out.push(std::mem::take(&mut cur));
                } else {
                    cur.clear();
                }
                continue;
            }
            let step = left.min(rem);
            let (px, py) = (x0 + ux * step, y0 + uy * step);
            if on {
                cur.push((px, py));
            }
            x0 = px;
            y0 = py;
            left -= step;
            rem -= step;
        }
    }
    if cur.len() >= 2 {
        out.push(cur);
    }
    out
}

fn stroke_contours(
    pts: &[(f64, f64)],
    width: f64,
    cap: u8,
    join: u8,
    dash: &[f32],
    phase: f32,
) -> Vec<Vec<(f64, f64)>> {
    let hw = (width / 2.0).max(0.35);
    let mut out: Vec<Vec<(f64, f64)>> = Vec::new();
    for run in apply_dash(pts, dash, phase) {
        if run.len() < 2 {
            if run.len() == 1 && cap == 1 {
                out.push(orient_ccw(disk(run[0].0, run[0].1, hw)));
            }
            continue;
        }
        let mut normals: Vec<(f64, f64)> = Vec::new();
        let last_seg = run.len() - 2;
        for (si, seg) in run.windows(2).enumerate() {
            let (x0, y0) = seg[0];
            let (x1, y1) = seg[1];
            let (dx, dy) = (x1 - x0, y1 - y0);
            let len = (dx * dx + dy * dy).sqrt();
            if len < 1e-9 {
                normals.push((0.0, 0.0));
                continue;
            }
            let (nx, ny) = (-dy / len * hw, dx / len * hw);
            normals.push((nx, ny));
            let (mut sx0, mut sy0, mut sx1, mut sy1) = (x0, y0, x1, y1);
            if cap == 2 {
                // Projecting square cap: extend the terminal segments.
                let (ux, uy) = (dx / len * hw, dy / len * hw);
                if si == 0 {
                    sx0 -= ux;
                    sy0 -= uy;
                }
                if si == last_seg {
                    sx1 += ux;
                    sy1 += uy;
                }
            }
            out.push(orient_ccw(vec![
                (sx0 + nx, sy0 + ny),
                (sx1 + nx, sy1 + ny),
                (sx1 - nx, sy1 - ny),
                (sx0 - nx, sy0 - ny),
            ]));
        }
        // Joins at interior vertices.
        for i in 1..run.len().saturating_sub(1) {
            let (x, y) = run[i];
            if join == 1 {
                out.push(orient_ccw(disk(x, y, hw)));
            } else {
                // Bevel; also the (under-)approximation used for miter.
                let n0 = normals[i - 1];
                let n1 = normals[i];
                out.push(orient_ccw(vec![(x, y), (x + n0.0, y + n0.1), (x + n1.0, y + n1.1)]));
                out.push(orient_ccw(vec![(x, y), (x - n0.0, y - n0.1), (x - n1.0, y - n1.1)]));
            }
        }
        if cap == 1 {
            let a = run[0];
            let b = run[run.len() - 1];
            out.push(orient_ccw(disk(a.0, a.1, hw)));
            out.push(orient_ccw(disk(b.0, b.1, hw)));
        }
    }
    out
}

// ---------------------------------------------------------------------------
// The prim rasteriser
// ---------------------------------------------------------------------------

/// What a rasterisation had to skip. Non-zero values make a comparison
/// meaningless, so [`compare_page`] refuses to grade a page with any.
#[derive(Default, Debug, PartialEq, Eq)]
struct Skipped {
    /// `Prim::Text` with `outline == false`: substitute typeface, no contours.
    substitute_text: usize,
    /// `Prim::Image` with `format == 1` (undecoded JPEG passthrough).
    jpeg_images: usize,
    /// Any prim carrying a non-Normal blend mode.
    blended: usize,
}

fn argb_to_rgb_a(argb: u32) -> ([f32; 3], f32) {
    let a = ((argb >> 24) & 0xFF) as f32 / 255.0;
    let r = ((argb >> 16) & 0xFF) as f32 / 255.0;
    let g = ((argb >> 8) & 0xFF) as f32 / 255.0;
    let b = (argb & 0xFF) as f32 / 255.0;
    ([r, g, b], a)
}

struct Rast {
    w: usize,
    h: usize,
    scale: f64,
    page_h: f64,
    /// Layer stack; the last entry is the current draw target.
    layers: Vec<Canvas>,
    clip: Vec<f32>,
    clip_stack: Vec<Vec<f32>>,
    /// Group alphas, innermost last.
    group_alpha: Vec<f32>,
    soft: Vec<SoftState>,
    skipped: Skipped,
}

struct SoftState {
    mask_type: u8,
    transfer: Option<Box<[u8; 256]>>,
    content: Option<Canvas>,
}

impl Rast {
    /// Prim display space (y up, origin bottom-left) → device pixels (y down).
    fn dev(&self, x: f64, y: f64) -> (f64, f64) {
        (x * self.scale, (self.page_h - y) * self.scale)
    }

    fn cur(&mut self) -> &mut Canvas {
        let n = self.layers.len() - 1;
        &mut self.layers[n]
    }

    fn fill_contours(&mut self, contours: &[Vec<(f64, f64)>], even_odd: bool, argb: u32) {
        let (rgb, a) = argb_to_rgb_a(argb);
        if a <= 0.0 {
            return;
        }
        let cov = poly_coverage(self.w, self.h, contours, even_odd);
        let coverage: Vec<f32> = cov.iter().zip(self.clip.iter()).map(|(cov_i, clip_i)| cov_i * clip_i).take(self.w * self.h).collect();
        for (i, c) in coverage.iter().enumerate() {
            if *c > 0.0 {
                self.cur().blend_px(i, rgb, a * c);
            }
        }
    }

    fn draw_image(&mut self, ctm: &Mat, w: u32, h: u32, data: &[u8], alpha: f32) {
        if w == 0 || h == 0 || data.len() < (w as usize * h as usize * 4) {
            return;
        }
        // Unit square → device pixels.
        let s = self.scale;
        let ph = self.page_h;
        let to_dev: Mat = mat_mul(ctm, &[s, 0.0, 0.0, -s, 0.0, ph * s]);
        let inv = match mat_inverse_checked(&to_dev) {
            Some(m) => m,
            None => return,
        };
        // Device bbox of the transformed unit square.
        let corners = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]
            .map(|(u, v)| transform(&to_dev, u, v));
        let (mut x0, mut y0, mut x1, mut y1) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
        for (x, y) in corners {
            x0 = x0.min(x);
            y0 = y0.min(y);
            x1 = x1.max(x);
            y1 = y1.max(y);
        }
        let px0 = (x0.floor().max(0.0)) as usize;
        let py0 = (y0.floor().max(0.0)) as usize;
        let px1 = (x1.ceil().min(self.w as f64)).max(0.0) as usize;
        let py1 = (y1.ceil().min(self.h as f64)).max(0.0) as usize;
        for py in py0..py1 {
            for px in px0..px1 {
                let i = py * self.w + px;
                let clip = self.clip[i];
                if clip <= 0.0 {
                    continue;
                }
                let mut acc = [0f32; 4];
                let mut n = 0f32;
                for sy in 0..2 {
                    for sx in 0..2 {
                        let dx = px as f64 + (sx as f64 + 0.5) / 2.0;
                        let dy = py as f64 + (sy as f64 + 0.5) / 2.0;
                        let (u, v) = transform(&inv, dx, dy);
                        n += 1.0;
                        if !(0.0..1.0).contains(&u) || !(0.0..1.0).contains(&v) {
                            continue;
                        }
                        // Row 0 is the TOP of the unit square (v = 1), §8.9.5.2.
                        let col = ((u * w as f64) as usize).min(w as usize - 1);
                        let row = (((1.0 - v) * h as f64) as usize).min(h as usize - 1);
                        let o = (row * w as usize + col) * 4;
                        let sa = data[o + 3] as f32 / 255.0;
                        acc[0] += data[o] as f32 / 255.0 * sa;
                        acc[1] += data[o + 1] as f32 / 255.0 * sa;
                        acc[2] += data[o + 2] as f32 / 255.0 * sa;
                        acc[3] += sa;
                    }
                }
                if acc[3] <= 0.0 {
                    continue;
                }
                let a = acc[3] / n * alpha * clip;
                let rgb = [acc[0] / acc[3], acc[1] / acc[3], acc[2] / acc[3]];
                self.cur().blend_px(i, rgb, a);
            }
        }
    }
}

/// Rasterise a rendered page into a canvas plus the tally of what was skipped.
fn rasterize(page: &PageData, scale: f32) -> (Canvas, Skipped) {
    let w = (page.width as f64 * scale as f64).floor().max(1.0) as usize;
    let h = (page.height as f64 * scale as f64).floor().max(1.0) as usize;
    let mut r = Rast {
        w,
        h,
        scale: scale as f64,
        page_h: page.height as f64,
        layers: vec![Canvas::opaque_white(w, h)],
        clip: vec![1.0; w * h],
        clip_stack: Vec::new(),
        group_alpha: Vec::new(),
        soft: Vec::new(),
        skipped: Skipped::default(),
    };
    let galpha = |r: &Rast| r.group_alpha.iter().product::<f32>();

    for prim in &page.prims {
        match prim {
            Prim::Fill { argb, even_odd, contours, blend } => {
                if !matches!(blend, BlendMode::Normal) {
                    r.skipped.blended += 1;
                }
                let cs: Vec<Vec<(f64, f64)>> = contours
                    .iter()
                    .map(|c| c.iter().map(|&(x, y)| r.dev(x as f64, y as f64)).collect())
                    .collect();
                let a = galpha(&r);
                let argb = apply_alpha_to_argb(*argb, a as f64);
                r.fill_contours(&cs, *even_odd, argb);
            }
            Prim::Stroke { argb, width, dash, dash_phase, cap, join, miter: _, pts, blend } => {
                if !matches!(blend, BlendMode::Normal) {
                    r.skipped.blended += 1;
                }
                let dpts: Vec<(f64, f64)> =
                    pts.iter().map(|&(x, y)| r.dev(x as f64, y as f64)).collect();
                let ddash: Vec<f32> = dash.iter().map(|v| v * scale).collect();
                let cs = stroke_contours(
                    &dpts,
                    *width as f64 * scale as f64,
                    *cap,
                    *join,
                    &ddash,
                    *dash_phase * scale,
                );
                let a = galpha(&r);
                let argb = apply_alpha_to_argb(*argb, a as f64);
                r.fill_contours(&cs, false, argb);
            }
            Prim::Image { ctm, w: iw, h: ih, format, data, alpha, blend } => {
                if !matches!(blend, BlendMode::Normal) {
                    r.skipped.blended += 1;
                }
                if *format != 0 {
                    r.skipped.jpeg_images += 1;
                    continue;
                }
                let a = *alpha * galpha(&r);
                r.draw_image(ctm, *iw, *ih, data, a);
            }
            Prim::ImageTiled { ctm, w: iw, h: ih, data, xstep: _, ystep: _, i0, j0, nx, ny, alpha, blend } => {
                if !matches!(blend, BlendMode::Normal) {
                    r.skipped.blended += 1;
                }
                let a = *alpha * galpha(&r);
                for j in *j0..(*j0 + *ny as i32) {
                    for i in *i0..(*i0 + *nx as i32) {
                        let cell = mat_mul(&translate(i as f64, j as f64), ctm);
                        r.draw_image(&cell, *iw, *ih, data, a);
                    }
                }
            }
            Prim::ClipPush { even_odd, pts, path_ops } => {
                let contours = clip_contours(&r, pts, path_ops);
                let cov = poly_coverage(r.w, r.h, &contours, *even_odd);
                r.clip_stack.push(r.clip.clone());
                for (clip_i, cov_i) in r.clip.iter_mut().zip(cov.iter()) {
                    *clip_i *= cov_i;
                }
            }
            Prim::ClipPop => {
                if let Some(prev) = r.clip_stack.pop() {
                    r.clip = prev;
                }
            }
            Prim::TextClipApply => {}
            Prim::GroupPush { isolated: _, knockout: _, alpha, blend } => {
                if !matches!(blend, BlendMode::Normal) {
                    r.skipped.blended += 1;
                }
                r.layers.push(Canvas::transparent(w, h));
                r.group_alpha.push(*alpha);
            }
            Prim::GroupPop => {
                if r.layers.len() > 1 {
                    let layer = r.layers.pop().unwrap_or_else(|| Canvas::transparent(w, h));
                    let _ = r.group_alpha.pop();
                    // Group alpha was already folded into each prim's colour, so
                    // compositing the layer at 1.0 here avoids applying it twice.
                    r.cur().composite_layer(&layer, 1.0, None);
                }
            }
            Prim::SoftMaskPush { mask_type } => {
                r.soft.push(SoftState { mask_type: *mask_type, transfer: None, content: None });
                r.layers.push(Canvas::transparent(w, h));
            }
            Prim::SoftMaskTransfer(lut) => {
                if let Some(s) = r.soft.last_mut() {
                    s.transfer = Some(lut.clone());
                }
            }
            Prim::SoftMaskContent => {
                let content = r.layers.pop().unwrap_or_else(|| Canvas::transparent(w, h));
                if let Some(s) = r.soft.last_mut() {
                    s.content = Some(content);
                }
                let lum = r.soft.last().map(|s| s.mask_type == 1).unwrap_or(false);
                r.layers.push(if lum {
                    Canvas::new(w, h, [0.0, 0.0, 0.0, 1.0])
                } else {
                    Canvas::transparent(w, h)
                });
            }
            Prim::SoftMaskPop => {
                let maskc = r.layers.pop().unwrap_or_else(|| Canvas::transparent(w, h));
                if let Some(s) = r.soft.pop() {
                    let mut mask = vec![0f32; w * h];
                    for (i, mask_i) in mask.iter_mut().enumerate().take(w * h) {
                        let p = maskc.px[i];
                        let v = if s.mask_type == 1 {
                            // Luminosity, §11.6.5.2: Y of the (premultiplied)
                            // colour over the black backdrop.
                            0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2]
                        } else {
                            p[3]
                        };
                        let v = match &s.transfer {
                            Some(lut) => {
                                let idx = (v.clamp(0.0, 1.0) * 255.0 + 0.5) as usize;
                                lut[idx.min(255)] as f32 / 255.0
                            }
                            None => v.clamp(0.0, 1.0),
                        };
                        *mask_i = v;
                    }
                    if let Some(content) = s.content {
                        r.cur().composite_layer(&content, 1.0, Some(&mask));
                    }
                }
            }
            Prim::Text { outline, render_mode, argb, text, .. } => {
                // Outline text is already present as Fill/Stroke prims; this
                // record exists only for selection. Invisible / clip-only modes
                // and zero alpha paint nothing.
                if *outline || matches!(render_mode, 3 | 7) || (argb >> 24) == 0 || text.is_empty()
                {
                    continue;
                }
                r.skipped.substitute_text += 1;
            }
        }
    }
    let canvas = r.layers.swap_remove(0);
    (canvas, r.skipped)
}
