use ash::vk;
use std::ffi::CString;
const BLOCK_VERT_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/block.vert.spv"));
const BLOCK_FRAG_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/block.frag.spv"));
const SKY_VERT_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/sky.vert.spv"));
const SKY_FRAG_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/sky.frag.spv"));
const CLOUD_FRAG_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/cloud.frag.spv"));
const ENTITY_VERT_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/entity.vert.spv"));
const ENTITY_FRAG_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/entity.frag.spv"));
const SHADOW_VERT_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/shadow.vert.spv"));
const WATER_VERT_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/water.vert.spv"));
const WATER_FRAG_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/water.frag.spv"));
const POST_VERT_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/post.vert.spv"));
const BRIGHT_FRAG_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/bright.frag.spv"));
const BLUR_FRAG_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/blur.frag.spv"));
const COMPOSITE_FRAG_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/composite.frag.spv"));
pub struct Pipelines {
    pub layout: vk::PipelineLayout,
    pub pipeline: vk::Pipeline,
    pub sky_pipeline: vk::Pipeline,
    pub cloud_pipeline: vk::Pipeline,
    pub entity_pipeline: vk::Pipeline,
    pub shadow_pipeline: vk::Pipeline,
    pub water_pipeline: vk::Pipeline,
    pub post_layout: vk::PipelineLayout,
    pub bright_pipeline: vk::Pipeline,
    pub blur_pipeline: vk::Pipeline,
    pub composite_pipeline: vk::Pipeline,
    pub descriptor_set_layout: vk::DescriptorSetLayout,
    pub descriptor_pool: vk::DescriptorPool,
    pub descriptor_set: vk::DescriptorSet,
}
impl Pipelines {
    // Classic Vulkan 1.0 RenderPass path - no KHR_dynamic_rendering, avoids Unable to load cmd_begin_rendering
    pub unsafe fn new(device: &ash::Device, render_pass: vk::RenderPass, shadow_render_pass: vk::RenderPass, composite_rp: vk::RenderPass, post_rp: vk::RenderPass, post_desc_layout: vk::DescriptorSetLayout, ubo_buffer: vk::Buffer, ubo_size: u64, atlas_view: vk::ImageView, atlas_sampler: vk::Sampler, shadow_view: vk::ImageView, shadow_sampler: vk::Sampler, entity_view: vk::ImageView, entity_sampler: vk::Sampler) -> Result<Self, String> {
        let bindings = [
            vk::DescriptorSetLayoutBinding::default().binding(0).descriptor_type(vk::DescriptorType::UNIFORM_BUFFER).descriptor_count(1).stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT),
            vk::DescriptorSetLayoutBinding::default().binding(1).descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
            vk::DescriptorSetLayoutBinding::default().binding(2).descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
            vk::DescriptorSetLayoutBinding::default().binding(3).descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
        ];
        let dsl = device.create_descriptor_set_layout(&vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings), None).map_err(|e| format!("dsl {e:?}"))?;
        let layouts = [dsl];
        let layout = device.create_pipeline_layout(&vk::PipelineLayoutCreateInfo::default().set_layouts(&layouts), None).map_err(|e| format!("layout {e:?}"))?;
        let pool_sizes = [vk::DescriptorPoolSize::default().ty(vk::DescriptorType::UNIFORM_BUFFER).descriptor_count(2), vk::DescriptorPoolSize::default().ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).descriptor_count(6)];
        let pool = device.create_descriptor_pool(&vk::DescriptorPoolCreateInfo::default().pool_sizes(&pool_sizes).max_sets(2), None).map_err(|e| format!("pool {e:?}"))?;
        let sets = device.allocate_descriptor_sets(&vk::DescriptorSetAllocateInfo::default().descriptor_pool(pool).set_layouts(&layouts)).map_err(|e| format!("alloc {e:?}"))?;
        let set = sets[0];
        let buf_info = vk::DescriptorBufferInfo::default().buffer(ubo_buffer).offset(0).range(ubo_size);
        let img_info = vk::DescriptorImageInfo::default().image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL).image_view(atlas_view).sampler(atlas_sampler);
        let shadow_info = vk::DescriptorImageInfo::default().image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL).image_view(shadow_view).sampler(shadow_sampler);
        let entity_info = vk::DescriptorImageInfo::default().image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL).image_view(entity_view).sampler(entity_sampler);
        let writes = [
            vk::WriteDescriptorSet::default().dst_set(set).dst_binding(0).descriptor_type(vk::DescriptorType::UNIFORM_BUFFER).buffer_info(std::slice::from_ref(&buf_info)),
            vk::WriteDescriptorSet::default().dst_set(set).dst_binding(1).descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).image_info(std::slice::from_ref(&img_info)),
            vk::WriteDescriptorSet::default().dst_set(set).dst_binding(2).descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).image_info(std::slice::from_ref(&shadow_info)),
            vk::WriteDescriptorSet::default().dst_set(set).dst_binding(3).descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).image_info(std::slice::from_ref(&entity_info)),
        ];
        device.update_descriptor_sets(&writes, &[]);
        let vert_mod = Self::create_module(device, BLOCK_VERT_SPV)?;
        let frag_mod = Self::create_module(device, BLOCK_FRAG_SPV)?;
        let entry = CString::new("main").unwrap();
        let stages = [
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::VERTEX).module(vert_mod).name(&entry),
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::FRAGMENT).module(frag_mod).name(&entry)
        ];
        let binding = crate::vulkan::buffers::Vertex::binding_description();
        let attrs = crate::vulkan::buffers::Vertex::attribute_descriptions();
        let vi = vk::PipelineVertexInputStateCreateInfo::default().vertex_binding_descriptions(std::slice::from_ref(&binding)).vertex_attribute_descriptions(&attrs);
        let ia = vk::PipelineInputAssemblyStateCreateInfo::default().topology(vk::PrimitiveTopology::TRIANGLE_LIST);
        let vp_state = vk::PipelineViewportStateCreateInfo::default().viewport_count(1).scissor_count(1);
        // Fix black: cull NONE because Vulkan Y flip (-1) inverts winding, old BACK culled everything
        let raster = vk::PipelineRasterizationStateCreateInfo::default().polygon_mode(vk::PolygonMode::FILL).cull_mode(vk::CullModeFlags::NONE).front_face(vk::FrontFace::COUNTER_CLOCKWISE).line_width(1.0);
        let ms = vk::PipelineMultisampleStateCreateInfo::default().rasterization_samples(vk::SampleCountFlags::TYPE_1);
        let ds = vk::PipelineDepthStencilStateCreateInfo::default().depth_test_enable(true).depth_write_enable(true).depth_compare_op(vk::CompareOp::LESS);
        let att = vk::PipelineColorBlendAttachmentState::default().color_write_mask(vk::ColorComponentFlags::RGBA).blend_enable(true).src_color_blend_factor(vk::BlendFactor::SRC_ALPHA).dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA);
        let cb = vk::PipelineColorBlendStateCreateInfo::default().attachments(std::slice::from_ref(&att));
        let dyn_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
        let dyn_state = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dyn_states);
        let pi = vk::GraphicsPipelineCreateInfo::default().stages(&stages).vertex_input_state(&vi).input_assembly_state(&ia).viewport_state(&vp_state).rasterization_state(&raster).multisample_state(&ms).depth_stencil_state(&ds).color_blend_state(&cb).dynamic_state(&dyn_state).layout(layout).render_pass(render_pass).subpass(0);
        let pipes = device.create_graphics_pipelines(vk::PipelineCache::null(), std::slice::from_ref(&pi), None).map_err(|(_, e)| format!("pipe {e:?}"))?;
        device.destroy_shader_module(vert_mod, None);
        device.destroy_shader_module(frag_mod, None);

        // Sky pipeline: fullscreen triangle, no vertex input, no depth test/write, opaque. Reuses the
        // same pipeline layout / descriptor set (UBO at binding 0) and render pass as the block pass.
        let sky_vert_mod = Self::create_module(device, SKY_VERT_SPV)?;
        let sky_frag_mod = Self::create_module(device, SKY_FRAG_SPV)?;
        let sky_stages = [
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::VERTEX).module(sky_vert_mod).name(&entry),
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::FRAGMENT).module(sky_frag_mod).name(&entry)
        ];
        let sky_vi = vk::PipelineVertexInputStateCreateInfo::default();
        // Depth-tested (LEQUAL, no write) so the sky only fills pixels terrain didn't cover.
        let sky_ds = vk::PipelineDepthStencilStateCreateInfo::default().depth_test_enable(true).depth_write_enable(false).depth_compare_op(vk::CompareOp::LESS_OR_EQUAL);
        let sky_att = vk::PipelineColorBlendAttachmentState::default().color_write_mask(vk::ColorComponentFlags::RGBA).blend_enable(false);
        let sky_cb = vk::PipelineColorBlendStateCreateInfo::default().attachments(std::slice::from_ref(&sky_att));
        let sky_pi = vk::GraphicsPipelineCreateInfo::default().stages(&sky_stages).vertex_input_state(&sky_vi).input_assembly_state(&ia).viewport_state(&vp_state).rasterization_state(&raster).multisample_state(&ms).depth_stencil_state(&sky_ds).color_blend_state(&sky_cb).dynamic_state(&dyn_state).layout(layout).render_pass(render_pass).subpass(0);
        let sky_pipes = device.create_graphics_pipelines(vk::PipelineCache::null(), std::slice::from_ref(&sky_pi), None).map_err(|(_, e)| format!("sky pipe {e:?}"))?;
        device.destroy_shader_module(sky_vert_mod, None);
        device.destroy_shader_module(sky_frag_mod, None);

        // Cloud pipeline: fullscreen volumetric clouds drawn after the sky. Depth-tested (LEQUAL) against
        // terrain but no depth write; writes gl_FragDepth (slab entry) so terrain occludes clouds and
        // vice versa. Premultiplied-alpha blend (ONE, ONE_MINUS_SRC_ALPHA) composites over the scene.
        let cloud_vert_mod = Self::create_module(device, SKY_VERT_SPV)?;
        let cloud_frag_mod = Self::create_module(device, CLOUD_FRAG_SPV)?;
        let cloud_stages = [
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::VERTEX).module(cloud_vert_mod).name(&entry),
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::FRAGMENT).module(cloud_frag_mod).name(&entry)
        ];
        let cloud_vi = vk::PipelineVertexInputStateCreateInfo::default();
        let cloud_ds = vk::PipelineDepthStencilStateCreateInfo::default().depth_test_enable(true).depth_write_enable(false).depth_compare_op(vk::CompareOp::LESS_OR_EQUAL);
        let cloud_att = vk::PipelineColorBlendAttachmentState::default().color_write_mask(vk::ColorComponentFlags::RGBA).blend_enable(true)
            .src_color_blend_factor(vk::BlendFactor::ONE).dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA).color_blend_op(vk::BlendOp::ADD)
            .src_alpha_blend_factor(vk::BlendFactor::ONE).dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA).alpha_blend_op(vk::BlendOp::ADD);
        let cloud_cb = vk::PipelineColorBlendStateCreateInfo::default().attachments(std::slice::from_ref(&cloud_att));
        let cloud_pi = vk::GraphicsPipelineCreateInfo::default().stages(&cloud_stages).vertex_input_state(&cloud_vi).input_assembly_state(&ia).viewport_state(&vp_state).rasterization_state(&raster).multisample_state(&ms).depth_stencil_state(&cloud_ds).color_blend_state(&cloud_cb).dynamic_state(&dyn_state).layout(layout).render_pass(render_pass).subpass(0);
        let cloud_pipes = device.create_graphics_pipelines(vk::PipelineCache::null(), std::slice::from_ref(&cloud_pi), None).map_err(|(_, e)| format!("cloud pipe {e:?}"))?;
        device.destroy_shader_module(cloud_vert_mod, None);
        device.destroy_shader_module(cloud_frag_mod, None);

        // Entity pipeline: mob box models, opaque (alpha-tested in the shader), depth-tested + written,
        // same block vertex format + descriptor set (samples the entity atlas at binding 3).
        let entity_vert_mod = Self::create_module(device, ENTITY_VERT_SPV)?;
        let entity_frag_mod = Self::create_module(device, ENTITY_FRAG_SPV)?;
        let entity_stages = [
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::VERTEX).module(entity_vert_mod).name(&entry),
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::FRAGMENT).module(entity_frag_mod).name(&entry)
        ];
        let entity_ds = vk::PipelineDepthStencilStateCreateInfo::default().depth_test_enable(true).depth_write_enable(true).depth_compare_op(vk::CompareOp::LESS);
        let entity_att = vk::PipelineColorBlendAttachmentState::default().color_write_mask(vk::ColorComponentFlags::RGBA).blend_enable(false);
        let entity_cb = vk::PipelineColorBlendStateCreateInfo::default().attachments(std::slice::from_ref(&entity_att));
        let entity_pi = vk::GraphicsPipelineCreateInfo::default().stages(&entity_stages).vertex_input_state(&vi).input_assembly_state(&ia).viewport_state(&vp_state).rasterization_state(&raster).multisample_state(&ms).depth_stencil_state(&entity_ds).color_blend_state(&entity_cb).dynamic_state(&dyn_state).layout(layout).render_pass(render_pass).subpass(0);
        let entity_pipes = device.create_graphics_pipelines(vk::PipelineCache::null(), std::slice::from_ref(&entity_pi), None).map_err(|(_, e)| format!("entity pipe {e:?}"))?;
        device.destroy_shader_module(entity_vert_mod, None);
        device.destroy_shader_module(entity_frag_mod, None);

        // Shadow pipeline: depth-only, renders terrain from the sun into the shadow render pass. No
        // fragment shader / color attachment; depth bias reduces shadow acne.
        let shadow_vert_mod = Self::create_module(device, SHADOW_VERT_SPV)?;
        let shadow_stages = [vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::VERTEX).module(shadow_vert_mod).name(&entry)];
        let shadow_binding = crate::vulkan::buffers::Vertex::binding_description();
        let shadow_attrs = [vk::VertexInputAttributeDescription::default().binding(0).location(0).format(vk::Format::R32G32B32_SFLOAT).offset(0)];
        let shadow_vi = vk::PipelineVertexInputStateCreateInfo::default().vertex_binding_descriptions(std::slice::from_ref(&shadow_binding)).vertex_attribute_descriptions(&shadow_attrs);
        let shadow_raster = vk::PipelineRasterizationStateCreateInfo::default().polygon_mode(vk::PolygonMode::FILL).cull_mode(vk::CullModeFlags::NONE).front_face(vk::FrontFace::COUNTER_CLOCKWISE).line_width(1.0)
            .depth_bias_enable(true).depth_bias_constant_factor(1.5).depth_bias_slope_factor(2.0);
        let shadow_ds = vk::PipelineDepthStencilStateCreateInfo::default().depth_test_enable(true).depth_write_enable(true).depth_compare_op(vk::CompareOp::LESS);
        let shadow_cb = vk::PipelineColorBlendStateCreateInfo::default(); // no color attachments
        let shadow_pi = vk::GraphicsPipelineCreateInfo::default().stages(&shadow_stages).vertex_input_state(&shadow_vi).input_assembly_state(&ia).viewport_state(&vp_state).rasterization_state(&shadow_raster).multisample_state(&ms).depth_stencil_state(&shadow_ds).color_blend_state(&shadow_cb).dynamic_state(&dyn_state).layout(layout).render_pass(shadow_render_pass).subpass(0);
        let shadow_pipes = device.create_graphics_pipelines(vk::PipelineCache::null(), std::slice::from_ref(&shadow_pi), None).map_err(|(_, e)| format!("shadow pipe {e:?}"))?;
        device.destroy_shader_module(shadow_vert_mod, None);

        // Water pipeline: transparent (alpha blend), depth-tested but no depth write, in the main pass.
        let water_vert_mod = Self::create_module(device, WATER_VERT_SPV)?;
        let water_frag_mod = Self::create_module(device, WATER_FRAG_SPV)?;
        let water_stages = [
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::VERTEX).module(water_vert_mod).name(&entry),
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::FRAGMENT).module(water_frag_mod).name(&entry)
        ];
        let water_ds = vk::PipelineDepthStencilStateCreateInfo::default().depth_test_enable(true).depth_write_enable(false).depth_compare_op(vk::CompareOp::LESS_OR_EQUAL);
        let water_att = vk::PipelineColorBlendAttachmentState::default().color_write_mask(vk::ColorComponentFlags::RGBA).blend_enable(true).src_color_blend_factor(vk::BlendFactor::SRC_ALPHA).dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA).color_blend_op(vk::BlendOp::ADD).src_alpha_blend_factor(vk::BlendFactor::ONE).dst_alpha_blend_factor(vk::BlendFactor::ZERO);
        let water_cb = vk::PipelineColorBlendStateCreateInfo::default().attachments(std::slice::from_ref(&water_att));
        let water_pi = vk::GraphicsPipelineCreateInfo::default().stages(&water_stages).vertex_input_state(&vi).input_assembly_state(&ia).viewport_state(&vp_state).rasterization_state(&raster).multisample_state(&ms).depth_stencil_state(&water_ds).color_blend_state(&water_cb).dynamic_state(&dyn_state).layout(layout).render_pass(render_pass).subpass(0);
        let water_pipes = device.create_graphics_pipelines(vk::PipelineCache::null(), std::slice::from_ref(&water_pi), None).map_err(|(_, e)| format!("water pipe {e:?}"))?;
        device.destroy_shader_module(water_vert_mod, None);
        device.destroy_shader_module(water_frag_mod, None);

        // --- Post-processing pipelines (fullscreen): bloom bright/blur + FXAA composite. ---
        let push = vk::PushConstantRange::default().stage_flags(vk::ShaderStageFlags::FRAGMENT).offset(0).size(16);
        let post_set_layouts = [post_desc_layout];
        let post_layout = device.create_pipeline_layout(&vk::PipelineLayoutCreateInfo::default().set_layouts(&post_set_layouts).push_constant_ranges(std::slice::from_ref(&push)), None).map_err(|e| format!("post layout {e:?}"))?;
        let post_vert_mod = Self::create_module(device, POST_VERT_SPV)?;
        let bright_mod = Self::create_module(device, BRIGHT_FRAG_SPV)?;
        let blur_mod = Self::create_module(device, BLUR_FRAG_SPV)?;
        let composite_mod = Self::create_module(device, COMPOSITE_FRAG_SPV)?;
        let empty_vi = vk::PipelineVertexInputStateCreateInfo::default();
        let post_ds = vk::PipelineDepthStencilStateCreateInfo::default().depth_test_enable(false).depth_write_enable(false);
        let post_att = vk::PipelineColorBlendAttachmentState::default().color_write_mask(vk::ColorComponentFlags::RGBA).blend_enable(false);
        let post_cb = vk::PipelineColorBlendStateCreateInfo::default().attachments(std::slice::from_ref(&post_att));
        let mk_post = |device: &ash::Device, frag: vk::ShaderModule, rp: vk::RenderPass| -> Result<vk::Pipeline, String> {
            let stages = [
                vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::VERTEX).module(post_vert_mod).name(&entry),
                vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::FRAGMENT).module(frag).name(&entry),
            ];
            let pi = vk::GraphicsPipelineCreateInfo::default().stages(&stages).vertex_input_state(&empty_vi).input_assembly_state(&ia).viewport_state(&vp_state).rasterization_state(&raster).multisample_state(&ms).depth_stencil_state(&post_ds).color_blend_state(&post_cb).dynamic_state(&dyn_state).layout(post_layout).render_pass(rp).subpass(0);
            let p = device.create_graphics_pipelines(vk::PipelineCache::null(), std::slice::from_ref(&pi), None).map_err(|(_, e)| format!("post pipe {e:?}"))?;
            Ok(p[0])
        };
        let bright_pipeline = mk_post(device, bright_mod, post_rp)?;
        let blur_pipeline = mk_post(device, blur_mod, post_rp)?;
        let composite_pipeline = mk_post(device, composite_mod, composite_rp)?;
        device.destroy_shader_module(post_vert_mod, None);
        device.destroy_shader_module(bright_mod, None);
        device.destroy_shader_module(blur_mod, None);
        device.destroy_shader_module(composite_mod, None);

        Ok(Self{layout, pipeline: pipes[0], sky_pipeline: sky_pipes[0], cloud_pipeline: cloud_pipes[0], entity_pipeline: entity_pipes[0], shadow_pipeline: shadow_pipes[0], water_pipeline: water_pipes[0], post_layout, bright_pipeline, blur_pipeline, composite_pipeline, descriptor_set_layout: dsl, descriptor_pool: pool, descriptor_set: set})
    }
    unsafe fn create_module(device: &ash::Device, spv: &[u8]) -> Result<vk::ShaderModule, String> {
        let code = unsafe { std::slice::from_raw_parts(spv.as_ptr() as *const u32, spv.len()/4) };
        device.create_shader_module(&vk::ShaderModuleCreateInfo::default().code(code), None).map_err(|e| format!("mod {e:?}"))
    }
    pub unsafe fn destroy(&mut self, device: &ash::Device) {
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline(self.sky_pipeline, None);
        device.destroy_pipeline(self.cloud_pipeline, None);
        device.destroy_pipeline(self.entity_pipeline, None);
        device.destroy_pipeline(self.shadow_pipeline, None);
        device.destroy_pipeline(self.water_pipeline, None);
        device.destroy_pipeline(self.bright_pipeline, None);
        device.destroy_pipeline(self.blur_pipeline, None);
        device.destroy_pipeline(self.composite_pipeline, None);
        device.destroy_pipeline_layout(self.post_layout, None);
        device.destroy_pipeline_layout(self.layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
    }
}
