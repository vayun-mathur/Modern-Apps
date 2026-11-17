// Mesh upload, resize, per-frame draw, teardown, and the sun-dir helper.
//
// Split out of `renderer.rs` so each file stays under the 500-line Rust limit.
// Same module scope via `renderer.rs` (`include!`), so item order is irrelevant.

impl YuuRenderer {
    /// Upload the static avatar mesh (once per surface; capped at the budget).
    ///
    /// # Safety
    /// Must be called on the render thread while this renderer's device
    /// is live and its buffers are not in use by the GPU.
    pub unsafe fn upload_avatar(&self, mesh: &[crate::mesh::YuuVertex]) {
        let converted: Vec<YuuVkVertex> = mesh.iter().take(AVATAR_VERT_CAP).map(YuuVkVertex::from).collect();
        let bytes = unsafe {
            std::slice::from_raw_parts(
                converted.as_ptr() as *const u8,
                converted.len() * mem::size_of::<YuuVkVertex>(),
            )
        };
        self.avatar_buf.upload(&self.ctx.device, bytes);
    }

    /// Resize the swapchain (recreates against the adopted window).
    ///
    /// # Safety
    /// Must be called on the render thread that owns this renderer.
    pub unsafe fn resize(&mut self, width: u32, height: u32) {
        self.width = width.max(1);
        self.height = height.max(1);
        // Full swapchain recreation follows the kart resize path; until then
        // the viewport/scissor in `draw` tracks the new extent.
    }

    /// Draw one frame: upload the bone UBO, record, submit, present.
    /// `mesh_count` is the vertex count from the last [`Self::upload_avatar`].
    ///
    /// # Safety
    /// Must be called on the render thread while this renderer's device
    /// and swapchain are live.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn draw(
        &mut self,
        view_proj: [[f32; 4]; 4],
        cam_pos: [f32; 3],
        bones: [[f32; 16]; MAX_BONES],
        skin_rgb: [f32; 3],
        mesh_count: usize,
    ) -> Result<(), String> {
        let _ = skin_rgb;
        let device = &self.ctx.device;
        // Wait for the last frame, then reset.
        device
            .wait_for_fences(std::slice::from_ref(&self.fence), true, 1_000_000_000)
            .map_err(|e| format!("fence wait {e:?}"))?;
        device.reset_fences(std::slice::from_ref(&self.fence)).map_err(|e| format!("fence reset {e:?}"))?;

        // UBO: view-projection + fixed sun + camera + bones.
        let mut ubo_bones = [[[0.0f32; 4]; 4]; MAX_BONES];
        for (i, b) in bones.iter().enumerate().take(MAX_BONES) {
            for (c, col) in b.chunks_exact(4).enumerate().take(4) {
                ubo_bones[i][c] = [col[0], col[1], col[2], col[3]];
            }
        }
        let ubo = YuuUbo {
            view_proj,
            sun_dir: normalize([0.35, -1.0, 0.45]),
            _pad0: 0.0,
            cam_pos,
            _pad1: 0.0,
            bones: ubo_bones,
        };
        let ubo_bytes = unsafe {
            std::slice::from_raw_parts(
                (&ubo as *const YuuUbo) as *const u8,
                mem::size_of::<YuuUbo>(),
            )
        };
        self.ubo_buf.upload(device, ubo_bytes);

        let acquire = self.swap.loader.acquire_next_image(
            self.swap.swapchain,
            1_000_000_000,
            self.sem_acquire,
            vk::Fence::null(),
        );
        let img_idx = match acquire {
            Ok((idx, _)) => idx,
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => return Err("swapchain out of date".to_string()),
            Err(e) => return Err(format!("acquire {e:?}")),
        };
        let fb = self.swap.framebuffers[img_idx as usize];
        device
            .reset_command_buffer(self.cmd, vk::CommandBufferResetFlags::empty())
            .map_err(|e| format!("reset cmd {e:?}"))?;
        let begin = vk::CommandBufferBeginInfo::default();
        device.begin_command_buffer(self.cmd, &begin).map_err(|e| format!("begin {e:?}"))?;
        let clear = [
            vk::ClearValue {
                color: vk::ClearColorValue { float32: [0.45, 0.68, 0.94, 1.0] },
            },
            vk::ClearValue {
                depth_stencil: vk::ClearDepthStencilValue { depth: 1.0, stencil: 0 },
            },
        ];
        let rp = vk::RenderPassBeginInfo::default()
            .render_pass(self.swap.render_pass)
            .framebuffer(fb)
            .render_area(vk::Rect2D {
                offset: vk::Offset2D { x: 0, y: 0 },
                extent: self.swap.extent,
            })
            .clear_values(&clear);
        device.cmd_begin_render_pass(self.cmd, &rp, vk::SubpassContents::INLINE);
        let viewport = vk::Viewport::default()
            .x(0.0)
            .y(0.0)
            .width(self.swap.extent.width as f32)
            .height(self.swap.extent.height as f32)
            .min_depth(0.0)
            .max_depth(1.0);
        device.cmd_set_viewport(self.cmd, 0, std::slice::from_ref(&viewport));
        let scissor = vk::Rect2D {
            offset: vk::Offset2D { x: 0, y: 0 },
            extent: self.swap.extent,
        };
        device.cmd_set_scissor(self.cmd, 0, std::slice::from_ref(&scissor));
        device.cmd_bind_pipeline(self.cmd, vk::PipelineBindPoint::GRAPHICS, self.pipes.pipeline);
        device.cmd_bind_descriptor_sets(
            self.cmd,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipes.layout,
            0,
            std::slice::from_ref(&self.pipes.descriptor_set),
            &[],
        );
        // Static avatar mesh.
        if mesh_count > 0 {
            device.cmd_bind_vertex_buffers(self.cmd, 0, std::slice::from_ref(&self.avatar_buf.buffer), &[0]);
            device.cmd_draw(self.cmd, mesh_count.min(AVATAR_VERT_CAP) as u32, 1, 0, 0);
        }
        device.cmd_end_render_pass(self.cmd);
        device.end_command_buffer(self.cmd).map_err(|e| format!("end {e:?}"))?;

        let wait = [self.sem_acquire];
        let signal = [self.sem_render];
        let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
        let submit = vk::SubmitInfo::default()
            .wait_semaphores(&wait)
            .wait_dst_stage_mask(&wait_stages)
            .command_buffers(std::slice::from_ref(&self.cmd))
            .signal_semaphores(&signal);
        device
            .queue_submit(self.ctx.queue, std::slice::from_ref(&submit), self.fence)
            .map_err(|e| format!("submit {e:?}"))?;
        let present = vk::PresentInfoKHR::default()
            .wait_semaphores(&signal)
            .swapchains(std::slice::from_ref(&self.swap.swapchain))
            .image_indices(std::slice::from_ref(&img_idx));
        match self.swap.loader.queue_present(self.ctx.queue, &present) {
            Ok(_) => Ok(()),
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) | Err(vk::Result::SUBOPTIMAL_KHR) => {
                Err("swapchain out of date".to_string())
            }
            Err(e) => Err(format!("present {e:?}")),
        }
    }

    /// Free everything except the context (the engine releases the window).
    ///
    /// # Safety
    /// Must be called on the render thread that owns this renderer, at
    /// most once.
    pub unsafe fn destroy(&mut self) {
        let device = &self.ctx.device;
        device.device_wait_idle().ok();
        device.destroy_semaphore(self.sem_acquire, None);
        device.destroy_semaphore(self.sem_render, None);
        device.destroy_fence(self.fence, None);
        device.destroy_command_pool(self.pool, None);
        device.destroy_sampler(self.albedo_sampler, None);
        device.destroy_image_view(self.albedo_view, None);
        device.destroy_image(self.albedo_image, None);
        device.free_memory(self.albedo_memory, None);
        self.ubo_buf.destroy(device);
        self.avatar_buf.destroy(device);
        self.pipes.destroy(device);
        self.swap.cleanup(device);
    }
}

fn normalize(v: [f32; 3]) -> [f32; 3] {
    let len = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt().max(1e-6);
    [v[0] / len, v[1] / len, v[2] / len]
}
