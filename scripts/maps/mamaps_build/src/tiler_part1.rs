/// Tile every feature and write the archive.
/// Tile every feature in `store` into a `.mamaps` archive.
///
/// The store is read **once per zoom**, in order, rather than held in memory for all of them. Fifteen
/// sequential passes over a file cost seconds on any modern disk; holding the features cost 4.9 GB of
/// a measured 10.03 GB California peak.
pub fn build(store: &Store, settings: &Settings) -> Result<(Vec<u8>, Vec<ZoomStats>)> {
    adopt_thread_budget();
    let mut writer = writer_for(store, settings)?;
    // Tests share the zoom loop but report no phase: the timer is dropped without `finish`,
    // so no `TIMING` line ever reaches test output.
    let mut timer = crate::timing::PhaseTimer::start("tiles");
    let per_zoom = tile_zooms(store, settings, &mut writer, &mut timer)?;
    let bytes = writer.finish()?;
    Ok((bytes, per_zoom))
}

/// Tile every feature in `store` straight into the archive at `out`, without ever holding the
/// archive in memory.
///
/// `build` above returns the whole archive as a `Vec<u8>` -- fine for tests, fatal for a planet:
/// the data section alone is tens of GB, and holding it beside the merge's own peak is what OOMs
/// a box with ~50 GB of commit headroom right after z14 merges. This runs the identical zoom loop
/// ([`tile_zooms`]) and finishes through [`StreamWriter::finish_to_path`], so the only thing ever
/// held is the prefix (the index, small next to the bodies). Same tiles, same order, same bytes:
/// the writer appends identically either way, and `finish_to_path` assembles the same
/// header/dictionary/root/leaves/data layout as `finish`.
pub fn build_to_path(
    store: &Store,
    settings: &Settings,
    out: &std::path::Path,
    timer: &mut crate::timing::PhaseTimer,
) -> Result<Vec<ZoomStats>> {
    adopt_thread_budget();
    let mut writer = writer_for(store, settings)?;
    let per_zoom = tile_zooms(store, settings, &mut writer, timer)?;
    writer.finish_to_path(out)?;
    Ok(per_zoom)
}

/// The [`StreamWriter`] for a build: zoom range, build id, compression, and the body scratch
/// beside the output.
///
/// The scratch placement matters: the writer spills the data section (tens of GB on a planet)
/// through a temp file, and the default is the system temp dir -- routinely a small system
/// volume. Beside the output it shares the volume the operator already sized for the build
/// (feature spill, tile chunks, archive), and it is removed on drop like every other scratch
/// file. Path only, never bytes: the archive is identical wherever the scratch lives.
fn writer_for(store: &Store, settings: &Settings) -> Result<StreamWriter> {
    let bbox = store.bbox();
    StreamWriter::new(Options {
        min_zoom: 0,
        max_zoom: crate::DEFAULT_MAX_ZOOM,
        build_id: settings.build_id,
        compress: true,
        // Stage C runs on every tile below, so the claim is true. It is what lets the renderer
        // skip its repair pass — and the renderer still keeps that pass, gated, because a claim is
        // only as good as the generator making it.
        rings_validated: true,
        min_lon_e7: bbox.0,
        min_lat_e7: bbox.1,
        max_lon_e7: bbox.2,
        max_lat_e7: bbox.3,
        spill_dir: settings.scratch.parent().map(std::path::Path::to_path_buf),
        // Leaf compression at finish runs on the build's own thread budget.
        threads: par::threads(),
        // The body scratch holds already-DEFLATEd bodies (~53 GB on a planet): keep btrfs
        // `compress-force` from zstd'ing them again at writeback.
        spill_file_hook: Some(osm_ingest::mem::mark_scratch_uncompressed),
        ..Options::default()
    })
}

/// The zoom loop both builders share: map, merge, encode and append z0..=z14 through `writer`.
///
/// Extracted whole from `build` so the two finishes cannot diverge: the per-zoom stats, the
/// progress bars, the `check_books` gate and the append order are identical, and only the final
/// assembly differs (in-memory vs streaming).
fn tile_zooms(
    store: &Store,
    settings: &Settings,
    writer: &mut StreamWriter,
    timer: &mut crate::timing::PhaseTimer,
) -> Result<Vec<ZoomStats>> {

    let mut per_zoom = Vec::new();
    // The tile-chunk spill's backend, decided once from the feature count: z14 holds the most
    // clipped copies at once (the 53 GB north-america case that motivated the spill), so sizing
    // for the whole store is the safe side. Per-zoom files share one scratch path -- each zoom's
    // spill truncates it on create and removes it on drop, so peak disk is still the largest
    // single zoom.
    let chunk_plan = plan_chunk_spill(store, &settings.scratch);
    // The operator's override wins over the gate: `force_chunk_spill_file` (from
    // `MAPS_ANON_SPILL`, unset or non-`1`) stages through the scratch file even when the gate
    // picks anon. Same chunks, same bytes, no commit charge for the merge to trip over.
    let chunk_plan = if settings.force_chunk_spill_file
        && chunk_plan == osm_ingest::mem::SpillPlan::Anon
    {
        eprintln!(
            "  [tiler] chunk spill -> {} (file backend: MAPS_ANON_SPILL)",
            settings.scratch.display(),
        );
        osm_ingest::mem::SpillPlan::File
    } else {
        chunk_plan
    };
    for z in 0..=crate::DEFAULT_MAX_ZOOM {
        let mut stats = ZoomStats { zoom: z, ..ZoomStats::default() };
        let tolerance =
            simplify::tolerance_for(z, crate::DEFAULT_MAX_ZOOM, DEFAULT_SIMPLIFICATION);
        let buffer = geom::buffer_for(EXTENT);

        // Per zoom, so peak scratch is the largest single zoom rather than the sum, and so a build
        // that dies at z14 leaves one zoom behind rather than fifteen. Anonymous
        // pagefile-backed memory (no file) when commit allows, else the scratch file; the merge
        // reads through the same ChunkReader either way.
        let spill = ChunkSpill::create_planned(&settings.scratch, chunk_plan)?;
        // Per-zoom `map`: repeat visits merge into one total at `finish`.
        timer.switch("map");
        let mapped = std::time::Instant::now();
        let (chunks, tally) = map_zoom(store, settings, z, tolerance, buffer, &spill)?;
        stats.map_ms = mapped.elapsed().as_millis() as u64;
        stats.features = tally.features;
        stats.points = tally.points;
        stats.dropped = tally.dropped;

        // Ascending by tile id because the merge makes it so, not because a sort step was
        // remembered. A `BTreeMap` per chunk and a heap across them is the same guarantee the
        // single-threaded `BTreeMap` gave; the maps now live on disk and the guarantee does not
        // change, because a chunk's bytes are written in its key order and read back in file order.
        //
        // Range-parallel merge when the zoom repays planning: one worker k-way merges each
        // cost-balanced tile range (see `tiler_part3::merge_parallel`), drained in order by the
        // same consume loop below — sequence-identical to serial, so batches, prune and append
        // are untouched. Small zooms stay serial: planning costs more than it saves there. So do
        // z0–z3, whose tile spans (1–64) don't divide by the 256 cost buckets — the bucket table
        // can't invert those edges exactly (see `parallel_merge_supported`).
        //
        // The choice is `merge_gate` below, hardcoded: no environment override.
        // Operator control arrives through `--threads`/`MAPS_THREADS` (which sizes the
        // worker count) exactly like every other parallel stage in this tree.
        let merge_parallel = merge_gate(&chunks, z);
        if merge_parallel {
            let merge_workers = par::threads().min(crate::tiler::PARALLEL_MERGE_MAX_WORKERS);
            // One worker owns the whole tile space on a one-thread box, which is
            // the serial sequence by construction — correctness never depends on
            // the count, only speed does.
            let merge_workers = merge_workers.max(1);
            // Timed as its own subphase: the planning scan is a serial
            // skip-walk over every chunk, and at z14 it is large enough to
            // matter against the merge it parallelises.
            timer.switch("merge-plan");
            let planning = std::time::Instant::now();
            // Many more ranges than workers (see `plan_ranges_auto`): workers claim ranges in
            // order and the coordinator drains them in order, so `merge_workers` consecutive
            // ranges merge at once instead of one range at a time behind a full run-ahead budget.
            let plan = crate::tiler::plan_ranges_auto(&chunks, &spill, z, merge_workers)?;
            let ranges = plan.bounds.len() - 1;
            stats.merge_ms += planning.elapsed().as_millis() as u64;
            // One line per zoom on the parallel path: the spread of per-range cost estimates.
            // `hot` is the costliest single tile: ranges assign whole tiles, so no range can go
            // below it however they split.
            let (lo, hi) = plan
                .range_cost
                .iter()
                .fold((u64::MAX, 0u64), |(lo, hi), &c| (lo.min(c), hi.max(c)));
            eprintln!(
                "  [merge] z{z}: {ranges} range(s) on {merge_workers} worker(s), cost {lo}..{hi} per range, hot tile {} at {}, entries {}, planned in {:.1}s",
                plan.hot_tile,
                plan.hot_cost,
                plan.remaining
                    .iter()
                    .map(|row| row.iter().sum::<u64>())
                    .sum::<u64>(),
                planning.elapsed().as_secs_f64(),
            );
            let queues = crate::tiler::RangeQueues::new(ranges);
            let claim = AtomicUsize::new(0);
            std::thread::scope(|s| -> Result<()> {
                // Shared refs are `Copy`: each spawned closure gets its own
                // copy rather than moving `chunks`/`spill` on first use.
                let chunks_ref = &chunks;
                let spill_ref = &spill;
                let range_plan = &plan;
                for _ in 0..merge_workers.min(ranges) {
                    let (queues, claim) = (&queues, &claim);
                    s.spawn(move || loop {
                        let r = claim.fetch_add(1, Ordering::Relaxed);
                        if r >= ranges || queues.abandoned() {
                            return;
                        }
                        crate::tiler::merge_range_into(
                            chunks_ref,
                            spill_ref,
                            range_plan,
                            r,
                            queues.sender(r),
                        );
                    });
                }
                let mut stream =
                    crate::tiler::MergeStream::Parallel(crate::tiler::ParallelMerge::new(
                        std::sync::Arc::clone(&queues),
                    ));
                consume_merged(
                    &mut stream,
                    &mut stats,
                    z,
                    timer,
                    &settings.dem,
                    store.conventions(),
                    &settings.region_links,
                    writer,
                )?;
                Ok(())
            })?;
        } else {
            let mut stream = crate::tiler::MergeStream::Serial(merge(&chunks, &spill));
            consume_merged(
                &mut stream,
                &mut stats,
                z,
                timer,
                &settings.dem,
                store.conventions(),
                &settings.region_links,
                writer,
            )?;
        }
        // Reserved, written, on disk and read back must agree. A chunk that quietly lost entries
        // would produce an archive with holes in it and nothing downstream could tell.
        spill.check_books()?;
        per_zoom.push(stats);
    }

    Ok(per_zoom)
}

/// The entry gate for the range-parallel merge: big supported zoom on a
/// multi-thread box.
///
/// Split out so the call site reads as intent rather than inline conditions.
/// z0–z3 stay serial however threaded: they can't invert their bucket edges
/// exactly, so they have one path and no divergence to compare.
fn merge_gate(chunks: &[ChunkRef], z: u8) -> bool {
    let total_entries: u64 = chunks.iter().map(|c| c.entries).sum();
    let merge_workers = par::threads().min(crate::tiler::PARALLEL_MERGE_MAX_WORKERS);
    total_entries >= crate::tiler::PARALLEL_MERGE_MIN_ENTRIES
        && merge_workers > 1
        && crate::tiler::parallel_merge_supported(z)
}

/// Merge → encode → prune → append for one zoom, over any tile-ordered merge
/// iterator.
///
/// Extracted whole from the zoom loop so serial and range-parallel merges share
/// it: batches are contiguous pulls in merge order, `encode_batch` preserves
/// tile order, and prune/append see the identical sequence either way — which
/// is why the archive does not move between the two paths.
#[allow(clippy::too_many_arguments)]
fn consume_merged(
    merged: &mut (impl Iterator<Item = Result<(u64, Vec<ChunkEntry>)>> + Send),
    stats: &mut ZoomStats,
    z: u8,
    timer: &mut crate::timing::PhaseTimer,
    dem: &crate::dem::Dem,
    conventions: &crate::schema::boundaries::Conventions,
    region_links: &std::collections::HashMap<u64, u64>,
    writer: &mut tilecodec::mamaps::write::StreamWriter,
) -> Result<()> {
    // Merge, encode and append have no total to count against -- the tile count is only known once
    // the merge has produced it -- so the merge bar counts tiles merged and the encode line below
    // counts tiles written.
    let zoom_start = std::time::Instant::now();
    let mut merged_tiles = 0usize;
    let mut merge_shown = 0usize;
    let mut written = 0usize;
    let mut shown = 0usize;
    // Pipelined by one batch: while batch `k` is appended (serial: the writer's dedup and its
    // scratch writes), batch `k + 1` is pulled from the merge and encoded on the pool. The three
    // used to take turns, so the pool sat idle through every append and the writer through
    // every encode. Batches are the same contiguous pulls of the merge, encoded and pruned
    // exactly as before (`prune_barren_subtree` never looks past its own batch), and appended in
    // the same order, so the archive does not move. The per-zoom `merge`/`encode`/`append`
    // columns still time each part on its own; the run's phase clock books the overlapped
    // stretch as `merge`.
    let mut pending: Option<Vec<Encoded>> = None;
    // A third stage ahead of those two: a puller thread takes the merge batches (the k-way merge,
    // or the parallel coordinator) for pass `k + 2` while pass `k + 1` encodes and pass `k`
    // appends. The pulls are the same `merge_batch` calls in the same order with the same cuts,
    // only on another thread; the hand-off is a one-deep channel, so at most one pulled pass
    // waits beside the two in flight.
    type Pulled = Result<Option<(Vec<(u64, Vec<ChunkEntry>)>, Vec<usize>, u64)>>;
    std::thread::scope(|scope| -> Result<()> {
        let (pull_tx, pull_rx) = std::sync::mpsc::sync_channel::<Pulled>(1);
        let merged = &mut *merged;
        scope.spawn(move || loop {
            let merging = std::time::Instant::now();
            // Several merge batches per encode pass, each cut exactly where `merge_batch`
            // always cut it. One cost-capped batch is too little work to spread over 192 threads
            // (California z13: ~8 encode tasks running, the rest of the pool spinning), but the
            // cut points are what `prune_barren_subtree` is bounded by, so they are kept and each
            // batch is pruned on its own. Encoding is per tile, so the bytes do not depend on how
            // many batches share a pass.
            let mut batch: Vec<(u64, Vec<ChunkEntry>)> = Vec::new();
            let mut cuts: Vec<usize> = Vec::new();
            let mut failed = None;
            for _ in 0..BATCHES_PER_ENCODE {
                // A truncated scratch file must fail the build rather than end the zoom early and
                // publish a short archive.
                match merge_batch(&mut *merged, par::batch_len()) {
                    Ok(pulled) if pulled.is_empty() => break,
                    Ok(pulled) => {
                        batch.extend(pulled);
                        cuts.push(batch.len());
                    }
                    Err(e) => {
                        failed = Some(e);
                        break;
                    }
                }
            }
            let item: Pulled = match failed {
                Some(e) => Err(e),
                None if batch.is_empty() => Ok(None),
                None => Ok(Some((batch, cuts, merging.elapsed().as_millis() as u64))),
            };
            let last = !matches!(item, Ok(Some(_)));
            if pull_tx.send(item).is_err() || last {
                return;
            }
        });
        loop {
            // Cost-batched rather than count-batched: z14 has tiles whose merged geometry is tens
            // of MB (a dense city tile's whole layer set) beside rural tiles worth bytes. A
            // fixed `batch_len()` of those holds ~GB of merged tiles plus their encoded copies
            // before the writer takes them -- on top of the merge's own peak, on a box with
            // ~50 GB of commit headroom, which is where `Map z14 [100%]` went followed by a
            // 512 KiB allocation failure. Capped at `MERGE_BATCH_BYTES` of merged cost, so a
            // heavy tile still gets its own batch (a minimum of one batch per pull) and common
            // zooms -- whose batches never reach the cap -- take the same count they always
            // did. Order is untouched: batches are contiguous pulls of the merge in order, and
            // `encode_batch` preserves tile order, so the archive does not move.
            timer.switch("merge");
            let pulled = pull_rx
                .recv()
                .unwrap_or_else(|_| Err(tile_build::proto::Error("the merge puller stopped".into())));
            let (appended, produced) = par::install(|| {
                rayon::join(
                    || -> Result<u64> {
                        let Some(done) = pending.take() else { return Ok(0) };
                        let appending = std::time::Instant::now();
                        for (id, encoded, rings, lines) in done {
                            stats.rings.add(rings);
                            stats.lines.add(lines);
                            let Some((stored, raw_len, hash)) = encoded else { continue };
                            // Uncompressed, as this column has always meant.
                            stats.bytes += raw_len as u64;
                            stats.tiles += 1;
                            // Handed over whole: the writer's background thread copies and
                            // writes the body, this thread only does the dedup bookkeeping.
                            writer.append_stored_owned(id, stored, hash)?;
                            written += 1;
                            // Every 25k tiles: often enough to look alive on a continent, rare
                            // enough that the write itself is never the cost.
                            if written - shown >= 25_000 {
                                shown = written;
                                eprint!(
                                    "\r{:<28} [{written:>10} tile(s)]  {} elapsed   ",
                                    format!("Encode z{z}"),
                                    tile_build::progress::hms(zoom_start.elapsed().as_secs_f64()),
                                );
                                let _ = std::io::Write::flush(&mut std::io::stderr());
                            }
                        }
                        Ok(appending.elapsed().as_millis() as u64)
                    },
                    || -> Result<Option<(Vec<Encoded>, usize, u64, u64)>> {
                        let Some((batch, cuts, merge_ms)) = pulled? else { return Ok(None) };
                        let tiles = batch.len();
                        let encoding = std::time::Instant::now();
                        let encoded = encode_batch(batch, dem, conventions, region_links)?;
                        // Phase B: prune barren subtrees. A tile whose four children all came
                        // back empty (`None` — genuinely featureless, or full-bleed water
                        // background) is itself the end of the tree: drop the children and let
                        // the reader stop at the parent (overzoom). Only above z0 and only when
                        // all four children of a parent are present-and-empty in one merge
                        // batch — never across batch boundaries, so the archive stays
                        // independent of batching and thread count.
                        let mut done: Vec<Encoded> = Vec::with_capacity(encoded.len());
                        let mut encoded = encoded.into_iter();
                        let mut start = 0usize;
                        for &end in &cuts {
                            let mut piece: Vec<Encoded> = encoded.by_ref().take(end - start).collect();
                            prune_barren_subtree(z, &mut piece);
                            done.extend(piece);
                            start = end;
                        }
                        Ok(Some((done, tiles, merge_ms, encoding.elapsed().as_millis() as u64)))
                    },
                )
            });
            // The append of the earlier batch reports first, as it did when it ran first.
            stats.append_ms += appended?;
            let Some((done, tiles, merge_ms, encode_ms)) = produced? else { break };
            stats.merge_ms += merge_ms;
            stats.encode_ms += encode_ms;
            // Count the tiles as soon as the merge produced them, so the bar moves during the
            // merge rather than only when the append drains.
            merged_tiles += tiles;
            if merged_tiles - merge_shown >= 25_000 {
                merge_shown = merged_tiles;
                eprint!(
                    "\r{:<28} [{merged_tiles:>10} tile(s)]  {} elapsed   ",
                    format!("Merge z{z}"),
                    tile_build::progress::hms(zoom_start.elapsed().as_secs_f64()),
                );
                let _ = std::io::Write::flush(&mut std::io::stderr());
            }
            pending = Some(done);
        }
        Ok(())
    })?;
        if merged_tiles > 0 {
            eprintln!(
                "\r{:<28} [{merged_tiles:>10} tile(s)]  {} elapsed   ",
                format!("Merge z{z}"),
                tile_build::progress::hms(zoom_start.elapsed().as_secs_f64()),
            );
        }
        if written > 0 {
            eprintln!(
                "\r{:<28} [{written:>10} tile(s)]  {} elapsed   ",
                format!("Encode z{z}"),
                tile_build::progress::hms(zoom_start.elapsed().as_secs_f64()),
            );
        }
    Ok(())
}

/// Merge batches gathered into one encode pass by [`consume_merged`]. Each keeps its own cut (and
/// its own prune); this only decides how much work one pool hand-off carries.
const BATCHES_PER_ENCODE: usize = 16;

/// One merge pull, capped by merged-geometry cost rather than by tile count.
///
/// Pulls up to `max_tiles` from the merge, stopping early once the accumulated `tile_cost` --
/// features plus coordinates, the same proxy the encode pass balances tasks by -- reaches
/// [`MERGE_BATCH_BYTES`]. Always pulls at least one tile, so a tile heavier than the cap on its
/// own still makes progress. The pulled prefix is contiguous merge order, so feeding it to
/// `encode_batch` (which preserves tile order) keeps the archive byte-identical: the only
/// change is how many tiles one batch holds.
///
/// Contiguity is also what [`prune_barren_subtree`] relies on: four siblings of one parent
/// occupy consecutive tile ids, so a parent's whole child set is either inside one batch or
/// split across a batch boundary — and only the first case prunes, which keeps the archive
/// independent of where the batcher cut.
fn merge_batch(
    merged: &mut impl Iterator<Item = Result<(u64, Vec<ChunkEntry>)>>,
    max_tiles: usize,
) -> Result<Vec<(u64, Vec<ChunkEntry>)>> {
    let mut batch: Vec<(u64, Vec<ChunkEntry>)> = Vec::new();
    let mut cost: u64 = 0;
    for tile in merged.by_ref().take(max_tiles) {
        let (id, layers) = tile?;
        cost += tile_cost(&layers);
        batch.push((id, layers));
        // Checked after pushing, so the batch is never empty and a heavy tile closes its own
        // batch -- the same shape as `partition_by_cost`'s grouping, for the same reason.
        if cost >= MERGE_BATCH_BYTES {
            break;
        }
    }
    Ok(batch)
}

/// Prune barren subtrees within one encoded batch: drop the four empty children of a
/// tile whose whole child set came back `None`.
///
/// A `None` child is a tile with nothing drawable — genuinely featureless, or full-bleed
/// water background (Phase B). The reader's miss path already overzooms: a missing tile
/// falls back to its parent, whose background (or whose own body) shows through. So four
/// empty siblings carry no information their parent does not, and storing them buys index
/// entries, leaf span and fetches for zero pixels.
///
/// The conditions are deliberately strict, because a wrong prune deletes drawable map:
///
/// * `z == 0` never prunes: the world tile has no parent to overzoom to.
/// * All four children of a parent must be present **in this batch** and all `None`.
///   A parent split across a batch boundary keeps its children — the archive must not
///   depend on where the cost-batcher cut, and a partial set cannot prove barrenness.
/// * Only `None` children prune. A stored child (even a tiny one) means drawable content.
/// * The parent itself is untouched: it was merged and encoded in its own zoom pass (zooms
///   tile independently), so pruning children never orphans a stored parent.
///
/// Recursion across zooms falls out of the zoom loop: a z13 tile whose z14 children were
/// all pruned is itself absent-or-water, so when z13 encodes, *its* parent sees four empty
/// children and prunes again — the tree ends where the content does.
///
/// Runs on the serial append thread over the encoded batch (not in the parallel encode
/// workers): it inspects batch adjacency, which only the ordered batch has. Pure function
/// of the batch's `(id, Option)` pairs, so thread count and chunk boundaries cannot move it.
fn prune_barren_subtree(z: u8, done: &mut Vec<Encoded>) {
    if z == 0 || done.len() < 4 {
        return;
    }
    // Index by tile id for sibling lookup. The batch is ascending by id (merge order
    // through `encode_batch`'s positional collect), so positions are found by search
    // rather than assumed adjacent: siblings are consecutive ids but not necessarily
    // consecutive positions (a stored neighbour may sit between them — which itself
    // vetoes the prune, correctly).
    let mut drop_ids: std::collections::BTreeSet<u64> = std::collections::BTreeSet::new();
    let mut at: std::collections::HashMap<u64, usize> = std::collections::HashMap::new();
    for (index, (id, _, _, _)) in done.iter().enumerate() {
        at.insert(*id, index);
    }
    for (index, (id, encoded, _, _)) in done.iter().enumerate() {
        let _ = index;
        if encoded.is_some() {
            continue;
        }
        let (tz, tx, ty) = tilecodec::pmtiles::tile_zxy(*id);
        if tz != z {
            continue;
        }
        // Only the top-left child starts the check, so each parent is considered once.
        if tx % 2 != 0 || ty % 2 != 0 {
            continue;
        }
        let (px, py) = (tx / 2, ty / 2);
        let mut siblings: Vec<u64> = Vec::with_capacity(4);
        for (dx, dy) in [(0u64, 0u64), (1, 0), (0, 1), (1, 1)] {
            siblings.push(tilecodec::pmtiles::tile_id(z, px * 2 + dx, py * 2 + dy));
        }
        // All four present in this batch and all empty: the parent covers them.
        let all_empty = siblings.iter().all(|sib| {
            at.get(sib).is_some_and(|&i| done[i].1.is_none())
        });
        if all_empty {
            for sib in siblings {
                drop_ids.insert(sib);
            }
        }
    }
    if !drop_ids.is_empty() {
        done.retain(|(id, _, _, _)| !drop_ids.contains(id));
    }
}

/// Cap on one merge pull's merged-geometry cost, in `tile_cost` units (features + coordinates).
///
/// 8 M units is ~64 MB of merged tile geometry per batch at typical z14 density -- small against
/// the box, large against the pool (still hundreds of tiles per batch on common zooms, so the
/// encode pass stays fed). The failure this bounds held ~GB: `batch_len()` tiles at 64 threads
/// is 4096 tiles, and a planet z14 batch of those carries dense-city tiles tens of MB each.
const MERGE_BATCH_BYTES: u64 = 8 << 20;

/// Which backend one zoom's tile-chunk spill stages in.
///
/// The chunk spill holds clipped copies -- one record per (feature, tile) pair -- so its size
/// scales with the store's feature count times the tiling fan-out, not with anything known
/// exactly up front. The estimate is `features x 512 B`: a clipped copy carries the feature's
/// packed body (tens of bytes) plus its share of parts/coords, and z14's fan-out dominates.
/// Over-estimating files a zoom that would have fit anon; under-estimating pages it. Files are
/// the safe side -- same chunks, removed on drop -- so the constant leans that way.
///
/// Unknown limits (or a small store under 1 M features): the historical anon path, no behaviour
/// change.
fn plan_chunk_spill(store: &Store, scratch: &std::path::Path) -> osm_ingest::mem::SpillPlan {
    const CHUNK_BYTES_PER_FEATURE: u64 = 512;
    const SMALL_STORE_FEATURES: u64 = 1_000_000;
    if store.len() < SMALL_STORE_FEATURES {
        return osm_ingest::mem::SpillPlan::Anon;
    }
    let mut budget = osm_ingest::mem::StageBudget::default();
    // Reuse the anon-commit field for the chunk estimate: it is the number `decide` compares
    // against the commit allowance, and the chunk spill is the only anon charge this phase adds
    // (the feature spill is already staged and counted separately).
    budget.ways_spill_bytes = store.len().saturating_mul(CHUNK_BYTES_PER_FEATURE);
    match osm_ingest::mem::SpillPlan::decide(&budget, scratch, "tiler chunk spill") {
        Ok(plan) => {
            if plan != osm_ingest::mem::SpillPlan::Anon {
                eprintln!(
                    "  [tiler] chunk spill -> {} (file backend: commit budget)",
                    scratch.display(),
                );
            }
            plan
        }
        Err(e) => {
            // A zoom that fits neither backend is not a hard failure here: later zooms are
            // smaller, and each zoom re-creates the spill. Warn and take files -- the operator
            // sees the message and the build still has a chance.
            eprintln!("WARN: {e}; continuing with file staging");
            osm_ingest::mem::SpillPlan::File
        }
    }
}

/// The map half of one zoom: every feature in the store, clipped into per-chunk tile maps and
/// spilled to `spill` as each is finished.
///
/// Returns the chunks **in read order**, which is the only order the reduce may use them in.
///
/// The reader gets a thread of its own rather than a task on the pool, and that is not a
/// preference. A producer that blocks on a full channel from inside the pool it feeds deadlocks when
/// the pool has one thread: the single worker *is* the producer, so nothing can drain what it is
/// waiting to write. Off the pool, one thread is merely slow — which matters, because one thread is
/// a configuration this has to stay byte-identical at.
fn map_zoom(
    store: &Store,
    settings: &Settings,
    z: u8,
    tolerance: f64,
    buffer: f64,
    spill: &ChunkSpill,
) -> Result<(Vec<ChunkRef>, Tally)> {
    // The full budget, **not** minus the reader's prefetch lanes. Subtracting them was tried, on the
    // reasoning that 64 workers plus 16 lanes plus a reader is 81 runnable threads on 64 CPUs. It
    // fixed us-west (151.9 s to 94.4 s) and cost north-america more than it saved (766.9 s to
    // 900.4 s), because the premise is only true when the lanes are actually running: on
    // north-america the workers are the ones blocked, waiting on a channel the reader cannot fill
    // fast enough, so the lanes were competing with nothing and the subtraction just removed a
    // quarter of the clipping. z14's map went 138.7 s to 170.0 s, almost exactly the ratio.
    //
    // Which of those two regimes a build is in is what `crate::store::PREFETCH_LANES` has to be set
    // for, and it is not knowable from the thread count.
    let workers = par::threads().max(1);
    // Bounded, because this is the one place the parallel tiler holds features the sequential one
    // did not: `2 * threads` chunks of about `chunk_vertices()` vertices, and no more however far
    // ahead of the workers the reader gets.
    let (send, receive) = std::sync::mpsc::sync_channel::<(usize, Vec<Vec<Feature>>)>(workers * 2);
    let receive = Mutex::new(receive);
    let done: Mutex<Vec<(usize, ChunkRef, Tally)>> = Mutex::new(Vec::new());
    let failed = std::sync::atomic::AtomicBool::new(false);

    std::thread::scope(|scope| -> Result<()> {
        // TEMP-PROFILE: reader wall (feed rate). The reader thread returns
        // its wall clock alongside its result; compared against the zoom's
        // map wall it answers feed-bound vs worker-bound. Removed with the
        // probes.
        let reader_start = std::time::Instant::now();
        let reader = std::thread::Builder::new()
            .name("mamaps-read".to_string())
            .spawn_scoped(scope, move || {
                let r = read_chunks(store, z, send);
                (r, reader_start.elapsed())
            })
            .map_err(|e| tile_build::proto::Error(format!("cannot start the store reader: {e}")))?;

        // One worker runs on this thread whatever happens, so a machine that will not give us
        // threads is slow rather than a build hung on a channel nobody is draining.
        let mut spawned = Vec::new();
        for i in 1..workers {
            let worker = std::thread::Builder::new()
                .name(format!("mamaps-tile-{i}"))
                .stack_size(WORKER_STACK)
                .spawn_scoped(scope, || {
                    tile_chunks(&receive, &done, &failed, spill, z, tolerance, buffer, &settings.country_zooms)
                });
            match worker {
                Ok(handle) => spawned.push(handle),
                Err(e) => {
                    eprintln!("WARN: cannot start tiling thread {i} ({e}); continuing without");
                    break;
                }
            }
        }
        tile_chunks(&receive, &done, &failed, spill, z, tolerance, buffer, &settings.country_zooms);
        for handle in spawned {
            handle.join().map_err(|_| {
                tile_build::proto::Error("a tiling thread panicked".to_string())
            })?;
        }
        let (reader_result, reader_wall) = reader.join().map_err(|_| {
            tile_build::proto::Error("the store reader thread panicked".to_string())
        })?;
        // TEMP-PROFILE: stash the reader wall where the tally fold prints
        // it. Removed with the probes.
        crate::tiler::stash_reader_wall(z, reader_wall);
        reader_result
    })?;

    if failed.load(Ordering::Relaxed) {
        // Refused rather than published. A skipped chunk is a handful of features missing from a
        // handful of tiles: no error on device, no visibly broken tile, just an archive that is
        // quietly not the one the report describes.
        return err(format!("a chunk of z{z} could not be tiled; see the message above"));
    }

    let mut chunks = done.into_inner().expect("the chunk list outlives its workers");
    // **The reduce order.** By the index a chunk was read at, never by the order it finished in, and
    // never by where in the scratch file it happened to land. This one line is what keeps the
    // archive independent of the thread count.
    chunks.sort_by_key(|(index, _, _)| *index);
    let mut tally = Tally::default();
    for (_, _, chunk_tally) in &chunks {
        tally.add(*chunk_tally);
    }
    // TEMP-PROFILE: per-stage map walls, summed across chunks (CPU time, so
    // the sum exceeds wall on 64 threads — ratios, not walls, are the read),
    // plus the reader thread's own wall (feed rate) and the map wall implied
    // by the caller. `reader_ms` vs the zoom's `map_ms` answers feed-bound
    // vs worker-bound: a reader wall near the map wall means workers starved.
    // Removed with the probes.
    eprintln!(
        "  [mapprofile] z{z}: project={:.1}s annotate={:.1}s filter={:.1}s subdiv={:.1}s reader={:.1}s",
        tally.profile.project.as_secs_f64(),
        tally.profile.annotate.as_secs_f64(),
        tally.profile.filter.as_secs_f64(),
        tally.profile.subdiv.as_secs_f64(),
        crate::tiler::take_reader_wall().as_secs_f64()
    );
    Ok((chunks.into_iter().map(|(_, at, _)| at).collect(), tally))
}

/// Cut the store into chunks of roughly [`chunk_vertices`] input vertices and send them on.
///
/// The cut is a function of the feature stream alone — not of the thread count, not of how fast the
/// workers drain — so two runs chunk identically even before the merge makes the boundaries
/// invisible.
///
/// A feature below the zoom's own floor never arrives: [`crate::store::ZoomReader`] drops it in one
/// of its prefetch lanes. That filter used to be right here, and here it was one thread discarding
/// most of a billion features — see the reader's own docs for what that cost.

#[cfg(test)]
mod tests_part1 {
    use super::*;

    fn empty_entry(id: u64) -> Encoded {
        (id, None, crate::rings::Stats::default(), crate::coalesce::Stats::default())
    }

    fn stored_entry(id: u64) -> Encoded {
        (
            id,
            Some((vec![7u8; 32], 32, tilecodec::mamaps::write::hash64(&[7u8; 32]))),
            crate::rings::Stats::default(),
            crate::coalesce::Stats::default(),
        )
    }

    fn ids_of(done: &[Encoded]) -> Vec<u64> {
        done.iter().map(|(id, _, _, _)| *id).collect()
    }

    /// **Phase B subtree pruning: four empty siblings end the tree at the parent.**
    /// All four children of one z12 parent arrive `None` in one batch: all four are
    /// dropped, so the reader stops at the z11 parent (overzoom) instead of fetching
    /// four empty bodies.
    #[test]
    fn four_empty_siblings_are_pruned_to_the_parent() {
        use tilecodec::pmtiles::tile_id;
        let (px, py) = (1_200u64, 1_500u64);
        let z = 12u8;
        let mut done = vec![
            empty_entry(tile_id(z, px * 2, py * 2)),
            empty_entry(tile_id(z, px * 2 + 1, py * 2)),
            empty_entry(tile_id(z, px * 2, py * 2 + 1)),
            empty_entry(tile_id(z, px * 2 + 1, py * 2 + 1)),
        ];
        prune_barren_subtree(z, &mut done);
        assert!(done.is_empty(), "all four empty siblings must go: {done:?}");
    }

    /// **A stored sibling vetoes the prune.** One child carries drawable content, so the
    /// parent cannot cover the set — dropping the other three would delete the map's
    /// answer for their ground (the reader would overzoom a parent that draws coarsely).
    #[test]
    fn a_stored_sibling_vetoes_the_prune() {
        use tilecodec::pmtiles::tile_id;
        let (px, py) = (1_200u64, 1_500u64);
        let z = 12u8;
        let mut done = vec![
            empty_entry(tile_id(z, px * 2, py * 2)),
            stored_entry(tile_id(z, px * 2 + 1, py * 2)),
            empty_entry(tile_id(z, px * 2, py * 2 + 1)),
            empty_entry(tile_id(z, px * 2 + 1, py * 2 + 1)),
        ];
        prune_barren_subtree(z, &mut done);
        assert_eq!(done.len(), 4, "a stored child keeps the whole set");
    }

    /// **A partial set never prunes.** The parent split across a batch boundary keeps its
    /// children: the archive must not depend on where the cost-batcher cut, and three
    /// empties cannot prove the fourth is barren.
    #[test]
    fn a_partial_child_set_never_prunes() {
        use tilecodec::pmtiles::tile_id;
        let (px, py) = (1_200u64, 1_500u64);
        let z = 12u8;
        let mut done = vec![
            empty_entry(tile_id(z, px * 2, py * 2)),
            empty_entry(tile_id(z, px * 2 + 1, py * 2)),
            empty_entry(tile_id(z, px * 2, py * 2 + 1)),
        ];
        prune_barren_subtree(z, &mut done);
        assert_eq!(done.len(), 3, "a split parent keeps its children");
    }

    /// **z0 never prunes.** The world tile has no parent to overzoom to; dropping it
    /// would delete the map's coarsest answer.
    #[test]
    fn the_world_tile_is_never_pruned() {
        use tilecodec::pmtiles::tile_id;
        let mut done = vec![
            empty_entry(tile_id(0, 0, 0)),
            empty_entry(tile_id(0, 1, 0)),
            empty_entry(tile_id(0, 0, 1)),
            empty_entry(tile_id(0, 1, 1)),
        ];
        let before = ids_of(&done);
        prune_barren_subtree(0, &mut done);
        assert_eq!(ids_of(&done), before, "z0 keeps everything");
    }

    /// **Unrelated tiles are untouched.** A batch of four empties from different parents
    /// prunes nothing — the check is per-parent, not per-batch.
    #[test]
    fn empties_from_different_parents_do_not_prune() {
        use tilecodec::pmtiles::tile_id;
        let z = 12u8;
        let mut done = vec![
            empty_entry(tile_id(z, 0, 0)),
            empty_entry(tile_id(z, 100, 100)),
            empty_entry(tile_id(z, 200, 200)),
            empty_entry(tile_id(z, 300, 300)),
        ];
        prune_barren_subtree(z, &mut done);
        assert_eq!(done.len(), 4, "no parent has its full set here");
    }
}
