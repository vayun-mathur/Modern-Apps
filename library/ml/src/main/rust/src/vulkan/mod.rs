//! The Vulkan compute layer.
//!
//! Built on every target, not just Android: `ash`'s `loaded` feature dlopens whatever
//! Vulkan loader the platform provides, so the shaders can be executed on a development
//! host instead of only on a phone.
//!
//! * [`context`] — instance, device, compute queue, and the fp16 feature chaining that
//!   Vulkan 1.1 makes necessary.
//! * [`buffers`] — the weights buffer, the activation arena, and the one staging buffer
//!   both directions go through.
//! * [`pipeline`] — the seven compute pipelines and the descriptor sets they share.
//! * [`segment`] — splitting the weights buffer into descriptor-sized pieces, because
//!   `maxStorageBufferRange` is only guaranteed to be 128 MiB and SMaLL-100's weights are 318.
//! * [`run`] — recording a [`crate::nets::Plan`] into one command buffer, and submitting it.
//!
//! Nothing above this layer knows Vulkan exists: [`crate::nets`] produces a flat plan of
//! offsets and [`run`] is the only thing that turns those into commands. That split is
//! what lets `cargo test` cover both networks on the host.

pub mod buffers;
pub mod context;
pub mod imageprobe;
pub mod pipeline;
pub mod pipeline_extra;
mod pipeline_cache;
pub mod reshape;
pub mod run;
pub mod segment;
pub mod segment_extra;

#[cfg(test)]
mod parity;

#[cfg(test)]
#[path = "segment_tests.rs"]
mod segment_tests;
