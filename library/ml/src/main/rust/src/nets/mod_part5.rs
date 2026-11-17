/// Head/dynamic/scale options for [`Builder::attn_scores_cached_at`].
#[derive(Clone, Copy)]
pub(crate) struct CachedScores {
    /// Query heads.
    pub heads: u32,
    /// Key/value heads supplying the cache.
    pub kv_heads: u32,
    /// Whether the key count is supplied by the step.
    pub dynamic: bool,
    /// Whether to derive `1 / sqrt(head_dim)` here.
    pub derive_scale: bool,
    /// Whether this layer's sliding window applies.
    pub sliding: bool,
}

impl<'a> Builder<'a> {
    fn weight(&mut self, index: usize, dims: &[u32]) -> u32 {
        match self.read.get_mut(index) {
            Some(slot) => *slot = true,
            None => self.fail(format!(
                "tensor {index}: the file holds {}",
                self.weights.count()
            )),
        }
        match self.weights.shaped(index, dims) {
            Ok(offset) => offset,
            Err(e) => {
                self.fail(e);
                0
            }
        }
    }

    /// `2x2` stride-2 max pooling, which is the only pooling either net does.
    ///
    /// ONNX marks these `ceil_mode=1`, but every pooled extent in U^2-Netp is even
    /// (320 halves five times to 10), so ceil and floor agree and the distinction is
    /// deliberately not modelled. [`tests::u2netp_pools_only_even_extents`] holds that.
    pub fn max_pool_2x2(&mut self, input: Id) -> Id {
        let in_shape = self.shape_of(input);
        if in_shape.h % (2) != 0 || in_shape.w % (2) != 0 {
            self.fail(format!(
                "max_pool_2x2 on {}x{}: ceil_mode is not modelled, so an odd extent \
                 would silently drop a row",
                in_shape.h, in_shape.w
            ));
        }
        let out = self.tensor(Shape::new(in_shape.c, in_shape.h / 2, in_shape.w / 2));
        self.nodes.push(Node::MaxPool {
            input,
            out,
            kernel: (2, 2),
            stride: (2, 2),
        });
        out
    }

    /// Average pooling over `kernel` at `stride`, which must tile the input exactly.
    ///
    /// The one use is `(3, 2)` at `(3, 2)` on a `3 x 80` map, so it tiles. Refusing
    /// anything else is deliberate: a window that overhangs makes the divisor a question
    /// — ONNX has `count_include_pad` for it and the two answers differ — and the shader
    /// divides by `kh * kw` unconditionally. A ragged extent would silently scale the
    /// edge of the sequence.
    pub fn avg_pool(&mut self, input: Id, kernel: (u32, u32), stride: (u32, u32)) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let out_h = conv_out(in_shape.h, kh, stride.0, 1, 0);
        let out_w = conv_out(in_shape.w, kw, stride.1, 1, 0);
        for (axis, extent, k, s, out) in [
            ('h', in_shape.h, kh, stride.0, out_h),
            ('w', in_shape.w, kw, stride.1, out_w),
        ] {
            if out == 0 || out.saturating_sub(1) * s + k != extent {
                self.fail(format!(
                    "avg_pool of {k} at stride {s} does not tile {extent} along {axis}, so \
                     the divisor would not be the window size"
                ));
            }
        }
        let out = self.tensor(Shape::new(in_shape.c, out_h, out_w));
        self.nodes.push(Node::AvgPool { input, out, kernel, stride });
        out
    }
    /// Bilinear resize to `like`'s spatial size, which is how both shipping nets always
    /// use it — U^2-Net's `_upsample_like`, and the selfie net's decoder skips.
    pub fn resize_like(&mut self, input: Id, like: Id) -> Id {
        let target = self.shape_of(like);
        self.resize_to(input, target.h, target.w)
    }

    /// Bilinear resize to an explicit size.
    pub fn resize_to(&mut self, input: Id, h: u32, w: u32) -> Id {
        let in_shape = self.shape_of(input);
        let out = self.tensor(Shape::new(in_shape.c, h, w));
        self.nodes.push(Node::Resize { input, out, nearest: false });
        out
    }

    /// Nearest-neighbour resize to `like`'s spatial size — SCRFD's two FPN upsamples.
    pub fn resize_nearest_like(&mut self, input: Id, like: Id) -> Id {
        let target = self.shape_of(like);
        let in_shape = self.shape_of(input);
        let out = self.tensor(Shape::new(in_shape.c, target.h, target.w));
        self.nodes.push(Node::Resize { input, out, nearest: true });
        out
    }

    /// Mean over H and W, to `C x 1 x 1`.
    pub fn global_avg_pool(&mut self, input: Id) -> Id {
        let in_shape = self.shape_of(input);
        let out = self.tensor(Shape::new(in_shape.c, 1, 1));
        self.nodes.push(Node::GlobalAvgPool { input, out });
        out
    }

    /// Elementwise `a + b`. Shapes must match.
    ///
    /// # Fusion
    ///
    /// [`Builder::finish`] may fold this into the convolution that produced one side —
    /// see `Node::Conv::res` — when that side has no other reader and the other side is
    /// already written by then. An FPN-style `add(earlier, later)` is the canonical
    /// non-residual: the skip side is *downstream* of the producer, so reading it from
    /// the producer's store would read an unwritten tensor, and the add stays its own
    /// dispatch. Write residuals as `add(skip, produced)` and the fold applies; the
    /// argument order carries no semantics either way.
    pub fn add(&mut self, a: Id, b: Id) -> Id {
        let (sa, sb) = (self.shape_of(a), self.shape_of(b));
        if sa != sb {
            self.fail(format!("add of {sa:?} and {sb:?}"));
        }
        let out = self.tensor(sa);
        self.nodes.push(Node::Binary { kind: Kind::Add, a, b, out });
        out
    }

    /// Elementwise `a * b`. Shapes must match; see [`Builder::mul_channel`] for the
    /// broadcasting form.
    ///
    /// 241 uses in Supertonic's flow-matching sampler alone, gating and scaling whole
    /// activations rather than whole channels.
    pub fn mul(&mut self, a: Id, b: Id) -> Id {
        let (sa, sb) = (self.shape_of(a), self.shape_of(b));
        if sa != sb {
            self.fail(format!("mul of {sa:?} and {sb:?}"));
        }
        let out = self.tensor(sa);
        self.nodes.push(Node::Binary { kind: Kind::Mul, a, b, out });
        out
    }

    /// `a * b` with `b` broadcast over H and W — the excite half of a squeeze-excite
    /// block, where `b` came from [`Builder::global_avg_pool`].
    pub fn mul_channel(&mut self, a: Id, b: Id) -> Id {
        let (sa, sb) = (self.shape_of(a), self.shape_of(b));
        if sb.c != sa.c || sb.h != 1 || sb.w != 1 {
            self.fail(format!("mul_channel of {sa:?} by {sb:?}, which is not Cx1x1"));
        }
        let out = self.tensor(sa);
        self.nodes.push(Node::Binary { kind: Kind::MulBroadcast, a, b, out });
        out
    }

    /// Concatenate along the channel axis. Spatial sizes must match.
    pub fn concat(&mut self, parts: &[Id]) -> Id {
        let shapes: Vec<Shape> = parts.iter().map(|&p| self.shape_of(p)).collect();
        let first = match shapes.first() {
            Some(&s) => s,
            None => {
                self.fail("concat of nothing".into());
                Shape::new(0, 0, 0)
            }
        };
        let mut channels = 0;
        for s in &shapes {
            if s.h != first.h || s.w != first.w {
                self.fail(format!("concat of {first:?} with {s:?}"));
            }
            channels += s.c;
        }
        let out = self.tensor(Shape::new(channels, first.h, first.w));
        self.nodes.push(Node::Concat { parts: parts.to_vec(), out });
        out
    }

    /// Concatenate along the **width** axis. Channels and height must match.
    ///
    /// One use, TinyCLIP's class token: prepending a single position to the `[256, 1, 196]` patch
    /// grid to make the `[256, 1, 197]` sequence the vision transformer reads.
    ///
    /// Unlike [`Builder::concat`] this is not one copy. The position axis is innermost, so a
    /// *part* is a column range of every channel rather than a contiguous run — the same fact that
    /// forced the position-major KV cache in [`Kind::AttnScoresCached`]. It is still only
    /// [`Op::Copy`] and needs no shader: `c * h` runs per part, recorded **once** when the plan is
    /// built rather than per inference. For CLIP that is 512 copies in the command buffer, for a
    /// tensor the class token is one 512-byte column of.
    pub fn concat_positions(&mut self, parts: &[Id]) -> Id {
        let shapes: Vec<Shape> = parts.iter().map(|&p| self.shape_of(p)).collect();
        let first = match shapes.first() {
            Some(&s) => s,
            None => {
                self.fail("a position concat of nothing".into());
                Shape::new(0, 0, 0)
            }
        };
        let mut width = 0;
        for s in &shapes {
            if s.c != first.c || s.h != first.h {
                self.fail(format!("a position concat of {first:?} with {s:?}"));
            }
            width += s.w;
        }
        let out = self.tensor(Shape::new(first.c, first.h, width));
        self.nodes.push(Node::ConcatPositions { parts: parts.to_vec(), out });
        out
    }

    /// `x * scale + shift`, elementwise with scalar parameters. See [`Kind::Affine`].
    pub fn affine(&mut self, input: Id, scale: f32, shift: f32) -> Id {
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::Affine { input, out, scale, shift });
        out
    }

    /// Layer normalisation over the channel axis, with a per-channel affine.
    ///
    /// `weight_index` is the gamma tensor's position in the `.maml` table; beta follows
    /// it, the way a convolution's bias follows its weight.
    pub fn layer_norm(&mut self, input: Id, weight_index: usize, epsilon: f32) -> Id {
        let shape = self.shape_of(input);
        let gamma = self.weight(weight_index, &[shape.c]);
        let beta = self.weight(weight_index + 1, &[shape.c]);
        self.push_layer_norm(input, gamma, beta, epsilon)
    }

    /// The node push behind [`Builder::layer_norm`] and [`Builder::layer_norm_raw`].
    ///
    /// As [`Builder::push_conv` is for convolutions: shared shape passthrough and node
    /// construction, differing only in how the gamma/beta offsets are obtained.
    fn push_layer_norm(&mut self, input: Id, gamma: u32, beta: u32, epsilon: f32) -> Id {
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::LayerNorm { input, out, gamma, beta, epsilon });
        out
    }

    /// [`Builder::layer_norm`] with resolved weight offsets rather than table indices.
    ///
    /// As [`Builder::conv_raw` for convolutions.
    pub fn layer_norm_raw(
        &mut self,
        input: Id,
        gamma: u32,
        beta: u32,
        epsilon: f32,
    ) -> Id {
        self.push_layer_norm(input, gamma, beta, epsilon)
    }

    /// Instance normalisation over the spatial axes, with no affine.
    ///
    /// `(x - mean(ch)) / sqrt(var(ch) + epsilon)` per channel. What MODNet's
    /// `IBNorm` second halves are (`InstanceNorm2d(affine=False)`); the fold
    /// script guarantees scale 1 / bias 0, so this takes no weights. See
    /// [`Kind::InstanceNorm`].
    pub fn instance_norm(&mut self, input: Id, epsilon: f32) -> Id {
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::InstanceNorm { input, out, epsilon });
        out
    }

    /// Root-mean-square normalisation over the channel axis, with a per-channel gain.
    ///
    /// [`layer_norm`](Self::layer_norm) without the mean subtraction and without beta, so
    /// `weight_index` is a single tensor rather than the start of a pair.
    pub fn rms_norm(&mut self, input: Id, weight_index: usize, epsilon: f32) -> Id {
        self.rms_norm_grouped(input, weight_index, epsilon, 1)
    }

    /// [`Builder::rms_norm`] over each of `groups` contiguous runs of channels independently.
    ///
    /// One gain table of `channels / groups` entries, re-used by every group. This is Gemma 4's
    /// QK-norm: a query is `[heads * head_dim, 1, 1]` and each head's `head_dim` slice is
    /// normalised on its own, against one `head_dim`-long gamma.
    ///
    /// Not expressible as a reshape. The channels are head-major, so head `h`'s slice is
    /// contiguous at `h * head_dim`; a `[head_dim, 1, heads]` view would stride the wrong way and
    /// silently normalise across heads instead of within them.
    pub fn rms_norm_grouped(
        &mut self,
        input: Id,
        weight_index: usize,
        epsilon: f32,
        groups: u32,
    ) -> Id {
        let shape = self.shape_of(input);
        if groups == 0 || shape.c % (groups) != 0 {
            self.fail(format!("{} channels do not split into {groups} groups", shape.c));
        }
        let per_group = shape.c.checked_div(groups.max(1)).unwrap_or(0);
        let gamma = self.weight(weight_index, &[per_group]);
        let out = self.tensor(shape);
        self.nodes.push(Node::RmsNorm { input, out, gamma, epsilon, groups, res: None, quant_scale: None });
        out
    }

    /// Attention scores from `q` and `k`, both `[d_model, 1, T]`, into `[heads, T, T]`.
    ///
    /// The `1 / sqrt(head_dim)` scale is derived here rather than taken as an argument:
    /// it is a property of the head geometry, not a trained value, so there is no call
    /// site that could legitimately pass a different one.
    /// [`Builder::attn_scores`] where `kv_heads` heads supply the keys, and the query is
    /// **already scaled**.
    ///
    /// Gemma 4's prefill: eight query heads against one key head, and the `1 / sqrt(head_dim)`
    /// already folded into `q_norm` - see `nets::gemma4`'s `Q_NORM_CARRIES_SCALE`.
    pub fn attn_scores_grouped_prescaled(
        &mut self,
        q: Id,
        k: Id,
        heads: u32,
        kv_heads: u32,
    ) -> Id {
        let (out, _) = self.score_map(q, k, heads, kv_heads);
        self.nodes.push(Node::AttnScores {
            q,
            k,
            out,
            heads,
            kv_heads,
            scale: 1.0,
        });
        out
    }

    pub fn attn_scores(&mut self, q: Id, k: Id, heads: u32) -> Id {
        let (out, scale) = self.score_map(q, k, heads, heads);
        self.nodes.push(Node::AttnScores { q, k, out, heads, kv_heads: heads, scale });
        out
    }

    /// [`Builder::attn_scores`] for a query that is **already scaled**.
    ///
    /// The uncached counterpart of [`Builder::attn_scores_cached_prescaled`], and it exists for
    /// the same export. Gemma 4's vision tower goes `q_proj -> Clip -> q_norm -> rotary ->
    /// MatMul` with no `Mul` anywhere in between, so there is no `1 / sqrt(head_dim)` to
    /// reproduce; its `q_norm` and `k_norm` gammas are uniform scalars whose product is about a
    /// half in every layer, which is where the scaling actually lives.
    ///
    /// Deriving the scale here as well would divide every score by eight. That is not a shape
    /// error and no layout test sees it - it just flattens all sixteen layers of attention.
    pub fn attn_scores_prescaled(&mut self, q: Id, k: Id, heads: u32) -> Id {
        let (out, _) = self.score_map(q, k, heads, heads);
        self.nodes.push(Node::AttnScores { q, k, out, heads, kv_heads: heads, scale: 1.0 });
        out
    }

    /// Attention scores for one query against a **position-major** K cache.
    ///
    /// `q` is `[d_model, 1, 1]` and `cache` is `[keys, 1, d_model]`, giving `[heads, 1, keys]`.
    /// The cache's axes are the other way round from every other sequence here, deliberately: see
    /// [`Kind::AttnScoresCached`].
    ///
    /// One query is what makes a causal mask unnecessary — a decode step attends over exactly the
    /// positions in the cache, so the prefix bound is the tensor's own length.
    pub fn attn_scores_cached(&mut self, q: Id, cache: Id, heads: u32) -> Id {
        self.attn_scores_cached_at(q, cache, CachedScores { heads, kv_heads: heads, dynamic: false, derive_scale: true, sliding: true })
    }

    /// [`Builder::attn_scores_cached`] with the key count supplied by the step, not the shape.
    ///
    /// `cache` is then sized to the **maximum** context the plan is built for, and only its
    /// leading `prefix + 1` positions are attended. See [`Push::dyn_keys`].
    pub fn attn_scores_cached_dynamic(&mut self, q: Id, cache: Id, heads: u32) -> Id {
        self.attn_scores_cached_at(q, cache, CachedScores { heads, kv_heads: heads, dynamic: true, derive_scale: true, sliding: true })
    }

    /// [`Builder::attn_scores_cached_dynamic`] where `kv_heads` heads supply the keys.
    ///
    /// The cache is then `[keys, 1, kv_heads * head_dim]` rather than `[keys, 1, d_model]`, which
    /// is how a decoder holding one KV head for eight query heads stores an eighth as much.
    pub fn attn_scores_cached_grouped(
        &mut self,
        q: Id,
        cache: Id,
        heads: u32,
        kv_heads: u32,
    ) -> Id {
        self.attn_scores_cached_at(q, cache, CachedScores { heads, kv_heads, dynamic: true, derive_scale: true, sliding: true })
    }

    /// [`Builder::attn_scores_cached_grouped`] for a query that is **already scaled**.
    ///
    /// The usual `1 / sqrt(head_dim)` is a property of the head geometry, so every other entry
    /// point derives it rather than taking it. Gemma 4 is the exception: its export folds the
    /// scale into `q_norm`'s gamma and applies none between the projection and the score matmul,
    /// so deriving it here as well would apply it twice - which is not a shape error, does not
    /// fail any layout test, and merely flattens every attention distribution in the model.
    ///
    /// `sliding` says whether this layer's window applies. See [`Push::sliding`].
    pub fn attn_scores_cached_prescaled(
        &mut self,
        q: Id,
        cache: Id,
        heads: u32,
        kv_heads: u32,
        sliding: bool,
    ) -> Id {
        self.attn_scores_cached_at(q, cache, CachedScores { heads, kv_heads, dynamic: true, derive_scale: false, sliding })
    }

    fn attn_scores_cached_at(&mut self, q: Id, cache: Id, scores: CachedScores) -> Id {
        let CachedScores { heads, kv_heads, dynamic, derive_scale, sliding } = scores;
        let (sq, sc) = (self.shape_of(q), self.shape_of(cache));
        if sq.h != 1 || sq.w != 1 {
            self.fail(format!(
                "a cached score map over q {sq:?}: a decode step is one query, so q is \
                 [d_model, 1, 1]"
            ));
        }
        if sc.h != 1 {
            self.fail(format!(
                "a cached score map over q {sq:?} and a cache {sc:?}: a cache is \
                 [keys, 1, kv_heads * head_dim], so its channels are the keys"
            ));
        }
        if heads == 0 || sq.c % (heads) != 0 {
            self.fail(format!("{} channels do not split into {heads} heads", sq.c));
        }
        let head_dim = sq.c.checked_div(heads).unwrap_or(0);
        if kv_heads == 0 || heads % kv_heads != 0 {
            self.fail(format!("{heads} query heads do not group into {kv_heads} key/value heads"));
        }
        if sc.w != kv_heads * head_dim {
            self.fail(format!(
                "a cache {sc:?} for {kv_heads} key/value heads of {head_dim}: its width should \
                 be {}",
                kv_heads * head_dim
            ));
        }
        let scale = if derive_scale { 1.0 / (head_dim.max(1) as f32).sqrt() } else { 1.0 };
        let out = self.tensor(Shape::new(heads, 1, sc.c));
        self.nodes.push(Node::AttnScoresCached { q, cache, out, heads, kv_heads, scale, dynamic, sliding, window: 0 });
        out
    }

    /// One query's attention output against a **position-major** V cache.
    ///
    /// `probs` is `[heads, 1, keys]` and `cache` is `[keys, 1, d_model]`, giving
    /// `[d_model, 1, 1]` — back in the channel-major layout the next projection reads, so the
    /// cache layout is confined to the two operands that are caches.
    pub fn attn_apply_cached(&mut self, probs: Id, cache: Id, heads: u32) -> Id {
        self.attn_apply_cached_at(probs, cache, heads, heads, false, true)
    }
    // [`Builder::attn_apply_cached`] with the key count supplied by the step, not the shape.
    //
    // Must be paired with [`Builder::attn_scores_cached_dynamic`] and
    // [`Builder::softmax_prefix`]: all three read the same bound, and mixing a dynamic score map
    // with a full-width sum would fold unattended positions into the result.
}