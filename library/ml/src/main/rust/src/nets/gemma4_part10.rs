/// Name the final norm as host-read, for the modes that stop before the head.
///
/// [`Mode::Trace`] and [`Mode::Prefill`] both end after a layer rather than after the
/// norm, and `Builder::finish` refuses a plan that leaves a tensor unread.
fn name_head(b: &mut Builder) {
    b.host_tensor(FINAL_NORM, &[D_MODEL]);
}

/// Name every tensor of layer `index` as host-read, for the modes that skip it.
///
/// `Builder::finish` refuses a plan that leaves a tensor unread, and rightly so. This says "the
/// host owns these", which keeps that invariant meaningful for the layers the plan *does* run.
///
/// Mirrors [`super::declare_layer`] tensor for tensor: split gate/ff1/linear, no
/// `v_norm`, plus the trailing `skip` scalar. A shape here that drifted from the
/// declaration would name a tensor the file does not hold at that index.
fn name_layer(b: &mut Builder, index: usize) {
    let dim = head_dim(index);
    let inner = ffn(index);
    let blocks = |inp: u32| inp.div_ceil(crate::weights::I4_BLOCK);
    let mut next = layer_at(index);
    let plain = |b: &mut Builder, n: &mut usize, dims: &[u32]| {
        b.host_tensor(*n, dims);
        *n += 1;
    };
    let proj4 = |b: &mut Builder, n: &mut usize, out: u32, inp: u32| {
        b.host_tensor(*n, &[out, inp, 1, 1]);
        b.host_tensor(*n + 1, &[out, blocks(inp)]);
        b.host_tensor(*n + 2, &[out]);
        *n += PROJECTION_TENSORS;
    };
    let proj8 = |b: &mut Builder, n: &mut usize, out: u32, inp: u32| {
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

/// [`Mode::Tail`]'s inputs for column `at` of a `width`-wide prefill: that column of the
/// residual the prefill returned and of the prefill's inputs 1..3, then of input 0 when the plan
/// combines in-plan ([`combines_in_plan`]).
pub fn tail_inputs(residual: &[f32], inputs: [&[f32]; 4], at: usize, width: usize, fused: bool) -> Vec<Vec<f32>> {
    use super::gemma4_gather::column;
    let mut out = vec![column(residual, at, width)];
    out.extend(inputs[1..].iter().map(|block| column(block, at, width)));
    if fused {
        out.push(column(inputs[0], at, width));
    }
    out
}

#[cfg(test)]
mod tail_tests {
    use super::*;
    use crate::nets::tests::Shapes;
    use crate::nets::{Kind, Op};

    const TEST_CONTEXT: u32 = CONTEXT_TIERS[0];

    fn count(plan: &Plan, wanted: Kind) -> usize {
        plan.ops.iter().filter(|op| matches!(op, Op::Dispatch { kind, .. } if *kind == wanted)).count()
    }

    #[test]
    fn a_prefill_and_its_tail_split_the_layers_at_the_last_cache_owner() {
        for tensors in [TENSORS, TENSORS_WITH_COMBINE] {
            let source = Shapes::new(tensors);
            let decode = build(&source, Mode::DecodeStep.at(TEST_CONTEXT)).expect("decode builds");
            let prefill = build(&source, Mode::Prefill { tokens: 8 }.at(TEST_CONTEXT)).expect("prefill builds");
            let tail = build(&source, Mode::Tail.at(TEST_CONTEXT)).expect("tail builds");
            assert_eq!(count(&prefill, Kind::AttnScoresCached), OWNS_CACHE_LAYERS);
            assert_eq!(count(&tail, Kind::AttnScoresCached), LAYERS - OWNS_CACHE_LAYERS);
            assert_eq!(count(&prefill, Kind::CacheWrite), count(&decode, Kind::CacheWrite), "every cache");
            assert_eq!(count(&tail, Kind::CacheWrite), 0, "the tail's layers own no cache");
            assert_eq!(prefill.outputs[0].shape, Shape::new(D_MODEL, 1, 8), "the residual, every column");
            assert_eq!(tail.outputs[0].shape, decode.outputs[0].shape, "the normed state");
            let fused = tensors == TENSORS_WITH_COMBINE;
            assert_eq!(tail.inputs.len(), INPUTS + usize::from(fused));
            assert!(tail.inputs.iter().all(|input| input.shape.w == 1));
            // At most the one extra input: warm-up records it, so it never grows a filled arena.
            let slack = if fused { D_MODEL } else { 0 };
            assert!(
                tail.arena_elems <= decode.arena_elems + slack,
                "tail {} against decode {}",
                tail.arena_elems,
                decode.arena_elems
            );
        }
    }

    #[test]
    fn the_tail_reads_the_caches_where_the_prefill_wrote_them() {
        let source = Shapes::new(TENSORS);
        let reads = |plan: &Plan| -> Vec<u32> {
            plan.ops
                .iter()
                .filter_map(|op| match op {
                    Op::Dispatch { kind: Kind::AttnScoresCached | Kind::AttnApplyCached, push, .. } => Some(push.in1),
                    _ => None,
                })
                .collect()
        };
        let writes = |plan: &Plan| -> Vec<u32> {
            plan.ops
                .iter()
                .filter_map(|op| match op {
                    Op::Dispatch { kind: Kind::CacheWrite, push, .. } => Some(push.out),
                    _ => None,
                })
                .collect()
        };
        let prefill = build(&source, Mode::Prefill { tokens: 8 }.at(TEST_CONTEXT)).expect("prefill builds");
        let tail = build(&source, Mode::Tail.at(TEST_CONTEXT)).expect("tail builds");
        let written = writes(&prefill);
        let read = reads(&tail);
        assert_eq!(read.len(), 2 * (LAYERS - OWNS_CACHE_LAYERS));
        assert!(read.iter().all(|at| written.contains(at)), "tail reads {read:?}, prefill wrote {written:?}");
    }

    #[test]
    fn tail_inputs_take_one_column_of_each_block() {
        let width = 3;
        let block = |c: usize| (0..c * width).map(|i| i as f32).collect::<Vec<f32>>();
        let (residual, hidden, rows, local, global) = (block(2), block(2), block(3), block(1), block(1));
        let plain = tail_inputs(&residual, [&hidden, &rows, &local, &global], 2, width, false);
        assert_eq!(plain, vec![vec![2.0, 5.0], vec![2.0, 5.0, 8.0], vec![2.0], vec![2.0]]);
        let fused = tail_inputs(&residual, [&hidden, &rows, &local, &global], 1, width, true);
        assert_eq!(fused.len(), 5);
        assert_eq!(fused[4], vec![1.0, 4.0]);
    }
}
