use super::{
    argb_to_rgba, Renderer, IDENTITY, QUAD_INDICES, VEHICLE_RING_DP, VEHICLE_RING_QUAD_DP,
};
use super::bind_state::BindState;
use super::record_markers::{
    emit_marker_corners, emit_marker_label, marker_screen_geom, upright_offset,
};
use crate::camera::Camera;
use crate::marker::{
    Marker, MARKER_LABEL_DARK, MARKER_LABEL_DP, MARKER_LABEL_HALO_DARK, MARKER_LABEL_HALO_LIGHT,
    MARKER_LABEL_LIGHT,
};
use crate::style::Palette;
use crate::vulkan::pipeline::{Push, MORPH_NONE};
use ash::vk;

impl Renderer {
    /// Draw the app's pins as billboarded atlas sprites, batched into one draw, with their
    /// name labels beside them like POI labels.
    ///
    /// The shared sprite/billboard path — WS-F's transit vehicles reuse it verbatim (with no
    /// labels). Each marker is glued to its `lon`/`lat` and kept screen-constant under tilt by
    /// [`Camera::screen_quad_to_clip`](crate::camera::Camera::screen_quad_to_clip), exactly as the
    /// puck is. Because a marker is *screen-anchored* (a fixed Dp size), its billboard quad is
    /// resolved to clip space on the CPU here — the per-marker perspective `w` is constant across
    /// the quad's four corners, so the divide can be done once — and every marker is emitted into
    /// one shared vertex/index buffer drawn with the identity matrix. That is what makes the bulk
    /// many-sprites case (dozens of vehicles) one upload and one draw rather than one per sprite.
    ///
    /// Icons and labels stay screen-upright under a heading-up camera: each quad-local offset is
    /// counter-rotated by the inverse bearing before it is hung off the (bearing-rotated) clip
    /// axes — the marker counterpart of the POI [`upright`](crate::tess::text::upright) path, so
    /// pins read exactly like the POIs around them instead of turning with the map. The globe
    /// path is already axis-aligned and needs no counter-rotation. The puck cone is untouched:
    /// its rotation with the map is what keeps a heading honest.
    ///
    /// `labels` rides parallel to `markers` (same index): an absent or empty entry draws the
    /// icon alone. Labels batch into one text draw through the flat symbol pipeline beside the
    /// icon draw — no new pipeline, and the SDF halo keeps them legible on either basemap.
    ///
    /// A marker whose icon the sheet does not carry, or which the tilt puts behind the eye, is
    /// skipped rather than drawn wrong — icon, label and POI-placement blocker alike. No
    /// `sprite_set` (a sheet that would not decode) draws no markers, exactly as it draws no
    /// POI icons; no `glyph_set` draws the icons alone.
    #[allow(clippy::too_many_arguments)]
    pub(super) unsafe fn draw_markers(
        &mut self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        palette: Palette,
        markers: &[Marker],
        labels: &[String],
        submitted: &mut usize,
        binds: &mut BindState,
        // This frame's globe flag: far-side markers culled, screen quads placed
        // via the globe anchor — or the flat billboard path bit-identically.
        globe: bool,
    ) {
        let Some(sprite_set) = self.sprite_set else {
            return;
        };
        let glyph_set = self.glyph_set;
        let glyph_atlas = crate::tile::glyph::atlas();
        let atlas = crate::tile::sprite::atlas();
        // The sheet is the light half over the dark one; dark mode adds this to every `v`, exactly
        // as `emit_icon` does, so a palette switch stays a per-frame emit rather than a re-upload.
        let dv = if palette.variant == crate::style::Variant::Dark {
            atlas.dark_v_offset()
        } else {
            0.0
        };
        // The inverse bearing the icon and label offsets are counter-rotated by so both stay
        // screen-upright. `(1, 0)` north-up, where the counter-rotation is the identity; the
        // globe path is already axis-aligned, so it takes the identity too.
        let rotation: (f64, f64) = if globe { (1.0, 0.0) } else { camera.rotation() };

        let mut vertices: Vec<f32> = Vec::with_capacity(markers.len() * 4 * 4);
        let mut indices: Vec<u32> = Vec::with_capacity(markers.len() * 6);
        let mut text_vertices: Vec<f32> = Vec::new();
        let mut text_indices: Vec<u32> = Vec::new();
        for (i, marker) in markers.iter().enumerate() {
            let Some((sprite, cx, cy, xu, yu, xv, yv, _)) =
                marker_screen_geom(camera, marker, globe)
            else {
                continue;
            };
            // Draw the icon at `MARKER_SIZE_DP` on its larger side, keeping its aspect ratio. With
            // `radius_dp = 1.0` above, a local coordinate is one Dp, so these half-extents are Dp.
            // (On the globe the quad is axis-aligned clip — same units, same math.) The extents
            // come from the shared helper so the POI-placement blocker covers exactly this box.
            let (hw_dp, hh_dp) = crate::marker::marker_icon_half_extents(sprite);
            let (hw, hh) = (hw_dp as f64, hh_dp as f64);
            let uv = sprite.uv;
            let (v0, v1) = (uv.v0 + dv, uv.v1 + dv);
            let base = (vertices.len() / 4) as u32;
            // Corners: (local_u, local_v, tex_u, tex_v). y-down in both clip and atlas, as
            // `emit_icon` documents, so v0 goes with the top edge. Counter-rotated so the
            // icon stays screen-upright under a heading-up camera.
            let corners = [
                upright_offset(-hw, -hh, rotation),
                upright_offset(hw, -hh, rotation),
                upright_offset(hw, hh, rotation),
                upright_offset(-hw, hh, rotation),
            ];
            let corners = [
                (corners[0].0, corners[0].1, uv.u0, v0),
                (corners[1].0, corners[1].1, uv.u1, v0),
                (corners[2].0, corners[2].1, uv.u1, v1),
                (corners[3].0, corners[3].1, uv.u0, v1),
            ];
            emit_marker_corners(&mut vertices, &mut indices, base, corners, cx, cy, xu, yu, xv, yv);
            // The name beside the icon, POI-style: shaped with the glyph atlas, laid out in Dp
            // to the icon's right, and hung off the same counter-rotated axes so it sits beside
            // the upright icon rather than wherever the bearing turned the map to.
            let label = labels.get(i).map(String::as_str).unwrap_or("");
            if glyph_set.is_some() && !label.is_empty() {
                emit_marker_label(
                    glyph_atlas,
                    label,
                    hw,
                    cx,
                    cy,
                    xu,
                    yu,
                    xv,
                    yv,
                    rotation,
                    &mut text_vertices,
                    &mut text_indices,
                );
            }
        }
        if indices.is_empty() {
            return;
        }
        // Vertices are already in clip space, so the shader must not transform them: identity. Only
        // `color.a` is read by `sprite.frag` (an icon draws in its own colours), so full alpha.
        let push = Push {
            tile_to_clip: IDENTITY,
            color: [1.0, 1.0, 1.0, 1.0],
            line: [0.0; 4],
            misc: [0.0, 0.0, 0.0, camera.time_seconds],
            morph: MORPH_NONE,
        };
        self.draw_symbol_batch(
            command_buffer,
            self.pipelines.sprite,
            sprite_set,
            &vertices,
            &indices,
            &push,
            submitted,
            binds,
        );
        if text_indices.is_empty() {
            return;
        }
        let Some(glyph_set) = glyph_set else {
            return;
        };
        // Labels ride the flat symbol pipeline: their quads are already clip-space, so the
        // billboard flag stays clear and the shader draws them straight through the identity
        // matrix — the same reduction `symbol_billboard.vert` documents for pitch 0. Colours
        // are the neutral locality recipe (grey text, theme halo) since markers span kinds.
        let dark = palette.variant == crate::style::Variant::Dark;
        let text_argb = if dark { MARKER_LABEL_DARK } else { MARKER_LABEL_LIGHT };
        let halo_argb = if dark {
            MARKER_LABEL_HALO_DARK
        } else {
            MARKER_LABEL_HALO_LIGHT
        };
        let text_rgba = argb_to_rgba(text_argb);
        let halo_rgb = argb_to_rgba(halo_argb);
        let text_px = MARKER_LABEL_DP * camera.density;
        let label_push = Push {
            tile_to_clip: IDENTITY,
            color: text_rgba,
            line: [
                text_px,
                1.0 * camera.density,
                glyph_atlas.sdf_per_em,
                0.0,
            ],
            misc: [0.0, halo_rgb[0], halo_rgb[1], halo_rgb[2]],
            morph: MORPH_NONE,
        };
        self.draw_symbol_batch(
            command_buffer,
            self.pipelines.symbol,
            glyph_set,
            &text_vertices,
            &text_indices,
            &label_push,
            submitted,
            binds,
        );
    }

    /// Draw the route-colour rings under coloured vehicles, so each reads in
    /// its line's colour.
    ///
    /// A dot-only pass on the puck pipeline (no cone, no white rim): one disc
    /// per vehicle with a nonzero [`Marker::colour`], sized just past the
    /// 28 Dp sprite, drawn before the sprites so they cover its middle and
    /// only the edge shows as an outline. The pipeline is bound once and each
    /// vehicle is one push-constant draw — the visible set is bounded by the
    /// host's bbox/zoom gate, so this stays a handful of draws per frame.
    /// Vehicles with colour `0` (the pack carries no `route_color`) and ones
    /// the tilt puts behind the eye draw nothing.
    pub(super) unsafe fn draw_vehicle_rings(
        &mut self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        vehicles: &[Marker],
        submitted: &mut usize,
        binds: &mut BindState,
        // This frame's globe flag: far-side vehicles culled via the globe anchor.
        globe: bool,
    ) {
        let density = camera.density;
        let device = &self.context.device;
        // Rings share the puck pipeline with the puck draw below: one bind for both
        // when vehicles are present, none at all when they are not.
        binds.bind_pipeline(device, command_buffer, self.pipelines.puck);
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[self.quad.vertices.buffer], &[0]);
        device.cmd_bind_index_buffer(
            command_buffer,
            self.quad.indices.buffer,
            0,
            vk::IndexType::UINT32,
        );
        for v in vehicles {
            if v.colour == 0 {
                continue;
            }
            // Globe: far-side vehicles culled; near-side placed via the globe anchor.
            // The ring quad is screen-constant either way, so only the matrix differs.
            let m = if globe {
                let Some((sx, sy)) = camera.globe_anchor_to_screen(v.lon, v.lat) else {
                    continue;
                };
                super::placement::globe_screen_quad(camera, sx, sy, VEHICLE_RING_QUAD_DP as f64)
            } else {
                camera.screen_quad_to_clip(v.lon, v.lat, VEHICLE_RING_QUAD_DP as f64)
            };
            if m[15] <= 0.0 {
                continue; // behind the eye / above the horizon under tilt.
            }
            let push = Push {
                tile_to_clip: m,
                color: argb_to_rgba(0xFF00_0000 | v.colour),
                // Dot only: rim 0 hides the white ring, cone sizes 0 and
                // misc.y 0 hide the bearing cone.
                line: [0.0, VEHICLE_RING_DP * density, 0.0, 0.0],
                misc: [
                    0.0,
                    0.0,
                    VEHICLE_RING_QUAD_DP * density,
                    camera.time_seconds,
                ],
                morph: MORPH_NONE,
            };
            device.cmd_push_constants(
                command_buffer,
                self.pipelines.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_draw_indexed(command_buffer, QUAD_INDICES.len() as u32, 1, 0, 0, 0);
            *submitted += 1;
        }
    }


    /// Suballocate one vertex/index pair out of this frame's scratch ring, bind `pipeline` with
    /// `set`, and draw it.
    ///
    /// The icon and text paths differ only in which pipeline and atlas they bind, so they
    /// share this. Geometry goes into [`Renderer::scratch`] rather than into a buffer of its own:
    /// a screenful of labels is several hundred of these a frame, and a `vkAllocateMemory` per
    /// draw is both slow and bounded by `maxMemoryAllocationCount`. The ring is reset at the top
    /// of the frame under the in-flight fence, which is what makes the memory safe to reuse.
    #[allow(clippy::too_many_arguments)]
    pub(super) unsafe fn draw_symbol_batch(
        &mut self,
        command_buffer: vk::CommandBuffer,
        pipeline: vk::Pipeline,
        set: vk::DescriptorSet,
        vertices: &[f32],
        indices: &[u32],
        push: &Push,
        submitted: &mut usize,
        binds: &mut BindState,
    ) {
        let frame_index = self.frame_index;
        let device = &self.context.device;
        // Disjoint field borrows, deliberately not `self.context.device.clone()` as the frame path
        // does once per frame: `ash::Device` clones its whole function-pointer table, and this
        // runs a few hundred times a frame.
        let Some((vbuf, voffset)) = self.scratch[frame_index].push(
            &self.context.instance,
            self.context.physical_device,
            device,
            vertices,
        ) else {
            return;
        };
        let Some((ibuf, ioffset)) = self.scratch[frame_index].push(
            &self.context.instance,
            self.context.physical_device,
            device,
            indices,
        ) else {
            return;
        };
        // One bind per (pipeline, atlas) pair no matter how many batches share it:
        // consecutive text batches at one size collapse to a single bind.
        let symbol_layout = self.pipelines.symbol_layout;
        binds.bind_pipeline_with_set(
            device,
            command_buffer,
            symbol_layout,
            pipeline,
            set,
        );
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.symbol_layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[vbuf], &[voffset]);
        device.cmd_bind_index_buffer(command_buffer, ibuf, ioffset, vk::IndexType::UINT32);
        device.cmd_draw_indexed(command_buffer, indices.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
    }
}
