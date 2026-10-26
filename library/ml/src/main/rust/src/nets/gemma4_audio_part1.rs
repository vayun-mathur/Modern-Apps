/// Declare every tensor of layer `index` against `weights`, in file order.
///
/// The order is the export's topological order, which is what the converter walks. Reading the
/// clips as part of the sequence rather than collecting them separately is deliberate: with
/// eighteen of them a layer and no names to match on, it is the only thing that keeps the two
/// sides in step.
pub fn declare_layer(weights: &dyn WeightSource, index: usize) -> Result<(), String> {
    let at = layer_at(index);
    let mut next = at;
    let plain = |dims: &[u32], n: &mut usize| -> Result<(), String> {
        let here = *n;
        *n += 1;
        weights.shaped(here, dims).map(|_| ())
    };
    let clip = |n: &mut usize| -> Result<(), String> {
        let here = *n;
        *n += CLIP_TENSORS;
        weights.shaped(here, &[2]).map(|_| ())
    };

    // Feed-forward one, the macaron's first half.
    plain(&[D_MODEL], &mut next)?; // ffw1 pre-norm
    clip(&mut next)?;
    projection(weights, &mut next, FFN, D_MODEL)?; // ffw1 up
    clip(&mut next)?;
    clip(&mut next)?; // after the silu
    projection(weights, &mut next, D_MODEL, FFN)?; // ffw1 down
    clip(&mut next)?;
    plain(&[D_MODEL], &mut next)?; // ffw1 post-norm

    // Attention.
    plain(&[D_MODEL], &mut next)?; // pre-attention norm
    clip(&mut next)?; // shared by q, k and v
    projection(weights, &mut next, HEADS * HEAD_DIM, D_MODEL)?; // q
    projection(weights, &mut next, HEADS * HEAD_DIM, D_MODEL)?; // k
    projection(weights, &mut next, HEADS * HEAD_DIM, D_MODEL)?; // v
    clip(&mut next)?;
    clip(&mut next)?;
    clip(&mut next)?;
    // The folded `softplus(per_dim_scale) * Q_SCALE`, tiled from [128] to the full width so it
    // is an ordinary per-channel multiply rather than one that repeats every head. Rank three
    // because `Builder::constant` copies it into the arena as a `[D_MODEL, 1, 1]` operand.
    plain(&[D_MODEL, 1, 1], &mut next)?;
    // The relative-position table, transposed by the converter from the export's
    // [heads, head_dim, offsets] so that head_dim - the axis the dot product contracts over -
    // is contiguous.
    plain(&[HEADS, REL_OFFSETS, HEAD_DIM], &mut next)?;
    clip(&mut next)?;
    projection(weights, &mut next, D_MODEL, HEADS * HEAD_DIM)?; // post
    clip(&mut next)?;
    plain(&[D_MODEL], &mut next)?; // post-attention norm

    // The light convolution module.
    plain(&[D_MODEL], &mut next)?; // pre-norm
    clip(&mut next)?;
    projection(weights, &mut next, LCONV_GATE, D_MODEL)?; // gate projection, split by the GLU
    clip(&mut next)?;
    // The causal depthwise kernel, `[1024, 1, 5]` in the export, as a `1 x 5` grouped
    // convolution over a `[D_MODEL, 1, T]` sequence. Unquantised: 5,120 parameters.
    weights.shaped(next, &[D_MODEL, 1, 1, CONV_KERNEL])?;
    weights.shaped(next + 1, &[D_MODEL])?;
    next += DENSE_TENSORS;
    plain(&[D_MODEL], &mut next)?; // conv norm, after the convolution
    clip(&mut next)?;
    projection(weights, &mut next, D_MODEL, D_MODEL)?; // exit projection
    clip(&mut next)?;

    // Feed-forward two, then the terminal norm.
    plain(&[D_MODEL], &mut next)?; // ffw2 pre-norm
    clip(&mut next)?;
    projection(weights, &mut next, FFN, D_MODEL)?;
    clip(&mut next)?;
    clip(&mut next)?;
    projection(weights, &mut next, D_MODEL, FFN)?;
    clip(&mut next)?;
    plain(&[D_MODEL], &mut next)?; // ffw2 post-norm
    plain(&[D_MODEL], &mut next)?; // terminal norm, no residual

    if next != layer_at(index + 1) {
        return Err(format!(
            "audio layer {index} declared {} tensors, not the {} its span allows",
            next - at,
            layer_at(index + 1) - at
        ));
    }
    Ok(())
}

/// One quantised `1 x 1` projection: kernel, per-block scale, bias.
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

/// One **unquantised** `1 x 1` projection: kernel and bias, the pair `Builder::conv` reads.
fn dense(weights: &dyn WeightSource, next: &mut usize, out: u32, inp: u32) -> Result<(), String> {
    weights.shaped(*next, &[out, inp, 1, 1])?;
    weights.shaped(*next + 1, &[out])?;
    *next += DENSE_TENSORS;
    Ok(())
}

/// Declare the tensors that sit outside any layer, in file order.
pub fn declare_shared(weights: &dyn WeightSource) -> Result<(), String> {
    let mut next = SSCP_CONV0;
    for (channels, inputs) in [(SSCP_CHANNELS[0], 1), (SSCP_CHANNELS[1], SSCP_CHANNELS[0])] {
        weights.shaped(next, &[channels, inputs, SSCP_KERNEL, SSCP_KERNEL])?;
        weights.shaped(next + 1, &[channels])?;
        next += DENSE_TENSORS;
        // Gain then beta, the pair `Builder::layer_norm` reads. The export has no beta; the
        // converter synthesises zeros so the norm needs no second form.
        weights.shaped(next, &[channels])?;
        weights.shaped(next + 1, &[channels])?;
        next += DENSE_TENSORS;
    }
    dense(weights, &mut next, D_MODEL, D_MODEL)?; // input projection
    dense(weights, &mut next, OUT_DIM, D_MODEL)?; // output projection, with a real bias
    weights.shaped(next, &[OUT_DIM])?; // the embedder's pre-projection norm
    next += 1;
    dense(weights, &mut next, OUT_DIM, OUT_DIM)?; // the embedder's projection
    if next != SHARED_TENSORS {
        return Err(format!("{next} shared tensors, not {SHARED_TENSORS}"));
    }
    Ok(())
}

/// Which pass [`build`] emits.
///
/// The frame count is part of the key because a plan is recorded at one shape and a clip's
/// length varies. [`crate::vulkan::Reshaped`] re-records when the key changes, and takes the key
/// by `Copy + PartialEq`, which is what this derives.
///
/// The three variants return different numbers of outputs, deliberately rather than folding
/// [`Mode::Sscp`] into `Trace { layers: 0 }`: a mode whose output count varies with a parameter
/// reads fine and then hands the caller the wrong tensor.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// A whole clip: one output, `[OUT_DIM, 1, tokens(frames)]`, already in text-embedding space.
    Clip {
        /// Mel frames in, before any subsampling.
        frames: u32,
    },
    /// Stop after `layers` layers and hand back both sides of the output projection.
    ///
    /// For parity bisection: comparing the whole tower can only say that the answer moved, and
    /// this says where. Outputs are `[hidden, tail]` - the tower's own state at `[D_MODEL, 1, T]`
    /// **before** `output_proj`, and `[OUT_DIM, 1, T]` after it and its bias. Two rather than one
    /// because the tail is a single `1024 x 1536` matmul and a disagreement could be either side
    /// of it.
    ///
    /// `layers: 0` stops before layer 0 and so reports the front end alone.
    Trace {
        /// Mel frames in.
        frames: u32,
        /// Layers to run, `0..=LAYERS`.
        layers: usize,
    },
    /// Both sides of the input projection: `[sscp_out, projected]`, each `[D_MODEL, 1, T]`.
    ///
    /// `projected` is bit-identical to [`Mode::Trace`]`{ layers: 0 }`'s `hidden` - the export has
    /// nothing but a reshape between them - which makes the pair a free self-check on a harness.
    Sscp {
        /// Mel frames in.
        frames: u32,
    },
}

impl Mode {
    /// The mel frame count this pass is recorded for.
    pub const fn frames(self) -> u32 {
        match self {
            Mode::Clip { frames } | Mode::Trace { frames, .. } | Mode::Sscp { frames } => frames,
        }
    }

    /// Soft tokens the pass emits, which is every output's width.
    pub const fn tokens(self) -> u32 {
        tokens(self.frames())
    }
}

/// Plan inputs, in declaration order.
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0 | `[1, MELS, frames]` | the log-mel spectrogram, laid out by [`prepare`] |
///
/// One, where [`super::gemma4_vision`] has three: this tower has no host-gathered position table
/// and no rotary, so the mel is the whole input.
pub const INPUTS: usize = 1;

/// The shortest clip this tower can be recorded for, in soft tokens.
///
/// [`super::Builder::attn_scores_banded`] refuses a band wider than the sequence, and the band
/// cannot simply be narrowed to `T`: column `j` reads relative offset `j + 1`, so a band of `T`
/// would read offsets `1..=T` where the displacements are `0..=T-1`, i.e. the wrong columns of
/// the table. The band is a property of the trained model, not of the clip.
///
/// `T = ATTEND_SPAN` needs 45 mel frames, which is 0.45 s of audio.
pub const MIN_TOKENS: u32 = ATTEND_SPAN;

/// Build the pass `mode` describes.
pub fn build(weights: &dyn WeightSource, mode: Mode) -> Result<Plan, String> {
    let frames = mode.frames();
    let seq = tokens(frames);
    if frames == 0 {
        return Err("a clip of no frames".into());
    }
    if seq > MAX_TOKENS {
        return Err(format!(
            "{frames} mel frames is {seq} soft tokens, past the {MAX_TOKENS} a clip may hold"
        ));
    }
    if seq < MIN_TOKENS {
        return Err(format!(
            "{frames} mel frames is {seq} soft tokens, under the {MIN_TOKENS} the \
             {ATTEND_SPAN}-wide attention band needs. See MIN_TOKENS: the band is the model's, \
             not the clip's."
        ));
    }
    let stop_after = match mode {
        Mode::Clip { .. } => LAYERS,
        Mode::Sscp { .. } => 0,
        Mode::Trace { layers, .. } if layers <= LAYERS => layers,
        Mode::Trace { layers, .. } => {
            return Err(format!("a trace of {layers} of {LAYERS} layers"));
        }
    };

    let mut builder = Builder::new(weights);
    let b = &mut builder;
    let mel = b.input(input_shape(frames));

    // The subsampling front end, over the mel as a one-channel image with the bins on the height
    // axis and time on the width - see `input_shape`. `layer_norm` normalises the channel axis,
    // which is where the export's two permutes per norm go.
    let x = sscp(b, mel, SSCP_CONV0, SSCP_NORM0, SSCP_CHANNELS[0]);
    let x = sscp(b, x, SSCP_CONV1, SSCP_NORM1, SSCP_CHANNELS[1]);
    // Free: `[32, 32, T]` and `[1024, 1, T]` are the same bytes, which is the whole reason the
    // mel is carried transposed. `INPUT_PROJECTION`'s rows are permuted to suit.
    let sscp_out = b.reshaped(x, Shape::new(D_MODEL, 1, seq));
    let projected = dense_point(b, INPUT_PROJECTION, sscp_out, D_MODEL);

    if let Mode::Sscp { .. } = mode {
        for index in 0..LAYERS {
            declare_layer(weights, index)?;
            name_layer(b, index);
        }
        name_tail(b);
        return builder.finish(&[sscp_out, projected]);
    }

    // Layers run padded to a multiple of eight positions so the int4 GEMMs take their 16-byte
    // load path. Exact: the attention band and the depthwise conv only read earlier positions,
    // and every other op is per position.
    let padded = seq.next_multiple_of(8);
    let mut x = if padded == seq { projected } else { b.pad_positions(projected, padded) };
    for index in 0..stop_after {
        x = layer(b, index, x)?;
    }
    if padded != seq {
        x = b.crop_positions(x, seq);
    }
    for index in stop_after..LAYERS {
        declare_layer(weights, index)?;
        name_layer(b, index);
    }

    // `output_proj` is the one linear in the export with a real bias.
    let tail = dense_point(b, OUT_PROJECTION, x, OUT_DIM);
    if let Mode::Trace { .. } = mode {
        // Both sides of the projection, so a tower that agrees and an output that does not can
        // be told apart from a tower that never agreed.
        b.host_tensor(EMBED_NORM, &[OUT_DIM]);
        b.host_tensor(EMBED_PROJECTION, &[OUT_DIM, OUT_DIM, 1, 1]);
        b.host_tensor(EMBED_PROJECTION + 1, &[OUT_DIM]);
        return builder.finish(&[x, tail]);
    }

    // The embedder, which the exporter traced through: the result is already in text-embedding
    // space and scatters straight into the decoder's prompt.
    let normed = b.rms_norm(tail, EMBED_NORM, EPSILON);
    let out = dense_point(b, EMBED_PROJECTION, normed, OUT_DIM);
    builder.finish(&[out])
}

/// One subsampling stage: `conv -> layer norm -> ReLU`, halving both axes.
///
/// The convolution carries no bias and the norm no beta; the converter synthesises both as zeros
/// so this needs no special case. `Act::None` on the convolution rather than `Act::Relu` because
/// the norm sits between them.
fn sscp(b: &mut Builder, x: Id, kernel: usize, norm: usize, out: u32) -> Id {
    let convolved = b.conv(
        x,
        kernel,
        out,
        (SSCP_KERNEL, SSCP_KERNEL),
        (SSCP_STRIDE, SSCP_STRIDE),
        (1, 1),
        (1, 1, 1, 1),
        1,
        Act::None,
    );
    let normed = b.layer_norm(convolved, norm, EPSILON);
    b.activate(normed, Act::Relu)
}

/// A `1 x 1` int4 projection, which every linear inside a layer is.
fn point(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    b.conv_int4(x, at, out, Act::None)
}

/// A `1 x 1` **fp16** projection, which the three at the ends are.
fn dense_point(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    b.conv(x, at, out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None)
}

/// One macaron feed-forward half: `norm -> up -> silu -> down -> norm -> *0.5 -> add`.
///
/// [`RESIDUAL_WEIGHT`] scales the **branch**, after its post-norm and before the add. The four
/// clips are the export's, in its order: the norm's output, the up projection's output, the
/// activation's output, and the down projection's output.
#[allow(clippy::too_many_arguments)]
fn feed_forward(
    b: &mut Builder,
    x: Id,
    pre_norm: usize,
    clip_in: usize,
    up: usize,
    clip_up: usize,
    clip_act: usize,
    down: usize,
    clip_out: usize,
    post_norm: usize,
) -> Id {
    let h = b.rms_norm(x, pre_norm, EPSILON);
    let h = b.clamp(h, clip_in);
    let h = point(b, up, h, FFN);
    let h = b.clamp(h, clip_up);
    let h = b.activate(h, Act::Swish);
    let h = b.clamp(h, clip_act);
    let h = point(b, down, h, D_MODEL);
    let h = b.clamp(h, clip_out);
    let h = b.rms_norm(h, post_norm, EPSILON);
    let h = b.affine(h, RESIDUAL_WEIGHT, 0.0);
    b.add(x, h)
}
