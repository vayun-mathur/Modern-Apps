//! Warp frames onto a common canvas (via chained homographies) with feather
//! blending and simple gain compensation.

use crate::imgbuf::Rgba;
use nalgebra::{Matrix3, Vector3};

/// Precomputed edge-distance feather weight for a w×h frame.
pub(crate) fn feather_weights(w: usize, h: usize) -> Vec<f32> {
    let mut wt = vec![0f32; w * h];
    for y in 0..h {
        let dy = (y.min(h - 1 - y)) as f32 + 1.0;
        for x in 0..w {
            let dx = (x.min(w - 1 - x)) as f32 + 1.0;
            wt[y * w + x] = dx.min(dy);
        }
    }
    // normalize to [0,1]
    let max = wt.iter().cloned().fold(1.0f32, f32::max);
    for v in wt.iter_mut() {
        *v /= max;
    }
    wt
}

#[inline]
fn apply(h: &Matrix3<f64>, x: f64, y: f64) -> (f64, f64) {
    let v = h * Vector3::new(x, y, 1.0);
    if v.z.abs() < 1e-12 {
        (v.x, v.y)
    } else {
        (v.x / v.z, v.y / v.z)
    }
}

/// Mean luma of an RGBA frame (opaque pixels only).
pub fn mean_luma(f: &Rgba) -> f32 {
    let mut sum = 0f64;
    let mut n = 0f64;
    for i in 0..f.w * f.h {
        if f.px[i * 4 + 3] == 0 {
            continue;
        }
        let r = f.px[i * 4] as f64;
        let g = f.px[i * 4 + 1] as f64;
        let b = f.px[i * 4 + 2] as f64;
        sum += 0.299 * r + 0.587 * g + 0.114 * b;
        n += 1.0;
    }
    if n > 0.0 { (sum / n) as f32 } else { 0.0 }
}

/// Warp all `frames` into the reference plane using `h_to_ref[i]` (frame i ->
/// reference coords), apply per-frame `gains`, feather-blend, and crop to the
/// covered region. Returns the stitched RGBA image.
pub fn warp_and_blend(
    frames: &[Rgba],
    h_to_ref: &[Matrix3<f64>],
    gains: &[f32],
    max_canvas: usize,
) -> Option<Rgba> {
    if frames.is_empty() || frames.len() != h_to_ref.len() {
        return None;
    }
    // 1) canvas bounds from warped frame corners
    let mut min_x = f64::MAX;
    let mut min_y = f64::MAX;
    let mut max_x = f64::MIN;
    let mut max_y = f64::MIN;
    for (f, h) in frames.iter().zip(h_to_ref.iter()) {
        let corners = [
            (0.0, 0.0),
            ((f.w - 1) as f64, 0.0),
            (0.0, (f.h - 1) as f64),
            ((f.w - 1) as f64, (f.h - 1) as f64),
        ];
        for &(cx, cy) in corners.iter() {
            let (x, y) = apply(h, cx, cy);
            min_x = min_x.min(x);
            min_y = min_y.min(y);
            max_x = max_x.max(x);
            max_y = max_y.max(y);
        }
    }
    if !min_x.is_finite() || !max_x.is_finite() {
        return None;
    }
    let cw = (max_x - min_x).ceil() as i64 + 1;
    let ch = (max_y - min_y).ceil() as i64 + 1;
    if cw <= 0 || ch <= 0 || cw as usize > max_canvas || ch as usize > max_canvas {
        return None;
    }
    let cw = cw as usize;
    let ch = ch as usize;

    let mut acc = vec![0f32; cw * ch * 3];
    let mut accw = vec![0f32; cw * ch];

    for (fi, f) in frames.iter().enumerate() {
        let h = h_to_ref[fi];
        let h_inv = match h.try_inverse() {
            Some(m) => m,
            None => continue,
        };
        let gain = gains.get(fi).copied().unwrap_or(1.0);
        let wt = feather_weights(f.w, f.h);

        // Warped bbox of this frame on canvas.
        let corners = [
            (0.0, 0.0),
            ((f.w - 1) as f64, 0.0),
            (0.0, (f.h - 1) as f64),
            ((f.w - 1) as f64, (f.h - 1) as f64),
        ];
        let (mut bx0, mut by0, mut bx1, mut by1) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
        for &(cx, cy) in corners.iter() {
            let (x, y) = apply(&h, cx, cy);
            bx0 = bx0.min(x);
            by0 = by0.min(y);
            bx1 = bx1.max(x);
            by1 = by1.max(y);
        }
        let x0 = ((bx0 - min_x).floor() as i64).max(0) as usize;
        let y0 = ((by0 - min_y).floor() as i64).max(0) as usize;
        let x1 = (((bx1 - min_x).ceil() as i64).min(cw as i64 - 1)).max(0) as usize;
        let y1 = (((by1 - min_y).ceil() as i64).min(ch as i64 - 1)).max(0) as usize;

        for cy in y0..=y1 {
            for cx in x0..=x1 {
                // canvas -> reference -> source frame
                let rx = cx as f64 + min_x;
                let ry = cy as f64 + min_y;
                let (sx, sy) = apply(&h_inv, rx, ry);
                if let Some(c) = f.sample(sx as f32, sy as f32) {
                    if c[3] < 8.0 {
                        continue;
                    }
                    // feather weight from nearest source pixel
                    let ix = (sx as usize).min(f.w - 1);
                    let iy = (sy as usize).min(f.h - 1);
                    let w = wt[iy * f.w + ix];
                    if w <= 0.0 {
                        continue;
                    }
                    let idx = cy * cw + cx;
                    acc[idx * 3] += (c[0] * gain).min(255.0) * w;
                    acc[idx * 3 + 1] += (c[1] * gain).min(255.0) * w;
                    acc[idx * 3 + 2] += (c[2] * gain).min(255.0) * w;
                    accw[idx] += w;
                }
            }
        }
    }

    // 2) resolve accumulation + crop to covered bbox
    let (mut minx, mut miny, mut maxx, mut maxy) = (cw, ch, 0usize, 0usize);
    let mut any = false;
    for y in 0..ch {
        for x in 0..cw {
            if accw[y * cw + x] > 0.0 {
                any = true;
                minx = minx.min(x);
                miny = miny.min(y);
                maxx = maxx.max(x);
                maxy = maxy.max(y);
            }
        }
    }
    if !any {
        return None;
    }
    let ow = maxx - minx + 1;
    let oh = maxy - miny + 1;
    let mut out = Rgba::new(ow, oh);
    for y in 0..oh {
        for x in 0..ow {
            let sidx = (y + miny) * cw + (x + minx);
            let w = accw[sidx];
            let didx = (y * ow + x) * 4;
            if w > 0.0 {
                out.px[didx] = (acc[sidx * 3] / w).round().clamp(0.0, 255.0) as u8;
                out.px[didx + 1] = (acc[sidx * 3 + 1] / w).round().clamp(0.0, 255.0) as u8;
                out.px[didx + 2] = (acc[sidx * 3 + 2] / w).round().clamp(0.0, 255.0) as u8;
                out.px[didx + 3] = 255;
            } else {
                out.px[didx + 3] = 0;
            }
        }
    }
    Some(out)
}
