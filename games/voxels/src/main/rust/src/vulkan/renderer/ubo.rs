//! Per-frame uniform block: `UboFrame` inputs and `VulkanRenderer::update_ubo`.
//!
//! File-length split from `renderer` (see `renderer.rs`): the atmosphere, sun
//! and fog computation that fills the UBO each frame, beside the renderer it
//! uploads to. No behaviour change — the method body moved verbatim.

use super::VulkanRenderer;
use crate::vulkan::buffers::UboData;
use glam::{Mat4, Vec3};

fn smoothstep(e0: f32, e1: f32, x: f32) -> f32 { let t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0); t * t * (3.0 - 2.0 * t) }

/// Per-frame inputs to `VulkanRenderer::update_ubo`, grouped as one argument.
pub struct UboFrame {
    pub view_proj: Mat4,
    pub player_pos: Vec3,
    pub time: f32,
    pub underwater: f32,
    pub night_vision: f32,
    pub dim: u8,
    pub rain: f32,
}

impl VulkanRenderer {
    /// Recomputes and uploads the per-frame uniform block.
    ///
    /// # Safety
    /// Must be called from the thread that owns the renderer, and the UBO buffer must not be read by an in-flight frame while it is rewritten.
    pub unsafe fn update_ubo(&mut self, frame: UboFrame) {
        let UboFrame { view_proj, player_pos, time, underwater, night_vision, dim, rain } = frame;
        self.dim = dim;
        let day_cycle=crate::engine::DAY_CYCLE; let day_t=(time/day_cycle)%1.0;
        let sun_angle=day_t*std::f32::consts::TAU - std::f32::consts::FRAC_PI_2;
        let sun_dir=Vec3::new(sun_angle.cos()*0.3, sun_angle.sin(), sun_angle.cos()*0.1).normalize_or_zero();
        let mut day_factor=(sun_dir.y*0.5+0.5).clamp(0.0,1.0).powf(0.6);
        // Horizon sky colour approximating the sky shader, so distance fog melts terrain into the sky.
        let sun_up = smoothstep(-0.10, 0.22, sun_dir.y);
        let day_sky = Vec3::new(0.52, 0.66, 0.86);
        let sunset = Vec3::new(0.85, 0.45, 0.28);
        let night = Vec3::new(0.03, 0.04, 0.09);
        let horizon_day = day_sky.lerp(sunset, (1.0 - sun_up) * 0.7);
        let fog_color = night.lerp(horizon_day, sun_up);
        // Shared surface lighting (same sun the sky/clouds use). Warm at sunrise/sunset, white at noon.
        // Kept modest so daylight doesn't blow out to white after tonemapping.
        let sun_color = Vec3::new(1.0, 0.45, 0.20).lerp(Vec3::new(1.0, 0.96, 0.88), sun_up) * (1.5 * sun_up + 0.03);
        // Sky-dome ambient: blue-ish in day, dark at night; keeps shadowed faces from going black.
        let ambient_color = Vec3::new(0.04, 0.05, 0.09).lerp(Vec3::new(0.34, 0.40, 0.52), sun_up);
        let inv_view_proj = view_proj.inverse();
        // Sun shadow map: orthographic view from the sun toward the player, covering a box around them.
        let up = if sun_dir.y.abs() > 0.99 { Vec3::Z } else { Vec3::Y };
        let light_dist = 140.0;
        let light_eye = player_pos + sun_dir * light_dist;
        let light_view = Mat4::look_at_rh(light_eye, player_pos, up);
        let half = 90.0;
        let light_proj = Mat4::orthographic_rh(-half, half, -half, half, 1.0, light_dist * 2.0);
        let light_view_proj = light_proj * light_view;
        // Per-dimension atmosphere: red haze in the Nether, dark void in the End (no sun there).
        let (mut fog_color, mut sun_color, mut ambient_color, mut cloud_shadow) = (fog_color, sun_color, ambient_color, 0.6f32);
        if dim == 1 {
            fog_color = Vec3::new(0.32, 0.09, 0.07); ambient_color = Vec3::new(0.52, 0.30, 0.24);
            sun_color = Vec3::ZERO; day_factor = 1.0; cloud_shadow = 0.0;
        } else if dim == 2 {
            fog_color = Vec3::new(0.05, 0.03, 0.09); ambient_color = Vec3::new(0.44, 0.38, 0.54);
            sun_color = Vec3::ZERO; day_factor = 0.9; cloud_shadow = 0.0;
        }
        // Overcast weather: grey the horizon, kill the warmth and the contrast, and thicken the haze.
        // Doing this here rather than in each shader keeps the whole scene consistent for free.
        let rain = rain.clamp(0.0, 1.0);
        let mut fog_density = 0.008 / (1.0 + day_factor * 0.8).max(0.2);
        if rain > 0.0 && dim == 0 {
            let overcast = Vec3::new(0.44, 0.46, 0.50) * (0.25 + 0.75 * sun_up);
            fog_color = fog_color.lerp(overcast, rain * 0.8);
            sun_color = sun_color.lerp(sun_color * 0.35, rain);
            ambient_color = ambient_color.lerp(Vec3::new(0.26, 0.28, 0.32) * (0.2 + 0.8 * sun_up), rain * 0.85);
            // A solid overcast casts no crisp cloud shadows; it just dims everything.
            cloud_shadow *= 1.0 - rain * 0.7;
            day_factor *= 1.0 - rain * 0.4;
            fog_density *= 1.0 + rain * 2.2;
        }
        let _ = (&mut fog_color, &mut sun_color, &mut ambient_color, &mut cloud_shadow);
        self.ubo_data=UboData{ view_proj: view_proj.to_cols_array_2d(), sun_dir: sun_dir.to_array(), time, fog_color: fog_color.to_array(), fog_density, player_pos: [player_pos.x, player_pos.y, player_pos.z, underwater], day_factor, rain, _pad: [0.0;2], inv_view_proj: inv_view_proj.to_cols_array_2d(), sun_color: sun_color.to_array(), cloud_shadow, ambient_color: ambient_color.to_array(), _pad2: night_vision, light_view_proj: light_view_proj.to_cols_array_2d() };
        let bytes=unsafe { std::slice::from_raw_parts((&self.ubo_data as *const UboData) as *const u8, std::mem::size_of::<UboData>()) };
        self.ubo_buffer.upload(&self.ctx.device, bytes);
    }
}

#[cfg(test)]
#[path = "ubo_tests.rs"]
mod ubo_tests;
