use super::build::build;
use crate::style;
use crate::style::Layer;
use tilecodec::mamaps::body::{Body, GEOM_LINE};

/// A body of straight roads, one per `(lane_count, oneway)` entry, on the `roads` layer.
fn carriageway_body(roads: &[(u8, bool)]) -> Body {
    use tilecodec::mamaps::body::{
        Feature, Layer as BodyLayer, Part, FLAG_IS_ONEWAY, NAME_NONE, WINDING_OUTER,
    };
    use tilecodec::mamaps::dict;
    let mut body = Body::new(4096);
    let mut source = BodyLayer::new(dict::LAYER_ROADS);
    for (i, &(lane_count, oneway)) in roads.iter().enumerate() {
        let parts_offset = source.parts.len() as u32;
        source.parts.push(Part {
            coord_start: source.coords.len() as u32,
            point_count: 2,
            winding: WINDING_OUTER,
        });
        let y = 100 + i as i16 * 100;
        source.coords.extend_from_slice(&[(0, y), (1000, y)]);
        source.features.push(Feature {
            kind: crate::style::kind_id_for_test("major_road"),
            kind_detail: 0,
            geom_type: GEOM_LINE,
            flags: if oneway { FLAG_IS_ONEWAY } else { 0 },
            name_idx: NAME_NONE,
            parts_offset,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count,
        });
    }
    body.layers.push(source);
    body
}

/// The `roads-carriageway` layer as a one-layer slice, so a test states its own layer set.
///
/// Read with lane rendering forced on: [`style::LANE_RENDERING`] is off for release, so the
/// shipped layer set carries no carriageway at all and the tests below would have nothing to
/// assert against.
fn carriageway_only() -> &'static [Layer] {
    let all = style::layers_with_lane_rendering();
    let at = all
        .iter()
        .position(|l| l.carriageway)
        .expect("the carriageway layer");
    all.get(at..=at).expect("a one-layer slice")
}

/// One road with a known carriageway row, tessellated under right-hand traffic.
fn split_for(lanes: u8, shape: tilecodec::mamaps::body::Carriageway) -> f32 {
    use tilecodec::mamaps::dict;
    let mut body = carriageway_body(&[(lanes, false)]);
    body.carriageways = vec![(dict::LAYER_ROADS, vec![shape])];
    build(&body, carriageway_only(), 16, 0, 0, false).carriageways[0].split
}

/// **What makes the `NO_MARKINGS` sentinel safe.** The renderer signals "paint no markings on
/// this ribbon" by pushing -2.0 into `Push.line.z`, the slot that otherwise carries the
/// centre-line split. That is only sound because a real split is an across-road coordinate and
/// so cannot leave `[-1, +1]` — a road that ever pushed a value below -1.5 would silently lose
/// its lane markings.
///
/// `vulkan/` is `#[cfg(target_os = "android")]`, so the sentinel itself is not reachable from a
/// host test. This pins the half that is: the producer side, over every shape a road can take,
/// including the lopsided splits that push furthest toward a kerb.
#[test]
fn a_roads_split_can_never_reach_the_no_markings_sentinel() {
    use tilecodec::mamaps::body::{Carriageway, MarkingConvention};

    let mut worst: f32 = 0.0;
    for left_hand in [false, true] {
        for lanes in 1..=12u8 {
            // The unknown-split path, which is what every archive takes today.
            let mut body = carriageway_body(&[(lanes, false), (lanes, true)]);
            body.convention = Some(MarkingConvention {
                left_hand,
                yellow_centre: false,
            });
            for mesh in build(&body, carriageway_only(), 16, 0, 0, false).carriageways {
                worst = worst.max(mesh.split.abs());
            }
            // And every tagged division of those lanes, including all-forward and
            // all-backward, which are the extremes that land the split on a kerb.
            for forward in 0..=lanes {
                let shape = Carriageway {
                    forward,
                    backward: lanes - forward,
                    solid_dividers: 0,
                };
                worst = worst.max(split_for(lanes, shape).abs());
            }
        }
    }
    assert!(
        worst <= 1.0,
        "a road pushed a split of {worst}, outside the +/-1 an across-road coordinate can \
         take; anything past -1.5 would be read as the no-markings sentinel",
    );
}

/// Turn arrows are produced from the archive's turn-lane table at high zoom and gated off
/// below it: a road with `turn:lanes` yields one arrow per marked lane, pointing along the
/// road, and none when the tile is too coarse.
#[test]
fn turn_arrows_come_from_the_turn_table_at_high_zoom() {
    use tilecodec::mamaps::body::{
        Feature, LaneTurns, Layer as BodyLayer, Part, LANE_LEFT, LANE_THROUGH, NAME_NONE,
        WINDING_OUTER,
    };
    use tilecodec::mamaps::dict;
    let mut body = Body::new(4096);
    let mut source = BodyLayer::new(dict::LAYER_ROADS);
    // A straight eastbound road spanning the tile, ending at the east edge.
    source.parts.push(Part {
        coord_start: 0,
        point_count: 2,
        winding: WINDING_OUTER,
    });
    source
        .coords
        .extend_from_slice(&[(100, 2000), (3000, 2000)]);
    source.features.push(Feature {
        kind: crate::style::kind_id_for_test("major_road"),
        kind_detail: 0,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 2,
    });
    body.layers.push(source);
    body.turn_lanes = vec![(
        dict::LAYER_ROADS,
        vec![LaneTurns {
            forward: vec![LANE_LEFT, LANE_THROUGH],
            backward: vec![],
        }],
    )];

    // Below the gate: no arrows built.
    assert!(build(&body, style::layers(), 11, 0, 0, false)
        .arrows
        .is_empty());

    let mesh = build(&body, style::layers(), 16, 0, 0, false);
    assert_eq!(mesh.arrows.len(), 2, "one arrow per marked forward lane");
    assert!(
        mesh.arrows.iter().all(|a| a.angle.abs() < 1e-4),
        "eastbound heading is ~0"
    );
    assert_eq!(mesh.arrows[0].arrow, crate::tile::arrow::TurnArrow::Left);
    assert_eq!(mesh.arrows[1].arrow, crate::tile::arrow::TurnArrow::Through);
    assert!(mesh.arrows.iter().all(|a| a.count == 2));
}
