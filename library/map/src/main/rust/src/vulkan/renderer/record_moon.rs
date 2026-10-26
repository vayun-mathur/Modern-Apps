//! Moon disc draw: the maps-only lunar globe.
//!
//! Split from `frame.rs` so that file stays under the file-length limit. One
//! textured disc (the shared unit quad, already uploaded in `Renderer::new`)
//! through the Moon pipeline, with the two Moon-owned sets bound — or nothing
//! but the clear colour when the host never pushed textures.
use super::Renderer;
use crate::camera::Camera;
use crate::vulkan::pipeline::Push;
use ash::vk;

impl Renderer {
    /// Draw the Moon frame: begin the pass, draw the disc, end the pass.
    ///
    /// Mirrors [`render`](Self::render)'s fence/retire/acquire/submit skeleton
    /// without the tile machinery: no placement, no symbols, no overlays. The
    /// disc radius fills the globe the Earth path would have drawn at this
    /// zoom (same `globe_radius`), centred on the viewport, so switching bodies
    /// never moves the planet under the finger.
    pub(super) fn render_moon(&mut self, camera: &Camera, clear: u32) -> Result<bool, String> {
        if self.needs_rebuild {
            self.rebuild()?;
            self.needs_rebuild = false;
        }
        let device = self.context.device.clone();
        unsafe {
            device
                .wait_for_fences(
                    std::slice::from_ref(&self.frames[self.frame_index].in_flight),
                    true,
                    u64::MAX,
                )
                .map_err(|e| format!("wait_for_fences {e:?}"))?;
        }
        self.collect_retired();
        unsafe { self.scratch[self.frame_index].reset() };

        let image_index = unsafe {
            let frame = &self.frames[self.frame_index];
            match self.swapchain.loader.acquire_next_image(
                self.swapchain.swapchain,
                u64::MAX,
                frame.image_available,
                vk::Fence::null(),
            ) {
                Ok((index, _)) => index,
                Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
                    self.needs_rebuild = true;
                    return Ok(false);
                }
                Err(e) => return Err(format!("acquire_next_image {e:?}")),
            }
        };
        let (command_buffer, in_flight, image_available, render_finished) = {
            let frame = &self.frames[self.frame_index];
            (
                frame.command_buffer,
                frame.in_flight,
                frame.image_available,
                frame.render_finished,
            )
        };
        unsafe {
            let device = self.context.device.clone();
            device
                .reset_fences(std::slice::from_ref(&in_flight))
                .map_err(|e| format!("reset_fences {e:?}"))?;
            self.record_moon(command_buffer, image_index as usize, camera, clear)?;
            let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
            let submit = vk::SubmitInfo::default()
                .wait_semaphores(std::slice::from_ref(&image_available))
                .wait_dst_stage_mask(&wait_stages)
                .command_buffers(std::slice::from_ref(&command_buffer))
                .signal_semaphores(std::slice::from_ref(&render_finished));
            device
                .queue_submit(self.context.queue, std::slice::from_ref(&submit), in_flight)
                .map_err(|e| format!("queue_submit {e:?}"))?;
            let swapchains = [self.swapchain.swapchain];
            let indices = [image_index];
            let present = vk::PresentInfoKHR::default()
                .wait_semaphores(std::slice::from_ref(&render_finished))
                .swapchains(&swapchains)
                .image_indices(&indices);
            match self.swapchain.loader.queue_present(self.context.queue, &present) {
                Ok(false) => {}
                Ok(true) => {}
                Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => self.needs_rebuild = true,
                Err(e) => return Err(format!("queue_present {e:?}")),
            }
        }
        self.frame_index = (self.frame_index + 1) % super::FRAMES_IN_FLIGHT;
        // The Moon path binds exactly once and draws once: record the pair so the
        // overlay's bind row stays truthful across body switches.
        self.step_times
            .borrow_mut()
            .record(crate::timing::Step::PipelineBinds, 1);
        Ok(true)
    }

    /// Record the Moon pass: clear, bind the Moon pipeline + sets, draw the disc.
    unsafe fn record_moon(
        &self,
        command_buffer: vk::CommandBuffer,
        image_index: usize,
        camera: &Camera,
        // The same theme-following clear the vector frame uses (`style::background` of the
        // palette variant).
        clear: u32,
    ) -> Result<(), String> {
        let device = &self.context.device;
        Self::begin_frame_pass(
            device,
            command_buffer,
            self.swapchain.render_pass,
            self.swapchain.framebuffers[image_index],
            self.swapchain.extent,
            clear,
        )?;
        let (Some(moon_set), Some(_color), Some(_dem)) =
            (self.moon_set, self.moon_color.as_ref(), self.moon_dem.as_ref())
        else {
            // Textures never pushed: present the clear colour. Not an error — the
            // host pushes on attach, and a frame can legitimately beat it.
            device.cmd_end_render_pass(command_buffer);
            device
                .end_command_buffer(command_buffer)
                .map_err(|e| format!("end_command_buffer {e:?}"))?;
            return Ok(());
        };
        // Viewport + scissor over the whole target (begin_frame_pass leaves them
        // unset — the vector path sets them in `record_inner`; do it here).
        let extent = self.swapchain.extent;
        let viewport = vk::Viewport::default()
            .width(extent.width as f32)
            .height(extent.height as f32)
            .min_depth(0.0)
            .max_depth(1.0);
        device.cmd_set_viewport(command_buffer, 0, std::slice::from_ref(&viewport));
        let scissor = vk::Rect2D {
            offset: vk::Offset2D { x: 0, y: 0 },
            extent,
        };
        device.cmd_set_scissor(command_buffer, 0, std::slice::from_ref(&scissor));

        // Disc quad: the shared unit quad (-1..1) scaled to the globe diameter
        // and centred — via push constants the vertex shader reads as a clip
        // rect, so no new vertex path. The quad vertices are -1..1 already; the
        // matrix maps them to the disc: scale = globe diameter in clip units,
        // centred on the viewport centre.
        let r_dp = crate::camera::globe_radius(camera.zoom, camera.center_lat);
        let kx = 2.0 / camera.width_dp as f64;
        let ky = 2.0 / camera.height_dp as f64;
        let disc = [
            (kx * r_dp) as f32,
            0.0,
            0.0,
            0.0,
            0.0,
            (ky * r_dp) as f32,
            0.0,
            0.0,
            0.0,
            0.0,
            1.0,
            0.0,
            0.0,
            0.0,
            0.0,
            1.0,
        ];
        let push = Push {
            tile_to_clip: disc,
            // xy: visible-hemisphere centre lon/lat (degrees) for the texture basis.
            color: [
                camera.center_lon as f32,
                camera.center_lat as f32,
                0.0,
                1.0,
            ],
            line: [0.0; 4],
            // z: DEM relief strength (normal nudge per metre, tuned for the
            // ±10km LDEM range at globe zoom).
            misc: [0.0, 0.0, 0.02, camera.time_seconds],
            morph: [0.0; 4],
        };
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.moon,
        );
        device.cmd_bind_descriptor_sets(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.moon_layout,
            0,
            &[moon_set],
            &[],
        );
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.moon_layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[self.quad.vertices.buffer], &[0]);
        device.cmd_bind_index_buffer(
            command_buffer,
            self.quad.indices.buffer,
            0,
            vk::IndexType::UINT32,
        );
        device.cmd_draw_indexed(
            command_buffer,
            super::QUAD_INDICES.len() as u32,
            1,
            0,
            0,
            0,
        );
        self.submitted_draws.set(1);
        device.cmd_end_render_pass(command_buffer);
        device
            .end_command_buffer(command_buffer)
            .map_err(|e| format!("end_command_buffer {e:?}"))?;
        Ok(())
    }
}
