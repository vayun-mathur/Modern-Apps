#[cfg(test)]
pub(crate) mod tests {

    /// `CONV_VEC_ROWS` must equal `ROWS` in the int8 gemv shader.
    ///
    /// The same number is declared twice across two languages and nothing connects them:
    /// Rust uses it to decide how many workgroups to dispatch, the shader to decide which
    /// channels a workgroup owns. Disagreeing does not fail to build - it dispatches too few
    /// workgroups and leaves most output channels never written, which reads as a plausible
    /// wrong answer rather than an error. That happened while tuning occupancy, and only the
    /// device parity fixtures caught it.
    ///
    /// The int4 gemv shader is deliberately not in this list: it runs `ROWS` 8 for the
    /// stream reason its header gives, and its workgroup count comes from
    /// [`CONV_VEC_INT4_ROWS`], checked by the test below it.
    #[test]
    fn the_gemv_row_count_matches_both_shaders() {
        assert_eq!(
            gemv_rows_of("conv_vec_int8.comp"),
            CONV_VEC_ROWS,
            "the int8 gemv shader and Rust disagree on channels per workgroup"
        );
    }

    /// `CONV_VEC_INT4_ROWS` must equal `ROWS` in the int4 gemv shader.
    ///
    /// The same agreement as the int8 test above, for the shader that diverged to 8:
    /// Rust dispatches `out / 8` workgroups and the shader owns 8 channels each. At 8
    /// the failure mode is the same — silent undispatch — and the shared-memory
    /// partials are also sized by it, so a mismatch corrupts the reduction too.
    #[test]
    fn the_int4_gemv_row_count_matches_its_shader() {
        assert_eq!(
            gemv_rows_of("conv_vec_int4.comp"),
            CONV_VEC_INT4_ROWS,
            "the int4 gemv shader and Rust disagree on channels per workgroup"
        );
    }

    /// `CONV_VEC_Q2K_ROWS` must equal `ROWS` in the Q2_K gemv shader.
    ///
    /// The same agreement as the int4 test above, for the shader that clones its lane
    /// pattern: Rust dispatches `out / 8` workgroups and the shader owns 8 channels each.
    /// Disagreeing dispatches too few workgroups and leaves most output channels never
    /// written, which reads as a plausible wrong answer rather than an error.
    #[test]
    fn the_q2k_gemv_row_count_matches_its_shader() {
        assert_eq!(
            gemv_rows_of("conv_vec_q2k.comp"),
            CONV_VEC_Q2K_ROWS,
            "the Q2_K gemv shader and Rust disagree on channels per workgroup"
        );
    }

    /// `ARGMAX_PER_LANE` must equal `PER_LANE` in the argmax partials shader, and the
    /// group counts must put NLLB's and MADLAD's vocabularies in the multi-workgroup
    /// regime with room to spare.
    #[test]
    fn the_argmax_geometry_matches_its_shader() {
        let path =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("shaders").join("argmax.comp");
        let source = std::fs::read_to_string(&path).expect("the argmax shader reads");
        let per_lane = source
            .lines()
            .find_map(|line| {
                line.trim_start().strip_prefix("#define PER_LANE").and_then(|rest| {
                    rest.trim().trim_end_matches('u').parse::<u32>().ok()
                })
            })
            .unwrap_or_else(|| panic!("argmax.comp declares no PER_LANE"));
        assert_eq!(per_lane, ARGMAX_PER_LANE);
        // One lane's share at full occupancy: 64 lanes x 64 groups x PER_LANE.
        assert_eq!(64 * 64 * ARGMAX_PER_LANE, 262_144);
        // Both shipping vocabularies reduce over dozens of groups, not one.
        assert_eq!(argmax_groups(256_206), 63);
        assert_eq!(argmax_groups(256_000), 63);
        // Ragged and small rows still dispatch at least one workgroup.
        assert_eq!(argmax_groups(262_143), 64);
        assert_eq!(argmax_groups(1_000), 1);
        assert_eq!(argmax_groups(0), 1);
    }
    /// `CONV_POINT_INT8_TILE` must equal `TILE` in the int8 tiled shader.
    ///
    /// Rust dispatches one workgroup per `TILE x TILE` block and the shader owns that block, so a
    /// mismatch leaves part of every projection unwritten - plausible wrong numbers, not an error.
    #[test]
    fn the_int8_tile_matches_its_shader() {
        assert_eq!(tile_of("conv_point_int8.comp"), CONV_POINT_INT8_TILE);
        // The Q2_K tiled shader shares the int8 one's geometry and its workgroup count.
        assert_eq!(tile_of("conv_point_q2k.comp"), CONV_POINT_INT8_TILE);
    }

    /// `ATTN_TILE` must equal `TILE` in both attention shaders' tiled modes, for the same reason.
    #[test]
    fn the_attention_tiles_match_their_shaders() {
        assert_eq!(tile_of("attn_scores.comp"), ATTN_TILE);
        assert_eq!(tile_of("attn_apply.comp"), ATTN_TILE);
    }

    /// The spatial block constants must equal the `conv_tiled.glsl` geometry: Rust
    /// dispatches one invocation per `BLOCK_OC x BLOCK_X` block and the shader owns
    /// that block. A mismatch leaves outputs unwritten, which only the device parity
    /// fixtures would catch.
    #[test]
    fn the_spatial_tiles_match_their_shader() {
        let path =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("shaders").join("conv_tiled.glsl");
        let source = std::fs::read_to_string(&path).expect("the tiled conv include reads");
        let define = |name: &str| {
            source
                .lines()
                .find_map(|line| {
                    line.trim_start().strip_prefix("#define ").and_then(|rest| {
                        rest.strip_prefix(name).and_then(|value| {
                            value.trim().trim_end_matches('u').parse::<u32>().ok()
                        })
                    })
                })
                .unwrap_or_else(|| panic!("conv_tiled.glsl declares no {name}"))
        };
        assert_eq!(define("BLOCK_OC"), SPATIAL_BLOCK_OC);
        assert_eq!(define("BLOCK_X"), SPATIAL_BLOCK_X);
    }

    fn tile_of(shader: &str) -> u32 {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("shaders").join(shader);
        let source = std::fs::read_to_string(&path).expect("the shader reads");
        source
            .lines()
            .find_map(|line| line.trim_start().strip_prefix("#define TILE"))
            .and_then(|rest| rest.trim().trim_end_matches('u').parse().ok())
            .unwrap_or_else(|| panic!("{shader} declares no TILE"))
    }
    use super::*;

    /// A [`WeightSource`] that knows only shapes.
    ///
    /// It hands back the tensor index as the offset, which is meaningless as an
    /// address but makes the plan reproducible, and it records every shape it was
    /// asked for so a test can assert the whole ordered layer table without a
    /// `.maml`.
    pub struct Shapes {
        pub asked: std::cell::RefCell<Vec<(usize, Vec<u32>)>>,
        pub count: usize,
    }

    impl Shapes {
        pub fn new(count: usize) -> Shapes {
            Shapes { asked: std::cell::RefCell::new(Vec::new()), count }
        }
    }

    impl WeightSource for Shapes {
        fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
            self.asked.borrow_mut().push((index, dims.to_vec()));
            Ok(index as u32)
        }
        fn shaped_words(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
            self.asked.borrow_mut().push((index, dims.to_vec()));
            Ok(index as u32)
        }

        fn count(&self) -> usize {
            self.count
        }
    }

    /// A dispatch kind''s name, with `Conv`''s tiled lowerings folded back into the graph op.
    ///
    /// The op-inventory tests state what a network contains. Whether an ungrouped `1 x 1` is
    /// served by `conv.comp` or the tiled `conv_point.comp` is a lowering decision that those
    /// tests should not see, and folding it here keeps the assertions readable as counts of
    /// convolutions rather than counts of shaders. The int8 pair folds the same way.
    pub fn name_of(kind: super::Kind) -> String {
        match kind {
            super::Kind::ConvPoint => "Conv".to_string(),
            // Both staged int8 lowerings are the same graph op as the untiled one. Which shader
            // serves a `1 x 1` is a lowering decision the op-inventory tests should not see.
            // The int4 and Q2_K triples fold the same way.
            super::Kind::ConvPointInt8 | super::Kind::ConvVecInt8 => "ConvInt8".to_string(),
            super::Kind::ConvPointInt4 | super::Kind::ConvVecInt4 | super::Kind::ConvPointInt4Dot => {
                "ConvInt4".to_string()
            }
            super::Kind::ConvQ2K | super::Kind::ConvPointQ2K | super::Kind::ConvVecQ2K => {
                "ConvQ2K".to_string()
            }
            other => format!("{other:?}"),
        }
    }

    /// Assert that no op reads a region of the arena that it also writes.
    ///
    /// Every op reads and writes the same `VkBuffer`, and a convolution invocation reads
    /// many elements to write one, so a producer whose output overlapped its own input
    /// would be a data race that no barrier can fix and no test of the *output* would
    /// catch — it would just make the mask slightly wrong, differently on each driver.
    ///
    /// [`Builder::finish`] prevents it by allocating a node's output before freeing its
    /// inputs. This is the check that it worked, run against both real networks.
    pub fn assert_no_aliasing(plan: &Plan) {
        let disjoint = |a: (u32, u32), b: (u32, u32)| a.0 + a.1 <= b.0 || b.0 + b.1 <= a.0;
        for (step, op) in plan.ops.iter().enumerate() {
            let (out, reads) = match *op {
                Op::Copy { src, dst, elems } => ((dst, elems), vec![(src, elems)]),
                Op::Dispatch { kind, push, .. } => {
                    // The one table, in `Kind::arena_reads`. This assertion is the weaker of its
                    // two consumers - it only asks whether the reads overlap the write - but
                    // sharing it is what keeps `schedule`, which asks the stronger question,
                    // honest against every shipping net.
                    let reads = match kind.arena_reads(&push) {
                        Reads::Ranges(ranges) => ranges,
                        // Nothing to assert about a kind that has not been audited. It is not a
                        // failure here; `schedule` is where it costs something.
                        Reads::Unknown => continue,
                    };
                    let written = push.out_c * push.out_h * push.out_w;
                    ((push.out, written), reads)
                }
            };
            for read in reads {
                assert!(
                    disjoint(out, read),
                    "step {step} writes {}..{} and reads {}..{}",
                    out.0,
                    out.0 + out.1,
                    read.0,
                    read.0 + read.1,
                );
            }
        }
    }

    #[test]
    fn the_push_block_is_inside_the_guaranteed_limit() {
        // 128 bytes is the minimum `maxPushConstantsSize` the spec requires, so
        // staying under it means no device can reject this.
        assert!(
            std::mem::size_of::<Push>() <= 128,
            "{} bytes exceeds the guaranteed 128",
            std::mem::size_of::<Push>()
        );
    }

    #[test]
    fn the_push_block_has_no_padding() {
        // The shaders read it at fixed offsets, so a gap Rust inserted would shift
        // every field after it.
        assert_eq!(std::mem::size_of::<Push>(), 32 * 4);
        assert_eq!(std::mem::align_of::<Push>(), 4);
        // Vulkan only guarantees 128 bytes of push constants, so this is the ceiling the
        // block has to stay under however many modes get added to it.
        assert!(std::mem::size_of::<Push>() <= 128, "{}", std::mem::size_of::<Push>());
    }

    #[test]
    fn the_fused_addend_fields_default_to_opted_out() {
        // `..Push::default()` is how every non-convolution op is built. Offset 0 is the
        // first input tensor, so a derived default of 0 would fuse a live addend into
        // every one of them; the manual default must say [`NO_FUSE`] instead.
        let push = Push::default();
        assert_eq!(push.res, NO_FUSE);
        assert_eq!(push.shift, NO_FUSE);
    }

    #[test]
    fn conv_output_sizes_match_onnx() {
        // The selfie net's first layer: 256 -> 128 with asymmetric pads [0, 0, 1, 1], so
        // one row and column of padding in total.
        assert_eq!(conv_out(256, 3, 2, 1, 1), 128);
        // Its 5x5 stride-2 depthwise, pads [1,1,2,2]: 32 -> 16.
        assert_eq!(conv_out(32, 5, 2, 1, 1 + 2), 16);
        // U^2-Netp's dilated 3x3s, where pad == dilation holds the size.
        assert_eq!(conv_out(320, 3, 1, 1, 2), 320);
        assert_eq!(conv_out(20, 3, 1, 8, 16), 20);
        // 1x1, the majority of the selfie net.
        assert_eq!(conv_out(16, 1, 1, 1, 0), 16);
    }

    #[test]
    fn transposed_conv_output_size_matches_onnx() {
        // The selfie net's only ConvTranspose: 2x2 stride 2, 128 -> 256.
        assert_eq!(deconv_out(128, 2, 2, 0), 256);
    }

    #[test]
    fn the_arena_reuses_a_freed_block_rather_than_growing() {
        let mut arena = Arena::new();
        let a = arena.alloc(64);
        let b = arena.alloc(64);
        assert_eq!((a, b), (0, 64));
        arena.free(a, 64);
        // The freed block is the first fit, so this must land back at 0 and leave the
        // high-water mark alone. If it does not, neither net's arena is bounded.
        assert_eq!(arena.alloc(64), 0);
        assert_eq!(arena.high_water, 128);
    }

    #[test]
    fn the_arena_coalesces_adjacent_frees() {
        let mut arena = Arena::new();
        let a = arena.alloc(64);
        let b = arena.alloc(64);
        let c = arena.alloc(64);
        arena.free(a, 64);
        arena.free(c, 64);
        arena.free(b, 64);
        assert_eq!(arena.free, vec![(0, 192)]);
        // All three coalesced, so a request larger than any one of them fits.
        assert_eq!(arena.alloc(192), 0);
        assert_eq!(arena.high_water, 192);
    }

    #[test]
    fn every_allocation_stays_16_byte_aligned() {
        let mut arena = Arena::new();
        // 3 elements is 6 bytes: the round-up is what keeps the next tensor aligned.
        for _ in 0..8 {
            assert_eq!(arena.alloc(3) % ALIGN_ELEMS, 0);
        }
    }

    #[test]
    fn a_wrong_weight_shape_fails_the_build() {
        struct Wrong;
        impl WeightSource for Wrong {
            fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
                Err(format!("tensor {index} is not {dims:?}"))
            }
            fn shaped_words(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
                Err(format!("tensor {index} is not {dims:?}"))
            }
            fn count(&self) -> usize {
                2
            }
        }
        let mut builder = Builder::new(&Wrong);
        let input = builder.input(Shape::new(3, 8, 8));
        let out = builder.conv_same(input, 0, 4, 3, 1, Act::Relu);
        let error = builder.finish(&[out]).expect_err("bad weights");
        assert!(error.contains("tensor 0"), "{error}");
    }

    #[test]
    fn a_pass_that_leaves_tensors_unread_fails_the_build() {
        let source = Shapes::new(4);
        let mut builder = Builder::new(&source);
        let input = builder.input(Shape::new(3, 8, 8));
        let out = builder.conv_same(input, 0, 4, 3, 1, Act::Relu);
        let error = builder.finish(&[out]).expect_err("short pass");
        // Named by index, not just counted: a pass that skipped a layer in the middle
        // reads the right *number* of tensors and the wrong ones.
        assert!(error.contains("never reads tensor 2 of 4"), "{error}");
    }

    #[test]
    fn concat_lowers_to_contiguous_copies_and_no_shader() {
        let source = Shapes::new(0);
        let mut builder = Builder::new(&source);
        let a = builder.input(Shape::new(2, 2, 2));
        let b = builder.resize_to(a, 2, 2);
        let joined = builder.concat(&[a, b]);
        let plan = builder.finish(&[joined]).expect("builds");
        let copies: Vec<&Op> = plan.ops.iter().filter(|o| matches!(o, Op::Copy { .. })).collect();
        assert_eq!(copies.len(), 2);
        // The second part lands exactly one part's worth of elements after the first:
        // in NCHW a channel run is contiguous, which is the whole reason concat needs
        // no shader.
        match (copies.first(), copies.get(1)) {
            (Some(Op::Copy { dst: first, elems, .. }), Some(Op::Copy { dst: second, .. })) => {
                assert_eq!(*second, first + elems);
            }
            other => panic!("expected two copies, got {other:?}"),
        }
    }

    /// A residual add over a convolution's output folds into its store.
    ///
    /// The ConvNeXt shape: `narrowed = conv(widened)`, then `add(x, narrowed)`. One
    /// convolution dispatch and no add, with the sum stored where the add's output
    /// would have gone.
    #[test]
    fn a_single_consumer_residual_folds_into_the_producing_store() {
        let source = Shapes::new(5);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let widened = builder.conv_same(x, 0, 8, 1, 1, Act::Relu);
        let narrowed = builder.conv_same(widened, 2, 4, 1, 1, Act::None);
        let out = builder.add(x, narrowed);
        // Tensor 4 is the file's spare: `finish` refuses an unread tensor, and this
        // pass legitimately owns only four of the five.
        builder.host_tensor(4, &[1]);
        let plan = builder.finish(&[out]).expect("builds");
        assert!(
            plan.ops.iter().all(|op| !matches!(op, Op::Dispatch { kind: Kind::Add, .. })),
            "the residual add should have folded: {:?}",
            plan.ops
        );
        let fused = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { push, .. } if push.res != NO_FUSE => Some(push),
                _ => None,
            })
            .collect::<Vec<_>>();
        assert_eq!(fused.len(), 1, "exactly one fused store: {fused:?}");
        // The fused convolution writes the add's own output tensor, and reads the skip
        // side alongside its input.
        assert_eq!(fused[0].out, plan.outputs[0].at);
        // `x` is the first input, pinned at offset 0 — the case a zero sentinel could
        // not distinguish from "no residual".
        assert_eq!(fused[0].res, plan.inputs[0].at);
    }

    /// A timestep-style per-channel shift folds into the same store as the residual.
    ///
    /// `add_channel(conv_out, shift)` after the residual above: the convolution stores
    /// `activate(acc + bias) + residual + shift[channel]` in one dispatch, and neither
    /// binary survives.
    #[test]
    fn a_timestep_shift_folds_into_the_same_store() {
        let source = Shapes::new(7);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let shift_in = builder.input(Shape::new(4, 1, 1));
        let widened = builder.conv_same(x, 0, 8, 1, 1, Act::Relu);
        let narrowed = builder.conv_same(widened, 2, 4, 1, 1, Act::None);
        let residual = builder.add(x, narrowed);
        let out = builder.add_channel(residual, shift_in);
        // Tensors 4 and 5 are the file's spares; see the residual test above.
        builder.host_tensor(4, &[1]);
        builder.host_tensor(5, &[1]);
        builder.host_tensor(6, &[1]);
        let plan = builder.finish(&[out]).expect("builds");
        assert!(
            plan.ops.iter().all(|op| !matches!(
                op,
                Op::Dispatch { kind: Kind::Add | Kind::AddBroadcast, .. }
            )),
            "both binaries should have folded: {:?}",
            plan.ops
        );
        let fused = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { push, .. } if push.shift != NO_FUSE => Some(push),
                _ => None,
            })
            .collect::<Vec<_>>();
        assert_eq!(fused.len(), 1, "exactly one shifted store: {fused:?}");
        assert_eq!(fused[0].out, plan.outputs[0].at);
        assert_eq!(fused[0].res, plan.inputs[0].at);
        assert_eq!(fused[0].shift, plan.inputs[1].at);
        // The shift tensor is one value per channel of the output.
        assert_eq!(plan.inputs[1].shape, Shape::new(4, 1, 1));
        assert_eq!(fused[0].out_c, 4);
    }

    include!("mod_part11b.rs");
}