#[test]
#[ignore = "needs a Vulkan device"]
fn a_persistent_cache_accumulates_across_submits_of_one_recording() {
    // The end state Phase 2 is for: **one** recording, submitted once per token, each step
    // appending to a KV cache that lives in the arena and is never sent to the host. If this
    // holds, a decode loop stops rebuilding and stops round-tripping the cache.
    //
    // Checked against the thing it replaces: a plan built at exactly `STEPS` keys with the whole
    // cache handed in as an input, which is how the decoder works today.
    const D_MODEL: u32 = 64;
    const HEADS: u32 = 8;
    const STEPS: u32 = 6;
    const MAX: u32 = 32;

    // One row per step, each distinguishable so a step reading the wrong row is visible.
    let rows: Vec<Vec<f32>> =
        (0..STEPS).map(|s| spread(D_MODEL as usize, 0.4 + s as f32)).collect();

    // What the loop should converge on: every row already in a cache, attended at full length.
    let expected = {
        let mut flat: Vec<f32> = Vec::new();
        for row in &rows {
            flat.extend_from_slice(row);
        }
        let last = rows.last().expect("STEPS is not zero").clone();
        let source = Invented::new(0);
        let mut builder = Builder::new(&source);
        let q = builder.input(Shape::new(D_MODEL, 1, 1));
        let cache = builder.input(Shape::new(STEPS, 1, D_MODEL));
        let scores = builder.attn_scores_cached(q, cache, HEADS);
        let probs = builder.softmax(scores);
        let out = builder.attn_apply_cached(probs, cache, HEADS);
        let plan = builder.finish(&[out]).expect("the fixed-length plan builds");
        on_device(plan, source.into_data(), &[&last, &flat])
    };

    // The decode loop: built once, at the maximum, with the cache held on the device.
    let source = Invented::new(0);
    let mut builder = Builder::new(&source);
    let row = builder.input(Shape::new(D_MODEL, 1, 1));
    let cache = builder.persistent(Shape::new(MAX, 1, D_MODEL));
    builder.cache_write(row, cache);
    let scores = builder.attn_scores_cached_dynamic(row, cache, HEADS);
    let probs = builder.softmax_prefix(scores, true);
    let out = builder.attn_apply_cached_dynamic(probs, cache, HEADS);
    let plan = builder.finish(&[out]).expect("the record-once decode plan builds");
    crate::nets::tests::assert_no_aliasing(&plan);

    let weights = Weights::from_data(source.into_data());
    let mut net = Net::new(device(), plan, &weights, RESCALE_ONLY)
        .expect("the decode plan records into a command buffer");

    let mut last = Vec::new();
    for (step, row) in rows.iter().enumerate() {
        let prefix = u32::try_from(step).expect("STEPS is small");
        // The only thing that changes between submits. No rebuild, no re-record, and the cache
        // rows written by earlier steps are still in the arena.
        net.set_params(StepParams { prefix, window_start: 0 }).expect("the step params write");
        last = net.infer_raw_many(&[row]).expect("the decode step submits");
    }

    matches("a decode loop over one recording", &expected, &last);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn multi_query_attention_reads_one_cache_head_for_every_query_head() {
    // Gemma 4's text decoder is eight query heads against **one** key/value head, so a cache
    // position is an eighth the width. The mapping is what breaks silently: reading head `h`'s
    // slice out of a cache that only has one produces plausible numbers from the wrong offsets.
    //
    // Checked against an explicitly broadcast cache: replicating the single KV head eight times
    // and running ordinary multi-head attention must give the same answer.
    const HEAD_DIM: u32 = 16;
    const HEADS: u32 = 8;
    const KV_HEADS: u32 = 1;
    const KEYS: u32 = 5;
    let d_model = HEAD_DIM * HEADS;

    let query = spread((HEAD_DIM * HEADS) as usize, 0.3);
    // The narrow cache: `KEYS` positions of `KV_HEADS * HEAD_DIM`.
    let narrow: Vec<f32> = spread((KEYS * KV_HEADS * HEAD_DIM) as usize, 1.1);
    // The same data broadcast to every head, which is what MQA means.
    let mut wide = Vec::new();
    for key in 0..KEYS as usize {
        let row = narrow
            .get(key * (KV_HEADS * HEAD_DIM) as usize..(key + 1) * (KV_HEADS * HEAD_DIM) as usize)
            .expect("the narrow cache is rectangular");
        for _ in 0..HEADS {
            wide.extend_from_slice(row);
        }
    }

    let broadcast = {
        let source = Invented::new(0);
        let mut builder = Builder::new(&source);
        let q = builder.input(Shape::new(d_model, 1, 1));
        let cache = builder.input(Shape::new(KEYS, 1, d_model));
        let scores = builder.attn_scores_cached(q, cache, HEADS);
        let probs = builder.softmax(scores);
        let out = builder.attn_apply_cached(probs, cache, HEADS);
        let plan = builder.finish(&[out]).expect("the broadcast plan builds");
        on_device(plan, source.into_data(), &[&query, &wide])
    };

    let grouped = {
        let source = Invented::new(0);
        let mut builder = Builder::new(&source);
        let q = builder.input(Shape::new(d_model, 1, 1));
        let cache = builder.input(Shape::new(KEYS, 1, KV_HEADS * HEAD_DIM));
        let scores = builder.attn_scores_cached_grouped(q, cache, HEADS, KV_HEADS);
        let probs = builder.softmax_prefix(scores, true);
        let out = builder.attn_apply_cached_grouped(probs, cache, HEADS, KV_HEADS, true);
        let plan = builder.finish(&[out]).expect("the grouped plan builds");
        crate::nets::tests::assert_no_aliasing(&plan);
        on_device_at(plan, source.into_data(), &[&query, &narrow], KEYS - 1)
    };

    matches("multi-query attention against a broadcast cache", &broadcast, &grouped);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_sliding_window_ignores_everything_before_its_start() {
    // Gemma 4 alternates four sliding layers to one global one, window 512. A sliding layer must
    // not see the cache before `window_start`, and the failure mode is soft: attending to too
    // much is fluent and wrong rather than an error.
    //
    // Checked by construction: a window over `[start, prefix]` of a long cache must equal a full
    // attention over a cache holding only those positions.
    const HEAD_DIM: u32 = 16;
    const HEADS: u32 = 4;
    const KEYS: u32 = 9;
    const START: u32 = 4;
    let d_model = HEAD_DIM * HEADS;

    let query = spread(d_model as usize, 0.55);
    let full: Vec<f32> = spread((KEYS * d_model) as usize, 2.2);
    let inside: Vec<f32> = full
        .get((START * d_model) as usize..)
        .expect("the window is inside the cache")
        .to_vec();

    // Ordinary attention over just the windowed positions.
    let expected = {
        let source = Invented::new(0);
        let mut builder = Builder::new(&source);
        let q = builder.input(Shape::new(d_model, 1, 1));
        let cache = builder.input(Shape::new(KEYS - START, 1, d_model));
        let scores = builder.attn_scores_cached(q, cache, HEADS);
        let probs = builder.softmax(scores);
        let out = builder.attn_apply_cached(probs, cache, HEADS);
        let plan = builder.finish(&[out]).expect("the windowed-only plan builds");
        on_device(plan, source.into_data(), &[&query, &inside])
    };

    // The same window, expressed as a range over the whole cache.
    let source = Invented::new(0);
    let mut builder = Builder::new(&source);
    let q = builder.input(Shape::new(d_model, 1, 1));
    let cache = builder.input(Shape::new(KEYS, 1, d_model));
    let scores = builder.attn_scores_cached_dynamic(q, cache, HEADS);
    let probs = builder.softmax_prefix(scores, true);
    let out = builder.attn_apply_cached_dynamic(probs, cache, HEADS);
    let plan = builder.finish(&[out]).expect("the windowed plan builds");
    crate::nets::tests::assert_no_aliasing(&plan);

    let weights = Weights::from_data(source.into_data());
    let mut net = Net::new(device(), plan, &weights, RESCALE_ONLY)
        .expect("the windowed plan records");
    net.set_params(StepParams { prefix: KEYS - 1, window_start: START })
        .expect("the window is written");
    let windowed = net.infer_raw_many(&[&query, &full]).expect("the windowed plan submits");

    matches("a sliding window over a longer cache", &expected, &windowed);

    // And the window is really read: opening it wider must change the answer, or the shaders are
    // ignoring `window_start` and this test proves nothing.
    net.set_params(StepParams { prefix: KEYS - 1, window_start: 0 }).expect("widen the window");
    let wide = net.infer_raw_many(&[&query, &full]).expect("the wide plan submits");
    let moved = expected
        .iter()
        .zip(&wide)
        .flat_map(|(a, b)| a.iter().zip(b))
        .any(|(a, b)| (a - b).abs() > TOLERANCE);
    assert!(moved, "opening the window changed nothing, so window_start is unread");
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_grouped_rms_norm_normalises_each_head_on_its_own() {
    // Gemma 4's QK-norm: a query is `[heads * head_dim, 1, 1]` and each head's slice is
    // normalised independently against one shared `head_dim`-long gamma.
    //
    // The failure this catches is normalising across heads instead of within them, which is what
    // a naive reshape does - the channels are head-major, so a `[head_dim, 1, heads]` view
    // strides the wrong way. Distinct per-head magnitudes make that visible: if the reduction
    // spanned heads, every head would share one scale factor and the ratios between them would
    // collapse.
    const HEAD_DIM: u32 = 8;
    const HEADS: u32 = 4;
    let channels = HEAD_DIM * HEADS;
    // Head `h` is scaled by `h + 1`, so the heads have deliberately different norms.
    let input: Vec<f32> = (0..channels)
        .map(|i| {
            let head = i / HEAD_DIM;
            spread(HEAD_DIM as usize, 0.9)[(i % HEAD_DIM) as usize] * (head as f32 + 1.0)
        })
        .collect();
    let gamma: Vec<f32> = spread(HEAD_DIM as usize, 2.5);

    agrees(
        "a grouped rms norm",
        &[Shape::new(channels, 1, 1)],
        &[&input],
        &[(vec![HEAD_DIM], gamma)],
        |b, ids| b.rms_norm_grouped(ids[0], 0, 1e-6, HEADS),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_banded_score_map_matches_the_cpu_oracle() {
    // Two heads of head_dim 4 over eight positions, band 3, so the first two queries have dead
    // columns and the rest are full. The relative table is `[heads, offsets, head_dim]` with
    // offsets = band + 1, because column `j` reads offset `j + 1` and the widest column needs
    // offset `band`.
    const HEADS: u32 = 2;
    const HEAD_DIM: u32 = 4;
    const T: u32 = 8;
    const BAND: u32 = 3;
    const OFFSETS: u32 = BAND + 1;
    let q = spread((HEADS * HEAD_DIM * T) as usize, 0.31);
    let k = spread((HEADS * HEAD_DIM * T) as usize, -0.23);
    let table = spread((HEADS * OFFSETS * HEAD_DIM) as usize, 0.17);
    // `agrees` and not `agrees_invented`: the second argument of `agrees_invented` is a tensor
    // COUNT, and passing `table.len()` there declared 32 tensors for a plan that reads one, so
    // `finish` refused the plan before a device was ever asked for. Handing the table over as a
    // real fixture tensor is what the relative-attention tests do and keeps the values here.
    // Straddle the cap, or the test would pass against no softcap at all.
    agrees(
        "a banded score map",
        &[Shape::new(HEADS * HEAD_DIM, 1, T), Shape::new(HEADS * HEAD_DIM, 1, T)],
        &[&q, &k],
        &[(vec![HEADS, OFFSETS, HEAD_DIM], table)],
        |b, ids| b.attn_scores_banded(ids[0], ids[1], crate::nets::BandedScores { heads: HEADS, band: BAND, table: 0, offsets: OFFSETS, scale: 1.0, cap: 2.0 }),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_banded_value_mix_matches_the_cpu_oracle() {
    const HEADS: u32 = 2;
    const HEAD_DIM: u32 = 4;
    const T: u32 = 8;
    const BAND: u32 = 3;
    let probs = spread((HEADS * T * BAND) as usize, 0.11);
    let v = spread((HEADS * HEAD_DIM * T) as usize, 0.29);
    agrees_invented(
        "a banded value mix",
        0,
        &[Shape::new(HEADS, T, BAND), Shape::new(HEADS * HEAD_DIM, 1, T)],
        &[&probs, &v],
        |b, ids| b.attn_apply_banded(ids[0], ids[1], HEADS, BAND),
    );
}

/// Heads, head_dim, sequence and band the banded value fixtures below share.
///
/// The band is [`crate::nets::gemma4_audio::ATTEND_SPAN`] itself rather than a small stand-in, so
/// that `q = 0, 1, 11` here are the same edge rows the tower actually has. `T` is two past the
/// band, which puts three fully populated rows after the ragged ones.
const FIXTURE_HEADS: u32 = 2;
const FIXTURE_HEAD_DIM: u32 = 2;
const FIXTURE_T: u32 = 14;

/// The logit cap the banded fixtures use.
///
/// Not the production 50: with these magnitudes `tanh(x / 50) * 50` is within a percent of `x`,
/// and a shader that dropped the cap would pass. At 8 the fixture's largest score bends by 32%,
/// which `the_banded_cap_is_applied_to_the_sum_and_not_to_the_fill` pins directly.
const FIXTURE_CAP: f32 = 8.0;

/// Q, K and the relative table for the banded score fixtures.
///
/// Every value is a multiple of `1/8`, so it survives the fp16 arena exactly and the only
/// rounding in the whole fixture is the single store of the result. That is what lets these
/// assert *values* rather than "finite and about right" — which is the entire point, since a
/// wrapped out-of-bounds read returns a finite plausible number from a neighbouring tensor.
///
/// Head 0's keys ascend and head 1's descend, and the two heads read different rows of the
/// table, so a swapped head or a transposed key axis is visible in the answer rather than
/// cancelling out.
fn banded_score_fixture(offsets: u32) -> (Vec<f32>, Vec<f32>, Vec<f32>) {
    let channels = FIXTURE_HEADS * FIXTURE_HEAD_DIM;
    let q = vec![1.0; (channels * FIXTURE_T) as usize];
    let mut k = vec![0.0; (channels * FIXTURE_T) as usize];
    for t in 0..FIXTURE_T {
        // Channel 0 is head 0's first lane, channel 2 is head 1's. The other lane of each head
        // stays zero, so the content term is exactly one product.
        k[t as usize] = (t + 1) as f32 * 0.5;
        k[(2 * FIXTURE_T + t) as usize] = -((t + 1) as f32) * 0.5;
    }
    let mut table = vec![0.0; (FIXTURE_HEADS * offsets * FIXTURE_HEAD_DIM) as usize];
    for o in 0..offsets {
        // Lane 1 carries the table, so the relative term is also exactly one product. The two
        // heads get different weights.
        table[((o * FIXTURE_HEAD_DIM) + 1) as usize] = o as f32 * 0.25;
        table[(((offsets + o) * FIXTURE_HEAD_DIM) + 1) as usize] = o as f32 * 0.125;
    }
    (q, k, table)
}

/// What `banded_score_fixture` should produce at `(head, query, column)` for a window of `band`,
/// or `None` when the column falls before the start of the sequence.
///
/// Derived from the fixture's own arithmetic and `band_key_in`, not from the op: content is
/// `+/-(k + 1) / 2` and the relative term is `(j + 1) / 4` or `(j + 1) / 8`, and the cap is
/// applied to their sum.
///
/// `band` is a parameter rather than [`ATTEND_SPAN`] for the same reason the reference arms take
/// it: the op is parameterised and reads it from `Push::kh`. Hardcoding the span here would be
/// invisible today — every caller passes `ATTEND_SPAN` and the two agree at 12 — and would fail
/// the moment someone writes a non-12 test, with the EXPECTATION computed at 12 and the ARM at
/// their band. That presents as an op bug, and the shader is where they would look first.
fn banded_score_want(band: u32, head: u32, query: u32, column: u32) -> Option<f32> {
    let key = crate::nets::gemma4_audio::band_key_in(band, query, column)?;
    let content = (key + 1) as f32 * 0.5;
    let relative = (column + 1) as f32;
    let total = if head == 0 { content + relative * 0.25 } else { -content + relative * 0.125 };
    Some((total / FIXTURE_CAP).tanh() * FIXTURE_CAP)
}

/// Run the banded score op over `banded_score_fixture` on the host interpreter.
fn banded_scores(band: u32, offsets: u32) -> Vec<f32> {
    let (q, k, table) = banded_score_fixture(offsets);
    let channels = FIXTURE_HEADS * FIXTURE_HEAD_DIM;
    let tensors = [(vec![FIXTURE_HEADS, offsets, FIXTURE_HEAD_DIM], table)];
    let given = Given::new(&tensors).expect("the banded fixture tensors lay out");
    let shapes = [Shape::new(channels, 1, FIXTURE_T), Shape::new(channels, 1, FIXTURE_T)];
    let plan = build(&given, &shapes, |b, ids| {
        // The scale is 1.0 and not the export's 0.127517431974411: `maml_convert.py` folds that
        // into the per-layer `QueryScale` tensor, so Q reaches this op already scaled. See
        // `Builder::attn_scores_banded`.
        b.attn_scores_banded(ids[0], ids[1], crate::nets::BandedScores { heads: FIXTURE_HEADS, band, table: 0, offsets, scale: 1.0, cap: FIXTURE_CAP })
    });
    let outputs =
        run_multi(&plan, given.data(), &[&q, &k]).expect("the banded score fixture runs");
    match <[Vec<f32>; 1]>::try_from(outputs) {
        Ok([only]) => only,
        Err(other) => panic!("{} outputs", other.len()),
    }
}

/// Assert `got` is `want` to within one fp16 store of the fixture's magnitude.
fn pinned(what: &str, got: f32, want: f32) {
    // fp16 keeps about three decimal digits and the operands here are exact, so the only error
    // is the store of the result.
    let tolerance = want.abs() * 1e-3 + 1e-3;
    assert!((got - want).abs() <= tolerance, "{what}: got {got}, want {want}");
}
