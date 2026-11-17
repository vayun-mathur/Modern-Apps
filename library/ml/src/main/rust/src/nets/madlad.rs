//! MADLAD400-3B-MT: a T5 encoder-decoder translation model, in one `.maml`.
//!
//! # What it is
//!
//! MADLAD-400 trained for machine translation: 32 encoder layers, 32 decoder layers,
//! `d_model` 1024, 16 heads of `d_kv` 128 (`inner_dim` 2048), an 8192-wide gated-GELU
//! feed-forward, and a 256,000-entry vocabulary with **untied** embeddings — the input
//! table and the logits kernel are separate tables, each emitted as four 64,000-class
//! splits (256,000 divides evenly, so unlike NLLB there is no uneven tail).
//!
//! The architecture is `T5ForConditionalGeneration` (`model_type: t5`,
//! `feed_forward_proj: gated-gelu`, `tie_word_embeddings: false`). The forward pass
//! follows `nets::nllb` structurally — pre-norm layers, cached-KV decode, host-side
//! embedding gather, fd-handoff handles — with four T5 differences, each pinned by a
//! layout test:
//!
//! 1. **RMSNorm, not LayerNorm.** Every norm is `T5LayerNorm`: gain only, no bias,
//!    epsilon 1e-6. [`Builder::rms_norm`], as Maia's is.
//! 2. **GEGLU, not ReLU.** `wo(GELU(wi_0(x)) * wi_1(x))` over the converter's fused
//!    `[wi_0 rows | wi_1 rows]` projection, via [`Builder::gated_activate`] with
//!    [`Act::Gelu`]. T5's is the tanh (`gelu_new`) approximation and the shader is
//!    exact-erf; the two differ by at most 4.7e-4, and fp16's step at that magnitude
//!    is 3.1e-2, so the difference is sixty times finer than the arena can represent.
//! 3. **No query scaling.** T5 applies none (Mesh TensorFlow init), so both attentions
//!    use [`Builder::attn_scores_prescaled`] / `attn_scores_cached_prescaled`, whose
//!    scale is exactly 1.0. Deriving NLLB's `1 / sqrt(head_dim)` here would divide
//!    every score by 11.3 — not a shape error, and no layout test would see it.
//! 4. **Relative position bias, host-computed.** T5's position signal is a 32-bucket
//!    table lookup added to the score maps. [`relative_bias`] computes it from the
//!    two block-0 tables; the net takes it as a plan **input** and adds it with a
//!    plain [`Builder::add`]. No T5-specific shader logic: the bias is data, not topology.
//!
//! # One file, two passes
//!
//! [`Mode`] selects which of them [`build`] emits. They share the file the way NLLB's
//! do, but the head is *two* tables rather than one: [`Mode::DecodeStep`] emits four
//! `ConvVecInt8` ops over the `lm_head` splits, and gathers rows of the `shared` splits
//! on the host. `tests::the_passes_cover_the_file_and_every_one_of_them_builds` keeps
//! the two in step.
//!
//! # The embedding is gathered on the host, with NO scale and NO positions
//!
//! T5 has no `scale_embedding` and no position tables — learned or sinusoidal. A token's
//! input vector is one table row, dequantised:
//!
//! ```text
//! x[t] = embedding[id[t]]
//! ```
//!
//! [`embed_positions`] reads the row with [`crate::weights::Reader::q2k_row`] and hands
//! it in as an ordinary fp16 plan input. The name keeps NLLB's (`past` still offsets
//! nothing — it is kept so the call sites read the same), but there is no `+ 2` and no
//! sinusoid: T5's first token sits at position 0, and the relative bias carries the
//! order.
//!
//! # The bias is an input, not a weight
//!
//! A bias table is `[32 buckets, 16 heads]`, but what the net adds is the *gathered*
//! `[heads, queries, keys]` matrix for one shape — which differs per length, and per
//! step in decode. Baking it as weights would mean a table per shape; instead the host
//! computes it per call ([`relative_bias`]) and uploads it beside the token. The cost
//! is one extra input and one `Add` dispatch per self-attention — and the plan stays
//! step-independent, so `Reshaped::at` still matches after the first token.
//!
//! Cross-attention has no bias (`has_relative_attention_bias` is false for every
//! `EncDecAttention`), so it uses the ordinary unprescaled pair exactly as NLLB's does
//! — except prescaled, since T5 never scales.
//!
//! # Pre-norm
//!
//! As NLLB's: each stack normalises **before** each sublayer and skips around both,
//! ending with one more RMS norm.
//!
//! # Decode protocol
//!
//! The **target** tag (`<2xx>`) leads the encoder source, and the decoder starts from
//! `decoder_start_token_id` 0 (`<unk>`, which doubles as the start token) with no
//! forced-BOS override. See `post::madlad::translate`. Getting the tag side backwards
//! produces fluent output in the wrong language rather than an error.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use super::madlad_extra2::decode_step;

/// Channels throughout: `d_model`.
pub const D_MODEL: u32 = 1024;

/// Attention heads, each of `d_kv` 128. The net never scales by it: see the module docs.
pub const HEADS: u32 = 16;

/// Head dimension, `d_kv`: `inner_dim` 2048 over 16 heads.
pub const HEAD_DIM: u32 = 128;

/// Attention inner width: `HEADS * HEAD_DIM` (2048). Every q/k/v projects to this, and `o`
/// projects back from it — unlike NLLB, where `d_model` serves both sides.
pub const INNER: u32 = HEADS * HEAD_DIM;

/// The feed-forward width, `d_ff`. Each half of the fused projection.
pub const FFN: u32 = 8192;

/// Vocabulary entries. Untied: the input table and the logits kernel are separate.
pub const VOCAB: u32 = 256_000;

/// Class ranges each untied table is emitted as. Even: 256,000 = 4 x 64,000.
pub const HEAD_SPLITS: usize = 4;

/// Classes per split.
pub const CLASSES_PER_SPLIT: u32 = 64_000;

/// Classes in split `split`: uniform, unlike NLLB's uneven tail.
pub fn split_classes(split: usize) -> u32 {
    debug_assert!(split < HEAD_SPLITS, "split {split} of {HEAD_SPLITS}");
    CLASSES_PER_SPLIT
}

/// Encoder layers, `num_layers`.
pub const ENCODER_LAYERS: usize = 32;

/// Decoder layers, `num_decoder_layers`.
pub const DECODER_LAYERS: usize = 32;

/// Relative buckets, `relative_attention_num_buckets`.
pub const BUCKETS: u32 = 32;

/// `relative_attention_max_distance`.
pub const MAX_DISTANCE: u32 = 128;

/// Decoder positions the KV cache is built to hold.
///
/// The decode plan is recorded once at this length rather than rebuilt per token, so this is
/// charged to the arena in full for every translation: `2 * DECODER_LAYERS` caches of
/// `MAX_DECODE_POSITIONS * D_MODEL` fp16, which is 16 MB at these numbers.
///
/// It matches `post::madlad::MAX_TOKENS`, the cap on the greedy loop, so the loop cannot
/// outrun the cache. Raising one without the other either wastes arena or drops positions:
/// `shaders/cache_write.comp` refuses a write past the end rather than running off it.
pub const MAX_DECODE_POSITIONS: u32 = 128;

/// The epsilon in every RMS norm, `layer_norm_epsilon`.
pub(crate) const EPSILON: f32 = 1e-6;

/// Tensors per encoder layer: two single-tensor norms, four projections of three,
/// two more of three (the fused wi_01 counts as one projection of three).
pub(crate) const ENCODER_LAYER_TENSORS: usize = 1 + 4 * 3 + 1 + 3 + 3;

/// Tensors per decoder layer: an encoder layer plus a cross-attention norm and four projections.
pub(crate) const DECODER_LAYER_TENSORS: usize = ENCODER_LAYER_TENSORS + 1 + 4 * 3;

/// The first of the four input-table splits. They come first, because the embedding is read
/// before anything else and the file is written in forward order.
pub(crate) const HEAD_SHARED: usize = 0;

/// The first of the four logits-kernel splits.
pub(crate) const HEAD_LM: usize = HEAD_SHARED + HEAD_SPLITS * 3;

/// The first encoder layer.
pub(crate) const ENCODER: usize = HEAD_LM + HEAD_SPLITS * 3;

/// The encoder's trailing norm.
pub(crate) const ENCODER_NORM: usize = ENCODER + ENCODER_LAYERS * ENCODER_LAYER_TENSORS;

/// The first decoder layer.
pub(crate) const DECODER: usize = ENCODER_NORM + 1;

/// The decoder's trailing norm.
pub(crate) const DECODER_NORM: usize = DECODER + DECODER_LAYERS * DECODER_LAYER_TENSORS;

/// The encoder self-attention bias table: fp16 `[BUCKETS, HEADS]`, read on the host.
pub const TABLE_ENC: usize = DECODER_NORM + 1;

/// The decoder self-attention bias table. Last.
pub const TABLE_DEC: usize = TABLE_ENC + 1;

/// Tensors the `.maml` must hold, and the count `maml_convert.py` writes: 24 head +
/// 640 encoder + 1 + 1056 decoder + 1 + 2 tables.
pub const TENSORS: usize = TABLE_DEC + 1;

/// Convolutions read as Q2_K rather than fp16, each carrying a third tensor for its scale.
///
/// **Every one of them.** Eight head splits, all six projections of each encoder layer
/// (self q/k/v/o plus the fused wi_01 and wo) and all ten of each decoder layer
/// (self four, cross four, fused wi_01, wo). Only the RMS gains, the synthesised
/// biases and the two bias tables stay fp16.
pub const Q2K_CONVS: usize = HEAD_SPLITS * 2 + ENCODER_LAYERS * 6 + DECODER_LAYERS * 10;

/// Which forward pass [`build`] emits.
///
/// One graph and two plans, run through [`crate::vulkan::run::Net::rebuild`] rather than two
/// nets, so the ~2.9 GB upload happens once.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// The encoder over `len` tokens: `[1024, 1, len]` in, `[1024, 1, len]` out,
    /// plus the `[16, len, len]` encoder bias in.
    Encode {
        /// Source tokens, after [`embed_positions`].
        len: u32,
    },
    /// One decoder step: the token just produced and its `[16, 1, prefix + 1]` bias in,
    /// its logits out.
    ///
    /// A step is **one query** against `prefix + 1` keys — the positions already decoded plus this
    /// one — which is causal by construction and needs no mask. The bias input is what carries
    /// the position dependence: its *shape* is fixed per plan (`[HEADS, 1, MAX_DECODE_POSITIONS]`
    /// wide, prefix-bounded by `softmax_prefix` like the score map), and its *contents* arrive
    /// per step with the token.
    ///
    /// The key count is deliberately **not** part of this. It changes every token, and a plan
    /// keyed on it is re-recorded every token: a `device_wait_idle` under the queue lock, a fresh
    /// plan and a full re-emit, per token. So the plan is built once for
    /// [`MAX_DECODE_POSITIONS`], the K and V caches live on the device across steps
    /// ([`Builder::persistent`]), and how much of them is live arrives in
    /// [`crate::vulkan::run::StepParams::prefix`] at submit time.
    DecodeStep {
        /// Source positions the cross-attention attends over.
        src_len: u32,
    },
}

/// Hands out `.maml` tensor indices in the order the layers appear.
pub(crate) struct Layers {
    pub(crate) next: usize,
}

impl Layers {
    /// An RMS norm's gain: one tensor, no beta.
    pub(crate) fn take(&mut self) -> usize {
        let index = self.next;
        self.next += 1;
        index
    }

    /// An int8 kernel, its per-output-channel scale, and the bias after that.
    /// The bias is always synthesised zeros — T5 is bias-free — but the slot is real.
    pub(crate) fn take3(&mut self) -> usize {
        let index = self.next;
        self.next += 3;
        index
    }
}

/// A `1 x 1` convolution with a Q2_K kernel, which every projection here is.
pub(crate) fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv_q2k(x, l.take3(), out, act)
}

/// One pre-norm self-attention sublayer with a host-supplied relative bias, plus its residual.
///
/// `bias` is the `[HEADS, queries, keys]` plan input for this shape: [`relative_bias`]'s
/// output for the encoder length, or the step's row of it for decode. Added with a plain
/// `Add` — the bias is data, and no new op kind is owed one.
fn self_attention(b: &mut Builder, l: &mut Layers, residual: Id, x: Id, bias: Id) -> Id {
    let q = point(b, l, x, INNER, Act::None);
    let k = point(b, l, x, INNER, Act::None);
    let v = point(b, l, x, INNER, Act::None);
    // Prescaled: T5 applies no 1/sqrt(head_dim). The scale here is exactly 1.0.
    let scores = b.attn_scores_prescaled(q, k, HEADS);
    let biased = b.add(scores, bias);
    let probs = b.softmax(biased);
    let mixed = b.attn_apply(probs, v, HEADS);
    let projected = point(b, l, mixed, D_MODEL, Act::None);
    b.add(residual, projected)
}

/// One pre-norm cross-attention sublayer — no bias — plus its residual.
///
/// Cross-attention never carries a relative term (`has_relative_attention_bias` is false for
/// every `EncDecAttention`), so this is NLLB's shape with T5's (absent) scale. The key and value
/// were projected once by [`Mode::Encode`] into `cross_k` and `cross_v`; the query is this step's.
pub(crate) fn cross_attention(
    b: &mut Builder,
    l: &mut Layers,
    residual: Id,
    queries: Id,
    cross_k: Id,
    cross_v: Id,
) -> Id {
    let q = point(b, l, queries, INNER, Act::None);
    // Past this layer's cross K and V projections, which the encode pass read.
    l.next += 6;
    let scores = b.attn_scores_prescaled(q, cross_k, HEADS);
    let probs = b.softmax(scores);
    let mixed = b.attn_apply(probs, cross_v, HEADS);
    let projected = point(b, l, mixed, D_MODEL, Act::None);
    b.add(residual, projected)
}

/// Where a decoder layer's cross-attention K and V projections start within the layer: after the
/// self-attention norm and four projections, the cross norm and the cross query.
pub(crate) const CROSS_KV_WITHIN_LAYER: usize = 1 + 4 * 3 + 1 + 3;

/// Decoder layer `layer`'s cross-attention K and V projections: two kernels, scales and biases.
///
/// [`Mode::Encode`] reads them and [`Mode::DecodeStep`] does not.
pub(crate) fn cross_kv(layer: usize) -> std::ops::Range<usize> {
    let start = DECODER + layer * DECODER_LAYER_TENSORS + CROSS_KV_WITHIN_LAYER;
    start..start + 6
}

/// Every decoder layer's cross-attention K and V, `[INNER, 1, src_len]` each, held on the device.
///
/// Must be the first pinned tensors either pass declares: [`Builder::finish`] gives pinned tensors
/// their arena offsets in declaration order, which is what puts these at the same place in both
/// plans. See `nets::nllb` for the same constraint.
pub(crate) fn declare_cross_caches(b: &mut Builder, src_len: u32) -> Vec<(Id, Id)> {
    (0..DECODER_LAYERS)
        .map(|_| {
            let k = b.persistent(Shape::new(INNER, 1, src_len));
            let v = b.persistent(Shape::new(INNER, 1, src_len));
            (k, v)
        })
        .collect()
}

/// The pre-norm GEGLU feed-forward sublayer, plus its residual.
///
/// `wo(GELU(wi_0(x)) * wi_1(x))` over the converter's fused `[wi_0 | wi_1]` projection:
/// one int8 convolution of `2 * FFN` outputs, halved by `GatedActivate`. T5's gate is the
/// tanh `gelu_new`; the shader is exact-erf, sixty times below fp16 resolution — see the
/// module docs.
pub(crate) fn feed_forward(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let normed = b.rms_norm(x, l.take(), EPSILON);
    let fused = point(b, l, normed, 2 * FFN, Act::None);
    let gated = b.gated_activate(fused, Act::Gelu);
    let projected = point(b, l, gated, D_MODEL, Act::None);
    b.add(x, projected)
}

/// Build one of MADLAD's two passes. See [`Mode`].
pub fn build(weights: &dyn WeightSource, mode: Mode) -> Result<Plan, String> {
    match mode {
        Mode::Encode { len } => encode(weights, len),
        Mode::DecodeStep { src_len } => decode_step(weights, src_len),
    }
}

/// The encoder over `len` already-embedded positions, plus its bias input.
fn encode(weights: &dyn WeightSource, len: u32) -> Result<Plan, String> {
    if len == 0 {
        return Err("an encoder pass over no tokens".into());
    }

    let l = &mut Layers { next: ENCODER };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // The two heads, the decoder and the two bias tables belong elsewhere, except the decoder's
    // cross-attention K and V projections, which run here once per sentence. The trailing encoder
    // norm is part of the first range, so it runs to `DECODER` rather than to `ENCODER_NORM`.
    let mut read = Vec::new();
    read.push(ENCODER..DECODER);
    read.extend((0..DECODER_LAYERS).map(cross_kv));
    name_host_tensors(b, &read);

    let caches = declare_cross_caches(b, len);
    let x = b.input(Shape::new(D_MODEL, 1, len));
    let bias = b.input(Shape::new(HEADS, len, len));
    let mut x = x;
    for _ in 0..ENCODER_LAYERS {
        let normed = b.rms_norm(x, l.take(), EPSILON);
        x = self_attention(b, l, x, normed, bias);
        x = feed_forward(b, l, x);
    }
    if l.next != ENCODER_NORM {
        return Err(format!("the encoder claims {} tensors, not {ENCODER_NORM}", l.next));
    }
    let out = b.rms_norm(x, l.take(), EPSILON);
    if l.next != DECODER {
        return Err(format!("the encoder norm ends at {}, not {DECODER}", l.next));
    }
    for (layer, &(cache_k, cache_v)) in caches.iter().enumerate() {
        let kv = &mut Layers { next: cross_kv(layer).start };
        let k = point(b, kv, out, INNER, Act::None);
        let v = point(b, kv, out, INNER, Act::None);
        b.copy_into(k, cache_k);
        b.copy_into(v, cache_v);
    }
    // The encoder state stays the output, although the host no longer needs it: a pass must have
    // one, and a persistent tensor cannot be one without being pinned twice.
    builder.finish(&[out])
}

/// Name every tensor **outside** `read` as one this pass does not touch.
///
/// [`Builder::finish`] refuses an unread tensor, and no one of the two passes reads the whole
/// file. Declaring the complement rather than listing it keeps the two in step: adding a layer
/// changes the ranges and nothing else.
pub(crate) fn name_host_tensors(b: &mut Builder, read: &[std::ops::Range<usize>]) {
    for index in 0..TENSORS {
        if !read.iter().any(|range| range.contains(&index)) {
            b.host_tensor(index, &dims_of(index));
        }
    }
}

/// The shape of tensor `index`, derived from the layout constants.
///
/// `host_tensor` checks it against the file, so this is a *second* statement of the table that
/// `maml_convert.collect_madlad` writes — which is the point: a converter and a runtime that
/// disagree about a shape fail here rather than on the device.
pub(crate) fn dims_of(index: usize) -> Vec<u32> {
    if index < ENCODER {
        // A head split: Q2_K kernel `[classes, taps]`, per-block `(d, dmin)` scale,
        // synthesised zero bias. Both tables split evenly at 64,000 classes.
        return match index % 3 {
            0 => vec![CLASSES_PER_SPLIT, D_MODEL],
            1 => vec![CLASSES_PER_SPLIT * (D_MODEL / crate::weights::Q2K_BLOCK) * 2],
            _ => vec![CLASSES_PER_SPLIT],
        };
    }
    if index >= TABLE_ENC {
        // The two block-0 relative tables: `[BUCKETS, HEADS]` fp16, read on the host.
        return vec![BUCKETS, HEADS];
    }
    let (base, per_layer, layers) = if index < DECODER {
        (ENCODER, ENCODER_LAYER_TENSORS, ENCODER_LAYERS)
    } else {
        (DECODER, DECODER_LAYER_TENSORS, DECODER_LAYERS)
    };
    let end = base + per_layer * layers;
    if index >= end {
        // One of the two trailing RMS norms: gain only.
        return vec![D_MODEL];
    }
    layer_dims((index - base) % per_layer, per_layer)
}

/// The shape of the `within`th tensor of a layer of `per_layer` tensors.
fn layer_dims(within: usize, per_layer: usize) -> Vec<u32> {
    // A layer is a sequence of groups: `[1]` for an RMS norm, `[out, in] [out*blocks*2]
    // [out]` for a Q2_K projection (kernel, scale table, bias). Walking them is shorter
    // than a table and cannot disagree with `Layers`.
    // The fused wi_01 is one `[2 * FFN, D_MODEL]` projection, not two.
    let mut groups: Vec<(usize, u32, u32)> = vec![(1, 0, 0)];
    // Self q/k/v project to INNER (2048), not D_MODEL: T5's heads are 128-wide, unlike
    // NLLB's 64-wide ones where the two coincide. The `o` projection reads them back.
    groups.extend([(3, INNER, D_MODEL); 3]);
    groups.push((3, D_MODEL, INNER));
    if per_layer == DECODER_LAYER_TENSORS {
        groups.push((1, 0, 0));
        groups.extend([(3, INNER, D_MODEL); 3]);
        groups.push((3, D_MODEL, INNER));
    }
    groups.push((1, 0, 0));
    groups.push((3, 2 * FFN, D_MODEL));
    groups.push((3, D_MODEL, FFN));

    let mut at = 0;
    for (size, out, inputs) in groups {
        if within < at + size {
            let offset = within - at;
            return match (size, offset) {
                // An RMS norm's gain.
                (1, _) => vec![D_MODEL],
                // A projection's Q2_K kernel.
                (_, 0) => vec![out, inputs],
                // Its `(d, dmin)` scale table: two fp16 values per superblock.
                (_, 1) => vec![out * inputs.div_ceil(crate::weights::Q2K_BLOCK) * 2],
                // Its bias.
                _ => vec![out],
            };
        }
        at += size;
    }
    vec![D_MODEL]
}