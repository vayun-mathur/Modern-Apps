use ash::vk;
use crate::vulkan::swapchain::Swapchain;

pub const HDR_FORMAT: vk::Format = vk::Format::R16G16B16A16_SFLOAT;

// Offscreen HDR pipeline for bloom + FXAA. The world is rendered to `hdr` (linear HDR), a bright-pass +
// separable blur build the bloom in half-res `bloom_a/bloom_b`, then a composite pass (using the
// swapchain's own render pass) does FXAA + bloom add + tonemap to the swapchain.
pub struct PostFx {
    pub main_rp: vk::RenderPass,   // -> hdr (+ depth)
    pub post_rp: vk::RenderPass,   // -> a bloom target
    pub sampler: vk::Sampler,
    pub desc_layout: vk::DescriptorSetLayout,
    desc_pool: vk::DescriptorPool,
    pub bright_set: vk::DescriptorSet,
    pub blur_h_set: vk::DescriptorSet,
    pub blur_v_set: vk::DescriptorSet,
    pub composite_set: vk::DescriptorSet,

    pub extent: vk::Extent2D,
    pub bloom_extent: vk::Extent2D,
    hdr_img: vk::Image, hdr_mem: vk::DeviceMemory, pub hdr_view: vk::ImageView,
    ba_img: vk::Image, ba_mem: vk::DeviceMemory, pub bloom_a_view: vk::ImageView,
    bb_img: vk::Image, bb_mem: vk::DeviceMemory, pub bloom_b_view: vk::ImageView,
    pub main_fb: vk::Framebuffer,
    pub bloom_a_fb: vk::Framebuffer,
    pub bloom_b_fb: vk::Framebuffer,
}

unsafe fn mem_type(instance: &ash::Instance, phys: vk::PhysicalDevice, bits: u32, props: vk::MemoryPropertyFlags) -> Result<u32, String> {
    let mp = instance.get_physical_device_memory_properties(phys);
    (0..mp.memory_type_count).find(|&i| (bits & (1<<i))!=0 && mp.memory_types[i as usize].property_flags.contains(props)).ok_or("no mem type".into())
}

unsafe fn make_image(instance: &ash::Instance, device: &ash::Device, phys: vk::PhysicalDevice, extent: vk::Extent2D, format: vk::Format, usage: vk::ImageUsageFlags, aspect: vk::ImageAspectFlags) -> Result<(vk::Image, vk::DeviceMemory, vk::ImageView), String> {
    let (w, h) = (extent.width, extent.height);
    let ci = vk::ImageCreateInfo::default().image_type(vk::ImageType::TYPE_2D).format(format)
        .extent(vk::Extent3D{width:w.max(1),height:h.max(1),depth:1}).mip_levels(1).array_layers(1)
        .samples(vk::SampleCountFlags::TYPE_1).tiling(vk::ImageTiling::OPTIMAL).usage(usage).initial_layout(vk::ImageLayout::UNDEFINED);
    let img = device.create_image(&ci, None).map_err(|e| format!("postfx img {e:?}"))?;
    let reqs = device.get_image_memory_requirements(img);
    let mt = mem_type(instance, phys, reqs.memory_type_bits, vk::MemoryPropertyFlags::DEVICE_LOCAL)?;
    let mem = device.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(reqs.size).memory_type_index(mt), None).map_err(|e| format!("postfx mem {e:?}"))?;
    device.bind_image_memory(img, mem, 0).map_err(|e| format!("postfx bind {e:?}"))?;
    let view = device.create_image_view(&vk::ImageViewCreateInfo::default().image(img).view_type(vk::ImageViewType::TYPE_2D).format(format)
        .subresource_range(vk::ImageSubresourceRange{aspect_mask:aspect,base_mip_level:0,level_count:1,base_array_layer:0,layer_count:1}), None).map_err(|e| format!("postfx view {e:?}"))?;
    Ok((img, mem, view))
}

fn color_rp(device: &ash::Device, format: vk::Format, with_depth: Option<vk::Format>) -> Result<vk::RenderPass, String> {
    let mut atts = vec![vk::AttachmentDescription::default().format(format).samples(vk::SampleCountFlags::TYPE_1)
        .load_op(vk::AttachmentLoadOp::CLEAR).store_op(vk::AttachmentStoreOp::STORE)
        .initial_layout(vk::ImageLayout::UNDEFINED).final_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
    let color_ref = vk::AttachmentReference::default().attachment(0).layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL);
    let depth_ref = vk::AttachmentReference::default().attachment(1).layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
    let mut subpass = vk::SubpassDescription::default().pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS).color_attachments(std::slice::from_ref(&color_ref));
    if let Some(df) = with_depth {
        atts.push(vk::AttachmentDescription::default().format(df).samples(vk::SampleCountFlags::TYPE_1)
            .load_op(vk::AttachmentLoadOp::CLEAR).store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED).final_layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL));
        subpass = subpass.depth_stencil_attachment(&depth_ref);
    }
    let deps = [
        vk::SubpassDependency::default().src_subpass(vk::SUBPASS_EXTERNAL).dst_subpass(0)
            .src_stage_mask(vk::PipelineStageFlags::FRAGMENT_SHADER).dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
            .src_access_mask(vk::AccessFlags::SHADER_READ).dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE),
        vk::SubpassDependency::default().src_subpass(0).dst_subpass(vk::SUBPASS_EXTERNAL)
            .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT).dst_stage_mask(vk::PipelineStageFlags::FRAGMENT_SHADER)
            .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE).dst_access_mask(vk::AccessFlags::SHADER_READ),
    ];
    let ci = vk::RenderPassCreateInfo::default().attachments(&atts).subpasses(std::slice::from_ref(&subpass)).dependencies(&deps);
    unsafe { device.create_render_pass(&ci, None) }.map_err(|e| format!("postfx rp {e:?}"))
}

impl PostFx {
    /// Creates the post-processing render passes and targets.
    ///
    /// # Safety
    /// The Vulkan handles passed in must be valid, created from the same instance/device, and must not be destroyed or used concurrently while this call runs. `swapchain` must be live and its depth view valid.
    pub unsafe fn new(instance: &ash::Instance, device: &ash::Device, phys: vk::PhysicalDevice, swapchain: &Swapchain, depth_format: vk::Format) -> Result<Self, String> {
        let main_rp = color_rp(device, HDR_FORMAT, Some(depth_format))?;
        let post_rp = color_rp(device, HDR_FORMAT, None)?;
        let sampler = device.create_sampler(&vk::SamplerCreateInfo::default().mag_filter(vk::Filter::LINEAR).min_filter(vk::Filter::LINEAR)
            .mipmap_mode(vk::SamplerMipmapMode::NEAREST).address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE).address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE).address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE).max_lod(0.0), None).map_err(|e| format!("postfx sampler {e:?}"))?;
        let bindings = [
            vk::DescriptorSetLayoutBinding::default().binding(0).descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
            vk::DescriptorSetLayoutBinding::default().binding(1).descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
        ];
        let desc_layout = device.create_descriptor_set_layout(&vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings), None).map_err(|e| format!("postfx dsl {e:?}"))?;
        let pool_sizes = [vk::DescriptorPoolSize::default().ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).descriptor_count(16)];
        let desc_pool = device.create_descriptor_pool(&vk::DescriptorPoolCreateInfo::default().pool_sizes(&pool_sizes).max_sets(4), None).map_err(|e| format!("postfx pool {e:?}"))?;
        let layouts = [desc_layout, desc_layout, desc_layout, desc_layout];
        let sets = device.allocate_descriptor_sets(&vk::DescriptorSetAllocateInfo::default().descriptor_pool(desc_pool).set_layouts(&layouts)).map_err(|e| format!("postfx sets {e:?}"))?;
        let mut pf = Self {
            main_rp, post_rp, sampler, desc_layout, desc_pool,
            bright_set: sets[0], blur_h_set: sets[1], blur_v_set: sets[2], composite_set: sets[3],
            extent: vk::Extent2D{width:1,height:1}, bloom_extent: vk::Extent2D{width:1,height:1},
            hdr_img: vk::Image::null(), hdr_mem: vk::DeviceMemory::null(), hdr_view: vk::ImageView::null(),
            ba_img: vk::Image::null(), ba_mem: vk::DeviceMemory::null(), bloom_a_view: vk::ImageView::null(),
            bb_img: vk::Image::null(), bb_mem: vk::DeviceMemory::null(), bloom_b_view: vk::ImageView::null(),
            main_fb: vk::Framebuffer::null(), bloom_a_fb: vk::Framebuffer::null(), bloom_b_fb: vk::Framebuffer::null(),
        };
        pf.create_targets(instance, device, phys, swapchain)?;
        Ok(pf)
    }

    unsafe fn create_targets(&mut self, instance: &ash::Instance, device: &ash::Device, phys: vk::PhysicalDevice, swapchain: &Swapchain) -> Result<(), String> {
        let ext = swapchain.extent;
        self.extent = ext;
        self.bloom_extent = vk::Extent2D { width: (ext.width/2).max(1), height: (ext.height/2).max(1) };
        let (hi, hm, hv) = make_image(instance, device, phys, ext, HDR_FORMAT, vk::ImageUsageFlags::COLOR_ATTACHMENT | vk::ImageUsageFlags::SAMPLED, vk::ImageAspectFlags::COLOR)?;
        let (ai, am, av) = make_image(instance, device, phys, self.bloom_extent, HDR_FORMAT, vk::ImageUsageFlags::COLOR_ATTACHMENT | vk::ImageUsageFlags::SAMPLED, vk::ImageAspectFlags::COLOR)?;
        let (bi, bm, bv) = make_image(instance, device, phys, self.bloom_extent, HDR_FORMAT, vk::ImageUsageFlags::COLOR_ATTACHMENT | vk::ImageUsageFlags::SAMPLED, vk::ImageAspectFlags::COLOR)?;
        self.hdr_img=hi; self.hdr_mem=hm; self.hdr_view=hv;
        self.ba_img=ai; self.ba_mem=am; self.bloom_a_view=av;
        self.bb_img=bi; self.bb_mem=bm; self.bloom_b_view=bv;
        let main_atts = [self.hdr_view, swapchain.depth_view];
        self.main_fb = device.create_framebuffer(&vk::FramebufferCreateInfo::default().render_pass(self.main_rp).attachments(&main_atts).width(ext.width).height(ext.height).layers(1), None).map_err(|e| format!("main fb {e:?}"))?;
        self.bloom_a_fb = device.create_framebuffer(&vk::FramebufferCreateInfo::default().render_pass(self.post_rp).attachments(std::slice::from_ref(&self.bloom_a_view)).width(self.bloom_extent.width).height(self.bloom_extent.height).layers(1), None).map_err(|e| format!("bloomA fb {e:?}"))?;
        self.bloom_b_fb = device.create_framebuffer(&vk::FramebufferCreateInfo::default().render_pass(self.post_rp).attachments(std::slice::from_ref(&self.bloom_b_view)).width(self.bloom_extent.width).height(self.bloom_extent.height).layers(1), None).map_err(|e| format!("bloomB fb {e:?}"))?;
        self.write_sets(device);
        Ok(())
    }

    unsafe fn write_sets(&self, device: &ash::Device) {
        let mk = |view: vk::ImageView| vk::DescriptorImageInfo::default().image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL).image_view(view).sampler(self.sampler);
        let hdr = mk(self.hdr_view); let ba = mk(self.bloom_a_view); let bb = mk(self.bloom_b_view);
        let cis = vk::DescriptorType::COMBINED_IMAGE_SAMPLER;
        let writes = [
            vk::WriteDescriptorSet::default().dst_set(self.bright_set).dst_binding(0).descriptor_type(cis).image_info(std::slice::from_ref(&hdr)),
            vk::WriteDescriptorSet::default().dst_set(self.bright_set).dst_binding(1).descriptor_type(cis).image_info(std::slice::from_ref(&hdr)),
            vk::WriteDescriptorSet::default().dst_set(self.blur_h_set).dst_binding(0).descriptor_type(cis).image_info(std::slice::from_ref(&ba)),
            vk::WriteDescriptorSet::default().dst_set(self.blur_h_set).dst_binding(1).descriptor_type(cis).image_info(std::slice::from_ref(&ba)),
            vk::WriteDescriptorSet::default().dst_set(self.blur_v_set).dst_binding(0).descriptor_type(cis).image_info(std::slice::from_ref(&bb)),
            vk::WriteDescriptorSet::default().dst_set(self.blur_v_set).dst_binding(1).descriptor_type(cis).image_info(std::slice::from_ref(&bb)),
            vk::WriteDescriptorSet::default().dst_set(self.composite_set).dst_binding(0).descriptor_type(cis).image_info(std::slice::from_ref(&hdr)),
            vk::WriteDescriptorSet::default().dst_set(self.composite_set).dst_binding(1).descriptor_type(cis).image_info(std::slice::from_ref(&ba)),
        ];
        device.update_descriptor_sets(&writes, &[]);
    }

    unsafe fn destroy_targets(&mut self, device: &ash::Device) {
        for fb in [self.main_fb, self.bloom_a_fb, self.bloom_b_fb] { if fb != vk::Framebuffer::null() { device.destroy_framebuffer(fb, None); } }
        for v in [self.hdr_view, self.bloom_a_view, self.bloom_b_view] { if v != vk::ImageView::null() { device.destroy_image_view(v, None); } }
        for im in [self.hdr_img, self.ba_img, self.bb_img] { if im != vk::Image::null() { device.destroy_image(im, None); } }
        for m in [self.hdr_mem, self.ba_mem, self.bb_mem] { if m != vk::DeviceMemory::null() { device.free_memory(m, None); } }
    }

    /// Recreates the sized targets for a new swapchain extent.
    ///
    /// # Safety
    /// The Vulkan handles passed in must be valid, created from the same instance/device, and must not be destroyed or used concurrently while this call runs.
    pub unsafe fn resize(&mut self, instance: &ash::Instance, device: &ash::Device, phys: vk::PhysicalDevice, swapchain: &Swapchain) -> Result<(), String> {
        self.destroy_targets(device);
        self.create_targets(instance, device, phys, swapchain)
    }

    /// Destroys the render passes, descriptor objects and targets.
    ///
    /// # Safety
    /// The handles owned by `self` must have been created on `device`, must not already have been destroyed, and no in-flight GPU work may still use them.
    pub unsafe fn destroy(&mut self, device: &ash::Device) {
        self.destroy_targets(device);
        device.destroy_descriptor_pool(self.desc_pool, None);
        device.destroy_descriptor_set_layout(self.desc_layout, None);
        device.destroy_sampler(self.sampler, None);
        device.destroy_render_pass(self.main_rp, None);
        device.destroy_render_pass(self.post_rp, None);
    }
}
