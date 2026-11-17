//! Port of `BlurData.kt`: separable Gaussian blur, motion blur, radial/spin
//! blur, masked lens-blur. Previously used `rayon` (6 crates) only for row
//! parallel `par_chunks_mut`. Now stdlib only – no external dep, trivial 1-function usage.

use crate::pixel::*;

pub fn gaussian_kernel(radius: i32) -> Vec<f32> {
    if radius <= 0 {
        // Degenerate kernel is the identity impulse; sigma would be 0
        // (radius 0 -> NaN weights) or the size negative (radius < 0).
        return vec![1.0];
    }
    let size = (radius * 2 + 1) as usize;
    let mut kernel = vec![0f32; size];
    let sigma = radius as f32 / 3f32;
    let mut sum = 0f32;
    for (i, k) in kernel.iter_mut().enumerate() {
        let x = (i as i32 - radius) as f32;
        *k = (-(x * x) / (2f32 * sigma * sigma)).exp();
        sum += *k;
    }
    for k in &mut kernel {
        *k /= sum;
    }
    kernel
}

pub fn gaussian_blur(pixels: &[i32], w: usize, h: usize, radius: i32) -> Vec<i32> {
    if radius <= 0 {
        return pixels.to_vec();
    }
    let kernel = gaussian_kernel(radius);
    let rad = radius as isize;
    let mut temp = vec![0i32; w * h];
    // horizontal
    for y in 0..h {
        for x in 0..w {
            let (mut rr, mut gg, mut bb, mut aa) = (0f32, 0f32, 0f32, 0f32);
            for kk in -rad..=rad {
                let sx = clamp_i(x as i32 + kk as i32, 0, w as i32 - 1) as usize;
                let px = pixels[y * w + sx];
                let weight = kernel[(kk + rad) as usize];
                aa += a(px) as f32 * weight;
                rr += r(px) as f32 * weight;
                gg += g(px) as f32 * weight;
                bb += b(px) as f32 * weight;
            }
            temp[y * w + x] = pack(
                clamp_i(aa as i32, 0, 255),
                clamp_i(rr as i32, 0, 255),
                clamp_i(gg as i32, 0, 255),
                clamp_i(bb as i32, 0, 255),
            );
        }
    }
    let mut output = vec![0i32; w * h];
    for y in 0..h {
        for x in 0..w {
            let (mut rr, mut gg, mut bb, mut aa) = (0f32, 0f32, 0f32, 0f32);
            for kk in -rad..=rad {
                let sy = clamp_i(y as i32 + kk as i32, 0, h as i32 - 1) as usize;
                let px = temp[sy * w + x];
                let weight = kernel[(kk + rad) as usize];
                aa += a(px) as f32 * weight;
                rr += r(px) as f32 * weight;
                gg += g(px) as f32 * weight;
                bb += b(px) as f32 * weight;
            }
            output[y * w + x] = pack(
                clamp_i(aa as i32, 0, 255),
                clamp_i(rr as i32, 0, 255),
                clamp_i(gg as i32, 0, 255),
                clamp_i(bb as i32, 0, 255),
            );
        }
    }
    output
}

fn motion_blur(src: &[i32], w: usize, h: usize, length: i32, angle: f32) -> Vec<i32> {
    let mut out = vec![0i32; w * h];
    let rad = (angle as f64).to_radians();
    let dx = rad.cos() as f32;
    let dy = rad.sin() as f32;
    let half = length / 2;
    for y in 0..h {
        for x in 0..w {
            let (mut aa, mut rr, mut gg, mut bb, mut n) = (0f32, 0f32, 0f32, 0f32, 0f32);
            for k in -half..=half {
                let sx = clamp_i((x as f32 + dx * k as f32) as i32, 0, w as i32 - 1) as usize;
                let sy = clamp_i((y as f32 + dy * k as f32) as i32, 0, h as i32 - 1) as usize;
                let p = src[sy * w + sx];
                aa += a(p) as f32;
                rr += r(p) as f32;
                gg += g(p) as f32;
                bb += b(p) as f32;
                n += 1f32;
            }
            out[y * w + x] = pack((aa / n) as i32, (rr / n) as i32, (gg / n) as i32, (bb / n) as i32);
        }
    }
    out
}

fn radial_blur(src: &[i32], w: usize, h: usize, amount: f32, center_x: f32, center_y: f32, spin: bool) -> Vec<i32> {
    let mut out = vec![0i32; w * h];
    let cx = center_x * w as f32;
    let cy = center_y * h as f32;
    let samples = clamp_i((amount * 0.2f32) as i32, 3, 30);
    let strength = amount / 100f32;
    for y in 0..h {
        for x in 0..w {
            let (mut aa, mut rr, mut gg, mut bb) = (0f32, 0f32, 0f32, 0f32);
            let odx = x as f32 - cx;
            let ody = y as f32 - cy;
            for s in 0..samples {
                let t = s as f32 / samples as f32;
                let (sx, sy) = if spin {
                    let ang = -strength * 0.3f32 * t;
                    let cos_a = (ang as f64).cos() as f32;
                    let sin_a = (ang as f64).sin() as f32;
                    (cx + (odx * cos_a - ody * sin_a), cy + (odx * sin_a + ody * cos_a))
                } else {
                    let scale = 1f32 - strength * t;
                    (cx + odx * scale, cy + ody * scale)
                };
                let ix = clamp_i(sx as i32, 0, w as i32 - 1) as usize;
                let iy = clamp_i(sy as i32, 0, h as i32 - 1) as usize;
                let p = src[iy * w + ix];
                aa += a(p) as f32;
                rr += r(p) as f32;
                gg += g(p) as f32;
                bb += b(p) as f32;
            }
            let n = samples as f32;
            out[y * w + x] = pack((aa / n) as i32, (rr / n) as i32, (gg / n) as i32, (bb / n) as i32);
        }
    }
    out
}

fn generate_mask(mode: i32, center_x: f32, center_y: f32, radius: f32, feather: f32, angle: f32, w: usize, h: usize) -> Vec<f32> {
    let mut mask = vec![0f32; w * h];
    let max_wh = w.max(h) as f32;
    match mode {
        0 => {
            let cx = center_x * w as f32;
            let cy = center_y * h as f32;
            let rad = radius * max_wh;
            let feather_dist = feather * max_wh;
            for y in 0..h {
                for x in 0..w {
                    let dx = x as f32 - cx;
                    let dy = y as f32 - cy;
                    let dist = ((dx * dx + dy * dy) as f64).sqrt() as f32;
                    mask[y * w + x] = if dist < rad { 0f32 } else { clamp_f((dist - rad) / feather_dist.max(1f32), 0f32, 1f32) };
                }
            }
        }
        1 => {
            let cx = center_x * w as f32;
            let cy = center_y * h as f32;
            let rad = radius * max_wh;
            let feather_dist = feather * max_wh;
            let radian = (angle as f64).to_radians();
            let nx = -(radian.sin() as f32);
            let ny = radian.cos() as f32;
            for y in 0..h {
                for x in 0..w {
                    let dist = ((x as f32 - cx) * nx + (y as f32 - cy) * ny).abs();
                    mask[y * w + x] = if dist < rad { 0f32 } else { clamp_f((dist - rad) / feather_dist.max(1f32), 0f32, 1f32) };
                }
            }
        }
        _ => {
            let cx = center_x * w as f32;
            let cy = center_y * h as f32;
            let rad = radius * max_wh;
            for y in 0..h {
                for x in 0..w {
                    let dx = x as f32 - cx;
                    let dy = y as f32 - cy;
                    let dist = ((dx * dx + dy * dy) as f64).sqrt() as f32;
                    mask[y * w + x] = if dist > rad { 1f32 } else { 0f32 };
                }
            }
        }
    }
    mask
}

pub fn blur_params(pixels: &[i32], w: usize, h: usize, mode: i32, center_x: f32, center_y: f32, radius: f32, intensity: f32, feather: f32, angle: f32) -> Vec<i32> {
    let blur_radius = clamp_i((intensity * 0.5f32) as i32, 1, 25);
    let blurred = gaussian_blur(pixels, w, h, blur_radius);
    let mask = generate_mask(mode, center_x, center_y, radius, feather, angle, w, h);
    let mut output = vec![0i32; w * h];
    for i in 0..pixels.len() {
        let m = mask[i];
        output[i] = pack(
            clamp_i((a(pixels[i]) as f32 + (a(blurred[i]) - a(pixels[i])) as f32 * m) as i32, 0, 255),
            clamp_i((r(pixels[i]) as f32 + (r(blurred[i]) - r(pixels[i])) as f32 * m) as i32, 0, 255),
            clamp_i((g(pixels[i]) as f32 + (g(blurred[i]) - g(pixels[i])) as f32 * m) as i32, 0, 255),
            clamp_i((b(pixels[i]) as f32 + (b(blurred[i]) - b(pixels[i])) as f32 * m) as i32, 0, 255),
        );
    }
    output
}

pub fn filter_blur(pixels: &[i32], w: usize, h: usize, mode: i32, amount: f32, angle: f32, center_x: f32, center_y: f32) -> Vec<i32> {
    match mode {
        0 => gaussian_blur(pixels, w, h, clamp_i((amount * 0.5f32) as i32, 1, 60)),
        1 => motion_blur(pixels, w, h, clamp_i(amount as i32, 1, 100), angle),
        2 => radial_blur(pixels, w, h, amount, center_x, center_y, false),
        _ => radial_blur(pixels, w, h, amount, center_x, center_y, true),
    }
}
