//! Tests for reference-interpreter ops that previously had no direct
//! fixture: clamp / mul-scalar / gated activation / softcap / quantize,
//! argmax + top-k, prefix softmax, cache write, the int4 (plain and
//! int8-dot) and Q2_K pointwise convolutions, position crop/pad, and
//! the interpreter's own input-validation errors.
//!
//! Every expectation is hand-computed from the op's definition, not
//! copied from the implementation's output.

use super::{run, run_multi, run_multi_at, Given};
use crate::nets::{Act, Builder, Shape};
use crate::weights::{graph, write_mixed, Fixture, Weights};

/// Build a one-input plan over explicit fp16 tensors and run it.
fn one(
    input_shape: Shape,
    input: &[f32],
    tensors: &[(Vec<u32>, Vec<f32>)],
    record: impl FnOnce(&mut Builder, crate::nets::Id) -> crate::nets::Id,
) -> Vec<f32> {
    let given = Given::new(tensors).expect("fixture tensors are consistent");
    let mut builder = Builder::new(&given);
    let first = builder.input(input_shape);
    let last = record(&mut builder, first);
    let plan = builder.finish(&[last]).expect("the fixture plan builds");
    run(&plan, given.data(), input).expect("the fixture plan runs")
}

fn close(got: &[f32], want: &[f32]) {
    assert_eq!(got.len(), want.len(), "length: got {got:?}, want {want:?}");
    for (g, w) in got.iter().zip(want) {
        assert!((g - w).abs() < 1e-3, "got {got:?}, want {want:?}");
    }
}

#[test]
fn clamp_uses_the_weight_pair_as_bounds() {
    let out = one(
        Shape::new(1, 1, 4),
        &[-5.0, -0.5, 1.5, 9.0],
        &[(vec![2], vec![-1.0, 2.0])],
        |b, x| b.clamp(x, 0),
    );
    close(&out, &[-1.0, -0.5, 1.5, 2.0]);
}

#[test]
fn mul_scalar_multiplies_by_the_weight_scalar() {
    let out = one(
        Shape::new(1, 1, 3),
        &[2.0, -1.0, 0.5],
        &[(vec![1], vec![3.0])],
        |b, x| b.mul_scalar(x, 0),
    );
    close(&out, &[6.0, -3.0, 1.5]);
}

#[test]
fn softcap_is_cap_times_tanh_of_x_over_cap() {
    let out = one(Shape::new(1, 1, 3), &[0.0, 2.0, -20.0], &[], |b, x| b.softcap(x, 2.0));
    // tanh(0)=0; tanh(1)=0.76159416; tanh(-10) saturates to -1.
    close(&out, &[0.0, 2.0 * 0.761_594_16, -2.0]);
}

#[test]
fn quantize_rounds_half_up_and_saturates_to_int8() {
    let out = one(Shape::new(1, 1, 4), &[0.3, -0.3, 100.0, -100.0], &[], |b, x| {
        b.quantize(x, 0.5)
    });
    // floor(x/scale + 0.5) * scale, clamped to [-128, 127] steps:
    // 0.6 -> 1 step, -0.6 -> -1 step (floor(-0.1) = -1), then saturation.
    close(&out, &[0.5, -0.5, 63.5, -64.0]);
}

#[test]
fn gated_activate_multiplies_the_activated_gate_by_the_up_half() {
    // Channels [gate | up]; ReLU zeroes the negative gate.
    let out = one(Shape::new(2, 1, 2), &[3.0, -2.0, 4.0, 5.0], &[], |b, x| {
        b.gated_activate(x, Act::Relu)
    });
    close(&out, &[12.0, 0.0]);
}

#[test]
fn argmax_keeps_the_first_of_tied_maxima() {
    let out = one(Shape::new(1, 1, 4), &[1.0, 5.0, 3.0, 5.0], &[], |b, x| b.argmax(x));
    // Output is the (lo, hi) lane pair of the winning index, 1.
    close(&out, &[1.0, 0.0]);
}

#[test]
fn top_k_orders_by_value_then_lower_index_and_pads_missing_ranks() {
    let out = one(Shape::new(1, 1, 5), &[3.0, 1.0, 4.0, 1.0, 5.0], &[], |b, x| b.top_k(x));
    assert_eq!(out.len(), 64 * 3);
    // (value, lo, hi) triples, highest first; the two 1.0s keep index order.
    close(&out[0..3], &[5.0, 4.0, 0.0]);
    close(&out[3..6], &[4.0, 2.0, 0.0]);
    close(&out[6..9], &[3.0, 0.0, 0.0]);
    close(&out[9..12], &[1.0, 1.0, 0.0]);
    close(&out[12..15], &[1.0, 3.0, 0.0]);
    // Ranks past the row length carry the missing-rank sentinel.
    close(&out[15..18], &[-65504.0, 2047.0, 2047.0]);
}

#[test]
fn prefix_softmax_normalises_only_the_attended_prefix() {
    let given = Given::new(&[]).expect("no tensors");
    let mut builder = Builder::new(&given);
    let first = builder.input(Shape::new(1, 1, 4));
    let last = builder.softmax_prefix(first, false);
    let plan = builder.finish(&[last]).expect("builds");
    // Prefix 0 attends to exactly one key, so it takes all the mass; the
    // untouched tail of the output stays at the arena's zero.
    let out = run_multi_at(&plan, given.data(), &[&[0.0, 0.0, 5.0, 5.0]], 0, 0).expect("runs");
    close(&out[0], &[1.0, 0.0, 0.0, 0.0]);
    // Prefix 1 over two equal logits splits evenly.
    let out = run_multi_at(&plan, given.data(), &[&[2.0, 2.0, 5.0, 5.0]], 1, 0).expect("runs");
    close(&out[0], &[0.5, 0.5, 0.0, 0.0]);
}

#[test]
fn crop_and_pad_positions_go_through_the_unit_pool() {
    let cropped = one(Shape::new(1, 1, 3), &[3.0, 5.0, 7.0], &[], |b, x| {
        b.crop_positions(x, 1)
    });
    close(&cropped, &[3.0]);
    let padded = one(Shape::new(1, 1, 2), &[3.0, 5.0], &[], |b, x| b.pad_positions(x, 4));
    close(&padded, &[3.0, 5.0, 0.0, 0.0]);
}

#[test]
fn an_int4_convolution_applies_per_block_scales_and_bias() {
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![1, 2, 1, 1], vec![2, -1]),
            Fixture::F16(vec![1, 1], vec![0.5]),
            Fixture::F16(vec![1], vec![1.0]),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(2, 1, 1));
    let last = builder.conv_int4(first, 0, 1, Act::None);
    let plan = builder.finish(&[last]).expect("builds");
    let out = run(&plan, weights.data(), &[3.0, 4.0]).expect("runs");
    // (3*2 + 4*(-1)) * 0.5 + 1 = 2.
    close(&out, &[2.0]);
}

#[test]
fn a_q2k_convolution_unpacks_the_superblock_affine_pair() {
    let mut kernel = vec![0u8; 84];
    kernel[0] = 0x21; // scale byte: lo = 1, hi = 2
    kernel[16] = 3; // first quant byte: lane 0 carries q = 3
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::Q2K(vec![1, 256], kernel),
            Fixture::F16(vec![2], vec![1.0, 0.5]), // (d, dmin)
            Fixture::F16(vec![1], vec![0.25]),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(256, 1, 1));
    let last = builder.conv_q2k(first, 0, 1, Act::None);
    let plan = builder.finish(&[last]).expect("builds");
    let mut input = vec![0.0f32; 256];
    input[0] = 2.0;
    let out = run(&plan, weights.data(), &input).expect("runs");
    // Only tap 0 is live: 2 * (d*d*q*lo - dmin*d*hi) + bias
    // = 2 * (1*3*1 - 0.5*1*2) + 0.25 = 4.25.
    close(&out, &[4.25]);
}

#[test]
fn the_int4_dot_path_quantises_the_input_per_block() {
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![1, 32, 1, 1], vec![1; 32]),
            Fixture::F16(vec![1, 1], vec![2.0]),
            Fixture::F16(vec![1], vec![0.0]),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(32, 1, 8));
    let last = builder.conv_int4(first, 0, 1, Act::None);
    let plan = builder.finish(&[last]).expect("builds");
    let out = run(&plan, weights.data(), &[1.0; 256]).expect("runs");
    // Per position: 32 taps of (127 * 1) summed, scaled by (1/127 * 2) = 64.
    assert_eq!(out.len(), 8);
    close(&out, &[64.0; 8]);
}

#[test]
fn cache_write_lands_the_row_at_the_prefix_position() {
    let given = Given::new(&[]).expect("no tensors");
    let mut builder = Builder::new(&given);
    let row = builder.input(Shape::new(2, 1, 1));
    let cache = builder.persistent(Shape::new(3, 1, 2));
    builder.cache_write(row, cache);
    let plan = builder.finish(&[cache]).expect("builds");
    // Prefix 1: the row occupies the middle position of the cache, the
    // rest stays zero. Position-major layout: [pos0, pos1, pos2].
    let out = run_multi_at(&plan, given.data(), &[&[7.0, 8.0]], 1, 0).expect("runs");
    close(&out[0], &[0.0, 0.0, 7.0, 8.0, 0.0, 0.0]);
}

#[test]
fn the_reference_rejects_mismatched_inputs_and_odd_weight_bytes() {
    let given = Given::new(&[]).expect("no tensors");
    let mut builder = Builder::new(&given);
    let first = builder.input(Shape::new(1, 1, 2));
    let last = builder.softcap(first, 1.0);
    let plan = builder.finish(&[last]).expect("builds");
    assert!(run_multi(&plan, given.data(), &[]).is_err(), "missing input slice");
    assert!(run(&plan, &[0u8], &[1.0, 2.0]).is_err(), "odd fp16 byte count");
    assert!(run(&plan, given.data(), &[1.0]).is_err(), "short input slice");
}

#[test]
fn a_standalone_activation_applies_the_function_elementwise() {
    let out = one(Shape::new(1, 1, 3), &[0.0, 2.0, -2.0], &[], |b, x| {
        b.activate(x, Act::Sigmoid)
    });
    close(&out, &[0.5, 0.880_797, 0.119_203]);
}

#[test]
fn a_multi_position_cache_write_transposes_into_the_cache() {
    let given = Given::new(&[]).expect("no tensors");
    let mut builder = Builder::new(&given);
    let row = builder.input(Shape::new(2, 1, 3));
    let cache = builder.persistent(Shape::new(4, 1, 2));
    builder.cache_write(row, cache);
    let plan = builder.finish(&[cache]).expect("builds");
    // Three positions written at prefix 1: position-major cache slots
    // 1..4 take (c0, c1) pairs from the channel-major row.
    let out = run_multi_at(&plan, given.data(), &[&[1.0, 2.0, 3.0, 4.0, 5.0, 6.0]], 1, 0)
        .expect("runs");
    close(&out[0], &[0.0, 0.0, 1.0, 4.0, 2.0, 5.0, 3.0, 6.0]);
    // At prefix 3 only the first of the three positions still fits;
    // the rest are dropped, matching the shader's bounds check.
    let out = run_multi_at(&plan, given.data(), &[&[1.0, 2.0, 3.0, 4.0, 5.0, 6.0]], 3, 0)
        .expect("runs");
    close(&out[0], &[0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 4.0]);
}

/// Build a one-op plan with `record`, then corrupt `param0_bits` on its
/// dispatch and confirm the reference (not the builder) refuses it.
fn mutated_param0(
    input_shape: Shape,
    input: &[f32],
    tensors: &[(Vec<u32>, Vec<f32>)],
    record: impl FnOnce(&mut Builder, crate::nets::Id) -> crate::nets::Id,
    bits: u32,
) -> String {
    let given = Given::new(tensors).expect("fixture tensors are consistent");
    let mut builder = Builder::new(&given);
    let first = builder.input(input_shape);
    let last = record(&mut builder, first);
    let mut plan = builder.finish(&[last]).expect("the fixture plan builds");
    for op in &mut plan.ops {
        if let crate::nets::Op::Dispatch { push, .. } = op {
            push.param0_bits = bits;
        }
    }
    run(&plan, given.data(), input).expect_err("the reference must refuse")
}

#[test]
fn the_reference_refuses_a_softcap_and_quantize_it_cannot_apply() {
    let err = mutated_param0(Shape::new(1, 1, 1), &[1.0], &[], |b, x| b.softcap(x, 2.0), 0);
    assert!(err.contains("a softcap of 0"), "{err}");
    let err = mutated_param0(
        Shape::new(1, 1, 1),
        &[1.0],
        &[],
        |b, x| b.quantize(x, 0.5),
        (-1.0f32).to_bits(),
    );
    assert!(err.contains("a quantize of -1"), "{err}");
}

#[test]
fn the_reference_refuses_a_banded_cap_and_bad_head_splits_from_a_plan() {
    // A banded score map whose cap arrives negative (builder refuses
    // this at build time; a hand-made plan must not slip it through).
    // The scale travels in param0, the cap in param1.
    let given = Given::new(&[(vec![1, 3, 2], vec![0.0; 6])]).expect("tensors");
    let mut builder = Builder::new(&given);
    let x = builder.input(Shape::new(2, 1, 2));
    let last = builder.attn_scores_banded(x, x, crate::nets::BandedScores {
        heads: 1, band: 2, table: 0, offsets: 3, scale: 1.0, cap: 30.0,
    });
    let mut plan = builder.finish(&[last]).expect("builds");
    for op in &mut plan.ops {
        if let crate::nets::Op::Dispatch { push, .. } = op {
            push.param1_bits = (-1.0f32).to_bits();
        }
    }
    let err = run(&plan, given.data(), &[1.0, 2.0, 3.0, 4.0]).expect_err("negative cap");
    assert!(err.contains("logit cap"), "{err}");

    // Head counts the builder would have caught, planted in a plan.
    let given = Given::new(&[]).expect("no tensors");
    let mut builder = Builder::new(&given);
    let q = builder.input(Shape::new(4, 1, 2));
    let k = builder.input(Shape::new(4, 1, 2));
    let last = builder.attn_scores(q, k, 1);
    let mut plan = builder.finish(&[last]).expect("builds");
    for op in &mut plan.ops {
        if let crate::nets::Op::Dispatch { push, .. } = op {
            push.group = 3;
        }
    }
    let err = run_multi(&plan, given.data(), &[&[1.0; 8], &[1.0; 8]]).expect_err("bad head split");
    assert!(err.contains("do not split into 3 heads"), "{err}");

    // A grouped convolution whose group count was zeroed.
    let given = Given::new(&[(vec![2, 2, 1, 1], vec![1.0, 0.0, 0.0, 1.0]), (vec![2], vec![0.0, 0.0])])
        .expect("tensors");
    let mut builder = Builder::new(&given);
    let first = builder.input(Shape::new(4, 1, 1));
    let last = builder.conv(first, 0, 2, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 2, Act::None);
    let mut plan = builder.finish(&[last]).expect("builds");
    for op in &mut plan.ops {
        if let crate::nets::Op::Dispatch { push, .. } = op {
            push.group = 0;
        }
    }
    let err = run(&plan, given.data(), &[1.0, 2.0, 3.0, 4.0]).expect_err("zero groups");
    assert!(err.contains("zero groups"), "{err}");
}

#[test]
fn a_given_fixture_refuses_values_that_do_not_match_its_dims() {
    let err = match Given::new(&[(vec![2, 2], vec![1.0])]) {
        Ok(_) => panic!("dims and values must disagree"),
        Err(e) => e,
    };
    assert!(err.contains("declares 4 values, got 1"), "{err}");
    // A shape query against a laid-out tensor of a different length.
    use crate::nets::WeightSource as _;
    let given = Given::new(&[(vec![2], vec![1.0, 2.0])]).expect("given");
    let err = given.shaped(0, &[3]).expect_err("shape mismatch");
    assert!(err.contains("holds 2 values"), "{err}");
    assert!(given.shaped(9, &[1]).is_err(), "past the fixture's tensors");
}
