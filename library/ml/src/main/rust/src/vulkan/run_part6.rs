impl Net {
    /// Copy the weights blob to device-local memory through the staging buffer, in chunks.
    ///
    /// Its own staging allocation and its own one-shot command buffer, because the permanent
    /// staging buffer is sized for one input and the weights are ten times that. Both are freed
    /// before returning; this happens once per net.
    ///
    /// # Why chunked, and why this small
    ///
    /// A single copy needs a staging buffer the size of the whole blob. For Supertonic's 127 MB
    /// sampler that is 127 MB of `HOST_VISIBLE` memory on top of the 127 MB device-local
    /// destination, at the moment of load — and it defeats the point of streaming the data section
    /// out of the APK, since the peak would be the whole file again just in a different allocation.
    ///
    /// [`Net::CHUNK_BYTES`] instead, one `cmd_copy_buffer` per chunk at the right `dst_offset`.
    /// Two staging buffers alternate, so the file read for chunk N+1 overlaps chunk N's copy:
    /// each side waits only for its own previous copy before being overwritten. That halves
    /// the round trips per chunk — the transfer is bandwidth-bound anyway — and it happens
    /// once per net rather than once per inference.
    fn upload_weights(&self, weights: &dyn Blob) -> Result<(), String> {
        let (lo, hi) = self.window;
        self.upload_with(0, hi - lo, &mut |offset, piece| weights.read_at(lo + offset, piece))
    }

    /// Write `total` bytes at weights-buffer byte `dst`, `fill(offset, piece)` producing each
    /// chunk. See [`Net::upload_weights`].
    fn upload_with(
        &self,
        dst: u64,
        total: u64,
        fill: &mut dyn FnMut(u64, &mut [u8]) -> Result<(), String>,
    ) -> Result<(), String> {
        if total == 0 {
            return Ok(());
        }
        let chunk = total.min(Self::CHUNK_BYTES);
        let staging = [
            Buffer::staging(&self.context, chunk as vk::DeviceSize)?,
            Buffer::staging(&self.context, chunk as vk::DeviceSize)?,
        ];
        let mut buffer = vec![0u8; usize::try_from(chunk).map_err(|_| "a chunk overflowed")?];
        let command_buffers = [self.allocate_command_buffer()?, self.allocate_command_buffer()?];
        // One fence per side, so a side is waited on only when it is about to be reused.
        // SAFETY: plain object creations on this net's device; both are destroyed below on
        // every path, after their work has been waited on.
        let fences = unsafe {
            let first = self
                .context
                .device
                .create_fence(&vk::FenceCreateInfo::default(), None)
                .map_err(|e| format!("create_fence {e:?}"))?;
            let second = self
                .context
                .device
                .create_fence(&vk::FenceCreateInfo::default(), None)
                .map_err(|e| format!("create_fence {e:?}"));
            match second {
                Ok(second) => [first, second],
                Err(e) => {
                    self.context.device.destroy_fence(first, None);
                    return Err(e);
                }
            }
        };
        // SAFETY: both command buffers were just allocated from this device's pool and are
        // freed on every path below; each copy is bounds-checked by the loop, which never asks
        // for more than `chunk` bytes of staging or writes past `total` of the destination.
        // A side's staging buffer is rewritten only after its own fence was waited on, so no
        // copy reads bytes another write is racing.
        unsafe {
            let device = &self.context.device;
            let mut submitted = [false, false];
            let mut written = 0u64;
            let outcome = loop {
                if written >= total {
                    break Ok(());
                }
                let side = (written / chunk) as usize % 2;
                let size = chunk.min(total - written);
                let piece = match buffer.get_mut(..size as usize) {
                    Some(piece) => piece,
                    None => break Err("a chunk is larger than its buffer".to_string()),
                };
                if let Err(e) = fill(written, piece) {
                    break Err(e);
                }
                // This side's previous copy (two chunks ago) may still be reading its staging
                // buffer: wait for it BEFORE overwriting. The file read above overlaps it.
                if submitted[side] {
                    if let Err(e) = device.wait_for_fences(&[fences[side]], true, FENCE_TIMEOUT_NS)
                    {
                        break Err(format!("wait_for_fences {e:?}"));
                    }
                    if let Err(e) = device.reset_fences(&[fences[side]]) {
                        break Err(format!("reset_fences {e:?}"));
                    }
                }
                if let Err(e) = staging[side].write(piece) {
                    break Err(e);
                }
                let copy = || -> Result<(), String> {
                    let command_buffer = command_buffers[side];
                    device
                        .begin_command_buffer(
                            command_buffer,
                            &vk::CommandBufferBeginInfo::default()
                                .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT),
                        )
                        .map_err(|e| format!("begin_command_buffer {e:?}"))?;
                    let region = vk::BufferCopy::default()
                        .dst_offset((dst + written) as vk::DeviceSize)
                        .size(size as vk::DeviceSize);
                    device.cmd_copy_buffer(
                        command_buffer,
                        staging[side].buffer,
                        self.weights.buffer,
                        std::slice::from_ref(&region),
                    );
                    device
                        .end_command_buffer(command_buffer)
                        .map_err(|e| format!("end_command_buffer {e:?}"))?;
                    // No wait: the next chunk's file read overlaps this copy, and the fence
                    // is waited on above before this side is reused, two chunks later.
                    let guard = self.context.lock_queue();
                    let submitted = device.queue_submit(
                        self.context.queue,
                        &[vk::SubmitInfo::default()
                            .command_buffers(std::slice::from_ref(&command_buffer))],
                        fences[side],
                    );
                    drop(guard);
                    submitted.map_err(|e| format!("queue_submit {e:?}"))
                };
                if let Err(e) = copy() {
                    break Err(e);
                }
                submitted[side] = true;
                written += size;
            };
            // Both sides may still hold in-flight copies; the staging buffers must outlive them.
            for (side, fence) in fences.iter().enumerate() {
                if submitted[side] {
                    let _ = device.wait_for_fences(std::slice::from_ref(fence), true, FENCE_TIMEOUT_NS);
                }
                device.destroy_fence(*fence, None);
            }
            device.free_command_buffers(self.command_pool, &command_buffers);
            // `staging` drops here, after every fence has been waited on.
            outcome
        }
    }

    /// `push` rebased into the uploaded window, or an error if it reads outside it.
    fn windowed(
        &self,
        step: usize,
        kind: crate::nets::Kind,
        push: &crate::nets::Push,
    ) -> Result<crate::nets::Push, String> {
        let push = &self.widen_push(kind, push);
        let (lo, hi) = self.window;
        let reads = kind.weight_reads(push);
        for read in &reads {
            let inside = read.at >= lo
                && self.tensors.iter().any(|tensor| {
                    let start = lo + u64::from(tensor.offset);
                    let end = start + tensor.dtype.bytes(u64::from(tensor.len));
                    read.at >= start && read.at < end.max(start + 1)
                });
            if !inside {
                return Err(format!(
                    "step {step} ({kind:?}) reads {} at byte {}, outside the weights window \
                     {lo}..{hi} this net uploaded. Build it with Net::covering or Net::whole.",
                    read.field, read.at
                ));
            }
        }
        Ok(super::segment::rebase_push(lo, kind, push))
    }

    /// The `[lo, hi)` data-section bytes this net uploaded.
    pub fn weights_window(&self) -> (u64, u64) {
        self.window
    }

    /// Whether a recording of `plan` is kept for a later [`Net::rebuild`] to swap in.
    #[cfg(test)]
    pub(crate) fn keeps_recording(&self, plan: &Plan) -> bool {
        self.recorded.iter().any(|(recorded, _)| recorded == plan)
    }

    fn allocate_command_buffer(&self) -> Result<vk::CommandBuffer, String> {
        let info = vk::CommandBufferAllocateInfo::default()
            .command_pool(self.command_pool)
            .level(vk::CommandBufferLevel::PRIMARY)
            .command_buffer_count(1);
        // SAFETY: an allocation from this net's own pool, which no other thread can reach.
        let buffers = unsafe { self.context.device.allocate_command_buffers(&info) }
            .map_err(|e| format!("allocate_command_buffers {e:?}"))?;
        buffers
            .first()
            .copied()
            .ok_or_else(|| "allocate_command_buffers returned nothing".into())
    }

    fn create_fence(&self) -> Result<vk::Fence, String> {
        // SAFETY: a plain object creation on a device this net holds an `Arc` to.
        unsafe {
            self.context
                .device
                .create_fence(&vk::FenceCreateInfo::default(), None)
                .map_err(|e| format!("create_fence {e:?}"))
        }
    }

    /// Size the timestamp query pool to this plan, or leave it uncreated.
    ///
    /// Called before every `record`: a rebuild may have changed the dispatch
    /// count, and a pool sized for another plan would overrun or waste. When
    /// timestamps are disabled — or the device reports no timestamp bits —
    /// any existing pool is destroyed and the recording stays exactly what it
    /// always was. Idempotent for repeated records of one plan.
    fn reset_query_pool(&mut self) -> Result<(), String> {
        // SAFETY: plain object creation/destruction on this net's device, with
        // nothing in flight: `new` has submitted nothing yet, and `rebuild`
        // waited for idle before reaching here.
        unsafe {
            let device = &self.context.device;
            if self.query_pool != vk::QueryPool::null() {
                device.destroy_query_pool(self.query_pool, None);
                self.query_pool = vk::QueryPool::null();
                self.query_count = 0;
            }
            if !timestamps_enabled() {
                return Ok(());
            }
            if self.context.limits.timestamp_valid_bits == 0 {
                timing!(
                    "timestamps requested but the compute family reports no timestamp bits"
                );
                return Ok(());
            }
            let dispatches = self
                .plan
                .ops
                .iter()
                .filter(|op| matches!(op, Op::Dispatch { .. }))
                .count();
            let count = match u32::try_from(dispatches) {
                Ok(n) => n.checked_mul(2).ok_or_else(|| "a query count overflowed".to_string())?,
                Err(_) => return Err("a plan with more dispatches than u32".to_string()),
            };
            if count == 0 {
                return Ok(());
            }
            let info = vk::QueryPoolCreateInfo::default()
                .query_type(vk::QueryType::TIMESTAMP)
                .query_count(count);
            self.query_pool =
                device.create_query_pool(&info, None).map_err(|e| format!("create_query_pool {e:?}"))?;
            self.query_count = count;
            Ok(())
        }
    }
}
