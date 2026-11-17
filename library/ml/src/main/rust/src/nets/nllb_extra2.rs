use super::nllb::{
    DECODER, DECODER_LAYERS, DECODER_NORM, ENCODER, EPSILON, HEAD,
    HEAD_SPLITS, HEADS, MAX_DECODE_POSITIONS, TENSORS, D_MODEL,
};
use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use super::nllb::{
    cross_kv, declare_cross_caches, feed_forward, name_host_tensors, point, split_classes, Layers,
};

/// One decoder step.
///
/// # Inputs
///
/// One: the current token, `[1024, 1, 1]`, after [`embed_positions`] at `past = cache_len`.
///
/// The encoder's contribution is not an input. [`super::nllb::Mode::Encode`] left every layer's
/// cross-attention K and V in persistent tensors at the same arena offsets this plan declares
/// them at, and the self-attention caches are persistent too.
///
/// # Output
///
/// One: the greedy next token as the `[2, 1, 1]` pair [`Builder::argmax`] writes.
pub(crate) fn decode_step(weights: &dyn WeightSource, src_len: u32) -> Result<Plan, String> {
    if src_len == 0 {
        return Err("a decode step with no source to attend over".into());
    }

    let l = &mut Layers { next: DECODER };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // The decoder plus the tied head, which this pass computes in the same plan, minus the
    // cross-attention K and V projections the encode pass ran. The encoder belongs to that pass.
    let mut read = Vec::new();
    read.push(HEAD..ENCODER);
    let mut at = DECODER;
    for layer in 0..DECODER_LAYERS {
        let skipped = cross_kv(layer);
        read.push(at..skipped.start);
        at = skipped.end;
    }
    read.push(at..TENSORS);
    name_host_tensors(b, &read);

    // First, so the offsets match the encode pass's. See `nllb::declare_cross_caches`.
    let cross = declare_cross_caches(b, src_len);
    let mut x = b.input(Shape::new(D_MODEL, 1, 1));
    // Every layer's self-attention cache pair, sized for the longest decode the loop will run so
    // one recording serves every token. How much is live comes from `StepParams::prefix`.
    let caches: Vec<(Id, Id)> = (0..DECODER_LAYERS)
        .map(|_| {
            let k = b.persistent(Shape::new(MAX_DECODE_POSITIONS, 1, D_MODEL));
            let v = b.persistent(Shape::new(MAX_DECODE_POSITIONS, 1, D_MODEL));
            (k, v)
        })
        .collect();

    for (&(cache_k, cache_v), &(cross_k, cross_v)) in caches.iter().zip(&cross) {
        // Self-attention, pre-norm, against the cache this step is about to extend.
        let normed = b.layer_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, D_MODEL, Act::None);
        let k_new = point(b, l, normed, D_MODEL, Act::None);
        let v_new = point(b, l, normed, D_MODEL, Act::None);
        // A projection writes `[d_model, 1, 1]`; a cache position is `[1, 1, d_model]`. The same
        // bytes, so this is a relabelling and the write below is one contiguous copy.
        let k_row = b.reshaped(k_new, Shape::new(1, 1, D_MODEL));
        let v_row = b.reshaped(v_new, Shape::new(1, 1, D_MODEL));
        b.cache_write(k_row, cache_k);
        b.cache_write(v_row, cache_v);
        let scores = b.attn_scores_cached_dynamic(q, cache_k, HEADS);
        let probs = b.softmax_prefix(scores, true);
        let mixed = b.attn_apply_cached_dynamic(probs, cache_v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);

        // Cross-attention over the K and V the encode pass left behind.
        let normed = b.layer_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, D_MODEL, Act::None);
        // Past this layer's cross K and V projections, which the encode pass read.
        l.next += 6;
        let scores = b.attn_scores(q, cross_k, HEADS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply(probs, cross_v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);

        x = feed_forward(b, l, x);
    }
    if l.next != DECODER_NORM {
        return Err(format!("the decoder claims {} tensors, not {DECODER_NORM}", l.next));
    }
    let state = b.layer_norm(x, l.take(), EPSILON);
    if l.next != TENSORS {
        return Err(format!("the decoder norm ends at {}, not {TENSORS}", l.next));
    }

    // The tied head, in the same plan. The last two splits are one class short.
    let head = &mut Layers { next: HEAD };
    let splits: Vec<Id> = (0..HEAD_SPLITS)
        .map(|split| point(b, head, state, split_classes(split), Act::None))
        .collect();
    if head.next != ENCODER {
        return Err(format!("the head claims {} tensors, not {ENCODER}", head.next));
    }
    // Greedy on the device: the splits are disjoint, in id order, so their concatenation is the
    // whole vocabulary and the argmax index is the token id.
    let row = b.concat(&splits);
    let id = b.argmax(row);
    builder.finish(&[id])
}
