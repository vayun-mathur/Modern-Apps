impl<'a> Builder<'a> {
    fn emit(
        &self,
        node: &Node,
        at: &dyn Fn(Id) -> Result<u32, String>,
        shape: &dyn Fn(Id) -> Shape,
        ops: &mut Vec<Op>,
    ) -> Result<(), String> {
        match node {
            Node::ConvInt8 {
                input,
                out,
                weight,
                scale,
                bias,
                kernel,
                stride,
                dilation,
                pad,
                group,
                act,
                quant,
                res,
                shift,
                quant_scale,
            } => {
                let (si, so) = (shape(*input), shape(*out));
                // The same test `Node::Conv` applies below, less the two cases that cannot arise
                // here: there is no transposed int8 convolution, and `Builder::conv_int8` refuses
                // `Act::PRelu` outright because the scale occupies the offset a slope would need.
                //
                // It matters more here than it does there: Supertonic's sampler is 92% pointwise
                // by parameter count and runs `2 * STEPS` times an utterance, so leaving it on the
                // untiled shader would cost far more time than int8 saves space.
                let tiled = *group == 1
                    && kernel == &(1, 1)
                    && stride == &(1, 1)
                    && pad == &(0, 0)
                    && si.h == so.h
                    && si.w == so.w;
                let positions = so.h * so.w;
                // At one position the tiled shader pads 15 of every 16 columns and stores from 8 of
                // every 64 invocations, so a single-position pointwise convolution goes to the gemv
                // shader instead. It is the whole of a SMaLL-100 decode step.
                let vector = tiled && positions == 1;
                let int8_tiles =
                    so.c.div_ceil(CONV_POINT_INT8_TILE) * positions.div_ceil(CONV_POINT_INT8_TILE);
                // One workgroup per row-group: 2 channels for int8, 8 for int4 and Q2_K —
                // the three gemv shaders diverged, so each kind counts its own rows.
                let rows = match quant {
                    Quant::I8 => so.c.div_ceil(CONV_VEC_ROWS),
                    Quant::I4 => so.c.div_ceil(CONV_VEC_INT4_ROWS),
                    Quant::Q2K => so.c.div_ceil(CONV_VEC_Q2K_ROWS),
                };
                let kind = match (quant, tiled, vector) {
                    (Quant::I8, _, true) => Kind::ConvVecInt8,
                    (Quant::I8, true, false) => Kind::ConvPointInt8,
                    (Quant::I8, false, false) => Kind::ConvInt8,
                    (Quant::I4, _, true) => Kind::ConvVecInt4,
                    (Quant::I4, true, false) => Kind::ConvPointInt4,
                    (Quant::I4, false, false) => {
                        return Err(format!(
                            "an int4 convolution with a {}x{} kernel or {group} groups: only the \
                             two 1x1 lowerings are quantised to four bits, because the padded \
                             and grouped shader has no int4 counterpart",
                            kernel.0, kernel.1
                        ))
                    }
                    (Quant::Q2K, _, true) => Kind::ConvVecQ2K,
                    (Quant::Q2K, true, false) => Kind::ConvPointQ2K,
                    (Quant::Q2K, false, false) => Kind::ConvQ2K,
                };
                // A one-row 1 x kw int8 convolution (whisper's stem) as the register-blocked
                // GEMM with K = (ic, kx): `row_taps` in conv_point_int8.comp, `in1` = 2.
                let row_gemm = kind == Kind::ConvInt8
                    && *group == 1
                    && kernel.0 == 1
                    && si.h == 1
                    && so.h == 1
                    && (si.c * kernel.1) % 16 == 0
                    && *weight % 4 == 0;
                let kind = if row_gemm { Kind::ConvPointInt8 } else { kind };
                // Too few 32 x 32 tiles to fill the GPU: two channels per invocation instead of
                // four, bit-identical. See `split_rows` in conv_point_int8.comp. On a Pixel 8,
                // splitting everything under 256 tiles took Supertonic's sampler 2199 -> 1521 ms.
                let split = kind == Kind::ConvPointInt8
                    && !row_gemm
                    && si.c % 16 == 0
                    && *weight % 4 == 0
                    && int8_tiles < CONV_POINT_INT8_SPLIT_BELOW;
                // Opted-in nets mark the wide paths 3 / 4 (fp16-widened weights, see
                // conv_point_widened.glsl); the runtime falls back to 0 / 1 if it did not widen.
                let widened = self.widen_int8
                    && kind == Kind::ConvPointInt8
                    && !row_gemm
                    && si.c % 16 == 0
                    && *weight % 4 == 0;
                // The untiled shader's register-blocked path (`in1` = 1): 4 channels x 8 columns
                // of a row per invocation. Ungrouped only: maia's grouped 1x1 got slower.
                let int8_blocked = kind == Kind::ConvInt8 && *group == 1;
                // The int4 GEMM's wide path (`in1` = 1, `wide()` in conv_point_int4.comp): 4 channels
                // x 8 positions per lane over 32 x 64 tiles, for whole blocks and quad-aligned columns.
                let input_at = at(*input)?;
                let int4_wide = kind == Kind::ConvPointInt4
                    && si.c % 32 == 0
                    && *weight % 4 == 0
                    && positions % 4 == 0
                    && input_at % 4 == 0;
                // A grouped single-position 1x1 (maia's bias expansion): `grouped_rows` in
                // conv_int8.comp, `in1` = 2, wide loads of each group's taps.
                let grouped_vec = kind == Kind::ConvInt8
                    && *group > 1
                    && positions == 1
                    && kernel == &(1, 1)
                    && (si.c / *group) % 16 == 0
                    && *weight % 4 == 0
                    && input_at % 4 == 0;
                // Workgroups for the staged kinds, output elements for the untiled one.
                let count = match kind {
                    Kind::ConvVecInt8 | Kind::ConvVecInt4 | Kind::ConvVecQ2K => rows,
                    Kind::ConvPointInt8 if split => {
                        so.c.div_ceil(CONV_POINT_INT8_TILE / 2) * positions.div_ceil(CONV_POINT_INT8_TILE)
                    }
                    Kind::ConvPointInt4 if int4_wide => so.c.div_ceil(CONV_POINT_INT8_TILE) * positions.div_ceil(64),
                    Kind::ConvPointInt8 | Kind::ConvPointInt4 | Kind::ConvPointQ2K => int8_tiles,
                    _ => so.len(),
                };
                ops.push(Op::Dispatch {
                    kind,
                    push: Push {
                        in0: input_at,
                        in1: if row_gemm || grouped_vec {
                            2
                        } else if widened {
                            3 + u32::from(split)
                        } else {
                            u32::from(split || int8_blocked || int4_wide)
                        },
                        out: at(*out)?,
                        // A word offset, not an fp16 one: int8 is unpacked through the
                        // 32-bit view of the weights buffer.
                        weight: *weight,
                        bias: *bias,
                        // The dequantisation scale rides in the field `PRelu` would use,
                        // which is why the two cannot be combined.
                        act_weight: *scale,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        // Neither staged shader reads these, unlike `Kind::ConvPoint`'s push block,
                        // which leaves them at zero. They are filled in either way so that
                        // `nets::reference` can serve all three kinds from one `conv_int8`, whose
                        // arithmetic is identical once the geometry above holds.
                        kh: kernel.0,
                        kw: kernel.1,
                        stride_h: stride.0,
                        stride_w: stride.1,
                        dil_h: dilation.0,
                        dil_w: dilation.1,
                        pad_t: pad.0,
                        pad_l: pad.1,
                        group: *group,
                        act: act.code(),
                        count,
                        res: Self::fuse_offset(*res, &at)?,
                        shift: Self::fuse_offset(*shift, &at)?,
                        // A folded int8 round-trip scale, or zero for none. `param0_bits`
                        // is free on the quantised convolutions (their dequantisation
                        // scale rides `act_weight`); `quant_store` in the shader reads it.
                        param0_bits: quant_scale.map_or(0, f32::to_bits),
                        ..Push::default()
                    },
                    // One workgroup of 64 per unit for the staged shaders; one invocation per
                    // output element for the untiled one.
                    invocations: match kind {
                        Kind::ConvVecInt8
                        | Kind::ConvPointInt8
                        | Kind::ConvVecInt4
                        | Kind::ConvPointInt4
                        | Kind::ConvVecQ2K
                        | Kind::ConvPointQ2K => count * 64,
                        Kind::ConvInt8 if int8_blocked => {
                            so.c.div_ceil(SPATIAL_BLOCK_OC) * so.h * so.w.div_ceil(SPATIAL_BLOCK_X)
                        }
                        _ => count,
                    },
                });
            }
            Node::Conv {
                input,
                out,
                weight,
                bias,
                kernel,
                stride,
                dilation,
                pad,
                group,
                act,
                act_weight,
                transpose,
                pad_edge,
                res,
                shift,
            } => {
                let (si, so) = (shape(*input), shape(*out));
                // An ungrouped 1x1 goes to the tiled path. Its geometry is a matrix multiply
                // over `out_h * out_w` positions, so stride, dilation and padding are all
                // trivially identity and nothing else in the push block changes meaning.
                let tiled = !*transpose
                    && *group == 1
                    && kernel == &(1, 1)
                    && stride == &(1, 1)
                    && pad == &(0, 0)
                    && si.h == so.h
                    && si.w == so.w;
                // A kernel covering the whole unpadded input (mbf's 7x7 GDC) is one dot product
                // per channel over the flattened input: a 1x1 over `c * h * w` channels.
                let full_window = !*transpose
                    && *group == 1
                    && kernel == &(si.h, si.w)
                    && dilation == &(1, 1)
                    && pad == &(0, 0)
                    && so.h == 1
                    && so.w == 1;
                if tiled || full_window {
                    let (in_c, in_h, in_w) =
                        if full_window { (si.len(), 1, 1) } else { (si.c, si.h, si.w) };
                    let positions = so.h * so.w;
                    let tiles =
                        so.c.div_ceil(CONV_POINT_TILE) * positions.div_ceil(CONV_POINT_TILE);
                    let in0 = at(*input)?;
                    // Too few tiles to fill the GPU: two channels per lane (`split_rows` in
                    // conv_point.comp), same sums in the same order. Wide alignment only.
                    let split = tiles < CONV_POINT_INT8_SPLIT_BELOW
                        && in_c % 8 == 0
                        && *weight % 8 == 0
                        && positions % 4 == 0
                        && in0 % 4 == 0;
                    let tiles = if split {
                        so.c.div_ceil(CONV_POINT_TILE / 2) * positions.div_ceil(CONV_POINT_TILE)
                    } else {
                        tiles
                    };
                    ops.push(Op::Dispatch {
                        kind: Kind::ConvPoint,
                        push: Push {
                            in0,
                            in1: u32::from(split),
                            out: at(*out)?,
                            weight: *weight,
                            bias: *bias,
                            act_weight: *act_weight,
                            in_c,
                            in_h,
                            in_w,
                            out_c: so.c,
                            out_h: so.h,
                            out_w: so.w,
                            act: act.code(),
                            // Tiles, not elements: one workgroup per tile.
                            count: tiles,
                            res: Self::fuse_offset(*res, &at)?,
                            shift: Self::fuse_offset(*shift, &at)?,
                            ..Push::default()
                        },
                    // 64 invocations a workgroup, so this asks for exactly `tiles` of them.
                        invocations: tiles * 64,
                    });
                    return Ok(());
                }
                // A spatial convolution on the register-blocked path, when the net opted
                // in with `Builder::spatial_tiling`. Same `Kind::Conv` and push block as
                // the untiled path below; `in1` = 1 selects the path in `conv.comp` and
                // there is one invocation per 4-channel x 8-column block of a row.
                // Transposed convolutions (different weight layout), edge-padded depthwise
                // convolutions and other grouped convolutions stay untiled. A depthwise
                // convolution takes `in1` = 2: one invocation per channel x 8-column block.
                let depthwise = *group > 1 && *group == si.c && *group == so.c;
                if self.spatial_tiled
                    && !*transpose
                    && (*group == 1 || (depthwise && !*pad_edge))
                {
                    let channel_blocks =
                        if depthwise { so.c } else { so.c.div_ceil(SPATIAL_BLOCK_OC) };
                    let blocks = channel_blocks * so.h * so.w.div_ceil(SPATIAL_BLOCK_X);
                    ops.push(Op::Dispatch {
                        kind: Kind::Conv,
                        push: Push {
                            in0: at(*input)?,
                            // The tiled selector. See `Push::in1`.
                            in1: if depthwise { 2 } else { 1 },
                            out: at(*out)?,
                            weight: *weight,
                            bias: *bias,
                            in_c: si.c,
                            in_h: si.h,
                            in_w: si.w,
                            out_c: so.c,
                            out_h: so.h,
                            out_w: so.w,
                            kh: kernel.0,
                            kw: kernel.1,
                            stride_h: stride.0,
                            stride_w: stride.1,
                            dil_h: dilation.0,
                            dil_w: dilation.1,
                            pad_t: pad.0,
                            pad_l: pad.1,
                            pad_edge: u32::from(*pad_edge),
                            group: *group,
                            act: act.code(),
                            act_weight: *act_weight,
                            count: so.len(),
                            res: Self::fuse_offset(*res, &at)?,
                            shift: Self::fuse_offset(*shift, &at)?,
                            ..Push::default()
                        },
                        invocations: blocks,
                    });
                    return Ok(());
                }
                // A 2x2 stride-2 unpadded transpose under spatial tiling: `in1` = 1 selects
                // the one-tap-per-output path in `conv_transpose.comp`, one invocation per
                // input pixel x 4 output channels.
                let blocked_transpose = self.spatial_tiled
                    && *transpose
                    && *group == 1
                    && kernel == &(2, 2)
                    && stride == &(2, 2)
                    && pad == &(0, 0)
                    && so.h == 2 * si.h
                    && so.w == 2 * si.w;
                ops.push(Op::Dispatch {
                    kind: if *transpose { Kind::ConvTranspose } else { Kind::Conv },
                    push: Push {
                        in0: at(*input)?,
                        in1: u32::from(blocked_transpose),
                        out: at(*out)?,
                        weight: *weight,
                        bias: *bias,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        kh: kernel.0,
                        kw: kernel.1,
                        stride_h: stride.0,
                        stride_w: stride.1,
                        dil_h: dilation.0,
                        dil_w: dilation.1,
                        pad_t: pad.0,
                        pad_l: pad.1,
                        pad_edge: u32::from(*pad_edge),
                        group: *group,
                        act: act.code(),
                        act_weight: *act_weight,
                        count: so.len(),
                        res: Self::fuse_offset(*res, &at)?,
                        shift: Self::fuse_offset(*shift, &at)?,
                        ..Push::default()
                    },
                    invocations: if blocked_transpose {
                        so.c.div_ceil(4) * si.h * si.w
                    } else {
                        so.len()
                    },
                });
            }
            Node::MaxPool { input, out, kernel, stride } => {
                let (si, so) = (shape(*input), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::MaxPool,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        kh: kernel.0,
                        kw: kernel.1,
                        stride_h: stride.0,
                        stride_w: stride.1,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::AvgPool { input, out, kernel, stride } => {
                let (si, so) = (shape(*input), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AvgPool,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        kh: kernel.0,
                        kw: kernel.1,
                        stride_h: stride.0,
                        stride_w: stride.1,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Resize { input, out, nearest } => {
                let (si, so) = (shape(*input), shape(*out));
                ops.push(Op::Dispatch {
                    kind: if *nearest { Kind::ResizeNearest } else { Kind::Resize },
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::GlobalAvgPool { input, out } => {
                let (si, so) = (shape(*input), shape(*out));
                // One invocation per channel, each reducing over its own plane, so
                // the dispatch is the channel count — or, for large planes, one
                // workgroup each. See `row_reduce_plane`.
                let (kw, invocations) = row_reduce_plane(si.c, si.h * si.w);
                ops.push(Op::Dispatch {
                    kind: Kind::GlobalAvgPool,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: 1,
                        out_w: 1,
                        count: so.len(),
                        kw,
                        ..Push::default()
                    },
                    invocations,
                });
            }
            other => {
                self.emit_mid(other, at, shape, ops)?;
            }
        }
        Ok(())
    }
}