use super::context::Limits;
use super::segment::{Block, Segment, Segments, ALIGNMENT, BLOCK_OVERLAP, MAX_SEGMENTS};

impl Segments {
    /// Lay `total` bytes of weights out as blocks of windows, so that every op's tensors land
    /// inside one block and one descriptor.
    ///
    /// Depends only on the file's length and the device, never on the plan. See the module docs.
    pub fn plan(total: u64, limits: &Limits) -> Result<Segments, String> {
        let granularity = limits.min_storage_buffer_offset_alignment.max(ALIGNMENT);
        let range = limits.max_storage_buffer_range;
        if range < granularity {
            return Err(format!(
                "maxStorageBufferRange {range} is below minStorageBufferOffsetAlignment \
                 {granularity}"
            ));
        }
        let mut segments = Vec::new();
        if total <= limits.max_memory_allocation_size {
            // Every file before Gemma 4: one allocation, windowed exactly as it always was.
            let block = windows(0, 0, total, range, granularity, &mut segments)?;
            return Ok(Segments { segments, blocks: vec![block], block_stride: 0, overlap: u64::MAX });
        }

        // Never more than a window on this device could reach, so a forced small range shrinks
        // it too and the parity fixtures can cross a block boundary at a few tens of MiB.
        let window_reach = range - ((range / 4) / granularity) * granularity;
        let overlap = (BLOCK_OVERLAP.min(window_reach) / granularity) * granularity;
        // Rounded down so `get_buffer_memory_requirements` padding cannot push a block past it.
        let cap = (limits.max_memory_allocation_size >> 20) << 20;
        if cap <= overlap {
            return Err(format!(
                "maxMemoryAllocationSize {} leaves no room past a {overlap}-byte block overlap",
                limits.max_memory_allocation_size
            ));
        }
        // Equal blocks rather than full ones and a tail: smaller allocations are likelier to
        // succeed on a heap shared with the rest of the system, and the overlap costs the same.
        let count = (total - overlap).div_ceil(cap - overlap);
        let block_stride = (total - overlap).div_ceil(count).div_ceil(granularity) * granularity;
        let count = usize::try_from(count).map_err(|_| "too many blocks")?;
        if count > MAX_SEGMENTS {
            return Err(format!("{total} bytes of weights need {count} allocations"));
        }
        let mut blocks = Vec::with_capacity(count);
        for index in 0..count {
            let base = index as u64 * block_stride;
            if base >= total {
                break;
            }
            let len = (block_stride + overlap).min(total - base);
            blocks.push(windows(index, base, len, range, granularity, &mut segments)?);
        }
        if segments.len() > MAX_SEGMENTS {
            return Err(format!(
                "{total} bytes of weights need {} descriptor windows, past the {MAX_SEGMENTS} \
                 this allows",
                segments.len()
            ));
        }
        Ok(Segments { segments, blocks, block_stride, overlap })
    }
}

/// Window block `block`, `len` bytes at weights byte `base`, appending its segments.
fn windows(
    block: usize,
    base: u64,
    len: u64,
    range: u64,
    granularity: u64,
    segments: &mut Vec<Segment>,
) -> Result<Block, String> {
    let first = segments.len();
    if len <= range {
        // The overwhelmingly common case: one descriptor over the whole block, `base` zero, so
        // `rebase` moves only by the block's own offset.
        segments.push(Segment { block, base: 0, len });
        return Ok(Block { base, len, first, stride: 0, reach: range });
    }
    // A quarter of the range, rounded down to something both views can address.
    let stride = ((range / 4) / granularity) * granularity;
    if stride == 0 {
        return Err(format!("maxStorageBufferRange {range} is too small to window"));
    }
    let count = usize::try_from(len.div_ceil(stride)).map_err(|_| "too many segments")?;
    if count > MAX_SEGMENTS {
        return Err(format!(
            "{len} bytes of weights need {count} descriptor windows at a \
             maxStorageBufferRange of {range}, past the {MAX_SEGMENTS} this allows"
        ));
    }
    segments.extend((0..count).map(|index| {
        let at = index as u64 * stride;
        Segment { block, base: at, len: range.min(len - at) }
    }));
    Ok(Block { base, len, first, stride, reach: range - stride })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nets::{Kind, Push};
    use crate::weights::{Dtype, Tensor};

    const GIB: u64 = 1 << 30;
    const MIB: u64 = 1 << 20;
    const GEMMA4_TEXT: u64 = 1_101_558_320;

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
        Tensor {
            rank: 1,
            dims: [(bytes / 2) as u32, 0, 0, 0],
            offset: offset as u32,
            len: (bytes / 2) as u32,
            dtype: Dtype::F16,
        }
    }

    /// An op reading only an fp16 `weight` at byte `at`.
    fn reading(at: u64) -> (Kind, Push) {
        (Kind::RmsNorm, Push { weight: (at / 2) as u32, ..Push::default() })
    }

    fn check_blocks(segments: &Segments, total: u64, cap: u64) {
        let blocks = segments.blocks();
        assert_eq!(blocks.first().map(|b| b.base), Some(0));
        assert_eq!(blocks.last().map(|b| b.base + b.len), Some(total));
        for pair in blocks.windows(2) {
            assert_eq!(pair[0].base + pair[0].len - pair[1].base, segments.overlap);
        }
        assert!(blocks.iter().all(|b| b.len <= cap && b.base % 64 == 0));
        for segment in segments.all() {
            let block = blocks[segment.block];
            assert!(segment.base + segment.len <= block.len);
        }
    }

    #[test]
    fn a_file_under_the_allocation_limit_is_one_block_as_before() {
        let segments = Segments::plan(318 * MIB, &limits(128 * MIB, GIB)).expect("plans");
        assert_eq!(segments.blocks().len(), 1);
        assert_eq!(segments.block_stride, 0);
        let all = segments.all();
        assert_eq!(all.len(), 318usize.div_ceil(32));
        assert!(all.iter().all(|s| s.block == 0 && s.len <= 128 * MIB));
        assert_eq!(all[1].base, 32 * MIB);
    }

    #[test]
    fn gemma4_text_splits_into_two_blocks_at_the_adreno_limit() {
        for range in [128 * MIB, 4 * GIB - 1] {
            let segments = Segments::plan(GEMMA4_TEXT, &limits(range, GIB)).expect("plans");
            assert_eq!(segments.blocks().len(), 2, "range {range}");
            assert_eq!(segments.overlap, 96 * MIB);
            check_blocks(&segments, GEMMA4_TEXT, GIB);
        }
    }

    #[test]
    fn a_span_resolves_to_the_block_it_starts_in() {
        let segments = Segments::plan(GEMMA4_TEXT, &limits(4 * GIB - 1, GIB)).expect("plans");
        let stride = segments.block_stride;
        let tensors = [f16(0, 8 * MIB), f16(stride - 4 * MIB, 64 * MIB), f16(stride + 8 * MIB, MIB)];
        let resolve = |at: u64| {
            let (kind, push) = reading(at);
            segments.for_op(0, kind, &push, &tensors).expect("fits").expect("reads weights")
        };
        assert_eq!(segments.all()[resolve(0)].block, 0);
        // Straddles the second block's base, but inside the overlap of the first.
        assert_eq!(segments.all()[resolve(stride - 4 * MIB)].block, 0);
        let later = resolve(stride + 8 * MIB);
        assert_eq!(segments.all()[later].block, 1);
        let (kind, push) = reading(stride + 8 * MIB);
        assert_eq!(segments.rebase(later, kind, &push).weight, (8 * MIB / 2) as u32);
    }

    #[test]
    fn a_span_longer_than_the_overlap_across_a_cut_is_refused() {
        let segments = Segments::plan(GEMMA4_TEXT, &limits(4 * GIB - 1, GIB)).expect("plans");
        let start = segments.block_stride - MIB;
        let tensors = [f16(start, 100 * MIB)];
        let (kind, push) = reading(start);
        assert!(segments.for_op(0, kind, &push, &tensors).is_err());
    }

    #[test]
    fn forced_small_limits_put_a_late_tensor_in_a_later_block() {
        // The shape `parity_part3`'s 44 MiB fixture takes under
        // MODELRUNNER_MAX_STORAGE_RANGE=MODELRUNNER_MAX_ALLOCATION=33554432.
        let total = 44 * MIB + 4096;
        let segments = Segments::plan(total, &limits(32 * MIB, 32 * MIB)).expect("plans");
        assert_eq!(segments.overlap, 24 * MIB);
        assert!(segments.blocks().len() >= 2);
        check_blocks(&segments, total, 32 * MIB);
        let tensors = [f16(44 * MIB, 4096)];
        let (kind, push) = reading(44 * MIB);
        let index = segments.for_op(0, kind, &push, &tensors).expect("fits").expect("reads");
        let block = segments.blocks()[segments.all()[index].block];
        assert!(block.base > 0);
        let rebased = segments.rebase(index, kind, &push).weight;
        assert_eq!(u64::from(rebased) * 2, 44 * MIB - block.base - segments.all()[index].base);
    }
}
