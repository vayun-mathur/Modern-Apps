
impl Graph {
    /// Byte offset of edge `idx`'s name in the string pool, or `None` when it has
    /// none.
    ///
    /// Separate from [`Edge`] because two thirds of edges are unnamed — 62.3% of
    /// California's, 68.9% of Europe's — and this is read on the
    /// instruction-emission path, never in the A* relaxation. So the field is a
    /// sparse side table: three loads instead of one, for the third of edges that
    /// have a name and nothing at all for the rest.
    ///
    /// Being the *sole* reader is what made that possible. The A* relaxation used to
    /// copy this into the scratchpad on every improving edge — into a field nothing
    /// ever read — and until that store went away no sparse representation was
    /// affordable.
    #[inline]
    pub fn edge_name_offset(&self, idx: u64) -> Option<u32> {
        if !self.names.contains(idx) {
            return None;
        }
        Some(unsafe { read_at::<u32>(self.name_off, self.names.rank_of(idx) as usize) })
    }

    /// Real OSM per-lane turn-indication masks for directed edge `idx`, ordered
    /// left→right. Each `u16` is a set of `LANE_*` bits. Returns `None` when the
    /// graph has no lane data or this edge carries none (callers then fall back
    /// to topology-inferred lanes).
    ///
    /// The index is sparse, so "this edge has no lanes" is spelled as absence from
    /// it and found by binary search. `turn:lanes` is rare — 2.9 M of a planet's
    /// 1.07 G edges — and this is called once per maneuver rather than per edge,
    /// so ~22 probes buys back 8.6 GB of dense offset table.
    pub fn edge_lane_masks(&self, idx: u64) -> Option<Vec<u16>> {
        if self.lane_index.is_null() || idx >= self.edge_count {
            return None;
        }
        let want = u32::try_from(idx).ok()?;
        let entry = |i: u32| unsafe { read_at::<LaneEntry>(self.lane_index, i as usize) };
        // Lower bound over the real entries; the trailing sentinel is only ever
        // read for its offset, which is how the last edge gets a length.
        let mut lo = 0u32;
        let mut hi = self.lane_edges;
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            if entry(mid).edge_idx < want {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if lo >= self.lane_edges || entry(lo).edge_idx != want {
            return None;
        }
        let start = entry(lo).blob_off;
        let end = entry(lo + 1).blob_off;
        if end <= start {
            return None;
        }
        let count = ((end - start) as usize) / std::mem::size_of::<u16>();
        let mut out = Vec::with_capacity(count);
        for k in 0..count {
            let byte_off = start as usize + k * std::mem::size_of::<u16>();
            let mask = unsafe { (self.lane_data.add(byte_off) as *const u16).read_unaligned() };
            out.push(mask);
        }
        Some(out)
    }

    /// Byte offset of the `g`-th geometry edge's polyline in the intermediate blob.
    ///
    /// Two loads instead of one: a `u64` per [`INTERMEDIATE_BLOCK`] geometry edges
    /// plus a `u16` each. Same cost profile, and 1.5 GB smaller at planet scale
    /// than one `u64` apiece.
    #[inline]
    fn geometry_offset(&self, g: u64) -> u64 {
        let coarse =
            unsafe { read_at::<u64>(self.intermediate_coarse, (g / self.intermediate_block) as usize) };
        let within = unsafe { read_at::<u16>(self.intermediate_within, g as usize) };
        coarse + u64::from(within)
    }

    /// `(byte offset, length)` of edge `idx`'s stored polyline, or `None` when it
    /// stores none.
    #[inline]
    fn intermediate_range(&self, idx: u64) -> Option<(u64, u32)> {
        if !self.geometry.contains(idx) {
            return None;
        }
        let g = self.geometry.rank_of(idx);
        let start = self.geometry_offset(g);
        let end = self.geometry_offset(g + 1);
        Some((start, (end - start) as u32))
    }

    /// Borrow a NUL-terminated road/feed/stop name from the string pool at
    /// `offset`. Returns `None` for the sentinel or out-of-range offsets.
    pub fn road_name(&self, offset: u32) -> Option<String> {
        if offset == NO_NAME || (offset as usize) >= self.road_names_size {
            return None;
        }
        Some(self.cstr_at(offset as usize))
    }

    /// Read the NUL-terminated string starting at byte `offset` in the pool.
    fn cstr_at(&self, offset: usize) -> String {
        unsafe {
            let start = self.road_names.add(offset);
            let mut len = 0usize;
            while offset + len < self.road_names_size && *start.add(len) != 0 {
                len += 1;
            }
            let slice = std::slice::from_raw_parts(start, len);
            String::from_utf8_lossy(slice).into_owned()
        }
    }

    /// The node whose edge range `edge_idx` falls in, i.e. the edge's source.
    ///
    /// Found by binary search on the monotonic `edge_ptr`, ~29 probes on a planet.
    /// Only for the one caller that genuinely has no source: an edge index arriving
    /// from Kotlin in `updateTrafficNative`. Everything else already holds the
    /// source, because it got the edge index by iterating a node's own range.
    ///
    /// # The tie-break is a contract, not an accident
    ///
    /// This returns the **largest** node index whose `edge_ptr <= edge_idx`. That is
    /// the unique owner of the edge — any larger node's `edge_ptr` is at least
    /// `edge_ptr(owner + 1)`, which is past `edge_idx` — but it is only the same
    /// thing as the *first* such node when every node has out-degree at least one.
    /// Degree-0 nodes exist (1,480 of California's 6.4 M, 0.023%), and they make
    /// runs of nodes share an `edge_ptr`. A reimplementation that returned the first
    /// of such a run would satisfy the description "a node whose `edge_ptr <=
    /// edge_idx`" and be wrong: that node's range is empty, and the result seeds
    /// [`Graph::decode_edge_coords`], so the failure is a polyline drawn from the
    /// wrong place rather than anything that reports an error.
    pub fn find_node_idx_for_edge(&self, edge_idx: u64) -> u32 {
        let mut low: i64 = 0;
        let mut high: i64 = self.node_count as i64 - 1;
        let mut res: u32 = 0;
        while low <= high {
            let mid = low + (high - low) / 2;
            if self.node(mid as u32).edge_ptr <= edge_idx {
                res = mid as u32;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        res
    }

    /// 64-bit Morton (Z-order) code from lat/lon degrees.
    ///
    /// The node array is sorted by this, so it decides both what
    /// [`crate::routing::find_nearest_edge`]'s fixed 800-node window covers and how
    /// far apart two adjacent nodes' *indices* are. It must agree bit for bit with
    /// `latlng_to_spatial` in `scripts/maps/osm_ingest/src/spatial.rs`, which is
    /// what sorts the array; that crate has a test comparing the two
    /// transcriptions.
    ///
    /// # Hilbert was tried and is not better
    ///
    /// Z-order's known flaw is the "Z" discontinuity at every quadrant boundary:
    /// the cells either side are neighbours on the ground and up to a whole
    /// quadrant apart in index. Hilbert has no long-range jumps at all, so it looks
    /// like the obvious win for `edges.bin`, which stores `target` as an `i16`
    /// delta from its source and needs an escape table for the pairs that do not
    /// fit.
    ///
    /// Measured on California (`road_graph --stats`), it is not. The two curves
    /// cross almost exactly at the `i16` boundary:
    ///
    /// | `|target - source|` > | Morton | Hilbert |
    /// |---|---|---|
    /// | 1,024 | 2.225% | 2.155% |
    /// | 16,384 | 0.4711% | 0.4648% |
    /// | **32,767** | **0.3014%** | **0.3076%** |
    /// | 262,144 | 0.0713% | 0.0892% |
    ///
    /// Hilbert wins below ~16 k and loses above it: it concentrates the
    /// distribution rather than shortening it, trading mid-range deltas for far
    /// ones. So the quadrant discontinuities are *not* what generates the 0.3%
    /// tail — mapping a 2-D neighbourhood onto a 1-D index costs that much however
    /// it is done — and the escape table is unavoidable. Not worth a node
    /// permutation change for 2% the wrong way.
    pub fn latlng_to_spatial(lat: f64, lon: f64) -> u64 {
        let x = (lon + 180.0) / 360.0;
        let y = (lat + 90.0) / 180.0;
        let ix = (x * 4_294_967_295.0) as u32;
        let iy = (y * 4_294_967_295.0) as u32;
        let mut res: u64 = 0;
        for i in 0..32u64 {
            res |= (((ix >> i) & 1) as u64) << (2 * i);
            res |= (((iy >> i) & 1) as u64) << (2 * i + 1);
        }
        res
    }

    #[inline]
    pub fn node_spatial_id(node: &NodeMaster) -> u64 {
        Graph::latlng_to_spatial(node.lat_e7 as f64 * 1e-7, node.lon_e7 as f64 * 1e-7)
    }

    /// Decode the delta-encoded coordinate blob for `edge_idx`, whose source is
    /// `source`, into `out`. Returns `Some((count, is_reversed))`, with `count == 0`
    /// when the edge stores no polyline. `out` must hold at least 256 points.
    ///
    /// The `edges.is_null()` arm means "graph without an edge table", not
    /// "blocked layout": under v7 the base is still the section base (see
    /// `load`/`assemble`), and the per-record path reads the inflate cache.
    ///
    /// `source` must really be the node whose range `edge_idx` falls in. It seeds
    /// the decode, so a wrong one silently draws the polyline from the wrong place.
    /// Every call site iterating a node's edge range already has it; the one that
    /// does not recovers it with [`Graph::find_node_idx_for_edge`].
    pub fn get_edge_coordinates_from(
        &self,
        source: u32,
        edge_idx: u64,
        out: &mut [LatLon],
    ) -> Option<(u32, bool)> {
        if self.edges.is_null() || edge_idx >= self.edge_count {
            return None;
        }
        let e = self.edge(source, edge_idx);
        if source >= self.node_count || e.target >= self.node_count {
            return None;
        }

        if e.type_ & REVERSE_GEOMETRY_FLAG != 0 {
            // The twin runs `target -> source`, so it is seeded and terminated the
            // other way round. `get_pt_at` is what flips the result for callers.
            let (s, e_ptr) = self.edge_range(e.target);
            for k in s..e_ptr {
                if self.edge_targets(k, e.target, source) {
                    let cnt = self.decode_edge_coords(k, e.target, source, out);
                    return Some((cnt, true));
                }
            }
            None
        } else {
            let cnt = self.decode_edge_coords(edge_idx, source, e.target, out);
            Some((cnt, false))
        }
    }

    /// Delta-decode edge `idx`'s polyline into `out`, seeded with node `source`'s
    /// coordinates and terminated with node `target`'s. Returns the number of
    /// decoded points, or 0 when the edge stores no polyline.
    ///
    /// The blob holds *only* the interior points, because the first and last are
    /// always the edge's own endpoints and `nodes.bin` already has both. Nothing
    /// here distinguishes an absent blob from an empty one; the presence bitmap
    /// does, in [`Graph::intermediate_range`].
    fn decode_edge_coords(&self, idx: u64, source: u32, target: u32, out: &mut [LatLon]) -> u32 {
        let Some((data_off, byte_len)) = self.intermediate_range(idx) else {
            return 0;
        };
        let a = self.node(source);
        let mut lat = a.lat_e7;
        let mut lon = a.lon_e7;
        out[0] = LatLon {
            lat_e7: lat,
            lon_e7: lon,
        };

        let data = unsafe { self.intermediate_data.add(data_off as usize) };
        let mut count: u32 = 1;
        let mut off: u32 = 0;
        if self.intermediate_varint {
            // v8: zigzag varint deltas on the 1e-6 degree grid, seeded from the source node's
            // own grid position. Each decoded point lands on a multiple of 10 e7 units.
            let blob = unsafe { std::slice::from_raw_parts(data, byte_len as usize) };
            let mut qlat = (lat as i64).div_euclid(10) + i64::from((lat as i64).rem_euclid(10) >= 5);
            let mut qlon = (lon as i64).div_euclid(10) + i64::from((lon as i64).rem_euclid(10) >= 5);
            let mut at = 0usize;
            let mut uvar = |at: &mut usize| -> Option<u64> {
                let (mut r, mut s) = (0u64, 0u32);
                while *at < blob.len() {
                    let b = blob[*at];
                    *at += 1;
                    r |= u64::from(b & 0x7f) << s;
                    if b & 0x80 == 0 {
                        return Some(r);
                    }
                    s += 7;
                }
                None
            };
            while at < blob.len() && count + 1 < 256 {
                let (Some(a), Some(b)) = (uvar(&mut at), uvar(&mut at)) else { break };
                qlat += ((a >> 1) as i64) ^ -((a & 1) as i64);
                qlon += ((b >> 1) as i64) ^ -((b & 1) as i64);
                out[count as usize] = LatLon { lat_e7: (qlat * 10) as i32, lon_e7: (qlon * 10) as i32 };
                count += 1;
            }
            let b = self.node(target);
            out[count as usize] = LatLon { lat_e7: b.lat_e7, lon_e7: b.lon_e7 };
            return count + 1;
        }
        // One slot is reserved for the target, so the interior stops one short of
        // the 256 points every caller's buffer holds.
        while off + 4 <= byte_len && count + 1 < 256 {
            let d_lat = unsafe { (data.add(off as usize) as *const i16).read_unaligned() };
            let d_lon = unsafe { (data.add(off as usize + 2) as *const i16).read_unaligned() };
            lat = lat.wrapping_add(d_lat as i32);
            lon = lon.wrapping_add(d_lon as i32);
            out[count as usize] = LatLon {
                lat_e7: lat,
                lon_e7: lon,
            };
            count += 1;
            off += 4;
        }

        let b = self.node(target);
        out[count as usize] = LatLon {
            lat_e7: b.lat_e7,
            lon_e7: b.lon_e7,
        };
        count + 1
    }
}

/// Fetch point `idx` from a decoded coordinate buffer, honouring reversal.
#[inline]
pub fn get_pt_at(coords: &[LatLon], count: u32, is_reversed: bool, idx: u32) -> LatLon {
    if is_reversed {
        coords[(count - 1 - idx) as usize]
    } else {
        coords[idx as usize]
    }
}
