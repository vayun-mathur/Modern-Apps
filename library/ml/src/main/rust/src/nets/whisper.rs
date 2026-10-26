//! whisper-base: a 30-second audio encoder and a KV-cached decoder, in one `.maml`.
//!
//! # What it is
//!
//! An encoder-decoder transformer over log-mel spectrograms: a two-convolution stem that turns
//! `[80, 3000]` mel frames into 1500 positions, then 6 encoder layers; 6 decoder layers with self
//! and cross attention; `d_model` 512, 8 heads of 64, a 2048-wide feed-forward, and a 51,865-entry
//! vocabulary shared between the input embedding and the logits projection. 72,593,920 parameters,
//! 70.6 MiB as int8.
//!
//! It replaces onnxruntime running two int8 ONNX exports totalling 76.9 MB
//! (`speech/src/main/assets/whisper-base/*.onnx`), and with them the last third-party inference
//! runtime in the tree. `:speech` used the **reduced** `onnxruntime-reduced-android` build, so what
//! actually left is 10,466,856 bytes of arm64 `.so` — about 3.6 MiB deflated in the APK — against the
//! 797 KB `libmodelrunner.so` that was already there for Supertonic. The release APK went from
//! 188.1 MiB to a measured **181.8 MiB**; the weights are most of both numbers.
//!
//! It is also **four times closer** to the fp32 checkpoint than what it replaces. Over the encoder's
//! `[1500, 512]` output on one deterministic window, against an fp32 reference computed from the
//! checkpoint directly:
//!
//! | | max | mean | correlation |
//! | :--- | ---: | ---: | ---: |
//! | the shipped `encoder_model_int8.onnx` | 14.33 | 0.1101 | 0.995204 |
//! | this `.maml` | **3.19** | **0.0293** | **0.999633** |
//!
//! on a tensor whose largest value is 22.8. That gap is per-output-channel quantisation against the
//! export's per-tensor dynamic quantisation, the same difference `nets::nllb` was ported for.
//! `scripts/ml/onnx_parity.py whisper` reproduces the middle column of it.
//!
//! # The arithmetic, measured rather than estimated
//!
//! ~43.7 GMAC per 30-second window in the encoder, regardless of how much speech is in it: 7.02 GMAC
//! per layer — 1.57 in the four projections, 3.15 in the feed-forward and 2.30 in the
//! `[8, 1500, 1500]` attention — plus 1.55 in the conv stem. A decode step is 60 MMAC by comparison.
//! Whether that is usable is a device question and the one open item in this port.
//!
//! # One file, two passes
//!
//! [`Mode`] selects which. They share the file because the embedding is **tied**: it is the
//! decoder's input table and the logits kernel, and two files would upload 26.6 MB of it twice. At
//! 51,865 x 512 that binding is well inside `maxStorageBufferRange`'s guaranteed 128 MiB, so unlike
//! `nets::nllb`'s 256,206-row head it needs no class split.
//!
//! # The encoder produces the cross-attention keys and values, not the hidden states
//!
//! Whisper's cross-attention reads the encoder output through each decoder layer's own `k_proj` and
//! `v_proj`, and those depend on nothing that changes between steps. Recomputing them per step is
//! what `nets::nllb` does, and it is fine there because a source sentence is tens of positions.
//! Here it is **1500**: twelve `512 x 512` projections over 1500 positions is 4.7 GMAC *per decode
//! step*, against the 26.5 MMAC of the logits head. That is 177 times the head, and at 224 tokens it
//! is a thousand GMAC.
//!
//! So [`Mode::Encode`] runs them once and copies them into **twelve** persistent `[512, 1, 1500]`
//! tensors that [`Mode::DecodeStep`] reads in place. Nothing comes back to the host and nothing is
//! re-uploaded: an earlier version handed them to the host and back in, 18.4 MB per token.
//!
//! Both passes declare those tensors first, through [`declare_cross_caches`], because
//! [`Builder::finish`] gives pinned tensors their arena offsets in declaration order, and that is
//! what puts them in the same place in both plans. Nothing is transposed: a single-query
//! cross-attention reads a channel-major sequence through the ordinary [`Builder::attn_scores`]
//! pair, exactly as `nets::nllb`'s does.
//!
//! # The two position tables go opposite ways
//!
//! Both are real tensors in the checkpoint, not computed sinusoids. The **encoder's** `[1500, 512]`
//! is added to the conv stem's output, which is a device tensor, so the converter transposes it to
//! `[512, 1, 1500]` and it arrives as a [`crate::nets::Kind::Constant`]. The **decoder's**
//! `[448, 512]` is added to a gathered embedding row on the *host*, in f32, as
//! [`embed_positions`] does — so it is left `[448, 512]` and never reaches a shader.
//!
//! There is no `sqrt(d_model)` on the embedding: `config.json` has `scale_embedding: false`.
//!
//! # The conv stem's stride is what sets the sequence length
//!
//! `conv1` is `1 x 3` stride 1 and `conv2` is `1 x 3` **stride 2**, both `same`-padded, both
//! followed by GELU. 3000 mel frames therefore become 1500 encoder positions. A wrong stride gives
//! the right rank and the wrong length, and nothing downstream checks the length — which is why
//! `tests::the_conv_stem_halves_the_frame_count` pins it.
//!
//! `conv2` is 1.18 GMAC on [`crate::nets::Kind::ConvInt8`]'s untiled path, because the tiled int8
//! shader is `1 x 1` only. Flagged to measure rather than pre-optimised.
//!
//! # No attention mask, and none needed
//!
//! * The encoder attends over one 30-second window with no padding, so every key is real.
//! * The decoder decodes one token at a time, so a step is **one query against `cache_len + 1`
//!   keys**, which is causal by construction. That is what [`Builder::attn_scores_cached`] is for.
//! * The cross-attention is one query over all 1500 encoder positions.
//!
//! [`Builder::attn_scores`] applies `1 / sqrt(head_dim)` itself and `head_dim` is 64, which is
//! exactly `WhisperAttention`'s own scaling. So nothing folds a query scale.
//!
//! # Pre-norm
//!
//! `WhisperEncoderLayer` and `WhisperDecoderLayer` normalise **before** each sublayer and skip
//! around both, and each stack ends with one more layer norm. Written the other way round the net
//! still runs and still produces words.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use crate::weights::Reader;

/// Channels throughout: `d_model`.
pub const D_MODEL: u32 = 512;

/// Attention heads, so `head_dim` is 64 and [`Builder::attn_scores`]'s own scale is the model's.
pub const HEADS: u32 = 8;

/// The feed-forward width, `encoder_ffn_dim` and `decoder_ffn_dim`.
pub const FFN: u32 = 2048;

/// Mel bins the front end produces, `num_mel_bins`.
pub const MELS: u32 = 80;

/// Mel frames in one 30-second window, which is all whisper ever consumes.
pub const MEL_FRAMES: u32 = 3000;

/// Encoder positions, `max_source_positions`. [`MEL_FRAMES`] after `conv2`'s stride 2.
pub const SOURCE_POSITIONS: u32 = MEL_FRAMES / 2;

/// Decoder positions, `max_target_positions`, and so the longest transcript of one window.
pub const MAX_POSITIONS: u32 = 448;

/// Vocabulary entries, shared by the embedding and the logits projection.
pub const VOCAB: u32 = 51_865;

/// Encoder layers.
pub const ENCODER_LAYERS: usize = 6;

/// Decoder layers.
pub const DECODER_LAYERS: usize = 6;

/// The conv stem's kernel width. Both convolutions are `1 x 3`, `same`-padded.
const CONV_KERNEL: u32 = 3;

/// The epsilon in every layer norm.
const EPSILON: f32 = 1e-5;

/// Tensors per encoder layer: two norms of two, four projections of three, two more of three.
const ENCODER_LAYER_TENSORS: usize = 2 + 4 * 3 + 2 + 3 + 3;

/// Tensors per decoder layer: an encoder layer plus a cross-attention norm and four projections.
const DECODER_LAYER_TENSORS: usize = ENCODER_LAYER_TENSORS + 2 + 4 * 3;

/// `conv1`: int8 kernel, per-channel scale, bias.
const CONV1: usize = 0;

/// `conv2`, the stride-2 one.
const CONV2: usize = CONV1 + 3;

/// The encoder position table, transposed to `[D_MODEL, 1, SOURCE_POSITIONS]`.
const ENC_POSITIONS: usize = CONV2 + 3;

/// The first encoder layer.
const ENCODER: usize = ENC_POSITIONS + 1;

/// The encoder's trailing layer norm.
const ENC_NORM: usize = ENCODER + ENCODER_LAYERS * ENCODER_LAYER_TENSORS;

/// The tied embedding: int8 kernel, per-class scale, synthesised zero bias.
///
/// Two roles, one tensor. [`embed_positions`] gathers a row of it on the host to build a decode
/// step's input, and [`Mode::DecodeStep`] binds the same tensor as the logits kernel.
const HEAD: usize = ENC_NORM + 2;

/// The decoder position table, left `[MAX_POSITIONS, D_MODEL]` for the host.
const DEC_POSITIONS: usize = HEAD + 3;

/// The first decoder layer.
const DECODER: usize = DEC_POSITIONS + 1;

/// The decoder's trailing layer norm.
const DEC_NORM: usize = DECODER + DECODER_LAYERS * DECODER_LAYER_TENSORS;

/// Tensors the `.maml` must hold, and the count `maml_convert.py` writes.
pub const TENSORS: usize = DEC_NORM + 2;

/// Convolutions read as int8 rather than fp16, each carrying a third tensor for its scale.
///
/// The conv stem's two, all six projections of each encoder layer, all ten of each decoder layer,
/// and the tied head. Only the layer norms, the biases and the two position tables stay fp16, and
/// together they are 1.5% of the parameters.
pub const INT8_CONVS: usize = 2 + ENCODER_LAYERS * 6 + DECODER_LAYERS * 10 + 1;

/// Which forward pass [`build`] emits.
///
/// One file and two plans, run through [`crate::vulkan::run::Net::rebuild`] rather than two nets, so
/// the 70.6 MiB upload happens once.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// The audio encoder, and every decoder layer's cross-attention K and V.
    ///
    /// `[80, 1, 3000]` in — the log-mel window. One output, the `[512, 1, 1500]` hidden states,
    /// which only `scripts/ml/onnx_parity.py` reads. The cross K and V stay on the device for
    /// [`Mode::DecodeStep`]; see [`declare_cross_caches`].
    Encode,
    /// One decoder step against the self-attention cache.
    ///
    /// A step is **one query** against `prefix + 1` keys — the positions already decoded plus this
    /// one — which is causal by construction and needs no mask.
    ///
    /// It carries no step number, deliberately: one that did would make every token a different
    /// `Reshaped` key and re-record the plan per token. The plan is built once for
    /// [`MAX_POSITIONS`], the caches live on the device, and the live length arrives in
    /// [`crate::vulkan::run::StepParams::prefix`] at submit time.
    DecodeStep,
}

/// Hands out `.maml` tensor indices in the order the layers appear.
struct Layers {
    next: usize,
}

impl Layers {
    /// A weight and the bias after it: a layer norm's gamma and beta.
    fn take(&mut self) -> usize {
        let index = self.next;
        self.next += 2;
        index
    }

    /// An int8 kernel, its per-output-channel scale, and the bias after that.
    fn take3(&mut self) -> usize {
        let index = self.next;
        self.next += 3;
        index
    }

    /// Step over `count` projections this pass does not read. See [`Mode::Encode`].
    fn skip3(&mut self, count: usize) {
        self.next += count * 3;
    }
}

/// A `1 x 1` convolution with an int8 kernel, which every projection here is.
fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv_int8(x, l.take3(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// The pre-norm feed-forward sublayer, plus its residual. GELU, not ReLU.
fn feed_forward(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let normed = b.layer_norm(x, l.take(), EPSILON);
    let inner = point(b, l, normed, FFN, Act::Gelu);
    let projected = point(b, l, inner, D_MODEL, Act::None);
    b.add(x, projected)
}

/// The `.maml` index of decoder layer `layer`'s first tensor.
fn decoder_layer(layer: usize) -> usize {
    DECODER + layer * DECODER_LAYER_TENSORS
}

/// Decoder layer `layer`'s cross-attention **key** projection triple. The value triple follows it.
///
/// Derived from the layout rather than tabulated, because [`Mode::Encode`] reads exactly these two
/// triples out of each decoder layer and [`Mode::DecodeStep`] reads everything else: a wrong offset
/// here makes one pass read the other's weights.
fn cross_kv(layer: usize) -> usize {
    // A layer is: self norm (2), four self projections (12), cross norm (2), then cross q (3).
    decoder_layer(layer) + 2 + 4 * 3 + 2 + 3
}

/// Build one of whisper's two passes. See [`Mode`].
pub fn build(weights: &dyn WeightSource, mode: Mode) -> Result<Plan, String> {
    match mode {
        Mode::Encode => encode(weights),
        Mode::DecodeStep => decode_step(weights),
    }
}

/// Every decoder layer's cross-attention K and V, `[512, 1, 1500]` each, held on the device.
///
/// Must be the first pinned tensors either pass declares, so both plans give them the same arena
/// offsets. See the module docs.
fn declare_cross_caches(b: &mut Builder) -> Vec<(Id, Id)> {
    (0..DECODER_LAYERS)
        .map(|_| {
            let k = b.persistent(Shape::new(D_MODEL, 1, SOURCE_POSITIONS));
            let v = b.persistent(Shape::new(D_MODEL, 1, SOURCE_POSITIONS));
            (k, v)
        })
        .collect()
}

/// The audio encoder over one 30-second window, plus the twelve cross-attention caches.
fn encode(weights: &dyn WeightSource) -> Result<Plan, String> {
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // Its 1500-position int8 GEMMs run 22% faster over fp16 copies (Pixel 8); exact.
    b.widen_int8();
    name_host_tensors(b, &device_tensors(Mode::Encode));

    let caches = declare_cross_caches(b);
    let mel = b.input(Shape::new(MELS, 1, MEL_FRAMES));
    // `1 x 3` same-padded, GELU, stride 1 then stride 2. The second stride is what turns 3000 mel
    // frames into the 1500 positions the decoder cross-attends over.
    let l = &mut Layers { next: CONV1 };
    let x = b.conv_int8(
        mel,
        l.take3(),
        D_MODEL,
        (1, CONV_KERNEL),
        (1, 1),
        (1, 1),
        (0, 1, 0, 1),
        1,
        Act::Gelu,
    );
    let x = b.conv_int8(
        x,
        l.take3(),
        D_MODEL,
        (1, CONV_KERNEL),
        (1, 2),
        (1, 1),
        (0, 1, 0, 1),
        1,
        Act::Gelu,
    );
    let positions = b.constant(ENC_POSITIONS, Shape::new(D_MODEL, 1, SOURCE_POSITIONS));
    let mut x = b.add(x, positions);

    let l = &mut Layers { next: ENCODER };
    for _ in 0..ENCODER_LAYERS {
        let normed = b.layer_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, D_MODEL, Act::None);
        let k = point(b, l, normed, D_MODEL, Act::None);
        let v = point(b, l, normed, D_MODEL, Act::None);
        let scores = b.attn_scores(q, k, HEADS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply(probs, v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);
        x = feed_forward(b, l, x);
    }
    if l.next != ENC_NORM {
        return Err(format!("the encoder claims {} tensors, not {ENC_NORM}", l.next));
    }
    let encoded = b.layer_norm(x, ENC_NORM, EPSILON);

    // Each decoder layer's cross-attention key and value, computed once for the whole transcript
    // and left on the device.
    for (layer, &(cache_k, cache_v)) in caches.iter().enumerate() {
        let cross = &mut Layers { next: cross_kv(layer) };
        let k = point(b, cross, encoded, D_MODEL, Act::None);
        let v = point(b, cross, encoded, D_MODEL, Act::None);
        if cross.next != cross_kv(layer) + 6 {
            return Err(format!("layer {layer}'s cross cache ends at {}", cross.next));
        }
        b.copy_into(k, cache_k);
        b.copy_into(v, cache_v);
    }
    builder.finish(&[encoded])
}

/// One decoder step.
///
/// # Inputs
///
/// One: the current token, `[512, 1, 1]`, after [`embed_positions`] at `past = prefix`.
///
/// The cross-attention K and V are not inputs: [`Mode::Encode`] left them in persistent tensors at
/// the offsets this plan declares them at. The self-attention caches live in the arena too, and
/// this plan writes into them at the row the step names. See [`Builder::persistent`].
///
/// # Outputs, in declaration order
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0 | `[51865, 1, 1]` | the logits, which the host argmaxes |
fn decode_step(weights: &dyn WeightSource) -> Result<Plan, String> {
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    name_host_tensors(b, &device_tensors(Mode::DecodeStep));

    // First, so the offsets match the encode pass's.
    let cross = declare_cross_caches(b);
    let mut x = b.input(Shape::new(D_MODEL, 1, 1));
    // Sized for the longest transcript of one window rather than for this step, so the plan does
    // not mention the step number and is recorded once.
    let past: Vec<(Id, Id)> = (0..DECODER_LAYERS)
        .map(|_| {
            let k = b.persistent(Shape::new(MAX_POSITIONS, 1, D_MODEL));
            let v = b.persistent(Shape::new(MAX_POSITIONS, 1, D_MODEL));
            (k, v)
        })
        .collect();

    for (layer, (&(cross_k, cross_v), &(cache_k, cache_v))) in cross.iter().zip(&past).enumerate() {
        let l = &mut Layers { next: decoder_layer(layer) };

        // Self-attention, pre-norm, against the cache this step is about to extend.
        let normed = b.layer_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, D_MODEL, Act::None);
        let k_new = point(b, l, normed, D_MODEL, Act::None);
        let v_new = point(b, l, normed, D_MODEL, Act::None);
        // A projection writes `[d_model, 1, 1]`; a cache position is `[1, 1, d_model]`. The same
        // bytes, so this is a relabelling and the write below is one contiguous copy.
        let k_row = b.reshaped(k_new, Shape::new(1, 1, D_MODEL));
        let v_row = b.reshaped(v_new, Shape::new(1, 1, D_MODEL));
        b.cache_write(k_row, cache_k);
        b.cache_write(v_row, cache_v);
        let scores = b.attn_scores_cached_dynamic(q, cache_k, HEADS);
        let probs = b.softmax_prefix(scores, true);
        let mixed = b.attn_apply_cached_dynamic(probs, cache_v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);

        // Cross-attention over the encoder output. One query against 1500 channel-major keys, so it
        // uses the ordinary pair rather than the cached one, and needs no transpose.
        let normed = b.layer_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, D_MODEL, Act::None);
        // The key and value projections were run by `Mode::Encode`; their results are on the
        // device.
        l.skip3(2);
        let scores = b.attn_scores(q, cross_k, HEADS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply(probs, cross_v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);

        x = feed_forward(b, l, x);
        if l.next != decoder_layer(layer + 1) {
            return Err(format!("layer {layer} ends at {}, not {}", l.next, decoder_layer(layer + 1)));
        }
    }
    let state = b.layer_norm(x, DEC_NORM, EPSILON);

    // The tied head, in the same plan: a separate pass would mean a second rebuild and a round trip
    // through the host for a `[512]` vector.
    let head = &mut Layers { next: HEAD };
    let logits = point(b, head, state, VOCAB, Act::None);
    if head.next != DEC_POSITIONS {
        return Err(format!("the head claims {} tensors, not {DEC_POSITIONS}", head.next));
    }
    // The K and V rows are no longer outputs: they are in the cache, on the device.
    builder.finish(&[logits])
}

include!("whisper_part1.rs");
include!("whisper_part2.rs");