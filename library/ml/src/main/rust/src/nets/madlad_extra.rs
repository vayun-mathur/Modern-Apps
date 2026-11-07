#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op, Plan, Shape};
    use super::super::madlad::*;
    use super::super::madlad_part1::{relative_bucket, split_of};

    /// A short sentence: a language tag, six pieces and `</s>`.
    const LEN: u32 = 8;

    /// `Act::Gelu`'s code in `common.glsl`. `Act::code` is private, so the activation the fused
    /// projection folds is asserted as the number the shader reads rather than through the enum.
    const ACT_GELU: u32 = 8;

    fn plan(mode: Mode) -> (Shapes, Plan) {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, mode).expect("the pass builds");
        (source, plan)
    }

    #[test]
    fn the_layout_matches_the_converter() {
        // The numbers `maml_convert.py --graph madlad400 --print-layers` reports for this graph. A
        // disagreement here is a plan that reads one layer's weights as another's.
        assert_eq!(ENCODER_LAYER_TENSORS, 20);
        assert_eq!(DECODER_LAYER_TENSORS, 33);
        assert_eq!((HEAD_SHARED, HEAD_LM, ENCODER), (0, 12, 24));
        assert_eq!((ENCODER_NORM, DECODER, DECODER_NORM), (664, 665, 1721));
        assert_eq!((TABLE_ENC, TABLE_DEC, TENSORS), (1722, 1723, 1724));
        assert_eq!(Q2K_CONVS, 520);
        assert_eq!((split_classes(0), split_classes(3)), (64_000, 64_000));
        assert_eq!(
            split_classes(0) + split_classes(1) + split_classes(2) + split_classes(3),
            VOCAB
        );
    }

    #[test]
    fn the_parameter_total_matches_the_checkpoint() {
        // What the file holds, from the layout `every_tensor_shape_is_stated_the_same_way_twice`
        // pins, against the checkpoint plus exactly the two things quantising adds.
        //
        // `dims_of` counts ELEMENTS: a Q2_K kernel `[out, in, 1, 1]` is `out * in` elements
        // (its byte length is `out * blocks * 84`, handled below). So the element total is
        // the checkpoint count plus scales plus biases, exactly as the int8 port's was.
        let total: u64 = (0..TENSORS)
            .map(|index| dims_of(index).iter().map(|&d| u64::from(d)).product::<u64>())
            .sum();

        // One `(d, dmin)` fp16 pair per superblock of each of the 520 Q2_K convolutions:
        // heads 2 x 256000 rows x 4 blocks; per enc layer q/k/v 3x2048x4 + o 1024x8 +
        // wi_01 16384x4 + wo 1024x32; per dec layer that plus cross q/k/v/o. Two values
        // per block.
        let enc_blocks = 3 * 2048 * 4 + 1024 * 8 + 16384 * 4 + 1024 * 32;
        let dec_blocks = enc_blocks + 3 * 2048 * 4 + 1024 * 8;
        let scales =
            2 * (2 * u64::from(VOCAB) * 4 + ENCODER_LAYERS as u64 * enc_blocks
                + DECODER_LAYERS as u64 * dec_blocks);
        assert_eq!(scales, 22_970_368);
        // And a zero bias per output row of every Q2_K convolution, which T5 never trains.
        let synthesised = 2 * u64::from(VOCAB)
            + ENCODER_LAYERS as u64 * (3 * 2048 + 1024 + 16384 + 1024)
            + DECODER_LAYERS as u64 * (3 * 2048 + 1024 + 16384 + 1024 + 3 * 2048 + 1024);
        assert_eq!(synthesised, 2_314_240);

        // 2,940,374,016 checkpoint parameters (the inventory total), plus scales, plus biases.
        assert_eq!(total, 2_940_374_016 + scales + synthesised);

        // ~0.97 GiB on disk. The 520 kernels are Q2_K superblocks (84 bytes per 256 taps):
        // kernel bytes = sum over linears of out * blocks * 84. Everything else is fp16,
        // so the file is the kernel bytes plus twice the non-kernel elements.
        let kernels = (2 * u64::from(VOCAB) * 4 + ENCODER_LAYERS as u64 * enc_blocks
            + DECODER_LAYERS as u64 * dec_blocks)
            * 84;
        assert_eq!(kernels, 964_755_456);
        let file = kernels + (total - 2_940_207_104) * 2;
        assert!((960 << 20..1030 << 20).contains(&file), "{file} bytes");
    }

    /// The tensor ranges each pass reads on the device. Everything else it names.
    ///
    /// Stated here rather than returned by [`build`] because it is the thing under test: a pass
    /// that read the wrong range would name the right one and still be wrong.
    fn read_by(mode: Mode) -> Vec<std::ops::Range<usize>> {
        let mut ranges = Vec::new();
        match mode {
            Mode::Encode { .. } => ranges.push(ENCODER..DECODER),
            Mode::DecodeStep { .. } => {
                // The decode step computes the untied logits head itself, so it reads both ends
                // of the file. The input table and the bias tables are host-read.
                ranges.push(HEAD_LM..ENCODER);
                ranges.push(DECODER..TABLE_ENC);
            }
        }
        ranges
    }

    #[test]
    fn the_passes_cover_the_file_and_every_one_of_them_builds() {
        // `Builder::finish` only checks that a tensor is read *or* named, so this is what stops
        // naming being used to hide a layer the device never touches. Together the passes must read
        // every index — except the host-read tensors both passes name: the input table (gathered
        // per token) and the two bias tables (gathered per shape).
        let mut covered = std::collections::BTreeSet::new();
        for mode in [
            Mode::Encode { len: LEN },
            Mode::DecodeStep { src_len: LEN },
        ] {
            let source = Shapes::new(TENSORS);
            build(&source, mode).unwrap_or_else(|e| panic!("{mode:?}: {e}"));
            covered.extend(read_by(mode).into_iter().flatten());
        }
        let mut want: Vec<usize> = (0..TENSORS).collect();
        // Host-read: the input table and the two bias tables are named, never bound.
        want.retain(|i| !(HEAD_SHARED..HEAD_LM).contains(i) && *i != TABLE_ENC && *i != TABLE_DEC);
        assert_eq!(covered.into_iter().collect::<Vec<_>>(), want);
    }

    #[test]
    fn the_encoder_is_thirty_two_pre_norm_rms_layers() {
        let (_, plan) = plan(Mode::Encode { len: LEN });
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0usize) += 1;
            }
        }
        // Six Q2_K convolutions per layer: q, k, v, the output projection, fused wi_01 and wo —
        // plus the two cross-attention K/V projections this pass runs for every decoder layer
        // so the decode step never has to (see `cross_kv`).
        assert_eq!(
            counts.get("ConvQ2K"),
            Some(&(ENCODER_LAYERS * 6 + DECODER_LAYERS * 2)),
            "{counts:?}"
        );
        // Three RMS norms per layer would be post-norm. Pre-norm is two, plus one at the end.
        assert_eq!(counts.get("RmsNorm"), Some(&(ENCODER_LAYERS * 2 + 1)), "{counts:?}");
        // Prescaled scores (T5 applies no query scale), one bias Add per layer, and one
        // surviving residual Add per layer after the fusion fold (see Builder::add).
        assert_eq!(counts.get("AttnScores"), Some(&ENCODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("Add"), Some(&(ENCODER_LAYERS * 2)), "{counts:?}");
        assert_eq!(counts.get("Softmax"), Some(&ENCODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&ENCODER_LAYERS), "{counts:?}");
        // The GEGLU gate: one fused activation per layer.
        assert_eq!(counts.get("GatedActivate"), Some(&ENCODER_LAYERS), "{counts:?}");
        // And the fused projection folds Gelu — `feed_forward_proj: gated-gelu` — on the
        // `2 * FFN`-wide projection only. (The fused conv itself carries Act::None; the
        // gate is the separate GatedActivate op.)
        let mut gelu = 0;
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::GatedActivate, push, .. } = op {
                assert_eq!(push.act, ACT_GELU, "the GEGLU gate is Gelu: {push:?}");
                // `in_c` is the gate half's element count: FFN per position
                // over the whole sequence (see the GatedActivate lowering).
                assert_eq!(push.in_c, FFN * LEN, "the gate halves 2 * FFN: {push:?}");
                gelu += 1;
            }
        }
        assert_eq!(gelu, ENCODER_LAYERS, "{counts:?}");
    }

    #[test]
    fn the_encoder_takes_the_token_and_the_bias() {
        let (_, plan) = plan(Mode::Encode { len: LEN });
        // The embedded source and the host-computed bias, and nothing else.
        assert_eq!(plan.inputs.len(), 2);
        assert_eq!(plan.inputs[0].shape, Shape::new(D_MODEL, 1, LEN));
        assert_eq!(plan.inputs[1].shape, Shape::new(HEADS, LEN, LEN));
        assert_eq!(plan.output().unwrap().shape, Shape::new(D_MODEL, 1, LEN));
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_attention_score_maps_are_square_in_the_encoder() {
        // Self-attention over one sentence, so queries and keys are the same positions. The
        // cross-attention in the decode step is what makes them differ.
        let (_, plan) = plan(Mode::Encode { len: LEN });
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::AttnScores, push, .. } = op {
                assert_eq!((push.group, push.out_h, push.out_w), (HEADS, LEN, LEN), "{push:?}");
                // Prescaled: the push scale is 1.0, not 1/sqrt(128).
                assert_eq!(push.param0_bits, 1.0f32.to_bits(), "{push:?}");
            }
        }
    }

    #[test]
    fn a_decode_step_is_thirty_two_layers_of_two_attentions() {
        let (_, plan) = plan(Mode::DecodeStep { src_len: LEN });
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0usize) += 1;
            }
        }
        // Eight Q2_K convolutions per layer — self q/k/v/o, cross q/o (its K and V
        // were projected by the encode pass into the device caches), fused wi_01
        // and wo — and the four splits of the untied logits head.
        assert_eq!(
            counts.get("ConvQ2K"),
            Some(&(DECODER_LAYERS * 8 + HEAD_SPLITS)),
            "{counts:?}"
        );
        // Three RMS norms per layer, pre-norm, plus the trailing one.
        assert_eq!(counts.get("RmsNorm"), Some(&(DECODER_LAYERS * 3 + 1)), "{counts:?}");
        // Self-attention is cached and single-query; the cross-attention is not.
        assert_eq!(counts.get("AttnScoresCached"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApplyCached"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&DECODER_LAYERS), "{counts:?}");
        // The self-attention softmax is the prefix-bounded one; the cross-attention's is not.
        assert_eq!(counts.get("Softmax"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("SoftmaxPrefix"), Some(&DECODER_LAYERS), "{counts:?}");
        // One bias Add per layer (self only — cross has none), plus the residuals the
        // fusion fold leaves behind (see Builder::add): the 32 bias adds and 48 of
        // the 96 residual adds survive it, 80 in total.
        assert_eq!(counts.get("Add"), Some(&(DECODER_LAYERS * 2 + 16)), "{counts:?}");
        // K and V into the cache, per layer.
        assert_eq!(counts.get("CacheWrite"), Some(&(DECODER_LAYERS * 2)), "{counts:?}");
        assert_eq!(counts.get("GatedActivate"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_no_aliasing(&plan);
    }

    #[test]
    fn a_decode_step_takes_the_token_and_the_bias_and_returns_the_next_token() {
        let (_, plan) = plan(Mode::DecodeStep { src_len: LEN });
        // The token and the step's bias row - and nothing else: both kinds of cache are on the
        // device. The encoder output used to be uploaded here every step.
        assert_eq!(plan.inputs.len(), 2);
        assert_eq!(plan.inputs[0].shape, Shape::new(D_MODEL, 1, 1));
        assert_eq!(plan.inputs[1].shape, Shape::new(HEADS, 1, MAX_DECODE_POSITIONS));
        // `Builder::argmax`'s two lanes, rather than 256,000 logits.
        assert_eq!(plan.outputs.len(), 1);
        assert_eq!(plan.outputs[0].shape, Shape::new(2, 1, 1));
    }

    #[test]
    fn the_encode_pass_leaves_the_cross_caches_where_the_decode_step_reads_them() {
        // Two plans agreeing on where 64 persistent tensors live. If they disagree the decoder
        // attends over the other plan's activations and still produces fluent text.
        let (_, encode) = plan(Mode::Encode { len: LEN });
        let (_, step) = plan(Mode::DecodeStep { src_len: LEN });
        let caches = DECODER_LAYERS * 2;
        assert_eq!(encode.pinned[..caches], step.pinned[..caches]);
        for binding in &encode.pinned[..caches] {
            assert_eq!(binding.shape, Shape::new(INNER, 1, LEN));
            let filled = encode.ops.iter().any(|op| {
                matches!(*op, Op::Copy { dst, elems, .. } if dst == binding.at && elems == INNER * LEN)
            });
            assert!(filled, "the encode pass never fills the cache at {}", binding.at);
        }
    }

    #[test]
    fn the_decode_plan_does_not_depend_on_the_step() {
        // The property the whole record-once design rests on. Two steps of the same translation
        // produce the same key, so `Reshaped::at` matches and never re-records; and because the
        // plan is identical, the arena offsets - including the caches' - are identical too, which
        // is what lets the cache survive from one submit to the next. The bias *contents* differ
        // per step, but contents are inputs, not plan.
        let (_, first) = plan(Mode::DecodeStep { src_len: LEN });
        let (_, again) = plan(Mode::DecodeStep { src_len: LEN });
        assert_eq!(first.ops, again.ops);
        assert_eq!(first.arena_elems, again.arena_elems);
        assert_eq!(Mode::DecodeStep { src_len: LEN }, Mode::DecodeStep { src_len: LEN });
    }

    #[test]
    fn the_caches_are_built_for_the_longest_decode_the_loop_can_run() {
        // Sized to `MAX_DECODE_POSITIONS`, not to a step, so a plan recorded once covers every
        // token. If this and `post::madlad::MAX_TOKENS` ever disagree the loop either wastes
        // arena or silently drops positions at the end of a long sentence.
        let (_, plan) = plan(Mode::DecodeStep { src_len: LEN });
        let mut writes = 0;
        for op in &plan.ops {
            match op {
                Op::Dispatch { kind: Kind::CacheWrite, push, .. } => {
                    writes += 1;
                    assert_eq!(push.in_h, MAX_DECODE_POSITIONS, "{push:?}");
                    // Cache rows hold the projected K/V, so they are INNER wide,
                    // not d_model: the caches are declared [INNER, 1, src_len].
                    assert_eq!(push.in_c, INNER, "the row stride is inner: {push:?}");
                    assert_eq!(push.count, INNER, "a position is inner long: {push:?}");
                }
                // The score map is as wide as the cache, and the bound comes from the step.
                Op::Dispatch { kind: Kind::AttnScoresCached, push, .. } => {
                    assert_eq!((push.out_h, push.out_w), (1, MAX_DECODE_POSITIONS), "{push:?}");
                    assert_ne!(push.dyn_keys, 0, "the key count must come from the step");
                    // Prescaled: T5 applies no 1/sqrt(head_dim).
                    assert_eq!(push.param0_bits, 1.0f32.to_bits(), "{push:?}");
                }
                Op::Dispatch { kind: Kind::AttnApplyCached, push, .. } => {
                    assert_eq!((push.in_w, push.out_c), (MAX_DECODE_POSITIONS, INNER), "{push:?}");
                    assert_ne!(push.dyn_keys, 0, "the key count must come from the step");
                }
                // Cross-attention: one query over the source, which is a different length and a
                // fixed one, so it stays static.
                Op::Dispatch { kind: Kind::AttnScores, push, .. } => {
                    assert_eq!((push.out_h, push.out_w), (1, LEN), "{push:?}");
                    assert_eq!(push.dyn_keys, 0, "cross-attention is not prefix-bounded");
                    assert_eq!(push.param0_bits, 1.0f32.to_bits(), "{push:?}");
                }
                _ => {}
            }
        }
        assert_eq!(writes, DECODER_LAYERS * 2, "a K and a V per layer");
        assert_no_aliasing(&plan);
    }

    #[test]
    fn a_decode_step_over_nothing_is_refused() {
        let source = Shapes::new(TENSORS);
        let error =
            build(&source, Mode::DecodeStep { src_len: 0 }).expect_err("no source");
        assert!(error.contains("no source"), "{error}");
    }

    #[test]
    fn the_head_is_four_even_splits_over_disjoint_class_ranges() {
        // Together the four splits are the whole vocabulary, which the device argmax reduces
        // over in two stages. Even splits: 256,000 = 4 x 64,000.
        let source = Shapes::new(TENSORS);
        let plan = build(&source, Mode::DecodeStep { src_len: LEN })
            .expect("the step builds");
        let argmax: Vec<_> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Argmax, push, .. } => Some(push.count),
                _ => None,
            })
            .collect();
        assert_eq!(argmax, vec![VOCAB]);
        // And the fold over the partials is there exactly once.
        assert_eq!(
            plan.ops
                .iter()
                .filter(|op| matches!(op, Op::Dispatch { kind: Kind::ArgmaxFold, .. }))
                .count(),
            1
        );
        assert_eq!(split_classes(0) * HEAD_SPLITS as u32, VOCAB);
    }

    #[test]
    fn the_head_binds_under_the_guaranteed_range() {
        // Why the split exists and why it is four: one 256,000 x 1024 int8 table is ~250
        // MiB against a guaranteed 128 MiB. A quarter plus its scale and bias sits at ~64
        // MiB — comfortably inside, with headroom NLLB's tighter uneven split lacked.
        let whole = u64::from(VOCAB) * u64::from(D_MODEL);
        assert!(whole > 128 << 20, "the whole table is {whole} bytes, over the guaranteed 128 MiB");
        let quarter = u64::from(CLASSES_PER_SPLIT) * u64::from(D_MODEL)
            + u64::from(CLASSES_PER_SPLIT) * 4;
        assert!(quarter < 96 << 20, "a quarter is {quarter} bytes");
    }

    #[test]
    fn split_of_covers_the_whole_vocabulary_without_gaps() {
        // Every id must map to exactly one (split, row), and the row must be inside that split.
        let mut next = 0u32;
        for split in 0..HEAD_SPLITS {
            for row in 0..split_classes(split) {
                assert_eq!(split_of(next), (split, row), "id {next}");
                next += 1;
            }
        }
        assert_eq!(next, VOCAB);
        assert_eq!(split_of(0), (0, 0));
        assert_eq!(split_of(63_999), (0, 63_999));
        assert_eq!(split_of(64_000), (1, 0));
        assert_eq!(split_of(255_999), (3, 63_999));
    }

    #[test]
    fn a_pass_over_nothing_is_refused() {
        let source = Shapes::new(TENSORS);
        let error = build(&source, Mode::Encode { len: 0 }).expect_err("no tokens");
        assert!(error.contains("no tokens"), "{error}");
    }

    #[test]
    fn every_tensor_shape_is_stated_the_same_way_twice() {
        // `dims_of` restates the table `maml_convert.collect_madlad` writes, and `Layers` walks
        // it. This checks the two agree for every index, which is what makes `host_tensor`'s shape
        // check meaningful rather than circular.
        let mut expected: Vec<Vec<u32>> = Vec::new();
        for _ in 0..HEAD_SPLITS * 2 {
            // Both untied tables split evenly: kernel, scale, bias per split.
            // Q2_K kernels are rank 2 `[out, taps]` (superblocks have no kernel
            // axes to restate) — see `Builder::conv_quantised`.
            expected.push(vec![CLASSES_PER_SPLIT, D_MODEL]);
            expected.push(vec![CLASSES_PER_SPLIT * (D_MODEL / crate::weights::Q2K_BLOCK) * 2]);
            expected.push(vec![CLASSES_PER_SPLIT]);
        }
        // Kernel, its `(d, dmin)` scale table (two values per Q2_K superblock
        // of the input width), and the synthesised bias.
        let projection = |out: u32, inputs: u32| {
            vec![
                vec![out, inputs],
                vec![out * (inputs / crate::weights::Q2K_BLOCK) * 2],
                vec![out],
            ]
        };
        let norm = || vec![vec![D_MODEL]];
        for (layers, cross) in [(ENCODER_LAYERS, false), (DECODER_LAYERS, true)] {
            for _ in 0..layers {
                expected.extend(norm());
                // q/k/v are inner_dim-wide (HEADS * HEAD_DIM), o is d_model.
                for _ in 0..3 {
                    expected.extend(projection(HEADS * HEAD_DIM, D_MODEL));
                }
                expected.extend(projection(D_MODEL, HEADS * HEAD_DIM));
                if cross {
                    expected.extend(norm());
                    for _ in 0..3 {
                        expected.extend(projection(HEADS * HEAD_DIM, D_MODEL));
                    }
                    expected.extend(projection(D_MODEL, HEADS * HEAD_DIM));
                }
                expected.extend(norm());
                expected.extend(projection(2 * FFN, D_MODEL));
                expected.extend(projection(D_MODEL, FFN));
            }
            expected.extend(norm());
        }
        expected.push(vec![BUCKETS, HEADS]);
        expected.push(vec![BUCKETS, HEADS]);
        assert_eq!(expected.len(), TENSORS);
        for (index, want) in expected.iter().enumerate() {
            assert_eq!(&dims_of(index), want, "tensor {index}");
        }
    }

    #[test]
    fn the_relative_buckets_match_the_reference_math() {
        // transformers' `_relative_position_bucket` with num_buckets 32, max_distance 128,
        // hand-evaluated at the boundaries that distinguish the branches.
        // Bidirectional: buckets 0..15 behind-or-equal, 16..31 ahead.
        assert_eq!(relative_bucket(0, true), 0);
        assert_eq!(relative_bucket(-1, true), 1);
        assert_eq!(relative_bucket(-8, true), 8);
        assert_eq!(relative_bucket(1, true), 16 + 1);
        assert_eq!(relative_bucket(8, true), 16 + 8);
        // Causal: every ahead-position collapses to bucket 0; behind uses all 32.
        assert_eq!(relative_bucket(0, false), 0);
        assert_eq!(relative_bucket(1, false), 0);
        assert_eq!(relative_bucket(5, false), 0);
        assert_eq!(relative_bucket(-1, false), 1);
        assert_eq!(relative_bucket(-8, false), 8);
        assert_eq!(relative_bucket(-16, false), 16);
        // Far positions saturate at the last bucket of their half.
        assert_eq!(relative_bucket(-1000, true), 15);
        assert_eq!(relative_bucket(1000, true), 31);
        assert_eq!(relative_bucket(-1000, false), 31);
    }

    #[test]
    fn the_bias_gather_matches_a_hand_computed_case() {
        // `relative_bias` over a hand-made table cannot use a full TENSORS fixture (the table
        // is gigabytes at real shapes), so this pins the two halves separately: the bucket
        // function above, and — in `madlad_parity.py` — the gathered matrix against HF's
        // `compute_bias` on the real tables. What this pins is the table's own layout: bucket
        // rows of HEADS heads, which `relative_bias` reads as `gathered[bucket * HEADS + head]`.
        assert_eq!(BUCKETS, 32);
        assert_eq!(HEADS, 16);
        // Bucket 0 is the self-position in both directions; the log branch starts at 8.
        assert_eq!(relative_bucket(0, true), relative_bucket(0, false));
        assert_eq!(relative_bucket(-7, true), 7);
        assert_eq!(relative_bucket(-7, false), 7);
    }
}

