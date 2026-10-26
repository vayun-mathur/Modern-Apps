use ash::vk;

use crate::nets::{Kind, Push};

use super::context::Context;
use super::pipeline::{Cleanup, Shaders, SPIRV};

// The int8 round-trip shader, beside the table it joins (see `SPIRV`).
// Lives here rather than in `pipeline.rs` so that file stays under the
// 500-line Rust limit.
pub(crate) const QUANTIZE: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/quantize.comp.spv"));

// The Q2_K pair, beside the table they join (see `SPIRV`). Same reason as
// `QUANTIZE` above: `pipeline.rs` must stay under the 500-line Rust limit.
pub(crate) const CONV_POINT_Q2K: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/conv_point_q2k.comp.spv"));
pub(crate) const CONV_Q2K: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/conv_q2k.comp.spv"));
pub(crate) const QUANTIZE_BLOCKS: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/quantize_blocks.comp.spv"));
/// Declares `DotProductKHR`, so it is built only on a device that enabled the extension.
const CONV_POINT_INT4_DOT: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/conv_point_int4_dot.comp.spv"));

impl Shaders {
    /// Compile every shader and build the two layouts. Called once per device.
    pub fn new(context: &Context) -> Result<Shaders, String> {
        // SAFETY: every handle created below is destroyed by `Shaders::destroy`, or on the
        // failure path here before returning.
        unsafe { Self::create(context) }
    }

    unsafe fn create(context: &Context) -> Result<Shaders, String> {
        let device = &context.device;
        let bindings = [
            vk::DescriptorSetLayoutBinding::default()
                .binding(0)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
            vk::DescriptorSetLayoutBinding::default()
                .binding(1)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
            // The weights again, viewed as 32-bit words so int8 tensors can be unpacked
            // without `VK_KHR_8bit_storage`. Same buffer as binding 1; see `common.glsl`.
            vk::DescriptorSetLayoutBinding::default()
                .binding(2)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
            // The weights a third time, as `uvec4`.
            //
            // A gemv reading one 32-bit word at a time reaches 4.7 GB/s on a Tensor G4 while a
            // shader reading the same bytes as `uvec4` reaches 19.6 - four times the bytes per
            // load instruction, and very nearly four times the throughput. Same buffer, same
            // memory; only the width of each fetch differs.
            vk::DescriptorSetLayoutBinding::default()
                .binding(4)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
            // Per-step values the host rewrites without re-recording. See
            // [`super::buffers::Buffer::step_params`] for why this cannot be a push constant.
            vk::DescriptorSetLayoutBinding::default()
                .binding(3)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
        ];
        let descriptor_layout = device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                None,
            )
            .map_err(|e| format!("create_descriptor_set_layout {e:?}"))?;

        let mut cleanup = Cleanup::new(device);
        cleanup.descriptor_layout = Some(descriptor_layout);

        let push_range = vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::COMPUTE)
            .offset(0)
            .size(std::mem::size_of::<Push>() as u32);
        // **One** set layout, not one per segment. A pipeline layout entry declares a distinct
        // *bound set index*, and `record` only ever binds one set, at index 0. Declaring one per
        // segment was harmless on a desktop reporting 32, but `maxBoundDescriptorSets` is only
        // guaranteed to be 4, and NLLB at the guaranteed `maxStorageBufferRange` needs ten
        // windows — so the pipeline layout would fail to create on exactly the devices
        // segmenting exists for.
        let layout = device
            .create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default()
                    .set_layouts(std::slice::from_ref(&descriptor_layout))
                    .push_constant_ranges(std::slice::from_ref(&push_range)),
                None,
            )
            .map_err(|e| format!("create_pipeline_layout {e:?}"))?;
        cleanup.layout = Some(layout);

        let mut built = Vec::new();
        // Seeded from the previous launch's compile, if the driver accepts it. See
        // `pipeline_cache`.
        let (cache, seed) = super::pipeline_cache::open(device);
        let started = std::time::Instant::now();
        let conv_point_int4_dot = if context.int_dot {
            match compute_pipeline(device, layout, cache, CONV_POINT_INT4_DOT) {
                Ok(pipeline) => pipeline,
                Err(e) => {
                    super::pipeline_cache::close(device, cache, &seed);
                    return Err(e);
                }
            }
        } else {
            vk::Pipeline::null()
        };
        for spirv in SPIRV {
            match compute_pipeline(device, layout, cache, spirv) {
                Ok(pipeline) => built.push(pipeline),
                Err(e) => {
                    for pipeline in built {
                        device.destroy_pipeline(pipeline, None);
                    }
                    device.destroy_pipeline(conv_point_int4_dot, None);
                    super::pipeline_cache::close(device, cache, &seed);
                    return Err(e);
                }
            }
        }
        crate::timing!(
            "compiled {} pipelines in {:.0} ms ({} cached bytes in)",
            SPIRV.len(),
            started.elapsed().as_secs_f64() * 1000.0,
            seed.len()
        );
        super::pipeline_cache::close(device, cache, &seed);
        // A fixed-size array rather than a slice pattern of thirteen bindings, so
        // adding a shader is one entry in `SPIRV` and one name here.
        let [
            conv,
            conv_transpose,
            maxpool,
            avgpool,
            resize,
            resize_nearest,
            gap,
            add,
            mul_bcast,
            affine,
            layernorm,
            instancenorm,
            attn_scores,
            softmax,
            attn_apply,
            attn_scores_relative,
            attn_apply_relative,
            attn_scores_banded,
            attn_apply_banded,
            embed,
            conv_int8,
            conv_point,
            conv_point_int8,
            mul,
            constant,
            add_bcast,
            rotary,
            attn_scores_cached,
            attn_apply_cached,
            conv_vec_int8,
            softmax_causal,
            rmsnorm,
            softmax_prefix,
            cache_write,
            softcap,
            activate,
            gated_activate,
            conv_vec_int4,
            conv_point_int4,
            mul_scalar,
            clamp,
            conv_vec_q2k,
            conv_point_q2k,
            conv_q2k,
            quantize,
            argmax,
            argmax_fold,
            topk,
            quantize_blocks,
        ] = match <[vk::Pipeline; SPIRV.len()]>::try_from(built) {
            Ok(all) => all,
            Err(built) => {
                for pipeline in built {
                    device.destroy_pipeline(pipeline, None);
                }
                device.destroy_pipeline(conv_point_int4_dot, None);
                return Err("wrong pipeline count".into());
            }
        };

        cleanup.disarm();
        Ok(Shaders {
            layout,
            descriptor_layout,
            conv,
            conv_transpose,
            maxpool,
            avgpool,
            resize,
            resize_nearest,
            gap,
            add,
            mul_bcast,
            affine,
            layernorm,
            instancenorm,
            attn_scores,
            softmax,
            attn_apply,
            attn_scores_relative,
            attn_apply_relative,
            attn_scores_banded,
            attn_apply_banded,
            embed,
            conv_int8,
            conv_point,
            conv_point_int8,
            mul,
            constant,
            add_bcast,
            rotary,
            attn_scores_cached,
            attn_apply_cached,
            conv_vec_int8,
            softmax_causal,
            rmsnorm,
            softmax_prefix,
            cache_write,
            softcap,
            activate,
            gated_activate,
            conv_vec_int4,
            conv_point_int4,
            mul_scalar,
            clamp,
            conv_vec_q2k,
            conv_point_q2k,
            conv_q2k,
            quantize,
            argmax,
            argmax_fold,
            topk,
            quantize_blocks,
            conv_point_int4_dot,
        })
    }

    /// The pipeline a plan's [`Kind`] wants.
    pub fn for_kind(&self, kind: Kind) -> vk::Pipeline {
        match kind {
            Kind::Conv => self.conv,
            Kind::ConvTranspose => self.conv_transpose,
            Kind::MaxPool => self.maxpool,
            Kind::AvgPool => self.avgpool,
            Kind::Resize => self.resize,
            Kind::ResizeNearest => self.resize_nearest,
            Kind::GlobalAvgPool => self.gap,
            Kind::Add => self.add,
            Kind::MulBroadcast => self.mul_bcast,
            Kind::Affine => self.affine,
            Kind::LayerNorm => self.layernorm,
            Kind::InstanceNorm => self.instancenorm,
            Kind::RmsNorm => self.rmsnorm,
            Kind::AttnScores => self.attn_scores,
            Kind::Softmax => self.softmax,
            Kind::SoftmaxCausal => self.softmax_causal,
            Kind::SoftmaxPrefix => self.softmax_prefix,
            Kind::CacheWrite => self.cache_write,
            Kind::Softcap => self.softcap,
            Kind::Activate => self.activate,
            Kind::GatedActivate => self.gated_activate,
            Kind::ConvVecInt4 => self.conv_vec_int4,
            Kind::ConvPointInt4 => self.conv_point_int4,
            // Same push and tiling as the fp32 shader's `in1` 0 path, which serves it otherwise.
            Kind::ConvPointInt4Dot if self.conv_point_int4_dot != vk::Pipeline::null() => {
                self.conv_point_int4_dot
            }
            Kind::ConvPointInt4Dot => self.conv_point_int4,
            Kind::QuantizeBlocks => self.quantize_blocks,
            Kind::ConvVecQ2K => self.conv_vec_q2k,
            Kind::ConvPointQ2K => self.conv_point_q2k,
            Kind::ConvQ2K => self.conv_q2k,
            Kind::MulScalar => self.mul_scalar,
            Kind::Clamp => self.clamp,
            Kind::AttnApply => self.attn_apply,
            Kind::AttnScoresRelative => self.attn_scores_relative,
            Kind::AttnApplyRelative => self.attn_apply_relative,
            Kind::AttnScoresBanded => self.attn_scores_banded,
            Kind::AttnApplyBanded => self.attn_apply_banded,
            Kind::AttnScoresCached => self.attn_scores_cached,
            Kind::AttnApplyCached => self.attn_apply_cached,
            Kind::Embed => self.embed,
            Kind::ConvInt8 => self.conv_int8,
            Kind::ConvPoint => self.conv_point,
            Kind::ConvPointInt8 => self.conv_point_int8,
            Kind::ConvVecInt8 => self.conv_vec_int8,
            Kind::Mul => self.mul,
            Kind::Constant => self.constant,
            Kind::AddBroadcast => self.add_bcast,
            Kind::Rotary => self.rotary,
            Kind::Quantize => self.quantize,
            Kind::Argmax => self.argmax,
            Kind::ArgmaxFold => self.argmax_fold,
            Kind::TopK => self.topk,
        }
    }

    /// # Safety
    ///
    /// The device must be idle.
    pub unsafe fn destroy(&self, device: &ash::Device) {
        for pipeline in [
            self.conv,
            self.conv_transpose,
            self.maxpool,
            self.avgpool,
            self.resize,
            self.resize_nearest,
            self.gap,
            self.add,
            self.mul_bcast,
            self.affine,
            self.layernorm,
            self.instancenorm,
            self.attn_scores,
            self.softmax,
            self.attn_apply,
            self.attn_scores_relative,
            self.attn_apply_relative,
            self.attn_scores_banded,
            self.attn_apply_banded,
            self.embed,
            self.conv_int8,
            self.conv_point,
            self.conv_point_int8,
            self.mul,
            self.constant,
            self.add_bcast,
            self.rotary,
            self.attn_scores_cached,
            self.attn_apply_cached,
            self.conv_vec_int8,
            self.softmax_causal,
            self.rmsnorm,
            self.softmax_prefix,
            self.cache_write,
            self.softcap,
            self.activate,
            self.conv_vec_int4,
            self.conv_point_int4,
            self.conv_vec_q2k,
            self.conv_point_q2k,
            self.conv_q2k,
            self.mul_scalar,
            self.clamp,
            self.quantize,
            self.argmax,
            self.argmax_fold,
            self.topk,
            self.quantize_blocks,
            self.conv_point_int4_dot,
        ] {
            device.destroy_pipeline(pipeline, None);
        }
        device.destroy_pipeline_layout(self.layout, None);
        device.destroy_descriptor_set_layout(self.descriptor_layout, None);
    }
}

// Pipeline/shader-module construction, moved out of `pipeline.rs` so that
// file stays under the 500-line Rust limit. Called only by `Shaders::create`
// above (plus `shader_module`, called only by `compute_pipeline`).

pub(crate) unsafe fn compute_pipeline(
    device: &ash::Device,
    layout: vk::PipelineLayout,
    cache: vk::PipelineCache,
    spirv: &[u8],
) -> Result<vk::Pipeline, String> {
    let module = shader_module(device, spirv)?;
    let stage = vk::PipelineShaderStageCreateInfo::default()
        .stage(vk::ShaderStageFlags::COMPUTE)
        .module(module)
        .name(c"main");
    let info = vk::ComputePipelineCreateInfo::default().stage(stage).layout(layout);
    let result = device
        .create_compute_pipelines(cache, std::slice::from_ref(&info), None)
        .map_err(|(_, e)| format!("create_compute_pipelines {e:?}"))
        .and_then(|pipelines| {
            pipelines
                .first()
                .copied()
                .ok_or_else(|| "create_compute_pipelines returned nothing".to_string())
        });
    // The module is only needed while the pipeline is being created.
    device.destroy_shader_module(module, None);
    result
}

unsafe fn shader_module(device: &ash::Device, spirv: &[u8]) -> Result<vk::ShaderModule, String> {
    // SPIR-V is a stream of 32-bit words, and `vkShaderModuleCreateInfo` wants it as
    // `*const u32`. Reassembling rather than casting the `&[u8]`: `include_bytes!` gives
    // no alignment guarantee, and a misaligned `u32` read is undefined behaviour even
    // where the hardware tolerates it. This is `library/map`'s approach, not
    // `games/voxels`' pointer cast.
    if !spirv.len().is_multiple_of(4) || spirv.len() < 20 {
        return Err(format!("{} bytes is not a SPIR-V module", spirv.len()));
    }
    let mut words = Vec::with_capacity(spirv.len() / 4);
    for chunk in spirv.chunks_exact(4) {
        words.push(u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
    }
    device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
        .map_err(|e| format!("create_shader_module {e:?}"))
}
