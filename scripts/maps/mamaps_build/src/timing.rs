//! Gapless phase/subphase wall-clock timer for the maps pipeline.
//!
//! One `PhaseTimer` covers one tool run (which is one pipeline phase). Time is
//! booked by *switching*: `switch("next")` stops the current subphase and starts
//! the next one on the same instant, so subphases tile the phase wall with no
//! gaps and no overlaps — the stop of every subphase is the start of the next.
//! The summed subphases therefore equal the phase wall up to the microseconds
//! the switch itself costs.
//!
//! The timer starts in an implicit `"other"` subphase, which also absorbs any
//! stretch the caller never names. Callers name each major loop/pass and never
//! need to account for setup/teardown explicitly: whatever is left in `"other"`
//! at [`PhaseTimer::finish`] is the honest overhead bucket, not a fudge factor.
//!
//! Results leave the process as one parse-safe machine line on **stdout only**:
//!
//! ```text
//! TIMING {"phase":"tiles","wall_s":123.456,"subphases":[{"name":"stage-a","s":12.345}]}
//! ```
//!
//! Live progress meters stay on stderr (see `progress.rs`), warnings are
//! `WARN: ...` on stderr, errors `ERROR: ...` on stderr. Nothing else on stdout
//! may start with `TIMING `, and `TIMING` lines never go to stderr, so the
//! orchestrator can split the streams without ambiguity.
//!
//! Contract, kept identical in every crate that carries a copy of this file
//! (copies, not a shared crate: `gtfs_ingest` builds with zero dependencies,
//! and `par.rs` / `progress.rs` already follow the same copy convention):
//!
//! * std only, no dependencies.
//! * One timer per process, driven from the orchestrating (main) thread only.
//!   Worker threads never touch it; coarse loop/pass boundaries are the ticks.
//! * `finish` merges repeat visits to the same subphase name (e.g. per-zoom
//!   `map`/`merge`/`encode`/`append`) by summing, preserving first-seen order,
//!   so fine-grained loops stay bounded instead of exploding the output.
//! * Durations print with millisecond precision (`{:.3}` seconds).
//! * Never one entry per feed / per tile / per feature: group those loops into
//!   one bounded subphase (e.g. `parse-feeds`, `encode`), the way per-zoom work
//!   folds into four totals here.

use std::time::Instant;

/// A single phase's gapless subphase chain. See the module docs.
pub struct PhaseTimer {
    phase: String,
    start: Instant,
    /// Name of the currently open subphase.
    current: String,
    /// When the currently open subphase started.
    current_start: Instant,
    /// Closed `(name, seconds)` segments, in close order.
    segments: Vec<(String, f64)>,
}

impl PhaseTimer {
    /// Start a phase clock. The clock opens in the `"other"` subphase, so any
    /// work before the first [`PhaseTimer::switch`] is booked as overhead.
    pub fn start(phase: &str) -> Self {
        let now = Instant::now();
        Self {
            phase: phase.to_string(),
            start: now,
            current: "other".to_string(),
            current_start: now,
            segments: Vec::new(),
        }
    }

    /// Close the current subphase and open `name`, on the same instant.
    /// Repeat visits to a name are merged (summed) at [`PhaseTimer::finish`].
    pub fn switch(&mut self, name: &str) {
        let secs = self.current_start.elapsed().as_secs_f64();
        let old = std::mem::replace(&mut self.current, name.to_string());
        self.segments.push((old, secs));
        self.current_start = Instant::now();
    }

    /// Close the current subphase, merge repeats, and emit the `TIMING` line.
    /// Consumes the timer so a phase can only be reported once.
    pub fn finish(mut self) {
        self.close_current();
        let wall = self.start.elapsed().as_secs_f64();
        let mut merged: Vec<(String, f64)> = Vec::with_capacity(self.segments.len());
        for (name, secs) in &self.segments {
            match merged.iter_mut().find(|(n, _)| n == name) {
                Some(slot) => slot.1 += secs,
                None => merged.push((name.clone(), *secs)),
            }
        }
        let mut subs = String::new();
        for (i, (name, secs)) in merged.iter().enumerate() {
            if i > 0 {
                subs.push(',');
            }
            subs.push_str("{\"name\":\"");
            esc_json(name, &mut subs);
            subs.push_str(&format!("\",\"s\":{secs:.3}}}"));
        }
        let mut line = String::from("TIMING {\"phase\":\"");
        esc_json(&self.phase, &mut line);
        line.push_str(&format!("\",\"wall_s\":{wall:.3},\"subphases\":[{subs}]}}"));
        println!("{line}");
    }

    fn close_current(&mut self) {
        let secs = self.current_start.elapsed().as_secs_f64();
        let name = std::mem::take(&mut self.current);
        self.segments.push((name, secs));
    }
}

/// Append `s` to `out` with JSON string escaping.
fn esc_json(s: &str, out: &mut String) {
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c => out.push(c),
        }
    }
}

/// Emit one per-zoom machine line on stdout, next to (not instead of) the
/// human-readable `zN ...` table row. Integer milliseconds — unlike the
/// 0.1 s-rounded text row, these close the sum exactly.
///
/// `map_ms` / `merge_ms` / `encode_ms` / `append_ms` use the same meaning as
/// the text table: per-zoom worker time in each tiling step.
#[allow(clippy::too_many_arguments)]
pub fn emit_zoom_row(
    zoom: u8,
    tiles: u64,
    features: u64,
    points: u64,
    dropped: u64,
    bytes: u64,
    map_ms: u64,
    merge_ms: u64,
    encode_ms: u64,
    append_ms: u64,
) {
    println!(
        "TIMING {{\"zoom\":{zoom},\"tiles\":{tiles},\"features\":{features},\
         \"points\":{points},\"dropped\":{dropped},\"bytes\":{bytes},\
         \"map_ms\":{map_ms},\"merge_ms\":{merge_ms},\
         \"encode_ms\":{encode_ms},\"append_ms\":{append_ms}}}"
    );
}
