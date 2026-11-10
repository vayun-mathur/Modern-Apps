/// K-way merge one zoom's chunks into ascending tiles, each carrying its layers in id order.
///
/// This is the reduce, and its ordering is the whole correctness argument. Keying the heap on
/// `(tile, layer, chunk index)` makes the entries for one tile arrive layer-major and, within a
/// layer, in **chunk order** — so appending them as they arrive lays a layer's features down in the
/// order the store yielded them. That is not merely *a* deterministic order; it is the order the
/// single-threaded tiler produced, which is why the archive did not move.
///
/// `chunks` is in read order and the index into it is the chunk index, exactly as when the chunks
/// were `BTreeMap`s in memory. Disk is inserted *inside* one partition element, order-preservingly:
/// a chunk's bytes are written in `BTreeMap::into_iter` order and read back in file order, so a
/// [`ChunkReader`] yields the identical key sequence its map did. The heap, `front` and the
/// `layers.last_mut()` adjacency shortcut below are untouched.
///
/// Streamed rather than merged into one map first, so a chunk's bytes are decoded as they are
/// consumed instead of every chunk and a merged copy of it being live at once.
fn merge<'a>(chunks: &[ChunkRef], spill: &'a ChunkSpill) -> Merged<'a> {
    let window = tilespill::read_window_bytes(chunks.len());
    let mut chunks: Vec<ChunkReader<'a>> =
        chunks.iter().map(|at| spill.reader(at, window)).collect();
    let mut front: Vec<Held> = Vec::with_capacity(chunks.len());
    let mut next = BinaryHeap::with_capacity(chunks.len());
    for (index, chunk) in chunks.iter_mut().enumerate() {
        let head = chunk.next().transpose();
        match &head {
            // A stream that fails on its first read still has to enter the heap, or its error would
            // be silently dropped and the archive would come out short.
            Some(Ok(((tile, layer), _))) => next.push(Reverse((*tile, *layer, index))),
            Some(Err(_)) => next.push(Reverse((0, 0, index))),
            None => {}
        }
        front.push(head);
    }
    Merged { chunks, front, next }
}

/// One stream's held entry: `None` past the end of its chunk, `Err` when its bytes were not what
/// the header claimed.
type Held = Option<Result<((u64, u8), ChunkEntry)>>;

/// A merge in progress: one held entry per chunk, and a heap over their keys. `Reverse`, because
/// `BinaryHeap` is a max-heap and the writer wants ascending ids.
struct Merged<'a> {
    chunks: Vec<ChunkReader<'a>>,
    front: Vec<Held>,
    next: BinaryHeap<Reverse<(u64, u8, usize)>>,
}

impl Iterator for Merged<'_> {
    /// Fallible, because a truncated scratch file must fail the build rather than quietly shorten
    /// the archive.
    type Item = Result<(u64, Vec<ChunkEntry>)>;

    fn next(&mut self) -> Option<Result<(u64, Vec<ChunkEntry>)>> {
        let Reverse((tile, _, _)) = *self.next.peek()?;
        let mut layers: Vec<ChunkEntry> = Vec::new();
        while let Some(&Reverse((at, layer_id, index))) = self.next.peek() {
            if at != tile {
                break;
            }
            self.next.pop();
            let held = self.front[index].take().expect("a heap key without its entry");
            let (_, mut layer) = match held {
                Ok(entry) => entry,
                Err(e) => return Some(Err(e)),
            };
            // Entries for one layer are adjacent, because the key sorts the layer before the chunk.
            // So the accumulator only ever has to look at the layer it started last.
            match layers.last_mut() {
                Some(last) if last.layer.layer_id == layer_id => {
                    concatenate(last, &mut layer);
                    // `layer`'s arenas are drained now; hand them back so the next decode on this
                    // stream reuses them instead of allocating. Only the concatenated entries come
                    // back — the one that starts a layer escapes into `layers` and is encoded.
                    self.chunks[index].recycle(layer);
                }
                _ => layers.push(layer),
            }
            let head = self.chunks[index].next().transpose();
            match &head {
                Some(Ok(((tile, layer_id), _))) => self.next.push(Reverse((*tile, *layer_id, index))),
                // Sorted first so the error surfaces on the next call rather than at the end of the
                // zoom, and before anything else is appended.
                Some(Err(_)) => self.next.push(Reverse((0, 0, index))),
                None => {}
            }
            self.front[index] = head;
        }
        Some(Ok((tile, layers)))
    }
}

/// Append one chunk's share of a layer onto another chunk's.
///
/// Rebased, not rebuilt: a `BodyLayer` is three parallel arenas and a feature addresses its parts by
/// index, so concatenating them means shifting `parts_offset` by the parts already there and
/// `coord_start` by the coordinates. The result is byte for byte what [`push`] would have produced
/// had both chunks' features gone into one layer in this order, which is the claim the whole design
/// rests on and what `concatenating_two_chunks_of_a_layer_is_one_layer` holds it to.
///
/// Names remap alongside: each chunk's `name_idx` addresses its own table, so every incoming
/// feature's index is translated into the accumulator's (interning on first use, in merge order —
/// which is chunk order, which is deterministic).
fn concatenate(into: &mut ChunkEntry, from: &mut ChunkEntry) {
    let parts_base = into.layer.parts.len() as u32;
    let coords_base = into.layer.coords.len() as u32;
    // Names remap first, while both tables are still borrowed shared: each incoming index is
    // translated into the accumulator's (interning on first use, in merge order — which is chunk
    // order, which is deterministic). Only then do the arenas move. A probe-only `HashMap` keyed on
    // the accumulator's names turns what was a linear scan per incoming feature into a lookup, so a
    // dense tile stops being quadratic in its name table; the table itself is still grown in
    // first-use order, so the indices and bytes are byte for byte what a linear scan produced.
    let remap = remap_names(&mut into.names, from);
    // Drained rather than consumed by value: `from` is handed back to its reader afterwards so the
    // next decode can reuse these arenas. `drain(..)` empties the source while keeping its capacity,
    // which is exactly what a recycled buffer needs; the elements moved, and their order, are what
    // `into_iter` would have yielded, so `into` is byte for byte the same layer.
    into.layer.features.extend(from.layer.features.drain(..).zip(remap).map(
        |(mut feature, name_idx)| {
            feature.parts_offset += parts_base;
            feature.name_idx = name_idx;
            feature
        },
    ));
    into.layer.parts.extend(from.layer.parts.drain(..).map(|mut part| {
        part.coord_start += coords_base;
        part
    }));
    into.layer.coords.extend_from_slice(&from.layer.coords);
    // Ids are values, not indices into a table, so they concatenate with no remap. Appended in
    // the same order the features were, which is what keeps the two parallel.
    into.ids.extend_from_slice(&from.ids);
    // The turn masks ride the same way: values parallel to features, concatenated in feature order.
    into.turn_lanes.extend(from.turn_lanes.drain(..));
    // The building attrs ride the same way, dense-parallel to the buildings layer's features.
    into.buildings.extend(from.buildings.drain(..));
    // And the carriageways, dense-parallel to the roads layer's.
    into.carriageways.extend(from.carriageways.drain(..));
}

/// Translate `from`'s feature name indices into `into_names`, growing it in first-use order.
///
/// A probe-only `HashMap` over the accumulator's table replaces the old linear `position` scan, so
/// concatenating a dense tile is no longer quadratic in its name count. The map is never iterated:
/// new names are appended to `into_names` in exactly the order they are first seen, so the resulting
/// table — and every index into it — is byte for byte what a linear scan would have produced.
fn remap_names(into_names: &mut Vec<String>, from: &ChunkEntry) -> Vec<u16> {
    let mut new: Vec<&str> = Vec::new();
    let remap: Vec<u16> = {
        let mut index: HashMap<&str, u16> = HashMap::with_capacity(into_names.len());
        for (i, name) in into_names.iter().enumerate() {
            index.insert(name.as_str(), i as u16 + 1);
        }
        let base = into_names.len();
        from.layer
            .features
            .iter()
            .map(|feature| {
                if feature.name_idx == tilecodec::mamaps::body::NAME_NONE {
                    tilecodec::mamaps::body::NAME_NONE
                } else {
                    let name = from.names[feature.name_idx as usize - 1].as_str();
                    match index.get(name) {
                        Some(&at) => at,
                        None => {
                            let idx = (base + new.len()) as u16 + 1;
                            index.insert(name, idx);
                            new.push(name);
                            idx
                        }
                    }
                }
            })
            .collect()
    };
    for name in new {
        into_names.push(name.to_string());
    }
    remap
}

/// Stage C and body encoding for a batch of merged tiles, in parallel, results in tile order.
///
/// Both halves are pure functions of a single tile, which is the only reason this can fan out at
/// all: [`crate::rings::normalise`] rewrites one layer's own arenas and nothing else, and
/// [`tilecodec::mamaps::body::serialize`] only reads. `map(..).collect()` preserves the input's
/// order — rayon's collect is positional, not completion-ordered — so the caller appends in
/// ascending tile id with no sort of its own.
///
/// A tile whose layers all emptied out comes back as `None` rather than being dropped here, because
/// its stage C counters still belong in the build report.
#[allow(clippy::type_complexity)]
/// Stage C, serialise and **compress** one batch of merged tiles, in parallel.
///
/// Compression belongs here rather than in the writer's append. DEFLATE at level nine is the single
/// most expensive step per tile and the one least able to be stolen: left in `append_encoded` it ran
/// on the appending thread, downstream of this whole parallel phase, and on a California z14 it was
/// ~900 MB through one core while sixty-three sat idle. That is why a 64-thread build was *slower*
/// than the sequential one it replaced.
///
/// returns the raw length alongside the compressed frame, because the per-zoom `bytes` column has
/// always reported *uncompressed* body size and moving compression must not silently change what a
/// build report means.

/// The most features any one tile has carried in each layer.
///
/// `body::serialize` caps a tile-layer at [`u16::MAX`] features, and that cap **has already fired**:
/// `layer 4 has 79407 features` on north-america, fixed by [`crate::coalesce`]. Coalescing merges
/// *lines* only -- polygons pass straight through -- and a planet build's dense cities are far past
/// anything in a continent, so the question of whether the field has to widen to `u32` is a question
/// about a number nobody has measured. This measures it.
///
/// Sampled **after** coalescing and stage C, which is where the count that actually reaches the cap
/// is. Always on, unlike the timing counters: this is one `fetch_max` per layer per tile against a
/// cache line that is almost never written after the first few thousand tiles, where those are three
/// unconditional adds per tile.
static WIDEST_LAYER: [AtomicU64; 256] = [const { AtomicU64::new(0) }; 256];

/// The widest tile-layer seen per layer id, taken and reset.
///
/// Only the layers that appeared, so a build that carried three layers reports three rows.
pub fn widest_layers() -> Vec<(u8, u64)> {
    WIDEST_LAYER
        .iter()
        .enumerate()
        .filter_map(|(id, seen)| match seen.swap(0, Ordering::Relaxed) {
            0 => None,
            n => Some((id as u8, n)),
        })
        .collect()
}

/// One encoded tile on its way back from [`encode_batch`]: its id, the compressed body with its raw
/// length and its FNV-1a dedup hash, and what stage C and coalescing did to it.
///
/// The hash is computed in the encode worker (see [`encode_tile`]) so the serial append thread does
/// not have to walk the whole body to derive the dedup key.
///
/// Folded by the caller in `tile_id` order rather than accumulated across workers, so the counters
/// need no atomics and a million tiles do not contend on three cache lines.
type Encoded = (u64, Option<(Vec<u8>, usize, u64)>, crate::rings::Stats, crate::coalesce::Stats);

/// Parallel range merge: the same k-way merge as [`merge`], partitioned by tile id.
///
/// Serial `merge` is one heap over every chunk — at z14, ~27k streams and 240 M
/// entries on one core. This splits the zoom's tile-id space into cost-balanced
/// ranges; one worker k-way merges each range over the same chunks; a
/// coordinator drains ranges in order into the unchanged downstream
/// (`merge_batch` → encode → prune → append).
///
/// Byte-identity rests on three facts, each pinned by test:
/// 1. Every tile belongs to exactly one range, and ranges drain ascending, so
///    the yielded tile sequence is the serial one.
/// 2. Within a range, chunks are consumed in global chunk order through the
///    same heap discipline, so each tile's fragments concatenate identically.
/// 3. Range boundaries never alter batch cuts: the coordinator yields the same
///    sequence `merge_batch` would pull from the serial iterator, so batches —
///    and therefore `prune_barren_subtree` — are unchanged.
///
/// Workers borrow the spill through `thread::scope` at the call site; nothing
/// here is `'static`, and no chunk bytes are duplicated (each entry is decoded
/// exactly once, by its range's worker). A corrupt spill fails the build with
/// the lowest-indexed range's error — deterministic, matching serial's
/// earliest-tile failure.
const RANGE_BUCKETS: usize = 256;
/// At or above this many tile-layer entries, the planning scan pays for
/// itself; below it the serial [`merge`] runs (small zooms merge in
/// milliseconds either way, and spawning buys nothing).
const PARALLEL_MERGE_MIN_ENTRIES: u64 = 1_000_000;
/// Worker cap: each worker holds one cursor per chunk, so live readers scale
/// as chunks × workers. More workers also duplicate more skip-walk; 8 is the
/// shape until a bigger box says otherwise.
const PARALLEL_MERGE_MAX_WORKERS: usize = 8;
/// Per-worker output queue bound: backpressure without unbounded buffering.
/// Tiles vary wildly in cost (city vs ocean), so this counts entries, not
/// bytes; the coordinator drains continuously, so queues only ever hold a
/// scheduling bubble, not a range.
const RANGE_QUEUE_BOUND: usize = 32;

/// Whether the range-parallel merge supports zoom `z`.
///
/// The bucket table inverts exactly only when the zoom's tile span divides by
/// [`RANGE_BUCKETS`] (z4 and up: spans 256, 1024, …). On z0–z3 (spans 1–64)
/// an edge lands mid-bucket, the inversion floors a bucket low, and the
/// `remaining` difference undercounts the range's tail — which drops entries.
/// Those zooms stay serial: they merge in milliseconds either way, so planning
/// buys nothing there anyway. The caller gates on this; `plan_ranges` asserts
/// it in debug.
fn parallel_merge_supported(z: u8) -> bool {
    use tilecodec::pmtiles::zoom_base;
    (zoom_base(z + 1) - zoom_base(z)) % RANGE_BUCKETS as u64 == 0
}

/// Phase-A plan: cost-balanced tile ranges plus per-chunk seek positions.
struct RangePlan {
    /// Range starts in tile ids, ascending, length workers+1 (last is the
    /// zoom's end sentinel, exclusive).
    bounds: Vec<u64>,
    /// Per-range merge cost (entry-cost units from the planning scan), for the
    /// balance report. The coordinator drains ranges in order, so the slowest
    /// range sets the merge wall; a range holding most of the cost means the
    /// greedy cut failed and workers idle.
    range_cost: Vec<u64>,
    /// Hottest single tile and its cost: no range split can beat this tile,
    /// so it is the structural floor under the merge wall. See `plan_ranges`.
    hot_tile: u64,
    hot_cost: u64,
    /// Per chunk: file offset of the first entry at-or-after each range start
    /// (chunk end when the range starts past the chunk).
    offsets: Vec<Vec<u64>>,
    /// Per chunk: entries remaining from each range start (0 when the range
    /// starts past the chunk).
    remaining: Vec<Vec<u64>>,
    /// Per chunk: whether each range reaches the chunk's last byte (gates the
    /// no-trailing-bytes check in `next`).
    exact_end: Vec<Vec<bool>>,
}

/// Cost-balanced range boundaries over one zoom's tile space, with seek
/// positions.
///
/// Two serial skip-walks over every chunk — header parses and length walks
/// only, no allocation, no UTF-8, no struct builds, roughly a tenth of a full
/// decode each. Pass 1 accumulates per-tile cost into a histogram and cuts
/// tile-granular ranges from it; pass 2 records each chunk's cursor at every
/// range bound, so seeks are exact and ranges never overlap.
///
/// Exactness is load-bearing, not decorative: the spill's `check_books` gate
/// requires every entry decoded exactly once, so a range that re-reads
/// another's entries fails the build. An earlier revision sought
/// bucket-granular cursors with a skip loop absorbing the difference — the
/// loop walked entries the neighbouring range also walked, the books showed
/// ~196 KB read past written on a California z14, and the build rightly
/// refused. Two walks cost one extra scan; overlapping seeks cost the build.
///
/// Cuts are tile-granular because bucket edges proved too coarse: z14's cost
/// concentrates inside single buckets (one bucket held 97% of a California
/// z14), so no bucket-edge cut could balance and seven of eight workers
/// idled. The sorted key walk (never the map's own order) is what keeps the
/// cuts independent of hashing.
fn plan_ranges(
    chunks: &[ChunkRef],
    spill: &ChunkSpill,
    z: u8,
    workers: usize,
) -> Result<RangePlan> {
    use tilecodec::pmtiles::zoom_base;
    debug_assert!(
        parallel_merge_supported(z),
        "plan_ranges needs an exact span/256 split (z4+); z{z} must stay serial"
    );
    let base = zoom_base(z);
    // Pass 1: per-tile cost across all chunks, plus per-chunk max tile.
    // A HashMap, not a Vec over the span: z14's span is 268 M tiles but only
    // ~7.5 M carry entries, so the dense form would be ~2 GB of mostly
    // zeroes. Never iterated — cuts traverse a sorted key vec, so the map's
    // own order (and its hasher) is unobservable in the output.
    let mut tile_cost: HashMap<u64, u64> = HashMap::new();
    let mut chunk_max: Vec<u64> = Vec::with_capacity(chunks.len());
    let mut chunk_total: Vec<u64> = Vec::with_capacity(chunks.len());
    // Hottest single tile and its cost, across all chunks: the merge assigns
    // whole tiles to one range worker (a tile's layers concatenate in chunk
    // order), so no range split can beat this tile. Reported alongside the
    // per-range costs — when it approaches the largest range, the ceiling is
    // structural, not a balancer bug.
    let mut hot_tile = 0u64;
    let mut hot_cost = 0u64;
    for chunk in chunks {
        let mut reader = spill.reader(chunk, 8192);
        let mut consumed = 0u64;
        let mut max_tile = 0u64;
        // Current tile's accumulated cost, flushed when the tile id changes:
        // chunks store entries tile-major ((tile, layer) order), so a run of
        // equal tile ids is the whole tile.
        let mut run_tile = u64::MAX;
        let mut run_cost = 0u64;
        loop {
            let Some(((tile, _), cost)) = reader.skip_entry()? else {
                break;
            };
            if tile != run_tile {
                if run_tile != u64::MAX {
                    *tile_cost.entry(run_tile).or_insert(0) += run_cost;
                    if run_cost > hot_cost {
                        hot_tile = run_tile;
                        hot_cost = run_cost;
                    }
                }
                run_tile = tile;
                run_cost = 0;
            }
            run_cost += cost;
            consumed += 1;
            max_tile = max_tile.max(tile);
        }
        // Flush the trailing tile run — the loop only flushes on a tile
        // change, so the chunk's last tile would otherwise never count.
        if run_tile != u64::MAX {
            *tile_cost.entry(run_tile).or_insert(0) += run_cost;
            if run_cost > hot_cost {
                hot_tile = run_tile;
                hot_cost = run_cost;
            }
        }
        chunk_max.push(max_tile);
        chunk_total.push(consumed);
    }
    // Greedy cost balance over TILES, not buckets: cut when crossing each
    // multiple of total/workers, with the cut falling between the tiles that
    // straddle the mark. Fixed rule on scanned data — deterministic across
    // runs and machines. Tile-granular because bucket edges proved too coarse:
    // cost concentrates inside single buckets, and only tile steps can split
    // that mass. The sorted key walk (never the map's own order) is what keeps
    // the cuts independent of hashing.
    let total: u64 = tile_cost.values().sum();
    let mut tiles: Vec<u64> = tile_cost.keys().copied().collect();
    tiles.sort_unstable();
    let mut bounds = vec![base];
    let mut acc = 0u64;
    let mut cuts = 1usize;
    for tile in &tiles {
        // Commit the cut BEFORE adding this tile's cost when the mark was
        // already reached: the cut then falls between the previous tile and
        // this one, and this tile opens the next range. Cutting after would
        // hand the straddling tile's whole cost to the range that already
        // crossed its mark — which is how one range ends up owning nearly the
        // whole zoom.
        while cuts < workers && acc * workers as u64 >= total * cuts as u64 {
            let edge = *tile;
            if edge > *bounds.last().expect("bounds") {
                bounds.push(edge);
            }
            cuts += 1;
        }
        acc += tile_cost[tile];
    }
    // The trailing range owns through the last tile: no cut is committed
    // after the loop (the zoom end sentinel below closes it). A mark crossed
    // only by the final tile still leaves that tile in the last range rather
    // than stranding it past every cut.
    bounds.push(zoom_base(z + 1));
    bounds.dedup();
    // Padding: fewer distinct cut positions than workers (tiny zoom, or one
    // tile holding everything). Pad with the zoom end — never with a tile
    // strictly inside the span, which would hand two ranges the same tile.
    while bounds.len() < workers + 1 {
        bounds.push(zoom_base(z + 1));
    }
    // Per-range cost from the same tile histogram the cuts came from: exact,
    // not bucket-approximated. Reported by the caller (one line per zoom on
    // the parallel path).
    let range_cost = {
        let mut costs = Vec::with_capacity(workers);
        for r in 0..workers {
            // Binary search: tiles are sorted, bounds ascending.
            let lo = tiles.partition_point(|&t| t < bounds[r]);
            let hi = tiles.partition_point(|&t| t < bounds[r + 1]);
            costs.push(tiles[lo..hi].iter().map(|t| tile_cost[t]).sum());
        }
        costs
    };
    // Pass 2: each chunk's cursor at every range bound. Bounds ascend and the
    // walk is tile-ordered, so one pointer walks the bounds while the entries
    // stream past: bound `r` records the cursor (and consumed count) of the
    // first entry with tile >= bounds[r] — exactly the first entry the range
    // can own. No bucket machinery: the cursor IS the range start, so the
    // pre-bound skip loop is a provable no-op and `remaining` is the exact
    // owned-entry difference. Ranges partition tiles, so seeks partition
    // entries: nothing is read twice, and the books balance by construction.
    let mut offsets = Vec::with_capacity(chunks.len());
    let mut remaining = Vec::with_capacity(chunks.len());
    let mut exact_end = Vec::with_capacity(chunks.len());
    for (ci, chunk) in chunks.iter().enumerate() {
        let mut reader = spill.reader(chunk, 8192);
        // Bound cursors in walk order: bound 0 is always `base` (every entry
        // qualifies), so it records the chunk start with nothing consumed.
        let mut at_bound = 0usize;
        let mut bound_off = vec![0u64; workers + 1];
        let mut bound_con = vec![0u64; workers + 1];
        bound_off[0] = chunk.at;
        let mut consumed = 0u64;
        loop {
            let off = reader.position();
            let Some(((tile, _), _)) = reader.skip_entry()? else {
                break;
            };
            // Record every bound this entry is the first at-or-after: bounds
            // ascend, entries ascend, so one forward pointer suffices and gap
            // bounds (past this tile but unmet by later ones) are filled by
            // the chunk-end fill below.
            while at_bound < workers && tile >= bounds[at_bound] {
                bound_off[at_bound] = off;
                bound_con[at_bound] = consumed;
                at_bound += 1;
            }
            consumed += 1;
        }
        // Bounds past the chunk's last tile (or past every tile, for a chunk
        // entirely below the bound): chunk end, everything consumed. The
        // range then seeks to chunk end with nothing remaining — it owns
        // nothing here.
        while at_bound <= workers {
            bound_off[at_bound] = chunk.at + chunk.len;
            bound_con[at_bound] = consumed;
            at_bound += 1;
        }
        debug_assert_eq!(
            consumed, chunk_total[ci],
            "the two planning walks must agree on chunk {ci}'s entry count"
        );
        let mut orow = Vec::with_capacity(workers);
        let mut rrow = Vec::with_capacity(workers);
        let mut erow = Vec::with_capacity(workers);
        for r in 0..workers {
            orow.push(bound_off[r]);
            // Owned entries: everything from this bound to the next. Exact —
            // no prefix, no tail — because the cursors are the bounds.
            rrow.push(bound_con[r + 1].saturating_sub(bound_con[r]));
            // Exact end iff the chunk's max tile sits inside this range.
            erow.push(chunk_max[ci] < bounds[r + 1] && chunk_max[ci] >= bounds[r]);
        }
        offsets.push(orow);
        remaining.push(rrow);
        exact_end.push(erow);
    }
    Ok(RangePlan { bounds, range_cost, hot_tile, hot_cost, offsets, remaining, exact_end })
}

/// One range worker's output: tiles in ascending order, or the first error.
///
/// The error carries its range index so the coordinator reports the
/// lowest-indexed failure — deterministic, matching serial's earliest-tile
/// failure. Workers exit when their range is drained or the coordinator drops
/// the receiver (abort on an earlier range's failure): a failed `send` ends
/// the worker rather than blocking it forever.
type RangeOut = Result<(u64, Vec<ChunkEntry>)>;

/// Merge one tile range over all chunks, streaming results in order.
///
/// Same heap discipline as [`Merged`]: chunks consumed in global chunk order,
/// entries for one tile arriving layer-major, concatenated as they arrive. A
/// range is exactly the serial merge restricted to its tile span, so its
/// output is the serial output's subsequence — concatenating ranges in order
/// reproduces the serial tile sequence byte for byte.
///
/// `end_tile` bounds the range (exclusive); entries at-or-after it stay
/// unread for the next range. Chunks not touching the range are skipped
/// without opening a reader.
fn merge_range(
    chunks: &[ChunkRef],
    spill: &ChunkSpill,
    plan: &RangePlan,
    range: usize,
    end_tile: u64,
    out: &RangeTx,
) {
    use std::cmp::Reverse;
    use std::collections::BinaryHeap;
    // Padded trailing ranges ([zoom end, zoom end)) own nothing: yield
    // nothing rather than decoding bucket 255 a second time.
    if plan.bounds[range] >= end_tile {
        return;
    }
    let window = crate::tilespill::read_window_bytes(chunks.len());
    let start_tile = plan.bounds[range];
    let mut readers: Vec<Option<ChunkReader<'_>>> = Vec::with_capacity(chunks.len());
    for (ci, chunk) in chunks.iter().enumerate() {
        let remaining = plan.remaining[ci][range];
        if remaining == 0 {
            readers.push(None);
            continue;
        }
        let mut reader = spill.reader(chunk, window);
        reader.seek_to(plan.offsets[ci][range], remaining, plan.exact_end[ci][range]);
        // Bucket edges are coarser than range bounds: advance past entries
        // below the bound (skip-walked, uncounted, `left` adjusted by the
        // skips themselves so it still exhausts exactly at range end). A
        // corrupt entry reports through the channel like a decode error.
        loop {
            let below = match reader.peek_tile() {
                Ok(Some(t)) => t < start_tile,
                Ok(None) => false,
                Err(e) => {
                    let _ = out.send(Err(e));
                    readers.push(None);
                    continue;
                }
            };
            if !below {
                break;
            }
            match reader.skip_entry() {
                Ok(Some(_)) => {}
                Ok(None) => break,
                Err(e) => {
                    let _ = out.send(Err(e));
                    readers.push(None);
                    continue;
                }
            }
        }
        readers.push(Some(reader));
    }
    let mut heap: BinaryHeap<Reverse<(u64, u8, usize)>> = BinaryHeap::new();
    let mut front: Vec<Held> = Vec::with_capacity(chunks.len());
    for (index, reader) in readers.iter_mut().enumerate() {
        let head = match reader {
            Some(r) => r.next().transpose(),
            None => None,
        };
        match &head {
            Some(Ok(((tile, layer), _))) => heap.push(Reverse((*tile, *layer, index))),
            Some(Err(_)) => heap.push(Reverse((0, 0, index))),
            None => {}
        }
        front.push(head);
    }
    // Mirrors `Merged::next`: the heap top names the tile, the inner loop
    // drains it, entries concatenate layer-major in chunk order.
    while let Some(&Reverse((tile, _, _))) = heap.peek() {
        if tile >= end_tile {
            break;
        }
        let mut layers: Vec<ChunkEntry> = Vec::new();
        while let Some(&Reverse((at, layer_id, index))) = heap.peek() {
            if at != tile {
                break;
            }
            heap.pop();
            let held = front[index].take().expect("a heap key without its entry");
            let (_, mut layer) = match held {
                Ok(entry) => entry,
                Err(e) => {
                    let _ = out.send(Err(e));
                    return;
                }
            };
            match layers.last_mut() {
                Some(last) if last.layer.layer_id == layer_id => {
                    concatenate(last, &mut layer);
                    if let Some(r) = readers[index].as_mut() {
                        r.recycle(layer);
                    }
                }
                _ => layers.push(layer),
            }
            let head = match &mut readers[index] {
                Some(r) => r.next().transpose(),
                None => None,
            };
            match &head {
                Some(Ok(((t, l), _))) => heap.push(Reverse((*t, *l, index))),
                Some(Err(_)) => heap.push(Reverse((0, 0, index))),
                None => {}
            }
            front[index] = head;
        }
        if out.send(Ok((tile, layers))).is_err() {
            return;
        }
    }
}

/// Range-worker entry point for `thread::scope` spawning: merges range `r`
/// and streams it, converting panics-by-Result into channel values.
///
/// Never returns `Err` itself (there is nowhere for it to go — the
/// coordinator owns failure reporting): fallible setup is unreachable here
/// because planning already validated every seek position, so the body is
/// infallible by construction and any `Err` produced mid-merge travels as a
/// value.
fn merge_range_into(
    chunks: &[ChunkRef],
    spill: &ChunkSpill,
    plan: &RangePlan,
    r: usize,
    tx: RangeTx,
) {
    let end_tile = plan.bounds[r + 1];
    merge_range(chunks, spill, plan, r, end_tile, &tx);
}

/// Run-ahead allowance for range workers other than the one being drained, in estimated bytes of
/// merged tile (see [`tile_bytes`]), shared by all of them.
///
/// Ranges drain strictly in order, and each worker used to stop after `RANGE_QUEUE_BOUND` tiles:
/// so while range 0 drained, ranges 1..7 sat parked after 32 tiles each and the "parallel" merge
/// was one k-way merge at a time (California z13: merge 4.5 s however many ranges; the Nov 10
/// planet's z10-z12 merge+encode ran at 6-13 cores). Now the later ranges keep merging into
/// their queues until this much is buffered between them. The range being drained keeps the old
/// count bound -- it is consumed as it is produced -- and is never held by this budget, which is
/// what keeps the drain deadlock-free: the coordinator only ever waits on the current range, and
/// the current range only ever waits on the coordinator.
const RANGE_AHEAD_BYTES: u64 = 2 << 30;

/// A merged tile's rough size in memory, for [`RANGE_AHEAD_BYTES`]: its feature records and its
/// coordinates. An estimate for a budget, never observable in the output.
fn tile_bytes(layers: &[ChunkEntry]) -> u64 {
    layers
        .iter()
        .map(|entry| entry.layer.features.len() as u64 * 32 + entry.layer.coords.len() as u64 * 8)
        .sum::<u64>()
        + 64
}

/// The range workers' output queues, drained in range order by [`ParallelMerge`].
struct RangeQueues {
    state: Mutex<RangeState>,
    cv: std::sync::Condvar,
}

struct RangeState {
    /// Per range: queued tiles (with their byte estimate) and whether its worker is done.
    queues: Vec<std::collections::VecDeque<(RangeOut, u64)>>,
    finished: Vec<bool>,
    /// The range the coordinator is draining.
    current: usize,
    /// Bytes queued in ranges past `current`.
    ahead: u64,
    /// The coordinator went away (finished early on an error): workers stop.
    abandoned: bool,
    /// Who is parked on the condvar, so the hot paths notify only when someone is: a wake per
    /// tile was the merge's whole cost on a zoom of millions of small tiles.
    coordinator_waiting: bool,
    workers_waiting: usize,
}

impl RangeQueues {
    fn new(ranges: usize) -> std::sync::Arc<RangeQueues> {
        std::sync::Arc::new(RangeQueues {
            state: Mutex::new(RangeState {
                queues: (0..ranges).map(|_| std::collections::VecDeque::new()).collect(),
                finished: vec![false; ranges],
                current: 0,
                ahead: 0,
                abandoned: false,
                coordinator_waiting: false,
                workers_waiting: 0,
            }),
            cv: std::sync::Condvar::new(),
        })
    }

    /// Range `r`'s sending half, for its worker. Dropping it marks the range finished.
    fn sender(self: &std::sync::Arc<Self>, r: usize) -> RangeTx {
        RangeTx { queues: std::sync::Arc::clone(self), r }
    }
}

/// One range worker's end of [`RangeQueues`]: `send` in the shape of the channel it replaced.
struct RangeTx {
    queues: std::sync::Arc<RangeQueues>,
    r: usize,
}

impl RangeTx {
    /// Queue one tile (or error) for range `r`. `Err` when the coordinator is gone, which ends the
    /// worker exactly as a dropped receiver did.
    fn send(&self, item: RangeOut) -> std::result::Result<(), ()> {
        let bytes = match &item {
            Ok((_, layers)) => tile_bytes(layers),
            Err(_) => 0,
        };
        let q = &self.queues;
        let mut s = q.state.lock().expect("the merge's range queues");
        loop {
            if s.abandoned {
                return Err(());
            }
            let wait = if self.r == s.current {
                s.queues[self.r].len() >= RANGE_QUEUE_BOUND
            } else {
                // Always let a range queue its first tile, so a range is never blocked behind its
                // own empty queue; past that, the shared allowance decides.
                !s.queues[self.r].is_empty() && s.ahead + bytes > RANGE_AHEAD_BYTES
            };
            if !wait {
                break;
            }
            s.workers_waiting += 1;
            s = q.cv.wait(s).expect("the merge's range queues");
            s.workers_waiting -= 1;
        }
        if self.r != s.current {
            s.ahead += bytes;
        }
        s.queues[self.r].push_back((item, bytes));
        let wake = self.r == s.current && s.coordinator_waiting;
        drop(s);
        if wake {
            q.cv.notify_all();
        }
        Ok(())
    }
}

impl Drop for RangeTx {
    fn drop(&mut self) {
        let q = &self.queues;
        q.state.lock().expect("the merge's range queues").finished[self.r] = true;
        q.cv.notify_all();
    }
}

/// Ordered coordinator over range workers: an iterator yielding the serial
/// tile sequence.
///
/// Ranges drain strictly in order (range 0 to exhaustion, then range 1, ...),
/// so the yielded sequence is tile-ascending regardless of completion order.
/// A range's error aborts the drain with the lowest-indexed failure: ranges
/// before it drained cleanly, so it is the earliest tile-ordered failure —
/// deterministic, like serial.
struct ParallelMerge {
    queues: std::sync::Arc<RangeQueues>,
    ranges: usize,
    done: bool,
    /// Tiles taken off the current range's queue in one go, handed out one per `next`.
    local: std::collections::VecDeque<RangeOut>,
}

impl ParallelMerge {
    fn new(queues: std::sync::Arc<RangeQueues>) -> ParallelMerge {
        let ranges = queues.state.lock().expect("the merge's range queues").queues.len();
        ParallelMerge { queues, ranges, done: false, local: std::collections::VecDeque::new() }
    }

    /// Move everything queued for the current range into `local`, advancing past finished
    /// ranges, waiting only when the current range has nothing yet. `false` once every range
    /// has drained.
    fn refill(&mut self) -> bool {
        let q = &self.queues;
        let mut s = q.state.lock().expect("the merge's range queues");
        loop {
            if s.current >= self.ranges {
                return false;
            }
            let current = s.current;
            if !s.queues[current].is_empty() {
                self.local.extend(s.queues[current].drain(..).map(|(item, _)| item));
                let wake = s.workers_waiting > 0;
                drop(s);
                if wake {
                    q.cv.notify_all();
                }
                return true;
            }
            if s.finished[current] {
                // Range drained cleanly: advance. Its successor's queued bytes stop counting
                // against the run-ahead allowance now that it is the one being drained.
                s.current += 1;
                if s.current < self.ranges {
                    let next = s.current;
                    let queued: u64 = s.queues[next].iter().map(|(_, b)| *b).sum();
                    s.ahead -= queued;
                }
                if s.workers_waiting > 0 {
                    q.cv.notify_all();
                }
                continue;
            }
            s.coordinator_waiting = true;
            s = q.cv.wait(s).expect("the merge's range queues");
            s.coordinator_waiting = false;
        }
    }
}

impl Drop for ParallelMerge {
    /// Release any worker still parked on its queue: whatever ended the drain, nobody reads
    /// further, and the workers' scope is waiting to join them.
    fn drop(&mut self) {
        self.queues.state.lock().expect("the merge's range queues").abandoned = true;
        self.queues.cv.notify_all();
    }
}

impl Iterator for ParallelMerge {
    type Item = Result<(u64, Vec<ChunkEntry>)>;

    fn next(&mut self) -> Option<Result<(u64, Vec<ChunkEntry>)>> {
        if self.done {
            return None;
        }
        if self.local.is_empty() && !self.refill() {
            self.done = true;
            return None;
        }
        let item = self.local.pop_front().expect("refilled above");
        if item.is_err() {
            // Lowest-indexed failure wins because lower ranges already drained clean; nothing
            // further is read.
            self.done = true;
            self.local.clear();
            self.queues.state.lock().expect("the merge's range queues").abandoned = true;
            self.queues.cv.notify_all();
        }
        Some(item)
    }
}

enum MergeStream<'a> {
    Serial(Merged<'a>),
    Parallel(ParallelMerge),
}

impl Iterator for MergeStream<'_> {
    type Item = Result<(u64, Vec<ChunkEntry>)>;

    fn next(&mut self) -> Option<Result<(u64, Vec<ChunkEntry>)>> {
        match self {
            MergeStream::Serial(m) => m.next(),
            MergeStream::Parallel(m) => m.next(),
        }
    }
}

#[cfg(test)]
mod tests_parallel {
    use super::*;
    use crate::tiler::tests::*;
    use crate::tilespill::{ChunkRef, ChunkSpill};

    /// Drive a merge iterator to a vec, failing the test on the first error
    /// rather than publishing a short sequence.
    fn collect<I>(merged: &mut I) -> Vec<(u64, Vec<ChunkEntry>)>
    where
        I: Iterator<Item = Result<(u64, Vec<ChunkEntry>)>>,
    {
        merged
            .by_ref()
            .collect::<Result<Vec<_>>>()
            .expect("merge the chunks")
    }

    /// Map a fixture into spill chunks at `z`, returning refs plus the spill.
    /// The spill outlives the refs: callers keep both alive together. Forces
    /// small vertex chunks so the fixture spans several chunks even at test
    /// scale (a single-chunk merge exercises nothing).
    fn map_chunks(
        features: &[crate::extract::Feature],
        z: u8,
    ) -> (Vec<ChunkRef>, ChunkSpill) {
        let _guard = budget();
        crate::tiler::set_chunk_vertices(64);
        let store = spilled(features);
        let spill = ChunkSpill::create(scratch()).expect("spill");
        let tolerance = tile_build::simplify::tolerance_for(z, crate::DEFAULT_MAX_ZOOM, 1.0);
        let buffer = tile_build::geom::buffer_for(crate::tiler::EXTENT);
        let settings = settings(0, 14);
        let _ = &settings;
        let (chunks, _) =
            crate::tiler::map_zoom(&store, &settings, z, tolerance, buffer, &spill)
                .expect("map the fixture");
        crate::tiler::set_chunk_vertices(0);
        (chunks, spill)
    }

    /// The gate this work stands on: range-parallel output is the serial
    /// output, tile for tile, layer for layer, byte for byte — over a
    /// multi-chunk fixture with lakes, buildings and cross-tile rivers, at a
    /// zoom where the wash gate fires (z14) and one where it does not (z10).
    /// Both zooms are above z3, where the bucket table inverts exactly (see
    /// `parallel_merge_supported`) — the identity below rests on that.
    #[test]
    fn parallel_merge_is_the_serial_merge() {
        for z in [10u8, 14] {
            let (chunks, spill) = map_chunks(&a_crowd(), z);
            assert!(chunks.len() > 1, "the fixture must span chunks at z{z}");
            let mut serial = super::merge(&chunks, &spill);
            let want = collect(&mut serial);
            assert!(!want.is_empty(), "the fixture must yield tiles at z{z}");
            for workers in [2usize, 3, 7] {
                let plan =
                    super::plan_ranges(&chunks, &spill, z, workers).expect("plan the ranges");
                let got = std::thread::scope(|s| {
                    let queues = super::RangeQueues::new(workers);
                    let chunks_ref = &chunks;
                    let spill_ref = &spill;
                    let plan_ref = &plan;
                    for r in 0..workers {
                        let tx = queues.sender(r);
                        s.spawn(move || {
                            super::merge_range_into(chunks_ref, spill_ref, plan_ref, r, tx);
                        });
                    }
                    let mut coord = super::ParallelMerge::new(queues);
                    collect(&mut coord)
                });
                assert_eq!(got, want, "worker count {workers} moved z{z}");
            }
        }
    }

    /// Ranges that hold nothing yield nothing: a single-tile fixture merged
    /// under more workers than tiles still produces exactly that tile.
    /// (A pool, not a lake: wash areas never reach z14 tiles under the cap.)
    #[test]
    fn empty_ranges_yield_nothing() {
        let pool = crate::extract::Feature {
            class: crate::schema::Class::area(
                tilecodec::mamaps::dict::LAYER_LANDTYPE,
                crate::schema::kind("swimming_pool"),
                14,
            ),
            geometry: crate::tiler::tests::square(-122.0, 37.0, 0.005),
            name: None,
            id: tilecodec::mamaps::body::ID_NONE,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
            turn_fwd: Vec::new(),
            turn_bwd: Vec::new(),
            building: None,
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
        };
        let (chunks, spill) = map_chunks(&[pool], 14);
        let mut serial = super::merge(&chunks, &spill);
        let want = collect(&mut serial);
        assert!(!want.is_empty());
        let workers = 4usize;
        let plan = super::plan_ranges(&chunks, &spill, 14, workers).expect("plan");
        let got = std::thread::scope(|s| {
            let queues = super::RangeQueues::new(workers);
            let chunks_ref = &chunks;
            let spill_ref = &spill;
            let plan_ref = &plan;
            for r in 0..workers {
                let tx = queues.sender(r);
                s.spawn(move || {
                    super::merge_range_into(chunks_ref, spill_ref, plan_ref, r, tx);
                });
            }
            let mut coord = super::ParallelMerge::new(queues);
            collect(&mut coord)
        });
        assert_eq!(got, want, "empty ranges must not add or drop tiles");
    }

    /// The seek/count half of the plan, pinned directly: per-chunk
    /// `remaining` summed over chunks must cover the range's tile-layer
    /// entries — every (tile, layer) pair the range owns must be walked, no
    /// fewer. The serial merge yields each tile with its layers; counting
    /// layer entries per tile over the serial output gives the per-tile
    /// histogram, and each range's share must be covered by the plan's rows.
    ///
    /// Coverage, not equality: the plan counts from the containing bucket's
    /// cursor, so it INCLUDES the below-bound prefix the skip loop walks AND
    /// the mid-bucket tail past the end bound that the `end_tile` stop
    /// discards. Planned >= wanted always; planned < wanted means a starved
    /// reader — the failure this test exists to catch.
    #[test]
    fn plan_seek_counts_match_the_tile_histogram() {
        for z in [10u8, 14] {
            let (chunks, spill) = map_chunks(&a_crowd(), z);
            assert!(chunks.len() > 1, "the fixture must span chunks at z{z}");
            // Per-tile LAYER-entry counts from the serial output: one per
            // (tile, layer) pair, which is the unit the spill — and the
            // reader's `left` budget — counts in.
            let mut serial = super::merge(&chunks, &spill);
            let mut tile_entries: HashMap<u64, u64> = HashMap::new();
            for (tile, layers) in collect(&mut serial) {
                *tile_entries.entry(tile).or_insert(0) += layers.len() as u64;
            }
            for workers in [2usize, 3, 7] {
                let plan =
                    super::plan_ranges(&chunks, &spill, z, workers).expect("plan the ranges");
                // Bounds ascend and partition the zoom: first is the base,
                // last is the end sentinel, workers+1 entries.
                assert_eq!(plan.bounds.len(), workers + 1, "z{z} w{workers}");
                for w in plan.bounds.windows(2) {
                    assert!(w[0] <= w[1], "bounds ascend at z{z} w{workers}");
                }
                for r in 0..workers {
                    // Entries the plan says range r walks, summed over chunks.
                    let planned: u64 =
                        plan.remaining.iter().map(|row| row[r]).sum();
                    // Layer entries the histogram puts inside the range.
                    let want: u64 = tile_entries
                        .iter()
                        .filter(|(&t, _)| t >= plan.bounds[r] && t < plan.bounds[r + 1])
                        .map(|(_, &n)| n)
                        .sum();
                    assert!(
                        planned >= want,
                        "z{z} w{workers} range {r}: plan walks {planned}, histogram holds {want}"
                    );
                }
            }
        }
    }

    /// z0–z3 stay serial: their spans (1–64 tiles) don't divide the 256 cost
    /// buckets, so range edges can't invert exactly. The zoom loop gates on
    /// this — if the gate ever lets a low zoom through, planning must refuse.
    #[test]
    fn low_zooms_stay_serial() {
        for z in [0u8, 1, 2, 3] {
            assert!(!super::parallel_merge_supported(z), "z{z} must stay serial");
        }
        for z in [4u8, 10, 14] {
            assert!(super::parallel_merge_supported(z), "z{z} must parallelise");
        }
    }

    /// The full-archive version of the merge gate: the zoom loop's own
    /// selection (`merge_gate` → plan/workers vs serial) agrees with itself
    /// across thread counts. The unit tests above drive the planner and
    /// workers directly; this drives `build()` end to end — so a miswired
    /// gate can't hide behind correct components. One thread takes the
    /// serial path (single worker cap... no: `merge_workers > 1` fails, so
    /// serial), four threads take the range workers wherever the zoom is big
    /// enough; both must produce the same archive.
    #[test]
    fn gate_paths_agree_through_build() {
        use crate::tiler::tests::{budget, release_threads};
        let _guard = budget();
        crate::tiler::set_chunk_vertices(64);
        let store = spilled(&a_crowd());
        tile_build::par::set_threads(1);
        let serial = crate::tiler::build(&store, &settings(0, 14)).expect("one thread").0;
        tile_build::par::set_threads(4);
        let parallel = crate::tiler::build(&store, &settings(0, 14)).expect("four threads").0;
        crate::tiler::set_chunk_vertices(0);
        release_threads();
        assert_eq!(parallel, serial, "thread count moved the archive");
    }
}
