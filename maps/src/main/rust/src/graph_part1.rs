/// The whole-world routing dataset. Immutable after construction.
pub struct Graph {
    // mmap regions kept alive for the lifetime of the graph. The three
    // mandatory ones are `Some` on the multi-file path (one region per file)
    // and `None` on the single-archive path (the one archive region below
    // backs everything instead); exactly one side is populated, checked in
    // `assemble`.
    _nodes_region: Option<MmapRegion>,
    _edges_region: Option<MmapRegion>,
    _intermediate_region: Option<MmapRegion>,
    _road_names_region: Option<MmapRegion>,
    _lanes_region: Option<MmapRegion>,
    _elevation_region: Option<MmapRegion>,
    // The single-archive file on the `load_archive` path, which backs every
    // pointer above instead of the six per-file regions. `None` on the
    // multi-file `load` path.
    _archive_region: Option<MmapRegion>,
    // Owned byte backing for host tests (the host stub cannot mmap): the
    // graph keeps these alive exactly like the regions. Always empty in
    // production.
    _owned_buffers: Vec<Vec<u8>>,

    nodes: *const u8,
    pub node_count: u32, // real nodes; nodes.bin has node_count + 1 (sentinel)
    pub edge_count: u64,

    // `edges.bin`, in five sections:
    //   [ EdgeRec[E] ][ pad ][ u32 escape_first[E/1024 + 1] ][ EscapeRow[escapes] ]
    //   [ pad ][ u64 name rank[..] ][ u8 name present[..] ][ u32 name_off[named] ]
    // The records hold `target` as an i16 delta and `dist_mm` as a u24; either
    // field's sentinel sends both to the escape table. See `EdgeRec`.
    edges: *const u8,
    escape_first: *const u8, // u32[E.div_ceil(ESCAPE_BLOCK) + 1]
    escapes: *const u8,      // EscapeRow[escape_count], ascending by edge_idx
    /// Edges whose `target` or `dist_mm` came out of the escape table rather than
    /// the record. Validated against the block index's total at load.
    pub escape_count: u64,
    names: EdgeBitmap,
    name_off: *const u8, // u32[named_edges], indexed by rank in `names`
    /// Edges with a name at all. Validated against the rank index's total at load.
    pub named_edges: u64,

    // `intermediate.bin`, blob first and every table in a trailer. `off(g) =
    // coarse[g / INTERMEDIATE_BLOCK] + within[g]` for the g-th *geometry* edge,
    // where g is the edge's rank in `geometry`.
    intermediate_data: *const u8,   // delta-encoded coordinate bytes, at offset 0
    geometry: EdgeBitmap,           // which edges store a polyline, and their rank
    intermediate_coarse: *const u8, // u64[G.div_ceil(BLOCK) + 1]
    intermediate_within: *const u8, // u16[G + 1]
    // Planet5 (graph v8): blobs are zigzag-varint deltas on a 1e-6 degree grid and the coarse
    // table covers INTERMEDIATE_BLOCK_V8 geometry edges per entry. v6/v7: fixed i16 1e-7
    // degree deltas, INTERMEDIATE_BLOCK per entry.
    intermediate_varint: bool,
    intermediate_block: u64,

    // Real OSM turn-lane data (see `edge_lane_masks`). Sparse: only the edges that
    // actually carry `turn:lanes` appear, which on a planet is 2.9 M of 1.07 G.
    // Optional as a whole too — absent until the graph is regenerated with lane
    // extraction, in which case routing falls back to topology-inferred lanes.
    lane_index: *const u8, // LaneEntry[lane_edges + 1], ascending by edge_idx
    lane_edges: u32,
    lane_data: *const u8, // packed u16 per-lane masks

    road_names: *const u8,
    pub road_names_size: usize,

    // `elevation.bin`, an OPTIONAL `i16[node_count]` of per-node ground elevation in metres, baked
    // from the DEM at graph-build time (WS-G route profile). Null when the file is absent or its
    // length does not match `node_count`, in which case [`Graph::node_elevation`] reads 0 for every
    // node and the route profile comes out flat. A sidecar like `road_names.bin`/`lanes.bin`: no
    // metadata count and no GRAPH_VERSION bump, validated by its own length instead.
    //
    // Under v7 the file is a ZBLK section ([`elevation_blocked`]); the loader
    // dispatches on the magic, so absence still reads as all-zero.
    elevation: *const u8,

    /// v7 blocked views, parsed (not inflated) at load. `None` on the v6 path,
    /// where the raw pointers above serve directly.
    ///
    /// Each view inflates and keeps a block (4096 records) the first time a
    /// record in it is read. A route only touches the blocks along its search
    /// frontier, whereas inflating whole sections up front meant ~10 GiB of heap
    /// for the world pack on the first route: a multi-minute stall, then an OOM
    /// kill on an 8 GB phone.
    nodes_view: Option<BlockedView>,
    edges_view: Option<BlockedView>,
    elevation_view: Option<BlockedView>,

    /// The optional highway overlay. See `graph_overlay.rs`.
    overlay: Option<Overlay>,

    // Derived cost tables (computed in `load`, matching the C++ `init`).
    pub lon_to_mm_scale: [u32; 4096],
    pub time_scale_fixed: [u64; 4],
    pub edge_time_multipliers: [[u64; 16]; 4],
}

/// A parsed ZBLK section that borrows nothing.
///
/// The offset table is copied (8 bytes per block — 524 KB for 268 M planet
/// bodies) so this owns everything it needs except the frames themselves,
/// which stay in the mmap region (or archive slice) the caller keeps alive
/// exactly like the raw tables. No lifetime ties the two structs together.
pub(crate) struct BlockedView {
    record_len: usize,
    records_per_block: u64,
    /// `log2(records_per_block)` when it is a power of two (always, for packs
    /// the writer produces), so the hot lookup shifts instead of dividing.
    block_shift: Option<u32>,
    unpacked_len: u64,
    /// Absolute file offsets of each block start, plus the file end.
    offsets: Vec<u64>,
    frames: *const u8,
    /// Inflated blocks, filled on first read. A block that fails to decode is
    /// stored empty, so its records read as absent rather than retrying.
    blocks: Box<[std::sync::OnceLock<Box<[u8]>>]>,
}

// Raw pointers into a mapping the graph owns: Send exactly like the tables.
unsafe impl Send for BlockedView {}
unsafe impl Sync for BlockedView {}

impl BlockedView {
    /// Parse over `bytes` (the whole section file/slice) with the shared
    /// `tilecodec::blocked::Blocked::parse` — the same header and offset-table
    /// checks the writer's own reader runs (bad magic, unknown flags, zero
    /// stride, a table running past the section, unordered or out-of-bounds
    /// offsets) — then copies the offset table so no lifetime ties this view to
    /// the mapping. A corrupt section is `None` (no graph), never a panic.
    pub(crate) fn parse(bytes: &[u8]) -> Option<BlockedView> {
        let shared = tilecodec::blocked::Blocked::parse(bytes).ok()?;
        let block_count = shared.block_count();
        let offsets = (0..=block_count)
            .map(|b| shared.block_offset(b))
            .collect::<Option<Vec<u64>>>()?;
        let records_per_block = shared.records_per_block();
        // No end-equality check: a blocked record array may share its file
        // with trailing tables (`edges.bin`'s escapes/names). Truncation is
        // still refused by the shared parse — an offset past the bytes fails.
        Some(BlockedView {
            record_len: shared.record_len(),
            records_per_block,
            block_shift: records_per_block
                .is_power_of_two()
                .then(|| records_per_block.trailing_zeros()),
            unpacked_len: shared.unpacked_len(),
            offsets,
            frames: bytes.as_ptr(),
            blocks: (0..block_count).map(|_| std::sync::OnceLock::new()).collect(),
        })
    }

    /// The `record_len` bytes of record `id`, or `None` when it is out of range
    /// or its block does not decode.
    #[inline]
    pub(crate) fn record(&self, id: u64) -> Option<&[u8]> {
        let (b, i) = match self.block_shift {
            Some(s) => (id >> s, id & (self.records_per_block - 1)),
            None => (id / self.records_per_block, id % self.records_per_block),
        };
        let block = self.blocks.get(b as usize)?.get_or_init(|| self.inflate(b as usize));
        let at = i as usize * self.record_len;
        block.get(at..at + self.record_len)
    }

    /// Decode block `b`'s zstd frame, checking it against the size the header
    /// implies. Empty on any failure.
    #[cold]
    fn inflate(&self, b: usize) -> Box<[u8]> {
        let block_bytes = self.records_per_block * self.record_len as u64;
        let want = block_bytes.min(self.unpacked_len.saturating_sub(b as u64 * block_bytes)) as usize;
        let (start, end) = (self.offsets[b] as usize, self.offsets[b + 1] as usize);
        let frame = unsafe { std::slice::from_raw_parts(self.frames.add(start), end - start) };
        // The shared ZBLK frame decode (same code the writer's reader runs).
        match tilecodec::blocked::inflate_frame(frame, want) {
            Ok(out) if out.len() == want => out.into_boxed_slice(),
            _ => Box::default(),
        }
    }
}

unsafe impl Send for Graph {}
unsafe impl Sync for Graph {}
