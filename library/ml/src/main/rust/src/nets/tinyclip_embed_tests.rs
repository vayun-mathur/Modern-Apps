//! TinyCLIP text-side tests: the token-count and vocabulary refusals
//! in `embed_positions`, and one full embed over a synthetic file
//! (token row + position row), checked by hand.

use super::{embed_positions, CONTEXT, TOKENS, VOCAB, WIDTH};
use crate::weights::{graph, write_mixed, Fixture, Weights};

#[test]
fn embed_refuses_no_tokens_and_too_many_tokens() {
    let weights = Weights::from_data(Vec::new());
    let err = embed_positions(weights.reader(), &[]).expect_err("no tokens");
    assert!(err.contains("no tokens"), "{err}");
    let ids = vec![0u32; CONTEXT as usize + 1];
    let err = embed_positions(weights.reader(), &ids).expect_err("too many");
    assert!(err.contains("past the"), "{err}");
}

/// A file with the text stack's position table and token table.
/// Every other tensor is a one-element placeholder; the two tables
/// carry the values the embed sums.
fn text_file() -> Weights {
    let mut fixtures: Vec<Fixture> = Vec::new();
    for i in 0..=(TOKENS + 2) {
        if i == TOKENS {
            // int8 token rows of 2, scaled by 0.5 per row below.
            fixtures.push(Fixture::I8(
                vec![VOCAB, WIDTH, 1, 1],
                vec![2i8; (VOCAB * WIDTH) as usize],
            ));
        } else if i == TOKENS + 1 {
            fixtures.push(Fixture::F16(vec![VOCAB], vec![0.5; VOCAB as usize]));
        } else if i == TOKENS + 2 {
            // Position table: position 0 is all 1.0, the rest zero.
            let mut table = vec![0.0f32; (CONTEXT * WIDTH) as usize];
            table[..WIDTH as usize].fill(1.0);
            fixtures.push(Fixture::F16(vec![CONTEXT, WIDTH], table));
        } else {
            fixtures.push(Fixture::F16(vec![1], vec![0.0]));
        }
    }
    let blob = write_mixed(graph::SUPERTONIC_VE, &fixtures);
    Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the text file parses")
}

#[test]
fn embed_sums_the_token_row_and_the_position_row() {
    let weights = text_file();
    // Token 3 dequantises to 2 * 0.5 = 1.0 per channel; position 0
    // adds 1.0 more, so every channel of the only position is 2.0.
    let out = embed_positions(weights.reader(), &[3]).expect("embeds");
    assert_eq!(out.len(), WIDTH as usize);
    assert!(out.iter().all(|&v| (v - 2.0).abs() < 1e-3), "all channels 2.0");
    // Channel-major layout `[channel, position]`: for each channel,
    // position 0 sums token+position (2.0), position 1 token only (1.0).
    let out = embed_positions(weights.reader(), &[3, 3]).expect("embeds");
    assert_eq!(out.len(), 2 * WIDTH as usize);
    for channel in 0..WIDTH as usize {
        assert!((out[channel * 2] - 2.0).abs() < 1e-3, "channel {channel} pos 0");
        assert!((out[channel * 2 + 1] - 1.0).abs() < 1e-3, "channel {channel} pos 1");
    }
}

#[test]
fn embed_refuses_a_token_past_the_vocabulary() {
    let weights = text_file();
    let err = embed_positions(weights.reader(), &[VOCAB]).expect_err("past vocab");
    assert!(err.contains("past the"), "{err}");
}
