//! Swapchain + depth + classic render pass: the framebuffer side.
//!
//! Same arrangement as games/voxels `vulkan/swapchain.rs`, minus the
//! post-processing chain kart does not have: prefer `B8G8R8A8_UNORM` (Pixel
//! gralloc rejects SRGB), `MAILBOX` over `FIFO` when available, `IDENTITY`
//! pre-transform with the projection pre-rotation fallback, D32 depth, and a
//! Vulkan 1.0 `RenderPass` (no `KHR_dynamic_rendering`, which crashes older
//! loaders with "Unable to load cmd_begin_rendering").
use ash::vk;
use crate::vulkan::context::VulkanContext;

/// Swapchain images + views, depth, render pass, framebuffers.
pub struct Swapchain {
    /// Swapchain device functions.
    pub loader: ash::khr::swapchain::Device,
    /// The swapchain itself.
    pub swapchain: vk::SwapchainKHR,
    /// Presentation images.
    pub images: Vec<vk::Image>,
    /// One view per image.
    pub image_views: Vec<vk::ImageView>,
    /// Chosen surface format.
    pub format: vk::Format,
    /// Image extent.
    pub extent: vk::Extent2D,
    /// Depth image.
    pub depth_image: vk::Image,
    /// Depth memory.
    pub depth_mem: vk::DeviceMemory,
    /// Depth view.
    pub depth_view: vk::ImageView,
    /// The color+depth render pass.
    pub render_pass: vk::RenderPass,
    /// One framebuffer per image.
    pub framebuffers: Vec<vk::Framebuffer>,
    /// Surface pre-transform (for the projection pre-rotation).
    pub pre_transform: vk::SurfaceTransformFlagsKHR,
}

impl Swapchain {
    /// Android surfaces on portrait-native panels report a rotated
    /// `currentTransform` in landscape. Returns (swap_aspect,
    /// rotation_radians): whether width/height swap for the aspect ratio, and
    /// the clip-space Z rotation to prepend to the view-projection.
    pub fn pre_rotation(&self) -> (bool, f32) {
        use vk::SurfaceTransformFlagsKHR as T;
        match self.pre_transform {
            t if t == T::ROTATE_90 => (true, std::f32::consts::FRAC_PI_2),
            t if t == T::ROTATE_180 => (false, std::f32::consts::PI),
            t if t == T::ROTATE_270 => (true, std::f32::consts::FRAC_PI_2 * 3.0),
            _ => (false, 0.0),
        }
    }
}

impl Swapchain {
    /// Create from a live context at `width` x `height`. Fails cleanly.
    pub fn new(ctx: &VulkanContext, width: u32, height: u32) -> Result<Self, String> {
        unsafe { Self::new_inner(ctx, width.max(1), height.max(1)) }
    }

    unsafe fn new_inner(ctx: &VulkanContext, width: u32, height: u32) -> Result<Self, String> {
        let caps = ctx
            .surface_loader
            .get_physical_device_surface_capabilities(ctx.physical_device, ctx.surface)
            .map_err(|e| format!("caps {e:?}"))?;
        let formats = ctx
            .surface_loader
            .get_physical_device_surface_formats(ctx.physical_device, ctx.surface)
            .map_err(|e| format!("formats {e:?}"))?;
        let present_modes = ctx
            .surface_loader
            .get_physical_device_surface_present_modes(ctx.physical_device, ctx.surface)
            .map_err(|e| format!("present_modes {e:?}"))?;
        // Fix black screen: Pixel gralloc rejects SRGB 0x3b, prefer B8G8R8A8_UNORM.
        let mut chosen_format = formats.first().copied().ok_or("no surface formats")?;
        for fmt in &formats {
            if fmt.format == vk::Format::B8G8R8A8_UNORM {
                chosen_format = *fmt;
                break;
            }
        }
        if chosen_format.format == vk::Format::B8G8R8A8_SRGB {
            if let Some(&f) = formats.iter().find(|f| {
                f.format == vk::Format::B8G8R8A8_UNORM || f.format == vk::Format::R8G8B8A8_UNORM
            }) {
                chosen_format = f;
            }
        }
        let extent = if caps.current_extent.width == u32::MAX {
            vk::Extent2D { width, height }
        } else {
            vk::Extent2D {
                width: caps.current_extent.width.max(1),
                height: caps.current_extent.height.max(1),
            }
        };
        let present_mode = if present_modes.contains(&vk::PresentModeKHR::MAILBOX) {
            vk::PresentModeKHR::MAILBOX
        } else {
            vk::PresentModeKHR::FIFO
        };
        let image_count =
            (caps.min_image_count + 1).min(if caps.max_image_count == 0 { 3 } else { caps.max_image_count });
        let loader = ash::khr::swapchain::Device::new(&ctx.instance, &ctx.device);
        let pre_transform =
            if caps.supported_transforms.contains(vk::SurfaceTransformFlagsKHR::IDENTITY) {
                vk::SurfaceTransformFlagsKHR::IDENTITY
            } else {
                caps.current_transform
            };
        let ci = vk::SwapchainCreateInfoKHR::default()
            .surface(ctx.surface)
            .min_image_count(image_count)
            .image_format(chosen_format.format)
            .image_color_space(chosen_format.color_space)
            .image_extent(extent)
            .image_array_layers(1)
            .image_usage(vk::ImageUsageFlags::COLOR_ATTACHMENT)
            .image_sharing_mode(vk::SharingMode::EXCLUSIVE)
            .pre_transform(pre_transform)
            .composite_alpha(vk::CompositeAlphaFlagsKHR::OPAQUE)
            .present_mode(present_mode)
            .clipped(true);
        let swapchain = loader.create_swapchain(&ci, None).map_err(|e| format!("create_swapchain {e:?}"))?;
        let images = loader
            .get_swapchain_images(swapchain)
            .map_err(|e| format!("get_images {e:?}"))?;
        let mut image_views = Vec::with_capacity(images.len());
        for &img in &images {
            let vi = vk::ImageViewCreateInfo::default()
                .image(img)
                .view_type(vk::ImageViewType::TYPE_2D)
                .format(chosen_format.format)
                .subresource_range(vk::ImageSubresourceRange {
                    aspect_mask: vk::ImageAspectFlags::COLOR,
                    base_mip_level: 0,
                    level_count: 1,
                    base_array_layer: 0,
                    layer_count: 1,
                });
            image_views.push(
                ctx.device
                    .create_image_view(&vi, None)
                    .map_err(|e| format!("color view {e:?}"))?,
            );
        }
        let depth_format = vk::Format::D32_SFLOAT;
        let depth_ci = vk::ImageCreateInfo::default()
            .image_type(vk::ImageType::TYPE_2D)
            .format(depth_format)
            .extent(vk::Extent3D {
                width: extent.width,
                height: extent.height,
                depth: 1,
            })
            .mip_levels(1)
            .array_layers(1)
            .samples(vk::SampleCountFlags::TYPE_1)
            .tiling(vk::ImageTiling::OPTIMAL)
            .usage(vk::ImageUsageFlags::DEPTH_STENCIL_ATTACHMENT)
            .initial_layout(vk::ImageLayout::UNDEFINED);
        let depth_image = ctx.device.create_image(&depth_ci, None).map_err(|e| format!("depth img {e:?}"))?;
        let mem_reqs = ctx.device.get_image_memory_requirements(depth_image);
        let mem_props = ctx
            .instance
            .get_physical_device_memory_properties(ctx.physical_device);
        let mem_type = (0..mem_props.memory_type_count)
            .find(|&i| {
                (mem_reqs.memory_type_bits & (1 << i)) != 0
                    && mem_props.memory_types[i as usize]
                        .property_flags
                        .contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
            })
            .ok_or("no mem depth")?;
        let alloc = vk::MemoryAllocateInfo::default()
            .allocation_size(mem_reqs.size)
            .memory_type_index(mem_type);
        let depth_mem = ctx.device.allocate_memory(&alloc, None).map_err(|e| format!("alloc depth {e:?}"))?;
        ctx.device
            .bind_image_memory(depth_image, depth_mem, 0)
            .map_err(|e| format!("bind depth {e:?}"))?;
        let depth_view_ci = vk::ImageViewCreateInfo::default()
            .image(depth_image)
            .view_type(vk::ImageViewType::TYPE_2D)
            .format(depth_format)
            .subresource_range(vk::ImageSubresourceRange {
                aspect_mask: vk::ImageAspectFlags::DEPTH,
                base_mip_level: 0,
                level_count: 1,
                base_array_layer: 0,
                layer_count: 1,
            });
        let depth_view = ctx
            .device
            .create_image_view(&depth_view_ci, None)
            .map_err(|e| format!("depth view {e:?}"))?;
        // Classic RenderPass (Vulkan 1.0) avoids KHR_dynamic_rendering loader crashes.
        let color_att = vk::AttachmentDescription::default()
            .format(chosen_format.format)
            .samples(vk::SampleCountFlags::TYPE_1)
            .load_op(vk::AttachmentLoadOp::CLEAR)
            .store_op(vk::AttachmentStoreOp::STORE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(vk::ImageLayout::PRESENT_SRC_KHR);
        let depth_att = vk::AttachmentDescription::default()
            .format(depth_format)
            .samples(vk::SampleCountFlags::TYPE_1)
            .load_op(vk::AttachmentLoadOp::CLEAR)
            .store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        let attachments = [color_att, depth_att];
        let color_ref = vk::AttachmentReference::default()
            .attachment(0)
            .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL);
        let depth_ref = vk::AttachmentReference::default()
            .attachment(1)
            .layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        let subpass = vk::SubpassDescription::default()
            .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
            .color_attachments(std::slice::from_ref(&color_ref))
            .depth_stencil_attachment(&depth_ref);
        let dep = vk::SubpassDependency::default()
            .src_subpass(vk::SUBPASS_EXTERNAL)
            .dst_subpass(0)
            .src_stage_mask(
                vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT
                    | vk::PipelineStageFlags::EARLY_FRAGMENT_TESTS,
            )
            .dst_stage_mask(
                vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT
                    | vk::PipelineStageFlags::EARLY_FRAGMENT_TESTS,
            )
            .dst_access_mask(
                vk::AccessFlags::COLOR_ATTACHMENT_WRITE
                    | vk::AccessFlags::DEPTH_STENCIL_ATTACHMENT_WRITE,
            );
        let rp_info = vk::RenderPassCreateInfo::default()
            .attachments(&attachments)
            .subpasses(std::slice::from_ref(&subpass))
            .dependencies(std::slice::from_ref(&dep));
        let render_pass = ctx
            .device
            .create_render_pass(&rp_info, None)
            .map_err(|e| format!("rp {e:?}"))?;
        let mut framebuffers = Vec::with_capacity(image_views.len());
        for &iv in &image_views {
            let fb_attachments = [iv, depth_view];
            let fb_info = vk::FramebufferCreateInfo::default()
                .render_pass(render_pass)
                .attachments(&fb_attachments)
                .width(extent.width)
                .height(extent.height)
                .layers(1);
            framebuffers.push(
                ctx.device
                    .create_framebuffer(&fb_info, None)
                    .map_err(|e| format!("fb {e:?}"))?,
            );
        }
        Ok(Self {
            loader,
            swapchain,
            images,
            image_views,
            format: chosen_format.format,
            extent,
            depth_image,
            depth_mem,
            depth_view,
            render_pass,
            framebuffers,
            pre_transform,
        })
    }

    /// Free framebuffers, render pass, depth, views, swapchain.
    pub fn cleanup(&mut self, device: &ash::Device) {
        unsafe {
            for &fb in &self.framebuffers {
                device.destroy_framebuffer(fb, None);
            }
            self.framebuffers.clear();
            device.destroy_render_pass(self.render_pass, None);
            device.destroy_image_view(self.depth_view, None);
            device.free_memory(self.depth_mem, None);
            device.destroy_image(self.depth_image, None);
            for &v in &self.image_views {
                device.destroy_image_view(v, None);
            }
            self.image_views.clear();
            self.loader.destroy_swapchain(self.swapchain, None);
        }
    }
}
