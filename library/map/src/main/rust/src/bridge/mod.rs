//! The JNI surface: a small, fixed set of entry points, and nothing per-feature or
//! per-vertex.
//!
//! Kotlin creates and destroys the renderer for a `Surface`, resizes it, tells it whether
//! the device is online, and hands it **one camera snapshot per frame**. Tile selection,
//! fetching, decode, tessellation and drawing all happen on this side, so the boundary is
//! crossed a handful of times a frame rather than thousands.
//!
//! # Threading
//!
//! `render` is called from Kotlin's `Choreographer` callback, so on the main thread; every
//! Vulkan call happens there and the renderer is never driven from two threads at once.
//! Tile work — the range fetch, the gzip inflate, the MVT decode and the tessellation —
//! runs on a worker thread and hands finished meshes back through a channel, which
//! `render` drains. That is the split the plan asks for: the expensive half off the
//! critical path, and no JNI in the hot loop.
//!
//! Split by domain; each module header names what it holds.
mod frame;
mod handle;
mod layers;
mod lifecycle;
// `pub(crate)` so the tile fetch path (`tile::source::fetch`, issue-815 timing)
// can log through the same `MapRenderer` tag without a second log shim.
pub(crate) mod log;
mod markers;
mod moon;
mod pick;
mod puck;
mod region;
mod route;
mod workers;
