//! Frame renderer: command pool, per-frame fence, bone UBO + avatar mesh
//! upload, albedo texture, draw.
//!
//! One in-flight frame at a time (the render thread ticks at 60 Hz and waits
//! on the fence before re-recording, so no frame ever overtakes another).
//! The avatar mesh uploads once per surface; the bone UBO re-uploads every
//! frame from the engine's pose. View-projection comes from the preview
//! camera with the surface pre-rotation prepended.
use crate::atlas::ATLAS_SIZE;
use crate::avatar::MAX_BONES;
use crate::vulkan::buffers::{AllocatedBuffer, YuuUbo, YuuVkVertex};
use crate::vulkan::context::VulkanContext;
use crate::vulkan::pipeline::Pipelines;
use crate::vulkan::swapchain::Swapchain;
use ash::vk;
use std::mem;

/// Static avatar mesh budget: the procedural fallback is ~1k verts; 64k
/// leaves margin for a detailed glTF base mesh.
const AVATAR_VERT_CAP: usize = 65_536;

/// Everything needed to draw one frame.
pub struct YuuRenderer {
    /// Vulkan context (owns instance/device/surface).
    pub ctx: VulkanContext,
    /// Swapchain + depth + render pass + framebuffers.
    pub swap: Swapchain,
    /// Graphics pipeline + UBO/sampler descriptor set.
    pub pipes: Pipelines,
    /// Static avatar vertex buffer (uploaded per surface).
    pub avatar_buf: AllocatedBuffer,
    /// Uniform buffer (view-projection + sun + camera + bones).
    pub ubo_buf: AllocatedBuffer,
    /// Albedo texture image (ATLAS_SIZE×ATLAS_SIZE skin-flat default,
    /// re-uploaded in place from [`generate_atlas`](crate::atlas::generate_atlas)).
    pub albedo_image: vk::Image,
    /// Albedo memory.
    pub albedo_memory: vk::DeviceMemory,
    /// Albedo view.
    pub albedo_view: vk::ImageView,
    /// Albedo sampler.
    pub albedo_sampler: vk::Sampler,
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

impl YuuRenderer {
    /// Create from a live window at `width` x `height`. Fails cleanly.
    ///
    /// Follows the `KartRenderer::new` sequence verbatim (context → swapchain
    /// → UBO → pipeline → vertex buffer → pool/cmd/fence/semaphores), plus
    /// the ATLAS_SIZE x ATLAS_SIZE skin-flat albedo bound to the sampler slot.
    ///
    /// # Safety
    /// `window` must be a live adopted `ANativeWindow`; ownership stays with
    /// the engine (released on destroy), as on the kart path.
    pub unsafe fn new(
        window: *mut crate::vulkan::context::ANativeWindow,
        width: i32,
        height: i32,
        state_width: u32,
        state_height: u32,
    ) -> Result<Self, String> {
        // Prefer the engine's tracked size; fall back to the window size,
        // then to a sane default (lib.rs passes 1280x720 until the first
        // surfaceChanged arrives).
        let want_w = state_width.max(width.max(1) as u32).max(1);
        let want_h = state_height.max(height.max(1) as u32).max(1);
        let ctx = unsafe { VulkanContext::new(window, want_w as i32, want_h as i32) }
            .map_err(|e| format!("ctx {e:?}"))?;
        let swap = Swapchain::new(&ctx, want_w, want_h)?;
        let ubo_size = mem::size_of::<YuuUbo>() as u64;
        let ubo_buf = AllocatedBuffer::new(
            &ctx.instance,
            &ctx.device,
            ctx.physical_device,
            ubo_size,
            vk::BufferUsageFlags::UNIFORM_BUFFER,
        )?;
        let pipes = Pipelines::new(&ctx.device, swap.render_pass, ubo_buf.buffer, ubo_size)?;
        let vert_size = (mem::size_of::<YuuVkVertex>() * AVATAR_VERT_CAP) as u64;
        let avatar_buf = AllocatedBuffer::new(
            &ctx.instance,
            &ctx.device,
            ctx.physical_device,
            vert_size,
            vk::BufferUsageFlags::VERTEX_BUFFER,
        )?;
        let (albedo_image, albedo_memory, albedo_view, albedo_sampler) =
            unsafe { Self::create_albedo(&ctx, [0xE8, 0xB4, 0x8A, 0xFF]) }?;
        pipes.bind_albedo(&ctx.device, albedo_sampler, albedo_view);
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
            avatar_buf,
            ubo_buf,
            albedo_image,
            albedo_memory,
            albedo_view,
            albedo_sampler,
            pool,
            cmd,
            fence,
            sem_acquire,
            sem_render,
            width,
            height,
        })
    }
}

// Albedo image management and the per-frame upload/draw path live in part
// files so each file stays under the 500-line Rust limit (same module scope via `include!`).
include!("renderer_albedo.rs");
include!("renderer_frame.rs");
#[cfg(test)]
mod renderer_tests;
