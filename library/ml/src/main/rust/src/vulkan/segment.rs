//! Splitting the weights buffer into descriptor-sized pieces.
//!
//! # The problem
//!
//! Every shader reads the whole weights file through one descriptor whose `range` is the file's
//! length, and `maxStorageBufferRange` is only guaranteed to be **128 MiB**. SMaLL-100's weights
//! are 318 MiB. So on a device reporting the minimum, one descriptor cannot describe the file at
//! all: the binding would be invalid, and validation would say so while a release build read
//! whatever the driver felt like past the range.
//!
//! It is not new with SMaLL-100 either. Supertonic's fp16 sampler shipped at 121.7 MiB, 95% of the
//! guarantee, so this was one model away from breaking already.
//!
//! # One buffer, several descriptors
//!
//! The file stays a **single** `VkBuffer` and a single allocation — nothing about the upload,
//! [`crate::weights::Blob::read_at`] or `Net::CHUNK_BYTES` changes. What is split is the
//! *descriptor*: one set per segment, each pointing at the same buffer with its own
//! `(offset, range)`, and `Net::record` binds the set an op needs before dispatching it.
//!
//! Per-segment `VkBuffer`s would work too, and would sidestep `minStorageBufferOffsetAlignment`
//! entirely, at the price of N allocations, an upload that has to know which buffer each byte
//! belongs to, and N sets of aliasing rules. The alignment is not actually a problem: it is at
//! most 256, a segment base is a multiple of the window stride which is rounded down to it, and
//! the overlap that creates is read-only in every segment.
//!
//! # Overlapping windows, so the split does not depend on the plan
//!
//! The obvious segmentation is greedy over the ops: fill a descriptor until the next op's tensors
//! do not fit, then start another. It gives the fewest segments, and it is wrong here, because
//! [`super::run::Net::rebuild`] installs a **different plan** over the same weights. SMaLL-100's
//! encoder and decoder passes read different subsets of one file, so a plan-derived segmentation
//! would have to be recomputed on every rebuild, and a recomputation that wanted *more* segments
//! than the descriptor pool was allocated for could only fail.
//!
//! So the segments are fixed windows of `range` bytes at a stride of `range / 4`, covering the
//! file. Any byte span shorter than `range - stride`, which is three quarters of the range, is
//! then **entirely inside** at least one window: a span starting at `from` is inside window
//! `from / stride`, which reaches to `(from / stride) * stride + range`. The largest span any op
//! here has is one of SMaLL-100's two logits halves at 65.9 MB, against 100.7 MB of guaranteed
//! headroom.
//!
//! The cost of the overlap is descriptor sets, not memory: ten of them for a 318 MiB file at the
//! guaranteed range, one for the same file on a device reporting 4 GiB. Since it depends only on
//! the file's length, `rebuild` needs to recompute nothing.
//!
//! # What stays invisible
//!
//! [`crate::weights::Tensor::offset`] stays absolute and file-relative, and so does every offset
//! in a [`Plan`]. Nothing in `nets/` knows a segment exists, which is what keeps
//! `nets::reference` — the only oracle for the shaders — and every parity fixture unchanged.
//! Rebasing happens here, at record time, and is a pure function of the segment and the push.
//!
//! [`Plan`]: crate::nets::Plan

use crate::nets::{Kind, Push};
use crate::weights::Tensor;

use super::context::Limits;

/// The `.maml` tensor alignment, from `scripts/ml/maml_convert.py`.
///
/// A stride is rounded down to a multiple of at least this, so that dividing a segment base by 2
/// for the fp16 view and by 4 for the 32-bit word view are both exact.
/// `minStorageBufferOffsetAlignment` may be *finer* than 16 — it is only required to be a power of
/// two — and 2 would not satisfy the word view.
pub(crate) const ALIGNMENT: u64 = 16;

/// A window count past which the device's reported range is not believable.
///
/// At the spec's guaranteed 128 MiB this allows a 32 GiB weights file, which is two orders of
/// magnitude past anything this runtime loads. Hitting it means `max_storage_buffer_range` came
/// back absurdly small, and thousands of descriptor sets would be a worse failure than a message.
pub(crate) const MAX_SEGMENTS: usize = 1024;

/// One descriptor's worth of the weights buffer.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Segment {
    /// Byte offset of the descriptor. A multiple of the device's required alignment.
    pub base: u64,
    /// Bytes the descriptor covers, never more than `max_storage_buffer_range`.
    pub len: u64,
}

/// The weights file as overlapping windows, one descriptor set each.
#[derive(Debug, PartialEq, Eq)]
pub struct Segments {
    pub(crate) segments: Vec<Segment>,
    /// Distance between window bases. Zero when there is only one window.
    pub(crate) stride: u64,
    /// The longest span a window is guaranteed to contain, `range - stride`.
    pub(crate) reach: u64,
}

impl Segments {
    /// The segments, in the order their descriptor sets are allocated.
    pub fn all(&self) -> &[Segment] {
        &self.segments
    }

    /// The window op `step` should be dispatched through, or `None` if it reads no weights.
    ///
    /// An op that reads none keeps whatever set is already bound, which is always a valid one.
    pub fn for_op(
        &self,
        step: usize,
        kind: Kind,
        push: &Push,
        tensors: &[Tensor],
    ) -> Result<Option<usize>, String> {
        let Some((from, to)) = self.span(step, kind, push, tensors)? else {
            return Ok(None);
        };
        if self.stride == 0 {
            return Ok(Some(0));
        }
        let index = usize::try_from(from / self.stride).map_err(|_| "a segment index overflowed")?;
        let Some(segment) = self.segments.get(index) else {
            return Err(format!("step {step} reads at byte {from}, past the last segment"));
        };
        if to > segment.base + segment.len {
            return Err(format!(
                "step {step} ({kind:?}) reads bytes {from}..{to} of the weights, a span of {} \
                 against the {} one descriptor window is guaranteed to hold. Split the tensor in \
                 the converter.",
                to - from,
                self.reach
            ));
        }
        Ok(Some(index))
    }

    /// `push` with its three weights offsets made relative to segment `index`.
    ///
    /// The units differ per field and per kind — `weight` is a 32-bit word index for the int8
    /// AND int4 kinds and an fp16 element index otherwise — which is why the base is divided
    /// rather than subtracted from one normalised offset. A base is a multiple of `ALIGNMENT`,
    /// so both divisions are exact.
    ///
    /// The kinds named below must stay in step with the word-unit arm of [`Kind::weight_reads`],
    /// which is the only place that decides what a `weight` offset counts in.
    pub fn rebase(&self, index: usize, kind: Kind, push: &Push) -> Push {
        rebase_push(self.segments.get(index).map_or(0, |segment| segment.base), kind, push)
    }

    /// The `[from, to)` byte span of the weights one op reads, or `None` if it reads none.
    ///
    /// Each offset is resolved against the tensor table rather than reconstructed from the push's
    /// shape fields, so the extent is exact for every kind without this knowing what any of them
    /// compute. An offset landing in no tensor is a bug in the net module — a push pointing
    /// somewhere the file does not describe — and is reported rather than read on the device.
    fn span(
        &self,
        step: usize,
        kind: Kind,
        push: &Push,
        tensors: &[Tensor],
    ) -> Result<Option<(u64, u64)>, String> {
        let reads = kind.weight_reads(push);
        if reads.is_empty() {
            return Ok(None);
        }
        let mut from = u64::MAX;
        let mut to = 0u64;
        for read in reads {
            let end = tensor_end(read.at, tensors).ok_or_else(|| {
                format!(
                    "step {step} ({kind:?}) reads {} at byte {}, which is inside none of the {} \
                     tensors the file describes",
                    read.field,
                    read.at,
                    tensors.len()
                )
            })?;
            from = from.min(read.at);
            to = to.max(end);
        }
        Ok(Some((from, to)))
    }
}

/// `push` with every weights offset it reads moved down by `base` bytes, a multiple of
/// [`ALIGNMENT`]. Callers must have checked that no read lies below `base`.
pub(crate) fn rebase_push(base: u64, kind: Kind, push: &Push) -> Push {
    if base == 0 {
        return *push;
    }
    let elems = (base / 2) as u32;
    let words = (base / 4) as u32;
    let mut out = *push;
    for read in kind.weight_reads(push) {
        match read.field {
            "weight"
                if matches!(
                    kind,
                    Kind::ConvInt8
                        | Kind::ConvPointInt8
                        | Kind::ConvVecInt8
                        | Kind::ConvVecInt4
                        | Kind::ConvPointInt4
                        | Kind::ConvPointInt4Dot
                        | Kind::ConvQ2K
                        | Kind::ConvVecQ2K
                        | Kind::ConvPointQ2K
                ) =>
            {
                out.weight = push.weight.saturating_sub(words);
            }
            "weight" => out.weight = push.weight.saturating_sub(elems),
            "bias" => out.bias = push.bias.saturating_sub(elems),
            "act_weight" => out.act_weight = push.act_weight.saturating_sub(elems),
            _ => {}
        }
    }
    out
}

/// The `[start, end)` byte extent of the tensor containing byte `at`.
///
/// A linear scan, run once per op per recording over a table of at most a few hundred entries.
fn tensor_extent(at: u64, tensors: &[Tensor]) -> Option<(u64, u64)> {
    tensors.iter().find_map(|tensor| {
        let start = u64::from(tensor.offset);
        let bytes = tensor.dtype.bytes(u64::from(tensor.len));
        (at >= start && at < start + bytes.max(1)).then_some((start, start + bytes))
    })
}

fn tensor_end(at: u64, tensors: &[Tensor]) -> Option<u64> {
    tensor_extent(at, tensors).map(|(_, end)| end)
}

/// The `[lo, hi)` bytes of the data section that `plans`' ops read, whole tensors, with `lo`
/// rounded down to `align`. `(0, 0)` when nothing is read.
pub(crate) fn read_window(
    plans: &[&crate::nets::Plan],
    tensors: &[Tensor],
    align: u64,
) -> Result<(u64, u64), String> {
    let mut lo = u64::MAX;
    let mut hi = 0u64;
    for plan in plans {
        for (step, op) in plan.ops.iter().enumerate() {
            let crate::nets::Op::Dispatch { kind, push, .. } = op else { continue };
            for read in kind.weight_reads(push) {
                let (start, end) = tensor_extent(read.at, tensors).ok_or_else(|| {
                    format!(
                        "step {step} ({kind:?}) reads {} at byte {}, which is inside none of the \
                         {} tensors the file describes",
                        read.field,
                        read.at,
                        tensors.len()
                    )
                })?;
                lo = lo.min(start);
                hi = hi.max(end);
            }
        }
    }
    if hi == 0 {
        return Ok((0, 0));
    }
    Ok((lo - lo % align.max(1), hi))
}

/// The tensors lying wholly inside `[lo, hi)`, with offsets made relative to `lo`.
pub(crate) fn window_tensors(tensors: &[Tensor], lo: u64, hi: u64) -> Vec<Tensor> {
    tensors
        .iter()
        .filter_map(|tensor| {
            let start = u64::from(tensor.offset);
            let end = start + tensor.dtype.bytes(u64::from(tensor.len));
            (start >= lo && end <= hi)
                .then(|| Tensor { offset: (start - lo) as u32, ..*tensor })
        })
        .collect()
}
