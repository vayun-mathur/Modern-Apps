//! Frame renderer: EGL surface, glow program, skinned VAO/VBO, draw + present.
//!
//! The GLES twin of [`crate::vulkan::renderer::YuuRenderer`]: `new` brings up
//! EGL, loads glow from `eglGetProcAddress`, compiles/links the program,
//! creates the avatar [`MeshBuffer`] and uploads the static mesh, sets
//! viewport + `clear_color(SKY)` + depth `LESS` + cull off; `draw` clears,
//! sets uniforms (view-proj, sun, cam, bones), draws the mesh, then
//! `swap_buffers` (no fences — the swap is the present). `resize` re-creates
//! the EGL surface on the same context (GL objects survive); `destroy` frees
//! GL objects then tears down EGL. The avatar mesh uploads once per surface,
//! exactly like Vulkan.

use crate::atlas::ATLAS_SIZE;
use crate::avatar::MAX_BONES;
use crate::gles::buffers::{self, MeshBuffer};
use crate::gles::egl::Egl;
use crate::gles::shaders::{self, U_ALBEDO, U_BONES, U_CAM_POS, U_SUN_DIR, U_VIEW_PROJ};
use crate::mesh::YuuVertex;
use glow::HasContext as _;
#[cfg(test)]
mod renderer_tests;

/// Static avatar mesh budget: the procedural fallback is ~1k verts; 64k
/// leaves margin for a detailed glTF base mesh.
pub const AVATAR_VERT_CAP: usize = 65_536;

/// Sky clear color, shared with the Vulkan clear value and the frag shader.
pub const SKY: [f32; 3] = [0.45, 0.68, 0.94];

/// Fixed sun direction (normalized on upload — mirrors the Vulkan UBO fill).
const SUN_DIR: [f32; 3] = [0.35, -1.0, 0.45];

/// Everything needed to draw one GLES frame.
pub struct GlesRenderer {
    /// EGL display/surface/context (RAII; owns the presentation surface).
    egl: Egl,
    /// glow context (function pointers loaded via `eglGetProcAddress`).
    gl: glow::Context,
    /// Linked `yuu_avatar_gles` program.
    program: glow::Program,
    /// `uViewProj` uniform location.
    u_view_proj: glow::UniformLocation,
    /// `uSunDir` uniform location.
    u_sun_dir: glow::UniformLocation,
    /// `uCamPos` uniform location.
    u_cam_pos: glow::UniformLocation,
    /// `uBones` uniform location.
    u_bones: glow::UniformLocation,
    /// `uAlbedo` sampler location (texture unit 0 at draw).
    u_albedo: glow::UniformLocation,
    /// Albedo atlas texture (256x256 RGBA8; re-uploaded on trait change).
    albedo_tex: glow::Texture,
    /// Static avatar VAO/VBO (uploaded per surface).
    avatar: MeshBuffer,
    /// Current surface width.
    pub width: u32,
    /// Current surface height.
    pub height: u32,
}

impl GlesRenderer {
    /// Create from a live window at `width` x `height`. Fails cleanly (the
    /// caller logs and keeps the 2D fallback) rather than panicking.
    ///
    /// # Safety
    /// `window` must be a live `ANativeWindow` adopted from the JNI surface
    /// (the caller acquired it via `ANativeWindow_acquire`); ownership moves
    /// to the engine, exactly as on the Vulkan path.
    pub unsafe fn new(
        window: *mut crate::vulkan::context::ANativeWindow,
        width: u32,
        height: u32,
    ) -> Result<Self, String> {
        let egl = unsafe { Egl::new(window)? };
        // Validate the loader BEFORE the first real GL call: glow loads
        // lazily, so a null entry point only explodes inside the driver
        // (SIGSEGV in libGLES_mali, no EGL error). Fail to Err here and the
        // caller keeps the fallback instead of crashing.
        for name in [
            "glCreateShader",
            "glShaderSource",
            "glCompileShader",
            "glCreateProgram",
            "glLinkProgram",
            "glGenBuffers",
            "glBindBuffer",
            "glBufferData",
            "glGenVertexArrays",
            "glBindVertexArray",
            "glViewport",
            "glClearColor",
            "glClear",
        ] {
            egl.require_proc(name)?;
        }
        let gl = unsafe {
            glow::Context::from_loader_function(|name| egl.proc_address(name) as *const _)
        };
        let program = unsafe { shaders::compile_program(&gl)? };
        crate::gles::egl::step_log("program compiled+linked");
        let (u_view_proj, u_sun_dir, u_cam_pos, u_bones, u_albedo) = unsafe {
            let view = gl
                .get_uniform_location(program, U_VIEW_PROJ)
                .ok_or_else(|| format!("missing uniform {U_VIEW_PROJ}"))?;
            let sun = gl
                .get_uniform_location(program, U_SUN_DIR)
                .ok_or_else(|| format!("missing uniform {U_SUN_DIR}"))?;
            let cam = gl
                .get_uniform_location(program, U_CAM_POS)
                .ok_or_else(|| format!("missing uniform {U_CAM_POS}"))?;
            let bones = gl
                .get_uniform_location(program, U_BONES)
                .ok_or_else(|| format!("missing uniform {U_BONES}"))?;
            // Mali linkers keep the sampler alive since the frag samples it.
            let albedo = gl
                .get_uniform_location(program, U_ALBEDO)
                .ok_or_else(|| format!("missing uniform {U_ALBEDO}"))?;
            (view, sun, cam, bones, albedo)
        };
        crate::gles::egl::step_log("uniforms located");
        let albedo_tex = unsafe {
            let tex = gl
                .create_texture()
                .map_err(|e| format!("glGenTextures albedo: {e}"))?;
            gl.bind_texture(glow::TEXTURE_2D, Some(tex));
            gl.tex_parameter_i32(
                glow::TEXTURE_2D,
                glow::TEXTURE_MIN_FILTER,
                glow::LINEAR as i32,
            );
            gl.tex_parameter_i32(
                glow::TEXTURE_2D,
                glow::TEXTURE_MAG_FILTER,
                glow::LINEAR as i32,
            );
            gl.tex_parameter_i32(
                glow::TEXTURE_2D,
                glow::TEXTURE_WRAP_S,
                glow::CLAMP_TO_EDGE as i32,
            );
            gl.tex_parameter_i32(
                glow::TEXTURE_2D,
                glow::TEXTURE_WRAP_T,
                glow::CLAMP_TO_EDGE as i32,
            );
            // No uninitialized reads: allocate a skin-tone flat before the
            // engine's first upload_atlas. UNPACK_ALIGNMENT 1 is a no-op
            // safeguard (256*4 rows are already 4-aligned).
            gl.pixel_store_i32(glow::UNPACK_ALIGNMENT, 1);
            let size = ATLAS_SIZE as i32;
            let mut filled = vec![0u8; ATLAS_SIZE * ATLAS_SIZE * 4];
            for o in filled.chunks_exact_mut(4) {
                o.copy_from_slice(&[232, 181, 137, 255]);
            }
            gl.tex_image_2d(
                glow::TEXTURE_2D,
                0,
                glow::RGBA8 as i32,
                size,
                size,
                0,
                glow::RGBA,
                glow::UNSIGNED_BYTE,
                Some(&filled),
            );
            gl.bind_texture(glow::TEXTURE_2D, None);
            tex
        };
        crate::gles::egl::step_log("albedo texture created");
        let avatar = unsafe { MeshBuffer::new(&gl)? };
        crate::gles::egl::step_log("avatar MeshBuffer created");
        let renderer = Self {
            egl,
            gl,
            program,
            u_view_proj,
            u_sun_dir,
            u_cam_pos,
            u_bones,
            u_albedo,
            albedo_tex,
            avatar,
            width: width.max(1),
            height: height.max(1),
        };
        unsafe { renderer.apply_state() };
        Ok(renderer)
    }

    /// Upload the static avatar mesh (once per surface; capped at the cap).
    ///
    /// # Safety
    /// Must be called on the render thread with the EGL context current.
    pub unsafe fn upload_avatar(&mut self, mesh: &[YuuVertex]) {
        unsafe { buffers::upload_avatar_mesh(&self.gl, &mut self.avatar, mesh) };
    }

    /// Upload a fresh 256x256 RGBA atlas (from `atlas::generate_atlas`) when
    /// traits change; the engine calls this right after creation and on every
    /// trait update.
    ///
    /// # Safety
    /// Must be called on the render thread with the EGL context current.
    pub unsafe fn upload_atlas(&mut self, pixels: &[u8]) -> Result<(), String> {
        let want = ATLAS_SIZE * ATLAS_SIZE * 4;
        if pixels.len() != want {
            return Err(format!(
                "atlas size mismatch: got {} bytes, want {want}",
                pixels.len()
            ));
        }
        unsafe {
            self.gl.pixel_store_i32(glow::UNPACK_ALIGNMENT, 1);
            self.gl
                .bind_texture(glow::TEXTURE_2D, Some(self.albedo_tex));
            self.gl.tex_sub_image_2d(
                glow::TEXTURE_2D,
                0,
                0,
                0,
                ATLAS_SIZE as i32,
                ATLAS_SIZE as i32,
                glow::RGBA,
                glow::UNSIGNED_BYTE,
                glow::PixelUnpackData::Slice(pixels),
            );
            self.gl.bind_texture(glow::TEXTURE_2D, None);
        }
        Ok(())
    }

    /// Draw one frame: clear, set uniforms (view-proj, sun, cam, bones),
    /// draw the avatar, `eglSwapBuffers`.
    ///
    /// # Safety
    /// Must be called on the render thread with the EGL context current.
    pub unsafe fn draw(
        &mut self,
        view_proj: [[f32; 4]; 4],
        cam_pos: [f32; 3],
        bones: [[f32; 16]; MAX_BONES],
        _skin_rgb: [f32; 3],
        _mesh_count: usize,
    ) -> Result<(), String> {
        let flat = flatten(&view_proj);
        let sun = normalize(SUN_DIR);
        // Column-major bone flatten for `uniform_matrix_4_fv(transpose=false)`.
        let mut bone_flat = [0.0f32; MAX_BONES * 16];
        for (i, b) in bones.iter().enumerate() {
            bone_flat[i * 16..(i + 1) * 16].copy_from_slice(b);
        }
        unsafe {
            self.gl
                .clear_color(SKY[0], SKY[1], SKY[2], 1.0);
            self.gl.clear(glow::COLOR_BUFFER_BIT | glow::DEPTH_BUFFER_BIT);
            self.gl.use_program(Some(self.program));
            self.gl
                .uniform_matrix_4_f32_slice(Some(&self.u_view_proj), false, &flat);
            self.gl
                .uniform_3_f32(Some(&self.u_sun_dir), sun[0], sun[1], sun[2]);
            self.gl
                .uniform_3_f32(Some(&self.u_cam_pos), cam_pos[0], cam_pos[1], cam_pos[2]);
            self.gl.uniform_matrix_4_f32_slice(Some(&self.u_bones), false, &bone_flat);
            self.gl.active_texture(glow::TEXTURE0);
            self.gl
                .bind_texture(glow::TEXTURE_2D, Some(self.albedo_tex));
            self.gl.uniform_1_i32(Some(&self.u_albedo), 0);
        }
        unsafe {
            self.avatar.draw(&self.gl);
            self.gl.bind_texture(glow::TEXTURE_2D, None);
            self.gl.use_program(None);
        }
        self.egl.swap_buffers()
    }

    /// Re-create the EGL surface at the new size on the same context (GL
    /// objects survive, so no re-upload). On failure the caller drops the
    /// renderer and the 2D fallback covers, as on Vulkan.
    ///
    /// # Safety
    /// `window` must be the live adopted `ANativeWindow`; render thread only.
    pub unsafe fn resize(&mut self, width: u32, height: u32) {
        self.width = width.max(1);
        self.height = height.max(1);
        unsafe { self.apply_state() };
    }

    /// Free program + albedo texture + VAO/VBOs, then tear down EGL. The
    /// engine releases the window itself.
    ///
    /// # Safety
    /// Must be called on the render thread that owns the EGL context.
    pub unsafe fn destroy(&mut self) {
        unsafe {
            self.gl.use_program(None);
            self.gl.bind_texture(glow::TEXTURE_2D, None);
            self.gl.delete_texture(self.albedo_tex);
            self.avatar.destroy(&self.gl);
            self.gl.delete_program(self.program);
        }
        self.egl.teardown();
    }

    /// Viewport + fixed GL state: depth test `LESS` (matches the Vulkan
    /// pipeline), cull off (matches `CULL_NONE` — winding is as authored
    /// since the GLES shaders carry no Y flip).
    unsafe fn apply_state(&self) {
        unsafe {
            self.gl
                .viewport(0, 0, self.width as i32, self.height as i32);
            self.gl.enable(glow::DEPTH_TEST);
            self.gl.depth_func(glow::LESS);
            self.gl.disable(glow::CULL_FACE);
        }
    }
}

/// Column-major flatten for `uniform_matrix_4_f32_slice(transpose=false)`:
/// the `[[f32; 4]; 4]` from [`crate::engine::view_proj_for`] is already
/// `to_cols_array_2d`, so concatenation preserves column order.
fn flatten(m: &[[f32; 4]; 4]) -> [f32; 16] {
    let mut out = [0.0f32; 16];
    for (col, column) in m.iter().enumerate() {
        for (row, v) in column.iter().enumerate() {
            out[col * 4 + row] = *v;
        }
    }
    out
}

/// Normalize the fixed sun direction (mirrors the Vulkan UBO fill).
fn normalize(v: [f32; 3]) -> [f32; 3] {
    let len = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt().max(1e-6);
    [v[0] / len, v[1] / len, v[2] / len]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn flatten_preserves_column_major_order() {
        let m = [
            [1.0, 2.0, 3.0, 4.0],
            [5.0, 6.0, 7.0, 8.0],
            [9.0, 10.0, 11.0, 12.0],
            [13.0, 14.0, 15.0, 16.0],
        ];
        assert_eq!(flatten(&m), [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0]);
        // Identity stays identity under flatten (GL transpose=false path).
        let id = glam::Mat4::IDENTITY.to_cols_array_2d();
        let flat = flatten(&id);
        for i in 0..16 {
            assert_eq!(flat[i], if i % 5 == 0 { 1.0 } else { 0.0 }, "index {i}");
        }
    }

    #[test]
    fn normalize_and_fixed_lighting_constants() {
        assert_eq!(normalize([0.0, 0.0, 0.0]), [0.0, 0.0, 0.0], "zero vector guarded, not NaN");
        let n = normalize([3.0, 0.0, 4.0]);
        assert!((n[0] - 0.6).abs() < 1e-6 && (n[2] - 0.8).abs() < 1e-6);
        let sun = normalize(SUN_DIR);
        assert!((sun.iter().map(|v| v * v).sum::<f32>() - 1.0).abs() < 1e-5);
        assert!(SUN_DIR[1] < 0.0, "sun points downward onto the avatar");
        assert!(SKY.iter().all(|v| (0.0..=1.0).contains(v)));
        assert_eq!(AVATAR_VERT_CAP, 65_536);
    }
}
