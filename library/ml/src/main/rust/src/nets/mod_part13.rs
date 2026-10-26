/// Fold single-consumer `Quantize` nodes into their producing store.
///
/// A Gemma decode step spends 170 dispatches on int8 round-trips (`quantize.comp`):
/// the attention path runs at live's rounded precision, so every projection output
/// is `clamp(round(x / scale), -128, 127) * scale` before its consumer reads it.
/// Each is elementwise over a tensor that already sits in cache, so the producing
/// convolution, norm or cached apply stores `quantize(value)` directly — via
/// `quant_store` in `common.glsl`, fed by [`Push::param0_bits`] — and the
/// `Quantize` node never becomes a dispatch.
///
/// The fold direction mirrors [`Builder::fuse_elementwise`](super::Builder::fuse_elementwise):
/// the consumer is absorbed into the producer's store, never materialising anything
/// new. And like that fold it runs to fixpoint before liveness, so the arena
/// packing sees the folded graph.
///
/// # What can fold, and what cannot
///
/// The producer must be a `ConvInt8` (which covers the int4, int8 and Q2_K
/// lowerings — all three emit through that node), `RmsNorm` or `AttnApplyCached`
/// node whose output the quantize is the only reader of, counted over node inputs
/// *and* plan outputs. Anything else keeps its `Quantize` as its own op.
///
/// `Node::Conv` (fp16) is deliberately not a producer: no `Quantize` input in
/// Gemma is an fp16 convolution's output, so including it would need quant hooks
/// in shaders with no consumer. A producer that already carries a fused scale
/// (a chained `quantize(quantize(x))`, which no net builds) keeps the outer one
/// rather than dropping a rounding.
///
/// Only `Quantize` folds. A fused multiply would round differently (see the
/// elementwise fold), and a quantize is already the outermost operation of the
/// store it joins: `quantize(activate(acc + bias) + res + shift)`, which is
/// exactly `quantize(add(conv, res))` — the add-fold was already exact, so
/// applying the round-trip to its sum is too.
///
/// # Fallback
///
/// Gated by [`quant_fold_on`]: `MODELRUNNER_QUANT_FOLD=0` (or
/// `debug.modelrunner.quant_fold=0` on Android) restores the discrete
/// `Quantize` dispatches. See [`crate::knobs`].
impl<'a> Builder<'a> {
    /// Fold every foldable `Quantize` into its producer's store.
    fn fuse_quantize(&mut self, outputs: &[Id]) {
        loop {
            if !self.fuse_one_quantize(outputs) {
                return;
            }
        }
    }

    /// One fold, or `false` when no quantize qualifies.
    fn fuse_one_quantize(&mut self, outputs: &[Id]) -> bool {
        let readers = Readers::of(&self.nodes, self.shapes.len());
        let folded = (0..self.nodes.len()).find_map(|i| {
            // The quantize's input, its scale, and its own output, which the
            // rounded value moves onto. All `Copy`, so the borrow ends here.
            let (produced, scale, out) = match &self.nodes[i] {
                Node::Quantize { input, out, scale } => (*input, *scale, *out),
                _ => return None,
            };
            // A self-quantize would fold into a store that reads the tensor it
            // no longer writes. Refused rather than reasoned about: no net
            // builds one.
            if produced == out {
                return None;
            }
            // Elementwise means shape-preserving: a mismatch is a bug elsewhere,
            // and folding it would silently change the tensor the producer writes.
            if self.shape_of(produced) != self.shape_of(out) {
                return None;
            }
            // The producer's index. `None` for anything that is not a
            // quant-fusable store, or one that already carries a scale.
            let index = readers
                .writer(produced)
                .filter(|&w| quant_producer_of(&self.nodes[w..=w], produced).is_some())?;
            // The producer's output must reach exactly this quantize: a second
            // reader, or the plan holding it as an output, keeps the quantize
            // unfolded. Same rule as the elementwise fold.
            let single = readers.only_readers(&self.nodes, produced, [index, i])
                && !outputs.contains(&produced);
            single.then_some((i, index, scale, out))
        });
        let Some((i, index, scale, out)) = folded else {
            return false;
        };
        // The output tensor moves onto the producer: it stores the rounded value
        // directly, and the producer's old output — now unread by anything — is
        // never allocated.
        match &mut self.nodes[index] {
            Node::ConvInt8 { out: conv_out, quant_scale, .. } => {
                *conv_out = out;
                *quant_scale = Some(scale);
            }
            Node::RmsNorm { out: norm_out, quant_scale, .. } => {
                *norm_out = out;
                *quant_scale = Some(scale);
            }
            Node::AttnApplyCached { out: apply_out, quant_scale, .. } => {
                *apply_out = out;
                *quant_scale = Some(scale);
            }
            // `quant_producer_of` only returns those three, so reaching this
            // means the predicate and the application disagree — a bug, and
            // folding nothing is the safe side of it.
            _ => return false,
        }
        self.nodes.remove(i);
        true
    }
}

/// The node that wrote `id`, if it is one this fold can absorb a quantize into.
///
/// `ConvInt8` covers all three quantised lowerings (int8, int4, Q2_K emit
/// through it); `RmsNorm` covers the QI/QO/KO/VO/O sites whose consumer is a
/// norm; `AttnApplyCached` covers the MM site. A producer that already carries
/// a scale is excluded: folding a second one would drop a rounding.
fn quant_producer_of(nodes: &[Node], id: Id) -> Option<usize> {
    nodes.iter().position(|node| {
        let (out, free) = match node {
            Node::ConvInt8 { out, quant_scale, .. } => (*out, quant_scale.is_none()),
            Node::RmsNorm { out, quant_scale, .. } => (*out, quant_scale.is_none()),
            Node::AttnApplyCached { out, quant_scale, .. } => (*out, quant_scale.is_none()),
            _ => return false,
        };
        free && out == id
    })
}

/// Whether the quantize fold runs, from the `quant_fold` debug setting.
///
/// On by default: fusion is the shipping path, and the flag exists as the
/// per-phase fallback the plan requires, not as an experiment. Only an
/// explicit opt-out (`0`, `off` or `false`) disables it; anything else —
/// including unset — folds.
pub(crate) fn quant_fold_on(value: Option<String>) -> bool {
    match value.as_deref() {
        Some("0") | Some("off") | Some("false") => false,
        _ => true,
    }
}
