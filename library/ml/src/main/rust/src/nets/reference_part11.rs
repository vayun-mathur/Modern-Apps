impl Reference {
    /// Max pooling, floored and unpadded — which is what all 33 uses are.
    fn max_pool(&mut self, p: &Push) -> Result<(), String> {
        for oc in 0..p.out_c {
            let plane = oc * p.in_h * p.in_w;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    // The most negative fp16 rather than -inf, as `maxpool.comp` uses:
                    // every window here is fully inside the input, so it is never the
                    // answer.
                    let mut best = -65504.0f32;
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
                            best = best.max(self.load(p.in0, plane + iy * p.in_w + ix)?);
                        }
                    }
                    self.store(p.out, nchw(p, oc, oy, ox), best)?;
                }
            }
        }
        Ok(())
    }

    /// Instance normalisation over the spatial axes, per channel, with no affine.
    ///
    /// Mirrors `shaders/instancenorm.comp`: biased variance like every inference
    /// norm, epsilon inside the square root like `torch.nn.InstanceNorm2d`.
    /// `count` is the channel count, because one invocation handles a whole plane.
    fn instance_norm(&mut self, p: &Push) -> Result<(), String> {
        if p.in_c == 0 {
            return Err("an instance norm over zero channels".into());
        }
        let plane = p.in_h * p.in_w;
        if plane == 0 {
            return Err("an instance norm over an empty plane".into());
        }
        let epsilon = f32::from_bits(p.param1_bits);
        for c in 0..p.count {
            let base = c * plane;
            let mut total = 0.0;
            for i in 0..plane {
                total += self.load(p.in0, base + i)?;
            }
            let mean = total / plane as f32;

            let mut variance = 0.0;
            for i in 0..plane {
                let centred = self.load(p.in0, base + i)? - mean;
                variance += centred * centred;
            }
            let inverse = 1.0 / (variance / plane as f32 + epsilon).sqrt();

            for i in 0..plane {
                let centred = self.load(p.in0, base + i)? - mean;
                self.store(p.out, base + i, centred * inverse)?;
            }
        }
        Ok(())
    }

    /// `conv_point_int4_dot.comp`: the input quantised per position per 32-tap block to int8
    /// (`quantize_blocks.comp`'s rounding), exact integer block sums, then
    /// `acc += isum * (ascale * wscale)`. Read from the fp16 input, which the op keeps live, so
    /// [`Kind::QuantizeBlocks`] has nothing to do here.
    fn conv_int4_dot(&mut self, p: &Push) -> Result<(), String> {
        let block = crate::weights::I4_BLOCK;
        let blocks = p.in_c / block;
        let positions = p.out_h * p.out_w;
        let mut staged = vec![(0.0f32, [0i32; 32]); (blocks * positions) as usize];
        for b in 0..blocks {
            for position in 0..positions {
                let mut v = [0.0f32; 32];
                for (t, slot) in v.iter_mut().enumerate() {
                    *slot = self.load(p.in0, (b * block + t as u32) * positions + position)?;
                }
                let amax = v.iter().fold(0.0f32, |m, x| m.max(x.abs()));
                let inv = if amax > 0.0 { 127.0 / amax } else { 0.0 };
                let mut q = [0i32; 32];
                for (slot, x) in q.iter_mut().zip(v) {
                    *slot = ((x * inv).round_ties_even() as i32).clamp(-127, 127);
                }
                if let Some(cell) = staged.get_mut((b * positions + position) as usize) {
                    *cell = (amax / 127.0, q);
                }
            }
        }
        for oc in 0..p.out_c {
            let shift = self.fused_shift(p, oc)?;
            for position in 0..positions {
                let mut acc = 0.0f32;
                for b in 0..blocks {
                    let (ascale, q) = staged
                        .get((b * positions + position) as usize)
                        .copied()
                        .ok_or("staged block out of range")?;
                    let mut isum = 0i32;
                    for (t, x) in q.iter().enumerate() {
                        isum += x * i32::from(self.int4(p.weight, oc * p.in_c + b * block + t as u32)?);
                    }
                    acc += isum as f32 * (ascale * self.weight(p.act_weight, oc * blocks + b)?);
                }
                let biased = acc + self.weight(p.bias, oc)?;
                let index = oc * positions + position;
                let folded = activate(biased, p.act, 0.0) + self.fused_res(p, index)? + shift;
                self.store(p.out, index, self.fused_quant(p, folded)?)?;
            }
        }
        Ok(())
    }
}
