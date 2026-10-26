//! The shaders, against [`crate::nets::reference`].
//!
//! Every parity number recorded for this runtime so far compares onnxruntime against the *host
//! interpreter*. That checks the forward passes in `nets/` — which is where the transcription
//! mistakes are — and says nothing whatever about the SPIR-V in `shaders/`, because until the
//! Vulkan layer built on the host there was no way to run it anywhere but a phone. A shader that
//! indexed a tensor wrongly would have shipped with every structural check green.
//!
//! So these run the same plan twice, once through `nets::reference` and once through
//! [`Net`], and require the two to agree. The interpreter is the oracle: it is the thing the
//! ONNX parity scripts already validated.
//!
//! # Running them
//!
//! `#[ignore]`d, because a host with no Vulkan must still pass `cargo test`:
//!
//! ```text
//! cargo test -p modelrunner --lib -- --ignored vulkan::parity
//! ```
//!
//! # Running them segmented
//!
//! [`super::segment`] windows the weights buffer when it is larger than
//! `maxStorageBufferRange`, and on any device this runtime actually targets it never is — so the
//! windowed path would otherwise ship as dead code. Re-running the whole suite with the range
//! forced down exercises it against the same oracle:
//!
//! ```text
//! MODELRUNNER_MAX_STORAGE_RANGE=33554432 \
//!   cargo test -p modelrunner --lib -- --ignored vulkan::parity
//! ```
//!
//! Every number must be identical to the unforced run: windowing changes which descriptor set is
//! bound and what the three weights offsets in [`crate::nets::Push`] are measured from, and
//! nothing else. The variable is read once, when the [`Context`] is created, which is why it is set
//! on the command line rather than inside a test - the device here is process-wide and shared.
//!
//! # Running them on the phone
//!
//! A desktop GPU passing these says nothing about Adreno, which is the only device this runtime
//! actually ships to, and audio is far less forgiving of a shader that is subtly wrong than a
//! segmentation mask is. The test binary cross-compiles and runs from a shell, because `ash` loads
//! `libvulkan.so` at run time rather than linking it:
//!
//! ```text
//! NDK=$ANDROID_HOME/ndk/29.0.14206865/toolchains/llvm/prebuilt/<host>/bin
//! CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK/aarch64-linux-android31-clang \
//!   cargo test --release -p modelrunner --lib --no-run --target aarch64-linux-android
//! adb push target/aarch64-linux-android/release/deps/modelrunner-<hash> /data/local/tmp/mr_test
//! adb shell chmod 755 /data/local/tmp/mr_test
//! adb shell /data/local/tmp/mr_test --ignored vulkan::parity
//! ```
//!
//! The fixtures above need no assets and so run anywhere. The one that reads the shipped `.maml`
//! needs them pushed, and returns quietly when they are absent rather than failing a run that never
//! had them:
//!
//! ```text
//! adb push speech/src/main/assets/supertonic /data/local/tmp/
//! adb shell MODELRUNNER_ASSETS=/data/local/tmp/supertonic \
//!   /data/local/tmp/mr_test --ignored --nocapture the_shipped_supertonic
//! ```
//!
//! # Tolerance
//!
//! Not exact equality. Both sides store activations as fp16, but a shader reduces in parallel
//! and the interpreter sums left to right, so a dot product of `k` terms can differ in the last
//! fp16 place. The comparison is therefore relative to the magnitude of the tensor rather than
//! absolute — an absolute threshold either passes everything for a small output or fails a large
//! one for nothing.

use std::sync::{Arc, OnceLock};

use crate::nets::reference::{run_multi, Given, Invented};
use crate::nets::{embed_lanes, Act, Builder, Id, Plan, Shape, WeightSource};
use crate::preprocess::RESCALE_ONLY;
use crate::weights::{graph, write_mixed, Fixture, Streamed, Weights};

use super::context::{self, Context};
use super::run::{Net, StepParams};

/// Fraction of the tensor's own scale two runs of the same plan may differ by.
///
/// fp16 carries about three decimal digits, so a single rounding is ~5e-4 relative. These plans
/// are a handful of ops deep, and the loosest of them — a softmax over an attention score map —
/// compounds that a few times.
const TOLERANCE: f32 = 4e-3;

/// The shared device, or the reason there is none.
///
/// Not a `panic`: a machine with no Vulkan is a legitimate host for everything else in this
/// crate, and the tests below are `#[ignore]`d precisely so that machine is never asked.
/// The shared device, held for the lifetime of the test process.
///
/// Deliberately not a fresh [`Context`] per test, and deliberately never dropped: every net in
/// the process then shares one device, which is what `:camera` does with its two segmenters and
/// so the arrangement whose thread safety is worth exercising. Letting the last `Arc` go between
/// tests would instead tear down and recreate the `VkInstance` concurrently with another test's
/// net — something no caller of this runtime ever does.
fn device() -> Arc<Context> {
    static HELD: OnceLock<Arc<Context>> = OnceLock::new();
    HELD.get_or_init(|| context::shared().expect("this host has no usable Vulkan device")).clone()
}

/// Run `plan` on the device and return its outputs, in [`Plan::outputs`] order.
fn on_device(plan: Plan, data: Vec<u8>, inputs: &[&[f32]]) -> Vec<Vec<f32>> {
    let weights = Weights::from_data(data);
    let mut net = Net::new(device(), plan, &weights, RESCALE_ONLY)
        .expect("the plan records into a command buffer");
    net.infer_raw_many(inputs).expect("the command buffer submits and reads back")
}

/// Run `plan` on the device with the step parameters a decode plan reads.
fn on_device_at(plan: Plan, data: Vec<u8>, inputs: &[&[f32]], prefix: u32) -> Vec<Vec<f32>> {
    let weights = Weights::from_data(data);
    let mut net = Net::new(device(), plan, &weights, RESCALE_ONLY)
        .expect("the plan records into a command buffer");
    net.set_params(StepParams { prefix, window_start: 0 })
        .expect("the step params are written");
    net.infer_raw_many(inputs).expect("the command buffer submits and reads back")
}

/// Build `record`'s plan against `tensors`, run it both ways and require the two to agree.
///
/// Going through the real [`Builder`] is the point, as it is for the interpreter's own fixtures:
/// the shape propagation and the arena offsets are what a shader is indexing against, so a plan
/// assembled by hand would not be testing the thing that breaks.
fn agrees(
    what: &str,
    shapes: &[Shape],
    inputs: &[&[f32]],
    tensors: &[(Vec<u32>, Vec<f32>)],
    record: impl FnOnce(&mut Builder, &[Id]) -> Id,
) {
    let given = Given::new(tensors).expect("the fixture tensors are consistent");
    let plan = build(&given, shapes, record);
    compare(what, plan, given.data().to_vec(), inputs);
}

/// Run an invented-weight net on the interpreter and return its single output.
///
/// For properties that compare two *different* plans against each other rather than a plan
/// against the device - chunk invariance, for one - where the question is whether the maths is
/// self-consistent, not whether the GPU matches it.
fn run_invented(
    shapes: &[Shape],
    inputs: &[&[f32]],
    record: impl FnOnce(&mut Builder, &[Id]) -> Id,
) -> Vec<f32> {
    let source = Invented::new(0);
    let plan = build(&source, shapes, record);
    let data = source.into_data();
    let mut out = run_multi(&plan, &data, inputs).expect("the interpreter runs the plan");
    out.pop().expect("one output")
}

/// [`agrees`], for a whole net whose weights are invented rather than given.
fn agrees_invented(
    what: &str,
    count: usize,
    shapes: &[Shape],
    inputs: &[&[f32]],
    record: impl FnOnce(&mut Builder, &[Id]) -> Id,
) {
    let source = Invented::new(count);
    let plan = build(&source, shapes, record);
    compare(what, plan, source.into_data(), inputs);
}

fn build(
    source: &dyn WeightSource,
    shapes: &[Shape],
    record: impl FnOnce(&mut Builder, &[Id]) -> Id,
) -> Plan {
    let mut builder = Builder::new(source);
    let ids: Vec<Id> = shapes.iter().map(|&shape| builder.input(shape)).collect();
    let last = record(&mut builder, &ids);
    builder.finish(&[last]).expect("the fixture plan builds")
}

fn compare(what: &str, plan: Plan, data: Vec<u8>, inputs: &[&[f32]]) {
    let host = run_multi(&plan, &data, inputs).expect("the interpreter runs the plan");
    let got = on_device(plan, data, inputs);
    matches(what, &host, &got);
}

/// Require the interpreter's outputs and the device's to agree, one binding at a time.
fn matches(what: &str, host: &[Vec<f32>], got: &[Vec<f32>]) {
    assert_eq!(got.len(), host.len(), "{what}: output count");
    for (index, (host, got)) in host.iter().zip(got).enumerate() {
        assert_eq!(got.len(), host.len(), "{what}: output {index} length");
        // Scale from the interpreter's output, not from the pair, so a device result that came
        // back as zeros cannot shrink the threshold until it passes.
        let scale = host.iter().fold(0.0f32, |top, v| top.max(v.abs())).max(1e-3);
        let worst = host
            .iter()
            .zip(got)
            .enumerate()
            .max_by(|(_, (a, b)), (_, (c, d))| {
                (*a - *b).abs().total_cmp(&(*c - *d).abs())
            })
            .map(|(at, (a, b))| (at, *a, *b));
        if let Some((at, expected, actual)) = worst {
            let error = (expected - actual).abs() / scale;
            assert!(
                error <= TOLERANCE,
                "{what}: output {index} element {at} is {actual} on the device and {expected} \
                 on the host, {error} of the tensor's {scale} scale"
            );
        }
        // A shader that wrote nothing leaves the arena at whatever the last op left, which for
        // the first op in a plan is zero — and a zero tensor is within tolerance of a zero one.
        assert!(host.iter().any(|&v| v != 0.0), "{what}: the interpreter's output is all zeros");
    }
}

/// Values that are distinct, bounded and not symmetric about zero.
///
/// Symmetric inputs hide sign errors: a shader that swapped the halves of a rotary pair, or
/// transposed a score map, produces the right answer on data that happens to be even.
fn spread(count: usize, seed: f32) -> Vec<f32> {
    (0..count).map(|i| (i as f32 * 0.7 + seed).sin() * 0.8 + 0.1).collect()
}

#[test]
#[ignore = "needs a Vulkan device"]
fn the_cnn_spine_agrees_with_the_reference() {
    // Conv with a fused activation, the squeeze-excite pair, max pool, bilinear resize, add,
    // concat and a transposed convolution: the ops the two vision nets are made of.
    let input = spread(3 * 8 * 8, 0.0);
    agrees_invented(
        "the cnn spine",
        8,
        &[Shape::new(3, 8, 8)],
        &[&input],
        |b, ids| {
            let x = b.conv(ids[0], 0, 8, (3, 3), (2, 2), (1, 1), (0, 0, 1, 1), 1, Act::HardSwish);
            let pooled = b.global_avg_pool(x);
            let gate = b.conv_same(pooled, 2, 8, 1, 1, Act::Sigmoid);
            let gated = b.mul_channel(x, gate);
            let deep = b.max_pool_2x2(gated);
            let deep = b.conv_same(deep, 4, 8, 3, 1, Act::Relu);
            let up = b.resize_like(deep, gated);
            let merged = b.add(gated, up);
            let joined = b.concat(&[merged, up]);
            b.conv_transpose(joined, 6, 1, (2, 2), (2, 2), (0, 0, 0, 0), Act::Sigmoid)
        },
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn cached_attention_agrees_with_the_reference() {
    // A whole decode-step self-attention: one query against a position-major cache, through
    // softmax and back out channel-major. `nets::reference` is the only oracle for the two new
    // shaders, so this is what says the SPIR-V matches it on real hardware.
    //
    // d_model 8 in two heads, five cached positions. The cache is `[5, 1, 8]` — positions as
    // channels — which is the layout that makes appending one position a single contiguous copy.
    // A shader that read it channel-major would produce the right shape from the wrong vectors.
    let query = spread(8, 0.0);
    let cache = spread(5 * 8, 0.7);
    agrees(
        "cached attention",
        &[Shape::new(8, 1, 1), Shape::new(5, 1, 8)],
        &[&query, &cache],
        &[],
        |b, ids| {
            let scores = b.attn_scores_cached(ids[0], ids[1], 2);
            let probs = b.softmax(scores);
            b.attn_apply_cached(probs, ids[1], 2)
        },
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_cached_score_map_alone_agrees_with_the_reference() {
    // The scores on their own, undivided by a softmax, so a wrong `1 / sqrt(head_dim)` or a
    // transposed head range shows up as a value rather than as a redistribution. Four heads of
    // two over three positions.
    let query = spread(8, 0.2);
    let cache = spread(3 * 8, 1.1);
    agrees(
        "cached scores",
        &[Shape::new(8, 1, 1), Shape::new(3, 1, 8)],
        &[&query, &cache],
        &[],
        |b, ids| b.attn_scores_cached(ids[0], ids[1], 4),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_causal_softmax_agrees_with_the_reference() {
    // `softmax_causal.comp`, the only new shader TinyCLIP needs. The CPU interpreter is the only
    // oracle for the SPIR-V, so this is what says the two compute the same mask on real hardware.
    //
    // Two heads over T 5, which makes both indexing mistakes visible: the query index is
    // `row % out_h`, so a shader taking the flat row would leave head 1 unmasked, and the bound is
    // `query + 1`, so an off-by-one shows up as row 0 being a pair rather than a point.
    let scores = spread(2 * 5 * 5, 0.4);
    agrees(
        "a causal softmax",
        &[Shape::new(2, 5, 5)],
        &[&scores],
        &[],
        |b, ids| b.softmax_causal(ids[0]),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_causal_text_attention_agrees_with_the_reference() {
    // The whole of TinyCLIP's text-tower attention: scores, the causal softmax and the weighted
    // sum, in the shapes the real tower uses in miniature. `attn_apply.comp` multiplies the masked
    // tail as well as the head, so a shader that left the tail stale rather than zeroing it is
    // invisible in the softmax's own output and shows up here.
    let q = spread(8 * 4, 0.1);
    let k = spread(8 * 4, 0.9);
    let v = spread(8 * 4, 1.6);
    let given = Given::new(&[]).expect("no tensors");
    let mut builder = Builder::new(&given);
    let qi = builder.input(Shape::new(8, 1, 4));
    let ki = builder.input(Shape::new(8, 1, 4));
    let vi = builder.input(Shape::new(8, 1, 4));
    let scores = builder.attn_scores(qi, ki, 2);
    let probs = builder.softmax_causal(scores);
    let out = builder.attn_apply(probs, vi, 2);
    let plan = builder.finish(&[out]).expect("the fixture plan builds");
    compare("a causal text attention", plan, given.data().to_vec(), &[&q, &k, &v]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_position_concat_moves_the_same_columns_on_the_device() {
    // TinyCLIP's class token: `c * h` strided `vkCmdCopyBuffer`s rather than the one a channel
    // concat gets away with. What is under test is the destination stride, which is the output's
    // width and not the part's.
    let token = spread(6, 0.5);
    let grid = spread(6 * 4, 1.3);
    let given = Given::new(&[]).expect("no tensors");
    let mut builder = Builder::new(&given);
    let t = builder.input(Shape::new(6, 1, 1));
    let g = builder.input(Shape::new(6, 1, 4));
    let joined = builder.concat_positions(&[t, g]);
    let plan = builder.finish(&[joined]).expect("the fixture plan builds");
    compare("a position concat", plan, given.data().to_vec(), &[&token, &grid]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_reshape_moves_the_same_elements_on_the_device() {
    // The relabelling that lets a `[d_model, 1, 1]` projection become a `[1, 1, d_model]` cache
    // position. One `vkCmdCopyBuffer`, so what is under test is that the shapes either side agree
    // about the element count.
    let x = spread(16, 0.3);
    agrees(
        "reshape",
        &[Shape::new(16, 1, 1)],
        &[&x],
        &[],
        |b, ids| b.reshaped(ids[0], Shape::new(1, 1, 16)),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_decode_plan_built_at_the_maximum_matches_one_built_at_the_length() {
    // The invariant the whole record-once decode design rests on: a plan built for `MAX` cache
    // positions and run with `prefix = KEYS - 1` must produce what a plan built for exactly
    // `KEYS` positions produces. If that holds, the plan stops depending on the step number and
    // `Reshaped` stops re-recording per token.
    //
    // The cache is deliberately `MAX` positions of which only the first `KEYS` are real; the tail
    // is filled with values far outside the live range. Zeros there would let a shader that
    // ignored the bound still pass — `exp` of a zero score is a finite weight against a zero
    // value, which perturbs little. Large values cannot be silently absorbed: if any of the three
    // shaders reads past the bound, the softmax denominator or the weighted sum moves by orders
    // of magnitude and the comparison fails loudly.
    const D_MODEL: u32 = 64;
    const HEADS: u32 = 8;
    const KEYS: u32 = 5;
    const MAX: u32 = 32;

    let query = spread(D_MODEL as usize, 0.3);
    let live: Vec<f32> = spread((KEYS * D_MODEL) as usize, 1.1);
    // The same live prefix, then a tail no correct run may read.
    let mut padded = live.clone();
    padded.extend((0..((MAX - KEYS) * D_MODEL)).map(|i| if i % 2 == 0 { 90.0 } else { -90.0 }));

    // Built at exactly the live length, with the ordinary fixed-length ops.
    let at_length = {
        let source = Invented::new(0);
        let mut builder = Builder::new(&source);
        let q = builder.input(Shape::new(D_MODEL, 1, 1));
        let cache = builder.input(Shape::new(KEYS, 1, D_MODEL));
        let scores = builder.attn_scores_cached(q, cache, HEADS);
        let probs = builder.softmax(scores);
        let out = builder.attn_apply_cached(probs, cache, HEADS);
        let plan = builder.finish(&[out]).expect("the fixed-length decode plan builds");
        crate::nets::tests::assert_no_aliasing(&plan);
        on_device(plan, source.into_data(), &[&query, &live])
    };

    // Built at the maximum, told the real length at submit time.
    let build_at_maximum = || {
        let source = Invented::new(0);
        let mut builder = Builder::new(&source);
        let q = builder.input(Shape::new(D_MODEL, 1, 1));
        let cache = builder.input(Shape::new(MAX, 1, D_MODEL));
        let scores = builder.attn_scores_cached_dynamic(q, cache, HEADS);
        let probs = builder.softmax_prefix(scores, true);
        let out = builder.attn_apply_cached_dynamic(probs, cache, HEADS);
        let plan = builder.finish(&[out]).expect("the maximum-length decode plan builds");
        crate::nets::tests::assert_no_aliasing(&plan);
        (plan, source.into_data())
    };

    // `prefix` is the positions already cached, so this step attends over those plus its own.
    let (plan, data) = build_at_maximum();
    let at_maximum = on_device_at(plan, data, &[&query, &padded], KEYS - 1);
    matches("a decode plan built at its maximum", &at_length, &at_maximum);

    // And the bound is really being read, rather than the two agreeing for some other reason: at
    // a different prefix the same recording must produce something else. Without this the test
    // would still pass if every shader ignored `step_params` and used the full width, provided
    // the padding happened not to matter.
    let (plan, data) = build_at_maximum();
    let at_wrong_prefix = on_device_at(plan, data, &[&query, &padded], KEYS);
    let moved = at_length
        .iter()
        .zip(&at_wrong_prefix)
        .flat_map(|(a, b)| a.iter().zip(b))
        .any(|(a, b)| (a - b).abs() > TOLERANCE);
    assert!(moved, "attending over one more position changed nothing, so the bound is unread");
}

include!("parity_part1.rs");
include!("parity_part2.rs");
include!("parity_part3.rs");
include!("parity_part4.rs");
include!("parity_part9.rs");
include!("parity_part5.rs");
include!("parity_part6.rs");
include!("parity_part10.rs");
include!("parity_part7.rs");
include!("parity_part8.rs");
include!("parity_part11.rs");