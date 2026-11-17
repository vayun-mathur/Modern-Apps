/// One conformer layer: `FFN(1/2) -> attention -> conv -> FFN(1/2) -> norm`.
fn layer(b: &mut Builder, index: usize, x: Id) -> Result<Id, String> {
    let at = layer_at(index);
    let mut next = at;
    let one = |n: &mut usize| {
        let here = *n;
        *n += 1;
        here
    };
    let proj = |n: &mut usize| {
        let here = *n;
        *n += PROJECTION_TENSORS;
        here
    };

    let ff1_pre = one(&mut next);
    let ff1_clip_in = one(&mut next);
    let ff1_up = proj(&mut next);
    let ff1_clip_up = one(&mut next);
    let ff1_clip_act = one(&mut next);
    let ff1_down = proj(&mut next);
    let ff1_clip_out = one(&mut next);
    let ff1_post = one(&mut next);
    let pre_attn = one(&mut next);
    let clip_qkv = one(&mut next);
    let q_proj = proj(&mut next);
    let k_proj = proj(&mut next);
    let v_proj = proj(&mut next);
    let clip_q = one(&mut next);
    let clip_k = one(&mut next);
    let clip_v = one(&mut next);
    let query_scale = one(&mut next);
    let relative = one(&mut next);
    let clip_attn = one(&mut next);
    let post_proj = proj(&mut next);
    let clip_post = one(&mut next);
    let post_attn = one(&mut next);
    let lconv_pre = one(&mut next);
    let lconv_clip_in = one(&mut next);
    let lconv_gate = proj(&mut next);
    let lconv_clip_gate = one(&mut next);
    let depthwise = one(&mut next);
    let _depthwise_bias = one(&mut next);
    let conv_norm = one(&mut next);
    let lconv_clip_act = one(&mut next);
    let lconv_exit = proj(&mut next);
    let lconv_clip_out = one(&mut next);
    let ff2_pre = one(&mut next);
    let ff2_clip_in = one(&mut next);
    let ff2_up = proj(&mut next);
    let ff2_clip_up = one(&mut next);
    let ff2_clip_act = one(&mut next);
    let ff2_down = proj(&mut next);
    let ff2_clip_out = one(&mut next);
    let ff2_post = one(&mut next);
    let norm_out = one(&mut next);
    if next != layer_at(index + 1) {
        return Err(format!("audio layer {index} read {} tensors", next - at));
    }

    let x = feed_forward(
        b, x, ff1_pre, ff1_clip_in, ff1_up, ff1_clip_up, ff1_clip_act, ff1_down, ff1_clip_out,
        ff1_post,
    );

    // Attention. q and k are fully scaled here, so the score op is passed 1.0: the query takes a
    // per-channel vector the converter folded `Q_SCALE` into, and the key a scalar. Neither can
    // live in the op - a vector does not factor out of a dot product, and the key's scalar
    // applies to the content term but not to the relative one.
    let normed = b.rms_norm(x, pre_attn, EPSILON);
    let normed = b.clamp(normed, clip_qkv);
    let q = point(b, q_proj, normed, HEADS * HEAD_DIM);
    let q = b.clamp(q, clip_q);
    let k = point(b, k_proj, normed, HEADS * HEAD_DIM);
    let k = b.clamp(k, clip_k);
    let v = point(b, v_proj, normed, HEADS * HEAD_DIM);
    let v = b.clamp(v, clip_v);
    let scale = b.constant(query_scale, Shape::new(D_MODEL, 1, 1));
    let q = b.mul_channel(q, scale);
    let k = b.affine(k, K_SCALE, 0.0);
    // The cap is fused into the scores op, before its own masking sentinel; see `LOGIT_CAP`.
    let scores =
        b.attn_scores_banded(q, k, crate::nets::BandedScores { heads: HEADS, band: ATTEND_SPAN, table: relative, offsets: REL_OFFSETS, scale: 1.0, cap: LOGIT_CAP });
    let probs = b.softmax(scores);
    let mixed = b.attn_apply_banded(probs, v, HEADS, ATTEND_SPAN);
    let mixed = b.clamp(mixed, clip_attn);
    let attended = point(b, post_proj, mixed, D_MODEL);
    let attended = b.clamp(attended, clip_post);
    let attended = b.rms_norm(attended, post_attn, EPSILON);
    // Plain add: the half belongs to the feed-forwards only.
    let x = b.add(x, attended);

    // The light convolution module, `norm -> gate -> GLU -> causal conv -> norm -> silu -> exit`.
    let gated = b.rms_norm(x, lconv_pre, EPSILON);
    let gated = b.clamp(gated, lconv_clip_in);
    let gated = point(b, lconv_gate, gated, LCONV_GATE);
    let gated = b.clamp(gated, lconv_clip_gate);
    let value = b.slice_channels(gated, 0, D_MODEL);
    let gate = b.slice_channels(gated, D_MODEL, D_MODEL);
    let gate = b.activate(gate, Act::Sigmoid);
    let gated = b.mul(value, gate);
    // Causal: four taps of left pad and none of right, over the width axis.
    let gated = b.conv(
        gated,
        depthwise,
        D_MODEL,
        (1, CONV_KERNEL),
        (1, 1),
        (1, 1),
        (0, CONV_LEFT_PAD, 0, 0),
        D_MODEL,
        Act::None,
    );
    let gated = b.rms_norm(gated, conv_norm, EPSILON);
    let gated = b.activate(gated, Act::Swish);
    let gated = b.clamp(gated, lconv_clip_act);
    let gated = point(b, lconv_exit, gated, D_MODEL);
    let gated = b.clamp(gated, lconv_clip_out);
    let x = b.add(x, gated);

    let x = feed_forward(
        b, x, ff2_pre, ff2_clip_in, ff2_up, ff2_clip_up, ff2_clip_act, ff2_down, ff2_clip_out,
        ff2_post,
    );
    Ok(b.rms_norm(x, norm_out, EPSILON))
}

/// Declare every tensor of a layer [`build`] skipped, so [`Builder::finish`] still sees it read.
///
/// A [`Mode::Trace`] or [`Mode::Sscp`] stops early on purpose, and an unread tensor is otherwise
/// exactly what a forward pass that lost a layer looks like from the outside.
fn name_layer(b: &mut Builder, index: usize) {
    let at = layer_at(index);
    let mut next = at;
    let one = |b: &mut Builder, n: &mut usize, dims: &[u32]| {
        b.host_tensor(*n, dims);
        *n += 1;
    };
    let proj = |b: &mut Builder, n: &mut usize, out: u32, inp: u32| {
        b.host_tensor(*n, &[out, inp, 1, 1]);
        b.host_tensor(*n + 1, &[out, inp.div_ceil(crate::weights::I4_BLOCK)]);
        b.host_tensor(*n + 2, &[out]);
        *n += PROJECTION_TENSORS;
    };
    let half = |b: &mut Builder, n: &mut usize| {
        one(b, n, &[D_MODEL]);
        one(b, n, &[2]);
        proj(b, n, FFN, D_MODEL);
        one(b, n, &[2]);
        one(b, n, &[2]);
        proj(b, n, D_MODEL, FFN);
        one(b, n, &[2]);
        one(b, n, &[D_MODEL]);
    };

    half(b, &mut next);
    one(b, &mut next, &[D_MODEL]);
    one(b, &mut next, &[2]);
    for _ in 0..3 {
        proj(b, &mut next, HEADS * HEAD_DIM, D_MODEL);
    }
    for _ in 0..3 {
        one(b, &mut next, &[2]);
    }
    one(b, &mut next, &[D_MODEL, 1, 1]);
    one(b, &mut next, &[HEADS, REL_OFFSETS, HEAD_DIM]);
    one(b, &mut next, &[2]);
    proj(b, &mut next, D_MODEL, HEADS * HEAD_DIM);
    one(b, &mut next, &[2]);
    one(b, &mut next, &[D_MODEL]);
    one(b, &mut next, &[D_MODEL]);
    one(b, &mut next, &[2]);
    proj(b, &mut next, LCONV_GATE, D_MODEL);
    one(b, &mut next, &[2]);
    one(b, &mut next, &[D_MODEL, 1, 1, CONV_KERNEL]);
    one(b, &mut next, &[D_MODEL]);
    one(b, &mut next, &[D_MODEL]);
    one(b, &mut next, &[2]);
    proj(b, &mut next, D_MODEL, D_MODEL);
    one(b, &mut next, &[2]);
    half(b, &mut next);
    one(b, &mut next, &[D_MODEL]);
    debug_assert_eq!(next, layer_at(index + 1), "named {} tensors", next - at);
}

/// Declare the tail tensors a [`Mode::Sscp`] pass never reaches.
fn name_tail(b: &mut Builder) {
    b.host_tensor(OUT_PROJECTION, &[OUT_DIM, D_MODEL, 1, 1]);
    b.host_tensor(OUT_PROJECTION + 1, &[OUT_DIM]);
    b.host_tensor(EMBED_NORM, &[OUT_DIM]);
    b.host_tensor(EMBED_PROJECTION, &[OUT_DIM, OUT_DIM, 1, 1]);
    b.host_tensor(EMBED_PROJECTION + 1, &[OUT_DIM]);
}

/// The shape the export holds for a kernel this module declares as `dims`.
///
/// The inverse of the transposes `declare_layer` and `declare_shared` describe, so a converter
/// check can compare against the ONNX without either side restating the other's convention.
/// Scales, biases, norm gains and clip pairs have no counterpart to transpose and come back
/// unchanged.
///
/// Two cases beyond the decoder's and the vision tower's:
///
/// * The depthwise convolution is `[1024, 1, 5]` in the export and gains a unit height here, so
///   the rank differs rather than the order.
/// * The two subsampling kernels come back unchanged, which is correct for the *shape* even
///   though the converter swaps their spatial axes: both are `3 x 3`, so only the values move.
///   See [`SSCP_CONV0`].
pub fn as_exported(dims: &[u32]) -> Vec<u32> {
    match dims {
        [out, inp, 1, 1] => vec![*inp, *out],
        [out, 1, 1, taps] => vec![*out, 1, *taps],
        other => other.to_vec(),
    }
}

/// The shape of plan input 0 for a clip of `frames` mel frames.
///
/// `[1, MELS, frames]`: one channel, the mel bins on the height axis and time on the width.
///
/// **The two spatial axes are transposed against the reference**, which treats the mel as
/// `[1, frames, 128]`. The convolutions are square in kernel, stride and padding, so this is
/// exact **provided their kernels are transposed with it** - the converter does that; see
/// [`SSCP_CONV0`]. What it buys is the flatten into [`INPUT_PROJECTION`] becoming a relabelling
/// instead of a copy of a 23 MB map, and the export's two permutes around each channel-last
/// `LayerNormalization` disappearing, since this runtime's [`super::Builder::layer_norm`]
/// already normalises over the channel axis. The price is the row permutation baked into that
/// projection's weight, which the converter also applies.
///
/// Verified end to end rather than argued: running the export to `input_proj`'s output under
/// onnxruntime and this layout in numpy on the same input agrees to cosine 0.99999994.
pub fn input_shape(frames: u32) -> Shape {
    Shape::new(1, MELS, frames)
}

/// Plan input 0: the log-mel spectrogram, laid out as [`input_shape`] describes.
///
/// `mel` is what [`crate::logmel::LogMel::spectrogram`] appends - `frames` rows of
/// [`crate::logmel::MELS`] values, row major - and this transposes it into the mel-major,
/// time-minor order the plan reads.
///
/// # What the caller still owes, and what it does not
///
/// [`crate::logmel`] implements the reference's `_extract_spectrogram` and nothing around it.
/// The caller must resample to 16 kHz mono and truncate to [`MAX_SAMPLES`].
///
/// It must **not** zero-pad the samples to a multiple of 128. The reference does, so that a
/// batch stacks, and then carries a validity mask through the whole tower to undo it: two mask
/// multiplies in the front end, a masked attention term, and a `GatherND` on the output. A plan
/// here is recorded per frame count, exactly as [`super::gemma4_vision`] records one per patch
/// grid, so the clip is passed at its true length, every frame is valid and all of that
/// disappears.
///
/// Measured, not argued: against the export under onnxruntime, padded-and-masked versus
/// unpadded is bit-identical over 68 configurations, 50 of them at the one residue where the
/// second mask demonstrably does work. The second mask exists to zero a subsampled position
/// whose convolution window straddled the real/pad boundary; unpadded, that position does not
/// exist and the convolution's own edge padding supplies the same zero.
///
/// **If padding is ever reintroduced - to batch clips, say - the masks come back, and they come
/// back with a known signature.** Omitting them then corrupts exactly one soft token, the
/// **last** one, on clips where the frame count is `2 (mod 4)`: about a quarter of them. A
/// 30-second clip is 2,999 frames, which is `3 (mod 4)` and therefore immune, so a test that
/// only ever runs a full-length clip cannot see it.
pub fn prepare(mel: &[f32], frames: u32) -> Result<Vec<f32>, String> {
    let mels = MELS as usize;
    let count = frames as usize;
    if frames == 0 {
        return Err("a clip of no frames".into());
    }
    // Every arena figure this tower was budgeted against assumes T <= MAX_TOKENS, which the
    // reference guarantees by truncating to MAX_SAMPLES. Nothing downstream re-checks it: a
    // 60-second clip is 5,999 frames and 1,500 tokens, and `tokens`, `input_shape` and the plan
    // would all accept it and quietly allocate twice the arena that was approved.
    let want = tokens(frames);
    if want > MAX_TOKENS {
        return Err(format!(
            "{frames} mel frames is {want} soft tokens, past the {MAX_TOKENS} a clip may hold. \
             Truncate the waveform to {MAX_SAMPLES} samples first, as the reference does."
        ));
    }
    if mel.len() != count * mels {
        return Err(format!(
            "{} mel values for {frames} frames, not {}",
            mel.len(),
            count * mels
        ));
    }
    let mut out = vec![0.0; mel.len()];
    for frame in 0..count {
        for bin in 0..mels {
            out[bin * count + frame] = mel[frame * mels + bin];
        }
    }
    Ok(out)
}
