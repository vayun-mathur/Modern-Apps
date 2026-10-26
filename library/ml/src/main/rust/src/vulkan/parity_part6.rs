// Device parity fixtures (vision and Supertonic work). See `parity.rs` for the helpers.

// Tiled spatial convolutions (`shaders/conv_tiled.glsl` via `Builder::spatial_tiling`).
//
// Each fixture builds a one- or two-op plan with tiling opted in and requires the
// device to agree with the host interpreter, which serves `Kind::Conv` from the
// untiled `conv` — so these compare the two shader paths against each other through
// the same oracle. Sizes are small enough for the debug interpreter but ragged
// against the 32x32 tile on purpose: channels, positions and rows that are not
// multiples of the tile are where the clamping and masking go wrong.
//
// Real-size coverage comes from `profile_vision` checksums on the Pixel (the bar
// requires end-to-end outputs unchanged there); a whole 320x320 map through the
// debug interpreter is hours, not a fixture.

/// A dense 3x3 at stride 1 with ragged channels and positions: 24 channels is three
/// quarters of a channel tile, 36x36 is 1296 positions with a 16 tail.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_dense_conv_agrees_with_the_reference() {
    let input = spread(8 * 36 * 36, 0.0);
    agrees_invented(
        "a tiled dense 3x3",
        2,
        &[Shape::new(8, 36, 36)],
        &[&input],
        |b, ids| {
            b.spatial_tiling();
            b.conv(ids[0], 0, 24, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 1, Act::Relu)
        },
    );
}

/// A strided 3x3 with the selfie stem's asymmetric padding: nothing above or left,
// one below and right. Symmetric padding gives the same output size and a mask
// shifted half a pixel, so the asymmetry is the thing under test.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_strided_conv_keeps_its_asymmetric_padding() {
    let input = spread(8 * 32 * 32, 1.0);
    agrees_invented(
        "a tiled strided 3x3 with asymmetric pads",
        2,
        &[Shape::new(8, 32, 32)],
        &[&input],
        |b, ids| {
            b.spatial_tiling();
            b.conv(ids[0], 0, 16, (3, 3), (2, 2), (1, 1), (0, 0, 1, 1), 1, Act::Relu)
        },
    );
}

/// Depthwise 3x3s at strides 1 and 2, the SCRFD/MobileFaceNet shape: one group per
/// channel, so the kernel is `[channels, 1, 3, 3]` and each output channel must see
/// exactly one input channel.
#[test]
#[ignore = "needs a Vulkan device"]
fn tiled_depthwise_convs_agree_with_the_reference() {
    let input = spread(16 * 24 * 24, 2.0);
    agrees_invented(
        "tiled depthwise 3x3s",
        4,
        &[Shape::new(16, 24, 24)],
        &[&input],
        |b, ids| {
            b.spatial_tiling();
            let x = b.conv(ids[0], 0, 16, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 16, Act::Relu);
            b.conv(x, 2, 16, (3, 3), (2, 2), (1, 1), (1, 1, 1, 1), 16, Act::Relu)
        },
    );
}

/// A dilated 3x3 (U^2-Netp's RSU bottoms) and a dense 5x5 (MODNet's branch heads),
/// both with ragged extents: 30x30 is 900 positions with a 4 tail.
#[test]
#[ignore = "needs a Vulkan device"]
fn tiled_dilated_and_5x5_convs_agree_with_the_reference() {
    let input = spread(12 * 30 * 30, 3.0);
    agrees_invented(
        "tiled dilated 3x3 and dense 5x5",
        4,
        &[Shape::new(12, 30, 30)],
        &[&input],
        |b, ids| {
            b.spatial_tiling();
            let x = b.conv(ids[0], 0, 12, (3, 3), (1, 1), (2, 2), (2, 2, 2, 2), 1, Act::Relu);
            b.conv(x, 2, 12, (5, 5), (1, 1), (1, 1), (2, 2, 2, 2), 1, Act::Relu)
        },
    );
}

/// A grouped 3x3 that is neither dense nor depthwise (two channels per group, the
/// MobileFaceNet stem shape), with a fused PReLU: the slope read is per channel,
// so a row-indexing error lands two layers on one slope's value.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_grouped_conv_with_prelu_agrees_with_the_reference() {
    let input = spread(8 * 20 * 20, 4.0);
    agrees_invented(
        "a tiled grouped 3x3 with PReLU",
        3,
        &[Shape::new(8, 20, 20)],
        &[&input],
        |b, ids| {
            b.spatial_tiling();
            // Weight at 0, bias at 1, slope at 2: `conv` reads the bias after the
            // weight, and the slope index rides the activation.
            b.conv(ids[0], 0, 8, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 4, Act::PRelu(2))
        },
    );
}

/// A tiled convolution whose consumer is a residual add: `Builder::finish` folds
/// the add into the tiled store via `res`, so this exercises the fused addend at
/// the tiled block's flat index rather than only the plain store.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_conv_with_a_fused_residual_agrees_with_the_reference() {
    let input = spread(8 * 24 * 24, 5.0);
    agrees_invented(
        "a tiled 3x3 with a fused residual",
        2,
        &[Shape::new(8, 24, 24)],
        &[&input],
        |b, ids| {
            b.spatial_tiling();
            let projected =
                b.conv(ids[0], 0, 8, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 1, Act::None);
            b.add(ids[0], projected)
        },
    );
}

/// A row-mode instance norm at a MODNet-like shape: 32 channels of 128x128.
/// Planes this large must take the workgroup-per-channel path (`row_reduce_plane`
/// selects it at 64+ elements); the fixture asserts the plan chose it, then
/// requires the device to agree with the reference.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_row_mode_instance_norm_agrees_with_the_reference() {
    let input = spread(32 * 128 * 128, 0.7);
    let source = Invented::new(0);
    let plan = row_mode_plan(
        &source,
        &[Shape::new(32, 128, 128)],
        crate::nets::Kind::InstanceNorm,
        |b, ids| b.instance_norm(ids[0], 1e-5),
    );
    compare(
        "a row-mode instance norm, 32 channels of 128x128",
        plan,
        source.into_data(),
        &[&input],
    );
}

/// A row-mode instance norm at a ragged shape: 13 channels of 37x41, so neither
/// the channel count nor the plane is a multiple of anything convenient. Lanes
/// stride the plane, so partial workgroups and tail elements are the thing under
/// test.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_ragged_row_mode_instance_norm_agrees_with_the_reference() {
    let input = spread(13 * 37 * 41, -0.4);
    let source = Invented::new(0);
    let plan = row_mode_plan(
        &source,
        &[Shape::new(13, 37, 41)],
        crate::nets::Kind::InstanceNorm,
        |b, ids| b.instance_norm(ids[0], 1e-5),
    );
    compare(
        "a ragged row-mode instance norm, 13 channels of 37x41",
        plan,
        source.into_data(),
        &[&input],
    );
}

/// A row-mode global average pool at a real size: the plan must choose the
/// workgroup-per-channel path and the device must agree with the reference.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_row_mode_global_avg_pool_agrees_with_the_reference() {
    let input = spread(128 * 16 * 16, 1.3);
    let source = Invented::new(0);
    let plan = row_mode_plan(
        &source,
        &[Shape::new(128, 16, 16)],
        crate::nets::Kind::GlobalAvgPool,
        |b, ids| b.global_avg_pool(ids[0]),
    );
    compare(
        "a row-mode global average pool, 128 channels of 16x16",
        plan,
        source.into_data(),
        &[&input],
    );
}

/// Build `record` with spatial tiling on and require every spatial `Kind::Conv` in the
/// plan to have taken the blocked path (`in1 == 1`), then run it both ways.
fn blocked_conv_agrees(
    what: &str,
    count: usize,
    shapes: &[Shape],
    inputs: &[&[f32]],
    record: impl FnOnce(&mut Builder, &[Id]) -> Id,
) {
    let source = Invented::new(count);
    let plan = build(&source, shapes, |b, ids| {
        b.spatial_tiling();
        record(b, ids)
    });
    let mut seen = 0;
    for op in &plan.ops {
        if let crate::nets::Op::Dispatch { kind: crate::nets::Kind::Conv, push, .. } = op {
            assert_eq!(push.in1, 1, "{what}: a Conv missed the blocked path: {push:?}");
            seen += 1;
        }
    }
    assert!(seen > 0, "{what}: the plan has no Conv");
    compare(what, plan, source.into_data(), inputs);
}

/// Odd input widths put every row at a different quad alignment, so the scalar
/// fallback runs; 6 output channels leave a half-empty channel block and 45 columns
/// leave a partial column block.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_blocked_conv_over_unaligned_rows_agrees_with_the_reference() {
    let input = spread(5 * 29 * 45, 0.3);
    blocked_conv_agrees(
        "a blocked 3x3 over 45-wide rows",
        4,
        &[Shape::new(5, 29, 45)],
        &[&input],
        |b, ids| {
            let x = b.conv(ids[0], 0, 6, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 1, Act::Relu);
            b.conv(x, 2, 7, (3, 3), (1, 1), (2, 2), (2, 2, 2, 2), 1, Act::None)
        },
    );
}

/// A U^2-Netp-like layer at a size the interpreter still runs quickly: 24 input
/// channels into 16 at 40x80, with a fused residual onto a second dense 3x3, so most
/// blocks are interior and the fused store indexes through the blocked layout.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_blocked_conv_at_a_real_width_agrees_with_the_reference() {
    let input = spread(24 * 40 * 80, 1.7);
    blocked_conv_agrees(
        "a blocked 3x3 pair at 40x80",
        4,
        &[Shape::new(24, 40, 80)],
        &[&input],
        |b, ids| {
            let x = b.conv(ids[0], 0, 16, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 1, Act::Relu);
            let y = b.conv(x, 2, 16, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 1, Act::None);
            b.add(x, y)
        },
    );
}

/// Strided dense convolutions on the blocked path at ragged sizes: a stride-2 3x3
/// (scalar gather), a 5x5, and a stride-2 3x3 with the asymmetric stem padding.
#[test]
#[ignore = "needs a Vulkan device"]
fn blocked_strided_convs_agree_with_the_reference() {
    let input = spread(3 * 51 * 67, -0.6);
    blocked_conv_agrees(
        "blocked strided convs",
        6,
        &[Shape::new(3, 51, 67)],
        &[&input],
        |b, ids| {
            let x = b.conv(ids[0], 0, 12, (3, 3), (2, 2), (1, 1), (1, 1, 1, 1), 1, Act::HardSwish);
            let x = b.conv(x, 2, 12, (5, 5), (1, 1), (1, 1), (2, 2, 2, 2), 1, Act::Relu);
            b.conv(x, 4, 12, (3, 3), (2, 2), (1, 1), (0, 0, 1, 1), 1, Act::Relu)
        },
    );
}
/// The aligned-window path at every quad offset and kernel shape it specialises:
/// left pads 0, 3, 1 and 2 put the window start at offsets 0, 1, 3 and 2; then a
/// dilated 3x3, a 5x5 and a stride (2, 1) 3x3. 48 and 64 columns keep the rows quad
/// aligned so these take the window path rather than the scalar fallback.
#[test]
#[ignore = "needs a Vulkan device"]
fn blocked_convs_at_every_window_alignment_agree_with_the_reference() {
    let input = spread(6 * 20 * 48, 0.9);
    blocked_conv_agrees(
        "blocked convs at every window alignment",
        14,
        &[Shape::new(6, 20, 48)],
        &[&input],
        |b, ids| {
            let x = b.conv(ids[0], 0, 8, (3, 3), (1, 1), (1, 1), (1, 0, 1, 2), 1, Act::Relu);
            let x = b.conv(x, 2, 8, (3, 3), (1, 1), (1, 1), (1, 3, 1, 5), 1, Act::None);
            let x = b.conv(x, 4, 8, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 1, Act::Relu);
            let x = b.conv(x, 6, 8, (3, 3), (1, 1), (2, 2), (2, 2, 2, 2), 1, Act::Relu);
            let x = b.conv(x, 8, 8, (5, 5), (1, 1), (1, 1), (2, 2, 2, 2), 1, Act::Relu);
            let x = b.conv(x, 10, 8, (3, 3), (2, 1), (1, 1), (1, 1, 1, 1), 1, Act::Relu);
            b.conv(x, 12, 5, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 1, Act::Sigmoid)
        },
    );
}

/// The Supertonic sampler's padded plan: a dilated depthwise (1, 7) with edge padding over 52
/// columns, `pad_edge` = 4 so columns clamp at the real width of 49. The tail holds garbage,
/// and the first 49 columns must match the plain 49-wide plan exactly on the interpreter, and
/// the device must match the interpreter.
#[test]
#[ignore = "needs a Vulkan device"]
fn an_edge_clamp_short_of_the_width_ignores_the_padded_tail() {
    let (channels, real, padded) = (16usize, 49usize, 52usize);
    let given = Given::new(&[
        (vec![channels as u32, 1, 1, 7], spread(channels * 7, 0.4)),
        (vec![channels as u32], spread(channels, 1.7)),
    ])
    .expect("the fixture tensors are consistent");
    let data = given.data().to_vec();
    let at = |width: usize| {
        build(&given, &[Shape::new(channels as u32, 1, width as u32)], |b, ids| {
            b.edge_padding();
            let c = channels as u32;
            b.conv(ids[0], 0, c, (1, 7), (1, 1), (1, 4), (0, 12, 0, 12), c, Act::None)
        })
    };
    let narrow_input = spread(channels * real, 2.2);
    let mut wide_input = vec![37.0f32; channels * padded];
    for (row, chunk) in narrow_input.chunks_exact(real).enumerate() {
        wide_input[row * padded..row * padded + real].copy_from_slice(chunk);
    }
    let mut wide = at(padded);
    for op in &mut wide.ops {
        if let crate::nets::Op::Dispatch { kind: crate::nets::Kind::Conv, push, .. } = op {
            push.pad_edge = 1 + (padded - real) as u32;
        }
    }
    let narrow = run_multi(&at(real), &data, &[&narrow_input]).expect("the interpreter runs");
    let host = run_multi(&wide, &data, &[&wide_input]).expect("the interpreter runs");
    let cut: Vec<f32> = host[0].chunks_exact(padded).flat_map(|row| &row[..real]).copied().collect();
    assert_eq!(cut, narrow[0], "the real columns read the padded tail");
    compare("an edge clamp short of the width", wide, data, &[&wide_input]);
}

/// Count the plan's `Kind::Conv` dispatches by `in1` selector (untiled, dense, depthwise).
fn conv_selectors(plan: &crate::nets::Plan) -> [usize; 3] {
    let mut seen = [0; 3];
    for op in &plan.ops {
        if let crate::nets::Op::Dispatch { kind: crate::nets::Kind::Conv, push, .. } = op {
            seen[push.in1 as usize] += 1;
        }
    }
    seen
}

/// Depthwise convolutions on the blocked path (`in1` = 2): MobileFaceNet/SCRFD shapes at
/// stride 1 and 2 over 56 columns (interior quad reads), then 45-wide rows (ragged column
/// block, every alignment), a dilated 3x3, a 5x5 and a fused residual.
#[test]
#[ignore = "needs a Vulkan device"]
fn blocked_depthwise_convs_agree_with_the_reference() {
    let wide = spread(24 * 56 * 56, 0.7);
    let source = Invented::new(4);
    let plan = build(&source, &[Shape::new(24, 56, 56)], |b, ids| {
        b.spatial_tiling();
        let x = b.conv(ids[0], 0, 24, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 24, Act::Relu);
        b.conv(x, 2, 24, (3, 3), (2, 2), (1, 1), (1, 1, 1, 1), 24, Act::None)
    });
    assert_eq!(conv_selectors(&plan), [0, 0, 2], "depthwise convs missed the blocked path");
    compare("blocked depthwise 3x3s over 56 columns", plan, source.into_data(), &[&wide]);

    let ragged = spread(10 * 23 * 45, -1.1);
    let source = Invented::new(10);
    let plan = build(&source, &[Shape::new(10, 23, 45)], |b, ids| {
        b.spatial_tiling();
        let x = b.conv(ids[0], 0, 10, (3, 3), (2, 2), (1, 1), (0, 0, 1, 1), 10, Act::HardSwish);
        let x = b.conv(x, 2, 10, (3, 3), (1, 1), (2, 2), (2, 2, 2, 2), 10, Act::Relu);
        let x = b.conv(x, 4, 10, (5, 5), (1, 1), (1, 1), (2, 2, 2, 2), 10, Act::None);
        let y = b.conv(x, 6, 10, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 10, Act::None);
        let y = b.add(x, y);
        b.conv(y, 8, 10, (3, 3), (2, 2), (1, 1), (1, 1, 1, 1), 10, Act::Relu)
    });
    assert_eq!(conv_selectors(&plan), [0, 0, 5], "depthwise convs missed the blocked path");
    compare("blocked depthwise convs over 45 columns", plan, source.into_data(), &[&ragged]);
}

/// Edge padding (`pad_edge` 1 and 2: clamp columns to `in_w - pad_edge`, rows to the last
/// row) under spatial tiling: the depthwise conv stays untiled, the dense 3x3 with PReLU takes
/// the blocked path, and both agree with the reference. 30 columns leave a ragged x block.
#[test]
#[ignore = "needs a Vulkan device"]
fn edge_padded_convs_agree_under_spatial_tiling() {
    for edge in [1u32, 2] {
        let input = spread(6 * 9 * 30, 0.2);
        let source = Invented::new(5);
        let mut plan = build(&source, &[Shape::new(6, 9, 30)], |b, ids| {
            b.spatial_tiling();
            b.edge_padding();
            let x = b.conv(ids[0], 0, 6, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 6, Act::None);
            b.conv(x, 2, 8, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 1, Act::PRelu(4))
        });
        assert_eq!(conv_selectors(&plan), [1, 1, 0], "edge padding took the wrong paths");
        for op in &mut plan.ops {
            if let crate::nets::Op::Dispatch { kind: crate::nets::Kind::Conv, push, .. } = op {
                push.pad_edge = edge;
            }
        }
        let what = format!("edge-padded convs under spatial tiling, pad_edge {edge}");
        compare(&what, plan, source.into_data(), &[&input]);
    }
}
/// `conv_point.comp`'s wide path (8 weights per `uvec4`, 4 positions per quad): 64 input
/// channels over 96x96 positions (576 tiles, above the split threshold) into 40 ragged output
/// channels, with a fused residual.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_wide_pointwise_conv_agrees_with_the_reference() {
    let input = spread(64 * 96 * 96, 1.5);
    let source = Invented::new(4);
    let plan = build(&source, &[Shape::new(64, 96, 96)], |b, ids| {
        let x = b.conv(ids[0], 0, 40, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::Relu);
        let y = b.conv(x, 2, 40, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
        b.add(x, y)
    });
    assert!(plan.ops.iter().any(|op| matches!(
        op,
        crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPoint, push, .. }
            if push.weight % 8 == 0 && push.in_c % 8 == 0 && push.in1 == 0
    )));
    compare("a wide pointwise conv", plan, source.into_data(), &[&input]);
}

/// The stride-2 3x3 window path (`strided_rows`): quad-aligned 44-column rows, left pads 0-3
/// for every window alignment, and ragged output rows (22 or fewer columns) at the right edge.
#[test]
#[ignore = "needs a Vulkan device"]
fn strided_window_convs_agree_with_the_reference() {
    let input = spread(8 * 20 * 44, 0.3);
    for left in 0u32..4 {
        blocked_conv_agrees(
            &format!("a stride-2 3x3 window, left pad {left}"),
            2,
            &[Shape::new(8, 20, 44)],
            &[&input],
            |b, ids| b.conv(ids[0], 0, 12, (3, 3), (2, 2), (1, 1), (1, left, 1, 1), 1, Act::Relu),
        );
    }
}

/// The Q2_K gemv's pair-per-lane path (8-aligned input, even scale table) at MADLAD's input widths,
/// ragged row groups, several units per lane at 8192.
#[test]
#[ignore = "needs a Vulkan device"]
fn q2k_gemv_pairs_agree_with_the_reference() {
    for (out_c, in_c) in [(44u32, 1024u32), (13, 2048), (9, 8192)] {
        let blocks = in_c / crate::weights::Q2K_BLOCK;
        let bytes: Vec<u8> =
            (0..out_c * blocks * crate::weights::Q2K_BYTES).map(|i| (i.wrapping_mul(2_246_822_519) >> 7) as u8).collect();
        let pairs: Vec<f32> = (0..out_c * blocks * 2).map(|i| 0.05 + 0.01 * ((i * 5) % 9) as f32).collect();
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[
                Fixture::Q2K(vec![out_c, in_c], bytes),
                Fixture::F16(vec![out_c * blocks * 2], pairs),
                Fixture::F16(vec![out_c], spread(out_c as usize, 0.2)),
            ],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
        let input = spread(in_c as usize, 0.8);
        let mut builder = Builder::new(&weights);
        let first = builder.input(Shape::new(in_c, 1, 1));
        let last = builder.conv_q2k(first, 0, out_c, Act::None);
        let plan = builder.finish(&[last]).expect("the q2k gemv plan builds");
        assert!(plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecQ2K, push, .. }
                if push.in0 % 8 == 0 && push.act_weight % 2 == 0
        )));
        compare(&format!("a q2k gemv {out_c}x{in_c}"), plan, weights.data().to_vec(), &[&input]);
    }
}
