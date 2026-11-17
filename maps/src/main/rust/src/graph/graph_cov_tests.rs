//! Synthetic-graph tests for the loaders and table readers in graph_part2/2b/3
//! and graph_zsec. Graphs are built byte-by-byte in the v6 (raw) layout the
//! generator writes, so expected values are known by construction.
use super::*;
use std::collections::BTreeMap;

pub struct Spec {
    pub nodes: Vec<(i32, i32)>,                    // (lat_e7, lon_e7)
    pub edges: Vec<(u32, u32, u8, u8, u32)>,       // (src, dst, type, limit, dist_mm)
    pub escaped: Vec<u64>,                         // edge indices via the escape table
    pub names: BTreeMap<u64, u32>,                 // edge idx -> pool offset
    pub pool: Vec<u8>,
    pub geom: BTreeMap<u64, Vec<(i16, i16)>>,      // edge idx -> interior deltas
    pub lanes: Option<Vec<u8>>,
    pub elevation: Option<Vec<i16>>,
}

fn pad8(v: &mut Vec<u8>) {
    while v.len() % 8 != 0 {
        v.push(0);
    }
}
fn bitmap(edge_count: u64, present: &[u64]) -> Vec<u8> {
    let mut out = Vec::new();
    let blocks = edge_count.div_ceil(RANK_BLOCK_BYTES * 8);
    let mut rank = vec![0u64; (blocks + 1) as usize];
    let mut bytes = vec![0u8; edge_count.div_ceil(8) as usize];
    for &i in present {
        bytes[(i / 8) as usize] |= 1 << (i % 8);
    }
    let mut run = 0u64;
    for b in 0..blocks as usize {
        rank[b] = run;
        let (lo, hi) = (b * RANK_BLOCK_BYTES as usize, ((b + 1) * RANK_BLOCK_BYTES as usize).min(bytes.len()));
        run += bytes[lo..hi].iter().map(|x| u64::from(x.count_ones())).sum::<u64>();
    }
    rank[blocks as usize] = run;
    for r in rank {
        out.extend_from_slice(&r.to_le_bytes());
    }
    out.extend_from_slice(&bytes);
    out
}

/// The four mandatory section payloads (+ optionals) for `spec`, v6 layout.
pub fn payloads(spec: &Spec) -> (Vec<u8>, Vec<u8>, Vec<u8>, Vec<u8>) {
    let n = spec.nodes.len() as u32;
    let e = spec.edges.len() as u64;
    let mut edges: Vec<(usize, &(u32, u32, u8, u8, u32))> = spec.edges.iter().enumerate().collect();
    edges.sort_by_key(|(_, x)| x.0);
    let mut ptr = vec![0u32; spec.nodes.len() + 1];
    for (_, x) in &edges {
        ptr[x.0 as usize + 1] += 1;
    }
    for i in 0..spec.nodes.len() {
        ptr[i + 1] += ptr[i];
    }
    let mut nodes_b = Vec::new();
    for (i, (lat, lon)) in spec.nodes.iter().enumerate() {
        nodes_b.extend_from_slice(&lat.to_le_bytes());
        nodes_b.extend_from_slice(&lon.to_le_bytes());
        nodes_b.extend_from_slice(&ptr[i].to_le_bytes());
    }
    nodes_b.extend_from_slice(&0i32.to_le_bytes());
    nodes_b.extend_from_slice(&0i32.to_le_bytes());
    nodes_b.extend_from_slice(&(e as u32).to_le_bytes());

    let mut edges_b = Vec::new();
    let mut esc_rows: Vec<(u32, u32, u32)> = Vec::new();
    for (idx, (s, t, ty, lim, dist)) in &edges {
        if spec.escaped.contains(&(*idx as u64)) {
            edges_b.extend_from_slice(&i16::MIN.to_le_bytes());
            edges_b.extend_from_slice(&dist.to_le_bytes()[..3]);
            esc_rows.push((*idx as u32, *t, *dist));
        } else {
            edges_b.extend_from_slice(&((*t as i64 - *s as i64) as i16).to_le_bytes());
            edges_b.extend_from_slice(&dist.to_le_bytes()[..3]);
        }
        edges_b.push(*ty);
        edges_b.push(*lim);
    }
    pad8(&mut edges_b);
    let nblocks = e.div_ceil(ESCAPE_BLOCK) + 1;
    for b in 0..nblocks {
        let c = esc_rows.iter().filter(|r| u64::from(r.0) / ESCAPE_BLOCK < b).count();
        edges_b.extend_from_slice(&(c as u32).to_le_bytes());
    }
    for (idx, t, d) in &esc_rows {
        edges_b.extend_from_slice(&idx.to_le_bytes());
        edges_b.extend_from_slice(&t.to_le_bytes());
        edges_b.extend_from_slice(&d.to_le_bytes());
    }
    pad8(&mut edges_b);
    let named: Vec<u64> = spec.names.keys().copied().collect();
    edges_b.extend_from_slice(&bitmap(e, &named));
    for idx in &named {
        edges_b.extend_from_slice(&spec.names[idx].to_le_bytes());
    }

    // intermediate: blob, then [rank|present] bitmap, coarse, within, G.
    let gidx: Vec<u64> = spec.geom.keys().copied().collect();
    let g = gidx.len() as u64;
    let mut blob = Vec::new();
    let mut offs = vec![0u64];
    for idx in &gidx {
        for (dl, dn) in &spec.geom[idx] {
            blob.extend_from_slice(&dl.to_le_bytes());
            blob.extend_from_slice(&dn.to_le_bytes());
        }
        offs.push(blob.len() as u64);
    }
    let mut inter = blob.clone();
    inter.extend_from_slice(&bitmap(e, &gidx));
    let coarse_n = g.div_ceil(INTERMEDIATE_BLOCK) + 1;
    let mut coarse = vec![0u64; coarse_n as usize];
    let mut within = vec![0u16; (g + 1) as usize];
    // offs are cumulative; each block's coarse entry is its first offset and
    // `within` is the remainder (test blobs are far below the u16 limit).
    for i in 0..=g as usize {
        let blk = i / INTERMEDIATE_BLOCK as usize;
        if i % INTERMEDIATE_BLOCK as usize == 0 {
            coarse[blk] = offs[i];
        }
        within[i] = (offs[i] - coarse[blk]) as u16;
    }
    for c in &coarse {
        inter.extend_from_slice(&c.to_le_bytes());
    }
    for w in &within {
        inter.extend_from_slice(&w.to_le_bytes());
    }
    inter.extend_from_slice(&g.to_le_bytes());

    let mut meta = Vec::new();
    meta.extend_from_slice(&GRAPH_MAGIC.to_le_bytes());
    meta.extend_from_slice(&GRAPH_VERSION_MIN.to_le_bytes());
    meta.extend_from_slice(&u64::from(n).to_le_bytes());
    meta.extend_from_slice(&e.to_le_bytes());
    meta.extend_from_slice(&(esc_rows.len() as u64).to_le_bytes());
    meta.extend_from_slice(&(named.len() as u64).to_le_bytes());
    (meta, nodes_b, edges_b, inter)
}

pub fn assemble(spec: &Spec) -> Graph {
    let (meta, nodes, edges, inter) = payloads(spec);
    let mut owned = vec![meta, nodes, edges, inter];
    if !spec.pool.is_empty() {
        owned.push(spec.pool.clone());
    }
    if let Some(l) = &spec.lanes {
        owned.push(l.clone());
    }
    if let Some(el) = &spec.elevation {
        owned.push(el.iter().flat_map(|v| v.to_le_bytes()).collect());
    }
    let at = |i: usize| unsafe { std::slice::from_raw_parts(owned[i].as_ptr(), owned[i].len()) };
    let (mut i, mut pool, mut lanes, mut elev) = (4usize, None, None, None);
    if !spec.pool.is_empty() {
        pool = Some(at(i));
        i += 1;
    }
    if spec.lanes.is_some() {
        lanes = Some(at(i));
        i += 1;
    }
    if spec.elevation.is_some() {
        elev = Some(at(i));
    }
    Graph::assemble(
        GraphSections { meta: at(0), nodes: at(1), edges: at(2), intermediate: at(3), road_names: pool, lanes, elevation: elev },
        GraphOwnership { nodes: None, edges: None, intermediate: None, road_names: None, lanes: None, elevation: None, archive: None, buffers: owned },
    )
    .expect("synthetic graph assembles")
}

fn base_spec() -> Spec {
    // 0:(0,0) 1:(0,100_000) 2:(100_000,100_000) 3:(100_000,0); a square + a
    // degree-0 node 4. Edges both ways around the square.
    let mut edges = Vec::new();
    for (s, t) in [(0, 1), (1, 0), (1, 2), (2, 1), (2, 3), (3, 2), (3, 0), (0, 3)] {
        edges.push((s, t, 5u8, 50u8, 11_114u32));
    }
    Spec {
        nodes: vec![(0, 0), (0, 100_000), (100_000, 100_000), (100_000, 0), (50_000, 50_000)],
        edges,
        escaped: vec![],
        names: BTreeMap::new(),
        pool: vec![],
        geom: BTreeMap::new(),
        lanes: None,
        elevation: None,
    }
}

#[test]
fn assemble_reads_nodes_edges_and_ranges() {
    let g = assemble(&base_spec());
    assert_eq!((g.node_count, g.edge_count), (5, 8));
    assert_eq!((g.node(1).lat_e7, g.node(1).lon_e7), (0, 100_000));
    assert_eq!(g.edge_range(0), (0, 2), "node 0 owns edges 0..2 after src sort");
    assert_eq!(g.edge_range(4), (8, 8), "degree-0 node has an empty range");
    assert_eq!(g.edge_range(3).1, 8, "last-but-one range ends at the sentinel");
    let e = g.edge(0, 0);
    assert_eq!((e.target, e.dist_mm, e.type_, e.speed_limit), (1, 11_114, 5, 50));
    assert!(g.edge_targets(0, 0, 1) && !g.edge_targets(0, 0, 2));
    assert_eq!(g.get_node(99).edge_ptr, 0, "out-of-range reads as the zero node");
    assert_eq!(g.get_node(5).lat_e7, 0, "the sentinel is not a real node");
    assert!(!g.has_elevation() && g.node_elevation(0) == 0 && g.node_elevation(99) == 0);
}

#[test]
fn escaped_edges_read_target_and_distance_from_the_table() {
    let mut s = base_spec();
    s.escaped = vec![0];
    let g = assemble(&s);
    let e = g.edge(0, 0);
    assert_eq!((e.target, e.dist_mm), (1, 11_114), "escape row, not the sentinel record");
    assert_eq!(g.escape_count, 1);
}

#[test]
fn names_lanes_and_elevation_read_through_their_side_tables() {
    let mut s = base_spec();
    s.pool = b"Main Street\0Oak Avenue\0".to_vec();
    s.names = BTreeMap::from([(0u64, 0u32), (3u64, 12u32)]);
    // lanes: edge 1 has masks [through, left|right], edge 6 has [through].
    let mut lanes = Vec::new();
    lanes.extend_from_slice(&2u32.to_le_bytes());
    for (idx, off) in [(1u32, 0u32), (6, 4), (0, 6)] {
        lanes.extend_from_slice(&idx.to_le_bytes());
        lanes.extend_from_slice(&off.to_le_bytes());
    }
    for m in [2u16, 36, 2] {
        lanes.extend_from_slice(&m.to_le_bytes());
    }
    s.lanes = Some(lanes);
    s.elevation = Some(vec![10, 20, 30, 40, 50]);
    let g = assemble(&s);
    assert_eq!(g.edge_name_offset(0), Some(0));
    assert_eq!(g.edge_name_offset(3), Some(12));
    assert_eq!(g.edge_name_offset(1), None);
    assert_eq!(g.road_name(0).as_deref(), Some("Main Street"));
    assert_eq!(g.road_name(12).as_deref(), Some("Oak Avenue"));
    assert_eq!(g.road_name(NO_NAME), None);
    assert_eq!(g.road_name(10_000), None);
    assert_eq!(g.edge_lane_masks(1), Some(vec![2, 36]));
    assert_eq!(g.edge_lane_masks(6), Some(vec![2]));
    assert_eq!(g.edge_lane_masks(0), None);
    assert_eq!(g.edge_lane_masks(10_000), None);
    assert!(g.has_elevation());
    assert_eq!(g.node_elevation(2), 30);
}

#[test]
fn edge_geometry_decodes_deltas_and_reverses_for_the_flagged_twin() {
    let mut s = base_spec();
    // Edge 0 (0->1) stores one interior point halfway north of the chord.
    s.geom = BTreeMap::from([(0u64, vec![(30_000i16, 30_000i16)])]);
    let g = assemble(&s);
    let mut out = [LatLon { lat_e7: 0, lon_e7: 0 }; 256];
    let (cnt, rev) = g.get_edge_coordinates_from(0, 0, &mut out).unwrap();
    assert!(!rev && cnt == 3);
    assert_eq!((out[0].lat_e7, out[0].lon_e7), (0, 0), "seeded at the source");
    assert_eq!((out[1].lat_e7, out[1].lon_e7), (30_000, 30_000), "interior delta");
    assert_eq!((out[2].lat_e7, out[2].lon_e7), (0, 100_000), "terminated at the target");
    assert_eq!(get_pt_at(&out, cnt, false, 1).lat_e7, 30_000);
    // Sorted edge 2 (1->0) stores no geometry: callers get count 0, not a chord.
    let (cnt1, _) = g.get_edge_coordinates_from(1, 2, &mut out).unwrap();
    assert_eq!(cnt1, 0);
    assert!(g.get_edge_coordinates_from(0, 99, &mut out).is_none(), "past edge_count");
    // Flag the twin: sorted edge 2 (1->0, input index 1) gets
    // REVERSE_GEOMETRY_FLAG, so asking for it decodes edge 0's blob reversed.
    let mut s2 = base_spec();
    s2.edges[1].2 |= REVERSE_GEOMETRY_FLAG;
    s2.geom = BTreeMap::from([(0u64, vec![(30_000i16, 30_000i16)])]);
    let g2 = assemble(&s2);
    let (cnt2, rev2) = g2.get_edge_coordinates_from(1, 2, &mut out).unwrap();
    assert!(rev2 && cnt2 == 3);
    assert_eq!(get_pt_at(&out, cnt2, rev2, 0).lon_e7, 100_000, "reversed walk starts at node 1");
    assert_eq!(get_pt_at(&out, cnt2, rev2, 2).lon_e7, 0);
}

#[test]
fn spatial_codes_and_edge_owner_search() {
    assert_eq!(Graph::latlng_to_spatial(-90.0, -180.0), 0);
    assert_eq!(Graph::latlng_to_spatial(90.0, 180.0), u64::MAX);
    assert!(Graph::latlng_to_spatial(0.0, 0.0) < Graph::latlng_to_spatial(0.0, 0.001));
    assert!(Graph::latlng_to_spatial(0.0, 0.0) < Graph::latlng_to_spatial(0.001, 0.0));
    let g = assemble(&base_spec());
    // Node ranges (src-sorted): 0:[0,2) 1:[2,4) 2:[4,6) 3:[6,8) 4:[8,8).
    assert_eq!(g.find_node_idx_for_edge(0), 0);
    assert_eq!(g.find_node_idx_for_edge(1), 0);
    assert_eq!(g.find_node_idx_for_edge(2), 1);
    assert_eq!(g.find_node_idx_for_edge(7), 3);
    assert_eq!(g.find_node_idx_for_edge(8), 4, "largest ptr <= idx wins over degree-0 runs");
}

#[test]
fn load_reads_the_same_graph_from_files_and_refuses_corruption() {
    let spec = base_spec();
    let (meta, nodes, edges, inter) = payloads(&spec);
    let dir = std::env::temp_dir().join(format!("graph_load_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let write = |name: &str, b: &[u8]| std::fs::write(dir.join(name), b).unwrap();
    write("metadata.bin", &meta);
    write("nodes.bin", &nodes);
    write("edges.bin", &edges);
    write("intermediate.bin", &inter);
    let base = dir.to_str().unwrap().to_string();
    let g = Graph::load(&base).expect("multi-file load");
    assert_eq!((g.node_count, g.edge_count), (5, 8));
    assert_eq!(g.edge(0, 0).target, 1);
    assert!(Graph::load(&format!("{base}/")).is_some(), "trailing slash optional");
    // Bad magic, short metadata, wrong nodes length, missing file.
    let mut bad = meta.clone();
    bad[0] ^= 0xFF;
    write("metadata.bin", &bad);
    assert!(Graph::load(&base).is_none(), "bad magic refused");
    write("metadata.bin", &meta[..20]);
    assert!(Graph::load(&base).is_none(), "short metadata refused");
    write("metadata.bin", &meta);
    write("nodes.bin", &nodes[..12]);
    assert!(Graph::load(&base).is_none(), "nodes length must match the counts exactly");
    write("nodes.bin", &nodes);
    std::fs::remove_file(dir.join("intermediate.bin")).unwrap();
    assert!(Graph::load(&base).is_none(), "intermediate.bin is mandatory");
    write("intermediate.bin", &inter[..4]);
    assert!(Graph::load(&base).is_none(), "truncated intermediate refused");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn zsec_regions_round_trip_reads_and_cstrings() {
    let mut raw = Vec::new();
    raw.extend_from_slice(b"Main Street\0");
    raw.extend((0..70_000u32).map(|i| (i % 251) as u8));
    raw.extend_from_slice(b"Oak Avenue\0");
    let sec = tilecodec::zsec::write_zsec(&raw, 1024, 3);
    let z = ZsecRegion::parse(&sec).expect("zsec parses");
    assert_eq!(z.raw_len(), raw.len());
    let mut out = Vec::new();
    z.read_into(0, raw.len(), &mut out);
    assert_eq!(out, raw, "whole range across blocks");
    let mut tail = Vec::new();
    z.read_into(raw.len() - 5, 100, &mut tail);
    assert_eq!(tail, &raw[raw.len() - 5..], "reads clamp to the raw length");
    assert_eq!(z.cstr_at(0), b"Main Street");
    let oak = raw.len() - 11;
    assert_eq!(z.cstr_at(oak), b"Oak Avenue", "cstr at the very end");
    assert!(ZsecRegion::parse(b"nope").is_none());
    assert!(ZsecRegion::parse(&sec[..sec.len() - 1]).is_none(), "truncated refused");
    // v9 dispatchers: pre-v9 keeps raw bytes, v9 wraps them.
    let ptr = sec.as_ptr();
    assert!(matches!(ZsecRegion::v9_blob(ptr, sec.len() as u64, false), Some(None)));
    assert!(ZsecRegion::v9_blob(ptr, sec.len() as u64, true).is_some());
    assert!(matches!(ZsecRegion::v9_names(ptr, sec.len(), false), Some((None, _))));
    let (z2, raw_len) = ZsecRegion::v9_names(ptr, sec.len(), true).unwrap();
    assert!(z2.is_some() && raw_len == raw.len());
    assert!(matches!(ZsecRegion::v9_names(std::ptr::null(), 0, true), Some((None, 0))));
    let table = tilecodec::zsec::write_zsec(&8u32.to_le_bytes().repeat(2), 1024, 3);
    assert!(ZsecRegion::v9_name_offsets(&table, 0, 2, true).map_or(false, |z| z.is_some()));
    assert!(ZsecRegion::v9_name_offsets(&table, 0, 3, true).is_none(), "raw length must match named*4");
    assert!(ZsecRegion::v9_name_offsets(&[0u8; 8], 0, 2, false).map_or(false, |z| z.is_none()));
    assert!(ZsecRegion::v9_name_offsets(&[0u8; 7], 0, 2, false).is_none());
}

#[test]
fn load_reads_a_v7_blocked_pack_from_files() {
    let spec = base_spec();
    let (mut meta, nodes, edges, inter) = payloads(&spec);
    meta[4..8].copy_from_slice(&tilecodec::mamaps::archive::GRAPH_VERSION_BLOCKED.to_le_bytes());
    let nodes_b = tilecodec::blocked::write_blocked(&nodes, 12);
    let rec_end = 7 * spec.edges.len();
    let mut edges_b = tilecodec::blocked::write_blocked(&edges[..rec_end], 7);
    while edges_b.len() % 8 != 0 {
        edges_b.push(0);
    }
    edges_b.extend_from_slice(&edges[rec_end..]);
    let dir = std::env::temp_dir().join(format!("graph_load_v7_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    for (name, b) in [("metadata.bin", &meta), ("nodes.bin", &nodes_b), ("edges.bin", &edges_b), ("intermediate.bin", &inter)] {
        std::fs::write(dir.join(name), b).unwrap();
    }
    let g = Graph::load(dir.to_str().unwrap()).expect("v7 multi-file load");
    assert_eq!((g.node_count, g.edge_count), (5, 8));
    assert!(g.uses_blocked_sections());
    assert_eq!(g.edge(0, 0).target, 1, "records read through the inflate cache");
    assert_eq!(g.node(1).lon_e7, 100_000);
    let _ = std::fs::remove_dir_all(&dir);
}
