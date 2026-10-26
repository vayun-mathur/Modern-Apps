use super::fog::{apply_fog, culled_flat_draws, fog_color, fog_factor};
use super::bind_state::{flat_pipeline, BindState};
use super::{
    anchors_for, argb_to_rgba, scale_alpha, Overlay, Renderer, UserPuck, ARROW_COLOR, ARROW_DP,
    BUILDINGS_DRAW_MIN_ZOOM, IDENTITY, PUCK_COLOR, PUCK_CONE_DP, PUCK_CONE_HALF_STROKE_DP,
    PUCK_DOT_DP, PUCK_QUAD_DP, PUCK_RIM_DP, QUAD_INDICES, SCRIM_COLOR, TRAFFIC_WIDTH_DP,
};
use crate::camera::Camera;
use crate::marker::{Marker, MARKER_SIZE_DP};
use crate::style::paint::Stroke;
use crate::style::{Layer, LayerKind, Palette};
use crate::tile::select;
use crate::timing::{nanos_since, Step};
use crate::vulkan::pipeline::{Push, MORPH_NONE, NO_MARKINGS};
use ash::vk;
use std::collections::HashMap;
use tilecodec::mamaps::dict::LAYER_JUNCTION;

impl Renderer {
    /// Record the frame's single render pass.
    ///
    /// Draw order is **layer-major across tiles**: for each style layer, every resident
    /// tile's geometry for it. Tile-major would let one tile's road casing land on top of
    /// the next tile's road fill, which shows as a seam along every tile boundary.
    ///
    /// `record` takes `&mut self` (not `&self` like before) because symbol layers
    /// upload transient per-frame buffers. The frame path is still single-threaded
    /// per `crate::bridge`'s docs; only one command buffer records at a time.
    pub(super) unsafe fn record(
        &mut self,
        command_buffer: vk::CommandBuffer,
        image_index: usize,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        clear: u32,
        filter: &crate::style::KindFilter,
    ) -> Result<(), String> {
        // `record_symbol` takes `&mut self` (transient uploads), so `record`
        // issues all fill/line draws through small helpers that re-borrow per
        // call — no `self.` reference lives across a `&mut self` call. The ash
        // `device` is `Copy`-free but its methods take `&self`; copy the few
        // Copy handles needed (pipeline ids, layout) per draw instead.
        let render_pass = self.swapchain.render_pass;
        let framebuffer = self.swapchain.framebuffers[image_index];
        let extent = self.swapchain.extent;
        unsafe {
            self.record_inner(
                command_buffer,
                render_pass,
                framebuffer,
                extent,
                camera,
                layers,
                palette,
                clear,
                filter,
            )
        }
    }

    /// The body of [`record`](Self::record): split out so the borrow structure
    /// reads linearly. Draw order: one subpass, so order *is* correctness — flat
    /// layers blend (depth-off), 3D ones resolve by depth. Sequence:
    /// terrain, basemap layers, carriageways, traffic, arrows, region, route, overlays.
    #[allow(clippy::too_many_arguments)]
    unsafe fn record_inner(
        &mut self,
        command_buffer: vk::CommandBuffer,
        render_pass: vk::RenderPass,
        framebuffer: vk::Framebuffer,
        extent: vk::Extent2D,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        clear: u32,
        filter: &crate::style::KindFilter,
    ) -> Result<(), String> {
        let device = self.context.device.clone();
        Self::begin_frame_pass(
            &device,
            command_buffer,
            render_pass,
            framebuffer,
            extent,
            clear,
        )?;

        let mut bound: Option<LayerKind> = None;
        let mut submitted = 0usize;
        // Flat draws skipped by the off-screen cull below, counted into `Step::CullSkipped`
        // so the debug overlay shows what the cull saved. Tail passes add their own skips
        // to this through `record_tail`.
        let mut culled = 0usize;
        // Pipeline + descriptor bind dedup for the frame (MapLibre's program batching
        // without reordering any draw): every pass below binds through this instead of
        // issuing raw binds, so repeated binds of the same state collapse to one.
        let mut binds = BindState::new();
        // One tile-layer's draws, reused across the whole frame — and across frames: the
        // buffer lives on the renderer (`scratch_draws`) and is only cleared per tile below,
        // so a frame pays no allocation for it. Each draw carries its pool's bind offsets
        // alongside the slice (see the loop). A layer used to have at most one mesh per
        // tile; transit breaks that, so the handles are copied out rather than `find`-ing
        // — which also keeps `record_symbol`'s `&mut self` call legal below.
        // Borrowed out once here (not per tile below): `draws`/`ordered`/`tile_alpha` are
        // disjoint fields from `self.tiles`, and the loop's `&mut self` symbol call needs
        // the compiler to see that.
        let (draws, ordered, tile_alpha) = {
            self.scratch_draws.clear();
            self.scratch_ordered.clear();
            self.scratch_ordered.extend(self.tiles.keys().copied());
            self.scratch_ordered
                .sort_by_key(|k| self.tiles.get(k).map(|t| t.z).unwrap_or(0));
            // WS-D LOD cross-fade: one opacity per resident tile for this frame, written into
            // each tile draw's `Push.morph.x` below. A finer tile ramps 0→1 over
            // `LOD_FADE_SECONDS` from its `uploaded_at` stamp *while* a coarse ancestor is
            // resident to stand in under the gap (see `tile_lod_alpha`); a tile with nothing
            // beneath it stays fully opaque, so a freshly fetched area never fades up from the
            // background. Computed once here, not per layer.
            let now = camera.time_seconds;
            self.scratch_resident.clear();
            self.scratch_resident.extend(self.tiles.keys().copied());
            self.scratch_tile_alpha.clear();
            for &key in &self.scratch_ordered {
                let uploaded_at = self.tiles.get(&key).map(|t| t.uploaded_at).unwrap_or(0.0);
                self.scratch_tile_alpha.insert(
                    key,
                    select::tile_lod_alpha(
                        key,
                        uploaded_at,
                        now,
                        select::LOD_FADE_SECONDS,
                        &self.scratch_resident,
                    ),
                );
            }
            let Self {
                scratch_draws: draws,
                scratch_ordered: ordered,
                scratch_tile_alpha: tile_alpha,
                tiles: _,
                ..
            } = self;
            (draws as *mut _, ordered as *const _, tile_alpha as *const _)
        };
        // SAFETY: the three pointers name disjoint fields; `tiles` (shared through `self`
        // below) overlaps none of them. They are only used for the draw loop's duration.
        let (draws, ordered, tile_alpha): (
            &mut Vec<(
                u8,
                u32,
                u32,
                LayerKind,
                vk::Buffer,
                vk::DeviceSize,
                vk::Buffer,
                vk::DeviceSize,
                u32,
                u32,
                Option<u32>,
                (u8, u8, u8),
            )>,
            &Vec<u64>,
            &HashMap<u64, f32>,
        ) = unsafe { (&mut *draws, &*ordered, &*tile_alpha) };

        // The suppression map is current by now: `render` refreshed it
        // before the submit early-out, and a skipped frame reuses a map that
        // can only be stale if residency moved — which the residency epoch in
        // `FrameVersion` forbids. So every consult below is a plain read.
        // Symbol pre-pass: collision runs GLOBALLY across tiles and layers, but
        // draws stay per (tile, layer) below. Build one candidate per shaped
        // label with its screen box at this frame's text size, run the greedy
        // rank-ordered placer once, and hand the accept-set to `record_symbol`.
        // Without this every shaped label draws and z10 is an unreadable pile.
        let place_start = std::time::Instant::now();
        let accepted = self.place_symbols(camera, layers, &ordered, extent, filter);
        self.step_times
            .borrow_mut()
            .record(Step::PlaceSymbols, nanos_since(place_start));
        // Task-17 pick snapshot: the accepted labels with their screen boxes,
        // names, kinds and anchor geo — refreshed only when the accept-set is new, so a
        // reused placement (see PLACE_REUSE_MS) does not re-clone every name/kind String
        // per frame. Boxes still track the camera because the projection inputs are the
        // current frame's; only the *collision outcome* (which labels are in) lags.
        let mut accept_ids: Vec<u64> = accepted.keys().copied().collect();
        accept_ids.sort_unstable();
        if self.last_accept_ids.borrow().as_ref() != Some(&accept_ids) {
            self.refresh_placed(camera, layers, &accepted, extent);
            *self.last_accept_ids.borrow_mut() = Some(accept_ids);
        }

        // 3D terrain (WS-G): the DEM-displaced ground, drawn first (step 0) with depth on so it is
        // the ground the flat layers below sit over. A tile with no heightmap draws nothing here and
        // keeps its flat `earth` fill in the loop; at pitch 0 the grid collapses to the flat
        // footprint, so the overhead map is unchanged.
        // Fog toward the `earth` land colour, not the water-blue `clear`.
        let fog = fog_color(layers, palette, camera.zoom);
        let step_start = std::time::Instant::now();
        self.record_terrain(
            command_buffer,
            camera,
            layers,
            palette,
            fog,
            &mut submitted,
            &mut culled,
            &mut binds,
        );
        self.step_times
            .borrow_mut()
            .record(Step::RecordTerrain, nanos_since(step_start));

        // Symbol layers are collected here and drawn after the buildings pass rather than inside
        // the loop. Buildings have no depth interaction with symbols (the symbol pipelines are
        // `Depth::Off`, so they can never *fail* a test) — whichever is issued last simply paints
        // over the other, and issuing buildings last hid every tile-baked POI icon and label
        // behind them. `(key, layer index)`, replayed in the same order the loop met them.
        // Reused across frames on the renderer like the draw buffers above.
        let deferred_symbols = {
            self.scratch_deferred_symbols.clear();
            let Self {
                scratch_deferred_symbols: deferred,
                tiles: _,
                ..
            } = self;
            deferred as *mut _
        };
        // SAFETY: same disjoint-field split as the draw buffers above.
        let deferred_symbols: &mut Vec<(u64, usize)> = unsafe { &mut *deferred_symbols };

        let camera_z = camera.zoom.floor().clamp(0.0, 22.0) as u8;
        // The globe flag for this frame: globe matrices + shaders + depth path, or
        // the flat path bit-identically. Read once (not per tile) so a frame cannot
        // mix the two. Terrain is skipped on the globe (below): relief at globe
        // zoom is sub-pixel, and the globe shaders ignore draped height.
        let globe = crate::camera::globe_active(camera);
        let flat_start = std::time::Instant::now();
        for (index, layer) in layers.iter().enumerate() {
            // `min_zoom`/`max_zoom` are a data-and-cost gate, not paint: they say which zooms
            // the archive is worth asking for this layer at. Paint is the ramp below.
            if !layer.draws_at_focused(camera_z, layer.focused_by(filter)) {
                continue;
            }
            // Width and opacity come from the flat style, evaluated against the *camera's*
            // fractional zoom rather than the tile's, so a stroke grows and a fill fades
            // smoothly while zooming instead of jumping a step at every level. Both are push
            // constants, so this re-tessellates nothing, and neither varies per tile.
            // Symbols are the exception: label quads are sized per frame (see
            // tile::symbol), so the text size is evaluated here and the mesh lookup
            // below re-tessellates the tile's symbol layers every frame. Cheap —
            // dozens of quads — and placement stays frame-correct.
            let (stroke, opacity) = match layer.kind {
                // The ramp is what makes a fill visible, and it is the *only* thing: gating a
                // fill on an integer zoom drew `landcover` at full strength at z6 where the
                // ramp asks for half, and popped `landuse_park` on at full strength at z7
                // where it asks for a fifth.
                LayerKind::Fill => {
                    let opacity = layer.opacity_at(camera.zoom);
                    if opacity <= 0.0 {
                        continue;
                    }
                    (Stroke::NONE, opacity)
                }
                LayerKind::Line => {
                    let stroke = layer.stroke(camera.zoom);
                    // The ramps reach zero outside the zooms a layer is meant for, and that is
                    // the style's own gate: several road layers carry no `min_zoom` and rely on
                    // it.
                    if !stroke.visible() {
                        continue;
                    }
                    // A line's own opacity ramp (today only rail's 0.5): the authored style
                    // paints some lines translucent, and folding it into the colour column
                    // would bake a constant while the ramp stays per-frame like a fill's.
                    (stroke, layer.opacity_at(camera.zoom))
                }
                LayerKind::Symbol => {
                    if !layer.text_visible_at(camera.zoom) {
                        continue;
                    }
                    (Stroke::NONE, layer.opacity_at(camera.zoom))
                }
            };
            for key in ordered.iter() {
                // Symbol layers emit per frame at the frame's text size from the
                // tile's shaped candidates (see above): deferred to after the buildings
                // pass so labels are not painted over, then drawn with `&mut self` for
                // their transient uploads.
                if layer.kind == LayerKind::Symbol {
                    // Deeper tiles contribute no candidates (see `place_symbols`), so their
                    // labels are already suppressed by the accept set. Skipping the job here
                    // only avoids scanning their labels to emit nothing.
                    if self.tiles.get(key).is_some_and(|tile| tile.z > camera_z) {
                        continue;
                    }
                    deferred_symbols.push((*key, index));
                    continue;
                }
                // Covered-stand-in suppression: a settled finer set draws this
                // layer over the whole tile, so this tile's own draws of it are
                // the stacked-road copies. Counts into the same cull tally the
                // off-screen cull feeds, for the debug overlay.
                if self
                    .suppression
                    .get(key)
                    .is_some_and(|suppressed| suppressed.layers.contains(&index))
                {
                    if let Some(tile) = self.tiles.get(key) {
                        culled += tile
                            .layers
                            .iter()
                            .filter(|mesh| mesh.layer_index == index)
                            .count();
                    }
                    continue;
                }
                // Off-screen cull (MapLibre's bucket cull equivalent): a resident tile the
                // viewport cannot see issues no flat draws — see `culled_flat_draws`. Symbols
                // are deliberately NOT culled: placement dedup may have crowned this tile's
                // copy of a border label, so the deferred symbol job above still runs.
                if let Some(tile) = self.tiles.get(key) {
                    let skipped = culled_flat_draws(tile, index, camera);
                    if skipped > 0 {
                        culled += skipped;
                        continue;
                    }
                }
                // Copy the draw's inputs out, then issue them through the owned `device`
                // clone: `record_symbol` above takes `&mut self`, so this path must not
                // hold a `self.tiles` borrow either.
                draws.clear();
                if let Some(tile) = self.tiles.get(key) {
                    let pool = match layer.kind {
                        LayerKind::Fill => tile
                            .flat
                            .as_ref()
                            .map(|(v, vo, i, io)| (*v, *vo, *i, *io)),
                        LayerKind::Line => tile
                            .lines
                            .as_ref()
                            .map(|(v, vo, i, io)| (*v, *vo, *i, *io)),
                        LayerKind::Symbol => None,
                    };
                    let Some((pool_vbuf, pool_voff, pool_ibuf, pool_ioff)) = pool else {
                        // No pool means no mesh of this format packed — nothing to draw.
                        // (Symbols never take this path; see the deferred arm above.)
                        if layer.kind == LayerKind::Symbol {
                            continue;
                        }
                        continue;
                    };
                    for mesh in tile.layers.iter().filter(|l| l.layer_index == index) {
                        draws.push((
                            tile.z,
                            tile.x,
                            tile.y,
                            mesh.kind,
                            pool_vbuf,
                            pool_voff,
                            pool_ibuf,
                            pool_ioff,
                            mesh.first_index,
                            mesh.index_count,
                            mesh.color_override,
                            mesh.lane,
                        ));
                    }
                }
                // This tile's LOD cross-fade opacity for the frame (WS-D), applied through
                // `Push.morph.x`. The fill fragment multiplies output alpha by it; the line path
                // (owned by WS-B's `line.frag`) ignores `morph.x`, so road casings stay crisp
                // while the fill fades — the fade reads as the flat basemap ramping in.
                let tile_fade = tile_alpha.get(key).copied().unwrap_or(1.0);
                for &(tz, tx, ty, kind, vbuf, voff, ibuf, ioff, first_index, count, color_override, lane)
                    in draws.iter()
                {
                    // One bind per pipeline no matter how the kinds interleave across layers
                    // (`BindState` dedups for the frame).
                    if bound != Some(kind) {
                        let Some(pipeline) = flat_pipeline(self, kind, globe) else {
                            // Symbols never take this path (drawn deferred); the match stays
                            // exhaustive without panicking.
                            continue;
                        };
                        binds.bind_pipeline(&device, command_buffer, pipeline);
                        bound = Some(kind);
                    }

                    let (half_width_px, half_gap_px) = stroke.half_px(camera.density);
                    // `misc.y` tells the fragment shader whether it has to antialias the edge
                    // itself. With MSAA the rasteriser already resolves partial coverage, and
                    // applying a second coverage term on top fades a diagonal road twice — at
                    // z6 a 2.9px highway crossing the screen at an angle lost all but one
                    // pixel of its strength, while the near-vertical stretches of the same
                    // road kept three.
                    let edge_aa = f32::from(self.swapchain.samples == vk::SampleCountFlags::TYPE_1);
                    // A mesh that carries its own colour keeps it **unshifted**: transit
                    // colours are operator brand colours, and the reference draws them
                    // literally. Every other layer goes through `color(palette)`, which
                    // swaps in the dark column and blends toward the background when muted;
                    // doing that to a route colour would turn a network's own red into
                    // whatever the dark basemap thinks red should be. The opacity ramp still
                    // applies, because that is per-frame paint rather than palette.
                    let base = color_override.unwrap_or_else(|| layer.color(palette));
                    // Distance fog: haze the draw toward the land colour with its tile's
                    // distance, so tilted far tiles dissolve instead of ending at a hard
                    // ground edge. Zero at pitch 0 — the flat map is unchanged.
                    let base = apply_fog(base, fog, fog_factor(camera, tz, tx, ty));
                    // How far this mesh's whole band shifts sideways, in device pixels. The
                    // feature carries its colour's ordinal and its corridor's colour count,
                    // not an offset: how many lanes the corridor actually draws is a step
                    // function of the camera zoom, so the lane is chosen here rather than
                    // baked into the mesh. Zero for every layer but transit, where the count
                    // is absent and reads one.
                    let (ordinal, count_of_colours, taper) = lane;
                    let lateral_px = layer.lane_offset_px(
                        camera.zoom,
                        camera.density,
                        ordinal,
                        count_of_colours,
                        taper,
                    );
                    let push = Push {
                        tile_to_clip: camera.tile_to_clip(tz, tx, ty),
                        color: argb_to_rgba(scale_alpha(base, opacity)),
                        line: [half_width_px, half_gap_px, layer.dash.0, layer.dash.1],
                        // Globe and flat share this block: the globe matrix carries
                        // the centre (translation column) and the radius (2x2), so
                        // `misc`/`morph` keep their flat meaning — tile px, edge AA,
                        // lateral shift, clock, fade, span. The shaders bend through
                        // the matrix and discard past the limb.
                        misc: [
                            camera.tile_span_px(tz),
                            edge_aa,
                            lateral_px,
                            camera.time_seconds,
                        ],
                        // `morph.z` is the tile's world-px span (Dp): the draped-`z` scale.
                        // On the globe it is the globe radius (Dp) instead, and morph.xy
                        // the half-viewport (Dp) — see the `*_globe.vert` headers.
                        morph: if globe {
                            let r = crate::camera::globe_radius(camera.zoom, camera.center_lat) as f32;
                            [
                                tile_fade,
                                camera.width_dp / 2.0,
                                r,
                                camera.height_dp / 2.0,
                            ]
                        } else {
                            [tile_fade, 0.0, camera.tile_span_dp(tz) as f32, 0.0]
                        },
                    };
                    let layout = self.pipelines.layout;
                    device.cmd_push_constants(
                        command_buffer,
                        layout,
                        vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                        0,
                        push.as_bytes(),
                    );
                    device.cmd_bind_vertex_buffers(command_buffer, 0, &[vbuf], &[voff]);
                    // Uint32 rather than Uint16: a dense z14 tile can exceed 65535 vertices in
                    // one layer, and overflowing folds geometry back on itself rather than
                    // failing loudly. Indices are rebased to absolute at upload, so the slice
                    // draws from its firstIndex inside the shared pool.
                    device.cmd_bind_index_buffer(command_buffer, ibuf, ioff, vk::IndexType::UINT32);
                    device.cmd_draw_indexed(command_buffer, count, 1, first_index, 0, 0);
                    submitted += 1;
                }
            }
        }

        self.record_tail(
            command_buffer,
            camera,
            layers,
            palette,
            ordered,
            fog,
            deferred_symbols,
            &accepted,
            &mut bound,
            &mut submitted,
            &mut culled,
            &mut binds,
            &device,
            flat_start,
            globe,
        )
    }
}
