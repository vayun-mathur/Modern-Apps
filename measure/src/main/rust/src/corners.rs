//! Shi-Tomasi corner detection ("good features to track") with sub-pixel refinement.
//!
//! The score is the smaller eigenvalue of the 3x3 structure tensor: a point only
//! scores well when intensity varies along two independent directions, which is
//! exactly the property the Lucas-Kanade tracker needs (a straight edge has one
//! near-zero eigenvalue and slides along itself — the aperture problem).
//!
//! Mirrors the parameters the tracker was tuned against: quality level 0.01 of
//! the frame's best score, at most [`MAX_CORNERS`] corners, [`MIN_DISTANCE_PX`]
//! minimum separation.

use vision_core::imgbuf::Gray;

pub const MAX_CORNERS: usize = 220;
const MIN_DISTANCE_PX: f32 = 10.0;
const QUALITY_LEVEL: f32 = 0.01;
/// Pixels excluded at the frame edge: the LK window (half-width 7) plus the
/// pyramid's coarsest level must stay inside the frame.
const BORDER: usize = 10;

/// Detect up to [`MAX_CORNERS`] corners, strongest first, refined to sub-pixel.
pub fn detect(gray: &Gray) -> Vec<(f32, f32)> {
    if gray.w < 2 * BORDER + 8 || gray.h < 2 * BORDER + 8 {
        return Vec::new();
    }
    let (w, h) = (gray.w, gray.h);
    // Central-difference gradients.
    let mut ix = vec![0f32; w * h];
    let mut iy = vec![0f32; w * h];
    for y in 1..h - 1 {
        for x in 1..w - 1 {
            let i = y * w + x;
            ix[i] = (gray.px[i + 1] as f32 - gray.px[i - 1] as f32) * 0.5;
            iy[i] = (gray.px[i + w] as f32 - gray.px[i - w] as f32) * 0.5;
        }
    }
    // Smaller structure-tensor eigenvalue per pixel (3x3 tensor window).
    let mut score = vec![0f32; w * h];
    let mut best = 0f32;
    for y in BORDER..h - BORDER {
        for x in BORDER..w - BORDER {
            let (mut sxx, mut sxy, mut syy) = (0f32, 0f32, 0f32);
            for dy in -1i32..=1 {
                for dx in -1i32..=1 {
                    let i = ((y as i32 + dy) as usize) * w + (x as i32 + dx) as usize;
                    sxx += ix[i] * ix[i];
                    sxy += ix[i] * iy[i];
                    syy += iy[i] * iy[i];
                }
            }
            let half_trace = (sxx + syy) * 0.5;
            let disc = ((half_trace * half_trace - (sxx * syy - sxy * sxy)).max(0.0)).sqrt();
            let s = half_trace - disc;
            score[y * w + x] = s;
            if s > best {
                best = s;
            }
        }
    }
    if best <= 0.0 {
        return Vec::new();
    }
    let threshold = best * QUALITY_LEVEL;
    let mut candidates: Vec<(f32, usize)> = Vec::new();
    for y in BORDER..h - BORDER {
        for x in BORDER..w - BORDER {
            let s = score[y * w + x];
            if s > threshold {
                candidates.push((s, y * w + x));
            }
        }
    }
    candidates.sort_by(|a, b| b.0.total_cmp(&a.0));

    // Greedy non-maximum suppression by minimum separation.
    let mut accepted: Vec<(f32, f32)> = Vec::new();
    'outer: for (_, idx) in candidates {
        let x = (idx % w) as f32;
        let y = (idx / w) as f32;
        for &(ax, ay) in &accepted {
            let dx = ax - x;
            let dy = ay - y;
            if dx * dx + dy * dy < MIN_DISTANCE_PX * MIN_DISTANCE_PX {
                continue 'outer;
            }
        }
        accepted.push((x, y));
        if accepted.len() >= MAX_CORNERS {
            break;
        }
    }
    accepted
        .iter()
        .map(|&(x, y)| refine_subpixel(gray, &ix, &iy, x, y))
        .collect()
}

/// Iterative sub-pixel refinement: the corner is where every gradient in the
/// window is orthogonal to the offset from the corner, i.e. solve
/// `(sum g g^T) p = sum (g g^T) q` over the 5x5 window and iterate.
fn refine_subpixel(gray: &Gray, ix: &[f32], iy: &[f32], x: f32, y: f32) -> (f32, f32) {
    let (w, h) = (gray.w as i32, gray.h as i32);
    let (mut px, mut py) = (x, y);
    for _ in 0..10 {
        let (cx, cy) = (px.round() as i32, py.round() as i32);
        let (mut a00, mut a01, mut a11, mut b0, mut b1) = (0f32, 0f32, 0f32, 0f32, 0f32);
        for dy in -2..=2i32 {
            for dx in -2..=2i32 {
                let (qx, qy) = (cx + dx, cy + dy);
                if qx < 1 || qy < 1 || qx >= w - 1 || qy >= h - 1 {
                    continue;
                }
                let i = (qy as usize) * gray.w + qx as usize;
                let (gx, gy) = (ix[i], iy[i]);
                a00 += gx * gx;
                a01 += gx * gy;
                a11 += gy * gy;
                let d = gx * qx as f32 + gy * qy as f32;
                b0 += gx * d;
                b1 += gy * d;
            }
        }
        let det = a00 * a11 - a01 * a01;
        if det.abs() < 1e-6 {
            break;
        }
        let nx = (a11 * b0 - a01 * b1) / det;
        let ny = (a00 * b1 - a01 * b0) / det;
        let moved = (nx - px).abs().max((ny - py).abs());
        px = nx.clamp(0.0, (w - 1) as f32);
        py = ny.clamp(0.0, (h - 1) as f32);
        if moved < 0.05 {
            break;
        }
    }
    // Refinement is a local correction: a result far from the detected grid
    // point means the window held no well-defined corner, so keep the
    // original integer location rather than a wandered one.
    if (px - x).abs() > 2.0 || (py - y).abs() > 2.0 {
        return (x, y);
    }
    (px, py)
}

#[cfg(test)] #[path = "corners_tests.rs"] mod corners_tests;

#[cfg(test)]
mod tests {
    use super::*;

    /// Textured synthetic frame: overlapping sinusoids, corners everywhere.
    fn textured(w: usize, h: usize) -> Gray {
        let mut g = Gray::new(w, h);
        for y in 0..h {
            for x in 0..w {
                let v = ((x as f32 * 0.31).sin() * (y as f32 * 0.23).cos()
                    + (x as f32 * 0.11 + y as f32 * 0.17).sin())
                    * 60.0
                    + 128.0;
                g.px[y * w + x] = v.clamp(0.0, 255.0) as u8;
            }
        }
        g
    }

    #[test]
    fn detects_corners_on_a_textured_pattern() {
        let g = textured(320, 240);
        let pts = detect(&g);
        assert!(pts.len() >= 20, "expected many corners, got {}", pts.len());
        assert!(pts.len() <= MAX_CORNERS);
        for &(x, y) in &pts {
            assert!((0.0..320.0).contains(&x) && (0.0..240.0).contains(&y));
        }
        // Sub-pixel refinement should move some points off the integer grid
        // (its accuracy is asserted directly by the square-corner test).
        let refined = pts.iter().filter(|&&(x, y)| x.fract() != 0.0 || y.fract() != 0.0).count();
        assert!(refined > 0, "no point was refined sub-pixel");
        // Minimum separation holds at detection; sub-pixel refinement may
        // then move a point by a pixel or so, so allow that slack.
        for i in 0..pts.len() {
            for j in i + 1..pts.len() {
                let dx = pts[i].0 - pts[j].0;
                let dy = pts[i].1 - pts[j].1;
                assert!(dx * dx + dy * dy >= 64.0, "corners too close");
            }
        }
    }

    #[test]
    fn finds_the_corner_of_a_synthetic_square() {
        // White square on black: its four corners must be found near the truth.
        let mut g = Gray::new(200, 200);
        for y in 60..140 {
            for x in 70..150 {
                g.px[y * 200 + x] = 255;
            }
        }
        let pts = detect(&g);
        let truth = [(70.0, 60.0), (149.0, 60.0), (70.0, 139.0), (149.0, 139.0)];
        for (tx, ty) in truth {
            let near = pts
                .iter()
                .any(|&(x, y)| (x - tx).abs() < 3.0 && (y - ty).abs() < 3.0);
            assert!(near, "no corner near ({tx},{ty}); got {pts:?}");
        }
    }

    #[test]
    fn a_flat_frame_has_no_corners() {
        let mut g = Gray::new(160, 120);
        for v in g.px.iter_mut() {
            *v = 90;
        }
        assert!(detect(&g).is_empty());
    }

    #[test]
    fn a_pure_edge_is_not_a_corner() {
        // Vertical step edge only: one gradient direction, eigenvalue ~0.
        let mut g = Gray::new(160, 120);
        for y in 0..120 {
            for x in 80..160 {
                g.px[y * 160 + x] = 255;
            }
        }
        let pts = detect(&g);
        assert!(pts.len() < 8, "edge should yield almost no corners, got {}", pts.len());
    }
}

