impl Graph {

    /// Serialise a recorded forward pass to section bytes, for the converter.
    ///
    /// The inverse of [`Graph::lower`]: walks the fused [`Recorded`] nodes and writes
    /// one section entry per node, with weight *file indices* recovered from the
    /// builder's read flags. Only the phase-1 kinds serialise — anything else is an
    /// error naming the node, which is how a net that outgrew the section refuses to
    /// emit rather than emitting a partial graph.
    ///
    /// This is the emitter half of the equivalence loop: `maml_convert.py` will run it
    /// (via a host harness) over each net and compare against its own emission. The
    /// byte layout matches [`Graph::parse`] field for field; the two are tested by
    /// round-trip (`emit` then `parse` then `lower` then `finish`).
    ///
    /// `outputs` are the plan's output ids — `Recorded` carries inputs and pins but
    /// not which pins are outputs, and the section must name them.
    pub fn emit(
        recorded: &crate::nets::Recorded,
        table: &Offsets,
        outputs: &[crate::nets::Id],
    ) -> Result<Vec<u8>, String> {
        use crate::nets::{Act, Node, Quant};
        let mut bytes = Vec::new();
        let mut nodes_bytes: Vec<u8> = Vec::new();
        let u32s = |bytes: &mut Vec<u8>, values: &[u32]| {
            for v in values {
                bytes.extend_from_slice(&v.to_le_bytes());
            }
        };
        // Computed-index assignment: every tensor id in first-use order — inputs in
        // declaration order (they are pinned first), then each node's output as it
        // appears. The section's computed table is this order; node refs are positions
        // in it.
        //
        // A plain helper, not a closure: the emission match below borrows `order`
        // mutably per node while `file_index` borrows `table` immutably, and a
        // `FnMut` closure holding `&mut order` will not share the scope with those.
        fn position(order: &mut Vec<usize>, id: usize) -> u32 {
            match order.iter().position(|&i| i == id) {
                Some(at) => at as u32,
                None => {
                    order.push(id);
                    (order.len() - 1) as u32
                }
            }
        }
        let mut order: Vec<usize> = Vec::new();
        // Inputs first, in declaration order.
        for id in &recorded.inputs {
            position(&mut order, id.0);
        }
        // Weight file index for a resolved offset: invert through the table by byte
        // offset. Element and word views address the same bytes — an fp16 element
        // offset `e` is byte `2e`, a word offset `w` is byte `4w` — so normalise to
        // bytes before comparing. Byte offsets are unique per tensor (blobs never
        // alias), so the first match is the tensor. Unresolvable is an emitter bug.
        //
        // The `Shapes` test stub answers every tensor at its own index in both
        // views, so several stub tensors can share one byte offset. Disambiguate by
        // element count: the caller passes the length the node implies (kernel
        // elements, bias channels), and the match must agree on it. The real table
        // never collides at all; the stub is test-only, and a length mismatch there
        // is still an emitter error rather than a guess.
        let file_index =
            |offset: u32, is_word: bool, len: u32, kind: &str| -> Result<u32, String> {
                let bytes = if is_word { offset * 4 } else { offset * 2 };
                table
                    .tensors
                    .iter()
                    .position(|t| t.offset == bytes && t.len == len)
                    .map(|i| i as u32)
                    .ok_or_else(|| {
                        format!("emitter: {kind} at offset {offset} names no tensor")
                    })
            };
        let act_code = |act: Act| -> Result<u32, String> {
            match act {
                Act::None => Ok(0),
                Act::Relu => Ok(1),
                Act::HardSwish => Ok(2),
                Act::Sigmoid => Ok(3),
                Act::PRelu(_) => Err("emitter: a PReLU, which phase 1 does not serialise".into()),
                Act::Clip01 => Ok(5),
                Act::Swish => Ok(6),
                Act::Relu6 => Ok(7),
                Act::Gelu => Ok(8),
            }
        };
        let mut nodes_bytes = Vec::new();
        for (i, node) in recorded.nodes.iter().enumerate() {
            match node {
                Node::Conv {
                    input,
                    out,
                    weight,
                    bias,
                    kernel,
                    stride,
                    dilation,
                    pad,
                    group,
                    act,
                    act_weight,
                    transpose,
                    pad_edge,
                    res,
                    shift,
                } => {
                    if *transpose {
                        return Err(format!("emitter: node {i} is transposed, not in phase 1"));
                    }
                    if res.is_some() || shift.is_some() {
                        return Err(format!(
                            "emitter: node {i} carries a fused addend; emit before fusion"
                        ));
                    }
                    let shape = recorded.shapes.get(out.0).copied().ok_or_else(|| {
                        format!("emitter: node {i} output {} has no shape", out.0)
                    })?;
                    nodes_bytes.push(TAG_CONV);
                    let input_at = position(&mut order, input.0);
                    let out_at = position(&mut order, out.0);
                    let in_shape =
                        recorded.shapes.get(input.0).copied().ok_or_else(|| {
                            format!("emitter: node {i} input {} has no shape", input.0)
                        })?;
                    let per_group = in_shape.c / group.max(&1);
                    let w = file_index(
                        *weight,
                        false,
                        shape.c * per_group * kernel.0 * kernel.1,
                        "kernel",
                    )?;
                    let b = file_index(*bias, false, shape.c, "bias")?;
                    let slope = match act {
                        Act::PRelu(_) => {
                            return Err(format!("emitter: node {i} is a PReLU"));
                        }
                        _ => {
                            if *act_weight != 0 {
                                return Err(format!(
                                    "emitter: node {i} carries a slope without a PReLU"
                                ));
                            }
                            NO_TENSOR
                        }
                    };
                    u32s(
                        &mut nodes_bytes,
                        &[
                            input_at,
                            out_at,
                            w,
                            b,
                            kernel.0,
                            kernel.1,
                            stride.0,
                            stride.1,
                            dilation.0,
                            dilation.1,
                            pad.0,
                            pad.1,
                            0,
                            0,
                            *group,
                            act_code(*act)?,
                            slope,
                            u32::from(*pad_edge),
                        ],
                    );
                }
                Node::ConvInt8 {
                    input,
                    out,
                    weight,
                    scale,
                    bias,
                    kernel,
                    stride,
                    dilation,
                    pad,
                    group,
                    act,
                    quant,
                    res,
                    shift,
                    quant_scale,
                    staged,
                } => {
                    if res.is_some() || shift.is_some() || staged.is_some() {
                        return Err(format!(
                            "emitter: node {i} carries a fused addend; emit before fusion"
                        ));
                    }
                    if quant_scale.is_some() {
                        return Err(format!(
                            "emitter: node {i} carries a fused quantize; emit before fusion"
                        ));
                    }
                    if matches!(act, Act::PRelu(_)) {
                        return Err(format!("emitter: node {i} is a PReLU"));
                    }
                    let shape = recorded.shapes.get(out.0).copied().ok_or_else(|| {
                        format!("emitter: node {i} output {} has no shape", out.0)
                    })?;
                    nodes_bytes.push(TAG_CONV_INT8);
                    let input_at = position(&mut order, input.0);
                    let out_at = position(&mut order, out.0);
                    // Quantised kernels are word-addressed: flag the lookup so it
                    // normalises to bytes before comparing. Lengths are element
                    // counts, as the table records them.
                    let in_shape =
                        recorded.shapes.get(input.0).copied().ok_or_else(|| {
                            format!("emitter: node {i} input {} has no shape", input.0)
                        })?;
                    let per_group = in_shape.c / group.max(&1);
                    let w = file_index(
                        *weight,
                        true,
                        shape.c * per_group * kernel.0 * kernel.1,
                        "kernel-int",
                    )?;
                    let s = file_index(*scale, false, shape.c, "scale")?;
                    let b = file_index(*bias, false, shape.c, "bias")?;
                    u32s(
                        &mut nodes_bytes,
                        &[
                            input_at,
                            out_at,
                            w,
                            s,
                            b,
                            kernel.0,
                            kernel.1,
                            stride.0,
                            stride.1,
                            dilation.0,
                            dilation.1,
                            pad.0,
                            pad.1,
                            0,
                            0,
                            *group,
                            act_code(*act)?,
                            match quant {
                                Quant::I8 => 0,
                                Quant::I4 => 1,
                                Quant::Q2K => 2,
                            },
                        ],
                    );
                }
                Node::Binary { kind, a, b, out } => {
                    use crate::nets::Kind;
                    match kind {
                        Kind::Add => nodes_bytes.push(TAG_ADD),
                        Kind::AddBroadcast => nodes_bytes.push(TAG_ADD_BROADCAST),
                        other => {
                            return Err(format!(
                                "emitter: node {i} is {other:?}, not in phase 1"
                            ));
                        }
                    }
                    let a_at = position(&mut order, a.0);
                    let b_at = position(&mut order, b.0);
                    let out_at = position(&mut order, out.0);
                    u32s(&mut nodes_bytes, &[a_at, b_at, out_at]);
                }
                Node::LayerNorm { input, out, gamma, beta, epsilon } => {
                    nodes_bytes.push(TAG_LAYER_NORM);
                    let input_at = position(&mut order, input.0);
                    let out_at = position(&mut order, out.0);
                    let shape =
                        recorded.shapes.get(out.0).copied().ok_or_else(|| {
                            format!("emitter: node {i} output {} has no shape", out.0)
                        })?;
                    let g = file_index(*gamma, false, shape.c, "gamma")?;
                    let be = file_index(*beta, false, shape.c, "beta")?;
                    u32s(
                        &mut nodes_bytes,
                        &[input_at, out_at, g, be, epsilon.to_bits()],
                    );
                }
                Node::GlobalAvgPool { input, out } => {
                    nodes_bytes.push(TAG_GLOBAL_AVG_POOL);
                    let input_at = position(&mut order, input.0);
                    let out_at = position(&mut order, out.0);
                    u32s(&mut nodes_bytes, &[input_at, out_at]);
                }
                Node::Concat { parts, out } if parts.len() == 1 => {
                    // `reshaped`: the single-part form is a relabelling, lowered as
                    // one copy. Multi-part joins are real concatenations, not views.
                    nodes_bytes.push(TAG_RESHAPE);
                    let input_at = position(&mut order, parts[0].0);
                    let out_at = position(&mut order, out.0);
                    u32s(&mut nodes_bytes, &[input_at, out_at]);
                }
                other => {
                    return Err(format!("emitter: node {i} is {other:?}, not in phase 1"));
                }
            }
        }
        // Header: node count, then payloads. (`bytes` is empty until here: node
        // payloads accumulate in `nodes_bytes` first, so the count leads.)
        bytes.extend_from_slice(&(recorded.nodes.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&nodes_bytes);
        // Computed table in assignment order.
        bytes.extend_from_slice(&(order.len() as u32).to_le_bytes());
        for id in &order {
            let shape = recorded.shapes.get(*id).copied().ok_or_else(|| {
                format!("emitter: tensor {id} has no shape")
            })?;
            u32s(&mut bytes, &[shape.c, shape.h, shape.w]);
        }
        // Bindings: inputs and outputs as computed positions.
        let pos = |order: &[usize], id: usize| -> Result<u32, String> {
            order
                .iter()
                .position(|&i| i == id)
                .map(|at| at as u32)
                .ok_or_else(|| format!("emitter: binding {id} was never assigned"))
        };
        bytes.extend_from_slice(&(recorded.inputs.len() as u32).to_le_bytes());
        for id in &recorded.inputs {
            bytes.extend_from_slice(&pos(&order, id.0)?.to_le_bytes());
        }
        bytes.extend_from_slice(&(outputs.len() as u32).to_le_bytes());
        for id in outputs {
            bytes.extend_from_slice(&pos(&order, id.0)?.to_le_bytes());
        }
        // Host tensors: every read file tensor that no node consumed as a weight.
        // `Recorded.read` marks every file tensor the pass touched; node emission
        // above consumed the weights. The remainder are host-side by elimination —
        // and that is exactly `Builder::host_tensor`'s contract from the other side:
        // finish refuses an unread tensor, so every read tensor is either a weight
        // above or named here.
        //
        // Re-derived from the section bytes just written rather than threaded through
        // the emission match: every weight ref sits at a known payload slot per kind
        // tag (slots 2..4 — see the match above), so one walk over `nodes_bytes`
        // collects the used set without a second channel.
        let mut used = vec![false; table.len()];
        {
            let mut at = 0usize;
            for _ in &recorded.nodes {
                let tag = nodes_bytes.get(at).copied().unwrap_or(255);
                at += 1;
                let base = at;
                let slots: &[usize] = match tag {
                    TAG_CONV => &[2, 3],
                    TAG_CONV_INT8 => &[2, 3, 4],
                    TAG_LAYER_NORM => &[2, 3],
                    _ => &[],
                };
                for slot in slots {
                    let o = base + slot * 4;
                    if let Some(field) = nodes_bytes.get(o..o + 4) {
                        let index = u32_of(field) as usize;
                        if let Some(seen) = used.get_mut(index) {
                            *seen = true;
                        }
                    }
                }
                at += match tag {
                    TAG_CONV | TAG_CONV_INT8 => 18 * 4,
                    TAG_ADD | TAG_ADD_BROADCAST => 3 * 4,
                    TAG_LAYER_NORM => 5 * 4,
                    TAG_GLOBAL_AVG_POOL | TAG_RESHAPE => 2 * 4,
                    _ => 0,
                };
            }
        }
        let mut host: Vec<(u32, u32, [u32; 4])> = Vec::new();
        for (index, was_read) in recorded.read.iter().enumerate() {
            if *was_read && !used.get(index).copied().unwrap_or(true) {
                let found = table.tensors.get(index).ok_or_else(|| {
                    format!("emitter: read flag {index} past the table")
                })?;
                let mut dims = [0u32; 4];
                dims[..found.rank as usize].copy_from_slice(&found.dims[..found.rank as usize]);
                host.push((index as u32, found.rank, dims));
            }
        }
        bytes.extend_from_slice(&(host.len() as u32).to_le_bytes());
        for (index, rank, dims) in &host {
            bytes.extend_from_slice(&index.to_le_bytes());
            bytes.extend_from_slice(&rank.to_le_bytes());
            for d in dims {
                bytes.extend_from_slice(&d.to_le_bytes());
            }
        }
        Ok(bytes)
    }
}