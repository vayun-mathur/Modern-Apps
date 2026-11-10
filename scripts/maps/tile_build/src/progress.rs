//! A one-line progress bar for the long tiling loops.
//!
//! The single standard meter shape (`label [ 42%] done/total  elapsed, ETA`,
//! carriage return, no newline until the end), shared with `osm_ingest`'s
//! passes so a build log reads consistently across crates. A planet layer
//! spends minutes to hours per zoom and every tiler was previously silent
//! for all of it.
//!
//! Single-threaded, so no atomics: the tiling loops are one thread each.
use std::io::Write;

/// Progress over one zoom's tile loop.
pub struct Progress {
    label: String,
    total: usize,
    done: usize,
    last_pct: usize,
    start: std::time::Instant,
    on: bool,
}

/// Whole seconds as `HhMMmSSs`, dropping units above the largest non-zero one.
/// Same shape as `osm_ingest::progress::hms`, duplicated because the two
/// crates share no code.
pub fn hms(s: f64) -> String {
    let s = s.max(0.0) as u64;
    if s < 60 {
        return format!("{s}s");
    }
    let m = s / 60;
    if m < 60 {
        return format!("{m}m{:02}s", s % 60);
    }
    format!("{}h{:02}m{:02}s", m / 60, m % 60, s % 60)
}

impl Progress {
    /// `unit` names what is being counted, because the number is not always the tile
    /// count the report ends up printing — see [`Progress::new`]'s callers.
    pub fn new(label: String, total: usize, unit: &str, on: bool) -> Progress {
        let p = Progress {
            label,
            total,
            done: 0,
            last_pct: usize::MAX,
            start: std::time::Instant::now(),
            on,
        };
        if p.on {
            p.draw(unit);
        }
        p
    }

    /// One unit done. Throttled to whole percent: a planet zoom has millions of tiles
    /// and a write per tile would cost more than the tiling.
    pub fn tick(&mut self, unit: &str) {
        self.done += 1;
        if !self.on || self.total == 0 {
            return;
        }
        let pct = self.done * 100 / self.total;
        if pct != self.last_pct {
            self.last_pct = pct;
            self.draw(unit);
        }
    }

    /// `n` units done at once, for loops that finish work in batches rather than one unit at a
    /// time. Draws at most once per call, and only when the whole percent moved -- the same
    /// throttle as [`tick`](Self::tick), so the meter reads the same.
    pub fn tick_n(&mut self, n: usize, unit: &str) {
        self.done += n;
        if !self.on || self.total == 0 {
            return;
        }
        let pct = self.done * 100 / self.total;
        if pct != self.last_pct {
            self.last_pct = pct;
            self.draw(unit);
        }
    }

    /// Snap to 100% and drop to a fresh line, so the next stage's first line
    /// starts clean instead of on top of the meter.
    pub fn finish(&mut self, unit: &str) {
        if self.on {
            self.done = self.total;
            self.draw(unit);
            eprintln!();
        }
    }

    fn draw(&self, unit: &str) {
        let elapsed = self.start.elapsed().as_secs_f64();
        let pct = if self.total == 0 {
            100
        } else {
            (self.done * 100 / self.total).min(100)
        };
        // ETA extrapolates the average rate so far; meaningless until something is
        // done, so hold it at zero for the opening frame.
        let eta = if self.done == 0 {
            0.0
        } else {
            elapsed * (self.total - self.done) as f64 / self.done as f64
        };
        eprint!(
            "\r{:<28} [{pct:>3}%] {}/{} {unit}  {} elapsed, ETA {}   ",
            self.label,
            self.done,
            self.total,
            hms(elapsed),
            hms(eta),
        );
        let _ = std::io::stderr().flush();
    }
}
