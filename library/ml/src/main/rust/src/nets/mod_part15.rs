// Reader and writer indexes for `Builder`'s fold passes.
//
// `fuse_elementwise` and `fuse_quantize` ask, per candidate, which node writes a tensor and how many
// nodes read it. Answered by scanning the node list, that made each fold step quadratic: 30 of the
// 32 ms a Gemma 4 prefill plan took to build on a PC. These answer the same questions from one pass
// per fold step, with the same first-match semantics as the scans they replace.

/// For one snapshot of `nodes`: how many nodes read each tensor, and the first node writing it.
struct Readers {
    /// Per tensor id, the number of nodes whose inputs include it (a node counts once).
    count: Vec<u32>,
    /// Per tensor id, the index of the first node whose output it is.
    writer: Vec<Option<usize>>,
}

impl Readers {
    fn of(nodes: &[Node], tensors: usize) -> Readers {
        let mut count = vec![0u32; tensors];
        let mut writer = vec![None; tensors];
        for (index, node) in nodes.iter().enumerate() {
            let mut inputs = node.inputs();
            inputs.sort_unstable_by_key(|id| id.0);
            inputs.dedup();
            for id in inputs {
                if let Some(slot) = count.get_mut(id.0) {
                    *slot += 1;
                }
            }
            if let Some(slot) = writer.get_mut(node.out().0) {
                slot.get_or_insert(index);
            }
        }
        Readers { count, writer }
    }

    /// The first node writing `id`, as `nodes.iter().position(|n| n.out() == id)`.
    fn writer(&self, id: Id) -> Option<usize> {
        self.writer.get(id.0).copied().flatten()
    }

    /// Whether no node other than `skip` (two indices) reads `id`.
    fn only_readers(&self, nodes: &[Node], id: Id, skip: [usize; 2]) -> bool {
        let mut others = self.count.get(id.0).copied().unwrap_or(0);
        let mut seen = [usize::MAX; 2];
        for (slot, index) in skip.into_iter().enumerate() {
            if seen.contains(&index) {
                continue;
            }
            seen[slot] = index;
            if nodes.get(index).is_some_and(|node| node.inputs().contains(&id)) {
                others = others.saturating_sub(1);
            }
        }
        others == 0
    }
}
