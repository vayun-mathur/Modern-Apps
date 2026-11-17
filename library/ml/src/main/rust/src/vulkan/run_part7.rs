impl Net {
    /// A barrier over `offset .. offset + size` of the arena, in bytes.
    ///
    /// # Why the range matters
    ///
    /// It used to be the whole buffer on every op, on the reasoning that consecutive ops overlap
    /// anyway so there is nothing to narrow to. That is true of *which* ops depend on which, and
    /// false about what the barrier costs: a decode step is 1,094 ops and therefore 1,094
    /// whole-buffer barriers, and on a tile-based mobile GPU each one is a pipeline drain over a
    /// buffer that is now up to 303 MB. Measured on a Tensor G4 a decode step took 676 ms, of
    /// which the arithmetic accounts for perhaps a tenth - the rest was this.
    ///
    /// An op only ever makes visible what it wrote, so the range is its own output. Anything
    /// earlier was already made visible by the barrier that followed *it*.
    ///
    /// # How it is spelled is a variable, and it is switchable
    ///
    /// It is tempting to read a dispatch's dependency on the dispatch before it as only
    /// `SHADER_WRITE` to `SHADER_READ`, making the transfer stage and the two transfer accesses
    /// more than the spec requires. That is wrong here, and [`Formulation::Narrow`] is the
    /// measurement proving it: a copy is also an op in this runtime, so the transfer edge is load
    /// bearing and dropping it corrupts the output.
    ///
    /// Whether a *correct* spelling is cheaper than another correct one is a property of the
    /// driver rather than of the spec. On a Tensor G4 the answer turned out to be no — the four
    /// correct variants sit within 0.45% of each other. See [`Formulation`] and
    /// `analysis/maml_vs_litert.md` section 6.
    ///
    /// [`Formulation`] makes the choice a run-time knob rather than a rebuild. That is not
    /// convenience: the first attempt at this measured a rebuild against a rebuild, a shader edit
    /// silently failed to recompile in between, and the shader's 45% was attributed to the
    /// barrier. Switching within one process makes the comparison interleavable and immune to
    /// that. See `analysis/maml_vs_litert.md`.
    ///
    /// # Safety
    ///
    /// `buffer` must be inside a `begin`/`end` pair, and the range must be inside the arena.
    unsafe fn barrier_over(&self, buffer: vk::CommandBuffer, offset: u64, size: u64) {
        let formulation = Formulation::selected();
        if formulation == Formulation::None {
            return;
        }
        let (src_stage, dst_stage) = formulation.stages();
        let (src_access, dst_access) = formulation.accesses();
        // A global memory barrier names no buffer and no range, so the driver has nothing to
        // walk. If the cost scales with the range, this is where that shows up.
        if formulation == Formulation::Global || formulation == Formulation::GlobalNarrow {
            let barrier = vk::MemoryBarrier::default()
                .src_access_mask(src_access)
                .dst_access_mask(dst_access);
            self.context.device.cmd_pipeline_barrier(
                buffer,
                src_stage,
                dst_stage,
                vk::DependencyFlags::empty(),
                std::slice::from_ref(&barrier),
                &[],
                &[],
            );
            return;
        }
        let (offset, size) = if formulation == Formulation::Whole {
            (0, vk::WHOLE_SIZE)
        } else {
            (offset, size)
        };
        let barrier = vk::BufferMemoryBarrier::default()
            .src_access_mask(src_access)
            .dst_access_mask(dst_access)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .buffer(self.arena.buffer)
            .offset(offset)
            .size(size);
        self.context.device.cmd_pipeline_barrier(
            buffer,
            src_stage,
            dst_stage,
            vk::DependencyFlags::empty(),
            &[],
            std::slice::from_ref(&barrier),
            &[],
        );
    }

    /// A compute-only barrier over `offset .. offset + size` of the arena, in bytes.
    ///
    /// The `scheduled` barrier spelling for a dependency with no transfer on either side:
    /// [`crate::nets::schedule`] proved no copy is involved, so naming only
    /// `COMPUTE_SHADER` with the shader accesses is sound — unlike [`Formulation::Narrow`],
    /// which dropped the transfer edge unconditionally and corrupted the upload/readback.
    ///
    /// # Safety
    ///
    /// `buffer` must be inside a `begin`/`end` pair, and the range must be inside the arena.
    /// The caller must have established (via the schedule) that neither the pending ops nor
    /// this one move data through `TRANSFER`.
    unsafe fn barrier_compute(&self, buffer: vk::CommandBuffer, offset: u64, size: u64) {
        let barrier = vk::BufferMemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::SHADER_WRITE)
            .dst_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .buffer(self.arena.buffer)
            .offset(offset)
            .size(size);
        self.context.device.cmd_pipeline_barrier(
            buffer,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            std::slice::from_ref(&barrier),
            &[],
        );
    }

    /// Preprocess `pixels`, run the network, and return the mask as `0..1` floats.
    ///
    /// `pixels` is `ARGB_8888` as `Bitmap.getPixels` produces it. The returned mask is
    /// `output_shape.h * output_shape.w` long, row-major.
    ///
    /// Single-input, single-output: this is the bitmap-in, mask-out path the two
    /// segmenters and the face embedder use. It refuses a plan shaped otherwise rather
    /// than silently returning only the first binding.
    pub fn infer(
        &mut self,
        pixels: &[i32],
        width: u32,
        height: u32,
    ) -> Result<Vec<f32>, String> {
        let input = self.plan.input()?;
        // Reject a multi-output plan here rather than returning only the first binding:
        // `output_scratch` spans every output, so the concatenation would look like a
        // mask of the wrong size instead of an error.
        let _single_output = self.plan.output()?;
        preprocess::to_planar_f16(
            pixels,
            width,
            height,
            input.shape,
            &self.normalise,
            &mut self.input_scratch,
        )?;
        self.submit()?;
        Ok(self.output_scratch.iter().map(|&h| preprocess::f16_to_f32(h)).collect())
    }

    /// Letterbox `pixels` into the plan's input shape, run, and return **every** output.
    ///
    /// SCRFD's path: nine maps come back, in [`crate::nets::Plan::outputs`] order. `fit`
    /// must have been computed for the shape this net was built at, which
    /// [`preprocess::Letterbox::square`] guarantees for a fixed side.
    pub fn infer_letterboxed(
        &mut self,
        pixels: &[i32],
        width: u32,
        height: u32,
        fit: &preprocess::Letterbox,
    ) -> Result<Vec<Vec<f32>>, String> {
        let input = self.plan.input()?;
        if fit.shape() != input.shape {
            return Err(format!(
                "a letterbox of {:?} for a net built at {:?}",
                fit.shape(),
                input.shape
            ));
        }
        preprocess::to_letterboxed_f16(
            pixels,
            width,
            height,
            fit,
            &self.normalise,
            &mut self.input_scratch,
        )?;
        self.submit()?;
        self.split_outputs()
    }

    /// The plan's input shape, so a caller can size the tensor it hands to
    /// [`Net::infer_raw`] without holding the plan itself.
    pub fn input_shape(&self) -> Result<crate::nets::Shape, String> {
        Ok(self.plan.input()?.shape)
    }

    /// Run the plan over `values` directly, with no preprocessing, and return every output.
    ///
    /// The bitmap paths above exist because most of these networks take an image. Supertonic's
    /// do not: the text encoder takes character ids, the sampler takes a latent and a
    /// conditioning and the vocoder takes the sampler's output, each produced by the previous
    /// stage rather than by a camera. So this is the path for a net whose input is a tensor
    /// someone else computed.
    ///
    /// `values` must be exactly the input shape's element count, in `[c, h, w]` order — the
    /// same order [`crate::nets::Plan`] uses everywhere. It is rounded to fp16 on the way in,
    /// which is the arena's precision, so a caller cannot hand over more accuracy than the
    /// device will keep.
    pub fn infer_raw(&mut self, values: &[f32]) -> Result<Vec<Vec<f32>>, String> {
        let input = self.plan.input()?;
        let wanted = input.shape.len() as usize;
        if values.len() != wanted {
            return Err(format!(
                "{} values for an input of {:?}, which holds {wanted}",
                values.len(),
                input.shape
            ));
        }
        self.input_scratch.clear();
        self.input_scratch.extend(values.iter().map(|&v| preprocess::f32_to_f16(v)));
        self.submit()?;
        self.split_outputs()
    }
}
