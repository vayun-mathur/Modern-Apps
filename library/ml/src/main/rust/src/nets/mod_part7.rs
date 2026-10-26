impl<'a> Builder<'a> {
    /// [`Builder::attn_apply`] plus the value-side relative term.
    pub fn attn_apply_relative(
        &mut self,
        probs: Id,
        v: Id,
        heads: u32,
        table: usize,
        offsets: u32,
    ) -> Id {
        let out = self.mixed(probs, v, heads);
        let shape = self.shape_of(v);
        let probs_shape = self.shape_of(probs);
        if probs_shape.h != probs_shape.w {
            self.fail(format!(
                "a relative value mix over {probs_shape:?}: an offset is `key - query`, so both \
                 are positions in the same sequence"
            ));
        }
        let head_dim = shape.c.checked_div(heads.max(1)).unwrap_or(0);
        self.check_offsets(offsets);
        let table = self.weight(table, &[offsets, head_dim]);
        self.nodes.push(Node::AttnApplyRelative { probs, v, out, heads, table, offsets });
        out
    }

    /// Attention scores over a backward sliding window of `band` keys, as a `[heads, T, band]`
    /// band, with the relative-position term and the `cap` logit softcap fused in.
    ///
    /// `q` and `k` are `[d_model, 1, T]`. Column `j` of query `i` is key `i - (band - 1) + j`,
    /// so only keys at or before the query are ever addressed and the columns that fall before
    /// the sequence are filled with the most negative finite fp16. A band row is therefore an
    /// ordinary softmax domain: follow this with [`Builder::softmax`], not a windowed mode.
    ///
    /// `table` is `[heads, offsets, head_dim]` — **per head**, and one-sided rather than centred
    /// on zero displacement, so [`Builder::attn_scores_relative`]'s shared centred table is a
    /// different tensor and `check_offsets` does not apply. Column `j` reads offset `j + 1`; see
    /// `nets::gemma4_audio::rel_column` for why offset 0 is unreachable and correct.
    ///
    /// `scale` multiplies the whole sum. Pass 1.0 when the caller has already scaled `q` and `k`,
    /// which Gemma 4's audio tower must: it scales the query by a scalar *and* a per-head_dim
    /// vector, and the key by a different scalar, and neither of those can live here — a vector
    /// does not factor out of a dot product, and the key's scalar applies to the content term
    /// but not to the relative one.
    pub fn attn_scores_banded(
        &mut self,
        q: Id,
        k: Id,
        heads: u32,
        band: u32,
        table: usize,
        offsets: u32,
        scale: f32,
        cap: f32,
    ) -> Id {
        let (sq, sk) = (self.shape_of(q), self.shape_of(k));
        if sq.c != sk.c || sq.w != sk.w {
            self.fail(format!(
                "banded attention over q {sq:?} and k {sk:?}: a band is a window into the same \
                 sequence, so both are [d_model, 1, T] with the same T"
            ));
        }
        if sq.h != 1 || sk.h != 1 {
            self.fail(format!(
                "banded attention on {sq:?}: a sequence is [d_model, 1, T], so a height above \
                 one would silently reinterpret the layout"
            ));
        }
        if heads == 0 || !sq.c.is_multiple_of(heads) {
            self.fail(format!("{} channels do not split into {heads} heads", sq.c));
        }
        if band == 0 || band > sq.w {
            self.fail(format!(
                "a band of {band} over a sequence of {}: the window is the keys a query may see, \
                 so it is between one and the whole sequence",
                sq.w
            ));
        }
        // Column `band - 1` reads offset `band`, so the table needs one more entry than the
        // band is wide. The export's is exactly that: twelve attended offsets in thirteen slots.
        if offsets <= band {
            self.fail(format!(
                "{offsets} relative offsets for a band of {band}: column j reads offset j + 1, \
                 so the widest column needs offset {band}"
            ));
        }
        if cap <= 0.0 {
            self.fail(format!("a logit cap of {cap}, which must be positive"));
        }
        let head_dim = sq.c.checked_div(heads.max(1)).unwrap_or(0);
        let table = self.weight(table, &[heads, offsets, head_dim]);
        let out = self.tensor(Shape::new(heads, sq.w, band));
        self.nodes
            .push(Node::AttnScoresBanded { q, k, out, heads, band, table, offsets, scale, cap });
        out
    }

    /// Apply a `[heads, T, band]` band of probabilities to `v`, a `[d_model, 1, T]` sequence.
    ///
    /// The value half of [`Builder::attn_scores_banded`], with the same window: column `j` of
    /// query `i` weights key `i - (band - 1) + j`. Columns that fall before the sequence are
    /// skipped rather than read, which is exact because the softmax already gave them zero.
    pub fn attn_apply_banded(&mut self, probs: Id, v: Id, heads: u32, band: u32) -> Id {
        let (sp, sv) = (self.shape_of(probs), self.shape_of(v));
        if sp.c != heads || sp.w != band {
            self.fail(format!(
                "a banded value mix over probs {sp:?}: {heads} heads and a band of {band} means \
                 [{heads}, T, {band}]"
            ));
        }
        if sv.h != 1 || sp.h != sv.w {
            self.fail(format!(
                "a banded value mix over probs {sp:?} and v {sv:?}: one row per query and one \
                 value per key, and a band's queries and keys are the same sequence"
            ));
        }
        if heads == 0 || !sv.c.is_multiple_of(heads) {
            self.fail(format!("{} channels do not split into {heads} heads", sv.c));
        }
        let out = self.tensor(Shape::new(sv.c, 1, sv.w));
        self.nodes.push(Node::AttnApplyBanded { probs, v, out, heads, band });
        out
    }

    /// A relative table is `2 * window + 1` entries centred on zero displacement.
    ///
    /// Only the parity is checked. The table's size is deliberately *not* related to the
    /// sequence length: for a one-phoneme utterance only the centre entry is ever reachable
    /// and the other eight go unused, which is correct rather than an error — "a" is a word.
    fn check_offsets(&mut self, offsets: u32) {
        if offsets == 0 || offsets.is_multiple_of(2) {
            self.fail(format!(
                "{offsets} relative offsets: the table is 2 * window + 1 entries centred on \
                 zero displacement, so an even count has no centre"
            ));
        }
    }

    /// Apply `probs`, a `[heads, T, T]` score map, to `v`, a `[d_model, 1, T]` sequence.
    /// [`Builder::attn_apply`] where `kv_heads` heads supply the values.
    pub fn attn_apply_grouped(&mut self, probs: Id, v: Id, heads: u32, kv_heads: u32) -> Id {
        let sv = self.shape_of(v);
        let sp = self.shape_of(probs);
        // The output is the **query** side's width: `heads * head_dim`, where V is only
        // `kv_heads * head_dim`. Taking it from V would silently produce a narrower tensor.
        let head_dim = sv.c.checked_div(kv_heads.max(1)).unwrap_or(0);
        let out = self.tensor(Shape::new(heads * head_dim, 1, sp.h));
        self.nodes.push(Node::AttnApply { probs, v, out, heads, kv_heads });
        out
    }

    pub fn attn_apply(&mut self, probs: Id, v: Id, heads: u32) -> Id {
        let out = self.mixed(probs, v, heads);
        self.nodes.push(Node::AttnApply { probs, v, out, heads, kv_heads: heads });
        out
    }

    /// The validation and output tensor every weighted sum shares, relative or not.
    ///
    /// The output is one vector per **query**, so its width comes from the score map's height
    /// rather than from `v`. For self-attention those are the same number.
    fn mixed(&mut self, probs: Id, v: Id, heads: u32) -> Id {
        let (sp, sv) = (self.shape_of(probs), self.shape_of(v));
        if sp.c != heads || sp.w != sv.w {
            self.fail(format!(
                "attention weights {sp:?} do not match {heads} heads over a sequence of \
                 {} from {sv:?}",
                sv.w
            ));
        }
        if sv.h != 1 {
            self.fail(format!("attention values {sv:?} are not [d_model, 1, T]"));
        }
        if heads == 0 || !sv.c.is_multiple_of(heads) {
            self.fail(format!("{} channels do not split into {heads} heads", sv.c));
        }
        self.tensor(Shape::new(sv.c, 1, sp.h))
    }

    /// Pack the arena and resolve every offset, with `outputs` as the result tensors.
    pub fn finish(self, outputs: &[Id]) -> Result<Plan, String> {
        // `finish` needs no file table: it resolves offsets, never indices. The
        // emitter passes the table; the plan path passes an empty stand-in the
        // recording never consults.
        let recorded = self.record(outputs, &crate::weights::Offsets::empty())?;
        Ok(recorded.plan)
    }

    /// Run the recording pipeline up to but excluding `Op` emission, for the graph
    /// emitter.
    ///
    /// `finish` is record-then-emit; the emitter needs the recorded graph (nodes with
    /// resolved weight file indices, shapes, and bindings) without the `Plan`. Split
    /// out so both share the fusion fold, the liveness, and the every-tensor rule —
    /// the emitter must see exactly the graph the plan would have been built from, or
    /// the equivalence test is circular.
    ///
    /// `offsets` is unused today: nodes already carry resolved offsets and the emitter
    /// inverts them through its own table. It stays in the signature so the recording
    /// can later carry file indices directly (see the `Recorded` docs) without
    /// changing every call site again.
    pub(crate) fn record(
        mut self,
        outputs: &[Id],
        offsets: &crate::weights::Offsets,
    ) -> Result<Recorded, String> {
        let _ = offsets;
        self.pinned.extend_from_slice(outputs);
        if let Some(e) = self.error.take() {
            return Err(e);
        }
        if self.inputs.is_empty() {
            return Err("a pass with no input".into());
        }
        if outputs.is_empty() {
            return Err("a pass with no output".into());
        }
        // Every tensor in the file must have been read, or explicitly declared unread by this
        // pass. An accidentally unread one is the shape of a forward pass that stopped early or
        // skipped a layer, which is otherwise invisible — the file loads, the pass runs, and one
        // layer convolves with whatever its neighbour's weights happen to be.
        if let Some(index) = self.read.iter().position(|&read| !read) {
            return Err(format!(
                "the forward pass never reads tensor {index} of {}. If this pass is one of \
                 several over one file, say so with `Builder::host_tensor`.",
                self.read.len()
            ));
        }

        self.fuse_elementwise(outputs);
        // The quantize fold second: it removes no addends, so it enables no
        // further elementwise fold, and running it after keeps the existing
        // fusion's behaviour first. Gated by the phase fallback flag.
        if quant_fold_on(crate::knobs::get("quant_fold")) {
            self.fuse_quantize(outputs);
        }

        let plan = self.emit_all(outputs)?;
        Ok(Recorded {
            plan,
            nodes: self.nodes,
            shapes: self.shapes,
            inputs: self.inputs,
            pinned: self.pinned,
            read: self.read,
        })
    }

    /// Emit every node to `Op`s, packing the arena along the way.
    ///
    /// The second half of the old `finish`: allocate outputs before freeing inputs,
    /// resolve offsets, and build the bindings. Split out so `record` can stop before
    /// it — the emitter needs nodes and shapes, not dispatches.
    fn emit_all(&self, outputs: &[Id]) -> Result<Plan, String> {
        let last_use = self.last_use();
        let mut arena = Arena::new();
        let mut offsets: Vec<Option<u32>> = vec![None; self.shapes.len()];
        for &Id(id) in &self.pinned {
            let shape = self.shapes.get(id).copied().unwrap_or(Shape::new(0, 0, 0));
            *offsets.get_mut(id).ok_or("pinned id out of range")? =
                Some(arena.alloc(shape.len()));
        }

        let mut ops = Vec::new();
        for (step, node) in self.nodes.iter().enumerate() {
            // Allocate this node's output before freeing its inputs: an op reads and
            // writes the same buffer, so overlapping them would be a data race that
            // no barrier can fix.
            let out = node.out();
            if offsets.get(out.0).copied().flatten().is_none() {
                let shape = self.shapes.get(out.0).copied().ok_or("output id out of range")?;
                *offsets.get_mut(out.0).ok_or("output id out of range")? =
                    Some(arena.alloc(shape.len()));
            }

            let at = |id: Id| -> Result<u32, String> {
                offsets
                    .get(id.0)
                    .copied()
                    .flatten()
                    .ok_or_else(|| format!("step {step} reads tensor {} before it is written", id.0))
            };
            let shape = |id: Id| -> Shape {
                self.shapes.get(id.0).copied().unwrap_or(Shape::new(0, 0, 0))
            };
            self.emit(node, &at, &shape, &mut ops)?;

            for (id, &last) in last_use.iter().enumerate() {
                if last == Some(step) && !self.pinned.contains(&Id(id)) {
                    if let Some(offset) = offsets.get(id).copied().flatten() {
                        let len = self.shapes.get(id).map(|s| s.len()).unwrap_or(0);
                        arena.free(offset, len);
                    }
                }
            }
        }

        let binding = |id: Id| -> Result<Binding, String> {
            let at = offsets
                .get(id.0)
                .copied()
                .flatten()
                .ok_or_else(|| format!("tensor {} was never allocated", id.0))?;
            let shape = self
                .shapes
                .get(id.0)
                .copied()
                .ok_or_else(|| format!("tensor {} has no shape", id.0))?;
            Ok(Binding { at, shape })
        };
        Ok(Plan {
            ops,
            arena_elems: arena.high_water,
            inputs: self.inputs.iter().map(|&id| binding(id)).collect::<Result<_, _>>()?,
            pinned: self.pinned.iter().map(|&id| binding(id)).collect::<Result<_, _>>()?,
            outputs: outputs.iter().map(|&id| binding(id)).collect::<Result<_, _>>()?,
            widen: self.widened_weights(),
        })
    }

    /// Fold single-consumer elementwise adds into their producing convolution.
    ///
    /// A ConvNeXt block is five dispatches — depthwise conv, layer norm, widening pointwise
    /// with GELU, narrowing pointwise, residual add — and Supertonic's sampler holds 28 of
    /// them. The last of the five only adds two tensors that already sit in cache, so the
    /// producing convolution stores `activate(acc + bias) + residual` directly and the add
    /// never becomes a dispatch. Same for the timestep `AddBroadcast`: it adds one value per
    /// channel, so the store adds `arena[shift + channel]` alongside.
    ///
    /// RmsNorm producers fold the same way: a post-norm residual (`add(x, rms(...))`)
    /// stores `norm(input) + residual` directly. Only the `res` half applies (no
    /// activation, no bias, no shift on a norm store).
    ///
    /// Runs to fixpoint because the two chain: the residual add's output feeds the timestep
    /// shift, so the first iteration folds the add into the convolution and the second folds
    /// the shift into the same store. Runs before [`Builder::last_use`] so liveness, the
    /// arena packing and [`Kind::arena_reads`] all see the folded graph rather than the
    /// spelled-out one.
    ///
    /// # What can fold, and what cannot
    ///
    /// The producer must be a `Conv`, `ConvInt8` or `RmsNorm` node whose output the binary is the only
    /// reader of — counted over node inputs *and* plan outputs, since an output tensor has to
    /// survive even when nothing downstream reads it. Anything else (a second reader, a plan
    /// output, a non-folding producer) keeps the add as its own op, which is always
    /// correct and merely one dispatch.
    ///
    /// Only `Add` and `AddBroadcast` fold. A fused multiply would have to round differently
    /// from the unfused pair, and everything else elementwise in these nets already rides in
    /// the convolution's own activation.
    fn fuse_elementwise(&mut self, outputs: &[Id]) {
        loop {
            if !self.fuse_one_elementwise(outputs) {
                return;
            }
        }
    }

    /// One fold, or `false` when no binary qualifies.
    fn fuse_one_elementwise(&mut self, outputs: &[Id]) -> bool {
        let folded = (0..self.nodes.len()).find_map(|i| {
            // The producer's index, the tensor it produced, the addend that folds, and
            // the binary's own output, which the sum moves onto. All `Copy`, so the
            // borrow of `self.nodes` ends here.
            let (index, produced, residual, shift, out) = match &self.nodes[i] {
                Node::Binary { kind: Kind::Add, a, b, out } => {
                    if let Some(index) = producer_of(&self.nodes, *a) {
                        (index, *a, Some(*b), None, *out)
                    } else if let Some(index) = producer_of(&self.nodes, *b) {
                        (index, *b, Some(*a), None, *out)
                    } else {
                        return None;
                    }
                }
                Node::Binary { kind: Kind::AddBroadcast, a, b, out } => {
                    // `add_channel(a, b)` validates `b` as the `C x 1 x 1` shift, so the
                    // producer side is always `a`.
                    let index = producer_of(&self.nodes, *a)?;
                    (index, *a, None, Some(*b), *out)
                }
                _ => return None,
            };
            // A self-add (`add(x, x)`) would fold into a convolution that reads the very
            // tensor it no longer writes — the old output has no writer once the fold
            // moves it. Refused rather than reasoned about: no net builds one.
            if residual == Some(produced) || shift == Some(produced) {
                return None;
            }
            // The addend must already exist when the producer runs. It always does in a
            // residual — unless the "skip" side is itself downstream of the producer. A
            // producer that (transitively) reads the addend would, after the fold, read
            // a tensor whose writer moved downstream of it: a read-before-write the
            // allocator cannot see, since it allocates in node order. SCRFD's neck does
            // exactly this (`add(p4, upsample(p5))` where `p5`'s lateral is the later
            // node). Refused by dataflow: the addend may only depend on nodes strictly
            // before the producer. Inputs and host tensors depend on nothing, so they
            // always qualify.
            let addend_ready = |id: Id| {
                let mut seen = vec![false; self.nodes.len()];
                let mut stack = vec![id];
                while let Some(next) = stack.pop() {
                    // No producing node: an input or a host tensor, written before the
                    // pass runs, so always ready.
                    let Some(node_index) =
                        self.nodes.iter().position(|node| node.out() == next)
                    else {
                        continue;
                    };
                    if node_index >= index {
                        return false;
                    }
                    if seen[node_index] {
                        continue;
                    }
                    seen[node_index] = true;
                    stack.extend(self.nodes[node_index].inputs());
                }
                true
            };
            if !residual.is_none_or(addend_ready) || !shift.is_none_or(addend_ready) {
                return None;
            }
            // The producer's output must reach exactly this binary: a second reader, or
            // the plan holding it as an output, keeps the add unfolded.
            let single = self.nodes.iter().enumerate()
                .filter(|(j, _)| *j != index && *j != i)
                .all(|(_, node)| !node.inputs().contains(&produced))
                && !outputs.contains(&produced);
            single.then_some((i, index, residual, shift, out))
        });
        let Some((i, index, residual, shift, out)) = folded else {
            return false;
        };
        // The output tensor moves onto the producer: it stores the sum directly, and
        // the producer's old output — now unread by anything — is never allocated.
        // A convolution stores `activate(acc + bias) + residual`; an RmsNorm
        // stores `norm(input) + residual` (no activation, no bias, no shift —
        // only the `res` half of the fold applies).
        match &mut self.nodes[index] {
            Node::Conv { out: conv_out, res, shift: fused_shift, .. } => {
                *conv_out = out;
                if residual.is_some() {
                    *res = residual;
                }
                if shift.is_some() {
                    *fused_shift = shift;
                }
            }
            Node::ConvInt8 { out: conv_out, res, shift: fused_shift, .. } => {
                *conv_out = out;
                if residual.is_some() {
                    *res = residual;
                }
                if shift.is_some() {
                    *fused_shift = shift;
                }
            }
            Node::RmsNorm { out: norm_out, res: fused_res, .. } => {
                if shift.is_some() {
                    return false;
                }
                *norm_out = out;
                if residual.is_some() {
                    *fused_res = residual;
                }
            }
            // `producer_of` only returns convolution and norm nodes, so reaching this means the
            // predicate and the application disagree — a bug, and folding nothing is the
            // safe side of it.
            _ => return false,
        }
        self.nodes.remove(i);
        true
    }
}