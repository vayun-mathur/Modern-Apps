use crate::camera::Camera;

use super::coverage::visible;

use super::tile_id::TileId;

/// Whether this frame deserves the zoom-out upload burst: the first frame, or any frame
/// whose camera zoom decreased since the last.
///
/// Zoom-out invalidates the whole viewport at once while its replacements are usually
/// already finished (ancestors fetched ahead, resident descendants standing in), so the
/// steady-state smoothing only stretches the coarse interval. A pure function of the two
/// zooms so the drain policy is host-testable; the render thread threads its `last_zoom`
/// through it (see `MapHandle::upload_budget`).
pub fn zoom_out_burst(last_zoom: Option<f64>, zoom: f64) -> bool {
    last_zoom.is_none_or(|last| zoom < last)
}

/// The most tiles allowed resident on the GPU at once.
///
/// Together with [`RESIDENT_BYTE_BUDGET`] this bounds tile memory, so it is a real limit rather
/// than a safety net. It matters more now that descendants are kept: an
/// ancestor set is linear in depth, but a descendant set is not, and without a cap every deep
/// tile visited would stay resident for as long as the camera sat above it.
///
/// Sized from what the renderer reports rather than from theory. A dense z14 screenful logged
/// ~2.0 M triangles across 7 tiles, so a tile in a city centre costs single-digit megabytes of
/// vertex and index buffers; 64 leaves room for a ~12-tile viewport, its four ancestor levels and
/// a useful spread of descendants, while keeping the worst case in the hundreds of megabytes
/// rather than the gigabytes an uncapped depth-2 fan-out could reach. Going over drops
/// descendants first, so the cost of being wrong here is a briefly coarser zoom-out, not a blank
/// screen.
///
/// P5.4 verdict — held at 64, measured first. The structural pin is
/// `resident_demand_fits_the_cap_with_headroom`: worst unfogged keep set 37 (tablet
/// z12, 45° bearing), leaving 27 slots for descendant stand-ins under this cap. Tilted
/// frames enumerate more (162 at 65° pitch) but those far tiles are fogged to the land
/// colour past ~6 viewport heights (`FOG_FAR_VIEWPORTS`) — haze, not detail — so the
/// follow-up for tilt churn is a pitch-aware far-fetch cap in `coverage`, not a bigger
/// resident set. The device counters that would justify moving this number are the
/// per-frame report's `stale-dropped`, `fcan` and resident-tile counts: churn there
/// with a small keep set means raise; a large resident set with idle-time memory
/// pressure means lower.
///
/// Lives here (not in `bridge::handle`) so the host-testable selection suite can pin the
/// structural demand against it.
pub const RESIDENT_TILE_CAP: usize = 64;

/// GPU bytes (vertex + index) the non-visible resident tiles may add on top of what is on
/// screen.
///
/// The count cap alone let 64 dense city tiles stay resident, hundreds of megabytes. Visible
/// tiles are never evicted and ancestors are coarse and few, so only descendants and unrelated
/// tiles answer to this budget: past it they go first, which costs a briefly coarser zoom-out.
pub const RESIDENT_BYTE_BUDGET: u64 = 48 * 1024 * 1024;

/// Which resident tiles to evict, given each as `(rank, key, gpu_bytes)`.
///
/// Rank 0 is visible, 1 an ancestor, 2 a descendant stand-in, 3 unrelated. Rank 3 always goes;
/// past `cap` tiles anything but rank 0 goes; and once the running byte total (in rank order)
/// passes `byte_budget`, ranks 2 and 3 go. Visible tiles are never evicted: evicting one only
/// refetches it next frame.
pub fn evict(ranked: &mut [(u8, u64, u64)], cap: usize, byte_budget: u64) -> Vec<u64> {
    ranked.sort_unstable_by_key(|&(rank, key, _)| (rank, key));
    let mut bytes = 0u64;
    let mut kept = 0usize;
    let mut doomed = Vec::new();
    for &(rank, key, size) in ranked.iter() {
        let over_cap = kept >= cap && rank != 0;
        let over_budget = bytes.saturating_add(size) > byte_budget && rank >= 2;
        if rank == 3 || over_cap || over_budget {
            doomed.push(key);
        } else {
            kept += 1;
            bytes = bytes.saturating_add(size);
        }
    }
    doomed
}

/// How many levels of descendant to *keep* when they are already resident.
///
/// The mirror of [`ANCESTOR_DEPTH`], and the reason zooming out no longer blanks the map. An
/// ancestor stands in while a finer tile loads; nothing stood in while a *coarser* one loaded,
/// because the tiles on screen were the new tile's descendants and were evicted the moment the
/// camera moved. The map then drew nothing for as long as the fetch took.
///
/// Two rather than four: a descendant set grows as 4^depth where an ancestor set is linear, and
/// two levels already covers a 4x zoom-out — more than one pinch produces. This is a bound on GPU
/// memory, not a lookahead.
pub const DESCENDANT_DEPTH: u8 = 2;

/// Whether a resident tile is worth keeping as a stand-in for a visible one that has not arrived.
///
/// Deliberately a predicate over what is *already resident*, rather than something
/// [`resident_set`] could enumerate: naming every descendant means 4^[`DESCENDANT_DEPTH`] keys per
/// visible tile, most of which were never fetched, and the keep list is asserted to stay
/// proportional to the viewport.
pub fn stands_in_for_visible(key: u64, visible: &[TileId], depth: u8) -> bool {
    let tile = TileId::from_key(key);
    visible.iter().any(|v| tile.descends_from(v, depth))
}

/// How many levels of ancestor to *keep* when they are already resident.
///
/// Four covers a 16x zoom jump, which is more than a pinch produces in one gesture.
pub const ANCESTOR_DEPTH: u8 = 4;

/// The tiles worth **keeping resident**: the visible ones, plus any ancestor of a visible
/// tile.
///
/// This is deliberately *not* the fetch list. An ancestor is a fallback for a tile that has
/// not arrived yet, so it is only useful if we **already have it** — fetching one costs a
/// round trip to draw a blurrier version of a tile that is being fetched anyway. Doing that
/// tripled the network for a screenful (24 fetches instead of 12) and, because ancestors sort
/// first, spent all that latency *before* requesting the tiles the user is actually looking
/// at. MapLibre renders the parent it happens to have cached; it does not go and fetch one.
///
/// Nor is it the whole keep list. Descendants are the other half of the fallback — see
/// [`stands_in_for_visible`] — and cannot be named here without enumerating tiles that were
/// never fetched.
///
/// Ancestors are returned **before** the tiles they stand in for. The renderer sorts by zoom
/// before drawing, so this is for the residency cap rather than for draw order: coarser tiles
/// are the cheaper, more widely useful stand-ins and should be the last thing evicted.
pub fn resident_set(camera: &Camera, min_zoom: u8, max_zoom: u8) -> Vec<TileId> {
    let exact = visible(camera, min_zoom, max_zoom);
    if exact.is_empty() {
        return exact;
    }
    let mut out: Vec<TileId> = Vec::with_capacity(exact.len() * 2);
    let mut seen = std::collections::HashSet::new();

    // Coarsest first, so the draw order is ancestors under descendants.
    for level in (1..=ANCESTOR_DEPTH).rev() {
        for tile in &exact {
            if tile.z < min_zoom + level {
                continue;
            }
            let ancestor = TileId {
                z: tile.z - level,
                x: tile.x >> level,
                y: tile.y >> level,
            };
            if seen.insert(ancestor.key()) {
                out.push(ancestor);
            }
        }
    }
    for tile in exact {
        if seen.insert(tile.key()) {
            out.push(tile);
        }
    }
    out
}
