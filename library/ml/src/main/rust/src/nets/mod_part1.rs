/// The push-constant block every shader declares.
///
/// `repr(C)` so field order is declaration order, which is what the SPIR-V offsets
/// assume. Deliberately one block shared by every pipeline: it fits inside the
/// 128 bytes the spec guarantees (asserted in [`tests`]), so there is a single
/// pipeline layout, no uniform buffers and no descriptor writes after setup.
///
/// `Default` is manual rather than derived: [`NO_FUSE`] is the opt-out for the two fused-addend
/// fields, and the derive would write 0, which is a live offset. Every `..Push::default()` site
/// outside the fusion fold therefore gets "no fusion" without naming it.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Push {
    /// Element offset of the first input in the activation arena.
    pub in0: u32,
    /// Element offset of the second input, for the binary ops.
    ///
    /// [`Kind::Conv`] reuses this as the blocked-path selector: 1 routes to the
    /// register-blocked implementation in `shaders/conv_tiled.glsl`, 0 to the
    /// one-invocation-per-element one. The binary ops are the only other readers and
    /// no convolution is one, so the two uses cannot meet. Set by
    /// [`Builder::spatial_tiling`]; zero everywhere that never opts in.
    pub in1: u32,
    /// Element offset of the output in the arena.
    pub out: u32,
    /// Element offset of the kernel in the weights buffer.
    pub weight: u32,
    /// Element offset of the bias in the weights buffer.
    pub bias: u32,
    /// Input channels.
    pub in_c: u32,
    /// Input height.
    pub in_h: u32,
    /// Input width.
    pub in_w: u32,
    /// Output channels.
    pub out_c: u32,
    /// Output height.
    pub out_h: u32,
    /// Output width.
    pub out_w: u32,
    /// Kernel height.
    pub kh: u32,
    /// Kernel width.
    pub kw: u32,
    /// Vertical stride.
    pub stride_h: u32,
    /// Horizontal stride.
    pub stride_w: u32,
    /// Vertical dilation.
    pub dil_h: u32,
    /// Horizontal dilation.
    pub dil_w: u32,
    /// Padding above row 0. ONNX's `pads[0]`.
    pub pad_t: u32,
    /// Padding left of column 0. ONNX's `pads[1]`.
    pub pad_l: u32,
    /// Non-zero when the convolution replicates its border instead of reading zeros.
    ///
    /// ONNX spells this as a `Pad` node with `mode=edge` in front of a convolution whose own
    /// `pads` are all zero, which is how Supertonic''s vocoder keeps its length: twelve of them,
    /// one before every convolution. Zero padding there would corrupt up to twelve positions at
    /// each END of an utterance and leave the middle correct - an audible click at the start and
    /// finish of every sentence.
    ///
    /// `conv.comp` clamps columns at `in_w - pad_edge`, so values above 1 ignore a padded tail.
    pub pad_edge: u32,
    /// Convolution groups. `group == in_c == out_c` is depthwise.
    pub group: u32,
    /// [`Act::code`].
    pub act: u32,
    /// Element offset of the per-channel slope [`Act::PRelu`] reads, in the weights
    /// buffer. Zero and unread for every other activation.
    pub act_weight: u32,
    /// [`Kind::Affine`]'s multiplier, or [`Kind::AttnScores`]'s `1 / sqrt(head_dim)`,
    /// as raw bits. Unread by everything else.
    ///
    /// `f32` bits rather than an `f32` field so [`Push`] stays all-`u32` and
    /// `push_bytes` can keep treating it as a plain byte block with no padding.
    pub param0_bits: u32,
    /// [`Kind::Affine`]'s addend, or [`Kind::LayerNorm`]'s epsilon.
    pub param1_bits: u32,
    /// Output elements, so an over-dispatched workgroup can bail.
    ///
    /// Not always the element count: [`Kind::LayerNorm`] and [`Kind::Softmax`] each run
    /// one invocation over a whole reduction, so for them this is the number of those.
    pub count: u32,
    /// Non-zero when the attended key count comes from the step-params buffer, not from here.
    ///
    /// A decode step attends over one more position each token. Baking that into the plan is what
    /// made [`crate::vulkan::reshape::Reshaped`] re-record per token. When this is set, the three
    /// cached-attention kinds read `prefix + 1` from
    /// [`crate::vulkan::run::StepParams`] instead, and `in_w` / `out_w` stop being the key *count*
    /// and become only the key *stride* — the maximum the plan was built for.
    ///
    /// Zero for every net that does not decode, which is all of them but NLLB and whisper, so
    /// their recordings and their numbers are untouched.
    pub dyn_keys: u32,
    /// Key/value heads, when fewer than [`Push::group`]. Zero means as many as `group`.
    ///
    /// Grouped- and multi-query attention: several query heads share one key/value head, so a
    /// cache position is `kv_heads * head_dim` channels rather than `group * head_dim`. Gemma 4's
    /// text decoder is the extreme case, eight query heads to **one** key/value head.
    ///
    /// Zero for every net that predates this, so their caches and their pushes are unchanged.
    pub kv_heads: u32,

    /// Non-zero when this op's attention **slides**, so its first key is `window_start`.
    ///
    /// A push constant rather than a step parameter because whether a layer slides is
    /// structural: Gemma 4's layers 0-3 slide and layer 4 does not, for every step of every
    /// generation. Baking it at record time is what lets one [`crate::vulkan::run::StepParams`]
    /// serve a net that mixes both - the host computes `window_start` once and the full-attention
    /// layers ignore it.
    ///
    /// Without this the two kinds share a window, which is correct only while the whole prefix
    /// fits inside it. Past that the global layers would silently lose their long-range
    /// attention, which is the one thing they exist for, and only on conversations long enough
    /// that nobody tests them.
    pub sliding: u32,

    /// Independent rotary sub-blocks per head. 1 is ordinary 1-D RoPE.
    ///
    /// Gemma 4's vision tower uses 2: a 64-wide head is two 32-wide blocks, one rotated by the
    /// patch's row and one by its column, each with its own sixteen frequencies. Rotating the
    /// whole head as a single block would pair a row channel with a column channel, which is not
    /// a shape error and produces an image encoder that is subtly position-blind.
    pub rope_axes: u32,

    /// Arena offset of a residual addend folded into this op's store, or [`NO_FUSE`].
    ///
    /// [`Builder::finish`] removes a single-consumer `Add` after a convolution by storing
    /// `activate(acc + bias) + arena[res + index]` instead of emitting the add as its own
    /// dispatch. Same shape as the output, so the same index addresses both. Only the
    /// convolution kinds read it; every other op leaves [`NO_FUSE`].
    pub res: u32,
    /// Arena offset of a per-channel shift folded into this op's store, or [`NO_FUSE`].
    ///
    /// The `AddBroadcast` half of the same fold: Supertonic's timestep conditioning adds one
    /// value per channel, so the fused store adds `arena[shift + channel]`. `C` values, which
    /// is why this is a separate field rather than a second [`Push::in1`].
    pub shift: u32,
}

impl Default for Push {
    fn default() -> Push {
        Push {
            in0: 0,
            in1: 0,
            out: 0,
            weight: 0,
            bias: 0,
            in_c: 0,
            in_h: 0,
            in_w: 0,
            out_c: 0,
            out_h: 0,
            out_w: 0,
            kh: 0,
            kw: 0,
            stride_h: 0,
            stride_w: 0,
            dil_h: 0,
            dil_w: 0,
            pad_t: 0,
            pad_l: 0,
            pad_edge: 0,
            group: 0,
            act: 0,
            act_weight: 0,
            param0_bits: 0,
            param1_bits: 0,
            count: 0,
            dyn_keys: 0,
            kv_heads: 0,
            sliding: 0,
            rope_axes: 0,
            // The only fields whose zero value would be live: see [`NO_FUSE`].
            res: NO_FUSE,
            shift: NO_FUSE,
        }
    }
}

/// Which span of a score-map row a [`Node::Softmax`] normalises.
///
/// A dedicated pipeline each, rather than one shader branching on a push field, because
/// [`Kind::Softmax`] is shared by six shipping nets and a field left unset would break all of
/// them at once. See [`Kind::SoftmaxCausal`] and [`Kind::SoftmaxPrefix`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SoftmaxMode {
    /// The whole row.
    Full,
    /// Up to and including the diagonal; the rest is written as zero.
    Causal,
    /// The leading `prefix + 1` entries, the rest left untouched.
    Prefix,
}

/// How wide a quantised convolution's kernel is, and so how its scale is shaped.
///
/// The three are not interchangeable at the same scale layout: eight bits carry a row's dynamic
/// range with one scale per output channel, four bits do not, so [`Quant::I4`] takes a rank-2
/// scale of one per block of [`crate::weights::I4_BLOCK`] taps — and two bits need a
/// superblock `(d, dmin)` pair per 256 taps, so [`Quant::Q2K`] takes a rank-1 scale of two
/// values per block of [`crate::weights::Q2K_BLOCK`] taps.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Quant {
    /// Signed 8-bit, one scale per output channel.
    I8,
    /// Signed 4-bit, one scale per block of taps.
    I4,
    /// GGUF Q2_K superblocks, one `(d, dmin)` pair per block of taps.
    Q2K,
}

/// One step of a compiled forward pass.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Op {
    /// Run `kind` over `invocations` output elements.
    Dispatch {
        /// The pipeline to bind.
        kind: Kind,
        /// Its parameters.
        push: Push,
        /// Output elements, one per invocation.
        invocations: u32,
    },
    /// A contiguous copy inside the arena, in fp16 elements.
    ///
    /// This is how `Concat` along the channel axis is done, and it needs no shader:
    /// in NCHW a run of channels *is* contiguous, so concatenation is placing each
    /// part end to end. `vkCmdCopyBuffer` does that faster than a compute pass and
    /// is why there is no `concat.comp`.
    Copy {
        /// Source element offset.
        src: u32,
        /// Destination element offset.
        dst: u32,
        /// Elements to move.
        elems: u32,
    },
}

/// One weights-buffer byte offset an [`Op::Dispatch`] reads a tensor from.
///
/// Exists because `vulkan::segment` has to know which region of the weights file each op
/// touches, and the answer is a property of the *shaders* rather than of segmentation: three
/// of [`Push`]'s fields hold weights offsets, and which of them a shader reads — and in what
/// units — depends on the [`Kind`]. Keeping the table here means it sits beside the fields it
/// describes and beside `tests::assert_no_aliasing`, which does the same job for the arena.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WeightRead {
    /// Byte offset into the `.maml` data section.
    pub at: u64,
    /// Which [`Push`] field it came from, for error messages.
    pub field: &'static str,
}

impl Kind {
    /// Every weights offset this kind reads, in bytes, given one op's [`Push`].
    ///
    /// Read off the shaders rather than inferred: `shaders/*.comp` name `p.weight`, `p.bias` and
    /// `p.act_weight` explicitly, and `prelu` in `common.glsl` is what makes `act_weight` a read
    /// of any kind carrying [`Act::PRelu`] rather than only of the int8 pair.
    ///
    /// Bytes, not element indices, because `weight` is a **32-bit word** index for the two int8
    /// kinds and an fp16 element index everywhere else — the one place that difference has to be
    /// resolved rather than carried.
    pub fn weight_reads(self, push: &Push) -> Vec<WeightRead> {
        let elems = |at: u32| u64::from(at) * 2;
        let words = |at: u32| u64::from(at) * 4;
        let mut reads = Vec::new();
        match self {
            // A kernel, a bias, and a per-channel slope when the activation is PRelu.
            Kind::Conv | Kind::ConvTranspose | Kind::ConvPoint => {
                reads.push(WeightRead { at: elems(push.weight), field: "weight" });
                reads.push(WeightRead { at: elems(push.bias), field: "bias" });
            }
            // As above, plus the dequantisation scale, which occupies `act_weight` and is why
            // `Builder::conv_int8` refuses `Act::PRelu`.
            Kind::ConvInt8 | Kind::ConvPointInt8 | Kind::ConvVecInt8 => {
                reads.push(WeightRead { at: words(push.weight), field: "weight" });
                reads.push(WeightRead { at: elems(push.bias), field: "bias" });
                reads.push(WeightRead { at: elems(push.act_weight), field: "act_weight" });
            }
            // The same three reads: an int4 kernel is addressed through the identical 32-bit
            // word view, and its scale table is fp16 like an int8 one - only wider. Q2_K
            // reads identically too: superblocks through the word view, the `(d, dmin)`
            // table through the fp16 view, bias as usual.
            Kind::ConvVecInt4 | Kind::ConvPointInt4 | Kind::ConvPointInt4Dot | Kind::ConvQ2K | Kind::ConvVecQ2K | Kind::ConvPointQ2K => {
                reads.push(WeightRead { at: words(push.weight), field: "weight" });
                reads.push(WeightRead { at: elems(push.bias), field: "bias" });
                reads.push(WeightRead { at: elems(push.act_weight), field: "act_weight" });
            }
            // Gamma then beta, both rank-1.
            Kind::LayerNorm => {
                reads.push(WeightRead { at: elems(push.weight), field: "weight" });
                reads.push(WeightRead { at: elems(push.bias), field: "bias" });
            }
            // One table each: the relative position table, the literal to copy out, the
            // embedding rows. RMS norm is here too: gamma with no beta.
            Kind::AttnScoresRelative
            | Kind::AttnApplyRelative
            | Kind::AttnScoresBanded
            | Kind::Constant
            | Kind::Embed
            | Kind::RmsNorm => {
                reads.push(WeightRead { at: elems(push.weight), field: "weight" });
            }
            // Purely elementwise or arena-only. `attn_scores`, `softmax`, `attn_apply` and
            // `rotary` all take their second operand from the arena, and the cached attention
            // pair take theirs from a cache in the arena too. Instance norm joins them:
            // its scale and bias are constant 1 and 0, so it reads no weights at all.
            Kind::MaxPool
            | Kind::AvgPool
            | Kind::Resize
            | Kind::ResizeNearest
            | Kind::GlobalAvgPool
            | Kind::InstanceNorm
            | Kind::Add
            | Kind::MulBroadcast
            | Kind::AddBroadcast
            | Kind::Mul
            | Kind::Affine
            | Kind::AttnScores
            | Kind::AttnScoresCached
            | Kind::Softmax
            | Kind::SoftmaxCausal
            | Kind::SoftmaxPrefix
            | Kind::CacheWrite
            | Kind::Softcap
            | Kind::Quantize
            | Kind::QuantizeBlocks
            | Kind::Argmax
            | Kind::ArgmaxFold
            | Kind::TopK
            | Kind::GatedActivate => {}
            // A scalar (`mul_scalar`) or a low/high pair (`clamp`) at `act_weight`.
            Kind::MulScalar | Kind::Clamp => {
                reads.push(WeightRead { at: elems(push.act_weight), field: "act_weight" });
            }
            Kind::Activate
            | Kind::AttnApply
            | Kind::AttnApplyCached
            | Kind::AttnApplyBanded
            | Kind::Rotary => {}
        }
        let applies_act = !reads.is_empty() || matches!(self, Kind::Activate | Kind::GatedActivate);
        if applies_act && push.act == Act::PRelu(0).code() {
            let slope = WeightRead { at: elems(push.act_weight), field: "act_weight" };
            if !reads.contains(&slope) {
                reads.push(slope);
            }
        }
        reads
    }

    /// Every arena range this kind reads, in **fp16 elements**, given one op's [`Push`].
    ///
    /// The mirror of [`Kind::weight_reads`] for the other buffer, and the reason it is `pub` is
    /// [`super::schedule`]: deciding whether two ops need a barrier between them is exactly
    /// "does the later one read what the earlier one wrote", and that question needs both sides
    /// to be exact.
    ///
    /// # Why [`Reads::Unknown`] exists
    ///
    /// This table began as the arms of `tests::assert_no_aliasing`, which checks a *weaker*
    /// property: that an op does not read the range it is writing. A table that under-reports an
    /// operand still passes that check in every net where the operand happens not to overlap the
    /// output — but a scheduler believing the same table would drop a barrier and read a value
    /// the previous op had not finished writing, non-deterministically, on one driver.
    ///
    /// So there is no catch-all. A kind whose reads have not been checked against its shader
    /// answers [`Reads::Unknown`], which every caller must treat as "reads everything". Adding a
    /// kind is then a choice to audit it or to be conservative, rather than a default that is
    /// silently wrong.
    pub fn arena_reads(self, push: &Push) -> Reads {
        // Every input plane, as the generic shape-driven kinds read it.
        let dense = push.in_c * push.in_h * push.in_w;
        // Every output element. Not `push.count`: the reducing kinds dispatch one invocation per
        // reduction rather than per element, so their `count` understates what they touch.
        let written = push.out_c * push.out_h * push.out_w;
        let one = |at: u32, len: u32| Reads::Ranges(vec![(at, len)]);
        let two = |a: (u32, u32), b: (u32, u32)| Reads::Ranges(vec![a, b]);
        match self {
            // Both operands are the output's shape.
            Kind::Add | Kind::Mul => two((push.in0, written), (push.in1, written)),
            // Rotary reads a partner channel half a head away, so the whole plane, and the angle
            // table is `head_dim` channels of the same length.
            Kind::Rotary => two((push.in0, written), (push.in1, push.in_c * push.out_w)),
            // The gate is one value per channel, broadcast over H and W.
            Kind::MulBroadcast => two((push.in0, written), (push.in1, push.in_c)),
            // As `MulBroadcast`: one shift per channel.
            Kind::AddBroadcast => two((push.in0, written), (push.in1, push.out_c)),
            // Reads no arena at all - it is a copy out of the weights file.
            Kind::Constant => Reads::Ranges(Vec::new()),
            // Q and K are each `in_c` channels but of their own lengths, which the score map's
            // height and width carry.
            Kind::AttnScores | Kind::AttnScoresRelative | Kind::AttnScoresBanded => {
                two((push.in0, push.in_c * push.out_h), (push.in1, push.in_c * push.out_w))
            }
            // The score map is `[heads, queries, keys]`; V is a sequence of keys.
            Kind::AttnApply | Kind::AttnApplyRelative | Kind::AttnApplyBanded => {
                two((push.in0, push.group * push.out_w * push.in_w), (push.in1, dense))
            }
            // One query against a position-major cache. Q is one position of `in_c` channels; the
            // cache is `out_w` positions of `kv_heads * head_dim`, which under grouped- or
            // multi-query attention is narrower than `in_c`.
            Kind::AttnScoresCached => {
                let head_dim = push.in_c / push.group.max(1);
                let kv = if push.kv_heads == 0 { push.group } else { push.kv_heads };
                let queries = push.dil_h.max(1);
                two((push.in0, push.in_c * queries), (push.in1, push.out_w * kv * head_dim))
            }
            // One query's row of probabilities is `in_w` keys long, and the cache is `in_w`
            // positions of `kv_heads * head_dim`.
            Kind::AttnApplyCached => {
                let head_dim = push.out_c / push.group.max(1);
                let kv = if push.kv_heads == 0 { push.group } else { push.kv_heads };
                let queries = push.dil_h.max(1);
                two((push.in0, push.group * queries * push.in_w), (push.in1, push.in_w * kv * head_dim))
            }
            // One id per position per lane, so the read is `in_c * out_w` and not `dense` -
            // `in_w` here is the *table's* row count.
            Kind::Embed => one(push.in0, push.in_c * push.out_w),
            // The whole logits row, whatever the group count. See `Builder::argmax`.
            Kind::Argmax => one(push.in0, dense),
            // The candidate pairs plus the whole logits row, re-read at the candidates.
            // `in_c` is the group count, `in_w` the row length.
            Kind::ArgmaxFold => two((push.in0, push.in_c * 2), (push.in1, push.in_w)),
            // Stage 1 reads its row; stage 2 its candidate pairs and the row they index.
            Kind::TopK if push.kh == 0 => one(push.in0, push.count),
            Kind::TopK => two((push.in0, push.in_c * 2), (push.in1, push.in_w)),
            // One row in. Not `dense`: `in_h` here is the cache's capacity, not a spatial extent.
            Kind::CacheWrite => one(push.in0, push.count),
            // Convolutions, whose store may also add a folded residual and a folded
            // per-channel shift. The residual is the output's shape, so the same element
            // count the op writes; the shift is one value per output channel. `NO_FUSE`
            // reads nothing — see `Push::res` — so the ranges stay exact rather than
            // conservative, which is what `schedule` needs them to be.
            Kind::Conv
            | Kind::ConvTranspose
            | Kind::ConvInt8
            | Kind::ConvPoint
            | Kind::ConvPointInt8
            | Kind::ConvVecInt8
            | Kind::ConvPointInt4
            | Kind::ConvPointInt4Dot
            | Kind::ConvVecInt4
            | Kind::ConvQ2K
            | Kind::ConvPointQ2K
            | Kind::ConvVecQ2K => {
                let mut ranges = vec![(push.in0, dense)];
                // The dot GEMM also reads the int8 blocks staged at `kw`.
                if self == Kind::ConvPointInt4Dot {
                    ranges.push((push.kw, push.in_c / 8 * 5 * push.out_h * push.out_w));
                }
                if push.res != NO_FUSE {
                    ranges.push((push.res, written));
                }
                if push.shift != NO_FUSE {
                    ranges.push((push.shift, push.out_c));
                }
                Reads::Ranges(ranges)
            }
            // The whole input plane, whatever the kernel touches.
            Kind::MaxPool
            | Kind::AvgPool
            | Kind::Resize
            | Kind::ResizeNearest
            | Kind::GlobalAvgPool
            | Kind::LayerNorm
            | Kind::InstanceNorm
            | Kind::Softmax
            | Kind::SoftmaxCausal
            | Kind::SoftmaxPrefix
            | Kind::Affine
            | Kind::Softcap
            | Kind::Quantize
            | Kind::QuantizeBlocks
            | Kind::Activate
            | Kind::GatedActivate
            | Kind::MulScalar
            | Kind::Clamp => one(push.in0, dense),
            // As above, plus a folded residual addend (see `Push::res`): the
            // post-norm `add(x, branch)` the fusion moved into this store.
            Kind::RmsNorm => {
                let mut ranges = vec![(push.in0, dense)];
                if push.res != NO_FUSE {
                    ranges.push((push.res, written));
                }
                Reads::Ranges(ranges)
            }
        }
    }
}
