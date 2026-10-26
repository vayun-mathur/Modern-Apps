//! The Vulkan vector-tile map renderer behind `:library:map`.
//!
//! Replaces the CARTO raster basemap in findfamily, photos, weather, taxi and
//! fooddelivery (#615) with our own renderer over the planet-scale v7
//! single-file `planet3.mamaps` archive at `data.maproj.org/planet3.mamaps`
//! (12 layers, 128-byte header).
//!
//! # Layout
//!
//! * [`tess`] — mamaps geometry to triangles: earcut fills, stroked lines. Pure, and the
//!   half of a frame that actually costs something.
//! * [`style`] — which layers are drawn, in what order, in what colour.
//! * [`camera`] — a camera snapshot from Kotlin to a per-tile clip matrix.
//! * [`overlay`] — geographic polylines that are not tile data: the navigation route.
//! * [`tile`] — the streaming mamaps reader, its range cache, and tile selection.
//! * [`timing`] — per-step frame timing: `Instant` deltas on the render thread, no Vulkan, no
//!   JNI, host-testable.
//! * `vulkan` — device, swapchain, pipelines, frame. Android only.
//!
//! # The JNI boundary
//!
//! Deliberately narrow, and never in the hot loop: Kotlin creates and destroys the
//! renderer for a `Surface`, resizes it, and hands it **one camera snapshot per
//! frame**. Everything else — tile selection, fetching, decode, tessellation,
//! drawing — happens on this side. A per-feature or per-vertex crossing would cost
//! more than the rendering does.
//!
//! `Projection` stays in Kotlin, in `Dp`, because it is public API that every
//! consumer app's overlays are positioned by.
//!
//! # Host builds
//!
//! Everything except the Vulkan and JNI layers compiles and tests on the host, so
//! `cargo test` covers tessellation, tile selection, the cache and the camera maths
//! with no device attached.

pub mod camera;
pub mod camera_extra;
pub mod camera_extra2;
pub mod camera_extra3;
pub mod camera_globe;
#[cfg(test)]
mod camera_globe_tests;
pub mod marker;
pub mod overlay;
pub mod overlay_parts;
pub mod pipeline_cache;
pub mod range_alloc;
pub mod style;
pub mod tess;
pub mod tile;
pub mod timing;

#[cfg(target_os = "android")]
pub mod vulkan;

#[cfg(target_os = "android")]
mod bridge;
