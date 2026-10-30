//! Graphics pipeline: one unlit vertex-color pipeline for the whole world.
//!
//! The kart shader takes per-vertex colors and a single UBO (view-projection
//! + sun + camera), so one descriptor set with one uniform buffer is the
//! entire layout — no samplers, no atlases. Culling is off: the shader's
//! Vulkan Y flip inverts winding (same fix as games/voxels, where BACK
//! culled everything to black).
use ash::vk;
use std::ffi::CString;

const KART_VERT_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/kart.vert.spv"));
const KART_FRAG_SPV: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/kart.frag.spv"));

/// Pipeline + layout + the single UBO descriptor set.
pub struct Pipelines {
    /// Pipeline layout (one UBO set).
    pub layout: vk::PipelineLayout,
    /// The kart graphics pipeline.
    pub pipeline: vk::Pipeline,
    /// Descriptor set layout (binding 0 = UBO).
    pub descriptor_set_layout: vk::DescriptorSetLayout,
    /// Descriptor pool (one set).
    pub descriptor_pool: vk::DescriptorPool,
    /// The allocated set (UBO bound at creation).
    pub descriptor_set: vk::DescriptorSet,
}

impl Pipelines {
    /// Build against the swapchain's render pass, binding `ubo_buffer`.
    pub unsafe fn new(
        device: &ash::Device,
        render_pass: vk::RenderPass,
        ubo_buffer: vk::Buffer,
        ubo_size: u64,
    ) -> Result<Self, String> {
        let bindings = [vk::DescriptorSetLayoutBinding::default()
            .binding(0)
            .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
            .descriptor_count(1)
            .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)];
        let dsl = device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                None,
            )
            .map_err(|e| format!("dsl {e:?}"))?;
        let layouts = [dsl];
        let layout = device
            .create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default().set_layouts(&layouts),
                None,
            )
            .map_err(|e| format!("layout {e:?}"))?;
        let pool_sizes = [vk::DescriptorPoolSize::default()
            .ty(vk::DescriptorType::UNIFORM_BUFFER)
            .descriptor_count(1)];
        let pool = device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default()
                    .pool_sizes(&pool_sizes)
                    .max_sets(1),
                None,
            )
            .map_err(|e| format!("pool {e:?}"))?;
        let sets = device
            .allocate_descriptor_sets(
                &vk::DescriptorSetAllocateInfo::default()
                    .descriptor_pool(pool)
                    .set_layouts(&layouts),
            )
            .map_err(|e| format!("alloc {e:?}"))?;
        let set = sets[0];
        let buf_info = vk::DescriptorBufferInfo::default()
            .buffer(ubo_buffer)
            .offset(0)
            .range(ubo_size);
        let writes = [vk::WriteDescriptorSet::default()
            .dst_set(set)
            .dst_binding(0)
            .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
            .buffer_info(std::slice::from_ref(&buf_info))];
        device.update_descriptor_sets(&writes, &[]);

        let vert_mod = Self::create_module(device, KART_VERT_SPV)?;
        let frag_mod = Self::create_module(device, KART_FRAG_SPV)?;
        let entry = CString::new("main").unwrap();
        let stages = [
            vk::PipelineShaderStageCreateInfo::default()
                .stage(vk::ShaderStageFlags::VERTEX)
                .module(vert_mod)
                .name(&entry),
            vk::PipelineShaderStageCreateInfo::default()
                .stage(vk::ShaderStageFlags::FRAGMENT)
                .module(frag_mod)
                .name(&entry),
        ];
        let binding = crate::vulkan::buffers::KartVkVertex::binding_description();
        let attrs = crate::vulkan::buffers::KartVkVertex::attribute_descriptions();
        let vi = vk::PipelineVertexInputStateCreateInfo::default()
            .vertex_binding_descriptions(std::slice::from_ref(&binding))
            .vertex_attribute_descriptions(&attrs);
        let ia = vk::PipelineInputAssemblyStateCreateInfo::default()
            .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
        let vp_state = vk::PipelineViewportStateCreateInfo::default()
            .viewport_count(1)
            .scissor_count(1);
        // CULL_NONE: the shader's Y flip inverts winding; BACK culled everything.
        let raster = vk::PipelineRasterizationStateCreateInfo::default()
            .polygon_mode(vk::PolygonMode::FILL)
            .cull_mode(vk::CullModeFlags::NONE)
            .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
            .line_width(1.0);
        let ms = vk::PipelineMultisampleStateCreateInfo::default()
            .rasterization_samples(vk::SampleCountFlags::TYPE_1);
        let ds = vk::PipelineDepthStencilStateCreateInfo::default()
            .depth_test_enable(true)
            .depth_write_enable(true)
            .depth_compare_op(vk::CompareOp::LESS);
        let att = vk::PipelineColorBlendAttachmentState::default()
            .color_write_mask(vk::ColorComponentFlags::RGBA)
            .blend_enable(false);
        let cb = vk::PipelineColorBlendStateCreateInfo::default()
            .attachments(std::slice::from_ref(&att));
        let dyn_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
        let dyn_state = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dyn_states);
        let pi = vk::GraphicsPipelineCreateInfo::default()
            .stages(&stages)
            .vertex_input_state(&vi)
            .input_assembly_state(&ia)
            .viewport_state(&vp_state)
            .rasterization_state(&raster)
            .multisample_state(&ms)
            .depth_stencil_state(&ds)
            .color_blend_state(&cb)
            .dynamic_state(&dyn_state)
            .layout(layout)
            .render_pass(render_pass)
            .subpass(0);
        let pipes = device
            .create_graphics_pipelines(vk::PipelineCache::null(), std::slice::from_ref(&pi), None)
            .map_err(|(_, e)| format!("pipe {e:?}"))?;
        device.destroy_shader_module(vert_mod, None);
        device.destroy_shader_module(frag_mod, None);
        Ok(Self {
            layout,
            pipeline: pipes[0],
            descriptor_set_layout: dsl,
            descriptor_pool: pool,
            descriptor_set: set,
        })
    }

    unsafe fn create_module(device: &ash::Device, spv: &[u8]) -> Result<vk::ShaderModule, String> {
        let code =
            unsafe { std::slice::from_raw_parts(spv.as_ptr() as *const u32, spv.len() / 4) };
        device
            .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(code), None)
            .map_err(|e| format!("mod {e:?}"))
    }

    /// Free pipeline, layout, pool, set layout.
    pub unsafe fn destroy(&mut self, device: &ash::Device) {
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
    }
}
