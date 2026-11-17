use super::*;
use tilecodec::mamaps::body::{
    LaneTurns, LANE_LEFT, LANE_RIGHT, LANE_THROUGH,
};

/// The two directions' arrows do not land on top of each other, which is what drew as a single
/// double-headed shaft.
#[test]
fn the_two_directions_do_not_stack_into_one_double_headed_shaft() {
    // With a half-segment setback both anchors were the midpoint of the same two-point line:
    // the same spot, opposite headings, i.e. one shaft with a head at each end.
    let line = [(0.0, 0.0), (100.0, 0.0)];
    let turns = LaneTurns {
        forward: vec![LANE_THROUGH],
        backward: vec![LANE_THROUGH],
    };
    let arrows = place_arrows(&line, &turns, (1, 1), false);
    assert_eq!(arrows.len(), 2);
    let per_m = tile_local_per_metre(14, 6331);
    let forward = placed_anchor(&arrows[0], per_m);
    let backward = placed_anchor(&arrows[1], per_m);
    assert!(
        (forward.0 - backward.0).abs() > 1.0,
        "the two directions draw apart"
    );
    assert!(forward.0 > 50.0, "forward sits back from the way's end");
    assert!(backward.0 < 50.0, "backward sits back from the way's start");
}

/// The shaft never folds through itself, however tight the bend.
///
/// The inner edge of the bend has radius `BEND_LEN / |turn| - SHAFT_HALF_WIDTH`, smallest at a
/// U-turn; if the constants ever let that go negative the ribbon inverts and the glyph draws as
/// a bow tie. Checked as consistent triangle winding rather than by re-deriving the radius.
#[test]
fn the_shaft_does_not_fold_at_the_tightest_bend() {
    use std::f32::consts::PI;
    for step in -24i32..=24 {
        let turn = PI * step as f32 / 24.0;
        let glyph = unit_arrow_triangles(turn);
        // The shaft is everything before the head's three vertices.
        for tri in glyph[..ARROW_VERTS - 3].chunks(3) {
            let (a, b, c) = (tri[0], tri[1], tri[2]);
            let area = (b.0 - a.0) * (c.1 - a.1) - (b.1 - a.1) * (c.0 - a.0);
            assert!(
                area > 1e-6,
                "shaft triangle inverted at turn {turn}, area {area}"
            );
        }
    }
}

/// A direction's lanes sit on its own half of the carriageway rather than straddling the
/// centreline, and which half is the driving convention: the left-hand fan is the mirror image
/// of the right-hand one.
///
/// Compared as a sign and as a sum against its mirror rather than for exact equality — the two
/// are the same arithmetic with one negation, but not bit-for-bit.
#[test]
fn each_direction_keeps_to_its_own_half_and_mirrors_with_the_convention() {
    let line = [(0.0, 0.0), (100.0, 0.0)];
    // Two lanes each way, both directions tagged: four lanes across the road.
    let turns = LaneTurns {
        forward: vec![LANE_LEFT, LANE_THROUGH],
        backward: vec![LANE_THROUGH, LANE_RIGHT],
    };
    let centres = |left_hand| {
        place_arrows(&line, &turns, (2, 2), left_hand)
            .iter()
            .map(lane_centre)
            .collect::<Vec<f32>>()
    };
    let right = centres(false);
    let left = centres(true);
    assert_eq!(right.len(), 4, "two lanes each way");
    // Every lane of a direction is on the driver's own side of the centreline — measured in
    // that direction's own travel frame, so both directions come out the same side.
    assert!(
        right.iter().all(|&c| c > 0.0),
        "right-hand traffic keeps right: {right:?}"
    );
    assert!(
        left.iter().all(|&c| c < 0.0),
        "left-hand traffic keeps left: {left:?}"
    );
    // And the two conventions are mirror images across the centreline.
    for (r, l) in right.iter().zip(left.iter().rev()) {
        assert!((r + l).abs() < 1e-5, "{r} does not mirror {l}");
    }
    // The outermost lane of a four-lane road is a lane and a half from its centre, so the fan
    // lands on the carriageway rather than a half-road short of it.
    let outermost = right.iter().fold(0.0f32, |m, &c| m.max(c));
    assert!(
        (outermost - 1.5).abs() < 1e-5,
        "outermost lane at {outermost}, not 1.5"
    );
}

/// An asymmetric split is read from the road's own division, not from the mask count: on a
/// five-lane road divided 4/1 the four forward lanes take four fifths of the carriageway and
/// the single backward lane the rest, which is where the centre line is drawn from too.
///
/// This is the case that separates a shared split from a per-direction guess — halving the
/// road would put the boundary a full lane out.
#[test]
fn an_asymmetric_split_puts_the_fan_where_the_centre_line_is() {
    let line = [(0.0, 0.0), (100.0, 0.0)];
    let turns = LaneTurns {
        forward: vec![LANE_LEFT, LANE_THROUGH, LANE_THROUGH, LANE_RIGHT],
        backward: vec![LANE_THROUGH],
    };
    let arrows = place_arrows(&line, &turns, (4, 1), false);
    assert_eq!(arrows.len(), 5);
    let centres: Vec<f32> = arrows.iter().map(lane_centre).collect();
    // The road spans -2.5..2.5 lane widths. The boundary sits a lane and a half left of
    // centre, so forward holds -1.5..2.5 and backward the single lane beyond it.
    for (got, want) in centres.iter().zip([-1.0, 0.0, 1.0, 2.0, 2.0]) {
        assert!(
            (got - want).abs() < 1e-5,
            "got {centres:?}, wanted the 4/1 division"
        );
    }
    // The backward lane is measured in its own travel frame, so it too is on the driver's
    // right — the same side of the road as the forward lanes are in theirs.
    assert!(
        centres[4] > 0.0,
        "the single backward lane keeps right in its own frame"
    );
}

/// A one-way carries every lane, so its fan stays centred on the road — and a road whose total
/// is unknown or disagrees keeps the centred fan it has always had. Neither depends on the
/// convention, which is what makes the no-convention path behave exactly as it did before.
#[test]
fn a_one_way_and_an_unknown_total_keep_the_centred_fan_either_way() {
    let line = [(0.0, 0.0), (100.0, 0.0)];
    let turns = LaneTurns {
        forward: vec![LANE_LEFT, LANE_THROUGH, LANE_RIGHT],
        backward: vec![],
    };
    let cases = [
        ((3u8, 0u8), "a three-lane one-way"),
        ((0, 0), "no lanes tag"),
        ((2, 0), "a split that disagrees"),
    ];
    for (lanes_each_way, what) in cases {
        for left_hand in [false, true] {
            let centres: Vec<f32> = place_arrows(&line, &turns, lanes_each_way, left_hand)
                .iter()
                .map(lane_centre)
                .collect();
            assert_eq!(centres.len(), 3, "{what}");
            assert!(
                centres[1].abs() < 1e-6,
                "{what}: the middle lane is the centreline"
            );
            assert!(
                (centres[0] + centres[2]).abs() < 1e-6,
                "{what}: the fan is centred"
            );
            assert!(centres[0] < 0.0, "{what}: ordinal 0 is the leftmost lane");
            assert!(
                centres[2] > 0.0,
                "{what}: and the last ordinal the rightmost"
            );
        }
    }
}

/// A latitude test: the tile square is a Mercator square, so a metre is a larger fraction of a
/// tile the further from the equator, and it is that factor that keeps [`super::place::SETBACK_M`] a ground
/// distance rather than a Mercator one.
#[test]
fn a_metre_is_a_bigger_slice_of_a_tile_the_further_north_it_is() {
    let equator = tile_local_per_metre(14, 8192);
    let burlingame = tile_local_per_metre(14, 6331);
    assert!(
        burlingame > equator,
        "Mercator stretches: {burlingame} vs {equator}"
    );
    // An equator tile at z14 spans the equator divided by the tile count, by definition.
    let span_m = 1.0 / equator;
    assert!(
        (span_m - 40_075_016.686 / 16384.0).abs() < 1.0,
        "{span_m} m across the tile"
    );
}

/// The setback is a distance on the ground, not a fraction of whatever the archive's simplifier
/// left as the way's final segment.
///
/// This is the defect the device showed: an OSM way split a few metres short of its junction
/// node has a short final segment, and a fraction of that is no setback at all, so the arrow
/// drew inside the intersection.
#[test]
fn the_setback_is_a_ground_distance_not_a_fraction_of_the_final_segment() {
    let per_m = tile_local_per_metre(14, 6331);
    let turns = LaneTurns {
        forward: vec![LANE_THROUGH],
        backward: vec![],
    };
    let back_m = |line: &[(f32, f32)]| {
        let arrows = place_arrows(line, &turns, (1, 0), false);
        (arrows[0].junction_end.0 - placed_anchor(&arrows[0], per_m).0) / per_m
    };
    // The same eastbound approach reaching the same node, once as one long segment and once
    // split five metres short of it.
    let one_segment = [(0.4, 0.5), (0.5, 0.5)];
    let split_short = [(0.4, 0.5), (0.5 - 5.0 * per_m, 0.5), (0.5, 0.5)];
    assert!(
        (back_m(&one_segment) - 20.0).abs() < 0.5,
        "{} m",
        back_m(&one_segment)
    );
    assert!(
        (back_m(&split_short) - 20.0).abs() < 0.5,
        "{} m",
        back_m(&split_short)
    );
}

/// And it does not drift with the tile's zoom: the same road built into a coarse tile and a
/// fine one sets its arrow back the same number of metres, which a tile-local constant or a
/// pixel measurement could not do.
#[test]
fn the_setback_is_the_same_ground_distance_at_every_tile_zoom() {
    let turns = LaneTurns {
        forward: vec![LANE_THROUGH],
        backward: vec![],
    };
    for (z, y) in [(12u8, 1582u32), (13, 3165), (14, 6331)] {
        let per_m = tile_local_per_metre(z, y);
        let arrows = place_arrows(&[(0.1, 0.5), (0.5, 0.5)], &turns, (1, 0), false);
        let back_m = (arrows[0].junction_end.0 - placed_anchor(&arrows[0], per_m).0) / per_m;
        assert!((back_m - 20.0).abs() < 0.5, "z{z} set back {back_m} m");
    }
}

/// An approach that curves away from its junction shortens its own setback rather than
/// throwing the arrow off the carriageway: the setback travels in a straight line, so it may
/// only travel as far as the road keeps going that way.
#[test]
fn a_curving_approach_shortens_its_setback_instead_of_leaving_the_road() {
    let per_m = tile_local_per_metre(14, 6331);
    let turns = LaneTurns {
        forward: vec![LANE_THROUGH],
        backward: vec![],
    };
    // Ten metres of straight run into the node, and before that the road bends hard north.
    let bend = [
        (0.5 - 40.0 * per_m, 0.5 - 30.0 * per_m),
        (0.5 - 10.0 * per_m, 0.5),
        (0.5, 0.5),
    ];
    let arrows = place_arrows(&bend, &turns, (1, 0), false);
    let anchor = placed_anchor(&arrows[0], per_m);
    let back_m = (arrows[0].junction_end.0 - anchor.0) / per_m;
    assert!(
        back_m > 9.0 && back_m < 11.0,
        "stops where the road does, {back_m} m"
    );
    assert!(
        (anchor.1 - 0.5).abs() < 1e-6,
        "and stays on the straight run it measured"
    );
}

/// A way too short to give both directions a full setback still keeps them apart, which is the
/// case that used to draw as one shaft with a head at each end.
#[test]
fn a_short_way_shares_its_length_between_the_two_directions() {
    let per_m = tile_local_per_metre(14, 6331);
    // A twenty-metre stub: a full setback each way would carry the two past each other.
    let line = [(0.5, 0.5), (0.5 + 20.0 * per_m, 0.5)];
    let turns = LaneTurns {
        forward: vec![LANE_THROUGH],
        backward: vec![LANE_THROUGH],
    };
    let arrows = place_arrows(&line, &turns, (1, 1), false);
    let (forward, backward) = (
        placed_anchor(&arrows[0], per_m),
        placed_anchor(&arrows[1], per_m),
    );
    assert!(forward.0 < line[1].0, "forward is back from its own end");
    assert!(backward.0 > line[0].0, "backward is back from its own end");
    assert!(
        forward.0 > backward.0,
        "and they have not crossed: {forward:?} {backward:?}"
    );
}

/// The setback reaches the drawn glyph, not just the instance: the whole arrow moves back by
/// the ground distance, so nothing downstream can place it at the junction node by forgetting.
#[test]
fn the_glyph_is_drawn_at_the_set_back_anchor_not_at_the_junction() {
    let per_m = tile_local_per_metre(14, 6331);
    let turns = LaneTurns {
        forward: vec![LANE_THROUGH],
        backward: vec![],
    };
    let arrows = place_arrows(&[(0.4, 0.5), (0.5, 0.5)], &turns, (1, 0), false);
    let tip_x = |local_per_metre| {
        let mut verts = Vec::new();
        arrow_verts(&arrows[0], 0.001, 0.0, local_per_metre, &mut verts);
        verts.chunks(2).map(|p| p[0]).fold(f32::MIN, f32::max)
    };
    // Against the same glyph with no setback at all, so this measures the shift and not the
    // glyph's own reach ahead of its anchor.
    let shift_m = (tip_x(0.0) - tip_x(per_m)) / per_m;
    assert!(
        (shift_m - 20.0).abs() < 0.5,
        "the glyph moved back {shift_m} m"
    );
}

/// The renderer's per-arrow transform: a through arrow points along the road (its tip furthest
/// along +heading), the lane offset shifts it perpendicular to the heading, and a left arrow's
/// tip lands to the left of the road.
#[test]
fn arrow_verts_rotate_scale_and_offset_into_the_lane() {
    // Eastbound through arrow at tile-centre, no lane offset: tip furthest in +x. A zero run
    // means no setback, which isolates the rotate-scale-offset transform this checks; the
    // setback itself is `the_glyph_is_drawn_at_the_set_back_anchor_not_at_the_junction`.
    let through = ArrowInstance {
        junction_end: (0.5, 0.5),
        angle: 0.0,
        run: 0.0,
        ordinal: 0,
        count: 1,
        arrow: TurnArrow::Through,
        exit_angle: None,
        fan_offset: 0.0,
        left_hand: false,
    };
    let mut v = Vec::new();
    arrow_verts(&through, 0.1, 0.0, 1.0, &mut v);
    assert_eq!(v.len(), ARROW_VERTS * 2);
    let max_x = v.chunks(2).map(|p| p[0]).fold(f32::MIN, f32::max);
    let tip_y = v.chunks(2).max_by(|a, b| a[0].total_cmp(&b[0])).unwrap()[1];
    assert!(
        (max_x - 0.6).abs() < 1e-5,
        "tip a scale-length ahead of the anchor in +x"
    );
    assert!((tip_y - 0.5).abs() < 1e-5, "and level with it, no offset");
    // A non-zero lateral offset shifts the whole glyph off the centreline (perpendicular to
    // the heading); the sign of the real offset comes from the lane's place across the road.
    let mut v2 = Vec::new();
    arrow_verts(&through, 0.1, 0.2, 1.0, &mut v2);
    let mean_y2 = v2.chunks(2).map(|p| p[1]).sum::<f32>() / (v2.len() / 2) as f32;
    assert!(
        (mean_y2 - 0.5).abs() > 0.1,
        "the lane offset moves the arrow off the centreline"
    );
    // A left arrow on an eastbound road points north (-y): its tip is above the anchor.
    let left = ArrowInstance {
        arrow: TurnArrow::Left,
        ..through
    };
    let mut v3 = Vec::new();
    arrow_verts(&left, 0.1, 0.0, 1.0, &mut v3);
    let tip = v3.chunks(2).min_by(|a, b| a[1].total_cmp(&b[1])).unwrap();
    assert!(tip[1] < 0.5, "a left turn's tip is north of the anchor");
}
