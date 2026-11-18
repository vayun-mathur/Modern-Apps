//! Vertex + uniform buffers: the skinned Yuu vertex format, the bone-matrix
//! UBO, and the host-visible upload helper.
//!
//! Same arrangement as games/kart `vulkan/buffers.rs`: one `AllocatedBuffer`
//! (buffer + memory + size) with an `upload` that maps, copies, and unmaps.
//! The avatar mesh is small (one hero instance), so a single static upload
//! plus per-frame UBO updates stays comfortably in budget.
use crate::avatar::MAX_BONES;
use crate::mesh::YuuVertex;
use ash::vk;
use std::mem;
#[cfg(test)]
mod buffers_tests;

/// One skinned vertex: position + normal + UV + joints + weights. Layout must
/// match `yuu_avatar.vert` (locations 0..4).
#[derive(Clone, Copy, Debug, Default)]
#[repr(C)]
pub struct YuuVkVertex {
    /// Model-space position.
    pub pos: [f32; 3],
    /// Model-space normal.
    pub normal: [f32; 3],
    /// Albedo UV.
    pub uv: [f32; 2],
    /// Bone indices into the UBO order (floats — Mali note on YuuVertex).
    pub joints: [f32; 4],
    /// Bone weights (normalized).
    pub weights: [f32; 4],
}

impl From<&YuuVertex> for YuuVkVertex {
    fn from(v: &YuuVertex) -> Self {
        Self {
            pos: v.pos,
            normal: v.normal,
            uv: v.uv,
            joints: v.joints,
            weights: v.weights,
        }
    }
}

impl YuuVkVertex {
    /// Single binding, stride = struct size, per-vertex rate.
    pub fn binding_description() -> vk::VertexInputBindingDescription {
        vk::VertexInputBindingDescription::default()
            .binding(0)
            .stride(mem::size_of::<Self>() as u32)
            .input_rate(vk::VertexInputRate::VERTEX)
    }

    /// Five attributes: `vec3 pos` at 0, `vec3 normal` at 1, `vec2 uv` at 2,
    /// `vec4 joints` at 3, `vec4 weights` at 4 (floats — Mali note).
    pub fn attribute_descriptions() -> [vk::VertexInputAttributeDescription; 5] {
        [
            vk::VertexInputAttributeDescription::default()
                .binding(0)
                .location(0)
                .format(vk::Format::R32G32B32_SFLOAT)
                .offset(mem::offset_of!(YuuVkVertex, pos) as u32),
            vk::VertexInputAttributeDescription::default()
                .binding(0)
                .location(1)
                .format(vk::Format::R32G32B32_SFLOAT)
                .offset(mem::offset_of!(YuuVkVertex, normal) as u32),
            vk::VertexInputAttributeDescription::default()
                .binding(0)
                .location(2)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(mem::offset_of!(YuuVkVertex, uv) as u32),
            vk::VertexInputAttributeDescription::default()
                .binding(0)
                .location(3)
                .format(vk::Format::R32G32B32A32_SFLOAT)
                .offset(mem::offset_of!(YuuVkVertex, joints) as u32),
            vk::VertexInputAttributeDescription::default()
                .binding(0)
                .location(4)
                .format(vk::Format::R32G32B32A32_SFLOAT)
                .offset(mem::offset_of!(YuuVkVertex, weights) as u32),
        ]
    }
}

/// Uniforms for `yuu_avatar.vert`/`yuu_avatar.frag`: view-projection, sun,
/// camera, plus the bone matrices. `#[repr(C)]` + explicit padding so the
/// host layout matches std140.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct YuuUbo {
    /// `view * proj` (the Y flip lives in the shader, not here).
    pub view_proj: [[f32; 4]; 4],
    /// Fixed sun direction (normalized on upload).
    pub sun_dir: [f32; 3],
    /// std140 pad.
    pub _pad0: f32,
    /// Preview-cam world position (the fade origin).
    pub cam_pos: [f32; 3],
    /// std140 pad.
    pub _pad1: f32,
    /// Bone matrices in UBO joint order.
    pub bones: [[[f32; 4]; 4]; MAX_BONES],
}

impl Default for YuuUbo {
    fn default() -> Self {
        Self {
            view_proj: glam::Mat4::IDENTITY.to_cols_array_2d(),
            sun_dir: [0.35, -1.0, 0.45],
            _pad0: 0.0,
            cam_pos: [0.0, 1.1, 2.6],
            _pad1: 0.0,
            bones: [[[0.0; 4]; 4]; MAX_BONES],
        }
    }
}

/// A device buffer with its memory: the unit everything else allocates.
pub struct AllocatedBuffer {
    /// The Vulkan buffer.
    pub buffer: vk::Buffer,
    /// Its backing memory.
    pub memory: vk::DeviceMemory,
    /// Requested size (allocation may be larger).
    pub size: vk::DeviceSize,
}

impl AllocatedBuffer {
    /// Allocate host-visible coherent memory and bind it. Fails cleanly.
    ///
    /// # Safety
    /// `instance`, `device`, and `phys` must be valid handles from a live
    /// Vulkan context that outlives the returned buffer.
    pub unsafe fn new(
        instance: &ash::Instance,
        device: &ash::Device,
        phys: vk::PhysicalDevice,
        size: vk::DeviceSize,
        usage: vk::BufferUsageFlags,
    ) -> Result<Self, String> {
        let info = vk::BufferCreateInfo::default()
            .size(size.max(1))
            .usage(usage)
            .sharing_mode(vk::SharingMode::EXCLUSIVE);
        let buffer = device
            .create_buffer(&info, None)
            .map_err(|e| format!("create buffer {e:?}"))?;
        let mem_reqs = device.get_buffer_memory_requirements(buffer);
        let mem_props = instance.get_physical_device_memory_properties(phys);
        let properties =
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT;
        let mem_type = (0..mem_props.memory_type_count)
            .find(|&i| {
                (mem_reqs.memory_type_bits & (1 << i)) != 0
                    && mem_props.memory_types[i as usize]
                        .property_flags
                        .contains(properties)
            })
            .ok_or_else(|| format!("no mem type {size}"))?;
        let alloc = vk::MemoryAllocateInfo::default()
            .allocation_size(mem_reqs.size)
            .memory_type_index(mem_type);
        let memory = device
            .allocate_memory(&alloc, None)
            .map_err(|e| format!("alloc {e:?}"))?;
        device
            .bind_buffer_memory(buffer, memory, 0)
            .map_err(|e| format!("bind {e:?}"))?;
        Ok(Self { buffer, memory, size })
    }

    /// Map, copy, unmap. Empty uploads are a no-op.
    ///
    /// # Safety
    /// `device` must be the device that created this buffer, and the buffer
    /// memory must not be in use by the GPU. Render thread only.
    pub unsafe fn upload(&self, device: &ash::Device, data: &[u8]) {
        if data.is_empty() {
            return;
        }
        let ptr = device
            .map_memory(
                self.memory,
                0,
                data.len() as u64,
                vk::MemoryMapFlags::empty(),
            )
            .unwrap() as *mut u8;
        ptr.copy_from_nonoverlapping(data.as_ptr(), data.len());
        device.unmap_memory(self.memory);
    }

    /// Free memory + buffer.
    ///
    /// # Safety
    /// `device` must be the device that created this buffer; the buffer
    /// must not be in use by the GPU and must not be destroyed twice.
    pub unsafe fn destroy(&mut self, device: &ash::Device) {
        device.destroy_buffer(self.buffer, None);
        device.free_memory(self.memory, None);
    }
}
