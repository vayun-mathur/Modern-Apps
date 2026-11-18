//! Tests for `Kind::weight_reads` and `Kind::arena_reads`: the exact
//! read tables the segmenter and the barrier scheduler trust. Each
//! expectation is stated from the shader contract in the table's own
//! docs — fp16-element vs 32-bit-word addressing, and the arena ranges
//! an op's operands occupy.

use super::{Act, Kind, Push, Reads, NO_FUSE};

fn push() -> Push {
    Push {
        in0: 100,
        in1: 200,
        out: 300,
        weight: 10,
        bias: 20,
        act_weight: 30,
        in_c: 4,
        in_h: 2,
        in_w: 3,
        out_c: 5,
        out_h: 2,
        out_w: 3,
        group: 2,
        count: 7,
        res: NO_FUSE,
        shift: NO_FUSE,
        ..Push::default()
    }
}

fn fields(kind: Kind, p: &Push) -> Vec<(&'static str, u64)> {
    kind.weight_reads(p).iter().map(|r| (r.field, r.at)).collect()
}

#[test]
fn weight_reads_use_elements_for_fp16_and_words_for_quantised_kernels() {
    let p = push();
    // fp16 conv: kernel and bias in elements (x2 bytes).
    assert_eq!(fields(Kind::Conv, &p), vec![("weight", 20), ("bias", 40)]);
    assert_eq!(fields(Kind::ConvTranspose, &p), vec![("weight", 20), ("bias", 40)]);
    assert_eq!(fields(Kind::LayerNorm, &p), vec![("weight", 20), ("bias", 40)]);
    // Quantised kernels: kernel in words (x4), bias and scale in elements.
    for kind in [Kind::ConvInt8, Kind::ConvPointInt4, Kind::ConvQ2K, Kind::ConvVecInt8] {
        assert_eq!(
            fields(kind, &p),
            vec![("weight", 40), ("bias", 40), ("act_weight", 60)],
            "{kind:?}"
        );
    }
    // One-table kinds read only the weight.
    for kind in [Kind::RmsNorm, Kind::Constant, Kind::Embed, Kind::AttnScoresRelative] {
        assert_eq!(fields(kind, &p), vec![("weight", 20)], "{kind:?}");
    }
    // The scalar pair lives at act_weight.
    assert_eq!(fields(Kind::Clamp, &p), vec![("act_weight", 60)]);
    assert_eq!(fields(Kind::MulScalar, &p), vec![("act_weight", 60)]);
    // Arena-only kinds read no weights at all.
    for kind in [Kind::Softmax, Kind::Add, Kind::MaxPool, Kind::Argmax, Kind::Rotary, Kind::Activate] {
        assert!(fields(kind, &p).is_empty(), "{kind:?}");
    }
}

#[test]
fn a_prelu_activation_adds_its_slope_to_any_weight_reading_kind() {
    let mut p = push();
    p.act = Act::PRelu(0).code();
    // RmsNorm already reads its gamma; the slope is a second read.
    assert_eq!(fields(Kind::RmsNorm, &p), vec![("weight", 20), ("act_weight", 60)]);
    // A conv carrying PReLU gains the slope read alongside kernel and bias.
    assert_eq!(fields(Kind::Conv, &p), vec![("weight", 20), ("bias", 40), ("act_weight", 60)]);
    // An int8 conv already reads act_weight as its scale, so nothing is added twice.
    assert_eq!(fields(Kind::ConvInt8, &p).len(), 3);
    // A standalone activation reads no weights but does read its slope.
    assert_eq!(fields(Kind::Activate, &p), vec![("act_weight", 60)]);
}

fn ranges(kind: Kind, p: &Push) -> Vec<(u32, u32)> {
    match kind.arena_reads(p) {
        Reads::Ranges(r) => r,
        Reads::Unknown => panic!("{kind:?} should be audited"),
    }
}

#[test]
fn arena_reads_cover_both_operands_at_the_written_extent() {
    let p = push(); // written = 5*2*3 = 30, dense = 4*2*3 = 24
    assert_eq!(ranges(Kind::Add, &p), vec![(100, 30), (200, 30)]);
    assert_eq!(ranges(Kind::Mul, &p), vec![(100, 30), (200, 30)]);
    // The broadcast gate is one value per channel.
    assert_eq!(ranges(Kind::MulBroadcast, &p), vec![(100, 30), (200, 4)]);
    assert_eq!(ranges(Kind::AddBroadcast, &p), vec![(100, 30), (200, 5)]);
    // A constant copies out of the weights file and reads no arena.
    assert!(ranges(Kind::Constant, &p).is_empty());
    // Elementwise kinds read their whole input plane.
    assert_eq!(ranges(Kind::Softmax, &p), vec![(100, 24)]);
    assert_eq!(ranges(Kind::Clamp, &p), vec![(100, 24)]);
}

#[test]
fn arena_reads_for_attention_and_reductions_use_their_own_geometry() {
    let p = push();
    // Scores: Q is in_c x out_h, K is in_c x out_w.
    assert_eq!(ranges(Kind::AttnScores, &p), vec![(100, 8), (200, 12)]);
    // Argmax reads the whole row; its fold re-reads pairs plus the row.
    assert_eq!(ranges(Kind::Argmax, &p), vec![(100, 24)]);
    assert_eq!(ranges(Kind::ArgmaxFold, &p), vec![(100, 8), (200, 3)]);
    // Top-k stage 1 reads count; stage 2 reads candidate pairs and the row.
    let mut stage1 = push();
    stage1.kh = 0;
    assert_eq!(ranges(Kind::TopK, &stage1), vec![(100, 7)]);
    let mut stage2 = push();
    stage2.kh = 1;
    assert_eq!(ranges(Kind::TopK, &stage2), vec![(100, 8), (200, 3)]);
    // Embed reads one id per lane per position: in_c * out_w.
    assert_eq!(ranges(Kind::Embed, &p), vec![(100, 12)]);
    // The gated activation reads both halves: in_c + count.
    assert_eq!(ranges(Kind::GatedActivate, &p), vec![(100, 11)]);
}

#[test]
fn arena_reads_for_convolutions_add_fused_addends_only_when_fused() {
    let p = push();
    assert_eq!(ranges(Kind::Conv, &p), vec![(100, 24)]);
    let mut fused = push();
    fused.res = 400;
    fused.shift = 500;
    assert_eq!(ranges(Kind::Conv, &fused), vec![(100, 24), (400, 30), (500, 5)]);
    // RmsNorm folds only its residual, never a shift.
    assert_eq!(ranges(Kind::RmsNorm, &fused), vec![(100, 24), (400, 30)]);
    // The int4 dot read also covers the staged int8 blocks at kw:
    // in_c/8*5 * out_h*out_w = 0 here (in_c = 4), so use 32 channels.
    let mut dot = push();
    dot.in_c = 32;
    assert_eq!(ranges(Kind::ConvPointInt4Dot, &dot), vec![(100, 192), (0, 120)]);
}

#[test]
fn audited_reads_expose_ranges_and_shape_queries_record_their_asks() {
    use super::tests::{name_of, Shapes};
    use super::WeightSource as _;

    assert_eq!(Reads::Ranges(vec![(2, 3)]).ranges(), Some(&[(2, 3)][..]));
    assert_eq!(Reads::Unknown.ranges(), None);
    assert_eq!(name_of(Kind::ConvPoint), "Conv");
    assert_eq!(name_of(Kind::ConvPointInt8), "ConvInt8");
    assert_eq!(name_of(Kind::ConvVecInt4), "ConvInt4");
    assert_eq!(name_of(Kind::ConvPointQ2K), "ConvQ2K");
    assert_eq!(name_of(Kind::Softmax), "Softmax");

    let shapes = Shapes::new(3);
    assert_eq!(shapes.shaped_words(2, &[9]).expect("shape query"), 2);
    assert_eq!(shapes.count(), 3);
    assert_eq!(shapes.asked.borrow().as_slice(), &[(2, vec![9])]);
}
