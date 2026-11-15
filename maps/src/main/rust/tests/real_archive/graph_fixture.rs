//! A tiny routable graph (plus highway overlay) packed as a
//! single-archive `.mamaps` sidecar, for the synthetic real-archive
//! tests. Mirrors the container assembly in `src/graph_part5.rs` and
//! `src/transit_part9.rs` (which integration tests cannot link).
//!
//! Layout: a 3x3 node grid over San Francisco (rows south to north,
//! columns west to east), bidirectional edges between orthogonal
//! neighbours, every edge named, one edge carrying lane masks, an
//! elevation profile, and an identity highway overlay.

use std::path::PathBuf;

use tilecodec::mamaps::archive::{
    self as sidecar, ArchiveEntry, ArchiveFooter, GRAPH_MAGIC, GRAPH_VERSION_MIN,
};
use tilecodec::mamaps::header::Header;

/// Build id stamped on every synthetic archive (and its sidecar footer).
pub const BUILD_ID: u64 = 0x5eed_5eed_5eed_5eed;
/// Grid latitudes, south to north.
pub const LATS: [f64; 3] = [37.7694, 37.7825, 37.7955];
/// Grid longitudes, west to east.
pub const LONS: [f64; 3] = [-122.4862, -122.4399, -122.3937];
/// Road name every synthetic edge carries.
pub const ROAD_NAME: &str = "Grid Street";

/// `(lat, lon)` of a grid node.
pub fn node_lat_lon(id: usize) -> (f64, f64) {
    (LATS[id / 3], LONS[id % 3])
}

fn e7(deg: f64) -> i32 {
    (deg * 1e7).round() as i32
}

fn dist_mm(a: (f64, f64), b: (f64, f64)) -> u32 {
    let (la1, lo1) = (a.0.to_radians(), a.1.to_radians());
    let (la2, lo2) = (b.0.to_radians(), b.1.to_radians());
    let h = ((la2 - la1) / 2.0).sin().powi(2)
        + la1.cos() * la2.cos() * ((lo2 - lo1) / 2.0).sin().powi(2);
    (2.0 * 6_371_000_000.0 * h.sqrt().asin()).round() as u32
}

/// `(source, target, dist_mm)` for every directed edge, grouped by
/// source in ascending target order (the `edge_ptr` layout).
fn edges() -> Vec<(u32, u32, u32)> {
    let mut out = Vec::new();
    for id in 0..9usize {
        let (row, col) = (id / 3, id % 3);
        let mut targets = Vec::new();
        if col > 0 {
            targets.push(id - 1);
        }
        if col < 2 {
            targets.push(id + 1);
        }
        if row > 0 {
            targets.push(id - 3);
        }
        if row < 2 {
            targets.push(id + 3);
        }
        targets.sort_unstable();
        for target in targets {
            out.push((
                id as u32,
                target as u32,
                dist_mm(node_lat_lon(id), node_lat_lon(target)),
            ));
        }
    }
    out
}

fn meta(node_count: u64, edge_count: u64, named: u64) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&GRAPH_MAGIC.to_le_bytes());
    out.extend_from_slice(&GRAPH_VERSION_MIN.to_le_bytes());
    out.extend_from_slice(&node_count.to_le_bytes());
    out.extend_from_slice(&edge_count.to_le_bytes());
    out.extend_from_slice(&0u64.to_le_bytes());
    out.extend_from_slice(&named.to_le_bytes());
    out
}

fn nodes_payload(edges: &[(u32, u32, u32)]) -> Vec<u8> {
    let mut ptr = 0u32;
    let mut out = Vec::new();
    for id in 0..9usize {
        let (lat, lon) = node_lat_lon(id);
        out.extend_from_slice(&e7(lat).to_le_bytes());
        out.extend_from_slice(&e7(lon).to_le_bytes());
        out.extend_from_slice(&ptr.to_le_bytes());
        ptr += edges.iter().filter(|&&(s, _, _)| s as usize == id).count() as u32;
    }
    // Sentinel: edge_ptr == edge_count.
    out.extend_from_slice(&0i32.to_le_bytes());
    out.extend_from_slice(&0i32.to_le_bytes());
    out.extend_from_slice(&ptr.to_le_bytes());
    out
}

fn edges_payload(edges: &[(u32, u32, u32)]) -> Vec<u8> {
    let e = edges.len() as u64;
    let mut out = Vec::new();
    for &(source, target, dist) in edges {
        out.extend_from_slice(&(target as i32 - source as i32).to_le_bytes()[..2]);
        out.extend_from_slice(&dist.to_le_bytes()[..3]);
        out.push(5); // road type: drivable, walkable, bikeable
        out.push(50); // speed limit km/h
    }
    while out.len() as u64 % 8 != 0 {
        out.push(0);
    }
    // Escape index (E/1024 + 1 entries; total == 0 escapes), no rows.
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    while out.len() as u64 % 8 != 0 {
        out.push(0);
    }
    // Name bitmap: rank [0, E] then one present bit per edge (all named).
    out.extend_from_slice(&0u64.to_le_bytes());
    out.extend_from_slice(&e.to_le_bytes());
    out.extend(std::iter::repeat_n(0xFF, e.div_ceil(8) as usize));
    // One pool offset per named edge, in rank order (all the same name).
    for _ in 0..e {
        out.extend_from_slice(&1u32.to_le_bytes());
    }
    out
}

fn intermediate_payload(edge_count: u64) -> Vec<u8> {
    // G = 0: empty blob, then the zero trailer the counts describe.
    let bitmap = (edge_count.div_ceil(512) + 1) * 8 + edge_count.div_ceil(8);
    vec![0u8; (bitmap + 8 + 2 + 8) as usize]
}

fn lanes_payload() -> Vec<u8> {
    // One entry (edge 0, two masks) plus the sentinel.
    let mut out = Vec::new();
    out.extend_from_slice(&1u32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&4u32.to_le_bytes());
    out.extend_from_slice(&0x0002u16.to_le_bytes());
    out.extend_from_slice(&0x0004u16.to_le_bytes());
    out
}

fn elevation_payload() -> Vec<u8> {
    let mut out = Vec::new();
    for id in 0..9 {
        out.extend_from_slice(&(10 + id as i16).to_le_bytes());
    }
    out
}

fn overlay_payload(edges: &[(u32, u32, u32)]) -> Vec<u8> {
    let n = 9u64;
    let e = edges.len() as u64;
    let mut node_recs = Vec::new();
    let mut ptr = 0u32;
    for id in 0..9usize {
        let (lat, lon) = node_lat_lon(id);
        node_recs.extend_from_slice(&e7(lat).to_le_bytes());
        node_recs.extend_from_slice(&e7(lon).to_le_bytes());
        node_recs.extend_from_slice(&ptr.to_le_bytes());
        node_recs.extend_from_slice(&(id as u32).to_le_bytes());
        ptr += edges.iter().filter(|&&(s, _, _)| s as usize == id).count() as u32;
    }
    node_recs.extend_from_slice(&0i32.to_le_bytes());
    node_recs.extend_from_slice(&0i32.to_le_bytes());
    node_recs.extend_from_slice(&ptr.to_le_bytes());
    node_recs.extend_from_slice(&0u32.to_le_bytes());
    let nodes = tilecodec::blocked::write_blocked(&node_recs, 16);
    let mut edge_recs = Vec::new();
    for (idx, &(_, target, dist)) in edges.iter().enumerate() {
        edge_recs.extend_from_slice(&target.to_le_bytes());
        edge_recs.extend_from_slice(&dist.to_le_bytes());
        edge_recs.extend_from_slice(&(idx as u64).to_le_bytes());
        edge_recs.push(5);
        edge_recs.push(50);
        edge_recs.extend_from_slice(&0u16.to_le_bytes());
    }
    let overlay_edges = tilecodec::blocked::write_blocked(&edge_recs, 20);
    let mut out = Vec::new();
    out.extend_from_slice(&0x4C56_4F4Du32.to_le_bytes()); // "MOVL"
    out.extend_from_slice(&1u32.to_le_bytes());
    out.extend_from_slice(&n.to_le_bytes());
    out.extend_from_slice(&e.to_le_bytes());
    out.extend_from_slice(&n.to_le_bytes());
    out.extend_from_slice(&e.to_le_bytes());
    out.extend_from_slice(&(nodes.len() as u64).to_le_bytes());
    out.extend_from_slice(&(overlay_edges.len() as u64).to_le_bytes());
    out.extend_from_slice(&nodes);
    while out.len() as u64 % 8 != 0 {
        out.push(0);
    }
    out.extend_from_slice(&overlay_edges);
    while out.len() as u64 % 8 != 0 {
        out.push(0);
    }
    // Portals bitmap over the full nodes: every node is a portal.
    out.extend_from_slice(&0u64.to_le_bytes());
    out.extend_from_slice(&n.to_le_bytes());
    out.extend_from_slice(&[0xFF, 0x01]);
    out
}

fn test_header(file_len: u64) -> Header {
    Header {
        flags: 0,
        compression: 0,
        layer_count: 12,
        min_zoom: 0,
        max_zoom: 14,
        build_id: BUILD_ID,
        file_len,
        dict_offset: 128,
        dict_len: 64,
        leaf_entry_capacity: 4096,
        root_offset: 192,
        root_len: 32,
        leaf_count: 1,
        leaf_offset: 224,
        leaf_len: 16,
        data_offset: 240,
        data_len: 3856,
        tiles_addressed: 1,
        bodies_written: 1,
        min_lon_e7: 0,
        min_lat_e7: 0,
        max_lon_e7: 0,
        max_lat_e7: 0,
    }
}

/// Whole file: tile prefix (zeroes under a real serialized header) +
/// payloads laid out 8-aligned in kind order + directory + footer.
fn assemble(payloads: &[(u8, Vec<u8>, u64)]) -> Vec<u8> {
    let mut header = test_header(0);
    let mut out = vec![0u8; 4096];
    let mut entries = Vec::new();
    for (kind, bytes, extra) in payloads {
        while out.len() as u64 % sidecar::ARCHIVE_ALIGN != 0 {
            out.push(0);
        }
        let offset = out.len() as u64;
        out.extend_from_slice(bytes);
        entries.push(ArchiveEntry { kind: *kind, flags: 0, offset, len: bytes.len() as u64, extra: *extra });
    }
    while out.len() as u64 % sidecar::ARCHIVE_ALIGN != 0 {
        out.push(0);
    }
    let dir_offset = out.len() as u64;
    let (dir, _) = sidecar::serialize_dir(&entries, BUILD_ID, dir_offset);
    out.extend_from_slice(&dir);
    let footer = ArchiveFooter { dir_offset, dir_len: dir.len() as u64, build_id: BUILD_ID };
    out.extend_from_slice(&footer.serialize());
    header.file_len = out.len() as u64;
    let head = header.serialize();
    out[..head.len()].copy_from_slice(&head);
    out
}

/// The synthetic graph archive (graph sections + overlay, no tiles).
pub fn graph_archive() -> Vec<u8> {
    let edges = edges();
    let e = edges.len() as u64;
    assemble(&[
        (sidecar::ARCHIVE_KIND_GRAPH_META, meta(9, e, e), 0),
        (sidecar::ARCHIVE_KIND_GRAPH_NODES, nodes_payload(&edges), 9),
        (sidecar::ARCHIVE_KIND_GRAPH_EDGES, edges_payload(&edges), 0),
        (sidecar::ARCHIVE_KIND_GRAPH_INTERMEDIATE, intermediate_payload(e), 0),
        (sidecar::ARCHIVE_KIND_GRAPH_NAMES, format!("\0{ROAD_NAME}\0").into_bytes(), 0),
        (sidecar::ARCHIVE_KIND_GRAPH_LANES, lanes_payload(), 0),
        (sidecar::ARCHIVE_KIND_GRAPH_ELEVATION, elevation_payload(), 0),
        (sidecar::ARCHIVE_KIND_GRAPH_OVERLAY, overlay_payload(&edges), 0),
    ])
}

/// The synthetic transit archive (one TRIX section, no tiles).
pub fn transit_archive(pack: &[u8]) -> Vec<u8> {
    assemble(&[(sidecar::ARCHIVE_KIND_TRANSIT, pack.to_vec(), 0)])
}

/// Write `bytes` to a temp `.mamaps` file unique to this process.
pub fn write_temp(tag: &str, bytes: &[u8]) -> PathBuf {
    let path = std::env::temp_dir().join(format!("synthetic-{tag}-{}.mamaps", std::process::id()));
    std::fs::write(&path, bytes).expect("write synthetic archive");
    path
}
