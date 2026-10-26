use super::{
    anchors_for, argb_to_rgba, scale_alpha, Overlay, Renderer, RouteBuffers, UserPuck, ARROW_COLOR,
    ARROW_DP, BUILDINGS_DRAW_MIN_ZOOM, IDENTITY, PUCK_COLOR, PUCK_CONE_DP,
    PUCK_CONE_HALF_STROKE_DP, PUCK_DOT_DP, PUCK_QUAD_DP, PUCK_RIM_DP, QUAD_INDICES, SCRIM_COLOR,
    TRAFFIC_WIDTH_DP, VEHICLE_RING_DP, VEHICLE_RING_QUAD_DP,
};
use super::fog::tile_offscreen;
use super::bind_state::BindState;
use crate::camera::Camera;
use crate::marker::{Marker, MARKER_SIZE_DP};
use crate::style::paint::Stroke;
use crate::style::{Layer, LayerKind, Palette};
use crate::tile::select;
use crate::vulkan::pipeline::{Push, MORPH_NONE, NO_MARKINGS};
use ash::vk;
use std::collections::{HashMap, HashSet};
use tilecodec::mamaps::dict::LAYER_JUNCTION;

impl Renderer {
    /// Draw the live-traffic overlay: each resident component segment, coloured from the
    /// pushed table.
    ///
    /// The region-mask precedent, applied to lines: the geometry comes from the archive and is
    /// resident, and the only per-frame input is a lightweight dynamic table — here
    /// `component_id → ARGB` rather than one selected region id. A segment whose id is not in
    /// the table draws **nothing**: pushing only the segments the server has a reading for keeps
    /// the overlay to the roads traffic actually covers, rather than laying a neutral tint over
    /// the whole network (which would merely repaint roads the basemap already drew). The
    /// no-data look is therefore "unchanged basemap road", which is the cleaner of the two.
    ///
    /// Allocation-free and re-tessellation-free: colour is a push constant read from the map,
    /// the width is one constant evaluated per frame, and the buffers were uploaded with the
    /// tile. A new speed reading only replaces [`traffic_colors`](Self::traffic_colors), so this
    /// path picks it up on the next frame with no geometry work at all.
    pub(super) unsafe fn record_traffic(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        submitted: &mut usize,
        culled: &mut usize,
        binds: &mut BindState,
        // This frame's globe flag: globe line pipeline + sphere push below, or
        // the flat path bit-identically above.
        globe: bool,
    ) {
        // Gated on the toggle and empty until the host pushes a reading, so the common case —
        // traffic off, or no data yet — is one comparison and out.
        if !self.traffic_enabled || self.traffic_colors.is_empty() {
            return;
        }
        let device = &self.context.device;
        let half_width_px = TRAFFIC_WIDTH_DP * camera.density / 2.0;
        let edge_aa = f32::from(self.swapchain.samples == vk::SampleCountFlags::TYPE_1);

        for tile in self.tiles.values() {
            if tile.traffic.is_empty() {
                continue;
            }
            // Covered-stand-in suppression, like the terrain pass: the overlay
            // follows its road, so where the road is covered the overlay is
            // redundant too.
            if self
                .suppressed_tile(tile.z, tile.x, tile.y)
                .is_some_and(|suppressed| suppressed.traffic)
            {
                *culled += self.traffic_draws(tile);
                continue;
            }
            // Same off-screen cull as the flat layer loop (see `tile_offscreen`).
            if tile_offscreen(camera, tile.z, tile.x, tile.y) {
                *culled += self.traffic_draws(tile);
                continue;
            }
            // The line pool, bound at its offsets; each segment draws its slice by
            // firstIndex. Fresh pools bind at 0, arena ranges at their committed offsets.
            // Handles are raw `vk::Buffer`s now (see `ResidentTile`): copy by value.
            let Some((pool_v, pool_vo, pool_i, pool_io)) = tile
                .lines
                .as_ref()
                .map(|(v, vo, i, io)| (*v, *vo, *i, *io))
            else {
                continue;
            };
            let tile_to_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
            let tile_span_px = camera.tile_span_px(tile.z);
            for segment in &tile.traffic {
                // The dynamic half of the pass: a segment the host has no reading for is not
                // drawn, so the table's size — not the archive's — bounds the draw calls.
                let Some(&argb) = self.traffic_colors.get(&segment.id) else {
                    continue;
                };
                // One bind no matter how many segments draw: traffic shares the line
                // pipeline with the layer loop, so this usually binds nothing at all.
                binds.bind_pipeline(
                    device,
                    command_buffer,
                    if globe {
                        self.pipelines.line_globe
                    } else {
                        self.pipelines.line
                    },
                );
                // Globe and flat share this block (see the layer loop): the
                // globe matrix carries the centre and radius; `misc`/`morph`
                // keep their flat meaning (tile px, edge AA, clock; fade, span).
                let (misc, morph) = (
                    [tile_span_px, edge_aa, 0.0, camera.time_seconds],
                    if globe {
                        let r = crate::camera::globe_radius(camera.zoom, camera.center_lat) as f32;
                        [1.0, camera.width_dp / 2.0, r, camera.height_dp / 2.0]
                    } else {
                        [1.0, 0.0, camera.tile_span_dp(tile.z) as f32, 0.0]
                    },
                );
                let push = Push {
                    tile_to_clip,
                    color: argb_to_rgba(argb),
                    // A solid band. `line.frag` short-circuits on a zero dash gap before it
                    // touches the clock, so this reads no animation at all.
                    line: [half_width_px, 0.0, 0.0, 0.0],
                    misc,
                    // Draped traffic segments scale by the same span as the flat loop.
                    morph,
                };
                device.cmd_push_constants(
                    command_buffer,
                    self.pipelines.layout,
                    vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    0,
                    push.as_bytes(),
                );
                device.cmd_bind_vertex_buffers(command_buffer, 0, &[pool_v], &[pool_vo]);
                device.cmd_bind_index_buffer(command_buffer, pool_i, pool_io, vk::IndexType::UINT32);
                device.cmd_draw_indexed(
                    command_buffer,
                    segment.index_count,
                    1,
                    segment.first_index,
                    0,
                    0,
                );
                *submitted += 1;
            }
        }
    }

    /// Rasterise the selected region into the stencil, then dim everything it did not cover.
    ///
    /// Two passes over one attachment rather than any geometric cut: the region arrives as one
    /// clipped polygon per tile, and rasterising every piece with `REPLACE` unions them in the
    /// stencil for free. Overlapping pieces and the self-touching rings the tile clipper produces
    /// are both harmless to a rasteriser, which is exactly what defeated the two attempts to do
    /// this with a polygon boolean.
    pub(super) unsafe fn record_region_mask(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        submitted: &mut usize,
        culled: &mut usize,
        binds: &mut BindState,
    ) {
        let Some(selected) = self.selected_region else {
            return;
        };
        let device = &self.context.device;

        // Whether any piece drew: the scrim below still draws when a visible piece remains.
        // A local rather than `BindState` state — the scrim decision is draw order, not
        // pipeline state.
        let mut any = false;
        for tile in self.tiles.values() {
            // Off-screen region pieces rasterise nothing on-screen, so they add no stencil
            // (see `tile_offscreen`). The scrim below still draws when any visible piece
            // remains — `any` is only set by drawn pieces.
            if tile_offscreen(camera, tile.z, tile.x, tile.y) {
                *culled += tile.regions.iter().filter(|r| r.id == selected).count();
                continue;
            }
            // The flat pool, bound at its offsets; each region piece draws its slice by
            // firstIndex. Fresh pools bind at 0, arena ranges at their committed offsets.
            // Handles are raw `vk::Buffer`s now (see `ResidentTile`): copy by value.
            let Some((pool_v, pool_vo, pool_i, pool_io)) = tile
                .flat
                .as_ref()
                .map(|(v, vo, i, io)| (*v, *vo, *i, *io))
            else {
                continue;
            };
            for region in tile.regions.iter().filter(|r| r.id == selected) {
                // The stencil union draws in tile order, but the pipeline binds once:
                // every piece shares the mask pipeline.
                binds.bind_pipeline(device, command_buffer, self.pipelines.mask);
                any = true;
                let push = Push {
                    tile_to_clip: camera.tile_to_clip(tile.z, tile.x, tile.y),
                    color: [0.0; 4],
                    line: [0.0; 4],
                    misc: [0.0; 4],
                    morph: MORPH_NONE,
                };
                device.cmd_push_constants(
                    command_buffer,
                    self.pipelines.layout,
                    vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    0,
                    push.as_bytes(),
                );
                device.cmd_bind_vertex_buffers(command_buffer, 0, &[pool_v], &[pool_vo]);
                device.cmd_bind_index_buffer(command_buffer, pool_i, pool_io, vk::IndexType::UINT32);
                device.cmd_draw_indexed(
                    command_buffer,
                    region.index_count,
                    1,
                    region.first_index,
                    0,
                    0,
                );
                *submitted += 1;
            }
        }
        // No piece of the region is resident - the map has been panned away from it, or its
        // tiles have not landed yet. Dimming the whole screen would be worse than dimming none
        // of it, so the scrim is skipped rather than drawn over everything.
        if !any {
            return;
        }

        let push = Push {
            // The quad is already in clip space, so the vertex shader must not move it.
            tile_to_clip: IDENTITY,
            color: argb_to_rgba(SCRIM_COLOR),
            line: [0.0; 4],
            misc: [0.0; 4],
            morph: MORPH_NONE,
        };
        // The scrim follows the stencil pieces in draw order, but its pipeline binds
        // through the shared state so a repeated mask→scrim sequence costs one bind.
        binds.bind_pipeline(device, command_buffer, self.pipelines.scrim);
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[self.quad.vertices.buffer], &[0]);
        device.cmd_bind_index_buffer(
            command_buffer,
            self.quad.indices.buffer,
            0,
            vk::IndexType::UINT32,
        );
        device.cmd_draw_indexed(command_buffer, QUAD_INDICES.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
    }

    /// Draw the navigation route, above the basemap and below the puck.
    ///
    /// The casing is one draw of the whole index buffer at the wider half-width in the
    /// casing colour; the fills are then one draw per coloured run, each of its own index
    /// slice in its own colour, over the casing. `stroke` bakes no width into a vertex, so
    /// the same geometry drawn wider underneath *is* the outline — no second mesh — and one
    /// casing over the lot keeps the outline continuous across the colour changes.
    ///
    /// Allocation-free, like every other per-frame path here: the buffers were uploaded
    /// when the route was set, and everything that varies per frame is a matrix and the
    /// push blocks built on the stack. The matrix is the overlay sibling of `tile_to_clip`,
    /// so the route picks up the camera's bearing exactly as the tiles under it do.
    pub(super) unsafe fn record_route(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        submitted: &mut usize,
        binds: &mut BindState,
    ) {
        if let Some(route) = &self.route {
            Self::record_route_buffers(self, command_buffer, camera, route, submitted, binds);
        }
    }

    /// Draw the pack-driven rail-lines network under the navigation route.
    ///
    /// Same mesh, pipeline and casing/fill structure as [`record_route`](Self::record_route);
    /// the network draws first so a selected route reads over it.
    pub(super) unsafe fn record_rail_lines(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        submitted: &mut usize,
        binds: &mut BindState,
    ) {
        if let Some(rails) = &self.rail_lines {
            Self::record_route_buffers(self, command_buffer, camera, rails, submitted, binds);
        }
    }

    unsafe fn record_route_buffers(
        renderer: &Renderer,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        route: &RouteBuffers,
        submitted: &mut usize,
        binds: &mut BindState,
    ) {
        let device = &renderer.context.device;
        let (origin, span) = route.placement.at_zoom_near(camera);
        let matrix = camera.world_quad_to_clip(origin, span);
        // What `line.vert` divides a pixel offset by to reach local units. The route's
        // square stands in for a tile here, which is the whole reason the two share a
        // vertex format.
        let span_px = (span * camera.density as f64) as f32;
        let edge_aa = f32::from(renderer.swapchain.samples == vk::SampleCountFlags::TYPE_1);

        // Rail network and route share the line pipeline: the second draws with no
        // rebind when both are present.
        binds.bind_pipeline(device, command_buffer, renderer.pipelines.line);
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[route.vertices.buffer], &[0]);
        device.cmd_bind_index_buffer(
            command_buffer,
            route.indices.buffer,
            0,
            vk::IndexType::UINT32,
        );

        // Casing first (the whole route at the wider width, one colour), then each run's
        // fill over it (its own index slice, its own colour). A first entry with the whole
        // buffer stands in for the casing when there is one. Both are solid: the fills used to
        // carry a scrolling marching-ants, which was removed as unwanted motion. It carried no
        // meaning — `RouteOverlay` has no per-segment dashed flag, so walking, transit and
        // driving legs were all dashed alike and the mode is conveyed by colour.
        let casing = route.placement.casing_half(camera.density).map(|half| {
            (
                route.placement.style.casing_color,
                half,
                route.index_count,
                0u32,
            )
        });
        let fill_half = route.placement.fill_half(camera.density);
        let fills = route
            .segments
            .iter()
            .map(|s| (s.color, fill_half, s.index_count, s.index_offset));
        for (color, half_width_px, index_count, first_index) in casing.into_iter().chain(fills) {
            let push = Push {
                tile_to_clip: matrix,
                color: argb_to_rgba(color),
                line: [half_width_px, 0.0, 0.0, 0.0],
                misc: [span_px, edge_aa, 0.0, camera.time_seconds],
                morph: [1.0, 0.0, 0.0, 0.0],
            };
            device.cmd_push_constants(
                command_buffer,
                renderer.pipelines.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_draw_indexed(command_buffer, index_count, 1, first_index, 0, 0);
            *submitted += 1;
        }
    }

    /// Draw this frame's overlays on top of every tile.
    ///
    /// Called with the render pass still open: the viewport and scissor are already set
    /// and blending is the same straight src-alpha-over the tile layers use, so an
    /// overlay only has to bind its pipeline and push its own state.
    ///
    /// Draw order is fixed here rather than by vec position: markers (and WS-F's vehicles) first,
    /// the puck last, so the user's location stays on top of the pins around it. `&mut self`
    /// because the marker path uploads a transient buffer through
    /// [`draw_symbol_batch`](Self::draw_symbol_batch), same as the symbol layers.
    pub(super) unsafe fn record_overlays(
        &mut self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        palette: Palette,
        submitted: &mut usize,
        binds: &mut BindState,
        // This frame's globe flag: far-side markers/puck culled, positions via
        // the globe anchor below — or the flat path bit-identically above.
        globe: bool,
    ) {
        // Copy the overlay state out so no borrow of `self.overlays` lives across the `&mut self`
        // marker draw below (which uploads a transient buffer). Labels ride parallel to the
        // markers (same index); vehicles carry none, so their label slice stays empty.
        let mut markers: Vec<Marker> = Vec::new();
        let mut marker_labels: Vec<String> = Vec::new();
        let mut vehicles: Vec<Marker> = Vec::new();
        let mut puck: Option<UserPuck> = None;
        for overlay in &self.overlays {
            match overlay {
                Overlay::Puck(p) => puck = Some(*p),
                Overlay::Markers(m, labels) => {
                    markers.extend_from_slice(m);
                    marker_labels.extend(labels.iter().cloned());
                }
                Overlay::Vehicles(v) => vehicles.extend_from_slice(v),
            }
        }

        // Vehicles first (lowest), then the app pins over them, then the puck on top: a pin the
        // user placed and can tap outranks a simulated vehicle sprite at the same spot, and the
        // user's own location outranks both. Both go through the shared billboarded sprite path.
        if !vehicles.is_empty() {
            // Route-colour rings under the sprites that carry a colour, so a
            // vehicle reads in its line's colour: a dot-only pass on the puck
            // pipeline (no cone, no white rim), sized just past the 28 Dp
            // sprite, drawn before the sprites so they cover its middle.
            self.draw_vehicle_rings(command_buffer, camera, &vehicles, submitted, binds, globe);
            self.draw_markers(
                command_buffer,
                camera,
                palette,
                &vehicles,
                &[],
                submitted,
                binds,
                globe,
            );
        }
        if !markers.is_empty() {
            self.draw_markers(
                command_buffer,
                camera,
                palette,
                &markers,
                &marker_labels,
                submitted,
                binds,
                globe,
            );
        }

        // The puck last, so it sits on top of any pin at the same spot. The shared unit quad, an
        // analytic shader, and one push constant — nothing allocated. On the globe the
        // matrix comes from the globe anchor instead of the billboard path; far-side
        // is culled above.
        let Some(puck) = puck else { return };
        if globe {
            let (_, _, z) =
                crate::camera::globe_point(camera.center_lon, camera.center_lat, puck.lon, puck.lat);
            if z < 0.0 {
                return;
            }
        }
        let device = &self.context.device;
        let density = camera.density;
        let puck_matrix = if globe {
            // Re-derived defensively: a far-side race with the cull above skips, not panics.
            match camera.globe_anchor_to_screen(puck.lon, puck.lat) {
                Some((sx, sy)) => super::placement::globe_screen_quad(camera, sx, sy, PUCK_QUAD_DP as f64),
                None => return,
            }
        } else {
            camera.screen_quad_to_clip(puck.lon, puck.lat, PUCK_QUAD_DP as f64)
        };
        // Behind the eye under tilt (w <= 0): the quad would invert across the screen.
        if puck_matrix[15] <= 0.0 {
            return;
        }
        let push = Push {
            tile_to_clip: puck_matrix,
            color: argb_to_rgba(PUCK_COLOR),
            line: [
                PUCK_RIM_DP * density,
                PUCK_DOT_DP * density,
                PUCK_CONE_DP * density,
                PUCK_CONE_HALF_STROKE_DP * density,
            ],
            misc: [
                puck.bearing.unwrap_or(0.0).to_radians(),
                f32::from(puck.bearing.is_some()),
                PUCK_QUAD_DP * density,
                camera.time_seconds,
            ],
            morph: MORPH_NONE,
        };
        // The puck binds through the shared state: with no vehicles it binds once, with
        // rings before it the puck pipeline is usually already bound.
        binds.bind_pipeline(device, command_buffer, self.pipelines.puck);
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[self.quad.vertices.buffer], &[0]);
        device.cmd_bind_index_buffer(
            command_buffer,
            self.quad.indices.buffer,
            0,
            vk::IndexType::UINT32,
        );
        device.cmd_draw_indexed(command_buffer, QUAD_INDICES.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
    }
}
