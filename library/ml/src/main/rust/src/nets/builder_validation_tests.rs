//! Builder validation tests: every `fail` path in the phase-1 builder
//! methods, exercised with shapes that violate exactly one rule, plus
//! the raw-offset conv entry point the graph lowerer uses. A builder
//! that accepted any of these would emit a plan whose pushes disagree
//! with its shapes, so each case asserts `finish` refuses.

use super::tests::Shapes;
use super::{Act, Builder, Id, Shape};
use crate::weights::{graph, write_mixed, Fixture, Weights};

/// Run `record` on a fresh builder over one input of `shape`; return
/// the error `finish` reports.
fn fails(shape: Shape, record: impl FnOnce(&mut Builder, Id) -> Id) -> String {
    let source = Shapes::new(16);
    let mut builder = Builder::new(&source);
    let input = builder.input(shape);
    let out = record(&mut builder, input);
    builder.finish(&[out]).expect_err("the builder must refuse")
}

#[test]
fn elementwise_validations_refuse_bad_parameters() {
    // A standalone PReLU has no slope binding.
    assert!(fails(Shape::new(1, 1, 2), |b, x| b.activate(x, Act::PRelu(0))).contains("PRelu"));
    // A gated activation needs paired channels, and is not a PReLU either.
    assert!(fails(Shape::new(3, 1, 1), |b, x| b.gated_activate(x, Act::Relu)).contains("pair"));
    assert!(fails(Shape::new(2, 1, 1), |b, x| b.gated_activate(x, Act::PRelu(0))).contains("PRelu"));
    // Softcap and quantize need a positive constant.
    assert!(fails(Shape::new(1, 1, 1), |b, x| b.softcap(x, 0.0)).contains("softcap"));
    assert!(fails(Shape::new(1, 1, 1), |b, x| b.softcap(x, -1.0)).contains("softcap"));
    assert!(fails(Shape::new(1, 1, 1), |b, x| b.quantize(x, 0.0)).contains("quantize"));
}

#[test]
fn softmax_and_position_validations_refuse_bad_shapes() {
    // Softmax over an empty last axis, in all three modes.
    assert!(fails(Shape::new(1, 1, 0), |b, x| b.softmax(x)).contains("empty"));
    assert!(fails(Shape::new(1, 0, 0), |b, x| b.softmax_causal(x)).contains("empty"));
    assert!(fails(Shape::new(1, 1, 0), |b, x| b.softmax_prefix(x, false)).contains("empty"));
    // A causal map must be square.
    assert!(fails(Shape::new(1, 2, 3), |b, x| b.softmax_causal(x)).contains("attend"));
    // A key-bounded softmax needs 1..=w keys.
    assert!(fails(Shape::new(1, 1, 3), |b, x| b.softmax_keys(x, 0)).contains("keys"));
    assert!(fails(Shape::new(1, 1, 3), |b, x| b.softmax_keys(x, 4)).contains("keys"));
    // Cropping to nothing or past the end; padding below the width.
    assert!(fails(Shape::new(1, 1, 3), |b, x| b.crop_positions(x, 0)).contains("first"));
    assert!(fails(Shape::new(1, 1, 3), |b, x| b.crop_positions(x, 4)).contains("first"));
    assert!(fails(Shape::new(1, 1, 3), |b, x| b.pad_positions(x, 2)).contains("padded"));
    // A channel slice must be a non-empty in-range range.
    assert!(fails(Shape::new(3, 1, 1), |b, x| b.slice_channels(x, 2, 2)).contains("channels"));
    assert!(fails(Shape::new(3, 1, 1), |b, x| b.slice_channels(x, 0, 0)).contains("channels"));
    // A reshape may not change the element count.
    assert!(fails(Shape::new(1, 2, 2), |b, x| b.reshaped(x, Shape::new(1, 1, 3))).contains("reshaping"));
    // Max pooling needs even extents.
    assert!(fails(Shape::new(1, 3, 4), |b, x| b.max_pool_2x2(x)).contains("odd"));
}

#[test]
fn binary_and_concat_validations_refuse_mismatched_operands() {
    // Add of two different shapes (built with two inputs).
    let source = Shapes::new(16);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(1, 1, 2));
    let c = builder.input(Shape::new(1, 1, 3));
    let out = builder.add(a, c);
    assert!(builder.finish(&[out]).is_err());

    // mul_channel's gate must be Cx1x1.
    let source = Shapes::new(16);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(2, 1, 2));
    let c = builder.input(Shape::new(2, 1, 2));
    let out = builder.mul_channel(a, c);
    assert!(builder.finish(&[out]).is_err());

    // Concatenating nothing, and concatenating mismatched planes.
    assert!(fails(Shape::new(1, 1, 1), |b, _x| b.concat(&[])).contains("nothing"));
    let source = Shapes::new(16);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(1, 2, 2));
    let c = builder.input(Shape::new(1, 3, 2));
    let out = builder.concat(&[a, c]);
    assert!(builder.finish(&[out]).is_err());
    let source = Shapes::new(16);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(1, 1, 1));
    let out = builder.concat_positions(&[]);
    let _ = a;
    assert!(builder.finish(&[out]).is_err());
}

#[test]
fn cache_and_copy_validations_refuse_bad_targets() {
    // The row is not a whole number of cache positions.
    let source = Shapes::new(16);
    let mut builder = Builder::new(&source);
    let row = builder.input(Shape::new(3, 1, 1));
    let cache = builder.persistent(Shape::new(2, 1, 2));
    builder.cache_write(row, cache);
    assert!(builder.finish(&[cache]).is_err());
    // A cache must be [positions, 1, d_model].
    let source = Shapes::new(16);
    let mut builder = Builder::new(&source);
    let row = builder.input(Shape::new(2, 1, 1));
    let cache = builder.persistent(Shape::new(2, 2, 2));
    builder.cache_write(row, cache);
    assert!(builder.finish(&[cache]).is_err());
    // Copying into a non-persistent tensor, or a mismatched one.
    let source = Shapes::new(16);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(1, 1, 2));
    let dst = builder.input(Shape::new(1, 1, 2));
    builder.copy_into(a, dst);
    assert!(builder.finish(&[dst]).is_err());
}

#[test]
fn norm_and_attention_validations_refuse_bad_head_counts() {
    // Grouped rms norm needs the groups to divide the channels.
    assert!(fails(Shape::new(3, 1, 1), |b, x| b.rms_norm_grouped(x, 0, 0.0, 2)).contains("groups")
        || fails(Shape::new(3, 1, 1), |b, x| b.rms_norm_grouped(x, 0, 0.0, 2)).contains("split"));
    // Attention scores need the heads to divide the channels.
    let source = Shapes::new(16);
    let mut builder = Builder::new(&source);
    let q = builder.input(Shape::new(3, 1, 2));
    let k = builder.input(Shape::new(3, 1, 2));
    let out = builder.attn_scores(q, k, 2);
    assert!(builder.finish(&[out]).is_err());
    // Rotary needs a [d_model, 1, T] input whose channels split into heads.
    let source = Shapes::new(16);
    let mut builder = Builder::new(&source);
    let x = builder.input(Shape::new(3, 1, 2));
    let angles = builder.input(Shape::new(3, 1, 2));
    let out = builder.rotary(x, angles, 2);
    assert!(builder.finish(&[out]).is_err());
}

#[test]
fn conv_validations_refuse_bad_groups_and_prelu_quantisation() {
    // Groups must divide both channel counts.
    assert!(fails(Shape::new(4, 1, 1), |b, x| {
        b.conv(x, 0, 4, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 3, Act::None)
    })
    .contains("groups"));
    // A quantised conv cannot carry a PReLU.
    let err = fails(Shape::new(2, 1, 1), |b, x| {
        b.conv_int8(x, 0, 2, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::PRelu(0))
    });
    assert!(err.contains("PRelu"), "actual error: {err}");
}

#[test]
fn finish_refuses_a_pass_with_no_input_or_no_output() {
    let source = Shapes::new(16);
    let mut builder = Builder::new(&source);
    let input = builder.input(Shape::new(1, 1, 1));
    let _ = input;
    assert!(builder.finish(&[]).is_err(), "no output");
    let source = Shapes::new(16);
    let builder = Builder::new(&source);
    assert!(builder.finish(&[Id(0)]).is_err(), "no input");
}

#[test]
fn the_fp32_int4_flag_keeps_an_int4_conv_buildable() {
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![1, 2, 1, 1], vec![1, 1]),
            Fixture::F16(vec![1, 1], vec![1.0]),
            Fixture::F16(vec![1], vec![0.0]),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
    let mut builder = Builder::new(&weights);
    builder.fp32_int4();
    let input = builder.input(Shape::new(2, 1, 1));
    let out = builder.conv_int4(input, 0, 1, Act::None);
    let plan = builder.finish(&[out]).expect("the fp32 path builds");
    let got = crate::nets::reference::run(&plan, weights.data(), &[3.0, 4.0]).expect("runs");
    assert_eq!(got, vec![7.0]);
}

#[test]
fn conv_raw_takes_resolved_offsets_directly() {
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[Fixture::F16(vec![1, 1, 1, 1], vec![2.0]), Fixture::F16(vec![1], vec![0.5])],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
    let weight = weights.shaped(0, &[1, 1, 1, 1]).expect("kernel").elem_offset();
    let bias = weights.shaped(1, &[1]).expect("bias").elem_offset();
    let mut builder = Builder::new(&weights);
    let input = builder.input(Shape::new(1, 1, 1));
    let out = builder.conv_raw(input, weight, bias, u32::MAX, 1, Act::None, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, false);
    builder.host_tensor(0, &[1, 1, 1, 1]);
    builder.host_tensor(1, &[1]);
    let plan = builder.finish(&[out]).expect("builds");
    let got = crate::nets::reference::run(&plan, weights.data(), &[3.0]).expect("runs");
    assert_eq!(got, vec![6.5]);
}

/// Run `record` on a fresh builder over two inputs; return the error.
fn fails2(
    q: Shape,
    k: Shape,
    record: impl FnOnce(&mut Builder, Id, Id) -> Id,
) -> String {
    let source = Shapes::new(16);
    let mut builder = Builder::new(&source);
    let a = builder.input(q);
    let b = builder.input(k);
    let out = record(&mut builder, a, b);
    builder.finish(&[out]).expect_err("the builder must refuse")
}

use super::BandedScores;

#[test]
fn banded_attention_validations_refuse_bad_windows() {
    let valid = BandedScores { heads: 1, band: 2, table: 0, offsets: 3, scale: 1.0, cap: 30.0 };
    // Same sequence on both sides.
    assert!(fails2(Shape::new(4, 1, 3), Shape::new(2, 1, 3), |b, q, k| {
        b.attn_scores_banded(q, k, valid)
    }).contains("same"));
    assert!(fails2(Shape::new(4, 2, 3), Shape::new(4, 2, 3), |b, q, k| {
        b.attn_scores_banded(q, k, valid)
    }).contains("[d_model, 1, T]"));
    // Heads must divide the channels.
    assert!(fails2(Shape::new(5, 1, 3), Shape::new(5, 1, 3), |b, q, k| {
        b.attn_scores_banded(q, k, BandedScores { heads: 2, ..valid })
    }).contains("heads"));
    // The band is between one and the whole sequence.
    assert!(fails2(Shape::new(4, 1, 3), Shape::new(4, 1, 3), |b, q, k| {
        b.attn_scores_banded(q, k, BandedScores { band: 0, offsets: 1, ..valid })
    }).contains("band"));
    assert!(fails2(Shape::new(4, 1, 3), Shape::new(4, 1, 3), |b, q, k| {
        b.attn_scores_banded(q, k, BandedScores { band: 4, offsets: 5, ..valid })
    }).contains("band"));
    // Column j reads offset j + 1, so offsets must exceed the band.
    assert!(fails2(Shape::new(4, 1, 3), Shape::new(4, 1, 3), |b, q, k| {
        b.attn_scores_banded(q, k, BandedScores { offsets: 2, ..valid })
    }).contains("offset"));
    // The fused logit cap must be positive.
    assert!(fails2(Shape::new(4, 1, 3), Shape::new(4, 1, 3), |b, q, k| {
        b.attn_scores_banded(q, k, BandedScores { cap: 0.0, ..valid })
    }).contains("cap"));
    assert!(fails2(Shape::new(4, 1, 3), Shape::new(4, 1, 3), |b, q, k| {
        b.attn_scores_banded(q, k, BandedScores { cap: -2.0, ..valid })
    }).contains("cap"));
}

#[test]
fn attention_apply_validations_refuse_bad_operands() {
    // The value mix needs one row per query over the same sequence.
    assert!(fails2(Shape::new(2, 3, 3), Shape::new(4, 1, 4), |b, p, v| {
        b.attn_apply(p, v, 2)
    }).contains("match"));
    assert!(fails2(Shape::new(1, 2, 2), Shape::new(2, 2, 2), |b, p, v| {
        b.attn_apply(p, v, 1)
    }).contains("[d_model, 1, T]"));
    assert!(fails2(Shape::new(2, 2, 2), Shape::new(3, 1, 2), |b, p, v| {
        b.attn_apply(p, v, 2)
    }).contains("heads"));
    // The banded value mix reads its geometry off the probs shape.
    assert!(fails2(Shape::new(2, 2, 3), Shape::new(4, 1, 2), |b, p, v| {
        b.attn_apply_banded(p, v, 2, 4)
    }).contains("band"));
    assert!(fails2(Shape::new(2, 2, 3), Shape::new(4, 2, 2), |b, p, v| {
        b.attn_apply_banded(p, v, 2, 3)
    }).contains("value mix"));
    assert!(fails2(Shape::new(2, 1, 1), Shape::new(3, 1, 1), |b, p, v| {
        b.attn_apply_banded(p, v, 2, 1)
    }).contains("heads"));
    // The relative value mix needs a square map and a centred table.
    assert!(fails2(Shape::new(1, 2, 3), Shape::new(4, 1, 3), |b, p, v| {
        b.attn_apply_relative(p, v, 1, 0, 3)
    }).contains("same sequence"));
    assert!(fails2(Shape::new(1, 3, 3), Shape::new(4, 1, 3), |b, p, v| {
        b.attn_apply_relative(p, v, 1, 0, 2)
    }).contains("centre"));
    assert!(fails2(Shape::new(1, 3, 3), Shape::new(4, 1, 3), |b, p, v| {
        b.attn_apply_relative(p, v, 1, 0, 0)
    }).contains("centre"));
}

#[test]
fn cached_attention_validations_refuse_bad_geometry() {
    // A decode step's query is exactly one position.
    assert!(fails2(Shape::new(4, 1, 2), Shape::new(8, 1, 2), |b, q, c| {
        b.attn_scores_cached_grouped(q, c, 2, 1)
    }).contains("one query"));
    // The cache is [keys, 1, kv_heads * head_dim].
    assert!(fails2(Shape::new(4, 1, 1), Shape::new(8, 2, 2), |b, q, c| {
        b.attn_scores_cached_grouped(q, c, 2, 1)
    }).contains("cache is"));
    assert!(fails2(Shape::new(5, 1, 1), Shape::new(8, 1, 2), |b, q, c| {
        b.attn_scores_cached_grouped(q, c, 2, 1)
    }).contains("heads"));
    assert!(fails2(Shape::new(4, 1, 1), Shape::new(8, 1, 2), |b, q, c| {
        b.attn_scores_cached_grouped(q, c, 2, 3)
    }).contains("group into"));
    assert!(fails2(Shape::new(4, 1, 1), Shape::new(8, 1, 3), |b, q, c| {
        b.attn_scores_cached_grouped(q, c, 2, 1)
    }).contains("width should be"));
}

#[test]
fn quantised_convs_refuse_an_fp16_only_source_and_unsplittable_groups() {
    // The fp16 fixture's word view refuses: both quantised conv
    // builders surface that refusal at finish.
    let given = crate::nets::reference::Given::new(&[(vec![32], vec![0.0; 32])]).expect("given");
    let mut builder = Builder::new(&given);
    let x = builder.input(Shape::new(32, 1, 1));
    let out = builder.conv_int8(x, 0, 1, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
    let err = builder.finish(&[out]).expect_err("int8 over fp16");
    assert!(err.contains("holds no int8"), "{err}");

    let given = crate::nets::reference::Given::new(&[(vec![256], vec![0.0; 256])]).expect("given");
    let mut builder = Builder::new(&given);
    let x = builder.input(Shape::new(256, 1, 1));
    let out = builder.conv_q2k(x, 0, 1, Act::None);
    let err = builder.finish(&[out]).expect_err("q2k over fp16");
    assert!(err.contains("holds no int8"), "{err}");

    // 32 channels in 3 groups does not split.
    let source = Shapes::new(16);
    let mut builder = Builder::new(&source);
    let x = builder.input(Shape::new(32, 1, 1));
    let out = builder.conv_int8(x, 0, 1, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 3, Act::None);
    let err = builder.finish(&[out]).expect_err("unsplittable groups");
    assert!(err.contains("do not split into 3 groups"), "{err}");
}

#[test]
fn single_binding_accessors_require_exactly_one_binding() {
    // One input used as the only output: both accessors succeed.
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let only = builder.input(Shape::new(1, 1, 2));
    let plan = builder.finish(&[only]).expect("builds");
    assert_eq!(plan.input().expect("one input").shape, Shape::new(1, 1, 2));
    assert_eq!(plan.output().expect("one output").shape, Shape::new(1, 1, 2));

    // Two inputs and two outputs: both accessors name the real count.
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(1, 1, 2));
    let b = builder.input(Shape::new(1, 1, 2));
    let sum = builder.add(a, b);
    let plan = builder.finish(&[sum, a]).expect("builds");
    assert!(plan.input().expect_err("two inputs").contains("2 inputs, not one"));
    assert!(plan.output().expect_err("two outputs").contains("2 outputs, not one"));
}

#[test]
fn cached_apply_and_grouped_score_validations_refuse_bad_geometry() {
    // Probs not [heads, 1, keys].
    assert!(fails2(Shape::new(2, 2, 2), Shape::new(2, 1, 2), |b, p, c| {
        b.attn_apply_cached_grouped(p, c, 2, 2, false)
    }).contains("cached attention over probs"));
    // Key counts differ (cache channels c != probs width w).
    assert!(fails2(Shape::new(2, 1, 3), Shape::new(4, 1, 8), |b, p, c| {
        b.attn_apply_cached_grouped(p, c, 2, 2, false)
    }).contains("key counts differ"));
    // Query heads do not group into the KV heads.
    assert!(fails2(Shape::new(3, 1, 2), Shape::new(2, 1, 4), |b, p, c| {
        b.attn_apply_cached_grouped(p, c, 3, 2, false)
    }).contains("do not group into"));
    // Cache channels do not split across the KV heads.
    assert!(fails2(Shape::new(2, 1, 2), Shape::new(2, 1, 3), |b, p, c| {
        b.attn_apply_cached_grouped(p, c, 2, 2, false)
    }).contains("do not split into"));
    // Grouped score map: head grouping and head-dim agreement.
    assert!(fails2(Shape::new(4, 1, 2), Shape::new(4, 1, 2), |b, q, k| {
        b.attn_scores_grouped_prescaled(q, k, 3, 2)
    }).contains("do not group into"));
    assert!(fails2(Shape::new(4, 1, 2), Shape::new(6, 1, 2), |b, q, k| {
        b.attn_scores_grouped_prescaled(q, k, 2, 1)
    }).contains("attention over q"));
}

#[test]
fn copy_into_and_rotary_validations_refuse_bad_targets() {
    // Copying between different shapes.
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let src = builder.input(Shape::new(1, 1, 2));
    let dst = builder.persistent(Shape::new(1, 1, 3));
    builder.copy_into(src, dst);
    assert!(builder.finish(&[src]).expect_err("shape mismatch").contains("copying"));

    // Copying into a tensor that is not persistent.
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let src = builder.input(Shape::new(1, 1, 2));
    let dst = builder.input(Shape::new(1, 1, 2));
    builder.copy_into(src, dst);
    assert!(builder.finish(&[src]).expect_err("not persistent").contains("not persistent"));

    // Rotary over a non-sequence, over an unsplittable axis count,
    // and with an angle table of the wrong head width.
    assert!(fails2(Shape::new(4, 2, 2), Shape::new(4, 1, 2), |b, x, a| {
        b.rotary_axes(x, a, 1, 2)
    }).contains("a sequence is"));
    assert!(fails2(Shape::new(4, 1, 2), Shape::new(4, 1, 2), |b, x, a| {
        b.rotary_axes(x, a, 1, 3)
    }).contains("does not divide"));
    assert!(fails2(Shape::new(4, 1, 2), Shape::new(2, 1, 2), |b, x, a| {
        b.rotary_axes(x, a, 1, 2)
    }).contains("the table is"));
}

#[test]
fn a_broadcast_shift_folds_into_an_int8_conv_but_not_into_a_norm() {
    // conv_int8 -> add_channel: the per-channel shift folds into the
    // convolution's store, leaving one dispatch with shift set.
    let source = Shapes::new(3);
    let mut builder = Builder::new(&source);
    let x = builder.input(Shape::new(32, 1, 2));
    let shift = builder.input(Shape::new(2, 1, 1));
    let conv = builder.conv_int8(x, 0, 2, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
    let out = builder.add_channel(conv, shift);
    let plan = builder.finish(&[out]).expect("builds");
    let dispatches: Vec<_> = plan
        .ops
        .iter()
        .filter_map(|op| match op {
            crate::nets::Op::Dispatch { kind, push, .. } => Some((kind, push)),
            _ => None,
        })
        .collect();
    assert_eq!(dispatches.len(), 1, "the shift folded: {:?}", plan.ops);
    assert_ne!(dispatches[0].1.shift, crate::nets::NO_FUSE, "the folded shift is addressed");

    // rms_norm -> add_channel: a norm has no shift slot, so the fold
    // is refused and both dispatches survive.
    let source = Shapes::new(1);
    let mut builder = Builder::new(&source);
    let x = builder.input(Shape::new(2, 1, 2));
    let shift = builder.input(Shape::new(2, 1, 1));
    let norm = builder.rms_norm(x, 0, 0.0);
    let out = builder.add_channel(norm, shift);
    let plan = builder.finish(&[out]).expect("builds");
    let count = plan
        .ops
        .iter()
        .filter(|op| matches!(op, crate::nets::Op::Dispatch { .. }))
        .count();
    assert_eq!(count, 2, "the norm keeps its separate add: {:?}", plan.ops);
}
