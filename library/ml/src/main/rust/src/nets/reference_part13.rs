impl Reference {
    /// `S[h][i][j] = scale * sum_d Q[h][d][i] * K[h][d][j]`.
    ///
    /// Q and K are `[d_model, 1, T]`; the output is `[heads, T, T]`. A head is a run of
    /// `in_c / group` consecutive channels, which is what makes the split free — there is
    /// no transpose anywhere in this, by construction.
    fn attn_scores(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.in_c)?;
        // Q and K carry their own lengths, which are the score map's height and width. For
        // self-attention those are equal.
        let (query_stride, key_stride) = (p.out_h, p.out_w);
        let scale = f32::from_bits(p.param0_bits);
        for head in 0..p.group {
            let query_base = head * head_dim * query_stride;
            // K is `kv_heads` wide, so several query heads share a key head. The identity when
            // the two counts match, which is every net that is not Gemma's.
            let key_base = Self::kv_head_of(p, head) * head_dim * key_stride;
            for query in 0..p.out_h {
                for key in 0..p.out_w {
                    let mut total = 0.0;
                    for d in 0..head_dim {
                        total += self.load(p.in0, query_base + d * query_stride + query)?
                            * self.load(p.in1, key_base + d * key_stride + key)?;
                    }
                    self.store(p.out, nchw(p, head, query, key), total * scale)?;
                }
            }
        }
        Ok(())
    }

    /// Softmax over the last axis, `count` contiguous rows of `out_w`.
    ///
    /// The row maximum is subtracted first, as `softmax.comp` does. That is not a
    /// refinement: without it a row containing a large score exponentiates to infinity in
    /// fp32 and every probability in it becomes a NaN.
    ///
    /// `causal` truncates each row at the diagonal, as `softmax_causal.comp` does: rows are
    /// head-major with `out_h` queries per head, so query `row % out_h` reads `query + 1` keys and
    /// the rest of its row is stored as zero.
    ///
    /// [`SoftmaxMode::Prefix`] instead normalises the leading `prefix + 1` entries and **leaves
    /// the rest of the row alone**, matching `softmax_prefix.comp`. Zeroing the tail would be
    /// wrong there rather than merely wasteful: the tail belongs to positions this step does not
    /// attend over but a later one will, and the corresponding apply stops at the same bound.
    fn softmax(&mut self, p: &Push, mode: SoftmaxMode) -> Result<(), String> {
        if p.out_w == 0 {
            return Err("a softmax over an empty axis".into());
        }
        if mode == SoftmaxMode::Causal && p.out_h == 0 {
            return Err("a causal softmax over no queries".into());
        }
        for row in 0..p.count {
            let at = row * p.out_w;
            let keys = match mode {
                // At query 0 this is 1, so the distribution is exactly `[1, 0, ...]` and the
                // denominator can never be empty.
                SoftmaxMode::Causal => ((row % p.out_h.max(1)) + 1).min(p.out_w),
                SoftmaxMode::Prefix => {
                    let (first, last) = self.attended_q(p, p.out_w, row % p.dil_h.max(1));
                    let mut peak = -65504.0f32;
                    for i in first..=last {
                        peak = peak.max(self.load(p.in0, at + i)?);
                    }
                    let mut total = 0.0;
                    for i in first..=last {
                        total += (self.load(p.in0, at + i)? - peak).exp();
                    }
                    let inverse = 1.0 / total;
                    for i in first..=last {
                        let value = (self.load(p.in0, at + i)? - peak).exp() * inverse;
                        self.store(p.out, at + i, value)?;
                    }
                    continue;
                }
                SoftmaxMode::Full if p.kh != 0 => p.kh.min(p.out_w),
                SoftmaxMode::Full => p.out_w,
            };
            // A sliding layer's causal row also drops everything more than `p.kh - 1` behind
            // the query. Zero is no window, which is the plain causal mask.
            let query = row % p.out_h.max(1);
            let first = if mode == SoftmaxMode::Causal && p.kh != 0 {
                (query + 1).saturating_sub(p.kh)
            } else {
                0
            };
            let mut peak = -65504.0f32;
            for i in first..keys {
                peak = peak.max(self.load(p.in0, at + i)?);
            }
            let mut total = 0.0;
            for i in first..keys {
                total += (self.load(p.in0, at + i)? - peak).exp();
            }
            // The peak's own term is `exp(0)`, so this is at least 1.
            let inverse = 1.0 / total;
            for i in 0..first {
                self.store(p.out, at + i, 0.0)?;
            }
            for i in first..keys {
                let value = (self.load(p.in0, at + i)? - peak).exp() * inverse;
                self.store(p.out, at + i, value)?;
            }
            // Written rather than left alone (causal and key-bounded rows): `attn_apply`
            // multiplies the whole row, and the arena is reused, so a stale masked value would
            // contribute something unpredictable.
            for i in keys..p.out_w {
                self.store(p.out, at + i, 0.0)?;
            }
        }
        Ok(())
    }
}
