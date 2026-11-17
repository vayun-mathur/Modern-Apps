//! Frame renderer: command pool, per-frame fences, UBO + mesh upload, draw.
//!
//! One in-flight frame at a time (the render thread ticks at 60 Hz and waits
//! on the fence before re-recording, so no frame ever overtakes another).
//! The track mesh uploads once per race; karts/shells/boxes re-upload every
//! frame from [`crate::race`] transforms. View-projection comes from the
//! chase camera with the surface pre-rotation prepended.
use crate::mesh::KartVertex;
use crate::vulkan::buffers::{AllocatedBuffer, KartUbo, KartVkVertex};
use crate::vulkan::context::VulkanContext;
use crate::vulkan::pipeline::Pipelines;
use crate::vulkan::swapchain::Swapchain;
use ash::vk;
use std::mem;

/// Static track mesh budget: the oval sweep is ~30k verts; 64k leaves margin.
const TRACK_VERT_CAP: usize = 65_536;
/// Dynamic mesh budget per frame: 8 karts (~160 verts each) + boxes + shells.
const DYNAMIC_VERT_CAP: usize = 8_192;

/// Everything needed to draw one frame.
pub struct KartRenderer {
 /// Vulkan context (owns instance/device/surface).
    pub ctx: VulkanContext,
    /// Swapchain + depth + render pass + framebuffers.
    pub swap: Swapchain,
    /// Graphics pipeline + UBO descriptor set.
    pub pipes: Pipelines,
    /// Static track vertex buffer (re-uploaded per race).
    pub track_buf: AllocatedBuffer,
    /// Per-frame dynamic vertex buffer (karts, shells, boxes).
    pub dyn_buf: AllocatedBuffer,
    /// Uniform buffer (view-projection + sun + camera).
    pub ubo_buf: AllocatedBuffer,
    /// Command pool (graphics family).
    pub pool: vk::CommandPool,
    /// One reusable command buffer.
    pub cmd: vk::CommandBuffer,
    /// Frame fence (signalled when the GPU finishes the frame).
    pub fence: vk::Fence,
    /// Image-acquired + render-finished semaphores.
    pub sem_acquire: vk::Semaphore,
    /// Image-acquired + render-finished semaphores.
    pub sem_render: vk::Semaphore,
    /// Current surface width.
    pub width: u32,
    /// Current surface height.
    pub height: u32,
}

impl KartRenderer {
    /// Create from a live window at `width` x `height`. Fails cleanly.
    ///
    /// # Safety
    /// `ctx` must come from a live `ANativeWindow` (see
    /// [`VulkanContext::new`]); render thread only.
    pub unsafe fn new(
        ctx: VulkanContext,
        width: u32,
        height: u32,
    ) -> Result<Self, String> {
        let swap = Swapchain::new(&ctx, width, height)?;
        let ubo_size = mem::size_of::<KartUbo>() as u64;
        let ubo_buf = AllocatedBuffer::new(
            &ctx.instance,
            &ctx.device,
            ctx.physical_device,
            ubo_size,
            vk::BufferUsageFlags::UNIFORM_BUFFER,
        )?;
        let pipes = Pipelines::new(&ctx.device, swap.render_pass, ubo_buf.buffer, ubo_size)?;
        let vert_size = (mem::size_of::<KartVkVertex>() * TRACK_VERT_CAP) as u64;
        let track_buf = AllocatedBuffer::new(
            &ctx.instance,
            &ctx.device,
            ctx.physical_device,
            vert_size,
            vk::BufferUsageFlags::VERTEX_BUFFER,
        )?;
        let dyn_size = (mem::size_of::<KartVkVertex>() * DYNAMIC_VERT_CAP) as u64;
        let dyn_buf = AllocatedBuffer::new(
            &ctx.instance,
            &ctx.device,
            ctx.physical_device,
            dyn_size,
            vk::BufferUsageFlags::VERTEX_BUFFER,
        )?;
        let pool_info = vk::CommandPoolCreateInfo::default()
            .queue_family_index(ctx.queue_family_index)
            .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
        let pool = ctx.device.create_command_pool(&pool_info, None).map_err(|e| format!("pool {e:?}"))?;
        let alloc_info = vk::CommandBufferAllocateInfo::default()
            .command_pool(pool)
            .level(vk::CommandBufferLevel::PRIMARY)
            .command_buffer_count(1);
        let cmd = ctx
            .device
            .allocate_command_buffers(&alloc_info)
            .map_err(|e| format!("cmd {e:?}"))?[0];
        let fence = ctx
            .device
            .create_fence(&vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED), None)
            .map_err(|e| format!("fence {e:?}"))?;
        let sem_acquire = ctx.device.create_semaphore(&vk::SemaphoreCreateInfo::default(), None).map_err(|e| format!("sem {e:?}"))?;
        let sem_render = ctx.device.create_semaphore(&vk::SemaphoreCreateInfo::default(), None).map_err(|e| format!("sem {e:?}"))?;
        let width = swap.extent.width;
        let height = swap.extent.height;
        Ok(Self {
            ctx,
            swap,
            pipes,
            track_buf,
            dyn_buf,
            ubo_buf,
            pool,
            cmd,
            fence,
            sem_acquire,
            sem_render,
            width,
            height,
        })
    }

    /// Upload the static track mesh (once per race; capped at the budget).
    ///
    /// # Safety
    /// Must be called on the render thread while this renderer's device
    /// is live and its buffers are not in use by the GPU.
    pub unsafe fn upload_track(&self, mesh: &[KartVertex]) {
        let n = mesh.len().min(TRACK_VERT_CAP);
        let bytes = unsafe {
            std::slice::from_raw_parts(
                mesh.as_ptr() as *const u8,
                n * mem::size_of::<KartVkVertex>(),
            )
        };
        self.track_buf.upload(&self.ctx.device, bytes);
        // Remember the count via the buffer's user: stored alongside the UBO
        // update below (see draw: track_count is passed per frame).
    }

    /// Draw one frame: upload UBO + dynamic mesh, record, submit, present.
    /// `track_count` is the vertex count from the last [`Self::upload_track`].
    ///
    /// # Safety
    /// Must be called on the render thread while this renderer's device
    /// and swapchain are live.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn draw(
        &mut self,
        view_proj: [[f32; 4]; 4],
        cam_pos: [f32; 3],
        track_count: usize,
        dynamic: &[KartVertex],
    ) -> Result<(), String> {
        let device = &self.ctx.device;
        // Wait for the last frame, then reset.
        device
            .wait_for_fences(std::slice::from_ref(&self.fence), true, 1_000_000_000)
            .map_err(|e| format!("fence wait {e:?}"))?;
        device.reset_fences(std::slice::from_ref(&self.fence)).map_err(|e| format!("fence reset {e:?}"))?;

        // UBO: view-projection + fixed sun + camera.
        let ubo = KartUbo {
            view_proj,
            sun_dir: normalize([0.35, -1.0, 0.45]),
            _pad0: 0.0,
            cam_pos,
            _pad1: 0.0,
        };
        let ubo_bytes = unsafe {
            std::slice::from_raw_parts(
                (&ubo as *const KartUbo) as *const u8,
                mem::size_of::<KartUbo>(),
            )
        };
        self.ubo_buf.upload(device, ubo_bytes);
        // Dynamic mesh.
        let n = dynamic.len().min(DYNAMIC_VERT_CAP);
        let dyn_bytes = unsafe {
            std::slice::from_raw_parts(
                dynamic.as_ptr() as *const u8,
                n * mem::size_of::<KartVkVertex>(),
            )
        };
        self.dyn_buf.upload(device, dyn_bytes);

        let (img_idx, _) = self
            .swap
            .loader
            .acquire_next_image(
                self.swap.swapchain,
                1_000_000_000,
                self.sem_acquire,
                vk::Fence::null(),
            )
            .map_err(|e| format!("acquire {e:?}"))?;
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
        // Static track.
        if track_count > 0 {
            device.cmd_bind_vertex_buffers(self.cmd, 0, std::slice::from_ref(&self.track_buf.buffer), &[0]);
            device.cmd_draw(self.cmd, track_count.min(TRACK_VERT_CAP) as u32, 1, 0, 0);
        }
        // Dynamic karts/shells/boxes.
        if n > 0 {
            device.cmd_bind_vertex_buffers(self.cmd, 0, std::slice::from_ref(&self.dyn_buf.buffer), &[0]);
            device.cmd_draw(self.cmd, n as u32, 1, 0, 0);
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
        self.swap
            .loader
            .queue_present(self.ctx.queue, &present)
            .map_err(|e| format!("present {e:?}"))?;
        Ok(())
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
        self.ubo_buf.destroy(device);
        self.dyn_buf.destroy(device);
        self.track_buf.destroy(device);
        self.pipes.destroy(device);
        self.swap.cleanup(device);
    }
}

fn normalize(v: [f32; 3]) -> [f32; 3] {
    let len = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt().max(1e-6);
    [v[0] / len, v[1] / len, v[2] / len]
}
