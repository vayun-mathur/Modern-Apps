impl Net {

    /// Record the whole plan: input copy, every op with a barrier between, output copy.
    fn record(&self) -> Result<(), String> {
        let device = &self.context.device;
        let buffer = self.command_buffer;
        let emit_staged = std::time::Instant::now();
        // SAFETY: `buffer` is a primary command buffer from this device's pool, not
        // currently pending, and every handle referenced below outlives it (they are all
        // fields of `self`, dropped after it in `Drop`).
        unsafe {
            device
                .begin_command_buffer(buffer, &vk::CommandBufferBeginInfo::default())
                .map_err(|e| format!("begin_command_buffer {e:?}"))?;

            // The weights were written by `upload_weights`, in a different submission. A
            // fence wait between submissions orders them but is not a memory dependency, so
            // making that TRANSFER_WRITE visible to every SHADER_READ below needs a real
            // barrier. Recorded once at the top rather than folded into `barrier`, because
            // the weights buffer is never written again.
            let weights_visible: Vec<_> = self
                .weights
                .iter()
                .map(|block| {
                    vk::BufferMemoryBarrier::default()
                        .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                        .dst_access_mask(vk::AccessFlags::SHADER_READ)
                        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                        .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                        .buffer(block.buffer)
                        .offset(0)
                        .size(vk::WHOLE_SIZE)
                })
                .collect();
            device.cmd_pipeline_barrier(
                buffer,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &[],
                &weights_visible,
                &[],
            );
            // Timestamps start clean: a query pool left over from an earlier
            // recording would otherwise report a previous submit's times.
            // A null pool is the disabled path, and nothing below is recorded.
            let stamps = self.query_pool != vk::QueryPool::null();
            if stamps {
                device.cmd_reset_query_pool(buffer, self.query_pool, 0, self.query_count);
            }

            // One copy per input, packed end to end in the staging buffer in declaration
            // order. Both shipping nets have exactly one; SCRFD's nine outputs come back
            // the same way below.
            let mut staged = 0u64;
            for input in &self.plan.inputs {
                let bytes = (input.shape.len() as vk::DeviceSize) * 2;
                let region = vk::BufferCopy::default()
                    .src_offset(staged)
                    .dst_offset((input.at as vk::DeviceSize) * 2)
                    .size(bytes);
                device.cmd_copy_buffer(
                    buffer,
                    self.staging.buffer,
                    self.arena.buffer,
                    std::slice::from_ref(&region),
                );
                staged += bytes;
            }
            self.barrier(buffer);

            // `MODELRUNNER_BARRIER=scheduled`: only the barriers `nets::schedule` proves
            // necessary, instead of one after every op. Debug builds only: it measured no faster
            // and produces NaN in Supertonic's sampler, so a release build must not honour it.
            let scheduled = cfg!(debug_assertions)
                && crate::knobs::get("barrier").as_deref() == Some("scheduled");
            let schedule = scheduled.then(|| crate::nets::schedule::schedule(&self.plan));
            if let Some(schedule) = &schedule {
                crate::timing!(
                    "scheduled barriers: {} of {} ops ({:.0}%)",
                    schedule.barriers(),
                    schedule.steps.len(),
                    100.0 * schedule.barriers() as f64 / schedule.steps.len().max(1) as f64
                );
            }
            let mut stamp_next = 0u32;

            // MODELRUNNER_TIMING only: where record's CPU time goes, per phase and per kind.
            let clock = crate::timing::enabled();
            let mut lap = std::time::Instant::now();
            // bind pipeline, offsets, descriptors + push, dispatch, barrier.
            let mut phases = [0f64; 5];
            // (kind, total ms, first bind ms, binds)
            let mut binds: Vec<(crate::nets::Kind, f64, f64, u32)> = Vec::new();
            let mut slowest = (0f64, 0usize, None, 0u32);
            macro_rules! lap {
                ($slot:expr) => {
                    if clock {
                        let now = std::time::Instant::now();
                        phases[$slot] += (now - lap).as_secs_f64() * 1e3;
                        lap = now;
                    }
                };
            }
            let mut stamp_next = 0u32;
            // Writes since the last emitted barrier, as a byte-range union, for the
            // `scheduled` path. `None` is clean (nothing pending); `(0, u64::MAX)` is
            // the unbounded marker. See `dirty_union`.
            let mut dirty: Option<(u64, u64)> = None;
            for (step, op) in self.plan.ops.iter().enumerate() {
                // `scheduled` barriers go BEFORE the op that needs them: `steps[step]`
                // says whether anything written since the last barrier conflicts with
                // this op, so the barrier precedes this op's dispatch, not follows the
                // previous one. Emitting it after (like the default path's per-op
                // barrier) would order the wrong edge.
                if let Some(needed) = schedule.as_ref().and_then(|s| s.steps.get(step).copied())
                {
                    if needed.barrier {
                        if needed.transfer {
                            self.barrier(buffer);
                        } else {
                            match dirty {
                                Some((0, u64::MAX)) | None => self.barrier(buffer),
                                Some((lo, hi)) => unsafe {
                                    self.barrier_compute(buffer, lo, hi - lo)
                                },
                            }
                        }
                        dirty = None;
                    }
                }
                // Timestamp pairs count dispatches, not ops: copies take no
                // queries, so the pair index is separate from the step.
                match *op {
                    Op::Dispatch { kind, push, invocations } => {
                        // Bracket the dispatch: query 2i before the bind, 2i+1
                        // after the dispatch returns, both at COMPUTE_SHADER so
                        // the delta is the dispatch's device time.
                        let stamp = if stamps {
                            let n = stamp_next;
                            stamp_next += 1;
                            device.cmd_write_timestamp(
                                buffer,
                                vk::PipelineStageFlags::COMPUTE_SHADER,
                                self.query_pool,
                                2 * n,
                            );
                            Some(n)
                        } else {
                            None
                        };
                        lap!(4);
                        device.cmd_bind_pipeline(
                            buffer,
                            vk::PipelineBindPoint::COMPUTE,
                            self.pipelines.for_kind(kind),
                        );
                        lap!(0);
                        // File offsets
                        // File offsets made relative to the uploaded window, then to the
                        // descriptor window inside it. Both are the identity for a plan that
                        // reads from byte 0 of a file one descriptor can hold.
                        let push = self.windowed(step, kind, &push)?;
                        let segment =
                            self.segments.for_op(step, kind, &push, &self.tensors)?.unwrap_or(0);
                        let set = match self.pipelines.descriptor_sets.get(segment) {
                            Some(&set) => set,
                            None => return Err(format!("step {step} wants segment {segment}")),
                        };
                        device.cmd_bind_descriptor_sets(
                            buffer,
                            vk::PipelineBindPoint::COMPUTE,
                            self.pipelines.layout(),
                            0,
                            &[set],
                            &[],
                        );
                        let push = self.segments.rebase(segment, kind, &push);
                        lap!(1);
                        device.cmd_push_constants(
                            buffer,
                            self.pipelines.layout(),
                            vk::ShaderStageFlags::COMPUTE,
                            0,
                            push_bytes(&push),
                        );
                        // Split across x and y: the widest layer here needs 102,400
                        // workgroups and `maxComputeWorkGroupCount` is only guaranteed to
                        // be 65,535 per dimension. `global_index()` in the shaders
                        // flattens the grid back, and the grid over-covers, which is what
                        // `push.count` is checked against.
                        let groups = invocations.div_ceil(WORKGROUP);
                        lap!(2);
                        device.cmd_dispatch(
                            buffer,
                            groups.min(MAX_WORKGROUPS_PER_DIM),
                            groups.div_ceil(MAX_WORKGROUPS_PER_DIM),
                            1,
                        );
                        if clock {
                            let ms = lap.elapsed().as_secs_f64() * 1e3;
                            if ms > slowest.0 {
                                slowest = (ms, step, Some(kind), groups);
                            }
                            match binds.iter_mut().find(|entry| entry.0 == kind) {
                                Some(entry) => {
                                    entry.1 += ms;
                                    entry.3 += 1;
                                }
                                None => binds.push((kind, ms, ms, 1)),
                            }
                        }
                        lap!(3);
                        // Close the bracket opened above, before the barrier:
                        // with the barrier between every op, the delta covers
                        // the dispatch's execution and nothing else's.
                        if let Some(n) = stamp {
                            device.cmd_write_timestamp(
                                buffer,
                                vk::PipelineStageFlags::COMPUTE_SHADER,
                                self.query_pool,
                                2 * n + 1,
                            );
                        }
                    }
                    Op::Copy { src, dst, elems } => {
                        // Same buffer for source and destination. The spec allows that as
                        // long as the regions do not overlap, which the arena allocator
                        // guarantees and `nets::u2netp` asserts.
                        let region = vk::BufferCopy::default()
                            .src_offset((src as vk::DeviceSize) * 2)
                            .dst_offset((dst as vk::DeviceSize) * 2)
                            .size((elems as vk::DeviceSize) * 2);
                        device.cmd_copy_buffer(
                            buffer,
                            self.arena.buffer,
                            self.arena.buffer,
                            std::slice::from_ref(&region),
                        );
                    }
                }
                // Record this op's writes as pending for the `scheduled` path; the default
                // path emits its barrier below instead and needs no tracking.
                let (offset, size) = match *op {
                    Op::Dispatch { push, .. } => {
                        let elems = u64::from(push.out_c)
                            * u64::from(push.out_h.max(1))
                            * u64::from(push.out_w.max(1));
                        (u64::from(push.out) * 2, elems * 2)
                    }
                    Op::Copy { dst, elems, .. } => (u64::from(dst) * 2, u64::from(elems) * 2),
                };
                if schedule.is_some() {
                    dirty = dirty_union(dirty, offset, size, self.arena.size);
                    continue;
                }
                // Only what this op wrote. See `barrier_over`: the whole-arena form cost more
                // than the arithmetic it was protecting.
                // A zero-length range is not a barrier at all, and a shape this could not read
                // is a plan bug rather than something to guess around - so fall back to the
                // whole arena, which is always correct if slower.
                if size == 0 || offset + size > self.arena.size {
                    self.barrier(buffer);
                } else {
                    self.barrier_over(buffer, offset, size);
                }
            }
            lap!(4);
            // A scheduled run may have skipped the last op's barrier, but the readback
            // below is a transfer copy of whatever is still dirty: order it with a full
            // barrier. The default path needs nothing here — its last op was barriered.
            if schedule.is_some() && dirty.is_some() {
                self.barrier(buffer);
                dirty = None;
            }
            if clock {
                binds.sort_by(|a, b| b.1.total_cmp(&a.1));
                let top: Vec<String> = binds
                    .iter()
                    .take(8)
                    .map(|(kind, total, first, n)| {
                        format!("{kind:?} {total:.1} ms/{n} (first {first:.1})")
                    })
                    .collect();
                crate::timing!(
                    "record-phases bind {:.1} offsets+set {:.1} push {:.1} dispatch {:.1} \
barrier+copy {:.1} ms; slowest {:?}; dispatch by kind: {}",
                    phases[0],
                    phases[1],
                    phases[2],
                    phases[3],
                    phases[4],
                    slowest,
                    top.join(", ")
                );
            }

            let mut read_back = 0u64;
            for output in &self.plan.outputs {
                let bytes = (output.shape.len() as vk::DeviceSize) * 2;
                let region = vk::BufferCopy::default()
                    .src_offset((output.at as vk::DeviceSize) * 2)
                    .dst_offset(read_back)
                    .size(bytes);
                device.cmd_copy_buffer(
                    buffer,
                    self.arena.buffer,
                    self.staging.buffer,
                    std::slice::from_ref(&region),
                );
                read_back += bytes;
            }

            // Waiting on a fence makes the copy's writes *available*, but the device-to-host
            // domain operation still needs a memory dependency naming HOST_READ — and
            // HOST_COHERENT only removes the need for `vkInvalidateMappedMemoryRanges`, not
            // for this. Without it the readback in `infer` works on unified-memory parts and
            // is undefined on others, which is the worst kind of bug to leave in.
            let host_visible = vk::BufferMemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .buffer(self.staging.buffer)
                .offset(0)
                .size(vk::WHOLE_SIZE);
            device.cmd_pipeline_barrier(
                buffer,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &[],
                std::slice::from_ref(&host_visible),
                &[],
            );

            device
                .end_command_buffer(buffer)
                .map_err(|e| format!("end_command_buffer {e:?}"))?;
        }
        crate::timing!(
            "record-emit {:.1} ms for {} ops",
            emit_staged.elapsed().as_secs_f64() * 1e3,
            self.plan.ops.len()
        );
        Ok(())
    }

    /// # Safety
    ///
    /// `buffer` must be inside a `begin`/`end` pair.
    unsafe fn barrier(&self, buffer: vk::CommandBuffer) {
        // SAFETY: as `barrier_over`, which this defers to for the whole arena.
        unsafe { self.barrier_over(buffer, 0, vk::WHOLE_SIZE) }
    }

    /// [`Net::infer_raw`] for a plan with more than one input, one slice per binding.
    ///
    /// The recorded command buffer packs the inputs end to end from offset 0 in declaration
    /// order, so this is the mirror of `split_outputs`. Supertonic's sampler takes seven — a
    /// latent, two conditionings, the timestep shifts and two rotary angle tables — and getting
    /// them out of order would be a wrong answer at the right shape, so each is checked against
    /// its own binding rather than the total.
    pub fn infer_raw_many(&mut self, inputs: &[&[f32]]) -> Result<Vec<Vec<f32>>, String> {
        if inputs.len() != self.plan.inputs.len() {
            return Err(format!(
                "{} inputs for a plan that declares {}",
                inputs.len(),
                self.plan.inputs.len()
            ));
        }
        self.stage_inputs(inputs)?;
        self.submit()?;
        self.split_outputs()
    }

    /// Split the concatenated readback into one vector per output binding.
    ///
    /// The recorded command buffer packs the outputs end to end from offset 0 in the
    /// staging buffer, in declaration order, so this is the mirror of `record`.
    fn split_outputs(&self) -> Result<Vec<Vec<f32>>, String> {
        let mut outputs = Vec::with_capacity(self.plan.outputs.len());
        let mut at = 0usize;
        for binding in &self.plan.outputs {
            let len = binding.shape.len() as usize;
            let end = at.checked_add(len).ok_or("an output offset overflowed")?;
            let slice = self
                .output_scratch
                .get(at..end)
                .ok_or_else(|| format!("the readback is shorter than {end} elements"))?;
            outputs.push(slice.iter().map(|&h| preprocess::f16_to_f32(h)).collect());
            at = end;
        }
        Ok(outputs)
    }
}

/// Extend a pending-write byte range with one op's `(offset, size)` write.
///
/// `(0, u64::MAX)` is the unbounded marker (a zero-length or arena-overflowing range,
/// the same cases `record` falls back to the whole arena for): the union of anything
/// with an unbounded range is unbounded, and it stays that way.
fn dirty_union(
    dirty: Option<(u64, u64)>,
    offset: u64,
    size: u64,
    arena_size: u64,
) -> Option<(u64, u64)> {
    if size == 0 || offset + size > arena_size {
        return Some((0, u64::MAX));
    }
    match dirty {
        Some((0, u64::MAX)) => dirty,
        Some((lo, hi)) => Some((lo.min(offset), hi.max(offset + size))),
        None => Some((offset, offset + size)),
    }
}