/// Records a forward pass, then packs and resolves it.
pub struct Builder<'a> {
    weights: &'a dyn WeightSource,
    shapes: Vec<Shape>,
    nodes: Vec<Node>,
    /// Tensors that must keep a stable offset for the whole pass: the inputs and the
    /// outputs. Everything else is free to be reused once its last reader has run.
    pinned: Vec<Id>,
    error: Option<String>,
    inputs: Vec<Id>,
    /// One flag per tensor in the file, set when the pass reads it. See
    /// [`Builder::finish`], which insists every one was.
    read: Vec<bool>,
    /// Whether convolutions replicate their border rather than reading zeros.
    pad_edge: bool,
    /// Whether spatial convolutions lower to the register-blocked tiled path.
    /// See [`Builder::spatial_tiling`].
    spatial_tiled: bool,
    /// See [`Builder::widen_int8`].
    widen_int8: bool,
    /// See [`Builder::fp32_int4`].
    fp32_int4: bool,
}

/// Arena allocations are aligned to this many fp16 elements, i.e. 16 bytes — the same
/// boundary `.maml` aligns its tensors to.
const ALIGN_ELEMS: u32 = 8;
