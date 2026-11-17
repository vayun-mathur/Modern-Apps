//! Lane-guidance tests (sibling of `lanes.rs`): mask conversion, lane
//! validity tolerances, real OSM lanes, and topology fallback at a cross.
use super::*;
use crate::graph::graph_cov_tests::{assemble, Spec};
use std::collections::BTreeMap;

#[test]
fn osm_masks_convert_to_maneuver_ordinals() {
    assert_eq!(osm_mask_to_dir_mask(LANE_THROUGH), 1 << 9);
    assert_eq!(osm_mask_to_dir_mask(LANE_NONE), 1 << 9, "unmarked reads as through");
    assert_eq!(osm_mask_to_dir_mask(0), 1 << 9, "an empty mask defaults to straight");
    assert_eq!(osm_mask_to_dir_mask(LANE_LEFT), 1 << 4);
    assert_eq!(osm_mask_to_dir_mask(LANE_SLIGHT_LEFT), 1 << 1);
    assert_eq!(osm_mask_to_dir_mask(LANE_SHARP_LEFT), 1 << 2);
    assert_eq!(osm_mask_to_dir_mask(LANE_RIGHT), 1 << 8);
    assert_eq!(osm_mask_to_dir_mask(LANE_SLIGHT_RIGHT), 1 << 5);
    assert_eq!(osm_mask_to_dir_mask(LANE_SHARP_RIGHT), 1 << 6);
    assert_eq!(osm_mask_to_dir_mask(LANE_MERGE_TO_LEFT), 1 << 1);
    assert_eq!(osm_mask_to_dir_mask(LANE_MERGE_TO_RIGHT), 1 << 5);
    let both = osm_mask_to_dir_mask(LANE_THROUGH | LANE_RIGHT);
    assert_eq!(both, (1 << 9) | (1 << 8), "a lane can offer two turns");
}

#[test]
fn lane_serves_tolerates_the_neighbouring_turn() {
    let left = osm_mask_to_dir_mask(LANE_LEFT);
    assert!(lane_serves(left, 4) && lane_serves(left, 1) && lane_serves(left, 2));
    assert!(!lane_serves(left, 8) && !lane_serves(left, 9));
    let right = osm_mask_to_dir_mask(LANE_RIGHT);
    assert!(lane_serves(right, 8) && lane_serves(right, 5) && lane_serves(right, 6));
    assert!(!lane_serves(right, 4));
    let through = osm_mask_to_dir_mask(LANE_THROUGH);
    assert!(lane_serves(through, 9) && !lane_serves(through, 4));
}

fn cross(lanes: Option<Vec<u8>>) -> Spec {
    // Centre node 0 with arms east(1) north(2) west(3) south(4); edge 3->0 is
    // the approach carrying the real lanes in the lane test.
    let mut edges = Vec::new();
    for (s, t) in [(3, 0), (0, 1), (0, 2), (0, 3), (0, 4), (1, 0), (2, 0), (4, 0)] {
        edges.push((s, t, 5u8, 50u8, 1_113_900u32));
    }
    Spec {
        nodes: vec![(0, 0), (0, 100_000), (100_000, 0), (0, -100_000), (-100_000, 0)],
        edges,
        escaped: vec![],
        names: BTreeMap::new(),
        pool: vec![],
        geom: BTreeMap::new(),
        lanes,
        elevation: None,
    }
}

#[test]
fn real_lanes_pack_dir_mask_and_validity() {
    // Approach edge (sorted index of 3->0) carries [through, left].
    let mut spec0 = cross(None);
    spec0.edges.sort();
    let approach = spec0.edges.iter().position(|e| (e.0, e.1) == (3, 0)).unwrap() as u32;
    let mut lanes = Vec::new();
    lanes.extend_from_slice(&1u32.to_le_bytes());
    lanes.extend_from_slice(&approach.to_le_bytes());
    lanes.extend_from_slice(&0u32.to_le_bytes());
    lanes.extend_from_slice(&(approach + 1).to_le_bytes());
    lanes.extend_from_slice(&4u32.to_le_bytes()); // blob length sentinel
    for m in [LANE_THROUGH, LANE_LEFT] {
        lanes.extend_from_slice(&m.to_le_bytes());
    }
    let g = assemble(&cross(Some(lanes)));
    // Sorted graph index differs from the spec position: find it via range.
    let (s, _) = g.edge_range(3);
    let packed = real_lanes(&g, s, 9).expect("real lanes present");
    assert_eq!(packed, [(1 << 9) << 1 | 1, (1 << 4) << 1], "straight valid, left not");
    let packed_left = real_lanes(&g, s, 4).unwrap();
    assert_eq!(packed_left, [(1 << 9) << 1, (1 << 4) << 1 | 1]);
    assert!(real_lanes(&g, INVALID_EDGE, 9).is_none());
    let (s0, _) = g.edge_range(0);
    assert!(real_lanes(&g, s0, 9).is_none(), "an untagged edge falls back to topology");
}

#[test]
fn junction_lanes_offer_left_straight_right_at_a_cross() {
    let g = assemble(&cross(None));
    // Arrive at the centre heading east (bearing 90), from the west arm (3).
    let lanes = junction_lanes(&g, 0, 3, 90.0, 9);
    assert_eq!(lanes.len(), 3, "left, straight and right: {lanes:?}");
    assert_eq!(lanes[0], (1 << 4) << 1, "leftmost is the left turn, not taken");
    assert_eq!(lanes[1], (1 << 9) << 1 | 1, "straight is taken");
    assert_eq!(lanes[2], (1 << 8) << 1, "rightmost is the right turn");
    // Taking the left instead just moves the valid bit.
    let left = junction_lanes(&g, 0, 3, 90.0, 4);
    assert_eq!(left[0] & 1, 1);
    assert_eq!(left[1] & 1, 0);
    // One option only (the way back excluded) means no guidance.
    let g2 = assemble(&cross(None));
    assert!(junction_lanes(&g2, 1, 0, 90.0, 9).is_empty(), "a dead-end arm has no choice");
    assert!(junction_lanes(&g2, 999, 0, 90.0, 9).is_empty(), "no such junction");
}
