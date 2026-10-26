use crate::camera::Camera;
use crate::vulkan::buffers::ScratchRing;
use crate::vulkan::cache::ShaderCache;
use crate::vulkan::context::{ANativeWindow, Context};
use crate::vulkan::images::{AtlasSet, SampledImage};
use crate::vulkan::pick::Pick;
use crate::vulkan::pipeline::Pipelines;
use crate::vulkan::swapchain::Swapchain;
use ash::vk;
use std::cell::Cell;
use std::collections::HashMap;

use super::{
    AcceptSet, Frame, Overlay, PlacedHit, PlacementKey, ResidentTile, RouteBuffers,
    TransientBuffers,
};
use super::quad::Quad;
use super::upload_pool::UploadPool;
use crate::style::LayerKind;

pub struct Renderer {
    pub(crate) context: Context,
    pub(crate) swapchain: Swapchain,
    pub(crate) pipelines: Pipelines,
    /// Sampled-image infra shared by the glyph atlas and the sprite atlas: one
    /// pool/layout, one set per atlas. Uploaded once at startup from the
    /// CPU-built atlas bytes.
    pub(crate) atlas_set: AtlasSet,
    pub(crate) glyph_atlas: Option<SampledImage>,
    pub(crate) glyph_set: Option<vk::DescriptorSet>,
    /// The POI icon sheet, uploaded beside the glyphs. `None` when the sheet would
    /// not decode, which leaves POI labels drawing without icons rather than not at
    /// all.
    pub(crate) sprite_atlas: Option<SampledImage>,
    pub(crate) sprite_set: Option<vk::DescriptorSet>,
    /// Moon raster pair (maps-only lunar globe): LROC color + LDEM elevation,
    /// uploaded once via [`Renderer::set_moon_textures`](Self::set_moon_textures)
    /// from the JNI `setMoonTextures` push. `None` until the host pushes (or on
    /// a host that never does), in which case a Moon frame draws the clear
    /// colour rather than failing the frame.
    pub(crate) moon_color: Option<SampledImage>,
    pub(crate) moon_dem: Option<SampledImage>,
    /// Moon-owned descriptor pool + sets (color set 0, DEM set 1) for the Moon
    /// pipeline's two-image layout. `None` until the first `set_moon_textures`
    /// push; recreated (old pool destroyed) on every re-push, so a re-attach
    /// re-uploads rather than leaking.
    pub(crate) moon_pool: Option<vk::DescriptorPool>,
    pub(crate) moon_set: Option<vk::DescriptorSet>,
    /// Retired Moon images + pools waiting out the frames-in-flight grace (see
    /// `collect_moon_retired`, drained from `collect_retired`).
    pub(crate) moon_retiring: Vec<(usize, super::upload_moon::MoonRetired)>,
    /// Per-frame synchronisation and its command buffer.
    pub(crate) frames: Vec<Frame>,
    pub(crate) command_pool: vk::CommandPool,
    /// Per-frame scratch, reused across frames rather than reallocated per record.
    ///
    /// `record_inner` runs on every frame on the Choreographer callback; allocating its working
    /// sets (`draws`, `ordered`, `resident`, `tile_alpha`, `deferred_symbols`) fresh each time
    /// is allocator traffic for values that are recomputed wholesale. These live on the renderer
    /// and are cleared at the top of each record instead.
    pub(crate) scratch_draws: Vec<(
        u8,
        u32,
        u32,
        LayerKind,
        ash::vk::Buffer,
        ash::vk::DeviceSize,
        ash::vk::Buffer,
        ash::vk::DeviceSize,
        u32,
        u32,
        Option<u32>,
        (u8, u8, u8),
    )>,
    /// Resident-tile keys sorted coarsest-first for draw order (see `record_inner`).
    pub(crate) scratch_ordered: Vec<u64>,
    /// Resident-tile key set for the LOD cross-fade + placement key.
    pub(crate) scratch_resident: std::collections::HashSet<u64>,
    /// Per-tile LOD opacity for the frame (see `tile_lod_alpha`).
    pub(crate) scratch_tile_alpha: std::collections::HashMap<u64, f32>,
    /// `(tile key, layer index)` symbol draws deferred past the buildings pass.
    pub(crate) scratch_deferred_symbols: Vec<(u64, usize)>,
    pub(crate) frame_index: usize,
    pub(crate) tiles: HashMap<u64, ResidentTile>,
    /// Retired buffers waiting for the frames that might still reference them.
    pub(crate) retiring: Vec<(usize, ResidentTile)>,
    /// The persistent shader-compilation cache, seeded from disk at startup and shared by every
    /// pipeline and by [`Pick`]. Outlives [`Pipelines`], which is destroyed and rebuilt whenever
    /// the render pass changes — that is the whole point, since the rebuild is what used to
    /// recompile twelve pipelines inside a frame.
    pub(crate) pipeline_cache: ShaderCache,
    /// Transient per-frame symbol buffers, same grace rule as `retiring`.
    pub(crate) transients: Vec<TransientBuffers>,
    /// One scratch bump allocator per frame in flight, which every symbol draw's geometry is
    /// suballocated from. Indexed by [`frame_index`](Self::frame_index) and reset once that
    /// frame's fence has signalled. See [`ScratchRing`].
    pub(crate) scratch: Vec<ScratchRing>,
    pub(crate) window: *mut ANativeWindow,
    pub width: u32,
    pub height: u32,
    /// Set when the swapchain needs rebuilding: a resize, a rotation, or an out-of-date
    /// present.
    pub(crate) needs_rebuild: bool,
    /// Draw calls actually submitted by the last recorded frame.
    ///
    /// Counted where they are issued rather than re-derived, because a layer can be resident
    /// and still not drawn — the authored style ramps a road's width to zero outside the zooms
    /// it is meant for, and [`record`](Self::record) skips it. Any second implementation of
    /// that test would drift out of step with the one that matters and the number would start
    /// lying again, more subtly.
    ///
    /// A `Cell` because `record` takes `&self`; the frame path is single-threaded, as the
    /// module docs of [`crate::bridge`] set out.
    pub(crate) submitted_draws: Cell<usize>,
    /// Per-step frame timing (see [`crate::timing`]): last-frame steps plus the rolling
    /// sum/max the per-frame report prints and resets. A `RefCell` because the record sub-passes take `&self`
    /// — the same reason `placed` is one — and each record is a short leaf borrow.
    pub(crate) step_times: std::cell::RefCell<crate::timing::StepTimes>,
    /// Task-17 pick state: the last frame's PLACED labels — accept-set id,
    /// screen box in DEVICE px, layer index, display name, kind string, and
    /// anchor lon/lat — so `pick_labels` answers without re-tessellating.
    /// Refreshed by `record_inner` only when the accept-set changes (see the
    /// `last_accept_ids` guard there); read by the JNI pick path.
    pub(crate) placed: std::cell::RefCell<Vec<PlacedHit>>,
    /// Sorted accept-set ids of the last `refresh_placed`, so `record_inner` can skip the
    /// per-frame String clones when the placement was reused (see `PLACE_REUSE_MS`).
    pub(crate) last_accept_ids: std::cell::RefCell<Option<Vec<u64>>>,
    /// The last symbol placement and the state it was computed from.
    ///
    /// [`place_symbols`](Self::place_symbols) projects a collision box for every glyph of every
    /// curved label and then runs a solver that is quadratic in accepted boxes, all of it on the
    /// Choreographer callback. None of that depends on the frame clock, so a camera that has not
    /// moved gets last frame's answer instead of the same computation again — and a camera that
    /// has only *panned* within [`PLACE_REUSE_MS`](super::PLACE_REUSE_MS) reuses it
    /// too, which is what keeps panning from re-placing every frame.
    pub(crate) placement_cache:
        std::cell::RefCell<Option<(PlacementKey, AcceptSet, std::time::Instant)>>,
    /// What this frame draws on top of every tile, in order. See [`Overlay`].
    pub(crate) overlays: Vec<Overlay>,
    /// How many marker sets the host has pushed — bumped by every [`set_markers`](Renderer::set_markers).
    ///
    /// Part of the symbol-placement key (see [`PlacementKey`]): a new pin must immediately cull
    /// the POIs it covers, and placement otherwise reuses its answer while the camera is still.
    pub(crate) marker_epoch: u64,
    /// The geometry every overlay shares, uploaded once.
    pub(crate) quad: Quad,
    /// The OSM relation whose shape is punched out of the mask scrim, if any.
    ///
    /// Not an [`Overlay`]: an overlay draws itself over the tiles, while this one is a property
    /// of how every tile is drawn — two pipelines and a stencil rather than one quad.
    pub(crate) selected_region: Option<u64>,
    /// The navigation route line, or `None` when no route is set.
    pub(crate) route: Option<RouteBuffers>,
    /// The pack-driven rail-lines overlay, or `None` when the transit layer
    /// is off or the pack carries no shapes for the viewport. A second
    /// `RouteBuffers` slot beside the navigation route (same mesh, upload and
    /// draw path) so the network and a selected route coexist — the network
    /// draws first, the route over it.
    pub(crate) rail_lines: Option<RouteBuffers>,
    /// The live-traffic colour table: `component_id → ARGB`, pushed from the host each update.
    ///
    /// The device owns the theme and palette, so it sends fully-resolved colours; the renderer
    /// only looks them up. A segment whose id is absent draws nothing (see [`record_traffic`]),
    /// which keeps the overlay to the roads traffic actually covers rather than flooding the
    /// whole network with a neutral tint. Replacing this map is the whole of a recolour — no
    /// geometry is touched — so new speeds cost no tessellation.
    ///
    /// [`record_traffic`]: Self::record_traffic
    pub(crate) traffic_colors: HashMap<u64, u32>,
    /// Whether the traffic overlay is drawn this frame. Set from the host's layer toggle; the
    /// geometry is also gated at tessellation, so this is the cheap per-frame guard that stops
    /// resident traffic meshes drawing in the window before a toggle-off re-tessellation lands.
    pub(crate) traffic_enabled: bool,
    /// The offscreen id-buffer pass, for tap picking. Self-contained (its own render pass, pipeline
    /// and target); invoked out of band by [`pick_at`](Self::pick_at), never in the frame loop.
    pub(crate) pick: Pick,
    /// The last camera a frame was recorded with, so [`pick_at`](Self::pick_at) can place markers
    /// against the frame the user is actually looking at. `None` before the first frame.
    pub(crate) last_camera: Option<Camera>,
    /// Version of everything affecting the picture that is NOT the camera pose: overlays
    /// (puck, markers, vehicles), route/rail meshes, traffic table + toggle, region mask,
    /// Moon textures. Bumped by every setter below; part of the submit early-out key in
    /// [`render`](Self::render) (see `FrameVersion`).
    pub(crate) state_epoch: Cell<u64>,
    /// Version of tile residency: bumped by [`upload`](Self::upload) and by
    /// [`retain`](Self::retain) when it evicts anything. A new tile or an eviction changes
    /// the picture, so the early-out key carries this instead of hashing the resident set.
    pub(crate) residency_epoch: Cell<u64>,
    /// How many times [`place_symbols`](Self::place_symbols) recomputed (not reused) the
    /// accept-set. Reuse hits leave this alone, so the early-out key sees a stable placement
    /// across the retained-collision slow path without hashing the accept-set per frame.
    pub(crate) placement_version: Cell<u64>,
    /// What the last submitted frame drew, for the MapLibre-style submit early-out: when the
    /// next frame's version is identical, re-recording + `queue_submit` would present a
    /// bit-identical image, so the frame is skipped (counted in `Step::EarlyOut`). `None`
    /// before the first submit and after anything that invalidates unconditionally
    /// (swapchain rebuild), forcing the next frame through.
    pub(crate) last_frame_version: Option<FrameVersion>,
    /// Tile-upload staging arenas (see `upload_pool`): sub-allocated per tile and recycled on
    /// retire, so a tile upload costs copies but no `vkAllocateMemory`.
    pub(crate) upload_pool: UploadPool,
    /// Which resident tiles are fully covered by settled finer tiles
    /// ([`cover`](crate::tile::select::cover)), per draw group: the
    /// covered-stand-in suppression map, refreshed by
    /// [`refresh_suppression`](Renderer::refresh_suppression) before each
    /// record. A tile absent from the map draws everything; a tile present
    /// skips the listed groups.
    pub(crate) suppression: HashMap<u64, SuppressedDraws>,
    /// The residency epoch the suppression map was computed against. Together
    /// with [`suppression_had_unsettled`](Self::suppression_had_unsettled),
    /// this is what lets a steady frame skip the recompute: nothing can have
    /// changed while no upload/eviction landed and no fade was in flight.
    pub(crate) suppression_residency: u64,
    /// Whether the last refresh saw a fade still in flight. A fade completing
    /// changes who covers and who is suppressible, so a frame that had an
    /// unsettled tile always recomputes next frame.
    pub(crate) suppression_had_unsettled: bool,
    /// Version of the suppression map: bumped whenever it changes. Part of the
    /// submit early-out key in [`render`](Self::render) (see `FrameVersion`),
    /// so the fade-completion frame that newly suppresses a stand-in actually
    /// submits instead of skipping as "identical".
    pub(crate) suppression_epoch: Cell<u64>,
}

/// A resident tile's suppressed draw groups: which of its passes are redundant
/// because settled finer residents cover the whole tile with the same content.
///
/// Flat layers suppress individually (`layers` holds style layer indices), so
/// the z14 `landtype` wash survives where z15 tiles carry roads but no earth.
/// The mesh passes suppress as whole groups: each is one content kind per
/// tile. Empty means nothing is suppressed — the tile is absent from the map
/// rather than stored empty.
#[derive(Clone, Default, PartialEq, Eq)]
pub(crate) struct SuppressedDraws {
    /// Style layer indices whose flat draws this tile skips, sorted.
    pub layers: Vec<usize>,
    /// Skip the carriageway (ribbon) pass.
    pub carriageways: bool,
    /// Skip the DEM terrain pass.
    pub terrain: bool,
    /// Skip the building pass.
    pub buildings: bool,
    /// Skip the traffic overlay pass.
    pub traffic: bool,
    /// Skip the turn-arrow pass.
    pub arrows: bool,
}

/// What one submitted frame drew, for the submit early-out.
///
/// Everything here is compared by value per frame — all integers or bit patterns, no hashing,
/// no allocation. The camera pose is exact bits (any pose change draws); the clock is
/// quantised to 50 ms bins so dash-phase animation still steps during grace windows while
/// identical frames 16 ms apart skip. Residency, placement and overlay state ride as epoch
/// counters bumped at their single choke points; palette is a plain value (push-constant
/// recolour, no re-tessellation).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub(crate) struct FrameVersion {
    cam_lon: u64,
    cam_lat: u64,
    zoom: u64,
    bearing: u64,
    pitch: u64,
    width_dp: u32,
    height_dp: u32,
    density: u32,
    globe: bool,
    moon: bool,
    /// `time_seconds` in 50 ms bins: animation still moves during grace, steady frames skip.
    time_bin: i64,
    palette_dark: bool,
    palette_muted: bool,
    residency: u64,
    placement: u64,
    state: u64,
    suppression: u64,
}

impl FrameVersion {
    /// The version the current frame would submit. `palette` is read live (push-constant
    /// state, never tessellated); the epochs come from the renderer's counters.
    pub(crate) fn current(
        camera: &Camera,
        palette: crate::style::Palette,
        residency: u64,
        placement: u64,
        state: u64,
        suppression: u64,
    ) -> FrameVersion {
        FrameVersion {
            cam_lon: camera.center_lon.to_bits(),
            cam_lat: camera.center_lat.to_bits(),
            zoom: camera.zoom.to_bits(),
            bearing: camera.bearing_deg.to_bits(),
            pitch: camera.pitch_deg.to_bits(),
            width_dp: camera.width_dp.to_bits(),
            height_dp: camera.height_dp.to_bits(),
            density: camera.density.to_bits(),
            globe: camera.globe,
            moon: camera.moon,
            time_bin: (camera.time_seconds * 20.0).floor() as i64,
            palette_dark: palette.variant == crate::style::Variant::Dark,
            palette_muted: palette.muted,
            residency,
            placement,
            state,
            suppression,
        }
    }
}

impl Renderer {
    /// Bump the picture-state epoch: anything that changes what a frame draws outside the
    /// camera pose and tile residency (overlays, route/rail, traffic, region, Moon) calls
    /// this, so the submit early-out sees the change without comparing state.
    pub(crate) fn bump_state(&self) {
        self.state_epoch.set(self.state_epoch.get().wrapping_add(1));
    }

    /// Bump the residency epoch: an upload or an eviction changed the resident set.
    pub(crate) fn bump_residency(&self) {
        self.residency_epoch
            .set(self.residency_epoch.get().wrapping_add(1));
    }

    /// Forcibly retire the last submitted version (swapchain rebuild path): the next frame
    /// submits unconditionally, because a new swapchain/extent must present at least once.
    pub(crate) fn forget_submitted(&mut self) {
        self.last_frame_version = None;
    }
}
