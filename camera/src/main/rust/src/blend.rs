//! Compositing blender: a **seam-aware feather** blend. Each pixel is taken from
//! the single frame the seam finder assigned to it (sharp — so parallax from
//! close objects isn't ghosted by averaging the whole overlap), with only a
//! narrow feather across the seam. The weight is a convex combination of the
//! actual pixels, so it can never overshoot to white like a Laplacian pyramid.
//! After blending, the panorama is cropped to the maximal all-opaque rectangle.

use crate::imgbuf::Rgba;
use crate::sphere::WarpedTile;

/// Separable box blur of a float plane (running-sum, radius r).
fn box_blur(src: &[f32], w: usize, h: usize, r: usize) -> Vec<f32> {
    if r == 0 {
        return src.to_vec();
    }
    let mut tmp = vec![0f32; w * h];
    let norm = 1.0 / (2 * r + 1) as f32;
    // horizontal
    for y in 0..h {
        let row = y * w;
        let mut sum = 0.0;
        for x in 0..=r.min(w - 1) {
            sum += src[row + x];
        }
        for x in 0..w {
            tmp[row + x] = sum * norm;
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
            out[y * w + x] = sum * norm;
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

pub fn multiband_blend(tiles: &[WarpedTile], masks: &[Vec<u8>], gain_maps: &[Vec<f32>]) -> Option<Rgba> {
    let num = tiles.len();
    if num == 0 {
        return None;
    }
    let mut gx0 = i32::MAX;
    let mut gy0 = i32::MAX;
    let mut gx1 = i32::MIN;
    let mut gy1 = i32::MIN;
    for t in tiles {
        gx0 = gx0.min(t.corner_x);
        gy0 = gy0.min(t.corner_y);
        gx1 = gx1.max(t.corner_x + t.img.w as i32);
        gy1 = gy1.max(t.corner_y + t.img.h as i32);
    }
    let cw = (gx1 - gx0) as usize;
    let ch = (gy1 - gy0) as usize;
    if cw == 0 || ch == 0 || cw > 20000 || ch > 20000 {
        return None;
    }

    let mut acc = vec![0f32; cw * ch * 3];
    let mut accw = vec![0f32; cw * ch];

    for ti in 0..num {
        let t = &tiles[ti];
        let gmap = &gain_maps[ti];
        let (tw, th) = (t.img.w, t.img.h);
        // Feather = blurred seam mask: ~1 deep in this frame's owned region,
        // tapering to ~0 a few px past the seam. Narrow blur => sharp interiors,
        // thin transition, minimal parallax ghosting.
        let radius = (tw.min(th) / 40).clamp(6, 32);
        let maskf: Vec<f32> = masks[ti].iter().map(|&m| if m != 0 { 1.0 } else { 0.0 }).collect();
        let feather = box_blur(&maskf, tw, th, radius);
        for ly in 0..th {
            for lx in 0..tw {
                let li = ly * tw + lx;
                let c = t.img.get(lx, ly);
                if c[3] == 0 {
                    continue; // frame doesn't cover here
                }
                let weight = feather[li];
                if weight <= 0.0 {
                    continue;
                }
                let gain = gmap[li];
                let gxp = (t.corner_x - gx0) as usize + lx;
                let gyp = (t.corner_y - gy0) as usize + ly;
                let idx = gyp * cw + gxp;
                acc[idx * 3] += (c[0] as f32 * gain).min(255.0) * weight;
                acc[idx * 3 + 1] += (c[1] as f32 * gain).min(255.0) * weight;
                acc[idx * 3 + 2] += (c[2] as f32 * gain).min(255.0) * weight;
                accw[idx] += weight;
            }
        }
    }

    // Any covered pixel that got zero feather weight (e.g. a frame's owned region
    // where the blurred mask vanished) — fill from whichever frame covers it.
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

/// Crop rectangle = maximal all-opaque rectangle. With the coverage a proper
/// partition of the frames (no interior holes), this is the large landscape
/// rectangle spanning all frames, with the black curved borders removed.
fn content_rect(valid: &[bool], w: usize, h: usize) -> (usize, usize, usize, usize) {
    let mut heights = vec![0usize; w];
    let mut best = (0usize, 0usize, 0usize, 0usize);
    let mut best_area = 0usize;
    for y in 0..h {
        for x in 0..w {
            heights[x] = if valid[y * w + x] { heights[x] + 1 } else { 0 };
        }
        let mut stack: Vec<usize> = Vec::new();
        let mut x = 0usize;
        while x <= w {
            let cur = if x == w { 0 } else { heights[x] };
            if stack.is_empty() || cur >= heights[*stack.last().unwrap()] {
                stack.push(x);
                x += 1;
            } else {
                let top = stack.pop().unwrap();
                let height = heights[top];
                let left = if stack.is_empty() { 0 } else { *stack.last().unwrap() + 1 };
                let width = x - left;
                let area = height * width;
                if area > best_area {
                    best_area = area;
                    best = (left, y + 1 - height, width, height);
                }
            }
        }
    }
    best
}

fn crop_to_content(img: Rgba) -> Rgba {
    let valid: Vec<bool> = (0..img.w * img.h).map(|i| img.px[i * 4 + 3] == 255).collect();
    let (rx, ry, rw, rh) = content_rect(&valid, img.w, img.h);
    if rw == 0 || rh == 0 {
        return img;
    }
    let mut out = Rgba::new(rw, rh);
    for y in 0..rh {
        for x in 0..rw {
            let s = ((ry + y) * img.w + (rx + x)) * 4;
            let d = (y * rw + x) * 4;
            out.px[d..d + 4].copy_from_slice(&img.px[s..s + 4]);
        }
    }
    out
}
