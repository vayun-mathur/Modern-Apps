//! VAO/VBO pairs: the static avatar upload plus the per-frame bone uniforms.
//!
//! Mirrors `vulkan/buffers.rs` through GL instead of `ash`: `pos@0 (3f) +
//! normal@1 (3f) + uv@2 (2f) + joints@3 (4u) + weights@4 (4f)`, stride 56 (the
//! [`crate::mesh::YuuVertex`] layout), `STATIC_DRAW` storage sized to the cap,
//! `DrawArrays(TRIANGLES)`. The mesh uploads once per surface; bones upload
//! per frame as uniforms.

use crate::gles::renderer::AVATAR_VERT_CAP;
use crate::mesh::YuuVertex;
use glow::HasContext as _;
use std::mem;

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
    /// Create VAO+VBO and record the skinned layout.
    ///
    /// Stride/offsets derive from `YuuVertex` (`repr(C)`: 12+12+8+16+16 =
    /// 64 bytes) via `size_of`/`offset_of`, mirroring the Vulkan twin — never
    /// hardcode them.
    ///
    /// # Safety
    /// EGL context must be current on this thread.
    pub unsafe fn new(gl: &glow::Context) -> Result<Self, String> {
        let vao = unsafe { gl.create_vertex_array() }
            .map_err(|e| format!("glGenVertexArrays: {e}"))?;
        let vbo = unsafe { gl.create_buffer() }.map_err(|e| format!("glGenBuffers: {e}"))?;
        let stride = mem::size_of::<YuuVertex>() as i32;
        let off_pos = mem::offset_of!(YuuVertex, pos) as i32;
        let off_normal = mem::offset_of!(YuuVertex, normal) as i32;
        let off_uv = mem::offset_of!(YuuVertex, uv) as i32;
        let off_joints = mem::offset_of!(YuuVertex, joints) as i32;
        let off_weights = mem::offset_of!(YuuVertex, weights) as i32;
        unsafe {
            gl.bind_vertex_array(Some(vao));
            gl.bind_buffer(glow::ARRAY_BUFFER, Some(vbo));
            // pos@0 (3f) + normal@1 (3f) + uv@2 (2f) + joints@3 (4f) + weights@4 (4f).
            gl.enable_vertex_attrib_array(0);
            gl.vertex_attrib_pointer_f32(0, 3, glow::FLOAT, false, stride, off_pos);
            gl.enable_vertex_attrib_array(1);
            gl.vertex_attrib_pointer_f32(1, 3, glow::FLOAT, false, stride, off_normal);
            gl.enable_vertex_attrib_array(2);
            gl.vertex_attrib_pointer_f32(2, 2, glow::FLOAT, false, stride, off_uv);
            gl.enable_vertex_attrib_array(3);
            gl.vertex_attrib_pointer_f32(3, 4, glow::FLOAT, false, stride, off_joints);
            gl.enable_vertex_attrib_array(4);
            gl.vertex_attrib_pointer_f32(4, 4, glow::FLOAT, false, stride, off_weights);
            gl.bind_buffer(glow::ARRAY_BUFFER, None);
            gl.bind_vertex_array(None);
        }
        Ok(Self { vao, vbo, count: 0 })
    }

    /// Re-size the store and upload `mesh` (capped by the caller).
    ///
    /// # Safety
    /// EGL context must be current on this thread.
    pub unsafe fn upload(&mut self, gl: &glow::Context, mesh: &[YuuVertex]) {
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

/// Byte view of a vertex slice (stride-56 `YuuVertex`, `repr(C)`).
fn bytes_of(mesh: &[YuuVertex]) -> &[u8] {
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

/// Upload the static avatar mesh (once per surface; capped at the cap).
///
/// # Safety
/// EGL context must be current on this thread.
pub unsafe fn upload_avatar_mesh(
    gl: &glow::Context,
    buf: &mut MeshBuffer,
    mesh: &[YuuVertex],
) {
    let n = mesh.len().min(AVATAR_VERT_CAP);
    unsafe { buf.upload(gl, &mesh[..n]) };
}
