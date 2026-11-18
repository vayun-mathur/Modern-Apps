//! Expanded crate-root tests (moved inline tests + pure-logic coverage).
use super::*;
use crate::om_tests::{array_var, build_om, MemBackend, TestVar, VarSpec};
use crate::om::OmReader;

fn dwd_icon() -> Grid {
    Grid { nx: 2879, ny: 1441, lon_min: -180.0, lat_min: -90.0, dx: 0.125, dy: 0.125 }
}
fn small() -> Grid {
    Grid { nx: 20, ny: 16, lon_min: 0.0, lat_min: 0.0, dx: 0.5, dy: 0.5 }
}
fn w2() -> Window { Window { y0: 0, y1: 2, x0: 0, x1: 2 } }

// --- moved tests ---
#[test]
fn grid_covering_window_basic() {
    let grid = dwd_icon();
    let win = grid.covering_window(5.0, 47.0, 15.0, 55.0).expect("window");
    assert!(win.x1 > win.x0 && win.y1 > win.y0);
}
#[test]
fn bilinear_finite() {
    let data = vec![1.0f32, 2.0, 3.0, 4.0];
    let v = bilinear(&data, 2, 2, &w2(), 0.5, 0.5);
    assert!(v > 2.0 && v < 3.0);
}
#[test]
fn resample_produces_finite_values() {
    let grid = Grid { nx: 2, ny: 2, lon_min: 5.0, lat_min: 47.0, dx: 10.0, dy: 8.0 };
    let win = grid.covering_window(5.0, 47.0, 15.0, 55.0).expect("window");
    let (sx, sy) = (win.x1 - win.x0, win.y1 - win.y0);
    let data: Vec<f32> = (0..sx * sy).map(|i| i as f32 * 10.0).collect();
    let out = resample(data, sx, sy, &win, &grid, 5.0, 47.0, 15.0, 55.0, 4, 4);
    assert_eq!(out.len(), 16);
    assert!(out.iter().all(|v| v.is_finite()));
}

// --- covering_window ---
#[test]
fn covering_degenerate_point() {
    let win = small().covering_window(2.25, 2.25, 2.25, 2.25).expect("point window");
    assert_eq!((win.x0, win.x1, win.y0, win.y1), (3, 6, 3, 6));
}
#[test]
fn covering_single_cell_exact() {
    let win = small().covering_window(2.0, 2.0, 2.5, 2.5).expect("cell");
    assert_eq!((win.x0, win.x1, win.y0, win.y1), (3, 6, 3, 6));
}
#[test]
fn covering_exact_grid_bounds() {
    let win = small().covering_window(0.0, 0.0, 10.0, 8.0).expect("full");
    assert_eq!((win.x0, win.x1, win.y0, win.y1), (0, 20, 0, 16));
}
#[test]
fn covering_inverted_bbox_is_none() {
    assert!(small().covering_window(5.0, 2.0, 3.0, 3.0).is_none());
    assert!(small().covering_window(2.0, 5.0, 3.0, 3.0).is_none());
    assert!(small().covering_window(3.0, 3.0, 2.9, 2.9).is_none());
}
#[test]
fn covering_nonfinite_bbox_is_none() {
    assert!(small().covering_window(f64::NAN, 0.0, 1.0, 1.0).is_none());
    assert!(small().covering_window(0.0, 0.0, f64::INFINITY, 1.0).is_none());
    assert!(small().covering_window(0.0, f64::NEG_INFINITY, 1.0, 1.0).is_none());
}
#[test]
fn covering_touches_edge_and_zero_size_grid() {
    // Bbox starting exactly at a grid line still pads one cell west/south.
    let win = small().covering_window(5.0, 4.0, 6.0, 5.0).unwrap();
    assert_eq!((win.x0, win.y0), (9, 7));
    let empty = Grid { nx: 0, ny: 0, lon_min: 0.0, lat_min: 0.0, dx: 0.5, dy: 0.5 };
    assert!(empty.covering_window(0.0, 0.0, 1.0, 1.0).is_none());
}

// --- bilinear ---
#[test]
fn bilinear_too_small_is_nan() {
    let d = vec![1.0f32, 2.0, 3.0, 4.0];
    assert!(bilinear(&d, 1, 2, &w2(), 0.5, 0.5).is_nan());
    assert!(bilinear(&d, 2, 1, &w2(), 0.5, 0.5).is_nan());
    assert!(bilinear(&d, 0, 0, &w2(), 0.0, 0.0).is_nan());
    assert!(bilinear(&d, 1, 1, &w2(), 0.0, 0.0).is_nan());
}
#[test]
fn bilinear_negative_local_is_nan() {
    let d = vec![1.0f32, 2.0, 3.0, 4.0];
    let win = Window { y0: 2, y1: 4, x0: 3, x1: 5 };
    assert!(bilinear(&d, 2, 2, &win, 1.9, 4.0).is_nan());
    assert!(bilinear(&d, 2, 2, &win, 3.0, 2.9).is_nan());
    assert!(bilinear(&d, 2, 2, &win, -1.0, -1.0).is_nan());
}
#[test]
fn bilinear_fractional_weights_exact() {
    let d = vec![0.0f32, 10.0, 20.0, 30.0];
    assert_eq!(bilinear(&d, 2, 2, &w2(), 0.0, 0.25), 2.5);
    assert_eq!(bilinear(&d, 2, 2, &w2(), 0.5, 0.0), 10.0);
    assert_eq!(bilinear(&d, 2, 2, &w2(), 0.25, 0.5), 10.0);
    assert_eq!(bilinear(&d, 2, 2, &w2(), 0.75, 0.75), 22.5);
}
#[test]
fn bilinear_infinite_corners_fall_back_like_nan() {
    let d = vec![f32::INFINITY, 2.0, 3.0, 4.0];
    assert_eq!(bilinear(&d, 2, 2, &w2(), 0.75, 0.75), 4.0);
    assert_eq!(bilinear(&d, 2, 2, &w2(), 0.25, 0.25), 2.0);
    let neg = vec![f32::NEG_INFINITY, 2.0, 3.0, 4.0];
    assert_eq!(bilinear(&neg, 2, 2, &w2(), 0.75, 0.75), 4.0);
    assert!(bilinear(&[f32::INFINITY; 4], 2, 2, &w2(), 0.5, 0.5).is_nan());
    assert!(bilinear(&[f32::INFINITY, f32::NAN, f32::NAN, f32::NAN], 2, 2, &w2(), 0.5, 0.5).is_nan());
}
#[test]
fn bilinear_nan_coordinate_is_nan() {
    let d = vec![1.0f32, 2.0, 3.0, 4.0];
    assert!(bilinear(&d, 2, 2, &w2(), f64::NAN, 0.5).is_nan());
    assert!(bilinear(&d, 2, 2, &w2(), 0.5, f64::NAN).is_nan());
    let with_nan = vec![f32::NAN, 2.0, 3.0, 4.0];
    assert!(bilinear(&with_nan, 2, 2, &w2(), f64::NAN, 0.5).is_nan());
}
#[test]
fn bilinear_larger_grid_fractional() {
    // 3x3 ramp 0..8, window at origin; centre of lower-left cell.
    let d: Vec<f32> = (0..9).map(|i| i as f32).collect();
    let win = Window { y0: 0, y1: 3, x0: 0, x1: 3 };
    assert_eq!(bilinear(&d, 3, 3, &win, 0.5, 0.5), 2.0);
    assert_eq!(bilinear(&d, 3, 3, &win, 1.5, 1.5), 6.0);
}

// --- resample ---
#[test]
fn resample_output_sizes() {
    let grid = Grid { nx: 4, ny: 4, lon_min: 0.0, lat_min: 0.0, dx: 1.0, dy: 1.0 };
    let win = grid.covering_window(1.0, 1.0, 3.0, 3.0).unwrap();
    let (sx, sy) = (win.x1 - win.x0, win.y1 - win.y0);
    for (ow, oh) in [(1usize, 1usize), (3, 5), (7, 2), (1, 9)] {
        let data: Vec<f32> = (0..sx * sy).map(|i| i as f32).collect();
        let out = resample(data, sx, sy, &win, &grid, 1.0, 1.0, 3.0, 3.0, ow, oh);
        assert_eq!(out.len(), ow * oh);
    }
}
#[test]
fn resample_nan_propagation() {
    let grid = Grid { nx: 4, ny: 4, lon_min: 0.0, lat_min: 0.0, dx: 1.0, dy: 1.0 };
    let win = grid.covering_window(1.0, 1.0, 3.0, 3.0).unwrap();
    let (sx, sy) = (win.x1 - win.x0, win.y1 - win.y0);
    let out = resample(vec![f32::NAN; sx * sy], sx, sy, &win, &grid, 1.0, 1.0, 3.0, 3.0, 3, 3);
    assert!(out.iter().all(|v| v.is_nan()));
}
#[test]
fn resample_high_latitudes_finite_and_ordered() {
    let grid = Grid { nx: 4, ny: 20, lon_min: 0.0, lat_min: 70.0, dx: 1.0, dy: 1.0 };
    let win = grid.covering_window(1.0, 80.0, 3.0, 85.0).unwrap();
    let (sx, sy) = (win.x1 - win.x0, win.y1 - win.y0);
    let data: Vec<f32> = (0..sx * sy).map(|i| i as f32).collect();
    let out = resample(data, sx, sy, &win, &grid, 1.0, 80.0, 3.0, 85.0, 2, 2);
    assert!(out.iter().all(|v| v.is_finite()), "{out:?}");
    let grid_s = Grid { nx: 4, ny: 20, lon_min: 0.0, lat_min: -90.0, dx: 1.0, dy: 1.0 };
    let win_s = grid_s.covering_window(1.0, -85.0, 3.0, -80.0).unwrap();
    let (sx, sy) = (win_s.x1 - win_s.x0, win_s.y1 - win_s.y0);
    let data: Vec<f32> = (0..sx * sy).map(|i| i as f32).collect();
    let out_s = resample(data, sx, sy, &win_s, &grid_s, 1.0, -85.0, 3.0, -80.0, 2, 2);
    assert!(out_s.iter().all(|v| v.is_finite()), "{out_s:?}");
}
#[test]
fn resample_constant_field_stays_constant() {
    let grid = Grid { nx: 4, ny: 4, lon_min: 0.0, lat_min: 0.0, dx: 1.0, dy: 1.0 };
    let win = grid.covering_window(1.0, 1.0, 3.0, 3.0).unwrap();
    let (sx, sy) = (win.x1 - win.x0, win.y1 - win.y0);
    let out = resample(vec![7.5f32; sx * sy], sx, sy, &win, &grid, 1.0, 1.0, 3.0, 3.0, 4, 3);
    assert!(out.iter().all(|v| *v == 7.5));
}

// --- RangeBackend with pre-populated cache ---
fn backend_with_blocks(size: usize, blocks: &[(u64, Vec<u8>)]) -> RangeBackend {
    let b = RangeBackend::new("https://example.invalid/x.om".to_string(), size);
    for (k, v) in blocks {
        b.cache.lock().unwrap().insert(*k, v.clone());
    }
    b
}
#[test]
fn range_backend_cached_slices() {
    let blk0: Vec<u8> = (0..BLOCK as usize).map(|i| (i % 251) as u8).collect();
    let b = backend_with_blocks(200_000, &[(0, blk0.clone())]);
    assert_eq!(b.count(), 200_000);
    assert_eq!(b.get_bytes(0, 4).unwrap(), blk0[0..4].to_vec());
    assert_eq!(b.get_bytes(100, 50).unwrap(), blk0[100..150].to_vec());
    assert_eq!(b.get_bytes(BLOCK - 2, 2).unwrap(), blk0[(BLOCK - 2) as usize..].to_vec());
}
#[test]
fn range_backend_cached_spanning_blocks() {
    let blk0 = vec![1u8; BLOCK as usize];
    let blk1 = vec![2u8; BLOCK as usize];
    let b = backend_with_blocks(200_000, &[(0, blk0), (1, blk1)]);
    let out = b.get_bytes(BLOCK - 3, 6).unwrap();
    assert_eq!(out, vec![1, 1, 1, 2, 2, 2]);
    // Exactly at the block boundary.
    assert_eq!(b.get_bytes(BLOCK, 3).unwrap(), vec![2, 2, 2]);
    let all = b.get_bytes(0, 2 * BLOCK).unwrap();
    assert_eq!(all.len(), 2 * BLOCK as usize);
    assert!(all[..BLOCK as usize].iter().all(|v| *v == 1));
}
#[test]
fn range_backend_cached_partial_last_block() {
    let size = (BLOCK + 10) as usize;
    let b = backend_with_blocks(size, &[(0, vec![3u8; BLOCK as usize]), (1, vec![9u8; 10])]);
    assert_eq!(b.get_bytes(BLOCK - 1, 11).unwrap(), {
        let mut v = vec![3u8]; v.extend([9u8; 10]); v
    });
    assert_eq!(b.get_bytes(BLOCK, 10).unwrap(), vec![9u8; 10]);
    assert_eq!(b.get_bytes(size as u64 - 1, 1).unwrap(), vec![9u8]);
}
#[test]
fn range_backend_missing_block_errors_not_panics() {
    let b = backend_with_blocks(200_000, &[(0, vec![1u8; BLOCK as usize])]);
    // Block 1 is neither cached nor fetchable on host (no JNI bridge).
    assert!(b.get_bytes(BLOCK, 4).is_err());
    assert!(b.get_bytes(BLOCK - 1, 4).is_err());
}

// --- read_subgrid_generic via synthetic OM ---
fn synthetic_reader() -> OmReader<MemBackend> {
    let data = build_om(&[
        array_var("temperature_2m", vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0], [2, 3]),
        array_var("wind_u_component_10m", vec![3.0, 0.0, 6.0, 0.0], [2, 2]),
        array_var("wind_v_component_10m", vec![4.0, 0.0, 8.0, 0.0], [2, 2]),
        TestVar { name: "scale", spec: VarSpec::ScalarF32(1.0) },
    ]);
    OmReader::new(MemBackend::new(data)).unwrap()
}
#[test]
fn read_subgrid_direct_and_windowed() {
    let r = synthetic_reader();
    let win = Window { y0: 0, y1: 2, x0: 0, x1: 3 };
    assert_eq!(read_subgrid_generic(&r, "temperature_2m", &win).unwrap(), vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0]);
    let sub = Window { y0: 1, y1: 2, x0: 1, x1: 3 };
    assert_eq!(read_subgrid_generic(&r, "temperature_2m", &sub).unwrap(), vec![5.0, 6.0]);
}
#[test]
fn read_subgrid_wind_speed_magnitude() {
    let r = synthetic_reader();
    let win = Window { y0: 0, y1: 2, x0: 0, x1: 2 };
    assert_eq!(read_subgrid_generic(&r, "wind_speed_10m", &win).unwrap(), vec![5.0, 0.0, 10.0, 0.0]);
}
#[test]
fn read_subgrid_wind_speed_present_directly_wins() {
    let data = build_om(&[array_var("wind_speed_10m", vec![9.0, 9.0], [1, 2])]);
    let r = OmReader::new(MemBackend::new(data)).unwrap();
    let win = Window { y0: 0, y1: 1, x0: 0, x1: 2 };
    assert_eq!(read_subgrid_generic(&r, "wind_speed_10m", &win).unwrap(), vec![9.0, 9.0]);
}
#[test]
fn read_subgrid_errors() {
    let r = synthetic_reader();
    let win = Window { y0: 0, y1: 2, x0: 0, x1: 2 };
    let e = read_subgrid_generic(&r, "nope", &win).unwrap_err();
    assert!(e.contains("nope") && e.contains("not found"), "{e}");
    let e = read_subgrid_generic(&r, "scale", &win).unwrap_err();
    assert!(e.contains("not an array"), "{e}");
    // wind_speed with neither component present in this file shape:
    let data = build_om(&[array_var("temperature_2m", vec![1.0], [1, 1])]);
    let r2 = OmReader::new(MemBackend::new(data)).unwrap();
    let w1 = Window { y0: 0, y1: 1, x0: 0, x1: 1 };
    let e = read_subgrid_generic(&r2, "wind_speed_10m", &w1).unwrap_err();
    assert!(e.contains("wind_u_component_10m"), "{e}");
}

// --- decode_region_url_inner / cached_backend early errors ---
#[test]
fn decode_empty_output_is_err_before_network() {
    let g = small();
    assert_eq!(decode_region_url_inner("https://example.invalid/a", "t", &g, 1.0, 1.0, 2.0, 2.0, 0, 4).unwrap_err(), "empty output size");
    assert_eq!(decode_region_url_inner("https://example.invalid/a", "t", &g, 1.0, 1.0, 2.0, 2.0, 4, 0).unwrap_err(), "empty output size");
    // Empty wins even when the bbox also does not intersect.
    assert_eq!(decode_region_url_inner("https://example.invalid/a", "t", &g, 50.0, 50.0, 60.0, 60.0, 0, 0).unwrap_err(), "empty output size");
}
#[test]
fn decode_non_intersecting_bbox_is_err_before_network() {
    let g = small();
    assert_eq!(decode_region_url_inner("https://example.invalid/a", "t", &g, 50.0, 50.0, 60.0, 60.0, 4, 4).unwrap_err(), "bbox does not intersect grid");
    assert!(decode_region_url_inner("https://example.invalid/a", "t", &g, 5.0, 2.0, 3.0, 3.0, 4, 4).is_err());
}
#[test]
fn decode_valid_bbox_fails_at_backend_without_network() {
    let g = small();
    let e = decode_region_url_inner("https://example.invalid/unique-decode-1.om", "temperature_2m", &g, 1.0, 1.0, 2.0, 2.0, 4, 4).unwrap_err();
    assert!(!e.is_empty());
}
#[test]
fn cached_backend_unique_url_errors() {
    assert!(cached_backend("https://example.invalid/unique-cb-1.om").is_err());
    assert!(cached_backend("https://example.invalid/unique-cb-1.om").is_err());
}
#[test]
fn wind_speed_components_extra() {
    assert_eq!(wind_speed_components("wind_speed_10m"), Some(("wind_u_component_10m", "wind_v_component_10m")));
    assert_eq!(wind_speed_components("wind_speed"), None);
    assert_eq!(wind_speed_components("WIND_SPEED_10M"), None);
}

#[test]
fn covering_invalid_grid_spacing_is_none() {
    let g = Grid { dx: 0.0, ..small() };
    assert!(g.covering_window(1.0, 1.0, 2.0, 2.0).is_none());
    let g = Grid { dy: -0.5, ..small() };
    assert!(g.covering_window(1.0, 1.0, 2.0, 2.0).is_none());
    let g = Grid { dx: f64::NAN, ..small() };
    assert!(g.covering_window(1.0, 1.0, 2.0, 2.0).is_none());
}
#[test]
fn bilinear_fallback_nearest_each_corner() {
    // Only p00 finite, sample closest to p00.
    assert_eq!(bilinear(&[1.0, f32::NAN, f32::NAN, f32::NAN], 2, 2, &w2(), 0.1, 0.1), 1.0);
    // Only p10 finite, sample closest to p10 (fy>=.5, fx<.5).
    assert_eq!(bilinear(&[f32::NAN, f32::NAN, 3.0, f32::NAN], 2, 2, &w2(), 0.9, 0.1), 3.0);
    // Only p01 finite, closest to p01.
    assert_eq!(bilinear(&[f32::NAN, 2.0, f32::NAN, f32::NAN], 2, 2, &w2(), 0.1, 0.9), 2.0);
    // Nearest is NaN but a later corner is finite: scan finds p10.
    assert_eq!(bilinear(&[f32::NAN, f32::NAN, 3.0, f32::NAN], 2, 2, &w2(), 0.1, 0.1), 3.0);
}
#[test]
fn read_subgrid_wind_missing_v_and_read_failure() {
    let data = build_om(&[array_var("wind_u_component_10m", vec![1.0], [1, 1])]);
    let r = OmReader::new(MemBackend::new(data)).unwrap();
    let win = Window { y0: 0, y1: 1, x0: 0, x1: 1 };
    let e = read_subgrid_generic(&r, "wind_speed_10m", &win).unwrap_err();
    assert!(e.contains("wind_v_component_10m"), "{e}");
    let r = synthetic_reader();
    let big = Window { y0: 0, y1: 9, x0: 0, x1: 3 };
    let e = read_subgrid_generic(&r, "temperature_2m", &big).unwrap_err();
    assert!(e.contains("read temperature_2m failed"), "{e}");
}
