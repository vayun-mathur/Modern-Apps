use ash::vk;

pub const SHADOW_DIM: u32 = 2048;
const SHADOW_FORMAT: vk::Format = vk::Format::D32_SFLOAT;

// A depth-only render target the terrain is rasterized into from the sun's point of view. Sampled in
// the terrain fragment shader (binding 2) to decide whether a fragment is in sun shadow.
pub struct ShadowMap {
    pub image: vk::Image,
    pub memory: vk::DeviceMemory,
    pub view: vk::ImageView,
    pub sampler: vk::Sampler,
    pub render_pass: vk::RenderPass,
    pub framebuffer: vk::Framebuffer,
}

impl ShadowMap {
    /// Creates the shadow map target.
    ///
    /// # Safety
    /// The Vulkan handles passed in must be valid, created from the same instance/device, and must not be destroyed or used concurrently while this call runs.
    pub unsafe fn new(instance: &ash::Instance, device: &ash::Device, phys: vk::PhysicalDevice) -> Result<Self, String> {
        let img_ci = vk::ImageCreateInfo::default()
            .image_type(vk::ImageType::TYPE_2D)
            .format(SHADOW_FORMAT)
            .extent(vk::Extent3D { width: SHADOW_DIM, height: SHADOW_DIM, depth: 1 })
            .mip_levels(1).array_layers(1)
            .samples(vk::SampleCountFlags::TYPE_1)
            .tiling(vk::ImageTiling::OPTIMAL)
            .usage(vk::ImageUsageFlags::DEPTH_STENCIL_ATTACHMENT | vk::ImageUsageFlags::SAMPLED)
            .initial_layout(vk::ImageLayout::UNDEFINED);
        let image = device.create_image(&img_ci, None).map_err(|e| format!("shadow img {e:?}"))?;
        let reqs = device.get_image_memory_requirements(image);
        let mem_props = instance.get_physical_device_memory_properties(phys);
        let mem_type = (0..mem_props.memory_type_count).find(|&i| (reqs.memory_type_bits & (1<<i))!=0 && mem_props.memory_types[i as usize].property_flags.contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)).ok_or("no mem shadow")?;
        let alloc = vk::MemoryAllocateInfo::default().allocation_size(reqs.size).memory_type_index(mem_type);
        let memory = device.allocate_memory(&alloc, None).map_err(|e| format!("shadow alloc {e:?}"))?;
        device.bind_image_memory(image, memory, 0).map_err(|e| format!("shadow bind {e:?}"))?;

        let view_ci = vk::ImageViewCreateInfo::default().image(image).view_type(vk::ImageViewType::TYPE_2D).format(SHADOW_FORMAT)
            .subresource_range(vk::ImageSubresourceRange { aspect_mask: vk::ImageAspectFlags::DEPTH, base_mip_level: 0, level_count: 1, base_array_layer: 0, layer_count: 1 });
        let view = device.create_image_view(&view_ci, None).map_err(|e| format!("shadow view {e:?}"))?;

        let sampler_ci = vk::SamplerCreateInfo::default().mag_filter(vk::Filter::NEAREST).min_filter(vk::Filter::NEAREST)
            .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
            .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE).address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE).address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
            .max_anisotropy(1.0).min_lod(0.0).max_lod(0.0);
        let sampler = device.create_sampler(&sampler_ci, None).map_err(|e| format!("shadow sampler {e:?}"))?;

        // Depth-only render pass; leaves the image in SHADER_READ_ONLY for sampling in the main pass.
        let att = vk::AttachmentDescription::default().format(SHADOW_FORMAT).samples(vk::SampleCountFlags::TYPE_1)
            .load_op(vk::AttachmentLoadOp::CLEAR).store_op(vk::AttachmentStoreOp::STORE)
            .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE).stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED).final_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL);
        let depth_ref = vk::AttachmentReference::default().attachment(0).layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        let subpass = vk::SubpassDescription::default().pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS).depth_stencil_attachment(&depth_ref);
        let deps = [
            // Previous frame's sampling must finish before we overwrite the depth.
            vk::SubpassDependency::default().src_subpass(vk::SUBPASS_EXTERNAL).dst_subpass(0)
                .src_stage_mask(vk::PipelineStageFlags::FRAGMENT_SHADER).dst_stage_mask(vk::PipelineStageFlags::EARLY_FRAGMENT_TESTS)
                .src_access_mask(vk::AccessFlags::SHADER_READ).dst_access_mask(vk::AccessFlags::DEPTH_STENCIL_ATTACHMENT_WRITE),
            // Depth writes must be visible to the main pass sampling this map.
            vk::SubpassDependency::default().src_subpass(0).dst_subpass(vk::SUBPASS_EXTERNAL)
                .src_stage_mask(vk::PipelineStageFlags::LATE_FRAGMENT_TESTS).dst_stage_mask(vk::PipelineStageFlags::FRAGMENT_SHADER)
                .src_access_mask(vk::AccessFlags::DEPTH_STENCIL_ATTACHMENT_WRITE).dst_access_mask(vk::AccessFlags::SHADER_READ),
        ];
        let rp_ci = vk::RenderPassCreateInfo::default().attachments(std::slice::from_ref(&att)).subpasses(std::slice::from_ref(&subpass)).dependencies(&deps);
        let render_pass = device.create_render_pass(&rp_ci, None).map_err(|e| format!("shadow rp {e:?}"))?;

        let fb_ci = vk::FramebufferCreateInfo::default().render_pass(render_pass).attachments(std::slice::from_ref(&view)).width(SHADOW_DIM).height(SHADOW_DIM).layers(1);
        let framebuffer = device.create_framebuffer(&fb_ci, None).map_err(|e| format!("shadow fb {e:?}"))?;

        Ok(Self { image, memory, view, sampler, render_pass, framebuffer })
    }

    /// Destroys the shadow map handles.
    ///
    /// # Safety
    /// The handles owned by `self` must have been created on `device`, must not already have been destroyed, and no in-flight GPU work may still use them.
    pub unsafe fn destroy(&mut self, device: &ash::Device) {
        device.destroy_framebuffer(self.framebuffer, None);
        device.destroy_render_pass(self.render_pass, None);
        device.destroy_sampler(self.sampler, None);
        device.destroy_image_view(self.view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}
