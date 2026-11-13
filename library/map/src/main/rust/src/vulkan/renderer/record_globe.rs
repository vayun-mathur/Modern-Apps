//! The globe's ocean disc: the sea on the ball, drawn over the space-coloured clear.
//!
//! Split from `record.rs` (file-length limit). Globe frames clear to
//! [`style::space`](crate::style::space) — black in the dark palette, white in the light —
//! so the area outside the sphere reads as space rather than sea. The sphere itself still
//! needs the sea underneath its land, which the flat map gets for free from its water-blue
//! clear; this pass paints exactly that, as one analytic disc of the globe's screen radius.
//!
//! It reuses the user-puck pipeline and the shared unit quad: the puck shader already draws
//! an antialiased disc of a pushed pixel radius, so the sea costs one push constant and one
//! draw, no new shader and no geometry. When the sphere covers the whole viewport the frame
//! clears straight to water instead (see [`style::clear_color`](crate::style::clear_color))
//! and this draws nothing.
use super::bind_state::BindState;
use super::{argb_to_rgba, Renderer, QUAD_INDICES};
use crate::camera::Camera;
use crate::vulkan::pipeline::{Push, MORPH_NONE};
use ash::vk;

/// Extra quad margin past the disc edge, in Dp, so the shader's one-pixel edge falloff is not
/// clipped by the quad itself.
const EDGE_MARGIN_DP: f64 = 2.0;

impl Renderer {
    /// Draw the ocean disc under a globe frame that leaves space visible; no-op otherwise.
    ///
    /// Issued first in the pass, before any tile draw, so every globe fill/line lands on top
    /// of it exactly as the flat map's layers land on the water-blue clear.
    pub(super) unsafe fn record_globe_backdrop(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        ocean: u32,
        binds: &mut BindState,
        submitted: &mut usize,
    ) {
        if !crate::camera::globe_active(camera) || camera.globe_covers_viewport() {
            return;
        }
        let radius_dp = crate::camera::globe_radius(camera.zoom, camera.center_lat);
        let quad_dp = radius_dp + EDGE_MARGIN_DP;
        let density = camera.density;
        let matrix = super::placement::globe_screen_quad(
            camera,
            camera.width_dp as f64 / 2.0,
            camera.height_dp as f64 / 2.0,
            quad_dp,
        );
        let push = Push {
            tile_to_clip: matrix,
            color: argb_to_rgba(ocean),
            // Rim radius below zero: the puck shader's white rim contributes nothing, so the
            // disc is the core alone, in the ocean colour.
            line: [-1.0, (radius_dp as f32) * density, 0.0, 0.0],
            // No bearing cone; the quad radius in device px converts the local -1..1 to px.
            misc: [0.0, 0.0, (quad_dp as f32) * density, camera.time_seconds],
            morph: MORPH_NONE,
        };
        let device = &self.context.device;
        binds.bind_pipeline(device, command_buffer, self.pipelines.puck);
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[self.quad.vertices.buffer], &[0]);
        device.cmd_bind_index_buffer(command_buffer, self.quad.indices.buffer, 0, vk::IndexType::UINT32);
        device.cmd_draw_indexed(command_buffer, QUAD_INDICES.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
    }
}
