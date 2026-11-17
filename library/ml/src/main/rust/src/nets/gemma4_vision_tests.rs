//! Host tests for the Gemma 4 vision plan builder (`gemma4_vision_part1.rs`).
//!
//! The builder was the least-covered non-generated logic in the crate: the
//! existing tests pin the grid arithmetic and the host inputs, but nothing
//! built a plan. These tests build both modes against the shape-only
//! `WeightSource` stub and pin the plan's interface — input/output shapes,
//! the padded sequence length and the trace contract — plus the error paths
//! a bad trace request must take.

use super::*;
use crate::nets::tests::Shapes;

#[test]
fn padded_positions_rounds_up_to_a_multiple_of_eight() {
    assert_eq!(padded_positions(0), 0);
    assert_eq!(padded_positions(1), 8);
    assert_eq!(padded_positions(8), 8);
    assert_eq!(padded_positions(9), 16);
    assert_eq!(padded_positions(36), 40);
    assert_eq!(padded_positions(64), 64);
}

#[test]
fn an_image_plan_pads_its_inputs_and_pools_to_one_soft_token() {
    // A 3x3 grid is 9 patches, padded to 16; pooling 3x3 gives one soft token.
    let grid = Grid::new(3, 3).expect("a grid");
    let source = Shapes::new(TENSORS);
    let plan = build(&source, Mode::Image(grid)).expect("the image plan builds");
    assert_eq!(plan.inputs.len(), INPUTS);
    for input in &plan.inputs[..2] {
        assert_eq!(input.shape, Shape::new(D_MODEL, 1, 16));
    }
    assert_eq!(plan.inputs[2].shape, Shape::new(HEAD_DIM, 1, 16));
    let output = plan.output().expect("one output");
    assert_eq!(output.shape, Shape::new(OUT_DIM, 1, 1));
    assert!(!plan.ops.is_empty(), "a plan with no ops runs nothing");
    assert!(plan.arena_elems > 0);
}

#[test]
fn an_image_plan_at_a_multiple_of_eight_needs_no_padding() {
    // 6x12 patches is 72, already a multiple of eight.
    let grid = Grid::new(6, 12).expect("a grid");
    let source = Shapes::new(TENSORS);
    let plan = build(&source, Mode::Image(grid)).expect("the image plan builds");
    assert_eq!(plan.inputs[0].shape, Shape::new(D_MODEL, 1, 72));
    // (6/3) x (12/3) pooled cells.
    assert_eq!(plan.output().expect("one output").shape, Shape::new(OUT_DIM, 1, 8));
}

#[test]
fn a_trace_stops_where_asked_and_reports_both_sides_of_the_pooling() {
    let grid = Grid::new(6, 12).expect("a grid");
    let source = Shapes::new(TENSORS);
    let plan = build(&source, Mode::Trace { grid, layers: 0 }).expect("a zero-layer trace");
    assert_eq!(plan.outputs.len(), 2, "hidden state and its pooling");
    assert_eq!(plan.outputs[0].shape, Shape::new(D_MODEL, 1, 72));
    assert_eq!(plan.outputs[1].shape, Shape::new(D_MODEL, 1, 8));
    // A full-depth trace is the whole tower, still with both outputs.
    let source = Shapes::new(TENSORS);
    let plan = build(&source, Mode::Trace { grid, layers: LAYERS }).expect("a full trace");
    assert_eq!(plan.outputs.len(), 2);
}

#[test]
fn a_trace_past_the_tower_is_refused() {
    let grid = Grid::new(6, 12).expect("a grid");
    let source = Shapes::new(TENSORS);
    let error = build(&source, Mode::Trace { grid, layers: LAYERS + 1 })
        .expect_err("a trace deeper than the tower");
    assert!(error.contains("layers"), "unexpected error: {error}");
}
