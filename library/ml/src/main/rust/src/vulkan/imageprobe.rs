//! Is a texel-buffer read faster than a storage-buffer read on this device?
//!
//! # The question
//!
//! A Tensor G4 gives about 5 GB/s through storage buffers, and every kernel here is pinned near
//! it: a plain `vkCmdCopyBuffer` reaches 5.0, the logits head 6.9, a whole decode step 4.8. Size
//! does not move it, dispatch count does not move it, and neither do subgroup reductions or
//! occupancy - all measured.
//!
//! LiteRT-LM reports 89 ms a token for the same model, which needs roughly 15 GB/s. Its Android
//! default is `SetPreferTextureWeights(true)`, and its tensor taxonomy carries `IMAGE_BUFFER` and
//! `TEXTURE_2D` beside `BUFFER`. So the standing hypothesis is that the texture path is simply
//! faster here than the buffer path.
//!
//! This answers that with one number, before anything is rewritten to depend on it.
//!
//! # Why a texel buffer rather than a 2D image
//!
//! A texel buffer is the *same allocation*, viewed through a format, and fetches go via the
//! texture unit - which is the property under test. A `VkImage` would add a tiling change, a
//! layout transition and a staging copy, none of which are the thing being measured, and any one
//! of which could account for a difference on its own.
//!
//! Deliberately standalone: its own descriptors, pipelines and buffers, sharing nothing with
//! [`crate::vulkan::run::Net`]. A probe that perturbed the thing it measures would be worse than
//! no probe.
use std::sync::Arc;

use ash::vk;

use crate::vulkan::buffers::Buffer;
use crate::vulkan::context::Context;

const SSBO: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/probe_ssbo.comp.spv"));
const TEXEL: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/probe_texel.comp.spv"));

/// Bytes to sweep. Comfortably past any cache, and the order of a large weight tensor.
const BYTES: u64 = 256 * 1024 * 1024;

/// Workgroups. Enough to fill the device several times over so the launch is not the variable.
const GROUPS: u32 = 2048;

/// Rates in bytes a second: storage buffer first, texel buffer second.
pub fn compare(context: &Arc<Context>) -> Result<(f64, f64), String> {
    let quads = (BYTES / 16) as u32;
    let source = Buffer::device_local_usage(
        context,
        BYTES,
        vk::BufferUsageFlags::STORAGE_BUFFER
            | vk::BufferUsageFlags::UNIFORM_TEXEL_BUFFER
            | vk::BufferUsageFlags::TRANSFER_DST,
    )?;
    let sink = Buffer::device_local(context, 256)?;
    // One view over the same allocation. This is the whole difference between the two paths.
    let view_info = vk::BufferViewCreateInfo::default()
        .buffer(source.buffer)
        .format(vk::Format::R32G32B32A32_UINT)
        .offset(0)
        .range(BYTES);
    // SAFETY: `source` outlives the view, which is destroyed before it below.
    let view = unsafe { context.device.create_buffer_view(&view_info, None) }
        .map_err(|e| format!("create_buffer_view: {e:?}"))?;

    let ssbo = Probe::new(context, SSBO, vk::DescriptorType::STORAGE_BUFFER)?;
    let texel = Probe::new(context, TEXEL, vk::DescriptorType::UNIFORM_TEXEL_BUFFER)?;
    ssbo.bind_buffer(context, &source, &sink);
    texel.bind_texel(context, view, &sink);

    let a = ssbo.time(context, quads)?;
    let b = texel.time(context, quads)?;

    // SAFETY: both probes have finished - `time` waits on its fence - so nothing is in flight.
    unsafe { context.device.destroy_buffer_view(view, None) };
    Ok((BYTES as f64 / a, BYTES as f64 / b))
}

/// One pipeline, its descriptors and a command buffer.
///
/// **Its Vulkan objects are never destroyed.** This runs at most twice in a process, against a
/// device that lives as long as the process, and a `Drop` would need a context reference threaded
/// through it for no benefit. Stated rather than hidden behind an empty `Drop`.
struct Probe {
    layout: vk::DescriptorSetLayout,
    pool: vk::DescriptorPool,
    set: vk::DescriptorSet,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    module: vk::ShaderModule,
    command_pool: vk::CommandPool,
    command_buffer: vk::CommandBuffer,
    fence: vk::Fence,
}

impl Probe {
    fn new(context: &Arc<Context>, spirv: &[u8], source_kind: vk::DescriptorType) -> Result<Probe, String> {
        let device = &context.device;
        let bindings = [
            vk::DescriptorSetLayoutBinding::default()
                .binding(0)
                .descriptor_type(source_kind)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
            vk::DescriptorSetLayoutBinding::default()
                .binding(1)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
        ];
        let sizes = [
            vk::DescriptorPoolSize { ty: source_kind, descriptor_count: 1 },
            vk::DescriptorPoolSize {
                ty: vk::DescriptorType::STORAGE_BUFFER,
                descriptor_count: 1,
            },
        ];
        // SAFETY: every handle below is created from a live device and destroyed in `drop`.
        unsafe {
            let layout = device
                .create_descriptor_set_layout(
                    &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                    None,
                )
                .map_err(|e| format!("descriptor layout: {e:?}"))?;
            let pool = device
                .create_descriptor_pool(
                    &vk::DescriptorPoolCreateInfo::default().max_sets(1).pool_sizes(&sizes),
                    None,
                )
                .map_err(|e| format!("descriptor pool: {e:?}"))?;
            let layouts = [layout];
            let set = device
                .allocate_descriptor_sets(
                    &vk::DescriptorSetAllocateInfo::default()
                        .descriptor_pool(pool)
                        .set_layouts(&layouts),
                )
                .map_err(|e| format!("descriptor set: {e:?}"))?[0];
            let push = [vk::PushConstantRange::default()
                .stage_flags(vk::ShaderStageFlags::COMPUTE)
                .offset(0)
                .size(4)];
            let pipeline_layout = device
                .create_pipeline_layout(
                    &vk::PipelineLayoutCreateInfo::default()
                        .set_layouts(&layouts)
                        .push_constant_ranges(&push),
                    None,
                )
                .map_err(|e| format!("pipeline layout: {e:?}"))?;
            let words: Vec<u32> = spirv
                .chunks_exact(4)
                .map(|w| u32::from_le_bytes([w[0], w[1], w[2], w[3]]))
                .collect();
            let module = device
                .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
                .map_err(|e| format!("shader module: {e:?}"))?;
            let name = std::ffi::CString::new("main").expect("a literal with no nul");
            let stage = vk::PipelineShaderStageCreateInfo::default()
                .stage(vk::ShaderStageFlags::COMPUTE)
                .module(module)
                .name(&name);
            let pipeline = device
                .create_compute_pipelines(
                    vk::PipelineCache::null(),
                    &[vk::ComputePipelineCreateInfo::default()
                        .stage(stage)
                        .layout(pipeline_layout)],
                    None,
                )
                .map_err(|(_, e)| format!("compute pipeline: {e:?}"))?[0];
            let command_pool = device
                .create_command_pool(
                    &vk::CommandPoolCreateInfo::default()
                        .queue_family_index(context.queue_family_index)
                        .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER),
                    None,
                )
                .map_err(|e| format!("command pool: {e:?}"))?;
            let command_buffer = device
                .allocate_command_buffers(
                    &vk::CommandBufferAllocateInfo::default()
                        .command_pool(command_pool)
                        .level(vk::CommandBufferLevel::PRIMARY)
                        .command_buffer_count(1),
                )
                .map_err(|e| format!("command buffer: {e:?}"))?[0];
            let fence = device
                .create_fence(&vk::FenceCreateInfo::default(), None)
                .map_err(|e| format!("fence: {e:?}"))?;
            Ok(Probe {
                layout,
                pool,
                set,
                pipeline_layout,
                pipeline,
                module,
                command_pool,
                command_buffer,
                fence,
            })
        }
    }

    /// Point binding 0 at a storage buffer.
    fn bind_buffer(&self, context: &Arc<Context>, source: &Buffer, sink: &Buffer) {
        let src = [vk::DescriptorBufferInfo {
            buffer: source.buffer,
            offset: 0,
            range: vk::WHOLE_SIZE,
        }];
        let dst = [vk::DescriptorBufferInfo {
            buffer: sink.buffer,
            offset: 0,
            range: vk::WHOLE_SIZE,
        }];
        let writes = [
            vk::WriteDescriptorSet::default()
                .dst_set(self.set)
                .dst_binding(0)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&src),
            vk::WriteDescriptorSet::default()
                .dst_set(self.set)
                .dst_binding(1)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&dst),
        ];
        // SAFETY: the set is live and nothing is in flight against it yet.
        unsafe { context.device.update_descriptor_sets(&writes, &[]) };
    }

    /// Point binding 0 at a texel-buffer view of the same allocation.
    fn bind_texel(&self, context: &Arc<Context>, view: vk::BufferView, sink: &Buffer) {
        let views = [view];
        let dst = [vk::DescriptorBufferInfo {
            buffer: sink.buffer,
            offset: 0,
            range: vk::WHOLE_SIZE,
        }];
        let writes = [
            vk::WriteDescriptorSet::default()
                .dst_set(self.set)
                .dst_binding(0)
                .descriptor_type(vk::DescriptorType::UNIFORM_TEXEL_BUFFER)
                .texel_buffer_view(&views),
            vk::WriteDescriptorSet::default()
                .dst_set(self.set)
                .dst_binding(1)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&dst),
        ];
        // SAFETY: as `bind_buffer`.
        unsafe { context.device.update_descriptor_sets(&writes, &[]) };
    }

    /// Seconds for the fastest of five sweeps, after one to warm.
    fn time(&self, context: &Arc<Context>, quads: u32) -> Result<f64, String> {
        let device = &context.device;
        // SAFETY: recorded once, then submitted and waited on within this call each time.
        unsafe {
            device
                .begin_command_buffer(
                    self.command_buffer,
                    &vk::CommandBufferBeginInfo::default(),
                )
                .map_err(|e| format!("begin: {e:?}"))?;
            device.cmd_bind_pipeline(
                self.command_buffer,
                vk::PipelineBindPoint::COMPUTE,
                self.pipeline,
            );
            device.cmd_bind_descriptor_sets(
                self.command_buffer,
                vk::PipelineBindPoint::COMPUTE,
                self.pipeline_layout,
                0,
                &[self.set],
                &[],
            );
            device.cmd_push_constants(
                self.command_buffer,
                self.pipeline_layout,
                vk::ShaderStageFlags::COMPUTE,
                0,
                &quads.to_le_bytes(),
            );
            device.cmd_dispatch(self.command_buffer, GROUPS, 1, 1);
            device.end_command_buffer(self.command_buffer).map_err(|e| format!("end: {e:?}"))?;
        }
        let mut best = f64::MAX;
        for pass in 0..6 {
            let started = std::time::Instant::now();
            // SAFETY: the buffer is recorded and not in flight; the fence is waited on below.
            unsafe {
                device.reset_fences(&[self.fence]).map_err(|e| format!("reset: {e:?}"))?;
                let buffers = [self.command_buffer];
                let submit = vk::SubmitInfo::default().command_buffers(&buffers);
                let guard = context.lock_queue();
                let sent = device
                    .queue_submit(context.queue, std::slice::from_ref(&submit), self.fence)
                    .map_err(|e| format!("submit: {e:?}"));
                drop(guard);
                sent?;
                device
                    .wait_for_fences(&[self.fence], true, 20_000_000_000)
                    .map_err(|e| format!("wait: {e:?}"))?;
            }
            // The first pass pays for first-touch and any lazy allocation.
            if pass > 0 {
                best = best.min(started.elapsed().as_secs_f64());
            }
        }
        Ok(best)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Both paths read the same bytes, so both should report a sane rate.
    ///
    /// The assertion is deliberately loose - this is a measurement, and pinning it to a number
    /// would make it fail on the next machine. What it guards is that the texel path *runs*:
    /// a device without `UNIFORM_TEXEL_BUFFER` support for this format, or a descriptor bound
    /// wrongly, would report zero or fail rather than merely differ.
    #[test]
    #[ignore = "needs a Vulkan device"]
    fn both_read_paths_report_a_rate() {
        let context = crate::vulkan::context::shared().expect("a Vulkan device");
        let (buffer, texel) = compare(&context).expect("the probe runs");
        println!("  storage buffer {:.2} GB/s, texel buffer {:.2} GB/s", buffer / 1e9, texel / 1e9);
        assert!(buffer > 0.0, "the storage path reported {buffer}");
        assert!(texel > 0.0, "the texel path reported {texel}");
    }
}
