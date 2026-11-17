//! Tests for stitch.rs private helpers: match remapping, medians, gain resize,
//! compose-mask partitioning, and the public pipeline's degenerate inputs.

use super::*;
use crate::linalg::Matrix3;
use crate::sphere::WarpedTile;

fn mi(src: usize, dst: usize, conf: f64) -> MatchInfo {
    MatchInfo {
        src,
        dst,
        h: Matrix3::identity(),
        inliers: vec![((0.0, 0.0), (1.0, 1.0))],
        confidence: conf,
        num_inliers: 1,
    }
}

#[test]
fn remap_matches_reindexes_and_drops_unkept_frames() {
    let kept = vec![2usize, 5];
    let out = remap_matches(vec![mi(2, 5, 0.9), mi(5, 2, 0.8), mi(0, 2, 0.9)], &kept);
    assert_eq!(out.len(), 2, "match touching dropped frame 0 is removed");
    assert_eq!((out[0].src, out[0].dst), (0, 1));
    assert_eq!((out[1].src, out[1].dst), (1, 0));
}

#[test]
fn remap_matches_with_empty_kept_list_drops_everything() {
    assert!(remap_matches(vec![mi(0, 1, 1.0)], &[]).is_empty());
    assert!(remap_matches(vec![], &[0, 1]).is_empty());
}

fn cam(focal: f64) -> CameraParams {
    CameraParams { focal, aspect: 1.0, ppx: 0.0, ppy: 0.0, r: Matrix3::identity() }
}

#[test]
fn median_focal_odd_even_and_degenerate_inputs() {
    assert_eq!(median_focal(&[cam(100.0), cam(300.0), cam(200.0)]), 200.0);
    assert_eq!(median_focal(&[cam(100.0), cam(200.0), cam(300.0), cam(400.0)]), 250.0);
    assert_eq!(median_focal(&[]), 1.0, "empty falls back to 1.0");
    // Non-finite / implausible focals are filtered before the median.
    assert_eq!(median_focal(&[cam(f64::NAN), cam(0.5), cam(500.0)]), 500.0);
    assert_eq!(median_focal(&[cam(f64::NAN)]), 1.0);
}

#[test]
fn median_f32_filters_nonpositive_and_nonfinite() {
    assert_eq!(median_f32(&[1.0, 3.0, 2.0]), 2.0);
    assert_eq!(median_f32(&[1.0, 2.0, 3.0, 4.0]), 2.5);
    assert_eq!(median_f32(&[]), 0.0);
    assert_eq!(median_f32(&[0.0, -1.0, f32::NAN, 8.0]), 8.0);
}

#[test]
fn resize_gain_same_size_is_identity() {
    let src: Vec<f32> = (0..16).map(|i| 1.0 + i as f32 * 0.1).collect();
    let out = resize_gain(&src, 4, 4, 4, 4);
    for (a, b) in out.iter().zip(src.iter()) {
        assert!((a - b).abs() < 1e-3, "same-size resize must be identity: {a} vs {b}");
    }
}

#[test]
fn resize_gain_constant_map_stays_constant_at_any_size() {
    let out = resize_gain(&[2.5f32; 4], 2, 2, 7, 5);
    assert_eq!(out.len(), 35);
    assert!(out.iter().all(|v| (v - 2.5).abs() < 1e-6));
}

#[test]
fn resize_gain_degenerate_source_is_unity() {
    let out = resize_gain(&[], 0, 0, 3, 2);
    assert_eq!(out, vec![1.0f32; 6]);
}

#[test]
fn resize_gain_upscale_interpolates_monotonically() {
    // 2x1 ramp 0 -> 2 stretched to 5x1 must be non-decreasing and hit ends.
    let out = resize_gain(&[0.0f32, 2.0], 2, 1, 5, 1);
    assert!(out.windows(2).all(|w| w[1] >= w[0] - 1e-6), "monotone: {out:?}");
    assert!((out[0] - 0.0).abs() < 0.5 && (out[4] - 2.0).abs() < 0.5);
}

fn opaque_tile(w: usize, h: usize, cx: i32, cy: i32) -> WarpedTile {
    let mut img = Rgba::new(w, h);
    for y in 0..h {
        for x in 0..w {
            img.set(x, y, [1, 2, 3, 255]);
        }
    }
    WarpedTile { img, corner_x: cx, corner_y: cy }
}

#[test]
fn build_compose_masks_partitions_coverage_exactly_once() {
    let tiles = vec![opaque_tile(10, 10, 0, 0), opaque_tile(10, 10, 5, 0)];
    let low: Vec<Vec<u8>> = tiles.iter().map(|t| vec![1u8; t.img.w * t.img.h]).collect();
    let masks = build_compose_masks(&tiles, &tiles, &low);
    // Every covered global pixel (15x10 union) owned by exactly one mask.
    let mut owned = 0usize;
    for (m, t) in masks.iter().zip(tiles.iter()) {
        assert_eq!(m.len(), t.img.w * t.img.h);
        owned += m.iter().filter(|&&v| v != 0).count();
    }
    assert_eq!(owned, 15 * 10, "union coverage assigned exactly once");
}

#[test]
fn build_compose_masks_disjoint_tiles_each_own_their_area() {
    let tiles = vec![opaque_tile(4, 4, 0, 0), opaque_tile(4, 4, 20, 20)];
    let low: Vec<Vec<u8>> = tiles.iter().map(|t| vec![1u8; t.img.w * t.img.h]).collect();
    let masks = build_compose_masks(&tiles, &tiles, &low);
    assert!(masks.iter().all(|m| m.iter().all(|&v| v == 1)));
}

#[test]
fn build_compose_masks_empty_input_gives_empty_masks() {
    assert!(build_compose_masks(&[], &[], &[]).is_empty());
}

#[test]
fn stitch_panorama_degenerate_inputs_return_none_or_single_frame() {
    assert!(stitch_panorama(&[], &[], &[]).is_none());
    assert!(stitch_panorama(&[vec![1, 2, 3]], &[0.0], &[0.0]).is_none(), "bad JPEG");
    assert!(estimate_pano(&[vec![1, 2, 3]], &[0.0], &[0.0]).is_none(), "needs >= 2 frames");
    assert!(estimate_pano(&[], &[], &[]).is_none());
}
