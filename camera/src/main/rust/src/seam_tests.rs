//! Tests for seam.rs private helpers: max-flow min-cut, gradient maps,
//! colour differences, neighbourhood helpers, and the seam-mask contracts.

use super::*;
use crate::sphere::WarpedTile;

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
fn max_flow_single_edge_moves_full_capacity() {
    let mut mf = MaxFlow::new(2);
    mf.add_edge(0, 1, 7, 0);
    assert_eq!(mf.max_flow(0, 1), 7);
    let side = mf.min_cut_side(0);
    assert!(side[0] && !side[1], "saturated edge separates s from t");
}

#[test]
fn max_flow_bottleneck_is_the_min_cut() {
    // 0 -> 1 (10), 1 -> 2 (3), 0 -> 2 (2): max flow 5, cut capacity 5.
    let mut mf = MaxFlow::new(3);
    mf.add_edge(0, 1, 10, 10);
    mf.add_edge(1, 2, 3, 3);
    mf.add_edge(0, 2, 2, 2);
    assert_eq!(mf.max_flow(0, 2), 5);
}

#[test]
fn max_flow_disconnected_sink_flows_zero() {
    let mut mf = MaxFlow::new(3);
    mf.add_edge(0, 1, 5, 5);
    assert_eq!(mf.max_flow(0, 2), 0);
    assert!(mf.min_cut_side(0)[1], "reachable node stays on source side");
}

#[test]
fn max_flow_parallel_paths_add_up() {
    let mut mf = MaxFlow::new(4);
    mf.add_edge(0, 1, 4, 4);
    mf.add_edge(0, 2, 6, 6);
    mf.add_edge(1, 3, 4, 4);
    mf.add_edge(2, 3, 6, 6);
    assert_eq!(mf.max_flow(0, 3), 10);
}

#[test]
fn diff_l2_is_zero_for_equal_and_grows_with_distance() {
    assert_eq!(diff_l2([10, 20, 30, 255], [10, 20, 30, 0]), 0, "alpha ignored");
    assert_eq!(diff_l2([0, 0, 0, 255], [3, 4, 0, 255]), 5, "3-4-5 triangle");
    let far = diff_l2([0, 0, 0, 255], [255, 255, 255, 255]);
    assert!(far > 400, "white vs black L2 ~441, got {far}");
}

#[test]
fn luma_i_matches_rec601_approximately() {
    assert_eq!(luma_i([255, 0, 0, 255]), (0.299 * 255.0) as i64);
    assert_eq!(luma_i([0, 0, 0, 255]), 0);
    assert!(luma_i([255, 255, 255, 255]) >= 254);
}

#[test]
fn gradient_map_is_zero_on_flat_and_positive_on_edges() {
    let flat = tile(8, 8, 0, 0, [100, 100, 100, 255]);
    assert!(gradient_map(&flat.img).iter().all(|&g| g == 0));
    // Left half black, right half white: gradient concentrated at the seam.
    let mut img = Rgba::new(8, 4);
    for y in 0..4 {
        for x in 0..8 {
            let v = if x < 4 { 0 } else { 255 };
            img.set(x, y, [v, v, v, 255]);
        }
    }
    let g = gradient_map(&img);
    assert!(g[1 * 8 + 3] > 0 && g[1 * 8 + 4] > 0, "edge columns respond");
    assert_eq!(g[1 * 8], 0, "flat interior has no gradient");
    // Transparent pixels never produce gradient.
    let clear = gradient_map(&Rgba::new(4, 4));
    assert!(clear.iter().all(|&v| v == 0));
}

#[test]
fn neighbors_at_corners_and_centre() {
    assert_eq!(neighbors(0, 0, 3, 3).len(), 2);
    assert_eq!(neighbors(2, 2, 3, 3).len(), 2);
    assert_eq!(neighbors(1, 1, 3, 3).len(), 4);
    assert_eq!(neighbors(0, 1, 3, 3).len(), 3);
}

#[test]
fn global_bounds_covers_all_tiles_including_negative_corners() {
    let tiles = vec![tile(10, 8, -5, -3, [0, 0, 0, 255]), tile(4, 4, 20, 10, [0, 0, 0, 255])];
    assert_eq!(global_bounds(&tiles), (-5, -3, 24, 14));
}

#[test]
fn tile_at_and_covered_by_respect_bounds_and_alpha() {
    let t = tile(4, 4, 10, 20, [5, 6, 7, 255]);
    assert_eq!(tile_at(&t, 10, 20), Some([5, 6, 7, 255]));
    assert!(tile_at(&t, 9, 20).is_none());
    assert!(tile_at(&t, 14, 20).is_none());
    assert!(covered_by(&t, 13, 23));
    assert!(!covered_by(&t, 14, 23));
    let clear = tile(2, 2, 0, 0, [5, 6, 7, 0]);
    assert!(!covered_by(&clear, 0, 0), "transparent is not coverage");
}

#[test]
fn voronoi_masks_empty_and_single_tile() {
    assert!(voronoi_masks(&[]).is_empty());
    let t = vec![tile(6, 5, 3, 2, [9, 9, 9, 255])];
    let m = voronoi_masks(&t);
    assert_eq!(m.len(), 1);
    assert!(m[0].iter().all(|&v| v == 1), "sole tile owns all its pixels");
}

#[test]
fn voronoi_masks_partition_overlap_between_nearest_centres() {
    let tiles = vec![
        tile(10, 6, 0, 0, [255, 0, 0, 255]),
        tile(10, 6, 6, 0, [0, 0, 255, 255]),
    ];
    let masks = voronoi_masks(&tiles);
    let total: usize = masks.iter().map(|m| m.iter().filter(|&&v| v != 0).count()).sum();
    assert_eq!(total, 16 * 6, "union covered exactly once");
    // Leftmost column belongs to tile 0, rightmost to tile 1.
    assert_eq!(masks[0][0], 1);
    assert_eq!(masks[1][5 * 10 + 9], 1);
}

#[test]
fn seam_masks_agree_with_voronoi_on_disjoint_tiles() {
    let tiles = vec![
        tile(5, 5, 0, 0, [255, 0, 0, 255]),
        tile(5, 5, 30, 0, [0, 255, 0, 255]),
    ];
    let masks = seam_masks(&tiles);
    assert_eq!(masks.len(), 2);
    assert!(masks.iter().all(|m| m.iter().all(|&v| v == 1)));
    assert!(seam_masks(&[]).is_empty());
}

#[test]
fn seam_masks_partition_overlapping_coverage_exactly_once() {
    let tiles = vec![
        tile(12, 8, 0, 0, [200, 30, 30, 255]),
        tile(12, 8, 6, 2, [30, 30, 200, 255]),
    ];
    let masks = seam_masks(&tiles);
    // Union is the bounding box minus the two uncovered corners: compute
    // coverage from alpha and compare counts.
    let mut covered = 0usize;
    for gy in 0..10 {
        for gx in 0..18 {
            let cov = tiles.iter().any(|t| {
                let lx = gx - t.corner_x;
                let ly = gy - t.corner_y;
                lx >= 0 && ly >= 0 && (lx as usize) < t.img.w && (ly as usize) < t.img.h
            });
            if cov {
                covered += 1;
            }
        }
    }
    let owned: usize = masks.iter().map(|m| m.iter().filter(|&&v| v != 0).count()).sum();
    assert_eq!(owned, covered, "every covered pixel owned exactly once");
}
