//! The compute pipelines, and the one descriptor set they all share.
//!
//! # One layout, one descriptor set, written once
//!
//! Every shader declares the same two bindings — the activation arena at 0, the weights
//! at 1 — and the same [`Push`] block. So there is one descriptor set layout, one
//! pipeline layout, one descriptor set, and after setup nothing is ever rebound: a layer
//! is `vkCmdBindPipeline` plus `vkCmdPushConstants` plus `vkCmdDispatch`.
//!
//! That is only possible because a tensor is an *element offset* into the arena rather
//! than its own buffer. Binding per-tensor would mean a descriptor set per layer, 350 of
//! them for U^2-Netp, a pool to allocate them from, and `minStorageBufferOffsetAlignment`
//! to respect — all to express something a `u32` already says.
//!
//! # SPIR-V is linked in, not shipped
//!
//! `build.rs` compiles `shaders/*.comp` into `$OUT_DIR` and panics if `glslc` is missing
//! or a shader fails, so these `include_bytes!` cannot silently become stubs the way
//! `games/voxels` does. An asset can be absent at run time; a `&'static [u8]` cannot.

use std::sync::Arc;

use ash::vk;

use crate::nets::Kind;

use super::context::Context;
use super::pipeline_extra::{CONV_POINT_Q2K, CONV_Q2K, QUANTIZE, QUANTIZE_BLOCKS};
use super::segment::Segment;

const CONV: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/conv.comp.spv"));
const CONV_TRANSPOSE: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/conv_transpose.comp.spv"));
const MAXPOOL: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/maxpool.comp.spv"));
const AVGPOOL: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/avgpool.comp.spv"));
const RESIZE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/resize.comp.spv"));
const RESIZE_NEAREST: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/resize_nearest.comp.spv"));
const GAP: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gap.comp.spv"));
const ADD: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/add.comp.spv"));
const MUL_BCAST: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/mul_bcast.comp.spv"));
const AFFINE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/affine.comp.spv"));
const LAYERNORM: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/layernorm.comp.spv"));
const INSTANCENORM: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/instancenorm.comp.spv"));
const RMSNORM: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/rmsnorm.comp.spv"));
const ATTN_SCORES: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/attn_scores.comp.spv"));
const SOFTMAX: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/softmax.comp.spv"));
const SOFTMAX_CAUSAL: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/softmax_causal.comp.spv"));
const SOFTMAX_PREFIX: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/softmax_prefix.comp.spv"));
const CACHE_WRITE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/cache_write.comp.spv"));
const SOFTCAP: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/softcap.comp.spv"));
const ACTIVATE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/activate.comp.spv"));
const GATED_ACTIVATE: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/gated_activate.comp.spv"));
const MUL_SCALAR: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/mul_scalar.comp.spv"));
const CLAMP: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/clamp.comp.spv"));
const ATTN_APPLY: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/attn_apply.comp.spv"));
const ATTN_SCORES_RELATIVE: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/attn_scores_relative.comp.spv"));
const ATTN_APPLY_RELATIVE: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/attn_apply_relative.comp.spv"));
const ATTN_SCORES_BANDED: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/attn_scores_banded.comp.spv"));
const ATTN_APPLY_BANDED: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/attn_apply_banded.comp.spv"));
const EMBED: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/embed.comp.spv"));
const MUL: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/mul.comp.spv"));
const CONV_POINT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/conv_point.comp.spv"));
const CONV_INT8: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/conv_int8.comp.spv"));
const CONV_POINT_INT8: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/conv_point_int8.comp.spv"));
const CONSTANT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/constant.comp.spv"));
const ADD_BCAST: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/add_bcast.comp.spv"));
const ROTARY: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/rotary.comp.spv"));
const ATTN_SCORES_CACHED: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/attn_scores_cached.comp.spv"));
const ATTN_APPLY_CACHED: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/attn_apply_cached.comp.spv"));
const CONV_VEC_INT8: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/conv_vec_int8.comp.spv"));
const CONV_VEC_INT4: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/conv_vec_int4.comp.spv"));
const CONV_POINT_INT4: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/conv_point_int4.comp.spv"));
const CONV_VEC_Q2K: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/conv_vec_q2k.comp.spv"));
const ARGMAX: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/argmax.comp.spv"));
const ARGMAX_FOLD: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/argmax_fold.comp.spv"));
const TOPK: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/topk.comp.spv"));
/// Every shader, in the order `Shaders::create` destructures them (the Q2_K pair,
/// `QUANTIZE`, and the `compute_pipeline` / `shader_module` helpers live in
/// `pipeline_extra.rs` for length).
pub(crate) const SPIRV: [&[u8]; 49] = [
    CONV,
    CONV_TRANSPOSE,
    MAXPOOL,
    AVGPOOL,
    RESIZE,
    RESIZE_NEAREST,
    GAP,
    ADD,
    MUL_BCAST,
    AFFINE,
    LAYERNORM,
    INSTANCENORM,
    ATTN_SCORES,
    SOFTMAX,
    ATTN_APPLY,
    ATTN_SCORES_RELATIVE,
    ATTN_APPLY_RELATIVE,
    ATTN_SCORES_BANDED,
    ATTN_APPLY_BANDED,
    EMBED,
    CONV_INT8,
    CONV_POINT,
    CONV_POINT_INT8,
    MUL,
    CONSTANT,
    ADD_BCAST,
    ROTARY,
    ATTN_SCORES_CACHED,
    ATTN_APPLY_CACHED,
    CONV_VEC_INT8,
    SOFTMAX_CAUSAL,
    RMSNORM,
    SOFTMAX_PREFIX,
    CACHE_WRITE,
    SOFTCAP,
    ACTIVATE,
    GATED_ACTIVATE,
    CONV_VEC_INT4,
    CONV_POINT_INT4,
    MUL_SCALAR,
    CLAMP,
    CONV_VEC_Q2K,
    CONV_POINT_Q2K,
    CONV_Q2K,
    QUANTIZE,
    ARGMAX,
    ARGMAX_FOLD,
    TOPK,
    QUANTIZE_BLOCKS,
];

/// Descriptors in one set: the arena, the weights as fp16, the weights as words, the step params.
///
/// Named so the pool size and the write count cannot drift apart from the layout above.
const BINDINGS: u32 = 5;

/// `local_size_x` in `shaders/common.glsl`. A dispatch covers `ceil(invocations / this)`
/// workgroups, and each shader bails on the over-dispatched tail.
pub const WORKGROUP: u32 = 64;

/// The largest workgroup count per dimension the Vulkan spec *guarantees*
/// (`maxComputeWorkGroupCount`).
///
/// U^2-Netp's widest layer needs 102,400 workgroups, so a 1D dispatch would exceed this on
/// any device at the guaranteed minimum. `run::Net::record` splits the count across x and y
/// and `global_index()` in `shaders/common.glsl` flattens it back. Using the guaranteed
/// floor unconditionally rather than querying the device keeps one code path.
pub const MAX_WORKGROUPS_PER_DIM: u32 = 65_535;

/// Every compute pipeline, and the two layouts they share.
///
/// One per **device**, not one per net. Nothing here depends on a net's buffers: a pipeline is
/// its SPIR-V and its pipeline layout, and every net declares the same five bindings and the same
/// [`Push`] block. Compiling them per net cost Supertonic four times over — 40 shaders × 4 nets at
/// ~450 ms a set, which was most of the time between the TTS service binding and it saying
/// anything. [`Context::shaders`] builds this once and hands out an [`Arc`].
pub struct Shaders {
    /// Shared by all of them, so a bind never invalidates push constants.
    pub layout: vk::PipelineLayout,
    /// What every net's descriptor sets are allocated from. Sets allocated from one layout are
    /// compatible with a pipeline built against an identical one, which is what lets this be
    /// shared while the sets that point at each net's arena stay per-net.
    pub(crate) descriptor_layout: vk::DescriptorSetLayout,
    pub(crate) conv: vk::Pipeline,
    pub(crate) conv_transpose: vk::Pipeline,
    pub(crate) maxpool: vk::Pipeline,
    pub(crate) avgpool: vk::Pipeline,
    pub(crate) resize: vk::Pipeline,
    pub(crate) resize_nearest: vk::Pipeline,
    pub(crate) gap: vk::Pipeline,
    pub(crate) add: vk::Pipeline,
    pub(crate) mul_bcast: vk::Pipeline,
    pub(crate) affine: vk::Pipeline,
    pub(crate) layernorm: vk::Pipeline,
    pub(crate) instancenorm: vk::Pipeline,
    pub(crate) attn_scores: vk::Pipeline,
    pub(crate) softmax: vk::Pipeline,
    pub(crate) attn_apply: vk::Pipeline,
    pub(crate) attn_scores_relative: vk::Pipeline,
    pub(crate) attn_apply_relative: vk::Pipeline,
    pub(crate) attn_scores_banded: vk::Pipeline,
    pub(crate) attn_apply_banded: vk::Pipeline,
    pub(crate) embed: vk::Pipeline,
    pub(crate) conv_int8: vk::Pipeline,
    pub(crate) conv_point: vk::Pipeline,
    pub(crate) conv_point_int8: vk::Pipeline,
    pub(crate) mul: vk::Pipeline,
    pub(crate) constant: vk::Pipeline,
    pub(crate) add_bcast: vk::Pipeline,
    pub(crate) rotary: vk::Pipeline,
    pub(crate) attn_scores_cached: vk::Pipeline,
    pub(crate) attn_apply_cached: vk::Pipeline,
    pub(crate) conv_vec_int8: vk::Pipeline,
    pub(crate) softmax_causal: vk::Pipeline,
    pub(crate) softmax_prefix: vk::Pipeline,
    pub(crate) cache_write: vk::Pipeline,
    pub(crate) softcap: vk::Pipeline,
    pub(crate) activate: vk::Pipeline,
    pub(crate) gated_activate: vk::Pipeline,
    pub(crate) conv_vec_int4: vk::Pipeline,
    pub(crate) conv_point_int4: vk::Pipeline,
    pub(crate) conv_vec_q2k: vk::Pipeline,
    pub(crate) conv_point_q2k: vk::Pipeline,
    pub(crate) conv_q2k: vk::Pipeline,
    pub(crate) mul_scalar: vk::Pipeline,
    pub(crate) clamp: vk::Pipeline,
    pub(crate) rmsnorm: vk::Pipeline,
    pub(crate) quantize: vk::Pipeline,
    pub(crate) argmax: vk::Pipeline,
    pub(crate) argmax_fold: vk::Pipeline,
    pub(crate) topk: vk::Pipeline,
    pub(crate) quantize_blocks: vk::Pipeline,
    /// Null when the device lacks the int8 dot product; see [`Context::int_dot`].
    pub(crate) conv_point_int4_dot: vk::Pipeline,
}

/// One net's descriptor sets, over the device-wide [`Shaders`].
pub struct Pipelines {
    shaders: Arc<Shaders>,
    /// One set per weights segment: arena at binding 0, that segment of the weights at 1 and 2.
    ///
    /// Almost always exactly one. A second appears only when the weights are larger than
    /// `maxStorageBufferRange`, which today means SMaLL-100 on a device reporting the guaranteed
    /// minimum. Every set points at the same arena and the same weights buffer; they differ only
    /// in the weights descriptor's `(offset, range)`. See [`super::segment`].
    pub descriptor_sets: Vec<vk::DescriptorSet>,
    descriptor_pool: vk::DescriptorPool,
}

impl Pipelines {
    /// Point binding 0 at a different arena buffer, for [`super::run::Net::rebuild`].
    ///
    /// Only the arena moves: the weights are uploaded once and outlive any reshape, which is the
    /// whole reason rebuilding beats constructing a second net. The caller must have waited for
    /// the device to go idle first — a descriptor set may not be written while a command buffer
    /// that uses it is pending.
    ///
    /// Every segment's set is rewritten, because they all point at the same arena.
    pub fn rebind_arena(
        &self,
        device: &ash::Device,
        arena: vk::Buffer,
        arena_size: vk::DeviceSize,
    ) {
        let arena_info =
            vk::DescriptorBufferInfo::default().buffer(arena).offset(0).range(arena_size);
        let writes: Vec<_> = self
            .descriptor_sets
            .iter()
            .map(|&set| {
                vk::WriteDescriptorSet::default()
                    .dst_set(set)
                    .dst_binding(0)
                    .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                    .buffer_info(std::slice::from_ref(&arena_info))
            })
            .collect();
        // SAFETY: descriptor writes to sets this struct owns, against a buffer the caller keeps
        // alive. Nothing using them is pending, per the contract above.
        unsafe { device.update_descriptor_sets(&writes, &[]) };
    }

    /// Build all of them and point one descriptor set per segment at `arena` and `weights`.
    pub fn new(
        context: &Context,
        arena: vk::Buffer,
        arena_size: vk::DeviceSize,
        weights: vk::Buffer,
        params: vk::Buffer,
        segments: &[Segment],
    ) -> Result<Pipelines, String> {
        let shaders = context.shaders()?;
        // SAFETY: every handle created below is destroyed by `Pipelines::destroy`, or on
        // the failure path here before returning.
        unsafe { Self::create(context, shaders, arena, arena_size, weights, params, segments) }
    }

    unsafe fn create(
        context: &Context,
        shaders: Arc<Shaders>,
        arena: vk::Buffer,
        arena_size: vk::DeviceSize,
        weights: vk::Buffer,
        params: vk::Buffer,
        segments: &[Segment],
    ) -> Result<Pipelines, String> {
        let device = &context.device;
        if segments.is_empty() {
            return Err("a net needs at least one weights segment".into());
        }
        let descriptor_layout = shaders.descriptor_layout;
        let mut cleanup = Cleanup::new(device);

        let count = u32::try_from(segments.len()).map_err(|_| "too many weights segments")?;
        let pool_sizes = [vk::DescriptorPoolSize::default()
            .ty(vk::DescriptorType::STORAGE_BUFFER)
            .descriptor_count(BINDINGS * count)];
        let descriptor_pool = device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default().max_sets(count).pool_sizes(&pool_sizes),
                None,
            )
            .map_err(|e| format!("create_descriptor_pool {e:?}"))?;
        cleanup.descriptor_pool = Some(descriptor_pool);

        let layouts = vec![descriptor_layout; segments.len()];
        let descriptor_sets = device
            .allocate_descriptor_sets(
                &vk::DescriptorSetAllocateInfo::default()
                    .descriptor_pool(descriptor_pool)
                    .set_layouts(&layouts),
            )
            .map_err(|e| format!("allocate_descriptor_sets {e:?}"))?;
        if descriptor_sets.len() != segments.len() {
            return Err(format!(
                "allocate_descriptor_sets returned {} sets for {} segments",
                descriptor_sets.len(),
                segments.len()
            ));
        }

        let arena_info = vk::DescriptorBufferInfo::default()
            .buffer(arena)
            .offset(0)
            .range(arena_size);
        let params_info = vk::DescriptorBufferInfo::default()
            .buffer(params)
            .offset(0)
            .range(vk::WHOLE_SIZE);
        // Held outside the loop so the `buffer_info` borrows stay live until the one
        // `update_descriptor_sets` below.
        let weight_infos: Vec<_> = segments
            .iter()
            .map(|segment| {
                vk::DescriptorBufferInfo::default()
                    .buffer(weights)
                    .offset(segment.base)
                    .range(segment.len)
            })
            .collect();
        let mut writes = Vec::with_capacity(segments.len() * BINDINGS as usize);
        for (set, info) in descriptor_sets.iter().zip(&weight_infos) {
            writes.push(
                vk::WriteDescriptorSet::default()
                    .dst_set(*set)
                    .dst_binding(0)
                    .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                    .buffer_info(std::slice::from_ref(&arena_info)),
            );
            writes.push(
                vk::WriteDescriptorSet::default()
                    .dst_set(*set)
                    .dst_binding(1)
                    .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                    .buffer_info(std::slice::from_ref(info)),
            );
            // Binding 2 is the same range as binding 1, viewed as 32-bit words so that int8
            // tensors can be unpacked. Both are read-only, so aliasing them is safe.
            writes.push(
                vk::WriteDescriptorSet::default()
                    .dst_set(*set)
                    .dst_binding(2)
                    .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                    .buffer_info(std::slice::from_ref(info)),
            );
            writes.push(
                vk::WriteDescriptorSet::default()
                    .dst_set(*set)
                    .dst_binding(4)
                    .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                    .buffer_info(std::slice::from_ref(info)),
            );
            // The same params buffer on every set: which segment an op is dispatched through
            // says nothing about the step it belongs to.
            writes.push(
                vk::WriteDescriptorSet::default()
                    .dst_set(*set)
                    .dst_binding(3)
                    .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                    .buffer_info(std::slice::from_ref(&params_info)),
            );
        }
        device.update_descriptor_sets(&writes, &[]);

        cleanup.disarm();
        Ok(Pipelines { shaders, descriptor_sets, descriptor_pool })
    }

    /// The pipeline layout every net binds through, owned by [`Shaders`].
    pub fn layout(&self) -> vk::PipelineLayout {
        self.shaders.layout
    }

    /// The pipeline a plan's [`Kind`] wants.
    pub fn for_kind(&self, kind: Kind) -> vk::Pipeline {
        self.shaders.for_kind(kind)
    }

    /// # Safety
    ///
    /// The device must be idle.
    pub unsafe fn destroy(&self, device: &ash::Device) {
        // The sets are freed with their pool. The pipelines and both layouts belong to
        // `Shaders`, which outlives every net through the `Arc` above.
        device.destroy_descriptor_pool(self.descriptor_pool, None);
    }
}

/// Undoes a partially-built [`Pipelines`] on any early return.
///
/// There are five handles created in sequence and each failure point has to destroy a
/// different subset of them. A guard whose [`Drop`] does it means the fallible calls can
/// keep using `?` instead of nesting five `match`es, and no future insertion between two
/// of them can forget a leak.
pub(crate) struct Cleanup<'a> {
    pub(crate) device: &'a ash::Device,
    pub(crate) armed: bool,
    pub(crate) descriptor_layout: Option<vk::DescriptorSetLayout>,
    pub(crate) descriptor_pool: Option<vk::DescriptorPool>,
    pub(crate) layout: Option<vk::PipelineLayout>,
}

impl<'a> Cleanup<'a> {
    pub(crate) fn new(device: &'a ash::Device) -> Cleanup<'a> {
        Cleanup {
            device,
            armed: true,
            descriptor_layout: None,
            descriptor_pool: None,
            layout: None,
        }
    }

    pub(crate) fn disarm(&mut self) {
        self.armed = false;
    }
}

impl Drop for Cleanup<'_> {
    fn drop(&mut self) {
        if !self.armed {
            return;
        }
        // SAFETY: nothing has been submitted, so no command buffer references any of
        // these; and each is destroyed at most once because `disarm` is called on
        // success.
        unsafe {
            if let Some(layout) = self.layout {
                self.device.destroy_pipeline_layout(layout, None);
            }
            if let Some(pool) = self.descriptor_pool {
                self.device.destroy_descriptor_pool(pool, None);
            }
            if let Some(layout) = self.descriptor_layout {
                self.device.destroy_descriptor_set_layout(layout, None);
            }
        }
    }
}
