//! Compositing blender. Uses a FeatherBlender (distance-weighted average of the
//! actual warped pixels). Because the result is a convex combination of input
//! pixel values, it can never overshoot the valid range — unlike a Laplacian
//! multi-band pyramid, which rings and clips to white at seams in smooth regions.
//! After blending, the panorama is cropped to the maximal all-opaque rectangle so
//! there are no black borders.

use crate::imgbuf::Rgba;
use crate::sphere::WarpedTile;

/// Manhattan distance (in pixels) from each covered pixel to the nearest
/// uncovered pixel — used as the feather weight (large in the interior, small at
/// the coverage edge, so seams blend smoothly). Two-pass chamfer transform.
fn coverage_distance(img: &Rgba) -> Vec<f32> {
    let (w, h) = (img.w, img.h);
    let big = (w + h) as i32;
    let mut d = vec![0i32; w * h];
    for i in 0..w * h {
        d[i] = if img.px[i * 4 + 3] != 0 { big } else { 0 };
    }
    // forward
    for y in 0..h {
        for x in 0..w {
            let i = y * w + x;
            if d[i] == 0 {
                continue;
            }
            if x > 0 {
                d[i] = d[i].min(d[i - 1] + 1);
            }
            if y > 0 {
                d[i] = d[i].min(d[i - w] + 1);
            }
        }
    }
    // backward
    for y in (0..h).rev() {
        for x in (0..w).rev() {
            let i = y * w + x;
            if d[i] == 0 {
                continue;
            }
            if x + 1 < w {
                d[i] = d[i].min(d[i + 1] + 1);
            }
            if y + 1 < h {
                d[i] = d[i].min(d[i + w] + 1);
            }
        }
    }
    d.iter().map(|&v| v as f32).collect()
}

pub fn multiband_blend(tiles: &[WarpedTile], _masks: &[Vec<u8>], gain_maps: &[Vec<f32>]) -> Option<Rgba> {
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
        let dist = coverage_distance(&t.img);
        for ly in 0..t.img.h {
            for lx in 0..t.img.w {
                let li = ly * t.img.w + lx;
                let c = t.img.get(lx, ly);
                if c[3] == 0 {
                    continue;
                }
                // feather weight: distance from the coverage edge (+1 so edge
                // pixels still contribute), squared for a sharper interior bias.
                let dw = dist[li] + 1.0;
                let weight = dw * dw;
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
