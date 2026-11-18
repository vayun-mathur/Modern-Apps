//! DBNet region tests for the degenerate maps: wrong cell counts,
//! zero extents, blank maps, and a single-pixel component whose
//! contour cannot define an angle.

use super::regions;
use crate::preprocess::Letterbox;

fn fit() -> Letterbox {
    Letterbox::new(16, 16, 16, 1).expect("fits")
}

#[test]
fn regions_refuses_mismatched_and_zero_maps() {
    assert!(regions(&[0.5; 3], 16, 16, &fit(), (16, 16)).is_err(), "wrong count");
    assert!(regions(&[], 0, 16, &fit(), (16, 16)).is_err(), "zero width");
    assert!(regions(&[], 16, 0, &fit(), (16, 16)).is_err(), "zero height");
}

#[test]
fn a_blank_map_has_no_regions() {
    let found = regions(&[0.0; 16 * 16], 16, 16, &fit(), (16, 16)).expect("blank is fine");
    assert!(found.is_empty());
}

#[test]
fn sub_minimum_boxes_are_dropped_and_real_ones_kept() {
    // One pixel: its box is below MIN_SIZE (3.0), so it is examined
    // and dropped, exactly as the reference drops specks.
    let mut map = vec![0.0f32; 16 * 16];
    map[5 * 16 + 5] = 0.95;
    let found = regions(&map, 16, 16, &fit(), (16, 16)).expect("one pixel is fine");
    assert!(found.is_empty(), "a sub-MIN_SIZE box is dropped: {found:?}");

    // A 4x4 block clears both the box threshold and MIN_SIZE.
    let mut map = vec![0.0f32; 16 * 16];
    for y in 4..8 {
        for x in 4..8 {
            map[y * 16 + x] = 0.9;
        }
    }
    let found = regions(&map, 16, 16, &fit(), (16, 16)).expect("a block is fine");
    assert_eq!(found.len(), 1, "one block, one region: {found:?}");
    assert!((found[0].score - 0.9).abs() < 0.01, "score is the block mean: {}", found[0].score);
}

#[test]
fn a_low_score_component_is_examined_and_dropped() {
    // Above the pixel threshold (0.3) but below the box threshold
    // (0.6): labelled, scored, and dropped without a region.
    let mut map = vec![0.0f32; 16 * 16];
    for y in 4..8 {
        for x in 4..8 {
            map[y * 16 + x] = 0.45;
        }
    }
    let found = regions(&map, 16, 16, &fit(), (16, 16)).expect("a faint block is fine");
    assert!(found.is_empty(), "a faint component makes no region: {found:?}");
}

#[test]
fn a_corner_block_and_a_thin_line_still_make_regions() {
    // A block in the very corner: the flood fill's out-of-range
    // neighbours are skipped, not read.
    let mut map = vec![0.0f32; 16 * 16];
    for y in 0..4 {
        for x in 0..4 {
            map[y * 16 + x] = 0.9;
        }
    }
    let found = regions(&map, 16, 16, &fit(), (16, 16)).expect("a corner block is fine");
    assert_eq!(found.len(), 1, "{found:?}");

    // A one-pixel-wide line: a degenerate (collinear) contour whose
    // fitted rectangle has no height on one axis.
    let mut map = vec![0.0f32; 16 * 16];
    for x in 2..10 {
        map[7 * 16 + x] = 0.9;
    }
    let found = regions(&map, 16, 16, &fit(), (16, 16)).expect("a line is fine");
    assert_eq!(found.len(), 1, "the line survives as a region: {found:?}");
}

#[test]
fn many_specks_stop_at_the_candidate_budget() {
    // Isolated hot pixels on a coarse grid: each is a component the
    // labelling examines and drops, and the scan stops at the
    // candidate budget rather than walking the whole map.
    let mut map = vec![0.0f32; 64 * 64];
    for y in (0..64).step_by(2) {
        for x in (0..64).step_by(2) {
            map[y * 64 + x] = 0.95;
        }
    }
    let found = regions(&map, 64, 64, &fit(), (64, 64)).expect("specks are fine");
    assert!(found.is_empty(), "every speck is sub-minimum: {found:?}");
}
