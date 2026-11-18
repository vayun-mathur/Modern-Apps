//! Pure-logic tests for the weights segmentation: plan layout
//! refusals, per-op window resolution, offset rebasing in both unit
//! views, and the upload window helpers. No device is involved; the
//! layout depends only on the file length and the reported limits.

use super::context::Limits;
use super::segment::{rebase_push, read_window, window_tensors, Segments};
use crate::nets::tests::Shapes;
use crate::nets::{Builder, Kind, Push, Shape};
use crate::weights::{Dtype, Tensor};

const GIB: u64 = 1 << 30;
const MIB: u64 = 1 << 20;

fn limits(range: u64, allocation: u64) -> Limits {
    Limits {
        max_storage_buffer_range: range,
        min_storage_buffer_offset_alignment: 64,
        max_memory_allocation_size: allocation,
        timestamp_period: 1.0,
        timestamp_valid_bits: 0,
    }
}

fn f16(offset: u64, bytes: u64) -> Tensor {
    Tensor { rank: 1, dims: [(bytes / 2) as u32, 0, 0, 0], offset: offset as u32, len: (bytes / 2) as u32, dtype: Dtype::F16 }
}

#[test]
fn plan_refuses_a_range_below_the_alignment_granularity() {
    // Granularity is max(64 reported, 16 required); a range of 32 cannot
    // hold even one aligned descriptor base step.
    let err = Segments::plan(MIB, &limits(32, GIB)).expect_err("range too small");
    assert!(err.contains("below"), "{err}");
}

#[test]
fn plan_refuses_an_allocation_that_leaves_no_room_past_the_overlap() {
    // A multi-block file whose per-block cap is under the block overlap.
    let err = Segments::plan(10 * MIB, &limits(128 * MIB, MIB)).expect_err("cap under overlap");
    assert!(err.contains("overlap"), "{err}");
}

#[test]
fn plan_refuses_more_windows_than_the_descriptor_budget() {
    // 1024-byte range, 256-byte stride: 300_000 bytes needs 1172 windows.
    let err = Segments::plan(300_000, &limits(1024, GIB)).expect_err("too many windows");
    assert!(err.contains("descriptor windows"), "{err}");
}

#[test]
fn an_op_that_reads_no_weights_binds_no_window() {
    let segments = Segments::plan(MIB, &limits(128 * MIB, GIB)).expect("plans");
    let got = segments.for_op(0, Kind::Softmax, &Push::default(), &[]).expect("resolves");
    assert_eq!(got, None);
}

#[test]
fn an_op_reading_outside_every_tensor_is_refused() {
    let segments = Segments::plan(MIB, &limits(128 * MIB, GIB)).expect("plans");
    let push = Push { weight: 500, ..Push::default() };
    let err = segments
        .for_op(3, Kind::RmsNorm, &push, &[f16(0, 16)])
        .expect_err("byte 1000 is in no tensor");
    assert!(err.contains("inside none"), "{err}");
}

#[test]
fn an_op_whose_span_exceeds_one_window_is_refused() {
    // One block windowed at a 4 KiB range / 1 KiB stride: reach is 3 KiB,
    // and this tensor spans 8 KiB.
    let segments = Segments::plan(MIB, &limits(4096, GIB)).expect("plans");
    let push = Push { weight: 0, ..Push::default() };
    let err = segments.for_op(0, Kind::RmsNorm, &push, &[f16(0, 8192)]).expect_err("span too wide");
    assert!(err.contains("descriptor window"), "{err}");
}

#[test]
fn rebase_moves_fp16_offsets_by_elements_and_int8_kernels_by_words() {
    // RmsNorm reads only its gamma, in fp16 elements: 1024 bytes = 512.
    let push = Push { weight: 1000, bias: 777, ..Push::default() };
    let out = rebase_push(1024, Kind::RmsNorm, &push);
    assert_eq!(out.weight, 488);
    assert_eq!(out.bias, 777, "a field the op does not read is untouched");

    // An int8 kernel is word-addressed (256 words per 1024 bytes) while
    // its bias and scale stay in elements (512), saturating at zero.
    let push = Push { weight: 1000, bias: 1000, act_weight: 100, ..Push::default() };
    let out = rebase_push(1024, Kind::ConvInt8, &push);
    assert_eq!(out.weight, 744);
    assert_eq!(out.bias, 488);
    assert_eq!(out.act_weight, 0);

    // A zero base is the identity.
    assert_eq!(rebase_push(0, Kind::ConvInt8, &push), push);
}

#[test]
fn read_window_covers_whole_tensors_and_window_tensors_relativises() {
    let source = Shapes::new(1);
    let mut builder = Builder::new(&source);
    let input = builder.input(Shape::new(2, 1, 1));
    let normed = builder.rms_norm(input, 0, 0.0);
    let plan = builder.finish(&[normed]).expect("builds");

    let tensors = [f16(0, 8), f16(16, 8), f16(32, 8)];
    // The plan's only weight read is the gamma at byte 0, an 8-byte tensor.
    assert_eq!(read_window(&[&plan], &tensors, 16).expect("window"), (0, 8));
    // No plans read nothing.
    assert_eq!(read_window(&[], &tensors, 16).expect("empty"), (0, 0));
    // A read landing in no described tensor is refused.
    assert!(read_window(&[&plan], &[], 16).is_err());

    // Only tensors wholly inside the window survive, offsets rebased.
    let kept = window_tensors(&tensors, 16, 40);
    assert_eq!(kept.len(), 2);
    assert_eq!(kept[0].offset, 0);
    assert_eq!(kept[1].offset, 16);
    // A tensor straddling the edge (bytes 8..24 against a 0..16 window) is out.
    let straddler = [f16(8, 16)];
    assert!(window_tensors(&straddler, 0, 16).is_empty());
}

#[test]
fn plan_refuses_more_allocations_than_the_budget() {
    // A 128 MiB allocation cap over a 96 MiB overlap leaves 32 MiB
    // per block: 40 GiB of weights would need ~1277 allocations.
    let err = Segments::plan(40 * GIB, &limits(128 * MIB, 128 * MIB)).expect_err("allocations");
    assert!(err.contains("allocations"), "{err}");
}

#[test]
fn plan_refuses_a_range_too_small_to_window() {
    // 128 bytes at a 64-byte granularity: the quarter-range stride
    // rounds to zero, so no window can be laid out.
    let err = Segments::plan(300_000, &limits(128, GIB)).expect_err("unwindowable");
    assert!(err.contains("too small to window"), "{err}");
}

#[test]
fn an_op_reading_past_the_last_block_is_refused() {
    // A tensor described as starting exactly at the end of the
    // weights: its span runs past the one block's end, which is the
    // converter's cue to split it.
    let segments = Segments::plan(MIB, &limits(4096, GIB)).expect("plans");
    let push = Push { weight: (MIB / 2) as u32, ..Push::default() };
    let err = segments
        .for_op(0, Kind::RmsNorm, &push, &[f16(MIB, 16)])
        .expect_err("past the block");
    assert!(err.contains("Split the tensor in the converter"), "{err}");
}

#[test]
fn plan_refuses_a_window_budget_spent_across_blocks() {
    // Two blocks windowed at a 1 KiB range: each block alone needs
    // thousands of 256-byte-stride windows, so the shared budget of
    // 1024 descriptor windows is spent many times over.
    let err = Segments::plan(3 * MIB, &limits(1024, 2 * MIB)).expect_err("windows across blocks");
    assert!(err.contains("descriptor windows"), "{err}");
}
