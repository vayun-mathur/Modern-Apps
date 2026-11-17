//! Additional tests for `klt`: bilinear sampling, single-level tracking,
//! pyramid construction edges, and the coarse-to-fine advantage on large
//! displacements. Basic translation tracking is covered inline.

use super::*;

/// Smooth sinusoid texture at a (possibly fractional) shift.
fn checker_f(w: usize, h: usize, sx: f32, sy: f32) -> Gray {
    let mut g = Gray::new(w, h);
    for y in 0..h {
        for x in 0..w {
            let v = (((x as f32 - sx) * 0.35).sin() * ((y as f32 - sy) * 0.27).cos() + 1.0)
                * 110.0;
            g.px[y * w + x] = v as u8;
        }
    }
    g
}

/// Smooth aperiodic texture: seeded random coarse grid, bilinear-upsampled.
/// Unlike a pure sinusoid it has no period for the tracker to alias onto.
fn aperiodic(w: usize, h: usize, shift_x: i32, shift_y: i32) -> Gray {
    let mut seed = 20240u64;
    let mut rnd = || {
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
        ((seed >> 40) & 0xff) as f32
    };
    let gw = w / 4 + 8;
    let gh = h / 4 + 8;
    let grid: Vec<f32> = (0..gw * gh).map(|_| rnd()).collect();
    let mut g = Gray::new(w, h);
    for y in 0..h {
        for x in 0..w {
            let gx = (x as i32 - shift_x) as f32 / 4.0 + 4.0;
            let gy = (y as i32 - shift_y) as f32 / 4.0 + 4.0;
            let (x0, y0) = (gx.floor() as usize, gy.floor() as usize);
            let (fx, fy) = (gx - x0 as f32, gy - y0 as f32);
            let v = grid[y0 * gw + x0] * (1.0 - fx) * (1.0 - fy)
                + grid[y0 * gw + x0 + 1] * fx * (1.0 - fy)
                + grid[(y0 + 1) * gw + x0] * (1.0 - fx) * fy
                + grid[(y0 + 1) * gw + x0 + 1] * fx * fy;
            g.px[y * w + x] = v.clamp(0.0, 255.0) as u8;
        }
    }
    g.gaussian_blur_3x3()
}

// --- Bilinear sampling --------------------------------------------------------

#[test]
fn sample_at_integer_coordinates_returns_the_exact_pixel() {
    let mut g = Gray::new(8, 8);
    for y in 0..8 {
        for x in 0..8 {
            g.px[y * 8 + x] = (y * 8 + x) as u8;
        }
    }
    assert_eq!(sample(&g, 3.0, 2.0), Some(2.0 * 8.0 + 3.0));
    assert_eq!(sample(&g, 0.0, 0.0), Some(0.0));
}

#[test]
fn sample_interpolates_linearly_between_pixels() {
    // A horizontal ramp: value == x * 10 everywhere.
    let mut g = Gray::new(16, 4);
    for y in 0..4 {
        for x in 0..16 {
            g.px[y * 16 + x] = (x * 10) as u8;
        }
    }
    assert_eq!(sample(&g, 2.5, 1.0), Some(25.0));
    assert_eq!(sample(&g, 2.25, 1.0), Some(22.5));
    // Vertical interpolation on the same ramp changes nothing.
    assert_eq!(sample(&g, 2.5, 1.5), Some(25.0));
}

#[test]
fn sample_rejects_points_outside_the_valid_interior() {
    let g = Gray::new(16, 16);
    assert_eq!(sample(&g, -0.5, 5.0), None);
    assert_eq!(sample(&g, 5.0, -1.0), None);
    // The last valid coordinate is just under w-1 / h-1: bilinear needs the
    // (x+1, y+1) neighbours to exist.
    assert_eq!(sample(&g, 15.0, 5.0), None);
    assert_eq!(sample(&g, 5.0, 15.0), None);
    assert!(sample(&g, 14.9, 14.9).is_some());
}

// --- Pyramid construction ------------------------------------------------------

#[test]
fn pyramid_with_one_level_is_just_the_base_image() {
    let a = checker_f(128, 96, 0.0, 0.0);
    let p = Pyramid::build(&a, 1);
    assert_eq!(p.levels.len(), 1);
    assert_eq!((p.levels[0].w, p.levels[0].h), (128, 96));
    assert_eq!(p.levels[0].px, a.px);
}

#[test]
fn pyramid_levels_keep_the_same_content_at_half_resolution() {
    // Downsampled level of a smooth image should roughly preserve local means:
    // compare the centre pixel against the corresponding base neighbourhood.
    let a = checker_f(160, 160, 0.0, 0.0);
    let p = Pyramid::build(&a, 3);
    let base_mean = {
        let mut s = 0f32;
        for y in 78..83 {
            for x in 78..83 {
                s += a.at(x, y) as f32;
            }
        }
        s / 25.0
    };
    let coarse = p.levels[1].at(40, 40) as f32;
    assert!((coarse - base_mean).abs() < 20.0, "coarse {coarse} vs base {base_mean}");
}

#[test]
fn pyramid_of_a_tiny_image_stays_a_single_level() {
    let a = checker_f(20, 20, 0.0, 0.0);
    let p = Pyramid::build(&a, 4);
    assert_eq!(p.levels.len(), 1);
}

// --- Single-level tracking -------------------------------------------------------

#[test]
fn track_level_on_identical_images_finds_zero_displacement() {
    let a = checker_f(128, 128, 0.0, 0.0);
    let v = track_level(&a, &a, 64.0, 64.0, (0.0, 0.0)).expect("track");
    assert!(v.0.abs() < 0.02 && v.1.abs() < 0.02, "got {v:?}");
}

#[test]
fn track_level_recovers_a_small_subpixel_shift() {
    let a = checker_f(128, 128, 0.0, 0.0);
    let b = checker_f(128, 128, 1.3, -0.7);
    let v = track_level(&a, &b, 64.0, 64.0, (0.0, 0.0)).expect("track");
    assert!((v.0 - 1.3).abs() < 0.15, "dx {}", v.0);
    assert!((v.1 + 0.7).abs() < 0.15, "dy {}", v.1);
}

#[test]
fn track_level_rejects_a_flat_patch() {
    let mut a = Gray::new(128, 128);
    for v in a.px.iter_mut() {
        *v = 200;
    }
    assert!(track_level(&a, &a, 64.0, 64.0, (0.0, 0.0)).is_none());
}

#[test]
fn track_level_rejects_a_pure_edge_patch() {
    // Vertical step edge: gradient only along x, so the 2x2 structure tensor
    // is (near-)singular and y displacement is unobservable.
    let mut a = Gray::new(128, 128);
    for y in 0..128 {
        for x in 64..128 {
            a.px[y * 128 + x] = 255;
        }
    }
    assert!(track_level(&a, &a, 64.0, 64.0, (0.0, 0.0)).is_none());
}

#[test]
fn track_level_rejects_a_window_centred_outside_the_frame() {
    let a = checker_f(128, 128, 0.0, 0.0);
    assert!(track_level(&a, &a, 2.0, 2.0, (0.0, 0.0)).is_none());
    assert!(track_level(&a, &a, 126.0, 64.0, (0.0, 0.0)).is_none());
}

// --- Full pyramidal tracking ------------------------------------------------------

#[test]
fn tracks_a_fractional_pixel_shift() {
    let a = checker_f(160, 160, 0.0, 0.0);
    let b = checker_f(160, 160, 2.5, 1.5);
    let res = track(&Pyramid::build(&a, 3), &Pyramid::build(&b, 3), &[(80.0, 80.0)]);
    assert!(res[0].ok);
    assert!((res[0].x - 82.5).abs() < 0.25, "x {}", res[0].x);
    assert!((res[0].y - 81.5).abs() < 0.25, "y {}", res[0].y);
}

#[test]
fn the_pyramid_tracks_large_shifts_a_single_level_cannot() {
    // 11 px exceeds the useful convergence radius of a 15x15 single-level
    // window; coarse-to-fine tracking shrinks it to ~3 px at the top level.
    let a = aperiodic(240, 240, 0, 0);
    let b = aperiodic(240, 240, 11, -6);
    let pts = vec![(90.0f32, 150.0f32), (150.0, 90.0)];
    let pyr = track(&Pyramid::build(&a, 3), &Pyramid::build(&b, 3), &pts);
    let single = track(&Pyramid::build(&a, 1), &Pyramid::build(&b, 1), &pts);
    for i in 0..pts.len() {
        assert!(pyr[i].ok, "pyramidal track failed for point {i}");
        assert!((pyr[i].x - (pts[i].0 + 11.0)).abs() < 0.75, "pyr x {}", pyr[i].x);
        assert!((pyr[i].y - (pts[i].1 - 6.0)).abs() < 0.75, "pyr y {}", pyr[i].y);
        let single_err = ((single[i].x - (pts[i].0 + 11.0)).powi(2)
            + (single[i].y - (pts[i].1 - 6.0)).powi(2))
        .sqrt();
        assert!(
            !single[i].ok || single_err > 2.0,
            "single level unexpectedly accurate for point {i}: err {single_err}"
        );
    }
}

#[test]
fn tracking_an_empty_point_list_returns_empty() {
    let a = checker_f(64, 64, 0.0, 0.0);
    let res = track(&Pyramid::build(&a, 3), &Pyramid::build(&a, 3), &[]);
    assert!(res.is_empty());
}

#[test]
fn mismatched_pyramid_depths_still_track_small_shifts() {
    let a = checker_f(160, 160, 0.0, 0.0);
    let b = checker_f(160, 160, 2.0, 1.0);
    let res = track(&Pyramid::build(&a, 3), &Pyramid::build(&b, 2), &[(80.0, 80.0)]);
    assert!(res[0].ok);
    assert!((res[0].x - 82.0).abs() < 0.5 && (res[0].y - 81.0).abs() < 0.5);
}

#[test]
fn a_point_whose_window_leaves_the_frame_is_dropped_not_clamped() {
    let a = checker_f(128, 128, 0.0, 0.0);
    let b = checker_f(128, 128, 3.0, 0.0);
    let res = track(&Pyramid::build(&a, 3), &Pyramid::build(&b, 3), &[(5.0, 64.0)]);
    assert!(!res[0].ok);
    // On failure the result reports the original position, un-displaced.
    assert_eq!((res[0].x, res[0].y), (5.0, 64.0));
}

#[test]
fn tracking_from_a_blank_frame_is_rejected() {
    // The reverse direction of an occlusion: with a flat source patch the
    // displacement is unobservable, whatever the target holds. This is what
    // makes the session's forward-backward check reject points that were
    // "tracked" into a featureless region.
    let a = aperiodic(200, 200, 0, 0);
    let mut blank = Gray::new(200, 200);
    for v in blank.px.iter_mut() {
        *v = 128;
    }
    let pts: Vec<(f32, f32)> = (0..5).map(|i| (60.0 + i as f32 * 20.0, 100.0)).collect();
    let res = track(&Pyramid::build(&blank, 3), &Pyramid::build(&a, 3), &pts);
    assert!(res.iter().all(|r| !r.ok));
}
