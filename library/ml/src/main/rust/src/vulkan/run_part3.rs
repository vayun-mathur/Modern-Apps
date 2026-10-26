impl Net {

    /// Upload `input_scratch`, submit the recorded buffer, and read `output_scratch` back.
    ///
    /// Factored out of the two `infer` variants because it is the whole of the unsafe,
    /// order-sensitive part: everything about poisoning, the fence and the queue lock is
    /// here once rather than twice.
    fn submit(&mut self) -> Result<(), String> {
        if self.poisoned {
            return Err("this network is unusable after an earlier failure".into());
        }
        // SAFETY: the scratch buffer is exactly the fp16 elements the recorded copy
        // regions move, and `u16` has no padding or invalid bit patterns.
        let bytes = unsafe {
            std::slice::from_raw_parts(
                self.input_scratch.as_ptr().cast::<u8>(),
                std::mem::size_of_val(self.input_scratch.as_slice()),
            )
        };
        self.staging.write(bytes)?;

        let device = &self.context.device;
        let buffers = [self.command_buffer];
        let submit = vk::SubmitInfo::default().command_buffers(&buffers);
        // SAFETY: the fence is reset before the submit and waited on after it, so the
        // command buffer is never resubmitted while pending and the staging buffer is
        // never read while the GPU is writing it. `poisoned` is what keeps that true when
        // the wait times out. The queue and the pool are shared process-wide, hence the
        // lock around the submit; the fence wait is deliberately outside it.
        unsafe {
            device.reset_fences(&[self.fence]).map_err(|e| format!("reset_fences {e:?}"))?;
            // Poison first: from here until the wait returns, a submission may be pending,
            // and every path out of that state other than a successful wait is one this
            // net cannot recover from.
            self.poisoned = true;
            let guard = self.context.lock_queue();
            let submitted = device
                .queue_submit(self.context.queue, std::slice::from_ref(&submit), self.fence)
                .map_err(|e| format!("queue_submit {e:?}"));
            drop(guard);
            submitted?;
            device
                .wait_for_fences(&[self.fence], true, FENCE_TIMEOUT_NS)
                .map_err(|e| format!("wait_for_fences {e:?}"))?;
            self.poisoned = false;
        }

        self.staging.read_f16(&mut self.output_scratch)
    }

    /// The mask's dimensions, which is what the Kotlin wrapper reports to its caller.
    /// Copy the current arena into `into`, which must be at least as large.
    ///
    /// # Safety
    ///
    /// `into` must be a live device-local buffer of at least `self.arena.size` bytes, and no
    /// work may be in flight on this net.
    unsafe fn copy_arena(&mut self, into: &Buffer) -> Result<(), String> {
        if self.arena.size == 0 {
            return Ok(());
        }
        let device = &self.context.device;
        // SAFETY: the caller guarantees `into` is live and large enough, and the command buffer
        // is recorded and waited on entirely within this call.
        unsafe {
            device
                .begin_command_buffer(self.transfer, &vk::CommandBufferBeginInfo::default())
                .map_err(|e| format!("begin: {e:?}"))?;
            let region = vk::BufferCopy::default().src_offset(0).dst_offset(0).size(self.arena.size);
            device.cmd_copy_buffer(
                self.transfer,
                self.arena.buffer,
                into.buffer,
                std::slice::from_ref(&region),
            );
            device.end_command_buffer(self.transfer).map_err(|e| format!("end: {e:?}"))?;
            self.run_once()
        }
    }

    /// Copy arena ranges out, as raw fp16 bytes, concatenated in the order given.
    ///
    /// Its own submit rather than a hook in the inference path: this runs twice in a process at
    /// most - once to produce a cache, once to check one - so the simplest correct thing wins
    /// over anything folded into the hot path.
    fn read_arena(&mut self, ranges: &[(u32, u32)]) -> Result<Vec<u8>, String> {
        // Chunked to the staging buffer, which is half a megabyte against caches that run to
        // tens. One submit per chunk: this runs twice in a process, so the cost of the extra
        // submits is irrelevant beside not needing a second large allocation.
        let mut out = Vec::new();
        for batch in batches(ranges, self.staging.size) {
            out.extend_from_slice(&self.read_batch(&batch)?);
        }
        Ok(out)
    }

    /// One staging-sized batch of arena ranges, as raw fp16 bytes.
    fn read_batch(&mut self, ranges: &[(u32, u32)]) -> Result<Vec<u8>, String> {
        let total: u64 = ranges.iter().map(|&(_, elems)| u64::from(elems) * 2).sum();
        if total == 0 {
            return Ok(Vec::new());
        }
        let device = &self.context.device;
        // SAFETY: one command buffer, recorded and submitted here and waited on before return,
        // so nothing else observes it and the fence outlives the work.
        unsafe {
            device
                .begin_command_buffer(self.transfer, &vk::CommandBufferBeginInfo::default())
                .map_err(|e| format!("begin: {e:?}"))?;
            let mut at = 0u64;
            for &(from, elems) in ranges {
                let region = vk::BufferCopy::default()
                    .src_offset(u64::from(from) * 2)
                    .dst_offset(at)
                    .size(u64::from(elems) * 2);
                device.cmd_copy_buffer(
                    self.transfer,
                    self.arena.buffer,
                    self.staging.buffer,
                    std::slice::from_ref(&region),
                );
                at += u64::from(elems) * 2;
            }
            device.end_command_buffer(self.transfer).map_err(|e| format!("end: {e:?}"))?;
            self.run_once()?;
        }
        let mut halves = vec![0u16; (total / 2) as usize];
        self.staging.read_f16(&mut halves)?;
        let mut out = Vec::with_capacity(total as usize);
        for half in halves {
            out.extend_from_slice(&half.to_le_bytes());
        }
        Ok(out)
    }

    /// Copy `bytes` into arena ranges, the inverse of [`Net::read_arena`].
    fn write_arena(&mut self, ranges: &[(u32, u32)], bytes: &[u8]) -> Result<(), String> {
        let mut at = 0usize;
        for batch in batches(ranges, self.staging.size) {
            let size: usize = batch.iter().map(|&(_, e)| e as usize * 2).sum();
            let Some(slice) = bytes.get(at..at + size) else {
                return Err(format!("a cache shorter than its {} ranges", ranges.len()));
            };
            self.write_batch(&batch, slice)?;
            at += size;
        }
        Ok(())
    }

    /// One staging-sized batch, the inverse of [`Net::read_batch`].
    fn write_batch(&mut self, ranges: &[(u32, u32)], bytes: &[u8]) -> Result<(), String> {
        self.staging.write(bytes)?;
        let device = &self.context.device;
        // SAFETY: as `read_arena`.
        unsafe {
            device
                .begin_command_buffer(self.transfer, &vk::CommandBufferBeginInfo::default())
                .map_err(|e| format!("begin: {e:?}"))?;
            let mut at = 0u64;
            for &(to, elems) in ranges {
                let region = vk::BufferCopy::default()
                    .src_offset(at)
                    .dst_offset(u64::from(to) * 2)
                    .size(u64::from(elems) * 2);
                device.cmd_copy_buffer(
                    self.transfer,
                    self.staging.buffer,
                    self.arena.buffer,
                    std::slice::from_ref(&region),
                );
                at += u64::from(elems) * 2;
            }
            device.end_command_buffer(self.transfer).map_err(|e| format!("end: {e:?}"))?;
            self.run_once()
        }
    }

    /// Submit the one-shot transfer buffer and wait for it.
    ///
    /// The transfer helpers above record into `transfer`, never into `command_buffer`, so the
    /// plan's recording survives and nothing is re-recorded: writing persistent tensors (or
    /// copying the arena on a growth) costs a submit, not a ~400-dispatch record. The plan's
    /// first barrier, after its input copies, names TRANSFER_WRITE as a source, so it orders
    /// these writes before the plan's reads.
    ///
    /// # Safety
    ///
    /// `transfer` must be recorded and ended, and nothing may be in flight on this net.
    unsafe fn run_once(&mut self) -> Result<(), String> {
        let device = &self.context.device;
        let submit =
            vk::SubmitInfo::default().command_buffers(std::slice::from_ref(&self.transfer));
        unsafe {
            device.reset_fences(&[self.fence]).map_err(|e| format!("reset: {e:?}"))?;
            // The queue is shared with every other net in the process, so the lock is not
            // optional - see `Context::lock_queue`.
            let guard = self.context.lock_queue();
            let submitted = device
                .queue_submit(self.context.queue, std::slice::from_ref(&submit), self.fence)
                .map_err(|e| format!("queue_submit {e:?}"));
            drop(guard);
            submitted?;
            device
                .wait_for_fences(&[self.fence], true, FENCE_TIMEOUT_NS)
                .map_err(|e| format!("wait_for_fences {e:?}"))?;
        }
        Ok(())
    }

    /// Bytes a second, measured by copying the arena to a scratch buffer of the same size.
    ///
    /// # What this settles
    ///
    /// `vkCmdCopyBuffer` is the simplest thing a GPU can do with memory: no unpacking, no
    /// arithmetic, no descriptors. Whatever rate it reaches is the ceiling every kernel here is
    /// working under.
    ///
    /// The decode step reads 1.32 GB and takes 270 ms, which is 4.9 GB/s. If a plain copy also
    /// sits near that, the shaders are already at the memory system's limit and no amount of
    /// saved arithmetic - integer dot products, fewer dispatches, better occupancy - can help.
    /// If the copy is several times faster, the gap is arithmetic and worth chasing.
    ///
    /// Three passes, best kept: the first pays for the allocation and any first-touch cost.
    pub fn copy_bandwidth(&mut self) -> Result<f64, String> {
        let scratch = Buffer::device_local(&self.context, self.arena.size)?;
        let mut best = f64::MAX;
        for _ in 0..3 {
            let started = std::time::Instant::now();
            // SAFETY: `scratch` is live, exactly the arena's size, and nothing is in flight -
            // `copy_arena` waits on its own fence before returning.
            unsafe { self.copy_arena(&scratch)? };
            best = best.min(started.elapsed().as_secs_f64());
        }
        // Read and written, so twice the buffer.
        Ok((self.arena.size as f64 * 2.0) / best)
    }

    /// The first `positions` of every pinned tensor, as raw fp16 bytes.
    ///
    /// # What this is for
    ///
    /// The system block and tool declarations are the same ~1,100 positions on every device and
    /// every launch, and prefilling them costs 14 seconds on a phone. They are also the same
    /// *numbers*: the tokens do not change, so neither do the keys and values. Computing them
    /// once here and shipping the result means no device ever computes them again.
    ///
    /// The layout is per-tensor and in plan order - `positions * width` elements from the start
    /// of each - so it does not depend on the arena's offsets and survives a different cache
    /// tier on the far side. It is not bit-identical across GPUs, which does not matter: the
    /// difference is fp16 rounding, well inside the quantisation noise the weights already have.
    pub fn export_pinned(&mut self, tensors: usize, positions: u32) -> Result<Vec<u8>, String> {
        let mut wanted = Vec::new();
        // `tensors` rather than all of them: a plan's pinned list is whatever survives between
        // submits, and for Gemma that is the thirty KV caches *and* the soft-token buffers the
        // multimodal path keeps. Only the caches are a function of the prompt prefix; the rest
        // belong to whatever image or clip was last seen and mean nothing to another device.
        let Some(caches) = self.plan.pinned.get(..tensors) else {
            return Err(format!("{tensors} pinned of {}", self.plan.pinned.len()));
        };
        for cache in caches {
            let width = cache.shape.w;
            if positions > cache.shape.c {
                return Err(format!("{positions} positions of a {} cache", cache.shape.c));
            }
            wanted.push((cache.at, positions * width));
        }
        self.read_arena(&wanted)
    }

    /// Write `bytes` back over the first `positions` of every pinned tensor.
    ///
    /// The inverse of [`Net::export_pinned`], and it checks the length rather than trusting it -
    /// a file from a different model or a different position count would otherwise be written
    /// into the caches and answered from.
    pub fn import_pinned(
        &mut self,
        tensors: usize,
        positions: u32,
        bytes: &[u8],
    ) -> Result<(), String> {
        let mut wanted = Vec::new();
        let mut expect = 0usize;
        let Some(caches) = self.plan.pinned.get(..tensors) else {
            return Err(format!("{tensors} pinned of {}", self.plan.pinned.len()));
        };
        for cache in caches {
            if positions > cache.shape.c {
                return Err(format!("{positions} positions of a {} cache", cache.shape.c));
            }
            let elems = positions * cache.shape.w;
            wanted.push((cache.at, elems));
            expect += elems as usize * 2;
        }
        if bytes.len() != expect {
            return Err(format!("a cache of {} bytes, not {expect}", bytes.len()));
        }
        self.write_arena(&wanted, bytes)
    }

    pub fn output_size(&self) -> Result<(u32, u32), String> {
        let output = self.plan.output()?;
        Ok((output.shape.w, output.shape.h))
    }

    /// Every output binding, so a multi-output caller can size and shape the maps
    /// [`Net::infer_letterboxed`] returns without rebuilding the plan.
    pub fn output_shapes(&self) -> &[crate::nets::Binding] {
        &self.plan.outputs
    }
}

/// Total elements across `bindings`, which is how much staging space one direction needs.
fn binding_elems(bindings: &[crate::nets::Binding]) -> usize {
    bindings.iter().map(|b| b.shape.len() as usize).sum()
}

/// Long enough that a slow first dispatch on a cold driver is not mistaken for a hang,
/// short enough that a genuinely hung GPU does not block a UI thread forever. `:camera`
/// runs this on a dedicated executor, `:photos` on its own thread, so neither blocks the
/// main thread even at the limit.
///
/// # Raised from five seconds
///
/// Five was chosen for a desktop, where any submit is milliseconds and a slow one means a hung
/// GPU. A phone is not that: a legitimate prefill of 64 positions streams 1.30 GB of weights and
/// takes seconds, and the old bound turned that into `wait_for_fences TIMEOUT` - which poisons
/// the net, so the retry fails too and the user sees two dead turns rather than a slow one.
///
/// Twenty still bounds a genuine hang; it just no longer calls slow hardware broken.
const FENCE_TIMEOUT_NS: u64 = 60_000_000_000;

fn push_bytes(push: &crate::nets::Push) -> &[u8] {
    // SAFETY: `Push` is `repr(C)` and entirely `u32`, so it has no padding and no
    // uninitialised bytes; it is read as exactly its own size.
    unsafe {
        std::slice::from_raw_parts(
            (push as *const crate::nets::Push).cast::<u8>(),
            std::mem::size_of::<crate::nets::Push>(),
        )
    }
}

impl Drop for Net {
    fn drop(&mut self) {
        // SAFETY: `vkDeviceWaitIdle` returns only once nothing on the device is pending, so
        // after it everything below is safe to destroy — including the case where `infer`
        // timed out and left a submission in flight, which is why `poisoned` only has to
        // block further submits and not the teardown.
        //
        // The result is ignored because the only ways it fails are device loss and host
        // OOM. After a lost device the spec explicitly permits destroying objects, and a
        // host OOM here means the process is about to die anyway; in both cases leaking
        // every handle instead would be worse.
        //
        // The lock is held across the wait because `vkDeviceWaitIdle` needs every queue on
        // the device to itself. Destroying the pool frees the command buffer with it, and needs
        // no lock because the pool is this net's alone. The three `Buffer` fields free
        // themselves afterwards through their own Drop, which runs after this body.
        unsafe {
            let device = &self.context.device;
            let guard = self.context.lock_queue();
            let _ = device.device_wait_idle();
            drop(guard);
            device.destroy_fence(self.fence, None);
            device.destroy_command_pool(self.command_pool, None);
            if self.query_pool != vk::QueryPool::null() {
                device.destroy_query_pool(self.query_pool, None);
            }
            self.pipelines.destroy(device);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The GLSL `Params` block is written by hand, so the two layouts agree only by inspection.
    /// This is the part of that agreement a compiler can hold: `std430` packs a struct of `uint`s
    /// end to end, so the Rust side must be `u32`s with no padding and nothing else.
    #[test]
    fn the_step_params_block_matches_the_shader_layout() {
        assert_eq!(std::mem::size_of::<StepParams>(), 2 * 4, "two u32 fields, no padding");
        assert_eq!(std::mem::align_of::<StepParams>(), 4);
        assert_eq!(StepParams::default().as_bytes().len(), std::mem::size_of::<StepParams>());
        assert!(
            (std::mem::size_of::<StepParams>() as vk::DeviceSize) <= StepParams::BYTES,
            "the allocation must cover the struct",
        );
    }

    /// Field order is the whole contract with the shader, and a reorder is invisible to the type
    /// system. Distinct values placed through the struct must land at distinct known offsets.
    #[test]
    fn the_step_params_fields_are_in_the_declared_order() {
        let params = StepParams { prefix: 0x1111_1111, window_start: 0x2222_2222 };
        let bytes = params.as_bytes();
        assert_eq!(bytes.get(0..4), Some(&0x1111_1111u32.to_ne_bytes()[..]), "prefix first");
        assert_eq!(bytes.get(4..8), Some(&0x2222_2222u32.to_ne_bytes()[..]), "window_start second");
    }
}

/// Split `ranges` so each batch fits `budget` bytes, splitting a long range across batches.
///
/// A single KV cache row set is 16,384 positions of 512 bytes, far past any staging buffer, so
/// splitting *within* a range is the case that matters rather than merely between them.
fn batches(ranges: &[(u32, u32)], budget: u64) -> Vec<Vec<(u32, u32)>> {
    let per = (budget / 2).max(1) as u32;
    let mut out: Vec<Vec<(u32, u32)>> = Vec::new();
    let mut current: Vec<(u32, u32)> = Vec::new();
    let mut room = per;
    for &(at, elems) in ranges {
        let mut done = 0u32;
        while done < elems {
            if room == 0 {
                out.push(std::mem::take(&mut current));
                room = per;
            }
            let take = room.min(elems - done);
            current.push((at + done, take));
            done += take;
            room -= take;
        }
    }
    if !current.is_empty() {
        out.push(current);
    }
    out
}

