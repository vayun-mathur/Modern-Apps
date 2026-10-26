// Device parity fixtures (Gemma work). See `parity.rs` for the helpers.
//
// Gemma-4 decode is ~205 int4 GEMV projections plus 70 int8 per-layer
// projections a step (`analysis/litertlm-decode-gap.md`: ConvVecInt4 97 ms,
// ConvVecInt8 3 ms, RmsNorm since fixed by row-parallel reductions). The
// int4 GEMV (`shaders/conv_vec_int4.comp`) is the next kernel to change —
// the wide-load / read-only `f16vec4` activation pattern that took
// `conv_vec_int8` from 30 to 19 ms and `conv_vec_q2k` from 1738 to 45 ms —
// so these pin its numerics at the exact shapes decode runs before the
// shader is touched: 1536-wide down/O, 2048-wide sliding-Q, 12288-wide slim
// gate, the int8 per-layer pair (256x1536 gate, 1536x256 projection), and
// the 8960x1536 device-combine projection (`nets::gemma4_gather`).
//
// All are single-position, so `Builder` lowers each to the `ConvVec` shader
// — asserted per fixture, as the existing int4/int8 fixtures do.

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemma_down_projection_gemv_agrees_with_the_reference() {
    // `down` / `o_proj`: 1536 out over 1536 taps, 48 whole blocks. The
    // 29.3%-of-decode-bytes bucket (`conv_vec_int4.comp` header table).
    let out_channels = 1536u32;
    let in_channels = 1536u32;
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 7) % 16 - 8) as i8).collect();
    let scales: Vec<f32> =
        (0..out_channels * blocks).map(|i| 0.007_812_5 * (1.0 + (i % 5) as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 1.9);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread(in_channels as usize, 0.7);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int4(first, 0, out_channels, Act::None);
    let plan = builder.finish(&[last]).expect("the down-projection fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt4, .. }
        )),
        "a single-position int4 convolution must lower to the gemv shader",
    );
    compare("a gemma down-projection gemv", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemma_query_projection_gemv_agrees_with_the_reference() {
    // Sliding-layer `q_proj`: 2048 out (8 heads x head_dim 256) over 1536
    // taps. 2048 / ROWS 8 = 256 workgroups — comfortably occupied, unlike
    // the 256-wide KV projections (32 workgroups, 0.3x of warp slots).
    let out_channels = 2048u32;
    let in_channels = 1536u32;
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 11) % 16 - 8) as i8).collect();
    let scales: Vec<f32> =
        (0..out_channels * blocks).map(|i| 0.015_625 * (1.0 + (i % 3) as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 0.4);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread(in_channels as usize, 0.2);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int4(first, 0, out_channels, Act::None);
    let plan = builder.finish(&[last]).expect("the query-projection fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt4, .. }
        )),
        "a single-position int4 convolution must lower to the gemv shader",
    );
    compare("a gemma query-projection gemv", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemma_slim_gate_gemv_agrees_with_the_reference() {
    // Slim-layer fused gate: 12288 out over 1536 taps, ~9.4 MB of weights —
    // the 69%-of-decode-bytes bucket with the head. 1536 workgroups at
    // ROWS 8, the shape with enough occupancy to hide load latency.
    let out_channels = 12288u32;
    let in_channels = 1536u32;
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 13) % 16 - 8) as i8).collect();
    let scales: Vec<f32> =
        (0..out_channels * blocks).map(|i| 0.007_812_5 * (1.0 + (i % 7) as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 2.3);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread(in_channels as usize, 1.1);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int4(first, 0, out_channels, Act::Gelu);
    let plan = builder.finish(&[last]).expect("the slim-gate fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt4, .. }
        )),
        "a single-position int4 convolution must lower to the gemv shader",
    );
    compare("a gemma slim-gate gemv", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemma_per_layer_pair_agrees_with_the_reference() {
    // The two int8 per-layer projections (`nets::gemma4::projection8`):
    // gate 256x1536 with fused GELU, then projection 1536x256. Chained in
    // one plan, as the layer runs them (gate output feeds the mul, whose
    // output feeds the projection — here directly, shapes being the point).
    let (gate_out, proj_out, taps) = (256u32, 1536u32, 1536u32);
    let gate_kernel: Vec<i8> =
        (0..(gate_out * taps) as i32).map(|i| ((i * 29) % 253 - 126) as i8).collect();
    let proj_kernel: Vec<i8> =
        (0..(proj_out * gate_out) as i32).map(|i| ((i * 37) % 251 - 125) as i8).collect();
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![gate_out, taps, 1, 1], gate_kernel),
            Fixture::F16(
                vec![gate_out],
                (0..gate_out).map(|c| 0.007_812_5 * (1.0 + (c % 5) as f32)).collect(),
            ),
            Fixture::F16(vec![gate_out], spread(gate_out as usize, 0.8)),
            Fixture::I8(vec![proj_out, gate_out, 1, 1], proj_kernel),
            Fixture::F16(
                vec![proj_out],
                (0..proj_out).map(|c| 0.007_812_5 * (1.0 + (c % 3) as f32)).collect(),
            ),
            Fixture::F16(vec![proj_out], spread(proj_out as usize, 1.4)),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let input = spread(taps as usize, 0.6);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(taps, 1, 1));
    let gated =
        builder.conv_int8(first, 0, gate_out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::Gelu);
    let last =
        builder.conv_int8(gated, 3, proj_out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
    let plan = builder.finish(&[last]).expect("the per-layer-pair fixture plan builds");
    assert_eq!(
        plan.ops
            .iter()
            .filter(|op| matches!(
                op,
                crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt8, .. }
            ))
            .count(),
        2,
        "both per-layer projections must lower to the gemv shader",
    );
    compare("a gemma per-layer pair", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemma_combine_projection_gemv_agrees_with_the_reference() {
    // The device-combine projection (`nets::gemma4_gather::build_plan`):
    // 8960 out (35 layers x 256) over 1536 taps, int8 with a per-channel
    // scale. The 13.8M-MAC SGEMV the host runs per token (~94 ms on the
    // Pixel 8); the parity gate (`run_gemma4 --gather-parity`) covers the
    // full plan, this pins the projection kernel at its exact shape.
    let out_channels = 8960u32;
    let in_channels = 1536u32;
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 41) % 247 - 123) as i8).collect();
    let scales: Vec<f32> =
        (0..out_channels).map(|c| 0.000_976_562_5 * (1.0 + (c % 5) as f32)).collect();
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], spread(out_channels as usize, 1.7)),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let input = spread(in_channels as usize, 0.3);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int8(
        first,
        0,
        out_channels,
        (1, 1),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        1,
        Act::None,
    );
    let plan = builder.finish(&[last]).expect("the combine-projection fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt8, .. }
        )),
        "a single-position int8 convolution must lower to the gemv shader",
    );
    compare("a gemma combine-projection gemv", plan, weights.data().to_vec(), &[&input]);
}

/// An int4 1x1 convolution over `positions` columns, checked against the reference; asserts it
/// lowered to the tiled GEMM (`conv_point_int4.comp`).
fn int4_gemm_fixture(label: &str, out_channels: u32, in_channels: u32, positions: u32, act: Act) {
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 7 + i / 5) % 16 - 8) as i8).collect();
    let scales: Vec<f32> =
        (0..out_channels * blocks).map(|i| 0.007_812_5 * (1.0 + (i % 5) as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 1.3);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread((in_channels * positions) as usize, 0.5);
    let mut builder = Builder::new(&weights);
    builder.fp32_int4();
    let first = builder.input(Shape::new(in_channels, 1, positions));
    let last = builder.conv_int4(first, 0, out_channels, act);
    let plan = builder.finish(&[last]).expect("the int4 gemm fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPointInt4, .. }
        )),
        "a multi-position int4 convolution must lower to the tiled shader",
    );
    compare(label, plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemma_prefill_query_gemm_agrees_with_the_reference() {
    // Sliding `q_proj` over a 200-token prompt: whole 32x32 tiles, quad-aligned positions.
    int4_gemm_fixture("a gemma prefill query gemm", 2048, 1536, 200, Act::None);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_ragged_prefill_gemm_agrees_with_the_reference() {
    // 37 positions straddle quads and leave a partial tile; 1000 channels leave a partial row
    // tile; 2048 taps is 64 blocks.
    int4_gemm_fixture("a ragged prefill gemm", 1000, 2048, 37, Act::Gelu);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn an_unblocked_prefill_gemm_agrees_with_the_reference() {
    // 100 taps is not a whole number of 32-tap blocks: the per-tap scalar path.
    int4_gemm_fixture("an unblocked prefill gemm", 40, 100, 9, Act::None);
}
#[test]
#[ignore = "needs a Vulkan device"]
fn a_ragged_wide_int4_gemv_agrees_with_the_reference() {
    // 1056 taps is 33 blocks, so one lane group takes a third pass the others skip; 20 channels
    // leave four rows of the last workgroup to discard. Still the wide (`uvec4`) path.
    let out_channels = 20u32;
    let in_channels = 1056u32;
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 5 + i / 3) % 16 - 8) as i8).collect();
    let scales: Vec<f32> =
        (0..out_channels * blocks).map(|i| 0.015_625 * (1.0 + (i % 4) as f32)).collect();
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], spread(out_channels as usize, 0.9)),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread(in_channels as usize, 0.6);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int4(first, 0, out_channels, Act::None);
    let plan = builder.finish(&[last]).expect("the ragged gemv fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt4, .. }
        )),
        "a single-position int4 convolution must lower to the gemv shader",
    );
    compare("a ragged wide int4 gemv", plan, weights.data().to_vec(), &[&input]);
}
/// T queries at prefix `prefix` through `attn_cached_multi` against T single decode steps at
/// `prefix + t`, over caches holding junk past the last position (so an over-read shows).
fn multi_query_fixture(queries: u32, prefix: u32, head_dim: u32, window: u32) {
    use crate::nets::reference::run_multi_at;
    const HEADS: u32 = 8;
    const KV_HEADS: u32 = 1;
    let what = format!("multi-query attention T={queries} prefix={prefix} dim={head_dim} window={window}");
    let width = HEADS * head_dim;
    let kv = KV_HEADS * head_dim;
    let filled = prefix + queries;
    let context = filled + 3;
    let cache = |seed: f32| -> Vec<f32> {
        let mut values = spread((context * kv) as usize, seed);
        for v in values.iter_mut().skip((filled * kv) as usize) {
            *v = 50.0;
        }
        values
    };
    let (keys, values) = (cache(1.3), cache(2.9));
    let q: Vec<f32> = spread((width * queries) as usize, 0.4).iter().map(|v| v * 0.08).collect();
    let sliding = window != 0;

    let source = Invented::new(0);
    let mut builder = Builder::new(&source);
    let q1 = builder.input(Shape::new(width, 1, 1));
    let k1 = builder.input(Shape::new(context, 1, kv));
    let v1 = builder.input(Shape::new(context, 1, kv));
    let scores = builder.attn_scores_cached_prescaled(q1, k1, HEADS, KV_HEADS, sliding);
    let probs = builder.softmax_prefix(scores, sliding);
    let out = builder.attn_apply_cached_grouped(probs, v1, HEADS, KV_HEADS, sliding);
    let plan = builder.finish(&[out]).expect("the single-query plan builds");
    let weights = Weights::from_data(source.into_data());
    let mut net = Net::new(device(), plan, &weights, RESCALE_ONLY).expect("the single-query plan records");
    let mut stepped = vec![0f32; (width * queries) as usize];
    for t in 0..queries {
        let position = prefix + t;
        let window_start = if sliding { (position + 1).saturating_sub(window) } else { 0 };
        net.set_params(StepParams { prefix: position, window_start }).expect("step params");
        let column: Vec<f32> =
            (0..width).map(|c| q[(c * queries + t) as usize]).collect();
        let got = net.infer_raw_many(&[&column, &keys, &values]).expect("a single step submits");
        for c in 0..width {
            stepped[(c * queries + t) as usize] = got[0][c as usize];
        }
    }

    let source = Invented::new(0);
    let mut builder = Builder::new(&source);
    let qm = builder.input(Shape::new(width, 1, queries));
    let km = builder.input(Shape::new(context, 1, kv));
    let vm = builder.input(Shape::new(context, 1, kv));
    let out = builder.attn_cached_multi(qm, km, vm, HEADS, KV_HEADS, window);
    let plan = builder.finish(&[out]).expect("the multi-query plan builds");
    crate::nets::tests::assert_no_aliasing(&plan);
    let data = source.into_data();
    let oracle = run_multi_at(&plan, &data, &[&q, &keys, &values], prefix, 0).expect("the oracle runs");
    let weights = Weights::from_data(data);
    let mut net = Net::new(device(), plan, &weights, RESCALE_ONLY).expect("the multi-query plan records");
    net.set_params(StepParams { prefix, window_start: 0 }).expect("step params");
    let multi = net.infer_raw_many(&[&q, &keys, &values]).expect("the multi-query plan submits");

    matches(&format!("{what} vs the oracle"), &oracle, &multi);
    matches(&format!("{what} vs single steps"), &[stepped], &multi);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn multi_query_cached_attention_equals_single_steps() {
    // Gemma 4: sliding layers are head_dim 256 over a 512-key window, full layers 512 over
    // everything. Prefix 600 puts the window's start inside the cache.
    for queries in [1, 7, 64] {
        for prefix in [0, 5, 600] {
            multi_query_fixture(queries, prefix, 256, 512);
            multi_query_fixture(queries, prefix, 512, 0);
        }
    }
    // A short window, so every query's lower bound differs and most of them clip.
    multi_query_fixture(7, 5, 256, 4);
}

/// `[value, lo, hi] x 64` from a host sort: value descending, ties by lower id.
fn host_top_k(row: &[f32]) -> Vec<f32> {
    let mut ids: Vec<u32> = (0..row.len() as u32).collect();
    ids.sort_by(|&a, &b| row[b as usize].total_cmp(&row[a as usize]).then(a.cmp(&b)));
    (0..64)
        .flat_map(|r| match ids.get(r) {
            Some(&i) => [row[i as usize], (i % 2048) as f32, (i / 2048) as f32],
            None => [-65504.0, 2047.0, 2047.0],
        })
        .collect()
}

fn top_k_fixture(what: &str, row: &[f32]) {
    let source = Invented::new(0);
    let mut builder = Builder::new(&source);
    let input = builder.input(Shape::new(row.len() as u32, 1, 1));
    let out = builder.top_k(input);
    let plan = builder.finish(&[out]).expect("the top-k plan builds");
    crate::nets::tests::assert_no_aliasing(&plan);
    let data = source.into_data();
    let got = on_device(plan.clone(), data.clone(), &[row]);
    assert_eq!(got[0], host_top_k(row), "{what}: the device top 64 differs from a host sort");
    compare(what, plan, data, &[row]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn device_top_k_equals_a_host_sort() {
    // Gemma's vocabulary. Multiples of 1/64 in [-8, 8) are exact in fp16, and only 1024 of them
    // over 262,144 entries means long runs of ties: the top 64 is decided by index alone.
    let vocab = 262_144usize;
    let ties: Vec<f32> = (0..vocab).map(|i| ((i * 7919 + i / 3) % 1024) as f32 / 64.0 - 8.0).collect();
    top_k_fixture("a tied 262144 row", &ties);
    // Eighty distinct peaks above the noise at scattered ids: the top 64 is decided by value.
    let mut peaks = ties.clone();
    for k in 0..80usize {
        peaks[(k * 104_729 + 17) % vocab] = 8.0 + k as f32 / 16.0;
    }
    top_k_fixture("a 262144 row with peaks", &peaks);
    // Shorter than 64: the missing ranks come back as (2047, 2047).
    let short: Vec<f32> = (0..40).map(|i| ((i * 13) % 7) as f32 - 3.0).collect();
    top_k_fixture("a 40-entry row", &short);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn the_wide_int4_gemm_agrees_with_the_reference_at_gemma_prefill_shapes() {
    // `in1` = 1 (4 x 8 per lane, 32 x 64 tiles) is chosen for whole blocks and quad-aligned
    // columns. The 12288-wide MLP projection at 200 tokens; 36 positions leave a partial 64-tile
    // and lanes whose second quad is past the end; 68 spill one quad into a second tile; 1000
    // channels leave a partial row tile.
    for (label, out, inp, positions, act) in [
        ("a wide 1536 -> 12288 gemm at 200 positions", 12288, 1536, 200, Act::None),
        ("a wide ragged gemm at 36 positions", 1000, 2048, 36, Act::Gelu),
        ("a wide gemm at 68 positions", 96, 1536, 68, Act::None),
        ("a wide 16-byte-load gemm at 72 positions", 1000, 1536, 72, Act::Gelu),
    ] {
        int4_gemm_fixture(label, out, inp, positions, act);
    }
    let blocks = 1536u32.div_ceil(32);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![64, 1536, 1, 1], vec![1; 64 * 1536]),
            Fixture::F16(vec![64, blocks], vec![0.01; (64 * blocks) as usize]),
            Fixture::F16(vec![64], vec![0.0; 64]),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let mut builder = Builder::new(&weights);
    builder.fp32_int4();
    let first = builder.input(Shape::new(1536, 1, 200));
    let last = builder.conv_int4(first, 0, 64, Act::None);
    let plan = builder.finish(&[last]).expect("the plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPointInt4, push, .. } if push.in1 == 4
        )),
        "an 8-aligned int4 GEMM must take the wide 16-byte-load path",
    );
}
