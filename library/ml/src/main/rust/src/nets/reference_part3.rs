impl Reference {

    /// Keys an op attends over, as an inclusive `[first, last]` range.
    ///
    /// The host mirror of `attn_first` / `attn_last` in `shaders/common.glsl`.
    /// The inclusive key range an attention op may read. Mirrors `attn_first` / `attn_last`.
    ///
    /// A dynamic op that does **not** slide attends the whole prefix: it shares a submit with
    /// sliding layers, so `window_start` is set for them and it must ignore it.
    fn attended(&self, p: &Push, stride: u32) -> (u32, u32) {
        if p.dyn_keys != 0 {
            let first = if p.sliding != 0 { self.window_start } else { 0 };
            (first, self.prefix.min(stride.saturating_sub(1)))
        } else {
            (0, stride.saturating_sub(1))
        }
    }

    /// [`Self::attended`] for query `q` of a multi-query op. Mirrors `attn_first_q` /
    /// `attn_last_q`: query `q` sits at `prefix + q` and slides over `p.dil_w` keys.
    fn attended_q(&self, p: &Push, stride: u32, q: u32) -> (u32, u32) {
        if p.dil_h == 0 {
            return self.attended(p, stride);
        }
        let pos = self.prefix + q;
        let first = if p.sliding != 0 && p.dil_w != 0 { (pos + 1).saturating_sub(p.dil_w) } else { 0 };
        (first, pos.min(stride.saturating_sub(1)))
    }

    /// The KV head a query head reads from. Mirrors `kv_head_of` in `shaders/common.glsl`.
    fn kv_head_of(p: &Push, head: u32) -> u32 {
        let kv = if p.kv_heads == 0 { p.group } else { p.kv_heads };
        head / (p.group / kv.max(1)).max(1)
    }

    /// Channels one cache position occupies: `kv_heads * head_dim`.
    fn kv_stride(p: &Push, head_dim: u32) -> u32 {
        let kv = if p.kv_heads == 0 { p.group } else { p.kv_heads };
        kv * head_dim
    }

    /// Clamp to a range held in the weights. See `shaders/clamp.comp`.
    fn clamp(&mut self, p: &Push) -> Result<(), String> {
        let low = self.weight(p.act_weight, 0)?;
        let high = self.weight(p.act_weight, 1)?;
        for index in 0..p.count {
            let value = self.load(p.in0, index)?;
            self.store(p.out, index, value.clamp(low, high))?;
        }
        Ok(())
    }

    /// Multiply by a scalar held in the weights. See `shaders/mul_scalar.comp`.
    fn mul_scalar(&mut self, p: &Push) -> Result<(), String> {
        let scale = self.weight(p.act_weight, 0)?;
        for index in 0..p.count {
            let value = self.load(p.in0, index)?;
            self.store(p.out, index, value * scale)?;
        }
        Ok(())
    }

    /// An activation on its own. See `shaders/activate.comp`.
    /// `activate(gate) * up`, the fused form. See `shaders/gated_activate.comp`.
    fn gated_activate(&mut self, p: &Push) -> Result<(), String> {
        let spatial = (p.out_h * p.out_w).max(1);
        for index in 0..p.count {
            let gate = self.load(p.in0, index)?;
            // `in_c` is the whole gate half, so this is the same element in the up half.
            let up = self.load(p.in0 + p.in_c, index)?;
            let slope = self.weight(p.act_weight, index / spatial).unwrap_or(0.0);
            self.store(p.out, index, activate(gate, p.act, slope) * up)?;
        }
        Ok(())
    }
    fn activate(&mut self, p: &Push) -> Result<(), String> {
        let spatial = (p.out_h * p.out_w).max(1);
        for index in 0..p.count {
            let value = self.load(p.in0, index)?;
            // The free function, not this method. `PRelu` would need a slope per channel and is
            // not offered here - see `Kind::Activate`.
            let slope = self.weight(p.act_weight, index / spatial).unwrap_or(0.0);
            self.store(p.out, index, activate(value, p.act, slope))?;
        }
        Ok(())
    }

    /// `tanh(x / cap) * cap`. See `shaders/softcap.comp`.
    fn softcap(&mut self, p: &Push) -> Result<(), String> {
        let cap = f32::from_bits(p.param0_bits);
        if cap.partial_cmp(&0.0) != Some(std::cmp::Ordering::Greater) {
            return Err(format!("a softcap of {cap}"));
        }
        for index in 0..p.count {
            let value = self.load(p.in0, index)?;
            self.store(p.out, index, (value / cap).tanh() * cap)?;
        }
        Ok(())
    }

    /// S10's int8 round-trip. See `shaders/quantize.comp`.
    fn quantize(&mut self, p: &Push) -> Result<(), String> {
        let scale = f32::from_bits(p.param0_bits);
        if scale.partial_cmp(&0.0) != Some(std::cmp::Ordering::Greater) {
            return Err(format!("a quantize of {scale}"));
        }
        for index in 0..p.count {
            let value = self.load(p.in0, index)?;
            let q = ((value / scale + 0.5).floor()).clamp(-128.0, 127.0);
            self.store(p.out, index, q * scale)?;
        }
        Ok(())
    }

    /// Copy one position into a KV cache at the step's prefix. See `shaders/cache_write.comp`.
    ///
    /// The one op whose destination depends on the step rather than the recording, which is why
    /// the interpreter has to know the prefix at all.
    fn cache_write(&mut self, p: &Push) -> Result<(), String> {
        // Mirrors the transpose in `shaders/cache_write.comp`. See there for why it is here.
        let positions = p.group.max(1);
        if positions > 1 {
            for index in 0..p.count {
                let channel = index / positions;
                let position = index % positions;
                if self.prefix + position >= p.in_h {
                    continue;
                }
                let value = self.load(p.in0, index)?;
                self.store(p.out, (self.prefix + position) * p.in_c + channel, value)?;
            }
            return Ok(());
        }
        // Past the end writes nothing, matching the shader. The caller stops before this; a
        // silent drop is a better failure than a store outside the cache.
        if self.prefix >= p.in_h {
            return Ok(());
        }
        let base = self.prefix * p.in_c;
        for index in 0..p.count {
            let value = self.load(p.in0, index)?;
            self.store(p.out, base + index, value)?;
        }
        Ok(())
    }

    /// `O[h][d][i] = sum_j S[h][i][j] * V[h][d][j]`.
    ///
    /// `S` is `[heads, T, T]` at `in0`; `V` and the output are `[d_model, 1, T]`. The
    /// head index picks a plane of `S` and nothing else — channel `c` of `V` is channel
    /// `c` of the output — which is why concatenating the heads afterwards is not an op.
    fn attn_apply(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.out_c)?;
        // `out_w` is the query count and `in_w` the key count; equal for self-attention.
        let (queries, keys) = (p.out_w, p.in_w);
        for channel in 0..p.out_c {
            let head = channel / head_dim;
            let row = head * queries * keys;
            // V's channel for this query head, shared under multi-query attention.
            let value = Self::kv_head_of(p, head) * head_dim + (channel % head_dim);
            for query in 0..queries {
                let mut total = 0.0;
                for key in 0..keys {
                    total += self.load(p.in0, row + query * keys + key)?
                        * self.load(p.in1, value * keys + key)?;
                }
                self.store(p.out, nchw(p, channel, 0, query), total)?;
            }
        }
        Ok(())
    }

    /// [`Self::attn_scores`] for one query against a **position-major** K cache.
    ///
    /// The only difference from [`Self::attn_scores`] is the cache's indexing: a key is a run of
    /// `in_c` elements rather than a channel every `key_stride` apart, which is what makes
    /// appending a position one contiguous copy. `out_w` is the key count, and there is exactly one
    /// query, so the score map is `[heads, 1, keys]` and `softmax` normalises it unchanged.
    fn attn_scores_cached(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.in_c)?;
        let scale = f32::from_bits(p.param0_bits);
        let stride = Self::kv_stride(p, head_dim);
        let queries = p.dil_h.max(1);
        for head in 0..p.group {
            // Q is `[d_model, 1, queries]`: channel `c` of query `q` at `c * queries + q`.
            let kv_base = Self::kv_head_of(p, head) * head_dim;
            for q in 0..queries {
                let query_base = head * head_dim * queries + q;
                let (first, last) = self.attended_q(p, p.out_w, q);
                for key in first..=last {
                    let key_base = key * stride + kv_base;
                    let mut total = 0.0;
                    for d in 0..head_dim {
                        total += self.load(p.in0, query_base + d * queries)?
                            * self.load(p.in1, key_base + d)?;
                    }
                    self.store(p.out, nchw(p, head, q, key), total * scale)?;
                }
            }
        }
        Ok(())
    }

    /// [`Self::attn_apply`] for one query against a **position-major** V cache.
    ///
    /// The output is `[d_model, 1, 1]`, back in the channel-major layout the next projection
    /// reads, so the cache layout is confined to the operand that is a cache. `in_w` is the key
    /// count, which in this layout is the cache's channel count.
    fn attn_apply_cached(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.out_c)?;
        let stride = p.in_w;
        let vstride = Self::kv_stride(p, head_dim);
        let queries = p.dil_h.max(1);
        for channel in 0..p.out_c {
            let head = channel / head_dim;
            let value = Self::kv_head_of(p, head) * head_dim + (channel % head_dim);
            for q in 0..queries {
                // A row of probabilities starts a full stride apart even when this step attends
                // over fewer keys than the row can hold.
                let row = (head * queries + q) * stride;
                let (first, last) = self.attended_q(p, stride, q);
                let mut total = 0.0;
                for key in first..=last {
                    total += self.load(p.in0, row + key)?
                        * self.load(p.in1, value + key * vstride)?;
                }
                self.store(p.out, channel * queries + q, self.fused_quant(p, total)?)?;
            }
        }
        Ok(())
    }

    /// [`Self::attn_scores`] plus a relative-position term: nine taps of a learned
    /// table indexed by `key - query`, in place of the export's product-and-skew.
    ///
    /// The scale multiplies both terms, because the export divides the query by
    /// `sqrt(head_dim)` before either product.
    fn attn_scores_relative(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.in_c)?;
        let stride = p.in_h * p.in_w;
        let scale = f32::from_bits(p.param0_bits);
        let window = (p.kw.max(1) - 1) as i64 / 2;
        for head in 0..p.group {
            let base = head * head_dim * stride;
            for query in 0..p.out_h {
                for key in 0..p.out_w {
                    let mut total = 0.0;
                    for d in 0..head_dim {
                        let channel = base + d * stride;
                        total += self.load(p.in0, channel + query)?
                            * self.load(p.in1, channel + key)?;
                    }
                    let offset = key as i64 - query as i64;
                    if offset.abs() <= window {
                        let row = (offset + window) as u32 * head_dim;
                        for d in 0..head_dim {
                            total += self.load(p.in0, base + d * stride + query)?
                                * self.weight(p.weight, row + d)?;
                        }
                    }
                    self.store(p.out, nchw(p, head, query, key), total * scale)?;
                }
            }
        }
        Ok(())
    }

    /// [`Self::attn_apply`] plus the value-side relative term. Taps outside the sequence
    /// are skipped rather than clamped, which would double-count a neighbour at the ends.
    fn attn_apply_relative(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.out_c)?;
        let keys = p.out_w;
        let window = (p.kw.max(1) - 1) as i64 / 2;
        for channel in 0..p.out_c {
            let row = (channel / head_dim) * keys * keys;
            let depth = channel % head_dim;
            for query in 0..keys {
                let mut total = 0.0;
                for key in 0..keys {
                    total += self.load(p.in0, row + query * keys + key)?
                        * self.load(p.in1, channel * keys + key)?;
                }
                for offset in -window..=window {
                    let key = query as i64 + offset;
                    if key < 0 || key >= keys as i64 {
                        continue;
                    }
                    let entry = (offset + window) as u32 * head_dim + depth;
                    total += self.load(p.in0, row + query * keys + key as u32)?
                        * self.weight(p.weight, entry)?;
                }
                self.store(p.out, nchw(p, channel, 0, query), total)?;
            }
        }
        Ok(())
    }

    /// [`Kind::AttnScoresBanded`]. The guard mirrors `nets::gemma4_audio::band_key` and this
    /// arm calls it rather than restating it — four rearrangements of that condition have been
    /// proposed and been wrong, so the oracle and the shader share one definition on purpose.
    fn attn_scores_banded(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.in_c)?;
        let stride = p.in_h * p.in_w;
        let scale = f32::from_bits(p.param0_bits);
        let cap = f32::from_bits(p.param1_bits);
        if cap <= 0.0 {
            return Err(format!("a banded score map with a logit cap of {cap}"));
        }
        let band = p.kh;
        for head in 0..p.group {
            let base = head * head_dim * stride;
            for query in 0..p.out_h {
                for column in 0..band {
                    let slot = nchw(p, head, query, column);
                    // `None` gates the LOAD, not just the stored value. Parameterised by the op's
                    // own band, which the shader reads from `Push::kh` - calling the
                    // `ATTEND_SPAN` form here would agree only at band 12.
                    let Some(key) = crate::nets::gemma4_audio::band_key_in(band, query, column)
                    else {
                        self.store(p.out, slot, crate::nets::gemma4_audio::MASK_FILL)?;
                        continue;
                    };
                    if key >= p.in_w {
                        return Err(format!(
                            "banded score at query {query} column {column} reads key {key} of \
                             {} - right context is zero, so this cannot happen",
                            p.in_w
                        ));
                    }
                    // Column `j` reads relative offset `j + 1`; see `gemma4_audio::rel_column`.
                    let row = (head * p.kw + column + 1) * head_dim;
                    let mut total = 0.0;
                    for d in 0..head_dim {
                        let channel = base + d * stride;
                        let q = self.load(p.in0, channel + query)?;
                        total += q * self.load(p.in1, channel + key)?;
                        total += q * self.weight(p.weight, row + d)?;
                    }
                    total *= scale;
                    self.store(p.out, slot, (total / cap).tanh() * cap)?;
                }
            }
        }
        Ok(())
    }

    /// [`Kind::AttnApplyBanded`]. Columns before the sequence are skipped rather than read,
    /// which is exact because the softmax has already given them zero weight.
    fn attn_apply_banded(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.out_c)?;
        let keys = p.out_w;
        let band = p.kh;
        for channel in 0..p.out_c {
            let row = (channel / head_dim) * keys * band;
            for query in 0..keys {
                let mut total = 0.0;
                for column in 0..band {
                    let Some(key) = crate::nets::gemma4_audio::band_key_in(band, query, column)
                    else {
                        continue;
                    };
                    total += self.load(p.in0, row + query * band + column)?
                        * self.load(p.in1, channel * keys + key)?;
                }
                self.store(p.out, nchw(p, channel, 0, query), total)?;
            }
        }
        Ok(())
    }

    /// `out = a * b`, elementwise over two equal shapes.
    fn mul(&mut self, p: &Push) -> Result<(), String> {
        for i in 0..p.count {
            let product = self.load(p.in0, i)? * self.load(p.in1, i)?;
            self.store(p.out, i, product)?;
        }
        Ok(())
    }

    /// `out[c][y][x] = a[c][y][x] * b[c]`, the excite half of a squeeze-excite block.
    fn mul_broadcast(&mut self, p: &Push) -> Result<(), String> {
        for oc in 0..p.out_c {
            let gate = self.load(p.in1, oc)?;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    let index = nchw(p, oc, oy, ox);
                    let value = self.load(p.in0, index)?;
                    self.store(p.out, index, value * gate)?;
                }
            }
        }
        Ok(())
    }
}
