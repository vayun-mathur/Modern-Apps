use super::{argb_to_rgba, Renderer};
use super::bind_state::BindState;
use crate::camera::Camera;
use crate::style::{Layer, LayerKind, Palette};
use crate::timing::{nanos_since, Step};
use ash::vk;
use std::collections::HashMap;

impl Renderer {
    /// Begin the frame's command buffer and render pass, and set the viewport and scissor.
    ///
    /// Split from [`record_inner`](Self::record_inner) so `record.rs` stays under the
    /// file-length limit; the sequence itself is unchanged.
    pub(super) unsafe fn begin_frame_pass(
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        render_pass: vk::RenderPass,
        framebuffer: vk::Framebuffer,
        extent: vk::Extent2D,
        clear: u32,
    ) -> Result<(), String> {
        device
            .reset_command_buffer(command_buffer, vk::CommandBufferResetFlags::empty())
            .map_err(|e| format!("reset_command_buffer {e:?}"))?;
        let begin = vk::CommandBufferBeginInfo::default()
            .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
        device
            .begin_command_buffer(command_buffer, &begin)
            .map_err(|e| format!("begin_command_buffer {e:?}"))?;

        // One per attachment, in render-pass order, and the layout differs: multisampled is
        // [colour, resolve, depth-stencil] while single-sampled is [colour, depth-stencil]. The
        // depth-stencil is therefore at index 2 or index 1 depending on the device, so both
        // trailing entries carry the same depth+stencil clear — the resolve target is `DONT_CARE`
        // and ignores its entry, and a trailing extra entry is allowed. Depth clears to the far
        // plane (1.0) for the 3D layers; stencil clears to zero, which the scrim reads as
        // "outside the region".
        let depth_stencil_clear = vk::ClearValue {
            depth_stencil: vk::ClearDepthStencilValue {
                depth: 1.0,
                stencil: 0,
            },
        };
        let clear_values = [
            vk::ClearValue {
                color: vk::ClearColorValue {
                    float32: argb_to_rgba(clear),
                },
            },
            depth_stencil_clear,
            depth_stencil_clear,
        ];
        let pass = vk::RenderPassBeginInfo::default()
            .render_pass(render_pass)
            .framebuffer(framebuffer)
            .render_area(vk::Rect2D {
                offset: vk::Offset2D { x: 0, y: 0 },
                extent,
            })
            .clear_values(&clear_values);
        device.cmd_begin_render_pass(command_buffer, &pass, vk::SubpassContents::INLINE);

        let viewport = vk::Viewport::default()
            .width(extent.width as f32)
            .height(extent.height as f32)
            .min_depth(0.0)
            .max_depth(1.0);
        device.cmd_set_viewport(command_buffer, 0, std::slice::from_ref(&viewport));
        let scissor = vk::Rect2D {
            offset: vk::Offset2D { x: 0, y: 0 },
            extent,
        };
        device.cmd_set_scissor(command_buffer, 0, std::slice::from_ref(&scissor));
        Ok(())
    }

    /// The overlay tail of [`record_inner`](Self::record_inner): carriageways, buildings, the
    /// deferred symbols, traffic, arrows, the region scrim, rail lines, the route and the
    /// overlays — steps 2–7 of the draw-order contract documented on `record_inner`.
    ///
    /// Split from `record_inner` so `record.rs` stays under the file-length limit; the sequence
    /// itself is unchanged.
    #[allow(clippy::too_many_arguments)]
    pub(super) unsafe fn record_tail(
        &mut self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        ordered: &[u64],
        fog: u32,
        deferred_symbols: &[(u64, usize)],
        accepted: &HashMap<u64, (bool, u32)>,
        bound: &mut Option<LayerKind>,
        submitted: &mut usize,
        culled: &mut usize,
        binds: &mut BindState,
        device: &ash::Device,
        flat_start: std::time::Instant,
        // This frame's globe flag (read once in `record_inner`): globe shaders +
        // depth path below, or the flat tail bit-identically above.
        globe: bool,
    ) -> Result<(), String> {
        // Overlays last, over every tile and inside the same render pass, so they are
        // presented in the same frame and from the same camera value as the basemap under
        // them. Binding the overlay pipeline invalidates `bound`, which is why this comes
        // after the layer loop rather than anywhere inside it.
        //
        // The order between the three is the reading order the driver needs. The region
        // scrim is a property of the basemap, so it goes first and the route is *not*
        // dimmed by it — a route you are following must not fade because a details sheet
        // is open. The route then goes under the puck, because the puck is where you are
        // and it has to stay visible where it sits on top of the line it is following.
        // Traffic sits on the roads it colours, so it draws after the basemap layer loop but
        // before the region scrim — it is basemap detail and should dim with everything else
        // when a region is selected, unlike the route.
        // Road carriageways: over the flat layer loop, because the surface and its markings
        // replace the road fills at this zoom, and under the buildings and deferred symbols
        // below, because a carriageway is flat basemap like every other road layer.
        self.step_times
            .borrow_mut()
            .record(Step::RecordFlat, nanos_since(flat_start));
        let step_start = std::time::Instant::now();
        self.record_carriageways(
            command_buffer,
            camera,
            layers,
            palette,
            ordered,
            fog,
            submitted,
            culled,
            binds,
            globe,
        );
        self.step_times
            .borrow_mut()
            .record(Step::RecordCarriageways, nanos_since(step_start));
        // 3D buildings: after the flat basemap so they paint over it, depth-tested so they occlude
        // one another. Gated to z14+; at pitch 0 the building matrix collapses height to the
        // footprint, so the flat overhead map is unchanged. Before the deferred symbols, so POI
        // icons and labels are not buried behind a tower.
        let step_start = std::time::Instant::now();
        self.record_buildings(command_buffer, camera, layers, palette, submitted, culled, binds);
        self.step_times
            .borrow_mut()
            .record(Step::RecordBuildings, nanos_since(step_start));
        // Traffic and turn arrows are basemap detail drawn over the roads, and the rail lines
        // are a basemap-like overlay: all three go under the labels so road names and POIs are
        // not buried beneath them.
        let step_start = std::time::Instant::now();
        self.record_traffic(command_buffer, camera, submitted, culled, binds, globe);
        self.step_times
            .borrow_mut()
            .record(Step::RecordTraffic, nanos_since(step_start));
        let step_start = std::time::Instant::now();
        self.record_arrows(command_buffer, camera, layers, submitted, culled, binds);
        self.step_times
            .borrow_mut()
            .record(Step::RecordArrows, nanos_since(step_start));
        // The route and rail lines on the globe: hidden while the globe is
        // active. Both are flat-plane overlays whose matrices assume a Mercator
        // plane; bending them is a follow-up, and a route drawn flat over a ball
        // is worse than no route. Navigation always zooms past the globe
        // threshold anyway, so this only affects a zoomed-out preview.
        let mut rail_nanos = 0;
        if !globe {
            let rail_start = std::time::Instant::now();
            self.record_rail_lines(command_buffer, camera, submitted, binds);
            rail_nanos = nanos_since(rail_start);
        }
        let step_start = std::time::Instant::now();
        for &(key, index) in deferred_symbols.iter() {
            self.record_symbol(
                command_buffer,
                key,
                index,
                &layers[index],
                camera,
                palette,
                accepted,
                submitted,
                bound,
                binds,
                globe,
            );
        }
        self.step_times
            .borrow_mut()
            .record(Step::RecordSymbols, nanos_since(step_start));
        // The region scrim dims the whole basemap, labels included, so it follows them; the
        // route follows the scrim so a route you are following is never dimmed (see above).
        // Region mask on the globe: skipped with the route (flat-plane stencil). A selected
        // region's mask reappears on zoom-in.
        if !globe {
            let step_start = std::time::Instant::now();
            self.record_region_mask(command_buffer, camera, submitted, culled, binds);
            self.step_times
                .borrow_mut()
                .record(Step::RecordRegion, nanos_since(step_start));
            // The route shares `record_route_buffers` with the rail lines, so its cost rides on
            // `RecordRail` rather than a slot that reads zero on route-less frames.
            let route_start = std::time::Instant::now();
            self.record_route(command_buffer, camera, submitted, binds);
            self.step_times
                .borrow_mut()
                .record(Step::RecordRail, rail_nanos + nanos_since(route_start));
        }
        let step_start = std::time::Instant::now();
        self.record_overlays(command_buffer, camera, palette, submitted, binds, globe);
        self.step_times
            .borrow_mut()
            .record(Step::RecordOverlays, nanos_since(step_start));

        self.submitted_draws.set(*submitted);
        // What the off-screen cull saved this frame, for the debug overlay: recorded every
        // frame (including zero) so the overlay row tracks the cull rather than going stale.
        self.step_times
            .borrow_mut()
            .record(Step::CullSkipped, *culled as u64);
        // What bind dedup saved: flushed from the frame's `BindState` (see `bind_state`).
        // Early-out skips never reach here, so their row keeps the last real frame's count
        // beside an `eout` of 1 — the two rows read together.
        binds.flush(self);
        device.cmd_end_render_pass(command_buffer);
        device
            .end_command_buffer(command_buffer)
            .map_err(|e| format!("end_command_buffer {e:?}"))
    }
}
