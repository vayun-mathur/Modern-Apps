//! Gemma 4's audio tower: 12 conformer layers over log-mel frames, out as soft tokens.
//!
//! The counterpart of [`super::gemma4_vision`] for sound. It takes the log-mel spectrogram
//! [`crate::logmel`] produces and emits the `[n, 1536]` block that stands in for a clip in the
//! decoder's prompt, between `boa` and `eoa`.
//!
//! # The export is the tower *and* the embedder
//!
//! `get_audio_features` calls `embed_audio(...)` and the exporter traced through it, so the
//! graph tail is `output_proj -> RMSNorm -> embedding_projection` and the result is already in
//! text-embedding space. `output_proj_dims` and `text_config.hidden_size` are both 1536 and are
//! **different things**: `1024 -> 1536` with a bias, then `1536 -> 1536` without one. Conflating
//! them because the numbers match would silently drop a matmul.
//!
//! There is no pooling. `Gemma4VisionPooler` has no audio equivalent, `forward` goes from the
//! last layer straight to `output_proj`, and the graph tail contains no reduction. One soft
//! token per subsampled frame.
//!
//! # A layer is a macaron conformer, not a transformer block
//!
//! `FFN(1/2) -> attention -> conv -> FFN(1/2) -> norm`, nine RMS norms and eighteen calibrated
//! clips deep. Three details are easy to read the other way round and all three are silent:
//!
//! * **[`RESIDUAL_WEIGHT`] applies to the branch, not the residual**, and only inside the two
//!   feed-forwards - after their post-norm, immediately before the add. Not in attention, not in
//!   the conv module.
//! * **The conv module is `conv -> norm -> act`**, and its depthwise convolution is **causal**:
//!   four taps of left pad and none of right. With `attention_context_right = 0` the whole tower
//!   is streaming-shaped.
//! * **The residual adds around attention and the conv module are plain.** Only the two
//!   feed-forwards carry the half.
//!
//! # Attention is a 12-wide sliding window, not a block-diagonal one
//!
//! `ATTEND(q, k) <=> 0 <= q - k <= 11`; see [`attends`]. `attention_context_left` is 13 and
//! every use site is `attention_context_left - 1`, so the attended span is **12**, self plus
//! eleven past. That has been confirmed three independent ways - the reference's
//! `(dist >= 0) & (dist < left_window_size)`, a standalone re-derivation, and executing the
//! extracted mask subgraph under onnxruntime - and it is the single most expensive thing to get
//! wrong here, because reading 13 as the span costs nothing visible.
//!
//! The export tiles this into `[nb, 12, 24]` blocks with a `_rel_shift` skew. That is a
//! computational tiling and changes nothing about which pairs attend: `q = 12` attends
//! `1..=12`, eleven of which are in the previous block. This module reproduces neither the
//! tiling nor a square map.
//!
//! **The score map is banded**: `scores[h][q][j]` with `j = 0..=11` and `k = q - 11 + j`, so it
//! is `[HEADS, T, ATTEND_SPAN]` and every slot is a pair the mask admits. Three things follow,
//! and they are why this was chosen over both alternatives:
//!
//! * **No softmax mode.** A 12-wide row is the softmax domain, so the plain existing
//!   [`super::Builder::softmax`] normalises it untouched. There is no windowed variant.
//! * **No skew.** In banded coordinates the relative offset *is* the column - `o = j + 1`,
//!   independent of `q` - so the pad/reshape/slice reconstruction disappears and the bias fuses
//!   into the scores op. See [`rel_column`].
//! * **No dense mask.** The export materialises a `[1, 1, S, S]` bool mask to gather a
//!   `[nb, 12, 24]` view out of it. Nothing here needs either.
//!
//! A square `[HEADS, T, T]` map was proposed and rejected: at the 750-token cap it is 31x the
//! memory and, decisively, 31x the arithmetic - 27.6 GFLOP against 0.89 - with 1.6% of its slots
//! live. Banded is half the cost of the export's own blocked layout again, because blocked
//! shares one 24-wide context across twelve queries and throws away half of it.
//!
//! **The start edge is the trap.** The first eleven queries have dead low slots - 66 in total,
//! `q = 0` having eleven and `q = 10` one. They are dead *slots* but never a dead *row*:
//! `j = 11` gives `k = q`, which is always admitted and always in range, so `q = 0` yields
//! `[0, ..., 0, 1]`, correct rather than degenerate. Dead slots are filled with [`MASK_FILL`].
//!
//! **Write the guard as `j + q + 1 >= ATTEND_SPAN`, in both the shader and any oracle.** It is
//! purely additive, so there is nothing to wrap in GLSL and nothing to panic in Rust, and it is
//! written against the band rather than the literal 11. [`band_key`] is that form; copy it
//! rather than re-deriving, because the obvious alternatives are all wrong in ways that do not
//! announce themselves:
//!
//! * `j >= 11 - q` **underflows** for every `q > 11`, wrapping to about 4.29e9 so that the guard
//!   rejects the whole band. Correct on the twelve rows `q = 0..=11` and empty on the 738 after
//!   them. It looks like the safe rearrangement and is the worst of them.
//! * `k >= 0` is vacuously true for an unsigned `k` and compilers discard it.
//! * `k < T` with unsigned `k` **is** safe in GLSL, and is the right idiom for the upper bound:
//!   a negative `k` wraps far above any legal index, so one compare catches both ends. But it
//!   relies on wrap being defined, which it is not in Rust - `q - (ATTEND_SPAN - 1)` on a `usize`
//!   panics in debug and wraps in release, so an oracle written that way crashes in debug
//!   exactly where it would be right in release, and every parity run is a debug build.
//!
//! The guard must gate the **read**, not the write. Computing `k`, using it, and masking
//! afterwards has already read out of bounds - live arena data belonging to another tensor,
//! yielding plausible numbers for the first eleven of 750 tokens rather than crashing.
//!
//! # The relative-position term is content-dependent
//!
//! Not an additive bias. The `[8, 128, 13]` table is `relative_k_proj(sinusoid)` folded by the
//! exporter - twelve of them, one per layer, not shared - and it is **dot-producted against the
//! query**, Transformer-XL style, so it cannot be precomputed per `(q, k)`. It is per head and
//! one-sided: thirteen offsets covering `d = 0..=12`, which is odd but is *not* `2w + 1` about
//! zero. See [`rel_column`] for the fencepost.
//!
//! The converter transposes it to `[heads, offsets, head_dim]` so that the dimension the dot
//! product contracts over is the contiguous one. Emitting the export's own axis order would be
//! the right size and the wrong strides, and would read wrong taps without a shape error.
//!
//! # The tensor order is the contract
//!
//! As everywhere in this tree the `.maml` is an ordered table with no names, and
//! `maml_convert.collect_gemma4_audio` writes it in exactly the order [`declare_layer`] reads
//! it. The export's initializers are anonymised (`val_2567`, `permute_9`, `_to_copy_16`), so the
//! converter matches on **node numbering** in topological order and asserts the counts, which
//! makes agreeing on this order more load-bearing here rather than less.
use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Channels through the tower. `audio_config.hidden_size`.
pub const D_MODEL: u32 = 1024;

/// Attention heads. Ordinary multi-head.
pub const HEADS: u32 = 8;

/// Channels per head. `HEADS * HEAD_DIM == D_MODEL`.
pub const HEAD_DIM: u32 = 128;

/// Feed-forward width, four times [`D_MODEL`]. There are **two** of these per layer.
pub const FFN: u32 = 4096;

/// Conformer layers. `audio_config.num_hidden_layers`.
pub const LAYERS: usize = 12;

/// Mel channels per frame, from [`crate::logmel::MELS`].
pub const MELS: u32 = 128;

/// Output channels of the two subsampling convolutions, in order.
pub const SSCP_CHANNELS: [u32; 2] = [128, 32];

/// Side of the subsampling convolutions' square kernel. Hard-coded in the reference class and
/// **not** `conv_kernel_size`, which is the conformer depthwise one - see [`CONV_KERNEL`].
pub const SSCP_KERNEL: u32 = 3;

/// Stride of the subsampling convolutions, on both axes. Two of them, so time reduces 4x.
pub const SSCP_STRIDE: u32 = 2;

/// Taps in the conformer's depthwise convolution. `audio_config.conv_kernel_size`.
pub const CONV_KERNEL: u32 = 5;

/// Left pad the depthwise convolution needs to be causal: `(CONV_KERNEL - 1) * dilation`.
pub const CONV_LEFT_PAD: u32 = CONV_KERNEL - 1;

/// Width the conv module's gate projection produces, split in half by the GLU.
pub const LCONV_GATE: u32 = 2 * D_MODEL;

/// Mel channels surviving the two stride-2 subsamplings, `128 -> 64 -> 32`.
pub const MELS_SUBSAMPLED: u32 = subsample(subsample(MELS));

/// Keys a query attends: itself and [`ATTEND_SPAN`]` - 1` past positions, contiguous.
///
/// `attention_context_left - 1`, and the reason this is a constant with a name rather than a
/// literal is that `attention_context_left` itself is 13 and reading *that* as the span is the
/// trap. See [`attends`].
pub const ATTEND_SPAN: u32 = 12;

/// Columns in a layer's relative-position table. **Not** [`ATTEND_SPAN`].
///
/// Thirteen because the export's `_rel_shift` skew consumes one - `[12, 13]` padded to `[12, 25]`,
/// reshaped to 300, sliced to 288, viewed as `[12, 24]`. The band width and the mask width are
/// different quantities that happen to be adjacent numbers. Column 0 is `d = 12`, which the
/// strict `d < 12` mask never lets through: the table has thirteen columns and twelve of them
/// are live.
pub const REL_OFFSETS: u32 = ATTEND_SPAN + 1;

/// `tanh(x / cap) * cap` on the attention logits. `audio_config.attention_logit_cap`.
///
/// Applied **after** the relative term is added and **before** the mask. That order is
/// load-bearing in both directions:
///
/// * The cap applies to the sum, so the bias cannot be folded into a post-cap addition.
/// * The mask must come after, which is why the reference fills with `-1e9` rather than `-cap`.
///   Capping a masked logit would squash it back up to `-50` and give masked keys real weight.
///
/// So the cap is **fused into the banded scores op** rather than run as a separate
/// [`super::Builder::softcap`] pass. A separate pass over a band already holding [`MASK_FILL`]
/// would map the sentinels to `tanh(-1310.08) * 50 = -50` - a finite weight the softmax would
/// then include. Fusing puts the sentinel in *after* the cap, which is correct by construction
/// rather than by remembering. That ordering error would not raise anything.
pub const LOGIT_CAP: f32 = 50.0;

/// What a dead band slot is filled with before the softmax sees it.
///
/// `-65504` is fp16's most negative finite value, and it is the faithful port rather than a
/// guard against our own representation. The reference uses `-1e9`, which is finite in its fp32
/// score path; this runtime's arena is fp16 (`shaders/common.glsl` declares `float16_t arena[]`),
/// where `-1e9` saturates to `-inf`. Both give `exp(x - peak) -> 0` on a row with any live entry,
/// but on a fully masked row `-inf` gives `peak = -inf` and `exp(NaN) = NaN`, where `-65504`
/// gives a uniform distribution - which is what the reference produces there too.
///
/// [`attends`] guarantees `j = 11` is live in every row, so this tower cannot produce a fully
/// masked row. The constant is still the right one: the guarantee is a property of the banded
/// layout and of passing clips unpadded, and a sentinel should not depend on either holding.
pub const MASK_FILL: f32 = -65504.0;

/// What a feed-forward's branch is scaled by before its residual add. `residual_weight`.
///
/// One shared fp16 scalar in the export feeding twenty-four `Mul`s: two per layer, both inside
/// [`FFN`] blocks, applied to the **branch** after its post-norm. Nowhere else.
pub const RESIDUAL_WEIGHT: f32 = 0.5;

/// The epsilon in every norm, RMS and layer alike. `rms_norm_eps`.
pub const EPSILON: f32 = 1e-6;

/// What the decoder reads, and `output_proj_dims`. Equal to `text_config.hidden_size` by
/// coincidence of configuration, not by construction.
pub const OUT_DIM: u32 = 1536;

/// The query scale the export folds to a constant: `(HEAD_DIM ** -0.5) / ln(2)`.
///
/// Note the `/ ln 2`, which is not a normalisation anyone would guess. Measured in the graph as
/// `val_279 = 0.127517431974411`, matching the formula to eight digits.
///
/// **The converter folds this into the per-layer per-channel query scale**, so the `[1024, 1, 1]`
/// tensor at `layer_at(i) + 26` already carries it and the banded scores op is passed a scale of
/// 1.0. Verified against the emitted file: zero deviation from `q_scale * tile(v, 8)` in all
/// twelve layers. Applying it again in the op would scale every query twice.
pub const Q_SCALE: f32 = 0.127_517_43;

/// The key scale the export folds to a constant: `ln(1 + e) / ln(2)`, i.e. `softplus(1) / ln 2`.
///
/// Measured as `val_280 = 1.8946361541748047`. A scalar, unlike the query's, which is
/// per-channel. It applies to the content term only - the relative term reads the table, which
/// carries no scaling of its own - which is the other reason neither scale can live in the op.
pub const K_SCALE: f32 = 1.894_636_2;

/// Samples the reference truncates a clip to: 30 s at 16 kHz.
///
/// This is what makes the token count bounded, and with it the arena. See [`MAX_TOKENS`].
pub const MAX_SAMPLES: usize = 480_000;

/// Soft tokens [`MAX_SAMPLES`] produces, and so the longest sequence the tower ever sees.
///
/// 2,999 mel frames subsampled twice. `750 * 40 ms = 30 s`, which is where
/// `audio_ms_per_token = 40` comes from: a 10 ms hop through two stride-2 convolutions.
///
/// **Under the banded layout there is no quadratic term anywhere in this tower** - a score map
/// is `[HEADS, T, 12]`, linear in `T`, because the attended span is 12 regardless of length. So
/// unlike the vision tower's `[12, 2304, 2304]` maps this bound is a convenience rather than a
/// load-bearing budget. That property belongs to the layout and not to the cap: a square
/// `[HEADS, T, T]` map would reintroduce the quadratic term by choice, bounded only by this
/// constant. It is the main reason the square map was rejected.
pub const MAX_TOKENS: u32 = 750;

/// Tensors one clip contributes: a `[2]` fp16 pair, minimum first.
const CLIP_TENSORS: usize = 1;

/// Clips in one layer. Ten clippable linears x 2 bounds, less two because q, k and v share a
/// single input clamp on `norm_pre_attn`'s output. Counted off the export, not guessed: the
/// graph has exactly 18 between consecutive layer-leading norms, for all twelve layers, with
/// none before the first and none after the last.
const CLIPS_PER_LAYER: usize = 18;

/// Quantised projections in one layer: two feed-forwards of two, q, k, v, post, and the conv
/// module's two.
const PROJECTIONS_PER_LAYER: usize = 10;

/// Tensors one quantised projection contributes: kernel, per-block scale, bias.
const PROJECTION_TENSORS: usize = 3;

/// Tensors one **unquantised** projection contributes: kernel and bias, as `Builder::conv`
/// reads them.
const DENSE_TENSORS: usize = 2;

/// RMS norms in one layer: two per feed-forward, two around attention, two in the conv module,
/// and the terminal one.
const NORMS_PER_LAYER: usize = 9;

/// Tensors one layer contributes, in file order.
///
/// The three that are not norms, clips or projections: the folded per-channel query scale, the
/// relative-position table, and the depthwise convolution's kernel and bias.
const LAYER_TENSORS: usize = NORMS_PER_LAYER
    + CLIPS_PER_LAYER * CLIP_TENSORS
    + PROJECTIONS_PER_LAYER * PROJECTION_TENSORS
    + 1
    + 1
    + DENSE_TENSORS;

/// The first subsampling convolution, `[128, 1, 3, 3]` over the mel treated as a one-channel
/// image, with a synthesised zero bias. Held at fp16: it is 1,152 parameters.
///
/// **Its two spatial axes are swapped by the converter**, because [`input_shape`] carries the
/// mel map transposed against the export. Swapping a convolution's input axes is only exact if
/// its kernel's are swapped with them, and a `3 x 3` kernel is not symmetric - so an
/// untransposed kernel here is the right shape and a different convolution. Measured: with the
/// swap this layout reproduces the export's `input_proj` output to cosine 0.99999994, without
/// it 0.906.
pub const SSCP_CONV0: usize = 0;

/// The layer norm after [`SSCP_CONV0`], `[128]` gain and a synthesised zero beta.
///
/// A **layer** norm, not an RMS norm - the only two in the tower, and the export spells them
/// `LayerNormalization` where every other norm is `SimplifiedLayerNormalization`. It normalises
/// over the convolution's output channels, which is this runtime's channel axis, so the two
/// permutes the export wraps it in are not needed. Dropping them is where the front end's
/// arena halves; see the module docs on layout.
pub const SSCP_NORM0: usize = SSCP_CONV0 + DENSE_TENSORS;

/// The second subsampling convolution, `[32, 128, 3, 3]`, with a zero bias. Spatial axes
/// swapped by the converter, as [`SSCP_CONV0`]'s are.
pub const SSCP_CONV1: usize = SSCP_NORM0 + DENSE_TENSORS;

/// The layer norm after [`SSCP_CONV1`], `[32]` gain and zero beta.
pub const SSCP_NORM1: usize = SSCP_CONV1 + DENSE_TENSORS;

/// The projection from the flattened subsampled map into the tower, `[1024, 1024]`, at **fp16**.
///
/// Square by arithmetic rather than by identity: `(128 // 4) * 32` is the two stride-2 halvings
/// of the 128 mel bins times the second convolution's channel count, and it coincidentally
/// equals [`D_MODEL`].
///
/// **Its input rows are permuted by the converter.** The export flattens `[channels, mel]`
/// mel-major (`m * 32 + c`); this runtime's free reshape of a `[32, 32, T]` map is channel-major
/// (`c * 32 + m`). Rather than transpose a 23 MB activation at runtime, the converter reorders
/// the weight's 1024 input rows once. Both orderings are the right size and only one is the
/// right matrix, so this is written down in both places on purpose.
///
/// Unquantised, like the vision tower's two ends and for the same reason: nothing downstream
/// averages away an error made before layer 0 has run.
pub const INPUT_PROJECTION: usize = SSCP_NORM1 + DENSE_TENSORS;

/// The projection to [`OUT_DIM`], `[1536, 1024]`, at **fp16**, with a **real** bias.
///
/// The one biased linear in the export, which is why the exporter split it into a `MatMul` and
/// an `Add` and why the `Add` is the node called `node_linear_133`.
pub const OUT_PROJECTION: usize = INPUT_PROJECTION + DENSE_TENSORS;

/// The embedder's pre-projection RMS norm, `[1536]`.
///
/// `with_scale=False` in the reference, so the exporter materialised an all-ones gain. That is
/// not a no-op - an RMS norm still divides by the RMS - and emitting the export's own ones
/// costs three kilobytes and removes a special case, so the converter emits it rather than
/// dropping it and having the runtime carry a gainless variant.
pub const EMBED_NORM: usize = OUT_PROJECTION + DENSE_TENSORS;

/// The embedder's `[1536, 1536]` projection into text-embedding space, at **fp16**, no bias.
pub const EMBED_PROJECTION: usize = EMBED_NORM + 1;

/// Tensors before any layer.
const SHARED_TENSORS: usize = EMBED_PROJECTION + DENSE_TENSORS;

/// Where the layers start.
const LAYER0: usize = SHARED_TENSORS;

/// Total tensors the `.maml` holds, and the count `maml_convert.py` must write.
pub const TENSORS: usize = SHARED_TENSORS + LAYERS * LAYER_TENSORS;

/// The first tensor of layer `index`.
pub fn layer_at(index: usize) -> usize {
    LAYER0 + index * LAYER_TENSORS
}

/// Positions surviving one stride-2, pad-1, kernel-3 convolution: `ceil(n / 2)`.
pub const fn subsample(n: u32) -> u32 {
    if n == 0 { 0 } else { n.div_ceil(2) }
}

/// Soft tokens `frames` mel frames produce, one per twice-subsampled frame.
///
/// The reference computes this as `mask[:, ::2][:, ::2].sum()` on a padded batch, which is the
/// same number only because a bare `[::2]` decimation is not a min over the convolution's
/// receptive field. This runtime records a plan per frame count and passes no padding, so every
/// subsampled frame is valid and the count is the convolution's own output length. See
/// [`prepare`].
pub const fn tokens(frames: u32) -> u32 {
    subsample(subsample(frames))
}

/// Whether query `q` reads key `k`. The whole attention mask.
///
/// `0 <= q - k <= 11`: itself and eleven past, contiguous, nothing ahead. Only the first eleven
/// positions of a clip are truncated, at the left edge.
pub const fn attends(q: u32, k: u32) -> bool {
    k <= q && q - k < ATTEND_SPAN
}

/// The column of a relative-position table that carries displacement `distance = q - k`.
///
/// `REL_OFFSETS - 1 - distance`, so column 12 is `d = 0` (a query against itself) and column 1
/// is `d = 11` (the oldest key it may read). Column **0** is `d = 12`, which [`attends`] never
/// admits: it is computed by the export's skew and thrown away by the mask.
///
/// This is spelled out because a fencepost here lands on that dead column, produces finite
/// numbers of the right magnitude, and fails nothing.
///
/// **In banded coordinates this collapses to `o = j + 1`.** With `k = band_key(q, j)` the
/// displacement is `q - k = 11 - j`, so the column is `12 - (11 - j) = j + 1`, independent of
/// `q`. That independence is what removes the export's `_rel_shift` skew entirely and lets the
/// bias fuse into the scores op: the shader indexes `table[h][j + 1][..]` directly.
pub const fn rel_column(distance: u32) -> u32 {
    REL_OFFSETS - 1 - distance
}

/// The key that band slot `j` of query `q` refers to: `q - (ATTEND_SPAN - 1) + j`.
///
/// `None` when it would fall before the start of the sequence, which happens only in the first
/// eleven queries. `j = ATTEND_SPAN - 1` always yields `Some(q)`, so no row is ever entirely
/// dead.
///
/// **This is the reference form of the guard and the shader should match it.** The test is
/// `j + q + 1 >= ATTEND_SPAN`: additive, so nothing can wrap in GLSL or panic in Rust, and
/// written against the band rather than the literal 11. The subtraction happens only after the
/// guard has proved the result non-negative - the smallest `q + j + 1` the guard admits is
/// exactly `ATTEND_SPAN`, so it cannot underflow even at the boundary. See the module docs for
/// the three plausible rearrangements that are wrong, one of which empties the band on 738 of
/// 750 queries.
///
/// # `Some(k)` means `k >= 0`. It does **not** mean `k < T`.
///
/// Only the lower bound is guarded, and that is sufficient here for a reason that belongs to the
/// layout rather than to this function: right context is zero, so `k <= q < T` always, and the
/// largest key any query produces is `T - 1`. The upper bound is free.
///
/// Give this tower a non-zero right context, or reuse this helper for a window that reaches
/// forward, and `k` can exceed `q`. The upper bound stops being free and its absence fails
/// **silently**, because this would keep returning `Some` for an out-of-range key and a caller
/// treating `Some` as "safe to index" would read past the end.
/// `the_band_never_reaches_past_the_last_query` trips if that assumption ever changes.
pub const fn band_key(q: u32, j: u32) -> Option<u32> {
    band_key_in(ATTEND_SPAN, q, j)
}

/// [`band_key`] for a window of `span` rather than [`ATTEND_SPAN`], and the one implementation
/// both of them share.
///
/// The op is **parameterised** by its band — `Builder::attn_scores_banded` takes it, the emit
/// puts it in `Push::kh`, and the shaders read it from there. So the CPU oracle must honour that
/// field too. Calling [`band_key`] from an arm that loops over `Push::kh` agrees with the shader
/// only when the band happens to be [`ATTEND_SPAN`], and disagrees silently at every other width
/// — at band 3 it computes `q + j + 1 - 12`, which sends query 0 entirely dead and query 13 to
/// keys 2, 3, 4 instead of 11, 12, 13.
///
/// Parameterised rather than duplicated because this expression has now had six wrong
/// rearrangements proposed by five people. There is one spelling of it in this crate and this is
/// it; [`band_key`] is a name for the [`ATTEND_SPAN`] case, not a second copy.
pub const fn band_key_in(span: u32, q: u32, j: u32) -> Option<u32> {
    if j < span && j + q + 1 >= span {
        Some(q + j + 1 - span)
    } else {
        None
    }
}

include!("gemma4_audio_part1.rs");
include!("gemma4_audio_part2.rs");
include!("gemma4_audio_part3.rs");
include!("gemma4_audio_part4.rs");