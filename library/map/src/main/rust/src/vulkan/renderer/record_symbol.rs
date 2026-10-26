//! Symbol emit + draw: the icon/text push blocks and their draws.
//!
//! Split from `record_extra3.rs` (file-length limit). `record_symbol` resolves
//! the tile, its batches and its billboard inputs; `record_symbol_emit` builds the
//! globe/flat push blocks and issues the draws.
use super::{anchors_for, argb_to_rgba, scale_alpha, Renderer};
use super::bind_state::BindState;
use crate::camera::Camera;
use crate::style::{Layer, LayerKind, Palette};
use crate::vulkan::pipeline::Push;
use ash::vk;
use std::collections::HashMap;

impl Renderer {
    /// Build the icon + text push blocks for one tile's symbol batches and draw
    /// them. See [`record_symbol`](Self::record_symbol) for the emit half.
    #[allow(clippy::too_many_arguments)]
    pub(super) unsafe fn record_symbol_emit(
        &mut self,
        command_buffer: vk::CommandBuffer,
        layer: &Layer,
        camera: &Camera,
        palette: Palette,
        tz: u8,
        tile_clip: [f32; 16],
        _tile_span_px: f32,
        billboard_flag: f32,
        ortho2x2: [f32; 4],
        batches: &[(f32, Vec<f32>, Vec<u32>)],
        icon_vertices: &[f32],
        icon_indices: &[u32],
        glyph_set: vk::DescriptorSet,
        submitted: &mut usize,
        bound: &mut Option<LayerKind>,
        binds: &mut BindState,
        // This frame's globe flag: globe symbol/icon pipelines + sphere push, or
        // the flat path bit-identically above.
        globe: bool,
    ) {
        let halo = argb_to_rgba(layer.halo_color(palette));
        let color = argb_to_rgba(scale_alpha(
            layer.color(palette),
            layer.opacity_at(camera.zoom),
        ));
        let sdf_per_em = crate::tile::glyph::atlas().sdf_per_em;

        // Icons first, so the label's halo paints over the icon's edge rather than under
        // it — the order MapLibre draws them in. On the globe both go through the
        // globe pipelines with the sphere matrix (centre + radius in the matrix);
        // `misc.x` keeps its flat meaning (tile span, Dp) and `morph` carries the
        // globe half-viewport + radius (see `symbol_globe.vert`).
        let r = crate::camera::globe_radius(camera.zoom, camera.center_lat) as f32;
        let (globe_misc, globe_morph) = if globe {
            (
                [camera.tile_span_dp(tz) as f32, 0.0, 0.0, 0.0],
                [camera.width_dp / 2.0, camera.height_dp / 2.0, r, 0.0],
            )
        } else {
            (
                [camera.tile_span_dp(tz) as f32, 0.0, 0.0, 0.0],
                [ortho2x2[0], ortho2x2[1], ortho2x2[2], ortho2x2[3]],
            )
        };
        if let Some(sprite_set) = self.sprite_set.filter(|_| !icon_indices.is_empty()) {
            let push = Push {
                tile_to_clip: tile_clip,
                // Only the alpha is read by `sprite.frag`: an icon draws in its own
                // colours, since the reference sets no `icon-color`.
                color,
                // `sprite.frag` reads none of `line`; `w` is the billboard flag the shared vertex
                // shader reads, so an icon stands up under tilt on the same terms as its label.
                // On the globe the flag is clear: `symbol_globe.vert` bends instead.
                line: [0.0, 0.0, 0.0, if globe { 0.0 } else { billboard_flag }],
                // `misc.x` is the tile's world-px span (Dp): the anchor-height scale the
                // billboard shader multiplies by. (Was the device-px span; the vertex
                // shader documented it unused and nothing reads it.)
                misc: globe_misc,
                // The pitch-0 linear 2x2, as for the text below — the same matrix, so the icon and
                // the name beside it resolve their screen offsets identically.
                morph: globe_morph,
            };
            self.draw_symbol_batch(
                command_buffer,
                if globe {
                    self.pipelines.icon_globe
                } else {
                    self.pipelines.icon
                },
                sprite_set,
                icon_vertices,
                icon_indices,
                &push,
                submitted,
                binds,
            );
            // The icon pipeline shares `LayerKind::Symbol`, so this still forces the
            // fill/line path to rebind. The symbol pipeline is bound again immediately
            // below whenever there is any text — and a label with an icon always has
            // text, because `shape_label` returns nothing for an empty name.
            *bound = Some(LayerKind::Symbol);
        }

        for (text_px, vertices, indices) in batches {
            // Halo width from the style (authored text-halo-width, 1px), in device px
            // like the text size beside it; text color + opacity per palette.
            let push = Push {
                tile_to_clip: tile_clip,
                color,
                line: [
                    *text_px,
                    layer.halo_width * camera.density,
                    sdf_per_em,
                    if globe { 0.0 } else { billboard_flag },
                ],
                // `misc.x` is the tile's world-px span (Dp): the anchor-height scale.
                // `yzw` stay the halo rgb the fragment shader reads. The sphere
                // basis comes from the globe matrix, so the halo stays valid on
                // the globe (see `symbol_globe.vert`).
                misc: [camera.tile_span_dp(tz) as f32, halo[0], halo[1], halo[2]],
                // Repurposed for the symbol billboard pipeline: the pitch-0 tile matrix's linear
                // 2x2, so the shader can add a screen-constant glyph offset under tilt. The symbol
                // fragment shader does not read `morph`, so this collides with nothing.
                // On the globe: half-viewport + radius (see `symbol_globe.vert`).
                morph: globe_morph,
            };
            self.draw_symbol_batch(
                command_buffer,
                if globe {
                    self.pipelines.symbol_globe
                } else {
                    self.pipelines.symbol
                },
                glyph_set,
                vertices,
                indices,
                &push,
                submitted,
                binds,
            );
            *bound = Some(LayerKind::Symbol);
        }
    }

    /// Draw one tile's one symbol layer: emit its shaped labels at the frame's
    /// text size, upload a transient buffer pair, and draw it with the symbol
    /// pipeline bound to the glyph atlas set.
    ///
    /// Only labels in `accepted` (see [`place_symbols`](Self::place_symbols))
    /// emit; the rest lost their collisions this frame.
    ///
    /// The emit + draw half lives in `record_symbol_emit` (file-length split):
    /// this function resolves the tile, its ground sampler and its billboard
    /// inputs, then hands them over.
    #[allow(clippy::too_many_arguments)]
    pub(super) unsafe fn record_symbol(
        &mut self,
        command_buffer: vk::CommandBuffer,
        key: u64,
        layer_index: usize,
        layer: &Layer,
        camera: &Camera,
        palette: Palette,
        accepted: &HashMap<u64, (bool, u32)>,
        submitted: &mut usize,
        bound: &mut Option<LayerKind>,
        binds: &mut BindState,
        // This frame's globe flag: globe symbol/icon pipelines + sphere push, or
        // the flat path bit-identically above.
        globe: bool,
    ) {
        use crate::tile::{placement, symbol};
        let Some(glyph_set) = self.glyph_set else {
            return;
        };
        // THE density fix (task 1): the size ramp is authored in Dp but the
        // tile span and the shader are device px — without ×density every
        // label renders at 1/density size (≈2px tall cap-height at 17px Dp on
        // a density-3 screen, exactly the reported symptom). Lines already do
        // this (`half_px(density)`); symbols must too.
        //
        // Resolved per label rather than per layer, because the authored size depends on
        // the place's population rank as well as the zoom.
        if !layer.text_visible_at(camera.zoom) {
            return;
        }
        let Some(tile) = self.tiles.get(&key) else {
            return;
        };
        // The tile's coordinates, copied out so the `self.tiles` borrow below is held only by
        // `tile.labels` and ends at the batch loop — the `&mut self` uploads come after it.
        let (tz, tx, ty) = (tile.z, tile.x, tile.y);
        let tile_span_px = camera.tile_span_px(tz);
        // The tile-normalised ground height sampler for label anchors: the same DEM the flat
        // drape reads, so a label hangs off exactly the relief its road drapes onto. 0.0 with
        // no heightmap, so covered output is unchanged. Normalised against this tile's own
        // ground width — the shader scales it back with the pushed Dp span.
        let ground_width = crate::tile::geometry::tile_ground_width_m(tz, ty);
        let heightmap = &tile.heightmap;
        let ground = |u: f32, v: f32| -> f32 {
            crate::tile::geometry::sample_ground_metres(heightmap, u, v).map_or(0.0, |m| {
                (m as f64 / ground_width.max(f64::MIN_POSITIVE)) as f32
            })
        };
        // Copy out what the draw needs before any `&mut self` call below: `tile`
        // borrows `self`, and buffer upload takes `&self.context` while retiring
        // takes `&mut self`.
        let tile_clip = camera.tile_to_clip(tz, tx, ty);
        // Billboarding under tilt (point labels and their icons): the shader projects each corner's
        // ground anchor through `tile_clip` (perspective) and hangs the corner off it at a constant
        // screen offset, reconstructed from the pitch-0 tile matrix's linear 2x2. At pitch 0 the
        // flag is clear and the shader draws straight through `tile_clip`, byte-identical to
        // before. Curved labels carry each vertex as its own anchor, so they stay on the ground
        // regardless — a road name lies along the road, which is the one case where flat is right.
        //
        // One derivation, not two checked: [`symbol::icon_push_billboard`] builds the flag and the
        // matrix the icon push *and* the text push below both read, so the pictogram cannot slide
        // off its name under tilt. Reverting either value in one copy compiles clean and passes —
        // which is exactly the silent failure this sharing exists to make unrepresentable.
        let flat_clip = Camera {
            pitch_deg: 0.0,
            ..*camera
        }
        .tile_to_clip(tz, tx, ty);
        let (billboard_flag, ortho2x2) = symbol::icon_push_billboard(&flat_clip, camera.pitch_deg);
        let (primary, alternate) = anchors_for(layer);
        // Labels counter-rotate about their anchor so they stay upright under a
        // heading-up camera, which is what a driver needs and what keeps the placer's
        // axis-aligned collision boxes describing the box the label actually occupies.
        // `(1, 0)` north-up, where `upright` is a no-op.
        let (cos, sin) = camera.rotation();
        let rotation = (cos as f32, sin as f32);
        // Batched by resolved size, not one batch per tile-layer. A label's size now
        // depends on its population rank, so one draw can hold two of them — and
        // `Push::line.x` carries the text size the fragment shader turns a halo width in
        // px into SDF units with. One value cannot serve both arms, so each gets a draw.
        // The style declares at most two arms, so this is at most two. Buffers are
        // pre-sized from the label count rather than grown geometrically: the frame's
        // label set is known up front, so the push loop below pays no realloc.
        let label_count = tile
            .labels
            .iter()
            .filter(|l| l.layer_index == layer_index)
            .count();
        let mut batches: Vec<(f32, Vec<f32>, Vec<u32>)> = Vec::with_capacity(2);
        // Icons take one batch of their own however many sizes the text has: they are a
        // constant screen size, and they sample a different atlas through a different
        // fragment shader, so they could not share a draw with the text regardless.
        let mut icon_vertices: Vec<f32> =
            Vec::with_capacity(label_count * 4 * crate::tess::text::FLOATS_PER_VERTEX);
        let mut icon_indices: Vec<u32> = Vec::with_capacity(label_count * 6);
        // The style's size arms resolved once per (layer, zoom) rather than once per
        // label: each `text_size_for` walks two style ramps, and every label of this
        // layer shares the zoom. Bit-identical sizes — `text_size_with_arms` reads the
        // same three ramp values per label.
        let (base_size, large_size, rank_threshold) = layer.text_size_arms(camera.zoom);
        // Labels are enumerated in tile-list order — the same order (and the
        // same ids) the pre-pass used — and only accepted ones emit. A tile
        // whose every label collides emits nothing and skips its draw.
        //
        // Borrowed, not cloned. This used to deep-copy the whole label vector — every name
        // `String`, every shaped line, every curved centreline — once per symbol layer per
        // resident tile per frame, purely to release the `self.tiles` borrow before the uploads
        // further down. Nothing in this loop needs `&mut self`, so the borrow simply ends here.
        for (label_idx, label) in tile
            .labels
            .iter()
            .enumerate()
            .filter(|(_, l)| l.layer_index == layer_index)
        {
            let id = placement::candidate_id(tz, tx, ty, layer_index, label_idx);
            let Some(&(flipped, _)) = accepted.get(&id) else {
                continue;
            };
            // Draw at whichever anchor the placer actually accepted, or the label lands
            // on the side its box was rejected for.
            let anchor = if flipped {
                alternate.unwrap_or(primary)
            } else {
                primary
            };
            let text_px = crate::style::Layer::text_size_with_arms(
                label.pop,
                base_size,
                large_size,
                rank_threshold,
            ) * camera.density;
            if text_px <= 0.0 {
                continue;
            }
            if let Some(sprite) = label.sprite {
                symbol::emit_icon(
                    label,
                    sprite,
                    palette.variant == crate::style::Variant::Dark,
                    camera.density,
                    tile_span_px,
                    rotation,
                    &ground,
                    &mut icon_vertices,
                    &mut icon_indices,
                );
            }
            let batch = match batches.iter_mut().find(|(size, _, _)| *size == text_px) {
                Some(batch) => batch,
                None => {
                    // Sized for the whole layer's labels: the second arm's batch
                    // over-reserves when every label takes the first, which costs
                    // address space for one frame, not time.
                    let cap = label_count * 4 * crate::tess::text::FLOATS_PER_VERTEX;
                    batches.push((text_px, Vec::with_capacity(cap), Vec::with_capacity(label_count * 6)));
                    batches.last_mut().expect("just pushed")
                }
            };
            symbol::emit_label(
                label,
                anchor,
                layer.text_offset,
                text_px,
                tile_span_px,
                rotation,
                &ground,
                &mut batch.1,
                &mut batch.2,
            );
        }
        batches.retain(|(_, _, indices)| !indices.is_empty());
        if batches.is_empty() && icon_indices.is_empty() {
            return;
        }
        self.record_symbol_emit(
            command_buffer,
            layer,
            camera,
            palette,
            tz,
            tile_clip,
            tile_span_px,
            billboard_flag,
            ortho2x2,
            &batches,
            &icon_vertices,
            &icon_indices,
            glyph_set,
            submitted,
            bound,
            binds,
            globe,
        );
    }
}
