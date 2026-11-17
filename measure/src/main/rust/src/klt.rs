//! Pyramidal Lucas–Kanade feature tracking.
//!
//! The panorama stitcher re-detects ORB features in every image and matches
//! descriptors, which is fine when stitching a handful of stills. At 30 fps that cost
//! is wasted: consecutive frames differ by a few pixels, so it is far cheaper to
//! *track* where each feature moved than to redescribe and rematch the whole set.
//!
//! Coarse-to-fine iteration over an image pyramid is what allows displacements larger
//! than the window size to be followed; a single-level solver silently fails on fast
//! motion, which is precisely when a handheld phone is hardest to track.

use vision_core::imgbuf::Gray;

/// Half-width of the correlation window. 7 gives a 15x15 patch.
const HALF_WIN: i32 = 7;
const MAX_ITERS: usize = 20;
const CONVERGE_EPS: f32 = 0.01;
/// Below this the 2x2 normal matrix is singular: the patch is flat or an edge, so
/// displacement is unobservable (the aperture problem) and the point must be dropped.
const MIN_EIGENVALUE: f32 = 1e-4;

pub struct Pyramid {
    pub levels: Vec<Gray>,
}

impl Pyramid {
    /// Build `n_levels` halving each time. Level 0 is the original.
    pub fn build(base: &Gray, n_levels: usize) -> Pyramid {
        let mut levels = vec![base.clone()];
        for _ in 1..n_levels {
            let prev = levels.last().unwrap();
            if prev.w < 32 || prev.h < 32 {
                break;
            }
            // Blur before decimating, or aliasing injects false gradients.
            let blurred = prev.gaussian_blur_3x3();
            levels.push(blurred.resized(prev.w / 2, prev.h / 2));
        }
        Pyramid { levels }
    }
}

/// Bilinear sample; returns `None` outside the valid interior.
fn sample(img: &Gray, x: f32, y: f32) -> Option<f32> {
    if x < 0.0 || y < 0.0 || x >= (img.w - 1) as f32 || y >= (img.h - 1) as f32 {
        return None;
    }
    let x0 = x.floor() as usize;
    let y0 = y.floor() as usize;
    let fx = x - x0 as f32;
    let fy = y - y0 as f32;
    let p00 = img.at(x0, y0) as f32;
    let p10 = img.at(x0 + 1, y0) as f32;
    let p01 = img.at(x0, y0 + 1) as f32;
    let p11 = img.at(x0 + 1, y0 + 1) as f32;
    Some(
        p00 * (1.0 - fx) * (1.0 - fy)
            + p10 * fx * (1.0 - fy)
            + p01 * (1.0 - fx) * fy
            + p11 * fx * fy,
    )
}

#[derive(Clone, Copy, Debug)]
pub struct TrackResult {
    pub x: f32,
    pub y: f32,
    pub ok: bool,
}

/// Track one point from `prev` to `next`, starting from `guess`.
fn track_level(prev: &Gray, next: &Gray, px: f32, py: f32, guess: (f32, f32)) -> Option<(f32, f32)> {
    // Spatial gradients and the structure tensor are fixed by the source patch,
    // so they are computed once outside the iteration.
    let mut gxx = 0.0f32;
    let mut gxy = 0.0f32;
    let mut gyy = 0.0f32;
    let mut grads = Vec::with_capacity(((2 * HALF_WIN + 1) * (2 * HALF_WIN + 1)) as usize);

    for dy in -HALF_WIN..=HALF_WIN {
        for dx in -HALF_WIN..=HALF_WIN {
            let sx = px + dx as f32;
            let sy = py + dy as f32;
            let right = sample(prev, sx + 1.0, sy)?;
            let left = sample(prev, sx - 1.0, sy)?;
            let down = sample(prev, sx, sy + 1.0)?;
            let up = sample(prev, sx, sy - 1.0)?;
            let ix = (right - left) * 0.5;
            let iy = (down - up) * 0.5;
            let val = sample(prev, sx, sy)?;
            gxx += ix * ix;
            gxy += ix * iy;
            gyy += iy * iy;
            grads.push((dx, dy, ix, iy, val));
        }
    }

    let det = gxx * gyy - gxy * gxy;
    let trace = gxx + gyy;
    if det <= 0.0 || trace <= 0.0 {
        return None;
    }
    // Smaller eigenvalue of the 2x2 structure tensor.
    let disc = ((trace * trace - 4.0 * det).max(0.0)).sqrt();
    let min_eig = (trace - disc) * 0.5 / grads.len() as f32;
    if min_eig < MIN_EIGENVALUE {
        return None;
    }

    let (mut vx, mut vy) = guess;
    for _ in 0..MAX_ITERS {
        let mut bx = 0.0f32;
        let mut by = 0.0f32;
        let mut valid = 0usize;
        for &(dx, dy, ix, iy, src) in &grads {
            let tx = px + dx as f32 + vx;
            let ty = py + dy as f32 + vy;
            let dst = match sample(next, tx, ty) {
                Some(v) => v,
                None => continue,
            };
            let diff = src - dst;
            bx += diff * ix;
            by += diff * iy;
            valid += 1;
        }
        // Most of the patch left the frame; the remaining evidence is not enough.
        if valid * 2 < grads.len() {
            return None;
        }
        let dx = (gyy * bx - gxy * by) / det;
        let dy = (gxx * by - gxy * bx) / det;
        vx += dx;
        vy += dy;
        if dx.abs() < CONVERGE_EPS && dy.abs() < CONVERGE_EPS {
            break;
        }
    }
    Some((vx, vy))
}

/// Track `points` from one pyramid to the next, coarse to fine.
pub fn track(prev: &Pyramid, next: &Pyramid, points: &[(f32, f32)]) -> Vec<TrackResult> {
    let n_levels = prev.levels.len().min(next.levels.len());
    points
        .iter()
        .map(|&(x, y)| {
            let mut guess = (0.0f32, 0.0f32);
            let mut ok = true;
            for level in (0..n_levels).rev() {
                let scale = (1 << level) as f32;
                let lx = x / scale;
                let ly = y / scale;
                match track_level(&prev.levels[level], &next.levels[level], lx, ly, guess) {
                    Some(v) => {
                        // Carry the displacement down to the finer level.
                        guess = if level > 0 { (v.0 * 2.0, v.1 * 2.0) } else { v };
                    }
                    None => {
                        ok = false;
                        break;
                    }
                }
            }
            if ok {
                TrackResult { x: x + guess.0, y: y + guess.1, ok: true }
            } else {
                TrackResult { x, y, ok: false }
            }
        })
        .collect()
}

#[cfg(test)] #[path = "klt_tests.rs"] mod klt_tests;

#[cfg(test)]
mod tests {
    use super::*;

    /// Textured image; a smooth gradient would be untrackable by construction.
    fn checker(w: usize, h: usize, shift_x: i32, shift_y: i32) -> Gray {
        let mut g = Gray::new(w, h);
        for y in 0..h {
            for x in 0..w {
                let sx = x as i32 - shift_x;
                let sy = y as i32 - shift_y;
                // Overlapping sinusoids: smooth but with gradient in both axes.
                let v = (((sx as f32 * 0.35).sin() * (sy as f32 * 0.27).cos() + 1.0) * 110.0) as u8;
                g.px[y * w + x] = v;
            }
        }
        g
    }

    #[test]
    fn tracks_a_pure_translation() {
        let a = checker(160, 160, 0, 0);
        let b = checker(160, 160, 3, 2);
        let pa = Pyramid::build(&a, 3);
        let pb = Pyramid::build(&b, 3);
        let pts = vec![(80.0f32, 80.0f32), (60.0, 100.0), (100.0, 55.0)];
        let res = track(&pa, &pb, &pts);
        for (i, r) in res.iter().enumerate() {
            assert!(r.ok, "point {i} should track");
            assert!(
                (r.x - (pts[i].0 + 3.0)).abs() < 0.5,
                "x moved to {} expected {}",
                r.x,
                pts[i].0 + 3.0
            );
            assert!(
                (r.y - (pts[i].1 + 2.0)).abs() < 0.5,
                "y moved to {} expected {}",
                r.y,
                pts[i].1 + 2.0
            );
        }
    }

    #[test]
    fn identical_frames_yield_zero_displacement() {
        let a = checker(128, 128, 0, 0);
        let pa = Pyramid::build(&a, 3);
        let res = track(&pa, &pa, &[(64.0, 64.0)]);
        assert!(res[0].ok);
        assert!((res[0].x - 64.0).abs() < 0.05);
        assert!((res[0].y - 64.0).abs() < 0.05);
    }

    #[test]
    fn a_flat_patch_is_rejected() {
        // Uniform grey has no gradient, so displacement is unobservable.
        let mut a = Gray::new(128, 128);
        for v in a.px.iter_mut() {
            *v = 128;
        }
        let b = a.clone();
        let pa = Pyramid::build(&a, 3);
        let pb = Pyramid::build(&b, 3);
        let res = track(&pa, &pb, &[(64.0, 64.0)]);
        assert!(!res[0].ok, "a flat patch must be reported as untrackable");
    }

    #[test]
    fn pyramid_halves_each_level() {
        let a = checker(256, 256, 0, 0);
        let p = Pyramid::build(&a, 4);
        assert_eq!(p.levels.len(), 4);
        assert_eq!((p.levels[0].w, p.levels[0].h), (256, 256));
        assert_eq!((p.levels[1].w, p.levels[1].h), (128, 128));
        assert_eq!((p.levels[3].w, p.levels[3].h), (32, 32));
    }

    #[test]
    fn pyramid_stops_before_becoming_degenerate() {
        let a = checker(64, 64, 0, 0);
        let p = Pyramid::build(&a, 8);
        assert!(p.levels.len() < 8, "should stop once levels get tiny");
        assert!(p.levels.last().unwrap().w >= 16);
    }

    #[test]
    fn points_leaving_the_frame_are_dropped() {
        let a = checker(128, 128, 0, 0);
        let b = checker(128, 128, 3, 0);
        let pa = Pyramid::build(&a, 3);
        let pb = Pyramid::build(&b, 3);
        // Right on the border: the window cannot be sampled.
        let res = track(&pa, &pb, &[(1.0, 1.0)]);
        assert!(!res[0].ok);
    }
}
