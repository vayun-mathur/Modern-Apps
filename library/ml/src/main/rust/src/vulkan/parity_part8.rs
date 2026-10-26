// Device parity fixtures (runtime work). See `parity.rs` for the helpers.

/// A file whose plan reads only tensors deep inside it, behind big unused ones.
///
/// Tensors: 0 big, 1-2 an early gamma/beta, 3 big, 4-6 an int8 1x1 conv, 7-8 a late gamma/beta.
/// The big ones have odd lengths so the window's start is not a tensor boundary rounded by luck.
fn deep_window_fixture() -> (Weights, Plan, Plan) {
    const C: u32 = 64;
    const W: u32 = 9;
    let kernel: Vec<i8> = (0..C * C).map(|i| (i % 7) as i8 - 3).collect();
    let tensors = vec![
        Fixture::F16(vec![3_000_001], spread(3_000_001, 0.3)),
        Fixture::F16(vec![C], spread(C as usize, 1.7)),
        Fixture::F16(vec![C], spread(C as usize, 2.9)),
        Fixture::F16(vec![2_500_003], spread(2_500_003, 0.9)),
        Fixture::I8(vec![C, C, 1, 1], kernel),
        Fixture::F16(vec![C], vec![1.0 / 16.0; C as usize]),
        Fixture::F16(vec![C], spread(C as usize, 0.4)),
        Fixture::F16(vec![C], spread(C as usize, 1.1)),
        Fixture::F16(vec![C], spread(C as usize, 2.3)),
    ];
    let blob = write_mixed(graph::SUPERTONIC_VE, &tensors);
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let dims = |index: usize| -> Vec<u32> {
        match index {
            0 => vec![3_000_001],
            3 => vec![2_500_003],
            4 => vec![C, C, 1, 1],
            _ => vec![C],
        }
    };

    let mut b = Builder::new(&weights);
    let x = b.input(Shape::new(C, 1, W));
    let y = b.conv_int8(x, 4, C, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::Relu);
    let y = b.layer_norm(y, 7, 1e-5);
    for index in 0..4 {
        b.host_tensor(index, &dims(index));
    }
    let deep = b.finish(&[y]).expect("the deep plan builds");

    let mut b = Builder::new(&weights);
    let x = b.input(Shape::new(C, 1, W));
    let y = b.layer_norm(x, 1, 1e-5);
    for index in (0..9).filter(|&i| i != 1 && i != 2) {
        b.host_tensor(index, &dims(index));
    }
    let early = b.finish(&[y]).expect("the early plan builds");
    (weights, deep, early)
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_net_uploads_only_the_window_its_plan_reads() {
    let (weights, deep, early) = deep_window_fixture();
    let input = spread(64 * 9, 0.6);
    let host_deep = run_multi(&deep, weights.data(), &[&input]).expect("the interpreter runs");
    let host_early = run_multi(&early, weights.data(), &[&input]).expect("the interpreter runs");
    let total = weights.data().len() as u64;
    let offset_of = |index: usize| u64::from(weights.tensor(index).expect("it exists").offset);
    let kernel_at = offset_of(4);

    let mut net = Net::new(device(), deep.clone(), &weights, RESCALE_ONLY).expect("it records");
    let (lo, hi) = net.weights_window();
    assert!(lo > 0 && lo <= kernel_at && kernel_at - lo < 256, "window starts at {lo}");
    assert_eq!(hi, total, "the last tensor is read, so the window runs to the end");
    assert!(hi - lo < total / 100, "{} of {total} bytes uploaded", hi - lo);
    let windowed = net.infer_raw(&input).expect("the windowed net runs");
    matches("a windowed net", &host_deep, &windowed);

    let mut whole = Net::whole(device(), deep.clone(), &weights, RESCALE_ONLY).expect("it records");
    assert_eq!(whole.weights_window(), (0, total));
    let all = whole.infer_raw(&input).expect("the whole-file net runs");
    assert_eq!(windowed, all, "the window changes where bytes live, not what is computed");

    // Outside the window: refused, and the net keeps working at the plan it had.
    let refused = net.rebuild(early.clone());
    assert!(refused.is_err(), "a plan reading below the window was installed");
    let again = net.infer_raw(&input).expect("the net survives the refused rebuild");
    assert_eq!(again, windowed);

    let mut both = Net::covering(device(), deep, &[&early], &weights, RESCALE_ONLY)
        .expect("the covering net records");
    let (lo, _) = both.weights_window();
    assert!(lo <= offset_of(1) && lo > 0, "covering window at {lo}");
    matches("a covering net", &host_deep, &both.infer_raw(&input).expect("it runs"));
    both.rebuild(early).expect("the covered plan installs");
    matches("a covering net rebuilt", &host_early, &both.infer_raw(&input).expect("it runs"));
}

/// A logits row at NLLB scale, with the peak tied across workgroup slices.
///
/// `count` logits of `spread` values with the maximum planted at `peaks`: the device must
/// report the FIRST peak whatever slice it falls in. The winners are also asserted as the
/// `[lo, hi]` pair the host reconstructs with `hi * 2048 + lo`.
fn argmax_fixture(count: usize, peaks: &[u32]) -> (Plan, Vec<u8>, Vec<f32>) {
    let mut logits = spread(count, 0.7);
    let peak = logits.iter().fold(f32::NEG_INFINITY, |top, &v| top.max(v)) + 1.0;
    for &at in peaks {
        logits[at as usize] = peak;
    }
    let source = Invented::new(0);
    let mut builder = Builder::new(&source);
    let row = builder.input(Shape::new(count as u32, 1, 1));
    let id = builder.argmax(row);
    let plan = builder.finish(&[id]).expect("the argmax plan builds");
    (plan, source.into_data(), logits)
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_two_stage_argmax_keeps_the_first_of_ties_across_slices() {
    // 262,144 = 64 groups of 4,096: the full multi-workgroup regime, with the peak tied
    // across the first slice boundary, a middle slice and the last element, plus a ragged
    // count and a small row that reduces in a single group. (No peak at index 0: the
    // `[0, 0]` answer that gives is indistinguishable from an unwritten tensor, which the
    // parity guard refuses.)
    for (count, peaks) in [
        (262_144usize, vec![4096u32, 131_073, 262_143]),
        (262_143usize, vec![7u32, 200_000]),
        // NLLB's exact vocabulary: 63 groups with a short tail group, ties in the
        // first, a middle and the tail slice.
        (256_206usize, vec![4_000u32, 128_104, 256_205]),
        (1_000usize, vec![999u32, 3]),
    ] {
        let (plan, data, logits) = argmax_fixture(count, &peaks);
        let want = peaks.iter().copied().min().expect("a peak");
        let host = run_multi(&plan, &data, &[&logits]).expect("the interpreter runs");
        let got = on_device(plan, data, &[&logits]);
        matches(&format!("a two-stage argmax over {count} logits"), &host, &got);
        let pair = got.into_iter().next().expect("one output");
        assert_eq!(pair.len(), 2, "the [lo, hi] pair");
        let id = pair[1] as u32 * 2048 + pair[0] as u32;
        assert_eq!(id, want, "the first peak of {count} logits");
    }

    // Two leaders closer than the fp16 input can hold: 20.00005 and 20.00015 both round
    // to fp16 20.0 on upload, so the row holds a genuine tie and the FIRST index must win
    // — on the host and on the device alike. (Above the background, whose scaled values
    // top out near 16.)
    {
        const COUNT: usize = 262_144;
        let mut logits = spread(COUNT, 2.1);
        for value in logits.iter_mut() {
            *value = (*value - 0.1) * 20.0;
        }
        logits[5_000] = 20.00005;
        logits[200_000] = 20.00015;
        let source = Invented::new(0);
        let mut builder = Builder::new(&source);
        let row = builder.input(Shape::new(COUNT as u32, 1, 1));
        let id = builder.argmax(row);
        let plan = builder.finish(&[id]).expect("the near-tie plan builds");
        let data = source.into_data();
        let host = run_multi(&plan, &data, &[&logits]).expect("the interpreter runs");
        let got = on_device(plan, data, &[&logits]);
        matches("a sub-fp16 near-tie across slices", &host, &got);
        let pair = got.into_iter().next().expect("one output");
        let id = pair[1] as u32 * 2048 + pair[0] as u32;
        assert_eq!(id, 5_000, "the first of an fp16 tie");
    }
}

/// `Builder::widen_int8`: the int8 GEMM over fp16-widened weights (`in1` 3 and 4) is
/// bit-identical to the int8 paths and agrees with the reference. 512 x 520 / 517 positions take
/// the four-row path, 40 x 52 / 49 the split one; the widths cover aligned and ragged loads.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_widened_int8_gemm_matches_the_int8_paths_bit_for_bit() {
    for (out_channels, widths, path) in [(512u32, [520u32, 517], 3u32), (40, [52, 49], 4)] {
        let in_channels = 64u32;
        let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
            .map(|i| ((i * 37) % 255 - 127) as i8)
            .collect();
        let scales: Vec<f32> =
            (0..out_channels).map(|c| 0.007_812_5 * (1.0 + (c % 7) as f32)).collect();
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[
                Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
                Fixture::F16(vec![out_channels], scales),
                Fixture::F16(vec![out_channels], spread(out_channels as usize, 0.5)),
            ],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
        for width in widths {
            let input = spread((in_channels * width) as usize, 1.3);
            let mut builder = Builder::new(&weights);
            builder.widen_int8();
            let first = builder.input(Shape::new(in_channels, 1, width));
            let last = builder.conv_int8(
                first, 0, out_channels, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::Gelu,
            );
            let plan = builder.finish(&[last]).expect("the widened plan builds");
            assert_eq!(plan.widen.len(), 1);
            assert!(plan.ops.iter().any(|op| matches!(
                op,
                crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPointInt8, push, .. }
                    if push.in1 == path
            )));
            let narrow = Plan { widen: Vec::new(), ..plan.clone() };
            let int8 = on_device(narrow, weights.data().to_vec(), &[&input]);
            let what = format!("a widened int8 gemm, {out_channels} x {width}");
            let host = run_multi(&plan, weights.data(), &[&input]).expect("the interpreter runs");
            let widened = on_device(plan, weights.data().to_vec(), &[&input]);
            matches(&what, &host, &widened);
            assert!(
                int8.iter().flatten().zip(widened.iter().flatten()).all(|(a, b)| a.to_bits() == b.to_bits()),
                "{what}: the widened path differs from the int8 path"
            );
        }
    }
}

/// Record cost of row-mode reductions by kind and width (24 dispatches each, 512-long rows):
/// prints how long `Net::rebuild` takes to re-record at `width` rows. Measures, asserts nothing.
#[test]
#[ignore = "needs a Vulkan device"]
fn report_row_reduce_record_cost() {
    type Make = fn(&mut Builder, Id) -> Id;
    let kinds: [(&str, Make, usize, bool); 4] = [
        ("rms_norm", |b, x| b.rms_norm(x, 0, 1e-5), 1, false),
        ("layer_norm", |b, x| b.layer_norm(x, 0, 1e-5), 2, false),
        ("softmax", |b, x| b.softmax(x), 0, true),
        ("rms_norm", |b, x| b.rms_norm(x, 0, 1e-5), 1, false),
    ];
    for (name, make, count, rows_in_channels) in kinds {
        for width in [1u32, 16, 68, 200] {
            let source = Invented::new(count);
            let plan_at = |rows: u32| {
                let shape =
                    if rows_in_channels { Shape::new(rows, 1, 512) } else { Shape::new(512, 1, rows) };
                let mut b = Builder::new(&source);
                let mut x = b.input(shape);
                for _ in 0..24 {
                    x = make(&mut b, x);
                }
                b.finish(&[x]).expect("the probe plan builds")
            };
            let (small, big) = (plan_at(1), plan_at(width));
            let weights = Weights::from_data(source.into_data());
            let mut net = Net::new(device(), small, &weights, RESCALE_ONLY).expect("the net records");
            let started = std::time::Instant::now();
            net.rebuild(big).expect("the net re-records");
            println!(
                "record-probe {name} rows {width}: rebuild {:.2} ms for 24 dispatches",
                started.elapsed().as_secs_f64() * 1e3
            );
        }
    }
}
