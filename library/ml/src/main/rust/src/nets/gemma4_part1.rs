/// Which pass [`build`] emits.
///
/// One so far. A prefill over many positions at once would be a second, and is what the
/// `[C, 1, T]` shapes throughout leave room for.
/// A pass and the cache length it is recorded against.
///
/// The cache length has to be part of what [`crate::vulkan::reshape::Reshaped`] keys on, not a
/// value the plan function closes over: it holds a bare `fn` pointer, and more importantly every
/// pass sharing one recording must agree about where the caches are. Bundling them makes a
/// mismatch impossible to express rather than merely unlikely.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Pass {
    /// Which pass to emit.
    pub mode: Mode,
    /// Positions the KV caches hold. One of [`CONTEXT_TIERS`].
    pub context: u32,
}

impl Mode {
    /// This pass at a cache length. `Mode::DecodeStep.at(4096)`.
    pub const fn at(self, context: u32) -> Pass {
        Pass { mode: self, context }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// One decoder step: one token in, its logits out.
    ///
    /// Carries no step number, so a whole generation is one recording. The live cache length and
    /// each layer's window arrive in [`crate::vulkan::run::StepParams`] at submit time - see
    /// [`crate::nets::Builder::persistent`] and the note on windows below.
    DecodeStep,
    /// Stop after `layers` layers and hand back the hidden state and the per-layer block.
    ///
    /// For `examples/check_gemma4_parity.rs`, which bisects a wrong forward pass by comparing
    /// intermediates against onnxruntime. A composition error - a residual on the wrong side of
    /// a norm, a cache written at the wrong position - reaches the logits as a plausible ranking
    /// and is invisible from the outside; the only way to find it is to ask where the two first
    /// disagree.
    ///
    /// `layers` of 0 stops before the first layer, which checks the embedding and the per-layer
    /// combination on their own.
    Trace { layers: usize },
    /// Run layer 0 only and hand back its four tail branches: the attention branch
    /// (post-`post_attention_norm`, pre-residual), the MLP branch (post-`post_ffw_norm`,
    /// pre-residual), the per-layer branch (post-`post_per_layer_input_norm`, pre-residual),
    /// and the layer output (post-`skip`).
    ///
    /// For `examples/check_gemma4_parity.rs`, which bisects the L0 device-vs-numpy gap by
    /// comparing each branch against the numpy-fp oracle (`build/gemma4/l0_tail.json`):
    /// the first branch where the device drops below 0.99 while numpy holds is the
    /// divergence point. Computed by the same op sequence as [`layer`](super::layer),
    /// so the KV cache it fills matches a decode step exactly.
    TraceTail,
    /// Run every layer to fill the KV cache, with **no logits head**. `tokens` positions at once.
    ///
    /// # Why the head is skipped rather than ignored
    ///
    /// A prompt is pushed one position at a time and the logits are discarded for all of them but
    /// the last: `bridge.rs` checks `want_logits` *after* the forward pass, and the pass is
    /// [`Mode::DecodeStep`], which always runs the head. So the head is evaluated for every
    /// position of the prompt and the result is thrown away on the host.
    ///
    /// That is not a rounding error. The head is four int4 splits of `[CLASSES_PER_SPLIT,
    /// D_MODEL]` plus their scale tables - 227 MB of the file's 1.30 GB, **17.5% of the weight
    /// bytes**, against 9 of the plan's 1,094 ops. The op share is the misleading one: a "283 us
    /// per op" average hides that these four are gemvs reading 50 MB each, so their cost tracks
    /// bytes and not dispatch count.
    ///
    /// The last position of a prompt still needs its logits and uses [`Mode::DecodeStep`].
    ///
    /// # `tokens`
    ///
    /// One today. The batched path is what removes the per-dispatch cost that dominates prefill,
    /// and it needs a per-query sliding mask that no existing softmax expresses - 28 of 35 layers
    /// slide over [`WINDOW`] - and a transposing cache write, since `[kv_width, 1, N]` into a
    /// position-major cache is a real transpose rather than the free reshape it is at one
    /// position. `build` refuses anything above one rather than recording a plan that would
    /// attend as though it were single-position.
    Prefill { tokens: u32 },
    /// [`Mode::Prefill`] for a chunk that starts at position 0. Nothing precedes it, so its
    /// attention runs over the chunk's own keys with the tiled `T x T` kernels, which is faster
    /// than the cache-wide multi-query path. Wrong at any other position.
    PrefillFresh { tokens: u32 },
}

/// Plan inputs, in declaration order.
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0 | `[1536, 1, 1]` | the token's embedding, gathered on the host |
/// | 1 | `[8960, 1, 1]` | the token's COMBINED per-layer block, also gathered on the host |
/// | 2 | `[256, 1, 1]` | rotary angles for a sliding layer at this position |
/// | 3 | `[512, 1, 1]` | rotary angles for a full layer at this position |
///
/// Input 1 is combined - not the raw table concat - because the shared projection
/// lives in the EMBED file (see [`super::embed::SHARED_PROJ`]) and a pass reads one
/// weights file, not two: [`super::gather`] projects, norms and mixes on the host.
///
/// The embedding and the per-layer table are host gathers for the same reason NLLB's tied
/// embedding is: they are one row of a very large table, and a shader would have to bind the
/// whole thing. `embed_tokens.onnx` is literally two `Gather`s, so nothing is lost.
///
/// The rotary angles are one row of [`ROTARY_LOCAL`] or [`ROTARY_GLOBAL`], which the converter
/// has already put in the cosine-then-sine layout [`super::Builder::rotary`] expects.
pub const INPUTS: usize = 4;

/// Build the decode pass.
///
/// # Windows
///
/// Sliding and full layers want different attended ranges from the same submit, and
/// [`crate::vulkan::run::StepParams`] carries one `window_start`. Until it carries a per-layer
/// one, a plan built here uses the **sliding** window for every layer that slides and relies on
/// the host setting `window_start` to `prefix.saturating_sub(WINDOW - 1)`; the full layers pass
/// `dynamic` too but must see the whole prefix. That is the one piece of this that does not yet
/// have a home, and it is why [`Mode`] has a single variant rather than two.
pub fn build(weights: &dyn WeightSource, pass: Pass) -> Result<Plan, String> {
    let Pass { mode, context } = pass;
    if !CONTEXT_TIERS.contains(&context) {
        return Err(format!("a cache of {context} positions, which is not one of the tiers"));
    }
    let stop_after = match mode {
        Mode::DecodeStep => LAYERS,
        // Prefill runs every layer; what it skips is the head, not the depth.
        Mode::Prefill { tokens: 0 } | Mode::PrefillFresh { tokens: 0 } => {
            return Err("a prefill of no positions".into())
        }
        Mode::Prefill { tokens } | Mode::PrefillFresh { tokens } if tokens <= context => LAYERS,
        Mode::Prefill { tokens } | Mode::PrefillFresh { tokens } => {
            return Err(format!("a prefill of {tokens} positions, past a {context} cache"))
        }
        Mode::Trace { layers } if layers <= LAYERS => layers,
        Mode::Trace { layers } => return Err(format!("a trace of {layers} of {LAYERS} layers")),
        // Layer 0 only; the branches come from that one layer.
        Mode::TraceTail => 1,
    };
    let mut builder = Builder::new(weights);
    let b = &mut builder;

    // One cache pair per owning layer, sized for the whole context and held on the device.
    //
    // **Declared before the inputs, and that order is load-bearing.** [`Builder::finish`] assigns
    // arena offsets by walking its `pinned` list in order, and both `input` and `persistent` push
    // onto it - so a pinned tensor's offset is the running total of every pinned tensor declared
    // ahead of it. The inputs are the only pinned tensors whose size depends on the sequence
    // length. The caches are `MAX_CONTEXT`-sized whatever it is. Declaring the caches first is
    // therefore what gives them the same offsets in *every* plan built from this module, and that
    // is what lets one plan fill a cache and a differently-shaped one read it.
    //
    // With the inputs first they do not line up. At `T = N` the four inputs occupy
    // `(D_MODEL + PER_LAYER * LAYERS + HEAD_DIM + GLOBAL_HEAD_DIM) * N` elements - 11,264 per
    // position - so every cache behind them shifts by 11,264 * (N - 1). Two plans sharing one
    // arena would then disagree about where the caches are, and because the arena is a single
    // buffer that is not a read of uninitialised memory: it is a read of the other plan's live
    // activations. No crash, no shape error, no NaN. Fluent, wrong output.
    let caches: Vec<(Id, Id)> = (0..OWNS_CACHE_LAYERS)
        .map(|index| {
            let width = KV_HEADS * head_dim(index);
            // Only as long as this conversation has grown to need.
            let k = b.persistent(Shape::new(context, 1, width));
            let v = b.persistent(Shape::new(context, 1, width));
            (k, v)
        })
        .collect();

    // Positions this pass covers. One for a decode step; a whole prompt for a batched prefill,
    // which is the difference between reading 1.3 GB of weights per token and reading it once
    // for all of them.
    let width = match mode {
        Mode::Prefill { tokens } | Mode::PrefillFresh { tokens } => tokens,
        _ => 1,
    };
    let mut x = b.input(Shape::new(D_MODEL, 1, width));
    // Combined by the host, or raw rows this plan combines (see `combines_in_plan`).
    let per_layer_in = b.input(Shape::new(PER_LAYER * LAYERS as u32, 1, width));
    let angles_local = b.input(Shape::new(HEAD_DIM, 1, width));
    let angles_global = b.input(Shape::new(GLOBAL_HEAD_DIM, 1, width));
    let fused = combines_in_plan(weights);
    let per_layer_inputs = if fused { combine_in_plan(b, x, per_layer_in) } else { per_layer_in };
    // The rotary tables. Read on the host, a row at a time, and handed back as the two angle
    // inputs above - the same arrangement as the embedding.
    b.host_tensor(ROTARY_LOCAL, &[MAX_CONTEXT, HEAD_DIM]);
    b.host_tensor(ROTARY_GLOBAL, &[MAX_CONTEXT, GLOBAL_HEAD_DIM]);
    // The shared all-ones vectors, `[256]` and `[512]` (see [`ONE_SLIDING`]).
    b.host_tensor(ONE_SLIDING, &[HEAD_DIM]);
    b.host_tensor(ONE_FULL, &[GLOBAL_HEAD_DIM]);

    // TraceTail runs layer 0 through `layer_tail`, which returns the four branch
    // intermediates alongside the output. Every other mode runs `layer`.
    let mut tail: Option<[Id; 4]> = None;
    // A fresh prefill attends its own chunk, so the owning layers' K and V are carried forward
    // to the shared layers as tensors (the `cache_source` mapping, resolved to tensors).
    let fresh = matches!(mode, Mode::PrefillFresh { .. });
    let (mut sliding_kv, mut full_kv): (Option<(Id, Id)>, Option<(Id, Id)>) = (None, None);
    for index in 0..stop_after {
        let angles = if is_full_attention(index) { angles_global } else { angles_local };
        let (cache_k, cache_v) = *caches
            .get(cache_source(index))
            .ok_or_else(|| format!("layer {index} reads cache {}", cache_source(index)))?;
        if matches!(mode, Mode::TraceTail) {
            tail = Some(layer_tail(b, x, per_layer_inputs, angles, cache_k, cache_v)?);
            x = tail.map(|t| t[3]).unwrap_or(x);
            continue;
        }
        if width == 1 {
            x = layer(b, index, x, per_layer_inputs, angles, cache_k, cache_v)?;
            continue;
        }
        let full = is_full_attention(index);
        let carried = if full { full_kv } else { sliding_kv };
        let attend = if fresh { Attend::Chunk(carried) } else { Attend::Cache };
        let (next, kv) = prefill_layer(b, index, x, per_layer_inputs, angles, cache_k, cache_v, attend)?;
        x = next;
        if let Some(kv) = kv {
            if full {
                full_kv = Some(kv);
            } else {
                sliding_kv = Some(kv);
            }
        }
    }

    if let Mode::TraceTail = mode {
        // Layers 1..35 are named rather than evaluated, exactly as `Trace` does.
        for index in stop_after..LAYERS {
            declare_layer(weights, index)?;
            name_layer(b, index);
        }
        name_head(b);
        let Some([attn_branch, mlp_branch, ple_branch, out]) = tail else {
            return Err("a tail trace with no layer 0".into());
        };
        return builder.finish(&[attn_branch, mlp_branch, ple_branch, out]);
    }

    if let Mode::Trace { .. } = mode {
        // Every tensor the untraced pass would have read still has to be read, or `finish`
        // refuses the plan - so the layers this stops short of, and the head, are named rather
        // than evaluated.
        for index in stop_after..LAYERS {
            declare_layer(weights, index)?;
            name_layer(b, index);
        }
        name_head(b);
        return builder.finish(&[x, per_layer_inputs]);
    }

    if let Mode::Prefill { .. } | Mode::PrefillFresh { .. } = mode {
        // Prefill runs every layer - the point is the caches those layers fill - and then the
        // final norm, so the host can run the head on the last column: a prompt's final token
        // rides its prefill instead of costing a decode step of its own. The other columns'
        // states are read back and ignored.
        let state = b.rms_norm(x, FINAL_NORM, EPSILON);
        return builder.finish(&[state]);
    }

    let state = b.rms_norm(x, FINAL_NORM, EPSILON);
    // Tied head: litertlm stores no logits head, so logits are hidden @ E^T with
    // E the working embedding table - computed ON THE HOST in bridge_part6
    // (host tied-head SGEMV over the embed file's int4 table, then softcap).
    // The device returns the normed hidden state; this keeps 201 MB of head
    // weights out of the text file and out of VRAM. A Vulkan tied-head GEMM
    // (chunked over vocab) is the follow-up; host SGEMV is the correct v1.
    builder.finish(&[state])
}

/// Name the final norm as host-read, for the modes that stop before the head.
///
/// [`Mode::Trace`] and [`Mode::Prefill`] both end after a layer rather than after the
/// norm, and `Builder::finish` refuses a plan that leaves a tensor unread.
fn name_head(b: &mut Builder) {
    b.host_tensor(FINAL_NORM, &[D_MODEL]);
}

/// Name every tensor of layer `index` as host-read, for [`Mode::Trace`].
///
/// A trace stops early, so the layers after it are never evaluated - but `Builder::finish`
/// refuses a plan that leaves a tensor unread, and rightly so. This says "the host owns these",
/// which keeps that invariant meaningful for the layers the trace *does* run.
///
/// Mirrors [`super::declare_layer`] tensor for tensor: split gate/ff1/linear, no
/// `v_norm`, plus the trailing `skip` scalar. A shape here that drifted from the
/// declaration would name a tensor the file does not hold at that index.
fn name_layer(b: &mut Builder, index: usize) {
    let dim = head_dim(index);
    let inner = ffn(index);
    let blocks = |inp: u32| inp.div_ceil(crate::weights::I4_BLOCK);
    let mut next = layer_at(index);
    let mut plain = |b: &mut Builder, n: &mut usize, dims: &[u32]| {
        b.host_tensor(*n, dims);
        *n += 1;
    };
    let mut proj4 = |b: &mut Builder, n: &mut usize, out: u32, inp: u32| {
        b.host_tensor(*n, &[out, inp, 1, 1]);
        b.host_tensor(*n + 1, &[out, blocks(inp)]);
        b.host_tensor(*n + 2, &[out]);
        *n += PROJECTION_TENSORS;
    };
    let mut proj8 = |b: &mut Builder, n: &mut usize, out: u32, inp: u32| {
        b.host_tensor(*n, &[out, inp, 1, 1]);
        b.host_tensor(*n + 1, &[out]);
        b.host_tensor(*n + 2, &[out]);
        *n += PROJECTION_TENSORS;
    };
    plain(b, &mut next, &[D_MODEL]);
    plain(b, &mut next, &[dim]);
    proj4(b, &mut next, HEADS * dim, D_MODEL);
    if owns_cache(index) {
        plain(b, &mut next, &[dim]);
        proj4(b, &mut next, KV_HEADS * dim, D_MODEL);
        proj4(b, &mut next, KV_HEADS * dim, D_MODEL);
    }
    proj4(b, &mut next, D_MODEL, HEADS * dim);
    plain(b, &mut next, &[D_MODEL]);
    plain(b, &mut next, &[D_MODEL]);
    proj4(b, &mut next, inner, D_MODEL);
    proj4(b, &mut next, inner, D_MODEL);
    proj4(b, &mut next, D_MODEL, inner);
    plain(b, &mut next, &[D_MODEL]);
    proj8(b, &mut next, PER_LAYER, D_MODEL);
    proj8(b, &mut next, D_MODEL, PER_LAYER);
    plain(b, &mut next, &[D_MODEL]);
    plain(b, &mut next, &[1]);
    debug_assert_eq!(next, layer_at(index + 1), "layer {index} names past its span");
}

/// One decoder layer over `width` positions at once.
///
/// # Why this is not `layer` with a width argument
///
/// The attention is a different shape of computation, not the same one wider. A decode step is
/// one query against a cache and reads its keys from that cache; a prefill is T queries against
/// T keys held as tensors, masked causally. Those use different ops, different softmaxes and a
/// different source for K and V. Folding them into one function would be four branches around
/// every line of the attention block, and the decode path is the one that is already verified
/// correct against onnxruntime - so it is left exactly as it was.
///
/// Everything either side of the attention *is* the same computation wider, and is shared by
/// being written the same way: every projection, norm, activation and residual here is an
/// elementwise or per-position op that already took a `[C, 1, T]` shape.
///
/// Returns the layer's output and, for a layer that owns a cache, its `(K, V)` so the shared
/// layers after it can attend over them in [`Attend::Chunk`].
#[allow(clippy::too_many_arguments)]
fn prefill_layer(
    b: &mut Builder,
    index: usize,
    x: Id,
    per_layer_inputs: Id,
    angles: Id,
    cache_k: Id,
    cache_v: Id,
    attend: Attend,
) -> Result<(Id, Option<(Id, Id)>), String> {
    let dim = head_dim(index);
    let inner = ffn(index);
    let at = layer_at(index);
    let mut next = at;
    let mut plain = |n: &mut usize| {
        let here = *n;
        *n += 1;
        here
    };
    let mut proj = |n: &mut usize| {
        let here = *n;
        *n += PROJECTION_TENSORS;
        here
    };

    let input_norm = plain(&mut next);
    let q_norm_at = plain(&mut next);
    let q_proj = proj(&mut next);
    let (k_norm_at, k_proj, v_proj) = if owns_cache(index) {
        (Some(plain(&mut next)), Some(proj(&mut next)), Some(proj(&mut next)))
    } else {
        (None, None, None)
    };
    let o_proj = proj(&mut next);
    let post_attention = plain(&mut next);
    let pre_ff = plain(&mut next);
    let gate_proj = proj(&mut next);
    let ff1_proj = proj(&mut next);
    let down = proj(&mut next);
    let post_ff = plain(&mut next);
    let gate_at = proj(&mut next);
    let projection_at = proj(&mut next);
    let post_per_layer = plain(&mut next);
    let skip_at = plain(&mut next);
    if next != layer_at(index + 1) {
        return Err(format!("layer {index} read {} tensors", next - at));
    }

    let normed = b.rms_norm(x, input_norm, EPSILON);
    // The live int8 round-trips, as in [`super::layer`]: prefill computes the
    // same mixtures a decode does, so they quantize identically.
    let normed_q = b.quantize(normed, QI_SCALE[index]);
    let q = point(b, q_proj, normed_q, HEADS * dim);
    let q = b.quantize(q, QO_SCALE[index]);
    let q = b.rms_norm_grouped(q, q_norm_at, EPSILON, HEADS);
    let q = b.rotary(q, angles, HEADS);

    let mut mine = None;
    if let (Some(k_norm_at), Some(k_proj), Some(v_proj)) = (k_norm_at, k_proj, v_proj) {
        let k = point(b, k_proj, normed_q, KV_HEADS * dim);
        let k = b.quantize(k, KO_SCALE[index]);
        let k = b.rms_norm_grouped(k, k_norm_at, EPSILON, KV_HEADS);
        let k = b.rotary(k, angles, KV_HEADS);
        let v = point(b, v_proj, normed_q, KV_HEADS * dim);
        let v = b.quantize(v, VO_SCALE[index]);
        // S10's `value_norm`, as in [`super::layer`]: a parameter-free
        // RMS norm (all-ones gamma, [`ONE_SLIDING`]/[`ONE_FULL`]) before
        // the cache write. The decode path must read what this wrote.
        let ones = if is_full_attention(index) { ONE_FULL } else { ONE_SLIDING };
        let v = b.rms_norm(v, ones, EPSILON);
        // Positions `prefix .. prefix + T`, transposed into the position-major cache.
        b.cache_write(k, cache_k);
        b.cache_write(v, cache_v);
        mine = Some((k, v));
    }

    let slides = !is_full_attention(index);
    let mixed = match attend {
        // Every query attends the cache - the positions before this chunk and the chunk
        // itself, which the owning layer just wrote. The scale is already inside `q_norm`.
        Attend::Cache => {
            b.attn_cached_multi(q, cache_k, cache_v, HEADS, KV_HEADS, if slides { WINDOW } else { 0 })
        }
        Attend::Chunk(carried) => {
            let (k, v) = mine
                .or(carried)
                .ok_or_else(|| format!("layer {index} has no keys and none were carried forward"))?;
            let scores = b.attn_scores_grouped_prescaled(q, k, HEADS, KV_HEADS);
            let probs = b.softmax_causal_windowed(scores, if slides { WINDOW } else { 0 });
            b.attn_apply_grouped(probs, v, HEADS, KV_HEADS)
        }
    };
    // The live int8 round-trip, as in [`super::layer`]: prefill fills the same
    // caches a decode reads, so its mixtures quantize identically.
    let mixed = b.quantize(mixed, MM_SCALE[index]);
    let attended = point(b, o_proj, mixed, D_MODEL);
    let attended = b.quantize(attended, O_SCALE[index]);
    let attended = b_rms(b, attended, post_attention);
    let x = b.add(x, attended);

    let ff_in = b.rms_norm(x, pre_ff, EPSILON);
    // Split gate + up (litertlm stores them separately, not fused): gelu(gate) * up,
    // the same GeGLU the fused path computed via one gated_activate. The GELU
    // folds into the gate projection's store (single consumer: the mul below).
    let gelu_gate = point_act(b, gate_proj, ff_in, inner, Act::Gelu);
    let up = point(b, ff1_proj, ff_in, inner);
    let gated = b.mul(gelu_gate, up);
    let ff = point(b, down, gated, D_MODEL);
    let ff = b_rms(b, ff, post_ff);
    let x = b.add(x, ff);

    let mine_pl = b.slice_channels(per_layer_inputs, index as u32 * PER_LAYER, PER_LAYER);
    let gated_in = point8_act(b, gate_at, x, PER_LAYER, Act::Gelu);
    let combined = b.mul(gated_in, mine_pl);
    let projected = point8(b, projection_at, combined, D_MODEL);
    let branch = b_rms(b, projected, post_per_layer);
    let x = b.add(x, branch);
    let x = b.mul_scalar(x, skip_at);
    // The whole residual is scaled by `skip` after add2, as in [`super::layer`].
    Ok((x, mine))
}

/// A `1 x 1` int4 convolution, which every large projection in this net is.
fn point(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    point_act(b, at, x, out, Act::None)
}

/// [`point`] with a fused activation: the gate projections' GELU folds into
/// the convolution's store (`fused_store` in `common.glsl`) instead of a
/// separate `activate` dispatch. Only for single-consumer sites — the fused
/// value replaces the projection output, so nothing else may read it.
fn point_act(b: &mut Builder, at: usize, x: Id, out: u32, act: Act) -> Id {
    b.conv_int4(x, at, out, act)
}

/// A `1 x 1` **int8** convolution, for the two per-layer projections. See [`projection8`].
fn point8(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    point8_act(b, at, x, out, Act::None)
}

/// [`point8`] with a fused activation (see [`point_act`]).
fn point8_act(b: &mut Builder, at: usize, x: Id, out: u32, act: Act) -> Id {
    b.conv_int8(x, at, out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}
