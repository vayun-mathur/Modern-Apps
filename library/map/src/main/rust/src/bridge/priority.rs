//! Scheduling priority for the native tile workers.
//!
//! Split from `workers.rs` (file-length limit).

/// Nice value for the tile workers: Android's `THREAD_PRIORITY_BACKGROUND`.
///
/// The eight workers decode and tessellate whole tiles (tens of ms of CPU each on a phone) and
/// were left at the default nice 0 — the same weight as the UI thread and above nothing. A pan
/// queues a screenful of tiles at once, so all eight went runnable together on a phone with
/// eight cores, and the UI thread and the map's render thread had to share every core with
/// them for the length of the burst: dropped frames exactly while the user was moving the map.
/// At nice 10 the scheduler gives a nice-0 thread ~9x a worker's share of a contended core, so
/// interactive threads win when they need the CPU and workers still use every idle cycle.
const WORKER_NICE: i32 = 10;

/// Drop the calling thread to [`WORKER_NICE`]. Linux nice is per thread, so `PRIO_PROCESS`
/// with `who = 0` addresses the caller only. Best effort: a refusal leaves the default.
pub(super) fn lower_worker_priority() {
    extern "C" {
        fn setpriority(which: i32, who: u32, prio: i32) -> i32;
    }
    const PRIO_PROCESS: i32 = 0;
    // SAFETY: plain syscall wrapper on the calling thread; no memory is passed.
    if unsafe { setpriority(PRIO_PROCESS, 0, WORKER_NICE) } != 0 {
        super::log::log("could not lower a tile worker's priority; it stays at the default");
    }
}
