use super::fog::{apply_fog, fog_factor, tile_offscreen};
use super::bind_state::BindState;
use super::{
    anchors_for, argb_to_rgba, scale_alpha, Overlay, Renderer, UserPuck,
    BUILDINGS_DRAW_MIN_ZOOM, IDENTITY, PUCK_COLOR, PUCK_CONE_DP, PUCK_CONE_HALF_STROKE_DP,
    PUCK_DOT_DP, PUCK_QUAD_DP, PUCK_RIM_DP, QUAD_INDICES, SCRIM_COLOR, TRAFFIC_WIDTH_DP,
};
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
    /// Draw the road carriageways: each resident tile's road surfaces, with the lane markings
    /// painted on by `road_surface.frag` as a function of the across-road coordinate rather than
    /// drawn as geometry.
    ///
    /// The lane connectors through junctions draw here too, and are why this loop needs no
    /// connector-specific branch: a connector is the same asphalt on the same pipeline, differing
    /// only in the lane count and one-way flag it pushes, both of which already ride on the mesh.
    /// It belongs in this pass rather than beside it because it is carriageway — it has to sit
    /// under the buildings and the deferred symbols exactly as the roads it joins do, and a second
    /// pass could only get that right by accident.
    ///
    /// Its own pass rather than a branch of the layer loop because the ribbon is a different vertex
    /// format on a different pipeline, and because three of its push slots mean something else —
    /// the `Push` doc comment in [`crate::vulkan::pipeline`] is the contract this fills in.
    ///
    /// Coarsest tile first, in `ordered`, for the same reason the layer loop is: a stale ancestor
    /// standing in for a tile that has not arrived must be drawn *under* its descendants, or its
    /// asphalt lands on top of the sharp child as it loads.
    pub(super) unsafe fn record_carriageways(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        ordered: &[u64],
        fog: u32,
        submitted: &mut usize,
        culled: &mut usize,
        binds: &mut BindState,
        // This frame's globe flag: globe ribbon pipeline + sphere push below, or
        // the flat path bit-identically above.
        globe: bool,
    ) {
        let device = &self.context.device;
        let floor = camera.zoom.floor().clamp(0.0, 22.0) as u8;
        let edge_aa = f32::from(self.swapchain.samples == vk::SampleCountFlags::TYPE_1);
        for key in ordered {
            let Some(tile) = self.tiles.get(key) else {
                continue;
            };
            if tile.carriageways.is_empty() {
                continue;
            }
            // Same off-screen cull as the flat layer loop (see `tile_offscreen`):
            // carriageways are per-tile ground cover, not symbols.
            if tile_offscreen(camera, tile.z, tile.x, tile.y) {
                *culled += tile.carriageways.len();
                continue;
            }
            let suppressed_carriageways = self
                .suppression
                .get(key)
                .is_some_and(|suppressed| suppressed.carriageways);
            if suppressed_carriageways {
                // The whole tile's road surface is covered by settled finer
                // carriageways: skipping this tile's is what removes the
                // stacked copies. Counted like any other cull.
                *culled += tile.carriageways.len();
                continue;
            }
            // The ribbon pool, bound at its offsets; each road draws its slice by
            // firstIndex. Fresh pools bind at 0, arena ranges at their committed offsets.
            // Handles are raw `vk::Buffer`s now (see `ResidentTile`): copy by value.
            let Some((pool_v, pool_vo, pool_i, pool_io)) = tile
                .ribbons
                .as_ref()
                .map(|(v, vo, i, io)| (*v, *vo, *i, *io))
            else {
                continue;
            };
            let tile_to_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
            let tile_span_px = camera.tile_span_px(tile.z);
            let yellow = f32::from(tile.yellow_centre);
            for road in &tile.carriageways {
                let Some(layer) = layers.get(road.layer_index) else {
                    continue;
                };
                if !layer.draws_at(floor) {
                    continue;
                }
                // The style ramp is one *lane's* width, so this road's own width is that times
                // the lanes it carries — one number cannot describe both a two-lane street and an
                // eight-lane motorway. Halved because the vertex shader offsets each kerb from
                // the centreline, exactly as `Stroke::half_px` does for a stroke.
                let half_width_px =
                    layer.width.at(camera.zoom) * camera.density * road.lanes as f32 / 2.0;
                if half_width_px <= 0.0 {
                    continue;
                }
                // One bind for the whole pass no matter how many tiles carry roads:
                // every road draws through the same ribbon pipeline.
                binds.bind_pipeline(
                    device,
                    command_buffer,
                    if globe {
                        self.pipelines.ribbon_globe
                    } else {
                        self.pipelines.ribbon
                    },
                );
                // The asphalt only. The markings are the shader's own palette, because they have
                // to match the white it antialiases them against. Distance-fogged like the
                // flat layers so carriageways haze with the ground under tilt.
                let asphalt = apply_fog(
                    scale_alpha(layer.color(palette), layer.opacity_at(camera.zoom)),
                    fog,
                    fog_factor(camera, tile.z, tile.x, tile.y),
                );
                // A lane connector carries no paint. An intersection is not marked out into
                // lanes on the ground, and a connector is drawn in the carriageway's own colour,
                // so its markings would be the only part of it with any contrast against the road
                // beneath — twelve hairline pairs per junction rather than a widening of the
                // asphalt. `NO_MARKINGS` rides in the centre-line slot, which a one-way leaves
                // dead; see the `Push` doc comment in [`crate::vulkan::pipeline`].
                let centre_t = if layer.source_layer_id == LAYER_JUNCTION {
                    NO_MARKINGS
                } else {
                    road.split
                };
                let push = Push {
                    tile_to_clip,
                    color: argb_to_rgba(asphalt),
                    line: [
                        half_width_px,
                        road.lanes as f32,
                        centre_t,
                        f32::from(road.oneway),
                    ],
                    // Globe and flat share this block (see the layer loop): the
                    // globe matrix carries the centre and radius; `misc` keeps its
                    // flat meaning (tile px, edge AA, yellow flag, clock).
                    misc: [tile_span_px, edge_aa, yellow, camera.time_seconds],
                    // The markings are static, so unlike the traffic draw there is no phase to
                    // animate and nothing to fade: `MORPH_NONE` is what the ribbon contract asks
                    // for. `morph.z` is the tile's world-px span (Dp) — the draped-`z` scale,
                    // mirroring the flat layer loop. On the globe it is the globe radius
                    // (Dp) instead, and morph.xy the half-viewport (Dp).
                    morph: if globe {
                        let r = crate::camera::globe_radius(camera.zoom, camera.center_lat) as f32;
                        [MORPH_NONE[0], camera.width_dp / 2.0, r, camera.height_dp / 2.0]
                    } else {
                        [MORPH_NONE[0], 0.0, camera.tile_span_dp(tile.z) as f32, 0.0]
                    },
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
                    road.index_count,
                    1,
                    road.first_index,
                    0,
                    0,
                );
                *submitted += 1;
            }
        }
    }

    /// Draw the DEM-displaced ground grid: each resident tile's terrain mesh, depth-tested so hills
    /// occlude what is behind them and let buildings on the far side of a ridge be hidden by it.
    /// Drawn before the flat layer loop, so the flat 2D layers paint over it (see the drape-vs-offset
    /// note on [`record_inner`](Self::record_inner)). A tile with no heightmap has no terrain mesh
    /// and draws its flat `earth` fill in the loop instead; at pitch 0 the terrain vertex shader
    /// collapses the grid to the flat footprint, so the overhead map is unchanged.
    ///
    /// The ground colour is the `earth` style layer's, resolved for this palette and its fill
    /// opacity for this zoom — the same colour the flat earth fill would have used — pushed once and
    /// shared by every tile. `line.x` carries the tile's world-px span, the scale that turns the
    /// mesh's tile-normalised heights into the world-px height the WS0 matrix's `z` input expects.
    /// Distance-fogged per tile toward the earth land colour like the flat layers (buildings keep their
    /// per-vertex colours and are deliberately not fogged — no shader change for a z14+-only
    /// layer whose tiles sit inside the near ramp anyway).
    ///
    /// Since v8 the `earth` fill arm reads source `landtype` (matched by style id, because the
    /// source alone no longer names it).
    pub(super) unsafe fn record_terrain(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        fog: u32,
        submitted: &mut usize,
        culled: &mut usize,
        binds: &mut BindState,
    ) {
        // Skipped on the globe: the flat terrain mesh drapes through the flat
        // matrix, which the globe branch never takes — drawing it would paste
        // flat relief over the sphere. Relief at globe zoom is sub-pixel
        // anyway, and the globe shaders ignore draped height by construction.
        if crate::camera::globe_active(camera) {
            return;
        }
        let Some(earth) = layers
            .iter()
            .find(|l| l.id == "earth")
        else {
            return;
        };
        let opacity = earth.opacity_at(camera.zoom);
        if opacity <= 0.0 {
            return;
        }
        let unfogged = argb_to_rgba(scale_alpha(earth.color(palette), opacity));
        let fog_rgb = argb_to_rgba(fog);
        let device = &self.context.device;
        for tile in self.tiles.values() {
            let Some(terrain) = &tile.terrain else {
                continue;
            };
            // Covered-stand-in suppression: settled finer terrain covers this
            // tile's ground, so drawing it too only doubles the relief. Kept
            // where the finer tiles have no terrain of their own — those tiles
            // never suppress this one (see the per-group content predicate).
            if self
                .suppressed_tile(tile.z, tile.x, tile.y)
                .is_some_and(|suppressed| suppressed.terrain)
            {
                *culled += 1;
                continue;
            }
            // Same off-screen cull as the flat layer loop (see `tile_offscreen`).
            if tile_offscreen(camera, tile.z, tile.x, tile.y) {
                *culled += 1;
                continue;
            }
            // One bind for the whole pass: every tile shares the terrain pipeline.
            binds.bind_pipeline(device, command_buffer, self.pipelines.terrain);
            // Same fog as the flat layers, mixed one step later (linear floats rather than
            // packed ARGB — same ramp, rounding apart). Per tile: far ground hazes out.
            let f = fog_factor(camera, tile.z, tile.x, tile.y);
            let colour = [
                unfogged[0] + (fog_rgb[0] - unfogged[0]) * f,
                unfogged[1] + (fog_rgb[1] - unfogged[1]) * f,
                unfogged[2] + (fog_rgb[2] - unfogged[2]) * f,
                unfogged[3] + (fog_rgb[3] - unfogged[3]) * f,
            ];
            // Tile-local eye for the terrain specular, derived the way `fog_factor` derives
            // its tile geometry: camera centre in world px (`project`, at the camera zoom)
            // minus the tile origin, over the span (`tile_span_dp`). Tile u/v are world axes
            // normalised, so no bearing rotation enters — the matrix already owns that.
            let span = camera.tile_span_dp(tile.z);
            let (eye_u, eye_v, eye_h) = if span > 0.0 {
                let centre =
                    crate::camera::project(camera.center_lon, camera.center_lat, camera.zoom);
                (
                    ((centre.x - f64::from(tile.x) * span) / span) as f32,
                    ((centre.y - f64::from(tile.y) * span) / span) as f32,
                    ((1.5 * f64::from(camera.height_dp)) / span) as f32,
                )
            } else {
                (0.5, 0.5, 1.0e4)
            };
            let push = Push {
                tile_to_clip: camera.tile_to_clip(tile.z, tile.x, tile.y),
                color: colour,
                // `line.x`: the tile's world-px span, the tile-norm-height -> world-px scale.
                line: [camera.tile_span_dp(tile.z) as f32, 0.0, 0.0, 0.0],
                // `misc.xyz`: the tile-local eye for the Blinn-Phong specular — the camera
                // centre in world px minus the tile origin, over the span, so u/v land in
                // 0..1 (off-tile when the centre is elsewhere, which is fine: the view
                // vector stays well-defined). Eye height is the MapLibre-like eye distance
                // (1.5 viewport-heights, the `d` behind `Camera::perspective`) in the same
                // tile-norm units as the mesh heights, so `eye - frag` is a true direction
                // in tile space. At pitch 0 the fragment shader skips the specular outright,
                // so this only ever steers tilted highlights. A degenerate span falls back
                // to far-above-centre, where the view vector is straight down and the
                // highlight collapses to ~0.
                misc: [eye_u, eye_v, eye_h, camera.time_seconds],
                morph: MORPH_NONE,
            };
            device.cmd_push_constants(
                command_buffer,
                self.pipelines.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_bind_vertex_buffers(command_buffer, 0, &[terrain.vertices.buffer], &[0]);
            device.cmd_bind_index_buffer(
                command_buffer,
                terrain.indices.buffer,
                0,
                vk::IndexType::UINT32,
            );
            device.cmd_draw_indexed(command_buffer, terrain.index_count, 1, 0, 0, 0);
            *submitted += 1;
        }
    }

    /// Draw the extruded 3D buildings: each resident tile's combined building mesh, depth-tested so
    /// buildings occlude one another and sit above the flat basemap drawn before them. Only at
    /// z14+, where the extruded detail is legible; below that nothing is drawn and the map is the
    /// flat basemap it always was. At pitch 0 the building vertex shader collapses height to the
    /// footprint, so from directly overhead the buildings read as their 2D outline.
    ///
    /// The per-tile push carries the WS0 clip matrix and, in `line.x`, the tile's world-px span for
    /// this frame — the scale that turns the mesh's tile-normalised heights into the world-px height
    /// the matrix's `z` input expects. Colour is per-vertex, so there is no colour push and no
    /// atlas: the pass binds one pipeline and issues one draw per resident tile that has buildings.
    pub(super) unsafe fn record_buildings(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        submitted: &mut usize,
        culled: &mut usize,
        binds: &mut BindState,
    ) {
        if camera.zoom.floor() < BUILDINGS_DRAW_MIN_ZOOM {
            return;
        }
        // The palette's building colour, for every wall and roof the archive did not colour
        // itself. Vertex colour is baked at tessellation time, where the palette is not reachable,
        // so the fallback rides in as a push instead and a light/dark switch recolours on the next
        // frame with no re-tessellation. `extrude_building` marks a vertex as wanting it by writing
        // alpha 0, which `building.frag` tests.
        let default_color = layers
            .iter()
            .find(|l| l.source_layer_id == tilecodec::mamaps::dict::LAYER_BUILDINGS)
            .map(|l| argb_to_rgba(l.color(palette)))
            .unwrap_or([0.8, 0.8, 0.8, 1.0]);
        let device = &self.context.device;
        for tile in self.tiles.values() {
            let Some(buildings) = &tile.buildings else {
                continue;
            };
            // Covered-stand-in suppression, like the terrain pass: a building
            // footprint cannot survive a partially suppressed ancestor without
            // looking demolished, so buildings suppress only under full
            // coverage — which is exactly what the map records.
            if self
                .suppressed_tile(tile.z, tile.x, tile.y)
                .is_some_and(|suppressed| suppressed.buildings)
            {
                *culled += 1;
                continue;
            }
            // Same off-screen cull as the flat layer loop (see `tile_offscreen`).
            if tile_offscreen(camera, tile.z, tile.x, tile.y) {
                *culled += 1;
                continue;
            }
            // One bind for the whole pass: every tile shares the building pipeline.
            binds.bind_pipeline(device, command_buffer, self.pipelines.building);
            let push = Push {
                tile_to_clip: camera.tile_to_clip(tile.z, tile.x, tile.y),
                // The palette fallback for vertices that carry no archive colour (alpha 0).
                color: default_color,
                // `line.x`: the tile's world-px span, the tile-norm-height -> world-px scale.
                line: [camera.tile_span_dp(tile.z) as f32, 0.0, 0.0, 0.0],
                misc: [0.0, 0.0, 0.0, camera.time_seconds],
                morph: MORPH_NONE,
            };
            device.cmd_push_constants(
                command_buffer,
                self.pipelines.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_bind_vertex_buffers(command_buffer, 0, &[buildings.vertices.buffer], &[0]);
            device.cmd_bind_index_buffer(
                command_buffer,
                buildings.indices.buffer,
                0,
                vk::IndexType::UINT32,
            );
            device.cmd_draw_indexed(command_buffer, buildings.index_count, 1, 0, 0, 0);
            *submitted += 1;
        }
    }
}
