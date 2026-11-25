//! Bounded basemap-geometry queries for Kotlin consumers (the watch snapshot).
//!
//! The Vulkan renderer decodes `.mamaps` bodies natively and, until now,
//! nothing crossed back to Kotlin except pixels and label picks — so a
//! Kotlin consumer that needs actual road/water/building linework (the
//! Maps wear snapshot) had to declare those kinds degraded. This module
//! is the missing read path. It is deliberately **not** a second planet
//! parser: it opens the archive through the same
//! [`tilecodec::mamaps::read::TileSource`] the tile workers serve from
//! (shared leaf cache, batch body fetch, overlay-aware), selects the
//! tiles covering a bounding box with the same slippy-map maths the
//! renderer uses, and walks the decoded [`Body`] layers the tessellator
//! walks — `roads` lines, water features on `landtype`, `buildings`
//! polygons. No tessellation, no Vulkan, no frame path: a query runs on
//! its own archive view, off the render thread, and can never contend
//! with a frame. At the deepest zoom it also reuses the workers' wash
//! underlay (the z13 parent's water stitched into a gated z14 body,
//! `geometry::wash_overzoom`), so water does not vanish at street zoom.
//!
//! # Bounds (the performance discipline)
//!
//! * **Viewport-bounded.** Only tiles intersecting the query bbox are
//!   fetched, at the archive's own deepest zoom capped at
//!   [`QUERY_ZOOM_CAP`] (street detail without z16+ tile fan-out), and
//!   the tile count is capped at [`MAX_TILES`] by stepping zoom out
//!   until the bbox fits — a whole-continent bbox degrades to coarse
//!   tiles rather than thousands of fetches.
//! * **Feature/point-capped.** At most `max_features` features and
//!   [`MAX_POINTS`] points cross back, in wear priority order
//!   (road, path, water, building — the order the watch snapshot itself
//!   keeps under its byte cap), tiles centre-first. A feature that would
//!   exceed the remaining point budget is skipped whole (never cut
//!   mid-ring, which would corrupt a polygon) and the result is flagged
//!   `truncated` so the caller can say so.
//! * **Quantized output.** Points cross as lon/lat degrees; the JNI
//!   layer re-encodes them as E7 integers, and the Kotlin side's wear
//!   consumer quantizes to its 2 m grid. At region scale the double
//!   round-trip is far below that grid.
//!
//! # Honesty
//!
//! A tile the archive does not hold is `covered == false` ground, not an
//! error: most of a coastal viewport is ocean the writer never stored.
//! [`GeometryResult::covered_tiles`] counts bodies actually decoded, so
//! a caller can tell "scanned, genuinely nothing here" (covered > 0,
//! no features) from "nothing was scanned" (covered == 0). A query over
//! a present archive whose every tile failed to decode reports those in
//! `failed_tiles`; the JNI layer turns covered==0 + failures into its
//! UNAVAILABLE answer rather than a fake empty map.

use super::select::TileId;
use tilecodec::mamaps::body::{Body, GEOM_LINE, GEOM_POLYGON};
use tilecodec::mamaps::dict::{self, LAYER_BUILDINGS, LAYER_LANDTYPE, LAYER_ROADS, NONE};
use tilecodec::mamaps::read::TileSource;

/// Kind bit: a drivable/walkable road line (`roads` layer, not a path).
pub const KIND_ROAD: u32 = 1 << 0;
/// Kind bit: a path (`roads` layer feature whose kind is `path`).
pub const KIND_PATH: u32 = 1 << 1;
/// Kind bit: water — a polygon (lake/ocean/…) or line (river/stream)
/// on the merged `landtype` layer whose kind the style paints as water.
pub const KIND_WATER: u32 = 1 << 2;
/// Kind bit: a building outline (`buildings` layer polygon).
pub const KIND_BUILDING: u32 = 1 << 3;
/// Every kind this query can return.
pub const KIND_ALL: u32 = KIND_ROAD | KIND_PATH | KIND_WATER | KIND_BUILDING;

/// Deepest zoom a geometry query reads, however deep the archive goes.
///
/// The wear region is ~1.2 km across; z15 tiles are ~1.2 km at the
/// equator, so this keeps a region to a handful of tiles while still
/// reading street-level bodies. The archive's own `max_zoom` wins when
/// it is shallower (the published planets stop at z14).
pub const QUERY_ZOOM_CAP: u8 = 15;

/// Most tiles one query fetches. The zoom steps out until the bbox
/// fits this, so the bound holds for any bbox a caller passes.
pub const MAX_TILES: usize = 25;

/// Most points one query returns, across every feature.
///
/// A dense z14/z15 city tile carries on the order of a few thousand
/// road/building points; this holds several such tiles while keeping
/// the JNI string payload in the tens of KB — the wear snapshot's own
/// cap is 48 KB after simplification, and this feeds it, it does not
/// replace it.
pub const MAX_POINTS: usize = 8192;

/// Hard ceiling on `max_features`, however much a caller asks for.
pub const MAX_FEATURES: usize = 512;

/// The `landtype` kinds the style paints as water — the same list the
/// terrain clip uses (`tile::geometry::terrain::WATER_KINDS`), matched
/// by name so an appended dictionary kind cannot silently drift ids.
const WATER_KINDS: [&str; 12] = [
    "ocean", "sea", "lake", "water", "river", "stream", "bay", "strait", "fjord", "reef", "canal",
    "dock",
];

/// `roads`-layer kinds that are not roads a watch should draw as one:
/// rail geometry belongs to the transit styling, and a ferry/aerialway
/// line is a route across water/air, not a street.
const NON_ROAD_KINDS: [&str; 3] = ["rail", "ferry", "aerialway"];

/// One extracted feature: a polyline or one polygon ring/part.
#[derive(Debug, Clone, PartialEq)]
pub struct GeometryFeature {
    /// One of the `KIND_*` bits, as a single value (not a mask).
    pub kind: u32,
    /// The feature's class: the road's `kind_detail` (motorway,
    /// residential, …) when it carries one, else its `kind` name
    /// (major_road, lake, building, …). Empty when the feature is
    /// kind-less.
    pub class: String,
    /// True for a polygon ring (wear fills it), false for a line.
    pub closed: bool,
    /// `(lon, lat)` degrees, in body order.
    pub points: Vec<(f64, f64)>,
}

/// The outcome of one query.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct GeometryResult {
    pub features: Vec<GeometryFeature>,
    /// Tiles the archive held and this query decoded.
    pub covered_tiles: usize,
    /// Tiles covering the bbox at the chosen zoom.
    pub total_tiles: usize,
    /// Tiles whose decode failed (transport/corrupt), as opposed to
    /// tiles the archive simply does not hold.
    pub failed_tiles: usize,
    /// True when a cap (features, points or tile-zoom step-out) kept
    /// geometry back.
    pub truncated: bool,
}

/// A query: geographic bbox, kind mask, feature cap.
#[derive(Debug, Clone, Copy)]
pub struct GeometryQuery {
    pub west: f64,
    pub south: f64,
    pub east: f64,
    pub north: f64,
    pub kinds_mask: u32,
    pub max_features: usize,
}

fn kind_name(kind: u16) -> Option<&'static str> {
    if kind == NONE {
        return None;
    }
    dict::KINDS.get((kind - 1) as usize).copied()
}

fn detail_name(detail: u16) -> Option<&'static str> {
    if detail == NONE {
        return None;
    }
    dict::DETAILS.get((detail - 1) as usize).copied()
}

fn is_water_kind(kind: u16) -> bool {
    kind_name(kind).is_some_and(|name| WATER_KINDS.contains(&name))
}

/// Longitude of tile-local point `px` (extent units) on tile `tile`.
fn point_lon(tile: TileId, extent: u16, px: i16) -> f64 {
    let n = (1u64 << tile.z) as f64;
    (tile.x as f64 + px as f64 / extent.max(1) as f64) / n * 360.0 - 180.0
}

/// Latitude of tile-local point `py` (extent units) on tile `tile`:
/// the inverse Web-Mercator the renderer's camera uses.
fn point_lat(tile: TileId, extent: u16, py: i16) -> f64 {
    let n = (1u64 << tile.z) as f64;
    let y = tile.y as f64 + py as f64 / extent.max(1) as f64;
    let m = std::f64::consts::PI - 2.0 * std::f64::consts::PI * y / n;
    m.sinh().atan().to_degrees()
}

/// The tile containing `(lat, lon)` at `z` (slippy-map, clamped).
fn tile_of(lat: f64, lon: f64, z: u8) -> (u32, u32) {
    let n = (1u64 << z) as f64;
    let max = n as u32 - 1;
    let lat = lat.clamp(-85.051_128_78, 85.051_128_78);
    let x = ((lon + 180.0) / 360.0 * n).floor();
    let lat_r = lat.to_radians();
    let y = ((1.0 - (lat_r.tan() + 1.0 / lat_r.cos()).ln() / std::f64::consts::PI) / 2.0 * n)
        .floor();
    (
        (x as u32).min(max),
        (y as u32).min(max),
    )
}

/// Tiles covering a bbox at `z`, centre-first (nearest tile centre to
/// the bbox centre first) so a capped query keeps the middle of the
/// region — the wear snapshot is centre-first for the same reason.
pub fn tiles_for_bbox(west: f64, south: f64, east: f64, north: f64, z: u8) -> Vec<TileId> {
    if !(west < east && south < north) {
        return Vec::new();
    }
    let (x0, y1) = tile_of(south, west, z);
    let (x1, y0) = tile_of(north, east, z);
    let (cx, cy) = ((west + east) / 2.0, (south + north) / 2.0);
    let mut tiles: Vec<(f64, TileId)> = Vec::new();
    for x in x0..=x1 {
        for y in y0..=y1 {
            let t = TileId { z, x, y };
            // Distance from the bbox centre to this tile's centre, in
            // tile units — only an ordering key, never a measurement.
            let tx = point_lon(t, 1, 0) - cx;
            let ty = point_lat(t, 1, 0) - cy;
            tiles.push((tx * tx + ty * ty, t));
        }
    }
    tiles.sort_by(|a, b| a.0.total_cmp(&b.0));
    tiles.into_iter().map(|(_, t)| t).collect()
}

/// How many tiles cover a bbox at `z`, from its corners alone — O(1)
/// at any zoom, where materialising the tile list for a whole-world
/// bbox at z14 would be hundreds of millions of entries.
fn tile_count(west: f64, south: f64, east: f64, north: f64, z: u8) -> u64 {
    if !(west < east && south < north) {
        return 0;
    }
    let (x0, y1) = tile_of(south, west, z);
    let (x1, y0) = tile_of(north, east, z);
    (x1 as u64 - x0 as u64 + 1) * (y1 as u64 - y0 as u64 + 1)
}

/// The zoom a query reads: the archive's deepest (capped at
/// [`QUERY_ZOOM_CAP`]), stepped out until the bbox needs at most
/// [`MAX_TILES`] tiles. `truncated` reports that a step-out happened,
/// i.e. the caller is reading coarser bodies than the archive holds.
pub fn choose_zoom(min_zoom: u8, max_zoom: u8, q: &GeometryQuery) -> (u8, bool) {
    let mut z = max_zoom.min(QUERY_ZOOM_CAP).max(min_zoom);
    let mut stepped_out = false;
    while z > min_zoom && tile_count(q.west, q.south, q.east, q.north, z) > MAX_TILES as u64 {
        z -= 1;
        stepped_out = true;
    }
    (z, stepped_out)
}

/// Extract the requested kinds from already-decoded bodies.
///
/// `bodies` pairs each tile with its decoded body (`None` = the archive
/// does not hold it). Pure: the same function the JNI path feeds with
/// batch-fetched bodies is what the unit tests feed by hand.
pub fn extract(
    bodies: &[(TileId, Option<&Body>)],
    q: &GeometryQuery,
    max_points: usize,
) -> GeometryResult {
    let mut out = GeometryResult {
        total_tiles: bodies.len(),
        covered_tiles: bodies.iter().filter(|(_, b)| b.is_some()).count(),
        ..GeometryResult::default()
    };
    if q.kinds_mask & KIND_ALL == 0 || !(q.west < q.east && q.south < q.north) {
        return out;
    }
    let max_features = q.max_features.clamp(1, MAX_FEATURES);
    let mut points_left = max_points.min(MAX_POINTS);
    // Wear priority: roads survive a cap longest, buildings first to go.
    for want in [KIND_ROAD, KIND_PATH, KIND_WATER, KIND_BUILDING] {
        if q.kinds_mask & want == 0 {
            continue;
        }
        for (tile, body) in bodies {
            let Some(body) = body else { continue };
            let layer_id = match want {
                KIND_ROAD | KIND_PATH => LAYER_ROADS,
                KIND_WATER => LAYER_LANDTYPE,
                _ => LAYER_BUILDINGS,
            };
            let Some(layer) = body.layer(layer_id) else { continue };
            let extent = body.extent;
            for feature in &layer.features {
                let (kind, class) = match classify(want, feature) {
                    Some(v) => v,
                    None => continue,
                };
                for part in layer.parts_of(feature) {
                    let raw = layer.points(part);
                    if raw.len() < 2 {
                        continue;
                    }
                    let points: Vec<(f64, f64)> = raw
                        .iter()
                        .map(|&(px, py)| (point_lon(*tile, extent, px), point_lat(*tile, extent, py)))
                        .collect();
                    // Full parts only: a polygon cut to the bbox here
                    // would lose its outside ring points and fill
                    // wrong; the Kotlin extractor clips/simplifies.
                    if !part_intersects(&points, q) {
                        continue;
                    }
                    let closed = feature.geom_type == GEOM_POLYGON;
                    if closed && points.len() < 3 {
                        continue;
                    }
                    if out.features.len() >= max_features || points.len() > points_left {
                        out.truncated = true;
                        continue;
                    }
                    points_left -= points.len();
                    out.features.push(GeometryFeature {
                        kind,
                        class: class.clone(),
                        closed,
                        points,
                    });
                }
            }
        }
    }
    out
}

/// Whether one part belongs in the `want` group, and its class string.
fn classify(want: u32, feature: &tilecodec::mamaps::body::Feature) -> Option<(u32, String)> {
    let name = kind_name(feature.kind).unwrap_or("");
    match want {
        KIND_ROAD | KIND_PATH => {
            if feature.geom_type != GEOM_LINE || NON_ROAD_KINDS.contains(&name) {
                return None;
            }
            let is_path = name == "path";
            if (want == KIND_PATH) != is_path {
                return None;
            }
            let class = detail_name(feature.kind_detail)
                .unwrap_or(name)
                .to_string();
            Some((want, class))
        }
        KIND_WATER => {
            if !is_water_kind(feature.kind) {
                return None;
            }
            if feature.geom_type != GEOM_POLYGON && feature.geom_type != GEOM_LINE {
                return None;
            }
            Some((KIND_WATER, name.to_string()))
        }
        _ => {
            if feature.geom_type != GEOM_POLYGON {
                return None;
            }
            Some((KIND_BUILDING, name.to_string()))
        }
    }
}

fn part_intersects(points: &[(f64, f64)], q: &GeometryQuery) -> bool {
    let (mut w, mut s, mut e, mut n) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
    for &(lon, lat) in points {
        w = w.min(lon);
        e = e.max(lon);
        s = s.min(lat);
        n = n.max(lat);
    }
    w <= q.east && e >= q.west && s <= q.north && n >= q.south
}

/// Stitch the parent wash into bodies that need it, in place.
///
/// At the archive's deepest zoom the `landtype` wash is gated: a z14
/// body may carry no water polygon at all, and the renderer reads it
/// from its z13 parent instead (see `geometry::wash_overzoom`). A
/// geometry query at that zoom must do the same or lakes and
/// coastlines would silently vanish — reusing the workers' own
/// `needs_wash_underlay`/`parent_of`/`stitch_parent_wash`, with
/// `parents` holding each needed parent's fetch result. A parent that
/// failed fails its child (counted in `failed`), exactly as the
/// worker fails the tile for retry rather than drawing ocean for land;
/// a parent the archive does not hold leaves the child as it is.
fn apply_wash_underlay(
    pairs: &mut [(TileId, Option<Body>)],
    parents: &[(TileId, Option<Result<Body, ()>>)],
    failed: &mut usize,
) {
    use super::geometry::{needs_wash_underlay, parent_of, stitch_parent_wash};
    for (tile, slot) in pairs.iter_mut() {
        let Some(body) = slot.as_ref() else { continue };
        if !needs_wash_underlay(body, tile.z) {
            continue;
        }
        let (px, py, qx, qy) = parent_of(tile.x, tile.y);
        let parent = parents
            .iter()
            .find(|(t, _)| t.z == tile.z.saturating_sub(1) && t.x == px && t.y == py)
            .map(|(_, r)| r);
        match parent {
            Some(Some(Ok(parent_body))) => {
                *slot = Some(stitch_parent_wash(body, parent_body, qx, qy));
            }
            Some(Some(Err(()))) => {
                *failed += 1;
                *slot = None;
            }
            // Parent absent (or never fetched): the child stands.
            _ => {}
        }
    }
}

/// Run one query against an archive source: choose the zoom, batch-fetch
/// the covering tiles through the source's existing `tiles()` wave, and
/// extract. This is the entry the JNI bridge calls; it allocates one
/// archive view per call and never touches a renderer handle.
pub fn query_geometry<T: TileSource>(source: &T, q: &GeometryQuery) -> GeometryResult {
    let header = source.header();
    let (z, stepped_out) = choose_zoom(header.min_zoom, header.max_zoom, q);
    let tiles = tiles_for_bbox(q.west, q.south, q.east, q.north, z);
    if tiles.is_empty() {
        return GeometryResult { truncated: stepped_out, ..GeometryResult::default() };
    }
    let coords: Vec<(u8, u32, u32)> = tiles.iter().map(|t| (t.z, t.x, t.y)).collect();
    let fetched = source.tiles(&coords);
    let mut failed = 0usize;
    let mut pairs: Vec<(TileId, Option<Body>)> = tiles
        .iter()
        .copied()
        .zip(fetched)
        .map(|(tile, res)| match res {
            Ok(body) => (tile, body),
            Err(_) => {
                failed += 1;
                (tile, None)
            }
        })
        .collect();
    // Wash underlay (deepest-zoom tiles only): fetch each distinct
    // parent once, then stitch — the workers' wave-1.5 discipline.
    if z > header.min_zoom {
        use super::geometry::{needs_wash_underlay, parent_of};
        let mut distinct: Vec<(u8, u32, u32)> = Vec::new();
        for (tile, body) in &pairs {
            if let Some(body) = body {
                if needs_wash_underlay(body, tile.z) {
                    let (px, py, _, _) = parent_of(tile.x, tile.y);
                    let parent = (tile.z - 1, px, py);
                    if !distinct.contains(&parent) {
                        distinct.push(parent);
                    }
                }
            }
        }
        if !distinct.is_empty() {
            let fetched_parents = source.tiles(&distinct);
            let parents: Vec<(TileId, Option<Result<Body, ()>>)> = distinct
                .iter()
                .map(|&(pz, px, py)| TileId { z: pz, x: px, y: py })
                .zip(fetched_parents)
                .map(|(tile, res)| {
                    let mapped = match res {
                        Ok(Some(body)) => Some(Ok(body)),
                        Ok(None) => None,
                        Err(_) => Some(Err(())),
                    };
                    (tile, mapped)
                })
                .collect();
            apply_wash_underlay(&mut pairs, &parents, &mut failed);
        }
    }
    let refs: Vec<(TileId, Option<&Body>)> =
        pairs.iter().map(|(t, b)| (*t, b.as_ref())).collect();
    let mut out = extract(&refs, q, MAX_POINTS);
    out.failed_tiles = failed;
    out.truncated = out.truncated || stepped_out;
    out
}

#[cfg(test)]
mod tests;
