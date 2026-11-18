//! LogMel tests: the default construction, the (initially zero)
//! magnitude spectrum, and silence in -> finite log-mel out.

use super::{frame_count, LogMel, BINS, MELS};

#[test]
fn a_default_logmel_starts_with_a_zero_magnitude() {
    let logmel = LogMel::default();
    assert_eq!(logmel.magnitude().len(), BINS);
    assert!(logmel.magnitude().iter().all(|&v| v == 0.0));
}

#[test]
fn silence_produces_finite_frames() {
    let mut logmel = LogMel::new();
    let samples = vec![0.0f32; 16_000];
    let mut out = Vec::new();
    let frames = logmel.spectrogram(&samples, &mut out);
    assert_eq!(frames, frame_count(samples.len()));
    assert_eq!(out.len(), frames * MELS);
    assert!(out.iter().all(|v| v.is_finite()), "silence must not produce NaN");
}
