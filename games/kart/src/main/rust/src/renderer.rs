//! Renderer seam: which GPU backend drew the frame.
//!
//! Vulkan wins where drivers exist (64-bit phones); GLES covers Vulkan-less
//! boxes (ONN 4K, Mali-G31). Split out of `engine.rs` along module lines so
//! neither file trips the 500-line Rust gate. `engine.rs` owns the race, the
//! lifecycle, and the tick; this module owns the backend dispatch.

use crate::gles::renderer::GlesRenderer;
use crate::mesh::KartVertex;
use crate::vulkan::renderer::KartRenderer;
#[cfg(test)]
mod renderer_tests;

/// Which GPU backend drew the frame. `create_renderer` tries Vulkan first,
/// falls back to GLES, and fails only when both fail.
pub enum Renderer {
    /// Vulkan path (`ash` loaded): instance + swapchain + pipeline.
    /// Boxed so the enum stays pointer-sized (both renderers are large).
    Vulkan(Box<KartRenderer>),
    /// GLES 3.0 path (`glow` + `khronos-egl`): EGL surface + GL program.
    /// Boxed for the same reason as the Vulkan variant.
    Gles(Box<GlesRenderer>),
}

impl Renderer {
    /// Current surface width (either backend).
    pub fn width(&self) -> u32 {
        match self {
            Self::Vulkan(r) => r.width,
            Self::Gles(r) => r.width,
        }
    }

    /// Current surface height (either backend).
    pub fn height(&self) -> u32 {
        match self {
            Self::Vulkan(r) => r.height,
            Self::Gles(r) => r.height,
        }
    }

    /// Surface pre-rotation for [`crate::engine::view_proj_for`]: the
    /// swapchain's `currentTransform` on Vulkan, identity on GLES
    /// (landscape-native box).
    pub fn pre_rotation(&self) -> (bool, f32) {
        match self {
            Self::Vulkan(r) => r.swap.pre_rotation(),
            Self::Gles(r) => r.pre_rotation(),
        }
    }

    /// Upload the static track mesh (once per race; capped per backend).
    ///
    /// # Safety
    /// Must be called on the render thread with the backend's context current.
    pub unsafe fn upload_track(&mut self, mesh: &[KartVertex]) {
        match self {
            Self::Vulkan(r) => unsafe { r.upload_track(mesh) },
            Self::Gles(r) => unsafe { r.upload_track(mesh) },
        }
    }

    /// Draw one frame. `track_count` is the vertex count from the last
    /// [`Self::upload_track`].
    ///
    /// # Safety
    /// Must be called on the render thread with the backend's context current.
    pub unsafe fn draw(
        &mut self,
        view_proj: [[f32; 4]; 4],
        cam_pos: [f32; 3],
        track_count: usize,
        dynamic: &[KartVertex],
    ) -> Result<(), String> {
        match self {
            Self::Vulkan(r) => unsafe { r.draw(view_proj, cam_pos, track_count, dynamic) },
            Self::Gles(r) => unsafe { r.draw(view_proj, cam_pos, track_count, dynamic) },
        }
    }

    /// Free everything except the window (the engine releases that itself).
    ///
    /// # Safety
    /// Must be called on the render thread that owns the backend's context.
    pub unsafe fn destroy(&mut self) {
        match self {
            Self::Vulkan(r) => unsafe { r.destroy() },
            Self::Gles(r) => unsafe { r.destroy() },
        }
    }

    /// Tear down the Vulkan instance + device. No-op on GLES (the EGL
    /// display/context teardown already happened in [`Self::destroy`]).
    pub fn destroy_context(&mut self) {
        if let Self::Vulkan(r) = self {
            r.ctx.destroy();
        }
    }

    /// Build the winning backend for `window`: GLES-first on 32-bit (where a
    /// `libvulkan.so` loader stub with no driver behind it SIGSEGVs inside
    /// `create_instance` instead of returning an error — no `Result` can
    /// catch that, so never touch the stub unless GLES itself fails),
    /// Vulkan-first on 64-bit. `Err` only when both backends fail.
    ///
    /// # Safety
    /// `window` must be a live adopted `ANativeWindow`; ownership stays with
    /// the engine (released on destroy), as on the Vulkan path.
    pub unsafe fn select(
        window: *mut crate::vulkan::context::ANativeWindow,
        width: i32,
        height: i32,
        state_width: u32,
        state_height: u32,
    ) -> Result<Self, String> {
        #[cfg(target_pointer_width = "32")]
        {
            match unsafe { GlesRenderer::new(window, state_width, state_height) } {
                Ok(r) => {
                    crate::engine::log_backend("GLES");
                    Ok(Self::Gles(Box::new(r)))
                }
                Err(gles_err) => {
                    match crate::engine::try_vulkan(window, width, height, state_width, state_height)
                    {
                        Ok(r) => {
                            crate::engine::log_backend(&format!(
                                "Vulkan (GLES init failed: {gles_err})"
                            ));
                            Ok(r)
                        }
                        Err(vulkan_err) => {
                            Err(format!("no renderer (GLES: {gles_err}; Vulkan: {vulkan_err})"))
                        }
                    }
                }
            }
        }
        #[cfg(not(target_pointer_width = "32"))]
        {
            match crate::engine::try_vulkan(window, width, height, state_width, state_height) {
                Ok(r) => {
                    crate::engine::log_backend("Vulkan");
                    Ok(r)
                }
                Err(vulkan_err) => {
                    match unsafe { GlesRenderer::new(window, state_width, state_height) } {
                        Ok(r) => {
                            crate::engine::log_backend(&format!(
                                "GLES (Vulkan init failed: {vulkan_err})"
                            ));
                            Ok(Self::Gles(Box::new(r)))
                        }
                        Err(gles_err) => Err(format!(
                            "no renderer (Vulkan: {vulkan_err}; GLES: {gles_err})"
                        )),
                    }
                }
            }
        }
    }
}
