#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    fn plan() -> Plan {
        let source = Shapes::new(TENSORS);
        build(&source).unwrap_or_else(|e| panic!("{e}"))
    }

    fn counts(plan: &Plan) -> std::collections::BTreeMap<String, usize> {
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            let name = match op {
                Op::Copy { .. } => "Copy".to_string(),
                Op::Dispatch { kind, .. } => super::super::tests::name_of(*kind),
            };
            *counts.entry(name).or_insert(0) += 1;
        }
        counts
    }

    #[test]
    fn the_layout_constants_walk_the_whole_file() {
        // The cursor arithmetic in `build` is what actually enforces this; the constants
        // are restated here so a mis-sized `BLOCK_TENSORS` fails as a number rather than
        // as a shape mismatch deep in the stack.
        assert_eq!(BLOCK_TENSORS, 30);
        assert_eq!(BLOCK, 8);
        assert_eq!(FINAL_NORM, 8 + 8 * 30);
        assert_eq!(TENSORS, FINAL_NORM + 2 + 3 + 3 + 3);
        assert_eq!(TENSORS, 259);
        // Two globals, eight per block, three in the policy head.
        assert_eq!(INT8_CONVS, 69);
        // `build` returns `Err` rather than panicking if any cursor lands short.
        plan();
    }

    #[test]
    fn the_pass_reads_every_tensor_in_the_file() {
        // `Builder::finish` refuses a tensor that is neither read nor named, so a plan that
        // builds at all has covered the file. The two elo tables are the only host ones.
        let source = Shapes::new(TENSORS);
        assert!(build(&source).is_ok());
        assert!(build(&Shapes::new(TENSORS - 1)).is_err());
    }

    #[test]
    fn the_outputs_are_the_score_map_and_the_promotion_projection() {
        let plan = plan();
        let shapes: Vec<Shape> = plan.outputs.iter().map(|o| o.shape).collect();
        assert_eq!(shapes, vec![Shape::new(1, SQUARES, SQUARES), Shape::new(PROMOTIONS, 1, SQUARES)]);
        assert_eq!(plan.inputs.len(), 1);
        assert_eq!(plan.inputs[0].shape, Shape::new(INPUT, 1, SQUARES));
    }

    #[test]
    fn every_block_normalises_twice_with_an_rms_norm_and_never_with_a_layer_norm() {
        // Post-norm with RMSNorm inside the block: 16 RMS norms for the eight blocks, and
        // layer norms only in the bias generators (2 per block) and closing the stack.
        let counts = counts(&plan());
        assert_eq!(counts.get("RmsNorm"), Some(&(2 * BLOCKS)));
        assert_eq!(counts.get("LayerNorm"), Some(&(2 * BLOCKS + 1)));
    }

    #[test]
    fn the_bias_generator_is_a_grouped_convolution_the_head_split_can_read() {
        // Grouping is what keeps `Node::ConvInt8`'s pointwise fast path from taking this,
        // and is also the whole reason the einsum needs no transpose. It is therefore the
        // one op in the plan that is an untiled `ConvInt8` rather than a `ConvPointInt8`.
        let plan = plan();
        let grouped: Vec<&Op> = plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { kind: Kind::ConvInt8, .. }))
            .collect();
        assert_eq!(grouped.len(), BLOCKS);
        for op in grouped {
            let Op::Dispatch { push, .. } = op else { unreachable!() };
            assert_eq!(push.group, HEADS);
            assert_eq!(push.in_c, HEADS * BIAS_GEN);
            assert_eq!(push.out_c, BIAS_OUT);
        }
    }

    #[test]
    fn every_projection_is_int8_and_every_norm_is_not() {
        // The norms, the biases and the elo tables are the only fp16 left. A projection that
        // slipped back to `Builder::conv` would still build and still run, just twice the
        // size, and no shape would catch it.
        let counts = counts(&plan());
        let int8 = counts.get("ConvInt8").copied().unwrap_or(0)
            + counts.get("ConvPointInt8").copied().unwrap_or(0)
            + counts.get("ConvVecInt8").copied().unwrap_or(0);
        // Dispatches, not tensors, and the two differ by exactly the sharing: the token
        // projection, eight projections in each of eight blocks, three policy heads, and one
        // attention-bias expansion *per block* — but all eight of those read the one
        // replicated kernel at `SMOLGEN`, so the file holds `INT8_CONVS` kernels and the plan
        // runs seven more convolutions than that.
        assert_eq!(int8, 1 + BLOCKS * 9 + 3);
        assert_eq!(int8, INT8_CONVS + BLOCKS - 1);
        assert_eq!(counts.get("Conv"), None, "an fp16 convolution is left in the plan");
    }

    #[test]
    fn the_attention_bias_is_added_to_the_scaled_scores() {
        let plan = plan();
        let mut seen = 0;
        for pair in plan.ops.windows(3) {
            let [Op::Dispatch { kind: Kind::AttnScores, .. }, Op::Dispatch { kind: Kind::Add, push, .. }, Op::Dispatch { kind: Kind::Softmax, .. }] =
                pair
            else {
                continue;
            };
            assert_eq!((push.in_c, push.in_h, push.in_w), (HEADS, SQUARES, SQUARES));
            seen += 1;
        }
        assert_eq!(seen, BLOCKS);
    }

    #[test]
    fn the_policy_score_map_uses_the_models_own_scale() {
        // `attn_scores` derives `1 / sqrt(head_dim)`, and with one head over `HEAD_HIDDEN`
        // channels that is `1 / sqrt(256)` — upstream's `/ math.sqrt(head_hid_dim)`.
        let plan = plan();
        let last = plan
            .ops
            .iter()
            .rev()
            .find_map(|op| match op {
                Op::Dispatch { kind: Kind::AttnScores, push, .. } => Some(*push),
                _ => None,
            })
            .expect("a score map");
        assert_eq!(last.group, 1);
        let scale = f32::from_bits(last.param0_bits);
        assert!(
            (scale - 1.0 / (HEAD_HIDDEN as f32).sqrt()).abs() < 1e-9,
            "scale {scale}"
        );
    }

    #[test]
    fn no_op_reads_what_it_writes() {
        assert_no_aliasing(&plan());
    }

    #[test]
    fn the_elo_blend_is_upstreams_and_so_reads_backwards() {
        // At elo 0 the vector is `elo_embedding_high`, and at 5000 it is `_low`. The names
        // are upstream's and they are the wrong way round; the arithmetic is what matters.
        let blob = crate::weights::write_mixed(
            crate::weights::graph::MAIA,
            &[
                crate::weights::Fixture::F16(vec![1, ELO_DIM], vec![1.0; ELO_DIM as usize]),
                crate::weights::Fixture::F16(vec![1, ELO_DIM], vec![3.0; ELO_DIM as usize]),
            ],
        );
        let weights = crate::weights::Weights::parse(&blob, crate::weights::graph::MAIA)
            .expect("the fixture blob parses");
        let at_zero = elo_embedding(weights.reader(), 0.0).unwrap();
        let at_max = elo_embedding(weights.reader(), ELO_MAX).unwrap();
        let midpoint = elo_embedding(weights.reader(), ELO_MAX / 2.0).unwrap();
        assert!(at_zero.iter().all(|&v| (v - 3.0).abs() < 1e-3), "{:?}", &at_zero[..4]);
        assert!(at_max.iter().all(|&v| (v - 1.0).abs() < 1e-3), "{:?}", &at_max[..4]);
        assert!(midpoint.iter().all(|&v| (v - 2.0).abs() < 1e-3), "{:?}", &midpoint[..4]);
        // Past either end it clamps rather than extrapolating, as `torch.clamp` does.
        let beyond = elo_embedding(weights.reader(), 9000.0).unwrap();
        assert!(beyond.iter().all(|&v| (v - 1.0).abs() < 1e-3));
    }

    #[test]
    fn tokens_repeats_the_board_across_every_ply_and_broadcasts_the_elos() {
        // A board with one distinguishable value per plane-square, so a ply written in the
        // wrong place is visible.
        let planes: Vec<f32> =
            (0..PLANES * SQUARES).map(|i| i as f32).collect();
        let self_elo: Vec<f32> = (0..ELO_DIM).map(|i| 1000.0 + i as f32).collect();
        let oppo_elo: Vec<f32> = (0..ELO_DIM).map(|i| 2000.0 + i as f32).collect();
        let out = tokens(&planes, &self_elo, &oppo_elo).unwrap();
        assert_eq!(out.len(), INPUT as usize * SQUARES as usize);

        let stride = (PLANES * SQUARES) as usize;
        for ply in 0..HISTORY as usize {
            assert_eq!(&out[ply * stride..(ply + 1) * stride], &planes[..], "ply {ply}");
        }
        // Each elo channel is one value repeated across all 64 squares, and self comes
        // before oppo.
        let after = (PLANES * HISTORY * SQUARES) as usize;
        for (channel, &value) in self_elo.iter().enumerate() {
            let at = after + channel * SQUARES as usize;
            assert!(out[at..at + 64].iter().all(|&v| v == value));
        }
        let after_self = after + (ELO_DIM * SQUARES) as usize;
        for (channel, &value) in oppo_elo.iter().enumerate() {
            let at = after_self + channel * SQUARES as usize;
            assert!(out[at..at + 64].iter().all(|&v| v == value));
        }
    }

    #[test]
    fn tokens_refuses_a_board_or_an_elo_vector_of_the_wrong_length() {
        let planes = vec![0.0; (PLANES * SQUARES) as usize];
        let elo = vec![0.0; ELO_DIM as usize];
        assert!(tokens(&planes[..10], &elo, &elo).is_err());
        assert!(tokens(&planes, &elo[..10], &elo).is_err());
        assert!(tokens(&planes, &elo, &elo[..10]).is_err());
    }

    /// The graph-section round trip over the token projection and the bias path.
    ///
    /// The equivalence gate for phase 1 on a real net: record through the same `build`
    /// path the plan takes, emit the section bytes, parse and lower them from bytes
    /// alone, and require the two plans to agree op for op. Covers the token
    /// projection, the bias generator (pool, reshape, grouped conv), and the two
    /// norms — everything phase 1 serialises — stopping before the first attention,
    /// which is outside phase 1.
    #[test]
    fn the_token_projection_round_trips_through_a_graph_section() {
        use crate::weights::Graph;
        // The partial pass reads the elo pair, the token projection triple, and the
        // bias generator (projection triple, norm pair, projection triple, norm pair,
        // plus the shared smolgen kernel) — tensors 0..15, stopping before the first
        // encoder block. `record` enforces the every-tensor rule over the source's
        // count, so the stub is sized to that footprint rather than `TENSORS`: sizing
        // it to the whole file would demand the 244 tensors only the rest of the net
        // reads, which is the blanket naming the fixture comment above refuses.
        let partial = SMOLGEN + 3 + 2 + 3 + 2;
        let source = Shapes::new(partial);
        let head = &mut Layers { next: TOKEN_PROJECTION };
        let mut builder = Builder::new(&source);
        let b = &mut builder;
        b.host_tensor(ELO_LOW, &[1, ELO_DIM]);
        b.host_tensor(ELO_HIGH, &[1, ELO_DIM]);
        let tokens = b.input(Shape::new(INPUT, 1, SQUARES));
        let projected = point(b, head, tokens, WIDTH, Act::None);
        // The bias path of the first block: pool, two pointwise+GELU norms, grouped
        // expansion, reshape to the score-map layout.
        let bias = attention_bias(b, &mut Layers { next: SMOLGEN }, projected);
        // No blanket host naming: the section's host list is derived by elimination
        // from `Recorded.read`, and every named tensor must resolve in the table
        // the emitter inverts through. The token projection, bias path, and the two
        // elo tables are all consumed or named above; nothing else was asked, so
        // nothing else needs naming for the every-tensor rule.
        let table = source_table(&source);
        let recorded = builder
            .record(&[projected, bias], &table)
            .expect("the fixture records");
        let bytes =
            Graph::emit(&recorded, &table, &[projected, bias]).expect("the fixture emits");
        std::fs::write(
            std::env::temp_dir().join("maia_section.bin"),
            &bytes,
        )
        .expect("the section writes");
        let parsed = Graph::parse(&bytes, &table_tensors(&table)).expect("the section parses");
        let mut builder = Builder::new(&table);
        let outputs = parsed.lower(&mut builder, &table).expect("the section lowers");
        let plan = builder.finish(&outputs).expect("the lowered plan finishes");
        assert_eq!(plan.ops, recorded.plan.ops);
        assert_eq!(plan.inputs, recorded.plan.inputs);
        assert_eq!(plan.outputs, recorded.plan.outputs);
    }

    /// The `Shapes` stub's asked table, as an `Offsets` the emitter can invert through.
    ///
    /// The stub resolves `shaped` tensor `i` at element offset `i` and
    /// `shaped_words` tensor `i` at word offset `i` — but it does not record which
    /// view each ask used. Reconstruct it the way the builder calls: `conv_quantised`
    /// resolves the kernel through `shaped_words` and the scale/bias through
    /// `shaped`, in that order — kernel ask, scale ask, bias ask. So within each
    /// consecutive triple of asks where the first is 4-D, that triple is
    /// (kernel-word, scale-elem, bias-elem). Everything else is the element view.
    ///
    /// This is fragile by construction — it pattern-matches the builder's call
    /// sequence — and it is test-only: the real table never needs it, because real
    /// offsets are byte-distinct per tensor. If the builder's ask order changes,
    /// this breaks loudly (unresolvable emitter lookup), not silently.
    fn source_table(source: &Shapes) -> crate::weights::Offsets {
        use crate::weights::Dtype;
        let asked = source.asked.borrow();
        let is_kernel = |dims: &[u32]| dims.len() == 4;
        let mut views = vec![false; asked.len()];
        let mut i = 0;
        while i < asked.len() {
            if is_kernel(&asked[i].1)
                && asked.get(i + 1).is_some_and(|(_, d)| !is_kernel(d))
                && asked.get(i + 2).is_some_and(|(_, d)| !is_kernel(d))
            {
                views[i] = true;
                i += 3;
            } else {
                i += 1;
            }
        }
        let mut tensors = Vec::new();
        for ((index, dims), word) in asked.iter().zip(views) {
            let len: u32 = dims.iter().product();
            let mut full = [0u32; 4];
            for (d, dim) in dims.iter().enumerate().take(4) {
                full[d] = *dim;
            }
            tensors.push(crate::weights::Tensor {
                rank: dims.len() as u32,
                dims: full,
                offset: (*index as u32) * if word { 4 } else { 2 },
                len,
                dtype: if word { Dtype::I8 } else { Dtype::F16 },
            });
        }
        // No padding to the file length: every table entry must be either a weight
        // the nodes consume or a named host tensor, or `finish`'s every-tensor
        // rule refuses the lowered plan. The real file's trailing tensors are a
        // converter concern, not this fixture's.
        crate::weights::Offsets::from_test(tensors)
    }

    fn table_tensors(table: &crate::weights::Offsets) -> Vec<crate::weights::Tensor> {
        (0..table.len()).map(|i| table.tensor(i).expect("in range")).collect()
    }
}
