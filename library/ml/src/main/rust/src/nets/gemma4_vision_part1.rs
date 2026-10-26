/// Plan inputs, in declaration order. `T` is [`padded_positions`] of [`Grid::patches`]; the
/// padded columns are zeros.
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0 | `[768, 1, T]` | the patches, normalised to `(p - 0.5) * 2` on the host |
/// | 1 | `[768, 1, T]` | `column_table[col] + row_table[row]`, gathered on the host |
/// | 2 | `[64, 1, T]` | rotary angles: cos/sin for the column, then for the row |
///
/// The position embedding is a separate input rather than folded into the patches because the
/// export adds it **after** the patch projection, not before - summing them on the host would put
/// it through a matrix it was never meant to see.
///
/// All three are built by [`prepare`].
pub const INPUTS: usize = 3;

/// The sequence length the layers run at: the patches rounded up to a multiple of eight, so the
/// int4 GEMMs take their 16-byte load path (`wide_oct` in conv_point_int4.comp). Every op but
/// attention is per position; the padded keys are masked out of each softmax and the padded
/// positions cropped off before the pooling.
pub const fn padded_positions(patches: u32) -> u32 {
    patches.next_multiple_of(8)
}

/// Build the image pass.
pub fn build(weights: &dyn WeightSource, mode: Mode) -> Result<Plan, String> {
    let grid = mode.grid();
    let stop_after = match mode {
        Mode::Image(_) => LAYERS,
        Mode::Trace { layers, .. } if layers <= LAYERS => layers,
        Mode::Trace { layers, .. } => {
            return Err(format!("a trace of {layers} of {LAYERS} layers"));
        }
    };
    let patches = Grid::new(grid.rows, grid.cols)?.patches();
    let padded = padded_positions(patches);
    let mut builder = Builder::new(weights);
    let b = &mut builder;

    let values = b.input(Shape::new(D_MODEL, 1, padded));
    let positions = b.input(Shape::new(D_MODEL, 1, padded));
    let angles = b.input(Shape::new(HEAD_DIM, 1, padded));

    // The two position tables are gathered on the host and arrive summed, as `positions`.
    for at in [COLUMN_POSITIONS, ROW_POSITIONS] {
        b.host_tensor(at, &[POSITIONS, D_MODEL, 1, 1]);
        b.host_tensor(at + 1, &[POSITIONS, D_MODEL.div_ceil(crate::weights::I4_BLOCK)]);
        b.host_tensor(at + 2, &[POSITIONS]);
    }

    // The patch projection, then the position embedding. Its input is `16 * 16 * 3`, which
    // happens to equal `d_model`. Unquantised - see `PATCH_PROJECTION`.
    let projected = dense_point(b, PATCH_PROJECTION, values, D_MODEL);
    let mut x = b.add(projected, positions);

    for index in 0..stop_after {
        x = layer(b, index, x, angles, patches)?;
    }
    if padded != patches {
        x = b.crop_positions(x, patches);
    }

    if let Mode::Trace { .. } = mode {
        for index in stop_after..LAYERS {
            declare_layer(weights, index)?;
            name_layer(b, index);
        }
        b.host_tensor(FINAL_NORM, &[D_MODEL]);
        b.host_tensor(OUT_PROJECTION, &[OUT_DIM, D_MODEL, 1, 1]);
        b.host_tensor(OUT_PROJECTION + 1, &[OUT_DIM]);
        // Both sides of the pooling, so a tower that agrees and an output that does not can be
        // told apart from a tower that never agreed.
        let pooled = pool(b, x, grid);
        return builder.finish(&[x, pooled]);
    }

    let pooled = pool(b, x, grid);
    let normed = b.rms_norm(pooled, FINAL_NORM, EPSILON);
    let out = dense_point(b, OUT_PROJECTION, normed, OUT_DIM);
    builder.finish(&[out])
}

/// Average each `POOL x POOL` block of patches into one soft token.
///
/// The export does this as a matrix multiply against a one-hot matrix it builds from the position
/// ids, divided by `POOL * POOL`, followed by a `GatherND` that drops the cells no patch landed
/// in. Both of those exist to cope with the padding it applies to reach a fixed patch count. This
/// runtime records a plan per grid instead of padding, so every cell is full and the whole thing
/// collapses to an average pool.
///
/// The two reshapes are free. A sequence is `[c, 1, T]` and a patch at `(row, col)` sits at
/// `T`-index `row * cols + col`, which is exactly the layout of a `[c, rows, cols]` map - the
/// same bytes, relabelled.
fn pool(b: &mut Builder, x: Id, grid: Grid) -> Id {
    let map = b.reshaped(x, Shape::new(D_MODEL, grid.rows, grid.cols));
    let pooled = b.avg_pool(map, (POOL, POOL), (POOL, POOL));
    let sequence = b.reshaped(pooled, Shape::new(D_MODEL, 1, grid.soft_tokens()));
    // The export scales by `sqrt(d_model)` here. An RMS norm follows immediately and is scale
    // invariant, so this changes nothing mathematically - it is kept because it is what keeps the
    // values in fp16's range on the way in, which is presumably why the export has it.
    b.affine(sequence, f64::from(D_MODEL).sqrt() as f32, 0.0)
}

/// Declare every tensor of a layer [`build`] skipped, so [`Builder::finish`] still sees them read.
///
/// A [`Mode::Trace`] stops early on purpose, and an unread tensor is otherwise exactly what a
/// forward pass that lost a layer looks like from the outside.
fn name_layer(b: &mut Builder, index: usize) {
    let at = layer_at(index);
    let mut next = at;
    let plain = |b: &mut Builder, n: &mut usize, dims: &[u32]| {
        b.host_tensor(*n, dims);
        *n += 1;
    };
    let proj = |b: &mut Builder, n: &mut usize, out: u32, inp: u32| {
        b.host_tensor(*n, &[out, inp, 1, 1]);
        b.host_tensor(*n + 1, &[out, inp.div_ceil(crate::weights::I4_BLOCK)]);
        b.host_tensor(*n + 2, &[out]);
        *n += PROJECTION_TENSORS;
    };

    plain(b, &mut next, &[D_MODEL]);
    plain(b, &mut next, &[2]);
    for _ in 0..3 {
        proj(b, &mut next, HEADS * HEAD_DIM, D_MODEL);
    }
    for _ in 0..3 {
        plain(b, &mut next, &[2]);
    }
    for _ in 0..3 {
        plain(b, &mut next, &[HEAD_DIM]);
    }
    plain(b, &mut next, &[2]);
    proj(b, &mut next, D_MODEL, HEADS * HEAD_DIM);
    plain(b, &mut next, &[2]);
    plain(b, &mut next, &[D_MODEL]);
    plain(b, &mut next, &[D_MODEL]);
    plain(b, &mut next, &[2]);
    proj(b, &mut next, FFN, D_MODEL);
    proj(b, &mut next, FFN, D_MODEL);
    for _ in 0..3 {
        plain(b, &mut next, &[2]);
    }
    proj(b, &mut next, D_MODEL, FFN);
    plain(b, &mut next, &[2]);
    plain(b, &mut next, &[D_MODEL]);
    debug_assert_eq!(next, layer_at(index + 1), "named {} tensors", next - at);
}

/// A `1 x 1` int4 convolution, which every projection inside a layer is.
fn point(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    b.conv_int4(x, at, out, Act::None)
}

/// A `1 x 1` **fp16** convolution, which the two projections at the ends are.
///
/// Spelled out rather than routed through `conv_same` so the stride, dilation and padding are
/// visible: this is a projection over positions, not a convolution over a map.
fn dense_point(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    b.conv(x, at, out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None)
}

/// One encoder layer.
fn layer(b: &mut Builder, index: usize, x: Id, angles: Id, keys: u32) -> Result<Id, String> {
    let at = layer_at(index);
    let mut next = at;
    let plain = |n: &mut usize| {
        let here = *n;
        *n += 1;
        here
    };
    let clip = |n: &mut usize| {
        let here = *n;
        *n += CLIP_TENSORS;
        here
    };
    let proj = |n: &mut usize| {
        let here = *n;
        *n += PROJECTION_TENSORS;
        here
    };

    let pre_attn = plain(&mut next);
    let clip_in = clip(&mut next);
    let q_proj = proj(&mut next);
    let k_proj = proj(&mut next);
    let v_proj = proj(&mut next);
    let clip_q = clip(&mut next);
    let clip_k = clip(&mut next);
    let clip_v = clip(&mut next);
    let q_norm = plain(&mut next);
    let k_norm = plain(&mut next);
    let v_norm = plain(&mut next);
    let clip_mixed = clip(&mut next);
    let o_proj = proj(&mut next);
    let clip_o = clip(&mut next);
    let post_attn = plain(&mut next);
    let pre_ff = plain(&mut next);
    let clip_ff_in = clip(&mut next);
    let gate_proj = proj(&mut next);
    let up_proj = proj(&mut next);
    let clip_gate = clip(&mut next);
    let clip_up = clip(&mut next);
    let clip_gated = clip(&mut next);
    let down_proj = proj(&mut next);
    let clip_down = clip(&mut next);
    let post_ff = plain(&mut next);
    if next != layer_at(index + 1) {
        return Err(format!("vision layer {index} read {} tensors", next - at));
    }

    let normed = b.rms_norm(x, pre_attn, EPSILON);
    let normed = b.clamp(normed, clip_in);
    let q = point(b, q_proj, normed, HEADS * HEAD_DIM);
    let q = b.clamp(q, clip_q);
    let k = point(b, k_proj, normed, HEADS * HEAD_DIM);
    let k = b.clamp(k, clip_k);
    let v = point(b, v_proj, normed, HEADS * HEAD_DIM);
    let v = b.clamp(v, clip_v);
    // Per head against a `head_dim`-long gamma, as the decoder does.
    let q = b.rms_norm_grouped(q, q_norm, EPSILON, HEADS);
    let k = b.rms_norm_grouped(k, k_norm, EPSILON, HEADS);
    let v = b.rms_norm_grouped(v, v_norm, EPSILON, HEADS);
    // Two blocks per head: the patch's column rotates the first 32 channels, its row the rest.
    let q = b.rotary_axes(q, angles, HEADS, ROPE_AXES);
    let k = b.rotary_axes(k, angles, HEADS, ROPE_AXES);

    // No scale: the export goes straight from the rotary into the score matmul, and the
    // uniform q_norm and k_norm gammas carry it instead. See `SCALE_IS_IN_THE_NORMS`.
    let scores = b.attn_scores_prescaled(q, k, HEADS);
    let probs = b.softmax_keys(scores, keys);
    let mixed = b.attn_apply(probs, v, HEADS);
    let mixed = b.clamp(mixed, clip_mixed);
    let attended = point(b, o_proj, mixed, D_MODEL);
    let attended = b.clamp(attended, clip_o);
    let attended = b.rms_norm(attended, post_attn, EPSILON);
    let x = b.add(x, attended);

    let ff_in = b.rms_norm(x, pre_ff, EPSILON);
    let ff_in = b.clamp(ff_in, clip_ff_in);
    let gate = point(b, gate_proj, ff_in, FFN);
    let gate = b.clamp(gate, clip_gate);
    let up = point(b, up_proj, ff_in, FFN);
    let up = b.clamp(up, clip_up);
    let gate = b.activate(gate, Act::Gelu);
    let gated = b.mul(gate, up);
    let gated = b.clamp(gated, clip_gated);
    let ff = point(b, down_proj, gated, D_MODEL);
    let ff = b.clamp(ff, clip_down);
    let ff = b.rms_norm(ff, post_ff, EPSILON);
    Ok(b.add(x, ff))
}

/// The inverse frequencies of the two-dimensional rotary: `ROPE_THETA^(-i / half)`.
///
/// `half` is a quarter of [`HEAD_DIM`], because a head is [`ROPE_AXES`] blocks and each block
/// rotates half as many 2-planes as it has channels. For the shipped configuration that is 16
/// frequencies, and they come out as the powers of `0.75` the export holds as a constant.
pub fn inv_freq() -> Vec<f32> {
    let half = HEAD_DIM / ROPE_AXES / 2;
    (0..half)
        .map(|i| f64::from(ROPE_THETA).powf(-f64::from(i) / f64::from(half)) as f32)
        .collect()
}

/// The normalised patches, plan input 0: `[768, 1, T]`.
///
/// `pixels` is `width x height` ARGB_8888 in row-major order, already resized to
/// [`Grid::pixels`]. A patch's 768 values run `y`, then `x`, then channel - HWC inside the patch,
/// not CHW - and `p / 255` then `(p - 0.5) * 2` takes a byte to `[-1, 1]`. The export does the
/// second half itself; doing both here saves a pass over the arena.
pub fn patchify(grid: Grid, pixels: &[i32]) -> Result<Vec<f32>, String> {
    let (width, height) = grid.pixels();
    let wanted = width as usize * height as usize;
    if pixels.len() != wanted {
        return Err(format!(
            "{} pixels for a {width}x{height} image, not {wanted}",
            pixels.len()
        ));
    }
    let patches = grid.patches() as usize;
    let patch = PATCH as usize;
    let cols = grid.cols as usize;
    // `[768, 1, T]`, so a patch is a strided column and its 768 values are `T` apart.
    let mut values = vec![0.0; D_MODEL as usize * patches];
    for y in 0..height as usize {
        for x in 0..width as usize {
            let argb = pixels[y * width as usize + x];
            let at = ((y % patch) * patch + (x % patch)) * 3;
            let column = (y / patch) * cols + (x / patch);
            for (channel, shift) in [16, 8, 0].into_iter().enumerate() {
                let byte = f32::from(((argb >> shift) & 0xff) as u8);
                values[(at + channel) * patches + column] = (byte / 255.0 - 0.5) * 2.0;
            }
        }
    }
    Ok(values)
}

/// The rotary angles, plan input 2: `[64, 1, T]`.
///
/// Block 0 of a head is rotated by the patch's column and block 1 by its row, each written as
/// `half` cosines followed by `half` sines - the layout [`Builder::rotary_axes`] reads.
pub fn rotary_angles(grid: Grid) -> Vec<f32> {
    let patches = grid.patches() as usize;
    let frequencies = inv_freq();
    let half = frequencies.len();
    let mut angles = vec![0.0; HEAD_DIM as usize * patches];
    for row in 0..grid.rows as usize {
        for column in 0..grid.cols as usize {
            let at = row * grid.cols as usize + column;
            for (block, coordinate) in [column, row].into_iter().enumerate() {
                for (i, frequency) in frequencies.iter().enumerate() {
                    let theta = coordinate as f32 * frequency;
                    let base = block * 2 * half;
                    angles[(base + i) * patches + at] = theta.cos();
                    angles[(base + half + i) * patches + at] = theta.sin();
                }
            }
        }
    }
    angles
}

/// The three plan inputs for one image, in [`INPUTS`] order.
///
/// [`patchify`] and [`rotary_angles`] either side of the position gather, which is the only part
/// that needs the weights: `column_table[col] + row_table[row]`, summed because the export adds
/// them after the patch projection.
pub fn prepare(
    weights: &crate::weights::Reader<'_>,
    grid: Grid,
    pixels: &[i32],
) -> Result<[Vec<f32>; INPUTS], String> {
    let values = patchify(grid, pixels)?;
    let patches = grid.patches() as usize;

    // One row of each table per distinct coordinate, not per patch: a 48x48 grid is 2304 patches
    // but only 96 rows, and each row is a 768-wide int4 dequantisation.
    let table =
        |at: usize, index: u32| weights.int4_row(at, at + 1, &[POSITIONS, D_MODEL, 1, 1], index);
    let by_column = (0..grid.cols)
        .map(|c| table(COLUMN_POSITIONS, c))
        .collect::<Result<Vec<_>, _>>()?;
    let by_row = (0..grid.rows)
        .map(|r| table(ROW_POSITIONS, r))
        .collect::<Result<Vec<_>, _>>()?;

    let mut positions = vec![0.0; D_MODEL as usize * patches];
    for row in 0..grid.rows as usize {
        for column in 0..grid.cols as usize {
            let at = row * grid.cols as usize + column;
            for channel in 0..D_MODEL as usize {
                positions[channel * patches + at] =
                    by_column[column][channel] + by_row[row][channel];
            }
        }
    }
    let padded = padded_positions(grid.patches()) as usize;
    let pad = |values: Vec<f32>| -> Vec<f32> {
        if padded == patches {
            return values;
        }
        values.chunks(patches).flat_map(|row| row.iter().copied().chain(std::iter::repeat_n(0.0, padded - patches))).collect()
    };
    Ok([pad(values), pad(positions), pad(rotary_angles(grid))])
}
