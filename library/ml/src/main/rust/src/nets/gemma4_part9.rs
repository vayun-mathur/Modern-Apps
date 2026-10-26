// The per-layer combine inside the text plans, for text files that carry its weights.
//
// The converter appends a copy of the EMBED file's `SHARED_PROJ` triple and `SHARED_NORM` to
// the text file (`s10_to_maml.emit_combine`). A plan over such a file takes the raw per-layer
// rows as input 1 and combines them in the same submit, so a prompt skips the separate combine
// plan and its readback. Files without them keep the combined-on-the-side input.

/// The appended `SHARED_PROJ` triple: int8 `[LAYERS * PER_LAYER, D_MODEL, 1, 1]`, scale, bias.
pub const COMBINE_PROJ: usize = TENSORS;

/// The appended `SHARED_NORM`, `[PER_LAYER]`.
pub const COMBINE_NORM: usize = COMBINE_PROJ + PROJECTION_TENSORS;

/// Tensors a text file that carries the combine holds.
pub const TENSORS_WITH_COMBINE: usize = COMBINE_NORM + 1;

/// Whether plans over `weights` take raw per-layer rows and combine them themselves.
pub fn combines_in_plan(weights: &dyn WeightSource) -> bool {
    weights.count() == TENSORS_WITH_COMBINE
}

/// `(16 * rows + rms_norm(SHARED_PROJ @ hidden)) / sqrt(2)` per 256-wide group, exactly
/// `gemma4_gather::build_plan_at` over the text file's copy of its tensors, for the leading
/// `layers` groups: the rows of `SHARED_PROJ` past them feed layers the plan does not run.
fn combine_in_plan(b: &mut Builder, hidden: Id, rows: Id, layers: usize) -> Id {
    let wide = PER_LAYER * layers as u32;
    let (projected, rows) = if layers == LAYERS {
        let projected = b.conv_int8(hidden, COMBINE_PROJ, wide, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
        (projected, rows)
    } else {
        // Resolved against the whole tensor, then bound to its leading `wide` output rows: the
        // kernel, scale and bias are all row-major, so those rows are a prefix of each.
        let all = PER_LAYER * LAYERS as u32;
        let weight = b.weights.shaped_words(COMBINE_PROJ, &[all, D_MODEL, 1, 1]).unwrap_or_else(|e| {
            b.fail(e);
            0
        });
        if let Some(slot) = b.read.get_mut(COMBINE_PROJ) {
            *slot = true;
        }
        let scale = b.weight(COMBINE_PROJ + 1, &[all]);
        let bias = b.weight(COMBINE_PROJ + 2, &[all]);
        let one = (1, 1);
        let projected = b.conv_int8_raw(hidden, weight, scale, bias, wide, Act::None, one, one, one, (0, 0, 0, 0), 1, super::Quant::I8);
        (projected, b.slice_channels(rows, 0, wide))
    };
    let normed = b.rms_norm_grouped(projected, COMBINE_NORM, EPSILON, layers as u32);
    let scaled = b.affine(rows, super::gemma4_gather::GATHER_SCALE, 0.0);
    let summed = b.add(scaled, normed);
    b.affine(summed, std::f32::consts::FRAC_1_SQRT_2, 0.0)
}

/// One token's plan inputs 0 and 1 for a text file: the working row and either the raw
/// per-layer rows (`fused`, see [`combines_in_plan`]) or the host-combined block.
pub fn gather_input(
    embed: &crate::weights::Reader<'_>,
    token: u32,
    fused: bool,
) -> Result<(Vec<f32>, Vec<f32>), String> {
    if !fused {
        return gather(embed, token);
    }
    let tokens = [token];
    let hidden = super::gemma4_gather::token_rows(embed, &tokens)?;
    Ok((hidden, super::gemma4_gather::layer_rows(embed, &tokens)?))
}

/// [`gather_soft`]'s plan input 1 for a text file: raw row-`token` rows when `fused`.
pub fn gather_soft_input(
    embed: &crate::weights::Reader<'_>,
    hidden: &[f32],
    token: u32,
    fused: bool,
) -> Result<Vec<f32>, String> {
    if !fused {
        return gather_soft(embed, hidden, token);
    }
    let mut embedded = Vec::with_capacity((PER_LAYER as usize) * LAYERS);
    for i in 0..LAYERS {
        let at = embed::table(i);
        embedded.extend(embed.int4_row(at, at + 1, &[VOCAB, PER_LAYER], token)?);
    }
    Ok(embedded)
}

#[cfg(test)]
mod combine_in_plan_tests {
    use super::*;
    use crate::nets::tests::Shapes;

    const TEST_CONTEXT: u32 = CONTEXT_TIERS[0];

    #[test]
    fn a_text_file_with_the_combine_reads_it_in_every_mode() {
        let source = Shapes::new(TENSORS_WITH_COMBINE);
        assert!(combines_in_plan(&source));
        assert!(!combines_in_plan(&Shapes::new(TENSORS)));
        for mode in [
            Mode::DecodeStep,
            Mode::Prefill { tokens: 8 },
            Mode::PrefillFresh { tokens: 8 },
            Mode::Trace { layers: 0 },
            Mode::TraceTail,
        ] {
            let fused = build(&source, mode.at(TEST_CONTEXT)).expect("builds with the combine");
            let plain = build(&Shapes::new(TENSORS), mode.at(TEST_CONTEXT)).expect("builds without");
            assert_eq!(fused.inputs.len(), plain.inputs.len(), "{mode:?}");
            assert_eq!(fused.inputs[1].shape, plain.inputs[1].shape, "{mode:?}");
            // conv + grouped norm + up to two affines and an add (the add may fold), plus the
            // copy of the leading groups' rows in a prefill.
            let extra = fused.ops.len() - plain.ops.len();
            assert!((3..=6).contains(&extra), "{mode:?} added {extra} ops");
        }
    }
}
