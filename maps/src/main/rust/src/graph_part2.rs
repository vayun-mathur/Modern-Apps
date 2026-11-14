impl Graph {
    /// Load the graph from `base` (a directory path, trailing slash optional).
    /// Returns `None` if any mandatory file is absent, if `metadata.bin` is not a
    /// [`GRAPH_VERSION`] header, or if **any** file's length is not exactly what
    /// `metadata.bin`'s counts say it should be.
    ///
    /// # Why the lengths are checked, and checked exactly
    ///
    /// The version lives in `metadata.bin` while the layouts live in `nodes.bin`
    /// and `edges.bin`, so a matching version field cannot catch a pack directory
    /// assembled from two vintages — only the lengths can. `nodes.bin`'s length used
    /// not to be checked at all, and [`Graph::node`] documents that callers
    /// guarantee `id <= node_count` with no bounds test, so a short or stale file
    /// read straight past the mapping.
    ///
    /// Exactly, not "at least", because a file that is too *long* is just as much a
    /// vintage mismatch as one that is too short, and one comparison catches both.
    ///
    /// `road_names.bin` is the one file with no count to check against: it is a
    /// byte pool whose size is its own, and [`Graph::road_name`] bounds every read
    /// on it instead.
    ///
    /// `intermediate.bin` is mandatory, not optional. The generator collapses
    /// degree-2 chains, so an edge is a whole road between junctions and its
    /// polyline is the only record of the road's shape. Without the file,
    /// `find_nearest_edge` would project onto a straight chord spanning an entire
    /// street: snapping and drawn geometry would both be wrong, not merely
    /// coarse. Refusing to load says so instead of routing badly.
    pub fn load(base: &str) -> Option<Graph> {
        let mut base = base.to_string();
        if !base.is_empty() && !base.ends_with('/') {
            base.push('/');
        }

        // metadata.bin: u32 magic, u32 version, then a u64 count per section that
        // needs one — node_count, edge_count, escape_count, named_edges. Scoped so
        // the mapping is released before the big regions are mapped.
        //
        // Explicit count fields rather than a generic section directory: each
        // version adds one, and a named field is more debuggable than speculative
        // generality.
        let (node_count, edge_count, escape_count, named_edges, blocked_v7, varint_v8) = {
            let meta = MmapRegion::map(&format!("{base}metadata.bin"))?;
            if meta.len < 40 {
                return None;
            }
            let magic = unsafe { read_at::<u32>(meta.base(), 0) };
            let version = unsafe { read_at::<u32>(meta.base(), 1) };
            if magic != GRAPH_MAGIC
                || !(GRAPH_VERSION_MIN..=GRAPH_VERSION).contains(&version)
            {
                return None;
            }
            let nodes = unsafe { read_at::<u64>(meta.base().add(8), 0) };
            let edges = unsafe { read_at::<u64>(meta.base().add(16), 0) };
            let escapes = unsafe { read_at::<u64>(meta.base().add(24), 0) };
            let named = unsafe { read_at::<u64>(meta.base().add(32), 0) };
            // `edge_ptr` is a `u32`, so an edge count past that ceiling would have
            // wrapped in the writer and pointed a node at another node's range. The
            // generator refuses to produce one; this refuses to read one. Neither
            // sparse table can describe more edges than exist.
            if u32::try_from(edges).is_err() || escapes > edges || named > edges {
                return None;
            }
            (
                u32::try_from(nodes).ok()?,
                edges,
                escapes,
                named,
                version >= tilecodec::mamaps::archive::GRAPH_VERSION_BLOCKED,
                version >= tilecodec::mamaps::archive::GRAPH_VERSION_VARINT,
            )
        };
        let intermediate_block = if varint_v8 { INTERMEDIATE_BLOCK_V8 } else { INTERMEDIATE_BLOCK };

        // `edge_count` now comes from the header rather than from `edges.bin`'s
        // length. That is the point of version 4, and 5 and 6 are why: the record is
        // 7 bytes and the file has four more sections behind it, so its length is no
        // longer any simple multiple of anything.
        //
        // v7 packs the record arrays as ZBLK sections, so their lengths are
        // data-dependent: the blocked views below replace the exact-length
        // assertions, validating unpacked lengths instead.
        //
        // `version` is scoped to the metadata block above; `blocked_v7` rides
        // out with the counts.
        let nodes_region = MmapRegion::map(&format!("{base}nodes.bin"))?;
        let edges_region = MmapRegion::map(&format!("{base}edges.bin"))?;
        // nodes.bin is `NodeRec[node_count + 1]`: one sentinel record past the real
        // nodes, whose `edge_ptr` is `edge_count`. Eight call sites read it.
        let want_nodes =
            (u64::from(node_count) + 1) * std::mem::size_of::<NodeRec>() as u64;
        let nodes_view = if blocked_v7 {
            let bytes = unsafe {
                std::slice::from_raw_parts(nodes_region.base(), nodes_region.len)
            };
            let view = BlockedView::parse(bytes)?;
            if view.unpacked_len != want_nodes || view.record_len != 12 {
                return None;
            }
            Some(view)
        } else {
            if nodes_region.len as u64 != want_nodes {
                return None;
            }
            None
        };
        let _nodes = nodes_region.base();

        // edges.bin's five sections, computed forwards from the metadata counts.
        // The total-length assertion below is what replaces v3's self-locating
        // trailer: if the computed sections do not exactly consume the region, the
        // file is not the one these counts describe. Under v7 the record array
        // is a ZBLK section whose end the view reports; the escapes and names
        // follow it exactly as before.
        let escape_blocks = edge_count.div_ceil(ESCAPE_BLOCK) + 1;
        let (escape_first_off, edges_view) = if blocked_v7 {
            let bytes = unsafe {
                std::slice::from_raw_parts(edges_region.base(), edges_region.len)
            };
            let view = BlockedView::parse(bytes)?;
            if view.unpacked_len != edge_count * std::mem::size_of::<EdgeRec>() as u64
                || view.record_len != 7
            {
                return None;
            }
            // The records section ends at the last block offset; escapes follow.
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
        if edges_region.len as u64 != want_edges {
            return None;
        }
        // Table base is the file base unconditionally: only the record array
        // itself is blocked-compressed under v7 (see the single-archive
        // `assemble` — same fix, same on-device SIGSEGV). The per-record
        // readers branch onto the inflated view (see `edge_rec`).
        let edges = edges_region.base();
        let escape_first = unsafe { edges.add(escape_first_off as usize) };
        let escapes = unsafe { edges.add(escapes_off as usize) };
        let names = EdgeBitmap {
            rank: unsafe { edges.add(names_off as usize) },
            present: unsafe {
                edges.add((names_off + EdgeBitmap::present_offset(edge_count)) as usize)
            },
        };
        let name_off = unsafe { edges.add(name_off_off as usize) };
        // The block index's last entry is the row total, and the rank index's is the
        // named-edge total. Both are the direct analogue of `intermediate.bin`'s
        // `rank_total == geometry_edges` check: they tie an index to the section it
        // indexes rather than trusting both to have been written by the same run.
        if unsafe { read_at::<u32>(escape_first, (escape_blocks - 1) as usize) } as u64
            != escape_count
            || names.total(edge_count) != named_edges
        {
            return None;
        }
        // Rows ascend, so bounding the last one bounds them all.
        if escape_count > 0 {
            let last = unsafe { read_at::<EscapeRow>(escapes, (escape_count - 1) as usize) };
            if u64::from(last.edge_idx) >= edge_count {
                return None;
            }
        }

        // intermediate.bin:
        //   [ blob ][ u64 rank[..] ][ u8 present[..] ][ u64 coarse[..] ]
        //   [ u16 within[..] ][ u64 G ]
        // Everything is recoverable from the last 8 bytes plus `edge_count`: the
        // tables are sized from `G` and from `E`, and the blob is whatever is left
        // over at offset 0. Validated on both axes, because a stale file from a
        // different graph vintage would otherwise be read out of bounds.
        let intermediate_region = MmapRegion::map(&format!("{base}intermediate.bin"))?;
        let inter_len = intermediate_region.len as u64;
        if inter_len < 8 {
            return None;
        }
        let geometry_edges =
            unsafe { read_at::<u64>(intermediate_region.base().add((inter_len - 8) as usize), 0) };
        // `G` can never exceed the number of directed edges. Checking it before
        // anything is sized from it is also what stops a garbage trailer from
        // overflowing the table arithmetic below into a plausible-looking total.
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
        let intermediate_data = intermediate_region.base();
        let geometry = EdgeBitmap {
            rank: unsafe { intermediate_data.add(blob_bytes as usize) },
            present: unsafe {
                intermediate_data
                    .add((blob_bytes + EdgeBitmap::present_offset(edge_count)) as usize)
            },
        };
        let intermediate_coarse = unsafe { intermediate_data.add((blob_bytes + bitmap_bytes) as usize) };
        let intermediate_within = unsafe { intermediate_coarse.add(coarse_bytes as usize) };
        // The two halves of the trailer have to agree with each other and with the
        // blob: the sentinel offset is the blob's length, and the rank total is
        // `G`. Both are cheap, and between them they catch a truncated file, a
        // mismatched vintage and a generator that lost count.
        let sentinel = unsafe {
            read_at::<u64>(
                intermediate_coarse,
                (geometry_edges / intermediate_block) as usize,
            ) + u64::from(read_at::<u16>(intermediate_within, geometry_edges as usize))
        };
        if sentinel != blob_bytes || geometry.total(edge_count) != geometry_edges {
            return None;
        }

        let road_names_region = MmapRegion::map(&format!("{base}road_names.bin"));
        let (road_names, road_names_size) = match &road_names_region {
            Some(r) => (r.base(), r.len),
            None => (ptr::null(), 0),
        };

        // lanes.bin: [ u32 n ][ LaneEntry[n + 1] ][ u16 lane-mask blob ]. The index
        // is sized by its own header rather than by `edge_count`, so it is validated
        // against that; a truncated/mismatched file disables real lanes so routing
        // falls back to topology inference.
        let lanes_region = MmapRegion::map(&format!("{base}lanes.bin"));
        let lanes = lanes_region.as_ref().and_then(|r| {
            if r.len < 4 {
                return None;
            }
            let n = unsafe { read_at::<u32>(r.base(), 0) };
            let index = unsafe { r.base().add(4) };
            let index_bytes = 4 + (u64::from(n) + 1) * std::mem::size_of::<LaneEntry>() as u64;
            if (r.len as u64) < index_bytes {
                return None;
            }
            // The sentinel entry's offset is the blob length, so checking it
            // validates the blob as well as the index. That matters because
            // `edge_lane_masks` reads at offsets taken straight from the file.
            // Exactly, not "at least": the writer emits header + index + blob and
            // nothing else, so a longer file is a vintage mismatch too.
            let sentinel = unsafe { read_at::<LaneEntry>(index, n as usize) };
            if r.len as u64 != index_bytes + u64::from(sentinel.blob_off) {
                return None;
            }
            // Nothing else in this file mentions `edge_count`, so a stale-vintage
            // `lanes.bin` whose own header is self-consistent would otherwise load
            // and attribute masks to the wrong edges. Entries ascend, so bounding
            // the last one bounds them all.
            if n > 0 {
                let last = unsafe { read_at::<LaneEntry>(index, n as usize - 1) };
                if u64::from(last.edge_idx) >= edge_count {
                    return None;
                }
            }
            Some((index, n, unsafe { r.base().add(index_bytes as usize) }))
        });
        let (lane_index, lane_edges, lane_data) = lanes.unwrap_or((ptr::null(), 0, ptr::null()));

        // elevation.bin: `i16[node_count]`, one per real node in node-id order. Optional and
        // validated by length alone — a stale-vintage file whose node_count differs is simply
        // disabled (elevation reads 0), the same self-check lanes.bin does against `edge_count`.
        // Under v7 the file is a ZBLK section, dispatched on its magic: blocked
        // parses as blocked, a raw `N * 2` file as raw, anything else as absent.
        let elevation_region = MmapRegion::map(&format!("{base}elevation.bin"));
        let (elevation, elevation_view) = match &elevation_region {
            Some(r) if blocked_v7 => {
                let bytes =
                    unsafe { std::slice::from_raw_parts(r.base(), r.len) };
                match BlockedView::parse(bytes) {
                    Some(view)
                        if view.unpacked_len
                            == u64::from(node_count)
                                * std::mem::size_of::<i16>() as u64
                            && view.record_len == 2 =>
                    {
                        (ptr::null(), Some(view))
                    }
                    _ => (ptr::null(), None),
                }
            }
            Some(r)
                if r.len as u64
                    == u64::from(node_count) * std::mem::size_of::<i16>() as u64 =>
            {
                (r.base(), None)
            }
            _ => (ptr::null(), None),
        };
        // A wrong-length elevation.bin is dropped, not kept mapped: nothing should read a stale
        // vintage's bytes, and holding the region alive would imply it is usable. A valid v7
        // blocked view has a null raw pointer by design (see `node_elevation`), so it must not
        // drop the region its frames live in.
        let elevation_region = if elevation.is_null() && elevation_view.is_none() {
            None
        } else {
            elevation_region
        };

        let nodes = nodes_region.base();
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
        // Heuristic speed MUST be >= the fastest achievable edge speed (which is
        // clamped to MAX_DRIVING_KMH in get_edge_time_10ms) so the heuristic stays
        // consistent for the monotonic radix heap. See MAX_DRIVING_KMH.
        time_scale_fixed[DRIVING as usize] = calc_scale(MAX_DRIVING_KMH / 3.6);
        // PUBLIC_TRANSIT only ever walks in the road graph (see is_mode_allowed),
        // so it MUST use walk speed. Leaving it at a vehicle speed makes the A*
        // heuristic wildly over-optimistic: still correct, but catastrophically
        // slow because almost the whole graph gets expanded.
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

        Some(Graph {
            _nodes_region: Some(nodes_region),
            _edges_region: Some(edges_region),
            _intermediate_region: Some(intermediate_region),
            _road_names_region: road_names_region,
            _lanes_region: lanes_region,
            _elevation_region: elevation_region,
            _archive_region: None,
            _owned_buffers: Vec::new(),
            nodes,
            node_count,
            edge_count,
            edges,
            escape_first,
            escapes,
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
