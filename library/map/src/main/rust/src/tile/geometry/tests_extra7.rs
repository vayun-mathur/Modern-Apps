use super::build::build;
use super::mesh::{LayerMesh, TileMesh};
use super::terrain::tile_ground_width_m;
use crate::style;
use crate::style::Layer;
use crate::tess::terrain;
use tilecodec::mamaps::body::{
    Body, Feature, Layer as BodyLayer, Part, GEOM_POLYGON, NAME_NONE, WINDING_OUTER,
};
use tilecodec::mamaps::dict;

/// A representative v8 body: one `landtype` layer holding a kind-less mainland
/// polygon and one `lake` polygon — the same shapes the old MVT fixture
/// carried, built directly as a body so the tests no longer depend on the
/// MVT→body converter.
/// (Duplicated from `tests_extra3`: sibling `#[cfg(test)]` modules cannot
/// see each other's private items.)
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
    landtype
        .coords
        .extend_from_slice(&[(500, 3000), (1100, 3000), (1100, 3400), (500, 3400)]);
    body.layers.push(landtype);
    body
}

fn mesh_for<'a>(mesh: &'a TileMesh, layers: &[Layer], id: &str) -> Option<&'a LayerMesh> {
    mesh.meshes.iter().find(|m| layers[m.layer_index].id == id)
}

// --- 3D terrain relief (WS-G) ------------------------------------------

/// A `dim x dim` heightmap from a metres-above-sea closure, applying the +32768 bias the format
/// stores.
fn heightmap(dim: u16, metres: impl Fn(u16, u16) -> i32) -> tilecodec::mamaps::body::Heightmap {
    let mut samples = Vec::with_capacity((dim as usize).pow(2));
    for row in 0..dim {
        for col in 0..dim {
            samples.push((metres(col, row) + 32768) as u16);
        }
    }
    tilecodec::mamaps::body::Heightmap { dim, samples }
}

#[test]
fn a_heightmap_tile_builds_terrain_and_keeps_the_flat_earth_fill() {
    // A tile carrying a heightmap draws its ground as the displaced terrain grid, and its
    // flat `earth` fill is kept as backstop: the terrain draws first depth-tested and the
    // flat fill paints over its cracks via depth-off layer order, so gaps show earth
    // colour rather than the water-blue clear colour. Water still tessellates as before.
    let layers = style::layers();
    let mut body = real();
    body.heightmap = Some(heightmap(9, |c, r| (c as i32 + r as i32) * 20));
    let mesh = build(&body, layers, 11, 339, 770, false);

    assert!(
        !mesh.terrain.indices.is_empty(),
        "the heightmap tile builds a terrain grid"
    );
    assert_eq!(
        mesh.terrain.indices.len() % 3,
        0,
        "terrain indices come in threes"
    );
    assert_eq!(
        mesh.terrain.vertices.len() % terrain::FLOATS_PER_VERTEX,
        0,
        "terrain vertices are whole",
    );
    assert!(
        mesh_for(&mesh, layers, "earth").is_some(),
        "the flat earth fill stays as backstop under the terrain grid",
    );
    assert!(
        mesh_for(&mesh, layers, "water").is_some(),
        "water still draws flat over terrain"
    );
}

#[test]
fn a_tile_without_a_heightmap_stays_flat() {
    // The no-DEM case (ocean, off-coverage): no terrain grid, and the flat earth fill remains
    // exactly as it always was.
    let layers = style::layers();
    let mesh = build(&real(), layers, 11, 339, 770, false);
    assert!(
        mesh.terrain.indices.is_empty(),
        "a tile with no heightmap builds no terrain"
    );
    assert!(
        mesh_for(&mesh, layers, "earth").is_some(),
        "and keeps its flat earth fill"
    );
}

/// Open sea builds no relief even when a DEM is attached: a tile with no kind-less land
/// base is water (or off the land product), so the land mask is all-false and the grid
/// drops out completely. Relief drawn there would paint shaded earth over the ocean —
/// the dark-palette black sea.
#[test]
fn open_sea_with_a_heightmap_builds_no_terrain() {
    use tilecodec::mamaps::dict::NONE;
    let layers = style::layers();
    let mut body = Body::new(4096);
    // Water only: no NONE-kind mainland, so this tile is sea.
    let mut water = BodyLayer::new(dict::LAYER_LANDTYPE);
    water.features.push(Feature {
        kind: crate::style::kind_id_for_test("ocean"),
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
    water.parts.push(Part {
        coord_start: 0,
        point_count: 4,
        winding: WINDING_OUTER,
    });
    water.coords = vec![(0, 0), (4096, 0), (4096, 4096), (0, 4096)];
    body.layers.push(water);
    // A flat sea-level DEM, exactly what coastal tiles carry over their water half.
    body.heightmap = Some(heightmap(9, |_, _| 0));
    let mesh = build(&body, layers, 4, 2, 5, false);
    assert!(
        mesh.terrain.indices.is_empty(),
        "open sea builds no relief grid, or shaded earth covers the ocean"
    );
    assert!(
        mesh_for(&mesh, layers, "water").is_some(),
        "the water fill still draws flat over the clear"
    );
}

#[test]
fn terrain_height_is_normalised_from_the_dem() {
    // The displaced z is metres / the tile's ground width — the same tile-local unit buildings
    // use — so the grid is zoom-independent and a hill of a known height lands where expected.
    let layers = style::layers();
    let (z, y) = (11u8, 770u32);
    let peak_m = 500;
    let mut body = real();
    // Flat except one central sample, so the peak vertex is unambiguous.
    body.heightmap = Some(heightmap(
        5,
        |c, r| if c == 2 && r == 2 { peak_m } else { 0 },
    ));
    let mesh = build(&body, layers, z, 339, y, false);

    let ground = tile_ground_width_m(z, y);
    let max_z = mesh
        .terrain
        .vertices
        .chunks(terrain::FLOATS_PER_VERTEX)
        .map(|c| c[2])
        .fold(f32::MIN, f32::max);
    assert!(
        (max_z - peak_m as f32 / ground as f32).abs() < 1e-4,
        "the peak rises to metres/ground_width: {} vs {}",
        max_z,
        peak_m as f32 / ground as f32,
    );
}

/// A coastal tile keeps relief off its water half: land on one side, lake water on
/// the other, one DEM. Cells reaching the water drop out while land cells stay —
/// so zooming into a coastline never paves the sea with shaded earth, whatever
/// the mainland polygon covers.
#[test]
fn a_coastal_tile_withholds_terrain_from_its_water_half() {
    use tilecodec::mamaps::dict::NONE;
    let layers = style::layers();
    let mut body = Body::new(4096);
    let mut landtype = BodyLayer::new(dict::LAYER_LANDTYPE);
    // Mainland west half; lake east half, sharing the x=2048 edge exactly — the
    // shape a clipped shoreline has in the tile.
    for (kind, x0) in [(NONE, 0), (crate::style::kind_id_for_test("lake"), 2048)] {
        landtype.features.push(Feature {
            kind,
            kind_detail: NONE,
            geom_type: GEOM_POLYGON,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset: landtype.parts.len() as u32,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
        let base = landtype.coords.len() as u32;
        landtype.parts.push(Part {
            coord_start: base,
            point_count: 4,
            winding: WINDING_OUTER,
        });
        landtype.coords.extend_from_slice(&[
            (x0, 0),
            (x0 + 2048, 0),
            (x0 + 2048, 4096),
            (x0, 4096),
        ]);
    }
    body.layers.push(landtype);
    // Hilly DEM: relief must appear west, never east.
    body.heightmap = Some(heightmap(9, |c, r| (c as i32 + r as i32) * 20));
    let mesh = build(&body, layers, 11, 339, 770, false);
    assert!(
        !mesh.terrain.indices.is_empty(),
        "the land half still builds relief"
    );
    // Every terrain triangle sits west of the shoreline (u < 0.5 in tile-local).
    for t in mesh.terrain.indices.chunks_exact(3) {
        for &i in t {
            let u = mesh.terrain.vertices[i as usize * terrain::FLOATS_PER_VERTEX];
            assert!(
                u < 0.5,
                "a terrain vertex at u={u} sits over the water half"
            );
        }
    }
    assert!(
        mesh_for(&mesh, layers, "water").is_some(),
        "the water fill still draws flat over the withheld grid"
    );
}

/// A marine protected area over open water draws nothing land-coloured: the
/// Monterey Bay tile at z12 (a `protected_area` polygon over the whole tile,
/// no water polygon, a land sliver at the edge). The sanctuary must match no
/// fill arm, so the sea stays the water-blue clear colour — not earth tan.
#[test]
fn a_marine_sanctuary_over_open_water_draws_no_land_fill() {
    use tilecodec::mamaps::dict::NONE;
    let layers = style::layers();
    let mut body = Body::new(4096);
    let mut landtype = BodyLayer::new(dict::LAYER_LANDTYPE);
    // The sanctuary: full tile, like the real tile 12/659/1595 (100.5% cover).
    landtype.features.push(Feature {
        kind: crate::style::kind_id_for_test("protected_area"),
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
    body.layers.push(landtype);
    let mesh = build(&body, layers, 12, 659, 1595, false);
    for id in ["earth", "landcover"] {
        assert!(
            mesh_for(&mesh, layers, id).is_none(),
            "{id} must not tessellate the sanctuary — it is open water",
        );
    }
    assert!(
        mesh.meshes.is_empty(),
        "no fill arm claims the sanctuary, so the clear colour (water-blue) shows",
    );
}
