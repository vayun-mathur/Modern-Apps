impl Reference {

    /// Average pooling over an explicit window, floored and unpadded.
    ///
    /// The divisor is the window size rather than the number of elements actually read,
    /// which is only correct because `Builder::avg_pool` refuses a window that overhangs.
    fn avg_pool(&mut self, p: &Push) -> Result<(), String> {
        let window = p.kh * p.kw;
        if window == 0 {
            return Err("an average pool over an empty window".into());
        }
        for oc in 0..p.out_c {
            let plane = oc * p.in_h * p.in_w;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    let mut total = 0.0;
                    for ky in 0..p.kh {
                        let iy = oy * p.stride_h + ky;
                        if iy >= p.in_h {
                            continue;
                        }
                        for kx in 0..p.kw {
                            let ix = ox * p.stride_w + kx;
                            if ix >= p.in_w {
                                continue;
                            }
                            total += self.load(p.in0, plane + iy * p.in_w + ix)?;
                        }
                    }
                    self.store(p.out, nchw(p, oc, oy, ox), total / window as f32)?;
                }
            }
        }
        Ok(())
    }

    /// Bilinear resize under the `half_pixel` convention, `src = (dst + 0.5) * in / out - 0.5`.
    ///
    /// The same formula [`crate::preprocess`] uses for the input resize. That
    /// agreement is load-bearing: U^2-Netp's decoder upsamples and then adds an
    /// encoder skip five times, so a half-pixel disagreement between the two paths
    /// would misregister every one of those residuals.
    fn resize(&mut self, p: &Push) -> Result<(), String> {
        for oc in 0..p.out_c {
            let plane = oc * p.in_h * p.in_w;
            for oy in 0..p.out_h {
                let (y0, y1, ty) = bracket(oy, p.in_h, p.out_h)?;
                for ox in 0..p.out_w {
                    let (x0, x1, tx) = bracket(ox, p.in_w, p.out_w)?;
                    let row0 = plane + y0 * p.in_w;
                    let row1 = plane + y1 * p.in_w;
                    let top = lerp(self.load(p.in0, row0 + x0)?, self.load(p.in0, row0 + x1)?, tx);
                    let bottom =
                        lerp(self.load(p.in0, row1 + x0)?, self.load(p.in0, row1 + x1)?, tx);
                    self.store(p.out, nchw(p, oc, oy, ox), lerp(top, bottom, ty))?;
                }
            }
        }
        Ok(())
    }

    /// Nearest-neighbour resize, ONNX `asymmetric` coordinates with `floor` rounding:
    /// `src = floor(dst * in / out)`, which in integers is an exact division.
    ///
    /// SCRFD's feature pyramid, and deliberately a different function from
    /// [`Reference::resize`] rather than a mode flag — the two agree nowhere.
    fn resize_nearest(&mut self, p: &Push) -> Result<(), String> {
        if p.out_h == 0 || p.out_w == 0 || p.in_h == 0 || p.in_w == 0 {
            return Err("a resize of a zero extent".into());
        }
        for oc in 0..p.out_c {
            let plane = oc * p.in_h * p.in_w;
            for oy in 0..p.out_h {
                let iy = (oy * p.in_h / p.out_h).min(p.in_h - 1);
                for ox in 0..p.out_w {
                    let ix = (ox * p.in_w / p.out_w).min(p.in_w - 1);
                    let value = self.load(p.in0, plane + iy * p.in_w + ix)?;
                    self.store(p.out, nchw(p, oc, oy, ox), value)?;
                }
            }
        }
        Ok(())
    }

    /// Mean over H and W, to `C x 1 x 1`. ONNX `ReduceMean` over axes `[2, 3]`.
    fn global_avg_pool(&mut self, p: &Push) -> Result<(), String> {
        let elements = p.in_h * p.in_w;
        if elements == 0 {
            return Err("a global average pool over nothing".into());
        }
        for channel in 0..p.out_c {
            let plane = channel * elements;
            let mut total = 0.0;
            for i in 0..elements {
                total += self.load(p.in0, plane + i)?;
            }
            self.store(p.out, channel, total / elements as f32)?;
        }
        Ok(())
    }

    /// `out[o][p] = act(sum_i W[o][i] * in[i][p] + bias[o])`, a `1 x 1` convolution.
    ///
    /// The same arithmetic as [`Self::conv`] at kernel `1 x 1`, written separately because the
    /// device shader tiles it and carries a **tile** count in `push.count` rather than an
    /// element count. Every `1 x 1` in every net module now routes here, so the existing
    /// fixtures cover it.
    fn conv_point(&mut self, p: &Push) -> Result<(), String> {
        let positions = p.out_h * p.out_w;
        for channel in 0..p.out_c {
            let bias = self.weight(p.bias, channel)?;
            let shift = self.fused_shift(p, channel)?;
            let slope = self.slope(p, channel)?;
            for position in 0..positions {
                let mut total = 0.0f32;
                for input in 0..p.in_c {
                    total += self.weight(p.weight, channel * p.in_c + input)?
                        * self.load(p.in0, input * positions + position)?;
                }
                let index = channel * positions + position;
                let folded =
                    activate(total + bias, p.act, slope) + self.fused_res(p, index)? + shift;
                self.store(p.out, index, self.fused_quant(p, folded)?)?;
            }
        }
        Ok(())
    }

    /// `out[c][t] = table[id(t)][c]`, with the id read out of the arena as fp16, in one lane
    /// or in two (`lo + 2048 * hi`) for a table past `EMBED_LANE` rows.
    fn embed(&mut self, p: &Push) -> Result<(), String> {
        let rows = p.in_w;
        if rows == 0 {
            return Err("an embedding table with no rows".into());
        }
        for position in 0..p.out_w {
            let mut raw = self.load(p.in0, position)?;
            if p.in_c == 2 {
                raw += 2048.0 * self.load(p.in0, p.out_w + position)?;
            }
            // Round rather than truncate, and clamp: an unknown symbol should mispronounce
            // a word rather than read past the table.
            let id = ((raw + 0.5).max(0.0) as u32).min(rows - 1);
            for channel in 0..p.out_c {
                let value = self.weight(p.weight, id * p.out_c + channel)?;
                self.store(p.out, nchw(p, channel, 0, position), value)?;
            }
        }
        Ok(())
    }

    /// `out[i] = weights[i]`, a learned tensor copied into the arena. See [`Kind::Constant`].
    fn constant(&mut self, p: &Push) -> Result<(), String> {
        for i in 0..p.count {
            let value = self.weight(p.weight, i)?;
            self.store(p.out, i, value)?;
        }
        Ok(())
    }

    /// `out[c][y][x] = a[c][y][x] + b[c]`, a per-channel shift.
    fn add_broadcast(&mut self, p: &Push) -> Result<(), String> {
        for oc in 0..p.out_c {
            let shift = self.load(p.in1, oc)?;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    let at = nchw(p, oc, oy, ox);
                    let value = self.load(p.in0, at)?;
                    self.store(p.out, at, value + shift)?;
                }
            }
        }
        Ok(())
    }

    /// Rotary position embedding, the half-split convention. See [`Kind::Rotary`].
    fn rotary(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = p.in_c;
        // Independent rotary blocks inside one head; 1 is ordinary RoPE. See `Push::rope_axes`.
        let axes = p.rope_axes.max(1);
        if head_dim == 0 || !head_dim.is_multiple_of(axes) {
            return Err(format!("a rotary head of {head_dim} channels in {axes} blocks"));
        }
        let block = head_dim / axes;
        if block == 0 || !block.is_multiple_of(2) {
            return Err(format!("a rotary block of {block} channels"));
        }
        let half = block / 2;
        for channel in 0..p.out_c {
            let within = channel % head_dim;
            let sub = within / block;
            let local = within % block;
            let frequency = local % half;
            let lower = local < half;
            let partner = if lower { channel + half } else { channel - half };
            let base = sub * block;
            for position in 0..p.out_w {
                let angle_cos = self.load(p.in1, (base + frequency) * p.out_w + position)?;
                let angle_sin =
                    self.load(p.in1, (base + half + frequency) * p.out_w + position)?;
                let self_value = self.load(p.in0, channel * p.out_w + position)?;
                let other = self.load(p.in0, partner * p.out_w + position)?;
                let rotated = if lower { -other } else { other } * angle_sin;
                self.store(p.out, channel * p.out_w + position, self_value * angle_cos + rotated)?;
            }
        }
        Ok(())
    }

    /// `out = in * scale + shift`, both scalars carried as raw bits in the push block.
    fn affine(&mut self, p: &Push) -> Result<(), String> {
        let scale = f32::from_bits(p.param0_bits);
        let shift = f32::from_bits(p.param1_bits);
        for i in 0..p.count {
            let value = self.load(p.in0, i)?;
            self.store(p.out, i, value * scale + shift)?;
        }
        Ok(())
    }

    /// Layer normalisation over the channel axis, per spatial position.
    ///
    /// Biased variance, dividing by `C` rather than `C - 1`, which is what every
    /// framework does at inference. `count` is the spatial extent rather than the element
    /// count, because one invocation handles a whole column of channels.
    fn layer_norm(&mut self, p: &Push) -> Result<(), String> {
        if p.in_c == 0 {
            return Err("a layer norm over zero channels".into());
        }
        let stride = p.in_h * p.in_w;
        let epsilon = f32::from_bits(p.param1_bits);
        for position in 0..p.count {
            let mut total = 0.0;
            for c in 0..p.in_c {
                total += self.load(p.in0, c * stride + position)?;
            }
            let mean = total / p.in_c as f32;

            let mut variance = 0.0;
            for c in 0..p.in_c {
                let centred = self.load(p.in0, c * stride + position)? - mean;
                variance += centred * centred;
            }
            let inverse = 1.0 / (variance / p.in_c as f32 + epsilon).sqrt();

            for c in 0..p.in_c {
                let at = c * stride + position;
                let centred = self.load(p.in0, at)? - mean;
                let gamma = self.weight(p.weight, c)?;
                let beta = self.weight(p.bias, c)?;
                self.store(p.out, at, centred * inverse * gamma + beta)?;
            }
        }
        Ok(())
    }

    /// The argmax of a logits row, as the host reads it back.
    ///
    /// The first of the two stages [`Builder::argmax`](super::Builder::argmax) emits,
    /// mirroring `shaders/argmax.comp`: the row splits into [`argmax_groups`](super::argmax_groups)
    /// slices and each workgroup keeps its slice's first maximum (strict `>`), stored
    /// as a `(lo, hi)` index pair with `lo = id % 2048`, `hi = id / 2048`.
    /// See `Builder::argmax`.
    fn argmax(&mut self, p: &Push) -> Result<(), String> {
        let len = (p.in_c * p.in_h * p.in_w).max(1);
        let groups = super::argmax_groups(len).max(1);
        let slice = len.div_ceil(groups);
        for group in 0..groups {
            let mut top_val = f32::NEG_INFINITY;
            let mut top_idx = 0u32;
            let end = (group + 1) * slice;
            for i in group * slice..end.min(len) {
                let value = self.load(p.in0, i)?;
                if value > top_val {
                    top_val = value;
                    top_idx = i;
                }
            }
            let at = p.out + group * 2;
            self.store(at, 0, (top_idx % 2048) as f32)?;
            self.store(at, 1, (top_idx / 2048) as f32)?;
            // The slice maximum, kept only to mirror the shader's lane reduction. The
            // fold below re-reads the row rather than trusting this fp16 value, so a
            // near-tie the store would round is still decided exactly.
            let _ = top_val;
        }
        Ok(())
    }

    /// Fold [`Reference::argmax`]'s partials to the winning index.
    ///
    /// Mirrors `shaders/argmax_fold.comp`: each candidate index is re-read from the row
    /// at fp32 and the greatest wins, keeping the FIRST index on ties — exactly the
    /// single-stage scan, whatever the fp16 store above rounded. Stored as
    /// `lo = id % 2048`, `hi = id / 2048` fp16 values. See `Builder::argmax`.
    fn argmax_fold(&mut self, p: &Push) -> Result<(), String> {
        let groups = p.in_c.max(1);
        let mut top_val = f32::NEG_INFINITY;
        let mut top_idx = 0u32;
        for group in 0..groups {
            let at = p.in0 + group * 2;
            let index = self.load(at, 0)? as u32 + 2048 * self.load(at, 1)? as u32;
            // Loud on a bad index rather than clamped: every candidate comes from a
            // slice of this row, so an out-of-range one is a builder bug.
            let value = self.load(p.in1, index)?;
            if value > top_val {
                top_val = value;
                top_idx = index;
            }
        }
        self.store(p.out, 0, (top_idx % 2048) as f32)?;
        self.store(p.out, 1, (top_idx / 2048) as f32)?;
        Ok(())
    }

    /// Root-mean-square normalisation over the channel axis, per spatial position.
    ///
    /// [`Reference::layer_norm`] without the mean and without beta, and so one pass over
    /// the column rather than two. Epsilon is inside the square root, matching
    /// `torch.nn.RMSNorm`.
    fn rms_norm(&mut self, p: &Push) -> Result<(), String> {
        if p.in_c == 0 {
            return Err("an rms norm over zero channels".into());
        }
        let stride = p.in_h * p.in_w;
        let groups = p.group.max(1);
        if !p.in_c.is_multiple_of(groups) {
            return Err(format!("{} channels do not split into {groups} groups", p.in_c));
        }
        let per_group = p.in_c / groups;
        let epsilon = f32::from_bits(p.param1_bits);
        // `count` is `groups * stride`, so this walks one group at one position at a time.
        for index in 0..p.count {
            let group = index.checked_div(stride).unwrap_or(0);
            let position = index.checked_rem(stride).unwrap_or(0);
            let base = group * per_group;
            let mut squares = 0.0;
            for c in 0..per_group {
                let value = self.load(p.in0, (base + c) * stride + position)?;
                squares += value * value;
            }
            let inverse = 1.0 / (squares / per_group as f32 + epsilon).sqrt();

            for c in 0..per_group {
                let at = (base + c) * stride + position;
                let value = self.load(p.in0, at)?;
                // Gamma is indexed within the group, so every group shares one table.
                let gamma = self.weight(p.weight, c)?;
                // A folded int8 round-trip applies to the norm output itself,
                // before the residual below — mirroring `quant_store` in
                // `rmsnorm.comp`, which rounds before adding `p.res`.
                let normed = self.fused_quant(p, value * inverse * gamma)?;
                // A residual addend folded by `Builder::finish` (see `Push::res`):
                // the post-norm `add(x, branch)` the fusion moved into this
                // store. Same contract as the convolution stores.
                let folded = if p.res == super::NO_FUSE {
                    normed
                } else {
                    normed + self.load(p.res, at)?
                };
                self.store(p.out, at, folded)?;
            }
        }
        Ok(())
    }

    /// Elementwise sum of two equal shapes.
    fn add(&mut self, p: &Push) -> Result<(), String> {
        for i in 0..p.count {
            let sum = self.load(p.in0, i)? + self.load(p.in1, i)?;
            self.store(p.out, i, sum)?;
        }
        Ok(())
    }

    /// A folded residual addend, or 0.0 when the op stores its accumulator unfolded.
    ///
    /// Mirrors the `p.res` branch of the convolution shaders: `activate(acc + bias)` plus
    /// the same-indexed element of a same-shaped tensor. The single fp16 rounding happens
    /// in the caller's `store`, exactly as the single `float16_t()` in the shader — the
    /// unfolded pair would round twice, so this is not spelled as `add` after the fact.
    fn fused_res(&self, p: &Push, index: u32) -> Result<f32, String> {
        if p.res == super::NO_FUSE {
            Ok(0.0)
        } else {
            self.load(p.res, index)
        }
    }

    /// A folded per-channel shift, or 0.0 when the op stores unfolded.
    ///
    /// Mirrors the `p.shift` branch: Supertonic's timestep conditioning adds one value per
    /// output channel, broadcast over every position of that channel.
    fn fused_shift(&self, p: &Push, channel: u32) -> Result<f32, String> {
        if p.shift == super::NO_FUSE {
            Ok(0.0)
        } else {
            self.load(p.shift, channel)
        }
    }

    /// A folded int8 round-trip scale applied to the stored value, or the value
    /// unchanged when the op stores unfolded.
    ///
    /// Mirrors `quant_store` in `shaders/common.glsl`: the outermost store
    /// operation, after the activation and the folded addends. `param0_bits`
    /// of zero is the unfused default every push carries unless the
    /// quantize fold set a scale. Same arithmetic as [`Self::quantize`].
    fn fused_quant(&self, p: &Push, value: f32) -> Result<f32, String> {
        if p.param0_bits == 0 {
            return Ok(value);
        }
        let scale = f32::from_bits(p.param0_bits);
        if scale.partial_cmp(&0.0) != Some(std::cmp::Ordering::Greater) {
            return Err(format!("a fused quantize of {scale}"));
        }
        let q = ((value / scale + 0.5).floor()).clamp(-128.0, 127.0);
        Ok(q * scale)
    }
}
