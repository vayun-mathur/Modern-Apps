//! Full cv::Stitcher (PANORAMA) pipeline, ported to Rust — no fallbacks.
//! Performance mirrors OpenCV: registration (features/matching/estimation/bundle
//! adjustment) runs at a reduced resolution, then warping + blending run at the
//! captured resolution. Matching is windowed to nearby frames and the slow
//! stages are parallelised with rayon.

use crate::blend::multiband_blend;
use crate::bundle::bundle_adjust;
use crate::camera::CameraParams;
use crate::estimator::estimate_cameras;
use crate::exposure::compensate;
use crate::features::{detect_and_describe, Features};
use crate::imgbuf::{to_gray, Rgba};
use crate::matching::{biggest_component, match_all, MatchInfo};
use crate::seam::seam_masks;
use crate::sphere::{warp_one, warped_bounds, WarpedTile};
use crate::warp::mean_luma;
use crate::wave::wave_correct_horizontal;
use rayon::prelude::*;

const MAX_FEATURES: usize = 3000;
const FAST_THRESHOLD: i32 = 12;
const CONF_THRESH: f64 = 1.0;
const MATCH_MAX_ANGLE: f32 = 55.0; // match frames within this gyro separation (deg)
const REG_MEGAPIXELS: f64 = 0.6; // registration resolution (like OpenCV's registr_resol_)
const COMPOSE_MAX_PIXELS: f64 = 8_000_000.0; // cap output canvas area (bounds warp+blend cost/memory)
const SEAM_MAX_PIXELS: f64 = 100_000.0; // seam/exposure canvas area (OpenCV seam_est_resol_ = 0.1 MP)

fn remap_matches(matches: Vec<MatchInfo>, kept: &[usize]) -> Vec<MatchInfo> {
    let max = kept.iter().max().map(|m| m + 1).unwrap_or(0);
    let mut idx_of = vec![usize::MAX; max];
    for (new, &old) in kept.iter().enumerate() {
        idx_of[old] = new;
    }
    matches
        .into_iter()
        .filter_map(|mut m| {
            if m.src >= idx_of.len() || m.dst >= idx_of.len() {
                return None;
            }
            let (s, d) = (idx_of[m.src], idx_of[m.dst]);
            if s == usize::MAX || d == usize::MAX {
                return None;
            }
            m.src = s;
            m.dst = d;
            Some(m)
        })
        .collect()
}

fn median_focal(cams: &[CameraParams]) -> f64 {
    let mut f: Vec<f64> = cams.iter().map(|c| c.focal).filter(|v| v.is_finite() && *v > 1.0).collect();
    if f.is_empty() {
        return 1.0;
    }
    f.sort_by(|a, b| a.total_cmp(b));
    let m = f.len() / 2;
    if f.len() % 2 == 1 {
        f[m]
    } else {
        (f[m - 1] + f[m]) / 2.0
    }
}

/// One kept frame's camera solution for GPU compositing. `r` is the 3×3 rotation
/// serialized **row-major** (r[0..3] = row 0, etc.). Intrinsics are at full
/// capture resolution (aspect = 1, so K = [[focal,0,ppx],[0,focal,ppy],[0,0,1]]).
pub struct FrameCam {
    pub original_index: usize,
    pub focal: f64,
    pub ppx: f64,
    pub ppy: f64,
    pub r: [f64; 9],
    pub gain: f32,
}

/// Registration result for the GPU compositor: the global sphere canvas geometry
/// (in warp pixels) plus the per-kept-frame camera solutions. The compositor
/// warps each kept frame with `outputPx = (u - u0, v - v0)` onto a
/// `canvas_w × canvas_h` canvas using `scale`.
pub struct Estimate {
    pub canvas_w: u32,
    pub canvas_h: u32,
    pub u0: f64,
    pub v0: f64,
    pub scale: f64,
    pub cams: Vec<FrameCam>,
}

fn median_f32(vals: &[f32]) -> f32 {
    let mut v: Vec<f32> = vals.iter().copied().filter(|x| x.is_finite() && *x > 0.0).collect();
    if v.is_empty() {
        return 0.0;
    }
    v.sort_by(|a, b| a.total_cmp(b));
    let m = v.len() / 2;
    if v.len() % 2 == 1 {
        v[m]
    } else {
        (v[m - 1] + v[m]) / 2.0
    }
}

/// Registration-only path for the GPU compositor: runs the same low-res
/// features → matching → estimate → bundle-adjust → wave-correct stages as
/// [`stitch_panorama`], computes the compose canvas geometry, and returns
/// per-frame camera solutions + a cheap exposure gain — **without** running the
/// expensive per-pixel warp/seam/blend (those move to the GPU).
pub fn estimate_pano(frames_jpeg: &[Vec<u8>], yaw: &[f32], pitch: &[f32]) -> Option<Estimate> {
    let n = frames_jpeg.len();
    if n < 2 {
        return None;
    }

    // Full-resolution frame dimensions (from the first frame).
    let (full_w, full_h) = {
        let f0 = Rgba::from_jpeg(&frames_jpeg[0])?;
        (f0.w, f0.h)
    };

    // Registration at ~0.6 MP; also capture each frame's mean luma (for the
    // cheap per-frame exposure gain) from the already-decoded low-res image.
    let reg_scale = (REG_MEGAPIXELS * 1e6 / (full_w as f64 * full_h as f64)).sqrt().min(1.0);
    let reg_w = ((full_w as f64 * reg_scale).round() as usize).max(1);
    let reg_h = ((full_h as f64 * reg_scale).round() as usize).max(1);
    let reg: Vec<(Features, f32)> = frames_jpeg
        .par_iter()
        .map(|j| {
            let full = Rgba::from_jpeg(j).unwrap_or_else(|| Rgba::new(1, 1));
            let small = if reg_scale < 0.999 { full.resized(reg_w, reg_h) } else { full };
            let luma = mean_luma(&small);
            (detect_and_describe(&to_gray(&small), MAX_FEATURES, FAST_THRESHOLD), luma)
        })
        .collect();
    let (feats, lumas): (Vec<Features>, Vec<f32>) = reg.into_iter().unzip();

    let matches = match_all(&feats, yaw, pitch, MATCH_MAX_ANGLE);
    if matches.is_empty() {
        return None;
    }
    let kept = biggest_component(n, &matches, CONF_THRESH);
    if kept.len() < 2 {
        return None;
    }
    let matches = remap_matches(matches, &kept);
    if matches.is_empty() {
        return None;
    }
    let m = kept.len();

    let mut cams = estimate_cameras(m, reg_w, reg_h, &matches);
    bundle_adjust(&mut cams, &matches);
    wave_correct_horizontal(&mut cams);

    // Rescale intrinsics from registration to full resolution.
    for c in cams.iter_mut() {
        c.focal /= reg_scale;
        c.ppx = full_w as f64 / 2.0;
        c.ppy = full_h as f64 / 2.0;
    }

    // Choose the compose scale (bounded so the canvas stays within memory), the
    // same way stitch_panorama does, so GPU and CPU paths size the canvas alike.
    let mut scale = median_focal(&cams);
    if !scale.is_finite() || scale <= 1.0 {
        return None;
    }
    let compose_area = {
        let mut gx0 = f64::MAX;
        let mut gy0 = f64::MAX;
        let mut gx1 = f64::MIN;
        let mut gy1 = f64::MIN;
        for c in cams.iter() {
            if let Some((u0, v0, u1, v1)) = warped_bounds(full_w, full_h, &c.k(), &c.r, scale) {
                gx0 = gx0.min(u0);
                gy0 = gy0.min(v0);
                gx1 = gx1.max(u1);
                gy1 = gy1.max(v1);
            }
        }
        (gx1 - gx0).max(1.0) * (gy1 - gy0).max(1.0)
    };
    if compose_area.is_finite() && compose_area > COMPOSE_MAX_PIXELS {
        scale *= (COMPOSE_MAX_PIXELS / compose_area).sqrt();
    }

    // Final global bounds at the committed scale -> canvas geometry.
    let (mut gx0, mut gy0, mut gx1, mut gy1) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
    for c in cams.iter() {
        if let Some((u0, v0, u1, v1)) = warped_bounds(full_w, full_h, &c.k(), &c.r, scale) {
            gx0 = gx0.min(u0);
            gy0 = gy0.min(v0);
            gx1 = gx1.max(u1);
            gy1 = gy1.max(v1);
        }
    }
    if !gx0.is_finite() || !gy0.is_finite() || !gx1.is_finite() || !gy1.is_finite() {
        return None;
    }
    let canvas_w = ((gx1 - gx0).ceil() as i64 + 1).max(1);
    let canvas_h = ((gy1 - gy0).ceil() as i64 + 1).max(1);

    // Cheap per-frame exposure gain: pull each kept frame toward the median luma.
    let kept_lumas: Vec<f32> = kept.iter().map(|&i| lumas[i]).collect();
    let ref_luma = median_f32(&kept_lumas);
    let cams_out: Vec<FrameCam> = kept
        .iter()
        .zip(cams.iter())
        .map(|(&i, c)| {
            let luma = lumas[i];
            let gain = if ref_luma > 1.0 && luma > 1.0 { (ref_luma / luma).clamp(0.5, 2.0) } else { 1.0 };
            let r = c.r;
            FrameCam {
                original_index: i,
                focal: c.focal,
                ppx: c.ppx,
                ppy: c.ppy,
                r: [
                    r[(0, 0)], r[(0, 1)], r[(0, 2)],
                    r[(1, 0)], r[(1, 1)], r[(1, 2)],
                    r[(2, 0)], r[(2, 1)], r[(2, 2)],
                ],
                gain,
            }
        })
        .collect();

    Some(Estimate {
        canvas_w: canvas_w as u32,
        canvas_h: canvas_h as u32,
        u0: gx0,
        v0: gy0,
        scale,
        cams: cams_out,
    })
}

pub fn stitch_panorama(frames_jpeg: &[Vec<u8>], yaw: &[f32], pitch: &[f32]) -> Option<Rgba> {
    let n = frames_jpeg.len();
    if n == 0 {
        return None;
    }
    if n == 1 {
        return Rgba::from_jpeg(&frames_jpeg[0]);
    }

    // Full-resolution frame dimensions (from the first frame).
    let (full_w, full_h) = {
        let f0 = Rgba::from_jpeg(&frames_jpeg[0])?;
        (f0.w, f0.h)
    };

    // --- registration: decode each frame, downscale to ~0.6 MP, detect features.
    // Frames are decoded on demand and dropped, so max-res frames never all sit in
    // RAM as RGBA at once. ---
    let reg_scale = (REG_MEGAPIXELS * 1e6 / (full_w as f64 * full_h as f64)).sqrt().min(1.0);
    let reg_w = ((full_w as f64 * reg_scale).round() as usize).max(1);
    let reg_h = ((full_h as f64 * reg_scale).round() as usize).max(1);
    let feats: Vec<Features> = frames_jpeg
        .par_iter()
        .map(|j| {
            let full = Rgba::from_jpeg(j).unwrap_or_else(|| Rgba::new(1, 1));
            let small = if reg_scale < 0.999 { full.resized(reg_w, reg_h) } else { full };
            detect_and_describe(&to_gray(&small), MAX_FEATURES, FAST_THRESHOLD)
        })
        .collect();

    // 2) matching (by gyro angular proximity so both pano rings and sphere grids connect)
    let matches = match_all(&feats, yaw, pitch, MATCH_MAX_ANGLE);
    if matches.is_empty() {
        return None;
    }

    // 3) leaveBiggestComponent
    let kept = biggest_component(n, &matches, CONF_THRESH);
    if kept.len() < 2 {
        return None;
    }
    let matches = remap_matches(matches, &kept);
    if matches.is_empty() {
        return None;
    }
    let m = kept.len();

    // 4) estimate cameras -> 5) bundle adjust -> 6) wave correct  (all at reg res)
    let mut cams = estimate_cameras(m, reg_w, reg_h, &matches);
    bundle_adjust(&mut cams, &matches);
    wave_correct_horizontal(&mut cams);

    // Rescale intrinsics from registration to full resolution.
    for c in cams.iter_mut() {
        c.focal /= reg_scale;
        c.ppx = full_w as f64 / 2.0;
        c.ppy = full_h as f64 / 2.0;
    }

    // 7) choose compose scale (bounded so the blend stays within memory), then
    // a much smaller seam-estimation scale (OpenCV's seam_est_resol_ ~= 0.1 MP):
    // exposure compensation and graph-cut seam finding run on tiny low-res warps,
    // and the resulting masks/gains are upscaled for the full-res blend. This is
    // the key to speed — the max-flow runs on ~30x fewer pixels.
    let mut scale = median_focal(&cams);
    if !scale.is_finite() || scale <= 1.0 {
        return None;
    }
    let mut compose_area = {
        let mut gx0 = f64::MAX;
        let mut gy0 = f64::MAX;
        let mut gx1 = f64::MIN;
        let mut gy1 = f64::MIN;
        for c in cams.iter() {
            if let Some((u0, v0, u1, v1)) = warped_bounds(full_w, full_h, &c.k(), &c.r, scale) {
                gx0 = gx0.min(u0);
                gy0 = gy0.min(v0);
                gx1 = gx1.max(u1);
                gy1 = gy1.max(v1);
            }
        }
        (gx1 - gx0).max(1.0) * (gy1 - gy0).max(1.0)
    };
    if compose_area.is_finite() && compose_area > COMPOSE_MAX_PIXELS {
        scale *= (COMPOSE_MAX_PIXELS / compose_area).sqrt();
        compose_area = COMPOSE_MAX_PIXELS;
    }
    let seam_scale = (scale * (SEAM_MAX_PIXELS / compose_area).sqrt()).min(scale).max(1.0);

    // Decode each kept frame once (on demand), warp both the low-res seam tile and
    // the full-res compose tile from it, then drop the decoded frame — so at most a
    // few full-res frames are in RAM at a time even at maximum capture resolution.
    let warped: Vec<(WarpedTile, WarpedTile)> = kept
        .par_iter()
        .zip(cams.par_iter())
        .map(|(&i, c)| {
            let full = Rgba::from_jpeg(&frames_jpeg[i])?;
            let seam = warp_one(&full, &c.k(), &c.r, seam_scale)?;
            let comp = warp_one(&full, &c.k(), &c.r, scale)?;
            Some((seam, comp))
        })
        .collect::<Option<Vec<_>>>()?;
    let (seam_tiles, tiles): (Vec<WarpedTile>, Vec<WarpedTile>) = warped.into_iter().unzip();

    let seam_gains = compensate(&seam_tiles);
    let low_masks = seam_masks(&seam_tiles);

    // Compose-resolution seam masks as a true partition of the coverage.
    let masks = build_compose_masks(&tiles, &seam_tiles, &low_masks);
    let gain_maps: Vec<Vec<f32>> = (0..tiles.len())
        .map(|i| resize_gain(&seam_gains[i], seam_tiles[i].img.w, seam_tiles[i].img.h, tiles[i].img.w, tiles[i].img.h))
        .collect();

    multiband_blend(&tiles, &masks, &gain_maps)
}

/// Build compose-resolution seam masks as a partition of the actual coverage:
/// every pixel covered by any tile is assigned to exactly one covering tile
/// (preferring the tile the low-res seam assigned there), so the masks' union
/// equals the coverage — no unassigned black gaps.
fn build_compose_masks(tiles: &[WarpedTile], seam_tiles: &[WarpedTile], low_masks: &[Vec<u8>]) -> Vec<Vec<u8>> {
    let num = tiles.len();
    let mut masks: Vec<Vec<u8>> = tiles.iter().map(|t| vec![0u8; t.img.w * t.img.h]).collect();
    if num == 0 {
        return masks;
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
    for gy in gy0..gy1 {
        for gx in gx0..gx1 {
            let mut owner = usize::MAX;
            let mut fallback = usize::MAX;
            for ti in 0..num {
                let t = &tiles[ti];
                let lx = gx - t.corner_x;
                let ly = gy - t.corner_y;
                if lx < 0 || ly < 0 || lx >= t.img.w as i32 || ly >= t.img.h as i32 {
                    continue;
                }
                if t.img.get(lx as usize, ly as usize)[3] == 0 {
                    continue;
                }
                if fallback == usize::MAX {
                    fallback = ti;
                }
                // Does the low-res seam assign this region to tile ti?
                let st = &seam_tiles[ti];
                let slx = (lx as usize * st.img.w / t.img.w.max(1)).min(st.img.w.saturating_sub(1));
                let sly = (ly as usize * st.img.h / t.img.h.max(1)).min(st.img.h.saturating_sub(1));
                if low_masks[ti].get(sly * st.img.w + slx).copied().unwrap_or(0) != 0 {
                    owner = ti;
                    break;
                }
            }
            let chosen = if owner != usize::MAX { owner } else { fallback };
            if chosen != usize::MAX {
                let t = &tiles[chosen];
                let lx = (gx - t.corner_x) as usize;
                let ly = (gy - t.corner_y) as usize;
                masks[chosen][ly * t.img.w + lx] = 1;
            }
        }
    }
    masks
}

/// Bilinear upscale of a per-pixel gain map.
fn resize_gain(src: &[f32], sw: usize, sh: usize, dw: usize, dh: usize) -> Vec<f32> {
    if sw == 0 || sh == 0 {
        return vec![1.0f32; dw * dh];
    }
    let mut out = vec![1.0f32; dw * dh];
    for y in 0..dh {
        let fy = ((y as f64 + 0.5) * sh as f64 / dh as f64 - 0.5).clamp(0.0, sh as f64 - 1.0);
        let y0 = fy.floor() as usize;
        let y1 = (y0 + 1).min(sh - 1);
        let ay = (fy - y0 as f64) as f32;
        for x in 0..dw {
            let fx = ((x as f64 + 0.5) * sw as f64 / dw as f64 - 0.5).clamp(0.0, sw as f64 - 1.0);
            let x0 = fx.floor() as usize;
            let x1 = (x0 + 1).min(sw - 1);
            let ax = (fx - x0 as f64) as f32;
            let g00 = src[y0 * sw + x0];
            let g10 = src[y0 * sw + x1];
            let g01 = src[y1 * sw + x0];
            let g11 = src[y1 * sw + x1];
            let top = g00 * (1.0 - ax) + g10 * ax;
            let bot = g01 * (1.0 - ax) + g11 * ax;
            out[y * dw + x] = top * (1.0 - ay) + bot * ay;
        }
    }
    out
}
