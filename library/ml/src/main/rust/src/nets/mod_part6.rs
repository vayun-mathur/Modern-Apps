impl<'a> Builder<'a> {
    pub fn attn_apply_cached_dynamic(&mut self, probs: Id, cache: Id, heads: u32) -> Id {
        self.attn_apply_cached_at(probs, cache, heads, heads, true, true)
    }

    // [`Builder::attn_apply_cached`] with the key count supplied by the step, not the shape.
    //
    // Must be paired with [`Builder::attn_scores_cached_dynamic`] and
    // [`Builder::softmax_prefix`]: all three read the same bound, and mixing a dynamic score map
    // with a full-width sum would fold unattended positions into the result.

    /// [`Builder::attn_apply_cached_dynamic`] where `kv_heads` heads supply the values.
    ///
    /// `out_channels` is the query side's width, `heads * head_dim`, which is what the next
    /// projection reads — it cannot be inferred from a cache that is narrower.
    pub fn attn_apply_cached_grouped(
        &mut self,
        probs: Id,
        cache: Id,
        heads: u32,
        kv_heads: u32,
        sliding: bool,
    ) -> Id {
        self.attn_apply_cached_at(probs, cache, heads, kv_heads, true, sliding)
    }

    fn attn_apply_cached_at(
        &mut self,
        probs: Id,
        cache: Id,
        heads: u32,
        kv_heads: u32,
        dynamic: bool,
        sliding: bool,
    ) -> Id {
        let (sp, sc) = (self.shape_of(probs), self.shape_of(cache));
        if sp.c != heads || sp.h != 1 {
            self.fail(format!("cached attention over probs {sp:?} with {heads} heads"));
        }
        if sc.h != 1 || sp.w != sc.c {
            self.fail(format!(
                "cached attention over probs {sp:?} and a cache {sc:?}: the key counts differ"
            ));
        }
        if heads == 0 || kv_heads == 0 || heads % kv_heads != 0 {
            self.fail(format!("{heads} query heads do not group into {kv_heads} key/value heads"));
        }
        if !sc.w.is_multiple_of(kv_heads.max(1)) {
            self.fail(format!("{} cache channels do not split into {kv_heads} heads", sc.w));
        }
        // The output is the **query** side's width, `heads * head_dim`, which is what the next
        // projection reads. Under grouped- or multi-query attention the cache is narrower than
        // that, so taking the width from the cache would silently produce a shorter tensor.
        let head_dim = sc.w.checked_div(kv_heads.max(1)).unwrap_or(0);
        let out = self.tensor(Shape::new(heads * head_dim, 1, 1));
        self.nodes.push(Node::AttnApplyCached { probs, cache, out, heads, kv_heads, dynamic, sliding, window: 0, quant_scale: None });
        out
    }

    /// The same elements under a different shape, as one contiguous copy.
    ///
    /// A projection writes `[d_model, 1, 1]` and a position-major cache is `[T, 1, d_model]`, so
    /// appending the step's key means reading `d_model` elements as one *position* rather than as
    /// `d_model` channels. Those are the same bytes in the same order, and this is the relabelling.
    ///
    /// A copy rather than a view for the reason [`Builder::slice_channels`] gives: a view would
    /// have to survive the arena's last-use bookkeeping. It is `d_model` elements, and it reuses
    /// the concatenation path exactly — one [`Op::Copy`], no shader and no new op kind.
    pub fn reshaped(&mut self, input: Id, shape: Shape) -> Id {
        let from = self.shape_of(input);
        if from.len() != shape.len() {
            self.fail(format!("reshaping {from:?} to {shape:?} changes the element count"));
        }
        let out = self.tensor(shape);
        self.nodes.push(Node::Concat { parts: vec![input], out });
        out
    }

    /// The validation, output tensor and scale every score map shares, relative or not.
    ///
    /// `q` and `k` may be different lengths: the map is `[heads, queries, keys]`, which for
    /// self-attention is the square `[heads, T, T]` and for a cross-attention is not. They must
    /// still agree on the channel count, since that is what the dot product contracts over.
    fn score_map(&mut self, q: Id, k: Id, heads: u32, kv_heads: u32) -> (Id, f32) {
        let (sq, sk) = (self.shape_of(q), self.shape_of(k));
        if kv_heads == 0 || heads % kv_heads != 0 {
            self.fail(format!("{heads} query heads do not group into {kv_heads} kv heads"));
        }
        // K is `kv_heads` heads wide where Q is `heads` wide, so the channel counts agree only
        // for ordinary multi-head attention. What must always agree is the head dimension.
        if sq.c / heads.max(1) != sk.c / kv_heads.max(1) {
            self.fail(format!("attention over q {sq:?} and k {sk:?} at {kv_heads} kv heads"));
        }
        if sq.h != 1 || sk.h != 1 {
            self.fail(format!(
                "attention on {sq:?}: a sequence is [d_model, 1, T], so a height above \
                 one would silently reinterpret the layout"
            ));
        }
        if heads == 0 || !sq.c.is_multiple_of(heads) {
            self.fail(format!("{} channels do not split into {heads} heads", sq.c));
        }
        let head_dim = sq.c.checked_div(heads).unwrap_or(0);
        let scale = 1.0 / (head_dim.max(1) as f32).sqrt();
        (self.tensor(Shape::new(heads, sq.w, sk.w)), scale)
    }

    /// A tensor that keeps its contents from one execution to the next.
    ///
    /// The arena is one device-local buffer that nothing clears between submits, so a tensor the
    /// allocator never hands to anything else is still holding last execution's values when the
    /// next one starts. That is what lets a decoder keep its KV cache on the device instead of
    /// shipping it back to the host and in again every token.
    ///
    /// Unlike [`Builder::input`] this is **not** a plan input, which is the point: an input is
    /// overwritten from the staging buffer at the top of every recording, so a cache declared as
    /// one would be erased by the very submit meant to extend it.
    ///
    /// # What resets it
    ///
    /// [`crate::vulkan::run::Net::rebuild`] may allocate a larger arena and rebind to it, which
    /// drops the contents. For a decoder that is the correct behaviour and not a hazard: a
    /// rebuild happens when the plan's shape changes, which for NLLB means a new source sentence,
    /// and a new sentence must start from an empty cache anyway.
    ///
    /// # Cost
    ///
    /// Pinned for the whole pass, so it never shares space with an activation. A cache sized for
    /// the maximum context is charged in full to the arena whether or not a sentence reaches it.
    pub fn persistent(&mut self, shape: Shape) -> Id {
        let id = self.tensor(shape);
        self.pinned.push(id);
        id
    }

    /// Write `row` into `cache` at the row the step's prefix names.
    ///
    /// `cache` must come from [`Builder::persistent`] and be `[max_positions, 1, d_model]`; `row`
    /// is the `[d_model, 1, 1]` a projection just produced. Returns nothing, because the cache is
    /// not a value: it is a region later ops read by identity.
    pub fn cache_write(&mut self, row: Id, cache: Id) {
        let (sr, sc) = (self.shape_of(row), self.shape_of(cache));
        // One position, or a whole prefill's worth. The rows are contiguous and the cache is
        // position-major, so writing T of them is the same store with a longer count - see
        // `shaders/cache_write.comp`, which needed no change for this.
        if sc.w == 0 || sr.len() % sc.w != 0 {
            self.fail(format!(
                "appending {sr:?} to a cache {sc:?}: a position is the cache's width, \
                 {} elements, and this is not a whole number of them",
                sc.w
            ));
        }
        if sc.h != 1 {
            self.fail(format!("a cache is [max_positions, 1, d_model], not {sc:?}"));
        }
        self.nodes.push(Node::CacheWrite { row, cache });
    }

    /// Copy `src` into `dst`, a [`Builder::persistent`] tensor of the same shape, as one
    /// [`Op::Copy`].
    ///
    /// For a pass that fills a tensor a *later, differently shaped* plan reads. Unlike
    /// [`Builder::cache_write`] it ignores the step's prefix, which belongs to the other plan.
    pub fn copy_into(&mut self, src: Id, dst: Id) {
        let (ss, sd) = (self.shape_of(src), self.shape_of(dst));
        if ss != sd {
            self.fail(format!("copying {ss:?} into {sd:?}"));
        }
        if !self.pinned.contains(&dst) {
            self.fail("copying into a tensor that is not persistent".into());
        }
        self.nodes.push(Node::Concat { parts: vec![src], out: dst });
    }

    /// Clamp to the `[min, max]` pair at `weight_index`, a `[2]` fp16 tensor.
    ///
    /// The bounds are weights rather than arguments because Gemma 4's vision tower has 177 of
    /// them, each calibrated separately, and `Builder` cannot read a value at build time.
    pub fn clamp(&mut self, input: Id, weight_index: usize) -> Id {
        let bounds = self.weight(weight_index, &[2]);
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::Clamp { input, out, bounds });
        out
    }

    /// Multiply by a **scalar held in the weights**, a `[1]` tensor at `weight_index`.
    ///
    /// Distinct from [`Builder::affine`], whose scale is a compile-time constant. See
    /// [`Kind::MulScalar`].
    pub fn mul_scalar(&mut self, input: Id, weight_index: usize) -> Id {
        let scale = self.weight(weight_index, &[1]);
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::MulScalar { input, out, scale });
        out
    }

    /// An [`Act`] applied on its own, for a value no convolution produced.
    ///
    /// Every other activation in this runtime is folded into the projection before it. A gated
    /// feed-forward is the exception - see [`Kind::Activate`].
    pub fn activate(&mut self, input: Id, act: Act) -> Id {
        if matches!(act, Act::PRelu(_)) {
            self.fail("a standalone PRelu, whose slope is a weight this op does not bind".into());
        }
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::Activate { input, out, act });
        out
    }

    /// `activate(gate) * up` for a fused `[gate | up]` projection, in one op.
    ///
    /// Replaces `slice_channels` twice, an `activate` and a `mul`: four dispatches where three
    /// exist only to hand data to the next. See `shaders/gated_activate.comp`.
    pub fn gated_activate(&mut self, input: Id, act: Act) -> Id {
        let shape = self.shape_of(input);
        if shape.c % 2 != 0 {
            self.fail(format!("a gated activation over {shape:?}, whose channels are not a pair"));
        }
        if matches!(act, Act::PRelu(_)) {
            self.fail("a gated PRelu, whose per-channel slope this does not carry".to_string());
        }
        let out = self.tensor(Shape::new(shape.c / 2, shape.h, shape.w));
        self.nodes.push(Node::GatedActivate { input, out, act });
        out
    }
    /// `tanh(x / cap) * cap`, elementwise. See [`Kind::Softcap`].
    pub fn softcap(&mut self, input: Id, cap: f32) -> Id {
        if !(cap > 0.0) {
            self.fail(format!("a softcap of {cap}, which must be positive"));
        }
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::Softcap { input, out, cap });
        out
    }

    /// The argmax of a logits row: the winning index as two fp16 lanes.
    ///
    /// A device-side greedy sampler (see [`Kind::Argmax`]). The output is a
    /// `[2, 1, 1]` tensor holding `lo = id % 2048` and `hi = id / 2048` as
    /// fp16 values (both exactly representable; a single fp16 slot can hold
    /// neither the value nor the bits past id 2048). The host reconstructs
    /// `hi * 2048 + lo`. Ties keep the FIRST index (strict `>` in the
    /// shader), matching the host `argmax` loop.
    ///
    /// Two dispatches: [`Node::Argmax`] reduces the row to one `(lo, hi)` index pair
    /// per workgroup (see [`argmax_groups`]), then [`Node::ArgmaxFold`] re-reads the
    /// candidates' logits and keeps the greatest, exactly as a single scan would.
    /// One workgroup over 262k logits leaves the GPU idle; 63 workgroups do not.
    ///
    /// The fold re-reads rather than comparing the partials' values so a near-tie the
    /// fp16 store would round is still decided the way the single-stage scan decided
    /// it: on the fp32 values, keeping the first index.
    pub fn argmax(&mut self, input: Id) -> Id {
        let groups = argmax_groups(self.shape_of(input).len());
        let partials = self.tensor(Shape::new(groups * 2, 1, 1));
        self.nodes.push(Node::Argmax { input, out: partials });
        let out = self.tensor(Shape::new(2, 1, 1));
        self.nodes.push(Node::ArgmaxFold { input: partials, row: input, out });
        out
    }

    /// `clamp(round(x / scale), -128, 127) * scale`, elementwise. See [`Kind::Quantize`].
    ///
    /// S10's per-tensor int8 round-trip, applied where the live graph quantizes an
    /// activation (the attention mixture before the O projection). The scale is a
    /// compile-time constant like [`Builder::softcap`]'s cap, for the same reason:
    /// one float per layer, fixed by the source bundle (see `MM_SCALE`).
    pub fn quantize(&mut self, input: Id, scale: f32) -> Id {
        if !(scale > 0.0) {
            self.fail(format!("a quantize of {scale}, whose scale must be positive"));
        }
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::Quantize { input, out, scale });
        out
    }

    /// Softmax over the last axis, which for a score map is one query's distribution.
    pub fn softmax(&mut self, input: Id) -> Id {
        let shape = self.shape_of(input);
        if shape.w == 0 {
            self.fail(format!("a softmax over {shape:?}, whose last axis is empty"));
        }
        let out = self.tensor(shape);
        self.nodes.push(Node::Softmax { input, out, mode: SoftmaxMode::Full, sliding: false, window: 0 });
        out
    }

    /// [`Builder::softmax`] over only the leading `keys` entries of each row, storing zeros past
    /// them: keys padded onto a sequence then contribute nothing to `attn_apply`.
    pub fn softmax_keys(&mut self, input: Id, keys: u32) -> Id {
        let shape = self.shape_of(input);
        if keys == 0 || keys > shape.w {
            self.fail(format!("a softmax over {keys} keys of {shape:?}"));
        }
        let out = self.tensor(shape);
        let window = if keys == shape.w { 0 } else { keys };
        self.nodes.push(Node::Softmax { input, out, mode: SoftmaxMode::Full, sliding: false, window });
        out
    }

    /// The leading `count` positions of a `[C, H, W]` tensor, as a 1x1 average pool (one
    /// dispatch, exact) rather than `C * H` copies.
    pub fn crop_positions(&mut self, input: Id, count: u32) -> Id {
        let shape = self.shape_of(input);
        if count == 0 || count > shape.w {
            self.fail(format!("the first {count} positions of {shape:?}"));
        }
        let out = self.tensor(Shape::new(shape.c, shape.h, count));
        self.nodes.push(Node::AvgPool { input, out, kernel: (1, 1), stride: (1, 1) });
        out
    }

    /// [`Builder::softmax`] with each query's row truncated at the diagonal.
    ///
    /// The input must be a square score map `[heads, T, T]`: a causal mask is a statement about
    /// which *keys* a *query* may read, so queries and keys have to be the same sequence. A
    /// cross-attention map is not square and masking one would be meaningless rather than merely
    /// wrong, which is why this is refused instead of clamped.
    /// [`Builder::softmax_causal`] that also drops keys more than `window - 1` behind the query.
    ///
    /// The sliding half of a batched prefill. `window` of 0 is the plain causal mask.
    pub fn softmax_causal_windowed(&mut self, input: Id, window: u32) -> Id {
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::Softmax {
            input,
            out,
            mode: SoftmaxMode::Causal,
            sliding: false,
            window,
        });
        out
    }

    pub fn softmax_causal(&mut self, input: Id) -> Id {
        let shape = self.shape_of(input);
        if shape.w == 0 {
            self.fail(format!("a causal softmax over {shape:?}, whose last axis is empty"));
        }
        if shape.h != shape.w {
            self.fail(format!(
                "a causal softmax over {shape:?}: the mask is over one sequence attending to \
                 itself, so the map is [heads, T, T]"
            ));
        }
        let out = self.tensor(shape);
        self.nodes.push(Node::Softmax { input, out, mode: SoftmaxMode::Causal, sliding: false, window: 0 });
        out
    }

    /// [`Builder::softmax`] over only the leading `prefix + 1` entries of each row.
    ///
    /// For a decode plan built once at a maximum context: the row is `shape.w` wide, but the step
    /// supplies how much of it was written. See [`Kind::SoftmaxPrefix`].
    pub fn softmax_prefix(&mut self, input: Id, sliding: bool) -> Id {
        let shape = self.shape_of(input);
        if shape.w == 0 {
            self.fail(format!("a prefix softmax over {shape:?}, whose last axis is empty"));
        }
        let out = self.tensor(shape);
        self.nodes.push(Node::Softmax { input, out, mode: SoftmaxMode::Prefix, sliding, window: 0 });
        out
    }

    /// Channels `start .. start + count` of `input`, as a tensor of its own.
    ///
    /// One copy and no shader: a channel range of a `[C, H, W]` tensor is contiguous, so this
    /// is a element-range move like the one [`Builder::concat`] already uses. It is a copy
    /// rather than a view because a view would have to survive the arena's last-use
    /// bookkeeping, and the ranges here are a few hundred kilobytes.
    pub fn slice_channels(&mut self, input: Id, start: u32, count: u32) -> Id {
        let shape = self.shape_of(input);
        if count == 0 || start.saturating_add(count) > shape.c {
            self.fail(format!(
                "channels {start}..{} of {shape:?}",
                start.saturating_add(count)
            ));
        }
        let out = self.tensor(Shape::new(count, shape.h, shape.w));
        self.nodes.push(Node::SliceChannels { input, out, start });
        out
    }

    /// Declare that a tensor is read on the **host** rather than by the plan.
    ///
    /// [`Builder::finish`] refuses a file with an unread tensor, because that is what a forward
    /// pass which skipped a layer looks like from the outside. A few tensors legitimately never
    /// reach a shader: Supertonic's sampler conditions on a timestep embedding that is a function
    /// of two scalars, and on classifier-free-guidance tokens that differ per branch, so the host
    /// evaluates those and passes the results in as plan inputs. Naming them here keeps the
    /// invariant — nothing is *accidentally* unread — and puts the list in the net module beside
    /// the code that uses it.
    ///
    /// It also covers one file holding **several** passes. SMaLL-100 is one `.maml` and three
    /// plans — an encoder pass, a decode step and the logits projection — so no single one of them
    /// reads every tensor, and each names the others' as host tensors. The invariant that nothing
    /// is unread then has to be checked over the *union* of the plans, which is a test the net
    /// module owes because that is where the layout is; `nets::nllb` has it.
    pub fn host_tensor(&mut self, weight_index: usize, dims: &[u32]) {
        // Through `weight` so the shape is checked against the file like any other tensor.
        self.weight(weight_index, dims);
    }

    /// `a + b` where `b` is `C x 1 x 1`, a per-channel shift. See [`Kind::AddBroadcast`].
    pub fn add_channel(&mut self, a: Id, b: Id) -> Id {
        let (sa, sb) = (self.shape_of(a), self.shape_of(b));
        if sb.c != sa.c || sb.h != 1 || sb.w != 1 {
            self.fail(format!("add_channel of {sa:?} by {sb:?}, which is not Cx1x1"));
        }
        let out = self.tensor(sa);
        self.nodes.push(Node::Binary { kind: Kind::AddBroadcast, a, b, out });
        out
    }

    /// Rotary position embedding over a `[C, 1, W]` sequence. See [`Kind::Rotary`].
    ///
    /// `angles` is `[head_dim, 1, W]`: the cosines in its first `head_dim / 2` channels and the
    /// sines in the rest, one column per position.
    pub fn rotary(&mut self, input: Id, angles: Id, heads: u32) -> Id {
        self.rotary_axes(input, angles, heads, 1)
    }

    /// [`Builder::rotary`] over `axes` independent blocks inside each head.
    ///
    /// A 2-D position needs two rotations, not one over twice the channels: Gemma 4's vision
    /// tower rotates the first half of a 64-wide head by the patch's column and the second half
    /// by its row. Rotating the head as a single block would pair a row channel with a column
    /// channel - no shape error, and an encoder that is subtly position-blind.
    ///
    /// The angle table stays `[head_dim, 1, T]`, read as `axes` consecutive blocks of
    /// `head_dim / axes`, each cosines-then-sines.
    pub fn rotary_axes(&mut self, input: Id, angles: Id, heads: u32, axes: u32) -> Id {
        let (sx, sa) = (self.shape_of(input), self.shape_of(angles));
        if sx.h != 1 || sa.h != 1 {
            self.fail(format!("rotary on {sx:?}: a sequence is [d_model, 1, T]"));
        }
        if heads == 0 || !sx.c.is_multiple_of(heads) {
            self.fail(format!("{} channels do not split into {heads} heads", sx.c));
        }
        let head_dim = sx.c.checked_div(heads.max(1)).unwrap_or(0);
        if axes == 0 || !head_dim.is_multiple_of(axes.max(1)) {
            self.fail(format!(
                "rotary over a head of {head_dim} in {axes} blocks, which does not divide"
            ));
        }
        let block = head_dim.checked_div(axes.max(1)).unwrap_or(0);
        if block == 0 || !block.is_multiple_of(2) {
            self.fail(format!(
                "rotary over a block of {block}: it rotates 2-planes, so the block must be even"
            ));
        }
        if sa.c != head_dim || sa.w != sx.w {
            self.fail(format!(
                "rotary angles {sa:?} for {sx:?} in {heads} heads: the table is \
                 [head_dim, 1, T], cosines then sines"
            ));
        }
        let out = self.tensor(sx);
        self.nodes.push(Node::Rotary { input, angles, out, heads, axes });
        out
    }
}
