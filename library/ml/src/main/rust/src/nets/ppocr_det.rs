//! PP-OCRv5 mobile text detection: a DBNet probability map over the whole image.
//!
//! # Shape of the network
//!
//! A PP-HGNetV2 backbone, a four-level feature pyramid, and a head that fuses all four
//! levels and upsamples back to full resolution:
//!
//! * **Backbone**, 38 convolutions. Depthwise/pointwise pairs with `HardSwish`
//!   throughout, five stride-2 reductions to 1/32, 5x5 depthwise kernels from 1/16 down,
//!   and two squeeze-excite blocks near the bottom. It branches at 1/4, 1/8, 1/16 and
//!   1/32 with 48, 96, 192 and 384 channels.
//! * **Neck**: four 1x1 laterals (to 12, 18, 42 and 360 channels — not a uniform width),
//!   each projected to 96 and put through a *residual* squeeze-excite, then a top-down
//!   path of three nearest upsamples and adds.
//! * **Head**: a 3x3 convolution to 24 channels per level, each with its own residual
//!   squeeze-excite; the three coarse levels are upsampled to 1/4 and concatenated with
//!   the fine one; then a 3x3 convolution and two stride-2 transposed convolutions take
//!   24 channels at 1/4 back to **one channel at full resolution**, through a sigmoid.
//!
//! So the output is the same size as the input, which is what DBNet's post-processing
//! wants: a per-pixel text probability to threshold and trace.
//!
//! # This one is folded, not transcribed verbatim
//!
//! The official export is `paddle2onnx`, and it spells every convolution as a chain of
//! constant `Add`/`Mul`/`HardSigmoid` nodes threaded through `Identity`: 62 convolutions
//! become 117 `Add`s and 86 `Mul`s. `scripts/ml/ppocr_fold.py` folds all of that ahead of
//! time — see `library/ocr/src/main/assets/README.md`. What is transcribed below is the
//! *folded* graph, which is why:
//!
//! * every convolution here carries a bias even though none does in the ONNX,
//! * `HardSigmoid` appears as [`Act::Clip01`], its alpha and beta having gone into the
//!   weight and bias,
//! * and 23 of the 24 "learnable affine blocks" have vanished into the convolutions they
//!   feed. The **one** that survives is at [`Builder::affine`] below, because its result
//!   feeds a squeeze-excite branch as well as a convolution.
//!
//! The folded inventory — 62 convolutions, 2 transposed, 10 average pools, 10 channel
//! multiplies, 11 adds, 6 nearest resizes, 1 concatenation, 1 affine — matches ncnn's
//! independent conversion of the same model layer for layer.
//!
//! # Shapes
//!
//! Dynamically shaped, like [`super::scrfd`]: the long side goes to 960 and both extents
//! must be multiples of 32, because five stride-2 reductions and then three upsamples
//! back have to land on the same extents for the pyramid's adds to line up.

use super::ppocr_det_extra::AFFINES;
use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// The long side the detector runs at, from the export's own `inference.yml`
/// (`DetResizeForTest: resize_long: 960`).
pub const LONG_SIDE: u32 = 960;
/// Both extents must be a multiple of this.
pub const EXTENT_MULTIPLE: u32 = 32;
/// Tensors in the file: 64 folded layers, weight and bias each.
pub const TENSORS: usize = 128;

/// Channels the neck projects every pyramid level to.
const NECK: u32 = 96;
/// Channels each head branch produces, four of which concatenate back to [`NECK`].
const HEAD: u32 = 24;

/// Hands out `.maml` tensor indices in the order the layers appear. Every folded layer is
/// a convolution with a weight and a bias, so this steps by two throughout.
struct Layers {
    next: usize,
}

impl Layers {
    fn take(&mut self) -> usize {
        let index = self.next;
        self.next += 2;
        index
    }
}

/// A 1x1 convolution.
fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv(x, l.take(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// A `k x k` depthwise convolution, padded to hold the extent at stride 1.
fn depthwise(b: &mut Builder, l: &mut Layers, x: Id, kernel: u32, stride: u32, act: Act) -> Id {
    let channels = b.shape(x).c;
    let pad = kernel / 2;
    b.conv(
        x,
        l.take(),
        channels,
        (kernel, kernel),
        (stride, stride),
        (1, 1),
        (pad, pad, pad, pad),
        channels,
        act,
    )
}

/// A 3x3 convolution.
fn spatial(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv(x, l.take(), out, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 1, act)
}

/// The gate half of a squeeze-excite: pool, squeeze through a ReLU, expand through a
/// clamp. Returns the `C x 1 x 1` gate rather than applying it, because the two callers
/// below use it differently.
fn gate(b: &mut Builder, l: &mut Layers, x: Id, reduce: u32) -> Id {
    let channels = b.shape(x).c;
    let pooled = b.global_avg_pool(x);
    let squeezed = point(b, l, pooled, reduce, Act::Relu);
    point(b, l, squeezed, channels, Act::Clip01)
}

/// `x * gate(x)` — the plain squeeze-excite, used twice in the backbone.
fn squeeze_excite(b: &mut Builder, l: &mut Layers, x: Id, reduce: u32) -> Id {
    let g = gate(b, l, x, reduce);
    b.mul_channel(x, g)
}

/// `x + x * gate(x)` — the *residual* squeeze-excite the neck and head use, eight times.
///
/// Note the difference from [`squeeze_excite`]: the gated tensor is added back rather
/// than replacing the input, so a fully-closed gate is the identity here and zero there.
fn residual_excite(b: &mut Builder, l: &mut Layers, x: Id, reduce: u32) -> Id {
    let g = gate(b, l, x, reduce);
    let gated = b.mul_channel(x, g);
    b.add(x, gated)
}

/// Compile the forward pass for an input of `height` x `width`.
pub fn build(weights: &dyn WeightSource, height: u32, width: u32) -> Result<Plan, String> {
    compile(weights, height, width, false)
}

fn compile(
    weights: &dyn WeightSource,
    height: u32,
    width: u32,
    backbone: bool,
) -> Result<Plan, String> {
    if height == 0 || width == 0 {
        return Err(format!("a {width}x{height} input"));
    }
    if height % (EXTENT_MULTIPLE) != 0 || width % (EXTENT_MULTIPLE) != 0 {
        return Err(format!(
            "a {width}x{height} input: both extents must be a multiple of \
             {EXTENT_MULTIPLE}, or the pyramid's four levels are not exactly 2x apart"
        ));
    }

    let mut b = Builder::new(weights);
    b.spatial_tiling();
    let mut layers = Layers { next: 0 };
    let l = &mut layers;
    let input = b.input(Shape::new(3, height, width));

    // Backbone. Stem to 1/2, then depthwise/pointwise stages; the branch is always the
    // last pointwise before a stride-2 depthwise, taken *after* that stage's affine
    // because the export's lateral reads the affined tensor rather than the convolution's
    // own output.
    let x = spatial_strided(&mut b, l, input, 16, 2, Act::None);
    let x = depthwise(&mut b, l, x, 3, 1, Act::HardSwish);
    let x = point(&mut b, l, x, 32, Act::HardSwish);
    let x = affine(&mut b, x, 0);

    let x = depthwise(&mut b, l, x, 3, 2, Act::None);
    let x = point(&mut b, l, x, 48, Act::HardSwish);
    let x = affine(&mut b, x, 1);
    let x = depthwise(&mut b, l, x, 3, 1, Act::HardSwish);
    // 1/4, 48 channels.
    let x = point(&mut b, l, x, 48, Act::HardSwish);
    let c2 = affine(&mut b, x, 2);

    let x = depthwise(&mut b, l, c2, 3, 2, Act::None);
    let x = point(&mut b, l, x, 96, Act::HardSwish);
    let x = affine(&mut b, x, 3);
    let x = depthwise(&mut b, l, x, 3, 1, Act::HardSwish);
    // 1/8, 96 channels.
    let x = point(&mut b, l, x, 96, Act::HardSwish);
    let c3 = affine(&mut b, x, 4);

    let mut x = depthwise(&mut b, l, c3, 3, 2, Act::None);
    // Four 5x5 depthwise/pointwise pairs at 192 channels.
    for index in 0..4 {
        x = point(&mut b, l, x, 192, Act::HardSwish);
        x = affine(&mut b, x, 5 + index);
        x = depthwise(&mut b, l, x, 5, 1, Act::HardSwish);
    }
    // 1/16, 192 channels. The loop above ends on a depthwise, so the branch is the
    // pointwise that closes the stage.
    let x = point(&mut b, l, x, 192, Act::HardSwish);
    let c4 = affine(&mut b, x, 9);

    let x = depthwise(&mut b, l, c4, 5, 2, Act::None);
    let x = squeeze_excite(&mut b, l, x, 48);
    let x = point(&mut b, l, x, 384, Act::HardSwish);
    let x = affine(&mut b, x, 10);
    let x = depthwise(&mut b, l, x, 5, 1, Act::HardSwish);
    let x = affine(&mut b, x, 11);
    let mut x = squeeze_excite(&mut b, l, x, 96);
    for index in 0..2 {
        x = point(&mut b, l, x, 384, Act::HardSwish);
        x = affine(&mut b, x, 12 + index);
        x = depthwise(&mut b, l, x, 5, 1, Act::HardSwish);
    }
    // 1/32, 384 channels. The only stage whose branch has no affine after it — the
    // export's coarsest lateral reads this convolution's output directly.
    let c5 = point(&mut b, l, x, 384, Act::HardSwish);

    // Neck laterals, in the export's order: fine to coarse, and each to its own width.
    let lateral2 = point(&mut b, l, c2, 12, Act::None);
    let lateral3 = point(&mut b, l, c3, 18, Act::None);
    let lateral4 = point(&mut b, l, c4, 42, Act::None);
    let lateral5 = point(&mut b, l, c5, 360, Act::None);

    // Then coarse to fine, each projected to 96 and residually gated.
    let n5 = project_excite(&mut b, l, lateral5);
    let n4 = project_excite(&mut b, l, lateral4);
    let n3 = project_excite(&mut b, l, lateral3);
    let n2 = project_excite(&mut b, l, lateral2);

    // Top-down: nearest, not bilinear — the export is `mode=nearest`.
    let up5 = b.resize_nearest_like(n5, n4);
    let m4 = b.add(n4, up5);
    let up4 = b.resize_nearest_like(m4, n3);
    let m3 = b.add(n3, up4);
    let up3 = b.resize_nearest_like(m3, n2);
    let m2 = b.add(n2, up3);

    // Head: one 3x3 per level, each residually gated. Note the coarsest reads `n5`
    // directly — the top-down path starts *below* it, so there is no `m5`.
    let h5 = head_branch(&mut b, l, n5);
    let h4 = head_branch(&mut b, l, m4);
    let h3 = head_branch(&mut b, l, m3);
    let h2 = head_branch(&mut b, l, m2);

    // Fuse everything at 1/4. The three coarse levels upsample by 8x, 4x and 2x.
    let f5 = b.resize_nearest_like(h5, h2);
    let f4 = b.resize_nearest_like(h4, h2);
    let f3 = b.resize_nearest_like(h3, h2);
    let fused = b.concat(&[f5, f4, f3, h2]);

    let x = spatial(&mut b, l, fused, HEAD, Act::Relu);
    // Two 2x2 stride-2 transposed convolutions: 1/4 -> 1/2 -> full resolution, and down
    // to the single probability channel DBNet thresholds.
    let x = b.conv_transpose(x, l.take(), HEAD, (2, 2), (2, 2), (0, 0, 0, 0), Act::Relu);
    let probability =
        b.conv_transpose(x, l.take(), 1, (2, 2), (2, 2), (0, 0, 0, 0), Act::Sigmoid);
    if backbone {
        return b.finish(&[probability, c5]);
    }
    b.finish(&[probability])
}

/// [`build`], plus the backbone's output as a second binding.
///
/// Only for `scripts/ml/onnx_parity.py`: detection's own output is a text probability that
/// saturates to zero on a synthetic input, so comparing it against the export compares
/// zeros. `c5` is where all fourteen of the surviving [`AFFINES`] are, which is the part a
/// numerical check needs to see.
pub fn build_with_backbone(
    weights: &dyn WeightSource,
    height: u32,
    width: u32,
) -> Result<Plan, String> {
    compile(weights, height, width, true)
}

/// A 3x3 convolution at `stride`, for the stem.
fn spatial_strided(
    b: &mut Builder,
    l: &mut Layers,
    x: Id,
    out: u32,
    stride: u32,
    act: Act,
) -> Id {
    b.conv(x, l.take(), out, (3, 3), (stride, stride), (1, 1), (1, 1, 1, 1), 1, act)
}

/// One of the [`AFFINES`], by position in the folded graph.
fn affine(b: &mut Builder, x: Id, which: usize) -> Id {
    let (scale, shift) = AFFINES[which];
    b.affine(x, scale, shift)
}

/// A neck level: 1x1 to [`NECK`] channels, then a residual squeeze-excite at 24.
fn project_excite(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let projected = point(b, l, x, NECK, Act::None);
    residual_excite(b, l, projected, 24)
}

/// A head level: 3x3 to [`HEAD`] channels, then a residual squeeze-excite at 6.
fn head_branch(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let narrowed = spatial(b, l, x, HEAD, Act::None);
    residual_excite(b, l, narrowed, 6)
}

#[cfg(test)] #[path = "ppocr_det_tests.rs"] mod ppocr_det_tests;

#[cfg(test)]
mod tests {
    use super::super::tests::Shapes;
    use super::super::{Kind, Op};
    use super::*;

    fn plan() -> Plan {
        let source = Shapes::new(TENSORS);
        build(&source, 960, 960).expect("ppocr_det builds at 960x960")
    }

    /// Whether two kinds are the same graph operation, folding `Conv`''s tiled lowering in.
    fn same_op(found: Kind, wanted: Kind) -> bool {
        let fold = |k: Kind| if matches!(k, Kind::ConvPoint) { Kind::Conv } else { k };
        fold(found) == fold(wanted)
    }

    fn dispatches(plan: &Plan, kind: Kind) -> usize {
        plan.ops
            .iter()
            // `ConvPoint` is a lowering of `Conv`, not a different graph op: an ungrouped 1x1
            // goes to the tiled shader. These tests are about what the network contains, so the
            // two are counted as one.
            .filter(|op| matches!(op, Op::Dispatch { kind: k, .. } if same_op(*k, kind)))
            .count()
    }

    #[test]
    fn the_op_inventory_matches_the_folded_graph() {
        // These are the counts `scripts/ml/ppocr_fold.py` prints, and they also match
        // ncnn's independent conversion of the same model.
        let plan = plan();
        assert_eq!(dispatches(&plan, Kind::Conv), 62);
        assert_eq!(dispatches(&plan, Kind::ConvTranspose), 2);
        assert_eq!(dispatches(&plan, Kind::GlobalAvgPool), 10);
        assert_eq!(dispatches(&plan, Kind::MulBroadcast), 10);
        assert_eq!(dispatches(&plan, Kind::Add), 11);
        assert_eq!(dispatches(&plan, Kind::ResizeNearest), 6);
        assert_eq!(dispatches(&plan, Kind::Affine), 14);
        // The export's six `Resize` nodes are all nearest, so the bilinear pipeline must
        // not appear anywhere.
        assert_eq!(dispatches(&plan, Kind::Resize), 0);
        assert_eq!(dispatches(&plan, Kind::MaxPool), 0);
        // One concatenation, of four 24-channel head levels.
        assert_eq!(plan.ops.iter().filter(|o| matches!(o, Op::Copy { .. })).count(), 4);
    }

    #[test]
    fn the_pass_consumes_the_whole_tensor_table() {
        let source = Shapes::new(TENSORS);
        assert!(build(&source, 960, 960).is_ok());
        assert_eq!(source.asked.borrow().len(), TENSORS);
    }

    #[test]
    fn the_output_is_one_channel_at_the_input_resolution() {
        // DBNet thresholds a per-pixel probability, so the two transposed convolutions
        // have to take 1/4 all the way back to 1:1. A net that stopped at 1/2 would still
        // build and every box would come out at half scale.
        let plan = plan();
        assert_eq!(plan.input().expect("one input").shape, Shape::new(3, 960, 960));
        assert_eq!(plan.output().expect("one output").shape, Shape::new(1, 960, 960));
    }

    #[test]
    fn the_backbone_reduces_five_times_and_the_head_restores_twice() {
        let plan = plan();
        let strided: Vec<(u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Conv, push, .. } if push.stride_h == 2 => {
                    Some((push.in_c, push.in_h))
                }
                _ => None,
            })
            .collect();
        assert_eq!(
            strided,
            vec![(3, 960), (32, 480), (48, 240), (96, 120), (192, 60)]
        );
        // 1/32 is 30 rows at this size, and the pyramid's coarsest level sits there.
        let upsamples: Vec<(u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::ResizeNearest, push, .. } => {
                    Some((push.in_h, push.out_h))
                }
                _ => None,
            })
            .collect();
        assert_eq!(
            upsamples,
            vec![
                // Top-down, each exactly 2x.
                (30, 60),
                (60, 120),
                (120, 240),
                // The head's fuse, 8x / 4x / 2x onto 1/4.
                (30, 240),
                (60, 240),
                (120, 240),
            ]
        );
    }

    #[test]
    fn the_laterals_read_the_four_branch_widths() {
        // 48, 96, 192 and 384 appear as lateral *inputs* only at the four branch points,
        // so this pins that each was taken at the right depth. Taking one a pair early
        // gives a lateral the wrong input channel count, which the real asset's shape
        // check would catch but a stub source would not.
        let source = Shapes::new(TENSORS);
        let _ = build(&source, 960, 960);
        let asked = source.asked.borrow();
        let shape_at = |index: usize| -> Vec<u32> {
            asked
                .iter()
                .find(|(i, _)| *i == index)
                .map(|(_, dims)| dims.clone())
                .unwrap_or_default()
        };
        assert_eq!(shape_at(66), vec![12, 48, 1, 1]);
        assert_eq!(shape_at(68), vec![18, 96, 1, 1]);
        assert_eq!(shape_at(70), vec![42, 192, 1, 1]);
        assert_eq!(shape_at(72), vec![360, 384, 1, 1]);
    }

    #[test]
    fn the_layer_table_matches_the_folded_export() {
        let source = Shapes::new(TENSORS);
        let _ = build(&source, 960, 960);
        let asked = source.asked.borrow();
        let shape_at = |index: usize| -> Vec<u32> {
            asked
                .iter()
                .find(|(i, _)| *i == index)
                .map(|(_, dims)| dims.clone())
                .unwrap_or_default()
        };
        // Tensor 0, the stem.
        assert_eq!(shape_at(0), vec![16, 3, 3, 3]);
        // Tensor 26, where the depthwise kernels widen from 3x3 to 5x5.
        assert_eq!(shape_at(26), vec![192, 1, 5, 5]);
        // Tensors 44/46, the first squeeze-excite: 192 -> 48 -> 192.
        assert_eq!(shape_at(44), vec![48, 192, 1, 1]);
        assert_eq!(shape_at(46), vec![192, 48, 1, 1]);
        // Tensor 122, the 3x3 over the concatenated head. 4 x 24 = 96 channels in.
        assert_eq!(shape_at(122), vec![24, 96, 3, 3]);
        // The two transposed convolutions, `[in, out/group, k, k]` — the layout inversion.
        assert_eq!(shape_at(124), vec![24, 24, 2, 2]);
        assert_eq!(shape_at(126), vec![24, 1, 2, 2]);
    }

    #[test]
    fn the_residual_and_plain_squeeze_excites_are_not_confused() {
        // Ten gates in total: two plain ones in the backbone and eight residual ones in
        // the neck and head. The residual kind is the one followed by an `Add`, and using
        // the wrong one makes a closed gate the identity instead of zero.
        let plan = plan();
        let residual = plan
            .ops
            .iter()
            .enumerate()
            .filter(|(_, op)| matches!(op, Op::Dispatch { kind: Kind::MulBroadcast, .. }))
            .filter(|(index, _)| {
                matches!(
                    plan.ops.get(index + 1),
                    Some(Op::Dispatch { kind: Kind::Add, .. })
                )
            })
            .count();
        assert_eq!(residual, 8);
    }

    #[test]
    fn a_non_square_input_keeps_its_aspect_ratio_through_the_pyramid() {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, 512, 960).expect("builds at 960x512");
        assert_eq!(plan.output().expect("one output").shape, Shape::new(1, 512, 960));
    }

    #[test]
    fn an_extent_that_is_not_a_multiple_of_32_is_refused() {
        let source = Shapes::new(TENSORS);
        let error = build(&source, 100, 960).expect_err("not a multiple of 32");
        assert!(error.contains("multiple of 32"), "{error}");
    }

    #[test]
    fn no_op_reads_a_region_it_also_writes() {
        super::super::tests::assert_no_aliasing(&plan());
    }
}
