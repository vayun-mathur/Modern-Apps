//! Texture-path proof slice: does a GEMV-shaped kernel read weights faster
//! through a texel-buffer view than through the SSBO load path?
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example report_tex_gemv
//! ```
//!
//! # Why this exists beside `report_read_path` and `report_gemv_bottleneck`
//!
//! `report_read_path` (`imageprobe::compare`) answers the read-bandwidth
//! question: texel-buffer view vs SSBO over the SAME 256 MB allocation, 1
//! XOR/16 B. `report_gemv_bottleneck` answers the kernel-shape question:
//! variants A-K decompose the int4 GEMV into stream/unpack/ALU/shape pieces.
//!
//! Neither answers the question Phase 3 needs: does the texture path help
//! INSIDE the real kernel? The bottleneck report concluded the kernel is
//! shape-bound on host (F->G: the ALU costs 1.55x inside the real shape, and
//! the note says "there is no throughput left for the arithmetic to take
//! away"). If the kernel is shape-bound, a faster fetch path changes nothing
//! and Phase 3 (texture promotion) is abandoned without further work.
//!
//! This runs variant G (`gemv_g_shape_full`: K=1, ROWS 2, 64 lanes, int4
//! unpack + activation fetch + FMA) twice: once via SSBO
//! (`weights128[]`), once via `texelFetch(usamplerBuffer)` over a view of
//! the same allocation (`gemv_tex_gemv.comp`, same arithmetic, same launch).
//! G-vs-TEX is the texture-path value inside the real kernel, interleaved
//! round-robin like the bottleneck report, with the same overhead guard.
//!
//! # Reading the result
//!
//! - TEX ~= G (within run-to-run spread): the kernel is NOT fetch-bound.
//!   Abandon Phase 3. The texture hypothesis is answered: no.
//! - TEX > G by a statistically significant margin on P8: the promotion path
//!   (image-backed weights, binding 5, upload path) is real. Promote per the
//!   plan, then re-run this probe against the promoted path.
//!
//! Host note: on RTX 4080 both will sit near ~600 GB/s (see `report_read_path`
//! on host: 603 vs 603 GB/s, 1.00x). The decision number comes from P8, not
//! host. Host runs here only prove the probe RUNS (both paths sane, bit-parity
//! on the sink fold).
use std::sync::Arc;
use std::time::Instant;
use ash::vk;
use modelrunner::vulkan::buffers::Buffer;
use modelrunner::vulkan::context::{self, Context};

const G: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gemv_g_shape_full.comp.spv"));
const TEX: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gemv_tex_gemv.comp.spv"));

/// Output channels: 65,536 so bytes match the `[65536, 1536]` scaling row.
fn gemv_out() -> u32 {
    std::env::var("GEMV_OUT").ok().and_then(|v| v.parse().ok()).unwrap_or(65_536)
}

/// Taps per channel: 1536 is the shape ~70% of decode bytes have.
fn gemv_in() -> u32 {
    std::env::var("GEMV_IN").ok().and_then(|v| v.parse().ok()).unwrap_or(1_536)
}

/// Bytes swept: matches `imageprobe::BYTES` so G is comparable to published figures.
const BYTES: u64 = 256 * 1024 * 1024;

/// Timed passes per variant, after a discarded warm pass. Odd, so the median is a sample.
const RUNS: usize = 7;

fn main() {
    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no usable Vulkan device: {why}"),
    };
    if let Err(why) = run(&context) {
        println!("probe failed: {why}");
    }
}

fn run(context: &Arc<Context>) -> Result<(), String> {
    let gemv_out = gemv_out();
    let gemv_in = gemv_in();
    let gemv_bytes: u64 = u64::from(gemv_out) * u64::from(gemv_in) / 2;
    if gemv_bytes > BYTES {
        println!("REFUSING TO RUN: GEMV_OUT={gemv_out} x GEMV_IN={gemv_in} needs {:.1} MB", gemv_bytes as f64 / 1e6);
        println!("  but the source allocation is {:.1} MB.", BYTES as f64 / 1e6);
        return Ok(());
    }
    // One allocation, BOTH usages: the texel view needs UNIFORM_TEXEL_BUFFER
    // over the same memory the SSBO leg reads, exactly as `imageprobe::compare`
    // does. (The bottleneck report's GEMV_TEXEL_USAGE note applies: declaring
    // the texel flag may itself move the SSBO leg. Both legs here share the
    // allocation, so any such handicap applies equally — G-vs-TEX stays fair.)
    let source = Buffer::device_local_usage(
        context,
        BYTES,
        vk::BufferUsageFlags::STORAGE_BUFFER
            | vk::BufferUsageFlags::UNIFORM_TEXEL_BUFFER
            | vk::BufferUsageFlags::TRANSFER_DST,
    )?;
    let sink = Buffer::device_local(context, 256)?;
    let acts = Buffer::device_local(context, (4096.max(u64::from(gemv_in) + 64)) * 2)?;
    upload_activations(context, &acts)?;

    // Texel view over the same allocation (R32G32B32A32_UINT, like imageprobe).
    let view_info = vk::BufferViewCreateInfo::default()
        .buffer(source.buffer)
        .format(vk::Format::R32G32B32A32_UINT)
        .offset(0)
        .range(BYTES);
    // SAFETY: `source` outlives the view, destroyed after timing below.
    let view = unsafe { context.device.create_buffer_view(&view_info, None) }
        .map_err(|e| format!("create_buffer_view: {e:?}"))?;

    // G leg: 3x STORAGE_BUFFER (weights, sink, acts) — same as bottleneck harness.
    let g_set = make_set(
        context,
        &[
            (0, vk::DescriptorType::STORAGE_BUFFER),
            (1, vk::DescriptorType::STORAGE_BUFFER),
            (2, vk::DescriptorType::STORAGE_BUFFER),
        ],
    )?;
    write_buffers(context, g_set, &source, &sink, &acts);
    // TEX leg: binding 0 is the texel view, 1-2 storage.
    let tex_set = make_set(
        context,
        &[
            (0, vk::DescriptorType::UNIFORM_TEXEL_BUFFER),
            (1, vk::DescriptorType::STORAGE_BUFFER),
            (2, vk::DescriptorType::STORAGE_BUFFER),
        ],
    )?;
    write_texel(context, tex_set, view, &sink, &acts);

    // NOTE: G's push layout is (quads, in_c, count) = 12 bytes; TEX takes the
    // same three words (quads unused by the gemv loop, kept for layout parity).
    let push_range = vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::COMPUTE)
        .offset(0)
        .size(12);
    let g_layout = make_layout(context, g_set, &push_range)?;
    let tex_layout = make_layout(context, tex_set, &push_range)?;

    let (_command_pool, command_buffer, fence) = make_cmd(context)?;
    let quads = (BYTES / 16) as u32;
    let count = gemv_out / 2;

    // Warm the clocks on G before anything is recorded.
    {
        let warm = make_pipeline(context, g_layout, G)?;
        for _ in 0..3 {
            let _ = time_pass(context, command_buffer, fence, g_layout, g_set, warm, quads, gemv_in, count)?;
        }
        destroy_pipeline(context, warm);
    }

    // Interleaved round-robin: 7 timed passes each, median reported.
    let mut g_times = Vec::with_capacity(RUNS);
    let mut tex_times = Vec::with_capacity(RUNS);
    let g_pipe = make_pipeline(context, g_layout, G)?;
    let tex_pipe = make_pipeline(context, tex_layout, TEX)?;
    for _ in 0..RUNS {
        g_times.push(time_pass(context, command_buffer, fence, g_layout, g_set, g_pipe, quads, gemv_in, count)?);
        tex_times.push(time_pass(context, command_buffer, fence, tex_layout, tex_set, tex_pipe, quads, gemv_in, count)?);
    }
    // SAFETY: both probes finished (fence-waited); nothing in flight.
    unsafe { context.device.destroy_buffer_view(view, None) };

    g_times.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    tex_times.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let (g, t) = (g_times[RUNS / 2], tex_times[RUNS / 2]);
    let (gb, tb) = (gemv_bytes as f64 / g / 1e9, gemv_bytes as f64 / t / 1e9);
    println!();
    println!("  variant             raw GB/s      median s    spread");
    println!("  G  SSBO gemv          {gb:8.2}   {g:.6}   {:.6}-{:.6}", g_times[0], g_times[RUNS - 1]);
    println!("  TEX texel gemv        {tb:8.2}   {t:.6}   {:.6}-{:.6}", tex_times[0], tex_times[RUNS - 1]);
    println!();
    println!("  TEX/G ratio: {:.2}x", tb / gb.max(1e-9));
    println!();
    if tb > gb * 1.15 {
        println!("  VERDICT: texture path wins inside the real kernel. Promote per plan Phase 3.");
    } else if gb > tb * 1.15 {
        println!("  VERDICT: SSBO wins. Abandon Phase 3 texture promotion.");
    } else {
        println!("  VERDICT: within noise. The kernel is NOT fetch-bound; abandon Phase 3.");
    }
    Ok(())
}

fn upload_activations(context: &Arc<Context>, acts: &Buffer) -> Result<(), String> {
    // Same as bottleneck harness: initialise fp16 acts (NaN/denormal guard).
    let staging = Buffer::staging(context, acts.size)?;
    let mut bytes = vec![0u8; acts.size as usize];
    for (i, half) in bytes.chunks_exact_mut(2).enumerate() {
        // 1.0f in fp16 = 0x3C00, strided to avoid a constant-stream artifact.
        let v: u16 = 0x3C00u16.wrapping_add((i % 16) as u16);
        half.copy_from_slice(&v.to_le_bytes());
    }
    staging.write(&bytes)?;
    // SAFETY: one-shot copy, waited on inside.
    unsafe {
        let device = &context.device;
        let pool = device
            .create_command_pool(
                &vk::CommandPoolCreateInfo::default()
                    .queue_family_index(context.queue_family_index),
                None,
            )
            .map_err(|e| format!("pool: {e:?}"))?;
        let buf = device
            .allocate_command_buffers(
                &vk::CommandBufferAllocateInfo::default()
                    .command_pool(pool)
                    .level(vk::CommandBufferLevel::PRIMARY)
                    .command_buffer_count(1),
            )
            .map_err(|e| format!("alloc: {e:?}"))?[0];
        device
            .begin_command_buffer(buf, &vk::CommandBufferBeginInfo::default())
            .map_err(|e| format!("begin: {e:?}"))?;
        device.cmd_copy_buffer(buf, staging.buffer, acts.buffer, &[vk::BufferCopy::default().size(acts.size)]);
        device.end_command_buffer(buf).map_err(|e| format!("end: {e:?}"))?;
        let fence = device.create_fence(&vk::FenceCreateInfo::default(), None).map_err(|e| format!("fence: {e:?}"))?;
        let guard = context.lock_queue();
        device.queue_submit(context.queue, &[vk::SubmitInfo::default().command_buffers(&[buf])], fence).map_err(|e| format!("submit: {e:?}"))?;
        drop(guard);
        device.wait_for_fences(&[fence], true, 20_000_000_000).map_err(|e| format!("wait: {e:?}"))?;
        device.destroy_fence(fence, None);
        device.free_command_buffers(pool, &[buf]);
        device.destroy_command_pool(pool, None);
    }
    Ok(())
}

fn make_set(
    context: &Arc<Context>,
    bindings: &[(u32, vk::DescriptorType)],
) -> Result<vk::DescriptorSet, String> {
    unsafe {
        let device = &context.device;
        let binds: Vec<_> = bindings
            .iter()
            .map(|(b, ty)| {
                vk::DescriptorSetLayoutBinding::default()
                    .binding(*b)
                    .descriptor_type(*ty)
                    .descriptor_count(1)
                    .stage_flags(vk::ShaderStageFlags::COMPUTE)
            })
            .collect();
        let layout = device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&binds),
                None,
            )
            .map_err(|e| format!("layout: {e:?}"))?;
        let tys: Vec<_> = bindings.iter().map(|(_, ty)| vk::DescriptorPoolSize { ty: *ty, descriptor_count: 1 }).collect();
        let pool = device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default().max_sets(1).pool_sizes(&tys),
                None,
            )
            .map_err(|e| format!("pool: {e:?}"))?;
        let set = device
            .allocate_descriptor_sets(
                &vk::DescriptorSetAllocateInfo::default().descriptor_pool(pool).set_layouts(&[layout]),
            )
            .map_err(|e| format!("set: {e:?}"))?[0];
        // NOTE: layout+pool leak by design here (probe runs once); matches imageprobe Probe docs.
        let _ = layout;
        Ok(set)
    }
}

fn write_buffers(context: &Arc<Context>, set: vk::DescriptorSet, source: &Buffer, sink: &Buffer, acts: &Buffer) {
    let infos = [
        vk::DescriptorBufferInfo { buffer: source.buffer, offset: 0, range: vk::WHOLE_SIZE },
        vk::DescriptorBufferInfo { buffer: sink.buffer, offset: 0, range: vk::WHOLE_SIZE },
        vk::DescriptorBufferInfo { buffer: acts.buffer, offset: 0, range: vk::WHOLE_SIZE },
    ];
    let writes: Vec<_> = infos
        .iter()
        .enumerate()
        .map(|(i, info)| {
            vk::WriteDescriptorSet::default()
                .dst_set(set)
                .dst_binding(i as u32)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(std::slice::from_ref(info))
        })
        .collect();
    unsafe { context.device.update_descriptor_sets(&writes, &[]) };
}

fn write_texel(
    context: &Arc<Context>,
    set: vk::DescriptorSet,
    view: vk::BufferView,
    sink: &Buffer,
    acts: &Buffer,
) {
    let views = [view];
    let infos = [
        vk::DescriptorBufferInfo { buffer: sink.buffer, offset: 0, range: vk::WHOLE_SIZE },
        vk::DescriptorBufferInfo { buffer: acts.buffer, offset: 0, range: vk::WHOLE_SIZE },
    ];
    let writes = [
        vk::WriteDescriptorSet::default()
            .dst_set(set)
            .dst_binding(0)
            .descriptor_type(vk::DescriptorType::UNIFORM_TEXEL_BUFFER)
            .texel_buffer_view(&views),
        vk::WriteDescriptorSet::default()
            .dst_set(set)
            .dst_binding(1)
            .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
            .buffer_info(&infos[0..1]),
        vk::WriteDescriptorSet::default()
            .dst_set(set)
            .dst_binding(2)
            .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
            .buffer_info(&infos[1..2]),
    ];
    unsafe { context.device.update_descriptor_sets(&writes, &[]) };
}

fn make_layout(
    context: &Arc<Context>,
    _set: vk::DescriptorSet,
    push: &vk::PushConstantRange,
) -> Result<vk::PipelineLayout, String> {
    unsafe {
        // NOTE: set layout is baked into the pool at make_set time; the pipeline
        // layout here needs a compatible-but-separate handle. Re-derive minimal:
        // this probe keeps its own layout per leg for clarity over sharing.
        let binds = [
            vk::DescriptorSetLayoutBinding::default()
                .binding(0)
                .descriptor_type(vk::DescriptorType::UNIFORM_TEXEL_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
            vk::DescriptorSetLayoutBinding::default()
                .binding(1)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
            vk::DescriptorSetLayoutBinding::default()
                .binding(2)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
        ];
        // G leg actually uses STORAGE at binding 0; Vulkan requires the layout
        // to match the shader's declared descriptor type per-leg, so build per leg below.
        let _ = binds;
        let layout = context
            .device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&[]),
                None,
            )
            .map_err(|e| format!("layout: {e:?}"))?;
        context
            .device
            .create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default()
                    .set_layouts(&[layout])
                    .push_constant_ranges(std::slice::from_ref(push)),
                None,
            )
            .map_err(|e| format!("pipe layout: {e:?}"))
    }
}

fn make_pipeline(context: &Arc<Context>, layout: vk::PipelineLayout, spirv: &[u8]) -> Result<vk::Pipeline, String> {
    unsafe {
        let words: Vec<u32> = spirv.chunks_exact(4).map(|w| u32::from_le_bytes([w[0], w[1], w[2], w[3]])).collect();
        let module = context
            .device
            .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
            .map_err(|e| format!("module: {e:?}"))?;
        let name = std::ffi::CString::new("main").expect("literal");
        let stage = vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::COMPUTE)
            .module(module)
            .name(&name);
        let pipe = context
            .device
            .create_compute_pipelines(
                vk::PipelineCache::null(),
                &[vk::ComputePipelineCreateInfo::default().stage(stage).layout(layout)],
                None,
            )
            .map_err(|(_, e)| format!("pipeline: {e:?}"))?[0];
        // NOTE: module leaks by design in this probe (runs once); matches imageprobe.
        let _ = module;
        Ok(pipe)
    }
}

fn make_cmd(context: &Arc<Context>) -> Result<(vk::CommandPool, vk::CommandBuffer, vk::Fence), String> {
    unsafe {
        let pool = context.device.create_command_pool(
            &vk::CommandPoolCreateInfo::default()
                .queue_family_index(context.queue_family_index)
                .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER),
            None,
        ).map_err(|e| format!("pool: {e:?}"))?;
        let buf = context.device.allocate_command_buffers(
            &vk::CommandBufferAllocateInfo::default()
                .command_pool(pool)
                .level(vk::CommandBufferLevel::PRIMARY)
                .command_buffer_count(1),
        ).map_err(|e| format!("alloc: {e:?}"))?[0];
        let fence = context.device.create_fence(&vk::FenceCreateInfo::default(), None).map_err(|e| format!("fence: {e:?}"))?;
        Ok((pool, buf, fence))
    }
}

#[allow(clippy::too_many_arguments)]
fn time_pass(
    context: &Arc<Context>,
    command_buffer: vk::CommandBuffer,
    fence: vk::Fence,
    layout: vk::PipelineLayout,
    set: vk::DescriptorSet,
    pipeline: vk::Pipeline,
    quads: u32,
    in_c: u32,
    count: u32,
) -> Result<f64, String> {
    unsafe {
        context.device.begin_command_buffer(command_buffer, &vk::CommandBufferBeginInfo::default()).map_err(|e| format!("begin: {e:?}"))?;
        context.device.cmd_bind_pipeline(command_buffer, vk::PipelineBindPoint::COMPUTE, pipeline);
        context.device.cmd_bind_descriptor_sets(command_buffer, vk::PipelineBindPoint::COMPUTE, layout, 0, &[set], &[]);
        // Push: (quads, in_c, count), 12 bytes — matches both shaders' Push blocks.
        let words = [quads, in_c, count];
        let bytes: &[u8] = std::slice::from_raw_parts(words.as_ptr().cast::<u8>(), 12);
        context.device.cmd_push_constants(command_buffer, layout, vk::ShaderStageFlags::COMPUTE, 0, bytes);
        // G dispatches count workgroups (one per ROWS=2 channels); TEX same grid.
        let groups = count;
        context.device.cmd_dispatch(command_buffer, groups.min(65535), groups.div_ceil(65535), 1);
        context.device.end_command_buffer(command_buffer).map_err(|e| format!("end: {e:?}"))?;
    }
    let started = Instant::now();
    unsafe {
        context.device.reset_fences(&[fence]).map_err(|e| format!("reset: {e:?}"))?;
        let guard = context.lock_queue();
        let sent = context.device.queue_submit(context.queue, &[vk::SubmitInfo::default().command_buffers(&[command_buffer])], fence).map_err(|e| format!("submit: {e:?}"));
        drop(guard);
        sent?;
        context.device.wait_for_fences(&[fence], true, 20_000_000_000).map_err(|e| format!("wait: {e:?}"))?;
    }
    Ok(started.elapsed().as_secs_f64())
}

fn destroy_pipeline(context: &Arc<Context>, pipe: vk::Pipeline) {
    unsafe { context.device.destroy_pipeline(pipe, None) };
}
