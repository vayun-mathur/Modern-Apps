//! Gemma 4 head-plan tests: the greedy variant appends the
//! on-device argmax to the sixteen chunk outputs, in both the
//! fp16 and int8 chunk passes.

use super::{build_plan, build_plan_greedy, HEAD_CHUNKS, TENSORS_WITH_HEAD, TENSORS_WITH_HEAD8};
use crate::nets::tests::Shapes;

#[test]
fn the_greedy_fp16_head_appends_one_argmax_output() {
    let plan = build_plan_greedy(&Shapes::new(TENSORS_WITH_HEAD)).expect("greedy builds");
    assert_eq!(plan.outputs.len(), HEAD_CHUNKS + 1, "chunks plus the winning id");
    let plain = build_plan(&Shapes::new(TENSORS_WITH_HEAD)).expect("builds");
    assert_eq!(plain.outputs.len(), HEAD_CHUNKS);
    // The greedy tail is a single extra output past the chunks.
    assert_eq!(plan.outputs[..HEAD_CHUNKS], plain.outputs[..]);
}

#[test]
fn the_greedy_int8_head_appends_one_argmax_output() {
    let plan = build_plan_greedy(&Shapes::new(TENSORS_WITH_HEAD8)).expect("greedy builds");
    assert_eq!(plan.outputs.len(), HEAD_CHUNKS + 1);
    let plain = build_plan(&Shapes::new(TENSORS_WITH_HEAD8)).expect("builds");
    assert_eq!(plain.outputs.len(), HEAD_CHUNKS);
}
