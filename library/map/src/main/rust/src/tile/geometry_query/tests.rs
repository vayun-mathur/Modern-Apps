use super::*;
use tilecodec::mamaps::body::{Feature, Layer as BodyLayer, Part, NAME_NONE, WINDING_OUTER};
use tilecodec::mamaps::dict::NONE;

fn kind(name: &str) -> u16 {
    crate::style::kind_id(name).expect("schema kind")
}

fn feat(kind: u16, geom_type: u8) -> Feature {
    Feature {
        kind,
        kind_detail: NONE,
        geom_type,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    }
}

fn push(layer: &mut BodyLayer, mut f: Feature, pts: &[(i16, i16)]) {
    f.parts_offset = layer.parts.len() as u32;
    layer.parts.push(Part {
        coord_start: layer.coords.len() as u32,
        point_count: pts.len() as u32,
        winding: WINDING_OUTER,
    });
    layer.coords.extend_from_slice(pts);
    layer.features.push(f);
}

/// One tile at z1/0/0 (west-north quadrant): landtype lake, a road,
/// a path, and a building, all in the tile's middle.
fn body() -> Body {
    let mut body = Body::new(4096);
    let mut land = BodyLayer::new(LAYER_LANDTYPE);
    push(&mut land, feat(NONE, GEOM_POLYGON), &[(0, 0), (4096, 0), (4096, 4096), (0, 4096)]);
    push(&mut land, feat(kind("lake"), GEOM_POLYGON), &[(2000, 2000), (3000, 2000), (3000, 3000), (2000, 3000)]);
    body.layers.push(land);
    let mut roads = BodyLayer::new(LAYER_ROADS);
    push(&mut roads, feat(kind("major_road"), GEOM_LINE), &[(500, 2000), (2000, 2000), (3500, 2000)]);
    push(&mut roads, feat(kind("path"), GEOM_LINE), &[(500, 2500), (3500, 2500)]);
    push(&mut roads, feat(kind("rail"), GEOM_LINE), &[(500, 3000), (3500, 3000)]);
    body.layers.push(roads);
    let mut buildings = BodyLayer::new(LAYER_BUILDINGS);
    push(&mut buildings, feat(kind("building"), GEOM_POLYGON), &[(1000, 1000), (1500, 1000), (1500, 1500), (1000, 1500)]);
    body.layers.push(buildings);
    body
}

fn world() -> GeometryQuery {
    GeometryQuery { west: -180.0, south: -85.0, east: 180.0, north: 85.0, kinds_mask: KIND_ALL, max_features: 256 }
}

#[test]
fn extracts_roads_paths_water_and_buildings_with_classes() {
    let tile = TileId { z: 1, x: 0, y: 0 };
    let body = body();
    let out = extract(&[(tile, Some(&body))], &world(), MAX_POINTS);
    let kinds: Vec<u32> = out.features.iter().map(|f| f.kind).collect();
    // Rail is not a road; the kind-less mainland is not water.
    assert_eq!(kinds, vec![KIND_ROAD, KIND_PATH, KIND_WATER, KIND_BUILDING]);
    assert_eq!(out.features[0].class, "major_road");
    assert!(!out.features[0].closed);
    assert!(out.features[2].closed, "a lake polygon is closed");
    assert!(out.features[3].closed, "a building polygon is closed");
    assert_eq!(out.covered_tiles, 1);
    assert!(!out.truncated);
    // Points land inside the tile's quadrant (west, north).
    for f in &out.features {
        for &(lon, lat) in &f.points {
            assert!((-180.0..0.0).contains(&lon), "lon {lon}");
            assert!((0.0..90.0).contains(&lat), "lat {lat}");
        }
    }
}

#[test]
fn bbox_filtering_drops_features_outside_the_query() {
    let tile = TileId { z: 1, x: 0, y: 0 };
    let body = body();
    // A bbox over the tile's far west edge: only the road/path
    // lines (which start at x=500) reach it; the lake/building do not.
    let (w, n) = (point_lon(tile, 4096, 600), point_lat(tile, 4096, 0));
    let q = GeometryQuery { west: -180.0, south: 0.0, east: w, north: n, kinds_mask: KIND_ALL, max_features: 256 };
    let out = extract(&[(tile, Some(&body))], &q, MAX_POINTS);
    assert!(out.features.iter().all(|f| f.kind == KIND_ROAD || f.kind == KIND_PATH));
    // A bbox nowhere near the tile: honestly empty, still covered.
    let q = GeometryQuery { west: 10.0, south: 10.0, east: 11.0, north: 11.0, kinds_mask: KIND_ALL, max_features: 256 };
    let out = extract(&[(tile, Some(&body))], &q, MAX_POINTS);
    assert!(out.features.is_empty());
    assert_eq!(out.covered_tiles, 1, "scanned and found nothing is not unavailable");
}

#[test]
fn caps_truncate_whole_features_and_flag_it() {
    let tile = TileId { z: 1, x: 0, y: 0 };
    let body = body();
    let q = GeometryQuery { max_features: 2, ..world() };
    let out = extract(&[(tile, Some(&body))], &q, MAX_POINTS);
    assert_eq!(out.features.len(), 2, "road + path survive first (wear priority)");
    assert!(out.truncated);
    // Point cap below every feature's size: nothing partial crosses.
    let out = extract(&[(tile, Some(&body))], &world(), 1);
    assert!(out.features.is_empty());
    assert!(out.truncated);
}

#[test]
fn absent_tiles_are_uncovered_not_failed() {
    let tile = TileId { z: 1, x: 0, y: 0 };
    let out = extract(&[(tile, None)], &world(), MAX_POINTS);
    assert!(out.features.is_empty());
    assert_eq!(out.covered_tiles, 0);
    assert_eq!(out.total_tiles, 1);
    assert_eq!(out.failed_tiles, 0);
}

#[test]
fn wash_underlay_stitches_parent_water_into_a_gated_child() {
    // A z14 child with no landtype at all (gated wash) and a z13
    // parent holding a lake in the child's quadrant: after the
    // underlay the extract must see the water, in the child tile.
    let child = TileId { z: 14, x: 8192, y: 8192 };
    let (px, py, _, _) = crate::tile::geometry::parent_of(child.x, child.y);
    let parent_tile = TileId { z: 13, x: px, y: py };
    let mut child_body = Body::new(4096);
    let mut roads = BodyLayer::new(LAYER_ROADS);
    push(&mut roads, feat(kind("major_road"), GEOM_LINE), &[(100, 100), (3900, 3900)]);
    child_body.layers.push(roads);
    let mut parent_body = Body::new(4096);
    let mut land = BodyLayer::new(LAYER_LANDTYPE);
    push(&mut land, feat(kind("lake"), GEOM_POLYGON), &[(0, 0), (2048, 0), (2048, 2048), (0, 2048)]);
    parent_body.layers.push(land);
    assert!(crate::tile::geometry::needs_wash_underlay(&child_body, child.z));
    let mut pairs = vec![(child, Some(child_body))];
    let parents = vec![(parent_tile, Some(Ok(parent_body)))];
    let mut failed = 0usize;
    apply_wash_underlay(&mut pairs, &parents, &mut failed);
    assert_eq!(failed, 0);
    let refs: Vec<(TileId, Option<&Body>)> =
        pairs.iter().map(|(t, b)| (*t, b.as_ref())).collect();
    let out = extract(&refs, &world(), MAX_POINTS);
    let water: Vec<&GeometryFeature> =
        out.features.iter().filter(|f| f.kind == KIND_WATER).collect();
    assert_eq!(water.len(), 1, "the parent lake reaches the child: {:?}", out.features);
    assert!(water[0].closed);
    // A failed parent fails the child rather than inventing ocean.
    let mut pairs = vec![(child, Some(Body::new(4096)))];
    let parents = vec![(parent_tile, Some(Err(())))];
    let mut failed = 0usize;
    apply_wash_underlay(&mut pairs, &parents, &mut failed);
    assert_eq!(failed, 1);
    assert!(pairs[0].1.is_none());
}

#[test]
fn tile_selection_and_zoom_are_bounded() {
    // The whole world at z15 would be billions of tiles; the zoom
    // steps out until the 25-tile cap holds.
    let (z, stepped) = choose_zoom(0, 14, &world());
    assert!(stepped);
    assert!(tiles_for_bbox(-180.0, -85.0, 180.0, 85.0, z).len() <= MAX_TILES);
    // A street bbox keeps the archive's deepest zoom.
    let q = GeometryQuery { west: -0.001, south: 51.5, east: 0.001, north: 51.502, kinds_mask: KIND_ALL, max_features: 16 };
    let (z, stepped) = choose_zoom(0, 14, &q);
    assert_eq!(z, 14);
    assert!(!stepped);
    assert!(!tiles_for_bbox(q.west, q.south, q.east, q.north, z).is_empty());
    // A degenerate bbox selects nothing.
    assert!(tiles_for_bbox(5.0, 5.0, 5.0, 6.0, 10).is_empty());
}
