//! Host tests for the CPU reference interpreter's weightless ops
//! (`reference_part11.rs`): max pooling and instance normalisation, run
//! end to end through `Builder` + `reference::run` against hand-computed
//! values. These ops had no direct coverage; a wrong window or a biased/
//! unbiased variance mix-up would only have shown up as a worse mask.

use super::*;
use crate::nets::tests::Shapes;
use crate::nets::{Builder, Shape};

#[test]
fn max_pool_takes_the_largest_of_each_2x2_window() {
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let input = builder.input(Shape::new(1, 4, 4));
    let pooled = builder.max_pool_2x2(input);
    let plan = builder.finish(&[pooled]).expect("the plan builds");
    assert_eq!(plan.output().expect("one output").shape, Shape::new(1, 2, 2));
    let values: Vec<f32> = (0..16).map(|v| v as f32).collect();
    let out = run(&plan, &[], &values).expect("the reference runs");
    // Windows over rows/cols (0..2, 2..4): maxes are the bottom-right corners.
    assert_eq!(out, vec![5.0, 7.0, 13.0, 15.0]);
}

#[test]
fn max_pool_is_per_channel() {
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let input = builder.input(Shape::new(2, 2, 2));
    let pooled = builder.max_pool_2x2(input);
    let plan = builder.finish(&[pooled]).expect("the plan builds");
    // Channel 0 peaks at 9, channel 1 (negatives) at -1.
    let out = run(&plan, &[], &[1.0, 9.0, 3.0, 4.0, -5.0, -1.0, -9.0, -2.0]).expect("runs");
    assert_eq!(out, vec![9.0, -1.0]);
}

#[test]
fn instance_norm_centres_and_scales_each_channel_independently() {
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let input = builder.input(Shape::new(2, 2, 2));
    let normed = builder.instance_norm(input, 0.0);
    let plan = builder.finish(&[normed]).expect("the plan builds");
    // Channel 0: [1,2,3,4], mean 2.5, biased variance 1.25.
    // Channel 1: [10,10,20,20], mean 15, biased variance 25.
    let out = run(&plan, &[], &[1.0, 2.0, 3.0, 4.0, 10.0, 10.0, 20.0, 20.0]).expect("runs");
    let want0: Vec<f32> = [1.0, 2.0, 3.0, 4.0]
        .iter()
        .map(|v| (v - 2.5) / 1.25f32.sqrt())
        .collect();
    for (got, want) in out[..4].iter().zip(&want0) {
        assert!((got - want).abs() < 1e-3, "channel 0: {got} vs {want}");
    }
    // Channel 1 normalises to exactly -1, -1, +1, +1 (through fp16, so near).
    for (got, want) in out[4..].iter().zip([-1.0, -1.0, 1.0, 1.0]) {
        assert!((got - want).abs() < 1e-3, "channel 1: {got} vs {want}");
    }
    // A constant channel has zero variance; with epsilon 0 the reference
    // divides by zero and stores inf/NaN — so use a live epsilon there.
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let input = builder.input(Shape::new(1, 1, 2));
    let normed = builder.instance_norm(input, 1.0);
    let plan = builder.finish(&[normed]).expect("the plan builds");
    let out = run(&plan, &[], &[7.0, 7.0]).expect("runs");
    assert_eq!(out, vec![0.0, 0.0], "centred zeros stay zero at any epsilon");
}
