// --- Highway overlay ---
//
// The motorway/trunk/primary subgraph of the full graph, renumbered densely in
// full-id order, so a long search reads a few contiguous blocks instead of one
// block per scattered arterial node. Written by `road_overlay` in
// `scripts/maps/osm_ingest`; the two layouts must agree.
//
// ```text
// [magic "MOVL" u32][version u32]
// [full_node_count u64][full_edge_count u64]
// [overlay_node_count u64 M][overlay_edge_count u64 K]
// [nodes_len u64][edges_len u64]
// nodes: ZBLK, 16 B (lat_e7 i32, lon_e7 i32, edge_ptr u32, full_node u32), M + 1
//        records, the sentinel's edge_ptr = K
// pad to 8
// edges: ZBLK, 20 B (target u32 overlay id, dist_mm u32, full_edge u64,
//        type u8, speed_limit u8, pad u16), K records, grouped by source
// pad to 8
// portals: EdgeBitmap over the full node ids; overlay id = rank(full id)
// ```

/// `"MOVL"`, little-endian.
pub const OVERLAY_MAGIC: u32 = 0x4C56_4F4D;
pub const OVERLAY_VERSION: u32 = 1;
const OVERLAY_HEADER_LEN: usize = 56;
const OVERLAY_NODE_LEN: usize = 16;
const OVERLAY_EDGE_LEN: usize = 20;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct OverlayNode {
    pub lat_e7: i32,
    pub lon_e7: i32,
    pub edge_ptr: u64,
    pub full_node: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct OverlayEdge {
    pub target: u32,
    pub dist_mm: u32,
    pub full_edge: u64,
    pub type_: u8,
    pub speed_limit: u8,
}

pub struct Overlay {
    _region: Option<MmapRegion>,
    _owned: Option<Vec<u8>>,
    pub node_count: u32,
    pub edge_count: u64,
    nodes: BlockedView,
    edges: BlockedView,
    portals: EdgeBitmap,
}

unsafe impl Send for Overlay {}
unsafe impl Sync for Overlay {}

impl Overlay {
    /// Parse an overlay over `bytes`, refusing one built for a different graph or
    /// whose tables do not match its counts. `bytes` must outlive the overlay; the
    /// owning region or buffer is attached by the caller.
    fn parse(bytes: &[u8], full_nodes: u32, full_edges: u64) -> Option<Overlay> {
        if bytes.len() < OVERLAY_HEADER_LEN {
            return None;
        }
        let u32_at = |o: usize| u32::from_le_bytes(bytes[o..o + 4].try_into().expect("4 bytes"));
        let u64_at = |o: usize| u64::from_le_bytes(bytes[o..o + 8].try_into().expect("8 bytes"));
        if u32_at(0) != OVERLAY_MAGIC || u32_at(4) != OVERLAY_VERSION {
            return None;
        }
        if u64_at(8) != u64::from(full_nodes) || u64_at(16) != full_edges {
            return None;
        }
        let (m, k) = (u64_at(24), u64_at(32));
        // Overlay ids live above the full ids in one u32 space, below the
        // 0xFFFF_FFFF "no node" sentinel.
        if u64::from(full_nodes) + m >= u64::from(u32::MAX) {
            return None;
        }
        let (nodes_len, edges_len) = (u64_at(40) as usize, u64_at(48) as usize);
        let nodes_at = OVERLAY_HEADER_LEN;
        let edges_at = align_up(nodes_at.checked_add(nodes_len)? as u64, SECTION_ALIGN) as usize;
        let portals_at = align_up(edges_at.checked_add(edges_len)? as u64, SECTION_ALIGN) as usize;
        let portals_len = EdgeBitmap::bytes(u64::from(full_nodes)) as usize;
        if portals_at.checked_add(portals_len)? != bytes.len() {
            return None;
        }
        let nodes = BlockedView::parse(&bytes[nodes_at..nodes_at + nodes_len])?;
        let edges = BlockedView::parse(&bytes[edges_at..edges_at + edges_len])?;
        if nodes.record_len != OVERLAY_NODE_LEN
            || nodes.unpacked_len != (m + 1) * OVERLAY_NODE_LEN as u64
            || edges.record_len != OVERLAY_EDGE_LEN
            || edges.unpacked_len != k * OVERLAY_EDGE_LEN as u64
        {
            return None;
        }
        let base = bytes[portals_at..].as_ptr();
        let portals = EdgeBitmap {
            rank: base,
            present: unsafe { base.add(EdgeBitmap::present_offset(u64::from(full_nodes)) as usize) },
        };
        if portals.total(u64::from(full_nodes)) != m {
            return None;
        }
        Some(Overlay {
            _region: None,
            _owned: None,
            node_count: m as u32,
            edge_count: k,
            nodes,
            edges,
            portals,
        })
    }

    /// Overlay node `o`; `o == node_count` is the sentinel.
    #[inline]
    pub fn node(&self, o: u32) -> OverlayNode {
        match self.nodes.record(u64::from(o)) {
            Some(&[a0, a1, a2, a3, b0, b1, b2, b3, c0, c1, c2, c3, d0, d1, d2, d3, ..]) => OverlayNode {
                lat_e7: i32::from_le_bytes([a0, a1, a2, a3]),
                lon_e7: i32::from_le_bytes([b0, b1, b2, b3]),
                edge_ptr: u64::from(u32::from_le_bytes([c0, c1, c2, c3])),
                full_node: u32::from_le_bytes([d0, d1, d2, d3]),
            },
            _ => OverlayNode { lat_e7: 0, lon_e7: 0, edge_ptr: 0, full_node: u32::MAX },
        }
    }

    #[inline]
    pub fn edge_range(&self, o: u32) -> (u64, u64) {
        (self.node(o).edge_ptr, self.node(o + 1).edge_ptr)
    }

    #[inline]
    pub fn edge(&self, i: u64) -> OverlayEdge {
        match self.edges.record(i) {
            Some(r) if r.len() >= OVERLAY_EDGE_LEN => OverlayEdge {
                target: u32::from_le_bytes(r[0..4].try_into().expect("4 bytes")),
                dist_mm: u32::from_le_bytes(r[4..8].try_into().expect("4 bytes")),
                full_edge: u64::from_le_bytes(r[8..16].try_into().expect("8 bytes")),
                type_: r[16],
                speed_limit: r[17],
            },
            _ => OverlayEdge { target: u32::MAX, dist_mm: 0, full_edge: INVALID_EDGE, type_: 0, speed_limit: 0 },
        }
    }

    /// The overlay id of full node `full`, when it is on the overlay.
    #[inline]
    pub fn of_full(&self, full: u32) -> Option<u32> {
        let idx = u64::from(full);
        self.portals.contains(idx).then(|| self.portals.rank_of(idx) as u32)
    }
}

impl Graph {
    pub fn overlay(&self) -> Option<&Overlay> {
        self.overlay.as_ref()
    }

    /// Drop the overlay so long routes use the plain full-graph search.
    pub fn detach_overlay(&mut self) {
        self.overlay = None;
    }

    /// Attach an overlay from a standalone `overlay.bin` (the graph-dir layout).
    /// Returns false, leaving any current overlay in place, when it does not
    /// parse or was built for a different graph.
    pub fn attach_overlay_file(&mut self, path: &str) -> bool {
        let Some(region) = MmapRegion::map(path) else {
            return false;
        };
        let bytes = unsafe { std::slice::from_raw_parts(region.base(), region.len) };
        match Overlay::parse(bytes, self.node_count, self.edge_count) {
            Some(mut ov) => {
                ov._region = Some(region);
                self.overlay = Some(ov);
                true
            }
            None => false,
        }
    }

    /// [`Graph::attach_overlay_file`] over an owned buffer (tests and hosts
    /// without mmap).
    pub fn attach_overlay_bytes(&mut self, bytes: Vec<u8>) -> bool {
        let view = unsafe { std::slice::from_raw_parts(bytes.as_ptr(), bytes.len()) };
        match Overlay::parse(view, self.node_count, self.edge_count) {
            Some(mut ov) => {
                ov._owned = Some(bytes);
                self.overlay = Some(ov);
                true
            }
            None => false,
        }
    }

    /// The full-graph node behind a search id: itself below `node_count`, the
    /// overlay node's full node above it.
    #[inline]
    pub fn full_node_of(&self, id: u32) -> u32 {
        match (&self.overlay, id.checked_sub(self.node_count)) {
            (Some(ov), Some(o)) => ov.node(o).full_node,
            _ => id,
        }
    }
}

#[cfg(test)]
mod overlay_tests {
    use super::*;
    use crate::geometry::{accurate_dist_mm, TrafficSpeeds, ROAD_TYPE_MASK};
    use crate::routing::{perform_search_loop, prepare_routing, reconstruct_path};
    use crate::state::{RadixHeap, RoutingScratchpad};
    use tilecodec::blocked::write_blocked;

    const MOTORWAY_T: u8 = 1;
    const RESIDENTIAL_T: u8 = 7;
    const SPAN: u32 = 41;
    const STEP_E7: i32 = 500_000; // 0.05 deg of longitude at the equator, ~5.6 km

    /// `(source, target, type)`; distances come from the node coordinates.
    type Edge = (u32, u32, u8);

    fn pad8(v: &mut Vec<u8>) {
        while v.len() % 8 != 0 {
            v.push(0);
        }
    }

    /// A v7 graph with no escapes, names or stored geometry, in the exact tail
    /// layouts `assemble` checks.
    fn build_graph(nodes: &[(i32, i32)], edges: &[Edge]) -> Graph {
        let n = nodes.len() as u32;
        let e = edges.len() as u64;
        let mut sorted = edges.to_vec();
        sorted.sort_by_key(|&(s, t, _)| (s, t));
        let mut node_recs = Vec::new();
        let mut ptr = 0u32;
        for (id, &(lat, lon)) in nodes.iter().enumerate() {
            node_recs.extend_from_slice(&lat.to_le_bytes());
            node_recs.extend_from_slice(&lon.to_le_bytes());
            node_recs.extend_from_slice(&ptr.to_le_bytes());
            ptr += sorted.iter().filter(|&&(s, _, _)| s == id as u32).count() as u32;
        }
        node_recs.extend_from_slice(&[0u8; 8]);
        node_recs.extend_from_slice(&(e as u32).to_le_bytes());
        let mut edge_recs = Vec::new();
        for &(s, t, ty) in &sorted {
            let (a, b) = (nodes[s as usize], nodes[t as usize]);
            let dist = accurate_dist_mm(a.0, a.1, b.0, b.1);
            edge_recs.extend_from_slice(&((t as i64 - s as i64) as i16).to_le_bytes());
            edge_recs.extend_from_slice(&dist.to_le_bytes()[..3]);
            edge_recs.push(ty);
            edge_recs.push(0);
        }
        let mut edges_bytes = write_blocked(&edge_recs, 7);
        pad8(&mut edges_bytes);
        edges_bytes.extend(std::iter::repeat(0u8).take(((e.div_ceil(ESCAPE_BLOCK) + 1) * 4) as usize));
        pad8(&mut edges_bytes);
        edges_bytes.extend(std::iter::repeat(0u8).take(EdgeBitmap::bytes(e) as usize));
        let intermediate = vec![0u8; (EdgeBitmap::bytes(e) + 18) as usize];
        let mut meta = Vec::new();
        meta.extend_from_slice(&GRAPH_MAGIC.to_le_bytes());
        meta.extend_from_slice(&tilecodec::mamaps::archive::GRAPH_VERSION_BLOCKED.to_le_bytes());
        meta.extend_from_slice(&u64::from(n).to_le_bytes());
        meta.extend_from_slice(&e.to_le_bytes());
        meta.extend_from_slice(&0u64.to_le_bytes());
        meta.extend_from_slice(&0u64.to_le_bytes());
        let owned = vec![meta, write_blocked(&node_recs, 12), edges_bytes, intermediate];
        let at = |i: usize| unsafe { std::slice::from_raw_parts(owned[i].as_ptr(), owned[i].len()) };
        let sections = GraphSections {
            meta: at(0),
            nodes: at(1),
            edges: at(2),
            intermediate: at(3),
            road_names: None,
            lanes: None,
            elevation: None,
        };
        Graph::assemble(
            sections,
            GraphOwnership {
                nodes: None,
                edges: None,
                intermediate: None,
                road_names: None,
                lanes: None,
                elevation: None,
                archive: None,
                buffers: owned,
            },
        )
        .expect("synthetic graph assembles")
    }

    /// The overlay of `g`: every node touching a class <= 3 edge, in full-id
    /// order, and those edges. An independent writer from the ingest one, so the
    /// two cross-check the format.
    fn build_overlay(g: &Graph, full_nodes_header: u64) -> Vec<u8> {
        let n = g.node_count;
        let is_arterial = |ty: u8| (ty & ROAD_TYPE_MASK) <= 3 && (ty & ROAD_TYPE_MASK) >= 1;
        let mut on = vec![false; n as usize];
        for u in 0..n {
            let (s, e) = g.edge_range(u);
            for i in s..e {
                let edge = g.edge(u, i);
                if is_arterial(edge.type_) {
                    on[u as usize] = true;
                    on[edge.target as usize] = true;
                }
            }
        }
        let ids: Vec<u32> = (0..n).filter(|&u| on[u as usize]).collect();
        let of = |full: u32| ids.binary_search(&full).unwrap() as u32;
        let (mut node_recs, mut edge_recs, mut k) = (Vec::new(), Vec::new(), 0u32);
        for &u in &ids {
            let nd = g.node(u);
            node_recs.extend_from_slice(&nd.lat_e7.to_le_bytes());
            node_recs.extend_from_slice(&nd.lon_e7.to_le_bytes());
            node_recs.extend_from_slice(&k.to_le_bytes());
            node_recs.extend_from_slice(&u.to_le_bytes());
            let (s, e) = g.edge_range(u);
            for i in s..e {
                let edge = g.edge(u, i);
                if is_arterial(edge.type_) {
                    edge_recs.extend_from_slice(&of(edge.target).to_le_bytes());
                    edge_recs.extend_from_slice(&edge.dist_mm.to_le_bytes());
                    edge_recs.extend_from_slice(&i.to_le_bytes());
                    edge_recs.extend_from_slice(&[edge.type_, edge.speed_limit, 0, 0]);
                    k += 1;
                }
            }
        }
        node_recs.extend_from_slice(&[0u8; 8]);
        node_recs.extend_from_slice(&k.to_le_bytes());
        node_recs.extend_from_slice(&u32::MAX.to_le_bytes());
        let nodes = write_blocked(&node_recs, OVERLAY_NODE_LEN);
        let edges = write_blocked(&edge_recs, OVERLAY_EDGE_LEN);
        let mut out = Vec::new();
        for v in [u64::from(OVERLAY_MAGIC) | (u64::from(OVERLAY_VERSION) << 32), full_nodes_header, g.edge_count,
            ids.len() as u64, u64::from(k), nodes.len() as u64, edges.len() as u64]
        {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out.extend_from_slice(&nodes);
        pad8(&mut out);
        out.extend_from_slice(&edges);
        pad8(&mut out);
        let blocks = u64::from(n).div_ceil(RANK_BLOCK_BYTES * 8);
        let mut present = vec![0u8; u64::from(n).div_ceil(8) as usize];
        for &u in &ids {
            present[(u / 8) as usize] |= 1 << (u % 8);
        }
        let mut running = 0u64;
        for b in 0..=blocks {
            out.extend_from_slice(&running.to_le_bytes());
            let lo = (b * RANK_BLOCK_BYTES) as usize;
            let hi = (lo + RANK_BLOCK_BYTES as usize).min(present.len());
            if lo < hi {
                running += present[lo..hi].iter().map(|x| u64::from(x.count_ones())).sum::<u64>();
            }
        }
        out.extend_from_slice(&present);
        out
    }

    /// A ~220 km motorway along the equator with a residential road 1.1 km north
    /// of it, joined by a residential connector at every node. Motorway ids
    /// `0..SPAN`, residential ids `SPAN..2*SPAN`.
    fn corridor() -> Graph {
        let mut nodes = Vec::new();
        for lat in [0, 100_000] {
            for i in 0..SPAN as i32 {
                nodes.push((lat, i * STEP_E7));
            }
        }
        let mut edges = Vec::new();
        for i in 0..SPAN - 1 {
            for (s, t) in [(i, i + 1), (i + 1, i)] {
                edges.push((s, t, MOTORWAY_T));
                edges.push((SPAN + s, SPAN + t, RESIDENTIAL_T));
            }
        }
        for i in 0..SPAN {
            edges.push((i, SPAN + i, RESIDENTIAL_T));
            edges.push((SPAN + i, i, RESIDENTIAL_T));
        }
        build_graph(&nodes, &edges)
    }

    /// `(total time, total distance, used overlay)` for a route along the corridor
    /// at `lat`, or `None` when planning refuses it.
    fn route(g: &Graph, mode: i32, lat: f64, e_lon: f64) -> Option<(u64, u64, bool)> {
        let speeds = TrafficSpeeds::default();
        let (mut scratch, mut heap) = (RoutingScratchpad::new(), RadixHeap::new());
        let mut ensure = |_: i32, _: i32| {};
        let mut ctx =
            prepare_routing(g, &speeds, &mut ensure, lat, 0.0001, lat, e_lon, mode, &mut scratch, &mut heap)?;
        let hierarchical = ctx.hierarchy;
        perform_search_loop(g, &speeds, &mut ensure, mode, &mut ctx, &mut scratch, &mut heap);
        assert_ne!(ctx.target_node, 0xFFFF_FFFF, "a route is found");
        assert!(ctx.hierarchy == hierarchical, "the overlay search did not fall back");
        let steps = reconstruct_path(g, &speeds, mode, &ctx, &mut scratch);
        Some((steps.iter().map(|s| s.time_10ms).sum(), steps.iter().map(|s| s.dist_mm).sum(), hierarchical))
    }

    /// The corridor's end-to-end drive on the residential side.
    fn drive(g: &Graph) -> (u64, u64, bool) {
        let end_lon = f64::from((SPAN as i32 - 1) * STEP_E7) * 1e-7;
        route(g, DRIVING, 0.0101, end_lon - 0.0001).expect("both ends snap")
    }

    #[test]
    fn the_overlay_maps_full_ids_both_ways() {
        let mut g = corridor();
        let bytes = build_overlay(&g, u64::from(g.node_count));
        assert!(g.attach_overlay_bytes(bytes));
        let ov = g.overlay().expect("attached");
        assert_eq!(ov.node_count, SPAN, "exactly the motorway nodes");
        assert_eq!(ov.edge_count, u64::from(2 * (SPAN - 1)));
        for full in 0..SPAN {
            let o = ov.of_full(full).expect("motorway node is a portal");
            assert_eq!(ov.node(o).full_node, full);
            assert_eq!(g.full_node_of(g.node_count + o), full);
        }
        assert_eq!(ov.of_full(SPAN), None, "residential nodes are not on the overlay");
        for o in 0..ov.node_count {
            let (s, e) = ov.edge_range(o);
            for i in s..e {
                let edge = ov.edge(i);
                let full = g.edge(ov.node(o).full_node, edge.full_edge);
                assert_eq!(full.target, ov.node(edge.target).full_node, "overlay edge {i} is its full edge");
                assert_eq!(full.dist_mm, edge.dist_mm);
            }
        }
    }

    #[test]
    fn an_overlay_route_matches_the_full_search() {
        let full = drive(&corridor());
        assert!(!full.2, "no overlay, no hierarchy");
        let mut g = corridor();
        let bytes = build_overlay(&g, u64::from(g.node_count));
        assert!(g.attach_overlay_bytes(bytes));
        let hier = drive(&g);
        assert!(hier.2, "a 220 km drive uses the overlay");
        assert_eq!((hier.0, hier.1), (full.0, full.1), "same time and distance as plain A*");
    }

    #[test]
    fn an_overlay_built_for_another_graph_is_refused() {
        let mut g = corridor();
        let bytes = build_overlay(&g, u64::from(g.node_count) + 1);
        assert!(!g.attach_overlay_bytes(bytes));
        assert!(g.overlay().is_none());
    }

    #[test]
    fn walking_and_cycling_cross_country_ride_the_overlay() {
        let mut g = corridor();
        let bytes = build_overlay(&g, u64::from(g.node_count));
        assert!(g.attach_overlay_bytes(bytes));
        for mode in [WALK, BICYCLE] {
            let (_, _, hierarchical) = route(&g, mode, 0.0101, 1.9999).expect("a 220 km trip is planned");
            assert!(hierarchical, "mode {mode} uses the overlay past the hierarchy threshold");
            let (_, _, hierarchical) = route(&g, mode, 0.0101, 0.5).expect("a 55 km trip is planned");
            assert!(!hierarchical, "mode {mode} searches every road on a short trip");
        }
        assert!(route(&g, PUBLIC_TRANSIT, 0.0101, 1.9999).is_none(), "a 220 km transit walk leg is refused");
    }

    #[test]
    fn walking_leaves_a_motorway_for_a_parallel_side_road() {
        let g = corridor();
        let (_, dist_mm, _) = route(&g, WALK, 0.0001, 0.5).expect("a 55 km walk is planned");
        let along_motorway = accurate_dist_mm(0, 1_000, 0, 5_000_000);
        assert!(
            u64::from(along_motorway) + 2_000_000 < dist_mm,
            "took the two 1.1 km connectors to the side road: {dist_mm} mm vs {along_motorway} mm"
        );
    }
}
