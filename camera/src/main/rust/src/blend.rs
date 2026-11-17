//! Compositing blender: **MultiBandBlender** parity with OpenCV `modules/stitching/src/blenders.cpp`
//! Implements:
//!   actual_num_bands=5 auto-crop num_bands=min(actual, ceil(log(max_len)/log2))
//!   dst divisible by 1<<num_bands
//!   weight maps mask/255 CV_32F (our masks 0/1)
//!   Laplacian pyramids iterative (rows+1)/2, Gaussian weight pyramids
//!   gap 3*(1<<bands) alignment TL to multiple 1<<bands (simplified – gap border reflect for quality)
//!   feed weighted laplacian, blend reconstruct pyrUp
//! Falls back to seam-aware feather blend if pyramid fails (low memory / degenerate).

use crate::imgbuf::Rgba;
use crate::sphere::WarpedTile;

/// Separable box blur of a float plane (running-sum, radius r).
/// Each 1D pass normalises by the actual number of samples in its
/// (possibly truncated) window, so a constant plane is preserved
/// everywhere including borders/corners.
fn box_blur(src: &[f32], w: usize, h: usize, r: usize) -> Vec<f32> {
    if r == 0 || w == 0 || h == 0 {
        return src.to_vec();
    }
    let mut tmp = vec![0f32; w * h];
    // horizontal
    for y in 0..h {
        let row = y * w;
        let mut sum = 0.0;
        for x in 0..=r.min(w - 1) {
            sum += src[row + x];
        }
        for x in 0..w {
            let lo = x.saturating_sub(r);
            let hi = (x + r).min(w - 1);
            tmp[row + x] = sum / (hi - lo + 1) as f32;
            let add = x + r + 1;
            let sub = x as isize - r as isize;
            if add < w {
                sum += src[row + add];
            }
            if sub >= 0 {
                sum -= src[row + sub as usize];
            }
        }
    }
    let mut out = vec![0f32; w * h];
    // vertical
    for x in 0..w {
        let mut sum = 0.0;
        for y in 0..=r.min(h - 1) {
            sum += tmp[y * w + x];
        }
        for y in 0..h {
            let lo = y.saturating_sub(r);
            let hi = (y + r).min(h - 1);
            out[y * w + x] = sum / (hi - lo + 1) as f32;
            let add = y + r + 1;
            let sub = y as isize - r as isize;
            if add < h {
                sum += tmp[add * w + x];
            }
            if sub >= 0 {
                sum -= tmp[sub as usize * w + x];
            }
        }
    }
    out
}

// ---------------------------------------------------------------------------
// MultiBand core structures – f32 images for Laplacian accumulation
// ---------------------------------------------------------------------------

#[derive(Clone)]
struct Img3 {
    w: usize,
    h: usize,
    data: Vec<f32>, // w*h*3
}
#[derive(Clone)]
struct WMap {
    w: usize,
    h: usize,
    data: Vec<f32>, // w*h
}

fn reflect_101(idx: isize, max: usize) -> usize {
    let mut i = idx;
    let m = max as isize;
    if m <= 1 {
        return 0;
    }
    // OpenCV BORDER_REFLECT_101: reflect without repeating border
    while i < 0 || i >= m {
        if i < 0 {
            i = -i;
        } else {
            i = 2 * m - i - 2;
        }
    }
    i as usize
}

fn pyr_down_3ch(src: &Img3) -> Img3 {
    // Gaussian blur 5-tap [1 4 6 4 1]/16 separable with reflect_101, then downsample (even pixels)
    let w = src.w;
    let h = src.h;
    if w == 0 || h == 0 {
        return Img3 { w: 0, h: 0, data: Vec::new() };
    }
    // horizontal blur -> tmp
    let mut tmp = vec![0f32; w * h * 3];
    let kernel = [1.0f32, 4.0, 6.0, 4.0, 1.0];
    let ksum = 16.0;
    for y in 0..h {
        for x in 0..w {
            let mut acc = [0f32; 3];
            for k in -2isize..=2 {
                let xs = reflect_101(x as isize + k, w);
                let coeff = kernel[(k + 2) as usize] / ksum;
                let base = (y * w + xs) * 3;
                acc[0] += src.data[base] * coeff;
                acc[1] += src.data[base + 1] * coeff;
                acc[2] += src.data[base + 2] * coeff;
            }
            let base = (y * w + x) * 3;
            tmp[base] = acc[0];
            tmp[base + 1] = acc[1];
            tmp[base + 2] = acc[2];
        }
    }
    // vertical blur -> blurred
    let mut blurred = vec![0f32; w * h * 3];
    for y in 0..h {
        for x in 0..w {
            let mut acc = [0f32; 3];
            for k in -2isize..=2 {
                let ys = reflect_101(y as isize + k, h);
                let coeff = kernel[(k + 2) as usize] / ksum;
                let base = (ys * w + x) * 3;
                acc[0] += tmp[base] * coeff;
                acc[1] += tmp[base + 1] * coeff;
                acc[2] += tmp[base + 2] * coeff;
            }
            let base = (y * w + x) * 3;
            blurred[base] = acc[0];
            blurred[base + 1] = acc[1];
            blurred[base + 2] = acc[2];
        }
    }
    // downsample (w+1)/2
    let nw = w.div_ceil(2);
    let nh = h.div_ceil(2);
    if nw == 0 || nh == 0 {
        return Img3 { w: 0, h: 0, data: Vec::new() };
    }
    let mut data = vec![0f32; nw * nh * 3];
    for y in 0..nh {
        let sy = (y * 2).min(h - 1);
        for x in 0..nw {
            let sx = (x * 2).min(w - 1);
            let s_base = (sy * w + sx) * 3;
            let d_base = (y * nw + x) * 3;
            data[d_base] = blurred[s_base];
            data[d_base + 1] = blurred[s_base + 1];
            data[d_base + 2] = blurred[s_base + 2];
        }
    }
    Img3 { w: nw, h: nh, data }
}

fn pyr_down_1ch(src: &WMap) -> WMap {
    let w = src.w;
    let h = src.h;
    if w == 0 || h == 0 {
        return WMap { w: 0, h: 0, data: Vec::new() };
    }
    let mut tmp = vec![0f32; w * h];
    let kernel = [1.0f32, 4.0, 6.0, 4.0, 1.0];
    let ksum = 16.0;
    for y in 0..h {
        for x in 0..w {
            let mut acc = 0f32;
            for k in -2isize..=2 {
                let xs = reflect_101(x as isize + k, w);
                acc += src.data[y * w + xs] * kernel[(k + 2) as usize] / ksum;
            }
            tmp[y * w + x] = acc;
        }
    }
    let mut blurred = vec![0f32; w * h];
    for y in 0..h {
        for x in 0..w {
            let mut acc = 0f32;
            for k in -2isize..=2 {
                let ys = reflect_101(y as isize + k, h);
                acc += tmp[ys * w + x] * kernel[(k + 2) as usize] / ksum;
            }
            blurred[y * w + x] = acc;
        }
    }
    let nw = w.div_ceil(2);
    let nh = h.div_ceil(2);
    if nw == 0 || nh == 0 {
        return WMap { w: 0, h: 0, data: Vec::new() };
    }
    let mut data = vec![0f32; nw * nh];
    for y in 0..nh {
        let sy = (y * 2).min(h - 1);
        for x in 0..nw {
            let sx = (x * 2).min(w - 1);
            data[y * nw + x] = blurred[sy * w + sx];
        }
    }
    WMap { w: nw, h: nh, data }
}

fn pyr_up_3ch(src: &Img3, dst_w: usize, dst_h: usize) -> Img3 {
    // Proper Gaussian expansion (OpenCV pyrUp): zero-insert src at even
    // coordinates, then separable blur with [1 4 6 4 1]/8 per axis (the
    // /16 pyrDown kernel x2 gain per axis, x4 in 2D). Unlike the previous
    // bilinear upsample this is the expansion matching pyr_down_3ch, so
    // Laplacian reconstruction is (near-)energy-preserving: a constant
    // signal down-then-up (and a weighted blend of identical constants)
    // reconstructs to that constant.
    if src.w == 0 || src.h == 0 || dst_w == 0 || dst_h == 0 {
        return Img3 { w: dst_w, h: dst_h, data: vec![0f32; dst_w * dst_h * 3] };
    }
    let (ew, eh) = (src.w * 2, src.h * 2);
    let mut expanded = vec![0f32; ew * eh * 3];
    for y in 0..src.h {
        for x in 0..src.w {
            let s = (y * src.w + x) * 3;
            let d = ((2 * y) * ew + 2 * x) * 3;
            expanded[d] = src.data[s];
            expanded[d + 1] = src.data[s + 1];
            expanded[d + 2] = src.data[s + 2];
        }
    }
    let kernel = [1.0f32, 4.0, 6.0, 4.0, 1.0];
    let mut tmp = vec![0f32; ew * eh * 3];
    for y in 0..eh {
        for x in 0..ew {
            let mut acc = [0f32; 3];
            for k in -2isize..=2 {
                let xs = reflect_101(x as isize + k, ew);
                let coeff = kernel[(k + 2) as usize] / 8.0;
                let b = (y * ew + xs) * 3;
                acc[0] += expanded[b] * coeff;
                acc[1] += expanded[b + 1] * coeff;
                acc[2] += expanded[b + 2] * coeff;
            }
            let d = (y * ew + x) * 3;
            tmp[d] = acc[0];
            tmp[d + 1] = acc[1];
            tmp[d + 2] = acc[2];
        }
    }
    let mut up = vec![0f32; ew * eh * 3];
    for y in 0..eh {
        for x in 0..ew {
            let mut acc = [0f32; 3];
            for k in -2isize..=2 {
                let ys = reflect_101(y as isize + k, eh);
                let coeff = kernel[(k + 2) as usize] / 8.0;
                let b = (ys * ew + x) * 3;
                acc[0] += tmp[b] * coeff;
                acc[1] += tmp[b + 1] * coeff;
                acc[2] += tmp[b + 2] * coeff;
            }
            let d = (y * ew + x) * 3;
            up[d] = acc[0];
            up[d + 1] = acc[1];
            up[d + 2] = acc[2];
        }
    }
    if dst_w <= ew && dst_h <= eh {
        // Pyramid case: dst is 2*src or 2*src-1 per axis – crop top-left.
        if dst_w == ew && dst_h == eh {
            return Img3 { w: ew, h: eh, data: up };
        }
        let mut data = vec![0f32; dst_w * dst_h * 3];
        for y in 0..dst_h {
            let s = y * ew * 3;
            let d = y * dst_w * 3;
            data[d..d + dst_w * 3].copy_from_slice(&up[s..s + dst_w * 3]);
        }
        return Img3 { w: dst_w, h: dst_h, data };
    }
    // Non-pyramid size (>2x): bilinear-resize the Gaussian expansion.
    let mut data = vec![0f32; dst_w * dst_h * 3];
    for y in 0..dst_h {
        let fy = ((y as f32 + 0.5) * eh as f32 / dst_h as f32 - 0.5).clamp(0.0, (eh - 1) as f32);
        let y0 = fy.floor() as usize;
        let y1 = (y0 + 1).min(eh - 1);
        let ay = fy - y0 as f32;
        for x in 0..dst_w {
            let fx = ((x as f32 + 0.5) * ew as f32 / dst_w as f32 - 0.5).clamp(0.0, (ew - 1) as f32);
            let x0 = fx.floor() as usize;
            let x1 = (x0 + 1).min(ew - 1);
            let ax = fx - x0 as f32;
            let d = (y * dst_w + x) * 3;
            for c in 0..3 {
                let top = up[(y0 * ew + x0) * 3 + c] * (1.0 - ax) + up[(y0 * ew + x1) * 3 + c] * ax;
                let bot = up[(y1 * ew + x0) * 3 + c] * (1.0 - ax) + up[(y1 * ew + x1) * 3 + c] * ax;
                data[d + c] = top * (1.0 - ay) + bot * ay;
            }
        }
    }
    Img3 { w: dst_w, h: dst_h, data }
}

// ---------------------------------------------------------------------------
// Feather fallback (original) – kept and used if multiband fails
// ---------------------------------------------------------------------------

fn feather_blend(tiles: &[WarpedTile], masks: &[Vec<u8>], gain_maps: &[Vec<f32>], gx0: i32, gy0: i32, cw: usize, ch: usize) -> Option<Rgba> {
    let num = tiles.len();
    let mut acc = vec![0f32; cw * ch * 3];
    let mut accw = vec![0f32; cw * ch];

    for ti in 0..num {
        let t = &tiles[ti];
        let gmap = &gain_maps[ti];
        let (tw, th) = (t.img.w, t.img.h);
        let radius = (tw.min(th) / 40).clamp(6, 32);
        let maskf: Vec<f32> = masks[ti].iter().map(|&m| if m != 0 { 1.0 } else { 0.0 }).collect();
        let feather = box_blur(&maskf, tw, th, radius);
        for ly in 0..th {
            for lx in 0..tw {
                let li = ly * tw + lx;
                let c = t.img.get(lx, ly);
                if c[3] == 0 {
                    continue;
                }
                let weight = feather[li];
                if weight <= 0.0 {
                    continue;
                }
                let gain = gmap[li];
                let gxp = (t.corner_x - gx0) as usize + lx;
                let gyp = (t.corner_y - gy0) as usize + ly;
                if gxp >= cw || gyp >= ch {
                    continue;
                }
                let idx = gyp * cw + gxp;
                acc[idx * 3] += (c[0] as f32 * gain).min(255.0) * weight;
                acc[idx * 3 + 1] += (c[1] as f32 * gain).min(255.0) * weight;
                acc[idx * 3 + 2] += (c[2] as f32 * gain).min(255.0) * weight;
                accw[idx] += weight;
            }
        }
    }
    for ti in 0..num {
        let t = &tiles[ti];
        let gmap = &gain_maps[ti];
        for ly in 0..t.img.h {
            for lx in 0..t.img.w {
                let c = t.img.get(lx, ly);
                if c[3] == 0 {
                    continue;
                }
                let gxp = (t.corner_x - gx0) as usize + lx;
                let gyp = (t.corner_y - gy0) as usize + ly;
                if gxp >= cw || gyp >= ch {
                    continue;
                }
                let idx = gyp * cw + gxp;
                if accw[idx] <= 0.0 {
                    let gain = gmap[ly * t.img.w + lx];
                    acc[idx * 3] = (c[0] as f32 * gain).min(255.0);
                    acc[idx * 3 + 1] = (c[1] as f32 * gain).min(255.0);
                    acc[idx * 3 + 2] = (c[2] as f32 * gain).min(255.0);
                    accw[idx] = 1.0;
                }
            }
        }
    }
    let mut out = Rgba::new(cw, ch);
    for i in 0..cw * ch {
        let w = accw[i];
        let d = i * 4;
        if w > 0.0 {
            out.px[d] = (acc[i * 3] / w).round().clamp(0.0, 255.0) as u8;
            out.px[d + 1] = (acc[i * 3 + 1] / w).round().clamp(0.0, 255.0) as u8;
            out.px[d + 2] = (acc[i * 3 + 2] / w).round().clamp(0.0, 255.0) as u8;
            out.px[d + 3] = 255;
        } else {
            out.px[d + 3] = 0;
        }
    }
    Some(crop_to_content(out))
}

// ---------------------------------------------------------------------------
// Public entry: multiband_blend with true Laplacian pyramid
// ---------------------------------------------------------------------------

include!("blend_part1.rs");

#[cfg(test)]
#[path = "blend_tests.rs"]
mod blend_tests;
