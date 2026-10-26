impl Node {
    pub(crate) fn out(&self) -> Id {
        match self {
            Node::Conv { out, .. }
            | Node::MaxPool { out, .. }
            | Node::AvgPool { out, .. }
            | Node::Resize { out, .. }
            | Node::GlobalAvgPool { out, .. }
            | Node::Binary { out, .. }
            | Node::Affine { out, .. }
            | Node::LayerNorm { out, .. }
            | Node::InstanceNorm { out, .. }
            | Node::RmsNorm { out, .. }
            | Node::AttnScores { out, .. }
            | Node::AttnScoresCached { out, .. }
            | Node::AttnApplyCached { out, .. }
            | Node::AttnScoresRelative { out, .. }
            | Node::AttnApplyRelative { out, .. }
            | Node::AttnScoresBanded { out, .. }
            | Node::AttnApplyBanded { out, .. }
            | Node::Softmax { out, .. }
            | Node::Embed { out, .. }
            | Node::SliceChannels { out, .. }
            | Node::ConvInt8 { out, .. }
            | Node::AttnApply { out, .. }
            | Node::Constant { out, .. }
            | Node::Rotary { out, .. }
            | Node::ConcatPositions { out, .. }
            | Node::Concat { out, .. } => *out,
            | Node::Softcap { out, .. } => *out,
            | Node::Argmax { out, .. }
            | Node::ArgmaxFold { out, .. } => *out,
            | Node::TopK { out, .. } => *out,
            | Node::Quantize { out, .. } => *out,
            | Node::QuantizeBlocks { out, .. } => *out,
            | Node::Activate { out, .. }
            | Node::GatedActivate { out, .. } => *out,
            | Node::MulScalar { out, .. } => *out,
            | Node::Clamp { out, .. } => *out,
            // The cache is the destination, and it is pinned, so `finish` finds it already
            // allocated rather than making a fresh tensor for it.
            Node::CacheWrite { cache, .. } => *cache,
        }
    }

    fn inputs(&self) -> Vec<Id> {
        match self {
            Node::Conv { input, res, shift, .. } => {
                let mut reads = vec![*input];
                reads.extend(res.iter().copied());
                reads.extend(shift.iter().copied());
                reads
            }
            Node::ConvInt8 { input, res, shift, staged, .. } => {
                let mut reads = vec![*input];
                reads.extend(res.iter().copied());
                reads.extend(shift.iter().copied());
                reads.extend(staged.iter().copied());
                reads
            }
            Node::MaxPool { input, .. }
            | Node::AvgPool { input, .. }
            | Node::Resize { input, .. }
            | Node::Affine { input, .. }
            | Node::LayerNorm { input, .. }
            | Node::InstanceNorm { input, .. }
            | Node::Softmax { input, .. }
            | Node::Softcap { input, .. }
            | Node::Argmax { input, .. }
            | Node::Quantize { input, .. }
            | Node::QuantizeBlocks { input, .. }
            | Node::GatedActivate { input, .. }
            | Node::Activate { input, .. }
            | Node::MulScalar { input, .. }
            | Node::Clamp { input, .. }
            | Node::Embed { ids: input, .. }
            | Node::SliceChannels { input, .. }
            | Node::GlobalAvgPool { input, .. } => vec![*input],
            Node::ArgmaxFold { input, row, .. } => vec![*input, *row],
            Node::TopK { input, row, .. } => vec![*input, *row],
            Node::RmsNorm { input, res, .. } => {
                let mut reads = vec![*input];
                reads.extend(res.iter().copied());
                reads
            }
            Node::Binary { a, b, .. } => vec![*a, *b],
            Node::Rotary { input, angles, .. } => vec![*input, *angles],
            Node::AttnScores { q: a, k: b, .. }
            | Node::AttnScoresCached { q: a, cache: b, .. }
            | Node::AttnApplyCached { probs: a, cache: b, .. }
            | Node::AttnApply { probs: a, v: b, .. }
            | Node::AttnScoresRelative { q: a, k: b, .. }
            | Node::AttnScoresBanded { q: a, k: b, .. }
            | Node::AttnApplyBanded { probs: a, v: b, .. }
            | Node::AttnApplyRelative { probs: a, v: b, .. } => {
                vec![*a, *b]
            }
            Node::Concat { parts, .. } => parts.clone(),
            Node::ConcatPositions { parts, .. } => parts.clone(),
            // The cache is listed alongside the row so that neither can be freed under this op.
            // The cache is pinned and so was never a candidate, but saying it here keeps the
            // dependency visible to `last_use` rather than relying on that.
            Node::CacheWrite { row, cache } => vec![*row, *cache],
            // The only op with no arena input at all: it reads the weights file.
            Node::Constant { .. } => Vec::new(),
        }
    }
}

/// The convolution node that wrote `id`, if one did.
///
/// `finish`'s fusion fold only folds into convolutions — the op whose store does the adding —
/// so this is the predicate that names a foldable producer. Anything else (`None`) keeps the
/// binary as its own op.
fn producer_of(nodes: &[Node], id: Id) -> Option<usize> {
    nodes.iter().position(|node| {
        matches!(
            node,
            Node::Conv { .. } | Node::ConvInt8 { .. } | Node::RmsNorm { .. }
        ) && node.out() == id
    })
}

/// `floor((in + pad - dilation * (k - 1) - 1) / stride) + 1`, ONNX's convolution
/// output size.
fn conv_out(input: u32, kernel: u32, stride: u32, dilation: u32, pad_total: u32) -> u32 {
    let effective = dilation * (kernel - 1) + 1;
    let padded = input + pad_total;
    if padded < effective || stride == 0 {
        return 0;
    }
    (padded - effective) / stride + 1
}

/// `(in - 1) * stride + k - pad`, ONNX's transposed-convolution output size at
/// `dilation = 1` and no `output_padding` — which is the only form used.
fn deconv_out(input: u32, kernel: u32, stride: u32, pad_total: u32) -> u32 {
    let full = (input.max(1) - 1) * stride + kernel;
    full.saturating_sub(pad_total)
}

/// A first-fit free-list allocator over the activation arena.
///
/// The arena is one `VkBuffer`, so an "allocation" is an element offset into it. The
/// list is kept sorted and coalesced, which for the few hundred allocations either
/// net makes is far cheaper than the memory it saves: U^2-Netp's live set peaks well
/// below the sum of its intermediates, and holding them all would be tens of MB of
/// device memory for a net that reuses almost everything.
struct Arena {
    free: Vec<(u32, u32)>,
    high_water: u32,
}

impl Arena {
    fn new() -> Arena {
        Arena { free: Vec::new(), high_water: 0 }
    }

    fn alloc(&mut self, len: u32) -> u32 {
        let len = round_up(len.max(1));
        // Best fit. Measured against first fit on both real networks it makes no
        // difference to the high-water mark — U^2-Netp lands on 76 MiB either way — but it
        // is the better default for the shape of these allocations, which range from 64
        // elements to 6.5M, and it costs a linear scan of a list that never exceeds a
        // few dozen entries.
        //
        // The remaining ~30% over the true live set is fragmentation that no fit policy
        // fixes: a freed 13 MiB tensor gets split for a 6.6 MiB request and the halves
        // are then too small for the next 13 MiB one. Closing it needs the graph
        // changed rather than the allocator — see the note on memory in
        // `nets::u2netp`.
        let mut best: Option<usize> = None;
        for (i, &(_, size)) in self.free.iter().enumerate() {
            if size >= len && best.is_none_or(|b| self.free.get(b).is_some_and(|&(_, s)| size < s)) {
                best = Some(i);
            }
        }
        if let Some(i) = best {
            if let Some(&(start, size)) = self.free.get(i) {
                if size == len {
                    let _ = self.free.remove(i);
                } else if let Some(slot) = self.free.get_mut(i) {
                    *slot = (start + len, size - len);
                }
                return start;
            }
        }
        let start = self.high_water;
        self.high_water += len;
        start
    }

    fn free(&mut self, offset: u32, len: u32) {
        let len = round_up(len.max(1));
        let at = self.free.partition_point(|&(start, _)| start < offset);
        self.free.insert(at, (offset, len));
        // Coalesce with both neighbours, so a net that frees a run of equal-sized
        // tensors gets one big block back rather than a fragmented list.
        let mut i = 0;
        while i + 1 < self.free.len() {
            let (a_start, a_len) = self.free.get(i).copied().unwrap_or((0, 0));
            let (b_start, b_len) = self.free.get(i + 1).copied().unwrap_or((0, 0));
            if a_start + a_len == b_start {
                if let Some(slot) = self.free.get_mut(i) {
                    *slot = (a_start, a_len + b_len);
                }
                let _ = self.free.remove(i + 1);
            } else {
                i += 1;
            }
        }
    }
}

fn round_up(len: u32) -> u32 {
    len.div_ceil(ALIGN_ELEMS) * ALIGN_ELEMS
}
