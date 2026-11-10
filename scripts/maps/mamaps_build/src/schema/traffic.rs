//! `traffic`: one line per drivable **component** segment of the v6 routing graph.
//!
//! The one layer whose geometry is not in the `.osm.pbf` and not in a prepared product either: it
//! is read straight from the app's v6 routing graph — `metadata.bin`, `nodes.bin`, `edges.bin` and
//! `intermediate.bin`, produced by `scripts/maps/osm_ingest`'s `road_graph`. Building from the
//! graph is what makes every feature's `component_id` match, byte for byte and with no coordination
//! file, the ids the traffic server computes from the same graph. The authoritative spec is
//! `analysis/traffic_id_contract.md`; the reader below mirrors `maps/src/main/rust/src/graph.rs`.
//!
//! # What one feature is
//!
//! A big edge's polyline is `source_node -> interior points -> target_node`. A **component**
//! segment is one consecutive vertex pair of that polyline, and each becomes one `GEOM_LINE`
//! feature carrying `component_id = (big_edge_id << 16) | seg_index` in the body's id side table.
//! The tiler does not coalesce this layer, so its features stay one-to-one with those ids.
//!
//! # Emit-each-segment-once (dedup), verbatim from the contract §3
//!
//! A two-way road is two directed edges over the same ground. To draw it once — and, more
//! importantly, so the archive and the server pick the **same** directed `big_edge_id` for a
//! physical segment — this canonicalises identically to WS1:
//!
//! 1. Skip any edge with `REVERSE_GEOMETRY_FLAG` (`0x40`): it is the reverse twin of a *curved*
//!    two-way road and stores no geometry of its own; its non-flag twin owns the shape.
//! 2. A straight two-way chord stores no blob and the flag is set on neither direction, so step 1
//!    alone would emit both. For an edge with no stored geometry that has a drivable twin, emit
//!    only the canonical direction (`source_idx < target_idx`). A genuine one-way has no twin, so
//!    it is emitted regardless.
//!
//! # Drivable
//!
//! An edge is drivable iff `(type_ & 0x3F)` is in `1..=9` (motorway..living_street). The road-class
//! bits are masked before the range test so the reverse-geometry flag never trips it.

use std::path::Path;

use osm_ingest::proto::{err, Result};
use tile_build::geom::Geometry;
use tilecodec::mamaps::dict::{self, LAYER_TRAFFIC};

use super::Class;
use crate::store::Sink;

/// The zoom the traffic overlay first surfaces at.
///
/// Component lines are dense — roughly the whole drivable network as one line per edge — so a
/// traffic overlay only earns its bytes where a street network reads at all. z13 is the shallowest
/// zoom a city's arterials are legible; below it the overlay would be a red smear (and z12 alone
/// cost 0.38GB on the california build). Raise it to trim the archive further, lower it to show
/// motorway congestion sooner. The store packs `min_zoom` in five bits, so anything up to 31 is
/// representable.
pub const MIN_ZOOM: u8 = 13;

/// Bits reserved for the component-segment index inside a packed component id. Shared on-disk/wire
/// contract with the server and the device — keep in sync with `graph.rs::COMPONENT_SEG_BITS` and
/// `analysis/traffic_id_contract.md` §1.
pub const COMPONENT_SEG_BITS: u32 = 16;
pub const COMPONENT_SEG_MASK: u64 = (1 << COMPONENT_SEG_BITS) - 1;

/// `component_id = (big_edge_id << 16) | seg_index`.
#[inline]
pub fn pack_component_id(big_edge_id: u64, seg_index: u32) -> u64 {
    debug_assert!(u64::from(seg_index) <= COMPONENT_SEG_MASK);
    (big_edge_id << COMPONENT_SEG_BITS) | u64::from(seg_index)
}

/// The inverse of [`pack_component_id`].
#[inline]
pub fn unpack_component_id(component_id: u64) -> (u64, u32) {
    (component_id >> COMPONENT_SEG_BITS, (component_id & COMPONENT_SEG_MASK) as u32)
}

/// The class every traffic segment carries: layer `traffic`, no kind and no detail.
///
/// No `kind`/`kind_detail`, because the layer is not drawn by the basemap style — it is filtered on
/// and recoloured by the renderer from a pushed id→speed table, keyed on the `component_id` in the
/// body's id side table rather than on any interned attribute. Keeping it out of [`dict::KINDS`]
/// avoids disturbing the frozen kind table for a value nothing matches by name.
pub fn traffic_class() -> Class {
    Class {
        layer: LAYER_TRAFFIC,
        kind: dict::NONE,
        kind_detail: dict::NONE,
        flags: 0,
        area: false,
        min_zoom: MIN_ZOOM,
        min_area_px: 0.0,
    }
}

// --- v6/v7 graph on-disk constants (see traffic_id_contract.md §2 / graph.rs) -----------------

pub(crate) const MARG_MAGIC: u32 = 0x4752_414D; // "MARG"
/// Newest graph version: v7 blocked record sections.
pub(crate) const GRAPH_VERSION: u32 = 7;
/// Oldest graph version accepted: v6 raw tables still read directly.
pub(crate) const GRAPH_VERSION_MIN: u32 = 6;
/// One escape block index per 1024 edges (`graph.rs` ESCAPE_BLOCK).
pub(crate) const ESCAPE_BLOCK: u64 = 1024;
/// One coarse geometry offset per 32 geometry edges (`graph.rs` INTERMEDIATE_BLOCK).
pub(crate) const INTERMEDIATE_BLOCK: u64 = 32;
/// One rank word per 512 edges = 64 present bytes (`graph.rs` RANK_BLOCK_BYTES).
const RANK_BLOCK_BYTES: usize = 64;
/// `type_` bit 6: this edge stores no geometry of its own; its twin runs `target -> source`.
const REVERSE_GEOMETRY_FLAG: u8 = 0x40;
/// The low six bits of `type_` are the road class.
pub(crate) const ROAD_TYPE_MASK: u8 = 0x3F;
/// A `target_delta` of `i16::MIN` means the true target is in the escape table.
const TARGET_DELTA_ESCAPE: i16 = i16::MIN;
/// A `dist` u24 of all-ones means the true target and distance are in the escape table.
const DIST_MM_ESCAPE: u32 = 0x00FF_FFFF;
/// The most vertices a big edge's polyline holds (`geom.rs` MAX_POINTS); the reader stops here.
const MAX_POINTS: usize = 256;

fn u16_at(buf: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([buf[at], buf[at + 1]])
}
fn i16_at(buf: &[u8], at: usize) -> i16 {
    i16::from_le_bytes([buf[at], buf[at + 1]])
}
fn u32_at(buf: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([buf[at], buf[at + 1], buf[at + 2], buf[at + 3]])
}
fn i32_at(buf: &[u8], at: usize) -> i32 {
    i32::from_le_bytes([buf[at], buf[at + 1], buf[at + 2], buf[at + 3]])
}
fn u64_at(buf: &[u8], at: usize) -> u64 {
    u64::from_le_bytes([
        buf[at],
        buf[at + 1],
        buf[at + 2],
        buf[at + 3],
        buf[at + 4],
        buf[at + 5],
        buf[at + 6],
        buf[at + 7],
    ])
}

fn align_up8(n: usize) -> usize {
    (n + 7) & !7
}

/// Inflate every block of a validated section and concatenate.
///
/// Batch-tool path (traffic/junction stream whole tables), so one pass beats
/// per-block caching. The device inflates lazily instead — see
/// `maps/.../graph_part1.rs::BlockedView`.
pub(crate) fn inflate_all(view: &tilecodec::blocked::Blocked) -> Result<Vec<u8>> {
    // Blocks inflate independently (`Blocked::block` is `&self` and pure), so they go across the
    // pool a group at a time and are appended in block order: the same bytes the one-block-at-a-
    // time loop produced. On the planet graph this was ~13 GB of nodes + edge records inflated on
    // one thread, the 1m40s at ~1.3 cores before the junction layer's first connector.
    use rayon::prelude::*;
    const GROUP: usize = 4096;
    let mut out = Vec::with_capacity(view.unpacked_len() as usize);
    let n = view.block_count();
    let mut start = 0usize;
    while start < n {
        let end = (start + GROUP).min(n);
        let blocks: Vec<std::result::Result<Vec<u8>, _>> =
            tile_build::par::install(|| (start..end).into_par_iter().map(|b| view.block(b)).collect());
        for block in blocks {
            out.extend_from_slice(&block.map_err(|e| osm_ingest::proto::Error(e.0))?);
        }
        start = end;
    }
    if out.len() as u64 != view.unpacked_len() {
        return err("a blocked section inflated short of its unpacked length".to_string());
    }
    Ok(out)
}

/// Bytes an [`EdgeBitmap`] over `edges` edges occupies: a `u64` rank word per 512 edges plus one
/// present byte per 8 edges (`graph.rs` `EdgeBitmap`).
fn present_offset(edges: u64) -> usize {
    ((edges.div_ceil(512) + 1) * 8) as usize
}
fn edge_bitmap_bytes(edges: u64) -> usize {
    present_offset(edges) + edges.div_ceil(8) as usize
}

/// The subset of the v6/v7 graph reader the traffic layer needs: node coordinates,
/// edge targets and types, and big-edge geometry. Names, distances and speed
/// limits are skipped — the server reads those; the archive carries only
/// geometry and the id.
///
/// The three tables are file mappings rather than heap `Vec`s: a planet graph is gigabytes, and
/// `Graph::load` runs while the OSM tables are still alive, so reading them whole would stack that
/// many gigabytes of commit on top of the peak. Mapped, the OS pages the working set (junction
/// walks nodes in order) and no commit is charged. Same bytes, same layout, same readers.
///
/// Under v7 the record arrays are ZBLK sections, inflated eagerly at load
/// into `nodes_owned`/`rec_owned` (a batch tool, unlike the device's lazy
/// sections — the whole graph is walked anyway). The escapes, names and
/// intermediate tables stay mapped in both versions.
///
/// `pub(crate)` because [`crate::schema::junction`] reads the same four files for the same reason
/// and must not carry a second copy of this byte layout.
pub(crate) struct Graph {
    nodes: osm_ingest::mem::Mapped,
    edges: osm_ingest::mem::Mapped,
    inter: osm_ingest::mem::Mapped,
    /// Inflated v7 nodes table. Empty on the v6 path, where `nodes` serves.
    nodes_owned: Vec<u8>,
    /// Inflated v7 edge-record array. Empty on the v6 path, where the head of
    /// `edges` serves. Escapes/names always read from `edges` at computed offsets.
    rec_owned: Vec<u8>,
    pub(crate) node_count: u64,
    escape_first_off: usize,
    escapes_off: usize,
    // intermediate.bin sub-table offsets
    geom_rank_off: usize,
    geom_present_off: usize,
    coarse_off: usize,
    within_off: usize,
}

impl Graph {
    /// Node records: mapped on v6, inflated on v7.
    fn nb(&self) -> &[u8] {
        if self.nodes_owned.is_empty() {
            &self.nodes
        } else {
            &self.nodes_owned
        }
    }

    /// Edge records: mapped head on v6, inflated on v7.
    fn rb(&self) -> &[u8] {
        if self.rec_owned.is_empty() {
            &self.edges
        } else {
            &self.rec_owned
        }
    }
}

impl Graph {
    pub(crate) fn load(dir: &Path) -> Result<Graph> {
        let meta = std::fs::read(dir.join("metadata.bin")).map_err(|e| {
            osm_ingest::proto::Error(format!(
                "cannot read {}: {e}",
                dir.join("metadata.bin").display()
            ))
        })?;
        if meta.len() < 40 {
            return err(format!("metadata.bin is {} bytes, a v6 MARG header is 40", meta.len()));
        }
        if u32_at(&meta, 0) != MARG_MAGIC {
            return err("metadata.bin is not a MARG graph header".to_string());
        }
        let version = u32_at(&meta, 4);
        if !(GRAPH_VERSION_MIN..=GRAPH_VERSION).contains(&version) {
            return err(format!(
                "the graph is version {version}; the traffic layer reads v{GRAPH_VERSION_MIN}..=v{GRAPH_VERSION}"
            ));
        }
        let blocked_v7 = version >= GRAPH_VERSION;
        let node_count = u64_at(&meta, 8);
        let edge_count = u64_at(&meta, 16);
        let escape_count = u64_at(&meta, 24);
        if edge_count > u32::MAX as u64 {
            return err(format!("edge_count {edge_count} does not fit u32"));
        }
        if escape_count > edge_count {
            return err(format!(
                "escape_count {escape_count} exceeds edge_count {edge_count}"
            ));
        }

        // The three tables open concurrently: three page-cache faults on
        // three files, no dependency between them. Metadata validation above
        // stays serial — it sizes everything below. Small win, almost free.
        let (nodes, edges, inter) = std::thread::scope(|scope| {
            let open = |name: &'static str| {
                scope.spawn(move || {
                    osm_ingest::mem::Mapped::open(&dir.join(name))
                        .map_err(osm_ingest::proto::Error)
                })
            };
            let (a, b, c) = (open("nodes.bin"), open("edges.bin"), open("intermediate.bin"));
            let nodes = a.join().expect("the nodes.bin opener");
            let edges = b.join().expect("the edges.bin opener");
            let inter = c.join().expect("the intermediate.bin opener");
            nodes.and_then(|nodes| edges.map(|edges| (nodes, edges))).and_then(
                |(nodes, edges)| inter.map(|inter| (nodes, edges, inter)),
            )
        })?;
        let want_nodes = (node_count + 1)
            .checked_mul(12)
            .ok_or_else(|| osm_ingest::proto::Error("node table size overflows".to_string()))?;
        // v7 nodes are a ZBLK section: parse, check the unpacked length, and
        // inflate eagerly (batch tool — the whole table is walked).
        let nodes_owned = if blocked_v7 {
            let view = tilecodec::blocked::Blocked::parse(&nodes).map_err(|e| {
                osm_ingest::proto::Error(format!("nodes.bin is not a blocked section: {e}"))
            })?;
            if view.unpacked_len() != want_nodes || view.record_len() != 12 {
                return err(format!(
                    "nodes.bin unpacks to {} byte(s), expected {want_nodes}",
                    view.unpacked_len()
                ));
            }
            inflate_all(&view)?
        } else {
            if nodes.len() as u64 != want_nodes {
                return err(format!(
                    "nodes.bin is {} bytes, expected {} for {node_count}+1 records",
                    nodes.len(),
                    want_nodes,
                ));
            }
            Vec::new()
        };

        // v7 edge records are a ZBLK section at the file head; escapes and
        // names follow it exactly as before. The records' end comes from the
        // section, not from `edge_count * 7`.
        let (escape_first_off, rec_owned) = if blocked_v7 {
            let view = tilecodec::blocked::Blocked::parse(&edges).map_err(|e| {
                osm_ingest::proto::Error(format!("edges.bin is not a blocked section: {e}"))
            })?;
            if view.unpacked_len() != edge_count * 7 || view.record_len() != 7 {
                return err(format!(
                    "edges.bin unpacks to {} byte(s), expected {} 7-byte records",
                    view.unpacked_len(),
                    edge_count
                ));
            }
            let rec_end = view.block_offset(view.block_count()).ok_or_else(|| {
                osm_ingest::proto::Error("edges.bin block table has no end".to_string())
            })? as usize;
            (align_up8(rec_end), inflate_all(&view)?)
        } else {
            (align_up8((edge_count as usize) * 7), Vec::new())
        };
        let escape_blocks = edge_count.div_ceil(ESCAPE_BLOCK) + 1;
        let escapes_off = escape_first_off + (escape_blocks as usize) * 4;
        let want_edges_min = escapes_off + (escape_count as usize) * 12;
        if edges.len() < want_edges_min {
            return err(format!(
                "edges.bin is {} bytes, too short for {edge_count} edges and {escape_count} escapes",
                edges.len(),
            ));
        }

        if inter.len() < 8 {
            return err("intermediate.bin is too short to hold its geometry-edge count".to_string());
        }
        let g_edges = u64_at(&inter, inter.len() - 8);
        if g_edges > edge_count {
            return err(format!(
                "intermediate.bin claims {g_edges} geometry edges, past the {edge_count} in the graph"
            ));
        }
        let bitmap_bytes = edge_bitmap_bytes(edge_count);
        let coarse_bytes = ((g_edges.div_ceil(INTERMEDIATE_BLOCK) + 1) * 8) as usize;
        let within_bytes = ((g_edges + 1) * 2) as usize;
        let trailer = bitmap_bytes + coarse_bytes + within_bytes + 8;
        if inter.len() < trailer {
            return err(format!(
                "intermediate.bin is {} bytes, too short for its {trailer}-byte trailer",
                inter.len(),
            ));
        }
        let blob_bytes = inter.len() - trailer;
        let geom_rank_off = blob_bytes;
        let geom_present_off = blob_bytes + present_offset(edge_count);
        let coarse_off = blob_bytes + bitmap_bytes;
        let within_off = coarse_off + coarse_bytes;

        Ok(Graph {
            nodes,
            edges,
            inter,
            nodes_owned,
            rec_owned,
            node_count,
            escape_first_off,
            escapes_off,
            geom_rank_off,
            geom_present_off,
            coarse_off,
            within_off,
        })
    }

    /// A node's `(lat_e7, lon_e7)`.
    pub(crate) fn node(&self, n: u64) -> (i32, i32) {
        let base = (n as usize) * 12;
        (i32_at(self.nb(), base), i32_at(self.nb(), base + 4))
    }

    /// A node's first outgoing edge index. The sentinel record (index `node_count`) holds
    /// `edge_count`, so `edge_ptr(n+1)` is always defined for a real node `n`.
    pub(crate) fn edge_ptr(&self, n: u64) -> u32 {
        u32_at(self.nb(), (n as usize) * 12 + 8)
    }

    pub(crate) fn edge_type(&self, idx: u32) -> u8 {
        self.rb()[(idx as usize) * 7 + 5]
    }

    /// The target node of edge `idx` whose source is `s`, decoding the escape table when the
    /// delta or distance is sentinelled.
    pub(crate) fn edge_target(&self, idx: u32, s: u32) -> Result<u32> {
        let base = (idx as usize) * 7;
        let rb = self.rb();
        let delta = i16_at(rb, base);
        let dist = (rb[base + 2] as u32)
            | ((rb[base + 3] as u32) << 8)
            | ((rb[base + 4] as u32) << 16);
        if delta == TARGET_DELTA_ESCAPE || dist == DIST_MM_ESCAPE {
            self.escape_target(idx)
        } else {
            Ok(s.wrapping_add_signed(delta as i32))
        }
    }

    fn escape_target(&self, idx: u32) -> Result<u32> {
        let block = (idx as u64 / ESCAPE_BLOCK) as usize;
        let lo = u32_at(&self.edges, self.escape_first_off + block * 4);
        let hi = u32_at(&self.edges, self.escape_first_off + (block + 1) * 4);
        for r in lo..hi {
            let rb = self.escapes_off + (r as usize) * 12;
            if u32_at(&self.edges, rb) == idx {
                return Ok(u32_at(&self.edges, rb + 4));
            }
        }
        err(format!("edge {idx} is escaped but has no escape row"))
    }

    /// Does edge `idx` store an interior-point blob?
    pub(crate) fn geom_contains(&self, idx: u32) -> bool {
        let byte = self.geom_present_off + (idx as usize) / 8;
        self.inter[byte] & (1u8 << (idx % 8)) != 0
    }

    /// The rank of edge `idx` among the geometry-carrying edges: the number of set bits strictly
    /// below it in the presence bitmap.
    fn geom_rank(&self, idx: u32) -> u64 {
        let byte = (idx as usize) / 8;
        let block = byte / RANK_BLOCK_BYTES;
        let mut n = u64_at(&self.inter, self.geom_rank_off + block * 8);
        for b in (block * RANK_BLOCK_BYTES)..byte {
            n += self.inter[self.geom_present_off + b].count_ones() as u64;
        }
        let mask = ((1u16 << (idx % 8)) - 1) as u8;
        n += (self.inter[self.geom_present_off + byte] & mask).count_ones() as u64;
        n
    }

    /// The blob byte offset of the `g`th geometry edge: `coarse[g/32] + within[g]`.
    fn geom_offset(&self, g: u64) -> u64 {
        let coarse = u64_at(&self.inter, self.coarse_off + (g / INTERMEDIATE_BLOCK) as usize * 8);
        let within = u16_at(&self.inter, self.within_off + (g as usize) * 2) as u64;
        coarse + within
    }

    /// The `(start, end)` byte range of edge `idx`'s interior-point blob, or `None` when it stores
    /// no geometry (a straight chord).
    fn intermediate_range(&self, idx: u32) -> Option<(usize, usize)> {
        if !self.geom_contains(idx) {
            return None;
        }
        let g = self.geom_rank(idx);
        Some((self.geom_offset(g) as usize, self.geom_offset(g + 1) as usize))
    }

    /// The full polyline of edge `idx` in canonical `source -> target` order, as `(lat_e7, lon_e7)`
    /// vertices: the source node, any stored interior points, then the target node.
    pub(crate) fn polyline(&self, idx: u32, s: u32, t: u32) -> Vec<(i32, i32)> {
        let (slat, slon) = self.node(s as u64);
        let (tlat, tlon) = self.node(t as u64);
        let mut out = Vec::with_capacity(4);
        out.push((slat, slon));
        if let Some((start, end)) = self.intermediate_range(idx) {
            let (mut lat, mut lon) = (slat, slon);
            let mut off = start;
            // count starts at 1 for the source; the reader stops at MAX_POINTS total, matching
            // `graph.rs::decode_edge_coords`.
            let mut count = 1usize;
            while off + 4 <= end && count + 1 < MAX_POINTS {
                let d_lat = i16_at(&self.inter, off) as i32;
                let d_lon = i16_at(&self.inter, off + 2) as i32;
                lat = lat.wrapping_add(d_lat);
                lon = lon.wrapping_add(d_lon);
                out.push((lat, lon));
                count += 1;
                off += 4;
            }
        }
        out.push((tlat, tlon));
        out
    }

    /// Is there a drivable edge running `from -> to`? Used to tell a straight two-way chord (which
    /// has such a twin and is emitted only in the canonical direction) from a one-way (which has
    /// none and is always emitted).
    pub(crate) fn has_drivable_twin(&self, from: u32, to: u32) -> Result<bool> {
        let lo = self.edge_ptr(from as u64);
        let hi = self.edge_ptr(from as u64 + 1);
        for e in lo..hi {
            let rc = self.edge_type(e) & ROAD_TYPE_MASK;
            if (1..=9).contains(&rc) && self.edge_target(e, from)? == to {
                return Ok(true);
            }
        }
        Ok(false)
    }
}

/// Read the v6 graph at `dir` and push one line feature per drivable component segment into `sink`,
/// each carrying its packed `component_id`. Returns the number of segments emitted.
///
/// Modelled on [`crate::schema::landtype::stream_prepared`] and
/// [`crate::schema::transit::stream_routes`]: a source of geometry outside the `.osm.pbf`, read
/// after the OSM passes and streamed straight into the feature sink. Unlike those two it is not
/// clipped to a bbox here — the graph is already the built region, and the tiler clips each segment
/// per tile like any other line.
pub fn stream_graph(dir: &Path, sink: &mut Sink) -> Result<u64> {
    let graph = Graph::load(dir)?;
    let class = traffic_class();
    let mut emitted = 0u64;
    for n in 0..graph.node_count {
        let s = n as u32;
        let lo = graph.edge_ptr(n);
        let hi = graph.edge_ptr(n + 1);
        for idx in lo..hi {
            let ty = graph.edge_type(idx);
            // Step 1: the reverse twin of a curved two-way owns no geometry; its forward twin does.
            if ty & REVERSE_GEOMETRY_FLAG != 0 {
                continue;
            }
            // A driving overlay: motorway..living_street only.
            let rc = ty & ROAD_TYPE_MASK;
            if !(1..=9).contains(&rc) {
                continue;
            }
            let target = graph.edge_target(idx, s)?;
            if target as u64 >= graph.node_count {
                return err(format!(
                    "edge {idx} targets node {target}, past the {} in the graph",
                    graph.node_count,
                ));
            }
            // Step 2: a straight two-way chord is emitted only in the canonical direction; a
            // one-way (no drivable twin back) is always emitted.
            if !graph.geom_contains(idx) && graph.has_drivable_twin(target, s)? && s >= target {
                continue;
            }
            let verts = graph.polyline(idx, s, target);
            for seg in 0..verts.len().saturating_sub(1) {
                let (a_lat, a_lon) = verts[seg];
                let (b_lat, b_lon) = verts[seg + 1];
                // A zero-length segment draws nothing and would only cost a feature and an id.
                if a_lat == b_lat && a_lon == b_lon {
                    continue;
                }
                let component_id = pack_component_id(idx as u64, seg as u32);
                let line = vec![vec![
                    (a_lon as f64 * 1e-7, a_lat as f64 * 1e-7),
                    (b_lon as f64 * 1e-7, b_lat as f64 * 1e-7),
                ]];
                sink.push_named(&class, &crate::store::EncodedGeom::live(&Geometry::Lines(line))?, None, component_id)?;
                emitted += 1;
            }
        }
    }
    Ok(emitted)
}

