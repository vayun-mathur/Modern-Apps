impl<'a> Builder<'a> {
    fn emit_continued(
        &self,
        node: &Node,
        at: &dyn Fn(Id) -> Result<u32, String>,
        shape: &dyn Fn(Id) -> Shape,
        ops: &mut Vec<Op>,
    ) -> Result<(), String> {
        match node {
            Node::AttnApplyCached { probs, cache, out, heads, kv_heads, dynamic, sliding, window, quant_scale } => {
                let (sc, so) = (shape(*cache), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AttnApplyCached,
                    push: Push {
                        in0: at(*probs)?,
                        in1: at(*cache)?,
                        out: at(*out)?,
                        // As above: the cache's stride is `d_model`, which is also the output's
                        // channel count because attention preserves the width.
                        in_c: so.c,
                        in_h: 1,
                        // The key stride, which is the cache's *channel* count in this layout.
                        // When `dynamic`, only the leading `prefix + 1` of them are summed.
                        in_w: sc.c,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *heads,
                        count: so.len(),
                        dyn_keys: u32::from(*dynamic),
                        kv_heads: *kv_heads,
                        sliding: u32::from(*sliding),
                        // A folded int8 round-trip scale, or zero for none. Free on
                        // this kind (it carries no other scalar); `quant_store`
                        // in the shader reads it.
                        param0_bits: quant_scale.map_or(0, f32::to_bits),
                        // `[d_model, 1, queries]`: several queries take the per-query path.
                        dil_h: if so.w > 1 || *window != 0 { so.w } else { 0 },
                        dil_w: *window,
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::AttnScoresRelative { q, k, out, heads, scale, table, offsets } => {
                let (si, so) = (shape(*q), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AttnScoresRelative,
                    push: Push {
                        in0: at(*q)?,
                        in1: at(*k)?,
                        out: at(*out)?,
                        weight: *table,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        // The offset count reads as a kernel width, because that is what a
                        // band of `2 * window + 1` taps along the sequence is.
                        kw: *offsets,
                        group: *heads,
                        param0_bits: scale.to_bits(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::AttnApplyRelative { probs, v, out, heads, table, offsets } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::AttnApplyRelative,
                    push: Push {
                        in0: at(*probs)?,
                        in1: at(*v)?,
                        out: at(*out)?,
                        weight: *table,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        kw: *offsets,
                        group: *heads,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::AttnScoresBanded { q, k, out, heads, band, table, offsets, scale, cap } => {
                let (si, so) = (shape(*q), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AttnScoresBanded,
                    push: Push {
                        in0: at(*q)?,
                        in1: at(*k)?,
                        out: at(*out)?,
                        weight: *table,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        // The band reads as a kernel height and the offset count as its width:
                        // a window of taps along the sequence is what both of them are.
                        kh: *band,
                        kw: *offsets,
                        group: *heads,
                        param0_bits: scale.to_bits(),
                        param1_bits: cap.to_bits(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::AttnApplyBanded { probs, v, out, heads, band } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::AttnApplyBanded,
                    push: Push {
                        in0: at(*probs)?,
                        in1: at(*v)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        kh: *band,
                        group: *heads,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::SliceChannels { input, out, start } => {
                let so = shape(*out);
                // A channel range is contiguous, so this is one element-range move.
                ops.push(Op::Copy {
                    src: at(*input)? + start * so.h * so.w,
                    dst: at(*out)?,
                    elems: so.len(),
                });
            }
            Node::Embed { ids, out, table, rows } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Embed,
                    push: Push {
                        in0: at(*ids)?,
                        out: at(*out)?,
                        weight: *table,
                        // The table's row count, not the id tensor's extent: the shader
                        // clamps against it so an unknown symbol mispronounces a word
                        // rather than reading whatever follows the embedding.
                        in_w: *rows,
                        // Id lanes, 1 or 2. Not the output's channel count.
                        in_c: shape(*ids).c,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::CacheWrite { row, cache } => {
                let (sr, sc) = (shape(*row), shape(*cache));
                ops.push(Op::Dispatch {
                    kind: Kind::CacheWrite,
                    push: Push {
                        in0: at(*row)?,
                        out: at(*cache)?,
                        // The distance between cache rows, and the length of the one written.
                        in_c: sc.w,
                        // Positions the cache can hold, so a step past the end writes nothing.
                        in_h: sc.c,
                        in_w: 1,
                        // The cache's real dimensions: their product is the region
                        // `assert_no_aliasing` takes this op to write.
                        out_c: sc.c,
                        out_h: sc.h,
                        out_w: sc.w,
                        // Positions written at once. More than one is a prefill, whose K and V
                        // are channel-major and need transposing into the cache.
                        group: sr.len() / sc.w.max(1),
                        count: sr.len(),
                        ..Push::default()
                    },
                    invocations: sr.len(),
                });
            }
            Node::Clamp { input, out, bounds } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Clamp,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        act_weight: *bounds,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::MulScalar { input, out, scale } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::MulScalar,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        act_weight: *scale,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Activate { input, out, act } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Activate,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        act: act.code(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::GatedActivate { input, out, act } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::GatedActivate,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        // The distance from a gate element to its up partner: the whole gate
                        // half, which is the output's element count.
                        in_c: so.len(),
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        act: act.code(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Softcap { input, out, cap } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Softcap,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        param0_bits: cap.to_bits(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Argmax { input, out } => {
                let si = shape(*input);
                let so = shape(*out);
                // One `(value, lo, hi)` triple per workgroup: the partials
                // [`Node::ArgmaxFold`] folds. Groups from the shared helper, so the
                // device and the host interpreter split the row identically.
                // (`mod_part9b.rs` is inlined into `crate::nets`, so the helper is
                // a bare name here, not `super::`.)
                let groups = argmax_groups(si.len()).max(1);
                // The input is the concatenated logits row (all chunks, vocab-wide).
                ops.push(Op::Dispatch {
                    kind: Kind::Argmax,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: groups,
                        count: si.len(),
                        ..Push::default()
                    },
                    // One workgroup of 64 per group.
                    invocations: groups * 64,
                });
            }
            Node::ArgmaxFold { input, row, out } => {
                let si = shape(*input);
                let sr = shape(*row);
                let groups = si.len() / 2;
                ops.push(Op::Dispatch {
                    kind: Kind::ArgmaxFold,
                    push: Push {
                        in0: at(*input)?,
                        // The logits row, re-read at the candidate indices.
                        in1: at(*row)?,
                        in_c: groups,
                        in_w: sr.len(),
                        out: at(*out)?,
                        out_c: 2,
                        out_h: 1,
                        out_w: 1,
                        count: groups,
                        ..Push::default()
                    },
                    // One workgroup folds every candidate.
                    invocations: 64,
                });
            }
            Node::QuantizeBlocks { input, out } => {
                let (si, so) = (shape(*input), shape(*out));
                let count = si.c / crate::weights::I4_BLOCK * si.h * si.w;
                ops.push(Op::Dispatch {
                    kind: Kind::QuantizeBlocks,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count,
                        ..Push::default()
                    },
                    invocations: count,
                });
            }
            Node::Quantize { input, out, scale } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Quantize,
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
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Softmax { input, out, mode, sliding, window } => {
                let so = shape(*out);
                // One invocation per row of the last axis, each normalising `out_w`
                // contiguous elements, so the dispatch is rows rather than elements - or one
                // workgroup per row when the rows are few and long. The causal shader has no row
                // mode: it is prefill-only, where rows are plentiful. See `row_reduce`.
                let rows = so.c * so.h;
                let (kw, invocations) = match mode {
                    SoftmaxMode::Causal => (0, rows),
                    SoftmaxMode::Full | SoftmaxMode::Prefix => row_reduce_contiguous(rows, so.w),
                };
                ops.push(Op::Dispatch {
                    kind: match mode {
                        SoftmaxMode::Full => Kind::Softmax,
                        SoftmaxMode::Causal => Kind::SoftmaxCausal,
                        SoftmaxMode::Prefix => Kind::SoftmaxPrefix,
                    },
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: rows,
                        dyn_keys: u32::from(*mode == SoftmaxMode::Prefix),
                        sliding: u32::from(*sliding),
                        // Only the causal shader reads it, and only when non-zero.
                        kh: *window,
                        kw,
                        // A prefix softmax over several queries' rows (`[heads, queries, keys]`).
                        dil_h: if *mode == SoftmaxMode::Prefix && (so.h > 1 || *window != 0) { so.h } else { 0 },
                        dil_w: if *mode == SoftmaxMode::Prefix { *window } else { 0 },
                        ..Push::default()
                    },
                    invocations,
                });
            }
            Node::ConcatPositions { parts, out } => {
                let so = shape(*out);
                let base = at(*out)?;
                let mut column = 0;
                for &part in parts {
                    let sp = shape(part);
                    let src = at(part)?;
                    // A part is a column range, so one run per channel row rather than the single
                    // copy `Node::Concat` gets away with.
                    for row in 0..sp.c * sp.h {
                        ops.push(Op::Copy {
                            src: src + row * sp.w,
                            dst: base + row * so.w + column,
                            elems: sp.w,
                        });
                    }
                    column += sp.w;
                }
            }
            other => self.emit_last(other, at, shape, ops)?,
        }
        Ok(())
    }
}