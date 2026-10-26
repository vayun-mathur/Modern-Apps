//! PP-OCRv5 mobile text recognition: a PP-LCNetV3 backbone into a small transformer.
//!
//! # The shape of it
//!
//! A line crop comes in at `3 x 48 x W`. Thirty-three convolutions reduce it to
//! `480 x 3 x W/8`, one average pool with an asymmetric window collapses the last three
//! rows and halves the width again, and what comes out is `480 x 1 x W/8` — a sequence of
//! `T = W/8` positions. Two pre-norm transformer blocks run over that, the result is
//! concatenated back onto the pooled features, and a final `1x1` emits
//! [`crate::post::ctc::LOGITS`] logits per position for a CTC decode.
//!
//! Height is **fixed** at [`HEIGHT`] rather than being a parameter, and that is not a
//! simplification. Four layers carry a vertical stride of 2 (48 → 24 → 12 → 6 → 3) and
//! the pool's kernel height is exactly 3, so the whole vertical path is a chain of
//! constants that only closes at 48. A different height would need a different pool.
//!
//! # It is folded, like detection
//!
//! Same export shape as `ppocr_det`, same `scripts/ml/ppocr_fold.py`: every convolution
//! arrives as `Conv → Mul(scalar) → Add(scalar) → HardSigmoid(1/6) → Mul(self) →
//! Mul(scalar) → Add(scalar)`, and all of the constant part folds into the weight and
//! bias ahead of time. So every layer here is a convolution with a fused activation, and
//! [`TENSORS`] is exactly twice the layer count.
//!
//! The recogniser adds three things detection did not have, and each is the reason a
//! pipeline exists:
//!
//! * **Swish**, `x * sigmoid(x)`, seven times — spelled `Mul(x, Sigmoid(x))` in the
//!   export and recognised by the fold the way `HardSwish` is. Not interchangeable with
//!   `HardSwish`, which this same graph also uses 31 times.
//! * **Layer norm over the channel axis**, five times, four at epsilon `1e-5` and the
//!   last at `1e-6`.
//! * **Attention**, twice, at [`D_MODEL`] 120 in [`HEADS`] 8.
//!
//! # Why there is no permute anywhere
//!
//! The sequence is `[d_model, 1, T]` — the layout the backbone already produces. The ncnn
//! conversion of this same model has to `Reshape` and `Permute` into `[T, d_model]` before
//! its `LayerNorm`, and back again afterwards, four times. Here both are no-ops and are
//! simply not transcribed:
//!
//! * Every projection — the eleven in the two blocks, plus the classifier — is a `1x1`
//!   convolution over channels.
//! * Splitting `d_model` into heads is free, because `[120, 1, T]` read as `[8, 15, T]`
//!   is the same bytes in the same order.
//!
//! See [`super::Kind::LayerNorm`] for the full argument and [`super::Kind::AttnScores`]
//! for what the attention pipelines do with it.
//!
//! # The output is raw logits, class-major
//!
//! The export ends in a `Softmax` over the 838 classes. That is **not** transcribed: the
//! output here is `[LOGITS, 1, T]`, unnormalised, and
//! [`crate::post::ctc::decode`] takes it from there.
//!
//! Two reasons. The layout is one: a softmax over classes in this layout reduces over
//! channels, and the result would still be class-major while a CTC decode wants one row
//! per timestep — so a transpose on the host is unavoidable either way. The other is that
//! the decode does not need the distribution at all. It needs the argmax, which a softmax
//! cannot change, and the winner's probability, which is `1 / sum(exp(x - max))` from the
//! raw logits directly. So the pass is not merely moved to the host, it is deleted.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Input height, in pixels. Fixed; see the module docs.
pub const HEIGHT: u32 = 48;

/// The input width must be a positive multiple of this.
///
/// Three stride-2 stages act on the width — the stem, one depthwise in the third block,
/// and the pool — so `T` is `W / 8`. A width that is not a multiple would make one of the
/// three floor, silently dropping the last column of the line.
pub const WIDTH_MULTIPLE: u32 = 8;

/// Channels the backbone ends on, which is also the sequence's width before projection.
pub const FEATURES: u32 = 480;

/// The transformer's width.
pub const D_MODEL: u32 = 120;

/// Attention heads, so `head_dim` is 15.
pub const HEADS: u32 = 8;

/// The feed-forward block's inner width.
pub const FFN: u32 = 240;

/// Logits per timestep, which is the CTC label space and not a free choice.
pub const LOGITS: u32 = crate::post::ctc::LOGITS as u32;

/// Tensors the `.maml` must hold: 56 folded layers, each a weight and a bias.
///
/// 33 backbone convolutions (of which 4 are the two squeeze-excite blocks' squeeze and
/// expand), 2 into the sequence, 8 per transformer block, the final layer norm, and 4 in
/// the head including the classifier — `(33 + 2 + 16 + 1 + 4) * 2`.
pub const TENSORS: usize = 112;

/// The learnable affine blocks that survive the fold, in graph order.
///
/// `scripts/ml/ppocr_fold.py` pushes an affine into the convolution it feeds wherever that
/// is exact, which is wherever the convolution is **unpadded**. These sixteen are the ones
/// that are not: thirteen feed a padded depthwise, two feed a squeeze-excite's pool and
/// multiply, and the last feeds the sequence pool.
///
/// The padding is the whole reason. `conv(a * x + t)` is `a * conv(x) + t * sum(W)` at an
/// interior pixel, but a padded convolution reads zero rather than `t` outside the input,
/// so the constant's real contribution at the border is `t` times the sum of only the
/// in-bounds taps. Folding anyway biases the border of every feature map — 27% of a 12x16
/// map at a 3x3 kernel — and it compounds. ncnn's conversion of this model runs all 100 of
/// these as explicit elementwise ops; sixteen is what is left after the exact ones fold.
///
/// They are scalars, so they live here rather than in the `.maml`, which holds tensors.
pub(crate) const AFFINES: [(f32, f32); 16] = [
    (0.15316115, -0.25247484),
    (0.1658402, -0.41049767),
    (0.2158067, -0.13324346),
    (0.25548476, -0.3735239),
    (0.36402965, -0.035169836),
    (0.44559416, -0.27406755),
    (0.4579334, -0.15619978),
    (0.56647635, 0.074552566),
    (0.6249867, 0.1702801),
    (0.53231597, 0.17400852),
    (1.0831748, -1.0197166),
    (0.65120953, 0.05214425),
    (1.5772517, -1.7144594),
    (0.5974116, 0.17121108),
    (0.36693764, 0.11679397),
    (3.71384, -0.29614934),
];

/// Hands out `.maml` tensor indices in the order the layers appear. Every folded layer is
/// a weight followed by a bias, including the layer norms, whose gamma and beta land in
/// the same pair.
pub(crate) struct Layers {
    pub(crate) next: usize,
}

impl Layers {
    pub(crate) fn take(&mut self) -> usize {
        let index = self.next;
        self.next += 2;
        index
    }
}

/// A 1x1 convolution, which in this net is also every linear projection.
pub(crate) fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv(x, l.take(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// A `k x k` depthwise convolution, padded to hold the extent, at an independent stride
/// per axis.
///
/// The per-axis stride is the point: four of these stride the height alone and one
/// strides the width alone, which is how a 48-row crop becomes 3 rows while the width
/// only halves once in the backbone. A single stride would collapse the two.
pub(crate) fn depthwise(b: &mut Builder, l: &mut Layers, x: Id, kernel: u32, stride: (u32, u32)) -> Id {
    let channels = b.shape(x).c;
    let pad = kernel / 2;
    b.conv(
        x,
        l.take(),
        channels,
        (kernel, kernel),
        stride,
        (1, 1),
        (pad, pad, pad, pad),
        channels,
        Act::HardSwish,
    )
}

/// A `1 x 3` convolution along the sequence, padded to hold `T`.
///
/// Both uses sit in the head, after the map is one row tall, so the kernel is wide and
/// flat rather than square: it mixes three neighbouring timesteps and nothing vertical.
pub(crate) fn along_sequence(b: &mut Builder, l: &mut Layers, x: Id, out: u32) -> Id {
    b.conv(x, l.take(), out, (1, 3), (1, 1), (1, 1), (0, 1, 0, 1), 1, Act::Swish)
}

/// `x * gate(x)`, a squeeze-excite. Two uses, both plain rather than residual.
pub(crate) fn squeeze_excite(b: &mut Builder, l: &mut Layers, x: Id, reduce: u32) -> Id {
    let channels = b.shape(x).c;
    let pooled = b.global_avg_pool(x);
    let squeezed = point(b, l, pooled, reduce, Act::Relu);
    // `Clip01` rather than a HardSigmoid: the fold pushed its alpha and beta into this
    // convolution's weight and bias, leaving the bare clamp.
    let gate = point(b, l, squeezed, channels, Act::Clip01);
    b.mul_channel(x, gate)
}

/// Multi-head attention over `[d_model, 1, T]`, with no permute and no reshape.
///
/// Four projections, all `1x1`: query, key, value, and the output. In between, the score
/// map, its softmax and the weighted sum. The `1 / sqrt(head_dim)` scale is derived by
/// [`Builder::attn_scores`] from [`HEADS`] rather than passed in.
fn attention(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let q = point(b, l, x, D_MODEL, Act::None);
    let k = point(b, l, x, D_MODEL, Act::None);
    let v = point(b, l, x, D_MODEL, Act::None);
    let scores = b.attn_scores(q, k, HEADS);
    let probs = b.softmax(scores);
    let mixed = b.attn_apply(probs, v, HEADS);
    point(b, l, mixed, D_MODEL, Act::None)
}

/// One pre-norm transformer block: `x + attn(norm(x))`, then `x + ffn(norm(x))`.
///
/// Pre-norm, not post-norm — the residual adds the *unnormalised* input, which is what
/// the export does and is visible in its graph as the `Split` before each `LayerNorm`.
pub(crate) fn block(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let normed = b.layer_norm(x, l.take(), 1e-5);
    let attended = attention(b, l, normed);
    let residual = b.add(x, attended);

    let normed = b.layer_norm(residual, l.take(), 1e-5);
    let inner = point(b, l, normed, FFN, Act::Swish);
    let projected = point(b, l, inner, D_MODEL, Act::None);
    b.add(residual, projected)
}

/// Build the recognition pass. Implemented in [`super::ppocr_rec_extra`] to keep this
/// file under the size limit; re-exported so `ppocr_rec::build` keeps working.
pub use super::ppocr_rec_extra::{build, build_batch};

#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    /// The width a line crop is padded to on device, and 320 / 8 = 40 timesteps.
    const WIDTH: u32 = 320;

    fn plan(width: u32) -> (Shapes, Plan) {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, width).expect("ppocr_rec builds");
        (source, plan)
    }

    #[test]
    fn the_pass_reads_every_tensor_in_the_file_exactly_once() {
        let (source, _) = plan(WIDTH);
        let asked = source.asked.borrow();
        assert_eq!(asked.len(), TENSORS);
        // In order, with no repeats: `Builder::finish` rejects an unread tensor but not a
        // tensor read twice, which is what an off-by-one in `Layers` produces.
        let mut indices: Vec<usize> = asked.iter().map(|(i, _)| *i).collect();
        indices.sort_unstable();
        assert_eq!(indices, (0..TENSORS).collect::<Vec<usize>>());
    }

    #[test]
    fn the_output_is_one_logit_row_per_timestep() {
        let (_, plan) = plan(WIDTH);
        let out = plan.output().expect("one output");
        // Class-major `[838, 1, T]`, which is what the module docs promise the host.
        assert_eq!(out.shape, Shape::new(LOGITS, 1, WIDTH / 8));
        assert_eq!(plan.input().expect("one input").shape, Shape::new(3, HEIGHT, WIDTH));
    }

    #[test]
    fn the_timestep_count_is_the_width_over_eight() {
        // Three stride-2 stages act on the width. A net that lost one would still build
        // and would emit twice as many timesteps as the decode expects.
        for width in [8u32, 64, 320, 640] {
            let (_, plan) = plan(width);
            assert_eq!(
                plan.output().expect("one output").shape.w,
                width / 8,
                "at width {width}"
            );
        }
    }

    #[test]
    fn a_width_that_is_not_a_multiple_of_eight_is_refused() {
        let source = Shapes::new(TENSORS);
        for width in [0u32, 1, 12, 100, 321] {
            let error = build(&source, width).expect_err("a ragged width");
            assert!(error.contains("multiple of 8"), "at width {width}: {error}");
        }
    }

    #[test]
    fn the_op_inventory_matches_the_folded_graph() {
        let (_, plan) = plan(WIDTH);
        let mut counts = std::collections::BTreeMap::new();
        let mut copies = 0;
        for op in &plan.ops {
            match op {
                Op::Dispatch { kind, .. } => *counts.entry(super::super::tests::name_of(*kind)).or_insert(0) += 1,
                Op::Copy { .. } => copies += 1,
            }
        }
        // 56 layers, every one a convolution: 33 backbone + 2 into the sequence + 8 per
        // block + 4 in the head. The five layer norms are the only layers that are not.
        assert_eq!(counts.get("Conv"), Some(&51), "{counts:?}");
        assert_eq!(counts.get("LayerNorm"), Some(&5), "{counts:?}");
        // Two squeeze-excites, each a pool and a channel multiply.
        assert_eq!(counts.get("GlobalAvgPool"), Some(&2), "{counts:?}");
        assert_eq!(counts.get("MulBroadcast"), Some(&2), "{counts:?}");
        // The one pool that turns the map into a sequence.
        assert_eq!(counts.get("AvgPool"), Some(&1), "{counts:?}");
        // Two attentions, each three ops.
        assert_eq!(counts.get("AttnScores"), Some(&2), "{counts:?}");
        assert_eq!(counts.get("Softmax"), Some(&2), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&2), "{counts:?}");
        // Two residuals per block — half of which fold into their producing
        // convolution's store. See `Builder::add`.
        assert_eq!(counts.get("Add"), Some(&2), "{counts:?}");
        // The sixteen learnable affine blocks that could not fold; see `AFFINES`.
        assert_eq!(counts.get("Affine"), Some(&16), "{counts:?}");
        // The one concatenation, lowered to two copies and no shader.
        assert_eq!(copies, 2, "{counts:?}");
        // Nothing else. The op inventory below matches `ppocr_fold.py`'s, which is the
        // check that the transcription and the converter agree about the graph:
        //   Conv 38 + Linear 13 = 51, LayerNorm 5, Affine 16, GlobalAveragePool 2,
        //   Mul 2, AveragePool 1, MatMul 4, Softmax 2, Add 4, Concat 1.
        assert_eq!(counts.len(), 10, "{counts:?}");
    }

    #[test]
    fn the_tensor_table_matches_ncnns_independent_conversion() {
        // Every parameter the checked-in `latin_PP_OCRv5_mobile_rec.ncnn.param` declares:
        // the sum of `6=weight_data_size` over its 51 convolutions, `4 * 2=` over its two
        // `MultiHeadAttention`s, `8= * 9=` over its five `Gemm`s and `0=` over its five
        // `LayerNorm`s, plus one bias per output channel throughout.
        //
        // This is the strongest available check on the transcription, and the same one
        // `ppocr_det` leans on: ncnn converted this export independently, so a channel
        // count, kernel size or group that is wrong here disagrees with it. Every
        // individual weight size matches too — this is their sum because one number is
        // checkable by hand and 56 are not.
        let (source, _) = plan(WIDTH);
        let total: u64 = source
            .asked
            .borrow()
            .iter()
            .map(|(_, dims)| dims.iter().map(|&d| d as u64).product::<u64>())
            .sum();
        assert_eq!(total, 1_987_314);
    }

    #[test]
    fn the_backbone_reduces_the_height_to_three_before_the_pool() {
        // 48 → 24 → 12 → 6 → 3, four stride-2 stages on the height alone, then a kernel
        // of exactly 3 to collapse it. This is why the height is not a parameter: the
        // pool's window only matches at 48.
        let (_, plan) = plan(WIDTH);
        let pool = plan
            .ops
            .iter()
            .find_map(|op| match op {
                Op::Dispatch { kind: Kind::AvgPool, push, .. } => Some(*push),
                _ => None,
            })
            .expect("one average pool");
        assert_eq!((pool.in_c, pool.in_h, pool.in_w), (FEATURES, 3, WIDTH / 4));
        assert_eq!((pool.kh, pool.kw), (3, 2));
        assert_eq!((pool.out_c, pool.out_h, pool.out_w), (FEATURES, 1, WIDTH / 8));
    }

    #[test]
    fn every_attention_splits_d_model_into_eight_heads() {
        let (_, plan) = plan(WIDTH);
        let scores: Vec<super::super::Push> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::AttnScores, push, .. } => Some(*push),
                _ => None,
            })
            .collect();
        assert_eq!(scores.len(), 2);
        for push in scores {
            // Reading `[120, 1, T]` as `[8, 15, T]`, so the head count is in `group` and
            // the sequence is the width.
            assert_eq!((push.in_c, push.in_h, push.in_w), (D_MODEL, 1, WIDTH / 8));
            assert_eq!(push.group, HEADS);
            assert_eq!(push.in_c / push.group, 15);
            // The score map is `[heads, T, T]`.
            assert_eq!(
                (push.out_c, push.out_h, push.out_w),
                (HEADS, WIDTH / 8, WIDTH / 8)
            );
            // `1 / sqrt(15)`, derived from the geometry rather than read from the file.
            let scale = f32::from_bits(push.param0_bits);
            assert!((scale - 1.0 / 15f32.sqrt()).abs() < 1e-6, "{scale}");
        }
    }

    #[test]
    fn four_layer_norms_use_one_epsilon_and_the_last_uses_another() {
        // The export really does differ: 1e-5 through both blocks and 1e-6 on the final
        // one. A single hardcoded epsilon would be a small, invisible accuracy loss.
        let (_, plan) = plan(WIDTH);
        let epsilons: Vec<f32> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::LayerNorm, push, .. } => {
                    Some(f32::from_bits(push.param1_bits))
                }
                _ => None,
            })
            .collect();
        assert_eq!(epsilons, vec![1e-5, 1e-5, 1e-5, 1e-5, 1e-6]);
    }

    #[test]
    fn every_layer_norm_reduces_over_d_model_and_one_position_at_a_time() {
        let (_, plan) = plan(WIDTH);
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::LayerNorm, push, invocations } = op {
                assert_eq!(push.in_c, D_MODEL);
                // One reduction per position, not per element: `count` is the spatial extent
                // because each reduces a whole column of channels. A line is few positions, so
                // each gets a whole workgroup (`row_reduce`), 64 invocations.
                assert_eq!(push.count, WIDTH / 8);
                assert_eq!(push.kw, 1, "row mode: {push:?}");
                assert_eq!(*invocations, WIDTH / 8 * 64);
            }
        }
    }

    #[test]
    fn the_head_concatenates_the_pooled_features_before_the_transformer_output() {
        // Order is load-bearing: the export's `Concat` puts the skip first, and swapping
        // the two halves changes which 480 channels the next convolution's weights see.
        let (_, plan) = plan(WIDTH);
        let copies: Vec<(u32, u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Copy { src, dst, elems } => Some((*src, *dst, *elems)),
                _ => None,
            })
            .collect();
        let [(_, first_dst, first_elems), (_, second_dst, second_elems)] =
            <[(u32, u32, u32); 2]>::try_from(copies.as_slice()).expect("two copies");
        assert_eq!(first_elems, FEATURES * WIDTH / 8);
        assert_eq!(second_elems, FEATURES * WIDTH / 8);
        assert_eq!(second_dst, first_dst + first_elems);
    }

    #[test]
    fn no_op_reads_a_region_of_the_arena_that_it_also_writes() {
        let (_, plan) = plan(WIDTH);
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_arena_is_bounded_at_the_width_the_device_runs() {
        let (_, plan) = plan(WIDTH);
        // fp16, so bytes are twice the elements. Printed rather than pinned to a tight
        // figure, because the allocator's fit policy is allowed to change; the assertion
        // is only that it is nothing like the sum of every intermediate.
        let mib = plan.arena_elems as f32 * 2.0 / (1024.0 * 1024.0);
        println!("ppocr_rec at 48x{WIDTH}: {} elements, {mib:.2} MiB", plan.arena_elems);
        assert!(mib < 32.0, "{mib} MiB");
    }
}
