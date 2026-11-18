//! Gemma 4 pass-shape refusal tests: cache tiers, trace depth, and
//! prefill extents, all of which fire before a single tensor is read.

use super::{build, Mode, CONTEXT_TIERS, LAYERS};
use crate::nets::tests::Shapes;

#[test]
fn a_cache_length_that_is_no_tier_is_refused() {
    let err = build(&Shapes::new(0), Mode::DecodeStep.at(1000)).expect_err("not a tier");
    assert!(err.contains("a cache of 1000 positions"), "{err}");
    // Every real tier is admitted (the build then fails on the empty
    // source, which is a different refusal).
    for tier in CONTEXT_TIERS {
        let err = build(&Shapes::new(0), Mode::DecodeStep.at(tier)).expect_err("no tensors");
        assert!(!err.contains("not one of the tiers"), "tier {tier}: {err}");
    }
}

#[test]
fn trace_and_prefill_extents_are_refused() {
    let context = CONTEXT_TIERS[0];
    let err = build(&Shapes::new(0), Mode::Trace { layers: LAYERS + 1 }.at(context))
        .expect_err("trace too deep");
    assert!(err.contains("a trace of"), "{err}");
    let err = build(&Shapes::new(0), Mode::Prefill { tokens: 0 }.at(context))
        .expect_err("empty prefill");
    assert!(err.contains("a prefill of no positions"), "{err}");
    let err = build(&Shapes::new(0), Mode::Prefill { tokens: context + 1 }.at(context))
        .expect_err("prefill past the cache");
    assert!(err.contains("past a"), "{err}");
    let err = build(&Shapes::new(0), Mode::PrefillFresh { tokens: 0 }.at(context))
        .expect_err("empty fresh prefill");
    assert!(err.contains("a prefill of no positions"), "{err}");
}
