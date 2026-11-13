//! Tile workers: one archive view each, serving requests until the queue closes.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use super::handle::{OnlineFlag, TileResult, ZoomRange};
use super::log::{log, log_info};
use crate::style::{self, SharedToggles};
use crate::tile::cache::{RangeCache, DEFAULT_MAX_BYTES};
use crate::tile::geometry;
use crate::tile::select::TileId;
use crate::tile::source::BASEMAP_ARCHIVE_URL;
use crate::tile::source::{
    basemap_origin, discover, CachingRangeReader, FileRangeReader, JniRangeFetcher,
};
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};
use tilecodec::mamaps::header::Header;
use tilecodec::mamaps::read::LeafCache;
use tilecodec::mamaps::MamapsArchive;
use tilecodec::stream::RangeReader;
use tilecodec::stream::OPEN_PREFIX_BYTES;
/// A worker: opens its own view of the archive, then serves tile requests until the queue
/// closes.
///
/// Each worker holds its **own** [`MamapsArchive`] view (and its own prefix parse — small CPU
/// work on an in-memory prefix), so its range reads need no lock, but every view of one
/// surface reads parsed leaves through the **shared** [`LeafCache`]: a cold leaf is fetched
/// once no matter how many workers' tiles need it. What is shared across the surface's
/// workers is everything that costs a *network round trip*: the header `build_id` fetch (one
/// JNI request per cold start, not one per worker, via `header`) and the forced first-read
/// revalidation (one prefix read, not one per worker, via `prefix_gate`). The on-disk range
/// cache is shared too, and is safe to share because every entry is written temp-then-renamed.
///
/// header`/`prefix_gate` are built once per surface in `lifecycle::create` and handed to every
/// worker: whichever worker arrives first does the fetch while the rest block on it. `leaves`
/// is the surface's shared parsed-leaf cache (issue 815): every worker's archive reads through
/// it, so a cold leaf is fetched once no matter how many workers' tiles need it — previously
/// each worker carried a private cache and one viewport's leaves were fetched up to once per
/// worker.
#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_worker(
    index: usize,
    archive_url: String,
    cache_dir: String,
    local_path: Option<String>,
    queue: Arc<Mutex<Receiver<TileId>>>,
    finished: Sender<(u64, TileResult)>,
    online: Arc<OnlineFlag>,
    zoom_range: Arc<ZoomRange>,
    toggles: Arc<SharedToggles>,
    header: Arc<std::sync::OnceLock<Option<u64>>>,
    prefix_gate: Arc<std::sync::atomic::AtomicBool>,
    leaves: Arc<LeafCache>,
) {
    let started = std::thread::Builder::new()
        .name(format!("map-tiles-{index}"))
        .spawn(move || {
            super::priority::lower_worker_priority();
            // A pushed local archive wins: when the file is present the reader opens it
            // straight from disk and skips the header fetch, the origin marker and the
            // range cache entirely — the file is the whole archive, so none of the
            // republished-under-a-stable-name machinery applies.
            if let Some(path) = local_path.as_deref() {
                if FileRangeReader::exists(path) {
                    // A pinned overlay (segments + tombstones beside the base) serves
                    // patch-first; any gap falls back to the base alone, silently.
                    if let Some(found) = discover(std::path::Path::new(path), Arc::clone(&leaves)) {
                        log(&format!(
                            "worker {index} serving base plus overlay seq {}",
                            found.seq,
                        ));
                        serve(index, found.overlay, &queue, &finished, &zoom_range, &toggles);
                        return;
                    }
                    let reader = match FileRangeReader::open(path) {
                        Ok(r) => r,
                        Err(e) => {
                            log(&format!(
                                "worker {index} cannot open the local mamaps archive {path}: {e}"
                            ));
                            return;
                        }
                    };
                    match MamapsArchive::open_with_cache(reader, Arc::clone(&leaves)) {
                        Ok(archive) => {
                            serve(index, archive, &queue, &finished, &zoom_range, &toggles)
                        }
                        Err(e) => log(&format!(
                            "worker {index} cannot open the local mamaps archive {path}: {e}"
                        )),
                    }
                    return;
                }
            }

            // Single open: the header prefix is fetched *once per surface* (the first worker
            // fetches, the rest block on `header`), then the `build_id` is read out of it and the
            // cache opens once per worker with the full origin marker — wiping on mismatch up
            // front. No two-step reset.
            //
            // Retried with backoff, not once: a single failed fetch used to poison the lock for
            // every worker, so one transient network blip at startup killed the surface's workers
            // and stuck every in-flight tile for the session. A header that keeps failing past
            // `HEADER_RETRIES` still kills them once instead of logging four times — but only
            // after the network has had its chance to come back.
            let build_id = *header.get_or_init(|| fetch_header_with_retry(&archive_url));
            let Some(build_id) = build_id else { return };
            let cache = RangeCache::open(
                cache_dir,
                &basemap_origin(&archive_url, build_id),
                DEFAULT_MAX_BYTES,
            );
            let reader = CachingRangeReader::new_shared(
                archive_url.clone(),
                cache,
                JniRangeFetcher,
                prefix_gate,
            );
            reader.set_online(online.get());

            let archive = match MamapsArchive::open_with_cache(reader, Arc::clone(&leaves)) {
                Ok(a) => a,
                Err(e) => {
                    log(&format!(
                        "worker {index} cannot open the mamaps archive: {e}"
                    ));
                    return;
                }
            };
            serve(index, archive, &queue, &finished, &zoom_range, &toggles);
        })
        .is_ok();
    if !started {
        log(&format!("cannot start tile worker {index}"));
    }
}

/// How many times the cold-start header fetch is retried before the workers give up.
///
/// The fetch runs once per surface (not once per worker), so these retries are the surface's
/// whole startup budget: a few seconds of tunnel against giving up for the session. Past this
/// the lock holds `None` and every worker returns, as before.
#[cfg(target_os = "android")]
const HEADER_RETRIES: u32 = 5;

/// One attempt at the archive header's `build_id`: `Some` on a parsed 128-byte 206,
/// `None` on any transport failure, unexpected status or unparseable body.
#[cfg(target_os = "android")]
fn fetch_header_once(archive_url: &str) -> Option<u64> {
    use crate::tile::source::JniRangeFetcher;
    use crate::tile::source::RangeFetcher;
    match JniRangeFetcher.fetch(archive_url, "bytes=0-127") {
        Ok(r)
            if r.status == 206 && r.body.len() == tilecodec::mamaps::header::HEADER_LEN =>
        {
            match Header::parse(&r.body) {
                Ok(h) => Some(h.build_id),
                Err(e) => {
                    log(&format!("cannot parse the mamaps header: {e}"));
                    None
                }
            }
        }
        Ok(r) => {
            log(&format!(
                "cannot fetch the mamaps header: HTTP {}",
                r.status
            ));
            None
        }
        Err(e) => {
            log(&format!("cannot fetch the mamaps header: {e}"));
            None
        }
    }
}

/// Fetch the archive header's `build_id`, retrying transient failures with backoff.
///
/// A `Some` is the build id; `None` means the header never arrived and the caller returns.
/// Parse failures are not retried — a body that will not parse now will not parse in a
/// second — but transport failures and non-206 statuses are, because those are what a
/// tunnel, a captive portal and a cold radio look like.
#[cfg(target_os = "android")]
fn fetch_header_with_retry(archive_url: &str) -> Option<u64> {
    use crate::tile::source::retry_delay_ms;
    let mut attempt = 0u32;
    loop {
        match fetch_header_once(archive_url) {
            Some(id) => return Some(id),
            None if attempt >= HEADER_RETRIES => return None,
            None => {
                attempt += 1;
                std::thread::sleep(std::time::Duration::from_millis(retry_delay_ms(attempt)));
            }
        }
    }
}
/// How many queued tiles one worker takes per wave.
///
/// The frame enqueues centre-first, and the archive serves a batch in two waves (cold leaves,
/// then bodies) instead of two serial round trips per tile. Four is a viewport's share per
/// worker (a couple of dozen tiles across [`WORKER_COUNT`](super::handle::WORKER_COUNT)
/// workers): large enough that a cold screenful costs waves rather than serial tiles, small
/// enough that one worker never hoards the queue while others idle, and a toggle change still
/// reaches every tile within one wave (each tile tessellates under its own snapshot below).
const BATCH_TILES: usize = 4;

/// Generic over the tile source so the same loop drives the single archive,
/// the patch-first overlay, and the URL-backed reader: only how the archive
/// is opened differs, never how tiles are served from it.
fn serve<T: tilecodec::mamaps::read::TileSource>(
    index: usize,
    archive: T,
    queue: &Arc<Mutex<Receiver<TileId>>>,
    finished: &Sender<(u64, TileResult)>,
    zoom_range: &Arc<ZoomRange>,
    toggles: &Arc<SharedToggles>,
) {
    // Publish the real range. Until this lands the renderer works from a guess, and
    // a guess that is too high asks for a zoom the archive does not contain and
    // silently gets nothing back. The build id rides with it so the frame can clear
    // absences recorded against a previous publish (see `absent_build_id`).
    zoom_range.set(archive.header().min_zoom, archive.header().max_zoom, archive.header().build_id);
    let layers = style::layers();
    // Read once from the header rather than per tile: it is a property of the archive. Gated
    // by `TRUST_RINGS_VALIDATED`: the published planets set the flag without earning it.
    let rings_validated =
        archive.header().rings_validated() && geometry::TRUST_RINGS_VALIDATED;

    loop {
        // One blocking take, then drain whatever else is waiting without blocking: a wave of
        // up to `BATCH_TILES` the archive fetches in two concurrent waves (cold leaves, then
        // bodies) instead of two serial round trips per tile. The lock is held only across the
        // takes, never across the fetch — otherwise the workers would run strictly in turn.
        let first = match queue.lock() {
            Ok(guard) => guard.recv(),
            Err(_) => return,
        };
        let Ok(first) = first else { return };
        let mut batch = vec![first];
        if let Ok(guard) = queue.try_lock() {
            while batch.len() < BATCH_TILES {
                match guard.try_recv() {
                    Ok(tile) => batch.push(tile),
                    Err(_) => break,
                }
            }
        }
        if batch.len() < 2 {
            // One tile: the single-tile path, identical cost to before — no batch framing.
            let tile = batch[0];
            let result = build_one(
                &archive,
                tile,
                std::time::Instant::now(),
                layers,
                rings_validated,
                toggles,
            );
            // A closed receiver means the surface went away mid-decode.
            if finished.send((tile.key(), result)).is_err() {
                return;
            }
            continue;
        }
        // The wave: every tile's body in two concurrent fetches. Tessellation stays per
        // tile under its own toggle snapshot (a toggle change still reaches the very next
        // tile built), and each result is reported under its own key.
        let dequeue_at = std::time::Instant::now();
        let fetch_start = std::time::Instant::now();
        let coords: Vec<(u8, u32, u32)> = batch.iter().map(|t| (t.z, t.x, t.y)).collect();
        let fetched = archive.tiles(&coords);
        let fetch_ms = fetch_start.elapsed().as_millis();
        // One durability pass per wave, not one fsync per range: order everything the wave
        // just wrote while it is still hot, before tessellation takes the worker elsewhere.
        archive.flush_pending();
        debug_assert_eq!(fetched.len(), batch.len());
        // Wash overzoom, wave 1.5: collect the DISTINCT z13 parents needed by
        // tiles whose bodies want underlay, and fetch them in one batch call.
        // Siblings share parents (4:1), and the shared leaf/disk caches make
        // repeats cheap — but one fetch per distinct parent is strictly fewer
        // range reads, so dedupe here rather than per tile below.
        let mut need_underlay: Vec<usize> = Vec::new();
        for (i, (tile, body)) in batch.iter().zip(fetched.iter()).enumerate() {
            if let Ok(Some(body)) = body {
                if crate::tile::geometry::needs_wash_underlay(body, tile.z) {
                    need_underlay.push(i);
                }
            }
        }
        let mut parent_bodies: std::collections::HashMap<(u8, u32, u32), Option<tilecodec::mamaps::body::Body>> =
            std::collections::HashMap::new();
        let mut parent_failed = false;
        if !need_underlay.is_empty() {
            use crate::tile::geometry::parent_of;
            let mut distinct: Vec<(u8, u32, u32)> = Vec::new();
            let mut seen = std::collections::HashSet::new();
            for &i in &need_underlay {
                let tile = batch[i];
                let (px, py, _, _) = parent_of(tile.x, tile.y);
                if seen.insert((tile.z - 1, px, py)) {
                    distinct.push((tile.z - 1, px, py));
                }
            }
            let fetched_parents = archive.tiles(&distinct);
            for (coord, res) in distinct.iter().zip(fetched_parents) {
                match res {
                    Ok(body) => {
                        parent_bodies.insert(*coord, body);
                    }
                    Err(e) => {
                        log(&format!(
                            "wash underlay parent {}/{}/{} failed: {e}",
                            coord.0, coord.1, coord.2
                        ));
                        parent_failed = true;
                    }
                }
            }
        }
        for (tile, body) in batch.iter().zip(fetched) {
            // Read per tile, not once per batch: a toggle change has to reach the
            // very next tile built, and all three parts come from one snapshot so the
            // stamp can never describe different flags than the mesh was built with.
            let (enabled, kinds, generation) = toggles.get();
            let tess_start = std::time::Instant::now();
            let result = match body {
                Ok(Some(body)) => {
                    // Wash overzoom, wave 2.5: stitch the (already fetched)
                    // parent wash in, or fail the tile when its parent failed
                    // so retry backoff picks it up.
                    let body = if crate::tile::geometry::needs_wash_underlay(&body, tile.z) {
                        use crate::tile::geometry::{parent_of, stitch_parent_wash};
                        let (px, py, qx, qy) = parent_of(tile.x, tile.y);
                        // Borrowed, not removed: siblings share one parent.
                        match parent_bodies.get(&(tile.z - 1, px, py)) {
                            Some(Some(parent)) => stitch_parent_wash(&body, parent, qx, qy),
                            Some(None) => body,
                            None => {
                                // Its parent failed (parent_failed): fail the tile
                                // so retry backoff picks it up rather than
                                // rendering ocean for land.
                                debug_assert!(parent_failed);
                                if finished.send((tile.key(), TileResult::Failed)).is_err() {
                                    return;
                                }
                                continue;
                            }
                        }
                    } else {
                        body
                    };
                    let mesh = geometry::build_toggled(
                        &body,
                        layers,
                        tile.z,
                        tile.x,
                        tile.y,
                        rings_validated,
                        enabled,
                        &kinds,
                        generation,
                    );
                    let tess_ms = tess_start.elapsed().as_millis();
                    log_info(&format!(
                        "tile {}/{}/{} queue={}ms fetch={}ms tess={}ms",
                        tile.z,
                        tile.x,
                        tile.y,
                        dequeue_at.elapsed().as_millis().saturating_sub(fetch_ms + tess_ms),
                        fetch_ms,
                        tess_ms,
                    ));
                    TileResult::Ready(mesh)
                }
                Ok(None) => TileResult::Absent,
                Err(e) => {
                    log(&format!("tile {}/{}/{} failed: {e}", tile.z, tile.x, tile.y));
                    TileResult::Failed
                }
            };
            // A closed receiver means the surface went away mid-decode.
            if finished.send((tile.key(), result)).is_err() {
                return;
            }
        }
    }
}

/// Wash underlay for one fetched body: when a z14 tile's own landtype holds
/// no wash (gated archive), fetch the z13 parent and stitch its wash areas in
/// before tessellation. Returns the body to build (possibly augmented), or
/// `None` when the parent fetch failed and the tile must report failure so the
/// existing `RetryMap` backoff retries it.
///
/// Parent bodies flow through the same shared leaf cache and disk cache as any
/// other fetch — no new cache kind, no new request kind. Siblings share the
/// parent through those caches; the batch path below additionally dedupes
/// parent fetches within one wave (see `underlay_parents_for_batch`).
fn underlay_wash<T: tilecodec::mamaps::read::TileSource>(
    archive: &T,
    tile: TileId,
    body: tilecodec::mamaps::body::Body,
) -> Result<tilecodec::mamaps::body::Body, String> {
    use crate::tile::geometry::{needs_wash_underlay, parent_of, stitch_parent_wash};
    if !needs_wash_underlay(&body, tile.z) {
        return Ok(body);
    }
    let (px, py, qx, qy) = parent_of(tile.x, tile.y);
    match archive.tile(tile.z - 1, px, py) {
        Ok(Some(parent)) => Ok(stitch_parent_wash(&body, &parent, qx, qy)),
        Ok(None) => Ok(body),
        Err(e) => Err(e.0),
    }
}

/// One tile through fetch + tessellation, off the batch path.
///
/// What the worker did before batching, unchanged: the single-tile fallback above keeps it
/// for the one-deep queue, so its timing shape (`queue/fetch/tess` per tile) stays comparable
/// in the logs.
fn build_one<T: tilecodec::mamaps::read::TileSource>(
    archive: &T,
    tile: TileId,
    dequeue_at: std::time::Instant,
    layers: &[style::Layer],
    rings_validated: bool,
    toggles: &Arc<SharedToggles>,
) -> TileResult {
    // Read per tile, not once per worker: a toggle change has to reach the
    // very next tile built, and all three parts come from one snapshot so the
    // stamp can never describe different flags than the mesh was built with.
    let (enabled, kinds, generation) = toggles.get();
    let fetch_start = std::time::Instant::now();
    let fetched = archive.tile(tile.z, tile.x, tile.y);
    let fetch_ms = fetch_start.elapsed().as_millis();
    match fetched {
        Ok(Some(body)) => {
            // Wash overzoom: a gated z14 tile underlays its z13 parent's wash
            // before tessellation. A failed parent fetch fails the tile so the
            // existing retry backoff picks it up (the tile would otherwise
            // render ocean for land).
            let body = match underlay_wash(archive, tile, body) {
                Ok(body) => body,
                Err(e) => {
                    log(&format!("tile {}/{}/{} wash underlay failed: {e}", tile.z, tile.x, tile.y));
                    return TileResult::Failed;
                }
            };
            let tess_start = std::time::Instant::now();
            let mesh = geometry::build_toggled(
                &body,
                layers,
                tile.z,
                tile.x,
                tile.y,
                rings_validated,
                enabled,
                &kinds,
                generation,
            );
            let tess_ms = tess_start.elapsed().as_millis();
            // Per-tile pipeline timing for issue 815: queue wait vs fetch/decode
            // vs tessellation, at info level beside the per-frame report.
            // `queue_ms` includes time spent serving earlier queued tiles; a
            // large value on the first tiles of a cold load means the queue
            // is the bottleneck, not the network.
            log_info(&format!(
                "tile {}/{}/{} queue={}ms fetch={}ms tess={}ms",
                tile.z,
                tile.x,
                tile.y,
                dequeue_at.elapsed().as_millis().saturating_sub(fetch_ms + tess_ms),
                fetch_ms,
                tess_ms,
            ));
            TileResult::Ready(mesh)
        }
        Ok(None) => TileResult::Absent,
        Err(e) => {
            log(&format!(
                "tile {}/{}/{} failed: {e}",
                tile.z, tile.x, tile.y
            ));
            TileResult::Failed
        }
    }
}
