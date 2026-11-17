//! MODNet photographic-portrait matting, the `:camera` still-capture matte, at a fixed 512x512.
//!
//! # Shape of the network
//!
//! A MobileNetV2 encoder (inverted-residual blocks down to 16x16, ReLU6
//! throughout) with a squeeze-excite gate on the trunk, then three decoder
//! branches back up to full resolution: a low-resolution semantic branch, a
//! high-resolution detail branch fed by two encoder taps, and a fusion branch
//! over both plus the input image, ending in a 1x1 plus sigmoid. See
//! `analysis/modnet/onnx/modnet_onnx.py` (`MODNet`, `LRBranch`, `HRBranch`,
//! `FusionBranch`) for the architecture this transcribes.
//!
//! # IBNorm: the one structural idiom
//!
//! Every branch convolution is followed by an `IBNorm`: the output split in
//! two along channels, a frozen batch norm over the first half, an
//! affine-free instance norm over the second, re-concatenated, then a ReLU.
//! The batch-norm halves are folded into their convolutions by
//! `scripts/ml/modnet_fold.py` (a `Slice` sits between the convolution and
//! the norm, so `maml_convert`'s direct-follower fold cannot fire); what is
//! transcribed here is `conv -> slice_channels -> instance_norm -> concat ->
//! activate(Relu)`, via [`ibnorm`].
//!
//! # Three things about it that are easy to get wrong
//!
//! * **The projections are linear.** Every inverted-residual bottleneck ends in
//!   a 1x1 with no activation — the export has no `Clip`/`Relu` between those
//!   convolutions and their consumer — and the residuals add those linear
//!   outputs. Fusing a ReLU6 into a projection would clamp every skip
//!   connection in the backbone.
//! * **The 1x1s called `MatMul` are convolutions.** The squeeze-excite gate's
//!   two projections arrive as `MatMul` against 2-D constants; the fold script
//!   rewrites them as 1x1 `Conv` layers, and the surrounding
//!   `Unsqueeze`/`Concat`/`Reshape`/`Expand` shape scaffolding is dead code
//!   that is not transcribed — the gate is `global_avg_pool`, two 1x1s and a
//!   [`mul_channel`](Builder::mul_channel), whose broadcast is what the
//!   `Expand` was spelling out.
//! * **The encoder taps are mid-network tensors.** The high-resolution branch
//!   reads the backbone's 16-channel 256x256 output and the 24-channel 128x128
//!   residual output, so those stay live across the whole low-resolution
//!   branch; the arena packing in [`Builder::finish`] accounts for it.
//!
//! Preprocessing is `(value / 255 - 0.5) / 0.5` over RGB — see
//! [`crate::preprocess::MODNET`].

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// The side length the graph is lowered at.
pub const SIZE: u32 = 512;
/// Tensors in the file: 71 layers, weight and bias each.
pub const TENSORS: usize = 142;

/// Hands out `.maml` tensor indices in the order the layers appear. See
/// [`super::u2netp`] for why this is a counter.
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

/// A MobileNetV2 inverted-residual block: expand 1x1 (ReLU6), depthwise 3x3
/// (ReLU6), linear projection 1x1, optionally added back to the input.
fn inverted_residual(
    b: &mut Builder,
    layers: &mut Layers,
    x: Id,
    expand: u32,
    out: u32,
    stride: u32,
    residual: bool,
) -> Id {
    let expanded = b.conv_same(x, layers.take(), expand, 1, 1, Act::Relu6);
    // Depthwise: one group per channel, so the kernel is `[expand, 1, 3, 3]`.
    let pad = 1;
    let spatial = b.conv(
        expanded,
        layers.take(),
        expand,
        (3, 3),
        (stride, stride),
        (1, 1),
        (pad, pad, pad, pad),
        expand,
        Act::Relu6,
    );
    let projected = b.conv_same(spatial, layers.take(), out, 1, 1, Act::None);
    if residual {
        b.add(x, projected)
    } else {
        projected
    }
}

/// One `Conv2dIBNormRelu`: convolution (no fused activation — the ReLU comes
/// after the rejoin), channel split, instance norm over the second half,
/// re-concatenation, standalone ReLU.
fn ibnorm(
    b: &mut Builder,
    layers: &mut Layers,
    x: Id,
    out: u32,
    kernel: u32,
    stride: u32,
    split: u32,
) -> Id {
    let pad = kernel / 2;
    let h = if kernel == 1 {
        b.conv_same(x, layers.take(), out, 1, 1, Act::None)
    } else {
        b.conv(
            x,
            layers.take(),
            out,
            (kernel, kernel),
            (stride, stride),
            (1, 1),
            (pad, pad, pad, pad),
            1,
            Act::None,
        )
    };
    let a = b.slice_channels(h, 0, split);
    let c = b.slice_channels(h, split, out - split);
    let c = b.instance_norm(c, 1e-5);
    let h = b.concat(&[a, c]);
    b.activate(h, Act::Relu)
}

/// Compile the whole forward pass.
pub fn build(weights: &dyn WeightSource) -> Result<Plan, String> {
    let mut b = Builder::new(weights);
    b.spatial_tiling();
    let mut layers = Layers { next: 0 };
    let l = &mut layers;
    let input = b.input(Shape::new(3, SIZE, SIZE));

    // Stem: 512 -> 256.
    let x = b.conv(
        input,
        l.take(),
        32,
        (3, 3),
        (2, 2),
        (1, 1),
        (1, 1, 1, 1),
        1,
        Act::Relu6,
    );

    // Backbone. Kept tensors: `enc2x` (16ch @ 256) and `enc4x` (24ch @ 128)
    // feed the high-resolution branch below.
    let x = b.conv(x, l.take(), 32, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 32, Act::Relu6);
    let enc2x = b.conv_same(x, l.take(), 16, 1, 1, Act::None);
    let x = inverted_residual(&mut b, l, enc2x, 96, 24, 2, false);
    let x = inverted_residual(&mut b, l, x, 144, 24, 1, true);
    let enc4x = x;
    let x = inverted_residual(&mut b, l, x, 144, 32, 2, false);
    let x = inverted_residual(&mut b, l, x, 192, 32, 1, true);
    let x = inverted_residual(&mut b, l, x, 192, 32, 1, true);
    let x = inverted_residual(&mut b, l, x, 192, 64, 2, false);
    let x = inverted_residual(&mut b, l, x, 384, 64, 1, true);
    let x = inverted_residual(&mut b, l, x, 384, 64, 1, true);
    let x = inverted_residual(&mut b, l, x, 384, 64, 1, true);
    let x = inverted_residual(&mut b, l, x, 384, 96, 1, false);
    let x = inverted_residual(&mut b, l, x, 576, 96, 1, true);
    let x = inverted_residual(&mut b, l, x, 576, 96, 1, true);
    let x = inverted_residual(&mut b, l, x, 576, 160, 2, false);
    let x = inverted_residual(&mut b, l, x, 960, 160, 1, true);
    let x = inverted_residual(&mut b, l, x, 960, 160, 1, true);
    let x = inverted_residual(&mut b, l, x, 960, 320, 1, false);
    let trunk = b.conv_same(x, l.take(), 1280, 1, 1, Act::Relu6);

    // Squeeze-excite gate over the trunk: pooled to Cx1x1, two 1x1s
    // (rewritten from MatMuls by the fold script), sigmoid, broadcast back.
    let pooled = b.global_avg_pool(trunk);
    let gate = b.conv_same(pooled, l.take(), 320, 1, 1, Act::Relu);
    let gate = b.conv_same(gate, l.take(), 1280, 1, 1, Act::Sigmoid);
    let gated = b.mul_channel(trunk, gate);

    // Low-resolution branch: 16 -> 32 -> 64.
    let lr = b.resize_to(gated, 32, 32);
    let lr = ibnorm(&mut b, l, lr, 96, 5, 1, 48);
    let lr = b.resize_to(lr, 64, 64);
    let lr8x = ibnorm(&mut b, l, lr, 32, 5, 1, 16);

    // High-resolution branch inputs, straight off the source image.
    let img2x = b.resize_to(input, 256, 256);
    let img4x = b.resize_to(input, 128, 128);

    // Encoder taps projected across, then detail refinement at 128.
    let tohr2x = ibnorm(&mut b, l, enc2x, 32, 1, 1, 16);
    let hr = b.concat(&[img2x, tohr2x]);
    let hr = ibnorm(&mut b, l, hr, 32, 3, 2, 16);
    let tohr4x = ibnorm(&mut b, l, enc4x, 32, 1, 1, 16);
    let hr = b.concat(&[hr, tohr4x]);
    let hr = ibnorm(&mut b, l, hr, 64, 3, 1, 32);
    let lr4x = b.resize_to(lr8x, 128, 128);
    let hr = b.concat(&[hr, lr4x, img4x]);
    let hr = ibnorm(&mut b, l, hr, 64, 3, 1, 32);
    let hr = ibnorm(&mut b, l, hr, 64, 3, 1, 32);
    let hr = ibnorm(&mut b, l, hr, 32, 3, 1, 16);

    // Detail refinement at 256, reusing the projected 2x tap.
    let hr = b.resize_to(hr, 256, 256);
    let hr = b.concat(&[hr, tohr2x]);
    let hr = ibnorm(&mut b, l, hr, 64, 3, 1, 32);
    let hr = ibnorm(&mut b, l, hr, 32, 3, 1, 16);
    let hr = ibnorm(&mut b, l, hr, 32, 3, 1, 16);
    let hr2x = ibnorm(&mut b, l, hr, 32, 3, 1, 16);

    // Fusion branch: semantic stream upsampled, merged with detail and image.
    let f = b.resize_to(lr8x, 128, 128);
    let f = ibnorm(&mut b, l, f, 32, 5, 1, 16);
    let f = b.resize_to(f, 256, 256);
    let f = b.concat(&[f, hr2x]);
    let f = ibnorm(&mut b, l, f, 32, 3, 1, 16);
    let f = b.resize_to(f, 512, 512);
    let f = b.concat(&[f, input]);
    let f = ibnorm(&mut b, l, f, 16, 3, 1, 8);

    // 1x1 to one channel and the sigmoid that makes it an alpha.
    let matte = b.conv_same(f, l.take(), 1, 1, 1, Act::Sigmoid);
    b.finish(&[matte])
}

#[cfg(test)]
mod tests {
    use super::super::tests::Shapes;
    use super::super::{Kind, Op};
    use super::*;

    fn plan() -> Plan {
        let source = Shapes::new(TENSORS);
        build(&source).expect("modnet builds")
    }

    /// Whether two kinds are the same graph operation, folding `Conv`'s tiled lowering in.
    fn same_op(found: Kind, wanted: Kind) -> bool {
        let fold = |k: Kind| if matches!(k, Kind::ConvPoint) { Kind::Conv } else { k };
        fold(found) == fold(wanted)
    }

    fn dispatches(plan: &Plan, kind: Kind) -> usize {
        plan.ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { kind: k, .. } if same_op(*k, kind)))
            .count()
    }

    #[test]
    fn the_op_inventory_matches_the_folded_onnx() {
        // The counts `scripts/ml/modnet_fold.py --print-graph` reports over the
        // folded export, minus its dead shape scaffolding (the SE block's
        // `Unsqueeze`/`Concat`/`Reshape`/`Expand` cluster, which the gate
        // transcribes as pool-plus-two-1x1s-plus-broadcast instead): 71
        // convolutions (69 + 2 SE MatMuls rewritten as 1x1s), 10 backbone
        // residuals, the SE gate multiply, 16 IBNorm rejoins plus 6 structural
        // branch merges, 16 instance norms, one squeeze, and 17 standalone
        // ReLUs (16 post-rejoin plus the SE block's own — every one an
        // `Activate` because a `Concat` produced the value, not a convolution).
        let plan = plan();
        assert_eq!(dispatches(&plan, Kind::Conv), 71);
        // Six of the ten backbone residuals dispatch as their own op; the
        // other four fold into their producing convolution's store — the
        // inverted-residual tails whose skip side is already written when the
        // projection runs. See `Builder::add`.
        assert_eq!(dispatches(&plan, Kind::Add), 6);
        assert_eq!(dispatches(&plan, Kind::MulBroadcast), 1);
        assert_eq!(dispatches(&plan, Kind::InstanceNorm), 16);
        assert_eq!(dispatches(&plan, Kind::GlobalAvgPool), 1);
        // 16 post-rejoin ReLUs, one per IBNorm site. The SE block's own ReLU
        // is fused into its 1x1 rather than standalone, and both sigmoids are
        // fused the same way — so unlike the ONNX census (17 Relu, 2 Sigmoid)
        // only the 16 rejoins dispatch here.
        assert_eq!(dispatches(&plan, Kind::Activate), 16);
        assert_eq!(dispatches(&plan, Kind::Resize), 9);
        // `Concat` lowers to one copy per part: 16 two-part IBNorm rejoins
        // plus 6 structural branch merges (5 two-part, 1 three-part) — and each
        // `slice_channels` half is one copy too.
        let copies = plan.ops.iter().filter(|op| matches!(op, Op::Copy { .. })).count();
        assert_eq!(copies, 16 * 2 + 5 * 2 + 3 + 32);
        // No pooling, no transposed convolution.
        assert_eq!(dispatches(&plan, Kind::MaxPool), 0);
        assert_eq!(dispatches(&plan, Kind::ConvTranspose), 0);
    }

    #[test]
    fn the_pass_consumes_the_whole_tensor_table() {
        let source = Shapes::new(TENSORS);
        assert!(build(&source).is_ok());
        assert_eq!(source.asked.borrow().len(), TENSORS);
    }

    #[test]
    fn the_layer_table_matches_the_folded_export() {
        let source = Shapes::new(TENSORS);
        let _ = build(&source);
        let asked = source.asked.borrow();
        let shape_at = |index: usize| -> Vec<u32> {
            asked
                .iter()
                .find(|(i, _)| *i == index)
                .map(|(_, dims)| dims.clone())
                .unwrap_or_default()
        };
        // Layer 0, the stem.
        assert_eq!(shape_at(0), vec![32, 3, 3, 3]);
        // Layer 1, the first depthwise: `[32, 1, 3, 3]` is what group == channels
        // looks like, and the shape most likely to be wrong if grouping is misread.
        assert_eq!(shape_at(2), vec![32, 1, 3, 3]);
        // Layer 51, the backbone head: 320 channels in, 1280 out.
        assert_eq!(shape_at(102), vec![1280, 320, 1, 1]);
        // Layer 52, the first rewritten SE MatMul: `[320, 1280, 1, 1]`.
        assert_eq!(shape_at(104), vec![320, 1280, 1, 1]);
        // Layer 54, the first branch head: 5x5 over the 1280-channel trunk.
        assert_eq!(shape_at(108), vec![96, 1280, 5, 5]);
        // Layer 70, the final 1x1 to one channel.
        assert_eq!(shape_at(140), vec![1, 16, 1, 1]);
    }

    #[test]
    fn the_input_and_output_are_the_shapes_kotlin_expects() {
        let plan = plan();
        assert_eq!(plan.input().expect("one input").shape, Shape::new(3, SIZE, SIZE));
        assert_eq!(plan.output().expect("one output").shape, Shape::new(1, SIZE, SIZE));
    }

    #[test]
    fn the_ibnorm_splits_cover_the_whole_channel_output() {
        // Each IBNorm site slices `[0:k)` and `[k:channels)` and concatenates
        // them back: a split point past the end, or a rejoin of anything but
        // the two halves, would change the channel count the next convolution
        // was lowered against. This walks the plan checking that every
        // `Concat` of two `SliceChannels` restores its source's channels.
        // (Structural rather than value-checked: the parity script covers values.)
        let plan = plan();
        let rejoins = plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { kind: Kind::Activate, .. }))
            .count();
        // One standalone ReLU per IBNorm rejoin.
        assert_eq!(rejoins, 16);
    }

    #[test]
    fn no_op_reads_a_region_it_also_writes() {
        super::super::tests::assert_no_aliasing(&plan());
    }

    #[test]
    fn the_arena_is_bounded() {
        let plan = plan();
        let bytes = plan.arena_elems as u64 * 2;
        // This one runs once per shutter in `StillBokehRenderer`, not per frame,
        // so it may spend what U^2-Netp spends: tens of megabytes for the 64-
        // channel 256x256 detail tensors and the live encoder taps.
        assert!(bytes < 128 * 1024 * 1024, "arena is {bytes} bytes");
    }
}
