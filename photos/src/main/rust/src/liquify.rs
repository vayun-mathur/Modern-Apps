//! Port of `LiquifyData.kt`: build a per-pixel displacement field from a list of
//! ops, then bilinear-resample the source through it.
//!
//! Ops are marshalled across JNI as `tools: [i32]` (one LiquifyTool ordinal per
//! op — Push=0, Twirl=1, Pucker=2, Bloat=3, Reconstruct=4) and `params: [f32]`
//! with 6 floats per op: [x, y, dx, dy, radius, strength].

use crate::pixel::*;
use std::f64::consts::PI;

struct Op {
    tool: i32,
    x: f32,
    y: f32,
    dx: f32,
    dy: f32,
    radius: f32,
    strength: f32,
}

/// Port of `lerpChannel`.
#[inline]
fn lerp_channel(p00: i32, p10: i32, p01: i32, p11: i32, tx: f32, ty: f32, shift: u32) -> i32 {
    let c00 = (((p00 as u32) >> shift) & 0xFF) as f32;
    let c10 = (((p10 as u32) >> shift) & 0xFF) as f32;
    let c01 = (((p01 as u32) >> shift) & 0xFF) as f32;
    let c11 = (((p11 as u32) >> shift) & 0xFF) as f32;
    let top = c00 + (c10 - c00) * tx;
    let bottom = c01 + (c11 - c01) * tx;
    let value = top + (bottom - top) * ty;
    clamp_i(value as i32, 0, 255)
}

/// Port of `sampleBilinear`.
#[inline]
fn sample_bilinear(src: &[i32], w: usize, h: usize, fx: f32, fy: f32) -> i32 {
    let cx = clamp_f(fx, 0f32, (w - 1) as f32);
    let cy = clamp_f(fy, 0f32, (h - 1) as f32);
    let x0 = cx as i32;
    let y0 = cy as i32;
    let x1 = (x0 + 1).min(w as i32 - 1);
    let y1 = (y0 + 1).min(h as i32 - 1);
    let tx = cx - x0 as f32;
    let ty = cy - y0 as f32;
    let x0 = x0 as usize;
    let y0 = y0 as usize;
    let x1 = x1 as usize;
    let y1 = y1 as usize;
    let p00 = src[y0 * w + x0];
    let p10 = src[y0 * w + x1];
    let p01 = src[y1 * w + x0];
    let p11 = src[y1 * w + x1];
    let a = lerp_channel(p00, p10, p01, p11, tx, ty, 24);
    let r = lerp_channel(p00, p10, p01, p11, tx, ty, 16);
    let g = lerp_channel(p00, p10, p01, p11, tx, ty, 8);
    let b = lerp_channel(p00, p10, p01, p11, tx, ty, 0);
    pack(a, r, g, b)
}

/// Port of `LiquifyParams.applyToBitmap`.
pub fn liquify(src: &[i32], w: usize, h: usize, tools: &[i32], params: &[f32]) -> Vec<i32> {
    // Reconstruct the ops list (6 floats per op).
    let n = tools.len().min(params.len() / 6);
    let ops: Vec<Op> = (0..n)
        .map(|i| Op {
            tool: tools[i],
            x: params[i * 6],
            y: params[i * 6 + 1],
            dx: params[i * 6 + 2],
            dy: params[i * 6 + 3],
            radius: params[i * 6 + 4],
            strength: params[i * 6 + 5],
        })
        .collect();

    // Identity / degenerate: straight copy (mirrors the Kotlin early return).
    if ops.is_empty() || w == 0 || h == 0 {
        return src.to_vec();
    }

    let mut disp_x = vec![0f32; w * h];
    let mut disp_y = vec![0f32; w * h];
    let max_dim = w.max(h) as f32;

    for op in &ops {
        let cx = op.x * w as f32;
        let cy = op.y * h as f32;
        let radius_px = op.radius * max_dim;
        if radius_px <= 0f32 {
            continue;
        }

        let angle_base = (op.strength as f64 * PI) as f32;
        let drag_x = op.dx * max_dim;
        let drag_y = op.dy * max_dim;

        let min_x = 0.max((cx - radius_px) as i32);
        let max_x = (w as i32 - 1).min((cx + radius_px) as i32);
        let min_y = 0.max((cy - radius_px) as i32);
        let max_y = (h as i32 - 1).min((cy + radius_px) as i32);

        let mut py = min_y;
        while py <= max_y {
            let mut px = min_x;
            while px <= max_x {
                let ox = px as f32 - cx;
                let oy = py as f32 - cy;
                let dist = (ox * ox + oy * oy).sqrt();
                if dist > radius_px {
                    px += 1;
                    continue;
                }
                let raw = clamp_f(1f32 - dist / radius_px, 0f32, 1f32);
                let t = raw * raw * (3f32 - 2f32 * raw); // smoothstep falloff
                let idx = (py * w as i32 + px) as usize;

                match op.tool {
                    0 => {
                        // Push
                        disp_x[idx] += -drag_x * op.strength * t;
                        disp_y[idx] += -drag_y * op.strength * t;
                    }
                    1 => {
                        // Twirl
                        let angle = angle_base * t;
                        let c = angle.cos();
                        let s = angle.sin();
                        let rot_x = ox * c - oy * s;
                        let rot_y = ox * s + oy * c;
                        disp_x[idx] += rot_x - ox;
                        disp_y[idx] += rot_y - oy;
                    }
                    2 => {
                        // Pucker
                        disp_x[idx] += (cx - px as f32) * op.strength * t * 0.5f32;
                        disp_y[idx] += (cy - py as f32) * op.strength * t * 0.5f32;
                    }
                    3 => {
                        // Bloat
                        disp_x[idx] += (px as f32 - cx) * op.strength * t * 0.5f32;
                        disp_y[idx] += (py as f32 - cy) * op.strength * t * 0.5f32;
                    }
                    4 => {
                        // Reconstruct
                        let factor = clamp_f(1f32 - op.strength * t, 0f32, 1f32);
                        disp_x[idx] *= factor;
                        disp_y[idx] *= factor;
                    }
                    _ => {
                        // Unknown tool ordinal: no-op (never fall through
                        // to Reconstruct, which would corrupt prior ops).
                    }
                }
                px += 1;
            }
            py += 1;
        }
    }

    let mut dst = vec![0i32; w * h];
    for y in 0..h {
        for x in 0..w {
            let idx = y * w + x;
            let sx = x as f32 + disp_x[idx];
            let sy = y as f32 + disp_y[idx];
            dst[idx] = sample_bilinear(src, w, h, sx, sy);
        }
    }
    dst
}
