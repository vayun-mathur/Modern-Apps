//! Distance/time/bearing math and road-permission rules.
//!
//! Direct port of the `--- GEOMETRY ---`, `--- ROAD PERMISSIONS ---` and the
//! transit-scheduling helpers from `native-lib.cpp`. Integer arithmetic and
//! casts are reproduced exactly to keep routing output bit-identical.

use std::collections::HashMap;
use std::hash::{BuildHasherDefault, Hasher};

use crate::graph::{
    Graph, DEG_TO_RAD, DRIVING, INVALID_EDGE, LIVING_STREET, MAX_DRIVING_KMH, MOTORWAY,
    REVERSE_GEOMETRY_FLAG, STEPS,
};

/// Live traffic snapshot: global edge id -> speed (km/h). 0 means "unknown".
/// Probed once per relaxed edge while driving, so SipHash is replaced by a
/// single multiply.
pub type TrafficSpeeds = HashMap<u64, u8, BuildHasherDefault<EdgeIdHasher>>;

/// Fibonacci hash for `u64` edge ids. The multiply spreads entropy into the high
/// bits, which hashbrown uses for its control bytes.
#[derive(Default)]
pub struct EdgeIdHasher(u64);

impl Hasher for EdgeIdHasher {
    #[inline]
    fn write(&mut self, bytes: &[u8]) {
        for &b in bytes {
            self.0 = (self.0.rotate_left(8) ^ u64::from(b)).wrapping_mul(0x9E37_79B9_7F4A_7C15);
        }
    }

    #[inline]
    fn write_u64(&mut self, n: u64) {
        self.0 = (self.0 ^ n).wrapping_mul(0x9E37_79B9_7F4A_7C15);
    }

    #[inline]
    fn finish(&self) -> u64 {
        self.0
    }
}

/// Bits of an `Edge::type_` byte that carry the OSM road class. The high bits
/// are flags — [`REVERSE_GEOMETRY_FLAG`] today — so every consumer that treats
/// `type_` as a road class MUST mask them off first.
///
/// This mask used to be `0x7F`, which stripped only bit 7 and let
/// `REVERSE_GEOMETRY_FLAG` through into the range checks below. A flagged edge
/// then landed at 65..79, outside both permitted ranges, so it was rejected for
/// every mode — silently deleting it from snapping (`routing.rs`
/// `find_nearest_edge`), from A* relaxation (`perform_search_loop`) and from
/// junction lane inference. Since the generator sets the flag on one edge of
/// every bidirectional collapsed chain, that would have removed half the road
/// network. `get_edge_time_10ms` below was already immune only by accident: its
/// `& 0xF` discards bit 6 as well.
pub const ROAD_TYPE_MASK: u8 = !(REVERSE_GEOMETRY_FLAG | 0x80);

#[inline]
pub fn is_mode_allowed(road_type: u8, mode: i32) -> bool {
    let ty = road_type & ROAD_TYPE_MASK;
    if mode == DRIVING {
        return (MOTORWAY..=LIVING_STREET).contains(&ty);
    }
    // PUBLIC_TRANSIT reaches the road graph only for a journey's walking legs
    // (the timetable lives in the separate `.transit` index), so it permits
    // exactly what WALK does.
    (MOTORWAY..=STEPS).contains(&ty)
}

/// Fast equirectangular distance in millimetres (octagonal `max/min` approx),
/// using the precomputed cos-scaled longitude table.
#[inline(always)]
pub fn fast_dist_mm(g: &Graph, lat1_e7: i32, lon1_e7: i32, lat2_e7: i32, lon2_e7: i32) -> u32 {
    let dlat = (lat1_e7 as i64 - lat2_e7 as i64).abs();
    let dlon = (lon1_e7 as i64 - lon2_e7 as i64).abs();

    // 1 unit (1e-7 deg) latitude is ~11.1139 mm.
    let dy_mm = (dlat * 111_139) / 10_000;

    let mid = (lat1_e7.wrapping_add(lat2_e7)) / 2;
    let scale_idx = ((mid >> 19) + 2048) as u32;
    let scale = g.lon_to_mm_scale[(scale_idx & 4095) as usize] as i64;
    let dx_mm = (dlon * scale) >> 10;

    let (max_v, min_v): (u64, u64) = if dx_mm > dy_mm {
        (dx_mm as u64, dy_mm as u64)
    } else {
        (dy_mm as u64, dx_mm as u64)
    };
    (max_v - (max_v >> 5) + (min_v >> 1) - (min_v >> 3)) as u32
}

/// Haversine distance in millimetres, saturating at u32::MAX.
#[inline]
pub fn accurate_dist_mm(lat1_e7: i32, lon1_e7: i32, lat2_e7: i32, lon2_e7: i32) -> u32 {
    const R: f64 = 6_371_000_800.0;
    let phi1 = (lat1_e7 as f64 * 1e-7) * DEG_TO_RAD;
    let phi2 = (lat2_e7 as f64 * 1e-7) * DEG_TO_RAD;
    let delta_phi = (lat2_e7 - lat1_e7) as f64 * 1e-7 * DEG_TO_RAD;
    let delta_lambda = (lon2_e7 - lon1_e7) as f64 * 1e-7 * DEG_TO_RAD;
    let s_dphi = (delta_phi / 2.0).sin();
    let s_dlamb = (delta_lambda / 2.0).sin();
    let a = s_dphi * s_dphi + phi1.cos() * phi2.cos() * s_dlamb * s_dlamb;
    let dist = R * 2.0 * a.sqrt().atan2((1.0 - a).sqrt());
    if dist >= u32::MAX as f64 {
        return u32::MAX;
    }
    if dist < 0.0 {
        return 0;
    }
    dist as u32
}

/// Time (10 ms units) to traverse a non-transit edge, applying live traffic for
/// driving.
#[inline]
pub fn get_edge_time_10ms(
    g: &Graph,
    traffic: &TrafficSpeeds,
    edge_id: u64,
    dist_mm: u32,
    type_: u8,
    limit: u8,
    mode: i32,
) -> u32 {
    if mode == DRIVING {
        let traffic_speed = if edge_id != INVALID_EDGE {
            *traffic.get(&edge_id).unwrap_or(&0)
        } else {
            0
        };
        let effective_limit = if traffic_speed > 0 { traffic_speed } else { limit };
        // Clamp to the heuristic's max speed so no edge is ever faster than the
        // heuristic assumes — required to keep the A* heuristic consistent for the
        // monotonic radix heap (see MAX_DRIVING_KMH).
        let effective_limit = effective_limit.min(MAX_DRIVING_KMH as u8);
        if effective_limit > 0 {
            let speed_m_s = effective_limit as f64 / 3.6;
            return (dist_mm as f64 / (speed_m_s * 10.0)) as u32;
        }
    }
    let multiplier =
        g.edge_time_multipliers[(mode & 0x3) as usize][((type_ & ROAD_TYPE_MASK) & 0xF) as usize];
    ((dist_mm as u64 * multiplier) >> 32) as u32
}

/// Optimistic A* heuristic in 10 ms units.
#[inline]
pub fn heuristic_time_10ms(g: &Graph, lat1: i32, lon1: i32, lat2: i32, lon2: i32, mode: i32) -> u32 {
    let dist_mm = fast_dist_mm(g, lat1, lon1, lat2, lon2);
    let scaled_time = dist_mm as u64 * g.time_scale_fixed[(mode & 0x3) as usize];
    (scaled_time >> 32) as u32
}

#[inline]
pub fn get_bearing(lat1: i32, lon1: i32, lat2: i32, lon2: i32) -> f64 {
    let f1 = (lat1 as f64 / 1e7) * DEG_TO_RAD;
    let f2 = (lat2 as f64 / 1e7) * DEG_TO_RAD;
    let dl = ((lon2 - lon1) as f64 / 1e7) * DEG_TO_RAD;
    let y = dl.sin() * f2.cos();
    let x = f1.cos() * f2.sin() - f1.sin() * f2.cos() * dl.cos();
    y.atan2(x) * (180.0 / std::f64::consts::PI)
}

pub fn get_maneuver(prev_bearing: f64, next_bearing: f64) -> i32 {
    let mut angle_diff = next_bearing - prev_bearing;
    while angle_diff < -180.0 {
        angle_diff += 360.0;
    }
    while angle_diff > 180.0 {
        angle_diff -= 360.0;
    }
    // A reversal keeps its loop direction: a clockwise loop (positive sweep)
    // reads as a right U-turn and a counterclockwise one as a left U-turn, so
    // the two glyphs `ManeuverIcon` distinguishes can both be reached. Folding
    // both to UTURN_LEFT drew every U-turn as a left hook, which is only right
    // in right-hand traffic (task 36).
    if !(-155.0..=155.0).contains(&angle_diff) {
        return if angle_diff >= 0.0 { 7 } else { 3 };
    }
    if angle_diff < -100.0 {
        return 2;
    }
    if angle_diff < -45.0 {
        return 4;
    }
    if angle_diff < -10.0 {
        return 1;
    }
    if angle_diff < 10.0 {
        return 9;
    }
    if angle_diff < 45.0 {
        return 5;
    }
    if angle_diff < 100.0 {
        return 8;
    }
    6
}

pub struct Projection {
    pub lat_e7: i32,
    pub lon_e7: i32,
    pub dist_mm: u32,
}

/// Project point (px,py) onto segment (x1,y1)-(x2,y2). All coords are e7.
pub fn get_projection(
    g: &Graph,
    px: i32,
    py: i32,
    x1: i32,
    y1: i32,
    x2: i32,
    y2: i32,
) -> Projection {
    let lat_avg = (x1 + x2) as f64 / 2.0 * 1e-7;
    let lon_scale = (lat_avg * DEG_TO_RAD).cos();

    let dx = x2 as f64 - x1 as f64;
    let dy = (y2 as f64 - y1 as f64) * lon_scale;
    let dpx = px as f64 - x1 as f64;
    let dpy = (py as f64 - y1 as f64) * lon_scale;

    let mag_sq = dx * dx + dy * dy;
    let mut t = if mag_sq == 0.0 {
        0.0
    } else {
        (dpx * dx + dpy * dy) / mag_sq
    };
    t = t.clamp(0.0, 1.0);

    let proj_lat = (x1 as f64 + t * (x2 - x1) as f64) as i32;
    let proj_lon = (y1 as f64 + t * (y2 - y1) as f64) as i32;
    Projection {
        lat_e7: proj_lat,
        lon_e7: proj_lon,
        dist_mm: fast_dist_mm(g, px, py, proj_lat, proj_lon),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::graph::{BICYCLE, PUBLIC_TRANSIT, REVERSE_GEOMETRY_FLAG, WALK};

    /// The generator sets `REVERSE_GEOMETRY_FLAG` on one edge of every
    /// bidirectional collapsed chain, so permission checks must ignore it. The
    /// mask here was `0x7F`, which stripped only bit 7: a flagged residential
    /// road arrived as 71 rather than 7, fell outside every permitted range, and
    /// was dropped from snapping, from A* relaxation and from junction lane
    /// inference. That silently removed half the road network.
    #[test]
    fn the_reverse_geometry_flag_does_not_change_permissions() {
        for ty in 1..=STEPS {
            for mode in [DRIVING, WALK, BICYCLE, PUBLIC_TRANSIT] {
                assert_eq!(
                    is_mode_allowed(ty, mode),
                    is_mode_allowed(ty | REVERSE_GEOMETRY_FLAG, mode),
                    "type {ty} mode {mode} changed when flagged"
                );
            }
        }
    }

    /// A U-turn keeps its loop direction (task 36): folding both to UTURN_LEFT
    /// drew every reversal as a left hook. A clockwise loop sweeps positive and
    /// reads UTURN_RIGHT; counterclockwise sweeps negative and reads UTURN_LEFT.
    #[test]
    fn a_uturn_reports_the_direction_of_its_loop() {
        assert_eq!(get_maneuver(0.0, 180.0), 7, "due reversal at +180 loops right");
        assert_eq!(get_maneuver(0.0, -179.0), 3, "and at -179 it loops left");
        assert_eq!(get_maneuver(0.0, 170.0), 7, "a 170-degree clockwise sweep");
        assert_eq!(get_maneuver(0.0, -170.0), 3, "and counterclockwise");
        // The wrap is what makes the sign meaningful rather than an artefact:
        // southbound, looping back to just east of north sweeps negative and
        // reads left; to just west of north sweeps positive and reads right.
        assert_eq!(get_maneuver(180.0, 5.0), 3);
        assert_eq!(get_maneuver(180.0, -5.0), 7);
        // Everything short of a reversal is untouched.
        assert_eq!(get_maneuver(0.0, 0.0), 9);
        assert_eq!(get_maneuver(0.0, -90.0), 4);
        assert_eq!(get_maneuver(0.0, 90.0), 8);
        assert_eq!(get_maneuver(0.0, 150.0), 6);
        assert_eq!(get_maneuver(0.0, -150.0), 2);
    }

    #[test]
    fn driving_stops_at_living_street_but_walking_reaches_steps() {
        assert!(is_mode_allowed(MOTORWAY, DRIVING));
        assert!(is_mode_allowed(LIVING_STREET, DRIVING));
        assert!(!is_mode_allowed(LIVING_STREET + 1, DRIVING));
        assert!(!is_mode_allowed(STEPS, DRIVING));
        assert!(is_mode_allowed(STEPS, WALK));
        // Transit permits exactly what walking does: its road-graph use is the
        // walking legs of a journey.
        assert!(is_mode_allowed(STEPS, PUBLIC_TRANSIT));
        // Type 0 is "not a road" and is never permitted.
        assert!(!is_mode_allowed(0, DRIVING));
        assert!(!is_mode_allowed(0, WALK));
        assert!(!is_mode_allowed(REVERSE_GEOMETRY_FLAG, WALK));
    }
}

#[cfg(test)]
#[path = "geometry_tests.rs"]
mod cov_tests;
