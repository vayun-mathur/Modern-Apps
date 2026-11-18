//! Gemma 4 host-gather refusal tests: the vocabulary bound and the
//! soft-hidden length check, both of which fire before any table
//! read, so an empty file is a valid fixture.

use super::{gather, gather_soft, D_MODEL, VOCAB};
use crate::weights::Weights;

#[test]
fn gather_refuses_a_token_past_the_vocabulary() {
    let weights = Weights::from_data(Vec::new());
    let err = gather(&weights.reader(), VOCAB).expect_err("past vocab");
    assert!(err.contains("past the"), "{err}");
    // The largest valid token still refuses here, but on the missing
    // table rather than the bound — a different error entirely.
    let err = gather(&weights.reader(), VOCAB - 1).expect_err("no table");
    assert!(!err.contains("past the"), "{err}");
}

#[test]
fn gather_soft_refuses_a_hidden_state_of_the_wrong_width() {
    let weights = Weights::from_data(Vec::new());
    let err = gather_soft(&weights.reader(), &[1.0], 0).expect_err("wrong width");
    assert!(err.contains(&format!("not {D_MODEL}")), "{err}");
    let wide = vec![0.0f32; D_MODEL as usize + 1];
    assert!(gather_soft(&weights.reader(), &wide, 0).is_err(), "too wide is also refused");
}

#[test]
fn the_input_wrappers_delegate_to_the_same_refusals() {
    use super::{gather_input, gather_soft_input};
    let weights = Weights::from_data(Vec::new());
    // Unfused gather_input is gather itself: the vocabulary bound fires.
    let err = gather_input(&weights.reader(), VOCAB, false).expect_err("past vocab");
    assert!(err.contains("past the"), "{err}");
    // Unfused gather_soft_input checks the hidden width first.
    let err = gather_soft_input(&weights.reader(), &[1.0], 0, false).expect_err("wrong width");
    assert!(err.contains(&format!("not {D_MODEL}")), "{err}");
}

#[test]
fn row_gathering_admits_empty_and_zero_table_requests() {
    use crate::nets::gemma4_gather::{leading_layer_rows, token_rows};
    let weights = Weights::from_data(Vec::new());
    // No tokens: an empty block, and no table is ever consulted.
    assert!(token_rows(&weights.reader(), &[]).expect("empty").is_empty());
    // Zero tables requested: the block is allocated at full width
    // and left zeroed, again without a read.
    let block = leading_layer_rows(&weights.reader(), &[5, 5], 0).expect("zero tables");
    assert!(!block.is_empty());
    assert!(block.iter().all(|&v| v == 0.0));
}

#[test]
fn token_rows_refuses_a_token_past_the_vocabulary() {
    use crate::nets::gemma4_gather::{layer_rows, token_rows};
    let weights = Weights::from_data(Vec::new());
    let err = token_rows(&weights.reader(), &[VOCAB]).expect_err("past vocab");
    assert!(err.contains("past the"), "{err}");
    // No tokens at all: the per-layer gather is an empty block too.
    assert!(layer_rows(&weights.reader(), &[]).expect("empty").is_empty());
}

#[test]
fn leading_layer_rows_reads_only_the_tables_asked_for() {
    use super::embed;
    use crate::nets::gemma4_gather::leading_layer_rows;
    use crate::weights::{graph, write_mixed, Fixture, Weights};
    // Table 0 raw 1s at scale 1.0: token 3's row is all 1.0, placed
    // in the first table's slab; the other 34 slabs stay zero.
    let first = embed::table(0);
    let mut fixtures: Vec<Fixture> = Vec::new();
    for i in 0..=(first + 1) {
        if i == first {
            fixtures.push(Fixture::I4(
                vec![VOCAB, super::PER_LAYER],
                vec![1i8; (VOCAB * super::PER_LAYER) as usize],
            ));
        } else if i == first + 1 {
            fixtures.push(Fixture::F16(
                vec![VOCAB, super::PER_LAYER / 32],
                vec![1.0; (VOCAB * super::PER_LAYER / 32) as usize],
            ));
        } else {
            fixtures.push(Fixture::F16(vec![1], vec![0.0]));
        }
    }
    let blob = write_mixed(graph::GEMMA4_EMBED, &fixtures);
    let weights = Weights::parse(&blob, graph::GEMMA4_EMBED).expect("parses");
    let block = leading_layer_rows(&weights.reader(), &[3], 1).expect("one table");
    let wide = super::PER_LAYER as usize;
    assert_eq!(block.len(), wide * super::LAYERS);
    assert!(block[..wide].iter().all(|&v| (v - 1.0).abs() < 1e-3), "the read slab");
    assert!(block[wide..].iter().all(|&v| v == 0.0), "the unread slabs stay zero");
}
