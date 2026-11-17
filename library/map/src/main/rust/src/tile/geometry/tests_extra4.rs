use super::build::build;
use super::terrain::{sample_ground_metres, tile_ground_width_m};
use crate::style::paint::Ramp;
use crate::style::{Layer, LayerKind};
use crate::tess::roof;
use tilecodec::mamaps::body::{Body, Heightmap, GEOM_LINE, GEOM_POLYGON};

// --- curved labels along roads / rivers (WS-E) -------------------------

/// A symbol layer over the `roads` source with no kind filter, so it labels any named road
/// line — the render-side shape of the `roads-label` style layer the build carries names for.
fn roads_label_layer() -> Layer {
    Layer {
        id: "roads-label".to_string(),
        source_layer: "roads".to_string(),
        source_layer_id: tilecodec::mamaps::dict::LAYER_ROADS,
        kind: LayerKind::Symbol,
        kinds: Vec::new(),
        kind_ids: Vec::new(),
        require_flags: 0,
        forbid_flags: 0,
        detail_ids: Vec::new(),
        forbid_details: Vec::new(),
        forbid_kind_ids: Vec::new(),
        light: 0xFF3B3B3B,
        dark: 0xFFEDEDED,
        opacity: Ramp::constant(1.0),
        width: Ramp::constant(0.0),
        gap_width: Ramp::constant(0.0),
        spread: Ramp::constant(0.0),
        lanes: Ramp::constant(1.0),
        carriageway: false,
        dash: (0.0, 0.0),
        text_size: Ramp::constant(12.0),
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
        halo_dark: 0xFF0D1B2A,
        halo_width: 1.0,
        min_zoom: 0,
        browse_min_zoom: 0,
        max_zoom: 22,
        authored: "roads_label".to_string(),
    }
}

/// A body with one named road line running along `pts` (extent units).
fn named_road_body(name: &str, pts: &[(i16, i16)]) -> Body {
    use tilecodec::mamaps::body::{
        Feature, Layer as BodyLayer, Part, DEFAULT_EXTENT, WINDING_OUTER,
    };
    use tilecodec::mamaps::dict;
    let mut source = BodyLayer::new(dict::LAYER_ROADS);
    source.parts.push(Part {
        coord_start: 0,
        point_count: pts.len() as u32,
        winding: WINDING_OUTER,
    });
    source.coords.extend_from_slice(pts);
    source.features.push(Feature {
        kind: crate::style::kind_id_for_test("major_road"),
        kind_detail: 0,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: 1,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    Body {
        extent: DEFAULT_EXTENT,
        layers: vec![source],
        names: vec![name.to_string()],
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None,
        carriageways: Vec::new(),
        convention: None,
        region_links: Vec::new(),
    }
}

#[test]
fn a_named_road_line_shapes_a_curved_label_along_its_centreline() {
    if !crate::tile::glyph::fonts_staged() {
        eprintln!("SKIP: staged TTFs are not fonts");
        return;
    }
    // A straight eastbound road spanning the tile.
    let body = named_road_body("Market Street", &[(200, 2048), (3800, 2048)]);
    let layers = vec![roads_label_layer()];
    let mesh = build(&body, &layers, 14, 0, 8192, false);

    assert_eq!(
        mesh.labels.len(),
        1,
        "the named road shapes exactly one label"
    );
    let label = &mesh.labels[0];
    assert_eq!(label.name, "Market Street");
    assert_eq!(label.layer_index, 0);
    let centreline = label
        .centreline
        .as_ref()
        .expect("a road label is curved, not point");
    assert_eq!(
        centreline.len(),
        2,
        "the whole feature centreline rides on the label"
    );
    // Normalised into tile-local 0..1 from extent units, along the tile's mid-line.
    assert!(centreline
        .iter()
        .all(|&(x, y)| (0.0..=1.0).contains(&x) && (y - 0.5).abs() < 1e-3));
    assert!(
        centreline[1].0 > centreline[0].0,
        "eastbound: x increases along the line"
    );
}

#[test]
fn an_unnamed_road_line_shapes_no_label() {
    // Without a name there is nothing to lay along the line, so no curved label is produced —
    // the same silent skip the point path makes for an unnamed place.
    use tilecodec::mamaps::body::NAME_NONE;
    let mut body = named_road_body("ignored", &[(200, 2048), (3800, 2048)]);
    body.layers[0].features[0].name_idx = NAME_NONE;
    let mesh = build(&body, &[roads_label_layer()], 14, 0, 8192, false);
    assert!(
        mesh.labels.is_empty(),
        "an unnamed road line shapes nothing"
    );
}

// --- buildings sit on hills (Phase B: the datum gap) -----------------------

/// A `buildings` body with one square footprint (tile-local 0..~0.244) and the given attrs,
/// over an optional heightmap. Mirrors the `building_body` fixture in `tests_extra3`.
fn hill_building_body(
    attrs: Option<tilecodec::mamaps::body::BuildingAttrs>,
    heightmap: Option<Heightmap>,
) -> Body {
    use tilecodec::mamaps::body::{
        Feature, Layer as BodyLayer, Part, DEFAULT_EXTENT, NAME_NONE, WINDING_OUTER,
    };
    use tilecodec::mamaps::dict;
    let mut source = BodyLayer::new(dict::LAYER_BUILDINGS);
    source.parts.push(Part {
        coord_start: 0,
        point_count: 5,
        winding: WINDING_OUTER,
    });
    source
        .coords
        .extend_from_slice(&[(0, 0), (1000, 0), (1000, 1000), (0, 1000), (0, 0)]);
    source.features.push(Feature {
        kind: crate::style::kind_id_for_test("building"),
        kind_detail: 0,
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
    Body {
        extent: DEFAULT_EXTENT,
        layers: vec![source],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: match attrs {
            Some(a) => vec![(dict::LAYER_BUILDINGS, vec![a])],
            None => Vec::new(),
        },
        heightmap,
        carriageways: Vec::new(),
        convention: None,
        region_links: Vec::new(),
    }
}

/// A `dim x dim` heightmap from a metres-above-sea closure, applying the +32768 bias the
/// format stores.
fn hill_heightmap(dim: u16, metres: impl Fn(u16, u16) -> i32) -> Heightmap {
    let mut samples = Vec::with_capacity((dim as usize).pow(2));
    for row in 0..dim {
        for col in 0..dim {
            samples.push((metres(col, row) + 32768) as u16);
        }
    }
    Heightmap { dim, samples }
}

/// The lowest building-vertex z in the mesh, in tile-normalised units.
fn min_building_z(mesh: &super::mesh::TileMesh) -> f32 {
    mesh.buildings
        .vertices
        .chunks(roof::FLOATS_PER_VERTEX)
        .map(|c| c[2])
        .fold(f32::MAX, f32::min)
}

#[test]
fn a_building_over_a_hill_gets_its_base_lifted() {
    use tilecodec::mamaps::body::BuildingAttrs;
    // A 400 m plateau under the whole footprint (samples 0..3 in both axes cover the
    // tile-local 0..0.244 square on a 9x9 grid), so every corner samples exactly 400 m.
    // Wall bottoms hang the 8 m skirt below the sampled ground, so the lowest wall vertex
    // sits at base + (400 - 8) / ground_width — the same tile-normalised unit the terrain
    // grid uses.
    let (z, y) = (14u8, 8192u32);
    let ground = tile_ground_width_m(z, y);
    let mut body = hill_building_body(
        Some(BuildingAttrs {
            height: 300,
            ..Default::default()
        }),
        Some(hill_heightmap(
            9,
            |c, r| if c < 3 && r < 3 { 400 } else { 0 },
        )),
    );
    // The footprint's far corner (1000/4096 ≈ 0.244) lands at grid 1.95, inside the plateau;
    // widen the plateau check by asserting the lift rather than the corner math.
    let mesh = build(&body, crate::style::layers(), z, 0, y, false);
    assert!(
        !mesh.buildings.indices.is_empty(),
        "the building must still extrude"
    );
    let want = (400.0 - 8.0) / ground as f32;
    let got = min_building_z(&mesh);
    assert!(
        (got - want).abs() < 1e-4,
        "the skirted wall base rides the hill at (metres - skirt)/ground_width: {got} vs {want}",
    );
    body.heightmap = None;
    let flat = build(&body, crate::style::layers(), z, 0, y, false);
    assert!(
        min_building_z(&flat).abs() < 1e-6,
        "without the hill the same building starts at z 0",
    );
}

#[test]
fn no_heightmap_leaves_structural_heights_bit_exact() {
    use tilecodec::mamaps::body::BuildingAttrs;
    // With no DEM the ground closure is 0.0, so every z must be exactly one of the structural
    // heights — base, eaves, ridge — with no offset leaking in. This is the pitch-0
    // byte-identical property as a direct assertion: any ground sample added to a vertex would
    // move its z off the structural set and fail the bit-exact compare.
    let (z, y) = (14u8, 8192u32);
    let ground = tile_ground_width_m(z, y);
    let factor = 1.0 / ground;
    let body = hill_building_body(
        Some(BuildingAttrs {
            height: 300,
            roof_height: 100,
            ..Default::default()
        }),
        None,
    );
    let mesh = build(&body, crate::style::layers(), z, 0, y, false);
    assert!(!mesh.buildings.indices.is_empty());
    // 30 m total, 10 m of roof: base 0, eaves 20 m, ridge 30 m, tile-normalised.
    let structural = [0.0f32, (20.0 * factor) as f32, (30.0 * factor) as f32].map(f32::to_bits);
    for c in mesh.buildings.vertices.chunks(roof::FLOATS_PER_VERTEX) {
        assert!(
            structural.contains(&c[2].to_bits()),
            "a no-DEM vertex z {} is off the structural set — an offset leaked in",
            c[2],
        );
    }
    // And rebuilding is deterministic, so the mesh is a pure function of tile + style.
    let again = build(&body, crate::style::layers(), z, 0, y, false);
    assert_eq!(mesh.buildings.vertices, again.buildings.vertices);
    assert_eq!(mesh.buildings.indices, again.buildings.indices);
}

#[test]
fn a_building_in_a_depression_sits_below_zero() {
    use tilecodec::mamaps::body::BuildingAttrs;
    // A -100 m basin under the footprint (the Dead Sea case): the base must come out negative
    // rather than clamped to z=0, or the building would float above the terrain grid there.
    // The 8 m skirt hangs the wall bottoms lower still, which only deepens the burial.
    let (z, y) = (14u8, 8192u32);
    let ground = tile_ground_width_m(z, y);
    let body = hill_building_body(
        Some(BuildingAttrs {
            height: 300,
            ..Default::default()
        }),
        Some(hill_heightmap(
            9,
            |c, r| if c < 3 && r < 3 { -100 } else { 0 },
        )),
    );
    let mesh = build(&body, crate::style::layers(), z, 0, y, false);
    assert!(!mesh.buildings.indices.is_empty());
    let got = min_building_z(&mesh);
    assert!(got < 0.0, "a below-sea base dips below z 0, got {got}");
    let want = (-100.0 - 8.0) / ground as f32;
    assert!(
        (got - want).abs() < 1e-4,
        "the skirted base sits at (metres - skirt)/ground_width: {got} vs {want}"
    );
}

#[test]
fn the_ground_sampler_follows_the_rendered_triangle_plane_and_clamps() {
    // A 2x2 ramp (0, 100, 200, 300 m): corners read their samples exactly, and
    // out-of-range u/v clamp to the edge rather than reading past the grid.
    let hm = Some(hill_heightmap(2, |c, r| (r as i32 * 2 + c as i32) * 100));
    // The single cell renders as `[a, b, d] = [0, 100, 300]` above the fold and
    // `[a, d, c] = [0, 300, 200]` below it. The centre sits on the fold (`fx == fy`),
    // so both planes agree there: 0 + (300 - 0) * 0.5 = 150.
    assert_eq!(sample_ground_metres(&hm, 0.5, 0.5), Some(150.0));
    assert_eq!(sample_ground_metres(&hm, 0.0, 0.0), Some(0.0));
    assert_eq!(sample_ground_metres(&hm, 1.0, 1.0), Some(300.0));
    // Off the fold the planes part: at (0.75, 0.25) the top triangle gives
    // 0 + 100 * 0.5 + 300 * 0.25 = 125, while bilinear would average 150 — a 25 m sag
    // the old sampler would have buried the wall by.
    assert_eq!(sample_ground_metres(&hm, 0.75, 0.25), Some(125.0));
    // And at (0.25, 0.75) the bottom triangle gives 0 + 300 * 0.25 + 200 * 0.5 = 175,
    // 25 m above the bilinear 150 — the daylight side the old sampler floated over.
    assert_eq!(sample_ground_metres(&hm, 0.25, 0.75), Some(175.0));
    assert_eq!(
        sample_ground_metres(&hm, -1.0, 0.0),
        sample_ground_metres(&hm, 0.0, 0.0)
    );
    assert_eq!(
        sample_ground_metres(&hm, 2.0, 1.0),
        sample_ground_metres(&hm, 1.0, 1.0)
    );
    assert_eq!(sample_ground_metres(&None, 0.5, 0.5), None);
    assert_eq!(
        sample_ground_metres(
            &Some(Heightmap {
                dim: 1,
                samples: vec![32768]
            }),
            0.5,
            0.5
        ),
        None,
        "a degenerate grid has no cell to interpolate inside",
    );
}

/// On saddle terrain the old bilinear sampler buries one wall foot and floats the other:
/// with corners (0,0)=0, (1,0)=100, (0,1)=100, (1,1)=0 the rendered fold runs high along
/// `a -> d` while bilinear sags through the middle. A building straddling the fold must
/// seat its corners on the drawn surface — and its skirted wall bottoms must hang below
/// the surface everywhere along the walls, never poke through it.
#[test]
fn a_building_on_saddle_terrain_seats_on_the_fold_and_skirts_below() {
    use tilecodec::mamaps::body::BuildingAttrs;
    let (z, y) = (14u8, 8192u32);
    let ground = tile_ground_width_m(z, y);
    let factor = 1.0 / ground;
    // Checkerboard saddle over a 9x9 DEM: even cells read 0 m, odd cells 200 m, so every
    // cell folds steeply along its diagonal and the footprint (tile-local 0..0.244, i.e.
    // grid 0..1.95) straddles the first fold.
    let saddle = Some(hill_heightmap(
        9,
        |c, r| if (c + r) % 2 == 0 { 0 } else { 200 },
    ));
    // The corners seat on the drawn triangles: (0,0) is the `a` corner at 0 m, and the far
    // corner (1000/4096 ≈ 0.244 → grid ≈ (1.95, 1.95)) sits on the fold `a -> d` of its
    // cell, which the sampler follows exactly.
    let corners = [(0.0f32, 0.0f32), (1000.0 / 4096.0, 1000.0 / 4096.0)];
    for (u, v) in corners {
        let sampled = sample_ground_metres(&saddle, u, v).expect("inside the DEM");
        let gx = u * 8.0;
        let gy = v * 8.0;
        let (x0, y0) = (gx.floor() as usize, gy.floor() as usize);
        let (fx, fy) = (gx - x0 as f32, gy - y0 as f32);
        let at = |c: usize, r: usize| {
            if (c + r).is_multiple_of(2) {
                0.0f32
            } else {
                200.0
            }
        };
        let (a, b, c, d) = (
            at(x0, y0),
            at(x0 + 1, y0),
            at(x0, y0 + 1),
            at(x0 + 1, y0 + 1),
        );
        // Recompute the covering triangle's plane independently of the sampler.
        let want = if fx >= fy {
            a + (b - a) * (fx - fy) + (d - a) * fy
        } else {
            a + (d - a) * fx + (c - a) * (fy - fx)
        };
        assert!(
            (sampled - want).abs() < 1e-4,
            "corner ({u}, {v}) seats on the drawn triangle plane: {sampled} vs {want}",
        );
    }
    // End to end through `build`: a 30 m box (300 dm) over the saddle. The 8 m skirt hangs
    // every wall bottom below the surface the corners seat on, so the minimum wall z is the
    // lowest seated corner minus the skirt — below the terrain everywhere along the foot.
    // (The box is shorter than the saddle's 200 m relief, so the test asserts burial of the
    // foot rather than clearance of the eaves: wall tops ride the local ground plus 30 m and
    // stay well above the wall bottoms they cap.)
    let body = hill_building_body(
        Some(BuildingAttrs {
            height: 300,
            ..Default::default()
        }),
        saddle,
    );
    let mesh = build(&body, crate::style::layers(), z, 0, y, false);
    assert!(
        !mesh.buildings.indices.is_empty(),
        "the building must still extrude"
    );
    let skirt = (8.0 * factor) as f32;
    // Lowest seated corner is a 0 m `a` corner; the skirt hangs the foot 8 m below it.
    let want_min = (0.0 * factor) as f32 - skirt;
    let got_min = min_building_z(&mesh);
    assert!(
        (got_min - want_min).abs() < 1e-4,
        "skirted wall bottoms hang below the seated ground: {got_min} vs {want_min}",
    );
    // And the walls keep their full structural height over the buried foot: each wall
    // spans base..wall_top at its own corners, translated by the local ground — the skirt
    // only pushes the bottom edge down, so the wall height itself is unchanged. Group
    // vertices by footprint corner (walls are per-edge quads, so each corner appears in
    // the two walls meeting there) and check the top-bottom span per corner.
    use std::collections::BTreeMap;
    let wall_h = (30.0 * factor) as f32;
    let mut corners: BTreeMap<(u32, u32), (f32, f32)> = BTreeMap::new();
    let mut wall_verts = 0;
    for c in mesh.buildings.vertices.chunks(roof::FLOATS_PER_VERTEX) {
        // Wall vertices carry a horizontal normal; the roof cap points straight up.
        if c[5].abs() >= 0.9 {
            continue;
        }
        wall_verts += 1;
        let key = (c[0].to_bits(), c[1].to_bits());
        let e = corners.entry(key).or_insert((f32::MAX, f32::MIN));
        e.0 = e.0.min(c[2]);
        e.1 = e.1.max(c[2]);
    }
    assert!(wall_verts > 0, "the box must emit wall vertices");
    // Four footprint corners, each with a bottom (seated ground minus skirt) and a top.
    assert_eq!(
        corners.len(),
        4,
        "one span per footprint corner, got {}",
        corners.len()
    );
    for (key, (lo, hi)) in &corners {
        // The skirt pushes the bottom down but leaves the top on the seated ground, so
        // each corner spans the structural height *plus* the skirt.
        assert!(
            (hi - lo - wall_h - skirt).abs() < 1e-4,
            "corner {key:?} wall spans structural height plus skirt: {lo}..{hi} vs {wall_h} + {skirt}",
        );
        // The skirted bottom hangs below the drawn surface at its own corner: recompute
        // the seated ground there and check the bottom is below it.
        let u = f32::from_bits(key.0);
        let v = f32::from_bits(key.1);
        let seated = sample_ground_metres(&body.heightmap, u, v).expect("inside the DEM");
        let seated_norm = (seated as f64 * factor) as f32;
        assert!(
            *lo < seated_norm,
            "corner ({u}, {v}) wall bottom {lo} must hang below the seated ground {seated_norm}",
        );
    }
}
