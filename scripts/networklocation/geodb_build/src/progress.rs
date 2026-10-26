//! A stderr progress meter for the steps `osm_ingest` does not cover: materializing ways and
//! relations, and everything in `write` and `verify`. Draws the same
//! `label [pct%] done/total  elapsed, ETA` line as the extraction passes, and can be advanced
//! from worker threads, which the parallel column compression needs.

use std::io::Write;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

/// Advance this many items between redraws; drawing per item would dominate a tight loop.
const REDRAW_STEP: u64 = 1 << 16;

/// A live meter on one stderr line.
pub struct Progress {
    label: String,
    total: u64,
    done: AtomicU64,
    step: u64,
    start: Instant,
}

impl Progress {
    /// Start a meter over `total` items and draw its 0% line.
    pub fn new(label: impl Into<String>, total: u64) -> Progress {
        Progress::with_step(label, total, REDRAW_STEP)
    }

    /// A meter whose items are each slow (a compressed block, a dictionary), so every one
    /// redraws.
    pub fn coarse(label: impl Into<String>, total: u64) -> Progress {
        Progress::with_step(label, total, 1)
    }

    /// A step with no measurable progress, such as a single sort: shows the label and elapsed
    /// time while it runs, then 100% on [`Progress::finish`].
    pub fn stage(label: impl Into<String>) -> Progress {
        Progress::coarse(label, 1)
    }

    fn with_step(label: impl Into<String>, total: u64, step: u64) -> Progress {
        let p = Progress { label: label.into(), total, done: AtomicU64::new(0), step, start: Instant::now() };
        p.draw(0);
        p
    }

    /// Record one item done.
    #[inline]
    pub fn inc(&self) {
        self.add(1);
    }

    /// Record `n` items done. Safe to call from several threads at once.
    pub fn add(&self, n: u64) {
        let before = self.done.fetch_add(n, Ordering::Relaxed);
        let after = before + n;
        if before / self.step != after / self.step {
            self.draw(after);
        }
    }

    fn draw(&self, done: u64) {
        let done = done.min(self.total);
        let pct = (done * 100).checked_div(self.total).unwrap_or(100);
        let elapsed = self.start.elapsed().as_secs_f64();
        let eta = if done == 0 || self.total == 0 {
            0.0
        } else {
            elapsed * (self.total - done) as f64 / done as f64
        };
        let mut err = std::io::stderr().lock();
        let _ = write!(
            err,
            "\r{:<28} [{pct:>3}%] {done}/{}  {} elapsed, {} ETA   ",
            self.label,
            self.total,
            hms(elapsed),
            hms(eta),
        );
        let _ = err.flush();
    }

    /// Snap to 100% and move to a fresh line for the next step.
    pub fn finish(self) {
        self.draw(self.total);
        let _ = writeln!(std::io::stderr().lock());
    }
}

/// Whole seconds as `HhMMmSSs`, dropping units above the largest non-zero one.
fn hms(secs: f64) -> String {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_from_many_threads_add_up() {
        let p = Progress::new("test", 4 * 10_000);
        std::thread::scope(|s| {
            for _ in 0..4 {
                s.spawn(|| (0..10_000).for_each(|_| p.inc()));
            }
        });
        assert_eq!(p.done.load(Ordering::Relaxed), 40_000);
        p.finish();
    }

    #[test]
    fn durations_are_compact() {
        assert_eq!(hms(5.0), "5s");
        assert_eq!(hms(65.0), "1m05s");
        assert_eq!(hms(3725.0), "1h02m05s");
    }
}
