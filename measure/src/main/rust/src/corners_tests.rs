//! Additional tests for `corners`: size guards, the corner cap, strongest-first
//! ordering, border exclusion and sub-pixel refinement accuracy. Basic
//! detection quality is covered by the inline test module.

use super::*;

/// White rectangle on black, `value` bright, spanning (x0..x1, y0..y1).
fn rect(w: usize, h: usize, x0: usize, x1: usize, y0: usize, y1: usize, value: u8) -> Gray {
    let mut g = Gray::new(w, h);
    for y in y0..y1 {
        for x in x0..x1 {
            g.px[y * w + x] = value;
        }
    }
    g
}

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
fn frames_too_small_for_the_border_have_no_corners() {
    // BORDER is 10 per side plus the structure-tensor window: anything under
    // 2*BORDER+8 in either dimension must yield nothing (and not panic).
    assert!(detect(&Gray::new(27, 100)).is_empty());
    assert!(detect(&Gray::new(100, 27)).is_empty());
    assert!(detect(&Gray::new(0, 0)).is_empty());
    assert!(detect(&Gray::new(10, 10)).is_empty());
    // Just above the guard bound, detection runs and finds texture corners.
    assert!(!detect(&textured(64, 64)).is_empty());
}

#[test]
fn detection_is_capped_at_max_corners_on_dense_texture() {
    let pts = detect(&textured(800, 600));
    assert_eq!(pts.len(), MAX_CORNERS, "dense texture should saturate the cap");
}

#[test]
fn detection_is_deterministic() {
    let g = textured(320, 240);
    assert_eq!(detect(&g), detect(&g));
}

#[test]
fn strongest_corners_come_first() {
    // Two squares, one full-contrast and one faint. The structure-tensor score
    // scales with contrast squared, so the bright square's corners must be
    // accepted (and therefore returned) before the faint square's.
    let mut g = rect(320, 200, 40, 110, 40, 110, 255);
    for y in 40..110 {
        for x in 200..270 {
            g.px[y * 320 + x] = 60;
        }
    }
    let pts = detect(&g);
    assert!(pts.len() >= 8, "got {}", pts.len());
    let (fx, fy) = pts[0];
    assert!(
        (37.0..=113.0).contains(&fx) && (37.0..=113.0).contains(&fy),
        "strongest corner should belong to the bright square, got ({fx},{fy})"
    );
}

#[test]
fn corners_stay_out_of_the_border_band() {
    // A square whose corner sits inside the border band must not be reported
    // there: the LK window around a seeded point has to fit in the frame.
    let g = rect(200, 200, 2, 60, 2, 60, 255);
    let pts = detect(&g);
    for &(x, y) in &pts {
        assert!(x >= 8.0 && y >= 8.0, "corner in border band at ({x},{y})");
    }
    // The far corners (59,59) are outside the band and should still be found.
    assert!(pts.iter().any(|&(x, y)| (x - 59.0).abs() < 3.0 && (y - 59.0).abs() < 3.0));
}

#[test]
fn corners_respect_minimum_separation() {
    let pts = detect(&textured(400, 300));
    assert!(pts.len() > 20);
    for i in 0..pts.len() {
        for j in i + 1..pts.len() {
            let dx = pts[i].0 - pts[j].0;
            let dy = pts[i].1 - pts[j].1;
            // 10 px at detection; refinement may close at most ~2 px of that.
            assert!(dx * dx + dy * dy >= 64.0, "points {i},{j} too close");
        }
    }
}

#[test]
fn subpixel_refinement_locates_an_antialiased_corner() {
    // Square with a half-grey leading column/row: the true corner sits at the
    // half-pixel position (70.5, 60.5), and refinement must find it there
    // rather than snapping to a neighbouring integer pixel.
    let mut g = Gray::new(200, 200);
    for y in 61..140 {
        g.px[y * 200 + 70] = 128;
        for x in 71..150 {
            g.px[y * 200 + x] = 255;
        }
    }
    for x in 71..150 {
        g.px[60 * 200 + x] = 255;
    }
    g.px[60 * 200 + 70] = 128;
    // Top edge AA row.
    for x in 71..150 {
        g.px[59 * 200 + x] = 0;
    }
    let pts = detect(&g);
    let near: Vec<(f32, f32)> = pts
        .iter()
        .copied()
        .filter(|&(x, y)| (x - 70.5).abs() < 2.5 && (y - 60.5).abs() < 2.5)
        .collect();
    assert!(!near.is_empty(), "no corner near (70.5,60.5); got {pts:?}");
    let (x, y) = near[0];
    assert!((x - 70.5).abs() < 1.5 && (y - 60.5).abs() < 1.5, "refined to ({x},{y})");
    assert!(x.fract() != 0.0 || y.fract() != 0.0, "expected a sub-pixel position");
}

#[test]
fn a_checkerboard_yields_corners_at_square_junctions() {
    // Alternating 20 px squares: interior T-junctions are true corners with
    // gradients in both axes, unlike the straight edges between squares.
    let mut g = Gray::new(200, 200);
    for y in 0..200 {
        for x in 0..200 {
            if (x / 20 + y / 20) % 2 == 0 {
                g.px[y * 200 + x] = 255;
            }
        }
    }
    let pts = detect(&g);
    assert!(pts.len() >= 10, "got {}", pts.len());
    // A junction, e.g. near (40,40), should be among them.
    assert!(
        pts.iter().any(|&(x, y)| (x - 40.0).abs() < 3.0 && (y - 40.0).abs() < 3.0),
        "no corner near junction (40,40); got {pts:?}"
    );
}

#[test]
fn low_contrast_texture_still_detects_against_its_own_best() {
    // Quality level is relative to the frame's best score, so a faint but
    // structured pattern (amplitude +/-6 grey levels) still yields corners.
    let mut g = Gray::new(240, 180);
    for y in 0..180 {
        for x in 0..240 {
            let v = ((x as f32 * 0.31).sin() * (y as f32 * 0.23).cos()) * 6.0 + 128.0;
            g.px[y * 240 + x] = v.clamp(0.0, 255.0) as u8;
        }
    }
    let pts = detect(&g);
    assert!(pts.len() >= 10, "faint texture gave {}", pts.len());
}

#[test]
fn refine_subpixel_keeps_a_well_defined_corner_near_its_start() {
    // Build gradients exactly as detect does and refine the corner of a square.
    let g = rect(200, 200, 70, 150, 60, 140, 255);
    let (w, h) = (g.w, g.h);
    let mut ix = vec![0f32; w * h];
    let mut iy = vec![0f32; w * h];
    for y in 1..h - 1 {
        for x in 1..w - 1 {
            let i = y * w + x;
            ix[i] = (g.px[i + 1] as f32 - g.px[i - 1] as f32) * 0.5;
            iy[i] = (g.px[i + w] as f32 - g.px[i - w] as f32) * 0.5;
        }
    }
    // Integer corner candidates: the exact edge pixels are (70,60) etc.
    let (x, y) = refine_subpixel(&g, &ix, &iy, 70.0, 60.0);
    assert!((x - 70.0).abs() <= 2.0 && (y - 60.0).abs() <= 2.0, "refined to ({x},{y})");
    // On a flat region the system is singular: refinement must give up and
    // return the starting point unchanged.
    let flat = Gray::new(100, 100);
    let zeros = vec![0f32; 100 * 100];
    assert_eq!(refine_subpixel(&flat, &zeros, &zeros, 50.0, 50.0), (50.0, 50.0));
}
