/// Every tensor `mode` reads on the **device**, in ascending order.
///
/// Not a range, unlike `nets::nllb`'s: [`Mode::Encode`] reads the whole encoder *and* two
/// projections out of each decoder layer, because Whisper's cross-attention keys and values are a
/// decoder weight applied to an encoder result. See the module docs.
fn device_tensors(mode: Mode) -> Vec<usize> {
    let mut read: Vec<usize> = Vec::new();
    match mode {
        Mode::Encode => {
            read.extend(CONV1..HEAD);
            for layer in 0..DECODER_LAYERS {
                read.extend(cross_kv(layer)..cross_kv(layer) + 6);
            }
        }
        Mode::DecodeStep => {
            read.extend(HEAD..HEAD + 3);
            let skipped: std::collections::BTreeSet<usize> = (0..DECODER_LAYERS)
                .flat_map(|layer| cross_kv(layer)..cross_kv(layer) + 6)
                .collect();
            read.extend((DECODER..TENSORS).filter(|index| !skipped.contains(index)));
        }
    }
    read.sort_unstable();
    read
}

/// Name every tensor **outside** `read` as one this pass does not touch.
///
/// [`Builder::finish`] refuses an unread tensor, and neither pass reads the whole file. Declaring
/// the complement rather than listing it keeps the two in step.
fn name_host_tensors(b: &mut Builder, read: &[usize]) {
    for index in 0..TENSORS {
        if !read.contains(&index) {
            b.host_tensor(index, &dims_of(index));
        }
    }
}

/// The shape of tensor `index`, derived from the layout constants.
///
/// `host_tensor` checks it against the file, so this is a *second* statement of the table that
/// `maml_convert.collect_whisper` writes — which is the point: a converter and a runtime that
/// disagree about a shape fail here rather than on the device.
fn dims_of(index: usize) -> Vec<u32> {
    match index {
        CONV1 => vec![D_MODEL, MELS, 1, CONV_KERNEL],
        CONV2 => vec![D_MODEL, D_MODEL, 1, CONV_KERNEL],
        ENC_POSITIONS => vec![D_MODEL, 1, SOURCE_POSITIONS],
        HEAD => vec![VOCAB, D_MODEL, 1, 1],
        DEC_POSITIONS => vec![MAX_POSITIONS, D_MODEL],
        _ if index == HEAD + 1 || index == HEAD + 2 => vec![VOCAB],
        _ if (ENCODER..ENC_NORM).contains(&index) => {
            layer_dims((index - ENCODER) % ENCODER_LAYER_TENSORS, ENCODER_LAYER_TENSORS)
        }
        _ if (DECODER..DEC_NORM).contains(&index) => {
            layer_dims((index - DECODER) % DECODER_LAYER_TENSORS, DECODER_LAYER_TENSORS)
        }
        // The two convolutions' scales and biases, and the two trailing layer norms.
        _ => vec![D_MODEL],
    }
}

/// The shape of the `within`th tensor of a layer of `per_layer` tensors.
fn layer_dims(within: usize, per_layer: usize) -> Vec<u32> {
    // A layer is a sequence of groups: `[2]` for a norm, `[out, in, 1, 1] [out] [out]` for a
    // projection. Walking them is shorter than a table and cannot disagree with [`Layers`].
    let mut groups: Vec<(usize, u32, u32)> = vec![(2, 0, 0)];
    groups.extend([(3, D_MODEL, D_MODEL); 4]);
    if per_layer == DECODER_LAYER_TENSORS {
        groups.push((2, 0, 0));
        groups.extend([(3, D_MODEL, D_MODEL); 4]);
    }
    groups.push((2, 0, 0));
    groups.push((3, FFN, D_MODEL));
    groups.push((3, D_MODEL, FFN));

    let mut at = 0;
    for (size, out, inputs) in groups {
        if within < at + size {
            let offset = within - at;
            return match (size, offset) {
                // A norm's gamma and beta.
                (2, _) => vec![D_MODEL],
                // A projection's kernel, then its scale and its bias.
                (_, 0) => vec![out, inputs, 1, 1],
                _ => vec![out],
            };
        }
        at += size;
    }
    vec![D_MODEL]
}

/// The embedded and positioned tokens for `ids`, in the channel-major layout the plan wants.
///
/// The `[D_MODEL, 1, ids.len()]` fp16 input to [`Mode::DecodeStep`], as f32 for the caller to
/// upload. `x[t] = embed_tokens[ids[t]] + embed_positions[past + t]`, both read from the file and
/// summed in f32 before anything is rounded.
///
/// `past` is how many positions precede these ids, which is the step number. There is **no offset**,
/// unlike `nets::nllb`'s fairseq `+ 2`: whisper's first prompt token sits at position 0.
///
/// And no `sqrt(d_model)`: `config.json` has `scale_embedding: false`.
pub fn embed_positions(
    weights: Reader<'_>,
    ids: &[u32],
    past: u32,
) -> Result<Vec<f32>, String> {
    if ids.is_empty() {
        return Err("an embedding of no tokens".into());
    }
    let last = past as usize + ids.len();
    if last > MAX_POSITIONS as usize {
        return Err(format!("position {last} is past the model's {MAX_POSITIONS}-entry table"));
    }
    let table = weights.fp16(DEC_POSITIONS, &[MAX_POSITIONS, D_MODEL])?;
    let width = D_MODEL as usize;
    let mut out = vec![0.0f32; width * ids.len()];
    for (at, &id) in ids.iter().enumerate() {
        if id >= VOCAB {
            return Err(format!("token {id} is past the {VOCAB}-entry vocabulary"));
        }
        let embedding = weights.int8_row(HEAD, HEAD + 1, &[VOCAB, D_MODEL, 1, 1], id)?;
        let position = (past as usize + at) * width;
        for (channel, value) in embedding.iter().enumerate() {
            let learned = table
                .get(position + channel)
                .ok_or("the position table is shorter than the sequence")?;
            // Channel-major: this runtime indexes `[c, h, w]`, and both tables are `[w, c]`.
            let slot = out
                .get_mut(channel * ids.len() + at)
                .ok_or("an embedding row is wider than d_model")?;
            *slot = value + learned;
        }
    }
    Ok(out)
}
