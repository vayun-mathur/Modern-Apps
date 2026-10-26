impl Reference {
    fn new(plan: &Plan, weights: &[u8], inputs: &[&[f32]]) -> Result<Reference, String> {
        if inputs.len() != plan.inputs.len() {
            return Err(format!(
                "{} input slices for a net with {} inputs",
                inputs.len(),
                plan.inputs.len()
            ));
        }
        if !weights.len().is_multiple_of(2) {
            return Err(format!("{} bytes is not a whole number of fp16", weights.len()));
        }
        let decoded = weights
            .chunks_exact(2)
            .map(|pair| match pair {
                [low, high] => Ok(f16_to_f32(u16::from_le_bytes([*low, *high]))),
                _ => Err("a chunk of two is two bytes".to_string()),
            })
            .collect::<Result<Vec<f32>, String>>()?;

        let mut reference = Reference {
            arena: vec![0.0; plan.arena_elems as usize],
            weights: decoded,
            bytes: weights.to_vec(),
            prefix: 0,
            window_start: 0,
        };
        for (binding, values) in plan.inputs.iter().zip(inputs) {
            if values.len() != binding.shape.len() as usize {
                return Err(format!(
                    "{} input values for {:?}",
                    values.len(),
                    binding.shape
                ));
            }
            for (i, &value) in values.iter().enumerate() {
                reference.store(binding.at, i as u32, value)?;
            }
        }
        Ok(reference)
    }

    /// Run every op in order.
    fn execute(&mut self, plan: &Plan) -> Result<(), String> {
        for (step, op) in plan.ops.iter().enumerate() {
            let result = match op {
                Op::Copy { src, dst, elems } => self.copy(*src, *dst, *elems),
                Op::Dispatch { kind, push, .. } => match kind {
                    Kind::Conv => self.conv(push),
                    Kind::ConvTranspose => self.conv_transpose(push),
                    Kind::MaxPool => self.max_pool(push),
                    Kind::AvgPool => self.avg_pool(push),
                    Kind::Resize => self.resize(push),
                    Kind::ResizeNearest => self.resize_nearest(push),
                    Kind::GlobalAvgPool => self.global_avg_pool(push),
                    Kind::Add => self.add(push),
                    Kind::MulBroadcast => self.mul_broadcast(push),
                    Kind::Mul => self.mul(push),
                    Kind::Affine => self.affine(push),
                    Kind::LayerNorm => self.layer_norm(push),
                    Kind::InstanceNorm => self.instance_norm(push),
                    Kind::RmsNorm => self.rms_norm(push),
                    Kind::AttnScores => self.attn_scores(push),
                    Kind::Softmax => self.softmax(push, SoftmaxMode::Full),
                    Kind::SoftmaxCausal => self.softmax(push, SoftmaxMode::Causal),
                    Kind::SoftmaxPrefix => self.softmax(push, SoftmaxMode::Prefix),
                    Kind::CacheWrite => self.cache_write(push),
                    Kind::Softcap => self.softcap(push),
                    Kind::Argmax => self.argmax(push),
                    Kind::ArgmaxFold => self.argmax_fold(push),
                    Kind::TopK => self.top_k_stage(push),
                    Kind::Quantize => self.quantize(push),
                    Kind::Activate => self.activate(push),
                    Kind::GatedActivate => self.gated_activate(push),
                    Kind::MulScalar => self.mul_scalar(push),
                    Kind::Clamp => self.clamp(push),
                    Kind::AttnApply => self.attn_apply(push),
                    Kind::AttnScoresRelative => self.attn_scores_relative(push),
                    Kind::AttnApplyRelative => self.attn_apply_relative(push),
                    Kind::AttnScoresBanded => self.attn_scores_banded(push),
                    Kind::AttnApplyBanded => self.attn_apply_banded(push),
                    Kind::AttnScoresCached => self.attn_scores_cached(push),
                    Kind::AttnApplyCached => self.attn_apply_cached(push),
                    Kind::Embed => self.embed(push),
                    Kind::Constant => self.constant(push),
                    Kind::AddBroadcast => self.add_broadcast(push),
                    Kind::Rotary => self.rotary(push),
                    Kind::ConvPoint => self.conv_point(push),
                    // All three int8 lowerings compute exactly what the untiled one does, and
                    // `Builder::emit` fills the geometry fields in for every one of them, so there
                    // is one implementation rather than three that have to be kept agreeing.
                    Kind::ConvInt8 | Kind::ConvPointInt8 | Kind::ConvVecInt8 => {
                        self.conv_int8(push)
                    }
                    Kind::ConvVecInt4 | Kind::ConvPointInt4 => self.conv_int4(push),
                    Kind::ConvPointInt4Dot => self.conv_int4_dot(push),
                    Kind::QuantizeBlocks => Ok(()),
                    Kind::ConvQ2K | Kind::ConvPointQ2K | Kind::ConvVecQ2K => self.conv_q2k(push),
                },
            };
            result.map_err(|e| format!("step {step} ({op:?}): {e}"))?;
        }
        Ok(())
    }

    /// `elems` values starting at `at`, as fp32.
    fn read(&self, at: u32, elems: u32) -> Result<Vec<f32>, String> {
        (0..elems).map(|i| self.load(at, i)).collect()
    }

    fn load(&self, at: u32, index: u32) -> Result<f32, String> {
        let position = at.checked_add(index).ok_or("an arena offset overflowed")?;
        self.arena
            .get(position as usize)
            .copied()
            .ok_or_else(|| format!("arena read at {position} of {}", self.arena.len()))
    }

    fn store(&mut self, at: u32, index: u32, value: f32) -> Result<(), String> {
        let position = at.checked_add(index).ok_or("an arena offset overflowed")?;
        let len = self.arena.len();
        let slot = self
            .arena
            .get_mut(position as usize)
            .ok_or_else(|| format!("arena write at {position} of {len}"))?;
        *slot = through_f16(value);
        Ok(())
    }

    fn weight(&self, at: u32, index: u32) -> Result<f32, String> {
        let position = at.checked_add(index).ok_or("a weight offset overflowed")?;
        self.weights
            .get(position as usize)
            .copied()
            .ok_or_else(|| format!("weight read at {position} of {}", self.weights.len()))
    }

    /// The contiguous copy `Concat` lowers to.
    fn copy(&mut self, src: u32, dst: u32, elems: u32) -> Result<(), String> {
        // Read the whole run before writing any of it. Source and destination are
        // disjoint in every plan the builder produces, but reading into a buffer keeps
        // that an assertion of the builder's rather than an assumption here.
        let values = self.read(src, elems)?;
        for (i, value) in values.into_iter().enumerate() {
            self.store(dst, i as u32, value)?;
        }
        Ok(())
    }

    /// Convolution: ONNX semantics, weights `[m, in_c / group, kh, kw]`.
    ///
    /// Output channel `oc` belongs to group `oc / (out_c / group)` and reads only that
    /// group's `in_c / group` input channels — the indexing that a depthwise layer
    /// makes or breaks, since there `group == in_c == out_c` and every output channel
    /// must see exactly one input channel.
    fn conv(&mut self, p: &Push) -> Result<(), String> {
        let (in_per_group, out_per_group) = groups(p)?;
        for oc in 0..p.out_c {
            let first_in = (oc / out_per_group) * in_per_group;
            let kernel_at = p.weight + oc * in_per_group * p.kh * p.kw;
            let slope = self.slope(p, oc)?;
            let shift = self.fused_shift(p, oc)?;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    let mut acc = self.weight(p.bias, oc)?;
                    for ic in 0..in_per_group {
                        let plane = (first_in + ic) * p.in_h * p.in_w;
                        for ky in 0..p.kh {
                            let positioned = oy * p.stride_h + ky * p.dil_h;
                            let iy = if p.pad_edge != 0 {
                                tap_edge(positioned, p.pad_t, p.in_h)
                            } else {
                                match tap(positioned, p.pad_t, p.in_h) {
                                    Some(found) => found,
                                    None => continue,
                                }
                            };
                            let row = plane + iy * p.in_w;
                            let tap_at = kernel_at + (ic * p.kh + ky) * p.kw;
                            for kx in 0..p.kw {
                                let positioned = ox * p.stride_w + kx * p.dil_w;
                                let ix = if p.pad_edge != 0 {
                                    tap_edge(positioned, p.pad_l, p.in_w + 1 - p.pad_edge)
                                } else {
                                    match tap(positioned, p.pad_l, p.in_w) {
                                        Some(found) => found,
                                        None => continue,
                                    }
                                };
                                acc += self.load(p.in0, row + ix)?
                                    * self.weight(tap_at, kx)?;
                            }
                        }
                    }
                    let index = nchw(p, oc, oy, ox);
                    let folded =
                        activate(acc, p.act, slope) + self.fused_res(p, index)? + shift;
                    self.store(p.out, index, self.fused_quant(p, folded)?)?;
                }
            }
        }
        Ok(())
    }

    /// [`Reference::conv`] with int8 weights and a per-output-channel scale.
    ///
    /// Deliberately a separate function rather than a flag on `conv`: the accumulation is over
    /// integers scaled once at the end, and the padding is zero rather than edge — `Node::ConvInt8`
    /// carries no `pad_edge`, so there is nothing to branch on. Keeping them apart means the fp16
    /// path, which five shipping nets run on, is not touched by anything int8 needs.
    fn conv_int8(&mut self, p: &Push) -> Result<(), String> {
        let (in_per_group, out_per_group) = groups(p)?;
        for oc in 0..p.out_c {
            let first_in = (oc / out_per_group) * in_per_group;
            let kernel_at = oc * in_per_group * p.kh * p.kw;
            // One per output column, unlike the fp16 path's single `act_weight` slot.
            let scale = self.weight(p.act_weight, oc)?;
            let shift = self.fused_shift(p, oc)?;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    let mut acc = 0.0f32;
                    for ic in 0..in_per_group {
                        let plane = (first_in + ic) * p.in_h * p.in_w;
                        for ky in 0..p.kh {
                            let positioned = oy * p.stride_h + ky * p.dil_h;
                            let iy = match tap(positioned, p.pad_t, p.in_h) {
                                Some(found) => found,
                                None => continue,
                            };
                            let row = plane + iy * p.in_w;
                            let tap_at = kernel_at + (ic * p.kh + ky) * p.kw;
                            for kx in 0..p.kw {
                                let positioned = ox * p.stride_w + kx * p.dil_w;
                                let ix = match tap(positioned, p.pad_l, p.in_w) {
                                    Some(found) => found,
                                    None => continue,
                                };
                                acc += self.load(p.in0, row + ix)?
                                    * f32::from(self.int8(p.weight, tap_at + kx)?);
                            }
                        }
                    }
                    let biased = acc * scale + self.weight(p.bias, oc)?;
                    let index = nchw(p, oc, oy, ox);
                    // PRelu is refused at build time for int8, so the slope is never read.
                    let folded =
                        activate(biased, p.act, 0.0) + self.fused_res(p, index)? + shift;
                    self.store(p.out, index, self.fused_quant(p, folded)?)?;
                }
            }
        }
        Ok(())
    }

    /// A `1 x 1` int4 convolution with a per-block scale. See `shaders/conv_vec_int4.comp`.
    ///
    /// Only pointwise, which is what [`crate::nets::Builder::conv_int4`] offers, so there is no
    /// kernel or padding loop: `taps` is the contraction axis and the scale changes every
    /// [`crate::weights::I4_BLOCK`] of it.
    fn conv_int4(&mut self, p: &Push) -> Result<(), String> {
        let taps = p.in_c;
        let blocks = taps.div_ceil(crate::weights::I4_BLOCK);
        let positions = p.out_h * p.out_w;
        for oc in 0..p.out_c {
            let kernel_at = oc * taps;
            let shift = self.fused_shift(p, oc)?;
            for position in 0..positions {
                let mut acc = 0.0f32;
                for k in 0..taps {
                    let w = f32::from(self.int4(p.weight, kernel_at + k)?);
                    // The scale reaches the product, not the total: a block's worth of taps
                    // shares one, and the next block's is different.
                    let scale = self.weight(p.act_weight, oc * blocks + k / crate::weights::I4_BLOCK)?;
                    acc += self.load(p.in0, k * positions + position)? * w * scale;
                }
                let biased = acc + self.weight(p.bias, oc)?;
                let index = oc * positions + position;
                let folded =
                    activate(biased, p.act, 0.0) + self.fused_res(p, index)? + shift;
                self.store(p.out, index, self.fused_quant(p, folded)?)?;
            }
        }
        Ok(())
    }

    /// A `1 x 1` Q2_K convolution with a per-superblock `(d, dmin)` scale.
    /// See `shaders/conv_vec_q2k.comp`.
    ///
    /// Only pointwise, which is what [`crate::nets::Builder::conv_q2k`] offers, so there is no
    /// kernel or padding loop: `taps` is the contraction axis and the affine pair changes
    /// every [`crate::weights::Q2K_BLOCK`] of it. The exact `to_float` transcription —
    /// `y = d*d*(q*lo) - dmin*d*hi` per 16-tap lane — so the oracle and the shader are the
    /// same function by construction rather than by coincidence.
    fn conv_q2k(&mut self, p: &Push) -> Result<(), String> {
        use crate::weights::{Q2K_BLOCK, Q2K_BYTES};
        let taps = p.in_c;
        let blocks = taps.div_ceil(Q2K_BLOCK);
        let positions = p.out_h * p.out_w;
        for oc in 0..p.out_c {
            let shift = self.fused_shift(p, oc)?;
            for position in 0..positions {
                let mut acc = 0.0f32;
                for k in 0..taps {
                    let block = k / Q2K_BLOCK;
                    let inblock = k % Q2K_BLOCK;
                    let half = inblock / 128;
                    let pair = (inblock % 128) / 32;
                    let l = inblock % 16;
                    let odd = (inblock % 32) / 16;
                    // Superblock byte offset of this row's block: rows are contiguous runs
                    // of `blocks` superblocks from the word offset `p.weight`.
                    let sbo = p.weight * 4 + (oc * blocks + block) * Q2K_BYTES;
                    let sc = self.byte(sbo + half * 8 + pair * 2 + odd)?;
                    let lo = f32::from(sc & 15);
                    let hi = f32::from(sc >> 4);
                    // Quant byte: 64 per superblock after the 16 scale bytes; halves are
                    // separate 32-byte chunks, odd lanes in the second 16.
                    let qb = self.byte(sbo + 16 + half * 32 + odd * 16 + l)?;
                    let lane = pair % 4;
                    let q = f32::from((qb >> (lane * 2)) & 3);
                    let dm = self.weight(p.act_weight, (oc * blocks + block) * 2)?;
                    let dn = self.weight(p.act_weight, (oc * blocks + block) * 2 + 1)?;
                acc += self.load(p.in0, k * positions + position)?
                        * (dm * dm * q * lo - dn * dm * hi);
                }
                let biased = acc + self.weight(p.bias, oc)?;
                let index = oc * positions + position;
                let folded =
                    activate(biased, p.act, 0.0) + self.fused_res(p, index)? + shift;
                self.store(p.out, index, self.fused_quant(p, folded)?)?;
            }
        }
        Ok(())
    }

    /// One byte of the weights blob, for the quantised unpack helpers below.
    ///
    /// The shader reads quantised payloads through a `uint` view; here the same address
    /// arithmetic is done on the undecoded blob, so the two agree by construction.
    fn byte(&self, at: u32) -> Result<u8, String> {
        self.bytes
            .get(at as usize)
            .copied()
            .ok_or_else(|| format!("weight byte {at} of {}", self.bytes.len()))
    }

    /// Element `index` of the int8 tensor whose **32-bit word** offset is `word`.
    ///
    /// The shader reads these through a `uint` alias of the weights buffer and unpacks four bytes
    /// at a time, because a byte view would need `VK_KHR_8bit_storage` on top of the fp16
    /// extension this runtime already requires. Here the same address arithmetic is done on the
    /// undecoded blob, so the two agree by construction rather than by coincidence.
    fn int8(&self, word: u32, index: u32) -> Result<i8, String> {
        let at = (word as usize)
            .checked_mul(4)
            .and_then(|base| base.checked_add(index as usize))
            .ok_or("an int8 weight offset overflowed")?;
        self.bytes
            .get(at)
            .map(|&byte| byte as i8)
            .ok_or_else(|| format!("int8 weight byte {at} of {}", self.bytes.len()))
    }

    /// Element `index` of the int4 tensor whose **32-bit word** offset is `word`.
    ///
    /// Eight nibbles to a word, low nibble first, sign-extended from four bits. Mirrors
    /// `int4_at` in `shaders/common.glsl`; reading it unsigned would be a different
    /// quantisation, not a different spelling.
    fn int4(&self, word: u32, index: u32) -> Result<i8, String> {
        let at = (word as usize)
            .checked_mul(4)
            .and_then(|base| base.checked_add((index / 2) as usize))
            .ok_or("an int4 weight offset overflowed")?;
        let byte = self
            .bytes
            .get(at)
            .copied()
            .ok_or_else(|| format!("int4 weight byte {at} of {}", self.bytes.len()))?;
        let nibble = if index % 2 == 0 { byte & 0x0f } else { byte >> 4 };
        // Sign-extend from four bits: 0..7 stay, 8..15 become -8..-1.
        Ok(if nibble >= 8 { nibble as i8 - 16 } else { nibble as i8 })
    }

    /// The PReLU slope for output channel `c`, or zero when the activation is not PReLU.
    fn slope(&self, p: &Push, c: u32) -> Result<f32, String> {
        if p.act == act::PRELU {
            self.weight(p.act_weight, c)
        } else {
            Ok(0.0)
        }
    }

    /// Transposed convolution: ONNX semantics, weights `[in_c, out_c / group, kh, kw]`.
    ///
    /// Note the layout: input channels are outermost here and outermost-but-one in
    /// [`Reference::conv`]. That inversion is the one real difference between the two,
    /// and reading it the wrong way round still produces an output of the right shape.
    ///
    /// Written as a gather, like `conv_transpose.comp`: output row `oy` is fed by
    /// input row `(oy + pad_t - ky) / stride_h` whenever that division is exact.
    /// `group` is 1 at the only call site and is not modelled.
    fn conv_transpose(&mut self, p: &Push) -> Result<(), String> {
        if p.group != 1 {
            return Err(format!("a transposed convolution in {} groups", p.group));
        }
        // `fused_store` in `common.glsl` serves every convolution shader including this
        // one, so the reference matches it here. `Builder` never folds into a transpose
        // today — the selfie net's only one feeds the output directly — but if it ever
        // does, both sides already agree on what the store means.
        if p.res == super::NO_FUSE && p.shift == super::NO_FUSE {
            for oc in 0..p.out_c {
                let slope = self.slope(p, oc)?;
                for oy in 0..p.out_h {
                    for ox in 0..p.out_w {
                        let mut acc = self.weight(p.bias, oc)?;
                        for ic in 0..p.in_c {
                            let plane = ic * p.in_h * p.in_w;
                            let kernel_at = p.weight + (ic * p.out_c + oc) * p.kh * p.kw;
                            for ky in 0..p.kh {
                                let Some(iy) = source(oy + p.pad_t, ky, p.stride_h, p.in_h) else {
                                    continue;
                                };
                                let row = plane + iy * p.in_w;
                                let tap_at = kernel_at + ky * p.kw;
                                for kx in 0..p.kw {
                                    let Some(ix) = source(ox + p.pad_l, kx, p.stride_w, p.in_w)
                                    else {
                                        continue;
                                    };
                                    acc += self.load(p.in0, row + ix)?
                                        * self.weight(tap_at, kx)?;
                                }
                            }
                        }
                        self.store(p.out, nchw(p, oc, oy, ox), self.fused_quant(p, activate(acc, p.act, slope))?)?;
                    }
                }
            }
            return Ok(());
        }
        for oc in 0..p.out_c {
            let slope = self.slope(p, oc)?;
            let shift = self.fused_shift(p, oc)?;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    let mut acc = self.weight(p.bias, oc)?;
                    for ic in 0..p.in_c {
                        let plane = ic * p.in_h * p.in_w;
                        let kernel_at = p.weight + (ic * p.out_c + oc) * p.kh * p.kw;
                        for ky in 0..p.kh {
                            let Some(iy) = source(oy + p.pad_t, ky, p.stride_h, p.in_h) else {
                                continue;
                            };
                            let row = plane + iy * p.in_w;
                            let tap_at = kernel_at + ky * p.kw;
                            for kx in 0..p.kw {
                                let Some(ix) = source(ox + p.pad_l, kx, p.stride_w, p.in_w)
                                else {
                                    continue;
                                };
                                acc += self.load(p.in0, row + ix)?
                                    * self.weight(tap_at, kx)?;
                            }
                        }
                    }
                    let index = nchw(p, oc, oy, ox);
                    let folded =
                        activate(acc, p.act, slope) + self.fused_res(p, index)? + shift;
                    self.store(p.out, index, self.fused_quant(p, folded)?)?;
                }
            }
        }
        Ok(())
    }
}
