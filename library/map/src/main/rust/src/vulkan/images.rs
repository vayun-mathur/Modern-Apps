//! One sampled image, two atlases: the reusable texture path.
//!
//! M1 needs an SDF glyph atlas (R8); M2 needs a sprite atlas (RGBA). Both are
//! the same Vulkan object — a sampled image with a sampler, bound once per frame
//! — so this module builds it once and both pipelines share the pattern:
//!
//! * [`SampledImage::upload`] — R8 or RGBA bytes → `VkImage` + view + sampler,
//!   with a one-time command buffer for the copy + layout transitions.
//! * [`AtlasSet`] — descriptor pool + set layout + one set per image, so M2 adds
//!   its sprite atlas without touching this code.
//!
//! Android-only like the rest of `vulkan/`: host builds never see it.

use ash::vk;

/// A Vulkan sampled image plus everything needed to bind it.
pub struct SampledImage {
    pub image: vk::Image,
    pub memory: vk::DeviceMemory,
    pub view: vk::ImageView,
    pub sampler: vk::Sampler,
    pub width: u32,
    pub height: u32,
}

/// Descriptor pool + layout shared by every sampled atlas.
pub struct AtlasSet {
    pub pool: vk::DescriptorPool,
    pub layout: vk::DescriptorSetLayout,
}

impl AtlasSet {
    /// One pool, one layout: a single `combinedImageSampler` at set 0/binding 0
    /// visible to the fragment stage. M2's sprite atlas allocates a second set
    /// from the same pool.
    ///
    /// # Safety
    ///
    /// `device` must outlive the returned set; call [`destroy`](Self::destroy)
    /// while the device is idle.
    pub unsafe fn new(device: &ash::Device) -> Result<AtlasSet, String> {
        let binding = vk::DescriptorSetLayoutBinding::default()
            .binding(0)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .descriptor_count(1)
            .stage_flags(vk::ShaderStageFlags::FRAGMENT);
        let layout_info =
            vk::DescriptorSetLayoutCreateInfo::default().bindings(std::slice::from_ref(&binding));
        let layout = device
            .create_descriptor_set_layout(&layout_info, None)
            .map_err(|e| format!("create_descriptor_set_layout {e:?}"))?;
        // Two sets: glyph atlas now, sprite atlas in M2. Small over-allocation is
        // fine; pools are cheap and this one lives for the process.
        let pool_size = vk::DescriptorPoolSize::default()
            .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .descriptor_count(2);
        let pool_info = vk::DescriptorPoolCreateInfo::default()
            .pool_sizes(std::slice::from_ref(&pool_size))
            .max_sets(2);
        let pool = device
            .create_descriptor_pool(&pool_info, None)
            .map_err(|e| {
                device.destroy_descriptor_set_layout(layout, None);
                format!("create_descriptor_pool {e:?}")
            })?;
        Ok(AtlasSet { pool, layout })
    }

    /// Allocate one set and point it at `image`.
    ///
    /// # Safety
    ///
    /// Same lifetime rules as [`new`](Self::new).
    pub unsafe fn allocate(
        &self,
        device: &ash::Device,
        image: &SampledImage,
    ) -> Result<vk::DescriptorSet, String> {
        let alloc = vk::DescriptorSetAllocateInfo::default()
            .descriptor_pool(self.pool)
            .set_layouts(std::slice::from_ref(&self.layout));
        let set = device
            .allocate_descriptor_sets(&alloc)
            .map_err(|e| format!("allocate_descriptor_sets {e:?}"))?
            .into_iter()
            .next()
            .ok_or("descriptor pool gave no sets")?;
        let info = vk::DescriptorImageInfo::default()
            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
            .image_view(image.view)
            .sampler(image.sampler);
        let write = vk::WriteDescriptorSet::default()
            .dst_set(set)
            .dst_binding(0)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .image_info(std::slice::from_ref(&info));
        device.update_descriptor_sets(std::slice::from_ref(&write), &[]);
        Ok(set)
    }

    /// # Safety
    ///
    /// The device must be idle.
    pub unsafe fn destroy(&self, device: &ash::Device) {
        device.destroy_descriptor_pool(self.pool, None);
        device.destroy_descriptor_set_layout(self.layout, None);
    }
}

impl SampledImage {
    /// Upload `pixels` (`width`×`height`, `format` R8 or RGBA8) to a sampled image.
    ///
    /// Linear-filtered with clamp-to-edge: SDF glyphs need smooth interpolation
    /// between texels, and sprite icons must not bleed across atlas borders.
    /// Mipmaps are M5 (labels are minified at most 2x within their zoom window).
    ///
    /// # Safety
    ///
    /// Needs the graphics queue family (uses it for the copy); the returned
    /// image must be destroyed with [`destroy`](Self::destroy) while idle.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn upload(
        instance: &ash::Instance,
        physical_device: vk::PhysicalDevice,
        device: &ash::Device,
        queue: vk::Queue,
        queue_family: u32,
        command_pool: vk::CommandPool,
        pixels: &[u8],
        width: u32,
        height: u32,
        format: vk::Format,
    ) -> Result<SampledImage, String> {
        let bpp = match format {
            vk::Format::R8_UNORM => 1,
            vk::Format::R8G8B8A8_UNORM => 4,
            _ => return Err("atlas upload takes R8 or RGBA8".into()),
        };
        if pixels.len() != (width * height * bpp) as usize {
            return Err(format!(
                "atlas is {} bytes for a {width}x{height} x{bpp} image",
                pixels.len()
            ));
        }
        // SwiftShader (the emulator's Vulkan) advertises sampled R8 but its optimal-tiling R8
        // path is less exercised than RGBA8, so there (or on a device without linear-filtered
        // R8) the atlas is expanded R8 → RGBA8 at 4x the bytes. Everywhere else it stays R8 and
        // the view swizzles R into all four channels, which samples exactly like the expansion:
        // `glyph::expand_sdf_r8_to_rgba8` owns that byte-order contract and a host test pins it.
        let rgba;
        let (upload_pixels, upload_format) = match format {
            vk::Format::R8_UNORM if !r8_sampling_ok(instance, physical_device) => {
                rgba = crate::tile::glyph::expand_sdf_r8_to_rgba8(pixels);
                (rgba.as_slice(), vk::Format::R8G8B8A8_UNORM)
            }
            _ => (pixels, format),
        };
        let components = if upload_format == vk::Format::R8_UNORM {
            vk::ComponentMapping {
                r: vk::ComponentSwizzle::R,
                g: vk::ComponentSwizzle::R,
                b: vk::ComponentSwizzle::R,
                a: vk::ComponentSwizzle::R,
            }
        } else {
            vk::ComponentMapping::default()
        };
        let _ = bpp;
        // Device-local image, host-visible staging buffer (the Buffer helper is
        // vertex/index-shaped; staging wants TRANSFER_SRC so do it inline).
        let image_info = vk::ImageCreateInfo::default()
            .image_type(vk::ImageType::TYPE_2D)
            .format(upload_format)
            .extent(vk::Extent3D {
                width,
                height,
                depth: 1,
            })
            .mip_levels(1)
            .array_layers(1)
            .samples(vk::SampleCountFlags::TYPE_1)
            .tiling(vk::ImageTiling::OPTIMAL)
            .usage(vk::ImageUsageFlags::TRANSFER_DST | vk::ImageUsageFlags::SAMPLED)
            .sharing_mode(vk::SharingMode::EXCLUSIVE)
            .initial_layout(vk::ImageLayout::UNDEFINED);
        let image = device
            .create_image(&image_info, None)
            .map_err(|e| format!("create_image {e:?}"))?;
        let requirements = device.get_image_memory_requirements(image);
        let properties = instance.get_physical_device_memory_properties(physical_device);
        let memory_type = (0..properties.memory_type_count)
            .find(|&i| {
                requirements.memory_type_bits & (1 << i) != 0
                    && properties.memory_types[i as usize]
                        .property_flags
                        .contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
            })
            .ok_or("no device-local memory type for the atlas")?;
        let allocate = vk::MemoryAllocateInfo::default()
            .allocation_size(requirements.size)
            .memory_type_index(memory_type);
        let memory = device.allocate_memory(&allocate, None).map_err(|e| {
            device.destroy_image(image, None);
            format!("allocate_memory {e:?}")
        })?;
        if let Err(e) = device.bind_image_memory(image, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(format!("bind_image_memory {e:?}"));
        }
        // Staging: reuse the vertex-buffer path (host-visible + coherent).
        let staging = super::buffers::Buffer::upload(
            instance,
            physical_device,
            device,
            vk::BufferUsageFlags::TRANSFER_SRC,
            upload_pixels,
        )
        .map_err(|e| {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            format!("staging upload: {e}")
        })?;
        // One-time copy + both layout transitions on the graphics queue.
        let alloc_info = vk::CommandBufferAllocateInfo::default()
            .command_pool(command_pool)
            .level(vk::CommandBufferLevel::PRIMARY)
            .command_buffer_count(1);
        let copy = device
            .allocate_command_buffers(&alloc_info)
            .map_err(|e| format!("allocate copy command buffer {e:?}"))?
            .into_iter()
            .next()
            .ok_or("no copy command buffer")?;
        let begin = vk::CommandBufferBeginInfo::default()
            .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
        device
            .begin_command_buffer(copy, &begin)
            .map_err(|e| format!("begin copy {e:?}"))?;
        let barrier_to_dst = vk::ImageMemoryBarrier::default()
            .old_layout(vk::ImageLayout::UNDEFINED)
            .new_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .image(image)
            .subresource_range(vk::ImageSubresourceRange {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                base_mip_level: 0,
                level_count: 1,
                base_array_layer: 0,
                layer_count: 1,
            })
            .src_access_mask(vk::AccessFlags::empty())
            .dst_access_mask(vk::AccessFlags::TRANSFER_WRITE);
        device.cmd_pipeline_barrier(
            copy,
            vk::PipelineStageFlags::TOP_OF_PIPE,
            vk::PipelineStageFlags::TRANSFER,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            std::slice::from_ref(&barrier_to_dst),
        );
        let region = vk::BufferImageCopy::default()
            .image_subresource(vk::ImageSubresourceLayers {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                mip_level: 0,
                base_array_layer: 0,
                layer_count: 1,
            })
            .image_extent(vk::Extent3D {
                width,
                height,
                depth: 1,
            });
        device.cmd_copy_buffer_to_image(
            copy,
            staging.buffer,
            image,
            vk::ImageLayout::TRANSFER_DST_OPTIMAL,
            std::slice::from_ref(&region),
        );
        let barrier_to_read = vk::ImageMemoryBarrier::default()
            .old_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
            .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .image(image)
            .subresource_range(vk::ImageSubresourceRange {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                base_mip_level: 0,
                level_count: 1,
                base_array_layer: 0,
                layer_count: 1,
            })
            .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
            .dst_access_mask(vk::AccessFlags::SHADER_READ);
        device.cmd_pipeline_barrier(
            copy,
            vk::PipelineStageFlags::TRANSFER,
            vk::PipelineStageFlags::FRAGMENT_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            std::slice::from_ref(&barrier_to_read),
        );
        device
            .end_command_buffer(copy)
            .map_err(|e| format!("end copy {e:?}"))?;
        let submit = vk::SubmitInfo::default().command_buffers(std::slice::from_ref(&copy));
        device
            .queue_submit(queue, std::slice::from_ref(&submit), vk::Fence::null())
            .map_err(|e| format!("queue_submit atlas copy {e:?}"))?;
        device
            .queue_wait_idle(queue)
            .map_err(|e| format!("queue_wait_idle {e:?}"))?;
        device.free_command_buffers(command_pool, &[copy]);
        staging.destroy(device);
        // View + sampler (both in the uploaded format).
        let view_info = vk::ImageViewCreateInfo::default()
            .image(image)
            .view_type(vk::ImageViewType::TYPE_2D)
            .format(upload_format)
            .components(components)
            .subresource_range(vk::ImageSubresourceRange {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                base_mip_level: 0,
                level_count: 1,
                base_array_layer: 0,
                layer_count: 1,
            });
        let view = device.create_image_view(&view_info, None).map_err(|e| {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            format!("create_image_view {e:?}")
        })?;
        let sampler_info = vk::SamplerCreateInfo::default()
            .mag_filter(vk::Filter::LINEAR)
            .min_filter(vk::Filter::LINEAR)
            .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
            .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
            .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
            .max_lod(vk::LOD_CLAMP_NONE);
        let sampler = device.create_sampler(&sampler_info, None).map_err(|e| {
            device.destroy_image_view(view, None);
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            format!("create_sampler {e:?}")
        })?;
        Ok(SampledImage {
            image,
            memory,
            view,
            sampler,
            width,
            height,
        })
    }

    /// # Safety
    ///
    /// The device must be idle.
    pub unsafe fn destroy(&self, device: &ash::Device) {
        device.destroy_sampler(self.sampler, None);
        device.destroy_image_view(self.view, None);
        device.free_memory(self.memory, None);
        device.destroy_image(self.image, None);
    }
}

/// Whether R8 atlases can stay R8 on this device: not SwiftShader, and R8 supports linear
/// sampling with optimal tiling.
unsafe fn r8_sampling_ok(instance: &ash::Instance, physical_device: vk::PhysicalDevice) -> bool {
    let props = instance.get_physical_device_properties(physical_device);
    let name = std::ffi::CStr::from_ptr(props.device_name.as_ptr()).to_string_lossy();
    if name.contains("SwiftShader") {
        return false;
    }
    instance
        .get_physical_device_format_properties(physical_device, vk::Format::R8_UNORM)
        .optimal_tiling_features
        .contains(
            vk::FormatFeatureFlags::SAMPLED_IMAGE | vk::FormatFeatureFlags::SAMPLED_IMAGE_FILTER_LINEAR,
        )
}
