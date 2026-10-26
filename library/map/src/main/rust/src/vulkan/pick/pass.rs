use crate::camera::Camera;
use crate::marker::{Marker, MARKER_SIZE_DP};
use crate::vulkan::buffers::Buffer;
use crate::vulkan::context::Context;
use crate::vulkan::pipeline::{Push, MORPH_NONE, PUSH_CONSTANT_BYTES};
use ash::vk;

use super::pipeline::build_pipeline;
use super::target::{PickTarget, PICK_FORMAT};

/// The slot value the target is cleared to: "nothing here". Real features get 1-based slots.
const PICK_MISS: u32 = 0;

/// The offscreen id pass: a render pass, a pipeline that writes a slot id, and the target it
/// writes into (rebuilt on resize).
pub struct Pick {
    render_pass: vk::RenderPass,
    layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    /// The colour target + framebuffer, sized to the swapchain. `None` until the first pick, then
    /// rebuilt whenever the extent changes.
    target: Option<PickTarget>,
    /// A 4-byte host-visible buffer the tapped pixel is copied into and read back from.
    readback: Buffer,
}

impl Pick {
    /// Build the id pass. The render pass and pipeline are format-stable (the target is always
    /// `R32_UINT`, single-sampled, independent of the swapchain), so they are created once and
    /// survive [`Renderer::rebuild`](crate::vulkan::renderer) — only the target is resized.
    ///
    /// # Safety
    ///
    /// `context` must be live; call [`destroy`](Self::destroy) while the device is idle.
    pub unsafe fn new(context: &Context, cache: vk::PipelineCache) -> Result<Pick, String> {
        let device = &context.device;

        // One colour attachment, cleared to "miss", stored, and left in TRANSFER_SRC so the pixel
        // copy that follows the pass reads it directly.
        let color = vk::AttachmentDescription::default()
            .format(PICK_FORMAT)
            .samples(vk::SampleCountFlags::TYPE_1)
            .load_op(vk::AttachmentLoadOp::CLEAR)
            .store_op(vk::AttachmentStoreOp::STORE)
            .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL);
        let color_ref = vk::AttachmentReference::default()
            .attachment(0)
            .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL);
        let subpass = vk::SubpassDescription::default()
            .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
            .color_attachments(std::slice::from_ref(&color_ref));
        // In: wait for prior use of the image before the clear. Out: order the id writes before
        // the copy-to-buffer that reads them back after the pass ends.
        let deps = [
            vk::SubpassDependency::default()
                .src_subpass(vk::SUBPASS_EXTERNAL)
                .dst_subpass(0)
                .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
                .dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
                .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE),
            vk::SubpassDependency::default()
                .src_subpass(0)
                .dst_subpass(vk::SUBPASS_EXTERNAL)
                .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
                .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
                .dst_stage_mask(vk::PipelineStageFlags::TRANSFER)
                .dst_access_mask(vk::AccessFlags::TRANSFER_READ),
        ];
        let render_pass_info = vk::RenderPassCreateInfo::default()
            .attachments(std::slice::from_ref(&color))
            .subpasses(std::slice::from_ref(&subpass))
            .dependencies(&deps);
        let render_pass = device
            .create_render_pass(&render_pass_info, None)
            .map_err(|e| format!("pick create_render_pass {e:?}"))?;

        // Push-only layout, same range as the main pipelines so a full `Push` can be pushed.
        let push_range = vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
            .offset(0)
            .size(PUSH_CONSTANT_BYTES);
        let layout_info = vk::PipelineLayoutCreateInfo::default()
            .push_constant_ranges(std::slice::from_ref(&push_range));
        let layout = match device.create_pipeline_layout(&layout_info, None) {
            Ok(l) => l,
            Err(e) => {
                device.destroy_render_pass(render_pass, None);
                return Err(format!("pick create_pipeline_layout {e:?}"));
            }
        };

        let pipeline = match build_pipeline(device, cache, render_pass, layout) {
            Ok(p) => p,
            Err(e) => {
                device.destroy_pipeline_layout(layout, None);
                device.destroy_render_pass(render_pass, None);
                return Err(e);
            }
        };

        // A 4-byte host-visible buffer the tapped pixel is copied into. Allocated once (its initial
        // contents are irrelevant; it is overwritten by every copy) and mapped for read after.
        let readback = match Buffer::upload(
            &context.instance,
            context.physical_device,
            device,
            vk::BufferUsageFlags::TRANSFER_DST,
            &[PICK_MISS],
        ) {
            Ok(b) => b,
            Err(e) => {
                device.destroy_pipeline(pipeline, None);
                device.destroy_pipeline_layout(layout, None);
                device.destroy_render_pass(render_pass, None);
                return Err(format!("pick readback buffer: {e}"));
            }
        };

        Ok(Pick {
            render_pass,
            layout,
            pipeline,
            target: None,
            readback,
        })
    }

    /// The feature id under the device-pixel `(x, y)`, or `0` when the tap hit nothing.
    ///
    /// Renders every marker's slot into the id target with the frame's camera, reads back the one
    /// tapped pixel, and maps the slot to the marker's id. Markers are drawn in order, so a pin on
    /// top of another wins, matching what is on screen. A one-shot submit with `queue_wait_idle`:
    /// picking is a tap, not a frame, so a synchronous readback is fine and keeps this off the hot
    /// loop entirely.
    ///
    /// # Safety
    ///
    /// `context`/`command_pool`/`quad_*` must be live and belong to the same device this was built
    /// on; the caller (the renderer) guarantees that.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn at(
        &mut self,
        context: &Context,
        command_pool: vk::CommandPool,
        quad_vertices: vk::Buffer,
        quad_indices: vk::Buffer,
        quad_index_count: u32,
        extent: vk::Extent2D,
        camera: &Camera,
        markers: &[Marker],
        x: u32,
        y: u32,
    ) -> Result<u64, String> {
        if markers.is_empty() || extent.width == 0 || extent.height == 0 {
            return Ok(0);
        }
        // The tap can arrive a frame after a resize; clamp so the copy region is always in bounds.
        let px = x.min(extent.width - 1);
        let py = y.min(extent.height - 1);

        self.ensure_target(context, extent)?;
        let target = self.target.as_ref().expect("ensure_target set it");
        let device = &context.device;

        let alloc = vk::CommandBufferAllocateInfo::default()
            .command_pool(command_pool)
            .level(vk::CommandBufferLevel::PRIMARY)
            .command_buffer_count(1);
        let cmd = device
            .allocate_command_buffers(&alloc)
            .map_err(|e| format!("pick allocate cmd {e:?}"))?
            .into_iter()
            .next()
            .ok_or("pick got no command buffer")?;

        let free_cmd = |device: &ash::Device| device.free_command_buffers(command_pool, &[cmd]);

        let begin = vk::CommandBufferBeginInfo::default()
            .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
        if let Err(e) = device.begin_command_buffer(cmd, &begin) {
            free_cmd(device);
            return Err(format!("pick begin cmd {e:?}"));
        }

        let clear = [vk::ClearValue {
            color: vk::ClearColorValue {
                uint32: [PICK_MISS; 4],
            },
        }];
        let pass = vk::RenderPassBeginInfo::default()
            .render_pass(self.render_pass)
            .framebuffer(target.framebuffer)
            .render_area(vk::Rect2D {
                offset: vk::Offset2D { x: 0, y: 0 },
                extent,
            })
            .clear_values(&clear);
        device.cmd_begin_render_pass(cmd, &pass, vk::SubpassContents::INLINE);

        // Full viewport so clip→screen matches the visible frame, but the scissor is the single
        // tapped pixel: only a marker actually covering it writes, which is all the copy reads.
        let viewport = vk::Viewport::default()
            .width(extent.width as f32)
            .height(extent.height as f32)
            .min_depth(0.0)
            .max_depth(1.0);
        device.cmd_set_viewport(cmd, 0, std::slice::from_ref(&viewport));
        let scissor = vk::Rect2D {
            offset: vk::Offset2D {
                x: px as i32,
                y: py as i32,
            },
            extent: vk::Extent2D {
                width: 1,
                height: 1,
            },
        };
        device.cmd_set_scissor(cmd, 0, std::slice::from_ref(&scissor));

        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipeline);
        device.cmd_bind_vertex_buffers(cmd, 0, &[quad_vertices], &[0]);
        device.cmd_bind_index_buffer(cmd, quad_indices, 0, vk::IndexType::UINT32);

        // Slots are 1-based (0 is "miss"), so a slot is an index that fits a `u32` for any
        // plausible marker count; the id-buffer resolution caps at what a pixel can hold anyway.
        for (index, marker) in markers.iter().enumerate() {
            let slot = (index as u32).wrapping_add(1);
            // The same billboard matrix the sprite is drawn with, sized to the marker's tap box.
            // Globe: the same globe anchor the draw path resolves (far side culls
            // to a miss, matching the draw that culled the pin itself).
            let matrix = if crate::camera::globe_active(camera) {
                match camera.globe_anchor_to_screen(marker.lon, marker.lat) {
                    Some((sx, sy)) => {
                        crate::vulkan::renderer::globe_screen_quad(
                            camera,
                            sx,
                            sy,
                            (MARKER_SIZE_DP * 0.5) as f64,
                        )
                    }
                    None => continue,
                }
            } else {
                let mut m =
                    camera.screen_quad_to_clip(marker.lon, marker.lat, (MARKER_SIZE_DP * 0.5) as f64);
                // Behind the eye under tilt: the draw culls this pin, so the pick must too.
                if m[15] <= 0.0 {
                    continue;
                }
                // The drawn icon is counter-rotated to stay screen-upright (`upright_offset`);
                // turn the tap box's axes the same way so the two cover the same pixels.
                let (cos, sin) = camera.rotation();
                let (cos, sin) = (cos as f32, sin as f32);
                let (x0, x1, y0, y1) = (m[0], m[1], m[4], m[5]);
                m[0] = cos * x0 + sin * y0;
                m[1] = cos * x1 + sin * y1;
                m[4] = -sin * x0 + cos * y0;
                m[5] = -sin * x1 + cos * y1;
                m
            };
            let mut push = Push {
                tile_to_clip: matrix,
                color: [0.0; 4],
                line: [0.0; 4],
                misc: [0.0; 4],
                morph: MORPH_NONE,
            };
            // Carry the slot as the bit pattern of a float; the shader reads it with
            // `floatBitsToUint`, so the exact integer survives.
            push.color[0] = f32::from_bits(slot);
            device.cmd_push_constants(
                cmd,
                self.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_draw_indexed(cmd, quad_index_count, 1, 0, 0, 0);
        }

        device.cmd_end_render_pass(cmd);

        // Copy the tapped pixel out of the (now TRANSFER_SRC) image into the readback buffer.
        let region = vk::BufferImageCopy::default()
            .buffer_offset(0)
            .image_subresource(vk::ImageSubresourceLayers {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                mip_level: 0,
                base_array_layer: 0,
                layer_count: 1,
            })
            .image_offset(vk::Offset3D {
                x: px as i32,
                y: py as i32,
                z: 0,
            })
            .image_extent(vk::Extent3D {
                width: 1,
                height: 1,
                depth: 1,
            });
        device.cmd_copy_image_to_buffer(
            cmd,
            target.image,
            vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
            self.readback.buffer,
            std::slice::from_ref(&region),
        );

        if let Err(e) = device.end_command_buffer(cmd) {
            free_cmd(device);
            return Err(format!("pick end cmd {e:?}"));
        }

        let submit = vk::SubmitInfo::default().command_buffers(std::slice::from_ref(&cmd));
        if let Err(e) = device.queue_submit(
            context.queue,
            std::slice::from_ref(&submit),
            vk::Fence::null(),
        ) {
            free_cmd(device);
            return Err(format!("pick queue_submit {e:?}"));
        }
        if let Err(e) = device.queue_wait_idle(context.queue) {
            free_cmd(device);
            return Err(format!("pick queue_wait_idle {e:?}"));
        }
        free_cmd(device);

        // Read the slot back. HOST_COHERENT (the Buffer helper's memory), so no invalidate.
        let slot = {
            let mapped = device
                .map_memory(self.readback.memory, 0, 4, vk::MemoryMapFlags::empty())
                .map_err(|e| format!("pick map_memory {e:?}"))?;
            let slot = (mapped as *const u32).read_unaligned();
            device.unmap_memory(self.readback.memory);
            slot
        };
        if slot == PICK_MISS {
            return Ok(0);
        }
        // A slot is 1-based into `markers`; anything out of range is a stale/garbage read and is a
        // miss rather than a panic.
        Ok(markers.get((slot - 1) as usize).map(|m| m.id).unwrap_or(0))
    }

    /// Ensure the target exists at `extent`, rebuilding it if the surface was resized.
    unsafe fn ensure_target(
        &mut self,
        context: &Context,
        extent: vk::Extent2D,
    ) -> Result<(), String> {
        if self.target.as_ref().is_some_and(|t| t.extent == extent) {
            return Ok(());
        }
        if let Some(old) = self.target.take() {
            old.destroy(&context.device);
        }
        self.target = Some(PickTarget::new(context, self.render_pass, extent)?);
        Ok(())
    }

    /// # Safety
    ///
    /// The device must be idle.
    pub unsafe fn destroy(&mut self, device: &ash::Device) {
        if let Some(target) = self.target.take() {
            target.destroy(device);
        }
        self.readback.destroy(device);
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.layout, None);
        device.destroy_render_pass(self.render_pass, None);
    }
}
