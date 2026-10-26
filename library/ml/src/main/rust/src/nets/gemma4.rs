//! Gemma 4 E2B instruction-tuned: the text decoder, in one `.maml`.
//!
//! # What it is
//!
//! `Gemma4ForConditionalGeneration`'s text tower, from
//! `onnx-community/gemma-4-E2B-it-ONNX`. 35 layers, `d_model` 1536, a 262,144-entry vocabulary,
//! and multi-query attention: **eight query heads against one key/value head**.
//!
//! # Two layer archetypes, not one
//!
//! The model is structurally two halves, and this is the fact the rest of the module is shaped
//! around. `config.json` calls it `num_kv_shared_layers: 20` and `use_double_wide_mlp: true`;
//! in the initializer table it is two different tensor counts:
//!
//! * **Layers 0..15 own a KV cache.** Sixteen tensors: `q/k/v/o` projections, `q_norm`, `k_norm`,
//!   five RMS norms, a `[1536, 12288]` fused gate-and-up projection over a 6144-wide inner
//!   dimension, its `[6144, 1536]` down projection, two per-layer-input tensors and a scalar.
//! * **Layers 15..35 have no K or V at all.** Thirteen tensors: no `k_proj`, no `v_proj`, no
//!   `k_norm`. They re-use an earlier layer's cache, and spend the parameters on an MLP of
//!   **twice** the inner width - `[1536, 24576]` over 12288, down from `[12288, 1536]`.
//!
//! So the two halves trade key/value projections for feed-forward width. A single parameterised
//! layer function would have to carry that as a flag through every shape; two functions say it
//! once. See [`OWNS_CACHE_LAYERS`].
//!
//! # Which cache a shared layer reads
//!
//! Traced from the export's own graph rather than assumed:
//!
//! * The sliding shared layers - 15..19, 20..24, 25..29, 30..34 excluding the full ones - all
//!   read **layer 13**, the last sliding layer that owns a cache.
//! * The full-attention shared layers - 19, 24, 29 and 34 - read **layer 14**, the last full
//!   layer that owns one. In the graph they have no key transpose at all, and one mask where a
//!   sliding layer has two.
//!
//! # Sliding and full attention
//!
//! [`LAYER_TYPES`] alternates four sliding layers to one full one, so 4, 9, 14, 19, 24, 29 and 34
//! are full and the other 28 are sliding over a 512-position window. They differ in **head
//! dimension** as well: 256 sliding, 512 full, which is why `q_proj` is `[1536, 2048]` on one and
//! `[1536, 4096]` on the other. Everything else about them is the same.
//!
//! # The attention scale is already in `q_norm`
//!
//! The export applies **no** scale between the query projection and the score matmul - there is
//! no multiply or divide in the graph there, and the fused `GroupQueryAttention` nodes carry
//! `scale = 1.0`. The `1 / sqrt(head_dim)` is folded into `q_norm`'s gamma.
//!
//! [`Builder::attn_scores_cached_grouped`] supplies `1 / sqrt(head_dim)` itself, so a forward pass
//! that also uses the exported `q_norm` unchanged would apply it **twice** - which is not a shape
//! error and not a crash, just quietly flatter attention. The converter divides it out; see
//! [`Q_NORM_CARRIES_SCALE`].

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Hidden width.
pub const D_MODEL: u32 = 1536;

/// Query heads. Every layer has eight.
pub const HEADS: u32 = 8;

/// Key/value heads. One - multi-query attention.
pub const KV_HEADS: u32 = 1;

/// Head dimension on a sliding layer.
pub const HEAD_DIM: u32 = 256;

/// Head dimension on a full-attention layer, `global_head_dim`.
pub const GLOBAL_HEAD_DIM: u32 = 512;

/// Decoder layers.
pub const LAYERS: usize = 35;

/// Layers that own a KV cache. The rest read one of theirs.
pub const OWNS_CACHE_LAYERS: usize = 15;

/// Inner width of the feed-forward, 6144 on the full layers and 12288 on the
/// slim ones (15+).
///
/// The portable graph's slim MLP is a fused 2-bit gate+up pair at 12288-wide
/// with a 12288-wide down-projection (S10 tensors 1644/1647/1653); the GPU
/// bundle holds the same geometry in I8-tagged 2-bit storage. `FFN_WIDE` and
/// the fused `gate_up_proj` belonged to the ONNX export, which is a different
/// model.
pub const FFN: u32 = 6144;

/// Slim-layer inner width (see [`ffn`]).
pub const FFN_SLIM: u32 = 12288;

/// Per-layer input width, `hidden_size_per_layer_input`.
pub const PER_LAYER: u32 = 256;

/// Vocabulary, shared by the embedding and the logits head.
pub const VOCAB: u32 = 262_144;

/// Sliding attention window, in positions.
pub const WINDOW: u32 = 512;

/// Positions a decode plan is built for, and so the length of every KV cache.
///
/// `max_position_embeddings` is 131072, which no arena here could hold: fifteen caches at that
/// length would be gigabytes. This is the context the runtime actually offers.
///
/// A sliding layer never attends more than [`WINDOW`] positions back, so twelve of the fifteen
/// caches are far larger than they need to be. Making those a ring buffer would cut the arena by
/// most of its size, and needs modular indexing in `cache_write.comp` and in the attended range -
/// worth doing, deliberately not done here, so that the first version has one indexing scheme
/// rather than two.
pub const MAX_CONTEXT: u32 = 16_384;

/// Cache lengths a conversation is allowed to grow through.
///
/// The KV cache costs **18,432 bytes a position** - twelve sliding layers at 512 bytes and three
/// full ones at 1,024, doubled for keys and values. So the tier is the conversation length, and
/// the memory follows it linearly: 1,024 positions is 19 MB and 16,384 is 302 MB.
///
/// Tiers rather than a smooth grow because each change re-records the plan and **loses the cache**
/// - the arena is reallocated, so the whole prompt is prefilled again. Doubling makes that happen
/// a handful of times over a long conversation instead of continuously.
/// Pinned tensors that are KV caches, and the first that many in the plan.
///
/// `build` declares the caches before anything else, so they are a prefix of `Plan::pinned` -
/// which also holds the multimodal soft-token buffers. Only these are a function of the prompt,
/// so only these are worth saving. Two per layer that owns one.
pub const CACHE_TENSORS: usize = OWNS_CACHE_LAYERS * 2;

pub const CONTEXT_TIERS: [u32; 5] = [1024, 2048, 4096, 8192, 16_384];

/// Bytes of KV cache one position costs. See [`CONTEXT_TIERS`].
pub const BYTES_PER_POSITION: u32 = 18_432;

/// The largest tier whose cache fits in `budget` bytes, or the smallest if none do.
pub const fn tier_for(budget: u64) -> u32 {
    let mut best = CONTEXT_TIERS[0];
    let mut index = 0;
    while index < CONTEXT_TIERS.len() {
        let tier = CONTEXT_TIERS[index];
        if (tier as u64) * (BYTES_PER_POSITION as u64) <= budget {
            best = tier;
        }
        index += 1;
    }
    best
}

/// The next tier above `context`, or `None` at the top.
pub fn next_tier(context: u32) -> Option<u32> {
    CONTEXT_TIERS.iter().copied().find(|tier| *tier > context)
}

/// Tied-head logit splits for the Vulkan GEMM.
///
/// litertlm ties embeddings (no head tensor anywhere): logits are hidden @ E^T
/// with E the working embedding table. One descriptor is only guaranteed to
/// reach 128 MiB, so the 262144-class GEMM runs in four splits of 65536
/// classes (100 MB of int4 each at 1536-wide). Same arithmetic NLLB's head
/// split uses, applied to the tied table instead of a dedicated head.
pub const HEAD_SPLITS: usize = 4;

/// Classes in each split of the tied-head GEMM.
pub const CLASSES_PER_SPLIT: u32 = VOCAB / HEAD_SPLITS as u32;

/// The epsilon in every RMS norm, `rms_norm_eps`.
pub const EPSILON: f32 = 1e-6;

/// `final_logit_softcapping`: the logits are `tanh(x / CAP) * CAP`.
pub const LOGIT_CAP: f32 = 30.0;

/// The S2 signature gain the working embedding table carries.
///
/// The reference's gather path multiplies the raw decode table by the fp16
/// constant 39.25 (the `Mul` after `GatherBlockQuantized` in
/// `embed_tokens_q4f16.onnx`), so every gathered row carries that gain.
///
/// The tied head must therefore use the S10 head table (t2698, the raw-scale
/// `embedder.decode` composite), NOT the working table: the reference scores
/// its final hidden against the raw-scale table, and the S2 gain does not
/// belong in the logits. The working table (S2 rows, x39.25) is the gather
/// side only; the head side is raw. Getting this backwards is invisible -
/// the shapes agree and every logit merely saturates at `LOGIT_CAP`.
pub const EMBED_GAIN: f32 = 39.25;

/// Live int8 round-trip scales for the attention path.
///
/// Moved to gemma4_part5.rs (module-length lint): [MM_SCALE],
/// [QI_SCALE], [QO_SCALE], [KO_SCALE], [VO_SCALE], [O_SCALE].

/// Whether `q_norm`'s gamma already carries `1 / sqrt(head_dim)`.
///
/// True for a file `maml_convert.py` wrote without dividing it out, in which case the forward pass
/// must pass a scale of one rather than letting [`Builder::attn_scores_cached_grouped`] compute
/// the usual one. Named rather than left as a comment because getting it wrong is invisible: the
/// shapes agree and the output is merely wrong.
///
/// [`Builder::attn_scores_cached_grouped`]: super::Builder::attn_scores_cached_grouped
pub const Q_NORM_CARRIES_SCALE: bool = true;

/// Whether layer `index` uses full attention rather than a sliding window.
///
/// `config.json`'s `layer_types` is four sliding to one full, so every fifth layer from index 4.
pub const fn is_full_attention(index: usize) -> bool {
    index % 5 == 4
}

/// The head dimension layer `index` uses.
pub const fn head_dim(index: usize) -> u32 {
    if is_full_attention(index) {
        GLOBAL_HEAD_DIM
    } else {
        HEAD_DIM
    }
}

/// Whether layer `index` has its own key and value projections.
pub const fn owns_cache(index: usize) -> bool {
    index < OWNS_CACHE_LAYERS
}

/// The layer whose KV cache layer `index` reads.
///
/// Itself when it owns one. Otherwise the last owning layer of the same attention type: 14 for a
/// full layer, 13 for a sliding one.
pub const fn cache_source(index: usize) -> usize {
    if owns_cache(index) {
        index
    } else if is_full_attention(index) {
        OWNS_CACHE_LAYERS - 1
    } else {
        OWNS_CACHE_LAYERS - 2
    }
}

/// The inner feed-forward width of layer `index`: 6144 on the full (cache-owning)
/// layers, 12288 on the slim ones (see [`FFN_SLIM`]).
pub const fn ffn(index: usize) -> u32 {
    if owns_cache(index) {
        FFN
    } else {
        FFN_SLIM
    }
}

/// Tensors one quantised projection contributes: the kernel, its scale, and its bias.
///
/// Gemma 4 has **no biases** - `attention_bias` is false and the export holds none - but every
/// convolution in this runtime takes one, and an int8 or int4 kernel takes a `(kernel, scale,
/// bias)` triple. The converter writes a zero bias rather than the runtime growing a bias-free
/// path: at these widths a zero bias is `out_channels * 2` bytes against a kernel of
/// `out_channels * 1536`, so it is under a thousandth of the file, and it keeps one convolution
/// lowering instead of two.
const PROJECTION_TENSORS: usize = 3;

/// Projections a cache-owning layer has: q, k, v, o, gate, ff1, linear,
// and the per-layer gate/projection pair.
const OWNING_PROJECTIONS: usize = 9;

/// Projections a shared-cache layer has: the same without k and v.
const SHARED_PROJECTIONS: usize = OWNING_PROJECTIONS - 2;

/// Unquantised tensors a cache-owning layer has: pre_attn, q_norm, k_norm,
/// post_attn, pre_ffw, post_ffw, post_per_layer_input, skip. (No v_norm:
/// litertlm carries none. The all-ones `value_norm` in the portable graph is
/// a parameter-free RMSNorm the GPU kernel fuses, not a stored tensor.)
const OWNING_PLAIN: usize = 8;

/// The same without `k_norm`. (Only one fewer: litertlm has no `v_norm`
/// to drop, unlike the ONNX export this count was first written against.)
const SHARED_PLAIN: usize = OWNING_PLAIN - 1;

/// Tensors a cache-owning layer contributes, in file order.
const OWNING_LAYER_TENSORS: usize = OWNING_PLAIN + OWNING_PROJECTIONS * PROJECTION_TENSORS;

/// Tensors a shared-cache layer contributes.
const SHARED_LAYER_TENSORS: usize = SHARED_PLAIN + SHARED_PROJECTIONS * PROJECTION_TENSORS;

/// Where the layers start. The trailing norm, the two rotary tables, and the
/// two shared all-ones vectors come first.
const LAYER0: usize = SHARED_TENSORS;

/// Tensors before any layer: the trailing norm, the two rotary tables, and
/// the all-ones vector.
///
/// NO shared per-layer projection: it lives in the EMBED file (see
/// [`embed::SHARED_PROJ`]) and the host applies it in [`gather`], so the
/// text file is the transformer only. NO logits head either: litertlm ties
/// embeddings (no head tensor in any section of either bundle), so logits
/// are computed as hidden @ E^T on the host (see bridge `tied_head_logits`).
const SHARED_TENSORS: usize = 5;

/// The trailing norm.
pub const FINAL_NORM: usize = 0;

/// The sliding layers' rotary table, `[MAX_CONTEXT, HEAD_DIM]`.
///
/// Standard RoPE, computed by the converter (litertlm stores none). Thetas read
/// off the base bundle's own Section 10 `maybe_rope` constants: 1e4 sliding /
/// 1e6 full (freq[1] 0.86596435 at head_dim 256, 0.89768714 at head_dim 512).
/// `--rope-theta` overrides both for bisect.
pub const ROTARY_LOCAL: usize = FINAL_NORM + 1;

/// The full-attention layers' rotary table, `[MAX_CONTEXT, GLOBAL_HEAD_DIM]`. See
/// [`ROTARY_LOCAL`].
pub const ROTARY_GLOBAL: usize = ROTARY_LOCAL + 1;

/// The shared all-ones vectors: the gamma of S10's parameter-free
/// `value_norm` (one per owning layer in the graph, all ones over the head
/// dim: `[256]` sliding, `[512]` full). Two tensors because the norm reads
/// its gamma at the head width, not `D_MODEL`. `rms_norm` with them is
/// exactly S10's composite. See the `value_norm` note at [`layer`].
pub const ONE_SLIDING: usize = ROTARY_GLOBAL + 1;
pub const ONE_FULL: usize = ROTARY_GLOBAL + 2;

/// Total tensors the `.maml` holds, and the count `maml_convert.py` must write.
pub const TENSORS: usize = SHARED_TENSORS
    + OWNS_CACHE_LAYERS * OWNING_LAYER_TENSORS
    + (LAYERS - OWNS_CACHE_LAYERS) * SHARED_LAYER_TENSORS;

/// The first tensor index of layer `index`.
pub const fn layer_at(index: usize) -> usize {
    let owning = if index < OWNS_CACHE_LAYERS { index } else { OWNS_CACHE_LAYERS };
    let shared = if index < OWNS_CACHE_LAYERS { 0 } else { index - OWNS_CACHE_LAYERS };
    LAYER0 + owning * OWNING_LAYER_TENSORS + shared * SHARED_LAYER_TENSORS
}

/// Declare every tensor of layer `index` against `weights`, in file order.
///
/// Separate from any forward pass so the ordered layout can be asserted with no `.maml` on disk:
/// `nets::tests::Shapes` records each `(index, dims)` and hands the index back as the offset.
///
/// # These are the shapes the `.maml` holds, not the shapes ONNX holds
///
/// Two differences from the export, both introduced by the converter:
///
/// * **Transposed.** The export's projections are `MatMul` weights in `[in, out]` order -
///   `q_proj` is `[1536, 2048]`. Every projection here is evaluated by
///   [`super::Builder::conv_int8`], which like the rest of this runtime takes an ONNX `Conv`
///   kernel, `[out, in, kh, kw]`. This mirrors what `maml_convert.py` already does for `Gemm`:
///   the file stores what the shader indexes.
/// * **Three tensors per projection**, not one - see [`PROJECTION_TENSORS`].
///
/// So the file holds more tensors than the export does, and the counts here are the file's.
pub fn declare_layer(weights: &dyn WeightSource, index: usize) -> Result<(), String> {
    let at = layer_at(index);
    let dim = head_dim(index);
    let inner = ffn(index);
    let mut next = at;
    let plain = |dims: &[u32], next: &mut usize| -> Result<(), String> {
        let here = *next;
        *next += 1;
        weights.shaped(here, dims).map(|_| ())
    };

    plain(&[D_MODEL], &mut next)?; // pre_attention_norm
    plain(&[dim], &mut next)?; // attn.q_norm
    projection(weights, &mut next, HEADS * dim, D_MODEL)?; // attn.q
    if owns_cache(index) {
        plain(&[dim], &mut next)?; // attn.k_norm
        projection(weights, &mut next, KV_HEADS * dim, D_MODEL)?; // attn.k
        projection(weights, &mut next, KV_HEADS * dim, D_MODEL)?; // attn.v
    }
    projection(weights, &mut next, D_MODEL, HEADS * dim)?; // attn.o
    plain(&[D_MODEL], &mut next)?; // post_attention_norm
    plain(&[D_MODEL], &mut next)?; // pre_ffw_norm
    projection(weights, &mut next, inner, D_MODEL)?; // mlp.gate
    projection(weights, &mut next, inner, D_MODEL)?; // mlp.ff1 (up)
    projection(weights, &mut next, D_MODEL, inner)?; // mlp.linear (down)
    plain(&[D_MODEL], &mut next)?; // post_ffw_norm
    projection8(weights, &mut next, PER_LAYER, D_MODEL)?; // per_layer gate
    projection8(weights, &mut next, D_MODEL, PER_LAYER)?; // per_layer projection
    plain(&[D_MODEL], &mut next)?; // post_per_layer_input_norm
    plain(&[1], &mut next)?; // skip.scale: whole-residual multiplier after add2
    if next != layer_at(index + 1) {
        return Err(format!(
            "layer {index} declared {} tensors, not the {} its span allows",
            next - at,
            layer_at(index + 1) - at
        ));
    }
    Ok(())
}

/// One quantised `1x1` projection: kernel, per-block scale, bias.
///
/// Four bits, which is what every large projection uses.
fn projection(
    weights: &dyn WeightSource,
    next: &mut usize,
    out: u32,
    inp: u32,
) -> Result<(), String> {
    weights.shaped_words(*next, &[out, inp, 1, 1])?;
    let blocks = inp.div_ceil(crate::weights::I4_BLOCK);
    weights.shaped(*next + 1, &[out, blocks])?;
    weights.shaped(*next + 2, &[out])?;
    *next += PROJECTION_TENSORS;
    Ok(())
}

/// [`projection`] at **eight** bits, whose scale is rank 1.
///
/// For the two per-layer-input projections. They are the smallest weights in a layer -
/// `[1536, 256]` against the feed-forward's `[1536, 24576]` - and they are the ones that miss the
/// int4 fidelity gate: measured over the real export, `per_layer_input_gate` reconstructs at
/// 0.9870 on layer 21, under the 0.99 floor, while every large projection clears it. Quantising
/// them to four bits would save about a thousandth of the file for the worst error in it.
fn projection8(
    weights: &dyn WeightSource,
    next: &mut usize,
    out: u32,
    inp: u32,
) -> Result<(), String> {
    weights.shaped_words(*next, &[out, inp, 1, 1])?;
    weights.shaped(*next + 1, &[out])?;
    weights.shaped(*next + 2, &[out])?;
    *next += PROJECTION_TENSORS;
    Ok(())
}

/// The `[in, out]` shape the export holds for a kernel this module declares as `dims`.
///
/// The inverse of the transpose described on [`declare_layer`], so a converter check can compare
/// against the ONNX file without either side restating the other's convention. Scales and biases
/// have no counterpart in the export and come back unchanged.
pub fn as_exported(dims: &[u32]) -> Vec<u32> {
    match dims {
        [out, inp, 1, 1] => vec![*inp, *out],
        other => other.to_vec(),
    }
}

include!("gemma4_part1.rs");
include!("gemma4_part2.rs");
include!("gemma4_part3.rs");
include!("gemma4_part4.rs");
include!("gemma4_part5.rs");
include!("gemma4_part6.rs");
include!("gemma4_part7.rs");
include!("gemma4_part8.rs");
include!("gemma4_part9.rs");