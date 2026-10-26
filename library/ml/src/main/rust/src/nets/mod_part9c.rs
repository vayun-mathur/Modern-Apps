impl<'a> Builder<'a> {
    fn emit_mid(
        &self,
        node: &Node,
        at: &dyn Fn(Id) -> Result<u32, String>,
        shape: &dyn Fn(Id) -> Shape,
        ops: &mut Vec<Op>,
    ) -> Result<(), String> {
        match node {
            Node::Binary { kind, a, b, out } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: *kind,
                    push: Push {
                        in0: at(*a)?,
                        in1: at(*b)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Concat { parts, out } => {
                let base = at(*out)?;
                let mut written = 0;
                for &part in parts {
                    let len = shape(part).len();
                    ops.push(Op::Copy {
                        src: at(part)?,
                        dst: base + written,
                        elems: len,
                    });
                    written += len;
                }
            }
            Node::Affine { input, out, scale, shift } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Affine,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        param0_bits: scale.to_bits(),
                        param1_bits: shift.to_bits(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::LayerNorm { input, out, gamma, beta, epsilon } => {
                let so = shape(*out);
                // One invocation per position, each reducing over the channels, so the
                // dispatch is the spatial extent rather than the element count - or, for a
                // handful of positions, one workgroup each. See `row_reduce`.
                let positions = so.h * so.w;
                let (kw, invocations) = row_reduce(positions, so.c);
                ops.push(Op::Dispatch {
                    kind: Kind::LayerNorm,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        weight: *gamma,
                        bias: *beta,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        param1_bits: epsilon.to_bits(),
                        count: positions,
                        kw,
                        ..Push::default()
                    },
                    invocations,
                });
            }
            Node::InstanceNorm { input, out, epsilon } => {
                let so = shape(*out);
                // One invocation per channel, each reducing over its own plane, so
                // the dispatch is the channel count rather than the element count —
                // or, for large planes, one workgroup each. See `row_reduce_plane`.
                let (kw, invocations) = row_reduce_plane(so.c, so.h * so.w);
                ops.push(Op::Dispatch {
                    kind: Kind::InstanceNorm,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        param1_bits: epsilon.to_bits(),
                        count: so.c,
                        kw,
                        ..Push::default()
                    },
                    invocations,
                });
            }
            Node::RmsNorm { input, out, gamma, epsilon, groups, res, quant_scale } => {
                let so = shape(*out);
                // One invocation per group per position, one workgroup each when there are few,
                // or 16 positions a workgroup when they are strided. See `norm_reduce`.
                let positions = so.h * so.w * groups.max(&1);
                let (kw, invocations) = norm_reduce(positions, so.c / groups.max(&1), so.h * so.w);
                // A folded residual addend, or NO_FUSE. Same contract as the
                // convolution kinds: `fused_store` in the shader adds
                // `arena[res + index]`, and `arena_reads` covers the range.
                let res_at = match res {
                    Some(id) => at(*id)?,
                    None => crate::nets::NO_FUSE,
                };
                ops.push(Op::Dispatch {
                    kind: Kind::RmsNorm,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        weight: *gamma,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *groups,
                        param0_bits: quant_scale.map_or(0, f32::to_bits),
                        param1_bits: epsilon.to_bits(),
                        count: positions,
                        res: res_at,
                        kw,
                        ..Push::default()
                    },
                    invocations,
                });
            }
            Node::AttnScores { q, k, out, heads, kv_heads, scale } => {
                let (si, so) = (shape(*q), shape(*out));
                let (in0, in1) = (at(*q)?, at(*k)?);
                // The tiled shader reads four queries and four keys per load, so both lengths and
                // both bases must divide by four. See `shaders/attn_scores.comp`.
                let tiled = so.h >= 4
                    && so.h % 4 == 0
                    && so.w % 4 == 0
                    && in0 % 4 == 0
                    && in1 % 4 == 0;
                let tiles = so.c * so.h.div_ceil(ATTN_TILE) * so.w.div_ceil(ATTN_TILE);
                // Otherwise the same blocking through scalar loads (`kw` 2), e.g. a vision tower's
                // 594 patches. Long maps only: the shorter ragged ones (NLLB sources, OCR lines,
                // TinyCLIP's 197 tokens) keep the per-element shader and so their exact outputs.
                let ragged = !tiled && so.h >= 256 && so.w >= 256;
                let (kw, count, invocations) = if tiled {
                    (1, tiles, tiles * 64)
                } else if ragged {
                    (2, tiles, tiles * 64)
                } else {
                    (0, so.len(), so.len())
                };
                ops.push(Op::Dispatch {
                    kind: Kind::AttnScores,
                    push: Push {
                        in0,
                        in1,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *heads,
                        kv_heads: *kv_heads,
                        param0_bits: scale.to_bits(),
                        count,
                        kw,
                        ..Push::default()
                    },
                    invocations,
                });
            }
            Node::AttnScoresCached { q, cache, out, heads, kv_heads, scale, dynamic, sliding, window } => {
                let (sq, so) = (shape(*q), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AttnScoresCached,
                    push: Push {
                        in0: at(*q)?,
                        in1: at(*cache)?,
                        out: at(*out)?,
                        // `d_model`, which doubles as the cache's per-position stride: a cache is
                        // `[keys, 1, d_model]`, so one position is `in_c` elements.
                        in_c: sq.c,
                        in_h: 1,
                        in_w: 1,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *heads,
                        param0_bits: scale.to_bits(),
                        count: so.len(),
                        dyn_keys: u32::from(*dynamic),
                        kv_heads: *kv_heads,
                        sliding: u32::from(*sliding),
                        // Several queries (`so.h`): the per-query range path. Zero otherwise,
                        // so a decode step's push is unchanged.
                        dil_h: if so.h > 1 || *window != 0 { so.h } else { 0 },
                        dil_w: *window,
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            other => {
                self.emit_continued(other, at, shape, ops)?;
            }
        }
        Ok(())
    }
}
