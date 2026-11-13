/// Decode threads for [`read_chunks`]: `MAPS_PREFETCH_LANES` when set, else a share of the box.
///
/// The old reader decoded through one `ZoomReader` (4 lanes) behind three more partitions it could
/// not consume until the first was done, and moved every feature through one thread on the way to
/// the workers; on a 192-thread box the clipping workers spent most of each small zoom blocked on
/// it (California: ~190 of 192 workers parked on the chunk channel while the reader ran; the
/// Nov 10 planet build's map phases ran at 3-5 cores with the reader's wall equal to the map's).
/// Half the threads, at least 4 and at most 96: on the planet decode is most of a zoom's map (the
/// Nov 10 rerun's z14 map clipped at ~28 of 192 cores with 64 decoders), and on California,
/// where it is not, 32/64/96 decoders measured within noise of each other.
fn decode_threads() -> usize {
    match std::env::var("MAPS_PREFETCH_LANES").ok().and_then(|v| v.trim().parse::<usize>().ok()) {
        Some(n) if n > 0 => n,
        _ => (par::threads() / 2).clamp(4, 96),
    }
}

/// Spill chunks (64 features each) per decode claim in [`read_chunks`].
const DECODE_UNIT: usize = 16;

/// Decoded blocks a decode thread may run ahead of the sequencer, per decode thread.
const DECODE_AHEAD_PER_THREAD: usize = 8;

/// One decoded spill chunk: the zoom's features from it and each one's vertex count.
type DecodedBlock = osm_ingest::proto::Result<(Vec<Feature>, Vec<usize>)>;

/// One slot of [`read_chunks`]'s reorder ring. Wanted-chunk `k` lives in slot `k % ring`; the slot
/// expects `k` once the sequencer has taken `k - ring` out of it. A slot of its own per block --
/// rather than one map behind one lock and two condvars -- because the shared version woke every
/// parked decoder on every block taken and the sequencer on every block deposited, and that herd
/// cost more than the decode it organised.
struct RingSlot {
    /// The wanted-chunk index this slot holds or waits for.
    expect: usize,
    block: Option<DecodedBlock>,
}

fn read_chunks(
    store: &Store,
    z: u8,
    send: std::sync::mpsc::SyncSender<(usize, Vec<Vec<Feature>>)>,
) -> Result<()> {
    // Cut the zoom's feature stream into chunks of roughly [`chunk_vertices`] input vertices and
    // send them on, in order. The stream is the wanted spill chunks in file order, each filtered to
    // the zoom's window -- exactly what `Store::reader_for_zoom` yields -- and the cut is the same
    // running vertex count, so the chunks (and their indices) are what they always were.
    //
    // Decode runs on dedicated threads (not the pool, which the clipping workers' own pool hand-offs
    // use -- see `ZoomReader::spawn` for what queuing decode behind clipping cost), each claiming
    // the next wanted chunk, so decode scales with the box instead of with four lanes. The
    // sequencer (this thread) takes blocks back in wanted order and never touches a feature: it
    // adds up the decoders' vertex counts and moves whole blocks, splitting one only where a cut
    // falls inside it. A tiler chunk is therefore a list of blocks, walked in order by
    // `tile_chunk`.
    let wanted = store.wanted_chunks_for_zoom(z);
    let total = wanted.len();
    if total == 0 {
        return Ok(());
    }
    let chunks = store.zoom_chunks().map_err(|e| tile_build::proto::Error(e.to_string()))?;
    // Decoded in units of `DECODE_UNIT` consecutive spill chunks: one claim, one ring hand-off
    // and one sequencer wake per unit rather than per 64-feature chunk. The planet's z14 is 21 M
    // spill chunks, and per-chunk hand-offs (a lock and a futex wake each side) put the
    // sequencer near saturation while ~28 of 192 cores clipped.
    let units = total.div_ceil(DECODE_UNIT);
    let decoders = decode_threads().min(units).max(1);
    let ring = decoders * DECODE_AHEAD_PER_THREAD;
    let claim = AtomicUsize::new(0);
    let stop = std::sync::atomic::AtomicBool::new(false);
    let slots: Vec<(Mutex<RingSlot>, std::sync::Condvar)> = (0..ring)
        .map(|i| (Mutex::new(RingSlot { expect: i, block: None }), std::sync::Condvar::new()))
        .collect();
    let want = chunk_vertices();

    std::thread::scope(|scope| -> Result<()> {
        for lane in 0..decoders {
            let (chunks, wanted, claim, stop, slots) = (&chunks, &wanted, &claim, &stop, &slots);
            std::thread::Builder::new()
                .name(format!("mamaps-decode-{lane}"))
                .spawn_scoped(scope, move || {
                    let mut scratch = Vec::new();
                    let mut records = Vec::new();
                    loop {
                        let k = claim.fetch_add(1, Ordering::Relaxed);
                        if k >= units || stop.load(Ordering::Relaxed) {
                            return;
                        }
                        // The unit's chunks decoded in order and appended: the same features
                        // and counts, in the same order, as decoding them one by one.
                        let lo = k * DECODE_UNIT;
                        let hi = (lo + DECODE_UNIT).min(wanted.len());
                        let mut decoded: osm_ingest::proto::Result<(Vec<Feature>, Vec<usize>)> =
                            Ok((Vec::new(), Vec::new()));
                        for &chunk in &wanted[lo..hi] {
                            match chunks.decode(chunk, z, &mut scratch, &mut records) {
                                Ok((f, c)) => {
                                    let acc = decoded.as_mut().expect("still Ok");
                                    acc.0.extend(f);
                                    acc.1.extend(c);
                                }
                                Err(e) => {
                                    decoded = Err(e);
                                    break;
                                }
                            }
                        }
                        let failed = decoded.is_err();
                        // Park until the sequencer has emptied this slot of `k - ring`. The thread
                        // holding the lowest outstanding claim always finds its slot free, so the
                        // ring cannot deadlock.
                        let (lock, cv) = &slots[k % ring];
                        let mut slot = lock.lock().expect("the map reader's ring");
                        while slot.expect != k && !stop.load(Ordering::Relaxed) {
                            slot = cv.wait(slot).expect("the map reader's ring");
                        }
                        if stop.load(Ordering::Relaxed) {
                            return;
                        }
                        slot.block = Some(decoded);
                        drop(slot);
                        cv.notify_all();
                        if failed {
                            return;
                        }
                    }
                })
                .map_err(|e| tile_build::proto::Error(format!("cannot start decode thread {lane}: {e}")))?;
        }

        let mut bar = Progress::new(format!("Map z{z}"), total, "chunk(s)", true);
        let mut current: Vec<Vec<Feature>> = Vec::new();
        let mut vertices = 0usize;
        let mut index = 0usize;
        let mut outcome: Result<()> = Ok(());
        'blocks: for k in 0..units {
            let decoded = {
                let (lock, cv) = &slots[k % ring];
                let mut slot = lock.lock().expect("the map reader's ring");
                let block = loop {
                    if let Some(block) = slot.block.take() {
                        break block;
                    }
                    slot = cv.wait(slot).expect("the map reader's ring");
                };
                // Free the slot for `k + ring`, whose decoder may already be parked on it.
                slot.expect = k + ring;
                drop(slot);
                cv.notify_all();
                block
            };
            bar.tick_n((total - k * DECODE_UNIT).min(DECODE_UNIT), "chunk(s)");
            let (mut features, counts) = match decoded {
                Ok(block) => block,
                Err(e) => {
                    outcome = Err(tile_build::proto::Error(e.to_string()));
                    break 'blocks;
                }
            };
            // `taken`: how many of this block's features already went out with an earlier cut.
            let mut taken = 0usize;
            for (i, &n) in counts.iter().enumerate() {
                vertices += n;
                if vertices >= want {
                    let tail = features.split_off(i + 1 - taken);
                    current.push(std::mem::replace(&mut features, tail));
                    if send.send((index, std::mem::take(&mut current))).is_err() {
                        // The workers are gone (a failure they already recorded): stop reading.
                        break 'blocks;
                    }
                    index += 1;
                    vertices = 0;
                    taken = i + 1;
                }
            }
            if !features.is_empty() {
                current.push(features);
            }
        }
        // Release any decoder parked on a slot, whatever ended the loop.
        stop.store(true, Ordering::Relaxed);
        for (lock, cv) in &slots {
            drop(lock.lock().expect("the map reader's ring"));
            cv.notify_all();
        }
        if outcome.is_ok() && !current.is_empty() {
            let _ = send.send((index, current));
        }
        bar.finish("chunk(s)");
        outcome
    })
}

/// Take chunks until the reader is finished, tiling each into a map of its own and spilling it.
///
/// A panic inside one chunk is **caught and recorded** rather than allowed to unwind, and the loop
/// keeps draining. That is not defensiveness about a bug that should not happen, it is about the
/// shape of the failure: a worker that dies with a bounded channel full behind it leaves the reader
/// blocked on a send nobody will ever take, and at one worker there is nobody else to drain it. So
/// the bug's symptom would be a 64-core box sitting at 0% forever in the middle of a five-minute
/// stage, instead of an error naming the zoom. `map_zoom` fails the build on the flag.
///
/// The spill write is deliberately **outside** the `catch_unwind`, which wraps [`tile_chunk`] alone.
/// A failed write records the flag and keeps draining for exactly the same reason a panic does.
fn tile_chunks(
    receive: &Mutex<std::sync::mpsc::Receiver<(usize, Vec<Vec<Feature>>)>>,
    done: &Mutex<Vec<(usize, ChunkRef, Tally)>>,
    failed: &std::sync::atomic::AtomicBool,
    spill: &ChunkSpill,
    z: u8,
    tolerance: f64,
    buffer: f64,
    country_zooms: &std::collections::HashMap<u64, u8>,
) {
    loop {
        // Held to take a chunk and never while tiling one: the lock covers a pointer move, and the
        // work either side of it is milliseconds.
        let next = receive.lock().expect("the chunk queue").recv();
        let Ok((index, features)) = next else { return };
        // `AssertUnwindSafe` because everything the closure touches is a local: the chunk map it
        // builds is thrown away on a panic.
        let tiled = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            tile_chunk(&features, z, tolerance, buffer, country_zooms)
        }));
        match tiled {
            Ok((chunk, tally)) => match spill.write_chunk(chunk) {
                Ok(at) => done.lock().expect("the chunk list").push((index, at, tally)),
                Err(e) => {
                    eprintln!("ERROR: cannot spill chunk {index} of z{z}: {e}");
                    failed.store(true, Ordering::Relaxed);
                }
            },
            Err(_) => {
                eprintln!("ERROR: chunk {index} of z{z} panicked while being tiled");
                failed.store(true, Ordering::Relaxed);
            }
        }
    }
}

/// Project, simplify and clip one chunk of features into a tile map of its own.
///
/// Verbatim the loop this module used to run on the main thread over the whole store, with the
/// destination private to the caller. Nothing here reads shared state, and that is why the chunk
/// boundaries cannot change a result: a feature's fate depends on the feature, the zoom and the
/// tolerance, and on nothing else in the chunk it happens to be in.
///
/// The tile loop is a quadtree descent ([`subdivide::subdivide`]), not a pass over every tile the
/// feature touches. A boundary relation spanning three states used to be clipped in full against
/// each of its hundreds of thousands of z13 tiles; now it is clipped once per zoom level. Filtering
/// stays where it was, **before** the descent: significance is measured on the whole geometry, so a
/// vertex's fate must not depend on which tile it lands in.
///
/// `country_zooms` (place id → label start zoom, probe-only) drops a `country` label
/// below its start zoom before any geometry work: the label is a point, so nothing
/// downstream would remove it, and carrying it would defeat the gating.
/// Whether the z14 wash cap is active for this build.
///
/// Unconditional: the renderer overzooms wash from z13 (shipped together with
/// this gate), so gated archives are always safe to publish. (Previously
/// opt-in via `MAMAPS_WASH_CAP=1` during the renderer-first rollout; the flag
/// is retired now that both halves ship.)
///
/// `pub(crate)` so tests asserting flag-dependent behavior (e.g. the rings
/// lake test) can branch rather than baking one flag state in.
pub(crate) fn wash_cap_enabled() -> bool {
    true
}

/// Whether a feature is wash dropped at this zoom: z14 landtype areas whose
/// class is carried at shallower zooms (`min_zoom != max`) are redundant with
/// the z13 parent tile, which the renderer overzooms. Detail areas
/// (`min_zoom == 14`: pools, pitches, playgrounds — kinds absent at z13 so
/// overzoom cannot recover them) and all lines (cheap, high-salience relief
/// and hydrography cues) survive.
///
/// Pure function of (kind, zoom, geometry-type): no geometry work, no shared
/// state, so chunk/thread boundaries cannot change the answer — the property
/// `tile_chunk`'s doc comment defends.
fn is_wash_dropped(feature: &Feature, z: u8) -> bool {
    wash_cap_enabled() && wash_drops_kind(feature.class.layer, feature.class.area, feature.class.min_zoom, z)
}

/// The wash-cap predicate without the env gate, so unit tests can pin the
/// rule without touching process-global state (the `OnceLock` in
/// `wash_cap_enabled` is set once per test process).
fn wash_drops_kind(layer: u8, area: bool, min_zoom: u8, z: u8) -> bool {
    use tilecodec::mamaps::dict;
    z == crate::DEFAULT_MAX_ZOOM
        && layer == dict::LAYER_LANDTYPE
        && area
        && min_zoom != crate::DEFAULT_MAX_ZOOM
}

/// Vertices at or above which one feature's quadtree descent runs on the pool
/// ([`subdivide::subdivide_par`]). Below it a feature tiles in well under a millisecond and the
/// hand-off would cost more than it saves.
const PAR_SUBDIVIDE_MIN_VERTICES: usize = 20_000;

fn tile_chunk(
    features: &[Vec<Feature>],
    z: u8,
    tolerance: f64,
    buffer: f64,
    country_zooms: &std::collections::HashMap<u64, u8>,
) -> (Chunk, Tally) {
    let mut tiles: Chunk = BTreeMap::new();
    let mut tally = Tally::default();
    // TEMP-PROFILE: per-stage walls inside the map phase. Thread-local (each
    // worker runs its own `tile_chunk`), summed into the process log at
    // `map_zoom` end. Removed once the hotspot is measured (CA run).
    let mut t_project = std::time::Duration::ZERO;
    let mut t_annotate = std::time::Duration::ZERO;
    let mut t_filter = std::time::Duration::ZERO;
    let mut t_subdiv = std::time::Duration::ZERO;
    // A chunk is the reader's blocks in stream order (see `read_chunks`); flattened, it is the
    // feature run the chunk always was.
    for feature in features.iter().flatten() {
        // Country label gating: the score (footprint × headcount) decided in stage A
        // (see `extract_fallback::country_zoom`); this is where it is enforced. A
        // missing score keeps the schema floor — a missing map entry must never hide
        // a country. Probe-only (`get`), never iterated, so the archive stays
        // deterministic; counted as dropped so the report stays honest.
        if feature.class.layer == tilecodec::mamaps::dict::LAYER_PLACES {
            if let Some(&start) = country_zooms.get(&feature.id) {
                if z < start {
                    tally.dropped += 1;
                    continue;
                }
            }
        }
        // z14 wash cap: wash areas are redundant with the z13 parent (see
        // `is_wash_dropped`). Before any geometry work, like the gate above.
        if is_wash_dropped(feature, z) {
            tally.dropped += 1;
            continue;
        }
        let t = std::time::Instant::now();
        let mut projected = geom::project_geometry(&feature.geometry, z, EXTENT);
        t_project += t.elapsed();
        // Significance first, then filter: computed on the whole geometry so a vertex's fate
        // does not depend on which tile it lands in.
        let t = std::time::Instant::now();
        simplify::annotate(&mut projected);
        t_annotate += t.elapsed();
        // Buildings live only at z14, where the policy tolerance is 0.0; the floor below
        // keeps only collinear midpoints and sub-pixel jaggies — see `tolerance_for_layer`.
        // Roads take the per-feature floor at z14 (washable classes only).
        let layer_tolerance = tolerance_for_feature(feature, z, tolerance);
        let t = std::time::Instant::now();
        let thinned = simplify::filter(&projected, layer_tolerance);
        t_filter += t.elapsed();
        // Near-rectangles digitised by hand gain a cheap varint delta when their edges snap
        // exactly axis-aligned — see `snap_building_rings`. Survivors only, so the measured
        // significance above is never stale; a pure function of the feature, so the archive
        // stays independent of thread count and chunk boundaries. Gated to the one layer that
        // pays for it, so every other layer's filtered geometry moves without another copy.
        let thinned = if feature.class.layer == tilecodec::mamaps::dict::LAYER_BUILDINGS {
            snap_building_rings(&thinned, feature.class.layer)
        } else {
            thinned
        };
        if is_empty(&thinned) {
            tally.dropped += 1;
            continue;
        }
        let t = std::time::Instant::now();
        let mut emit = |tx: u64, ty: u64, clipped: &Geometry<SigPt>| {
            // The descent prunes on a part surviving at all; this is the stricter test that
            // decides whether a tile is worth writing, and it stays exactly where it was.
            if is_empty(clipped) {
                return;
            }
            let local = geom::to_tile(clipped, tx, ty, EXTENT);
            let q = size_opts().quant_step(z, feature.class.layer);
            let local = if q > 1 { snap_grid(local, q) } else { local };
            if is_empty_int(&local) {
                return;
            }
            let layer = tiles
                .entry((tile_id(z, tx, ty), feature.class.layer))
                .or_insert_with(|| ChunkEntry::new(feature.class.layer));
            let added = push(layer, feature, &local);
            tally.features += added.0;
            tally.points += added.1;
        };
        // A huge line or polygon (a state boundary, a national forest, a long coastline run)
        // descends on the pool: alone it held z12-z14's map phase on one thread for seconds
        // after every other chunk was done. `subdivide_par` returns the tiles in exactly the
        // order `subdivide` emits them, so replaying them through the same `emit` is the same
        // sequence of pushes.
        if !matches!(thinned, Geometry::Points(_))
            && subdivide::vertex_count(&thinned) >= PAR_SUBDIVIDE_MIN_VERTICES
        {
            for (tx, ty, clipped) in subdivide::subdivide_par(&thinned, z, EXTENT, buffer) {
                emit(tx, ty, &clipped);
            }
        } else {
            subdivide::subdivide(&thinned, z, EXTENT, buffer, &mut emit);
        }
        t_subdiv += t.elapsed();
    }
    // TEMP-PROFILE: summed per worker; see `map_zoom` for the fold.
    // Removed with the probes.
    tally.profile_add(t_project, t_annotate, t_filter, t_subdiv);
    (tiles, tally)
}

/// The tolerance to simplify a layer at: the per-zoom policy, with a floor for buildings
/// and a multiplier for the boundary wash.
///
/// Buildings live only at z14 and up, where [`simplify::tolerance_for`] returns 0.0 — full
/// detail. Half an extent unit there is ~0.3 m on the ground, sub-pixel on any screen the style
/// draws at, and only drops what is invisible anyway: exactly-collinear midpoints, whose
/// significance is 0 and which any non-zero threshold already removes, plus sub-pixel jaggies.
/// Every other layer keeps the policy tolerance untouched. A coarser global tolerance still wins
/// (`max`, not replace), and a zero/negative policy for a non-building layer is unchanged.
///
/// `boundaries` alone keeps the [`WASH_SIMPLIFICATION`] multiplier: a low-zoom line backdrop.
/// `landtype` simplifies at 1.5x: natural boundaries (forest edges, shorelines,
/// farmland) are washes, not navigation geometry — same reasoning as
/// boundaries, smaller factor because parks and lakes are looked at closer.
/// Roads and transit curves take the wash below z14: at z13 a 1.5x tolerance
/// drops sub-10m wiggles (a 2x grid stretch could straighten them) while z14
/// stays at policy 0.0 — full detail where carriageway/lane alignment lives.
/// Buildings and the point layers keep 1.0 — navigation geometry and
/// labels, not a wash.
///
/// The prototype at `analysis/mamaps_building_savings.py` estimates this plus the snap below at
/// ~2% + ~4% of building-tile bytes; buildings are z14-only, 9.76M features, ~20% of the archive.
fn tolerance_for_layer(layer: u8, z: u8, tolerance: f64) -> f64 {
    use tilecodec::mamaps::dict;
    if layer == dict::LAYER_BUILDINGS
        && z >= crate::schema::buildings::MIN_ZOOM
    {
        tolerance.max(BUILDING_TOLERANCE)
    } else if layer == dict::LAYER_BOUNDARIES {
        tolerance * WASH_SIMPLIFICATION
    } else if layer == dict::LAYER_LANDTYPE {
        // z13 tiles span ~5 km: triple the wash there, keep 1.5x at z14
        // where parks sit under streets.
        if z == 13 {
            tolerance * LANDTYPE_Z13_SIMPLIFICATION
        } else {
            tolerance * LANDTYPE_SIMPLIFICATION
        }
    } else if z < crate::DEFAULT_MAX_ZOOM
        && (layer == dict::LAYER_ROADS || layer == dict::LAYER_TRANSIT)
    {
        // Below the archive max the policy tolerance is non-zero, so the wash
        // bites; at z14 policy is 0.0 and 1.5x of zero is zero — crisp.
        tolerance * WASH_SIMPLIFICATION
    } else {
        tolerance
    }
}

/// The tolerance for one feature: the layer policy, plus the z14 roads
/// floor. Washable roads (not arterial, no lanes/split — see `needs_crisp`)
/// take [`ROAD_DETAIL_FLOOR`] at the archive max, where the layer policy is
/// 0.0; everything else keeps the layer value untouched.
fn tolerance_for_feature(feature: &crate::extract::Feature, z: u8, tolerance: f64) -> f64 {
    use tilecodec::mamaps::dict;
    let base = tolerance_for_layer(feature.class.layer, z, tolerance);
    let base = if z == crate::DEFAULT_MAX_ZOOM
        && feature.class.layer == dict::LAYER_ROADS
        && !needs_crisp(feature)
    {
        base.max(ROAD_DETAIL_FLOOR)
    } else {
        base
    };
    // `--z14-tolerance`: a z14 floor for the washable layers (crisp roads keep full detail).
    let floor = size_opts().z14_tolerance;
    if floor > 0.0 && z == crate::DEFAULT_MAX_ZOOM {
        let l = feature.class.layer;
        let washable = l == dict::LAYER_LANDTYPE
            || l == dict::LAYER_BOUNDARIES
            || l == dict::LAYER_BUILDINGS
            || (l == dict::LAYER_ROADS && !needs_crisp(feature));
        if washable {
            return base.max(floor);
        }
    }
    base
}

/// Snap a building geometry's near-axis-aligned edges to exactly axis-aligned.
///
/// Hand-digitised footprints are near-rectangles: edges within [`BUILDING_SNAP`] of horizontal
/// or vertical snap flat, turning an 8-delta wobble into a repeated constant the varint arena
/// encodes cheaply. Runs on `SigPt` survivors **after** [`simplify::filter`], via
/// [`Vertex::moved`] so significance rides along untouched — nothing downstream re-measures.
/// Only ever called for the buildings layer (the caller gates it); strictly per-edge — never
/// a corner merge, never a vertex invented or removed — so winding, closure and hole
/// containment are exactly what the filter left.
///
/// Deterministic by construction: a pure function of the ring's coordinates, so two runs tile
/// identically however features are chunked or threaded — the property
/// `the_archive_is_identical_however_the_features_are_chunked` defends.
fn snap_building_rings(geometry: &Geometry<SigPt>, layer: u8) -> Geometry<SigPt> {
    debug_assert_eq!(layer, tilecodec::mamaps::dict::LAYER_BUILDINGS);
    match geometry {
        Geometry::Points(_) | Geometry::Lines(_) => geometry.clone(),
        Geometry::Polygons(polygons) => Geometry::Polygons(
            polygons
                .iter()
                .map(|rings| rings.iter().map(|ring| snap_building_ring(ring)).collect())
                .collect(),
        ),
    }
}

/// Snap one ring's near-axis-aligned edges flat, preserving length, order, closure and winding.
fn snap_building_ring(ring: &[SigPt]) -> Vec<SigPt> {
    if ring.len() < 2 {
        return ring.to_vec();
    }
    // The explicit close duplicates the first vertex; snapping the closing edge in the same
    // pass as the rest would compare the duplicate against its twin and average the corner
    // away. Snap the distinct vertices, then re-close with the surviving first.
    let closed = ring.len() > 1 && ring.first().map(|v| v.xy()) == ring.last().map(|v| v.xy());
    let open_len = ring.len() - usize::from(closed);
    if open_len < 2 {
        return ring.to_vec();
    }
    let open = &ring[..open_len];
    let mut snapped: Vec<(f64, f64)> = open.iter().map(|v| v.xy()).collect();
    // Forward pass: each edge votes its dominant direction; a near-horizontal edge levels both
    // endpoints to their mean y, a near-vertical one to their mean x. Means, not first-wins, so
    // a shared corner snapped from either side lands identically — the seam property the
    // annotate-then-filter design exists for.
    for i in 0..open_len {
        let j = (i + 1) % open_len;
        let ((x0, y0), (x1, y1)) = (snapped[i], snapped[j]);
        let (dx, dy) = ((x1 - x0).abs(), (y1 - y0).abs());
        if dx <= BUILDING_SNAP && dy > BUILDING_SNAP {
            // Near-vertical: level x.
            let x = (x0 + x1) / 2.0;
            snapped[i].0 = x;
            snapped[j].0 = x;
        } else if dy <= BUILDING_SNAP && dx > BUILDING_SNAP {
            // Near-horizontal: level y.
            let y = (y0 + y1) / 2.0;
            snapped[i].1 = y;
            snapped[j].1 = y;
        }
    }
    let mut out: Vec<SigPt> = open
        .iter()
        .zip(snapped)
        .map(|(v, (x, y))| {
            // Round onto the building grid: hand jitter (±1 unit) collapses
            // into repeated constants. Runs here — after the filter, on
            // survivors only — so measured significance is never stale.
            let gx = (x / crate::tiler::BUILDING_GRID).round() * crate::tiler::BUILDING_GRID;
            let gy = (y / crate::tiler::BUILDING_GRID).round() * crate::tiler::BUILDING_GRID;
            v.moved((gx, gy))
        })
        .collect();
    if closed {
        out.push(out[0]);
    }
    out
}


/// `--coord-quant`: snap tile-local coordinates to a `q`-unit grid, dropping consecutive
/// duplicates. Max displacement per vertex is `q/2` per axis (`q/sqrt(2)` units). A line that
/// collapses below 2 vertices or a ring below 4 is dropped (a ring that was the exterior takes
/// its polygon with it) — the same floors `simplify::filter` applies. Points are untouched.
/// Rings are re-normalised by stage C afterwards, so `rings_validated` stays true.
fn snap_grid(g: tile_build::geom::IntGeometry, q: i32) -> tile_build::geom::IntGeometry {
    use tile_build::geom::IntGeometry;
    let s = |v: i32| (v + q / 2).div_euclid(q) * q;
    let snap = |pts: &[(i32, i32)]| -> Vec<(i32, i32)> {
        let mut out: Vec<(i32, i32)> = Vec::with_capacity(pts.len());
        for &(x, y) in pts {
            let p = (s(x), s(y));
            if out.last() != Some(&p) {
                out.push(p);
            }
        }
        out
    };
    match g {
        IntGeometry::Points(p) => IntGeometry::Points(p),
        IntGeometry::Lines(lines) => {
            IntGeometry::Lines(lines.iter().map(|l| snap(l)).filter(|l| l.len() >= 2).collect())
        }
        IntGeometry::Polygons(polys) => IntGeometry::Polygons(
            polys
                .iter()
                .filter_map(|rings| {
                    let mut out = Vec::with_capacity(rings.len());
                    for (i, r) in rings.iter().enumerate() {
                        let sr = snap(r);
                        // Distinct corners: a closed ring repeats its first vertex.
                        let closed = sr.len() > 1 && sr.first() == sr.last();
                        let distinct = sr.len() - usize::from(closed);
                        if distinct >= 3 {
                            out.push(sr);
                        } else if i == 0 {
                            return None;
                        }
                    }
                    (!out.is_empty()).then_some(out)
                })
                .collect(),
        ),
    }
}

fn is_empty_int(g: &tile_build::geom::IntGeometry) -> bool {
    use tile_build::geom::IntGeometry;
    match g {
        IntGeometry::Points(p) => p.is_empty(),
        IntGeometry::Lines(l) => l.is_empty(),
        IntGeometry::Polygons(p) => p.is_empty(),
    }
}
