// Device parity fixtures (kernels-d: Q2_K GEMM and vision). See `parity.rs` for the helpers.

/// The Q2_K GEMM over the word path with 4 lane slices summing the taps (`in1` 2) and unsplit:
/// ragged channel tiles, a 36-position map (a partial position tile whose idle lanes must still
/// reach the slice barrier), 8192 taps like MADLAD's FFN output projection, and shapes just past
/// the split's limits (64+ tiles, under 2048 taps).
#[test]
#[ignore = "needs a Vulkan device"]
fn q2k_gemm_tap_splits_agree_with_the_reference() {
    for (out_c, in_c, width, in1) in
        [(44u32, 8192u32, 32u32, 2u32), (52, 2048, 36, 2), (2050, 2048, 4, 0), (40, 1024, 36, 0)]
    {
        let blocks = in_c / crate::weights::Q2K_BLOCK;
        let bytes: Vec<u8> = (0..out_c * blocks * crate::weights::Q2K_BYTES)
            .map(|i| (i.wrapping_mul(2_654_435_761) >> 9) as u8)
            .collect();
        let pairs: Vec<f32> = (0..out_c * blocks * 2).map(|i| 0.04 + 0.01 * ((i * 3) % 7) as f32).collect();
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[
                Fixture::Q2K(vec![out_c, in_c], bytes),
                Fixture::F16(vec![out_c * blocks * 2], pairs),
                Fixture::F16(vec![out_c], spread(out_c as usize, 0.7)),
            ],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
        let input = spread((in_c * width) as usize, 0.35);
        let mut builder = Builder::new(&weights);
        let first = builder.input(Shape::new(in_c, 1, width));
        let last = builder.conv_q2k(first, 0, out_c, Act::Relu);
        let plan = builder.finish(&[last]).expect("the q2k gemm plan builds");
        assert!(plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPointQ2K, push, .. } if push.in1 == in1
        )));
        let what = format!("a q2k gemm {out_c}x{in_c} over {width}, split {in1}");
        compare(&what, plan, weights.data().to_vec(), &[&input]);
    }
}
