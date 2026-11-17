//! Cost-model and projection tests (sibling of `geometry.rs`). Expected
//! values are computed from first principles (haversine radius, Web-Mercator
//! scale at the equator, km/h -> 10 ms units), not from the code under test.
use super::*;
use crate::graph::graph_cov_tests::{assemble, Spec};
use crate::graph::{BICYCLE, DRIVING, INVALID_EDGE, MAX_DRIVING_KMH, PUBLIC_TRANSIT, WALK};
use std::collections::BTreeMap;

fn g() -> crate::graph::Graph {
    assemble(&Spec {
        nodes: vec![(0, 0), (0, 100_000)],
        edges: vec![(0, 1, 5, 50, 1_113_900), (1, 0, 5, 50, 1_113_900)],
        escaped: vec![],
        names: BTreeMap::new(),
        pool: vec![],
        geom: BTreeMap::new(),
        lanes: None,
        elevation: None,
    })
}

#[test]
fn haversine_matches_the_known_earth_scale() {
    assert_eq!(accurate_dist_mm(0, 0, 0, 0), 0);
    // One degree of latitude is pi/180 * R = 111,195 m for R = 6,371,000.8 m.
    let d = accurate_dist_mm(0, 0, 10_000_000, 0);
    assert!((111_190_000..111_200_000).contains(&d), "1 deg lat = {d} mm");
    // Antipodal: pi * R ~ 20,015 km in mm overflows u32, so it saturates
    // (the function's documented contract).
    assert_eq!(accurate_dist_mm(0, 0, 0, 1_800_000_000), u32::MAX);
    assert_eq!(accurate_dist_mm(10, 20, -30, 40), accurate_dist_mm(-30, 40, 10, 20), "symmetric");
}

#[test]
fn fast_distance_is_symmetric_and_equator_scaled() {
    let g = g();
    assert_eq!(fast_dist_mm(&g, 5, 5, 5, 5), 0);
    // Octagonal approx of 0.01 deg at the equator (~1113.9 m exact): within 5%.
    let d = fast_dist_mm(&g, 0, 0, 0, 100_000);
    assert!((1_050_000..1_120_000).contains(&d), "0.01 deg lon = {d} mm");
    assert_eq!(d, fast_dist_mm(&g, 0, 100_000, 0, 0), "symmetric");
    // Longitude metres shrink with cos(lat): at 60 deg the same span is ~half.
    let d60 = fast_dist_mm(&g, 600_000_000, 0, 600_000_000, 100_000);
    assert!(d60 < d / 2 + 20_000 && d60 > d / 2 - 60_000, "60 deg: {d60} vs equator {d}");
    // A diagonal reads between its legs and their sum.
    let diag = fast_dist_mm(&g, 0, 0, 100_000, 100_000);
    assert!(diag > d && diag < 2 * d, "diagonal {diag}");
}

#[test]
fn bearings_and_maneuver_boundaries() {
    assert!((get_bearing(0, 0, 10_000_000, 0)).abs() < 0.01, "due north is 0");
    assert!((get_bearing(0, 0, 0, 10_000_000) - 90.0).abs() < 0.01, "due east is 90");
    assert!((get_bearing(0, 0, -10_000_000, 0).abs() - 180.0).abs() < 0.01, "due south is 180");
    assert!((get_bearing(0, 0, 0, -10_000_000) + 90.0).abs() < 0.01, "due west is -90");
    // Maneuver bands, probed at their edges (codes per get_maneuver docs).
    for (diff, want) in [
        (-180.0, 3), (-156.0, 3), (-155.0, 2), (-101.0, 2), (-100.0, 4), (-46.0, 4),
        (-45.0, 1), (-11.0, 1), (-10.0, 9), (0.0, 9), (9.0, 9), (10.0, 5), (44.0, 5),
        (45.0, 8), (99.0, 8), (100.0, 6), (155.0, 6), (156.0, 7), (180.0, 7),
    ] {
        assert_eq!(get_maneuver(0.0, diff), want, "diff {diff}");
    }
    // Wrap: 170 -> -170 is a +20 turn, not a -340 reversal.
    assert_eq!(get_maneuver(170.0, -170.0), 5);
    assert_eq!(get_maneuver(-170.0, 170.0), 1);
}

#[test]
fn projection_clamps_to_the_segment() {
    let g = g();
    let mid = get_projection(&g, 0, 50_000, 0, 0, 0, 100_000);
    assert_eq!((mid.lat_e7, mid.lon_e7), (0, 50_000));
    assert_eq!(mid.dist_mm, 0, "a point on the segment projects to itself");
    let beyond = get_projection(&g, 0, 300_000, 0, 0, 0, 100_000);
    assert_eq!(beyond.lon_e7, 100_000, "clamped to the far end");
    let before = get_projection(&g, 0, -50_000, 0, 0, 0, 100_000);
    assert_eq!(before.lon_e7, 0, "clamped to the near end");
    let deg = get_projection(&g, 7, 9, 3, 4, 3, 4);
    assert_eq!((deg.lat_e7, deg.lon_e7), (3, 4), "a degenerate segment is its point");
    let side = get_projection(&g, 50_000, 50_000, 0, 0, 0, 100_000);
    assert_eq!((side.lat_e7, side.lon_e7), (0, 50_000));
    assert!(side.dist_mm > 500_000, "perpendicular distance kept: {}", side.dist_mm);
}

#[test]
fn edge_time_uses_limits_traffic_and_mode_tables() {
    let g = g();
    let none = TrafficSpeeds::default();
    // 36 km/h = 10 m/s: 10,000 mm takes exactly 1 s = 100 (10 ms units).
    assert_eq!(get_edge_time_10ms(&g, &none, INVALID_EDGE, 10_000, 5, 36, DRIVING), 100);
    assert_eq!(get_edge_time_10ms(&g, &none, INVALID_EDGE, 20_000, 5, 36, DRIVING), 200);
    // Live traffic replaces the posted limit; INVALID_EDGE ignores traffic.
    let mut traffic = TrafficSpeeds::default();
    traffic.insert(7, 72); // 20 m/s
    assert_eq!(get_edge_time_10ms(&g, &traffic, 7, 10_000, 5, 36, DRIVING), 50);
    assert_eq!(get_edge_time_10ms(&g, &traffic, INVALID_EDGE, 10_000, 5, 36, DRIVING), 100);
    assert_eq!(get_edge_time_10ms(&g, &traffic, 8, 10_000, 5, 36, DRIVING), 100, "other edges unaffected");
    // Speeds are clamped to MAX_DRIVING_KMH (130): 200 km/h reads as 130.
    let fast = get_edge_time_10ms(&g, &none, INVALID_EDGE, 130_000, 5, 200, DRIVING);
    let at_max = get_edge_time_10ms(&g, &none, INVALID_EDGE, 130_000, 5, MAX_DRIVING_KMH as u8, DRIVING);
    assert_eq!(fast, at_max);
    assert_eq!(at_max, 360, "130 m at 130 km/h is 3.6 s");
    // A zero limit falls back to the per-class table (finite and positive).
    let t = get_edge_time_10ms(&g, &none, INVALID_EDGE, 100_000, 5, 0, DRIVING);
    assert!(t > 0 && t < 10_000, "class-table time {t}");
    // Walking the same road is slower than driving it; transit walks too.
    let w = get_edge_time_10ms(&g, &none, INVALID_EDGE, 100_000, 5, 50, WALK);
    let p = get_edge_time_10ms(&g, &none, INVALID_EDGE, 100_000, 5, 50, PUBLIC_TRANSIT);
    assert!(w > t && p == w, "walk {w} drive {t}");
    // Heuristic: zero at the goal, and never above the fastest legal edge time
    // (the A* consistency the radix heap relies on).
    assert_eq!(heuristic_time_10ms(&g, 3, 4, 3, 4, DRIVING), 0);
    let h = heuristic_time_10ms(&g, 0, 0, 0, 100_000, DRIVING);
    let fastest = get_edge_time_10ms(&g, &none, INVALID_EDGE, 1_113_900, 5, MAX_DRIVING_KMH as u8, DRIVING);
    assert!(h > 0 && h <= fastest + 2, "heuristic {h} <= fastest {fastest}");
    let _ = BICYCLE;
}

#[test]
fn the_edge_id_hasher_is_deterministic_and_spreads() {
    let mut a = TrafficSpeeds::default();
    a.insert(42, 90);
    a.insert(u64::MAX, 30);
    assert_eq!(a.get(&42), Some(&90));
    assert_eq!(a.get(&u64::MAX), Some(&30));
    assert_eq!(a.get(&43), None);
    use std::hash::Hasher;
    let h = |n: u64| { let mut x = EdgeIdHasher::default(); x.write_u64(n); x.finish() };
    assert_eq!(h(7), h(7));
    assert_ne!(h(7), h(8));
    assert_ne!(h(0), h(1 << 32), "high bits spread too");
}
