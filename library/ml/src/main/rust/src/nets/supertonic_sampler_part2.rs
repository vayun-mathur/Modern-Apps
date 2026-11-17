/// Frames [`build_dual_padded`] runs at: `frames` rounded up to a multiple of four.
///
/// `conv_point_int8.comp` reads activations a quad at a time only when the width is a multiple
/// of four; at a ragged width each row costs two loads, and the sampler's 172 int8 GEMMs per
/// step ran ~1.6x slower on a Pixel 8.
pub fn padded_frames(frames: u32) -> u32 {
    frames.next_multiple_of(4)
}

/// [`build_dual`] at [`padded_frames`]`(frames)`, exact on the first `frames` positions.
///
/// The depthwise convolutions are the only ops here that mix positions — both attentions take
/// the frames as queries only, layer norm is over channels — so each one clamps its right edge
/// at `frames - 1` rather than at the padded width (`Push::pad_edge` = 1 + tail, read by
/// `conv.comp`). Real positions then never read the tail, whatever it holds. Inputs and outputs
/// are at the padded width: [`pad_frames`] the latent and query angles in, [`unpad_frames`]
/// the velocities out.
pub fn build_dual_padded(
    weights: &dyn WeightSource,
    frames: u32,
    chars: u32,
) -> Result<Plan, String> {
    let padded = padded_frames(frames);
    let mut plan = build_dual(weights, padded, chars)?;
    let edge = 1 + padded - frames;
    for op in &mut plan.ops {
        if let Op::Dispatch { kind: Kind::Conv, push, .. } = op {
            if push.group == CHANNELS && push.in_w == padded {
                push.pad_edge = edge;
            }
        }
    }
    Ok(plan)
}

/// A channel-major `[channels, frames]` tensor widened to [`padded_frames`] with a zero tail.
pub fn pad_frames(values: &[f32], frames: u32) -> Result<Vec<f32>, String> {
    let (real, padded) = (frames as usize, padded_frames(frames) as usize);
    if real == 0 || values.is_empty() || values.len() % (real) != 0 {
        return Err(format!("{} values are not whole rows of {real} frames", values.len()));
    }
    let mut out = vec![0.0; values.len() / real * padded];
    for (row, chunk) in values.chunks_exact(real).enumerate() {
        out[row * padded..row * padded + real].copy_from_slice(chunk);
    }
    Ok(out)
}

/// The first `frames` positions of each row of a [`padded_frames`]-wide tensor.
pub fn unpad_frames(values: &[f32], frames: u32) -> Result<Vec<f32>, String> {
    let (real, padded) = (frames as usize, padded_frames(frames) as usize);
    if real == 0 || values.is_empty() || values.len() % (padded) != 0 {
        return Err(format!("{} values are not whole rows of {padded} frames", values.len()));
    }
    Ok(values.chunks_exact(padded).flat_map(|row| &row[..real]).copied().collect())
}

#[cfg(test)]
mod padded_tests {
    use super::super::tests::Shapes;
    use super::super::{Kind, Op};
    use super::*;

    #[test]
    fn frames_round_up_to_a_multiple_of_four() {
        assert_eq!(padded_frames(1), 4);
        assert_eq!(padded_frames(49), 52);
        assert_eq!(padded_frames(52), 52);
    }

    #[test]
    fn the_padded_plan_runs_wide_and_clamps_every_depthwise_at_the_real_edge() {
        let (frames, chars) = (49, 24);
        let plan = build_dual_padded(&Shapes::new(TENSORS), frames, chars).expect("builds");
        assert_eq!(plan.inputs[0].shape, Shape::new(LATENT, 1, 52));
        assert_eq!(plan.inputs[5].shape, Shape::new(2 * FREQUENCIES, 1, 52));
        assert_eq!(plan.outputs.len(), 2);
        let edges: Vec<u32> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Conv, push, .. } => Some(push.pad_edge),
                _ => None,
            })
            .collect();
        assert_eq!(edges, vec![4; 2 * BLOCKS]);
    }

    #[test]
    fn an_aligned_length_is_the_plain_dual_plan() {
        let padded = build_dual_padded(&Shapes::new(TENSORS), 52, 24).expect("builds");
        let plain = build_dual(&Shapes::new(TENSORS), 52, 24).expect("builds");
        assert_eq!(padded.ops, plain.ops);
    }

    #[test]
    fn padding_and_unpadding_round_trip() {
        let values: Vec<f32> = (0..6).map(|v| v as f32).collect();
        let wide = pad_frames(&values, 3).expect("pads");
        assert_eq!(wide, vec![0.0, 1.0, 2.0, 0.0, 3.0, 4.0, 5.0, 0.0]);
        assert_eq!(unpad_frames(&wide, 3).expect("unpads"), values);
        assert!(pad_frames(&values[..5], 3).is_err());
        assert!(unpad_frames(&values, 3).is_err());
    }
}
