#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op, NO_FUSE};
    use super::*;

    /// About four seconds of audio, and a sentence to match.
    const FRAMES: u32 = 54;
    const CHARS: u32 = 24;

    fn plan(frames: u32, chars: u32) -> (Shapes, Plan) {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, frames, chars).expect("the sampler builds");
        (source, plan)
    }

    #[test]
    fn the_pass_reads_every_tensor_in_the_file_exactly_once() {
        let (source, _) = plan(FRAMES, CHARS);
        let asked = source.asked.borrow();
        assert_eq!(asked.len(), TENSORS);
        let mut indices: Vec<usize> = asked.iter().map(|(i, _)| *i).collect();
        indices.sort_unstable();
        assert_eq!(indices, (0..TENSORS).collect::<Vec<usize>>());
    }

    #[test]
    fn the_tensor_table_matches_the_export() {
        // Checked as a sum of its own parts, because the export's float initializer total
        // (64,013,449) also holds a few dozen scalar literals — the guidance 4 and 3, the
        // score divisor 16, the GELU constants — which are initializers here rather than
        // `Constant` nodes, so an exact equation against it would be reconciling weights
        // against arithmetic. The material differences from that figure are:
        //
        //   - the 28 `[1, 512, 1]` layer scales fold into their `pwconv2`:      -14,336
        //   - the four style `W_key`s and their biases fold into the keys:     -263,168
        //   - the folded keys are four stacked `[256, 50]` per branch, where
        //     the export holds one `[1, 50, 256]` `style_key` per branch:      +76,800
        //   - `proj_in` and `proj_out` have no bias, so two are synthesised:      +656
        //   - the timestep embedding's frequency table is a `Constant` node
        //     in the export rather than an initializer, so it is new here:         +32
        //
        // `increments` is an int64 ramp and never counted; the two `[1, 50, 256]` style key
        // constants leave and eight folded `[256, 50]` tensors arrive.
        let (source, _) = plan(FRAMES, CHARS);
        let total: u64 = source
            .asked
            .borrow()
            .iter()
            .map(|(_, dims)| dims.iter().map(|&d| d as u64).product::<u64>())
            .sum();

        let projection = |a: u64, b: u64| a * b + a;
        // A quantised projection carries a third tensor: the per-output-channel scale, which is
        // the same length as the bias. Every `1 x 1` here is one - see `INT8_CONVS`.
        let quantised = |a: u64, b: u64| projection(a, b) + a;
        let block = 2_560 + 512 + 512 + 512 + quantised(2_048, 512) + quantised(512, 2_048);
        let text_attention = quantised(512, 512) * 2 + quantised(512, 256) * 2 + 1_024;
        let style_attention =
            quantised(256, 512) + quantised(256, 256) + quantised(512, 256) + 1_024;
        let host = 32 + 32
            + projection(256, 64)
            + projection(64, 256)
            + projection(512, 64) * MAIN_BLOCKS as u64
            + 256
            + 12_800
            + 12_800 * 2 * MAIN_BLOCKS as u64;
        assert_eq!(
            total,
            quantised(512, 144)
                + block * BLOCKS as u64
                + (text_attention + style_attention) * MAIN_BLOCKS as u64
                + quantised(144, 512)
                + host
        );
        // And spelled out, so a reordering that preserved the sum would still be caught. The 84,624
        // above the fp16 total is the scales.
        assert_eq!(total, 63_813_392 + 84_624);
        assert_eq!(total, 63_898_016);
    }

    #[test]
    fn the_inputs_are_the_latent_the_two_conditionings_and_the_two_angle_tables() {
        let (_, plan) = plan(FRAMES, CHARS);
        let inputs: Vec<Shape> = plan.inputs.iter().map(|b| b.shape).collect();
        assert_eq!(
            inputs,
            vec![
                Shape::new(LATENT, 1, FRAMES),
                Shape::new(TEXT, 1, CHARS),
                Shape::new(STYLE * MAIN_BLOCKS as u32, 1, STYLE_TOKENS),
                Shape::new(STYLE, 1, STYLE_TOKENS),
                Shape::new(CHANNELS * MAIN_BLOCKS as u32, 1, 1),
                Shape::new(2 * FREQUENCIES, 1, FRAMES),
                Shape::new(2 * FREQUENCIES, 1, CHARS),
            ]
        );
        // One branch's velocity, at the latent's own shape. Not the Euler step: that is
        // `post::supertonic::step`, which needs both branches.
        assert_eq!(
            plan.output().expect("one output").shape,
            Shape::new(LATENT, 1, FRAMES)
        );
    }

    #[test]
    fn the_text_attention_is_a_cross_attention_of_frames_against_characters() {
        // `[8, frames, chars]` — the shape that made the cross-attention change necessary. The
        // style one is `[2, frames, 50]`.
        let (_, plan) = plan(FRAMES, CHARS);
        let maps: Vec<(u32, u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::AttnScores, push, .. } => {
                    Some((push.out_c, push.out_h, push.out_w))
                }
                _ => None,
            })
            .collect();
        let mut want = Vec::new();
        for _ in 0..MAIN_BLOCKS {
            want.push((TEXT_HEADS, FRAMES, CHARS));
            want.push((STYLE_HEADS, FRAMES, STYLE_TOKENS));
        }
        assert_eq!(maps, want);
    }

    #[test]
    fn rotary_runs_on_both_sides_of_every_text_attention() {
        // Eight in all: the query over the latent's length and the key over the text's. A
        // rotary on only one side would leave the two sequences in different frames and the
        // alignment would drift with the length ratio.
        let (_, plan) = plan(FRAMES, CHARS);
        let widths: Vec<(u32, u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Rotary, push, .. } => {
                    Some((push.group, push.in_c, push.out_w))
                }
                _ => None,
            })
            .collect();
        let mut want = Vec::new();
        for _ in 0..MAIN_BLOCKS {
            want.push((TEXT_HEADS, 2 * FREQUENCIES, FRAMES));
            want.push((TEXT_HEADS, 2 * FREQUENCIES, CHARS));
        }
        assert_eq!(widths, want);
    }

    #[test]
    fn the_depthwise_dilations_are_the_exports_own_sequence() {
        // Per main block 1, 2, 4, 8 then 1 then 1; then four more at 1. Sixteen at dilation 1,
        // four each at 2, 4 and 8 — which is exactly the export's `Conv` inventory.
        let (_, plan) = plan(FRAMES, CHARS);
        let found: Vec<(u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Conv, push, .. } if push.group == CHANNELS => {
                    Some((push.dil_w, push.pad_l))
                }
                _ => None,
            })
            .collect();
        let mut want = Vec::new();
        for _ in 0..MAIN_BLOCKS {
            for &dilation in &LEADING {
                want.push((dilation, dilation * (KERNEL - 1) / 2));
            }
            want.push((1, 2));
            want.push((1, 2));
        }
        for _ in 0..TRAILING {
            want.push((1, 2));
        }
        assert_eq!(found, want);
        let ones = found.iter().filter(|&&(d, _)| d == 1).count();
        assert_eq!(ones, 16);
        assert_eq!(found.len(), BLOCKS);
    }

    #[test]
    fn each_main_block_takes_its_own_slice_of_the_timestep_shifts() {
        // Four shifts of 512 out of one `[2048, 1, 1]` input, in block order. Reading the same
        // slice four times would condition every block on the first one's timestep - which at
        // step 0 of 16 is not even wrong by much, and gets worse as the step index grows.
        let (_, plan) = plan(FRAMES, CHARS);
        let shifts: Vec<u32> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::AddBroadcast, push, .. } => Some(push.out_c),
                _ => None,
            })
            .collect();
        assert_eq!(shifts, vec![CHANNELS; MAIN_BLOCKS]);
        // Four timestep slices and four style-key slices, each lowering to one copy.
        let copies = plan.ops.iter().filter(|op| matches!(op, Op::Copy { .. })).count();
        assert_eq!(copies, MAIN_BLOCKS * 2);
    }

    #[test]
    fn the_op_inventory_is_twenty_eight_convnext_blocks_and_eight_attentions() {
        let (_, plan) = plan(FRAMES, CHARS);
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0) += 1;
            }
        }
        // Three per ConvNeXt block, four per text attention (query, key, value, output),
        // three per style attention, plus the two projections. Everything but the 28 depthwise
        // convolutions is quantised - see `INT8_CONVS`.
        let convolutions = BLOCKS * 3 + MAIN_BLOCKS * (4 + 3) + 2;
        assert_eq!(counts.get("Conv"), Some(&(convolutions - INT8_CONVS)), "{counts:?}");
        assert_eq!(counts.get("ConvInt8"), Some(&INT8_CONVS), "{counts:?}");
        assert_eq!(counts.get("Rotary"), Some(&(MAIN_BLOCKS * 2)), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&(MAIN_BLOCKS * 2)), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&(MAIN_BLOCKS * 2)), "{counts:?}");
        assert_eq!(counts.get("Softmax"), Some(&(MAIN_BLOCKS * 2)), "{counts:?}");
        assert_eq!(counts.get("AddBroadcast"), Some(&MAIN_BLOCKS), "{counts:?}");
        assert_eq!(counts.get("LayerNorm"), Some(&(BLOCKS + MAIN_BLOCKS * 2)), "{counts:?}");
        // Half the residual adds fold into their producing pointwise's store: the 28
        // ConvNeXt tails and the 8 attention projections whose skip side is already
        // written when they run. The rest stay dispatches — see `Builder::add`.
        assert_eq!(counts.get("Add"), Some(&18), "{counts:?}");
        // No relative attention here: the positions are rotary.
        assert_eq!(counts.get("AttnScoresRelative"), None, "{counts:?}");
        assert_eq!(counts.get("Embed"), None, "{counts:?}");
        assert_eq!(counts.len(), 9, "{counts:?}");
    }

    #[test]
    fn nothing_changes_the_latent_length() {
        let (_, plan) = plan(FRAMES, CHARS);
        for (step, op) in plan.ops.iter().enumerate() {
            if let Op::Dispatch { kind: Kind::Conv, push, .. } = op {
                assert!(
                    push.out_w == FRAMES || push.out_w == CHARS || push.out_w == STYLE_TOKENS,
                    "step {step} produced {} positions",
                    push.out_w
                );
                assert_ne!(push.pad_edge, 0, "step {step} pads with zeros");
            }
        }
    }

    #[test]
    fn an_empty_latent_or_text_is_refused() {
        let source = Shapes::new(TENSORS);
        let error = build(&source, 0, CHARS).expect_err("no frames");
        assert!(error.contains("no frames"), "{error}");
        let source = Shapes::new(TENSORS);
        let error = build(&source, FRAMES, 0).expect_err("no characters");
        assert!(error.contains("no characters"), "{error}");
    }

    #[test]
    fn no_op_reads_a_region_of_the_arena_that_it_also_writes() {
        let (_, plan) = plan(FRAMES, CHARS);
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_arena_is_bounded_at_a_long_utterance() {
        // This is the net that decides how much device memory a voice needs: 2048 channels over
        // the latent, and an `[8, frames, chars]` score map four times.
        for (frames, chars) in [(FRAMES, CHARS), (216, 400)] {
            let (_, plan) = plan(frames, chars);
            let mib = plan.arena_elems as f32 * 2.0 / (1024.0 * 1024.0);
            println!("supertonic_sampler at {frames} frames, {chars} chars: {mib:.2} MiB");
            assert!(mib < 256.0, "{frames} frames wants {mib} MiB");
        }
    }

    /// The dual plan holds both branches: fourteen inputs, two outputs.
    ///
    /// Conditional branch first, in the order `bridge.rs` uploads them — the seven of
    /// [`build`] twice — and the two velocities out in the same order. Both branches'
    /// inputs are pinned, so the first branch's seven sit at the same offsets as the
    /// single plan's and the second branch's seven follow.
    #[test]
    fn the_dual_plan_holds_both_branches_in_order() {
        let source = Shapes::new(TENSORS);
        let dual = build_dual(&source, FRAMES, CHARS).expect("the dual plan builds");
        assert_eq!(dual.inputs.len(), 14);
        assert_eq!(dual.outputs.len(), 2);
        let single = plan(FRAMES, CHARS).1;
        assert_eq!(&dual.inputs[..7], &single.inputs[..]);
        // Same shapes in the same order for the second branch; different offsets,
        // since both branches' inputs are pinned.
        for (branch, first) in dual.inputs[7..].iter().zip(&single.inputs) {
            assert_eq!(branch.shape, first.shape);
        }
        assert_eq!(dual.inputs[0].shape, Shape::new(LATENT, 1, FRAMES));
        assert_eq!(dual.outputs[0].shape, Shape::new(LATENT, 1, FRAMES));
        assert_eq!(dual.outputs[1].shape, Shape::new(LATENT, 1, FRAMES));
        // The dual plan reads the same weights — replayed, not doubled — so the file
        // coverage is unchanged. Each branch reads every plan tensor once, and the
        // host tensors once per plan, so the ask count is two branches plus hosts.
        let asked = source.asked.borrow();
        assert_eq!(asked.len(), (TENSORS - 18) * 2 + 18);
        let mut indices: Vec<usize> = asked.iter().map(|(i, _)| *i).collect();
        indices.sort_unstable();
        indices.dedup();
        assert_eq!(indices, (0..TENSORS).collect::<Vec<usize>>());
    }

    /// The dual plan is two single plans back to back, op for op.
    ///
    /// Same dispatches in the same order per branch, so the second branch costs exactly
    /// what the first does and neither reads the other's arena. Checked structurally —
    /// the interpreter test below checks the numbers.
    #[test]
    fn the_dual_plan_is_two_single_plans_back_to_back() {
        let source = Shapes::new(TENSORS);
        let dual = build_dual(&source, FRAMES, CHARS).expect("the dual plan builds");
        let single = plan(FRAMES, CHARS).1;
        assert_eq!(dual.ops.len(), single.ops.len() * 2);
        // Both branches' inputs and outputs are pinned, so the dual arena holds two
        // branches' worth of live tensors rather than exactly twice one branch's
        // high-water mark — but it must stay well under the utterance budget.
        assert!(dual.arena_elems < 256 * 1024 * 1024 / 2, "{}", dual.arena_elems);
        assert!(dual.arena_elems >= single.arena_elems, "{}", dual.arena_elems);
        assert_no_aliasing(&dual);
    }

    /// Both branches of the dual plan compute their own velocity.
    ///
    /// Branch independence at the graph level: branch identity is carried by arena
    /// offsets, and the two branches' tensors must be disjoint. Checked structurally —
    /// every read of the second branch lands inside the second branch's own input or
    /// intermediate ranges, never inside the first branch's — rather than by running
    /// the whole sampler through the interpreter, which takes minutes at these shapes.
    #[test]
    fn the_dual_plan_computes_each_branch_from_its_own_inputs() {
        let source = Shapes::new(TENSORS);
        let dual = build_dual(&source, FRAMES, CHARS).expect("the dual plan builds");
        let single = plan(FRAMES, CHARS).1;
        let half = single.ops.len();
        assert_eq!(dual.ops.len(), half * 2);
        // The second branch's inputs are pinned at offsets 7..14 of the dual plan.
        // Every second-half read must land either inside those inputs or at/past the
        // second branch's own intermediates — never inside the first branch's range.
        let second_inputs: Vec<(u32, u32)> =
            dual.inputs[7..].iter().map(|b| (b.at, b.shape.len())).collect();
        let second_start = second_inputs.iter().map(|(at, _)| *at).min().unwrap_or(0);
        let in_second_inputs = |at: u32| {
            second_inputs.iter().any(|(base, len)| *base <= at && at < base + len)
        };
        for op in &dual.ops[half..] {
            match op {
                Op::Dispatch { kind, push, .. } => {
                    // `in1` is a path selector, not an operand, on the int8 GEMM.
                    let in1 = if *kind == Kind::ConvPointInt8 { 0 } else { push.in1 };
                    for read in [push.in0, in1] {
                        // `in1` is only meaningful on binary ops; the fused sentinel
                        // and the zero default of non-binary pushes are not reads.
                        if read == NO_FUSE || (read == 0 && push.in0 != 0) {
                            continue;
                        }
                        assert!(
                            in_second_inputs(read) || read >= second_start,
                            "second-branch op reads offset {read} inside the first branch"
                        );
                    }
                    if push.res != NO_FUSE {
                        assert!(
                            in_second_inputs(push.res) || push.res >= second_start,
                            "second-branch residual reads inside the first branch"
                        );
                    }
                    if push.shift != NO_FUSE {
                        assert!(
                            in_second_inputs(push.shift) || push.shift >= second_start,
                            "second-branch shift reads inside the first branch"
                        );
                    }
                }
                Op::Copy { src, .. } => {
                    assert!(
                        in_second_inputs(*src) || *src >= second_start,
                        "second-branch copy reads inside the first branch"
                    );
                }
            }
        }
        // And the outputs are one velocity per branch, at the two branch ends.
        assert_eq!(dual.outputs.len(), 2);
        assert_ne!(dual.outputs[0].at, dual.outputs[1].at);
    }
}
