// `Graph::load_archive`: the single-archive (`MAMA8`) loader.
//
// The graph's seven payloads (`metadata`/`nodes`/`edges`/`intermediate` and the
// optional `road_names`/`lanes`/`elevation`) live as sections of the one
// `.mamaps` file after the tile data, at 8-byte aligned offsets named by the
// section directory. This loader maps that one file once and hands
// [`Graph::assemble`] the same slices the multi-file [`Graph::load`] hands
// it, so every length check, index-total tie and sentinel bound runs
// identically on both layouts: the archive's own [`ArchiveView::parse`]
// refuses a corrupt container first, then the section payloads get exactly
// the validation the separate files always had.
//
// What backs the pointers (six per-file regions vs one archive region) is
// [`GraphOwnership`]; the `Graph` keeps whichever alive. The borrowed
// pointers are raw, so no lifetime ties the slices to the returned graph —
// ownership does that job instead.

use tilecodec::mamaps::archive::{
    ARCHIVE_KIND_GRAPH_ELEVATION, ARCHIVE_KIND_GRAPH_INTERMEDIATE, ARCHIVE_KIND_GRAPH_LANES,
    ARCHIVE_KIND_GRAPH_META, ARCHIVE_KIND_GRAPH_NAMES, ARCHIVE_KIND_GRAPH_NODES,
    ARCHIVE_KIND_GRAPH_EDGES, ARCHIVE_KIND_GRAPH_OVERLAY, ArchiveView,
};
use tilecodec::mamaps::header::Header;

/// The single-archive filename inside the base dir, mirroring
/// `MapTileCache.BASEMAP_ARCHIVE_FILE` on the Kotlin side. The two must agree:
/// Kotlin downloads to that name, Rust opens it. Kept as a const beside the
/// loader (rather than passed through JNI) so every entry point resolves the
/// same file without a new parameter on each.
pub const BASEMAP_ARCHIVE_FILE: &str = "basemap.mamaps";

/// `<base>/basemap.mamaps`, tolerating a trailing slash on `base`.
pub fn archive_path(base: &str) -> String {
    if base.ends_with('/') {
        format!("{base}{BASEMAP_ARCHIVE_FILE}")
    } else {
        format!("{base}/{BASEMAP_ARCHIVE_FILE}")
    }
}

/// The seven graph payloads as borrowed slices, in either layout.
pub(crate) struct GraphSections<'a> {
    pub meta: &'a [u8],
    pub nodes: &'a [u8],
    pub edges: &'a [u8],
    pub intermediate: &'a [u8],
    pub road_names: Option<&'a [u8]>,
    pub lanes: Option<&'a [u8]>,
    pub elevation: Option<&'a [u8]>,
}

/// Whatever mapping backs a graph's pointers: the six per-file regions on the
/// multi-file path, the one archive region on the single-archive path.
/// `buffers` is empty in production; tests back their slices with owned bytes
/// instead of mappings (the host stub cannot mmap), and the graph keeps them
/// alive the same way.
pub(crate) struct GraphOwnership {
    pub nodes: Option<MmapRegion>,
    pub edges: Option<MmapRegion>,
    pub intermediate: Option<MmapRegion>,
    pub road_names: Option<MmapRegion>,
    pub lanes: Option<MmapRegion>,
    pub elevation: Option<MmapRegion>,
    pub archive: Option<MmapRegion>,
    pub buffers: Vec<Vec<u8>>,
}

impl Graph {
    /// Validate `sections` and build the graph, keeping `own` alive.
    ///
    /// This is the body of the old multi-file `load` from the metadata parse
    /// onwards, with `region.len`/`region.base()` replaced by the slices'
    /// `len()`/`as_ptr()`. Both loaders funnel through here so the checks
    /// cannot drift apart: exact lengths from the meta counts, the escape
    /// block total, the names rank total, the ascending escape rows, the
    /// intermediate sentinel and geometry total, the lanes sentinel and edge
    /// bound, the elevation length, then the derived cost tables.
    pub(crate) fn assemble(sections: GraphSections<'_>, own: GraphOwnership) -> Option<Graph> {
        let meta = sections.meta;
        if meta.len() < 40 {
            return None;
        }
        let magic = u32::from_le_bytes([meta[0], meta[1], meta[2], meta[3]]);
        let version = u32::from_le_bytes([meta[4], meta[5], meta[6], meta[7]]);
        // Accept the same range as the multi-file `load` and the archive
        // `check_counts`: v6 raw tables still read directly, v7 blocked.
        // Rejecting v6 here (exact `== GRAPH_VERSION`) refused every
        // pre-blocked archive on the single-archive path, including the
        // minimal-v6 round-trip test.
        if magic != GRAPH_MAGIC || !(GRAPH_VERSION_MIN..=GRAPH_VERSION).contains(&version) {
            return None;
        }
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                meta[o], meta[o + 1], meta[o + 2], meta[o + 3], meta[o + 4], meta[o + 5],
                meta[o + 6], meta[o + 7],
            ])
        };
        let nodes_u64 = u64_at(8);
        let edges = u64_at(16);
        let escapes = u64_at(24);
        let named = u64_at(32);
        // `edge_ptr` is a `u32`, so an edge count past that ceiling would have
        // wrapped in the writer and pointed a node at another node's range. The
        // generator refuses to produce one; this refuses to read one. Neither
        // sparse table can describe more edges than exist.
        if u32::try_from(edges).is_err() || escapes > edges || named > edges {
            return None;
        }
        let node_count = u32::try_from(nodes_u64).ok()?;
        let edge_count = edges;
        let escape_count = escapes;
        let named_edges = named;
        let blocked_v7 = version >= tilecodec::mamaps::archive::GRAPH_VERSION_BLOCKED;
        let varint_v8 = version >= tilecodec::mamaps::archive::GRAPH_VERSION_VARINT;
        let intermediate_block = if varint_v8 { INTERMEDIATE_BLOCK_V8 } else { INTERMEDIATE_BLOCK };

        // `edge_count` comes from the header rather than from the edges
        // payload's length. That is the point of graph version 4, and 5 and 6
        // are why: the record is 7 bytes and the payload has four more
        // sections behind it, so its length is no longer any simple multiple
        // of anything. Under v7 the record array is a ZBLK section whose end
        // the view reports.
        let nodes_bytes = sections.nodes;
        let edges_bytes = sections.edges;
        // nodes payload is `NodeRec[node_count + 1]`: one sentinel record past
        // the real nodes, whose `edge_ptr` is `edge_count`. Eight call sites
        // read it.
        let want_nodes = (u64::from(node_count) + 1) * std::mem::size_of::<NodeRec>() as u64;
        let nodes_view = if blocked_v7 {
            let view = BlockedView::parse(nodes_bytes)?;
            if view.unpacked_len != want_nodes || view.record_len != 12 {
                return None;
            }
            Some(view)
        } else {
            if nodes_bytes.len() as u64 != want_nodes {
                return None;
            }
            None
        };

        // Edges payload's five sections, computed forwards from the metadata
        // counts. The total-length assertion below is what replaces v3's
        // self-locating trailer: if the computed sections do not exactly
        // consume the payload, it is not the one these counts describe.
        let escape_blocks = edge_count.div_ceil(ESCAPE_BLOCK) + 1;
        let (escape_first_off, edges_view) = if blocked_v7 {
            let view = BlockedView::parse(edges_bytes)?;
            if view.unpacked_len != edge_count * std::mem::size_of::<EdgeRec>() as u64
                || view.record_len != 7
            {
                return None;
            }
            let rec_end = view.offsets[view.offsets.len() - 1];
            (align_up(rec_end, SECTION_ALIGN), Some(view))
        } else {
            (
                align_up(
                    edge_count * std::mem::size_of::<EdgeRec>() as u64,
                    SECTION_ALIGN,
                ),
                None,
            )
        };
        let escapes_off =
            escape_first_off + escape_blocks * std::mem::size_of::<u32>() as u64;
        let names_off = align_up(
            escapes_off + escape_count * std::mem::size_of::<EscapeRow>() as u64,
            SECTION_ALIGN,
        );
        let name_off_off = names_off + EdgeBitmap::bytes(edge_count);
        let want_edges = name_off_off + named_edges * std::mem::size_of::<u32>() as u64;
        if edges_bytes.len() as u64 != want_edges {
            return None;
        }
        // The escape index, escape rows and name tables live behind the
        // record array in the same payload, raw in both v6 and v7 — only the
        // record array itself is blocked-compressed. So the table base is the
        // payload base unconditionally; the per-record readers branch onto the
        // inflated view (see `edge_rec`). Nulling the base under v7 left every
        // table at `0x0 + offset` and the totals check below read past null
        // (on-device SIGSEGV in `OfflineRouter_init`, 2026-10-19).
        let edges_ptr = edges_bytes.as_ptr();
        let escape_first = unsafe { edges_ptr.add(escape_first_off as usize) };
        let escapes_ptr = unsafe { edges_ptr.add(escapes_off as usize) };
        let names = EdgeBitmap {
            rank: unsafe { edges_ptr.add(names_off as usize) },
            present: unsafe {
                edges_ptr.add((names_off + EdgeBitmap::present_offset(edge_count)) as usize)
            },
        };
        let name_off = unsafe { edges_ptr.add(name_off_off as usize) };
        // The block index's last entry is the row total, and the rank index's
        // is the named-edge total. Both are the direct analogue of
        // `intermediate`'s `rank_total == geometry_edges` check: they tie an
        // index to the section it indexes rather than trusting both to have
        // been written by the same run.
        if unsafe { read_at::<u32>(escape_first, (escape_blocks - 1) as usize) } as u64
            != escape_count
            || names.total(edge_count) != named_edges
        {
            return None;
        }
        // Rows ascend, so bounding the last one bounds them all.
        if escape_count > 0 {
            let last = unsafe { read_at::<EscapeRow>(escapes_ptr, (escape_count - 1) as usize) };
            if u64::from(last.edge_idx) >= edge_count {
                return None;
            }
        }

        // Intermediate payload:
        //   [ blob ][ u64 rank[..] ][ u8 present[..] ][ u64 coarse[..] ]
        //   [ u16 within[..] ][ u64 G ]
        // Everything is recoverable from the last 8 bytes plus `edge_count`:
        // the tables are sized from `G` and from `E`, and the blob is whatever
        // is left over at offset 0. Validated on both axes, because a stale
        // payload from a different graph vintage would otherwise be read out
        // of bounds.
        let inter_bytes = sections.intermediate;
        let inter_len = inter_bytes.len() as u64;
        if inter_len < 8 {
            return None;
        }
        let geometry_edges = u64::from_le_bytes([
            inter_bytes[inter_len as usize - 8],
            inter_bytes[inter_len as usize - 7],
            inter_bytes[inter_len as usize - 6],
            inter_bytes[inter_len as usize - 5],
            inter_bytes[inter_len as usize - 4],
            inter_bytes[inter_len as usize - 3],
            inter_bytes[inter_len as usize - 2],
            inter_bytes[inter_len as usize - 1],
        ]);
        // `G` can never exceed the number of directed edges. Checking it
        // before anything is sized from it is also what stops a garbage
        // trailer from overflowing the table arithmetic below into a
        // plausible-looking total.
        if geometry_edges > edge_count {
            return None;
        }
        let bitmap_bytes = EdgeBitmap::bytes(edge_count);
        let coarse_bytes = (geometry_edges.div_ceil(intermediate_block) + 1)
            * std::mem::size_of::<u64>() as u64;
        let within_bytes = (geometry_edges + 1) * std::mem::size_of::<u16>() as u64;
        let trailer = bitmap_bytes + coarse_bytes + within_bytes + 8;
        if inter_len < trailer {
            return None;
        }
        let blob_bytes = inter_len - trailer;
        let intermediate_data = inter_bytes.as_ptr();
        let geometry = EdgeBitmap {
            rank: unsafe { intermediate_data.add(blob_bytes as usize) },
            present: unsafe {
                intermediate_data
                    .add((blob_bytes + EdgeBitmap::present_offset(edge_count)) as usize)
            },
        };
        let intermediate_coarse =
            unsafe { intermediate_data.add((blob_bytes + bitmap_bytes) as usize) };
        let intermediate_within = unsafe { intermediate_coarse.add(coarse_bytes as usize) };
        // The two halves of the trailer have to agree with each other and with
        // the blob: the sentinel offset is the blob's length, and the rank
        // total is `G`. Both are cheap, and between them they catch a
        // truncated payload, a mismatched vintage and a generator that lost
        // count.
        let sentinel = unsafe {
            read_at::<u64>(
                intermediate_coarse,
                (geometry_edges / intermediate_block) as usize,
            ) + u64::from(read_at::<u16>(intermediate_within, geometry_edges as usize))
        };
        if sentinel != blob_bytes || geometry.total(edge_count) != geometry_edges {
            return None;
        }

        let (road_names, road_names_size) = match sections.road_names {
            Some(b) => (b.as_ptr(), b.len()),
            None => (ptr::null(), 0),
        };

        // Lanes payload: [ u32 n ][ LaneEntry[n + 1] ][ u16 lane-mask blob ].
        // The index is sized by its own header rather than by `edge_count`, so
        // it is validated against that; a truncated/mismatched payload
        // disables real lanes so routing falls back to topology inference.
        let lanes = match sections.lanes {
            Some(b) => {
                if b.len() < 4 {
                    return None;
                }
                let n = u32::from_le_bytes([b[0], b[1], b[2], b[3]]);
                let index = unsafe { b.as_ptr().add(4) };
                let index_bytes =
                    4 + (u64::from(n) + 1) * std::mem::size_of::<LaneEntry>() as u64;
                if (b.len() as u64) < index_bytes {
                    return None;
                }
                // The sentinel entry's offset is the blob length, so checking
                // it validates the blob as well as the index. That matters
                // because `edge_lane_masks` reads at offsets taken straight
                // from the payload. Exactly, not "at least": the writer emits
                // header + index + blob and nothing else, so a longer payload
                // is a vintage mismatch too.
                let sentinel = unsafe { read_at::<LaneEntry>(index, n as usize) };
                if b.len() as u64 != index_bytes + u64::from(sentinel.blob_off) {
                    return None;
                }
                // Nothing else here mentions `edge_count`, so a stale-vintage
                // lanes payload whose own header is self-consistent would
                // otherwise load and attribute masks to the wrong edges.
                // Entries ascend, so bounding the last one bounds them all.
                if n > 0 {
                    let last = unsafe { read_at::<LaneEntry>(index, n as usize - 1) };
                    if u64::from(last.edge_idx) >= edge_count {
                        return None;
                    }
                }
                Some((index, n, unsafe { b.as_ptr().add(index_bytes as usize) }))
            }
            None => None,
        };
        let (lane_index, lane_edges, lane_data) =
            lanes.unwrap_or((ptr::null(), 0, ptr::null()));

        // Elevation payload: `i16[node_count]`, one per real node in node-id
        // order. Optional and validated by length alone — a stale-vintage
        // payload whose node_count differs is simply disabled (elevation reads
        // 0), the same self-check the lanes payload does against `edge_count`.
        // Under v7 the payload is a ZBLK section, dispatched on its magic.
        let (elevation, elevation_view) = match sections.elevation {
            Some(b) if blocked_v7 => match BlockedView::parse(b) {
                Some(view)
                    if view.unpacked_len
                        == u64::from(node_count) * std::mem::size_of::<i16>() as u64
                        && view.record_len == 2 =>
                {
                    (ptr::null(), Some(view))
                }
                _ => (ptr::null(), None),
            },
            Some(b)
                if b.len() as u64
                    == u64::from(node_count) * std::mem::size_of::<i16>() as u64 =>
            {
                (b.as_ptr(), None)
            }
            _ => (ptr::null(), None),
        };

        // A wrong-length elevation payload is dropped, not kept mapped:
        // nothing should read a stale vintage's bytes. A valid v7 blocked
        // view has a null raw pointer by design (`node_elevation` branches
        // onto the view first), so it must not count as "no elevation" here:
        // dropping the region would unmap the frames the view reads.
        let mut own = own;
        if elevation.is_null() && elevation_view.is_none() {
            own.elevation = None;
        }

        // Table base is the payload base unconditionally: under v7 the raw
        // bytes are the blocked section, never read as records (see `node`,
        // which branches onto the inflated view first). Nulling it instead
        // made `get_node`'s `is_null` guard return a zeroed node for every id.
        let nodes = nodes_bytes.as_ptr();
        // --- Derived tables (identical formulas to the C++ init) ---
        let mut lon_to_mm_scale = [0u32; 4096];
        for (i, scale) in lon_to_mm_scale.iter_mut().enumerate() {
            let lat_deg = (((i as i64 - 2048) << 19) as f64) / 1e7;
            *scale = ((111_139_000.0 / 1e7) * (lat_deg * DEG_TO_RAD).cos() * 1024.0) as u32;
        }

        let calc_scale =
            |speed_m_s: f64| -> u64 { ((100.0 / (speed_m_s * 1000.0)) * 4_294_967_296.0) as u64 };

        let mut time_scale_fixed = [0u64; 4];
        time_scale_fixed[WALK as usize] = calc_scale(WALK_SPEED_M_S);
        time_scale_fixed[BICYCLE as usize] = calc_scale(BICYCLE_SPEED_M_S);
        // Heuristic speed MUST be >= the fastest achievable edge speed (which
        // is clamped to MAX_DRIVING_KMH in get_edge_time_10ms) so the
        // heuristic stays consistent for the monotonic radix heap. See
        // MAX_DRIVING_KMH.
        time_scale_fixed[DRIVING as usize] = calc_scale(MAX_DRIVING_KMH / 3.6);
        // PUBLIC_TRANSIT only ever walks in the road graph (see
        // is_mode_allowed), so it MUST use walk speed. Leaving it at a vehicle
        // speed makes the A* heuristic wildly over-optimistic: still correct,
        // but catastrophically slow because almost the whole graph gets
        // expanded.
        time_scale_fixed[PUBLIC_TRANSIT as usize] = calc_scale(WALK_SPEED_M_S);

        let mut edge_time_multipliers = [[0u64; 16]; 4];
        for (m, row) in edge_time_multipliers.iter_mut().enumerate() {
            for (r, multiplier) in row.iter_mut().enumerate() {
                let speed_m_s = if m == DRIVING as usize {
                    match r as u8 {
                        1 => 105.0 / 3.6, // MOTORWAY
                        2 => 85.0 / 3.6,  // TRUNK
                        3 => 65.0 / 3.6,  // PRIMARY
                        4 => 55.0 / 3.6,  // SECONDARY
                        5 => 45.0 / 3.6,  // TERTIARY
                        _ => 30.0 / 3.6,
                    }
                } else if m == BICYCLE as usize {
                    BICYCLE_SPEED_M_S
                } else {
                    WALK_SPEED_M_S
                };
                let speed_m_s = if m != DRIVING as usize && r as u8 == MOTORWAY {
                    speed_m_s / ACTIVE_MOTORWAY_PENALTY
                } else {
                    speed_m_s
                };
                *multiplier = ((100.0 / (speed_m_s * 1000.0)) * 4_294_967_296.0) as u64;
            }
        }

        // The regions move in here so the pointers above stay alive as long as
        // the graph. The pointers must be backed by exactly one side: the
        // three per-file regions on the multi-file path, the archive region
        // on the single-archive path (whose pointers all land in the one
        // mapping), or owned buffers in host tests. A caller bringing none —
        // or mixing regions with an archive — is refused.
        let backed_by_buffers = !own.buffers.is_empty();
        let GraphOwnership {
            nodes: nodes_region,
            edges: edges_region,
            intermediate: intermediate_region,
            road_names: road_names_region,
            lanes: lanes_region,
            elevation: elevation_region,
            archive: archive_region,
            buffers: owned_buffers,
        } = own;
        let multi = nodes_region.is_some() && edges_region.is_some() && intermediate_region.is_some();
        let single = archive_region.is_some();
        if multi && single || (!multi && !single && !backed_by_buffers) {
            return None;
        }

        Some(Graph {
            _nodes_region: nodes_region,
            _edges_region: edges_region,
            _intermediate_region: intermediate_region,
            _road_names_region: road_names_region,
            _lanes_region: lanes_region,
            _elevation_region: elevation_region,
            _archive_region: archive_region,
            _owned_buffers: owned_buffers,
            nodes,
            node_count,
            edge_count,
            edges: edges_ptr,
            escape_first,
            escapes: escapes_ptr,
            escape_count,
            names,
            name_off,
            named_edges,
            intermediate_data,
            geometry,
            intermediate_coarse,
            intermediate_within,
            intermediate_varint: varint_v8,
            intermediate_block,
            lane_index,
            lane_edges,
            lane_data,
            road_names,
            road_names_size,
            elevation,
            nodes_view,
            edges_view,
            elevation_view,
            overlay: None,
            lon_to_mm_scale,
            time_scale_fixed,
            edge_time_multipliers,
        })
    }
}
