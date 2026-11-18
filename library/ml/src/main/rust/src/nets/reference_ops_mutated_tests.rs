//! Reference-interpreter tests for hand-corrupted plans and the
//! geometry refusals only a mutated push can reach, plus the fused
//! residual stores. Split from `reference_ops_tests.rs` to keep
//! both files under the repository's line limit.

use super::{run, run_multi, run_multi_at, Given};
use crate::nets::{Act, Builder, Shape};
use crate::weights::{graph, write_mixed, Fixture, Weights};

fn close(got: &[f32], want: &[f32]) {
    assert_eq!(got.len(), want.len(), "length: got {got:?}, want {want:?}");
    for (g, w) in got.iter().zip(want) {
        assert!((g - w).abs() < 1e-3, "got {got:?}, want {want:?}");
    }
}

#[test]
fn the_reference_refuses_an_attention_with_no_head_dimension() {
    // Channels zeroed under a head count: the split gives a head
    // dimension of zero, which the helper refuses by name.
    let err = mutated(&[], &[&[1.0; 8], &[1.0; 8]], |b| {
        let q = b.input(Shape::new(4, 1, 2));
        let k = b.input(Shape::new(4, 1, 2));
        b.attn_scores(q, k, 1)
    }, |p| p.in_c = 0).expect_err("no head dimension");
    assert!(err.contains("heads for 0 channels"), "{err}");
    // A bilinear resize whose input width was zeroed.
    let err = mutated(&[], &[&[1.0, 2.0, 3.0, 4.0]], |b| {
        let x = b.input(Shape::new(1, 2, 2));
        b.resize_to(x, 2, 2)
    }, |p| p.in_w = 0).expect_err("zero extent");
    assert!(err.contains("a resize of a zero extent"), "{err}");
    // A banded score map whose key extent was zeroed: the first
    // in-band key is already past it.
    let err = mutated(
        &[(vec![1, 3, 2], vec![0.0; 6])],
        &[&[1.0, 2.0, 3.0, 4.0]],
        |b| {
            let x = b.input(Shape::new(2, 1, 2));
            b.attn_scores_banded(x, x, crate::nets::BandedScores {
                heads: 1, band: 2, table: 0, offsets: 3, scale: 1.0, cap: 30.0,
            })
        },
        |p| p.in_w = 0,
    ).expect_err("key past the extent");
    assert!(err.contains("right context is zero"), "{err}");
}

#[test]
fn the_reference_refuses_empty_softmax_axes_and_norm_planes() {
    // A softmax whose row axis was zeroed.
    let err = mutated(&[], &[&[1.0, 2.0, 3.0]], |b| {
        let x = b.input(Shape::new(1, 1, 3));
        b.softmax(x)
    }, |p| p.out_w = 0).expect_err("empty axis");
    assert!(err.contains("a softmax over an empty axis"), "{err}");
    // A causal softmax with no queries.
    let err = mutated(&[], &[&[1.0; 4]], |b| {
        let x = b.input(Shape::new(1, 2, 2));
        b.softmax_causal(x)
    }, |p| p.out_h = 0).expect_err("no queries");
    assert!(err.contains("a causal softmax over no queries"), "{err}");
    // An instance norm over zero channels, and over an empty plane.
    let err = mutated(&[], &[&[1.0; 8]], |b| {
        let x = b.input(Shape::new(2, 2, 2));
        b.instance_norm(x, 0.0)
    }, |p| p.in_c = 0).expect_err("zero channels");
    assert!(err.contains("an instance norm over zero channels"), "{err}");
    let err = mutated(&[], &[&[1.0; 8]], |b| {
        let x = b.input(Shape::new(2, 2, 2));
        b.instance_norm(x, 0.0)
    }, |p| p.in_h = 0).expect_err("empty plane");
    assert!(err.contains("an instance norm over an empty plane"), "{err}");
}

#[test]
fn a_windowed_causal_softmax_zeroes_keys_before_the_window() {
    // kh = 2 planted on a causal map: query 2 attends keys 1..3
    // only, and key 0 is written as exactly zero.
    let got = mutated(&[], &[&[0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.0]], |b| {
        let x = b.input(Shape::new(1, 3, 3));
        b.softmax_causal(x)
    }, |p| p.kh = 2).expect("runs");
    let e = std::f32::consts::E;
    let (lo, hi) = (1.0 / (1.0 + e), e / (1.0 + e));
    close(&got[0], &[
        1.0, 0.0, 0.0,
        0.5, 0.5, 0.0,
        0.0, lo, hi,
    ]);
}

#[test]
fn a_single_key_cached_score_uses_the_plain_attended_range() {
    // One cached key: the score map is one wide, which is the
    // `dil_h == 0` form of the attended-range helper.
    let given = Given::new(&[]).expect("no tensors");
    let mut builder = Builder::new(&given);
    let q = builder.input(Shape::new(2, 1, 1));
    let cache = builder.input(Shape::new(1, 1, 2));
    let out = builder.attn_scores_cached(q, cache, 1);
    let plan = builder.finish(&[out]).expect("builds");
    let got = run_multi(&plan, given.data(), &[&[1.0, 2.0], &[5.0, 6.0]]).expect("runs");
    // (1*5 + 2*6) / sqrt(head_dim = 2), stored through fp16.
    assert_eq!(got[0].len(), 1);
    assert!((got[0][0] - 17.0 / 2f32.sqrt()).abs() < 5e-3, "got {:?}", got[0]);
}

#[test]
fn a_cache_write_past_the_end_writes_nothing() {
    // Single-position cache write at a prefix that is already the
    // cache's length: the shader drops it, and so does the reference.
    let given = Given::new(&[]).expect("no tensors");
    let mut builder = Builder::new(&given);
    let row = builder.input(Shape::new(2, 1, 1));
    let cache = builder.persistent(Shape::new(3, 1, 2));
    builder.cache_write(row, cache);
    let plan = builder.finish(&[cache]).expect("builds");
    let got = run_multi_at(&plan, given.data(), &[&[7.0, 8.0]], 3, 0).expect("runs");
    assert_eq!(got.len(), 1);
    assert!(got[0].iter().all(|&v| v == 0.0), "nothing was written: {:?}", got[0]);
}

#[test]
fn a_padded_int8_conv_reads_zero_outside_the_input() {
    // Centre-tap identity over channel 0 of 32, padded by one: the
    // border taps fall outside the input and are skipped.
    let mut kernel = vec![0i8; 32 * 9];
    kernel[4] = 1; // [ic 0][ky 1][kx 1] in (ic*kh + ky)*kw + kx order
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![1, 32, 3, 3], kernel),
            Fixture::F16(vec![1], vec![1.0]),
            Fixture::F16(vec![1], vec![0.0]),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
    let mut builder = Builder::new(&weights);
    let x = builder.input(Shape::new(32, 2, 2));
    let out = builder.conv_int8(x, 0, 1, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 1, Act::None);
    let plan = builder.finish(&[out]).expect("builds");
    let mut input = vec![0.0f32; 32 * 4];
    input[..4].copy_from_slice(&[1.0, 2.0, 3.0, 4.0]);
    let got = run(&plan, weights.data(), &input).expect("runs");
    // The output keeps the input's 2x2 extent; the centre tap is the
    // identity there, and every border tap reads outside and skips.
    close(&got, &[1.0, 2.0, 3.0, 4.0]);
}

/// Build a plan with `record`, corrupt every dispatch push with
/// `mutate`, and run it. For the reference's own validation arms,
/// which only a hand-made (or corrupt) plan can reach.
fn mutated(
    tensors: &[(Vec<u32>, Vec<f32>)],
    inputs: &[&[f32]],
    record: impl FnOnce(&mut Builder) -> crate::nets::Id,
    mutate: impl Fn(&mut crate::nets::Push),
) -> Result<Vec<Vec<f32>>, String> {
    let given = Given::new(tensors).expect("fixture tensors are consistent");
    let mut builder = Builder::new(&given);
    let last = record(&mut builder);
    let mut plan = builder.finish(&[last]).expect("the fixture plan builds");
    for op in &mut plan.ops {
        if let crate::nets::Op::Dispatch { push, .. } = op {
            mutate(push);
        }
    }
    run_multi(&plan, given.data(), inputs)
}

#[test]
fn the_reference_refuses_degenerate_geometry_in_a_plan() {
    // An average pool whose window was zeroed.
    let err = mutated(&[], &[&[1.0, 2.0, 3.0, 4.0]], |b| {
        let x = b.input(Shape::new(1, 2, 2));
        b.avg_pool(x, (2, 2), (1, 1))
    }, |p| p.kh = 0).expect_err("empty window");
    assert!(err.contains("an average pool over an empty window"), "{err}");
    // A nearest resize to a zero extent (the input doubles as `like`).
    let err = mutated(&[], &[&[1.0, 2.0, 3.0, 4.0]], |b| {
        let x = b.input(Shape::new(1, 2, 2));
        b.resize_nearest_like(x, x)
    }, |p| p.out_w = 0).expect_err("zero extent");
    assert!(err.contains("a resize of a zero extent"), "{err}");
    // A global pool over a zeroed input height.
    let err = mutated(&[], &[&[1.0; 8]], |b| {
        let x = b.input(Shape::new(2, 2, 2));
        b.global_avg_pool(x)
    }, |p| p.in_h = 0).expect_err("pool over nothing");
    assert!(err.contains("a global average pool over nothing"), "{err}");
    // An embedding table with no rows.
    let err = mutated(
        &[(vec![4, 2], vec![0.0; 8])],
        &[&[0.0, 1.0]],
        |b| {
            let ids = b.input(Shape::new(1, 1, 2));
            b.embed(ids, 0, 4, 2)
        },
        |p| p.in_w = 0,
    ).expect_err("no rows");
    assert!(err.contains("an embedding table with no rows"), "{err}");
    // A layer norm over zero channels.
    let err = mutated(
        &[(vec![2], vec![1.0, 1.0]), (vec![2], vec![0.0, 0.0])],
        &[&[1.0, 2.0, 3.0, 4.0]],
        |b| {
            let x = b.input(Shape::new(2, 1, 2));
            b.layer_norm(x, 0, 0.0)
        },
        |p| p.in_c = 0,
    ).expect_err("zero channels");
    assert!(err.contains("a layer norm over zero channels"), "{err}");
    // RMS norms: zero channels, an unsplittable group count, and a
    // fused quantize scale that is not positive.
    let gamma = &[(vec![2], vec![1.0, 1.0])];
    let one: &[&[f32]] = &[&[1.0, 2.0]];
    let err = mutated(gamma, one, |b| {
        let x = b.input(Shape::new(2, 1, 1));
        b.rms_norm(x, 0, 0.0)
    }, |p| p.in_c = 0).expect_err("rms zero channels");
    assert!(err.contains("an rms norm over zero channels"), "{err}");
    let err = mutated(gamma, one, |b| {
        let x = b.input(Shape::new(2, 1, 1));
        b.rms_norm(x, 0, 0.0)
    }, |p| p.group = 3).expect_err("rms groups");
    assert!(err.contains("2 channels do not split into 3 groups"), "{err}");
    let err = mutated(gamma, one, |b| {
        let x = b.input(Shape::new(2, 1, 1));
        b.rms_norm(x, 0, 0.0)
    }, |p| p.param0_bits = (-1.0f32).to_bits()).expect_err("fused quantize");
    assert!(err.contains("a fused quantize of -1"), "{err}");
    // Rotary heads that do not divide into the planted axis count.
    let rotary = |axes: u32| {
        mutated(&[], &[&[1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0], &[1.0; 8]], |b| {
            let x = b.input(Shape::new(4, 1, 2));
            let angles = b.input(Shape::new(4, 1, 2));
            b.rotary(x, angles, 1)
        }, |p| p.rope_axes = axes).expect_err("rotary axes")
    };
    assert!(rotary(3).contains("a rotary head of 4 channels in 3 blocks"));
    assert!(rotary(4).contains("a rotary block of 1 channels"));
}

#[test]
fn the_reference_counts_only_in_bounds_taps_for_a_strided_pool() {
    // Stride 2 planted on a 2x2 pool over a 2x3 input: the second
    // output's window hangs off the right edge, and the divisor is
    // still the whole window (the documented ONNX divergence).
    let got = mutated(&[], &[&[1.0, 2.0, 3.0, 4.0, 5.0, 6.0]], |b| {
        let x = b.input(Shape::new(1, 2, 3));
        b.avg_pool(x, (2, 2), (1, 1))
    }, |p| p.stride_w = 2).expect("runs");
    close(&got[0], &[3.0, 2.25]);
}

#[test]
fn an_rms_norm_adds_its_fused_residual_after_the_gamma() {
    // [1, 2] normalised at eps 0: inverse RMS is 1/sqrt(2.5); the
    // folded residual adds the input itself back, elementwise.
    let got = mutated(
        &[(vec![2], vec![1.0, 1.0])],
        &[&[1.0, 2.0]],
        |b| {
            let x = b.input(Shape::new(2, 1, 1));
            b.rms_norm(x, 0, 0.0)
        },
        |p| p.res = p.in0,
    ).expect("runs");
    let inv = 1.0 / 2.5f32.sqrt();
    close(&got[0], &[1.0 + inv, 2.0 + 2.0 * inv]);
}

#[test]
fn a_transposed_conv_adds_its_fused_residual_and_refuses_groups() {
    // Identity-scaled transpose (kernel 2.0, no bias): unfused it
    // would give [6, 10]; with the residual folded in (res = the
    // input's own arena slot) it gives [6+3, 10+5].
    let given = Given::new(&[(vec![1, 1, 1, 1], vec![2.0]), (vec![1], vec![0.0])]).expect("tensors");
    let mut builder = Builder::new(&given);
    let x = builder.input(Shape::new(1, 1, 2));
    let out = builder.conv_transpose(x, 0, 1, (1, 1), (1, 1), (0, 0, 0, 0), Act::None);
    let mut plan = builder.finish(&[out]).expect("builds");
    for op in &mut plan.ops {
        if let crate::nets::Op::Dispatch { push, .. } = op {
            push.res = push.in0;
        }
    }
    let got = run(&plan, given.data(), &[3.0, 5.0]).expect("runs fused");
    close(&got, &[9.0, 15.0]);

    // The same plan with a grouped transpose planted in it.
    let mut builder = Builder::new(&given);
    let x = builder.input(Shape::new(1, 1, 2));
    let out = builder.conv_transpose(x, 0, 1, (1, 1), (1, 1), (0, 0, 0, 0), Act::None);
    let mut plan = builder.finish(&[out]).expect("builds");
    for op in &mut plan.ops {
        if let crate::nets::Op::Dispatch { push, .. } = op {
            push.group = 2;
        }
    }
    let err = run(&plan, given.data(), &[3.0, 5.0]).expect_err("grouped transpose");
    assert!(err.contains("transposed convolution in 2 groups"), "{err}");
}

#[test]
fn a_fused_transposed_conv_skips_taps_that_do_not_land() {
    // 2x2 all-ones transpose at stride 1 over two inputs: half the
    // taps fall off the left/top edges and are skipped, and the
    // folded residual adds the input region back elementwise.
    let given = Given::new(&[(vec![1, 1, 2, 2], vec![1.0; 4]), (vec![1], vec![0.0])]).expect("tensors");
    let mut builder = Builder::new(&given);
    let x = builder.input(Shape::new(1, 1, 2));
    let out = builder.conv_transpose(x, 0, 1, (2, 2), (1, 1), (0, 0, 0, 0), Act::None);
    let mut plan = builder.finish(&[out]).expect("builds");
    for op in &mut plan.ops {
        if let crate::nets::Op::Dispatch { push, .. } = op {
            push.res = push.in0;
        }
    }
    let got = run(&plan, given.data(), &[3.0, 5.0]).expect("runs fused");
    // The output is 2x3 (both axes grow by the kernel extent).
    // Accumulators are [3, 8, 5] per row (edge taps skipped); the
    // residual reads the input base, which the first two stores have
    // already overwritten at indices 0 and 1 in this hand-made plan.
    close(&got, &[6.0, 13.0, 5.0, 3.0, 8.0, 5.0]);
}

#[test]
fn the_reference_refuses_group_counts_that_do_not_split_the_outputs() {
    // Four groups over two output channels (the input splits, the
    // output does not). A 3x3 kernel so the plan carries the spatial
    // Kind::Conv whose interpreter consults the group count.
    let spatial = &[(vec![2, 4, 3, 3], vec![0.0; 72]), (vec![2], vec![0.0, 0.0])];
    let input: &[f32] = &[1.0; 64];
    let err = mutated(spatial, &[input], |b| {
        let x = b.input(Shape::new(4, 4, 4));
        b.conv(x, 0, 2, (3, 3), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None)
    }, |p| p.group = 4).expect_err("output does not split");
    assert!(err.contains("do not split into 4 groups"), "{err}");
    // A group count over zero output channels.
    let err = mutated(spatial, &[input], |b| {
        let x = b.input(Shape::new(4, 4, 4));
        b.conv(x, 0, 2, (3, 3), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None)
    }, |p| p.out_c = 0).expect_err("no output channels");
    assert!(err.contains("groups for 0 output channels"), "{err}");
}
