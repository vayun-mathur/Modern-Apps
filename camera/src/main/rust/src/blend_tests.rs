//! Tests for blend.rs private helpers: box blur, reflect indexing, image
//! pyramids, content-rect cropping, and the public multiband entry point.

use super::*;
use crate::sphere::WarpedTile;

fn approx(a: f32, b: f32) -> bool {
    (a - b).abs() <= 1e-4
}

#[test]
fn box_blur_radius_zero_is_identity() {
    let src: Vec<f32> = (0..12).map(|i| i as f32).collect();
    assert_eq!(box_blur(&src, 4, 3, 0), src);
}

#[test]
fn box_blur_preserves_constant_plane_including_borders() {
    // A constant plane must blur to itself everywhere, borders and
    // corners included (<=1 gray level; f32 plane is exact in practice):
    // each pass normalises by the truncated window's actual sample count.
    let src = vec![7.0f32; 81];
    let out = box_blur(&src, 9, 9, 2);
    assert!(
        out.iter().all(|v| (v - 7.0).abs() <= 1.0 && approx(*v, 7.0)),
        "constant plane preserved incl. borders: corner {} centre {}",
        out[0],
        out[4 * 9 + 4]
    );
    // A radius larger than the image and a non-square plane too.
    let out2 = box_blur(&vec![3.5f32; 20], 5, 4, 3);
    assert!(out2.iter().all(|v| approx(*v, 3.5)), "small plane preserved: {out2:?}");
}

#[test]
fn box_blur_impulse_sums_approximately_and_peaks_at_centre() {
    // A single impulse spreads over the (2r+1)^2 window; total mass is only
    // approximately preserved because edges clamp the running window.
    let mut src = vec![0.0f32; 81];
    src[4 * 9 + 4] = 81.0;
    let out = box_blur(&src, 9, 9, 2);
    let centre = out[4 * 9 + 4];
    assert!(centre > out[0] && centre > out[4 * 9], "peak stays at impulse");
    assert!(out.iter().all(|v| *v >= 0.0));
    let total: f32 = out.iter().sum();
    assert!(total > 40.0 && total < 130.0, "mass roughly preserved, got {total}");
}

#[test]
fn reflect_101_mirrors_without_repeating_border() {
    assert_eq!(reflect_101(0, 5), 0);
    assert_eq!(reflect_101(4, 5), 4);
    assert_eq!(reflect_101(-1, 5), 1);
    assert_eq!(reflect_101(5, 5), 3);
    assert_eq!(reflect_101(6, 5), 2);
    assert_eq!(reflect_101(-3, 5), 3);
    // Degenerate widths collapse to 0 instead of looping forever.
    assert_eq!(reflect_101(7, 1), 0);
    assert_eq!(reflect_101(-7, 0 + 1), 0);
}

#[test]
fn reflect_101_handles_far_out_of_range_indices() {
    for idx in [-23isize, -9, 13, 41] {
        let r = reflect_101(idx, 5);
        assert!(r < 5, "idx {idx} -> {r}");
    }
}

fn const_img3(w: usize, h: usize, v: f32) -> Img3 {
    Img3 { w, h, data: vec![v; w * h * 3] }
}

#[test]
fn pyr_down_halves_dimensions_with_ceil_and_keeps_constants() {
    let down = pyr_down_3ch(&const_img3(7, 5, 42.0));
    assert_eq!((down.w, down.h), (4, 3), "ceil half of odd sizes");
    assert!(down.data.iter().all(|v| approx(*v, 42.0)));
    let down1 = pyr_down_1ch(&WMap { w: 8, h: 8, data: vec![3.0; 64] });
    assert_eq!((down1.w, down1.h), (4, 4));
    assert!(down1.data.iter().all(|v| approx(*v, 3.0)));
}

#[test]
fn pyr_down_of_empty_is_empty() {
    let d = pyr_down_3ch(&Img3 { w: 0, h: 0, data: Vec::new() });
    assert_eq!((d.w, d.h), (0, 0));
    let d1 = pyr_down_1ch(&WMap { w: 0, h: 4, data: Vec::new() });
    assert_eq!(d1.w * d1.h, 0);
}

#[test]
fn pyr_up_restores_requested_size_and_keeps_constants() {
    let up = pyr_up_3ch(&const_img3(2, 2, 9.0), 5, 3);
    assert_eq!((up.w, up.h), (5, 3));
    assert!(up.data.iter().all(|v| approx(*v, 9.0)));
    // Zero-size source upsamples to zeros of the requested shape.
    let z = pyr_up_3ch(&Img3 { w: 0, h: 0, data: Vec::new() }, 3, 2);
    assert_eq!(z.data.len(), 3 * 2 * 3);
    assert!(z.data.iter().all(|v| *v == 0.0));
}

#[test]
fn pyr_up_gaussian_expansion_interpolates_between_corners() {
    let mut src = const_img3(2, 1, 0.0);
    src.data[0] = 0.0;
    src.data[3] = 100.0; // right pixel red channel
    let up = pyr_up_3ch(&src, 4, 1);
    let reds: Vec<f32> = (0..4).map(|x| up.data[x * 3]).collect();
    assert!(reds[0] < reds[1] && reds[1] < reds[2] && reds[2] < reds[3], "monotone ramp {reds:?}");
    assert!(approx(reds[3], 100.0) || reds[3] > 75.0);
}

#[test]
fn content_rect_of_empty_and_full_grids() {
    let none = vec![false; 12];
    assert_eq!(content_rect(&none, 4, 3), (0, 0, 0, 0));
    let full = vec![true; 12];
    assert_eq!(content_rect(&full, 4, 3), (0, 0, 4, 3));
}

#[test]
fn content_rect_finds_maximal_opaque_rectangle() {
    // 6x4 grid with a 3x2 opaque block at (2,1).
    let mut valid = vec![false; 24];
    for y in 1..3 {
        for x in 2..5 {
            valid[y * 6 + x] = true;
        }
    }
    assert_eq!(content_rect(&valid, 6, 4), (2, 1, 3, 2));
}

#[test]
fn content_rect_prefers_wide_short_over_narrow_tall_on_area() {
    // L-shape: row of 5 plus a column of 3 sharing the corner. Best single
    // rectangle by area is the 5x1 row (area 5) vs 1x3 column (area 3) vs
    // any 2-wide sub-rectangle (area <= 4).
    let mut valid = vec![false; 25];
    for x in 0..5 {
        valid[x] = true;
    }
    for y in 0..3 {
        valid[y * 5] = true;
    }
    let (x, y, w, h) = content_rect(&valid, 5, 5);
    assert_eq!(w * h, 5, "best area 5, got ({x},{y},{w},{h})");
}

#[test]
fn crop_to_content_trims_transparent_borders() {
    let mut img = Rgba::new(6, 5);
    for y in 1..4 {
        for x in 2..5 {
            img.set(x, y, [10, 20, 30, 255]);
        }
    }
    let cropped = crop_to_content(img.clone());
    assert_eq!((cropped.w, cropped.h), (3, 3));
    assert_eq!(cropped.get(0, 0), [10, 20, 30, 255]);
    // Fully transparent image crops to itself (no valid rect).
    let empty = crop_to_content(Rgba::new(3, 3));
    assert_eq!((empty.w, empty.h), (3, 3));
}

fn tile(w: usize, h: usize, cx: i32, cy: i32, c: [u8; 4]) -> WarpedTile {
    let mut img = Rgba::new(w, h);
    for y in 0..h {
        for x in 0..w {
            img.set(x, y, c);
        }
    }
    WarpedTile { img, corner_x: cx, corner_y: cy }
}

#[test]
fn multiband_blend_empty_input_is_none() {
    assert!(multiband_blend(&[], &[], &[]).is_none());
}

#[test]
fn multiband_blend_single_tile_returns_its_colours() {
    let tiles = vec![tile(16, 16, 0, 0, [200, 100, 50, 255])];
    let masks = vec![vec![1u8; 16 * 16]];
    let gains = vec![vec![1.0f32; 16 * 16]];
    let out = multiband_blend(&tiles, &masks, &gains).expect("blend");
    assert_eq!((out.w, out.h), (16, 16));
    assert_eq!(out.get(8, 8), [200, 100, 50, 255]);
}

#[test]
fn multiband_blend_two_identical_overlapping_tiles_stays_uniform() {
    let tiles = vec![
        tile(16, 16, 0, 0, [120, 120, 120, 255]),
        tile(16, 16, 8, 0, [120, 120, 120, 255]),
    ];
    let masks: Vec<Vec<u8>> = tiles.iter().map(|t| vec![1u8; t.img.w * t.img.h]).collect();
    let gains: Vec<Vec<f32>> = tiles.iter().map(|t| vec![1.0f32; t.img.w * t.img.h]).collect();
    let out = multiband_blend(&tiles, &masks, &gains).expect("blend");
    assert!(out.w >= 16 && out.h == 16, "canvas covers both tiles: {}x{}", out.w, out.h);
    for y in 0..out.h {
        for x in 0..out.w {
            let p = out.get(x, y);
            assert_eq!(p[3], 255, "interior must be opaque at ({x},{y})");
            // Identical constant tiles must reconstruct to that constant
            // (within ~2% = 2 gray levels at 120), across the whole
            // canvas including the overlap: Gaussian pyrUp matching
            // pyrDown, plus whole-gap reflected tile borders, makes the
            // Laplacian reconstruction energy-preserving for constants.
            assert!((p[0] as i32 - 120).abs() <= 2, "uniform colour drifted: {p:?} at ({x},{y})");
            assert_eq!(p[0], p[1]);
            assert_eq!(p[1], p[2]);
        }
    }
}

#[test]
fn multiband_blend_gain_scales_colour_with_clamping() {
    let tiles = vec![tile(8, 8, 0, 0, [200, 10, 10, 255])];
    let masks = vec![vec![1u8; 64]];
    let gains = vec![vec![2.0f32; 64]];
    let out = multiband_blend(&tiles, &masks, &gains).expect("blend");
    // 200 * 2 clamps to 255 in the tile build; 10 * 2 = 20.
    assert_eq!(out.get(4, 4), [255, 20, 20, 255]);
}

#[test]
fn multiband_blend_zero_mask_falls_back_but_still_returns_coverage() {
    // All-zero masks give zero weights; the implementation falls back to the
    // feather path, whose second pass fills covered pixels directly.
    let tiles = vec![tile(8, 8, -4, -3, [30, 60, 90, 255])];
    let masks = vec![vec![0u8; 64]];
    let gains = vec![vec![1.0f32; 64]];
    let out = multiband_blend(&tiles, &masks, &gains).expect("fallback blend");
    assert_eq!((out.w, out.h), (8, 8));
    assert_eq!(out.get(4, 4), [30, 60, 90, 255]);
}
