impl Graph {

    /// Lower the section to builder nodes, for `finish`'s shared pipeline.
    ///
    /// Phase 1 covers single-branch, shape-fixed graphs: inputs are declared in order and
    /// every node replays through the matching `Builder` call. `finish` then fuses,
    /// packs, and resolves exactly as it would for a hand-written pass — which is what
    /// makes the equivalence test meaningful rather than circular.
    ///
    /// `emit` records one entry per node for the converter (see
    /// `scripts/ml/maml_convert.py`): the node's kind and its file/computed refs, in
    /// execution order. The emitter is the section writer's checklist — every `lower`
    /// arm below has a matching `emit` entry, and vice versa.
    pub fn lower(
        &self,
        builder: &mut crate::nets::Builder,
        table: &crate::weights::Offsets,
    ) -> Result<Vec<crate::nets::Id>, String> {
        use crate::nets::{Act, Shape};
        let shape_of = |c: [u32; 3]| Shape::new(c[0], c[1], c[2]);
        // Computed index -> builder Id, in section order. Inputs are declared first so
        // their indices are dense from zero; every other computed tensor arrives via
        // its producing node.
        let mut ids: Vec<Option<crate::nets::Id>> = vec![None; self.computed.len()];
        for &index in &self.inputs {
            let shape = self.computed.get(index as usize).ok_or_else(|| {
                format!("input names computed tensor {index} of {}", self.computed.len())
            })?;
            ids[index as usize] = Some(builder.input(shape_of(*shape)));
        }
        let id = |ids: &[Option<crate::nets::Id>], index: u32| -> Result<crate::nets::Id, String> {
            ids.get(index as usize).copied().flatten().ok_or_else(|| {
                format!("computed tensor {index} read before it is written")
            })
        };
        // A file index, range-checked. The section parser already validated shapes
        // against the table; lowering resolves the same indices to offsets through it.
        let offset = |table: &crate::weights::Offsets, index: u32| -> Result<u32, String> {
            let found = table.tensor(index as usize).map_err(|_| {
                format!("file tensor {index} of {}", table.len())
            })?;
            Ok(found.elem_offset())
        };
        // A word-unit offset, for quantised kernels addressed through the 32-bit view.
        let word_offset =
            |table: &crate::weights::Offsets, index: u32| -> Result<u32, String> {
                let found = table.tensor(index as usize).map_err(|_| {
                    format!("file tensor {index} of {}", table.len())
                })?;
                Ok(found.word_offset())
            };
        // The raw conv entry points below resolve offsets without asking the
        // builder's weight source, so nothing would mark the consumed file
        // tensors as read and `finish`'s every-tensor rule would refuse every
        // lowered section at its first kernel. Lowering is the authority on
        // what the pass reads — its nodes — so name them as they are replayed.
        // (`host_tensor` only shape-checks and marks; the emitter's host list
        // is derived as read-minus-node-consumed, so device weights named
        // here are never misreported as host tensors.)
        let mark_read = |builder: &mut crate::nets::Builder, index: u32| {
            if let Ok(found) = table.tensor(index as usize) {
                builder.host_tensor(index as usize, &found.dims[..found.rank as usize]);
            }
        };
        let act_of = |code: u32| -> Result<Act, String> {
            match code {
                0 => Ok(Act::None),
                1 => Ok(Act::Relu),
                2 => Ok(Act::HardSwish),
                3 => Ok(Act::Sigmoid),
                5 => Ok(Act::Clip01),
                6 => Ok(Act::Swish),
                8 => Ok(Act::Gelu),
                // PReLU carries its slope file index separately (see below); the code
                // alone cannot reconstruct it, so it is refused rather than guessed.
                4 => Err("a section PReLU, which needs a slope the payload omits".into()),
                other => Err(format!("activation code {other}")),
            }
        };
        for node in &self.nodes {
            match node {
                GraphNode::Conv {
                    input,
                    out,
                    weight,
                    bias,
                    kernel,
                    stride,
                    dilation,
                    pads,
                    group,
                    act,
                    act_weight,
                    pad_edge,
                } => {
                    if *act == 4 || *act_weight != NO_TENSOR {
                        return Err("a section PReLU, which phase 1 does not lower".into());
                    }
                    let activation = act_of(*act)?;
                    let a = id(&ids, *input)?;
                    // Resolved offsets, not indices: the parser validated shapes, so
                    // lowering only translates addressing. `m` is the section's own
                    // computed output channels — the one thing stated, not derived.
                    let out_shape =
                        self.computed.get(*out as usize).copied().ok_or_else(|| {
                            format!("computed tensor {out} out of range")
                        })?;
                    let produced = builder.conv_raw(
                        a,
                        offset(table, *weight)?,
                        offset(table, *bias)?,
                        NO_TENSOR,
                        out_shape[0],
                        activation,
                        *kernel,
                        *stride,
                        *dilation,
                        *pads,
                        *group,
                        *pad_edge,
                    );
                    mark_read(builder, *weight);
                    mark_read(builder, *bias);
                    if *act_weight != NO_TENSOR {
                        mark_read(builder, *act_weight);
                    }
                    ids[*out as usize] = Some(produced);
                }
                GraphNode::ConvInt8 {
                    input,
                    out,
                    weight,
                    scale,
                    bias,
                    kernel,
                    stride,
                    dilation,
                    pads,
                    group,
                    act,
                    quant,
                } => {
                    let activation = act_of(*act)?;
                    let a = id(&ids, *input)?;
                    let out_shape =
                        self.computed.get(*out as usize).copied().ok_or_else(|| {
                            format!("computed tensor {out} out of range")
                        })?;
                    let produced = builder.conv_int8_raw(
                        a,
                        word_offset(table, *weight)?,
                        offset(table, *scale)?,
                        offset(table, *bias)?,
                        out_shape[0],
                        activation,
                        *kernel,
                        *stride,
                        *dilation,
                        *pads,
                        *group,
                        match *quant {
                            0 => crate::nets::Quant::I8,
                            1 => crate::nets::Quant::I4,
                            2 => crate::nets::Quant::Q2K,
                            other => return Err(format!("quant code {other}")),
                        },
                    );
                    mark_read(builder, *weight);
                    mark_read(builder, *scale);
                    mark_read(builder, *bias);
                    ids[*out as usize] = Some(produced);
                }
                GraphNode::Add { a, b, out } => {
                    let produced = builder.add(id(&ids, *a)?, id(&ids, *b)?);
                    ids[*out as usize] = Some(produced);
                }
                GraphNode::AddBroadcast { a, b, out } => {
                    let produced = builder.add_channel(id(&ids, *a)?, id(&ids, *b)?);
                    ids[*out as usize] = Some(produced);
                }
                GraphNode::LayerNorm { input, out, gamma, beta, epsilon_bits } => {
                    let produced = builder.layer_norm_raw(
                        id(&ids, *input)?,
                        offset(table, *gamma)?,
                        offset(table, *beta)?,
                        f32::from_bits(*epsilon_bits),
                    );
                    mark_read(builder, *gamma);
                    mark_read(builder, *beta);
                    ids[*out as usize] = Some(produced);
                }
                GraphNode::GlobalAvgPool { input, out } => {
                    let from = id(&ids, *input)?;
                    // `Builder::global_avg_pool` derives the output shape from the
                    // input: the section states it, lowering recomputes it, and the
                    // validator already required the two to agree.
                    let produced = builder.global_avg_pool(from);
                    let want = self.computed.get(*out as usize).copied().ok_or_else(|| {
                        format!("computed tensor {out} out of range")
                    })?;
                    let got = builder.shape(produced);
                    if (got.c, got.h, got.w) != (want[0], want[1], want[2]) {
                        return Err(format!(
                            "global pool lowered to {got:?}, section says {want:?}"
                        ));
                    }
                    ids[*out as usize] = Some(produced);
                }
                GraphNode::Reshape { input, out } => {
                    let from = id(&ids, *input)?;
                    let want = self.computed.get(*out as usize).copied().ok_or_else(|| {
                        format!("computed tensor {out} out of range")
                    })?;
                    let produced = builder.reshaped(
                        from,
                        crate::nets::Shape::new(want[0], want[1], want[2]),
                    );
                    ids[*out as usize] = Some(produced);
                }
            }
        }
        // Host tensors named, so `finish`'s every-tensor rule holds over the union.
        for (index, rank, dims) in &self.host {
            table
                .shaped(*index as usize, &dims[..*rank as usize])
                .map_err(|e| format!("host tensor {index}: {e}"))?;
            builder.host_tensor(*index as usize, &dims[..*rank as usize]);
        }
        self.outputs
            .iter()
            .map(|index| {
                id(&ids, *index).map_err(|_| format!("output names computed tensor {index} unwritten"))
            })
            .collect()
    }
}