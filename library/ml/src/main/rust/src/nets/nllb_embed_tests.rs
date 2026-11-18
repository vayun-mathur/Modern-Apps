//! NLLB host-embedding tests: the vocabulary refusal, an empty
//! sequence, and the sinusoidal position table's two halves.

use super::{embed_positions, sinusoid, VOCAB};
use crate::weights::Weights;

#[test]
fn embed_refuses_a_token_past_the_vocabulary_and_admits_no_tokens() {
    let weights = Weights::from_data(Vec::new());
    let err = embed_positions(weights.reader(), &[VOCAB], 0).expect_err("past vocab");
    assert!(err.contains("past the 256206-entry vocabulary"), "{err}");
    assert!(embed_positions(weights.reader(), &[], 0).expect("empty").is_empty());
}

#[test]
fn the_sinusoid_is_sines_then_cosines_over_the_same_angles() {
    // Channel 0 is the first sine at angle = position; channel 512
    // (D_MODEL / 2) is the first cosine of that same angle.
    let angle = 2.0f32;
    assert!((sinusoid(2, 0) - angle.sin()).abs() < 1e-6);
    assert!((sinusoid(2, 512) - angle.cos()).abs() < 1e-6);
    // Position 0 zeroes every sine and sets every cosine to 1.
    assert_eq!(sinusoid(0, 0), 0.0);
    assert_eq!(sinusoid(0, 511), 0.0);
    assert_eq!(sinusoid(0, 512), 1.0);
    assert_eq!(sinusoid(0, 1023), 1.0);
}

#[test]
fn an_embedded_token_is_scaled_and_positioned() {
    use super::D_MODEL;
    use crate::weights::{graph, write_mixed, Fixture, Weights};
    // Split 0's kernel: every tap 1 at scale 1.0, so a token embeds
    // as sqrt(1024) = 32 per channel, plus the sinusoid at its
    // position (past 0 + index 0 + 1 + padding 1 = position 2).
    let classes = super::split_classes(0);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![classes, D_MODEL, 1, 1], vec![1i8; (classes * D_MODEL) as usize]),
            Fixture::F16(vec![classes], vec![1.0; classes as usize]),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
    let out = embed_positions(weights.reader(), &[0], 0).expect("embeds");
    assert_eq!(out.len(), D_MODEL as usize);
    let (sin2, cos2) = (2.0f32.sin(), 2.0f32.cos());
    assert!((out[0] - (32.0 + sin2)).abs() < 1e-3, "first sine channel: {}", out[0]);
    assert!((out[512] - (32.0 + cos2)).abs() < 1e-3, "first cosine channel: {}", out[512]);
    // A step far enough into the sequence runs off the position table.
    let err = embed_positions(weights.reader(), &[0], super::MAX_POSITIONS).expect_err("too far");
    assert!(err.contains("past the model's table"), "{err}");
}
