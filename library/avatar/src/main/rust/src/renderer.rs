//! Renderer seam: which GPU backend drew the frame.
//!
//! Same arrangement as games/kart: Vulkan wins where drivers exist (64-bit
//! phones); GLES covers Vulkan-less boxes. Split from `engine.rs` along module
//! lines so neither file trips the 500-line Rust gate.

use crate::avatar::MAX_BONES;
use crate::gles::renderer::GlesRenderer;
use crate::mesh::YuuVertex;
use crate::vulkan::renderer::YuuRenderer;

/// Which GPU backend drew the frame. `create_renderer` tries Vulkan first,
/// falls back to GLES, and fails only when both fail.
pub enum Renderer {
    /// Vulkan path (`ash` loaded): instance + swapchain + pipeline.
    /// Boxed so the enum stays pointer-sized (both renderers are large).
    Vulkan(Box<YuuRenderer>),
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

    /// Upload the static avatar mesh (once per surface; capped per backend).
    ///
    /// # Safety
    /// Must be called on the render thread with the backend's context current.
    pub unsafe fn upload_avatar(&mut self, mesh: &[YuuVertex]) {
        match self {
            Self::Vulkan(r) => unsafe { r.upload_avatar(mesh) },
            Self::Gles(r) => unsafe { r.upload_avatar(mesh) },
        }
    }

    /// Upload a fresh face atlas (regenerated when traits change).
    ///
    /// # Safety
    /// Must be called on the render thread with the backend's context current.
    pub unsafe fn upload_atlas(&mut self, pixels: &[u8]) -> Result<(), String> {
        match self {
            Self::Vulkan(r) => unsafe { r.upload_atlas(pixels) },
            Self::Gles(r) => unsafe { r.upload_atlas(pixels) },
        }
    }

    /// Resize the swapchain/surface.
    ///
    /// # Safety
    /// Must be called on the render thread with the backend's context current.
    pub unsafe fn resize(&mut self, width: u32, height: u32) {
        match self {
            Self::Vulkan(r) => unsafe { r.resize(width, height) },
            Self::Gles(r) => unsafe { r.resize(width, height) },
        }
    }

    /// Draw one frame: bone UBO + avatar mesh.
    ///
    /// # Safety
    /// Must be called on the render thread with the backend's context current.
    pub unsafe fn draw(
        &mut self,
        view_proj: [[f32; 4]; 4],
        cam_pos: [f32; 3],
        bones: [[f32; 16]; MAX_BONES],
        skin_rgb: [f32; 3],
        mesh_count: usize,
    ) -> Result<(), String> {
        match self {
            Self::Vulkan(r) => unsafe { r.draw(view_proj, cam_pos, bones, skin_rgb, mesh_count) },
            Self::Gles(r) => unsafe { r.draw(view_proj, cam_pos, bones, skin_rgb, mesh_count) },
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

    /// Tear down the Vulkan instance + device. No-op on GLES.
    pub fn destroy_context(&mut self) {
        if let Self::Vulkan(r) = self {
            r.ctx.destroy();
        }
    }

    /// Vulkan-only attempt (used by [`crate::engine::try_vulkan`]).
    ///
    /// # Safety
    /// `window` must be a live adopted `ANativeWindow`; ownership stays with
    /// the engine (released on destroy), as on the kart path.
    pub unsafe fn try_vulkan_only(
        window: *mut crate::vulkan::context::ANativeWindow,
        width: i32,
        height: i32,
        state_width: u32,
        state_height: u32,
    ) -> Result<Self, String> {
        match crate::vulkan::renderer::YuuRenderer::new(window, width, height, state_width, state_height) {
            Ok(r) => Ok(Self::Vulkan(Box::new(r))),
            Err(e) => Err(e),
        }
    }

    /// Build the winning backend for `window`: GLES-first on 32-bit (where a
    /// `libvulkan.so` loader stub with no driver behind it SIGSEGVs inside
    /// `create_instance` instead of returning an error), Vulkan-first on
    /// 64-bit. `Err` only when both backends fail.
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
                    match unsafe { Self::try_vulkan_only(window, width, height, state_width, state_height) } {
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
            match unsafe { Self::try_vulkan_only(window, width, height, state_width, state_height) } {
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
