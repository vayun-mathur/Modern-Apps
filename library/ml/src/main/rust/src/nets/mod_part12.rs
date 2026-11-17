    /// The folded plan carries the residual and the shift into the store.
    ///
    /// `nets::reference` serves fused pushes from the same arms as unfolded ones. This
    /// builds the residual-plus-shift graph over a real weights file — one tensor per
    /// slot, no overlaps — runs it through the host interpreter, and checks the fused
    /// store added both addends: outputs differ per position by exactly the input
    /// differences, and per channel pair by exactly the shift difference. An addend the
    /// fold dropped would show up as a missing difference.
    #[test]
    fn a_folded_plan_matches_its_unfolded_numbers() {
        use crate::nets::reference;
        // Four tensors, one per slot: two kernels and two biases. The shift input is
        // a plan input rather than a weight. Built directly — `Offsets` has no public
        // constructor — by parsing a hand-made `.maml` header, which also exercises
        // the real `WeightSource` path instead of the `Shapes` stub.
        let header = maml_header(&[
            (&[4, 2, 1, 1], crate::weights::DTYPE_F16),
            (&[4], crate::weights::DTYPE_F16),
            (&[2, 4, 1, 1], crate::weights::DTYPE_F16),
            (&[2], crate::weights::DTYPE_F16),
        ]);
        let parsed = crate::weights::Weights::parse(&header, crate::weights::graph::SELFIE)
            .expect("the hand-made header parses");
        let table = parsed.offsets();
        let mut builder = Builder::new(&table);
        let x = builder.input(Shape::new(2, 1, 2));
        let shift_in = builder.input(Shape::new(2, 1, 1));
        let widened = builder.conv_same(x, 0, 4, 1, 1, Act::None);
        let narrowed = builder.conv_same(widened, 2, 2, 1, 1, Act::None);
        let residual = builder.add(x, narrowed);
        let out = builder.add_channel(residual, shift_in);
        let plan = builder.finish(&[out]).expect("builds");
        assert!(
            plan.ops.iter().all(|op| !matches!(
                op,
                Op::Dispatch { kind: Kind::Add | Kind::AddBroadcast, .. }
            )),
            "test setup: both binaries should have folded: {:?}",
            plan.ops
        );
        // Kernels all ones, biases all 0.5 — every value exactly representable in fp16.
        // Laid out per the parsed table's own offsets: kernel0 at bytes 0..16, bias0
        // at 16..24, kernel1 at 32..48, bias1 at 48..52. (Each tensor starts
        // 16-aligned, so kernel1 pads to 32.)
        let half = |v: f32| crate::preprocess::f32_to_f16(v).to_le_bytes();
        let mut blob = vec![0u8; 52];
        for (offset, count, value) in [(0, 8, 1.0f32), (16, 4, 0.5), (32, 8, 1.0), (48, 2, 0.5)] {
            for e in 0..count {
                blob[offset + e * 2..offset + e * 2 + 2].copy_from_slice(&half(value));
            }
        }
        let x_values = [1.0f32, 2.0, 3.0, 4.0];
        let shift_values = [10.0f32, 20.0];
        let x_slice: &[f32] = &x_values;
        let shift_slice: &[f32] = &shift_values;
        let outputs =
            reference::run_multi(&plan, &blob, &[x_slice, shift_slice]).expect("runs");
        assert_eq!(outputs.len(), 1);
        // widened[c][p] = sum(x) + 0.5 = 10.5; narrowed[o][p] = 2 * 10.5 + 0.5 = 21.5;
        // out = narrowed + x + shift = 21.5 + x + shift, per channel: the contraction
        // is over the input's 2 channels, not the output's 4.
        //
        // The residual (x) and the shift are separate addends in the fused store, so
        // the differences pin each: positions in a channel differ by exactly the input
        // difference, and channel pairs differ by exactly the shift difference plus
        // the input difference. A dropped addend would zero one of those deltas.
        // `outputs[0]` is `[c, 1, w]` flattened channel-major: index `c * w + p`.
        // Hand-evaluated: x is channel 0 = [1, 2], channel 1 = [3, 4].
        // widened[c][p] = x[0][p] + x[1][p] + 0.5 = 4.5 / 6.5 per position;
        // narrowed[o][p] = 4 * widened + 0.5 = 18.5 / 26.5; out adds the residual
        // x and the shift [10, 20]: [29.5, 38.5, 41.5, 50.5].
        //
        // Same channel, adjacent positions differ by the narrowed delta (8.0) plus
        // the input delta (1.0); same position, adjacent channels differ by the
        // shift (10.0) plus the input (2.0). A dropped addend would zero one of
        // those deltas.
        let deltas = [
            (outputs[0][1] - outputs[0][0], 9.0),
            (outputs[0][3] - outputs[0][2], 9.0),
            (outputs[0][2] - outputs[0][0], 12.0),
            (outputs[0][3] - outputs[0][1], 12.0),
        ];
        for (found, wanted) in deltas {
            assert!((found - wanted).abs() < 0.06, "delta got {found}, want {wanted}");
        }
        // And the absolute level pins the convolution itself.
        assert!((outputs[0][0] - 29.5).abs() < 0.06, "level {:?}", outputs[0]);
    }

    /// A `.maml` header + table for `shapes`, with an empty data section.
    ///
    /// Test-only builder for [`a_folded_plan_matches_its_unfolded_numbers`]: `Offsets`
    /// has no public constructor, so the table is parsed the way a shipped asset is.
    /// `graph::SELFIE` is arbitrary — the id only has to match the parse call.
    ///
    /// `Weights::parse` requires the file to end exactly where the data section does,
    /// so the returned vector is padded with zeros to the declared length.
    #[cfg(test)]
    fn maml_header(shapes: &[(&[u32], u32)]) -> Vec<u8> {
        use crate::weights::graph;
        let count = shapes.len() as u32;
        let table_bytes = shapes.len() * 32;
        let mut data_len = 0usize;
        for (dims, _) in shapes {
            let len: usize = dims.iter().map(|&d| d as usize).product();
            data_len = (data_len + len * 2).next_multiple_of(16);
        }
        let mut bytes = vec![0u8; 64 + table_bytes + data_len];
        bytes[0..4].copy_from_slice(b"MAML");
        bytes[4..8].copy_from_slice(&1u32.to_le_bytes());
        bytes[8..12].copy_from_slice(&graph::SELFIE.to_le_bytes());
        bytes[12..16].copy_from_slice(&count.to_le_bytes());
        bytes[48..52].copy_from_slice(&(64 + table_bytes as u32).to_le_bytes());
        for (i, (dims, dtype)) in shapes.iter().enumerate() {
            let at = 64 + i * 32;
            bytes[at..at + 4].copy_from_slice(&(dims.len() as u32).to_le_bytes());
            for (d, dim) in dims.iter().enumerate() {
                bytes[at + 4 + d * 4..at + 8 + d * 4].copy_from_slice(&dim.to_le_bytes());
            }
            bytes[at + 20..at + 24].copy_from_slice(&dtype.to_le_bytes());
            // 16-aligned running offset: tensor 0 at 0, each later tensor after the
            // previous one's padded end. Payloads are fp16 here, two bytes an element.
            let mut offset = 0usize;
            for (prev, _) in &shapes[..i] {
                let prev_len: usize = prev.iter().map(|&d| d as usize).product();
                offset = (offset + prev_len * 2).next_multiple_of(16);
            }
            let len: usize = dims.iter().map(|&d| d as usize).product();
            bytes[at + 24..at + 28].copy_from_slice(&(offset as u32).to_le_bytes());
            bytes[at + 28..at + 32].copy_from_slice(&(len as u32).to_le_bytes());
        }
        bytes[52..56].copy_from_slice(&(data_len as u32).to_le_bytes());
        bytes
    }

    /// The fallback flag parses without touching the environment.
    ///
    /// `quant_fold_on` takes the raw setting value so this test pins the
    /// default-on contract with no `set_var`: fusion is the shipping path,
    /// and only an explicit opt-out disables it.
    #[test]
    fn the_quantize_fold_is_on_unless_explicitly_disabled() {
        use crate::nets::quant_fold_on;
        assert!(quant_fold_on(None));
        assert!(quant_fold_on(Some("1".into())));
        assert!(quant_fold_on(Some("yes".into())));
        assert!(!quant_fold_on(Some("0".into())));
        assert!(!quant_fold_on(Some("off".into())));
        assert!(!quant_fold_on(Some("false".into())));
    }

    /// A single-consumer quantize folds into its producing convolution.
    ///
    /// `conv_int8 -> quantize`: one convolution dispatch carrying the scale in
    /// `param0_bits`, and no `Quantize` op. The fused convolution writes the
    /// quantize's own output tensor.
    #[test]
    fn a_single_consumer_quantize_folds_into_the_producing_store() {
        use crate::nets::Kind;
        use crate::nets::tests::Shapes;
        let source = Shapes::new(4);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let projected = builder.conv_int8(x, 0, 4, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
        let out = builder.quantize(projected, 0.5);
        // Tensor 3 is the file's spare: `finish` refuses an unread tensor.
        builder.host_tensor(3, &[1]);
        let plan = builder.finish(&[out]).expect("builds");
        assert!(
            plan.ops.iter().all(|op| !matches!(op, Op::Dispatch { kind: Kind::Quantize, .. })),
            "the quantize should have folded: {:?}",
            plan.ops
        );
        let fused = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { push, .. } if push.param0_bits != 0 => Some(push),
                _ => None,
            })
            .collect::<Vec<_>>();
        assert_eq!(fused.len(), 1, "exactly one quant-fused store: {fused:?}");
        assert_eq!(fused[0].out, plan.outputs[0].at);
        assert_eq!(f32::from_bits(fused[0].param0_bits), 0.5);
    }

    /// A quantize whose input has a second reader stays its own dispatch.
    ///
    /// Folding it would leave the other reader with no writer: the producer's
    /// old output tensor is never allocated once the fold moves the rounding.
    #[test]
    fn a_shared_convolution_output_keeps_its_quantize() {
        use crate::nets::Kind;
        use crate::nets::tests::Shapes;
        let source = Shapes::new(5);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let projected = builder.conv_int8(x, 0, 4, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
        let rounded = builder.quantize(projected, 0.5);
        // A second reader of the convolution's output: the folded store would orphan it.
        let mixed = builder.mul(rounded, projected);
        builder.host_tensor(3, &[1]);
        builder.host_tensor(4, &[1]);
        let plan = builder.finish(&[mixed]).expect("builds");
        assert_eq!(
            plan.ops.iter().filter(|op| matches!(op, Op::Dispatch { kind: Kind::Quantize, .. })).count(),
            1,
            "the shared quantize must survive: {:?}",
            plan.ops
        );
    }

    /// A quantize held as a plan output stays its own dispatch.
    ///
    /// Nothing downstream reads the producer's output, but the host does — via
    /// the quantize, which folding would remove.
    #[test]
    fn a_quantize_that_is_a_plan_output_keeps_its_dispatch() {
        use crate::nets::Kind;
        use crate::nets::tests::Shapes;
        let source = Shapes::new(5);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let projected = builder.conv_int8(x, 0, 4, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
        let rounded = builder.quantize(projected, 0.5);
        builder.host_tensor(3, &[1]);
        builder.host_tensor(4, &[1]);
        let plan = builder.finish(&[projected, rounded]).expect("builds");
        assert_eq!(
            plan.ops.iter().filter(|op| matches!(op, Op::Dispatch { kind: Kind::Quantize, .. })).count(),
            1,
            "the output quantize must survive: {:?}",
            plan.ops
        );
    }

    /// A chained `quantize(quantize(x))` folds only the inner one.
    ///
    /// The outer scale would otherwise be dropped: the producer carries one
    /// `quant_scale`, so the second fold finds it occupied and stops.
    #[test]
    fn a_chained_quantize_folds_once_and_keeps_the_outer() {
        use crate::nets::Kind;
        use crate::nets::tests::Shapes;
        let source = Shapes::new(4);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let projected = builder.conv_int8(x, 0, 4, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
        let inner = builder.quantize(projected, 0.5);
        let out = builder.quantize(inner, 0.25);
        builder.host_tensor(3, &[1]);
        let plan = builder.finish(&[out]).expect("builds");
        assert_eq!(
            plan.ops.iter().filter(|op| matches!(op, Op::Dispatch { kind: Kind::Quantize, .. })).count(),
            1,
            "the outer quantize must survive: {:?}",
            plan.ops
        );
    }

    /// A quantize after a norm folds into the norm's store.
    ///
    /// The QI shape: `rms_norm -> quantize`, one dispatch carrying the scale.
    #[test]
    fn a_quantize_after_a_norm_folds_into_the_norm_store() {
        use crate::nets::Kind;
        use crate::nets::tests::Shapes;
        let source = Shapes::new(2);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let normed = builder.rms_norm(x, 0, 1e-6);
        let out = builder.quantize(normed, 0.25);
        builder.host_tensor(1, &[1]);
        let plan = builder.finish(&[out]).expect("builds");
        assert!(
            plan.ops.iter().all(|op| !matches!(op, Op::Dispatch { kind: Kind::Quantize, .. })),
            "the norm-fed quantize should have folded: {:?}",
            plan.ops
        );
        let fused = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::RmsNorm, push, .. } => Some(push),
                _ => None,
            })
            .collect::<Vec<_>>();
        assert_eq!(fused.len(), 1, "one norm: {fused:?}");
        assert_eq!(f32::from_bits(fused[0].param0_bits), 0.25);
    }

    /// A fused quantize store computes what the unfolded pair computes.
    ///
    /// The reference serves fused pushes from the same arms as unfolded ones,
    /// so this runs a folded `rms_norm -> quantize` plan (one dispatch) over
    /// a real weights file and checks the outputs against hand-computed
    /// norm-then-round-trip values. The fused form rounds once where the
    /// unfolded pair would round twice, so this asserts close agreement
    /// (well inside half an int8 step), not bit-identity. Bit-identity
    /// against live is the parity harness's job.
    #[test]
    fn a_fused_quantize_store_matches_its_unfolded_numbers() {
        use crate::nets::reference;
        // Two tensors: the norm gamma and the file's spare. Built directly —
        // `Offsets` has no public constructor — by parsing a hand-made `.maml`
        // header, which also exercises the real `WeightSource` path.
        let header = maml_header(&[
            (&[4], crate::weights::DTYPE_F16),
            (&[1], crate::weights::DTYPE_F16),
        ]);
        let parsed = crate::weights::Weights::parse(&header, crate::weights::graph::SELFIE)
            .expect("the hand-made header parses");
        let table = parsed.offsets();
        let mut builder = Builder::new(&table);
        let x = builder.input(Shape::new(4, 1, 4));
        let normed = builder.rms_norm(x, 0, 1e-6);
        let out = builder.quantize(normed, 0.25);
        builder.host_tensor(1, &[1]);
        let plan = builder.finish(&[out]).expect("builds");
        // Gamma all ones. Inputs exactly representable in fp16, so the upload
        // round-trips losslessly and the hand computation below is in f64.
        let half = |v: f32| crate::preprocess::f32_to_f16(v).to_le_bytes();
        let mut blob = vec![0u8; 16];
        for e in 0..4 {
            blob[e * 2..e * 2 + 2].copy_from_slice(&half(1.0));
        }
        // Channel-major `[c, 1, w]`: channel c holds `x_values[c] + position`.
        let x_values = [0.5f64, 1.5, -2.0, 3.0];
        let input: Vec<f32> =
            x_values.iter().flat_map(|&c| (0..4).map(move |p| (c + p as f64 * 0.5) as f32)).collect();
        let outputs = reference::run_multi(&plan, &blob, &[&input]).expect("runs");
        assert_eq!(outputs.len(), 1);
        assert_eq!(outputs[0].len(), 16);
        for (c, &base) in x_values.iter().enumerate() {
            for p in 0..4 {
                let column: Vec<f64> =
                    x_values.iter().map(|&cc| cc + p as f64 * 0.5).collect();
                let mean_sq = column.iter().map(|v| v * v).sum::<f64>() / 4.0;
                let normed = (base + p as f64 * 0.5) / (mean_sq + 1e-6).sqrt();
                let q = ((normed / 0.25 + 0.5).floor()).clamp(-128.0, 127.0);
                let want = q * 0.25;
                let got = outputs[0][c * 4 + p] as f64;
                assert!((got - want).abs() < 0.06, "channel {c} position {p}: got {got}, want {want}");
            }
        }
    }

