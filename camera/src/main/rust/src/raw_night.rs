//! RAW night merge (depth Phase 2): linear-light burst merge.
//!
//! Each frame is unpacked Bayer, black-level subtracted and white-level
//! normalised to linear f32, white-balance gained per channel, demosaiced
//! (bilinear), aligned on the green channel with the same ORB+RANSAC
//! homography estimation the YUV night path uses, and robustly merged
//! (trimmed mean with a linear-light deghost gate). The output is linear
//! RGB; `merge_raw_burst` tone-maps it to the 8-bit RGBA the existing
//! Kotlin tone/encode pipeline consumes. No gamma-space averaging ever
//! happens, which is the point: averaging gamma YUV bakes noise and
//! black-level crush into the result.

use crate::features::{detect_and_describe, match_features};
use crate::geometry::{find_homography_ransac, Pt};
use crate::imgbuf::{Gray, Rgba};
use crate::linalg::Matrix3;

const MAX_FEATURES: usize = 2000;
const FAST_THRESHOLD: i32 = 12;
const RATIO: f32 = 0.78;
const RANSAC_ITERS: usize = 500;
const RANSAC_THRESH: f32 = 3.0;
const MIN_INLIERS: usize = 12;
const REG_MAX_MP: f64 = 0.8;
/// Summed linear RGB difference above which a warped sample is a ghost.
const LINEAR_GHOST_THRESH: f32 = 0.35;

/// Bayer colour-filter arrangement (mirrors `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Cfa {
    Rggb,
    Bggr,
    Grbg,
    Gbrg,
    Mono,
}

impl Cfa {
    pub fn from_code(code: i32) -> Option<Self> {
        Some(match code {
            0 => Cfa::Rggb,
            1 => Cfa::Grbg,
            2 => Cfa::Gbrg,
            3 => Cfa::Bggr,
            4 => Cfa::Mono,
            _ => return None,
        })
    }
    /// Channel index (0=R,1=G,2=B) sampled at pixel (x,y); Mono is 1.
    fn channel_at(&self, x: usize, y: usize) -> usize {
        let (even_row, even_col) = (y % 2 == 0, x % 2 == 0);
        match self {
            Cfa::Mono => 1,
            Cfa::Rggb => match (even_row, even_col) {
                (true, true) => 0,
                (false, false) => 2,
                _ => 1,
            },
            Cfa::Bggr => match (even_row, even_col) {
                (true, true) => 2,
                (false, false) => 0,
                _ => 1,
            },
            Cfa::Grbg => match (even_row, even_col) {
                (true, false) => 0,
                (false, true) => 2,
                _ => 1,
            },
            Cfa::Gbrg => match (even_row, even_col) {
                (true, false) => 2,
                (false, true) => 0,
                _ => 1,
            },
        }
    }
}

/// Per-frame sensor metadata needed to linearise honestly.
#[derive(Clone, Copy, Debug)]
pub struct RawMeta {
    /// Black level per 2x2 pattern cell, indexed `(y%2)*2 + (x%2)`.
    pub black_levels: [f32; 4],
    pub white_level: f32,
    pub cfa: Cfa,
    /// White-balance gains (R,G,B); G is normally 1.
    pub gains: [f32; 3],
    /// Exposure x ISO product relative to the reference (1 = same).
    pub exposure_scale: f32,
}

impl RawMeta {
    /// Uniform-black convenience constructor (tests and scalar-black sensors).
    #[allow(dead_code)]
    pub fn scalar(black: f32, white: f32, cfa: Cfa) -> Self {
        RawMeta { black_levels: [black; 4], white_level: white, cfa, gains: [1.0, 1.0, 1.0], exposure_scale: 1.0 }
    }
}

/// One unpacked RAW frame (one u16 sample per photosite).
pub struct RawFrame {
    pub w: usize,
    pub h: usize,
    pub samples: Vec<u16>,
    pub meta: RawMeta,
}

/// Linear RGB image, one `[R,G,B]` f32 triplet per pixel (nominally 0..1,
/// may exceed 1 before tone mapping).
pub struct LinearRgb {
    pub w: usize,
    pub h: usize,
    pub px: Vec<[f32; 3]>,
}

/// Unpacks RAW10 (4 photosites in 5 bytes, low 2 bits packed in byte 4).
pub fn unpack_raw10(bytes: &[u8], w: usize, h: usize) -> Option<Vec<u16>> {
    if w == 0 || h == 0 || w % 4 != 0 {
        return None;
    }
    let row_bytes = w / 4 * 5;
    if bytes.len() < row_bytes * h {
        return None;
    }
    let mut out = vec![0u16; w * h];
    for y in 0..h {
        for g in 0..w / 4 {
            let b = &bytes[y * row_bytes + g * 5..y * row_bytes + g * 5 + 5];
            let lo = b[4];
            for k in 0..4 {
                out[y * w + g * 4 + k] = ((b[k] as u16) << 2) | ((lo >> (k * 2)) & 3) as u16;
            }
        }
    }
    Some(out)
}

/// Unpacks little-endian RAW_SENSOR / RAW16 samples (tight rows).
pub fn unpack_raw16(bytes: &[u8], w: usize, h: usize) -> Option<Vec<u16>> {
    if w == 0 || h == 0 || bytes.len() < w * h * 2 {
        return None;
    }
    Some((0..w * h).map(|i| u16::from_le_bytes([bytes[i * 2], bytes[i * 2 + 1]])).collect())
}

/// Black-subtract + white-normalise one photosite. Values at or below
/// black map to exactly 0 (never negative, so shadows are not crushed
/// below the noise floor they carry); above white clamps to 1.
#[inline]
fn linearise(sample: u16, black: f32, white: f32) -> f32 {
    let range = (white - black).max(1.0);
    ((sample as f32 - black) / range).clamp(0.0, 1.0)
}

fn sample_linear(frame: &RawFrame, x: usize, y: usize) -> f32 {
    let cell = (y % 2) * 2 + (x % 2);
    linearise(frame.samples[y * frame.w + x], frame.meta.black_levels[cell], frame.meta.white_level)
}

/// Bilinear demosaic to linear RGB: the sampled channel is exact, green
/// at R/B sites averages the cross neighbours, R/B at green sites average
/// the axis neighbours of that colour, and R at B (and vice versa) average
/// the diagonals. Border neighbours clamp to the edge.
pub fn demosaic(frame: &RawFrame) -> LinearRgb {
    let (w, h) = (frame.w, frame.h);
    let mut px = vec![[0f32; 3]; w * h];
    let at = |x: i32, y: i32| -> (f32, usize) {
        let cx = x.clamp(0, w as i32 - 1) as usize;
        let cy = y.clamp(0, h as i32 - 1) as usize;
        (sample_linear(frame, cx, cy), frame.meta.cfa.channel_at(cx, cy))
    };
    for y in 0..h as i32 {
        for x in 0..w as i32 {
            let (v, ch) = at(x, y);
            let mut rgb = [0f32; 3];
            rgb[ch] = v;
            if frame.meta.cfa == Cfa::Mono {
                rgb = [v, v, v];
            } else if ch == 1 {
                let horiz = [at(x - 1, y).0, at(x + 1, y).0];
                let vert = [at(x, y - 1).0, at(x, y + 1).0];
                let (hc, vc) = (at(x - 1, y).1, at(x, y - 1).1);
                rgb[hc] = (horiz[0] + horiz[1]) * 0.5;
                rgb[vc] = (vert[0] + vert[1]) * 0.5;
            } else {
                rgb[1] = (at(x - 1, y).0 + at(x + 1, y).0 + at(x, y - 1).0 + at(x, y + 1).0) * 0.25;
                let other = 2 - ch;
                rgb[other] = (at(x - 1, y - 1).0 + at(x + 1, y - 1).0 + at(x - 1, y + 1).0 + at(x + 1, y + 1).0) * 0.25;
            }
            for c in 0..3 {
                rgb[c] *= frame.meta.gains[c];
            }
            px[y as usize * w + x as usize] = rgb;
        }
    }
    LinearRgb { w, h, px }
}

/// Green channel as an 8-bit registration image (the sharpest, densest
/// channel: half of all photosites, and the homography's best signal).
fn green_gray(img: &LinearRgb, scale: f64) -> Gray {
    let (w, h) = (img.w, img.h);
    let (rw, rh) = if scale < 0.999 {
        (((w as f64 * scale).round() as usize).max(1), ((h as f64 * scale).round() as usize).max(1))
    } else {
        (w, h)
    };
    let mut g = Gray::new(rw, rh);
    for y in 0..rh {
        for x in 0..rw {
            let sx = ((x as f64 + 0.5) / scale - 0.5).max(0.0) as usize;
            let sy = ((y as f64 + 0.5) / scale - 0.5).max(0.0) as usize;
            let v = img.px[sy.min(h - 1) * w + sx.min(w - 1)][1];
            g.px[y * rw + x] = (v.clamp(0.0, 1.0) * 255.0).round() as u8;
        }
    }
    g
}

fn scale_homography(h: &Matrix3<f64>, s: f64) -> Matrix3<f64> {
    if (s - 1.0).abs() < 1e-6 {
        return *h;
    }
    Matrix3::new(h[(0, 0)], h[(0, 1)], h[(0, 2)] / s, h[(1, 0)], h[(1, 1)], h[(1, 2)] / s, h[(2, 0)] * s, h[(2, 1)] * s, h[(2, 2)])
}

/// Estimates, per frame, the warp from reference coordinates into that
/// frame (the inverse homography), by registering green channels at
/// reduced resolution exactly as the YUV night path registers luma.
/// Frame 0 is the reference; callers order the burst accordingly.
fn estimate_warps(frames: &[LinearRgb]) -> Vec<Option<Matrix3<f64>>> {
    let n = frames.len();
    let mut warps: Vec<Option<Matrix3<f64>>> = vec![None; n];
    if n < 2 || frames[0].w == 0 || frames[0].h == 0 {
        return warps;
    }
    let area = frames[0].w as f64 * frames[0].h as f64;
    let reg_scale = (REG_MAX_MP * 1e6 / area).sqrt().min(1.0);
    let grays: Vec<Gray> = frames.iter().map(|f| green_gray(f, reg_scale)).collect();
    let ref_feat = detect_and_describe(&grays[0], MAX_FEATURES, FAST_THRESHOLD);
    for i in 1..n {
        let feat = detect_and_describe(&grays[i], MAX_FEATURES, FAST_THRESHOLD);
        let matches = match_features(&feat, &ref_feat, RATIO);
        if matches.len() < MIN_INLIERS {
            continue;
        }
        let a: Vec<Pt> = matches.iter().map(|&(ia, _)| (feat.kps[ia].x, feat.kps[ia].y)).collect();
        let b: Vec<Pt> = matches.iter().map(|&(_, ib)| (ref_feat.kps[ib].x, ref_feat.kps[ib].y)).collect();
        if let Some((h_small, inl)) = find_homography_ransac(&a, &b, RANSAC_ITERS, RANSAC_THRESH) {
            if inl < MIN_INLIERS {
                continue;
            }
            let s = if frames[i].w > 0 { grays[i].w as f64 / frames[i].w as f64 } else { 1.0 };
            if let Some(inv) = scale_homography(&h_small, s).try_inverse() {
                warps[i] = Some(inv);
            }
        }
    }
    warps
}

#[inline]
fn apply_h(h: &Matrix3<f64>, x: f64, y: f64) -> (f64, f64) {
    let v = h * crate::linalg::Vector3::new(x, y, 1.0);
    if v.z.abs() < 1e-12 { (v.x, v.y) } else { (v.x / v.z, v.y / v.z) }
}

#[inline]
fn sample_linear_rgb(img: &LinearRgb, fx: f32, fy: f32) -> Option<[f32; 3]> {
    if fx < 0.0 || fy < 0.0 || fx > (img.w - 1) as f32 || fy > (img.h - 1) as f32 {
        return None;
    }
    let x0 = fx.floor() as usize;
    let y0 = fy.floor() as usize;
    let x1 = (x0 + 1).min(img.w - 1);
    let y1 = (y0 + 1).min(img.h - 1);
    let (ax, ay) = (fx - x0 as f32, fy - y0 as f32);
    let mut out = [0f32; 3];
    for c in 0..3 {
        let top = img.px[y0 * img.w + x0][c] * (1.0 - ax) + img.px[y0 * img.w + x1][c] * ax;
        let bot = img.px[y1 * img.w + x0][c] * (1.0 - ax) + img.px[y1 * img.w + x1][c] * ax;
        out[c] = top * (1.0 - ay) + bot * ay;
    }
    Some(out)
}

fn trimmed_mean(sum: f32, min: f32, max: f32, count: usize) -> f32 {
    match count {
        0 => 0.0,
        1 => sum,
        2 => sum * 0.5,
        3 => sum - min - max,
        _ => (sum - min - max) / (count - 2) as f32,
    }
}

/// Aligns and robustly merges a RAW burst in linear light. Frames are
/// exposure-normalised to the reference (`meta.exposure_scale`), warped
/// by the green-channel homographies, deghosted against the reference,
/// and combined with the night path's trimmed mean. Frames whose
/// registration failed contribute nothing (never an unwarped smear).
pub fn merge_raw_linear(frames: &[RawFrame]) -> Option<LinearRgb> {
    if frames.is_empty() || frames[0].w == 0 || frames[0].h == 0 {
        return None;
    }
    let (w, h) = (frames[0].w, frames[0].h);
    if frames.iter().any(|f| f.w != w || f.h != h || f.samples.len() != w * h) {
        return None;
    }
    let linear: Vec<LinearRgb> = frames.iter().map(demosaic).collect();
    if linear.len() == 1 {
        return Some(linear.into_iter().next().unwrap());
    }
    let warps = estimate_warps(&linear);
    let reff = &linear[0];
    let ref_scale = frames[0].meta.exposure_scale.max(1e-6);
    let mut out = LinearRgb { w, h, px: vec![[0f32; 3]; w * h] };
    for y in 0..h {
        for x in 0..w {
            let rp = reff.px[y * w + x];
            let mut sum = rp;
            let mut min = rp;
            let mut max = rp;
            let mut count = 1usize;
            for (i, f) in linear.iter().enumerate().skip(1) {
                let Some(warp) = warps[i] else { continue };
                let (sx, sy) = apply_h(&warp, x as f64, y as f64);
                let Some(mut c) = sample_linear_rgb(f, sx as f32, sy as f32) else { continue };
                let norm = ref_scale / frames[i].meta.exposure_scale.max(1e-6);
                for ch in 0..3 { c[ch] *= norm; }
                let diff = (c[0] - rp[0]).abs() + (c[1] - rp[1]).abs() + (c[2] - rp[2]).abs();
                if diff > LINEAR_GHOST_THRESH {
                    continue;
                }
                for ch in 0..3 {
                    sum[ch] += c[ch];
                    min[ch] = min[ch].min(c[ch]);
                    max[ch] = max[ch].max(c[ch]);
                }
                count += 1;
            }
            let mut px = [0f32; 3];
            for ch in 0..3 {
                px[ch] = trimmed_mean(sum[ch], min[ch], max[ch], count).max(0.0);
            }
            out.px[y * w + x] = px;
        }
    }
    Some(out)
}

/// Tone-maps linear RGB to 8-bit RGBA: exposure gain, a soft highlight
/// knee, then sRGB gamma. Hand-off to the existing Kotlin tone/encode
/// pipeline, which applies its highlight-preserving brighten before encoding.
pub fn tone_map(img: &LinearRgb, exposure_gain: f32) -> Rgba {
    let mut out = Rgba::new(img.w, img.h);
    for i in 0..img.w * img.h {
        let mut px = [0u8; 4];
        for c in 0..3 {
            let mut v = (img.px[i][c] * exposure_gain).max(0.0);
            if v > 0.8 {
                v = 0.8 + (v - 0.8) / (1.0 + (v - 0.8));
            }
            px[c] = (v.powf(1.0 / 2.2).clamp(0.0, 1.0) * 255.0).round() as u8;
        }
        px[3] = 255;
        out.px[i * 4..i * 4 + 4].copy_from_slice(&px);
    }
    out
}

/// Full RAW night merge: linear merge, then tone map for the encoder.
pub fn merge_raw_burst(frames: &[RawFrame], exposure_gain: f32) -> Option<Rgba> {
    Some(tone_map(&merge_raw_linear(frames)?, exposure_gain))
}

#[cfg(test)]
#[path = "raw_night_tests.rs"]
mod raw_night_tests;
