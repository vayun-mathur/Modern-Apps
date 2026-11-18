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

#[test]
fn exported_kernel_shapes_swap_the_first_two_axes() {
    assert_eq!(as_exported(&[1536, 768, 1, 1]), vec![768, 1536]);
    assert_eq!(as_exported(&[768]), vec![768]);
    assert_eq!(as_exported(&[3, 3]), vec![3, 3]);
}

#[test]
fn an_image_grid_scales_to_the_budget_and_rounds_down() {
    // Budget 280: at most 2520 patches. 800x600 scales by ~1.16, then
    // each side rounds down to a multiple of 48: 912x672 px.
    let grid = Grid::for_image(800, 600, 280).expect("a grid");
    assert_eq!((grid.rows, grid.cols), (42, 57));
    assert_eq!(grid.patches(), 42 * 57);
    assert_eq!(grid.soft_tokens(), 266);
    assert_eq!(grid.pixels(), (912, 672));
    // A sliver keeps one 48px block on the short side and is capped
    // on the long one, landing exactly on the budget.
    let grid = Grid::for_image(100_000, 100, 70).expect("a sliver");
    assert_eq!((grid.rows, grid.cols), (3, 210));
    assert_eq!(grid.soft_tokens(), 70);
    let grid = Grid::for_image(100, 100_000, 70).expect("a sliver");
    assert_eq!((grid.rows, grid.cols), (210, 3));
}

#[test]
fn image_grid_refusals_name_the_fault() {
    assert!(Grid::for_image(800, 600, 71).expect_err("budget").contains("soft tokens"));
    assert!(Grid::for_image(0, 600, 280).expect_err("zero width").contains("0x600"));
    assert!(Grid::for_image(800, 0, 280).expect_err("zero height").contains("800x0"));
    assert!(Grid::new(0, 3).is_err(), "no rows");
    assert!(Grid::new(4, 3).is_err(), "rows not divisible by the pooling kernel");
    assert!(Grid::new(300, 300).is_err(), "past the largest budget's patch ceiling");
}

#[test]
fn prepare_sums_the_column_and_row_position_tables() {
    use crate::weights::{graph, write_mixed, Fixture, Weights};
    // Column table raw 1s, row table raw 2s, both scaled by 1.0, so
    // every position value is 1.0 + 2.0 = 3.0. All other tensors are
    // one-element placeholders; the two tables are the only real data.
    let mut fixtures: Vec<Fixture> = Vec::new();
    for i in 0..=(ROW_POSITIONS + 1) {
        if i == COLUMN_POSITIONS {
            fixtures.push(Fixture::I4(
                vec![POSITIONS, D_MODEL, 1, 1],
                vec![1i8; (POSITIONS * D_MODEL) as usize],
            ));
        } else if i == COLUMN_POSITIONS + 1 || i == ROW_POSITIONS + 1 {
            fixtures.push(Fixture::F16(
                vec![POSITIONS, D_MODEL / 32],
                vec![1.0; (POSITIONS * (D_MODEL / 32)) as usize],
            ));
        } else if i == ROW_POSITIONS {
            fixtures.push(Fixture::I4(
                vec![POSITIONS, D_MODEL, 1, 1],
                vec![2i8; (POSITIONS * D_MODEL) as usize],
            ));
        } else {
            fixtures.push(Fixture::F16(vec![1], vec![0.0]));
        }
    }
    let blob = write_mixed(graph::SUPERTONIC_VE, &fixtures);
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
    let grid = Grid::new(3, 3).expect("a grid");
    // Solid red: R byte 255, G and B zero.
    let pixels = vec![0x00FF_0000i32; 48 * 48];
    let [patchified, positions, angles] =
        prepare(&weights.reader(), grid, &pixels).expect("prepares");
    // 9 patches padded to 16 columns in every input.
    assert_eq!(patchified.len(), (D_MODEL * 16) as usize);
    assert_eq!(positions.len(), (D_MODEL * 16) as usize);
    assert_eq!(angles.len(), (HEAD_DIM * 16) as usize);
    // Channel-major with the pad after each channel's 9 real patches.
    assert!((patchified[0] - 1.0).abs() < 1e-3, "red channel of patch 0");
    assert!((patchified[16] + 1.0).abs() < 1e-3, "green channel of patch 0");
    assert!((positions[0] - 3.0).abs() < 1e-3, "first position value");
    assert!((positions[8] - 3.0).abs() < 1e-3, "last real patch of channel 0");
    assert_eq!(positions[9], 0.0, "the padding is zero");
    assert!(angles.iter().all(|v| v.is_finite()));
}
