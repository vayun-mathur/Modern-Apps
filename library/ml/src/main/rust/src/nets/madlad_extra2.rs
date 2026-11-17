use super::madlad::{
    DECODER, DECODER_LAYERS, DECODER_NORM, D_MODEL, ENCODER, EPSILON, HEAD_LM,
    HEAD_SPLITS, HEADS, INNER, MAX_DECODE_POSITIONS, TABLE_ENC,
};
use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use super::madlad::{
    cross_attention, cross_kv, declare_cross_caches, feed_forward, name_host_tensors, point,
    split_classes, Layers,
};

/// One decoder step.
///
/// # Inputs, in declaration order
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0 | `[1024, 1, 1]` | the current token, after [`embed_positions`] |
/// | 1 | `[16, 1, MAX]` | the step's self-attention bias row, host-computed (see below) |
///
/// The encoder's contribution is not an input: [`super::madlad::Mode::Encode`] left every layer's
/// cross-attention K and V in persistent tensors at the offsets this plan declares them at.
///
/// # Output
///
/// One: the greedy next token as the `[2, 1, 1]` pair [`Builder::argmax`] writes, from the four
/// `lm_head` splits concatenated on the device. 256,000 logits used to come back every token.
///
/// # The bias input
///
/// A decode step is one query at absolute position `step` against keys `0..=step`, so its
/// bias row is `[HEADS, 1, step + 1]` of live values. The plan is recorded once, so the
/// input is `[HEADS, 1, MAX_DECODE_POSITIONS]` wide and the step uploads the live prefix
/// followed by zeros: `softmax_prefix` normalises only the leading `prefix + 1` entries,
/// and the stale tail never enters the maximum or the sum — the same bound the cached
/// score map already reads.
pub(crate) fn decode_step(weights: &dyn WeightSource, src_len: u32) -> Result<Plan, String> {
    if src_len == 0 {
        return Err("a decode step with no source to attend over".into());
    }

    let l = &mut Layers { next: DECODER };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // The decoder plus the logits head, minus the cross-attention K and V the encode pass ran.
    // The input table, the whole encoder and both bias tables belong elsewhere: the tables are
    // read on the host, never by a shader, so the range ends at TABLE_ENC.
    let mut read = Vec::new();
    read.push(HEAD_LM..ENCODER);
    let mut at = DECODER;
    for layer in 0..DECODER_LAYERS {
        let skipped = cross_kv(layer);
        read.push(at..skipped.start);
        at = skipped.end;
    }
    read.push(at..TABLE_ENC);
    name_host_tensors(b, &read);

    // First, so the offsets match the encode pass's.
    let cross = declare_cross_caches(b, src_len);
    let mut x = b.input(Shape::new(D_MODEL, 1, 1));
    let step_bias = b.input(Shape::new(HEADS, 1, MAX_DECODE_POSITIONS));
    // Every layer's cache pair, held on the device across steps rather than handed in and out.
    //
    // Sized for the longest decode the loop will run, not for this step, which is what lets one
    // recording serve every token: the plan no longer mentions the step number, so
    // `Reshaped::at` stops matching a new key and re-recording on each one. How much of each
    // cache is live comes from `StepParams::prefix` at submit time.
    let caches: Vec<(Id, Id)> = (0..DECODER_LAYERS)
        .map(|_| {
            let k = b.persistent(Shape::new(MAX_DECODE_POSITIONS, 1, INNER));
            let v = b.persistent(Shape::new(MAX_DECODE_POSITIONS, 1, INNER));
            (k, v)
        })
        .collect();

    for (&(cache_k, cache_v), &(cross_k, cross_v)) in caches.iter().zip(&cross) {
        // Self-attention, pre-norm, against the cache this step is about to extend.
        let normed = b.rms_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, INNER, Act::None);
        let k_new = point(b, l, normed, INNER, Act::None);
        let v_new = point(b, l, normed, INNER, Act::None);
        // A projection writes `[inner, 1, 1]`; a cache position is `[1, 1, inner]`. The same
        // bytes, so this is a relabelling and the write below is one contiguous copy.
        let k_row = b.reshaped(k_new, Shape::new(1, 1, INNER));
        let v_row = b.reshaped(v_new, Shape::new(1, 1, INNER));
        // Written into the cache at the row the step names, replacing the concatenation that
        // used to copy the entire prefix on every layer of every token.
        b.cache_write(k_row, cache_k);
        b.cache_write(v_row, cache_v);
        // Prescaled dynamic: T5 applies no 1/sqrt(head_dim), and the key count comes from
        // the step rather than the shape.
        let scores = b.attn_scores_cached_prescaled(q, cache_k, HEADS, HEADS, false);
        let biased = b.add(scores, step_bias);
        let probs = b.softmax_prefix(biased, true);
        let mixed = b.attn_apply_cached_dynamic(probs, cache_v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);

        // Cross-attention over the K and V the encode pass left behind. No bias: cross-attention
        // never carries a relative term.
        let normed = b.rms_norm(x, l.take(), EPSILON);
        x = cross_attention(b, l, x, normed, cross_k, cross_v);

        x = feed_forward(b, l, x);
    }
    if l.next != DECODER_NORM {
        return Err(format!("the decoder claims {} tensors, not {DECODER_NORM}", l.next));
    }
    let state = b.rms_norm(x, l.take(), EPSILON);
    if l.next != TABLE_ENC {
        return Err(format!("the decoder norm ends at {}, not {TABLE_ENC}", l.next));
    }

    // The untied logits head, in the same plan. Four even splits of 64,000, concatenated on the
    // device and reduced to the greedy token there.
    let head = &mut Layers { next: HEAD_LM };
    let splits: Vec<Id> = (0..HEAD_SPLITS)
        .map(|split| point(b, head, state, split_classes(split), Act::None))
        .collect();
    if head.next != ENCODER {
        return Err(format!("the head claims {} tensors, not {ENCODER}", head.next));
    }
    let row = b.concat(&splits);
    let id = b.argmax(row);
    builder.finish(&[id])
}
