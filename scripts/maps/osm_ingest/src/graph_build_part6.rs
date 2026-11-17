    use super::*;
    use crate::tags::{LANE_LEFT, LANE_THROUGH};
    use crate::testpbf;

    struct Outputs {
        meta: Vec<u8>,
        nodes: Vec<u8>,
        edges: Vec<u8>,
        lanes: Vec<u8>,
        names: Vec<u8>,
        inter: Vec<u8>,
    }

    fn read_outputs(dir: &Path) -> Outputs {
        let f = |n: &str| std::fs::read(dir.join(n)).unwrap();
        let meta = f("metadata.bin");
        let edge_count = u64::from_le_bytes(meta[16..24].try_into().unwrap());
        // v7 stores the record arrays as ZBLK sections. The helpers below
        // mirror the device's raw-table reads, so the boundary inflates back
        // to the v6 shape (records + original tail): the container has its
        // own unit tests in tilecodec, and these tests own the content.
        // `local_of`/`node_at` read nodes the same way.
        let nodes = unpack_or_raw(&f("nodes.bin"));
        let edges_raw = f("edges.bin");
        let edges = match tilecodec::blocked::Blocked::parse(&edges_raw) {
            Ok(v) => {
                if v.unpacked_len() != edge_count * 7 || v.record_len() != 7 {
                    panic!("blocked edges unpack to the wrong records");
                }
                let mut cache = tilecodec::blocked::BlockCache::new(0);
                let mut recs = Vec::with_capacity(v.unpacked_len() as usize);
                for b in 0..v.block_count() {
                    recs.extend_from_slice(cache.get(&v, b).unwrap());
                }
                // The v6 tail (pad + escapes + names) starts pad-aligned after
                // the records section — NOT at the section end, which carries
                // its own alignment padding. Slice from there.
                let rec_end = v.block_offset(v.block_count()).unwrap() as usize;
                let tail_at = (rec_end + 7) & !7;
                let mut out = recs;
                while out.len() % 8 != 0 {
                    out.push(0);
                }
                out.extend_from_slice(&edges_raw[tail_at..]);
                out
            }
            Err(_) => edges_raw,
        };
        Outputs {
            meta,
            nodes,
            edges,
            lanes: f("lanes.bin"),
            names: f("road_names.bin"),
            inter: f("intermediate.bin"),
        }
    }

    /// Inflate a ZBLK section, or return the bytes untouched when they are not
    /// one (v6 raw tables pass through).
    fn unpack_or_raw(bytes: &[u8]) -> Vec<u8> {
        let Ok(v) = tilecodec::blocked::Blocked::parse(bytes) else {
            return bytes.to_vec();
        };
        let mut cache = tilecodec::blocked::BlockCache::new(0);
        let mut out = Vec::with_capacity(v.unpacked_len() as usize);
        for b in 0..v.block_count() {
            out.extend_from_slice(cache.get(&v, b).unwrap());
        }
        assert_eq!(out.len() as u64, v.unpacked_len());
        out
    }

    fn node_at(o: &Outputs, i: usize) -> (i32, i32, u64) {
        let b = &o.nodes[i * 12..i * 12 + 12];
        (
            i32::from_le_bytes(b[0..4].try_into().unwrap()),
            i32::from_le_bytes(b[4..8].try_into().unwrap()),
            u64::from(u32::from_le_bytes(b[8..12].try_into().unwrap())),
        )
    }

    /// A `u64` field of `metadata.bin` at byte `at`.
    fn meta_u64(o: &Outputs, at: usize) -> u64 {
        u64::from_le_bytes(o.meta[at..at + 8].try_into().unwrap())
    }

    /// `edges.bin`, located and decoded exactly as `graph.rs::load` does: the record
    /// array sized from `edge_count`, padding to the section boundary, the escape
    /// block index and the rows.
    ///
    /// Every accessor below is a literal mirror of the device's, which is what makes
    /// these contract tests rather than a re-read of our own tables.
    ///
    /// The record array itself is owned: v6 files read it in place, v7 files
    /// inflate their ZBLK records here (the writer emits v7 only). The tail
    /// (escapes, names) stays borrowed — its layout relative to its own start
    /// is identical in both versions.
    struct Edges<'a> {
        bytes: &'a [u8],
        records: Vec<u8>,
        edge_count: u64,
        first_at: usize,
        rows_at: usize,
        /// The name presence bitmap's rank index, then the bitmap, then the offsets.
        name_rank_at: usize,
        name_present_at: usize,
        name_off_at: usize,
    }

    impl<'a> Edges<'a> {
        fn new(
            bytes: &'a [u8],
            edge_count: u64,
            escape_count: u64,
        ) -> Edges<'a> {
            // The records may be a ZBLK section (v7 writer) or raw (v6
            // fixtures). Either way `records` holds the unpacked array and
            // the tail offsets below are relative to the tail start.
            let (records, tail_base) = match tilecodec::blocked::Blocked::parse(bytes) {
                Ok(v) => {
                    assert_eq!(v.unpacked_len(), edge_count * EDGE_REC_BYTES);
                    assert_eq!(v.record_len(), EDGE_REC_BYTES as usize);
                    let mut cache = tilecodec::blocked::BlockCache::new(0);
                    let mut recs = Vec::with_capacity(v.unpacked_len() as usize);
                    for b in 0..v.block_count() {
                        recs.extend_from_slice(cache.get(&v, b).unwrap());
                    }
                    let tail = v.block_offset(v.block_count()).unwrap() as usize;
                    // The tail the writer appended starts pad-aligned after
                    // the records section.
                    (recs, (tail + 7) & !7)
                }
                Err(_) => {
                    // v6 raw tail (or a reassembled v6-shape buffer): records
                    // are the first E*7 bytes, the tail starts pad-aligned.
                    let n = (edge_count * EDGE_REC_BYTES) as usize;
                    (bytes[..n].to_vec(), align_up(n as u64, SECTION_ALIGN) as usize)
                }
            };
            // Tail layout from its own start: pad, escape index, rows, pad,
            // names. Identical in both versions; only the base moves.
            let first_at = 0usize;
            let blocks = edge_count.div_ceil(ESCAPE_BLOCK) + 1;
            let rows_at = first_at + (blocks * 4) as usize;
            let name_rank_at =
                align_up(rows_at as u64 + escape_count * 12, SECTION_ALIGN) as usize;
            let rank_bytes = ((edge_count.div_ceil(RANK_BLOCK_BITS) + 1) * 8) as usize;
            Edges {
                bytes: &bytes[tail_base..],
                records,
                edge_count,
                first_at,
                rows_at,
                name_rank_at,
                name_present_at: name_rank_at + rank_bytes,
                name_off_at: name_rank_at + PresenceBitmap::bytes(edge_count) as usize,
            }
        }

        fn of(o: &'a Outputs) -> Edges<'a> {
            Edges::new(&o.edges, meta_u64(o, 16), meta_u64(o, 24))
        }

        /// Unpacked record-array length. The v7 container check.
        fn records_len(&self) -> usize {
            self.records.len()
        }

        /// True when edge `idx` has a name, from the presence bitmap.
        fn named(&self, idx: u64) -> bool {
            self.bytes[self.name_present_at + (idx / 8) as usize] & (1u8 << (idx % 8)) != 0
        }

        /// Edge `idx`'s rank in the name bitmap, computed as `graph.rs` does: the
        /// block's stored count plus a popcount of the bytes below it.
        fn name_rank(&self, idx: u64) -> u64 {
            let byte = (idx / 8) as usize;
            let block = byte / RANK_BLOCK_BYTES;
            let at = self.name_rank_at + block * 8;
            let mut n = u64::from_le_bytes(self.bytes[at..at + 8].try_into().unwrap());
            for b in block * RANK_BLOCK_BYTES..byte {
                n += u64::from(self.bytes[self.name_present_at + b].count_ones());
            }
            let partial = self.bytes[self.name_present_at + byte] & ((1u8 << (idx % 8)) - 1);
            n + u64::from(partial.count_ones())
        }

        /// Edge `idx`'s name offset, or [`NO_NAME`] when it has none.
        fn name_offset(&self, idx: u64) -> u32 {
            if !self.named(idx) {
                return NO_NAME;
            }
            let at = self.name_off_at + (self.name_rank(idx) * 4) as usize;
            u32::from_le_bytes(self.bytes[at..at + 4].try_into().unwrap())
        }

        fn escape_first(&self, block: u64) -> u32 {
            let at = self.first_at + (block * 4) as usize;
            u32::from_le_bytes(self.bytes[at..at + 4].try_into().unwrap())
        }

        /// `(edge_idx, target, dist_mm)` of escape row `r`.
        fn row(&self, r: u64) -> (u32, u32, u32) {
            let b = &self.bytes[self.rows_at + (r * 12) as usize..];
            (
                u32::from_le_bytes(b[0..4].try_into().unwrap()),
                u32::from_le_bytes(b[4..8].try_into().unwrap()),
                u32::from_le_bytes(b[8..12].try_into().unwrap()),
            )
        }

        /// `(target, dist_mm, name_offset, type_, speed_limit)` of edge `idx`, whose
        /// source is `source` — decoded as `Graph::edge` and
        /// `Graph::edge_name_offset` do, escape table and name bitmap included.
        fn get(&self, source: u32, idx: u64) -> (u32, u32, u32, u8, u8) {
            assert!(idx < self.edge_count, "edge {idx} is past the pack");
            let b = &self.records[(idx * EDGE_REC_BYTES) as usize..];
            let delta = i16::from_le_bytes([b[0], b[1]]);
            let dist = u32::from_le_bytes([b[2], b[3], b[4], 0]);
            let (type_, speed) = (b[5], b[6]);
            let name = self.name_offset(idx);
            if delta == TARGET_DELTA_ESCAPE || dist == DIST_MM_ESCAPE {
                let block = idx / ESCAPE_BLOCK;
                for r in self.escape_first(block)..self.escape_first(block + 1) {
                    let (row_idx, target, dist_mm) = self.row(u64::from(r));
                    if u64::from(row_idx) == idx {
                        return (target, dist_mm, name, type_, speed);
                    }
                }
                panic!("edge {idx} carries a sentinel but has no escape row");
            }
            (source.wrapping_add_signed(i32::from(delta)), dist, name, type_, speed)
        }
    }

    /// The edge's source: the **largest** node index whose `edge_ptr <= idx`, which
    /// is how `find_node_idx_for_edge` defines it. Taking the first such node instead
    /// would land on an empty range wherever degree-0 nodes share an `edge_ptr`.
    fn source_of(o: &Outputs, idx: u64) -> u32 {
        (0..node_count(o))
            .rev()
            .find(|v| node_at(o, *v).2 <= idx)
            .unwrap_or(0) as u32
    }

    /// `(target, dist_mm, name_offset, type_, speed_limit)` of edge `i`, with the
    /// source recovered from `nodes.bin`. Keeps every existing caller unchanged
    /// across the delta encoding, and exercises the source recovery as a side effect.
    fn edge_at(o: &Outputs, i: usize) -> (u32, u32, u32, u8, u8) {
        Edges::of(o).get(source_of(o, i as u64), i as u64)
    }

    fn name_at(o: &Outputs, off: u32) -> String {
        let start = off as usize;
        let end = start + o.names[start..].iter().position(|b| *b == 0).unwrap();
        String::from_utf8(o.names[start..end].to_vec()).unwrap()
    }

    fn edge_count(o: &Outputs) -> u64 {
        meta_u64(o, 16)
    }

    fn node_count(o: &Outputs) -> usize {
        o.nodes.len() / 12 - 1
    }

    /// `intermediate.bin`, located exactly as `graph.rs::load` does: `G` from the
    /// last 8 bytes, the four trailer tables sized from `G` and the edge count, and
    /// the blob as whatever is left over at offset 0.
    ///
    /// Every accessor below is a literal mirror of the device's, which is what makes
    /// these contract tests rather than a re-read of our own tables.
    struct Inter<'a> {
        bytes: &'a [u8],
        geometry_edges: u64,
        blob_bytes: usize,
        rank_at: usize,
        present_at: usize,
        coarse_at: usize,
        within_at: usize,
    }

    impl<'a> Inter<'a> {
        fn new(bytes: &'a [u8], edge_count: u64) -> Inter<'a> {
            let len = bytes.len();
            let geometry_edges = u64::from_le_bytes(bytes[len - 8..].try_into().unwrap());
            let rank_bytes = ((edge_count.div_ceil(RANK_BLOCK_BITS) + 1) * 8) as usize;
            let present_bytes = edge_count.div_ceil(8) as usize;
            let coarse_bytes =
                ((geometry_edges.div_ceil(INTERMEDIATE_BLOCK) + 1) * 8) as usize;
            let within_bytes = ((geometry_edges + 1) * 2) as usize;
            let blob_bytes =
                len - (rank_bytes + present_bytes + coarse_bytes + within_bytes + 8);
            Inter {
                bytes,
                geometry_edges,
                blob_bytes,
                rank_at: blob_bytes,
                present_at: blob_bytes + rank_bytes,
                coarse_at: blob_bytes + rank_bytes + present_bytes,
                within_at: blob_bytes + rank_bytes + present_bytes + coarse_bytes,
            }
        }

        /// Whether edge `i` stores a polyline of its own.
        fn present(&self, i: u64) -> bool {
            self.bytes[self.present_at + (i / 8) as usize] & (1u8 << (i % 8)) != 0
        }

        /// Set bits below `i`: the rank block, then a `popcount` of the whole bytes
        /// after it and of the partial byte `i` falls in.
        fn rank(&self, i: u64) -> u64 {
            let byte = (i / 8) as usize;
            let block = byte / RANK_BLOCK_BYTES;
            let at = self.rank_at + block * 8;
            let mut n = u64::from_le_bytes(self.bytes[at..at + 8].try_into().unwrap());
            for b in block * RANK_BLOCK_BYTES..byte {
                n += u64::from(self.bytes[self.present_at + b].count_ones());
            }
            let partial = self.bytes[self.present_at + byte] & ((1u8 << (i % 8)) - 1);
            n + u64::from(partial.count_ones())
        }

        /// Blob offset of the `g`-th geometry edge, reassembled from both levels.
        fn offset(&self, g: u64) -> u64 {
            let cb = self.coarse_at + (g / INTERMEDIATE_BLOCK) as usize * 8;
            let coarse = u64::from_le_bytes(self.bytes[cb..cb + 8].try_into().unwrap());
            let wb = self.within_at + g as usize * 2;
            let within = u16::from_le_bytes(self.bytes[wb..wb + 2].try_into().unwrap());
            coarse + u64::from(within)
        }

        /// Edge `i`'s stored polyline bytes, or `None` when it stores none — which
        /// is how the device spells "no geometry" now that a zero-length blob is a
        /// valid chord.
        fn blob(&self, i: u64) -> Option<&'a [u8]> {
            if !self.present(i) {
                return None;
            }
            let g = self.rank(i);
            let (s, e) = (self.offset(g) as usize, self.offset(g + 1) as usize);
            Some(&self.bytes[s..e])
        }
    }

    fn inter(o: &Outputs) -> Inter<'_> {
        Inter::new(&o.inter, edge_count(o))
    }

    /// `lanes.bin`'s sparse index as `(edge_idx, blob_byte_off)` pairs, the trailing
    /// sentinel included, plus the blob behind it.
    fn lane_index(o: &Outputs) -> (Vec<(u32, u32)>, Vec<u16>) {
        let n = u32::from_le_bytes(o.lanes[0..4].try_into().unwrap()) as usize;
        let index: Vec<(u32, u32)> = (0..=n)
            .map(|i| {
                let b = &o.lanes[4 + i * 8..4 + i * 8 + 8];
                (
                    u32::from_le_bytes(b[0..4].try_into().unwrap()),
                    u32::from_le_bytes(b[4..8].try_into().unwrap()),
                )
            })
            .collect();
        let blob = o.lanes[4 + (n + 1) * 8..]
            .chunks_exact(2)
            .map(|c| u16::from_le_bytes(c.try_into().unwrap()))
            .collect();
        (index, blob)
    }

    /// The device's `edge_lane_masks`: the same lower-bound binary search over the
    /// sparse index, so a divergence between the search and the file shows up here
    /// rather than on a phone.
    fn lane_masks(o: &Outputs, edge_idx: u64) -> Option<Vec<u16>> {
        let (index, blob) = lane_index(o);
        let n = index.len() - 1;
        let want = u32::try_from(edge_idx).ok()?;
        let mut lo = 0usize;
        let mut hi = n;
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            if index[mid].0 < want {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if lo >= n || index[lo].0 != want {
            return None;
        }
        let (start, end) = (index[lo].1 as usize / 2, index[lo + 1].1 as usize / 2);
        (end > start).then(|| blob[start..end].to_vec())
    }

    /// The local id whose coordinates match fixture node `osm_id`, or `None` if
    /// that node was collapsed away.
    fn local_of(o: &Outputs, osm_id: i64) -> Option<u32> {
        let (_, lat, lon) = testpbf::NODES.iter().find(|n| n.0 == osm_id).copied().unwrap();
        (0..node_count(o) as u32).find(|i| {
            let (l, g, _) = node_at(o, *i as usize);
            (l, g) == (lat, lon)
        })
    }

    /// Reimplementation of `graph.rs`'s `get_edge_coordinates`, reverse-geometry
    /// lookup included. The point of decoding it this way rather than reading our
    /// own chain table back is that it is the device's algorithm that has to
    /// agree with the file.
    fn edge_coords(o: &Outputs, edge_idx: u64) -> Option<(Vec<(i32, i32)>, bool)> {
        let (target, _, _, type_, _) = edge_at(o, edge_idx as usize);
        let t = inter(o);
        let pt = |n: u32| {
            let (lat, lon, _) = node_at(o, n as usize);
            (lat, lon)
        };
        // The blob is interior-only, so the two endpoints come from `nodes.bin`.
        let decode = |k: u64, from: u32, to: u32| -> Option<Vec<(i32, i32)>> {
            Some(geom::decode(t.blob(k)?, pt(from), pt(to)))
        };
        // Find the source by the same monotonic search the device uses.
        let u = (0..node_count(o) as u32)
            .rev()
            .find(|i| node_at(o, *i as usize).2 <= edge_idx)
            .unwrap();
        if type_ & REVERSE_GEOMETRY_FLAG != 0 {
            let s = node_at(o, target as usize).2;
            let e = node_at(o, target as usize + 1).2;
            for k in s..e {
                if edge_at(o, k as usize).0 == u {
                    // The twin runs `target -> u`, so it is seeded and terminated
                    // the other way round. `get_pt_at` is what flips it on device,
                    // so flip it here too and hand callers a source-to-target
                    // polyline either way.
                    let mut pts = decode(k, target, u)?;
                    pts.reverse();
                    return Some((pts, true));
                }
            }
            return None;
        }
        Some((decode(edge_idx, u, target)?, false))
    }

    #[test]
    fn rank_agrees_with_a_brute_force_popcount() {
        // Ids chosen to straddle byte and 512-bit block boundaries, since those
        // are the two places the three-term rank can be off by one.
        let ids: Vec<u64> = vec![
            0, 1, 7, 8, 9, 63, 64, 65, 511, 512, 513, 1023, 1024, 4095, 4096, 9999,
        ];
        let mut b = Bitset::new(*ids.last().unwrap());
        for id in &ids {
            assert!(b.set(*id), "id {id} listed twice");
        }
        let total = b.build_rank(*ids.last().unwrap());
        assert_eq!(total, ids.len() as u64);

        // A set bit's dense id is its position in the sorted set of set ids...
        for (want, id) in ids.iter().enumerate() {
            assert_eq!(b.dense(*id), want as u32, "id {id}");
        }
        // ...and for every id, set or not, the rank is the brute-force count of
        // set bits strictly below it.
        for id in 0..=*ids.last().unwrap() {
            let want = ids.iter().filter(|x| **x < id).count() as u32;
            assert_eq!(b.dense(id), want, "id {id}");
        }
    }

    #[test]
    fn an_empty_bitset_ranks_everything_to_zero() {
        let mut b = Bitset::new(1024);
        assert_eq!(b.build_rank(1024), 0);
        assert_eq!(b.dense(0), 0);
        assert_eq!(b.dense(1024), 0);
        assert!(!b.get(7));
    }

    #[test]
    fn setting_a_bit_twice_is_reported_once() {
        let mut b = Bitset::new(64);
        assert!(b.set(9));
        assert!(!b.set(9));
        assert!(b.get(9));
        assert_eq!(b.build_rank(64), 1);
    }

