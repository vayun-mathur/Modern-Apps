//! The two hardcoded forward passes, and the small compiler they are written against.
//!
//! # Why a plan and not a graph interpreter
//!
//! Each network is code: [`u2netp::build`] and [`selfie::build`] call [`Builder`] in
//! the order the ONNX nodes run. There is no operator table, no name lookup and no
//! topology in the weights file — a `.maml` is ordered tensors and nothing else.
//!
//! But the *shape* of the pass being code does not mean the offsets should be. So a
//! builder call records a [`Node`] against symbolic tensor ids, and [`Builder::finish`]
//! then does three things a hand-written pass would get wrong:
//!
//! 1. Propagates shapes, so every conv's output size is derived from its pads,
//!    stride and dilation rather than restated.
//! 2. Computes each tensor's last use and packs the arena with a free list, so U^2-Netp
//!    at 320x320 reuses memory instead of holding every intermediate at once.
//! 3. Resolves everything to `u32` element offsets in one place, which is the
//!    arithmetic the host tests check.
//!
//! The result is a flat [`Plan`] of [`Op`]s. `vulkan::run` records it into **one**
//! command buffer once, at construction, so an inference is an upload, one submit and
//! one readback — nothing here runs per frame.
//!
//! # Host-testable
//!
//! Nothing in this module or its children touches Vulkan. Builders take a
//! [`WeightSource`] rather than a [`crate::weights::Weights`], so `cargo test` builds
//! both real networks, in full, with no device and no asset.

/// A CPU implementation of every op, to check the resolved plans against. Test-only,
/// so it adds nothing to the shipped `.so`.
#[cfg(test)]
pub mod reference;
pub mod schedule;
pub mod gemma4;
pub mod gemma4_audio;
pub mod gemma4_gather;
pub mod gemma4_head;
pub mod gemma4_vision;
pub mod mobilefacenet;
pub mod maia;
pub mod madlad;
pub mod madlad_extra;
pub mod madlad_extra2;
pub mod madlad_part1;
pub mod modnet;
pub mod nllb;
pub mod nllb_extra;
pub mod nllb_extra2;
pub mod nnfp;
pub mod ppocr_det;
pub mod ppocr_det_extra;
pub mod ppocr_rec;
pub mod ppocr_rec_extra;
pub mod scrfd;
pub mod selfie;
pub mod supertonic_duration;
pub mod supertonic_duration_extra;
pub mod supertonic_sampler;
pub mod supertonic_text;
pub mod supertonic_vocoder;
pub mod supertonic_vocoder_extra;
pub mod tinyclip;
pub mod u2netp;
pub mod whisper;

/// A `1 x c x h x w` fp16 tensor. Batch is always 1; neither net is ever batched.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Shape {
    /// Channels.
    pub c: u32,
    /// Height.
    pub h: u32,
    /// Width.
    pub w: u32,
}

impl Shape {
    /// A shape, for readability at the call sites in the net modules.
    pub const fn new(c: u32, h: u32, w: u32) -> Shape {
        Shape { c, h, w }
    }

    /// Elements, which for fp16 is bytes / 2. Not `usize`, because every consumer is
    /// a `u32` push constant or a `u32` device offset.
    // No `is_empty`: a zero-sized tensor is a bug the builder rejects, not a state worth
    // asking about.
    #[allow(clippy::len_without_is_empty)]
    pub fn len(&self) -> u32 {
        self.c * self.h * self.w
    }
}

/// The activation a layer folds into its own store, saving a full pass over the
/// output. U^2-Netp is 112 ReLUs over up to 6.5M elements each; not fusing them would
/// roughly double its memory traffic.
///
/// Fusing is not merely an optimisation for [`Act::PRelu`]: MobileFaceNet's 34 `PRelu`
/// nodes each follow a `Conv` directly, so there is no shape of graph in which one
/// would need to stand alone.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Act {
    /// Store the accumulator as-is.
    None,
    /// `max(x, 0)`.
    Relu,
    /// `x * clamp(x/6 + 0.5, 0, 1)`, i.e. ONNX `HardSwish` at its default alpha/beta.
    HardSwish,
    /// `1 / (1 + exp(-x))`.
    Sigmoid,
    /// ONNX `PRelu`: `x < 0 ? slope[c] * x : x`, one slope per output channel.
    ///
    /// Unlike the others this carries state — the slope's position in the `.maml`
    /// tensor table, which [`Builder`] resolves to an offset like any other weight and
    /// passes down in [`Push::act_weight`]. A plain `Relu` is the same thing at slope
    /// zero, and is kept separate because it needs no memory traffic at all.
    PRelu(usize),
    /// `clamp(x, 0, 1)`.
    ///
    /// This is a **normalised** `HardSigmoid`. ONNX's is `clamp(alpha * x + beta, 0, 1)`
    /// and PP-OCRv5 uses two different alphas, but `alpha` and `beta` fold into the
    /// convolution's weight and bias — `scripts/ml/ppocr_fold.py` does it — so the
    /// runtime needs one parameterless clamp rather than an activation carrying two
    /// floats through the push block.
    Clip01,
    /// `clamp(x, 0, 6)`: ONNX `Clip` at MobileNetV2's ReLU6 bounds.
    ///
    /// Every backbone convolution of MODNet's MobileNetV2 encoder is followed by
    /// one (35 uses, all `(0.0, 6.0)` — verified by `scripts/ml/modnet_fold.py`,
    /// which rejects any other bounds). A rewrite to plain ReLU would change the
    /// trained math, so this is fused into the producing convolution like every
    /// other activation here rather than rewritten away.
    Relu6,
    /// `x / (1 + exp(-x))`, i.e. ONNX `Sigmoid` multiplied by its own input.
    ///
    /// Seven uses in PP-OCRv5 recognition, where the export spells it out as
    /// `Mul(x, Sigmoid(x))`; the fold recognises that the way it recognises HardSwish.
    Swish,
    /// The **exact** GELU, `0.5 x (1 + erf(x / sqrt(2)))`.
    ///
    /// 44 uses across Supertonic's four networks, where the export spells it as an `Erf`.
    ///
    /// The tanh approximation would also have done: measured, the two forms differ by at most
    /// 4.7e-4 (at `x = 2.699`), and fp16's step there is 2.0e-3, so the difference is four
    /// times finer than the arena can represent. `erf` is used anyway for two reasons that are
    /// about agreement rather than accuracy — it is what the export computes, and
    /// `nets::erf` implements the same Abramowitz and Stegun 7.1.26 series for the host
    /// interpreter, so the reference and the shader are the same function by construction rather
    /// than by coincidence. The cost is comparable either way.
    Gelu,
}

/// Which compute pipeline an [`Op::Dispatch`] wants.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    /// Dense, grouped or dilated convolution with a fused activation.
    Conv,
    /// Transposed convolution. One use, in the selfie net's 2x upsample to 256x256.
    ConvTranspose,
    /// Max pooling.
    MaxPool,
    /// Average pooling over an explicit window, floored and unpadded.
    ///
    /// One use: the pool that turns PP-OCRv5 recognition's `[480, 3, 80]` feature map
    /// into the `[480, 1, 40]` sequence its transformer reads, with kernel and stride
    /// both `(3, 2)`. The asymmetric window is the reason this carries `kh`/`kw` rather
    /// than one size, and the reason it is not [`Kind::GlobalAvgPool`] with a flag — that
    /// one reduces all of H and W to a single value and needs no window arithmetic.
    AvgPool,
    /// Bilinear resize, `half_pixel` coordinates.
    Resize,
    /// Nearest-neighbour resize, `asymmetric` coordinates and `floor` rounding.
    ///
    /// SCRFD's feature-pyramid upsamples are `mode=nearest`, which is a different
    /// pipeline rather than a flag on [`Kind::Resize`] because the two share no
    /// arithmetic: `src = floor(dst * in / out)` against `(dst + 0.5) * in / out - 0.5`.
    /// Running an FPN through the bilinear one blurs every lateral it adds.
    ResizeNearest,
    /// Mean over H and W, keeping C. The squeeze in a squeeze-excite block.
    GlobalAvgPool,
    /// Elementwise sum of two equal shapes.
    Add,
    /// `a * b` where `b` is `C x 1 x 1`. The excite in a squeeze-excite block.
    MulBroadcast,
    /// `a + b` where `b` is `C x 1 x 1`. A per-channel shift.
    ///
    /// The mirror of [`Kind::MulBroadcast`], and used for one thing: the four timestep
    /// conditioning layers of Supertonic's sampler, whose `Linear` from the time embedding the
    /// host evaluates because it depends on two scalars. See `shaders/add_bcast.comp`.
    AddBroadcast,
    /// Elementwise `a * b` over two equal shapes.
    Mul,
    /// `x * scale + shift`, both scalars. PP-OCRv5's "learnable affine block".
    ///
    /// One pass over the data for two multiplies. It exists because these sit *after* an
    /// activation, so unlike everything else constant in that export they cannot be
    /// folded into the preceding convolution's weight.
    ///
    /// # Why they cannot all fold forward either
    ///
    /// Pushing one into the *following* convolution looks free: `conv(a * x + t)[m]` is
    /// `a * conv(x)[m] + t * sum(W[m])`. That identity holds at an interior pixel and
    /// **fails at a border one**, because a padded convolution reads zero outside the
    /// input rather than `t`, so the constant's real contribution there is `t` times the
    /// sum of only the in-bounds taps. The correction varies per output position, so it is
    /// not a bias and there is no fold.
    ///
    /// `scripts/ml/ppocr_fold.py` therefore folds an affine only into an *unpadded*
    /// convolution, which leaves 14 in detection and 16 in recognition — all of them
    /// feeding either a padded depthwise or a squeeze-excite's two branches.
    ///
    /// Getting this wrong is invisible to every structural check: the layer table, the
    /// tensor count and the digest are all unchanged, and only the weight *values* differ.
    /// `scripts/ml/onnx_parity.py` is what caught it.
    Affine,
    /// Layer normalisation **over the channel axis**, with a per-channel affine.
    ///
    /// # Why over channels
    ///
    /// PP-OCRv5's recogniser is a CNN feeding a small transformer — `d_model` 120, 8
    /// heads, two blocks — and this runtime keeps its sequences in the layout the CNN
    /// already produces: `[d_model, 1, T]`, features in channels and the sequence along
    /// the width. Two things fall out of that and neither is an accident:
    ///
    /// * **Every linear projection is a 1x1 convolution.** All five feed-forward `Gemm`s
    ///   and all six attention projections are already expressible, so they need no new
    ///   pipeline.
    /// * **Splitting `d_model` into heads is free.** `[120, 1, T]` read as `[8, 15, T]`
    ///   is the same bytes in the same order, so there is no `Permute` and no `Reshape`
    ///   anywhere in the attention — the attention pipelines take the head count in
    ///   [`Push::group`] and index accordingly.
    ///
    /// The cost is that the reduction here is strided rather than contiguous. At
    /// `d_model` 120 that is 120 loads a stride apart per position, which is nothing
    /// against what a contiguous layout would cost in permutes.
    LayerNorm,
    /// Instance normalisation over the **spatial** axes, with no affine.
    ///
    /// [`Kind::LayerNorm`] transposed: where that one reduces `C` strided values per
    /// position, this reduces one channel's contiguous `H x W` plane — so one
    /// invocation per channel, and the whole pass is contiguous reads. Sixteen uses,
    /// all MODNet's `IBNorm` second halves (`InstanceNorm2d(affine=False)`), each
    /// over one [`Builder::slice_channels`] half rejoined by [`Builder::concat`].
    ///
    /// No gamma/beta: the export's scale and bias are constant 1 and 0 (verified
    /// by `scripts/ml/modnet_fold.py`, which rejects anything else), so there are
    /// no weight reads at all — epsilon rides [`Push::param1_bits`] like the other
    /// norms'. The batch-norm halves of those same `IBNorm`s are folded into their
    /// convolutions by the fold script instead, which is why this is the only new
    /// pipeline MODNet needs.
    InstanceNorm,
    /// Root-mean-square normalisation over the channel axis, with a per-channel gain.
    ///
    /// [`Kind::LayerNorm`] without the mean subtraction and without beta:
    /// `x / sqrt(mean(x^2) + eps) * gamma`. Sixteen uses, all Maia3's `norm1` / `norm2`.
    ///
    /// Separate from [`Kind::LayerNorm`] rather than a flag on it because the difference
    /// is also a difference in *weights read* — one rank-1 tensor rather than two — and
    /// [`Kind::weight_reads`] is what `vulkan::segment` uses to decide which slice of the
    /// `.maml` a pass has to have resident. A flag would make that arm depend on the push
    /// block's contents rather than its kind.
    RmsNorm,
    /// `S[h][i][j] = scale * sum_d Q[h][d][i] * K[h][d][j]`, attention's score map.
    ///
    /// Contracts over the *middle* axis of `[heads, head_dim, T]`, which is what makes
    /// the head split free — see [`Kind::LayerNorm`]. Takes the head count in
    /// [`Push::group`] and `1 / sqrt(head_dim)` in [`Push::param0_bits`].
    ///
    /// The output is `[heads, T, T]` with the key index innermost, so the row a
    /// [`Kind::Softmax`] normalises is contiguous.
    AttnScores,
    /// Softmax over the last axis, one row at a time.
    ///
    /// Kept separate from [`Kind::AttnScores`] rather than fused into it. Fusing would
    /// save writing a `heads * T * T` intermediate — 100 KiB at the recogniser's sizes,
    /// which is not a cost worth a shader that does two things — and would give up the
    /// per-score parallelism, since a fused pass has to be one invocation per *row* to
    /// see the whole distribution it is normalising.
    Softmax,
    /// [`Kind::Softmax`] with each row truncated at the diagonal: a **causal** mask.
    ///
    /// One use, TinyCLIP's text tower, whose three layers attend over all 77 tokens at once and
    /// may not read the future. Query `q`'s row is normalised over keys `0 ..= q` and the rest of
    /// it is written as zero, so [`Kind::AttnApply`] can multiply the whole row unchanged.
    ///
    /// A separate pipeline rather than a flag on [`Kind::Softmax`], because that one is shared by
    /// PP-OCRv5 recognition, both Supertonic encoders and SMaLL-100 — a push field the bound came
    /// from would break four shipping nets at once if it were ever left unset. See
    /// `shaders/softmax_causal.comp`.
    SoftmaxCausal,
    /// [`Kind::Softmax`] over only the leading `prefix + 1` entries of each row.
    ///
    /// The decode counterpart. Once a decode plan is built once at a maximum context rather than
    /// rebuilt per token, a score-map row is as wide as that maximum but only its leading span was
    /// written this step; the rest holds whatever the previous step left. Normalising the whole
    /// row would fold that stale data into the maximum and the sum.
    ///
    /// A separate pipeline for the same reason [`Kind::SoftmaxCausal`] is one, and more so: plain
    /// [`Kind::Softmax`] is shared by PP-OCRv5 recognition, both Supertonic encoders, whisper,
    /// maia and NLLB's own encoder. See `shaders/softmax_prefix.comp`.
    SoftmaxPrefix,
    /// Write one position into a KV cache at the row [`crate::vulkan::run::StepParams::prefix`]
    /// names.
    ///
    /// The only op whose *destination* is decided at submit time rather than at record time, and
    /// the reason a decode plan can be recorded once. See `shaders/cache_write.comp` and
    /// [`Builder::cache_write`].
    CacheWrite,
    /// `tanh(x / cap) * cap`, Gemma's `final_logit_softcapping`. See `shaders/softcap.comp`.
    Softcap,
    /// The argmax of a logits row: index of the maximum value as a scalar.
    ///
    /// A device-side greedy sampler. The 262,144-logit host scan costs ~14 ms
    /// a token; this reduces on the device and reads back one value instead
    /// of 512 KB of logits. See `shaders/argmax.comp`. The full-logits path
    /// (Kotlin sampling) is untouched — this serves greedy decoding only.
    ///
    /// The first of two stages: many workgroups each reduce a slice of the row to a
    /// `(value, lo, hi)` partial, and [`Kind::ArgmaxFold`] folds the partials to the
    /// winning index. See [`Builder::argmax`].
    Argmax,
    /// Fold [`Kind::Argmax`]'s partials to the winning index as two fp16 lanes.
    ///
    /// Reads the `(value, lo, hi)` triples the first stage wrote and stores
    /// `lo = id % 2048`, `hi = id / 2048`, keeping the FIRST index on ties.
    /// See `shaders/argmax_fold.comp`.
    ArgmaxFold,
    TopK, // The 64 largest of a row with their ids; see shaders/topk.comp.
    /// An [`Act`] applied on its own, for a value no convolution produced. See
    /// `shaders/activate.comp`.
    ///
    /// [`Act::PRelu`] is **not** supported: its slope is a weight tensor, and the one caller
    /// this exists for wants `Gelu`. Passing it here silently uses a zero slope, which is a
    /// plain `Relu` - so [`Builder::activate`] refuses it rather than letting that happen.
    Activate,
    /// `activate(gate) * up` over a fused `[gate | up]` projection, replacing two slices, an
    /// activation and a multiply. See `shaders/gated_activate.comp`.
    GatedActivate,
    /// Multiply by a scalar held in the weights. See `shaders/mul_scalar.comp`.
    MulScalar,
    /// Clamp to a `[min, max]` pair held in the weights. See `shaders/clamp.comp`.
    Clamp,
    /// `clamp(round(x / scale), -128, 127) * scale`, an int8 round-trip at a
    /// per-layer scale. See `shaders/quantize.comp`.
    ///
    /// S10's live attention quantizes the mixture to int8 before the O projection
    /// consumes it; the scale rides the push block like [`Kind::Softcap`]'s cap.
    Quantize,
    /// Block-quantise an activation to int8 for [`Kind::ConvPointInt4Dot`]. See
    /// `shaders/quantize_blocks.comp`.
    QuantizeBlocks,
    /// [`Kind::ConvPointInt4`] on the int8 dot product, reading the staged activation at
    /// [`Push::kw`]. Falls back to the fp32 pipeline on a device without the extension. See
    /// `shaders/conv_point_int4_dot.comp`.
    ConvPointInt4Dot,
    /// `O[h][d][i] = sum_j S[h][i][j] * V[h][d][j]`, attention's weighted sum.
    ///
    /// `O[h][d][i] = sum_j S[h][i][j] * V[h][d][j]`, attention's weighted sum.
    ///
    /// Writes `[d_model, 1, T]`, so the head concatenation that normally follows
    /// attention is not an op: output channel `c` belongs to head `c / head_dim` and
    /// lands where the concatenated result wants it. Head count in [`Push::group`].
    AttnApply,
    /// [`Kind::AttnScores`] plus a relative-position term, which is nine taps rather than the
    /// `[heads, T, 2T-1]` product and skew the export spells out. Table at [`Push::weight`] as
    /// `[2 * window + 1, head_dim]`, offset count in [`Push::kw`].
    ///
    /// Both of Supertonic's encoders use it: `supertonic_text` has four such layers and
    /// `supertonic_duration` two, all at `window_size` 4, so `OFFSETS` is 9 in each.
    AttnScoresRelative,
    /// [`Kind::AttnApply`] plus the value-side relative term. See
    /// [`Kind::AttnScoresRelative`].
    AttnApplyRelative,
    /// Attention scores over a **backward sliding window**, stored as a band, with the relative
    /// term and the logit cap fused in. See `shaders/attn_scores_banded.comp`.
    ///
    /// `[heads, T, band]` rather than `[heads, T, T]`: Gemma 4's audio tower attends
    /// `0 <= q - k <= 11`, so a square map computes sixty-two times what it keeps. Band width in
    /// [`Push::kh`], relative offsets in [`Push::kw`], table at [`Push::weight`] as
    /// `[heads, offsets, head_dim]`, cap in [`Push::param1_bits`].
    AttnScoresBanded,
    /// [`Kind::AttnScoresBanded`]'s value half: a band of probabilities against a
    /// `[d_model, 1, T]` sequence. See `shaders/attn_apply_banded.comp`.
    AttnApplyBanded,
    /// [`AttnScores`](Kind::AttnScores) for one query against a **position-major** K cache.
    ///
    /// A decoder step is one query, and its keys live in a cache that a position is appended to
    /// every step. `[d_model, 1, T]` makes that append `d_model` scattered two-byte copies, so a
    /// cache is `[T, 1, d_model]` instead and reads through this. See
    /// `shaders/attn_scores_cached.comp` for why the shared shaders are not extended.
    AttnScoresCached,
    /// [`AttnApply`](Kind::AttnApply) for one query against a **position-major** V cache.
    AttnApplyCached,
    /// A `1 x 1` convolution as a tiled matrix multiply, staging weights through shared memory.
    ///
    /// [`Kind::Conv`] reads each output element''s weights from global memory with no reuse, which
    /// costs nothing for a spatial kernel and everything for a wide `1 x 1` over a short
    /// sequence - measured at 15.2 GFLOP/s on a Tensor G4 against a device peak of order 1000.
    /// This stages a tile of weights once per workgroup instead. `Builder::conv` routes here
    /// automatically for an ungrouped `1 x 1`; see `conv_point.comp`.
    ///
    /// [`Push::count`] is the **tile** count for this kind, not the output element count.
    ConvPoint,
    /// [`Kind::Conv`] with int8 weights and one dequantisation scale per output channel.
    ///
    /// Only for a network where the weights dominate the download. The scale multiplies the
    /// finished accumulator rather than every tap, so the inner loop costs what fp16 costs;
    /// activations stay fp16, which is simpler than the export''s dynamic quantisation and
    /// strictly more accurate. [`Push::weight`] is a **word** offset here, not an fp16 one, and
    /// [`Push::act_weight`] holds the scale tensor — which is why [`Act::PRelu`] is refused.
    ConvInt8,
    /// [`Kind::ConvPoint`] with int8 weights: the tiled lowering of [`Kind::ConvInt8`].
    ///
    /// `Builder::emit` routes an int8 convolution here under exactly the conditions an fp16 one
    /// reaches [`Kind::ConvPoint`] under — ungrouped, `1 x 1`, stride 1, unpadded. Without it,
    /// quantising Supertonic's sampler would move 92% of its parameters onto the untiled path,
    /// which is far slower than the size saving is worth; see `conv_point_int8.comp`.
    ///
    /// [`Push::count`] is the **tile** count here, as it is for [`Kind::ConvPoint`].
    ConvPointInt8,
    /// `out[c][t] = table[id(t)][c]`, an embedding lookup.
    ///
    /// The only op here whose addresses depend on the data. Ids arrive as an ordinary fp16
    /// tensor, which is exact for a small vocabulary — fp16 holds every integer to 2048. Table at
    /// [`Push::weight`], its row count in [`Push::in_w`], and an out-of-range id clamps rather
    /// than reading past the table.
    ///
    /// # Vocabularies past 2048
    ///
    /// Above [`EMBED_LANE`] the gaps between representable fp16 integers open up, so a single
    /// id lane would silently land on a neighbouring row — Supertonic has 8,322 symbols and
    /// SMaLL-100 has 128,112. Those arrive **split across two lanes**, `id = lo + 2048 * hi`,
    /// as a `[2, 1, T]` tensor; [`Push::in_c`] carries the lane count. See [`embed_lanes`].
    /// [`Kind::ConvPointInt8`] for a single position: a matrix-vector product.
    ///
    /// The tiled kind's workgroup count is `out_c.div_ceil(16) * positions.div_ceil(16)`, so at one
    /// position 15 of every 16 tile columns are padding and 56 of every 64 invocations store
    /// nothing. A SMaLL-100 decode step is entirely single-position — 30 projections and a
    /// 128,112-class head, 128 times per translation — so that waste lands on the dominant cost.
    /// See `shaders/conv_vec_int8.comp`.
    ///
    /// Chosen automatically by [`Builder`]; no net asks for it.
    ConvVecInt8,
    /// [`Kind::ConvVecInt8`] with a four-bit kernel and a per-block scale. See
    /// `shaders/conv_vec_int4.comp`.
    ConvVecInt4,
    /// [`Kind::ConvPointInt8`] with a four-bit kernel and a per-block scale. See
    /// `shaders/conv_point_int4.comp`.
    ConvPointInt4,
    /// [`Kind::ConvVecInt8`] with a GGUF Q2_K kernel and a per-superblock `(d, dmin)`
    /// scale. See `shaders/conv_vec_q2k.comp`.
    ConvVecQ2K,
    /// [`Kind::ConvPointInt8`] with a GGUF Q2_K kernel and a per-superblock `(d, dmin)`
    /// scale. See `shaders/conv_point_q2k.comp`.
    ConvPointQ2K,
    /// [`Kind::ConvInt8`] with a GGUF Q2_K kernel: the untiled lowering. See
    /// `shaders/conv_q2k.comp`.
    ConvQ2K,
    Embed,
    /// `out[i] = weights[i]`, a learned tensor copied into the arena.
    ///
    /// The only op that produces a tensor from nothing but the weights file. It exists because
    /// [`Kind::AttnScores`] contracts two *arena* tensors, and Supertonic's text encoder attends
    /// against 50 style keys that are entirely constant — `tanh(W_key . style_key + b_key)`,
    /// which the converter folds to one tensor. See `shaders/constant.comp`.
    Constant,
    /// Rotary position embedding over a `[C, 1, W]` sequence, the **half**-split convention.
    ///
    /// `out[j] = x[j] cos - x[j + half] sin` and `out[j + half] = x[j + half] cos + x[j] sin`,
    /// within each head. The angle table is the second operand rather than a weight: in this
    /// export the angle is `(position / length) * theta`, so it depends on the sequence length
    /// and the host rebuilds it per call. See `shaders/rotary.comp` for why the pairing
    /// convention matters more than it looks.
    Rotary,
}

include!("mod_part1.rs");
include!("mod_part2.rs");
include!("mod_part2b.rs");
include!("mod_part3.rs");
include!("mod_part4.rs");
include!("mod_part4b.rs");
include!("mod_part5.rs");
include!("mod_part6.rs");
include!("mod_part6b.rs");
include!("mod_part7.rs");
include!("mod_part8.rs");
include!("mod_part9.rs");
include!("mod_part9b.rs");
include!("mod_part9c.rs");
include!("mod_part9d.rs");
include!("mod_part10.rs");
include!("mod_part11.rs");
include!("mod_part12.rs");
include!("mod_part13.rs");
include!("mod_part14.rs");