/// One int8 tensor's fp16 copy, appended to the weights buffer past the uploaded window.
///
/// See [`crate::nets::Builder::widen_int8`]. Offsets are file-data-section bytes; a copy's
/// `dst` lies past the file window, as if the file had been longer.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Widened {
    /// Where the int8 tensor starts.
    src: u64,
    /// Its length in bytes, which is its element count.
    bytes: u64,
    /// Where its fp16 copy starts.
    dst: u64,
}

/// The copies `widen` asks for that lie in `window`, packed from window byte `base`.
///
/// A listed offset that is not inside a whole int8 tensor of the window, or whose copy could not
/// stay 16-byte aligned, is skipped: its ops keep the int8 path.
fn widened_layout(widen: &[u32], tensors: &[Tensor], window: (u64, u64), base: u64) -> Vec<Widened> {
    let (lo, hi) = window;
    let mut out: Vec<Widened> = Vec::new();
    let mut next = lo + base;
    for &word in widen {
        let at = u64::from(word) * 4;
        let Some(tensor) = tensors.iter().find(|t| {
            let start = u64::from(t.offset);
            at >= start && at < start + t.dtype.bytes(u64::from(t.len))
        }) else {
            continue;
        };
        let src = u64::from(tensor.offset);
        let bytes = u64::from(tensor.len);
        if !matches!(tensor.dtype, Dtype::I8)
            || src < lo
            || src + bytes > hi
            || src % 16 != 0
            || bytes % 8 != 0
            || out.iter().any(|w| w.src == src)
        {
            continue;
        }
        out.push(Widened { src, bytes, dst: next });
        next += bytes * 2;
    }
    out
}

/// `out` filled with the fp16 copies' bytes from file-relative byte `start`.
fn fill_widened(
    widened: &[Widened],
    weights: &dyn Blob,
    start: u64,
    out: &mut [u8],
    scratch: &mut Vec<u8>,
) -> Result<(), String> {
    static HALVES: OnceLock<[u16; 256]> = OnceLock::new();
    let halves = HALVES
        .get_or_init(|| std::array::from_fn(|b| preprocess::f32_to_f16(f32::from(b as u8 as i8))));
    let end = start + out.len() as u64;
    for w in widened {
        let (from, to) = (start.max(w.dst), end.min(w.dst + 2 * w.bytes));
        if from >= to {
            continue;
        }
        let count = usize::try_from((to - from) / 2).map_err(|_| "a widened span overflowed")?;
        scratch.resize(count, 0);
        weights.read_at(w.src + (from - w.dst) / 2, scratch)?;
        let at = usize::try_from(from - start).map_err(|_| "a widened span overflowed")?;
        let target = out.get_mut(at..at + 2 * count).ok_or("a widened span is outside its chunk")?;
        for (pair, &byte) in target.chunks_exact_mut(2).zip(scratch.iter()) {
            let bits = halves.get(usize::from(byte)).copied().unwrap_or(0);
            pair.copy_from_slice(&bits.to_le_bytes());
        }
    }
    Ok(())
}

impl Net {
    /// The window's tensors plus one fp16 entry per widened copy, offsets relative to `lo`.
    fn widened_tensors(
        mut tensors: Vec<Tensor>,
        widened: &[Widened],
        lo: u64,
    ) -> Result<Vec<Tensor>, String> {
        for w in widened {
            let len = u32::try_from(w.bytes).map_err(|_| "a widened tensor overflowed")?;
            tensors.push(Tensor {
                rank: 1,
                dims: [len, 0, 0, 0],
                offset: u32::try_from(w.dst - lo).map_err(|_| "a widened offset overflowed")?,
                len,
                dtype: Dtype::F16,
            });
        }
        Ok(tensors)
    }

    /// Upload the fp16 copies after the window, streamed in chunks like the window itself.
    fn upload_widened(&self, weights: &dyn Blob) -> Result<(), String> {
        let (Some(first), Some(last)) = (self.widened.first(), self.widened.last()) else {
            return Ok(());
        };
        let total = last.dst + 2 * last.bytes - first.dst;
        let mut scratch = Vec::new();
        self.upload_with(first.dst - self.window.0, total, &mut |offset, piece| {
            fill_widened(&self.widened, weights, first.dst + offset, piece, &mut scratch)
        })
    }

    /// A widened-path int8 GEMM (`in1` 3 or 4) pointed at its fp16 copy, or moved back to the
    /// int8 path (`in1` 0 or 1) when its weights were not widened or the copy is not reachable
    /// through one descriptor window together with the scale and bias.
    fn widen_push(&self, kind: crate::nets::Kind, push: &crate::nets::Push) -> crate::nets::Push {
        if kind != crate::nets::Kind::ConvPointInt8 || !(push.in1 == 3 || push.in1 == 4) {
            return *push;
        }
        let fallback = crate::nets::Push { in1: push.in1 - 3, ..*push };
        let at = u64::from(push.weight) * 4;
        let Some(w) = self.widened.iter().find(|w| at >= w.src && at < w.src + w.bytes) else {
            return fallback;
        };
        let dst = w.dst + 2 * (at - w.src);
        let Ok(word) = u32::try_from(dst / 4) else {
            return fallback;
        };
        if dst % 16 != 0 {
            return fallback;
        }
        let widened = crate::nets::Push { weight: word, ..*push };
        let rebased = super::segment::rebase_push(self.window.0, kind, &widened);
        match self.segments.for_op(0, kind, &rebased, &self.tensors) {
            Ok(_) => widened,
            Err(_) => fallback,
        }
    }

    /// Bytes of fp16 copies this net holds, for the load log.
    fn widened_bytes(&self) -> u64 {
        self.widened.iter().map(|w| 2 * w.bytes).sum()
    }
}

impl Net {
    /// Row-mode reductions (one 64-lane workgroup per row, shared memory) recorded per command
    /// buffer, and the rows each covers: the row-mode cap, `ROW_REDUCE_MAX_ROWS` in `nets`.
    const WARM_DISPATCHES: u32 = 96;
    const WARM_ROWS: u32 = 256;

    /// Once per process, record (never submit) row-mode reductions at the largest size into the
    /// transfer buffer.
    ///
    /// On Mali the driver's `vkCmdDispatch` for a shared-memory shader costs ~0.5-1 ms whenever
    /// the dispatch needs more of its internal memory than any recorded before, and that memory
    /// is kept process-wide. Without this, every Supertonic sentence longer than all earlier
    /// ones paid 50-90 ms re-recording the sampler's 72 row-mode layer norms. Only a net with a
    /// row-mode layer norm triggers it, so nets that never record one skip the ~47 ms.
    fn warm_row_dispatches(&self) -> Result<(), String> {
        static WARMED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
        let row_layer_norm = self.plan.ops.iter().any(|op| {
            matches!(op, Op::Dispatch { kind: crate::nets::Kind::LayerNorm, push, .. } if push.kw == 1)
        });
        if !row_layer_norm || WARMED.swap(true, std::sync::atomic::Ordering::SeqCst) {
            return Ok(());
        }
        let started = std::time::Instant::now();
        let device = &self.context.device;
        let set = self.pipelines.descriptor_sets.first().copied().ok_or("no descriptor set")?;
        // Zero rows: were this ever submitted, every workgroup would return at once.
        let push = crate::nets::Push { kw: 1, ..crate::nets::Push::default() };
        // SAFETY: `transfer` is this net's own idle buffer; it is recorded and ended here and
        // never submitted, and the next transfer re-begins (resets) it.
        unsafe {
            device
                .begin_command_buffer(self.transfer, &vk::CommandBufferBeginInfo::default())
                .map_err(|e| format!("begin: {e:?}"))?;
            device.cmd_bind_pipeline(
                self.transfer,
                vk::PipelineBindPoint::COMPUTE,
                self.pipelines.for_kind(crate::nets::Kind::LayerNorm),
            );
            device.cmd_bind_descriptor_sets(
                self.transfer,
                vk::PipelineBindPoint::COMPUTE,
                self.pipelines.layout(),
                0,
                &[set],
                &[],
            );
            device.cmd_push_constants(
                self.transfer,
                self.pipelines.layout(),
                vk::ShaderStageFlags::COMPUTE,
                0,
                push_bytes(&push),
            );
            for _ in 0..Self::WARM_DISPATCHES {
                device.cmd_dispatch(self.transfer, Self::WARM_ROWS, 1, 1);
            }
            device.end_command_buffer(self.transfer).map_err(|e| format!("end: {e:?}"))?;
            // Hand the grown memory back so other command buffers can take it.
            device
                .reset_command_buffer(self.transfer, vk::CommandBufferResetFlags::RELEASE_RESOURCES)
                .map_err(|e| format!("reset: {e:?}"))?;
        }
        timing!("row-dispatch warm-up {:.1} ms", started.elapsed().as_secs_f64() * 1e3);
        Ok(())
    }
}
