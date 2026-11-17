//! Tests for exposure.rs private helpers (luma norm, separable smoothing)
//! and compensate() contracts on synthetic warped tiles.

use super::*;
use crate::imgbuf::Rgba;
use crate::sphere::WarpedTile;

fn tile(w: usize, h: usize, cx: i32, cy: i32, v: u8) -> WarpedTile {
    let mut img = Rgba::new(w, h);
    for y in 0..h {
        for x in 0..w {
            img.set(x, y, [v, v, v, 255]);
        }
    }
    WarpedTile { img, corner_x: cx, corner_y: cy }
}

#[test]
fn luma_norm_is_euclidean_length() {
    assert_eq!(luma_norm([3, 4, 0, 255]), 5.0);
    assert_eq!(luma_norm([0, 0, 0, 255]), 0.0);
    let white = luma_norm([255, 255, 255, 0]); // alpha ignored
    assert!((white - (3.0f64 * 255.0 * 255.0).sqrt()).abs() < 1e-9);
}

#[test]
fn sep_filter_preserves_constant_plane() {
    let mut src = vec![4.0f32; 36];
    sep_filter_025_05_025(&mut src, 6, 6);
    assert!(src.iter().all(|v| (*v - 4.0).abs() < 1e-6));
}

#[test]
fn sep_filter_smooths_a_spike_toward_neighbours() {
    // Single bright centre in a 3x3: after one pass the centre drops and
    // the (clamped-border) neighbours rise; mass stays in a sane band.
    let mut src = vec![0.0f32; 9];
    src[4] = 16.0;
    sep_filter_025_05_025(&mut src, 3, 3);
    assert!(src[4] < 16.0 && src[4] > 0.0, "centre smoothed: {}", src[4]);
    assert!(src.iter().all(|v| *v >= 0.0));
    let total: f32 = src.iter().sum();
    assert!(total > 8.0 && total < 24.0, "mass roughly kept, got {total}");
}

#[test]
fn sep_filter_single_pixel_is_unchanged() {
    let mut src = vec![9.0f32];
    sep_filter_025_05_025(&mut src, 1, 1);
    assert_eq!(src, vec![9.0]);
}

#[test]
fn compensate_single_tile_is_unity_gain() {
    // No overlaps -> no equations -> all gains default to 1.
    let tiles = vec![tile(40, 40, 0, 0, 128)];
    let gains = compensate(&tiles);
    assert_eq!(gains.len(), 1);
    assert_eq!(gains[0].len(), 40 * 40);
    assert!(gains[0].iter().all(|v| *v == 1.0));
}

#[test]
fn compensate_equal_brightness_overlap_stays_near_unity() {
    // Two identical-brightness overlapping tiles: the gain solver should
    // keep both near 1 (nothing to correct), finite and within clamps.
    let tiles = vec![tile(48, 48, 0, 0, 150), tile(48, 48, 24, 0, 150)];
    let gains = compensate(&tiles);
    assert_eq!(gains.len(), 2);
    for g in &gains {
        assert_eq!(g.len(), 48 * 48);
        for v in g {
            assert!(v.is_finite() && (0.25..=4.0).contains(v), "gain {v} outside clamp");
            assert!((*v - 1.0).abs() < 0.35, "equal inputs should stay near unity, got {v}");
        }
    }
}

#[test]
fn compensate_dark_tile_gets_gain_above_bright_tile() {
    // Overlapping dark (60) vs bright (180): compensation must push the
    // dark tile's gain up relative to the bright tile's.
    let tiles = vec![tile(48, 48, 0, 0, 60), tile(48, 48, 24, 0, 180)];
    let gains = compensate(&tiles);
    let mean = |g: &[f32]| g.iter().sum::<f32>() / g.len() as f32;
    assert!(mean(&gains[0]) > mean(&gains[1]), "dark {} vs bright {}", mean(&gains[0]), mean(&gains[1]));
}

#[test]
fn compensate_respects_output_shape_for_odd_tile_sizes() {
    let tiles = vec![tile(33, 17, -5, 3, 100), tile(20, 40, 10, -8, 120)];
    let gains = compensate(&tiles);
    for (g, t) in gains.iter().zip(tiles.iter()) {
        assert_eq!(g.len(), t.img.w * t.img.h);
        assert!(g.iter().all(|v| v.is_finite() && *v > 0.0));
    }
}
