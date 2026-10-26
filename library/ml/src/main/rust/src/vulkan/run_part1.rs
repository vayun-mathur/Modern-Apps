impl Net {
    /// Allocate, upload the weights and record the plan.
    ///
    /// `weights` is read for its data section but not retained: once the blob is in
    /// device-local memory the host copy is dropped, which for U^2-Netp gives back 2.1 MB
    /// of heap.
    ///
    /// A [`Blob`] rather than a [`crate::weights::Weights`] so that a bundled net can stream
    /// straight out of the APK.
    ///
    /// Only the bytes `plan`'s ops read are uploaded: the smallest window of whole tensors
    /// covering them. A later [`Net::rebuild`] must read inside that window, so a net that
    /// switches between plans reading different tensors is built with [`Net::covering`] or
    /// [`Net::whole`].
    pub fn new(
        context: Arc<Context>,
        plan: Plan,
        weights: &dyn Blob,
        normalise: Normalise,
    ) -> Result<Net, String> {
        Self::covering(context, plan, &[], weights, normalise)
    }

    /// [`Net::new`], uploading the window that covers `plan` and every plan in `also`.
    pub fn covering(
        context: Arc<Context>,
        plan: Plan,
        also: &[&Plan],
        weights: &dyn Blob,
        normalise: Normalise,
    ) -> Result<Net, String> {
        let align = context.limits.min_storage_buffer_offset_alignment.max(super::segment::ALIGNMENT);
        let mut plans = vec![&plan];
        plans.extend_from_slice(also);
        let window = super::segment::read_window(&plans, weights.tensors(), align)?;
        // Every plan's list: the first may be a decode step that lists none of the encoder's.
        let widen: Vec<u32> = plans.iter().flat_map(|each| each.widen.iter().copied()).collect();
        Self::with_window(context, plan, weights, normalise, window, &widen)
    }

    /// [`Net::new`], uploading the whole data section so any plan over this file can be
    /// installed by [`Net::rebuild`].
    pub fn whole(
        context: Arc<Context>,
        plan: Plan,
        weights: &dyn Blob,
        normalise: Normalise,
    ) -> Result<Net, String> {
        let window = (0, weights.data_len());
        let widen = plan.widen.clone();
        Self::with_window(context, plan, weights, normalise, window, &widen)
    }

    fn with_window(
        context: Arc<Context>,
        plan: Plan,
        weights: &dyn Blob,
        normalise: Normalise,
        window: (u64, u64),
        widen: &[u32],
    ) -> Result<Net, String> {
        let (lo, hi) = window;
        if lo > hi || hi > weights.data_len() {
            return Err(format!("weights window {lo}..{hi} of a {}-byte file", weights.data_len()));
        }
        let arena_bytes = (plan.arena_elems as vk::DeviceSize) * 2;
        // fp16 copies of the int8 weights the plan asked to widen go after the window.
        let base = (hi - lo).div_ceil(super::segment::ALIGNMENT) * super::segment::ALIGNMENT;
        let widened = widened_layout(widen, weights.tensors(), window, base);
        let end = widened.last().map_or(hi - lo, |w| w.dst + 2 * w.bytes - lo);
        // Vulkan forbids a zero-sized buffer, and the descriptor set needs a real one bound to
        // the weights binding whether or not any shader reads it. A plan can legitimately read
        // no weights at all — a purely elementwise one does — so this floors the allocation
        // rather than refusing the plan.
        let weights_bytes = (end as vk::DeviceSize).max(2);
        let input_elems = binding_elems(&plan.inputs);
        let output_elems = binding_elems(&plan.outputs);
        // One staging buffer for both directions: the inputs and the outputs are never in
        // flight at the same time, because an inference is a single blocking submit. Each
        // side packs its bindings end to end from offset 0, in declaration order.
        let staging_bytes = (input_elems.max(output_elems) as vk::DeviceSize) * 2;

        // Before allocating anything: a file this device's descriptors cannot describe must fail
        // here, with the limit in the message, rather than at the first dispatch that reads past
        // a range. Windowing depends only on the length, so `rebuild` never redoes it.
        let segments = Segments::plan(weights_bytes, &context.limits)?;
        // A few kilobytes, kept because `record` needs each tensor's extent to know which window
        // an op fits in, and `rebuild` installs plans this net was not constructed with.
        let tensors = Self::widened_tensors(
            super::segment::window_tensors(weights.tensors(), lo, hi),
            &widened,
            lo,
        )?;

        let weights_buffer = Buffer::device_local(&context, weights_bytes)?;
        let arena = Buffer::device_local(&context, arena_bytes)?;
        let staging = Buffer::staging(&context, staging_bytes)?;
        let params = Buffer::step_params(&context, StepParams::BYTES)?;

        let staged = std::time::Instant::now();
        let pipelines = Pipelines::new(
            &context,
            arena.buffer,
            arena_bytes,
            weights_buffer.buffer,
            params.buffer,
            segments.all(),
        )?;
        timing!("pipelines {:.0} ms", staged.elapsed().as_secs_f64() * 1000.0);

        // `RESET_COMMAND_BUFFER`, so a net can re-record without reallocating.
        let pool_info = vk::CommandPoolCreateInfo::default()
            .queue_family_index(context.queue_family_index)
            .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
        // SAFETY: a plain object creation on a device this net holds an `Arc` to. It is stored in
        // the struct below before anything fallible runs, so `Drop` destroys it on every path.
        let command_pool = unsafe { context.device.create_command_pool(&pool_info, None) }
            .map_err(|e| format!("create_command_pool {e:?}"))?;

        let pinned_seen = !plan.pinned.is_empty();
        let mut net = Net {
            context,
            plan,
            normalise,
            weights: weights_buffer,
            arena,
            staging,
            params,
            pipelines,
            segments,
            tensors,
            widened,
            window,
            command_pool,
            command_buffer: vk::CommandBuffer::null(),
            recorded: Vec::new(),
            spare: Vec::new(),
            pinned_seen,
            fence: vk::Fence::null(),
            query_pool: vk::QueryPool::null(),
            query_count: 0,
            poisoned: false,
            input_scratch: vec![0u16; input_elems],
            input_seen: Vec::new(),
            input_cache: Vec::new(),
            output_scratch: vec![0u16; output_elems],
        };

        let staged = std::time::Instant::now();
        net.upload_weights(weights)?;
        net.upload_widened(weights)?;
        timing!(
            "upload {} MB of {} MB (bytes {lo}..{hi}, {} MB widened) in {:.0} ms",
            weights_bytes / (1024 * 1024),
            weights.data_len() / (1024 * 1024),
            net.widened_bytes() / (1024 * 1024),
            staged.elapsed().as_secs_f64() * 1000.0
        );
        net.command_buffer = net.allocate_command_buffer()?;
        net.fence = net.create_fence()?;
        // Written before the first record so a shader reading it never sees uninitialised
        // memory, even on a plan that never calls `set_params`.
        net.set_params(StepParams::default())?;
        net.reset_query_pool()?;
        let staged = std::time::Instant::now();
        net.record()?;
        timing!(
            "record {:.0} ms for {} ops, arena {} KB",
            staged.elapsed().as_secs_f64() * 1000.0,
            net.plan.ops.len(),
            arena_bytes / 1024
        );
        Ok(net)
    }

    /// Install the values the next submit reads, without touching the recording.
    ///
    /// The buffer is host-coherent, so the write is visible to a later submit with no flush and
    /// no barrier. It must not be called while a submit is in flight — `infer` blocks on its
    /// fence before returning, so holding `&mut self` is enough to guarantee that.
    pub fn set_params(&mut self, params: StepParams) -> Result<(), String> {
        self.params.write(params.as_bytes())
    }

    /// Re-record this net at `plan`'s shapes, keeping the weights already uploaded.
    ///
    /// Supertonic's nets are shaped by the utterance — the text encoder runs at the character
    /// count, the sampler and the vocoder at the latent length — and a [`Plan`] is one command
    /// buffer recorded at fixed shapes, so a new length needs a new recording. It does not need a
    /// new upload: the weights are the expensive part (198 MB for Supertonic, 605 MB for
    /// SMaLL-100) and they do not depend on the length. So this keeps the weights buffer and
    /// re-uses the arena and the staging buffer too, growing them only when the new shapes need
    /// more than the old ones did — which for a sequence of utterances means the allocation
    /// happens a handful of times rather than once per sentence.
    ///
    /// `plan` must have been built against the same weights as this net's. Nothing here checks
    /// that, because [`crate::nets::Builder::finish`] already refuses a plan that does not read
    /// every tensor in its file, so a plan for a different `.maml` cannot reach here.
    pub fn rebuild(&mut self, plan: Plan) -> Result<(), String> {
        if self.poisoned {
            return Err("this network is unusable after an earlier failure".into());
        }
        // Before anything changes, so a plan reading past the uploaded window is an error the
        // net survives rather than one that retires it at record time.
        for (step, op) in plan.ops.iter().enumerate() {
            if let Op::Dispatch { kind, push, .. } = op {
                self.windowed(step, *kind, push)?;
            }
        }
        let arena_bytes = (plan.arena_elems as vk::DeviceSize) * 2;
        let input_elems = binding_elems(&plan.inputs);
        let output_elems = binding_elems(&plan.outputs);
        let staging_bytes = (input_elems.max(output_elems) as vk::DeviceSize) * 2;

        // Allocate before touching any state. A failure part-way through must leave the net
        // running at its old plan: a descriptor pointing at a new arena while the recorded
        // command buffer still copies the inputs into the old one would be a wrong answer at the
        // right shape, which is far worse than an error.
        let staged = std::time::Instant::now();
        let grown_arena = if arena_bytes > self.arena.size {
            Some(Buffer::device_local(&self.context, arena_bytes)?)
        } else {
            None
        };
        let grown_staging = if staging_bytes > self.staging.size {
            Some(Buffer::staging(&self.context, staging_bytes)?)
        } else {
            None
        };
        timing!("rebuild-alloc {:.1} ms", staged.elapsed().as_secs_f64() * 1e3);

        // SAFETY: a descriptor set may not be rewritten, and a bound buffer may not be freed,
        // while a command buffer using either is pending. `submit` waits on its fence before
        // returning and the check above rejects the one case where it did not, so nothing is in
        // fact pending — but this costs one round trip per length change, not per inference, and
        // it makes that reasoning unnecessary rather than load-bearing.
        let staged = std::time::Instant::now();
        unsafe {
            let guard = self.context.lock_queue();
            let idle = self.context.device.device_wait_idle();
            drop(guard);
            idle.map_err(|e| format!("device_wait_idle {e:?}"))?;
        }
        timing!("rebuild-idle {:.1} ms", staged.elapsed().as_secs_f64() * 1e3);

        // Past here nothing fails until `record`, so the net cannot be left describing one shape
        // and recording another. Each old `Buffer` frees itself as it is replaced, after the wait
        // above.
        // A recording bakes in the arena and staging handles and the descriptor sets, so a
        // growth invalidates every kept one. So does a timestamp pool sized for another plan.
        let keep_recordings = grown_arena.is_none() && grown_staging.is_none() && !timestamps_enabled();
        if let Some(arena) = grown_arena {
            // Carry the old arena over first - unless no plan so far pinned anything, in which
            // case it holds only scratch that is rewritten before it is read.
            //
            // A wider plan needs a bigger arena, and a fresh `Buffer` holds undefined memory -
            // so swapping it in silently replaced every **persistent** tensor with rubbish. The
            // KV caches live there, which made this a wrong answer rather than a crash: the
            // model attended over noise and produced fluent nonsense, non-deterministically,
            // and only once a prompt was long enough to make the prefill plan outgrow the
            // decode plan's arena. Short prompts never tripped it, which is why it survived
            // every test until a 1,900-position prefix.
            //
            // Copying the whole old arena is more than the persistent tensors strictly need, but
            // offsets are assigned per plan and the scratch above them is written before it is
            // read - so the correct-by-construction thing is to preserve all of it. Any earlier
            // plan's pins count, not just the outgoing one's: a cache written two plans ago is
            // still live.
            if self.pinned_seen {
                let staged = std::time::Instant::now();
                // SAFETY: both buffers are live and owned here, the copy is inside its own
                // submit, and nothing else touches either until the fence is waited on.
                unsafe { self.copy_arena(&arena)? };
                timing!("rebuild-copy {:.1} ms", staged.elapsed().as_secs_f64() * 1e3);
            }
            self.pipelines.rebind_arena(&self.context.device, arena.buffer, arena.size);
            self.arena = arena;
        }
        if let Some(staging) = grown_staging {
            self.staging = staging;
        }
        if !keep_recordings {
            self.spare.extend(self.recorded.drain(..).map(|(_, buffer)| buffer));
        }
        self.input_scratch.resize(input_elems, 0);
        self.output_scratch.resize(output_elems, 0);
        self.pinned_seen |= !plan.pinned.is_empty();
        let old = std::mem::replace(&mut self.plan, plan);
        if keep_recordings {
            self.recorded.push((old, self.command_buffer));
        } else {
            self.spare.push(self.command_buffer);
        }
        let kept = self.recorded.iter().position(|(recorded, _)| *recorded == self.plan);
        let reused = kept.is_some();
        self.command_buffer = match kept {
            Some(index) => self.recorded.remove(index).1,
            None => match self.spare.pop() {
                Some(buffer) => buffer,
                None => match self.allocate_command_buffer() {
                    Ok(buffer) => buffer,
                    Err(e) => {
                        self.poisoned = true;
                        return Err(e);
                    }
                },
            },
        };
        while self.recorded.len() > Self::RECORDINGS {
            let (_, buffer) = self.recorded.remove(0);
            self.spare.push(buffer);
        }
        while self.spare.len() > 1 {
            if let Some(buffer) = self.spare.pop() {
                // SAFETY: the device is idle (waited above) and spares hold no pending work.
                unsafe { self.context.device.free_command_buffers(self.command_pool, &[buffer]) };
            }
        }
        self.reset_query_pool()?;
        if reused {
            timing!("rebuild reused the recording for {} ops", self.plan.ops.len());
            return Ok(());
        }

        // A `record` that fails leaves the command buffer part-written, and submitting that is
        // not something a later caller may be allowed to do. There is no way back — both of its
        // failure modes are device loss or host OOM — so the net is retired instead.
        let staged = std::time::Instant::now();
        if let Err(e) = self.record() {
            self.poisoned = true;
            return Err(e);
        }
        timing!(
            "rebuild-record {:.0} ms for {} ops, arena {} KB, dispatches {}, invocations {}",
            staged.elapsed().as_secs_f64() * 1000.0,
            self.plan.ops.len(),
            self.arena.size / 1024,
            self.plan.ops.iter().filter(|o| matches!(o, Op::Dispatch { .. })).count(),
            self.plan
                .ops
                .iter()
                .map(|o| match o {
                    Op::Dispatch { invocations, .. } => *invocations as u64,
                    _ => 0,
                })
                .sum::<u64>()
        );
        Ok(())
    }

    /// Staging bytes per copy. Large enough that the per-chunk round trip is noise against the
    /// transfer, small enough to be an unremarkable allocation on a low-RAM device.
    ///
    /// `pub(crate)` only so the parity fixture can assert its blob is bigger than one chunk. A
    /// fixture that fits in a single copy exercises none of the `dst_offset` arithmetic.
    pub(crate) const CHUNK_BYTES: u64 = 8 * 1024 * 1024;

    /// Recordings [`Net::rebuild`] keeps besides the current one. Enough for an encoder and a
    /// decoder at a couple of lengths, like `Reshaped`'s plan cache.
    const RECORDINGS: usize = 4;
}