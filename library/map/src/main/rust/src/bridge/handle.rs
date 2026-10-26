//! The map handle and the state shared with the tile workers.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use crate::style::{Layer, Palette, SharedToggles};
use crate::bridge::gesture::GestureDebounce;
use crate::tile::geometry::TileMesh;
use crate::tile::select::TileId;
/// Re-exported so `bridge::frame` keeps its `super::handle::RESIDENT_TILE_CAP` path: the
/// constant lives in `tile::select` (with the retention policy and its measurement pin),
/// but the frame reads it through the handle.
pub(crate) use crate::tile::select::{RESIDENT_BYTE_BUDGET, RESIDENT_TILE_CAP};
use crate::vulkan::renderer::Renderer;
use jni::sys::jlong;
use std::collections::{HashMap, HashSet};
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};
/// The archive's zoom range, shared with the worker that reads it out of the header.
///
/// Atomics rather than a `Mutex` because `render` reads this every frame, and it must be
/// read live rather than copied once: the range is not known until the header has been
/// fetched, which is a network round trip *after* the surface exists. Copying it at startup
/// is how this was wrong before — it captured the guess and never corrected it, so the
/// renderer asked for a zoom level the archive does not contain and got nothing back.
pub(crate) struct ZoomRange {
    min: std::sync::atomic::AtomicU8,
    max: std::sync::atomic::AtomicU8,
    /// The archive `build_id` the range came with, or 0 before the header lands.
    ///
    /// Read live every frame: when it changes, the archive was republished under its stable
    /// URL and every cached absence/backoff addresses the previous build's byte offsets —
    /// so the frame clears both rather than serving a map made of mismatched tiles.
    build_id: std::sync::atomic::AtomicU64,
}

impl ZoomRange {
    /// The published archive is z0-15. A wrong guess here is self-correcting once the
    /// header lands, but starting at the truth means the first frames are right too.
    pub(crate) fn unknown() -> ZoomRange {
        ZoomRange {
            min: std::sync::atomic::AtomicU8::new(0),
            max: std::sync::atomic::AtomicU8::new(15),
            build_id: std::sync::atomic::AtomicU64::new(0),
        }
    }

    pub(crate) fn set(&self, min: u8, max: u8, build_id: u64) {
        self.min.store(min, std::sync::atomic::Ordering::Relaxed);
        self.max.store(max, std::sync::atomic::Ordering::Relaxed);
        // Last: a reader that sees the new build id is guaranteed to also see the new
        // range (same ordering on both sides), so the frame below never clears the
        // absent set against a range it has not adopted yet.
        self.build_id
            .store(build_id, std::sync::atomic::Ordering::Relaxed);
    }

    pub(crate) fn get(&self) -> (u8, u8) {
        (
            self.min.load(std::sync::atomic::Ordering::Relaxed),
            self.max.load(std::sync::atomic::Ordering::Relaxed),
        )
    }

    pub(crate) fn build_id(&self) -> u64 {
        self.build_id.load(std::sync::atomic::Ordering::Relaxed)
    }
}

/// How many tile workers run in parallel.
///
/// Each tile costs one or two **sequential** range requests — a leaf directory, then the
/// body — so its cost is dominated by round-trip latency, not CPU. One worker serialises
/// every tile behind every other: the archive probe measured a 24-tile screenful taking ~15
/// seconds that way, which is exactly the "really slow to change which elements load"
/// symptom.
///
/// Eight is the first step toward MapLibre's 20 concurrent requests per host (issue 815):
/// it halves the serial waves of a cold screenful while staying inside what a phone's cores
/// and radio handle without thermal or socket pressure. Raise toward 20 only with the
/// per-tile `queue/fetch/tess` logs showing fetch still dominating and thermals clean.
pub(crate) const WORKER_COUNT: usize = 8;

/// How many finished tiles may be uploaded in one frame.
///
/// The drain used to be unbounded, so however many tiles the workers happened to finish between
/// two frames all landed in the next one. Uploading a tile is around a hundred `vkAllocateMemory`
/// calls, on the Choreographer callback, and a burst of them is what the frame-time tail is made
/// of. What is left stays in the channel and is picked up next frame — nothing is dropped, and
/// the tile is not re-requested, because its key is only removed from `in_flight` once it is
/// actually drained.
///
/// Eight is the middle that moved with the worker count (issue 815): a screenful is a
/// couple of dozen tiles, so a cold pan fills in over roughly half the frames it did at
/// four workers and four uploads, which stays a fraction of the fetch and decode latency
/// that preceded it and so is not visible. Lower would start to look like a trickle now that
/// twice as many workers finish per frame; higher gives the frame-time tail back — an upload
/// is still around a hundred `vkAllocateMemory` calls on the Choreographer callback.
///
/// Raised after a zoom-out (see [`upload_budget`]): a zoom-out invalidates a screenful at once,
/// and draining it at the steady rate leaves the map coarse for seconds while the ancestors it
/// needs are already finished and waiting in the channel.
pub(crate) const UPLOADS_PER_FRAME: usize = 8;

/// Uploads per frame while the camera is zooming out: the burst rate that drains a screenful
/// of newly-visible ancestors in a frame or two rather than ten.
///
/// Zoom-out is the one gesture that invalidates the whole viewport at once — every visible
/// key changes — while the replacements are usually already finished (ancestors fetched
/// ahead, or resident descendants standing in). The steady-state cap exists to smooth a
/// burst of *finished* tiles across frames; after a zoom-out the burst is the whole frame,
/// and smoothing it only stretches the coarse interval. Detected by comparing the camera
/// zoom against the last frame's: any decrease takes the burst path for that frame.
pub(crate) const UPLOADS_PER_FRAME_ZOOM_OUT: usize = 16;

/// The deepest zoom the `landtype` wash tiles to.
///
/// The archive stops at z14 (`mamaps_build::DEFAULT_MAX_ZOOM`); past it the renderer
/// overzooms, and the wash a z15+ viewport shows comes from its z14 ancestors — which the
/// fetch loop has to ask for explicitly (see `wash_extra` in `frame.rs`), because keeping
/// an ancestor resident is not the same as fetching it. One table for what used to be five
/// disagreeing caps (`z0-15` guess, z16 doc, 22 style max, hardcoded z12 wash, z14 tiler):
/// the tiler's z14 is the truth and this names it.
pub(crate) const WASH_MAX_ZOOM: u8 = 14;

/// What a worker reports back about a tile.
pub(crate) enum TileResult {
    /// Tessellated and ready to upload.
    Ready(TileMesh),
    /// The archive genuinely does not contain it — ordinary off the edge of coverage.
    /// Never retried.
    Absent,
    /// The fetch or decode failed. **Must** clear the in-flight marker so it can be tried
    /// again: leaving it set meant one transient network error blanked that tile for the
    /// rest of the session.
    Failed,
}

/// Everything one map surface owns. Handed to Kotlin as an opaque `jlong`.
pub(crate) struct MapHandle {
    pub(crate) renderer: Renderer,
    pub(crate) layers: &'static [Layer],
    /// Meshes finished by the workers, waiting to be uploaded on the render thread.
    pub(crate) finished: Receiver<(u64, TileResult)>,
    /// Tiles the workers should fetch.
    pub(crate) wanted: Sender<TileId>,
    /// The receiving end of [`wanted`](Self::wanted), shared with the workers.
    ///
    /// Lets a frame drain tiles that stopped being wanted *before* a worker spends a fetch on
    /// them (issue 815): panning queues the new viewport behind the old one, and without a
    /// drain the new area waits behind fetches nobody looks at anymore. Drained keys leave
    /// `in_flight` (see `frame.rs`), so still-visible ones are re-sent through the normal gates
    /// and genuinely-fetching workers are never disturbed — only never-started queue entries go
    /// away. Locked with `try_lock`, never blocking: a worker parked in `recv` holds it, and
    /// blocking behind that worker would deadlock the render thread against its own send.
    pub(crate) wanted_queue: Arc<Mutex<Receiver<TileId>>>,
    /// The wanted tile keys of the last Earth frame, for the drain-and-replace generation.
    ///
    /// When the wanted set changes, the queued (never-started) remainder of the previous set is
    /// stale and is drained (see [`wanted_queue`](Self::wanted_queue)); when it matches, the
    /// queue flows untouched. Finished tiles outside this set skip uploading: they scrolled out
    /// while in flight, and spending an upload slot on them only delays visible tiles. Empty
    /// before the first Earth frame, which disables the stale-drop until there is a set to be
    /// stale against. Untouched on Moon frames, so the Earth set resumes mid-stream.
    pub(crate) last_visible: HashSet<u64>,
    /// Requested but not yet arrived, so a tile is not asked for sixty times a second
    /// while it is in flight.
    pub(crate) in_flight: HashSet<u64>,
    /// Tiles the archive does not contain. Remembered so they are not re-requested every
    /// frame forever — most of a coastal viewport is ocean.
    ///
    /// Bounded (see [`ABSENT_CAP`]): without a cap a pan-heavy session accumulates one entry
    /// per distinct empty tile it ever visited. Eviction re-requests at most one tile that
    /// is still absent — a single fetch, not a storm.
    pub(crate) absent: AbsentSet,
    /// Tiles whose fetch or decode failed, as `key -> (consecutive failures, earliest retry)`.
    ///
    /// [`TileResult::Failed`] deliberately does not mark a tile [`TileResult::Absent`], so it
    /// is tried again — but with nothing recording *when*, "again" meant on the very next
    /// frame, and a tile that keeps failing was re-requested sixty times a second for as long
    /// as it stayed visible. This is the missing half: the same retry, at
    /// [`retry_delay_ms`](crate::tile::source::retry_delay_ms) intervals.
    ///
    /// `Instant`, not the camera's `time_seconds`, because that clock wraps hourly and a
    /// deadline across a wrap would either fire an hour early or an hour late.
    ///
    /// Cleared per tile on success. Bounded like [`absent`](Self::absent): it holds one small
    /// entry per distinct tile that has actually failed recently, not per tile that ever
    /// failed this session.
    pub(crate) retry: RetryMap,
    pub(crate) online: Arc<OnlineFlag>,
    /// Light or dark. Switching costs nothing: colour is a push constant and the layer set
    /// is identical, so no tile is re-tessellated or re-uploaded.
    /// Light or dark, muted or not. Switching costs nothing: colour is a push constant and
    /// the layer set is identical, so no tile is re-tessellated or re-uploaded.
    pub(crate) palette: Palette,
    /// Which optional layers (POI, transit) are on, plus the generation that identifies
    /// them. Shared with the tile workers, which gate tessellation on it and stamp every
    /// mesh they build with the generation they read.
    pub(crate) toggles: Arc<SharedToggles>,
    /// Read live every frame, because the worker only learns it after fetching the header.
    pub(crate) zoom_range: Arc<ZoomRange>,
    /// The archive `build_id` the absent/backoff sets were recorded against, or 0 before the
    /// header lands. When the worker publishes a new build id the archive was republished
    /// under its stable URL: cached absences address the previous build's byte offsets, so
    /// both sets are cleared rather than serving a map made of mismatched tiles.
    pub(crate) absent_build_id: u64,
    /// Last frame's density (device px per Dp), for the task-17 pick path's
    /// Dp→device-px conversion. Written every render call.
    pub(crate) density: f32,
    /// The camera zoom of the last frame that ran the upload drain, for the zoom-out
    /// burst (see [`upload_budget`](Self::upload_budget)). `None` before the first
    /// frame; `None` after a Moon frame, so returning from the Moon always takes the
    /// burst path once rather than comparing against a stale Earth zoom.
    pub(crate) last_zoom: Option<f64>,
    /// Gesture-debounce fetch state: while a gesture is active the fetch zoom freezes
    /// (see `GestureDebounce`), so a pinch does not churn the wanted set every frame.
    pub(crate) gesture: GestureDebounce,
}

impl MapHandle {
    /// How many finished tiles this frame may upload: the burst rate while zooming out,
    /// the steady rate otherwise — and the burst rate once after the Moon, when the Earth
    /// set resumes mid-stream.
    ///
    /// Zoom-out invalidates the whole viewport at once while its replacements are usually
    /// already finished (ancestors fetched ahead, resident descendants standing in), so
    /// the steady-state smoothing only stretches the coarse interval. Records the zoom it
    /// saw, so the next frame compares against this one (see
    /// [`zoom_out_burst`](crate::tile::select::zoom_out_burst)).
    pub(crate) fn upload_budget(&mut self, zoom: f64) -> usize {
        let burst = crate::tile::select::zoom_out_burst(self.last_zoom, zoom);
        self.last_zoom = Some(zoom);
        if burst {
            UPLOADS_PER_FRAME_ZOOM_OUT
        } else {
            UPLOADS_PER_FRAME
        }
    }

    /// Forget the zoom history, so the next frame takes the burst path.
    ///
    /// Called on the Moon guard's behalf: Moon frames skip the drain without recording a
    /// zoom, and the Earth set underneath kept changing (or the camera moved while the
    /// Moon drew), so resuming at the steady rate would trickle tiles the user is already
    /// looking at.
    pub(crate) fn forget_zoom(&mut self) {
        self.last_zoom = None;
    }

    /// The host's gesture signal: `true` while a finger is down or a fling runs.
    ///
    /// Freezes the *fetch* zoom at the current level until release (see
    /// [`GestureDebounce`]); rendering keeps tracking the live camera. Cheap and
    /// idempotent — the Kotlin gesture channel drives this, and a redundant set is a
    /// comparison, not a fetch.
    pub(crate) fn set_gesture_active(&mut self, active: bool, zoom: f64) {
        self.gesture.set_active(active, zoom);
    }
}

/// Shared so Kotlin's connectivity callback can reach the reader on the worker thread.
pub(crate) struct OnlineFlag(pub(crate) std::sync::atomic::AtomicBool);

impl OnlineFlag {
    pub(crate) fn set(&self, online: bool) {
        self.0.store(online, std::sync::atomic::Ordering::Relaxed);
    }
    pub(crate) fn get(&self) -> bool {
        self.0.load(std::sync::atomic::Ordering::Relaxed)
    }
}

pub(crate) fn handle_mut(handle: jlong) -> Option<&'static mut MapHandle> {
    if handle == 0 {
        return None;
    }
    // The handle is only ever the pointer `create` returned, and Kotlin drives all of
    // these from one thread.
    unsafe { Some(&mut *(handle as *mut MapHandle)) }
}

/// How many absent tiles are remembered before the oldest is forgotten.
///
/// A viewport is a couple of dozen tiles; 512 holds a long pan trail while keeping the
/// worst case at hundreds of entries rather than the tens of thousands an unbounded set
/// reaches on a pan-heavy session. Forgetting one only costs a single re-request if that
/// tile is still absent — never a storm, because a re-probed absent tile lands right back
/// here.
pub(crate) const ABSENT_CAP: usize = 512;

/// How many failed tiles hold a backoff at once. Same shape and same reasoning as
/// [`ABSENT_CAP`]: forgetting one only costs a re-request, and an unbounded map is a
/// slow leak on a flaky network.
pub(crate) const RETRY_CAP: usize = 512;

/// The tiles the archive does not contain: an insertion-ordered set capped at
/// [`ABSENT_CAP`].
///
/// `HashSet` for the per-frame membership test plus a `VecDeque` for the insertion order,
/// so evicting the oldest is O(1) amortised. All mutation goes through [`insert`] and
/// [`clear`]; there is deliberately no `remove` — nothing but eviction and a full clear
/// takes a tile out.
pub(crate) struct AbsentSet {
    set: HashSet<u64>,
    order: std::collections::VecDeque<u64>,
}

impl AbsentSet {
    pub(crate) fn new() -> AbsentSet {
        AbsentSet {
            set: HashSet::new(),
            order: std::collections::VecDeque::new(),
        }
    }

    pub(crate) fn contains(&self, key: &u64) -> bool {
        self.set.contains(key)
    }

    pub(crate) fn insert(&mut self, key: u64) {
        if !self.set.insert(key) {
            return;
        }
        self.order.push_back(key);
        while self.order.len() > ABSENT_CAP {
            if let Some(oldest) = self.order.pop_front() {
                self.set.remove(&oldest);
            } else {
                break;
            }
        }
    }

    pub(crate) fn clear(&mut self) {
        self.set.clear();
        self.order.clear();
    }

    pub(crate) fn len(&self) -> usize {
        self.set.len()
    }

    pub(crate) fn is_empty(&self) -> bool {
        self.set.is_empty()
    }
}

/// The failed-tile backoffs: `key -> (consecutive failures, earliest retry)`, capped at
/// [`RETRY_CAP`] by evicting the oldest insertion.
///
/// Same insertion-ordered shape as [`AbsentSet`]. Re-inserting a key that is already
/// present updates its value in place without moving it: the attempt count keeps climbing
/// while the tile keeps failing, and the eviction order stays first-failed-first-out.
pub(crate) struct RetryMap {
    map: HashMap<u64, (u32, std::time::Instant)>,
    order: std::collections::VecDeque<u64>,
}

impl RetryMap {
    pub(crate) fn new() -> RetryMap {
        RetryMap {
            map: HashMap::new(),
            order: std::collections::VecDeque::new(),
        }
    }

    pub(crate) fn get(&self, key: &u64) -> Option<&(u32, std::time::Instant)> {
        self.map.get(key)
    }

    pub(crate) fn insert(&mut self, key: u64, value: (u32, std::time::Instant)) {
        if self.map.insert(key, value).is_none() {
            self.order.push_back(key);
        }
        while self.order.len() > RETRY_CAP {
            if let Some(oldest) = self.order.pop_front() {
                self.map.remove(&oldest);
            } else {
                break;
            }
        }
    }

    pub(crate) fn remove(&mut self, key: &u64) -> Option<(u32, std::time::Instant)> {
        self.map.remove(key)
    }

    pub(crate) fn is_empty(&self) -> bool {
        self.map.is_empty()
    }

    pub(crate) fn values(&self) -> impl Iterator<Item = &(u32, std::time::Instant)> {
        self.map.values()
    }

    pub(crate) fn retain(&mut self, mut keep: impl FnMut(&u64, &mut (u32, std::time::Instant)) -> bool) {
        self.map.retain(|k, v| keep(k, v));
        if self.map.len() < self.order.len() {
            self.order.retain(|k| self.map.contains_key(k));
        }
    }

    pub(crate) fn clear(&mut self) {
        self.map.clear();
        self.order.clear();
    }

    #[cfg(test)]
    pub(crate) fn len(&self) -> usize {
        self.map.len()
    }

    /// Forget every backoff for a tile that is no longer visible.
    ///
    /// Not just housekeeping: an entry whose deadline has passed but which nothing
    /// re-requests would make `nextFrameDelayMillis` answer "draw now" forever, spinning
    /// the on-demand loop at 60fps for a tile that is off screen. Only the visible set is
    /// ever fetched, so only the visible set may hold a backoff.
    ///
    /// Skipped on Moon frames along with the fetch that feeds it: `visible` is empty, and
    /// retaining against it would drop every Earth backoff (harmless but wasteful).
    ///
    /// Linear rather than a `HashSet` of the visible keys, deliberately: this runs per frame,
    /// `retry` is empty in the ordinary case (so the closure never runs), and a viewport is a
    /// couple of dozen tiles. Building a set here would allocate every frame to save nothing.
    pub(crate) fn retain_visible(&mut self, visible: &[crate::tile::select::TileId]) {
        if self.map.is_empty() {
            return;
        }
        self.retain(|key, _| visible.iter().any(|t| t.key() == *key));
    }
}
