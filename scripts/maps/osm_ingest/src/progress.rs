//! A small stderr progress meter for the build's single-threaded scans.
//!
//! The parallel PBF passes already print a percentage of their own through
//! [`crate::pbf::run_pass_sink`]. This covers the sequential loops *between*
//! those passes — the kept-node scan, the rank index, the Morton sort setup, the
//! two CSR scans, the component walk and each write round — which used to run for
//! minutes at planet scale with nothing on screen.
//!
//! It writes to stderr rather than stdout so the meter's carriage returns never
//! interleave with the summary lines the build prints to stdout, and so a run
//! whose stdout is captured to a file still shows live progress on a terminal.

use std::io::Write;
use std::time::Instant;

/// Items to advance between redraws. A redraw formats a line and writes it under
/// the stderr lock, so doing it per item would dominate a tight scan; once per
/// this many is invisible to the loop and still smooth on screen. Loops shorter
/// than this simply draw at their start and their `finish`.
const REDRAW_STEP: u64 = 1 << 16;

/// A live `label [pct%] done/total  elapsed, ETA` meter on one stderr line.
pub(crate) struct Progress {
    label: String,
    total: u64,
    done: u64,
    /// Next `done` value at which to redraw.
    next: u64,
    start: Instant,
}

impl Progress {
    /// Start a meter for a scan of `total` items and draw its 0% line.
    pub(crate) fn new(label: impl Into<String>, total: u64) -> Progress {
        let p = Progress {
            label: label.into(),
            total,
            done: 0,
            next: 0,
            start: Instant::now(),
        };
        p.draw();
        p
    }

    /// Record one item done, redrawing at most once per [`REDRAW_STEP`].
    #[inline]
    pub(crate) fn inc(&mut self) {
        self.done += 1;
        if self.done >= self.next {
            self.next = self.done + REDRAW_STEP;
            self.draw();
        }
    }

    /// Record `n` items done at once (a parallel stage's batch), redrawing at
    /// most once per [`REDRAW_STEP`].
    #[inline]
    pub(crate) fn add(&mut self, n: u64) {
        self.done += n;
        if self.done >= self.next {
            self.next = self.done + REDRAW_STEP;
            self.draw();
        }
    }

    fn draw(&self) {
        let pct = (self.done * 100).checked_div(self.total).unwrap_or(100);
        let elapsed = self.start.elapsed().as_secs_f64();
        // ETA extrapolates the average rate so far; meaningless until something is
        // done, so hold it at zero for the opening frame.
        let eta = if self.done == 0 || self.total == 0 {
            0.0
        } else {
            elapsed * (self.total - self.done) as f64 / self.done as f64
        };
        let mut err = std::io::stderr().lock();
        // Trailing spaces clear a longer previous frame; `\r` keeps us on one line.
        let _ = write!(
            err,
            "\r{:<28} [{pct:>3}%] {}/{}  {} elapsed, {} ETA   ",
            self.label,
            self.done,
            self.total,
            hms(elapsed),
            hms(eta),
        );
        let _ = err.flush();
    }

    /// Snap to 100% and drop to a fresh line, so the next stage's first line
    /// starts clean instead of on top of the meter.
    pub(crate) fn finish(mut self) {
        self.done = self.total;
        self.draw();
        let _ = writeln!(std::io::stderr().lock());
    }
}

/// Whole seconds as `HhMMmSSs`, dropping units above the largest non-zero one.
/// Shared with `pbf.rs` so every live meter draws the one standard shape.
pub(crate) fn hms(secs: f64) -> String {
    let s = secs.max(0.0) as u64;
    let (h, m, s) = (s / 3600, (s % 3600) / 60, s % 60);
    if h > 0 {
        format!("{h}h{m:02}m{s:02}s")
    } else if m > 0 {
        format!("{m}m{s:02}s")
    } else {
        format!("{s}s")
    }
}
