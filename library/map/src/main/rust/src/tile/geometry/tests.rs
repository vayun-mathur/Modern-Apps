use super::build::{build, build_toggled};
use super::mesh::{LayerMesh, TileMesh};
use crate::style;
use crate::style::{KindFilter, Layer, LayerKind, LayerToggles};
use crate::tess::{fill, stroke};
use tilecodec::mamaps::body::{
    Body, Feature, Layer as BodyLayer, Part, GEOM_LINE, GEOM_POLYGON, NAME_NONE, WINDING_OUTER,
};
use tilecodec::mamaps::dict;

/// A representative v8 body: one `landtype` layer holding a kind-less mainland
/// polygon, one `major_road` LineString (kind 45) and two `lake` polygons — the
/// same shapes the old MVT fixture carried, built directly as a body so the
/// tests no longer depend on the MVT→body converter.
///
/// v8 merges the four wash layers into one `landtype` source: the mainland
/// carries kind [`NONE`](dict::NONE) (what the `earth` style arm matches — its
/// whitelist is empty) and the lakes carry `lake`, which the `water` arm names.
///
/// The road is named, so the curved-label tests in `tess::text` share this
/// fixture's shape: their own centreline below is this road's coordinates.
fn real() -> Body {
    use tilecodec::mamaps::dict::NONE;
    let mut body = Body::new(4096);
    body.names.push("Old Madrone Road".to_string());
    let mut landtype = BodyLayer::new(dict::LAYER_LANDTYPE);
    // Mainland first: the exterior every hole is contained in.
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
    for (i, base) in [(500i16, 3000i16), (2500, 500)].iter().enumerate() {
        landtype.features.push(Feature {
            kind: crate::style::kind_id_for_test("lake"),
            kind_detail: NONE,
            geom_type: GEOM_POLYGON,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset: 1 + i as u32,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
        landtype.parts.push(Part {
            coord_start: landtype.coords.len() as u32,
            point_count: 4,
            winding: WINDING_OUTER,
        });
        landtype.coords.extend_from_slice(&[
            *base,
            (base.0 + 600, base.1),
            (base.0 + 600, base.1 + 400),
            (base.0, base.1 + 400),
        ]);
    }
    body.layers.push(landtype);
    let mut roads = BodyLayer::new(dict::LAYER_ROADS);
    roads.features.push(Feature {
        kind: crate::style::kind_id_for_test("major_road"),
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: 1,
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

fn mesh_for<'a>(mesh: &'a TileMesh, layers: &[Layer], id: &str) -> Option<&'a LayerMesh> {
    mesh.meshes.iter().find(|m| layers[m.layer_index].id == id)
}

#[test]
fn the_real_tile_produces_geometry_for_the_layers_it_has_data_in() {
    // The fixture body carries one kind-less `landtype` mainland, one `roads`
    // LineString of kind = major_road, and two `lake` polygons on `landtype`.
    let layers = style::layers();
    let mesh = build(&real(), layers, 14, 339, 770, false);

    assert!(
        mesh_for(&mesh, layers, "earth").is_some(),
        "the earth polygon tessellates"
    );
    assert!(
        mesh_for(&mesh, layers, "water").is_some(),
        "both water polygons tessellate"
    );
    assert!(
        mesh_for(&mesh, layers, "roads-major").is_some(),
        "the major_road strokes"
    );
    assert!(
        mesh_for(&mesh, layers, "roads-major-casing").is_some(),
        "and so does its casing"
    );

    // Layers the tile has no data for produce no mesh at all, rather than an empty
    // one that would still cost a draw.
    assert!(
        mesh_for(&mesh, layers, "buildings").is_none(),
        "no buildings layer here"
    );
    assert!(
        mesh_for(&mesh, layers, "roads-highway").is_none(),
        "the road is a major_road"
    );
    assert!(
        mesh_for(&mesh, layers, "landuse_park:national_park").is_none(),
        "no landuse layer",
    );
}

#[test]
fn an_ancestor_carries_the_layers_it_will_stand_in_for() {
    // A tile is displayed as a stand-in ancestor up to ANCESTOR_DEPTH levels below its
    // own zoom, so tessellation has to cover that whole window. Gating on the tile's own
    // zoom instead means an ancestor holds no geometry for any layer whose `min_zoom` is
    // deeper than it, and those layers appear only once the exact-zoom tiles arrive.
    //
    // The layer is synthetic so the test states its own `min_zoom` of 9. It used to
    // borrow `roads-major`'s, which quietly tied this to the style table — and road
    // layers are gated by their width ramp now, so that number is gone.
    let layers = vec![major_road_at_min_zoom(9)];

    // z6 is within reach of z9 (6 + 4 = 10), so the road is tessellated ready for the
    // camera to descend onto it. The old tile-zoom gate dropped it here.
    let reaching = build(&real(), &layers, 6, 339, 770, false);
    assert!(
        mesh_for(&reaching, &layers, "roads-major").is_some(),
        "a z6 ancestor must carry the roads it will stand in for at z9",
    );

    // z4 is not (4 + 4 = 8 < 9), so the window stays bounded and this is not simply
    // tessellating everything at every zoom.
    let out_of_reach = build(&real(), &layers, 4, 339, 770, false);
    assert!(
        mesh_for(&out_of_reach, &layers, "roads-major").is_none(),
        "the window must stay bounded, or every tile pays for every layer",
    );
}

/// A line layer matching the published fixture's `major_road`, gated at `min_zoom`.
fn major_road_at_min_zoom(min_zoom: u8) -> Layer {
    use crate::style::paint::Ramp;
    Layer {
        id: "roads-major".to_string(),
        source_layer: "roads".to_string(),
        source_layer_id: tilecodec::mamaps::dict::LAYER_ROADS,
        kind: crate::style::LayerKind::Line,
        kinds: vec!["major_road".to_string()],
        kind_ids: vec![crate::style::kind_id_for_test("major_road")],
        require_flags: 0,
        forbid_flags: 0,
        detail_ids: Vec::new(),
        forbid_details: Vec::new(),
        forbid_kind_ids: Vec::new(),
        light: 0xFFFFFFFF,
        dark: 0xFF000000,
        opacity: Ramp::constant(1.0),
        width: Ramp::constant(1.0),
        gap_width: Ramp::constant(0.0),
        spread: Ramp::constant(0.0),
        lanes: Ramp::constant(1.0),
        carriageway: false,
        dash: (0.0, 0.0),
        text_size: Ramp::constant(0.0),
        text_size_large: None,
        rank_threshold: None,
        uppercase: false,
        medium: false,
        toggle: None,
        icon: false,
        text_offset: (0.0, 0.0),
        text_max_width: 0.0,
        variable_anchor: Vec::new(),
        halo_light: 0xFFFFFFFF,
        halo_dark: 0xFF000000,
        halo_width: 0.0,
        min_zoom,
        browse_min_zoom: min_zoom,
        max_zoom: 22,
        authored: "roads_major".to_string(),
    }
}

#[test]
fn every_mesh_is_well_formed() {
    let layers = style::layers();
    let mesh = build(&real(), layers, 11, 339, 770, false);
    assert!(!mesh.meshes.is_empty());
    for m in &mesh.meshes {
        let id = &layers[m.layer_index].id;
        let stride = match m.kind {
            LayerKind::Fill => fill::FLOATS_PER_VERTEX,
            LayerKind::Line => stroke::FLOATS_PER_VERTEX,
            LayerKind::Symbol => crate::tile::symbol::FLOATS_PER_VERTEX,
        };
        assert_eq!(m.vertices.len() % stride, 0, "{id} vertices are whole");
        assert_eq!(m.indices.len() % 3, 0, "{id} indices come in threes");
        assert!(!m.indices.is_empty(), "{id} has triangles");

        let vertex_count = (m.vertices.len() / stride) as u32;
        for &i in &m.indices {
            assert!(i < vertex_count, "{id} index {i} is out of range");
        }
        for f in &m.vertices {
            assert!(f.is_finite(), "{id} has a non-finite vertex");
        }
    }
}

#[test]
fn tessellated_output_is_within_the_bounds_the_shaders_assume() {
    // Tight, unlike the loose -3..4 this replaced. The vertex shaders assume
    // tile-local 0..1 positions, unit-length normals, and a distance-along-line that
    // is also tile-local — `line.vert` multiplies it by the tile's pixel size to get
    // pixels for the dash pattern. A violation of any of those renders a recognisable
    // map that is badly wrong, which is exactly the failure this pins down.
    let layers = style::layers();
    let mesh = build(&real(), layers, 11, 339, 770, false);
    assert!(!mesh.meshes.is_empty());

    let mut worst_pos = 0.0f32;
    let mut worst_normal = 0.0f32;
    let mut worst_distance = 0.0f32;
    for m in &mesh.meshes {
        match m.kind {
            LayerKind::Fill => {
                for chunk in m.vertices.chunks(fill::FLOATS_PER_VERTEX) {
                    worst_pos = worst_pos.max(chunk[0].abs()).max(chunk[1].abs());
                }
            }
            LayerKind::Line => {
                for chunk in m.vertices.chunks(stroke::FLOATS_PER_VERTEX) {
                    worst_pos = worst_pos.max(chunk[0].abs()).max(chunk[1].abs());
                    let length = (chunk[2] * chunk[2] + chunk[3] * chunk[3]).sqrt();
                    // A miter normal is deliberately *longer* than unit, by
                    // 1/cos(theta/2), so both segments' edges meet on it — the same
                    // trick MapLibre uses with its "special" normals of up to length
                    // 126/63 = 2. So the bound is the miter limit, not 1.
                    worst_normal = worst_normal.max(length);
                    worst_distance = worst_distance.max(chunk[6].abs());
                }
            }
            LayerKind::Symbol => {
                // Symbol quads carry (x, y, u, v): positions are tile-local like
                // fills, UVs are atlas 0..1 — checked by tess::text's own tests.
                for chunk in m.vertices.chunks(crate::tile::symbol::FLOATS_PER_VERTEX) {
                    worst_pos = worst_pos.max(chunk[0].abs()).max(chunk[1].abs());
                }
            }
        }
    }

    // Protomaps buffers tiles by a few percent, so a little overspill is expected and
    // 2.0 would not be.
    assert!(
        worst_pos < 1.3,
        "positions reach {worst_pos}, not tile-local 0..1"
    );
    assert!(
        worst_normal <= stroke::MITER_LIMIT + 1e-3,
        "a normal is {worst_normal} long, past the miter limit, so that join is too wide",
    );
    assert!(
        worst_distance < 1.3,
        "distance-along-line reaches {worst_distance}; line.vert scales it by the tile's \
         pixel size, so it must be tile-local, not extent units",
    );
    // And every vertex must be finite: a NaN normal from a zero-length segment would
    // silently drop or explode the triangles that share it.
    for m in &mesh.meshes {
        for f in &m.vertices {
            assert!(
                f.is_finite(),
                "{} emitted a non-finite vertex",
                layers[m.layer_index].id
            );
        }
    }
}

#[test]
fn positions_are_tile_normalised() {
    // The clip transform assumes 0..1 within the tile. Clipped geometry overspills
    // the edges a little, which is why the bound is generous rather than exact.
    let layers = style::layers();
    let mesh = build(&real(), layers, 11, 339, 770, false);
    for m in &mesh.meshes {
        let stride = match m.kind {
            LayerKind::Fill => fill::FLOATS_PER_VERTEX,
            LayerKind::Line => stroke::FLOATS_PER_VERTEX,
            LayerKind::Symbol => crate::tile::symbol::FLOATS_PER_VERTEX,
        };
        for chunk in m.vertices.chunks(stride) {
            assert!(
                chunk[0] > -3.0 && chunk[0] < 4.0,
                "x {} is not tile-normalised",
                chunk[0]
            );
            assert!(
                chunk[1] > -3.0 && chunk[1] < 4.0,
                "y {} is not tile-normalised",
                chunk[1]
            );
        }
    }
}

#[test]
fn a_layer_outside_its_zoom_range_is_skipped() {
    let layers = style::layers();
    let low = build(&real(), layers, 11, 339, 770, false);
    // buildings is min_zoom 14, and roads-minor is 13; the tile's road is a
    // major_road anyway.
    assert!(mesh_for(&low, layers, "roads-minor").is_none());
    assert!(
        mesh_for(&low, layers, "earth").is_some(),
        "earth draws at every zoom"
    );
}

#[test]
fn a_casing_produces_twice_the_vertices_of_a_plain_stroke() {
    let layers = style::layers();
    let mesh = build(&real(), layers, 11, 339, 770, false);
    let plain = mesh_for(&mesh, layers, "roads-major").expect("plain");
    let casing = mesh_for(&mesh, layers, "roads-major-casing").expect("casing");
    assert_eq!(
        plain.vertices.len() * 2,
        casing.vertices.len(),
        "a casing is two bands of the same centreline",
    );
    assert_eq!(plain.indices.len() * 2, casing.indices.len());
}

#[test]
fn an_empty_tile_produces_no_meshes() {
    let layers = style::layers();
    let mesh = build(&Body::new(4096), layers, 11, 0, 0, false);
    assert!(mesh.meshes.is_empty());
}

/// Three transit lines, two colours: the layer emits one mesh per distinct colour, in
/// first-seen feature order, and the two lines that share a colour share a mesh.
///
/// Without the split a `find` in the renderer would draw only the first mesh, so the
/// second operator's line would vanish rather than merely be miscoloured.
#[test]
fn transit_lines_split_into_one_mesh_per_colour() {
    use tilecodec::mamaps::body::{Feature, Layer as BodyLayer, Part, NAME_NONE, WINDING_OUTER};
    use tilecodec::mamaps::dict;

    let mut body = Body::new(4096);
    let mut source = BodyLayer::new(dict::LAYER_TRANSIT);
    // Blue, red, blue again — so the test also proves equal colours coalesce into one
    // mesh rather than one mesh per feature.
    for (color, y) in [
        (0x00_54_A5u32, 100i16),
        (0xE3_1E_24, 200),
        (0x00_54_A5, 300),
    ] {
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
            transit_color: color,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
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

    // Off by default, and the gate is before any tessellation: nothing at all.
    assert!(
        build(&body, only, 14, 0, 0, false).meshes.is_empty(),
        "an optional layer that is off must tessellate nothing",
    );

    let on = LayerToggles {
        poi: false,
        transit: true,
        traffic: false,
        safety: false,
    };
    let mesh = build_toggled(&body, only, 14, 0, 0, false, on, &KindFilter::all(), 7);
    assert_eq!(
        mesh.generation, 7,
        "the mesh records the generation it was built at"
    );
    let colours: Vec<Option<u32>> = mesh.meshes.iter().map(|m| m.color_override).collect();
    assert_eq!(
        colours,
        vec![Some(0xFF00_54A5), Some(0xFFE3_1E24)],
        "one opaque ARGB mesh per distinct colour, in first-seen order",
    );
    // The two blue lines really did share a mesh rather than each getting one.
    let (blue, red) = (&mesh.meshes[0], &mesh.meshes[1]);
    assert_eq!(
        blue.indices.len(),
        red.indices.len() * 2,
        "two lines against one"
    );
    for m in &mesh.meshes {
        assert_eq!(m.kind, LayerKind::Line);
        assert!(!m.indices.is_empty());
    }
}
