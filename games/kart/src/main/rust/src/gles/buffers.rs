//! VAO/VBO pairs: the static track upload plus the per-frame dynamic mesh.
//!
//! Mirrors `vulkan/buffers.rs` through GL instead of `ash`: `pos@0 (3f) +
//! color@1 (3f)`, stride 24 (the [`crate::mesh::KartVertex`] layout),
//! `STATIC_DRAW` storage sized to the caps, `DrawArrays(TRIANGLES)`.
//! `upload_track` re-sizes the store once per race; `upload_dynamic` re-sizes
//! every frame from the race transforms. No mesh budget increase (the sweep is
//! already modest for the 2 GB box).

use crate::gles::renderer::{DYNAMIC_VERT_CAP, TRACK_VERT_CAP};
use crate::mesh::KartVertex;
use glow::HasContext as _;
#[cfg(test)]
mod buffers_tests;

/// One VAO + VBO pair: vertex layout bound once, store re-sized per upload.
pub struct MeshBuffer {
    /// Vertex array (layout recorded at creation).
    vao: glow::VertexArray,
    /// Vertex buffer (store re-sized on every upload).
    vbo: glow::Buffer,
    /// Vertices in the last upload (the draw count).
    pub count: usize,
}

impl MeshBuffer {
    /// Create VAO+VBO and record the `pos@0`/`color@1` layout (stride 24).
    ///
    /// # Safety
    /// EGL context must be current on this thread.
    pub unsafe fn new(gl: &glow::Context) -> Result<Self, String> {
        let vao = unsafe { gl.create_vertex_array() }
            .map_err(|e| format!("glGenVertexArrays: {e}"))?;
        let vbo = unsafe { gl.create_buffer() }.map_err(|e| format!("glGenBuffers: {e}"))?;
        unsafe {
            gl.bind_vertex_array(Some(vao));
            gl.bind_buffer(glow::ARRAY_BUFFER, Some(vbo));
            // pos@0 (3f, offset 0) + color@1 (3f, offset 12), stride 24.
            gl.enable_vertex_attrib_array(0);
            gl.vertex_attrib_pointer_f32(0, 3, glow::FLOAT, false, 24, 0);
            gl.enable_vertex_attrib_array(1);
            gl.vertex_attrib_pointer_f32(1, 3, glow::FLOAT, false, 24, 12);
            gl.bind_buffer(glow::ARRAY_BUFFER, None);
            gl.bind_vertex_array(None);
        }
        Ok(Self { vao, vbo, count: 0 })
    }

    /// Re-size the store and upload `mesh` (capped by the caller).
    ///
    /// # Safety
    /// EGL context must be current on this thread.
    pub unsafe fn upload(&mut self, gl: &glow::Context, mesh: &[KartVertex]) {
        unsafe {
            gl.bind_buffer(glow::ARRAY_BUFFER, Some(self.vbo));
            gl.buffer_data_u8_slice(
                glow::ARRAY_BUFFER,
                bytes_of(mesh),
                glow::STATIC_DRAW,
            );
            gl.bind_buffer(glow::ARRAY_BUFFER, None);
        }
        self.count = mesh.len();
    }

    /// Draw the last upload as `TRIANGLES`.
    ///
    /// # Safety
    /// EGL context must be current; the program must be in use.
    pub unsafe fn draw(&self, gl: &glow::Context) {
        if self.count == 0 {
            return;
        }
        unsafe {
            gl.bind_vertex_array(Some(self.vao));
            gl.draw_arrays(glow::TRIANGLES, 0, self.count as i32);
            gl.bind_vertex_array(None);
        }
    }

    /// Free VAO + VBO.
    ///
    /// # Safety
    /// EGL context must be current on this thread.
    pub unsafe fn destroy(&mut self, gl: &glow::Context) {
        unsafe {
            gl.delete_buffer(self.vbo);
            gl.delete_vertex_array(self.vao);
        }
        self.count = 0;
    }
}

/// Byte view of a vertex slice (stride-24 `KartVertex`, `repr(C)`).
fn bytes_of(mesh: &[KartVertex]) -> &[u8] {
    if mesh.is_empty() {
        return &[];
    }
    unsafe {
        std::slice::from_raw_parts(
            mesh.as_ptr() as *const u8,
            std::mem::size_of_val(mesh),
        )
    }
}

/// Upload the static track mesh (once per race; capped at `TRACK_VERT_CAP`).
///
/// # Safety
/// EGL context must be current on this thread.
pub unsafe fn upload_track_mesh(
    gl: &glow::Context,
    buf: &mut MeshBuffer,
    mesh: &[KartVertex],
) {
    let n = mesh.len().min(TRACK_VERT_CAP);
    unsafe { buf.upload(gl, &mesh[..n]) };
}

/// Upload the per-frame dynamic mesh (capped at `DYNAMIC_VERT_CAP`).
///
/// # Safety
/// EGL context must be current on this thread.
pub unsafe fn upload_dynamic_mesh(
    gl: &glow::Context,
    buf: &mut MeshBuffer,
    mesh: &[KartVertex],
) {
    let n = mesh.len().min(DYNAMIC_VERT_CAP);
    unsafe { buf.upload(gl, &mesh[..n]) };
}
