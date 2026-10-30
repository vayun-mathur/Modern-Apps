//! The fill and line pipelines, built from the SPIR-V the Gradle `compileShaders` task compiled.
//!
//! # Push constants, and no descriptor sets at all
//!
//! Everything per-draw — the tile's clip matrix, the layer's colour, width, gap and dash,
//! the per-frame clock and the per-tile morph factor — is 128 bytes, exactly the minimum
//! the Vulkan spec guarantees for push constants. So there are no uniform buffers, no
//! descriptor set layouts, no descriptor pool and nothing to keep in sync with the camera.
//! `vkCmdPushConstants` before each draw is the whole per-draw state.
//!
//! This is a deliberate departure from the WebGPU design that preceded it, which used a
//! uniform buffer per tile plus a bind group per (tile, layer) so that pre-recorded
//! render bundles could be replayed while the camera moved. Bundles existed to avoid
//! WebGPU's per-draw validation cost; raw Vulkan does not have that cost, so the
//! simplest thing that works is to re-record the command buffer each frame. Roughly 600
//! draws at a few `vkCmd` calls each is a fraction of a millisecond. If profiling says
//! otherwise, secondary command buffers are the escape hatch — and they would need the
//! descriptor-set indirection back, because push constants are not inherited.

mod assemble;
mod attributes;
mod pipelines;
mod pipelines_attrs;
mod pipelines_extra;
mod pipelines_globe;
mod pipelines_moon;
mod pipelines_symbols;
mod push;
mod shaders;
mod state;

pub use pipelines::Pipelines;
pub use push::{Push, MORPH_NONE, NO_MARKINGS, PUSH_CONSTANT_BYTES};
pub use state::{Depth, Stencil};
