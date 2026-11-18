//! Gate tests: the channel-count refusal, the probability mapping,
//! and the gate over real PCM (silence in, bounded probabilities out).

use super::{probability, Gate, Hysteresis, Latch, MusicGate};
use crate::microfrontend::{Config, NOW_PLAYING};

#[test]
fn the_probability_mapping_is_bounded_and_monotonic() {
    let lo = probability(i16::MIN);
    let mid = probability(0);
    let hi = probability(i16::MAX);
    assert!((0.0..=1.0).contains(&lo), "lo {lo}");
    assert!((0.0..=1.0).contains(&hi), "hi {hi}");
    assert!(lo <= mid && mid <= hi, "monotonic: {lo} {mid} {hi}");
    assert!(lo < hi, "the extremes differ");
}

#[test]
fn a_gate_with_the_wrong_channel_count_is_refused() {
    let config = Config { channels: NOW_PLAYING.channels + 1, ..NOW_PLAYING };
    let err = match MusicGate::with_config(config) {
        Ok(_) => panic!("wrong channels must refuse"),
        Err(e) => e,
    };
    assert!(err.contains("mel channels"), "{err}");
}

#[test]
fn silence_flows_through_the_gate_and_reset_clears_it() {
    let mut gate = MusicGate::new().expect("the gate builds");
    // Two seconds of silence: many hops, each completing a frame.
    let pcm = vec![0i16; 32_000];
    let mut out = Vec::new();
    gate.push(&pcm, &mut out);
    assert!(gate.frames() > 0, "frames were produced");
    for p in &out {
        assert!((0.0..=1.0).contains(p), "probability {p} in range");
    }
    // The latest-score accessor agrees with the pushed stream (both
    // may be silent during warm-up; scores must be probabilities).
    if let Some(score) = gate.score(&pcm) {
        assert!((0.0..=1.0).contains(&score), "score {score} in range");
    }
    gate.reset();
    assert_eq!(gate.frames(), 0, "reset discards the context");
    let _ = Gate::new();
}

#[test]
fn the_defaults_are_the_shipped_parameters() {
    assert_eq!(Gate::default().frames(), 0);
    let shipped = Hysteresis::default();
    assert_eq!(shipped.positive_threshold, Hysteresis::SHIPPED.positive_threshold);
    assert_eq!(shipped.negative_threshold, Hysteresis::SHIPPED.negative_threshold);
    assert_eq!(shipped.window_frames, Hysteresis::SHIPPED.window_frames);
}

#[test]
fn the_latch_fires_after_the_positive_run_and_releases_on_negatives() {
    // SHIPPED wants five positive frames before the event. A maximal
    // score smoothes to probability ~1 from the first frame (the
    // window mean is over the frames fed so far).
    let mut latch = Latch::new(Hysteresis::SHIPPED);
    for frame in 1..5 {
        let verdict = latch.push(i16::MAX);
        assert!(!verdict.positive_event, "frame {frame} is too early");
        assert!(!verdict.active);
    }
    let verdict = latch.push(i16::MAX);
    assert!(verdict.positive_event, "the fifth positive frame fires");
    assert!(verdict.active);
    assert!(verdict.smoothed > 0.99);
    // Negative scores drain the window; the state releases, but the
    // shipped parameters generate no negative event.
    let mut verdict = latch.push(i16::MIN);
    for _ in 0..4 {
        verdict = latch.push(i16::MIN);
    }
    assert!(!verdict.active, "the state released: {verdict:?}");
    assert!(!verdict.negative_event, "shipped parameters suppress it");
}

#[test]
fn squelch_suppresses_events_but_not_the_state_and_negative_events_fire_when_enabled() {
    // Squelched for the whole run: the state still latches, silently.
    let squelched = Hysteresis { initial_squelch_frames: 100, ..Hysteresis::SHIPPED };
    let mut latch = Latch::new(squelched);
    let mut verdict = latch.push(i16::MAX);
    for _ in 0..5 {
        verdict = latch.push(i16::MAX);
    }
    assert!(verdict.active);
    assert!(!verdict.positive_event, "squelched");

    // With negative events enabled and no squelch, release fires one.
    let audible = Hysteresis { generate_negative_events: true, ..Hysteresis::SHIPPED };
    let mut latch = Latch::new(audible);
    for _ in 0..5 {
        latch.push(i16::MAX);
    }
    // The event is on the release frame itself, not after it.
    let mut fired = false;
    let mut verdict = latch.push(i16::MIN);
    for _ in 0..5 {
        verdict = latch.push(i16::MIN);
        fired |= verdict.negative_event;
    }
    assert!(!verdict.active);
    assert!(fired, "the release event fired");
    latch.reset();
    assert!(!latch.push(i16::MIN).active);
}
