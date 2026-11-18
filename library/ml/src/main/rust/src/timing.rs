//! Opt-in timing, silent unless it is asked for.
//!
//! This runtime's cost is dominated by things that do not show up in a profile of the arithmetic —
//! serialising every op is expensive, though the size of that effect is **unmeasured**: the
//! no-barrier control delivered 43,008 frames against a correct run's 150,528, so it compared a
//! full utterance against a truncated one. The one valid same-work pair, `narrow` 2,179 ms against
//! `none` 1,160 ms at 43,008 frames each, puts it at 47% of a truncated run with a cheaper
//! barrier. See `analysis/maml_vs_litert.md` section 6; pinning the frame count so both paths do
//! identical work is task 11.
//!
//! Either way the numbers have to come from the runtime itself, and they are useless in aggregate:
//! "inference took 5.5 seconds" says nothing, while "the sampler ran 32 times at 158 ms of which
//! 96 ms was barriers" says everything.
//!
//! Switched on by `MODELRUNNER_TIMING` or `debug.modelrunner.timing`; see [`crate::knobs`] for
//! why there are two.
//!
//! Read once and cached. The answer cannot change usefully within a process, and this is called
//! from inside the per-op recording loop where a lookup per call would be part of what it is
//! trying to measure.

use std::sync::OnceLock;

/// Whether timing was asked for. Cached; see the module header.
pub fn enabled() -> bool {
    static ON: OnceLock<bool> = OnceLock::new();
    *ON.get_or_init(|| crate::knobs::is_set("timing"))
}

/// Write one line wherever this platform's logs go.
///
/// Deliberately not behind [`enabled`]: the [`timing!`] macro checks that before formatting, so a
/// disabled build pays a load and a branch rather than an allocation.
pub fn emit(message: &str) {
    write(message);
}

/// One timed line, formatted only if timing is on.
///
/// The guard is outside the `format!` on purpose. These sites are inside the per-op loop, and
/// formatting a string that is then thrown away would cost more than the op being measured.
#[macro_export]
macro_rules! timing {
    ($($arg:tt)*) => {
        if $crate::timing::enabled() {
            $crate::timing::emit(&format!($($arg)*));
        }
    };
}

#[cfg(target_os = "android")]
fn write(message: &str) {
    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(priority: i32, tag: *const u8, text: *const u8) -> i32;
    }
    /// `ANDROID_LOG_INFO`. Not `ERROR`, which is what the first cut of this used and which put a
    /// per-utterance red line in every log this app touches.
    const INFO: i32 = 4;
    let mut text: Vec<u8> = message.as_bytes().to_vec();
    text.push(0);
    // SAFETY: both pointers are to NUL-terminated buffers that outlive the call.
    unsafe {
        let _ = __android_log_write(INFO, b"ModelRunner\0".as_ptr(), text.as_ptr());
    }
}

#[cfg(not(target_os = "android"))]
fn write(message: &str) {
    eprintln!("{message}");
}

#[cfg(test)]
#[path = "timing_tests.rs"]
mod timing_tests;
