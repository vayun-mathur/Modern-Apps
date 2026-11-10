// ---- the parallel write-write pipeline -----------------------------------
//
// Re-landed per the TODO(write-pipe) checklist: `bake_batch` is unit-tested on
// synthetic buffers (`graph_build_part10.rs`) for opener count == node span and
// opener edge_ptr == base + run offset, and the pool + in-order drain below sit
// behind that test. The framing bug that sank the first attempt was in
// `bake_batch`: it opened only the nodes up to its last edge's source, so the
// edgeless nodes at the END of a batch (and every node of an edgeless batch)
// never got a record. The "12x" reading was the gate's own message, which
// printed the unpacked BYTE count next to the expected RECORD count — so a
// slightly short nodes.bin read as twelve times too long.
//
// Shape: each round's node range is cut into fixed batches of
// [`WRITE_BATCH_NODES`] nodes. A batch is a pure function of the sorted round
// buffer, its node span and its absolute first edge index, so the pool bakes
// batches in any order; the main thread drains them strictly in batch order,
// which reproduces the serial byte sequence exactly. Baking a wave of batches
// overlaps with draining the previous wave (double buffering), and the two
// zstd-blocked streams compress whole 4096-record blocks on the pool
// ([`ParBlocked`]) instead of one block at a time on the drain thread.

/// Nodes per bake batch. A constant rather than a function of the thread
/// count, although nothing in the output depends on it: batches drain in
/// order, and a batch's bytes are exactly what the serial loop produced for
/// its span.
const WRITE_BATCH_NODES: u32 = 4096;

/// Batches baked per wave, per pool thread. A wave is what the drain consumes
/// while the next one bakes, so it bounds the bytes in flight to two waves.
const WAVE_BATCHES_PER_THREAD: usize = 4;

/// Whole blocks a [`ParBlocked`] stages before compressing them on the pool.
/// 256 blocks is 12 MiB of node records or 7 MiB of edge records per
/// parallel flush, which also makes each scratch-file append that large.
const PAR_BLOCKS: usize = 256;

/// A [`tilecodec::blocked::BlockedStreamWriter`] whose full blocks are
/// compressed on the pool.
///
/// Records stage here until [`PAR_BLOCKS`] whole blocks are waiting, then
/// those blocks compress in parallel ([`tilecodec::blocked::compress_block`]
/// is a pure function of one block) and go to the writer in order as frames.
/// The tail that never fills a block goes through the writer's own `push`, so
/// `finish` frames it exactly as before. Byte-identical to pushing every
/// record straight into the writer.
struct ParBlocked {
    w: tilecodec::blocked::BlockedStreamWriter,
    staged: Vec<u8>,
    block_bytes: usize,
}

impl ParBlocked {
    fn create(record_len: usize, scratch: &Path) -> std::io::Result<ParBlocked> {
        let w = tilecodec::blocked::BlockedStreamWriter::create(record_len, scratch)?;
        let block_bytes = w.block_bytes();
        Ok(ParBlocked {
            w,
            staged: Vec::with_capacity(block_bytes * PAR_BLOCKS * 2),
            block_bytes,
        })
    }

    /// Append whole records.
    #[inline]
    fn push(&mut self, records: &[u8]) -> std::io::Result<()> {
        self.staged.extend_from_slice(records);
        if self.staged.len() >= self.block_bytes * PAR_BLOCKS {
            self.flush_full()?;
        }
        Ok(())
    }

    /// Compress every whole staged block on the pool and hand the frames over
    /// in order; keep the partial tail staged.
    fn flush_full(&mut self) -> std::io::Result<()> {
        let full = self.staged.len() / self.block_bytes * self.block_bytes;
        if full == 0 {
            return Ok(());
        }
        let bb = self.block_bytes;
        let staged = &self.staged[..full];
        let frames: Vec<Vec<u8>> = par::install(|| {
            staged
                .par_chunks(bb)
                .map(tilecodec::blocked::compress_block)
                .collect()
        });
        self.w.push_frames(&frames)?;
        self.staged.drain(..full);
        Ok(())
    }

    fn finish(mut self) -> std::io::Result<Vec<u8>> {
        self.flush_full()?;
        let tail = std::mem::take(&mut self.staged);
        self.w.push(&tail)?;
        self.w.finish()
    }
}

/// One baked write batch: every byte the serial loop would have produced for
/// one span of nodes, staged per output stream.
///
/// Positional: `node_recs` holds one 12-byte record per node of the span
/// (edgeless ones included) with its ABSOLUTE `edge_ptr`; every per-edge side
/// table is keyed by the edge's offset within the batch, which the drain turns
/// into an absolute index by adding the batch's base. Nothing in here depends
/// on any other batch, which is what lets the pool bake them in any order.
#[derive(Default)]
struct BakedBatch {
    /// Node records, 12 B each, one per node of the span, in node order.
    node_recs: Vec<u8>,
    /// Edges in the batch.
    edge_count: u64,
    /// Edge records, 7 B each, already narrowed (escape sentinels in place).
    edge_recs: Vec<u8>,
    /// `(edge offset, target, dist_mm)` for each escaped edge, ascending.
    escapes: Vec<(u32, u32, u32)>,
    /// `(edge offset, name_offset)` for each named edge, ascending.
    names: Vec<(u32, u32)>,
    /// Encoded interior polylines of the storing edges, concatenated in order.
    geom: Vec<u8>,
    /// `(edge offset, blob length)` per storing edge, ascending.
    geom_runs: Vec<(u32, u32)>,
    /// Lane masks (`u16` LE) of the lane-bearing edges, concatenated in order.
    lanes: Vec<u8>,
    /// `(edge offset, byte length)` per lane-bearing edge, ascending.
    lane_runs: Vec<(u32, u32)>,
    /// The census over this batch's nodes and edges. Sums and maxima only, so
    /// merging batch censuses in any order equals the serial census.
    census: Census,
    geometry_edges: u64,
    reversed_edges: u64,
}

impl BakedBatch {
    /// Nodes in the batch's span.
    #[cfg(test)]
    fn node_count(&self) -> usize {
        self.node_recs.len() / 12
    }

    /// Node record `i` of the span as `(lat_e7, lon_e7, edge_ptr)`.
    #[cfg(test)]
    fn opener(&self, i: usize) -> (i32, i32, u32) {
        let r = &self.node_recs[i * 12..i * 12 + 12];
        (
            i32::from_le_bytes(r[0..4].try_into().expect("4 bytes")),
            i32::from_le_bytes(r[4..8].try_into().expect("4 bytes")),
            u32::from_le_bytes(r[8..12].try_into().expect("4 bytes")),
        )
    }
}

impl Census {
    /// Fold another census in. Every field is a count or a maximum, so this is
    /// order-independent and equals recording both sets of items into one.
    fn merge(&mut self, o: &Census) {
        for (a, b) in self.delta_bits.iter_mut().zip(o.delta_bits.iter()) {
            *a += *b;
        }
        self.delta_escapes += o.delta_escapes;
        self.dist_escapes += o.dist_escapes;
        self.escapes += o.escapes;
        self.max_delta = self.max_delta.max(o.max_delta);
        self.max_dist_mm = self.max_dist_mm.max(o.max_dist_mm);
        self.max_degree = self.max_degree.max(o.max_degree);
        self.degree_zero_nodes += o.degree_zero_nodes;
        self.named_edges += o.named_edges;
    }
}

/// Bake one write batch: the node span `first_node..end_node`, whose edges are
/// exactly `edges` (a source-sorted slice of the round buffer), with the
/// batch's first edge at absolute index `batch_edge_base`.
///
/// Walks the SPAN, not the edges — the serial loop's own shape — so every node
/// opens exactly once whether or not it has edges, and each opener's
/// `edge_ptr` is the base plus the edges before it in the batch. Per edge it
/// mirrors the serial body exactly: census, twin-check-before-read, polyline
/// read + reverse + `geom::fits` + `assert_endpoints` + encode, the narrowing
/// of [`EdgeFile::push`], name and lanes.
///
/// Pure over shared tables (`node_coords`, `lane_pool`, `csr`, `synth`, the
/// `chains.pts` mapping): safe across the pool. Fallible only like the serial
/// loop was — a truncated `chains.pts` fails the build rather than shortening
/// it — and the drain reports the first failure in batch order.
#[allow(clippy::too_many_arguments)]
fn bake_batch(
    edges: &[TmpEdge],
    first_node: u32,
    end_node: u32,
    batch_edge_base: u64,
    node_coords: &[geom::Pt],
    lane_pool: &[u16],
    csr: &Csr,
    synth: &[Synth],
    points: &crate::mem::Mapped,
) -> Result<BakedBatch> {
    let span = (end_node - first_node) as usize;
    let mut b = BakedBatch {
        node_recs: Vec::with_capacity(span * 12),
        edge_recs: Vec::with_capacity(edges.len() * EDGE_REC_BYTES as usize),
        ..BakedBatch::default()
    };
    let mut scratch: Vec<geom::Pt> = Vec::new();
    let mut cursor = 0usize;
    for v in first_node..end_node {
        let node = node_coords[v as usize];
        let edge_ptr = batch_edge_base + cursor as u64;
        b.node_recs.extend_from_slice(&node.0.to_le_bytes());
        b.node_recs.extend_from_slice(&node.1.to_le_bytes());
        b.node_recs.extend_from_slice(&(edge_ptr as u32).to_le_bytes());
        let degree_base = cursor;
        while cursor < edges.len() && edges[cursor].source == v {
            let e = &edges[cursor];
            let local = cursor as u32;
            let named = e.name_offset != NO_NAME;
            b.census.edge(e.source, e.target, e.dist_mm, named);

            // --- geometry ---
            let mut type_ = e.type_;
            // A two-point chain is just the chord between the edge's own
            // endpoints, and under the interior-only encoding that is what an
            // absent blob already means, so storing it would cost a presence
            // bit to say nothing — and a read to find that out.
            if e.chain != NO_CHAIN && e.pts_len >= 3 {
                if e.chain_rev
                    && e.source != e.target
                    && twin_is_unique(csr, synth, e.source, e.target)
                {
                    type_ |= REVERSE_GEOMETRY_FLAG;
                    b.reversed_edges += 1;
                } else {
                    // Only now is the polyline needed: this is the only random
                    // read in the writer, so every edge that defers to its twin
                    // is one read saved. Shared mapping, no lock.
                    chains::PointReader::read_shared(points, e.pts_start, e.pts_len, &mut scratch)?;
                    if e.chain_rev {
                        scratch.reverse();
                    }
                    assert!(
                        geom::fits(&scratch),
                        "chain of {} points cannot be encoded; the collapse should \
                         have split it",
                        scratch.len()
                    );
                    // The blob holds only the interior, so the reader rebuilds
                    // both ends out of `nodes.bin`.
                    assert_endpoints(&scratch, node_coords, e.source, e.target, e.chain);
                    let before = b.geom.len();
                    let n = geom::encode(&scratch, &mut b.geom);
                    debug_assert_eq!(n as usize, geom::encoded_points(&scratch) as usize);
                    b.geom_runs.push((local, (b.geom.len() - before) as u32));
                    b.geometry_edges += 1;
                }
            }

            // --- the edge record: `EdgeFile::push`'s narrowing, verbatim ---
            let (delta, dist) = match target_delta(e.source, e.target) {
                Some(d) if e.dist_mm < DIST_MM_ESCAPE => (d, e.dist_mm),
                _ => {
                    b.escapes.push((local, e.target, e.dist_mm));
                    (TARGET_DELTA_ESCAPE, DIST_MM_ESCAPE)
                }
            };
            let mut rec = [0u8; EDGE_REC_BYTES as usize];
            rec[0..2].copy_from_slice(&delta.to_le_bytes());
            rec[2..5].copy_from_slice(&dist.to_le_bytes()[..3]);
            rec[5] = type_;
            rec[6] = e.speed_limit;
            b.edge_recs.extend_from_slice(&rec);
            if named {
                b.names.push((local, e.name_offset));
            }

            // --- lanes ---
            if e.lane_count > 0 && e.lane_off != NO_LANES {
                let start = e.lane_off as usize;
                let before = b.lanes.len();
                for m in &lane_pool[start..start + e.lane_count as usize] {
                    b.lanes.extend_from_slice(&m.to_le_bytes());
                }
                b.lane_runs.push((local, (b.lanes.len() - before) as u32));
            }
            cursor += 1;
        }
        b.census.node((cursor - degree_base) as u32);
    }
    // Every edge handed to the batch must belong to a node of its span: an edge
    // left over here would silently vanish from the pack.
    if cursor != edges.len() {
        return Err(Error(format!(
            "write batch over node(s) {first_node}..{end_node} left {} edge(s) unbaked \
             (first stray source {})",
            edges.len() - cursor,
            edges[cursor].source
        )));
    }
    b.edge_count = edges.len() as u64;
    Ok(b)
}

/// Everything the in-order drain appends to.
struct WriteSinks<'a> {
    nodes: &'a mut ParBlocked,
    edges: &'a mut EdgeFile,
    inter: &'a mut GeomFile,
    lanes: &'a mut LaneFile,
    census: &'a mut Census,
    geometry_edges: &'a mut u64,
    reversed_edges: &'a mut u64,
}

/// Append one baked batch, whose first edge is absolute index `base`.
///
/// Strictly positional, and the only writer into the streams: node records,
/// edge records, escape rows, name bits, presence bits and offsets, polyline
/// bytes and lane runs all go out exactly as the serial loop emitted them.
fn drain_batch(s: &mut WriteSinks, b: &BakedBatch, base: u64) -> Result<()> {
    debug_assert_eq!(s.edges.pushed, base, "batches drained out of order");
    s.nodes
        .push(&b.node_recs)
        .map_err(|e| Error(format!("cannot stage node record: {e}")))?;
    s.edges.push_baked(b, base)?;
    s.inter.store_batch(base, b)?;
    let mut at = 0usize;
    for &(local, len) in &b.lane_runs {
        s.lanes.push(base + u64::from(local), &b.lanes[at..at + len as usize])?;
        at += len as usize;
    }
    s.census.merge(&b.census);
    *s.geometry_edges += b.geometry_edges;
    *s.reversed_edges += b.reversed_edges;
    Ok(())
}

impl EdgeFile {
    /// Append a baked batch's records and side tables, its first edge being
    /// absolute index `base`. Same rows, bits and offsets as calling
    /// [`EdgeFile::push`] once per edge.
    fn push_baked(&mut self, b: &BakedBatch, base: u64) -> Result<()> {
        for &(local, target, dist) in &b.escapes {
            let idx = cap_u32("escaped edge index", base + u64::from(local))?;
            self.escapes.push((idx, target, dist));
        }
        self.blocked
            .push(&b.edge_recs)
            .map_err(|e| Error(format!("cannot stage edge record: {e}")))?;
        for &(local, off) in &b.names {
            self.named.set(base + u64::from(local));
            self.name_offsets.push(off);
        }
        self.pushed += b.edge_count;
        Ok(())
    }
}

impl GeomFile {
    /// Append a baked batch: presence bits, offset entries and polyline bytes
    /// for its storing edges, a skip for every other edge. Same state as the
    /// serial sequence of [`GeomFile::store`] / [`GeomFile::skip`] calls, with
    /// the batch's blob going out as one write.
    fn store_batch(&mut self, base: u64, b: &BakedBatch) -> Result<()> {
        for &(local, len) in &b.geom_runs {
            debug_assert!(len > 0, "an interior-only blob of no bytes is the chord");
            self.pushed = base + u64::from(local);
            self.present.set(self.pushed);
            self.push_entry();
            self.blob_len += u64::from(len);
            self.stored += 1;
        }
        self.out.write_all(&b.geom).map_err(io_err)?;
        self.pushed = base + b.edge_count;
        Ok(())
    }
}

/// Chains per parallel scan/gather chunk in the writer. Fixed, so chunking
/// cannot depend on the thread count (nor could it change the output: chunks
/// concatenate in order).
const SCAN_CHUNK: usize = 1 << 16;

/// Buffer for the writer's sequential output streams: large appends, since on
/// a compress-force filesystem every extent costs CPU.
const WRITE_BUF_BYTES: usize = 8 << 20;

/// Write one round's nodes `lo..hi` from its sorted edge buffer, whose first
/// edge is absolute index `round_base`.
///
/// Batches of [`WRITE_BATCH_NODES`] nodes bake on the pool a wave at a time;
/// while wave `k` drains on this thread, wave `k + 1` bakes. Each batch's edge
/// slice is found by binary search on the source-sorted buffer, and its base is
/// `round_base` plus the edges before it, so no batch depends on another.
#[allow(clippy::too_many_arguments)]
fn write_round(
    buffer: &[TmpEdge],
    lo: u32,
    hi: u32,
    round_base: u64,
    node_coords: &[geom::Pt],
    lane_pool: &[u16],
    csr: &Csr,
    synth: &[Synth],
    points: &crate::mem::Mapped,
    sinks: &mut WriteSinks,
    round: u32,
    rounds: u32,
) -> Result<()> {
    let n_batches = (hi - lo).div_ceil(WRITE_BATCH_NODES) as usize;
    let wave = (par::threads() * WAVE_BATCHES_PER_THREAD).max(1);
    let n_waves = n_batches.div_ceil(wave);
    let span_of = |bi: usize| -> (u32, u32) {
        let first = lo + bi as u32 * WRITE_BATCH_NODES;
        (first, first.saturating_add(WRITE_BATCH_NODES).min(hi))
    };
    let bake_wave = |w: usize| -> Vec<(u64, Result<BakedBatch>)> {
        let b0 = w * wave;
        let b1 = (b0 + wave).min(n_batches);
        (b0..b1)
            .into_par_iter()
            .map(|bi| {
                let (first, end) = span_of(bi);
                let e0 = buffer.partition_point(|e| e.source < first);
                let e1 = buffer.partition_point(|e| e.source < end);
                let base = round_base + e0 as u64;
                let baked = bake_batch(
                    &buffer[e0..e1],
                    first,
                    end,
                    base,
                    node_coords,
                    lane_pool,
                    csr,
                    synth,
                    points,
                );
                (base, baked)
            })
            .collect()
    };
    let mut prog = crate::progress::Progress::new(
        format!("Round {}/{rounds}: write", round + 1),
        u64::from(hi - lo),
    );
    let drain_wave = |baked: Vec<(u64, Result<BakedBatch>)>,
                      sinks: &mut WriteSinks,
                      prog: &mut crate::progress::Progress|
     -> Result<()> {
        for (base, b) in baked {
            let b = b?;
            drain_batch(sinks, &b, base)?;
            prog.add((b.node_recs.len() / 12) as u64);
        }
        Ok(())
    };
    par::install(|| -> Result<()> {
        let mut pending = if n_waves > 0 { Some(bake_wave(0)) } else { None };
        for w in 0..n_waves {
            let cur = pending.take().expect("a baked wave is pending");
            let (drained, next) = rayon::join(
                || drain_wave(cur, sinks, &mut prog),
                || (w + 1 < n_waves).then(|| bake_wave(w + 1)),
            );
            drained?;
            pending = next;
        }
        Ok(())
    })?;
    prog.finish();
    debug_assert_eq!(sinks.edges.pushed, round_base + buffer.len() as u64);
    Ok(())
}
