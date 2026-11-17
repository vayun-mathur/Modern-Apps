//! Supertonic 3's flow-matching sampler: the velocity field, run N times per utterance.
//!
//! # What it is
//!
//! The expensive one. 1004 ONNX nodes and 64,013,449 parameters, of which 92% are the 56
//! pointwise convolutions inside its 28 ConvNeXt blocks, and it runs 16 times per utterance —
//! twice per step, for the reason below. Everything else in Supertonic is a rounding error
//! against it.
//!
//! Its shape is four **main blocks**, each of them
//!
//! ```text
//! 4 ConvNeXt blocks at dilations 1, 2, 4, 8
//! + a per-channel shift from the timestep
//! 1 ConvNeXt block
//! + an 8-head cross-attention over the text, with rotary positions
//! 1 ConvNeXt block
//! + a 2-head cross-attention over the 50 style tokens
//! ```
//!
//! then four more ConvNeXt blocks at dilation 1, between a `[512, 144, 1]` projection in and a
//! `[144, 512, 1]` projection out. The export flattens the four into `main_blocks.0` through
//! `main_blocks.23`, six entries each.
//!
//! # It is classifier-free guidance, and that doubles the cost
//!
//! The export's first three nodes are `Tile(x, [2, 1, 1])`: it runs the **whole network twice**,
//! once on the real text and style and once on two learned unconditional tokens, and combines
//! them at the end. Read off the graph:
//!
//! ```text
//! v = 4 * conditional - 3 * unconditional
//! denoised = (noisy_latent + v / total_step) * latent_mask
//! ```
//!
//! So the ONNX is not a velocity field but a whole **Euler step**, guidance scale 4 baked in.
//! This runtime has no batch axis and cannot fake one — putting the two branches side by side
//! along the sequence would let the depthwise convolutions mix them — so the plan is one branch
//! and [`crate::post::supertonic`] runs it twice. That is a real doubling of the sampler's cost against
//! any measurement taken of a single branch.
//!
//! Both branches are the same plan with different **inputs**: the unconditional one passes
//! `uncond_masker.text_special_token` broadcast over the text positions, and
//! `style_value_special_token` in place of the voice. Only the style *keys* differ structurally,
//! and those are folded constants (below), so they are an input too.
//!
//! # What the host computes, and why
//!
//! Four things never reach a shader, and [`Builder::host_tensor`] names each so
//! [`Builder::finish`] still refuses an *accidentally* unread tensor:
//!
//! * **The timestep conditioning.** A sinusoidal embedding of `current_step / total_step`, a
//!   two-layer MLP with a Mish in the middle, and then four `Linear`s to 512 numbers each — all
//!   a function of two scalars. That is `Sin`, `Cos`, `Softplus` and `Tanh` shaders for 2,048
//!   values, against a net that does 64M multiply-adds a position. The host evaluates it and
//!   passes `[2048, 1, 1]`, which [`Builder::slice_channels`] cuts into four per-channel shifts.
//! * **The rotary angles.** `(position / length) * theta`, so they depend on the two sequence
//!   lengths and on nothing learned. See [`super::Kind::Rotary`].
//! * **The style keys**, `tanh(W_key . style_key + b_key)`, entirely constant — and different per
//!   guidance branch *and* per main block, since the four style attentions share one `style_key`
//!   but each has its own `W_key`. Folding only the first was a real bug here: the net was exact
//!   through ten of its twenty-four sub-blocks and then correlated at 0.29.
//! * **The unconditional tokens**, which are the other branch's inputs.
//!
//! # Two scales that are not `1 / sqrt(head_dim)`
//!
//! Both attentions divide their scores by **16**. The text one has heads of 64 and the style one
//! heads of 128, so [`Builder::attn_scores`]'s own `1 / sqrt(head_dim)` is 2x and `sqrt(2)`x too
//! large respectively. The converter folds the difference into each `W_query`, which is exact —
//! and safe across the rotary, because a rotation commutes with a scalar.

//! # Measured parity
//!
//! Against onnxruntime on `denoised_latent` at 32 frames and 16 characters \u2014 the whole Euler step
//! over both guidance branches, so both plan runs and all the host arithmetic: correlation
//! 0.99999981, max 0.006019 on values reaching 5.13. Every one of the 25 stage boundaries inside
//! the net agrees to better than 0.99998.
//!
//! Getting there took one bisect. The first attempt folded a single style `W_key` and reused it
//! for all four style attentions; the net was then exact through `main_blocks.10` and correlated
//! at 0.29 from `main_blocks.11` on. A per-stage comparison found it in one run, which is much
//! faster than reasoning about a 1004-node graph.

use super::{Act, Builder, Id, Kind, Op, Plan, Shape, WeightSource};

/// Latent channels in and out. `ldim * chunk_compress_factor`, and the vocoder's `PACKED`.
pub const LATENT: u32 = 144;

/// Channels the stack works in. `vector_field.proj_in.odim`.
pub const CHANNELS: u32 = 512;

/// The ConvNeXt widening. `intermediate_dim`.
pub const INNER: u32 = 2048;

/// The text conditioning's width, which is the text encoder's output. `text_dim`.
pub const TEXT: u32 = 256;

/// The style conditioning's width. `style_dim`.
pub const STYLE: u32 = 256;

/// Style tokens each style cross-attention attends over. `n_style`.
pub const STYLE_TOKENS: u32 = 50;

/// Heads in the text cross-attention, so `head_dim` is 64. `text_cond_layer.n_heads`.
pub const TEXT_HEADS: u32 = 8;

/// Heads in the style cross-attention, so `head_dim` is 128.
pub const STYLE_HEADS: u32 = 2;

/// The timestep embedding's width. `time_encoder.time_dim`.
pub const TIME: u32 = 64;

/// The timestep MLP's inner width. `time_encoder.hdim`.
pub const TIME_INNER: u32 = 256;

/// Main blocks, each one four ConvNeXt blocks, a timestep shift, two conditionings and two more
/// ConvNeXt blocks.
pub const MAIN_BLOCKS: usize = 4;

/// ConvNeXt blocks in each main block's leading stack, at dilations 1, 2, 4 and 8.
const LEADING: [u32; 4] = [1, 2, 4, 8];

/// ConvNeXt blocks after the last main block, all at dilation 1. `last_convnext.num_layers`.
pub const TRAILING: usize = 4;

/// ConvNeXt blocks in the whole net: `4 * (4 + 1 + 1) + 4`.
pub const BLOCKS: usize = MAIN_BLOCKS * 6 + TRAILING;

/// The depthwise kernel width, `ksz`.
const KERNEL: u32 = 5;

/// The epsilon in all 36 layer norms.
const EPSILON: f32 = 1e-5;

/// Rotary frequencies, which is `head_dim / 2` of the text attention.
pub const FREQUENCIES: u32 = 32;

/// The guidance scale the export bakes in: `v = 4 * conditional - 3 * unconditional`.
pub const GUIDANCE: f32 = 4.0;

/// Tensors the plan itself reads: `proj_in`, 28 ConvNeXt blocks, four main blocks' conditioning,
/// and `proj_out`.
///
/// Ten per block rather than eight, and 25 per main block rather than 18, because every ungrouped
/// `1 x 1` here is int8 and carries a scale beside its kernel. See [`INT8_CONVS`].
pub const PLAN_TENSORS: usize = 3 + BLOCKS * 10 + MAIN_BLOCKS * 25 + 3;

/// Tensors the `.maml` must hold: the plan's, then the host's eighteen.
pub const TENSORS: usize = PLAN_TENSORS + 18;

/// Convolutions read as int8 rather than fp16, each carrying a third tensor for its scale.
///
/// **Every `1 x 1` in this net**: both projections, both `1 x 1`s of all 28 ConvNeXt blocks, and all
/// seven attention projections of each main block. Only the 28 depthwise convolutions stay fp16,
/// because they are grouped and edge-padded and `conv_int8.comp` is neither.
///
/// That is 92% of 63.8 million parameters, and it is why the tiled int8 shader had to exist before
/// any of this: `Node::ConvInt8` would otherwise lower to the untiled `conv_int8.comp`, and
/// [`crate::post::supertonic::synthesise`] runs this net `2 * STEPS` = 32 times per utterance. The
/// benchmark in `vulkan::parity` is what closed that off - the tiled int8 path measured at 0.98x of
/// the fp16 tiled one, so quantising costs no time here.
pub const INT8_CONVS: usize = 2 + BLOCKS * 2 + MAIN_BLOCKS * 7;

/// The rotary `theta`, `[32]`. `rotary_scale * rotary_base ^ (-j / 32)`.
pub const HOST_THETA: usize = PLAN_TENSORS;

/// The timestep embedding's sinusoidal frequencies, `[32]`.
pub const HOST_FREQUENCIES: usize = PLAN_TENSORS + 1;

/// The timestep MLP's first `Linear`, `[256, 64]` then `[256]`.
pub const HOST_MLP_IN: usize = PLAN_TENSORS + 2;

/// The timestep MLP's second `Linear`, `[64, 256]` then `[64]`.
pub const HOST_MLP_OUT: usize = PLAN_TENSORS + 4;

/// The four per-block timestep `Linear`s, `[512, 64]` and `[512]` each, in block order.
pub const HOST_TIME_LINEARS: usize = PLAN_TENSORS + 6;

/// `uncond_masker.text_special_token`, `[256]`, broadcast over every text position.
pub const HOST_TEXT_TOKEN: usize = PLAN_TENSORS + 14;

/// `uncond_masker.style_value_special_token`, `[256, 50]` and already transposed.
pub const HOST_STYLE_TOKEN: usize = PLAN_TENSORS + 15;

/// The folded conditional style keys, `[4 * 256, 50]` — one 256-channel block per main block.
pub const HOST_KEYS_CONDITIONAL: usize = PLAN_TENSORS + 16;

/// The folded unconditional style keys, `[4 * 256, 50]`.
pub const HOST_KEYS_UNCONDITIONAL: usize = PLAN_TENSORS + 17;

/// Hands out `.maml` tensor indices in the order the layers appear.
struct Layers {
    next: usize,
}

impl Layers {
    fn take(&mut self) -> usize {
        let index = self.next;
        self.next += 2;
        index
    }

    /// An int8 kernel, its per-output-channel scale, and the bias after that.
    ///
    /// See `supertonic_duration::Layers::take3`; the order is what `Builder::conv_int8` reads.
    fn take3(&mut self) -> usize {
        let index = self.next;
        self.next += 3;
        index
    }
}

/// A `1 x 1` convolution with an int8 kernel, which is every `1 x 1` here. See [`INT8_CONVS`].
fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv_int8(x, l.take3(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// One ConvNeXt block: depthwise, layer norm, widening 1x1 with a GELU, narrowing 1x1, residual.
///
/// Symmetric edge padding of `2 * dilation` each side, as in the text encoder. The block's
/// `[1, 512, 1]` gamma is folded into `pwconv2` by the converter.
fn convnext(b: &mut Builder, l: &mut Layers, x: Id, dilation: u32) -> Id {
    let each = dilation * (KERNEL - 1) / 2;
    let along = b.conv(
        x,
        l.take(),
        CHANNELS,
        (1, KERNEL),
        (1, 1),
        (1, dilation),
        (0, each, 0, each),
        CHANNELS,
        Act::None,
    );
    let normed = b.layer_norm(along, l.take(), EPSILON);
    let widened = point(b, l, normed, INNER, Act::Gelu);
    let narrowed = point(b, l, widened, CHANNELS, Act::None);
    b.add(x, narrowed)
}

/// Build one guidance branch of the sampler.
///
/// Seven inputs, in this order:
///
/// 0. `noisy_latent`, `[144, 1, frames]`
/// 1. the text conditioning, `[256, 1, chars]` — the text encoder's output, or the unconditional
///    token broadcast
/// 2. the folded style keys, `[1024, 1, 50]` — four stacked 256-channel blocks, one per main
///    block
/// 3. the style values, `[256, 1, 50]` — the voice's `style_ttl` transposed, or the unconditional
///    token
/// 4. the four timestep shifts, `[2048, 1, 1]`
/// 5. the query rotary angles, `[64, 1, frames]`
/// 6. the key rotary angles, `[64, 1, chars]`
///
/// The output is this branch's velocity, `[144, 1, frames]`. Combining the two branches and
/// taking the Euler step is [`crate::post::supertonic::step`].
pub fn build(weights: &dyn WeightSource, frames: u32, chars: u32) -> Result<Plan, String> {
    build_at(weights, frames, chars, &mut Layers { next: 0 })
}

/// One branch of [`build`], sharing `builder` and replaying `layers`.
///
/// [`build_dual`] runs both guidance branches in one plan and one submit. The branches share
/// every weight — the only structural difference is which style keys arrive as inputs — so the
/// second branch replays the same tensor indices rather than consuming new ones. `layers` is
/// the counter that hands those out, and rewinding it is what makes the replay land on the
/// same tensors instead of past the end of the file.
fn build_at(
    weights: &dyn WeightSource,
    frames: u32,
    chars: u32,
    layers: &mut Layers,
) -> Result<Plan, String> {
    let mut builder = Builder::new(weights);
    let velocity = branch(&mut builder, layers, frames, chars)?;
    branch_host_tensors(&mut builder);
    builder.finish(&[velocity])
}

/// Both guidance branches in one plan, for one submit instead of two.
///
/// Fourteen inputs — the seven of [`build`] twice, conditional branch first — and two
/// outputs, the two velocities in the same order. One `infer_raw_many` over fourteen slices
/// runs what were two submits, so a sampler step pays one `queue_submit` and one fence wait
/// rather than two. The GPU work is unchanged and still serial on this runtime's one queue;
/// what this removes is the host round trip per step, sixteen of them per utterance.
///
/// The branches share weights but not arena: each branch's tensors are allocated in order,
/// so the second branch's intermediates land past the first's and neither reads the other's.
/// The fusion fold in [`Builder::finish`] sees both branches' graphs at once and folds each
/// branch's residuals exactly as it would have alone.
///
/// `layers` replays between branches — see [`build_at`] — so `TENSORS` still covers the file:
/// no branch consumes a weight twice, and [`Builder::finish`]'s every-tensor-read rule holds
/// over the union.
pub fn build_dual(weights: &dyn WeightSource, frames: u32, chars: u32) -> Result<Plan, String> {
    if frames == 0 {
        return Err("a sampler pass over no frames".into());
    }
    if chars == 0 {
        return Err("a sampler pass over no characters".into());
    }
    let mut builder = Builder::new(weights);
    let mut layers = Layers { next: 0 };
    let conditional = branch(&mut builder, &mut layers, frames, chars)?;
    layers.next = 0;
    let unconditional = branch(&mut builder, &mut layers, frames, chars)?;
    branch_host_tensors(&mut builder);
    builder.finish(&[conditional, unconditional])
}

/// One branch's graph, with its seven inputs declared first.
///
/// Split out of [`build`] so [`build_dual`] can emit it twice. The inputs are declared here —
/// in branch order — so the dual plan's fourteen inputs are the conditional seven followed by
/// the unconditional seven, and the host uploads them in that order.
fn branch(
    b: &mut Builder,
    l: &mut Layers,
    frames: u32,
    chars: u32,
) -> Result<Id, String> {
    if frames == 0 {
        return Err("a sampler pass over no frames".into());
    }
    if chars == 0 {
        return Err("a sampler pass over no characters".into());
    }
    // Every padded convolution here replicates its border; all 28 `Pad`s are `mode=edge`.
    b.edge_padding();

    let latent = b.input(Shape::new(LATENT, 1, frames));
    let text = b.input(Shape::new(TEXT, 1, chars));
    let style_keys = b.input(Shape::new(STYLE * MAIN_BLOCKS as u32, 1, STYLE_TOKENS));
    let style_values = b.input(Shape::new(STYLE, 1, STYLE_TOKENS));
    let shifts = b.input(Shape::new(CHANNELS * MAIN_BLOCKS as u32, 1, 1));
    let query_angles = b.input(Shape::new(TEXT_HEADS * 2 * FREQUENCIES / TEXT_HEADS, 1, frames));
    let key_angles = b.input(Shape::new(2 * FREQUENCIES, 1, chars));

    // `proj_in` has no bias in the export; the converter synthesises a zero one.
    let mut x = point(b, l, latent, CHANNELS, Act::None);

    for block in 0..MAIN_BLOCKS {
        for &dilation in &LEADING {
            x = convnext(b, l, x, dilation);
        }

        // The timestep shift, one 512-vector per main block out of the host's `[2048, 1, 1]`.
        let shift = b.slice_channels(shifts, CHANNELS * block as u32, CHANNELS);
        x = b.add_channel(x, shift);

        x = convnext(b, l, x, 1);

        // The text cross-attention. Rotary on both sides, and the angles are normalised by each
        // sequence's own length so a query at the middle of the latent meets a key at the middle
        // of the text.
        let query = point(b, l, x, CHANNELS, Act::None);
        let query = b.rotary(query, query_angles, TEXT_HEADS);
        let keys = point(b, l, text, CHANNELS, Act::None);
        let keys = b.rotary(keys, key_angles, TEXT_HEADS);
        let values = point(b, l, text, CHANNELS, Act::None);
        let scores = b.attn_scores(query, keys, TEXT_HEADS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply(probs, values, TEXT_HEADS);
        let projected = point(b, l, mixed, CHANNELS, Act::None);
        let residual = b.add(x, projected);
        x = b.layer_norm(residual, l.take(), EPSILON);

        x = convnext(b, l, x, 1);

        // The style cross-attention.

        // The style cross-attention. Its keys arrive already through `tanh`, folded, and each
        // main block has its own: they share one `style_key` but not one `W_key`.
        let keys = b.slice_channels(style_keys, STYLE * block as u32, STYLE);
        let query = point(b, l, x, STYLE, Act::None);
        let values = point(b, l, style_values, STYLE, Act::None);
        let scores = b.attn_scores(query, keys, STYLE_HEADS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply(probs, values, STYLE_HEADS);
        let projected = point(b, l, mixed, CHANNELS, Act::None);
        let residual = b.add(x, projected);
        x = b.layer_norm(residual, l.take(), EPSILON);
    }

    for _ in 0..TRAILING {
        x = convnext(b, l, x, 1);
    }

    // `proj_out` has no bias either.
    let velocity = point(b, l, x, LATENT, Act::None);

    if l.next != PLAN_TENSORS {
        return Err(format!("the forward pass claims {} tensors, not {PLAN_TENSORS}", l.next));
    }
    Ok(velocity)
}

/// The host-owned tensors, named once per plan rather than once per branch.
///
/// [`build`] calls this after its single branch; [`build_dual`] after both. Naming them
/// twice would be harmless — `host_tensor` only marks the read flag — but once states the
/// invariant once: these eighteen are read on the host, whichever branch runs.
fn branch_host_tensors(b: &mut Builder) {
    // Named rather than skipped: see `Builder::host_tensor`.
    b.host_tensor(HOST_THETA, &[FREQUENCIES]);
    b.host_tensor(HOST_FREQUENCIES, &[FREQUENCIES]);
    b.host_tensor(HOST_MLP_IN, &[TIME_INNER, TIME]);
    b.host_tensor(HOST_MLP_IN + 1, &[TIME_INNER]);
    b.host_tensor(HOST_MLP_OUT, &[TIME, TIME_INNER]);
    b.host_tensor(HOST_MLP_OUT + 1, &[TIME]);
    for block in 0..MAIN_BLOCKS {
        b.host_tensor(HOST_TIME_LINEARS + block * 2, &[CHANNELS, TIME]);
        b.host_tensor(HOST_TIME_LINEARS + block * 2 + 1, &[CHANNELS]);
    }
    b.host_tensor(HOST_TEXT_TOKEN, &[TEXT]);
    b.host_tensor(HOST_STYLE_TOKEN, &[STYLE, STYLE_TOKENS]);
    b.host_tensor(HOST_KEYS_CONDITIONAL, &[STYLE * MAIN_BLOCKS as u32, STYLE_TOKENS]);
    b.host_tensor(HOST_KEYS_UNCONDITIONAL, &[STYLE * MAIN_BLOCKS as u32, STYLE_TOKENS]);
}

include!("supertonic_sampler_part1.rs");
include!("supertonic_sampler_part2.rs");