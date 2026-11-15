//! Synthetic `.mamaps` archives for the real-archive tests.
//!
//! The planet tests in `real_archive.rs` run against these archives by
//! default (temp dir, built in-test through the same `StreamWriter` the
//! generator uses); setting `MAMAPS_REAL_ARCHIVE` switches them back to
//! the real planet file. Nothing here reads fixtures from disk.

use std::path::{Path, PathBuf};

use tilecodec::mamaps::archive::{
    self as sidecar, ArchiveEntry, GRAPH_MAGIC, GRAPH_VERSION_MIN, TRANSIT_MAGIC,
};
use tilecodec::mamaps::body::{
    BuildingAttrs, Carriageway, Feature, Heightmap, LaneTurns, Layer as BodyLayer,
    MarkingConvention, Part, GEOM_LINE, GEOM_POINT, GEOM_POLYGON, NAME_NONE, WINDING_OUTER,
};
use tilecodec::mamaps::dict;
use tilecodec::mamaps::header::Header;
use tilecodec::mamaps::write::{Options, StreamWriter};
use tilecodec::pmtiles::tile_id;

/// Build id stamped on every synthetic archive (and its sidecar footer).
pub const BUILD_ID: u64 = 0x5eed_5eed_5eed_5eed;

const ENV: &str = "MAMAPS_REAL_ARCHIVE";

/// The planet archive `MAMAPS_REAL_ARCHIVE` names, if any.
pub fn planet_path() -> Option<PathBuf> {
    match std::env::var(ENV) {
        Ok(p) if !p.trim().is_empty() && Path::new(&p).is_file() => Some(PathBuf::from(p)),
        _ => None,
    }
}

/// The archive under test: the planet file when `MAMAPS_REAL_ARCHIVE`
/// names one (`planet == true`), else the synthetic archive `build`
/// writes to a temp file.
pub fn acquire(tag: &str, build: impl FnOnce(&Path)) -> (PathBuf, bool) {
    match planet_path() {
        Some(path) => (path, true),
        None => {
            let path = temp_path(tag);
            build(&path);
            (path, false)
        }
    }
}

/// Slippy-map tile containing (lat, lon) at zoom `z`.
pub fn tile_of(lat: f64, lon: f64, z: u8) -> (u32, u32) {
    let n = (1u64 << z) as f64;
    let x = ((lon + 180.0) / 360.0 * n).floor();
    let lat_r = lat.to_radians();
    let y = ((1.0 - (lat_r.tan() + 1.0 / lat_r.cos()).ln() / std::f64::consts::PI) / 2.0 * n).floor();
    let max = n as u32 - 1;
    ((x as u32).min(max), (y as u32).min(max))
}

/// A temp `.mamaps` path unique to this test process and `tag`.
pub fn temp_path(tag: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!(
        "synthetic-archive-{tag}-{}.mamaps",
        std::process::id()
    ));
    let _ = std::fs::remove_file(&path);
    path
}

fn kind(name: &str) -> u16 {
    map_renderer::style::kind_id(name).unwrap_or_else(|| panic!("schema kind {name}"))
}

fn feat(kind: u16, geom_type: u8, name_idx: u16) -> Feature {
    Feature {
        kind,
        kind_detail: dict::NONE,
        geom_type,
        flags: 0,
        name_idx,
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

/// A tile body carrying every basemap layer the style draws (all but
/// `traffic`, which the planet writer no longer emits either), plus the
/// optional side tables the reader decodes: turn lanes, carriageways,
/// building attrs, region links and a DEM heightmap peaking at `peak`
/// metres.
pub fn rich_body(peak: i32) -> tilecodec::mamaps::body::Body {
    let mut body = tilecodec::mamaps::body::Body::new(4096);
    body.names = vec![
        "Grid Street".to_string(),
        "Synthetic City".to_string(),
        "Cafe".to_string(),
    ];

    let mut landtype = BodyLayer::new(dict::LAYER_LANDTYPE);
    push(&mut landtype, feat(dict::NONE, GEOM_POLYGON, NAME_NONE), &[(0, 0), (4096, 0), (4096, 4096), (0, 4096)]);
    push(&mut landtype, feat(kind("lake"), GEOM_POLYGON, NAME_NONE), &[(500, 3000), (1100, 3000), (1100, 3400), (500, 3400)]);
    body.layers.push(landtype);

    let mut roads = BodyLayer::new(dict::LAYER_ROADS);
    let mut road = feat(kind("major_road"), GEOM_LINE, 1);
    road.lane_count = 2;
    push(&mut roads, road, &[(100, 100), (1500, 900), (2600, 1800), (3900, 2700)]);
    body.layers.push(roads);

    let mut boundaries = BodyLayer::new(dict::LAYER_BOUNDARIES);
    push(&mut boundaries, feat(kind("region_area"), GEOM_POLYGON, NAME_NONE), &[(200, 200), (3800, 200), (3800, 3800), (200, 3800)]);
    body.layers.push(boundaries);

    let mut buildings = BodyLayer::new(dict::LAYER_BUILDINGS);
    push(&mut buildings, feat(kind("building"), GEOM_POLYGON, NAME_NONE), &[(0, 0), (1000, 0), (1000, 1000), (0, 1000), (0, 0)]);
    body.layers.push(buildings);

    let mut places = BodyLayer::new(dict::LAYER_PLACES);
    push(&mut places, feat(kind("locality"), GEOM_POINT, 2), &[(2048, 2048)]);
    body.layers.push(places);

    let mut poi = BodyLayer::new(dict::LAYER_POI);
    push(&mut poi, feat(kind("cafe"), GEOM_POINT, 3), &[(1024, 1024)]);
    body.layers.push(poi);

    let mut transit = BodyLayer::new(dict::LAYER_TRANSIT);
    let mut line = feat(kind("rail"), GEOM_LINE, NAME_NONE);
    line.transit_color = 0x00E3_1B23;
    line.transit_lanes = 1;
    line.transit_taper = 255;
    push(&mut transit, line, &[(0, 4000), (4096, 200)]);
    body.layers.push(transit);

    let mut junction = BodyLayer::new(dict::LAYER_JUNCTION);
    push(&mut junction, feat(dict::NONE, GEOM_LINE, NAME_NONE), &[(0, 100), (500, 100), (1000, 300)]);
    body.layers.push(junction);

    let mut safety = BodyLayer::new(dict::LAYER_SAFETY);
    push(&mut safety, feat(kind("speed_camera"), GEOM_POINT, NAME_NONE), &[(3000, 3000)]);
    body.layers.push(safety);

    body.ids = vec![(dict::LAYER_BOUNDARIES, vec![4242])];
    body.turn_lanes = vec![(dict::LAYER_ROADS, vec![LaneTurns { forward: vec![0b0110], backward: Vec::new() }])];
    body.buildings = vec![(dict::LAYER_BUILDINGS, vec![BuildingAttrs {
        height: 120,
        min_height: 0,
        roof_height: 0,
        roof_shape: 0,
        roof_direction: 0,
        roof_orientation: 0,
        building_colour: 0,
        roof_colour: 0,
    }])];
    body.carriageways = vec![(dict::LAYER_ROADS, vec![Carriageway { forward: 1, backward: 1, solid_dividers: 0 }])];
    body.convention = Some(MarkingConvention { left_hand: false, yellow_centre: false });
    body.region_links = vec![(dict::LAYER_PLACES, vec![4242])];
    body.heightmap = Some(heightmap(4, 0, peak));
    body
}

/// A `dim` x `dim` heightmap rising by row from `lo` to `hi` metres.
pub fn heightmap(dim: u16, lo: i32, hi: i32) -> Heightmap {
    let mut samples = Vec::with_capacity(dim as usize * dim as usize);
    for row in 0..dim {
        let metres = if dim > 1 { lo + (hi - lo) * i32::from(row) / i32::from(dim - 1) } else { lo };
        for _ in 0..dim {
            samples.push((metres + 32768) as u16);
        }
    }
    Heightmap { dim, samples }
}

/// A rich body whose heightmap spans `lo..hi` metres (DEM tests).
pub fn dem_body(lo: i32, hi: i32) -> tilecodec::mamaps::body::Body {
    let mut body = rich_body(hi);
    body.heightmap = Some(heightmap(8, lo, hi));
    body
}

/// Write `tiles` (any order; the writer needs ascending tile ids) as a
/// tiles-only archive at `path` and return the path.
pub fn write_archive(path: &Path, tiles: &[(u8, u32, u32, tilecodec::mamaps::body::Body)]) -> PathBuf {
    let mut rows: Vec<(u64, &tilecodec::mamaps::body::Body)> = tiles
        .iter()
        .map(|(z, x, y, b)| (tile_id(*z, u64::from(*x), u64::from(*y)), b))
        .collect();
    rows.sort_by_key(|(id, _)| *id);
    // Distinct cities share the z0 tile; the writer wants each id once.
    rows.dedup_by_key(|(id, _)| *id);
    let mut writer = StreamWriter::new(Options {
        build_id: BUILD_ID,
        rings_validated: true,
        ..Options::default()
    })
    .expect("writer options");
    for (id, body) in rows {
        writer.append(id, body).expect("append tile");
    }
    writer.finish_to_path(path).expect("finish archive");
    path.to_path_buf()
}

fn graph_meta(node_count: u64, edge_count: u64) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&GRAPH_MAGIC.to_le_bytes());
    out.extend_from_slice(&GRAPH_VERSION_MIN.to_le_bytes());
    out.extend_from_slice(&node_count.to_le_bytes());
    out.extend_from_slice(&edge_count.to_le_bytes());
    out.extend_from_slice(&0u64.to_le_bytes());
    out.extend_from_slice(&0u64.to_le_bytes());
    out
}

fn poi_header(magic: &[u8; 4], count: u32, len: usize) -> Vec<u8> {
    let mut out = vec![0u8; len];
    out[0..4].copy_from_slice(magic);
    out[8..12].copy_from_slice(&count.to_le_bytes());
    out
}

/// The smallest payload set `ArchiveView` accepts for every kind the app
/// reads: a one-node graph, one POI record, and a header-only transit
/// pack. Counts are the validators' own arithmetic (see the tilecodec
/// archive tests, whose fixtures these mirror).
fn sidecar_payloads() -> Vec<(u8, Vec<u8>, u64)> {
    let mut transit = vec![0u8; 80 + 20 * 16];
    transit[0..4].copy_from_slice(&TRANSIT_MAGIC.to_le_bytes());
    transit[4..8].copy_from_slice(&7u32.to_le_bytes());
    transit[8..12].copy_from_slice(&20u32.to_le_bytes());
    vec![
        (sidecar::ARCHIVE_KIND_GRAPH_META, graph_meta(1, 0), 0),
        (sidecar::ARCHIVE_KIND_GRAPH_NODES, vec![0u8; 24], 1),
        (sidecar::ARCHIVE_KIND_GRAPH_EDGES, vec![0u8; 16], 0),
        (sidecar::ARCHIVE_KIND_GRAPH_INTERMEDIATE, vec![0u8; 26], 0),
        (sidecar::ARCHIVE_KIND_GRAPH_NAMES, b"\0Grid Street\0".to_vec(), 0),
        (sidecar::ARCHIVE_KIND_GRAPH_LANES, vec![0u8; 12], 0),
        (sidecar::ARCHIVE_KIND_GRAPH_ELEVATION, vec![0u8; 2], 0),
        (sidecar::ARCHIVE_KIND_POI_INDEX, vec![0u8; 14], 0),
        (sidecar::ARCHIVE_KIND_POI_NAMES, b"\0Cafe\0".to_vec(), 0),
        (sidecar::ARCHIVE_KIND_POI_ATTRS, poi_header(b"MAPA", 1, 12), 0),
        (sidecar::ARCHIVE_KIND_POI_SPATIAL, poi_header(b"PSP1", 1, 32), 0),
        (sidecar::ARCHIVE_KIND_POI_WORDS, poi_header(b"PNI1", 1, 16), 0),
        (sidecar::ARCHIVE_KIND_TRANSIT, transit, 0),
        (sidecar::ARCHIVE_KIND_GRAPH_OVERLAY, b"MOVL".to_vec(), 0),
        (sidecar::ARCHIVE_KIND_POI_REGION, poi_header(b"PRG1", 1, 20), 0),
    ]
}

/// Append the sidecar payloads to the tiles-only archive at `path`
/// (header `file_len` patched to cover the whole file).
pub fn append_sidecar(path: &Path) {
    let mut out = std::fs::read(path).expect("read tiles archive");
    let mut header = Header::parse(&out).expect("tile header parses");
    assert_eq!(header.build_id, BUILD_ID);
    while out.len() as u64 % sidecar::ARCHIVE_ALIGN != 0 {
        out.push(0);
    }
    let mut entries = Vec::new();
    for (kind, bytes, extra) in sidecar_payloads() {
        while out.len() as u64 % sidecar::ARCHIVE_ALIGN != 0 {
            out.push(0);
        }
        let offset = out.len() as u64;
        out.extend_from_slice(&bytes);
        entries.push(ArchiveEntry { kind, flags: 0, offset, len: bytes.len() as u64, extra });
    }
    while out.len() as u64 % sidecar::ARCHIVE_ALIGN != 0 {
        out.push(0);
    }
    let dir_offset = out.len() as u64;
    let (dir, footer) = sidecar::serialize_dir(&entries, BUILD_ID, dir_offset);
    out.extend_from_slice(&dir);
    out.extend_from_slice(&footer.serialize());
    header.file_len = out.len() as u64;
    let head = header.serialize();
    out[..head.len()].copy_from_slice(&head);
    std::fs::write(path, &out).expect("write full archive");
}
