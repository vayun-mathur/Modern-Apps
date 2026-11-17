use super::build::build;
use super::terrain::{drape_vertices, sample_ground_metres, tile_ground_width_m};
use crate::style::{Layer, LayerKind};
use crate::tess::{fill, ribbon, stroke};
use tilecodec::mamaps::body::{Body, Heightmap};

// --- flat layers drape onto the relief -----------------------------------

/// A `dim x dim` heightmap from a metres-above-sea closure, applying the +32768 bias the
/// format stores. Mirrors the `hill_heightmap` fixture in `tests_extra4`.
fn drape_heightmap(dim: u16, metres: impl Fn(u16, u16) -> i32) -> Heightmap {
    let mut samples = Vec::with_capacity((dim as usize).pow(2));
    for row in 0..dim {
        for col in 0..dim {
            samples.push((metres(col, row) + 32768) as u16);
        }
    }
    Heightmap { dim, samples }
}

#[test]
fn tessellators_emit_zero_z_in_the_new_trailing_slot() {
    // The drape pass fills z at build time; straight out of the tessellator it is 0.0.
    let mut v = Vec::new();
    fill::tessellate(
        &[vec![(0, 0), (4096, 0), (4096, 4096), (0, 4096), (0, 0)]],
        4096,
        true,
        &mut v,
        &mut Vec::new(),
    );
    assert!(!v.is_empty());
    for c in v.chunks_exact(fill::FLOATS_PER_VERTEX) {
        assert_eq!(c[2], 0.0, "fill z defaults to 0.0");
    }
    let mut v = Vec::new();
    stroke::stroke(&[0, 0, 100, 0], 100, false, &mut v, &mut Vec::new());
    assert!(!v.is_empty());
    for c in v.chunks_exact(stroke::FLOATS_PER_VERTEX) {
        assert_eq!(c[7], 0.0, "stroke z defaults to 0.0");
    }
    let mut v = Vec::new();
    ribbon::ribbon(&[0, 0, 100, 0], 100, &mut v, &mut Vec::new());
    assert!(!v.is_empty());
    for c in v.chunks_exact(ribbon::FLOATS_PER_VERTEX) {
        assert_eq!(c[6], 0.0, "ribbon z defaults to 0.0");
    }
}

#[test]
fn drape_lifts_vertices_by_sampled_metres_over_ground_width_plus_lift() {
    // A flat 400 m plateau sampled at the vertices' own (u, v): every z lands on
    // 400 / ground_width + 0.5 / ground_width exactly.
    let ground_width_m = 1000.0;
    let hm = Some(drape_heightmap(2, |_, _| 400));
    // A fill vertex at tile-local (0.25, 0.5) plus a second at (0.75, 0.5).
    let mut v = vec![0.25, 0.5, 0.0, 0.75, 0.5, 0.0];
    drape_vertices(&mut v, fill::FLOATS_PER_VERTEX, 2, &hm, ground_width_m);
    let want = (400.5 / ground_width_m) as f32;
    assert!((v[2] - want).abs() < 1e-6, "got {} want {want}", v[2]);
    assert!((v[5] - want).abs() < 1e-6, "got {} want {want}", v[5]);
}

#[test]
fn drape_without_a_heightmap_leaves_vertices_bit_identical() {
    // Guarded callers skip the walk, but even an unguarded call over `None` (or a
    // degenerate grid) must not move a vertex: the sampler returns `None` and the
    // tessellator's 0.0 stands.
    let before = vec![
        0.25f32, 0.5, 7.0, 0.0, 0.0, 1.0, 2.0, 3.0, 0.0, 4.0, 5.0, 6.0, 0.5, 9.0,
    ];
    for hm in [
        None,
        Some(Heightmap {
            dim: 1,
            samples: vec![32768],
        }),
    ] {
        let mut v = before.clone();
        drape_vertices(&mut v, stroke::FLOATS_PER_VERTEX, 7, &hm, 1000.0);
        // Slot 7 (z) keeps its old value; every other float is untouched.
        assert_eq!(v, before, "no heightmap means no vertex moves");
        let _ = sample_ground_metres(&hm, 0.5, 0.5);
    }
}

#[test]
fn drape_at_tile_edges_matches_terrain_edge_samples() {
    // The sampler clamps u/v to [0, 1] with edge replication, so vertices on the border
    // read exactly the edge posts — a region crossing the tile edge meets the same ground.
    let ground_width_m = 2000.0;
    // West edge 100 m, east edge 300 m, linear ramp between.
    let hm = Some(drape_heightmap(3, |c, _| 100 + 100 * c as i32));
    let mut v = vec![0.0, 0.5, 0.0, 1.0, 0.5, 0.0, 0.5, 0.5, 0.0];
    drape_vertices(&mut v, fill::FLOATS_PER_VERTEX, 2, &hm, ground_width_m);
    let lift = (0.5 / ground_width_m) as f32;
    assert!(
        (v[2] - (100.0 / ground_width_m as f32 + lift)).abs() < 1e-5,
        "west edge: {}",
        v[2]
    );
    assert!(
        (v[5] - (300.0 / ground_width_m as f32 + lift)).abs() < 1e-5,
        "east edge: {}",
        v[5]
    );
    assert!(
        (v[8] - (200.0 / ground_width_m as f32 + lift)).abs() < 1e-5,
        "centre: {}",
        v[8]
    );
}

/// A plain (non-carriageway) stroke layer over `LAYER_ROADS` matching `major_road`, so the
/// test below exercises the stroke drape path through `build` rather than calling the helper
/// directly. Mirrors `major_road_at_min_zoom` in `tests.rs`.
fn stroke_roads_layer(min_zoom: u8) -> Layer {
    use crate::style::paint::Ramp;
    Layer {
        id: "roads-major".to_string(),
        source_layer: "roads".to_string(),
        source_layer_id: tilecodec::mamaps::dict::LAYER_ROADS,
        kind: LayerKind::Line,
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

/// One straight east-running road across the tile middle, over `LAYER_ROADS`.
fn straight_road_body(heightmap: Option<Heightmap>) -> Body {
    use tilecodec::mamaps::body::{Feature, Layer as BodyLayer, Part, NAME_NONE, WINDING_OUTER};
    use tilecodec::mamaps::dict;
    let mut body = Body::new(4096);
    let mut source = BodyLayer::new(dict::LAYER_ROADS);
    source.parts.push(Part {
        coord_start: 0,
        point_count: 2,
        winding: WINDING_OUTER,
    });
    source.coords.extend_from_slice(&[(0, 2048), (4096, 2048)]);
    source.features.push(Feature {
        kind: crate::style::kind_id_for_test("major_road"),
        kind_detail: 0,
        geom_type: tilecodec::mamaps::body::GEOM_LINE,
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
    body.layers.push(source);
    body.heightmap = heightmap;
    body
}

#[test]
fn a_degenerate_heightmap_keeps_the_flat_earth_fill() {
    // A tile carrying a heightmap too small to tessellate (dim < 2) builds no terrain
    // grid — and must keep its flat `earth` fill, or the water-blue background would
    // show through land. Sea is decided by water polygons, never by elevation.
    use tilecodec::mamaps::body::{Feature, Heightmap, Layer as BodyLayer, GEOM_POLYGON};
    use tilecodec::mamaps::body::{NAME_NONE, WINDING_OUTER};
    let layers = crate::style::layers();
    let mut body = Body::new(4096);
    let mut landtype = BodyLayer::new(tilecodec::mamaps::dict::LAYER_LANDTYPE);
    landtype.features.push(Feature {
        kind: tilecodec::mamaps::dict::NONE,
        kind_detail: tilecodec::mamaps::dict::NONE,
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
    landtype.parts.push(tilecodec::mamaps::body::Part {
        coord_start: 0,
        point_count: 4,
        winding: WINDING_OUTER,
    });
    landtype.coords = vec![(0, 0), (4096, 0), (4096, 4096), (0, 4096)];
    body.layers.push(landtype);
    body.heightmap = Some(Heightmap {
        dim: 1,
        samples: vec![32768],
    });
    let mesh = build(&body, layers, 11, 339, 770, false);
    assert!(
        mesh.terrain.indices.is_empty(),
        "a degenerate grid tessellates to nothing"
    );
    assert!(
        mesh.meshes
            .iter()
            .any(|m| layers[m.layer_index].id == "earth"),
        "and the flat earth fill stays as backstop",
    );
}

#[test]
fn a_built_road_sits_on_the_terrain_grid_below_it() {
    // The altitude question end to end through `build`: a road vertex's draped z must equal
    // the terrain grid's z at the same ground point (plus the sub-metre lift). A 9x9 ramp
    // (0..1600 m eastward) gives distinct heights across the road's run.
    let (z, y) = (11u8, 770u32);
    let ground = tile_ground_width_m(z, y);
    let hm = drape_heightmap(9, |c, _| c as i32 * 200);
    let body = straight_road_body(Some(hm));
    let mesh = build(&body, &[stroke_roads_layer(0)], z, 339, y, false);

    assert!(
        !mesh.terrain.vertices.is_empty(),
        "the heightmap builds a terrain grid"
    );
    let road = mesh
        .meshes
        .iter()
        .find(|m| m.kind == LayerKind::Line)
        .expect("a road mesh");
    assert!(!road.vertices.is_empty());
    let lift = (0.5 / ground) as f32;
    // Terrain grid z at the nearest grid vertex to (u, v): the grid is 9x9 at 1/8 steps.
    let terrain_z = |u: f32, v: f32| -> f32 {
        let col = ((u * 8.0).round() as usize).min(8);
        let row = ((v * 8.0).round() as usize).min(8);
        let at = row * 9 + col;
        mesh.terrain.vertices[at * crate::tess::terrain::FLOATS_PER_VERTEX + 2]
    };
    let mut checked = 0;
    for c in road.vertices.chunks_exact(stroke::FLOATS_PER_VERTEX) {
        let (u, v, road_z) = (c[0], c[1], c[7]);
        // Stroke vertices stand off the centreline by the join normal; accept the nearest
        // grid vertex, which is at most half a cell (1/16 tile) away in each axis.
        let want = terrain_z(u, v) + lift;
        assert!(
            (road_z - want).abs() < 0.02,
            "road z {road_z} vs terrain {want} at ({u}, {v})"
        );
        checked += 1;
    }
    assert!(checked > 0);
}
