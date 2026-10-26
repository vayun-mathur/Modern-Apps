// Device parity fixtures for the int4 GEMM on the int8 dot product (`conv_point_int4_dot.comp`,
// staged by `quantize_blocks.comp`). See `parity.rs` for the helpers.
//
// Three checks per shape: the device against the reference's int8 path (same quantisation, so
// the usual tolerance), the device against the fp32 int4 reference within a bound derived from
// the activation rounding, and the fallback (the fp32 pipeline under the same push, which is
// what a device without the extension runs) against the fp32 reference.

fn int4_dot_fixture(label: &str, out_channels: u32, in_channels: u32, positions: u32, act: Act) {
    let blocks = in_channels / 32;
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 7 + i / 5) % 16 - 8) as i8).collect();
    let scales: Vec<f32> =
        (0..out_channels * blocks).map(|i| 0.007_812_5 * (1.0 + (i % 5) as f32)).collect();
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel.clone()),
            Fixture::F16(vec![out_channels, blocks], scales.clone()),
            Fixture::F16(vec![out_channels], spread(out_channels as usize, 1.3)),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread((in_channels * positions) as usize, 0.5);
    let build = |fp32: bool| {
        let mut builder = Builder::new(&weights);
        if fp32 {
            builder.fp32_int4();
        }
        let first = builder.input(Shape::new(in_channels, 1, positions));
        let last = builder.conv_int4(first, 0, out_channels, act);
        builder.finish(&[last]).expect("the int4 dot fixture plan builds")
    };
    let plan = build(false);
    let has = |plan: &Plan, kind: crate::nets::Kind| {
        plan.ops.iter().any(|op| matches!(op, crate::nets::Op::Dispatch { kind: k, .. } if *k == kind))
    };
    assert!(
        has(&plan, crate::nets::Kind::ConvPointInt4Dot) && has(&plan, crate::nets::Kind::QuantizeBlocks),
        "{label}: an aligned multi-position int4 GEMM must stage its input for the dot product",
    );
    crate::nets::tests::assert_no_aliasing(&plan);
    let data = weights.data().to_vec();
    let fp32 = run_multi(&build(true), &data, &[&input]).expect("the fp32 reference runs");
    let got = on_device(plan.clone(), data.clone(), &[&input]);
    if device().int_dot {
        compare(&format!("{label} vs the int8 reference"), plan.clone(), data.clone(), &[&input]);
    }
    let (fp32, got) = (&fp32[0], &got[0]);
    let mut worst = 0.0f32;
    for oc in 0..out_channels {
        for position in 0..positions {
            let mut bound = 0.0f32;
            for b in 0..blocks {
                let amax = (0..32)
                    .map(|t| input[((b * 32 + t) * positions + position) as usize].abs())
                    .fold(0.0f32, f32::max);
                let taps: f32 = (0..32)
                    .map(|t| f32::from(kernel[(oc * in_channels + b * 32 + t) as usize]).abs())
                    .sum();
                bound += scales[(oc * blocks + b) as usize] * taps * amax / 254.0;
            }
            let at = (oc * positions + position) as usize;
            let diff = (got[at] - fp32[at]).abs();
            worst = worst.max(diff);
            assert!(
                diff <= 1.2 * bound + 4e-3 * fp32[at].abs() + 1e-3,
                "{label}: channel {oc} position {position} is {} on the device and {} in fp32, \
                 past the int8 rounding bound {bound}",
                got[at],
                fp32[at],
            );
        }
    }
    eprintln!("{label}: max |int8 dot - fp32| = {worst}");
    let mut fallback = plan;
    for op in fallback.ops.iter_mut() {
        if let crate::nets::Op::Dispatch { kind, .. } = op {
            if *kind == crate::nets::Kind::ConvPointInt4Dot {
                *kind = crate::nets::Kind::ConvPointInt4;
            }
        }
    }
    compare(&format!("{label} on the fp32 fallback"), fallback, data, &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn the_int8_dot_int4_gemm_agrees_with_the_reference() {
    // Sliding `q_proj` at 200 tokens; a partial 32-position tile and a partial row tile under
    // GELU; the 12288-tap down projection (384 blocks) at the fewest positions that stage.
    int4_dot_fixture("an int8-dot 1536 -> 2048 gemm at 200 positions", 2048, 1536, 200, Act::None);
    int4_dot_fixture("an int8-dot ragged gemm at 36 positions", 1000, 2048, 36, Act::Gelu);
    int4_dot_fixture("an int8-dot 12288 -> 96 gemm at 8 positions", 96, 12288, 8, Act::None);
}

#[test]
fn int4_gemms_the_dot_shader_cannot_tile_stay_fp32() {
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![64, 1536, 1, 1], vec![1; 64 * 1536]),
            Fixture::F16(vec![64, 48], vec![0.01; 64 * 48]),
            Fixture::F16(vec![64], vec![0.0; 64]),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    // Ragged positions, one position (decode), and too few positions.
    for positions in [37, 1, 4] {
        let mut builder = Builder::new(&weights);
        let first = builder.input(Shape::new(1536, 1, positions));
        let last = builder.conv_int4(first, 0, 64, Act::None);
        let plan = builder.finish(&[last]).expect("the plan builds");
        assert!(
            plan.ops.iter().all(|op| !matches!(
                op,
                crate::nets::Op::Dispatch {
                    kind: crate::nets::Kind::ConvPointInt4Dot | crate::nets::Kind::QuantizeBlocks,
                    ..
                }
            )),
            "{positions} positions must not take the int8 dot path",
        );
    }
}
