impl<'a> Builder<'a> {
    /// Start recording a pass. Declare its inputs with [`Builder::input`].
    pub fn new(weights: &'a dyn WeightSource) -> Builder<'a> {
        Builder {
            read: vec![false; weights.count()],
            weights,
            shapes: Vec::new(),
            nodes: Vec::new(),
            pinned: Vec::new(),
            error: None,
            inputs: Vec::new(),
            pad_edge: false,
            spatial_tiled: false,
            widen_int8: false,
            fp32_int4: false,
        }
    }
    /// Keep this net's int4 GEMMs on fp32 activations, off the int8 dot-product path that
    /// `stage_int8` otherwise picks.
    pub fn fp32_int4(&mut self) {
        self.fp32_int4 = true;
    }

    /// Run this net's int8 GEMMs over fp16 copies of their weights, widened at upload.
    ///
    /// Exact (every int8 is an fp16, the sums are unchanged) and faster where the int8 unpack is
    /// the bottleneck, at twice the memory for those weights. [`Plan::widen`] lists them and the
    /// runtime falls back to the int8 path for any it did not widen.
    pub fn widen_int8(&mut self) {
        self.widen_int8 = true;
    }

    /// Lower dense spatial convolutions to the register-blocked path from here on.
    ///
    /// A builder-level mode like [`Builder::edge_padding`]: the vision nets set it once
    /// and every `group == 1` convolution lowers to one invocation per 4-channel x
    /// 8-column block (`shaders/conv_tiled.glsl`) instead of one per output element.
    /// Off by default, so nets that never opt in keep the dispatches they have today.
    ///
    /// Only [`Builder::conv`] consults it; [`Builder::conv_raw`] (graph-section
    /// lowering) leaves it off, which is the previous behaviour there.
    pub fn spatial_tiling(&mut self) {
        self.spatial_tiled = true;
    }

    /// Make every convolution from here on replicate its border instead of reading zeros.
    ///
    /// A builder-level mode rather than an argument on [`Builder::conv`], because a network
    /// either pads this way throughout or not at all: Supertonic''s vocoder puts an ONNX `Pad`
    /// with `mode=edge` in front of all twelve of its convolutions, and threading a flag through
    /// every call site would be noise at each one. See [`Push::pad_edge`] for what goes wrong if
    /// this is missed.
    pub fn edge_padding(&mut self) {
        self.pad_edge = true;
    }

    /// Declare an input of `shape`.
    ///
    /// Callable more than once, in which case [`Plan::inputs`] lists them in this
    /// order and the host must upload them in the same one.
    pub fn input(&mut self, shape: Shape) -> Id {
        let id = self.tensor(shape);
        self.inputs.push(id);
        self.pinned.push(id);
        id
    }

    fn tensor(&mut self, shape: Shape) -> Id {
        self.shapes.push(shape);
        Id(self.shapes.len() - 1)
    }

    /// The first error recorded, so the net modules can chain calls without a `?` on
    /// each of 119 layers and still fail loudly.
    fn fail(&mut self, message: String) {
        if self.error.is_none() {
            self.error = Some(message);
        }
    }

    fn shape_of(&self, id: Id) -> Shape {
        // Ids only come from `tensor`, so this cannot be out of range; a zero shape
        // is returned rather than panicking if a future refactor breaks that, because
        // `finish` will reject the plan anyway.
        self.shapes.get(id.0).copied().unwrap_or(Shape::new(0, 0, 0))
    }

    /// The shape of `id`, for the net modules that need a channel count to size the
    /// next layer — a squeeze-excite's expand stage, for instance.
    pub fn shape(&self, id: Id) -> Shape {
        self.shape_of(id)
    }

    /// A convolution, with ONNX's semantics: weights `[m, in_c/group, kh, kw]`, pads
    /// `[top, left, bottom, right]`, output size floor-divided.
    ///
    /// `weight_index` and `bias_index` are positions in the `.maml` tensor table, so a
    /// net module reads as the ordered list of layers that it is.
    ///
    /// [`Builder::conv_raw`] is the same convolution with resolved offsets rather than
    /// table indices, for lowering a version-2 graph section. Hand-written passes use
    /// this; the section loader uses that; both push the same `Node`.
    #[allow(clippy::too_many_arguments)]
    pub fn conv(
        &mut self,
        input: Id,
        weight_index: usize,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: Act,
    ) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let splits =
            group != 0 && in_shape.c.is_multiple_of(group) && m.is_multiple_of(group);
        if !splits {
            self.fail(format!(
                "tensor {weight_index}: {} in / {m} out channels do not split into \
                 {group} groups",
                in_shape.c
            ));
        }
        let per_group = in_shape.c.checked_div(group).unwrap_or(0);
        let weight = self.weight(weight_index, &[m, per_group, kh, kw]);
        let bias = self.weight(weight_index + 1, &[m]);
        let act_weight = self.act_weight(act, m);
        self.push_conv(
            input,
            weight,
            bias,
            act_weight,
            m,
            kernel,
            stride,
            dilation,
            pads,
            group,
            act,
            false,
            self.pad_edge,
        )
    }

    /// The node push behind [`Builder::conv`] and [`Builder::conv_raw`].
    ///
    /// Split out so the two entry points — table indices for hand-written passes,
    /// resolved offsets for section lowering — share the shape propagation, the output
    /// allocation, and the node construction. The only thing they do differently is how
    /// the weight offsets are obtained.
    #[allow(clippy::too_many_arguments)]
    fn push_conv(
        &mut self,
        input: Id,
        weight: u32,
        bias: u32,
        act_weight: u32,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: Act,
        transpose: bool,
        pad_edge: bool,
    ) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let (pad_t, pad_l, pad_b, pad_r) = pads;
        let out_h = conv_out(in_shape.h, kh, stride.0, dilation.0, pad_t + pad_b);
        let out_w = conv_out(in_shape.w, kw, stride.1, dilation.1, pad_l + pad_r);
        let out = self.tensor(Shape::new(m, out_h, out_w));
        self.nodes.push(Node::Conv {
            input,
            out,
            weight,
            bias,
            kernel,
            stride,
            dilation,
            pad: (pad_t, pad_l),
            group,
            act,
            act_weight,
            transpose,
            pad_edge,
            res: None,
            shift: None,
        });
        out
    }

    /// [`Builder::conv`] with resolved weight offsets rather than table indices.
    ///
    /// The version-2 graph section names file tensors by index, and the section parser
    /// already resolved and shape-checked them — re-resolving here would need the dims
    /// the section deliberately does not carry. So this takes offsets (exactly what
    /// `Offsets::shaped` returns) and `m` (the output channels, from the section's
    /// computed table) and pushes the same `Node::Conv` the indexed path would have.
    #[allow(clippy::too_many_arguments)]
    pub fn conv_raw(
        &mut self,
        input: Id,
        weight: u32,
        bias: u32,
        act_weight: u32,
        m: u32,
        act: Act,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        pad_edge: bool,
    ) -> Id {
        self.push_conv(
            input, weight, bias, act_weight, m, kernel, stride, dilation, pads, group,
            act, false, pad_edge,
        )
    }

    /// [`Builder::conv`] with int8 weights and a per-output-channel dequantisation scale.
    ///
    /// Three tensors rather than two: the int8 kernel at `weight_index`, an `[m]` fp16 scale
    /// after it, and the fp16 bias after that. The scale is its own tensor because a `.maml`
    /// table entry is already full at 32 bytes, and a companion tensor needs no format version
    /// bump.
    ///
    /// The quantisation is symmetric and per output channel, so a value is `int8 * scale` and the
    /// scale multiplies the finished accumulator once. `Act::PRelu` is refused: it would need a
    /// second weights offset, and the push block has one spare which the scale is using.
    #[allow(clippy::too_many_arguments)]
    pub fn conv_int8(
        &mut self,
        input: Id,
        weight_index: usize,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: Act,
    ) -> Id {
        self.conv_quantised(
            input, weight_index, m, kernel, stride, dilation, pads, group, act, Quant::I8,
        )
    }

    /// [`Builder::conv_int8`] with a **four-bit** kernel and a per-block scale.
    ///
    /// The scale tensor is rank 2, `(m, ceil(taps / I4_BLOCK))`, where a tap is one element of
    /// `per_group * kh * kw`. Four bits cannot hold an output row's dynamic range under a single
    /// scale; a block of 32 taps can.
    ///
    /// Only `1 x 1` is offered. The padded and grouped shader has no int4 counterpart, and the
    /// tensors worth quantising this far - projections and feed-forwards - are all pointwise.
    pub fn conv_int4(&mut self, input: Id, weight_index: usize, m: u32, act: Act) -> Id {
        self.conv_quantised(
            input,
            weight_index,
            m,
            (1, 1),
            (1, 1),
            (1, 1),
            (0, 0, 0, 0),
            1,
            act,
            Quant::I4,
        )
    }

    /// [`Builder::conv_int8`] with a **GGUF Q2_K** kernel and a per-superblock scale.
    ///
    /// The scale tensor is `(m * blocks * 2,)` flat — one `(d, dmin)` fp16 pair per 256
    /// taps. Only `1 x 1` is offered, like int4.
    pub fn conv_q2k(&mut self, input: Id, weight_index: usize, m: u32, act: Act) -> Id {
        self.conv_quantised(
            input,
            weight_index,
            m,
            (1, 1),
            (1, 1),
            (1, 1),
            (0, 0, 0, 0),
            1,
            act,
            Quant::Q2K,
        )
    }

    /// The body behind [`Builder::conv_int8`], [`Builder::conv_int4`] and
    /// [`Builder::conv_q2k`]. The three differ only in the scale tensor's rank and in
    /// which shaders the plan lowers to.
    #[allow(clippy::too_many_arguments)]
    fn conv_quantised(
        &mut self,
        input: Id,
        weight_index: usize,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: Act,
        quant: Quant,
    ) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let splits =
            group != 0 && in_shape.c.is_multiple_of(group) && m.is_multiple_of(group);
        if !splits {
            self.fail(format!(
                "tensor {weight_index}: {} in / {m} out channels do not split into \
                 {group} groups",
                in_shape.c
            ));
        }
        if let Act::PRelu(_) = act {
            self.fail(format!(
                "tensor {weight_index}: a quantised convolution cannot carry a PRelu, whose \
                 per-channel slope would need the offset the scale occupies"
            ));
        }
        let per_group = in_shape.c.checked_div(group).unwrap_or(0);
        // Q2_K kernels are rank 2 `[out, taps]` in the table (superblocks have no kernel
        // axes to restate); int8/int4 kernels are rank 4 `[out, in, kh, kw]`. The shape
        // check is what stops one being read as the other.
        let weight = match quant {
            Quant::Q2K => match self.weights.shaped_words(weight_index, &[m, per_group]) {
                Ok(offset) => {
                    if let Some(slot) = self.read.get_mut(weight_index) {
                        *slot = true;
                    }
                    offset
                }
                Err(e) => {
                    self.fail(e);
                    0
                }
            },
            _ => match self.weights.shaped_words(weight_index, &[m, per_group, kh, kw]) {
                Ok(offset) => {
                    if let Some(slot) = self.read.get_mut(weight_index) {
                        *slot = true;
                    }
                    offset
                }
                Err(e) => {
                    self.fail(e);
                    0
                }
            },
        };
        let scale = match quant {
            Quant::I8 => self.weight(weight_index + 1, &[m]),
            // One per block of taps, so the table is `(out, blocks)`. Resolved by shape, which is
            // what stops an int8 scale being read as an int4 one.
            Quant::I4 => {
                let blocks = (per_group * kh * kw).div_ceil(crate::weights::I4_BLOCK);
                self.weight(weight_index + 1, &[m, blocks])
            }
            // One `(d, dmin)` pair per superblock, so the table is `(out, blocks, 2)` —
            // flat `(out * blocks * 2,)`. Resolved by shape, which is what stops an int4
            // scale being read as a Q2_K one.
            Quant::Q2K => {
                let blocks = (per_group * kh * kw).div_ceil(crate::weights::Q2K_BLOCK);
                self.weight(weight_index + 1, &[m * blocks * 2])
            }
        };
        let bias = self.weight(weight_index + 2, &[m]);
        self.push_conv_int8(input, weight, scale, bias, m, kernel, stride, dilation, pads, group, act, quant)
    }

    /// The node push behind [`Builder::conv_quantised`] and [`Builder::conv_int8_raw`].
    ///
    /// As [`Builder::push_conv`] is for the fp16 path: the indexed and resolved entry
    /// points share shape propagation and node construction, differing only in how the
    /// weight offsets are obtained.
    #[allow(clippy::too_many_arguments)]
    fn push_conv_int8(
        &mut self,
        input: Id,
        weight: u32,
        scale: u32,
        bias: u32,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: Act,
        quant: Quant,
    ) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let (pad_t, pad_l, pad_b, pad_r) = pads;
        let out_h = conv_out(in_shape.h, kh, stride.0, dilation.0, pad_t + pad_b);
        let out_w = conv_out(in_shape.w, kw, stride.1, dilation.1, pad_l + pad_r);
        let out = self.tensor(Shape::new(m, out_h, out_w));
        let staged = self.stage_int8(input, weight, quant, kernel, stride, (pad_t, pad_l, pad_b, pad_r), group);
        self.nodes.push(Node::ConvInt8 {
            input,
            out,
            weight,
            scale,
            bias,
            kernel,
            stride,
            dilation,
            pad: (pad_t, pad_l),
            group,
            act,
            quant,
            res: None,
            shift: None,
            quant_scale: None,
            staged,
        });
        out
    }

    /// The int8 blocks an int4 GEMM over `input` reads on the dot-product path, or `None` when
    /// it stays fp32: decode (one position), shapes the dot shader cannot tile, or
    /// `MODELRUNNER_INT8_DOT=0`. Convolutions sharing an input share one staging.
    #[allow(clippy::too_many_arguments)]
    fn stage_int8(
        &mut self,
        input: Id,
        weight: u32,
        quant: Quant,
        kernel: (u32, u32),
        stride: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
    ) -> Option<Id> {
        let s = self.shape_of(input);
        let positions = s.h * s.w;
        let eligible = quant == Quant::I4
            && !self.fp32_int4
            && kernel == (1, 1)
            && stride == (1, 1)
            && pads == (0, 0, 0, 0)
            && group == 1
            && s.c % crate::weights::I4_BLOCK == 0
            && positions >= INT8_DOT_MIN_POSITIONS
            && positions % 4 == 0
            && weight % 4 == 0
            && crate::knobs::get("int8_dot").as_deref() != Some("0");
        if !eligible {
            return None;
        }
        let existing = self.nodes.iter().find_map(|node| match node {
            Node::QuantizeBlocks { input: i, out } if *i == input => Some(*out),
            _ => None,
        });
        if existing.is_some() {
            return existing;
        }
        let out = self.tensor(Shape::new(s.c / 8 * 5, s.h, s.w));
        self.nodes.push(Node::QuantizeBlocks { input, out });
        Some(out)
    }

    /// The int8 dot-product GEMM and its staged input's offset when the builder staged one (see
    /// `stage_int8`); its 32 x 32 tiling is also the fp32 shader's `in1` 0 path, the fallback.
    fn int8_dot(
        kind: Kind,
        staged: Option<Id>,
        at: &dyn Fn(Id) -> Result<u32, String>,
    ) -> Result<(Kind, Option<u32>), String> {
        let staged_at = match staged {
            Some(id) if kind == Kind::ConvPointInt4 => Some(at(id)?).filter(|s| s % 8 == 0),
            _ => None,
        };
        Ok((if staged_at.is_some() { Kind::ConvPointInt4Dot } else { kind }, staged_at))
    }
}

/// Fewest positions an int4 GEMM needs to take the int8 dot-product path.
const INT8_DOT_MIN_POSITIONS: u32 = 8;