//! Per-step frame timing: `Instant` deltas on the render thread, nothing else.
//!
//! The frame log used to say only how many draws and triangles the last frame submitted, so a
//! janky pan or tilt could not be attributed to a stage — upload drain, tile select, fence wait,
//! each record sub-pass, submit, present. This module times each of those steps separately, so
//! the per-frame report can say *which* one owns the tail.
//!
//! # Cost model
//!
//! Every frame pays one [`Instant::now`](std::time::Instant::now) pair per step and three integer
//! array stores in [`StepTimes::record`] — no allocation, no formatting — plus one report string
//! built in [`StepTimes::report`], which also resets the window. A slow UI poll reads the last
//! window. A slow UI poll reads the last frame's array through [`StepTimes::last_nanos`] for the
//! debug overlay; the render thread never blocks on it.
//!
//! # Clocks
//!
//! [`Instant`](std::time::Instant), never the camera's `time_seconds`: that clock wraps hourly
//! (see [`CLOCK_WRAP_NANOS`](crate::camera::CLOCK_WRAP_NANOS)) and a delta across a wrap would
//! read as an hour. `Instant` is monotonic and the whole chain runs on one thread, so a pair of
//! reads around a step is exact.
//!
//! Host-testable: no Vulkan, no JNI, pure integer math over injected samples.

/// One timed step of a frame, in the order the frame runs them.
///
/// Short names are the per-frame logcat labels; [`Step::ALL`] fixes the order the JNI getter and the
/// Kotlin overlay mirror, so adding a step means appending here *and* there (the overlay
/// size-checks the array and shows `n/a` on drift rather than mislabelling rows).
#[derive(Clone, Copy)]
pub(crate) enum Step {
    /// The whole JNI `render` call, entry to return.
    JniEntry,
    /// Draining finished worker tiles into uploads (bounded by `UPLOADS_PER_FRAME`).
    UploadDrain,
    /// Visible-tile select plus the fetch loop and resident-set retain.
    Select,
    /// Waiting on the frame slot's in-flight fence.
    FenceWait,
    /// Freeing retired buffers plus the scratch-ring reset.
    CollectRetired,
    /// Acquiring the next swapchain image.
    Acquire,
    /// The whole `record` (command-buffer build), entry to end.
    RecordTotal,
    /// The global symbol collision pre-pass plus the pick snapshot refresh.
    PlaceSymbols,
    /// The DEM ground-grid pass.
    RecordTerrain,
    /// The flat fill/line layer loop.
    RecordFlat,
    /// The road-carriageway ribbon pass.
    RecordCarriageways,
    /// The extruded-buildings pass.
    RecordBuildings,
    /// The deferred per-tile symbol draws.
    RecordSymbols,
    /// The live-traffic overlay pass.
    RecordTraffic,
    /// The lane-arrow batch builds and draws.
    RecordArrows,
    /// The region-mask stencil plus scrim.
    RecordRegion,
    /// The rail-lines network under the route.
    RecordRail,
    /// Markers, vehicles and the puck.
    RecordOverlays,
    /// The `queue_submit`.
    Submit,
    /// The `queue_present`.
    Present,
    /// A frame that skipped record + submit because nothing changed (see
    /// `Renderer::render`'s version check): the steady-state proof the retained
    /// path is working, not a gap in the report.
    EarlyOut,
    /// A symbol-placement pass served from the retained collision index rather
    /// than re-run (exact key hit or same-bucket drift hit).
    PlacementHit,
    /// Fill/line draws skipped by the frustum + fog cull.
    CullSkipped,
    /// Real `vkCmdBindPipeline` calls the frame issued, after `BindState`
    /// dedup (see `vulkan::renderer::bind_state`): the proof draw batching is
    /// working, not a gap in the report.
    PipelineBinds,
    /// Wanted-set keys dropped without fetching: drain-and-replace queue
    /// discards (see `bridge::frame`).
    FetchCancelled,
    /// Fresh `vkAllocateMemory` calls the upload drain issued this frame: the
    /// proof the pooled staging path (see `upload_pool`) is working. Reads zero
    /// once every resident upload comes out of the pool; any nonzero row names
    /// the leak to fix.
    UploadAllocs,
}

impl Step {
    /// Every step, in frame order. The JNI getter returns [`StepTimes::last_nanos`] in this
    /// order; the Kotlin overlay labels rows from its mirror of it.
    pub(crate) const ALL: [Step; 26] = [
        Step::JniEntry,
        Step::UploadDrain,
        Step::Select,
        Step::FenceWait,
        Step::CollectRetired,
        Step::Acquire,
        Step::RecordTotal,
        Step::PlaceSymbols,
        Step::RecordTerrain,
        Step::RecordFlat,
        Step::RecordCarriageways,
        Step::RecordBuildings,
        Step::RecordSymbols,
        Step::RecordTraffic,
        Step::RecordArrows,
        Step::RecordRegion,
        Step::RecordRail,
        Step::RecordOverlays,
        Step::Submit,
        Step::Present,
        Step::EarlyOut,
        Step::PlacementHit,
        Step::CullSkipped,
        Step::PipelineBinds,
        Step::FetchCancelled,
        Step::UploadAllocs,
    ];

    /// Steps timed per frame.
    pub(crate) const COUNT: usize = Self::ALL.len();

    /// The per-frame logcat label: short, fixed-width-ish, unambiguous at a glance.
    pub(crate) fn short_name(self) -> &'static str {
        match self {
            Step::JniEntry => "jni",
            Step::UploadDrain => "upl",
            Step::Select => "sel",
            Step::FenceWait => "fen",
            Step::CollectRetired => "ret",
            Step::Acquire => "acq",
            Step::RecordTotal => "rec",
            Step::PlaceSymbols => "plc",
            Step::RecordTerrain => "ter",
            Step::RecordFlat => "flt",
            Step::RecordCarriageways => "car",
            Step::RecordBuildings => "bld",
            Step::RecordSymbols => "sym",
            Step::RecordTraffic => "trf",
            Step::RecordArrows => "arw",
            Step::RecordRegion => "msk",
            Step::RecordRail => "ral",
            Step::RecordOverlays => "ovl",
            Step::Submit => "sub",
            Step::Present => "prs",
            Step::EarlyOut => "eout",
            Step::PlacementHit => "phit",
            Step::CullSkipped => "cull",
            Step::PipelineBinds => "bind",
            Step::FetchCancelled => "fcan",
            Step::UploadAllocs => "uall",
        }
    }

    /// Whether this step records a plain count rather than nanoseconds.
    ///
    /// Count steps (`EarlyOut`, `PlacementHit`, `CullSkipped`) ride the same `StepTimes`
    /// arrays and the same JNI/overlay row order as the timing steps, but [`report`](StepTimes::report)
    /// prints them raw (`sum/max`) instead of as ms, and the Kotlin overlay formats those
    /// rows as integers rather than milliseconds.
    pub(crate) fn is_count(self) -> bool {
        matches!(
            self,
            Step::EarlyOut
                | Step::PlacementHit
                | Step::CullSkipped
                | Step::PipelineBinds
                | Step::FetchCancelled
                | Step::UploadAllocs
        )
    }
}

/// The per-step timing state for one renderer.
///
/// Three fixed arrays plus the window length: the last frame's steps (for the JNI getter), and
/// the rolling sum/max the per-frame report prints. Fixed-size and allocation-free by construction —
/// [`record`](Self::record) is three integer stores, and only [`report`](Self::report) formats.
pub(crate) struct StepTimes {
    last: [u64; Step::COUNT],
    sum: [u64; Step::COUNT],
    max: [u64; Step::COUNT],
}

impl StepTimes {
    /// Empty: every step reads 0 until its first frame.
    pub(crate) fn new() -> Self {
        StepTimes {
            last: [0; Step::COUNT],
            sum: [0; Step::COUNT],
            max: [0; Step::COUNT],
        }
    }

    /// Time one step's sample: the index is the step's position in [`Step::ALL`].
    ///
    /// Integer adds/stores only — this runs twenty times a frame, so it must not allocate, format
    /// or branch on anything but the max.
    pub(crate) fn record(&mut self, step: Step, nanos: u64) {
        let at = step as usize;
        self.last[at] = nanos;
        self.sum[at] = self.sum[at].saturating_add(nanos);
        if nanos > self.max[at] {
            self.max[at] = nanos;
        }
    }

    /// The last frame's step times in [`Step::ALL`] order, for the JNI getter's copy.
    pub(crate) fn last_nanos(&self) -> &[u64; Step::COUNT] {
        &self.last
    }

    /// The `avg/max` report over the last `frames` frames, in ms with one decimal, then reset the
    /// window. Called every frame, so this is the one place that formats.
    ///
    /// Count steps (see [`Step::is_count`]) print as raw `sum/max` instead — a skipped-draw
    /// count is not a duration, and printing one as ms would lie by three orders of magnitude.
    ///
    /// `frames` is the window length (1 on the per-frame cadence), not read from here, so a frame
    /// that never ran a step still divides correctly.
    pub(crate) fn report(&mut self, frames: u32) -> String {
        let frames = frames.max(1) as u64;
        let mut out = String::from("steps");
        for step in Step::ALL {
            let at = step as usize;
            if step.is_count() {
                out.push_str(&format!(
                    " {} {}/{}",
                    step.short_name(),
                    self.sum[at],
                    self.max[at]
                ));
            } else {
                let avg_ms = self.sum[at] as f64 / frames as f64 / 1_000_000.0;
                let max_ms = self.max[at] as f64 / 1_000_000.0;
                out.push_str(&format!(" {} {avg_ms:.1}/{max_ms:.1}", step.short_name()));
            }
            self.sum[at] = 0;
            self.max[at] = 0;
        }
        out
    }
}

/// Nanos between two `Instant` reads, saturating rather than wrapping on absurd values.
pub(crate) fn nanos_since(start: std::time::Instant) -> u64 {
    u64::try_from(start.elapsed().as_nanos()).unwrap_or(u64::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_fresh_timer_reads_zero() {
        let t = StepTimes::new();
        assert!(t.last_nanos().iter().all(|&n| n == 0));
    }

    #[test]
    fn record_keeps_last_sum_and_max() {
        let mut t = StepTimes::new();
        t.record(Step::Select, 100);
        t.record(Step::Select, 300);
        assert_eq!(t.last_nanos()[Step::Select as usize], 300);
        // Avg over a 2-frame window is 200 ns; max is the worst sample.
        let line = t.report(2);
        assert!(
            line.contains("sel 0.0/0.0"),
            "200ns rounds to 0.0ms, got {line}"
        );
        // A millisecond-scale sample to check the decimals are real.
        t.record(Step::Present, 2_500_000);
        let line = t.report(1);
        assert!(line.contains("prs 2.5/2.5"), "got {line}");
    }

    #[test]
    fn report_resets_the_window() {
        let mut t = StepTimes::new();
        t.record(Step::Submit, 1_000_000);
        let _ = t.report(1);
        let line = t.report(1);
        assert!(
            line.contains("sub 0.0/0.0"),
            "the window must reset after a report, got {line}"
        );
        // But the last-frame array survives for the JNI getter.
        assert_eq!(t.last_nanos()[Step::Submit as usize], 1_000_000);
    }

    #[test]
    fn every_step_has_a_distinct_short_name() {
        let mut names: Vec<&str> = Step::ALL.iter().map(|s| s.short_name()).collect();
        names.sort_unstable();
        names.dedup();
        assert_eq!(
            names.len(),
            Step::COUNT,
            "short names must be unambiguous in logcat"
        );
    }

    #[test]
    fn step_discriminants_match_all_order() {
        // `record` indexes by `as usize`: the enum order and ALL order must agree.
        for (at, step) in Step::ALL.iter().enumerate() {
            assert_eq!(
                *step as usize,
                at,
                "Step::{:?} sits at the wrong index",
                step.short_name()
            );
        }
    }

    #[test]
    fn count_steps_report_raw_not_ms() {
        let mut t = StepTimes::new();
        t.record(Step::CullSkipped, 12);
        t.record(Step::EarlyOut, 1);
        t.record(Step::UploadAllocs, 0);
        let line = t.report(1);
        assert!(
            line.contains("cull 12/12"),
            "counts must print raw, got {line}"
        );
        assert!(
            line.contains("eout 1/1"),
            "counts must print raw, got {line}"
        );
        assert!(
            line.contains("uall 0/0"),
            "counts must print raw, got {line}"
        );
        // And timings still print as ms beside them.
        t.record(Step::Submit, 2_500_000);
        let line = t.report(1);
        assert!(line.contains("sub 2.5/2.5"), "got {line}");
    }
}
