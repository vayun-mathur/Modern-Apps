//! Pure-logic tests inside `run`: the barrier formulation's stage and
//! access masks, the staging-batch splitter, binding accounting, the
//! push-block byte view, and the int8-widening layout. None of these
//! touch a device.

use super::{batches, binding_elems, push_bytes, widened_layout, Formulation, StepParams};
use crate::nets::{Binding, Push, Shape};
use crate::weights::{Blob, Dtype, Tensor};
use ash::vk;

#[test]
fn the_default_barrier_covers_compute_and_transfer_on_both_sides() {
    let (src, dst) = Formulation::Range.stages();
    assert!(src == vk::PipelineStageFlags::COMPUTE_SHADER | vk::PipelineStageFlags::TRANSFER);
    assert!(dst == src);
    let (src, dst) = Formulation::Range.accesses();
    assert!(src.contains(vk::AccessFlags::SHADER_WRITE | vk::AccessFlags::TRANSFER_WRITE));
    assert!(dst.contains(vk::AccessFlags::TRANSFER_READ));
    // The other correct spellings share the default masks.
    assert!(Formulation::Whole.stages() == Formulation::Range.stages());
    assert!(Formulation::Global.accesses() == Formulation::Range.accesses());
}

#[test]
fn the_narrow_formulations_name_only_the_compute_dependency() {
    for form in [Formulation::Narrow, Formulation::GlobalNarrow] {
        assert!(form.stages() == (vk::PipelineStageFlags::COMPUTE_SHADER, vk::PipelineStageFlags::COMPUTE_SHADER));
        assert!(
            form.accesses()
                == (vk::AccessFlags::SHADER_WRITE, vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE)
        );
    }
    let all = vk::PipelineStageFlags::ALL_COMMANDS;
    assert!(Formulation::AllCommands.stages() == (all, all));
    assert!(
        Formulation::AllCommands.accesses()
            == (vk::AccessFlags::MEMORY_WRITE, vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE)
    );
    assert!(Formulation::None.stages() == Formulation::Range.stages());
}

#[test]
fn the_selected_formulation_defaults_to_range() {
    // No knob is set in the test process, and the choice is cached, so
    // this is the shipped default.
    assert_eq!(Formulation::selected(), Formulation::Range);
}

#[test]
fn batches_split_long_ranges_at_the_budget_and_pack_short_ones() {
    // Budget is in fp16 bytes: 8 bytes holds 4 elements per batch.
    assert_eq!(batches(&[(0, 10)], 8), vec![vec![(0, 4)], vec![(4, 4)], vec![(8, 2)]]);
    // Short ranges share a batch until the budget runs out.
    assert_eq!(batches(&[(0, 3), (10, 3)], 8), vec![vec![(0, 3), (10, 1)], vec![(11, 2)]]);
    // Nothing to move, and a zero-length range moves nothing.
    assert!(batches(&[], 8).is_empty());
    assert!(batches(&[(5, 0)], 8).is_empty());
    // A zero budget still makes progress one element at a time.
    assert_eq!(batches(&[(0, 2)], 0), vec![vec![(0, 1)], vec![(1, 1)]]);
}

#[test]
fn dirty_union_merges_ranges_and_sticks_at_unbounded() {
    use super::dirty_union;
    // The first write opens the range; later writes only extend it.
    assert_eq!(dirty_union(None, 16, 8, 1024), Some((16, 24)));
    assert_eq!(dirty_union(Some((16, 24)), 8, 4, 1024), Some((8, 24)));
    assert_eq!(dirty_union(Some((16, 24)), 100, 4, 1024), Some((16, 104)));
    // A zero-length or arena-overflowing write is unbounded, and stays so.
    assert_eq!(dirty_union(None, 0, 0, 1024), Some((0, u64::MAX)));
    assert_eq!(dirty_union(Some((16, 24)), 1000, 100, 1024), Some((0, u64::MAX)));
    assert_eq!(dirty_union(Some((0, u64::MAX)), 8, 4, 1024), Some((0, u64::MAX)));
}

#[test]
fn binding_elems_sums_the_binding_shapes() {
    let bindings = [
        Binding { at: 0, shape: Shape::new(2, 3, 4) },
        Binding { at: 24, shape: Shape::new(1, 1, 5) },
    ];
    assert_eq!(binding_elems(&bindings), 29);
    assert_eq!(binding_elems(&[]), 0);
}

#[test]
fn push_bytes_is_exactly_the_push_block() {
    let a = Push { weight: 1, ..Push::default() };
    let b = Push { weight: 2, ..Push::default() };
    assert_eq!(push_bytes(&a).len(), std::mem::size_of::<Push>());
    assert_ne!(push_bytes(&a), push_bytes(&b));
    assert_eq!(StepParams::default().prefix, 0);
}

/// An in-memory `Blob` over raw bytes, for the widening fill.
struct Bytes(Vec<u8>);

impl Blob for Bytes {
    fn data_len(&self) -> u64 {
        self.0.len() as u64
    }
    fn tensors(&self) -> &[Tensor] {
        &[]
    }
    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String> {
        let start = usize::try_from(offset).map_err(|_| "offset overflow")?;
        let from = self.0.get(start..start + into.len()).ok_or("past the end")?;
        into.copy_from_slice(from);
        Ok(())
    }
}

fn tensor(offset: u32, len: u32, dtype: Dtype) -> Tensor {
    Tensor { rank: 1, dims: [len, 0, 0, 0], offset, len, dtype }
}

#[test]
fn widened_layout_packs_only_whole_aligned_int8_tensors_in_the_window() {
    // [int8 16B @0][f16 @16][int8 8B @32]
    let tensors = [tensor(0, 16, Dtype::I8), tensor(16, 8, Dtype::F16), tensor(32, 8, Dtype::I8)];
    // Word 0 -> byte 0 (int8, in window), word 4 -> byte 16 (fp16: skip),
    // word 8 -> byte 32 (int8 but past a 0..16 window: skip), word 0 again (dup: skip).
    let laid = widened_layout(&[0, 4, 8, 0], &tensors, (0, 16), 100);
    assert_eq!(laid.len(), 1);
    assert_eq!((laid[0].src, laid[0].bytes, laid[0].dst), (0, 16, 100));
    // A full window packs both int8 tensors back to back, fp16 copies
    // twice the int8 size.
    let laid = widened_layout(&[0, 8], &tensors, (0, 64), 0);
    assert_eq!(laid.len(), 2);
    assert_eq!((laid[1].src, laid[1].dst), (32, 32));
}

#[test]
fn fill_widened_converts_int8_bytes_to_fp16_in_place() {
    // Widen the 4-byte int8 tensor at 0 to fp16 at dst 0, over a 8-byte out.
    let laid = widened_layout(&[0], &[tensor(0, 8, Dtype::I8)], (0, 8), 0);
    assert_eq!(laid.len(), 1);
    let blob = Bytes(vec![1u8, 0xFF, 2, 0, 0, 0, 0, 0]); // 1, -1, 2, 0, ... as int8
    let mut out = vec![0u8; 16];
    let mut scratch = Vec::new();
    super::fill_widened(&laid, &blob, 0, &mut out, &mut scratch).expect("fills");
    let halves: Vec<u16> = out.chunks_exact(2).map(|c| u16::from_le_bytes([c[0], c[1]])).collect();
    let decoded: Vec<f32> = halves.iter().map(|&h| crate::preprocess::f16_to_f32(h)).collect();
    assert_eq!(decoded, vec![1.0, -1.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
    // A window past the widened copy writes nothing.
    let mut out = vec![0u8; 4];
    super::fill_widened(&laid, &blob, 100, &mut out, &mut scratch).expect("fills nothing");
    assert_eq!(out, vec![0u8; 4]);
}
