impl Graph {
    /// Parse and validate `section` against the file's tensor `table`.
    ///
    /// Validation is in dependency order: structure first (counts, tags, payload lengths),
    /// then references (every index resolves), then shapes (recomputed, not trusted). Any
    /// failure is a `String`, like every other load refusal in this module.
    pub fn parse(section: &[u8], table: &[Tensor]) -> Result<Graph, String> {
        let mut cursor = Cursor { bytes: section };
        let count = cursor.u32("node count")? as usize;
        if count == 0 || count > MAX_GRAPH_NODES {
            return Err(format!("graph section claims {count} nodes"));
        }
        let mut nodes = Vec::with_capacity(count.min(1024));
        for i in 0..count {
            let tag = cursor.u8(&format!("node {i} tag"))?;
            let node = match tag {
                TAG_CONV => {
                    let f = cursor.take(72, &format!("node {i} conv"))?;
                    let g = |o: usize| u32_of(&f[o..o + 4]);
                    GraphNode::Conv {
                        input: g(0),
                        out: g(4),
                        weight: g(8),
                        bias: g(12),
                        kernel: (g(16), g(20)),
                        stride: (g(24), g(28)),
                        dilation: (g(32), g(36)),
                        pads: (g(40), g(44), g(48), g(52)),
                        group: g(56),
                        act: g(60),
                        act_weight: g(64),
                        pad_edge: match g(68) {
                            0 => false,
                            1 => true,
                            other => {
                                return Err(format!("node {i} pad_edge {other}, not 0 or 1"))
                            }
                        },
                    }
                }
                TAG_CONV_INT8 => {
                    let f = cursor.take(72, &format!("node {i} conv_int8"))?;
                    let g = |o: usize| u32_of(&f[o..o + 4]);
                    let quant = g(68);
                    if quant > 1 {
                        return Err(format!("node {i} quant {quant}, not 0 or 1"));
                    }
                    GraphNode::ConvInt8 {
                        input: g(0),
                        out: g(4),
                        weight: g(8),
                        scale: g(12),
                        bias: g(16),
                        kernel: (g(20), g(24)),
                        stride: (g(28), g(32)),
                        dilation: (g(36), g(40)),
                        pads: (g(44), g(48), g(52), g(56)),
                        group: g(60),
                        act: g(64),
                        quant,
                    }
                }
                TAG_ADD => {
                    let f = cursor.take(12, &format!("node {i} add"))?;
                    GraphNode::Add {
                        a: u32_of(&f[0..4]),
                        b: u32_of(&f[4..8]),
                        out: u32_of(&f[8..12]),
                    }
                }
                TAG_ADD_BROADCAST => {
                    let f = cursor.take(12, &format!("node {i} add_broadcast"))?;
                    GraphNode::AddBroadcast {
                        a: u32_of(&f[0..4]),
                        b: u32_of(&f[4..8]),
                        out: u32_of(&f[8..12]),
                    }
                }
                TAG_LAYER_NORM => {
                    let f = cursor.take(20, &format!("node {i} layer_norm"))?;
                    GraphNode::LayerNorm {
                        input: u32_of(&f[0..4]),
                        out: u32_of(&f[4..8]),
                        gamma: u32_of(&f[8..12]),
                        beta: u32_of(&f[12..16]),
                        epsilon_bits: u32_of(&f[16..20]),
                    }
                }
                TAG_GLOBAL_AVG_POOL => {
                    let f = cursor.take(8, &format!("node {i} global_avg_pool"))?;
                    GraphNode::GlobalAvgPool {
                        input: u32_of(&f[0..4]),
                        out: u32_of(&f[4..8]),
                    }
                }
                TAG_RESHAPE => {
                    let f = cursor.take(8, &format!("node {i} reshape"))?;
                    GraphNode::Reshape {
                        input: u32_of(&f[0..4]),
                        out: u32_of(&f[4..8]),
                    }
                }
                other => return Err(format!("node {i} has kind tag {other}")),
            };
            nodes.push(node);
        }
        // Computed-shape table.
        let tensor_count = cursor.u32("computed count")? as usize;
        if tensor_count == 0 || tensor_count > MAX_GRAPH_TENSORS {
            return Err(format!("graph section claims {tensor_count} computed tensors"));
        }
        let mut computed = Vec::with_capacity(tensor_count.min(1024));
        for i in 0..tensor_count {
            let f = cursor.take(12, &format!("computed tensor {i}"))?;
            computed.push([u32_of(&f[0..4]), u32_of(&f[4..8]), u32_of(&f[8..12])]);
        }
        // Bindings.
        let inputs = cursor.indices("inputs")?;
        let outputs = cursor.indices("outputs")?;
        if inputs.is_empty() {
            return Err("graph section names no inputs".into());
        }
        if outputs.is_empty() {
            return Err("graph section names no outputs".into());
        }
        let host_count = cursor.u32("host count")? as usize;
        if host_count > MAX_GRAPH_TENSORS {
            return Err(format!("graph section claims {host_count} host tensors"));
        }
        let mut host = Vec::with_capacity(host_count.min(64));
        for i in 0..host_count {
            let f = cursor.take(24, &format!("host tensor {i}"))?;
            let rank = u32_of(&f[4..8]);
            if rank == 0 || rank > 4 {
                return Err(format!("host tensor {i} has rank {rank}"));
            }
            host.push((
                u32_of(&f[0..4]),
                rank,
                [u32_of(&f[8..12]), u32_of(&f[12..16]), u32_of(&f[16..20]), u32_of(&f[20..24])],
            ));
        }
        if !cursor.rest().is_empty() {
            return Err(format!("graph section has {} trailing bytes", cursor.rest().len()));
        }
        let graph = Graph { nodes, computed, inputs, outputs, host };
        graph.validate(table)?;
        Ok(graph)
    }

    /// Reference and shape checks over an already-parsed section.
    ///
    /// Every computed index must resolve into `computed`; every weight ref must resolve
    /// into `table` *at the shape the node implies*; every computed shape must equal the
    /// shape propagation recomputes. This is the part that makes a corrupt section a load
    /// error rather than a wrong answer.
    fn validate(&self, table: &[Tensor]) -> Result<(), String> {
        let computed = |index: u32, what: &str| -> Result<[u32; 3], String> {
            self.computed.get(index as usize).copied().ok_or_else(|| {
                format!("{what} names computed tensor {index} of {}", self.computed.len())
            })
        };
        let weight = |index: u32, dims: &[u32], what: &str| -> Result<(), String> {
            let found = table.get(index as usize).ok_or_else(|| {
                format!("{what} names file tensor {index} of {}", table.len())
            })?;
            let got = &found.dims[..found.rank as usize];
            if got != dims {
                return Err(format!("{what} wants file tensor {index} as {dims:?}, it is {got:?}"));
            }
            Ok(())
        };
        for (i, node) in self.nodes.iter().enumerate() {
            let what = format!("node {i}");
            match node {
                GraphNode::Conv {
                    input,
                    out,
                    weight: w,
                    bias: b,
                    kernel,
                    stride,
                    dilation,
                    pads,
                    group,
                    act,
                    act_weight,
                    pad_edge: _,
                } => {
                    let input_shape = computed(*input, &what)?;
                    let (kh, kw) = *kernel;
                    if *group == 0 {
                        return Err(format!("{what} has no groups"));
                    }
                    if input_shape[0] % (*group) != 0 {
                        return Err(format!(
                            "{what}: {} channels do not split into {group} groups",
                            input_shape[0],
                            group = group
                        ));
                    }
                    let per_group = input_shape[0] / group;
                    // Output channels come from the node's own computed shape — the one
                    // thing the section states rather than derives.
                    let out_shape = computed(*out, &what)?;
                    weight(*w, &[out_shape[0], per_group, kh, kw], &what)?;
                    weight(*b, &[out_shape[0]], &what)?;
                    if *act == 4 {
                        if *act_weight == NO_TENSOR {
                            return Err(format!("{what} is a PReLU with no slope tensor"));
                        }
                        weight(*act_weight, &[out_shape[0], 1, 1], &what)?;
                    } else if *act_weight != NO_TENSOR {
                        return Err(format!("{what} carries a slope without a PReLU"));
                    }
                    let (pad_t, pad_l, pad_b, pad_r) = *pads;
                    let want_h =
                        conv_out_shape(input_shape[1], kh, stride.0, dilation.0, pad_t + pad_b);
                    let want_w =
                        conv_out_shape(input_shape[2], kw, stride.1, dilation.1, pad_l + pad_r);
                    if (out_shape[1], out_shape[2]) != (want_h, want_w) {
                        return Err(format!(
                            "{what}: computed shape {out_shape:?} is not [{}, {want_h}, {want_w}]",
                            out_shape[0]
                        ));
                    }
                }
                GraphNode::ConvInt8 {
                    input,
                    out,
                    weight: w,
                    scale,
                    bias: b,
                    kernel,
                    stride,
                    dilation,
                    pads,
                    group,
                    act,
                    quant,
                } => {
                    if *act == 4 {
                        return Err(format!("{what}: a quantised convolution cannot carry a PReLU"));
                    }
                    let input_shape = computed(*input, &what)?;
                    let (kh, kw) = *kernel;
                    if *group == 0 || input_shape[0] % (*group) != 0 {
                        return Err(format!("{what}: bad groups for {}", input_shape[0]));
                    }
                    let per_group = input_shape[0] / group;
                    let out_shape = computed(*out, &what)?;
                    let kernel_dims = [out_shape[0], per_group, kh, kw];
                    weight(*w, &kernel_dims, &what)?;
                    match quant {
                        0 => weight(*scale, &[out_shape[0]], &what)?,
                        1 => {
                            let blocks = (per_group * kh * kw).div_ceil(I4_BLOCK);
                            weight(*scale, &[out_shape[0], blocks], &what)?;
                        }
                        _ => return Err(format!("{what}: quant {quant}")),
                    }
                    weight(*b, &[out_shape[0]], &what)?;
                    let (pad_t, pad_l, pad_b, pad_r) = *pads;
                    let want_h =
                        conv_out_shape(input_shape[1], kh, stride.0, dilation.0, pad_t + pad_b);
                    let want_w =
                        conv_out_shape(input_shape[2], kw, stride.1, dilation.1, pad_l + pad_r);
                    if (out_shape[1], out_shape[2]) != (want_h, want_w) {
                        return Err(format!(
                            "{what}: computed shape {out_shape:?} mismatches the geometry"
                        ));
                    }
                }
                GraphNode::Add { a, b, out } => {
                    let (sa, sb, so) =
                        (computed(*a, &what)?, computed(*b, &what)?, computed(*out, &what)?);
                    if sa != sb || sa != so {
                        return Err(format!("{what}: add of {sa:?} and {sb:?} into {so:?}"));
                    }
                }
                GraphNode::AddBroadcast { a, b, out } => {
                    let (sa, sb, so) =
                        (computed(*a, &what)?, computed(*b, &what)?, computed(*out, &what)?);
                    if sb[1] != 1 || sb[2] != 1 || sb[0] != sa[0] || sa != so {
                        return Err(format!("{what}: channel add of {sa:?} by {sb:?} into {so:?}"));
                    }
                }
                GraphNode::LayerNorm { input, out, gamma, beta, epsilon_bits: _ } => {
                    let shape = computed(*input, &what)?;
                    let result = computed(*out, &what)?;
                    if shape != result {
                        return Err(format!("{what}: norm of {shape:?} into {result:?}"));
                    }
                    weight(*gamma, &[shape[0]], &what)?;
                    weight(*beta, &[shape[0]], &what)?;
                }
                GraphNode::GlobalAvgPool { input, out } => {
                    let shape = computed(*input, &what)?;
                    let result = computed(*out, &what)?;
                    if result != [shape[0], 1, 1] {
                        return Err(format!("{what}: pool of {shape:?} into {result:?}"));
                    }
                }
                GraphNode::Reshape { input, out } => {
                    let shape = computed(*input, &what)?;
                    let result = computed(*out, &what)?;
                    if shape[0] * shape[1] * shape[2] != result[0] * result[1] * result[2] {
                        return Err(format!("{what}: reshape of {shape:?} into {result:?}"));
                    }
                }
            }
        }
        // Bindings resolve too.
        for (i, index) in self.inputs.iter().enumerate() {
            computed(*index, &format!("input {i}"))?;
        }
        for (i, index) in self.outputs.iter().enumerate() {
            computed(*index, &format!("output {i}"))?;
        }
        for (i, (index, rank, dims)) in self.host.iter().enumerate() {
            let found = table.get(*index as usize).ok_or_else(|| {
                format!("host tensor {i} names file tensor {index} of {}", table.len())
            })?;
            let got = &found.dims[..found.rank as usize];
            let want = &dims[..*rank as usize];
            if got != want {
                return Err(format!("host tensor {i} is {got:?}, not {want:?}"));
            }
        }
        Ok(())
    }
}