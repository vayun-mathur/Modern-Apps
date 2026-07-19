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
use crate::wave::wave_correct_horizontal;
use rayon::prelude::*;

const MAX_FEATURES: usize = 3000;
const FAST_THRESHOLD: i32 = 12;
const CONF_THRESH: f64 = 1.0;
const MATCH_WINDOW: usize = 5; // match each frame only to ~5 neighbours (ordered sweep)
const REG_MEGAPIXELS: f64 = 0.6; // registration resolution (like OpenCV's registr_resol_)
const COMPOSE_MAX_PIXELS: f64 = 3_000_000.0; // cap output canvas area (bounds warp+blend cost/memory)
const SEAM_MAX_PIXELS: f64 = 400_000.0; // seam/exposure canvas area (OpenCV seam_est_resol_)

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

pub fn stitch_panorama(frames: &[Rgba], _yaw: &[f32], _pitch: &[f32]) -> Option<Rgba> {
    let n = frames.len();
    if n == 0 {
        return None;
    }
    if n == 1 {
        return Some(frames[0].clone());
    }

    let full_w = frames[0].w;
    let full_h = frames[0].h;

    // --- registration resolution (downscale for the slow stages) ---
    let reg_scale = (REG_MEGAPIXELS * 1e6 / (full_w as f64 * full_h as f64)).sqrt().min(1.0);
    let reg_w = ((full_w as f64 * reg_scale).round() as usize).max(1);
    let reg_h = ((full_h as f64 * reg_scale).round() as usize).max(1);
    let reg_frames: Vec<Rgba> = if reg_scale < 0.999 {
        frames.par_iter().map(|f| f.resized(reg_w, reg_h)).collect()
    } else {
        frames.to_vec()
    };

    // 1) features (parallel)
    let feats: Vec<Features> = reg_frames
        .par_iter()
        .map(|f| detect_and_describe(&to_gray(f), MAX_FEATURES, FAST_THRESHOLD))
        .collect();

    // 2) all-pairs matching
    let matches = match_all(&feats, MATCH_WINDOW);
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
    let kept_full: Vec<&Rgba> = kept.iter().map(|&i| &frames[i]).collect();
    let mut compose_area = {
        let mut gx0 = f64::MAX;
        let mut gy0 = f64::MAX;
        let mut gx1 = f64::MIN;
        let mut gy1 = f64::MIN;
        for (f, c) in kept_full.iter().zip(cams.iter()) {
            if let Some((u0, v0, u1, v1)) = warped_bounds(f.w, f.h, &c.k(), &c.r, scale) {
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

    // Low-res warps -> exposure + seam (cheap).
    let seam_tiles = kept_full
        .par_iter()
        .zip(cams.par_iter())
        .map(|(f, c)| warp_one(f, &c.k(), &c.r, seam_scale))
        .collect::<Option<Vec<_>>>()?;
    let seam_gains = compensate(&seam_tiles);
    let low_masks = seam_masks(&seam_tiles);

    // Full-res warps -> blend.
    let tiles = kept_full
        .par_iter()
        .zip(cams.par_iter())
        .map(|(f, c)| warp_one(f, &c.k(), &c.r, scale))
        .collect::<Option<Vec<_>>>()?;

    // Build compose-resolution seam masks as a true partition of the actual
    // coverage: every covered pixel is owned by exactly one covering tile,
    // guided by the low-res seam. This avoids unassigned (black) gaps at seams
    // that break the blend and the crop.
    let masks = build_compose_masks(&tiles, &seam_tiles, &low_masks);
    let gain_maps: Vec<Vec<f32>> = (0..tiles.len())
        .map(|i| resize_gain(&seam_gains[i], seam_tiles[i].img.w, seam_tiles[i].img.h, tiles[i].img.w, tiles[i].img.h))
        .collect();

    // 10) multi-band blend + crop
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
