//! Turn-lane guidance: OSM `turn:lanes` decoding and junction-topology fallback.
use crate::geometry::*;
use crate::graph::*;

/// Convert an OSM lane indication mask (`LANE_*` bits from the generator) into a
/// maneuver-ordinal bitmask (bit `i` set => `Maneuver` ordinal `i` is offered by
/// the lane). Ordinals match [`get_maneuver`] and the Kotlin
/// `RouteService.API.Maneuver` enum. An unmarked ("none") lane maps to STRAIGHT
/// so every lane always carries at least one arrow.
fn osm_mask_to_dir_mask(osm: u16) -> u32 {
    let mut m: u32 = 0;
    if osm & LANE_THROUGH != 0 {
        m |= 1 << 9; // STRAIGHT
    }
    if osm & LANE_NONE != 0 {
        m |= 1 << 9; // unmarked -> through
    }
    if osm & LANE_LEFT != 0 {
        m |= 1 << 4; // TURN_LEFT
    }
    if osm & LANE_SLIGHT_LEFT != 0 {
        m |= 1 << 1; // TURN_SLIGHT_LEFT
    }
    if osm & LANE_SHARP_LEFT != 0 {
        m |= 1 << 2; // TURN_SHARP_LEFT
    }
    if osm & LANE_RIGHT != 0 {
        m |= 1 << 8; // TURN_RIGHT
    }
    if osm & LANE_SLIGHT_RIGHT != 0 {
        m |= 1 << 5; // TURN_SLIGHT_RIGHT
    }
    if osm & LANE_SHARP_RIGHT != 0 {
        m |= 1 << 6; // TURN_SHARP_RIGHT
    }
    if osm & LANE_REVERSE != 0 {
        // A `reverse` lane offers the U-turn in whichever direction the route
        // loops: the tag names the reversal, not the side, and `get_maneuver`
        // reports the loop's own direction (task 36).
        m |= (1 << 3) | (1 << 7); // UTURN_LEFT | UTURN_RIGHT
    }
    if osm & LANE_MERGE_TO_LEFT != 0 {
        m |= 1 << 1; // slight left
    }
    if osm & LANE_MERGE_TO_RIGHT != 0 {
        m |= 1 << 5; // slight right
    }
    if m == 0 {
        m |= 1 << 9; // default to STRAIGHT
    }
    m
}

/// Whether a lane whose maneuver set is `dir_mask` leads onto the route when the
/// taken maneuver is `taken`. Exact match, with a small tolerance so a dedicated
/// left lane also serves a slight/sharp-left maneuver (and symmetrically right).
fn lane_serves(dir_mask: u32, taken: i32) -> bool {
    let has = |o: i32| (0..31).contains(&o) && (dir_mask & (1u32 << o)) != 0;
    match taken {
        9 => has(9),           // STRAIGHT
        4 => has(4) || has(1), // TURN_LEFT
        1 => has(1) || has(4), // TURN_SLIGHT_LEFT
        2 => has(2) || has(4), // TURN_SHARP_LEFT
        3 => has(3),           // UTURN_LEFT
        7 => has(7),           // UTURN_RIGHT
        8 => has(8) || has(5), // TURN_RIGHT
        5 => has(5) || has(8), // TURN_SLIGHT_RIGHT
        6 => has(6) || has(8), // TURN_SHARP_RIGHT
        _ => has(taken),
    }
}

/// Build packed lane guidance from the real OSM lanes of the `approach` edge —
/// the edge the driver is on as they reach the maneuver junction. Each returned
/// int is `dir_mask * 2 + valid` where `dir_mask` is a maneuver-ordinal bitmask
/// (a lane can offer several turns) and `valid` marks a lane that leads onto the
/// taken route. Returns `None` when the edge has no real lane tags, so callers
/// fall back to [`junction_lanes`] topology inference.
pub(super) fn real_lanes(g: &Graph, approach: u64, taken: i32) -> Option<Vec<i32>> {
    if approach == INVALID_EDGE {
        return None;
    }
    let masks = g.edge_lane_masks(approach)?;
    if masks.is_empty() {
        return None;
    }
    let packed = masks
        .iter()
        .map(|&osm| {
            let dir_mask = osm_mask_to_dir_mask(osm);
            let valid = if lane_serves(dir_mask, taken) { 1 } else { 0 };
            ((dir_mask as i32) << 1) | valid
        })
        .collect();
    Some(packed)
}

/// Derive the available turn lanes at `junction` for a maneuver taken with the
/// given `incoming_bearing`. Purely topological: enumerates the junction's
/// outgoing driveable edges, classifies each by turn direction relative to the
/// incoming heading, and marks the one(s) matching `taken`. Returns packed
/// `dir_mask * 2 + valid` entries sorted left→right (each topology lane offers a
/// single direction, so `dir_mask` has one bit set), or empty when there is no
/// real choice (<= 1 option). Used as the fallback when an edge has no real OSM
/// turn:lanes.
pub(super) fn junction_lanes(
    g: &Graph,
    junction: u32,
    prev_node: u32,
    incoming_bearing: f64,
    taken: i32,
) -> Vec<i32> {
    if junction >= g.node_count {
        return Vec::new();
    }
    let jnode = g.node(junction);
    let (s, e_ptr) = g.edge_range(junction);
    let jlat = jnode.lat_e7;
    let jlon = jnode.lon_e7;

    let mut coords = [LatLon { lat_e7: 0, lon_e7: 0 }; 256];
    // (signed angle diff for sorting, turn direction code)
    let mut opts: Vec<(f64, i32)> = Vec::new();

    for k in s..e_ptr {
        let edge = g.edge(junction, k);
        if !is_mode_allowed(edge.type_, DRIVING) {
            continue;
        }
        if edge.target >= g.node_count {
            continue;
        }
        // Skip the edge back the way we came — that's a U-turn, not a lane.
        if edge.target == prev_node {
            continue;
        }

        // Outgoing heading: junction -> first vertex leaving the junction.
        let (nlat, nlon) = match g.get_edge_coordinates_from(junction, k, &mut coords) {
            Some((count, is_rev)) if count >= 2 => {
                let p1 = get_pt_at(&coords, count, is_rev, 1);
                (p1.lat_e7, p1.lon_e7)
            }
            _ => {
                let n = g.get_node(edge.target);
                (n.lat_e7, n.lon_e7)
            }
        };

        let out_bearing = get_bearing(jlat, jlon, nlat, nlon);
        let mut ad = out_bearing - incoming_bearing;
        while ad < -180.0 {
            ad += 360.0;
        }
        while ad > 180.0 {
            ad -= 360.0;
        }
        let dir = get_maneuver(incoming_bearing, out_bearing);
        if !opts.iter().any(|&(_, d)| d == dir) {
            opts.push((ad, dir));
        }
        if opts.len() >= 8 {
            break;
        }
    }

    if opts.len() <= 1 {
        return Vec::new();
    }
    opts.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
    opts.iter()
        .map(|&(_, dir)| {
            let dir_mask: i32 = if (0..31).contains(&dir) { 1 << dir } else { 0 };
            (dir_mask << 1) | if dir == taken { 1 } else { 0 }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A `reverse` lane offers the U-turn in either loop direction (task 36):
    /// the tag names the reversal, not the side, and the taken maneuver carries
    /// the loop's own direction from `get_maneuver`.
    #[test]
    fn a_reverse_lane_serves_a_uturn_looping_either_way() {
        let mask = osm_mask_to_dir_mask(LANE_REVERSE);
        assert_ne!(mask & (1 << 3), 0, "UTURN_LEFT is offered");
        assert_ne!(mask & (1 << 7), 0, "UTURN_RIGHT is offered");
        assert!(lane_serves(mask, 3), "a left loop validates");
        assert!(lane_serves(mask, 7), "and so does a right loop");
        assert!(!lane_serves(mask, 9), "but not a straight-on");
    }
}
