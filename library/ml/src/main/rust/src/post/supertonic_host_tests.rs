//! Host-side Supertonic tests: `time_shifts` and `Conditioning::read`
//! over a synthetic sampler file whose host tensors are known, and
//! the emoji ranges `normalise` strips. With every MLP weight zero,
//! each block's shift is exactly its linear's bias, so the whole
//! timestep chain is checked against a hand-stated expectation.

use super::{normalise, time_shifts, to_ids, Conditioning, INDEXER_ENTRIES};
use crate::nets::supertonic_sampler as net;
use crate::weights::{graph, write_mixed, Fixture, Weights};

/// A sampler-shaped file: every tensor present, the host ones sized
/// as the code expects, everything else a one-element placeholder.
fn sampler_weights() -> Weights {
    let fixtures: Vec<Fixture> = (0..net::TENSORS)
        .map(|i| {
            let zeros = |n: usize| vec![0.0f32; n];
            match i {
                x if x == net::HOST_THETA => Fixture::F16(vec![32], vec![0.5; 32]),
                x if x == net::HOST_FREQUENCIES => Fixture::F16(vec![32], zeros(32)),
                x if x == net::HOST_MLP_IN => Fixture::F16(vec![256, 64], zeros(256 * 64)),
                x if x == net::HOST_MLP_IN + 1 => Fixture::F16(vec![256], zeros(256)),
                x if x == net::HOST_MLP_OUT => Fixture::F16(vec![64, 256], zeros(64 * 256)),
                x if x == net::HOST_MLP_OUT + 1 => Fixture::F16(vec![64], zeros(64)),
                x if x == net::HOST_TEXT_TOKEN => Fixture::F16(vec![256], vec![1.0; 256]),
                x if x == net::HOST_STYLE_TOKEN => Fixture::F16(vec![256, 50], vec![0.25; 256 * 50]),
                x if x == net::HOST_KEYS_CONDITIONAL => {
                    Fixture::F16(vec![1024, 50], vec![0.5; 1024 * 50])
                }
                x if x == net::HOST_KEYS_UNCONDITIONAL => {
                    Fixture::F16(vec![1024, 50], vec![-0.5; 1024 * 50])
                }
                x if (net::HOST_TIME_LINEARS..net::HOST_TIME_LINEARS + 8).contains(&x) => {
                    let block = (x - net::HOST_TIME_LINEARS) / 2;
                    if (x - net::HOST_TIME_LINEARS) % 2 == 0 {
                        Fixture::F16(vec![512, 64], zeros(512 * 64))
                    } else {
                        Fixture::F16(vec![512], vec![(block + 1) as f32; 512])
                    }
                }
                _ => Fixture::F16(vec![1], vec![0.0]),
            }
        })
        .collect();
    let blob = write_mixed(graph::SUPERTONIC_VE, &fixtures);
    Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the synthetic sampler parses")
}

#[test]
fn time_shifts_are_the_block_biases_when_the_mlp_is_zero() {
    let weights = sampler_weights();
    let shifts = time_shifts(weights.reader(), 3, 16).expect("shifts");
    assert_eq!(shifts.len(), 4 * 512);
    for block in 0..4 {
        let want = (block + 1) as f32;
        assert!(
            shifts[block * 512..(block + 1) * 512].iter().all(|&v| (v - want).abs() < 1e-3),
            "block {block} should be its bias {want} throughout"
        );
    }
    // No steps is a refusal, checked before any tensor is read.
    assert!(time_shifts(weights.reader(), 0, 0).is_err());
}

#[test]
fn conditioning_reads_theta_keys_token_style_and_every_step() {
    let weights = sampler_weights();
    let conditioning = Conditioning::read(weights.reader()).expect("conditioning");
    assert_eq!(conditioning.theta, vec![0.5; 32]);
    assert_eq!(conditioning.shifts.len(), 16, "one shift set per step");
    assert!(conditioning.shifts.iter().all(|s| s.len() == 4 * 512));
    assert!(conditioning.conditional_keys.iter().all(|&v| v == 0.5));
    assert!(conditioning.unconditional_keys.iter().all(|&v| v == -0.5));
    assert_eq!(conditioning.text_token, vec![1.0; 256]);
    assert!(conditioning.unconditional_style.iter().all(|&v| v == 0.25));
}

#[test]
fn normalise_strips_the_remaining_emoji_ranges() {
    // U+1F300 (symbols/pictographs) and a regional indicator (flags).
    assert!(!normalise("a🌀b").contains('🌀'));
    assert!(!normalise("a🇦b").contains('🇦'));
    // Ordinary letters and digits survive.
    assert!(normalise("abc 123").contains("abc"));
}

#[test]
fn astral_characters_are_dropped_by_the_indexer_not_mapped() {
    // A table mapping only 'a' (to token 5); everything else is -1.
    let mut indexer = vec![0xFFu8; INDEXER_ENTRIES * 2];
    indexer[b'a' as usize * 2..b'a' as usize * 2 + 2].copy_from_slice(&5i16.to_le_bytes());
    // U+20000 (a CJK extension, not an emoji) survives normalise and
    // then falls off the end of the BMP-sized table.
    let ids = to_ids(&indexer, "a\u{20000}", "en").expect("ids");
    assert!(ids.contains(&5), "the mapped letter is present: {ids:?}");
    // And text with nothing else is refused rather than synthesised.
    let err = to_ids(&indexer, "\u{20000}", "en").expect_err("nothing maps");
    assert!(err.contains("nothing in this text"), "{err}");
    // A malformed language code is refused before any of that.
    assert!(to_ids(&indexer, "a", "EN").is_err());
    assert!(to_ids(&indexer[..100], "a", "en").is_err(), "a short table");
}
