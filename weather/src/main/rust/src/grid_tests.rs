//! Tests for the grid geometry and resampling logic in the crate root.

use super::*;

fn small_grid() -> Grid {
    // 20 x 16 cells of 0.5 degrees, origin at (0, 0): lon 0..10, lat 0..8.
    Grid { nx: 20, ny: 16, lon_min: 0.0, lat_min: 0.0, dx: 0.5, dy: 0.5 }
}

#[test]
fn covering_window_pads_by_one_cell() {
    let g = small_grid();
    let win = g.covering_window(2.0, 2.0, 3.0, 3.0).expect("window");
    // Exact cells would be x 4..6, y 4..6; the -1/+1 pad widens to 3..7.
    assert_eq!((win.x0, win.x1, win.y0, win.y1), (3, 7, 3, 7));
}

#[test]
fn covering_window_clamps_to_grid_edges() {
    let g = small_grid();
    let win = g.covering_window(-5.0, -5.0, 1.0, 1.0).expect("window");
    assert_eq!((win.x0, win.y0), (0, 0));
    assert_eq!((win.x1, win.y1), (3, 3));
    let win = g.covering_window(9.0, 7.0, 50.0, 50.0).expect("window");
    assert_eq!((win.x1, win.y1), (20, 16));
}

#[test]
fn covering_window_outside_grid_is_none() {
    let g = small_grid();
    assert!(g.covering_window(20.0, 2.0, 30.0, 3.0).is_none());
    assert!(g.covering_window(2.0, 20.0, 3.0, 30.0).is_none());
    assert!(g.covering_window(-30.0, -30.0, -20.0, -20.0).is_none());
}

#[test]
fn bilinear_at_corners_returns_corner_values() {
    let data = vec![1.0f32, 2.0, 3.0, 4.0];
    let win = Window { y0: 0, y1: 2, x0: 0, x1: 2 };
    assert_eq!(bilinear(&data, 2, 2, &win, 0.0, 0.0), 1.0);
    // Points exactly on the east/north edge sample the edge cell and return
    // the corner values, not no-data.
    assert_eq!(bilinear(&data, 2, 2, &win, 0.0, 1.0), 2.0);
    assert_eq!(bilinear(&data, 2, 2, &win, 1.0, 0.0), 3.0);
    assert_eq!(bilinear(&data, 2, 2, &win, 1.0, 1.0), 4.0);
}

#[test]
fn bilinear_midpoint_is_the_average() {
    let data = vec![1.0f32, 2.0, 3.0, 4.0];
    let win = Window { y0: 0, y1: 2, x0: 0, x1: 2 };
    assert_eq!(bilinear(&data, 2, 2, &win, 0.5, 0.5), 2.5);
}

#[test]
fn bilinear_outside_window_is_nan() {
    let data = vec![1.0f32, 2.0, 3.0, 4.0];
    let win = Window { y0: 1, y1: 3, x0: 1, x1: 3 };
    // gy below the window's first row.
    assert!(bilinear(&data, 2, 2, &win, 0.5, 1.5).is_nan());
    // A point on the window's last grid line clamps to the edge cell.
    assert_eq!(bilinear(&data, 2, 2, &win, 2.0, 1.5), 3.5);
}

#[test]
fn bilinear_nan_corner_falls_back_to_nearest() {
    let data = vec![f32::NAN, 2.0, 3.0, 4.0];
    let win = Window { y0: 0, y1: 2, x0: 0, x1: 2 };
    // Closest to p11 -> 4.0.
    assert_eq!(bilinear(&data, 2, 2, &win, 0.75, 0.75), 4.0);
    // Closest to the NaN corner -> first finite in scan order is p01 = 2.0.
    assert_eq!(bilinear(&data, 2, 2, &win, 0.25, 0.25), 2.0);
}

#[test]
fn bilinear_all_nan_is_nan() {
    let data = vec![f32::NAN; 4];
    let win = Window { y0: 0, y1: 2, x0: 0, x1: 2 };
    assert!(bilinear(&data, 2, 2, &win, 0.5, 0.5).is_nan());
}

#[test]
fn bilinear_respects_window_offset() {
    // 3x3 data; window starts at (1,1), so grid coords shift by one.
    let data: Vec<f32> = (0..9).map(|i| i as f32).collect();
    let win = Window { y0: 1, y1: 3, x0: 1, x1: 3 };
    // Grid point (1,1) is data[0] of the sub-window's local frame corner:
    // local (0,0) -> p00 = data[0 * 3 + 0] only when sub_nx is the window width.
    // Here sub_nx/sub_ny are the window dims (2), but data is the full 3x3 the
    // caller sliced — bilinear indexes data as sub_nx-wide rows.
    let v = bilinear(&data, 2, 2, &win, 1.0, 1.0);
    assert_eq!(v, 0.0);
}

#[test]
fn wind_speed_components_mapping() {
    assert_eq!(
        wind_speed_components("wind_speed_10m"),
        Some(("wind_u_component_10m", "wind_v_component_10m"))
    );
    assert_eq!(wind_speed_components("temperature_2m"), None);
    assert_eq!(wind_speed_components(""), None);
}

#[test]
fn range_backend_count_and_empty_read() {
    let backend = RangeBackend::new("https://example.invalid/x.om".to_string(), 0);
    assert_eq!(backend.count(), 0);
    // Zero-length reads never touch the network.
    assert_eq!(backend.get_bytes(0, 0).unwrap().len(), 0);
    assert_eq!(backend.get_bytes(123, 0).unwrap().len(), 0);
}

#[test]
fn range_backend_read_past_empty_file_is_cache_miss() {
    let backend = RangeBackend::new("https://example.invalid/x.om".to_string(), 0);
    // Block 0 starts at 0 >= size 0, so ensure_block fetches nothing; the
    // subsequent cache lookup must fail cleanly rather than panic.
    let err = backend.get_bytes(0, 10).expect_err("must fail");
    assert_eq!(err, "cache miss");
}

#[test]
fn resample_corners_track_grid() {
    // Ramp data over a 4x4 grid; resample an interior bbox so every output
    // pixel has a full interpolation cell.
    let grid = Grid { nx: 4, ny: 4, lon_min: 0.0, lat_min: 0.0, dx: 1.0, dy: 1.0 };
    let win = grid.covering_window(1.0, 1.0, 3.0, 3.0).expect("window");
    let sub_nx = win.x1 - win.x0;
    let sub_ny = win.y1 - win.y0;
    let data: Vec<f32> = (0..16).map(|i| i as f32).collect();
    let out = resample(data, sub_nx, sub_ny, &win, &grid, 1.0, 1.0, 3.0, 3.0, 2, 2);
    assert_eq!(out.len(), 4);
    assert!(out.iter().all(|v| v.is_finite()));
    // Data rises eastward and northward, and output row 0 is the north row.
    assert!(out[1] > out[0]);
    assert!(out[0] > out[2]);
    assert!(out[3] > out[2]);
}
