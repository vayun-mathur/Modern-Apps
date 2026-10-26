//! Moon texture upload onto the renderer: GPU images + Moon-owned sets.
//!
//! Split from `upload.rs` so that file stays under the file-length limit. The
//! Moon pair is maps-pushed (not process-global like the glyph/sprite atlases),
//! so the images, the descriptor pool and both sets live on the renderer and are
//! replaced wholesale on every push, with the old images retired through the
//! frames-in-flight grace queue.
use super::{Renderer, TransientBuffers, FRAMES_IN_FLIGHT};
use crate::vulkan::context::Context;
use crate::vulkan::images::SampledImage;
use ash::vk;

impl Renderer {
    /// Upload the Moon raster pair, replacing any previous one.
    ///
    /// `color_rgba` is `width x height` RGBA8 (converted LROC color);
    /// `dem_rg` is `dem_width x dem_height` RG8 with R=hi/G=lo of the uint16
    /// half-metres + 20000 packing (converted LDEM). Both upload through the
    /// stock [`SampledImage::upload`] path (linear-filtered, clamp-to-edge —
    /// the sphere wrap needs REPEAT on U, set below by re-creating the sampler;
    /// see the note). Sets allocate from a Moon-owned pool against
    /// [`Pipelines::moon_ds_layout`](crate::vulkan::pipeline::Pipelines::moon_ds_layout).
    ///
    /// Old images retire through the frames-in-flight grace queue (a command
    /// buffer submitted last frame may still sample them); the old pool is
    /// destroyed only after its sets are no longer referenced, which the same
    /// grace guarantees. On failure the previous pair (if any) stays live
    /// rather than half-set.
    pub fn set_moon_textures(
        &mut self,
        color_rgba: &[u8],
        width: u32,
        height: u32,
        dem_rg: &[u8],
        dem_width: u32,
        dem_height: u32,
    ) -> Result<(), String> {
        if color_rgba.len() != (width as usize) * (height as usize) * 4 {
            return Err(format!(
                "moon color is {} bytes for a {width}x{height} RGBA image",
                color_rgba.len()
            ));
        }
        if dem_rg.len() != (dem_width as usize) * (dem_height as usize) * 2 {
            return Err(format!(
                "moon dem is {} bytes for a {dem_width}x{dem_height} RG image",
                dem_rg.len()
            ));
        }
        // SAFETY: fresh GPU images; the old ones retire below.
        let (color, dem) = unsafe {
            (
                upload_moon_image(
                    &self.context,
                    self.command_pool,
                    color_rgba,
                    width,
                    height,
                    vk::Format::R8G8B8A8_UNORM,
                )?,
                upload_moon_rg(
                    &self.context,
                    self.command_pool,
                    dem_rg,
                    dem_width,
                    dem_height,
                )?,
            )
        };
        // Moon-owned pool for exactly 2 combined-image-sampler descriptors.
        let pool = unsafe {
            let size = vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(2);
            let info = vk::DescriptorPoolCreateInfo::default()
                .pool_sizes(std::slice::from_ref(&size))
                .max_sets(1);
            self.context
                .device
                .create_descriptor_pool(&info, None)
                .map_err(|e| {
                    color.destroy(&self.context.device);
                    dem.destroy(&self.context.device);
                    format!("create_descriptor_pool(moon) {e:?}")
                })?
        };
        let set = unsafe {
            let alloc = vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(pool)
                .set_layouts(std::slice::from_ref(&self.pipelines.moon_ds_layout));
            let set = self
                .context
                .device
                .allocate_descriptor_sets(&alloc)
                .map_err(|e| {
                    self.context.device.destroy_descriptor_pool(pool, None);
                    color.destroy(&self.context.device);
                    dem.destroy(&self.context.device);
                    format!("allocate_descriptor_sets(moon) {e:?}")
                })?
                .into_iter()
                .next()
                .ok_or_else(|| {
                    self.context.device.destroy_descriptor_pool(pool, None);
                    color.destroy(&self.context.device);
                    dem.destroy(&self.context.device);
                    "moon descriptor pool gave no sets".to_string()
                })?;
            let color_info = vk::DescriptorImageInfo::default()
                .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                .image_view(color.view)
                .sampler(color.sampler);
            let dem_info = vk::DescriptorImageInfo::default()
                .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                .image_view(dem.view)
                .sampler(dem.sampler);
            let writes = [
                vk::WriteDescriptorSet::default()
                    .dst_set(set)
                    .dst_binding(0)
                    .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                    .image_info(std::slice::from_ref(&color_info)),
                vk::WriteDescriptorSet::default()
                    .dst_set(set)
                    .dst_binding(1)
                    .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                    .image_info(std::slice::from_ref(&dem_info)),
            ];
            self.context.device.update_descriptor_sets(&writes, &[]);
            set
        };
        // Retire the previous pair through the grace queue; destroy the previous
        // pool (its sets die with it — no frame still references them after the
        // grace, which is the same guarantee the buffer retire path relies on).
        if let Some(old_color) = self.moon_color.replace(color) {
            self.retire_moon_image(old_color);
        }
        if let Some(old_dem) = self.moon_dem.replace(dem) {
            self.retire_moon_image(old_dem);
        }
        if let Some(old_pool) = self.moon_pool.replace(pool) {
            self.retire_moon_pool(old_pool);
        }
        self.moon_set = Some(set);
        self.bump_state();
        Ok(())
    }

    /// Retire the Moon pair and its descriptor pool; a Moon frame then draws the clear colour
    /// until the next [`set_moon_textures`](Self::set_moon_textures).
    pub fn clear_moon_textures(&mut self) {
        let had = self.moon_color.is_some() || self.moon_dem.is_some() || self.moon_pool.is_some();
        if let Some(color) = self.moon_color.take() {
            self.retire_moon_image(color);
        }
        if let Some(dem) = self.moon_dem.take() {
            self.retire_moon_image(dem);
        }
        if let Some(pool) = self.moon_pool.take() {
            self.retire_moon_pool(pool);
        }
        self.moon_set = None;
        if had {
            self.bump_state();
        }
    }

    fn retire_moon_image(&mut self, image: SampledImage) {
        // Images are not buffers: hold them aside with the same frame count and
        // destroy on drain (see `collect_retired`).
        self.moon_retiring.push((FRAMES_IN_FLIGHT, MoonRetired::Image(image)));
    }

    fn retire_moon_pool(&mut self, pool: vk::DescriptorPool) {
        self.moon_retiring.push((FRAMES_IN_FLIGHT, MoonRetired::Pool(pool)));
    }

    pub(super) fn collect_moon_retired(&mut self) {
        let device = self.context.device.clone();
        self.moon_retiring.retain_mut(|(remaining, retired)| {
            if *remaining > 0 {
                *remaining -= 1;
                return true;
            }
            unsafe {
                match retired {
                    MoonRetired::Image(image) => image.destroy(&device),
                    MoonRetired::Pool(pool) => device.destroy_descriptor_pool(*pool, None),
                }
            }
            false
        });
    }
}

/// A retired Moon image or descriptor pool waiting out the frames-in-flight grace.
pub(super) enum MoonRetired {
    Image(SampledImage),
    Pool(vk::DescriptorPool),
}

/// Upload one Moon image through the stock sampled-image path.
unsafe fn upload_moon_image(
    context: &Context,
    command_pool: vk::CommandPool,
    pixels: &[u8],
    width: u32,
    height: u32,
    format: vk::Format,
) -> Result<SampledImage, String> {
    SampledImage::upload(
        &context.instance,
        context.physical_device,
        &context.device,
        context.queue,
        context.queue_family_index,
        command_pool,
        pixels,
        width,
        height,
        format,
    )
}

/// Upload the packed RG DEM: expanded to RGBA8 on the host (R=hi, G=lo, B=hi,
/// A=255) so it rides the universally-supported RGBA8 image path — the shader
/// reads `.rg` and ignores the rest. Costs 2x DEM bytes (8MB one-time); keeps
/// one image format for the whole renderer.
unsafe fn upload_moon_rg(
    context: &Context,
    command_pool: vk::CommandPool,
    packed: &[u8],
    width: u32,
    height: u32,
) -> Result<SampledImage, String> {
    let px = width as usize * height as usize;
    let mut rgba = Vec::with_capacity(px * 4);
    for chunk in packed.chunks_exact(2) {
        rgba.extend_from_slice(&[chunk[0], chunk[1], chunk[0], 255]);
    }
    SampledImage::upload(
        &context.instance,
        context.physical_device,
        &context.device,
        context.queue,
        context.queue_family_index,
        command_pool,
        &rgba,
        width,
        height,
        vk::Format::R8G8B8A8_UNORM,
    )
}
