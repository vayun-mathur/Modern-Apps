//! SCRFD 500M with keypoints, the face detector `:photos` finds faces with.
//!
//! # Shape of the network
//!
//! Three parts, and the middle one is where the complexity is:
//!
//! * A **backbone** of 29 convolutions — a stem, then 14 depthwise/pointwise pairs —
//!   which is a plain sequential stack with no residuals at all. It branches three
//!   times, at strides 8, 16 and 32, with 72, 152 and 288 channels.
//! * A **PAFPN neck**: three 1x1 laterals reduce all three branches to 16 channels, a
//!   top-down path upsamples and adds, a bottom-up path downsamples and adds, and seven
//!   3x3 convolutions sit along it. None of the neck's convolutions has an activation.
//! * Three identical **heads**, one per stride, each four convolutions deep and ending
//!   in three outputs: a 2-channel score map, an 8-channel box map and a 20-channel
//!   keypoint map. Two anchors per cell, so those are 1, 4 and 10 values per anchor.
//!
//! Nine outputs in total, which is what [`super::Plan::outputs`] being a list is for.
//!
//! # Dynamically shaped, unlike every other net here
//!
//! The export's input is `[1, 3, ?, ?]`: SCRFD runs at 640 on the long side with the
//! short side padded up to a multiple of 32, so the shape depends on the photo's aspect
//! ratio. [`build`] therefore takes the size, and a plan is compiled per shape rather
//! than once.
//!
//! Multiples of 32 are required rather than merely conventional. Five stride-2
//! convolutions take the input to 1/32, and the neck's two upsamples and two
//! downsamples assume the three branch extents are exactly 2x apart. At a size that is
//! not a multiple of 32 the floor-divisions disagree by a row, an `Add` in the neck gets
//! two different shapes and [`build`] fails — which is the right outcome, but it is
//! cheaper to say so up front.
//!
//! # What is deliberately left to the host
//!
//! The ONNX ends each head with `Transpose` + `Reshape`, and `Sigmoid` on the scores.
//! The transpose and reshape are a relabelling — they flatten `[1, c, h, w]` to
//! `[h * w * anchors, c / anchors]` — and NCHW indexing does that for free, so the plan
//! stops at the convolution outputs and the anchor decode reads them directly. The
//! sigmoid **is** kept, fused into the score convolution, because it is arithmetic:
//! applying it before a pure permutation rather than after is identical.
//!
//! So anchor decode, non-maximum suppression and undoing the letterbox all happen in
//! Rust on the maps this returns. See `post::nms`.
//!
//! Preprocessing is mean 127.5 and norm **1/128** — note the divisor, which is *not*
//! [`super::mobilefacenet`]'s 1/127.5. Sharing one constant between them would be wrong
//! in one of the two.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// The long side the detector runs at.
pub const LONG_SIDE: u32 = 640;
/// Both extents must be a multiple of this. See the note on shapes above.
pub const EXTENT_MULTIPLE: u32 = 32;
/// The three strides the heads predict at.
pub const STRIDES: [u32; 3] = [8, 16, 32];
/// Anchors per cell, at every stride.
pub const ANCHORS: u32 = 2;
/// Tensors in the file: 60 convolutions, weight and bias each.
pub const TENSORS: usize = 120;

/// Channels the neck reduces every branch to.
const NECK: u32 = 16;
/// Channels inside each head.
const HEAD: u32 = 64;

/// Hands out `.maml` tensor indices in the order the layers appear. Every layer here is
/// a convolution, so this steps by two throughout — unlike
/// [`super::mobilefacenet`]'s, which has `PRelu` slopes to interleave.
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

/// The backbone's 14 depthwise/pointwise pairs, as `(stride, output channels)`.
///
/// Read off `maml_convert.py --print-layers`. The stem convolution before them is
/// written out at its call site because it is the only dense one.
const TRUNK: [(u32, u32); 14] = [
    (1, 16),
    (2, 40),
    (1, 40),
    (2, 72),
    (1, 72),
    (1, 72), // -> stride 8
    (2, 152),
    (1, 152), // -> stride 16
    (2, 288),
    (1, 288),
    (1, 288),
    (1, 288),
    (1, 288),
    (1, 288), // -> stride 32
];

/// Indices into [`TRUNK`] whose pointwise output the neck reads.
const BRANCHES: [usize; 3] = [5, 7, 13];

/// A 3x3 depthwise convolution, `pads = [1, 1, 1, 1]`, with a fused ReLU.
fn depthwise(b: &mut Builder, l: &mut Layers, x: Id, stride: u32) -> Id {
    let channels = b.shape(x).c;
    b.conv(
        x,
        l.take(),
        channels,
        (3, 3),
        (stride, stride),
        (1, 1),
        (1, 1, 1, 1),
        channels,
        Act::Relu,
    )
}

/// A 1x1 convolution.
fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv(x, l.take(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// A 3x3 convolution at `stride`, `pads = [1, 1, 1, 1]`.
fn spatial(b: &mut Builder, l: &mut Layers, x: Id, out: u32, stride: u32, act: Act) -> Id {
    b.conv(
        x,
        l.take(),
        out,
        (3, 3),
        (stride, stride),
        (1, 1),
        (1, 1, 1, 1),
        1,
        act,
    )
}

/// One head: four convolutions, then the score, box and keypoint maps.
///
/// The score map fuses a sigmoid; the other two are raw, because a box is a distance and
/// a keypoint an offset. Returns them in that order.
fn head(b: &mut Builder, l: &mut Layers, x: Id) -> (Id, Id, Id) {
    let x = depthwise(b, l, x, 1);
    let x = point(b, l, x, HEAD, Act::Relu);
    let x = depthwise(b, l, x, 1);
    let shared = point(b, l, x, HEAD, Act::Relu);

    let score = spatial(b, l, shared, ANCHORS, 1, Act::Sigmoid);
    let bbox = spatial(b, l, shared, ANCHORS * 4, 1, Act::None);
    let keypoints = spatial(b, l, shared, ANCHORS * 10, 1, Act::None);
    (score, bbox, keypoints)
}

/// Compile the forward pass for an input of `height` x `width`.
///
/// Both must be multiples of [`EXTENT_MULTIPLE`]; see the module docs for why that is a
/// requirement and not a convention.
pub fn build(weights: &dyn WeightSource, height: u32, width: u32) -> Result<Plan, String> {
    if height == 0 || width == 0 {
        return Err(format!("a {width}x{height} input"));
    }
    if height % (EXTENT_MULTIPLE) != 0 || width % (EXTENT_MULTIPLE) != 0 {
        return Err(format!(
            "a {width}x{height} input: both extents must be a multiple of \
             {EXTENT_MULTIPLE}, or the neck's three branches are not exactly 2x apart"
        ));
    }

    let mut b = Builder::new(weights);
    b.spatial_tiling();
    let mut layers = Layers { next: 0 };
    let l = &mut layers;
    let input = b.input(Shape::new(3, height, width));

    // Backbone. The stem is the only dense convolution; everything after it is a
    // depthwise/pointwise pair, and there are no residual connections anywhere.
    let mut x = spatial(&mut b, l, input, 16, 2, Act::Relu);
    let mut branches = Vec::with_capacity(BRANCHES.len());
    for (pair, &(stride, out)) in TRUNK.iter().enumerate() {
        x = depthwise(&mut b, l, x, stride);
        x = point(&mut b, l, x, out, Act::Relu);
        if BRANCHES.contains(&pair) {
            branches.push(x);
        }
    }
    let [c3, c4, c5] = match branches.as_slice() {
        [a, b, c] => [*a, *b, *c],
        other => return Err(format!("the backbone branched {} times, not 3", other.len())),
    };

    // Neck, in the export's node order — which is also ascending tensor order, so the
    // counter above stays a counter. The two `Add`s of the bottom-up path have to be
    // interleaved between the downsamples that feed them.
    //
    // Laterals: everything down to 16 channels.
    let p3 = point(&mut b, l, c3, NECK, Act::None);
    let p4 = point(&mut b, l, c4, NECK, Act::None);
    let p5 = point(&mut b, l, c5, NECK, Act::None);

    // Top-down. `resize_nearest_like`, not `resize_like`: the export is `mode=nearest`,
    // and the bilinear kernel would blur every lateral these are added to.
    let up5 = b.resize_nearest_like(p5, p4);
    let merged4 = b.add(p4, up5);
    let up4 = b.resize_nearest_like(merged4, p3);
    let merged3 = b.add(p3, up4);

    let fpn3 = spatial(&mut b, l, merged3, NECK, 1, Act::None);
    let fpn4 = spatial(&mut b, l, merged4, NECK, 1, Act::None);
    let fpn5 = spatial(&mut b, l, p5, NECK, 1, Act::None);

    // Bottom-up: stride 8 back down onto stride 16, then that onto stride 32.
    let down3 = spatial(&mut b, l, fpn3, NECK, 2, Act::None);
    let pan4 = b.add(fpn4, down3);
    let down4 = spatial(&mut b, l, pan4, NECK, 2, Act::None);
    let pan5 = b.add(fpn5, down4);

    let out4 = spatial(&mut b, l, pan4, NECK, 1, Act::None);
    let out5 = spatial(&mut b, l, pan5, NECK, 1, Act::None);

    // Heads. Note the stride-8 head reads `fpn3` directly: the export has no
    // `pafpn_conv` on that level, only on the two the bottom-up path touched.
    let mut outputs = Vec::with_capacity(9);
    for level in [fpn3, out4, out5] {
        let (score, bbox, keypoints) = head(&mut b, l, level);
        outputs.push(score);
        outputs.push(bbox);
        outputs.push(keypoints);
    }
    // Grouped by stride, three at a time — so the decode is `outputs.chunks_exact(3)`
    // over `STRIDES`. The ONNX declares its nine outputs grouped the other way, by kind
    // then stride; the order here is the one that makes the post-processing loop
    // obviously right, and it is the only place the two conventions meet.
    b.finish(&outputs)
}

#[cfg(test)]
mod tests {
    use super::super::tests::Shapes;
    use super::super::{Kind, Op};
    use super::*;

    fn plan() -> Plan {
        let source = Shapes::new(TENSORS);
        build(&source, 640, 640).expect("scrfd builds at 640x640")
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
    fn the_op_inventory_matches_the_onnx() {
        // The counts `scripts/ml/maml_convert.py` asserts against the ONNX itself.
        let plan = plan();
        assert_eq!(dispatches(&plan, Kind::Conv), 60);
        assert_eq!(dispatches(&plan, Kind::Add), 4);
        assert_eq!(dispatches(&plan, Kind::ResizeNearest), 2);
        // The export's two `Resize` nodes are both nearest, so the bilinear pipeline
        // must not appear. This is the assertion that catches `resize_like` being used
        // by habit.
        assert_eq!(dispatches(&plan, Kind::Resize), 0);
        assert_eq!(dispatches(&plan, Kind::ConvTranspose), 0);
        assert_eq!(dispatches(&plan, Kind::MaxPool), 0);
        assert_eq!(dispatches(&plan, Kind::GlobalAvgPool), 0);
        assert_eq!(dispatches(&plan, Kind::MulBroadcast), 0);
        assert_eq!(plan.ops.iter().filter(|o| matches!(o, Op::Copy { .. })).count(), 0);
    }

    #[test]
    fn the_pass_consumes_the_whole_tensor_table() {
        let source = Shapes::new(TENSORS);
        assert!(build(&source, 640, 640).is_ok());
        assert_eq!(source.asked.borrow().len(), TENSORS);
    }

    #[test]
    fn there_are_nine_outputs_grouped_by_stride() {
        // Two anchors per cell: 2 score channels, 8 box channels, 20 keypoint channels,
        // at 1/8, 1/16 and 1/32 of a 640x640 input.
        let plan = plan();
        assert_eq!(plan.outputs.len(), 9);
        let shapes: Vec<Shape> = plan.outputs.iter().map(|b| b.shape).collect();
        assert_eq!(
            shapes,
            vec![
                Shape::new(2, 80, 80),
                Shape::new(8, 80, 80),
                Shape::new(20, 80, 80),
                Shape::new(2, 40, 40),
                Shape::new(8, 40, 40),
                Shape::new(20, 40, 40),
                Shape::new(2, 20, 20),
                Shape::new(8, 20, 20),
                Shape::new(20, 20, 20),
            ]
        );
    }

    #[test]
    fn the_anchor_count_matches_the_onnx_output_lengths() {
        // The export declares its score maps as 12800, 3200 and 800 rows. Those are
        // `h * w * anchors` at each stride, and they are what pin `ANCHORS` at 2 rather
        // than the channel counts, which 1 anchor of 2 classes would also explain.
        let plan = plan();
        let rows: Vec<u32> = plan
            .outputs
            .chunks_exact(3)
            .filter_map(|group| group.first())
            .map(|score| score.shape.h * score.shape.w * ANCHORS)
            .collect();
        assert_eq!(rows, vec![12800, 3200, 800]);
    }

    #[test]
    fn only_the_score_maps_are_activated() {
        // The three sigmoids in the export are all on scores. A sigmoid on a box map
        // would clamp every distance into `0..1` and shrink every detection to a point.
        let plan = plan();
        let sigmoids = plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { push, .. } if push.act == Act::Sigmoid.code()))
            .count();
        assert_eq!(sigmoids, 3);
        for (group, stride) in plan.outputs.chunks_exact(3).zip(STRIDES) {
            match group {
                [score, bbox, keypoints] => {
                    assert_eq!(score.shape.c, ANCHORS, "stride {stride} score");
                    assert_eq!(bbox.shape.c, ANCHORS * 4, "stride {stride} box");
                    assert_eq!(keypoints.shape.c, ANCHORS * 10, "stride {stride} keypoints");
                }
                other => panic!("stride {stride}: {other:?}"),
            }
        }
    }

    #[test]
    fn the_neck_reduces_all_three_branches_to_sixteen_channels() {
        // The laterals are the only place 72, 152 and 288 channels appear as *inputs*,
        // so this pins that the branches were taken at the right depths. Taking one a
        // pair too early gives a lateral the wrong input channel count, and the weight
        // shape check would then fail against the real asset — but not against a stub.
        let source = Shapes::new(TENSORS);
        let _ = build(&source, 640, 640);
        let asked = source.asked.borrow();
        let shape_at = |index: usize| -> Vec<u32> {
            asked
                .iter()
                .find(|(i, _)| *i == index)
                .map(|(_, dims)| dims.clone())
                .unwrap_or_default()
        };
        assert_eq!(shape_at(58), vec![16, 72, 1, 1]);
        assert_eq!(shape_at(60), vec![16, 152, 1, 1]);
        assert_eq!(shape_at(62), vec![16, 288, 1, 1]);
    }

    #[test]
    fn the_layer_table_matches_the_onnx_export() {
        let source = Shapes::new(TENSORS);
        let _ = build(&source, 640, 640);
        let asked = source.asked.borrow();
        let shape_at = |index: usize| -> Vec<u32> {
            asked
                .iter()
                .find(|(i, _)| *i == index)
                .map(|(_, dims)| dims.clone())
                .unwrap_or_default()
        };
        // Tensor 0, the stem: the only dense convolution in the backbone.
        assert_eq!(shape_at(0), vec![16, 3, 3, 3]);
        // Tensor 2, the first depthwise.
        assert_eq!(shape_at(2), vec![16, 1, 3, 3]);
        // Tensors 64..76, the neck's seven 3x3s, all 16 to 16.
        for index in [64, 66, 68, 70, 72, 74, 76] {
            assert_eq!(shape_at(index), vec![16, 16, 3, 3], "tensor {index}");
        }
        // Tensor 78, the stride-8 head's depthwise over the neck's 16 channels.
        assert_eq!(shape_at(78), vec![16, 1, 3, 3]);
        // Tensors 86, 88, 90: the stride-8 score, box and keypoint convolutions.
        assert_eq!(shape_at(86), vec![2, 64, 3, 3]);
        assert_eq!(shape_at(88), vec![8, 64, 3, 3]);
        assert_eq!(shape_at(90), vec![20, 64, 3, 3]);
        // The stride-32 head, 14 tensors later twice over. The three heads have their
        // own weights in this export rather than sharing them.
        assert_eq!(shape_at(114), vec![2, 64, 3, 3]);
        assert_eq!(shape_at(118), vec![20, 64, 3, 3]);
    }

    #[test]
    fn the_backbone_downsamples_five_times() {
        // 640 -> 320 -> 160 -> 80 -> 40 -> 20, which is what puts the three branches at
        // strides 8, 16 and 32. The neck adds two more stride-2 convolutions on 16
        // channels, hence seven in total.
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
            vec![
                // Backbone: the stem, then four depthwise reductions.
                (3, 640),
                (16, 320),
                (40, 160),
                (72, 80),
                (152, 40),
                // Neck: the bottom-up path, both on 16 channels.
                (16, 80),
                (16, 40),
            ]
        );
    }

    #[test]
    fn a_non_rectangular_input_is_lowered_at_its_own_aspect_ratio() {
        // The letterboxed shape for a landscape photo: 640 long, short side padded to a
        // multiple of 32. Every extent below has to halve cleanly five times.
        let source = Shapes::new(TENSORS);
        let plan = build(&source, 384, 640).expect("builds at 640x384");
        let shapes: Vec<Shape> = plan.outputs.iter().map(|b| b.shape).collect();
        assert_eq!(shapes.first().copied(), Some(Shape::new(2, 48, 80)));
        assert_eq!(shapes.get(8).copied(), Some(Shape::new(20, 12, 20)));
    }

    #[test]
    fn an_extent_that_is_not_a_multiple_of_32_is_refused() {
        // 100 halves to 50, 25, 13, 7, 4 — and 13 is where the neck's assumption that
        // the branches are 2x apart breaks. Refused with a reason rather than failing
        // later inside an `Add`.
        let source = Shapes::new(TENSORS);
        let error = build(&source, 100, 640).expect_err("not a multiple of 32");
        assert!(error.contains("multiple of 32"), "{error}");
    }

    #[test]
    fn no_op_reads_a_region_it_also_writes() {
        super::super::tests::assert_no_aliasing(&plan());
    }

    #[test]
    fn the_arena_is_bounded() {
        let plan = plan();
        let bytes = plan.arena_elems as u64 * 2;
        // 16x320x320 fp16 is 3.3 MB and the widest thing here is the stem's output at
        // 16x320x320. A detector run is one-shot per photo during indexing, so this has
        // more headroom than the selfie net, but it still has to fit alongside the
        // embedder.
        assert!(bytes < 64 * 1024 * 1024, "arena is {bytes} bytes");
    }
}
