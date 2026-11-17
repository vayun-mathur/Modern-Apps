use super::build::{build, build_toggled};
use super::mesh::LayerMesh;
use crate::style;
use crate::style::{KindFilter, Layer, LayerToggles};
use crate::tess::ribbon;
use tilecodec::mamaps::body::{
    Body, Feature, Layer as BodyLayer, Part, GEOM_LINE, GEOM_POLYGON, NAME_NONE, WINDING_OUTER,
};
use tilecodec::mamaps::dict;
use tilecodec::mamaps::dict::LAYER_JUNCTION;

/// A representative v8 body: one `landtype` layer holding a kind-less mainland
/// polygon, one `major_road` LineString (kind 45) and one `lake` polygon — the
/// same shapes the old MVT fixture carried, built directly as a body so the
/// tests no longer depend on the MVT→body converter.
fn real() -> Body {
    use tilecodec::mamaps::dict::NONE;
    let mut body = Body::new(4096);
    let mut landtype = BodyLayer::new(dict::LAYER_LANDTYPE);
    landtype.features.push(Feature {
        kind: NONE,
        kind_detail: NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    landtype.parts.push(Part {
        coord_start: 0,
        point_count: 4,
        winding: WINDING_OUTER,
    });
    landtype.coords = vec![(0, 0), (4096, 0), (4096, 4096), (0, 4096)];
    landtype.features.push(Feature {
        kind: crate::style::kind_id_for_test("lake"),
        kind_detail: NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 1,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    landtype.parts.push(Part {
        coord_start: 4,
        point_count: 4,
        winding: WINDING_OUTER,
    });
    landtype.coords.extend_from_slice(&[(500, 3000), (1100, 3000), (1100, 3400), (500, 3400)]);
    body.layers.push(landtype);
    let mut roads = BodyLayer::new(dict::LAYER_ROADS);
    roads.features.push(Feature {
        kind: crate::style::kind_id_for_test("major_road"),
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 1,
    });
    roads.parts.push(Part {
        coord_start: 0,
        point_count: 4,
        winding: WINDING_OUTER,
    });
    roads.coords = vec![(100, 100), (1500, 900), (2600, 1800), (3900, 2700)];
    body.layers.push(roads);
    body
}

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

// --- lane connectors through junctions ---------------------------------

/// A body of straight lane connectors on the junction layer, each an already-sampled polyline
/// of three points — the shape the tiler emits, with the bezier sampled on its side.
///
/// The features deliberately carry a lane count of six and no one-way flag, neither of which a
/// connector can actually be. A connector's shape is fixed by what it *is*, so the tests below
/// prove the renderer imposes that rather than reading it off the feature.
fn junction_body(count: usize) -> Body {
    use tilecodec::mamaps::body::{Feature, Layer as BodyLayer, Part, NAME_NONE, WINDING_OUTER};
    let mut body = Body::new(4096);
    let mut source = BodyLayer::new(LAYER_JUNCTION);
    for i in 0..count {
        let parts_offset = source.parts.len() as u32;
        source.parts.push(Part {
            coord_start: source.coords.len() as u32,
            point_count: 3,
            winding: WINDING_OUTER,
        });
        let y = 100 + i as i16 * 100;
        source
            .coords
            .extend_from_slice(&[(0, y), (500, y), (1000, y + 200)]);
        source.features.push(Feature {
            kind: 0,
            kind_detail: 0,
            geom_type: GEOM_LINE,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 6,
        });
    }
    body.layers.push(source);
    body
}

/// The `junction-connector` layer as a one-layer slice, matching [`carriageway_only`].
fn connector_only() -> &'static [Layer] {
    let all = style::layers_with_lane_rendering();
    let at = all
        .iter()
        .position(|l| l.id == "junction-connector")
        .expect("the connector layer");
    all.get(at..=at).expect("a one-layer slice")
}

/// A connector goes through the carriageway path as one lane of one-way traffic, whatever the
/// feature carries.
///
/// Both halves matter to `road_surface.frag`, which reads them straight out of the push block.
/// One lane leaves no interior lane boundary, so no divider is dashed down the middle of it;
/// one-way suppresses the centre line, which is the point — a single stream of traffic has no
/// opposing direction to be separated from, and a connector painted with a centre line reads
/// as a two-way road through the junction.
///
/// Twelve of them, which is what a plain 4-arm crossroads emits (4 approaches x 3 legal exits,
/// no U-turn). They must collapse to **one** draw: every connector agrees on all three push
/// inputs by construction, so the mesh key coalesces them however many there are. That is what
/// keeps a dense tile to one extra draw call rather than one per connector, and it is the
/// property that would silently regress if a connector ever gained a per-feature shape.
#[test]
fn a_connector_is_one_lane_of_one_way_traffic_whatever_the_feature_carries() {
    let mesh = build(&junction_body(12), connector_only(), 17, 0, 0, false);
    assert_eq!(
        mesh.carriageways.len(),
        1,
        "a whole crossroads is one draw, not twelve"
    );
    let connector = &mesh.carriageways[0];
    assert_eq!(
        connector.lanes, 1,
        "one lane wide, not the six the feature claims"
    );
    assert!(
        connector.oneway,
        "and one-way, so no centre line is painted down it"
    );
    assert!(
        connector.split.abs() < 1e-6,
        "the split is meaningless on a one-way"
    );
    assert!(
        mesh.meshes.is_empty(),
        "a connector is not a stroked layer mesh"
    );

    // The ribbon vertex, so the existing pipeline and shaders draw it with no new format.
    assert_eq!(connector.vertices.len() % ribbon::FLOATS_PER_VERTEX, 0);
    assert_eq!(connector.indices.len() % 3, 0);
    let vertex_count = (connector.vertices.len() / ribbon::FLOATS_PER_VERTEX) as u32;
    assert_eq!(
        vertex_count, 72,
        "twelve connectors, three points each, two vertices a point"
    );
    assert!(
        connector.indices.iter().all(|&i| i < vertex_count),
        "an index is out of range"
    );
    assert!(connector.vertices.iter().all(|f| f.is_finite()));
}

/// Roads and connectors never share a draw, and the connector draws second.
///
/// They disagree on every push input, so they could not share one anyway. The order is the
/// point: a connector overlaps the road surface at the mouth of the junction, and the road
/// painting over the connector would leave the connector's edge lines cut off short of where
/// they meet the kerb.
#[test]
fn connectors_and_roads_are_separate_draws_with_the_connector_over_the_road() {
    let layers = style::layers_with_lane_rendering();
    let roads = layers
        .iter()
        .position(|l| l.id == "roads-carriageway")
        .expect("roads");
    let connectors = layers
        .iter()
        .position(|l| l.id == "junction-connector")
        .expect("connectors");
    assert!(
        roads < connectors,
        "layer order is draw order, and the connector goes on top"
    );

    let mut body = carriageway_body(&[(4, false)]);
    body.layers.extend(junction_body(1).layers);
    let mesh = build(&body, layers, 16, 0, 0, false);
    assert_eq!(
        mesh.carriageways
            .iter()
            .map(|c| (c.layer_index, c.lanes, c.oneway))
            .collect::<Vec<_>>(),
        vec![(roads, 4, false), (connectors, 1, true)],
    );
}

/// **The only path that exists today.** No archive carries a junction layer, and none will
/// until the tiler writes one, so the connector layer has to cost exactly nothing on every tile
/// there is: no mesh, no vertex, no draw, and no road drawn any differently.
///
/// Asserted as a byte-equality against the carriageway layer on its own, rather than as "no
/// connector mesh appeared". The weaker form would still pass if the connector layer had
/// quietly changed a road's lane count or split on its way past, which is the failure that
/// would actually reach a screen.
#[test]
fn a_tile_with_no_junction_layer_is_untouched_by_the_connector_layer() {
    let layers = style::layers_with_lane_rendering();
    let connectors = layers
        .iter()
        .position(|l| l.id == "junction-connector")
        .expect("connectors");

    // Ordinary roads at the carriageway zoom, and nothing else — a v7 archive.
    let body = carriageway_body(&[(4, false), (3, true), (0, false)]);
    assert!(
        body.layer(LAYER_JUNCTION).is_none(),
        "the fixture has no junction layer"
    );

    let full = build(&body, layers, 16, 0, 0, false);
    let roads_only = build(&body, carriageway_only(), 16, 0, 0, false);
    assert_eq!(
        full.carriageways.len(),
        roads_only.carriageways.len(),
        "an extra draw"
    );
    for (a, b) in full.carriageways.iter().zip(&roads_only.carriageways) {
        assert_ne!(
            a.layer_index, connectors,
            "a connector mesh out of thin air"
        );
        assert_eq!(a.lanes, b.lanes);
        assert_eq!(a.oneway, b.oneway);
        assert!(
            (a.split - b.split).abs() < 1e-6,
            "split {} became {}",
            b.split,
            a.split
        );
        assert_eq!(
            a.vertices, b.vertices,
            "the road geometry is byte-identical"
        );
        assert_eq!(a.indices, b.indices);
    }

    // And the published tile, which is the real thing and carries no junction layer either.
    let published = build(&real(), layers, 11, 339, 770, false);
    assert!(
        published.carriageways.is_empty(),
        "z11 is below the carriageway floor anyway"
    );
    assert!(
        !published.meshes.iter().any(|m| m.layer_index == connectors),
        "the connector layer must not draw a stroked mesh either",
    );
}

#[test]
fn transit_lines_of_one_colour_split_again_on_their_corridor_ordinal() {
    use tilecodec::mamaps::body::{Feature, Layer as BodyLayer, Part, NAME_NONE, WINDING_OUTER};
    use tilecodec::mamaps::dict;
    let mut body = Body::new(4096);
    let mut source = BodyLayer::new(dict::LAYER_TRANSIT);
    for (ordinal, y) in [(0u8, 100i16), (1, 200), (0, 300)] {
        let parts_offset = source.parts.len() as u32;
        source.parts.push(Part {
            coord_start: source.coords.len() as u32,
            point_count: 2,
            winding: WINDING_OUTER,
        });
        source.coords.extend_from_slice(&[(0, y), (1000, y)]);
        source.features.push(Feature {
            kind: crate::style::kind_id_for_test("rail"),
            kind_detail: 0,
            geom_type: GEOM_LINE,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset,
            part_count: 1,
            transit_color: 0x00_54_A5,
            transit_ordinal: ordinal,
            transit_lanes: 2,
            transit_taper: 255,
            lane_count: 0,
        });
    }
    body.layers.push(source);
    let all = style::layers();
    let at = all
        .iter()
        .position(|l| l.id == "transit-rail")
        .expect("the transit layer");
    let Some(only) = all.get(at..=at) else {
        panic!("a one-layer slice")
    };
    let on = LayerToggles {
        poi: false,
        transit: true,
        traffic: false,
        safety: false,
    };
    let mesh = build_toggled(&body, only, 14, 0, 0, false, on, &KindFilter::all(), 0);
    assert_eq!(
        mesh.meshes
            .iter()
            .map(|m| m.lane)
            .collect::<Vec<(u8, u8, u8)>>(),
        vec![(0, 2, 255), (1, 2, 255)],
        "one mesh per ordinal, in first-seen order",
    );
    assert!(mesh
        .meshes
        .iter()
        .all(|m| m.color_override == Some(0xFF00_54A5)));
    // The two lines on the same ordinal really did share a mesh.
    assert_eq!(
        mesh.meshes[0].indices.len(),
        mesh.meshes[1].indices.len() * 2
    );
}

/// The counterpart: a feature with no colour of its own stays in the layer's single
/// mesh, so the road path is untouched by the split.
#[test]
fn a_layer_whose_features_carry_no_colour_still_emits_one_mesh() {
    let layers = style::layers();
    let mesh = build(&real(), layers, 11, 339, 770, false);
    for m in &mesh.meshes {
        assert_eq!(
            m.color_override, None,
            "`{}` gained a colour override from a road feature",
            layers[m.layer_index].id,
        );
    }
    let roads: Vec<&LayerMesh> = mesh
        .meshes
        .iter()
        .filter(|m| layers[m.layer_index].id == "roads-major")
        .collect();
    assert_eq!(
        roads.len(),
        1,
        "one mesh per layer where no feature carries a colour"
    );
}

/// An unnamed safety point draws as a bare icon with the safety toggle on, and costs nothing
/// (no label, but still recorded in the gated summary) with it off.
#[test]
fn a_safety_point_is_an_icon_only_label_gated_by_its_toggle() {
    use tilecodec::mamaps::body::GEOM_POINT;
    let mut body = Body::new(4096);
    let mut source = BodyLayer::new(dict::LAYER_SAFETY);
    source.parts.push(Part { coord_start: 0, point_count: 1, winding: WINDING_OUTER });
    source.coords.push((2048, 2048));
    source.features.push(Feature {
        kind: crate::style::kind_id_for_test("stop_sign"),
        kind_detail: 0,
        geom_type: GEOM_POINT,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    body.layers.push(source);
    let all = style::layers();
    let at = all.iter().position(|l| l.id == "safety-points").expect("the safety layer");
    let only = &all[at..=at];
    let off = LayerToggles::default();
    let on = LayerToggles { safety: true, ..off };

    let shown = build_toggled(&body, only, 16, 0, 0, false, on, &KindFilter::all(), 0);
    assert_eq!(shown.labels.len(), 1, "one icon for one stop sign");
    assert!(shown.labels[0].lines.is_empty(), "no text: the point has no name");
    assert!(shown.labels[0].sprite.is_some(), "the stop_sign sprite resolved");
    assert!(shown.toggle_stamp.gated.has_safety);

    let hidden = build_toggled(&body, only, 16, 0, 0, false, off, &KindFilter::all(), 0);
    assert!(hidden.labels.is_empty(), "safety off draws nothing");
    assert!(hidden.toggle_stamp.gated.has_safety, "but the tile knows it holds safety");
}
