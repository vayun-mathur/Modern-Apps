/// The endpoint guarantee `intermediate.bin`'s interior-only encoding rests on: a
/// chain's first and last points are the coordinates of its first and last node.
///
/// True by construction, since both come from the same `node_coords` array, but
/// never checked before v3 — and the reader now *depends* on it, because it rebuilds
/// both ends out of `nodes.bin`. If this ever stopped holding, the failure would be
/// a silently wrong polyline on device rather than a failed build.
fn assert_endpoints(
    chain: &[geom::Pt],
    node_coords: &[geom::Pt],
    source: u32,
    target: u32,
    ci: u32,
) {
    assert_eq!(
        chain[0], node_coords[source as usize],
        "chain {ci} starts off its source node {source}"
    );
    assert_eq!(
        chain[chain.len() - 1], node_coords[target as usize],
        "chain {ci} ends off its target node {target}"
    );
}

/// One directed edge of a chain, in final id space.
fn chain_edge(c: &chains::ChainRec, ci: u32, source: u32, target: u32, rev: bool) -> TmpEdge {
    TmpEdge {
        source,
        target,
        dist_mm: c.dist_mm,
        name_offset: c.name_offset,
        type_: c.type_,
        speed_limit: c.speed_limit,
        lane_off: if rev { c.bwd_lane_off } else { c.fwd_lane_off },
        lane_count: if rev { c.bwd_lane_count } else { c.fwd_lane_count },
        chain: ci,
        chain_rev: rev,
        pts_start: c.pts_start,
        pts_len: c.pts_len,
    }
}

/// `intermediate.bin`: the polyline blob at offset 0, then everything needed to
/// index it as a **trailer**.
///
/// ```text
/// [ blob ]
/// [ u64 rank[E.div_ceil(512) + 1] ]   set bits before each 64-byte block, total appended
/// [ u8  present[E.div_ceil(8)] ]      one bit per directed edge
/// [ u64 coarse[G.div_ceil(32) + 1] ]  G = the edges that store a polyline
/// [ u16 within[G + 1] ]
/// [ u64 G ]                           fixed-size trailer
/// ```
///
/// `off(g) = coarse[g / 32] + within[g]` for the *g*-th geometry edge, where
/// `g = rank(idx)`; entry *g* runs to `off(g + 1)`, so the sentinel gives the last
/// edge its length and the blob its total.
///
/// # Why the offsets describe geometry edges, not edges
///
/// 70.4% of a planet's directed edges store no polyline at all (measured on
/// Europe: 110,807,665 of 374,856,290 store one), and under v2 each of them still
/// owned a `u16` and a repeated coarse `u64`. A presence bitmap costs one bit per
/// edge and makes "absent" an explicit, free test, which takes 0.87 GB of tables
/// where v2 needed 2.42 GB. It also tightens the `u16` bound rather than loosening
/// it: every entry in a block is now a real blob, where before most were
/// zero-length.
///
/// # Why the tables come last
///
/// `G` is not known until the final round has run, so a reserved prefix cannot be
/// sized. Streaming the blob to scratch and copying it back \u2014 what [`LaneFile`]
/// does, which is free for 13 MB \u2014 would be ~23 GB of extra I/O here. A trailer
/// instead makes the whole file append-only with **no scratch file at all**: write
/// the blob as it is encoded, then append the tables and `G`. That is strictly
/// less I/O than v2, which copied a 2.4 GB prefix back.
///
/// The tables are held in memory rather than spilled, which is 0.87 GB at planet
/// scale against the blob's 7.63 GB.
struct GeomFile {
    path: PathBuf,
    out: BufWriter<File>,
    blob_len: u64,
    /// One bit per directed edge, sized up front from the edge count.
    present: PresenceBitmap,
    coarse: Vec<u64>,
    within: Vec<u16>,
    /// Blob offset of the first geometry edge in the block being filled, i.e. the
    /// coarse entry the `u16`s are currently relative to.
    block_base: u64,
    /// Directed edges seen so far, which is the index the next call describes.
    pushed: u64,
    /// Geometry edges so far, i.e. `G` once the file is complete.
    stored: u64,
}

impl GeomFile {
    /// Byte size of the trailer for `edge_count` directed edges of which
    /// `geometry_edges` store a polyline. The reader sizes it the same way, from
    /// `edges.bin`'s length and the final `u64`.
    fn trailer_bytes(edge_count: u64, geometry_edges: u64) -> u64 {
        PresenceBitmap::bytes(edge_count)
            + (geometry_edges.div_ceil(INTERMEDIATE_BLOCK) + 1) * 8
            + (geometry_edges + 1) * 2
            + 8
    }

    fn create(dir: &Path, name: &str, edge_count: u64) -> Result<GeomFile> {
        let path = dir.join(name);
        Ok(GeomFile {
            // Large appends: the drain hands over one batch's blobs at a time, and
            // on a compress-force filesystem every extent costs CPU.
            out: BufWriter::with_capacity(WRITE_BUF_BYTES, create(&path)?),
            path,
            blob_len: 0,
            present: PresenceBitmap::new(edge_count),
            coarse: Vec::new(),
            within: Vec::new(),
            block_base: 0,
            pushed: 0,
            stored: 0,
        })
    }

    /// This edge stores no polyline. Its presence bit stays clear and it owns no
    /// offset at all, which is the whole point of the bitmap.
    fn skip(&mut self) {
        self.pushed += 1;
    }

    /// Record `bytes` as this edge's polyline. Called once per storing edge, in
    /// ascending edge index, because the rounds ascend and each round writes its
    /// edges in index order.
    fn store(&mut self, bytes: &[u8]) -> Result<()> {
        debug_assert!(
            !bytes.is_empty(),
            "an interior-only blob of no bytes is the chord, which needs no presence bit"
        );
        self.present.set(self.pushed);
        self.push_entry();
        self.out.write_all(bytes).map_err(io_err)?;
        self.blob_len += bytes.len() as u64;
        self.stored += 1;
        self.pushed += 1;
        Ok(())
    }

    /// One `(coarse?, within)` pair for geometry index `self.stored`.
    fn push_entry(&mut self) {
        if self.stored.is_multiple_of(INTERMEDIATE_BLOCK) {
            self.block_base = self.blob_len;
            self.coarse.push(self.blob_len);
        }
        let within = self.blob_len - self.block_base;
        assert!(
            within <= u64::from(u16::MAX),
            "block starting at geometry edge {} spans {within} bytes, past the {} a u16 offset \
             can address; a per-edge blob should be at most {} bytes",
            self.stored - self.stored % INTERMEDIATE_BLOCK,
            u16::MAX,
            4 * (geom::MAX_POINTS - 2)
        );
        self.within.push(within as u16);
    }

    /// Append the sentinel entry and the whole trailer. Returns the finished
    /// file's size.
    fn finish(mut self, edge_count: u64) -> Result<u64> {
        if self.pushed != edge_count {
            return Err(Error(format!(
                "{} describes {} edge(s), not the {edge_count} written",
                self.path.display(),
                self.pushed
            )));
        }
        self.push_entry();
        // `coarse` is sized by `div_ceil`, so when `G` is not a multiple of the
        // block size it holds one slot past the last block the sentinel touched.
        // Filling it keeps the table exactly the length the reader computes.
        if !self.stored.is_multiple_of(INTERMEDIATE_BLOCK) {
            self.coarse.push(self.blob_len);
        }

        // The presence bitmap and its rank index, the same pair `edges.bin`'s names
        // use. The rank total is `G`, which is what lets the reader cross-check the
        // two halves of the trailer against each other.
        let total = self.present.write(&mut self.out)?;
        debug_assert_eq!(total, self.stored, "the presence bitmap disagrees with G");
        for c in &self.coarse {
            self.out.write_all(&c.to_le_bytes()).map_err(io_err)?;
        }
        for w in &self.within {
            self.out.write_all(&w.to_le_bytes()).map_err(io_err)?;
        }
        self.out.write_all(&self.stored.to_le_bytes()).map_err(io_err)?;
        self.out.flush().map_err(io_err)?;

        let want = self.blob_len + GeomFile::trailer_bytes(edge_count, self.stored);
        let got = std::fs::metadata(&self.path)
            .map_err(|e| Error(format!("cannot stat {}: {e}", self.path.display())))?
            .len();
        if got != want {
            return Err(Error(format!(
                "{} came out {got} byte(s) long, not the {want} the reader will compute",
                self.path.display()
            )));
        }
        Ok(want)
    }
}

/// A presence bitmap over the directed edges, built in memory and written with a
/// rank index in front of it.
///
/// The writer half of `graph.rs`'s `EdgeBitmap`, and an on-disk contract with it:
///
/// ```text
/// [ u64 rank[E.div_ceil(512) + 1] ]   set bits before each 64-byte block, total appended
/// [ u8  present[E.div_ceil(8)] ]      one bit per directed edge
/// ```
///
/// Used twice, for the same reason the reader's is: `intermediate.bin`'s stored
/// polylines and `edges.bin`'s names are both per-edge fields most edges do not have.
struct PresenceBitmap {
    bits: Vec<u8>,
    /// Bits set so far, i.e. the length of the side table this indexes.
    set: u64,
}

impl PresenceBitmap {
    /// Byte size of the rank index plus the bitmap for `edge_count` edges. The reader
    /// sizes them the same way.
    fn bytes(edge_count: u64) -> u64 {
        (edge_count.div_ceil(RANK_BLOCK_BITS) + 1) * 8 + edge_count.div_ceil(8)
    }

    fn new(edge_count: u64) -> PresenceBitmap {
        PresenceBitmap {
            bits: vec![0u8; edge_count.div_ceil(8) as usize],
            set: 0,
        }
    }

    /// Mark edge `idx` as having one. Must be called in ascending `idx`, which every
    /// caller satisfies because the rounds ascend and each round writes its edges in
    /// index order.
    fn set(&mut self, idx: u64) {
        debug_assert!(
            self.bits[(idx / 8) as usize] & (1u8 << (idx % 8)) == 0,
            "edge {idx} was already marked"
        );
        self.bits[(idx / 8) as usize] |= 1u8 << (idx % 8);
        self.set += 1;
    }

    /// Write the rank index then the bitmap. Returns the number of set bits, which is
    /// the rank index's appended total and the length the side table must have.
    fn write<W: Write>(&self, out: &mut W) -> Result<u64> {
        // Set bits before each block, with the total appended. That total is what
        // lets the reader tie the index to the table it indexes.
        let blocks = self.bits.len().div_ceil(RANK_BLOCK_BYTES);
        let mut total = 0u64;
        for b in 0..blocks {
            out.write_all(&total.to_le_bytes()).map_err(io_err)?;
            let start = b * RANK_BLOCK_BYTES;
            let end = (start + RANK_BLOCK_BYTES).min(self.bits.len());
            total += self.bits[start..end]
                .iter()
                .map(|byte| u64::from(byte.count_ones()))
                .sum::<u64>();
        }
        out.write_all(&total.to_le_bytes()).map_err(io_err)?;
        out.write_all(&self.bits).map_err(io_err)?;
        debug_assert_eq!(total, self.set, "the bitmap disagrees with its own counter");
        Ok(total)
    }
}

/// `edges.bin`, in five sections (v7: the record array is blocked):
///
/// ```text
/// [ ZBLK records[E] ]                7 B each, blocked zstd (see
///                                    `tilecodec::blocked`); v6 is raw E*7
/// [ pad to SECTION_ALIGN ]
/// [ u32 escape_first[E.div_ceil(1024) + 1] ]   first escape row of each block
/// [ { u32 edge_idx, u32 target, u32 dist_mm } x escapes ]   ascending by edge_idx
/// [ pad to SECTION_ALIGN ]
/// [ u64 name rank[E.div_ceil(512) + 1] ][ u8 name present[E.div_ceil(8)] ]
/// [ u32 name_off[named_edges] ]                indexed by rank in the bitmap
/// ```
///
/// `target` is a signed delta from the edge's own source, which is affordable only
/// because the nodes are sorted by a space-filling curve: 99.699% of California's
/// deltas fit an `i16`. `dist_mm` is a `u24`, exact below 16.78 km, which is
/// 99.999% of edges. Either field's sentinel means *both* come from one escape row,
/// so the reader takes one branch and the table stays single.
///
/// `name_offset` is sparse rather than a field, because two thirds of edges have no
/// name — 62.3% of California's, 68.9% of Europe's — and it is read when an
/// instruction is emitted, never in the A* relaxation.
///
/// # Why the tables come last, and why they fit in memory
///
/// Neither the escape count nor the named-edge count is known until the final round
/// has run, so a reserved prefix cannot be sized. The blocked record array is
/// likewise data-dependent in length, so — unlike the v6 fixed stride — the
/// reader cannot compute offsets from `metadata.bin`'s counts alone: it parses
/// the ZBLK header for the records' end, then the escapes and names exactly as
/// before. The escape rows accumulate in memory — 39 MB at planet scale against
/// `edges.bin`'s 8.6 GB — as do the name offsets (1.6 GB, the largest of them)
/// and the bitmap (134 MB).
struct EdgeFile {
    path: PathBuf,
    /// Blocked record staging: 7 B records stream here (one block in memory,
    /// frames to scratch), finished into the file head at [`EdgeFile::finish`].
    blocked: ParBlocked,
    /// `(edge_idx, target, dist_mm)` for the edges a record cannot hold, in
    /// ascending `edge_idx` — which is the order they are pushed in, because the
    /// rounds ascend and each round writes its edges in index order.
    escapes: Vec<(u32, u32, u32)>,
    /// Which edges have a name, and the pool offsets of the ones that do, in the
    /// same order.
    named: PresenceBitmap,
    name_offsets: Vec<u32>,
    /// Directed edges written so far, which is the index the next call describes.
    pushed: u64,
}

impl EdgeFile {
    /// Byte size of the whole file. Only the v6 fixed-stride layout is
    /// computable from counts; the v7 blocked layout is data-dependent, so
    /// this covers the trailer and the reader parses the records' end out of
    /// the ZBLK header instead.
    fn total_bytes(edge_count: u64, escapes: u64, named: u64) -> u64 {
        let rows = align_up(edge_count * EDGE_REC_BYTES, SECTION_ALIGN)
            + (edge_count.div_ceil(ESCAPE_BLOCK) + 1) * 4
            + escapes * 12;
        align_up(rows, SECTION_ALIGN) + PresenceBitmap::bytes(edge_count) + named * 4
    }

    fn create(dir: &Path, name: &str, edge_count: u64) -> Result<EdgeFile> {
        let path = dir.join(name);
        let scratch = dir.join(format!("{name}.blocked.tmp"));
        Ok(EdgeFile {
            blocked: ParBlocked::create(EDGE_REC_BYTES as usize, &scratch)
                .map_err(|e| Error(format!("cannot stage {}: {e}", scratch.display())))?,
            path,
            escapes: Vec::new(),
            named: PresenceBitmap::new(edge_count),
            name_offsets: Vec::new(),
            pushed: 0,
        })
    }

    /// Write one directed edge's record, escaping it if either field will not fit and
    /// recording its name if it has one.
    fn push(&mut self, e: &TmpEdge, type_: u8) -> Result<()> {
        let (delta, dist) = match target_delta(e.source, e.target) {
            Some(d) if e.dist_mm < DIST_MM_ESCAPE => (d, e.dist_mm),
            // Both sentinels, not just the one that failed: the reader tests either,
            // so writing both keeps the two tests in agreement whichever field a
            // future caller happens to have in hand.
            _ => {
                let idx = cap_u32("escaped edge index", self.pushed)?;
                self.escapes.push((idx, e.target, e.dist_mm));
                (TARGET_DELTA_ESCAPE, DIST_MM_ESCAPE)
            }
        };
        let mut rec = [0u8; EDGE_REC_BYTES as usize];
        rec[0..2].copy_from_slice(&delta.to_le_bytes());
        rec[2..5].copy_from_slice(&dist.to_le_bytes()[..3]);
        rec[5] = type_;
        rec[6] = e.speed_limit;
        self.blocked
            .push(&rec)
            .map_err(|e| Error(format!("cannot stage edge record: {e}")))?;
        if e.name_offset != NO_NAME {
            self.named.set(self.pushed);
            self.name_offsets.push(e.name_offset);
        }
        self.pushed += 1;
        Ok(())
    }

    /// Finish the blocked records into the file head, then pad and append the
    /// block index, the escape rows, the name bitmap and the name offsets.
    /// Returns the finished file's size.
    fn finish(self, edge_count: u64) -> Result<u64> {
        if self.pushed != edge_count {
            return Err(Error(format!(
                "{} holds {} edge(s), not the {edge_count} the pack is sized for",
                self.path.display(),
                self.pushed
            )));
        }
        let EdgeFile { path, blocked, escapes, named, name_offsets, pushed: _ } = self;
        // The record section streams straight into the file head; the sanity
        // parse reads it back through a mapping once the file is complete.
        let mut out = BufWriter::with_capacity(WRITE_BUF_BYTES, create(&path)?);
        let mut at = blocked
            .finish_into(&mut out)
            .map_err(|e| Error(format!("cannot finish {}: {e}", path.display())))?;
        at = pad_to(&mut out, &path, at)?;

        // `escape_first[b]` is the index of the first row in block `b`, i.e. the
        // count of rows below `b * ESCAPE_BLOCK`. The rows ascend, so one walk over
        // them fills the whole array, and the final entry is the row total — which is
        // what lets the reader tie the index to the section it indexes.
        let blocks = edge_count.div_ceil(ESCAPE_BLOCK) + 1;
        let mut row = 0usize;
        for b in 0..blocks {
            let limit = b * ESCAPE_BLOCK;
            while row < escapes.len() && u64::from(escapes[row].0) < limit {
                row += 1;
            }
            out.write_all(&(row as u32).to_le_bytes()).map_err(io_err)?;
        }
        if row != escapes.len() {
            return Err(Error(format!(
                "{}: the block index reached row {row} of {}, so a row is past the \
                 last block",
                path.display(),
                escapes.len()
            )));
        }
        at += blocks * 4;

        for (idx, target, dist_mm) in &escapes {
            out.write_all(&idx.to_le_bytes()).map_err(io_err)?;
            out.write_all(&target.to_le_bytes()).map_err(io_err)?;
            out.write_all(&dist_mm.to_le_bytes()).map_err(io_err)?;
        }
        at += escapes.len() as u64 * 12;
        at = pad_to(&mut out, &path, at)?;

        let named = named.write(&mut out)?;
        if named != name_offsets.len() as u64 {
            return Err(Error(format!(
                "{}: the name bitmap has {named} bit(s) set but {} offset(s) were \
                 collected",
                path.display(),
                name_offsets.len()
            )));
        }
        for off in &name_offsets {
            out.write_all(&off.to_le_bytes()).map_err(io_err)?;
        }
        out.flush().map_err(io_err)?;
        drop(out);

        // Sanity: the blocked section must unpack to exactly the pushed records.
        let map = crate::mem::Mapped::open(&path).map_err(Error)?;
        let parsed = tilecodec::blocked::Blocked::parse(&map).map_err(|e| {
            Error(format!("freshly blocked {} does not parse: {e}", path.display()))
        })?;
        if parsed.unpacked_len() != edge_count * EDGE_REC_BYTES {
            return Err(Error(format!(
                "{} unpacks to {} byte(s), not {edge_count} 7-byte records",
                path.display(),
                parsed.unpacked_len()
            )));
        }
        let got = map.len() as u64;
        Ok(got)
    }
}

/// Write zeroes up to the next section boundary, returning the new offset.
///
/// Free function (not a method): [`EdgeFile::finish`] destructures `self`
/// before the trailer, so there is no receiver left to hang it on.
fn pad_to(out: &mut BufWriter<File>, path: &Path, at: u64) -> Result<u64> {
    let aligned = align_up(at, SECTION_ALIGN);
    let zeroes = [0u8; SECTION_ALIGN as usize];
    out.write_all(&zeroes[..(aligned - at) as usize]).map_err(io_err)?;
    Ok(aligned)
}
