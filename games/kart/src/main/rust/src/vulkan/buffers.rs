//! Vertex + uniform buffers: the kart vertex format, the view-projection
//! UBO, and the host-visible upload helper.
//!
//! Same arrangement as games/voxels `vulkan/buffers.rs`: one
//! `AllocatedBuffer` (buffer + memory + size) with an `upload` that maps,
//! copies, and unmaps. The world is small (one oval + ≤8 karts), so per-frame
//! full re-uploads of the dynamic mesh stay comfortably in budget.
use ash::vk;
use std::mem;

/// One vertex: world position + flat RGB. Layout must match `kart.vert`
/// (`location 0 = pos`, `location 1 = color`).
#[derive(Clone, Copy, Debug, Default)]
#[repr(C)]
pub struct KartVkVertex {
    /// World position.
    pub pos: [f32; 3],
    /// Flat RGB.
    pub color: [f32; 3],
}

impl KartVkVertex {
    /// Single binding, stride = struct size, per-vertex rate.
    pub fn binding_description() -> vk::VertexInputBindingDescription {
        vk::VertexInputBindingDescription::default()
            .binding(0)
            .stride(mem::size_of::<Self>() as u32)
            .input_rate(vk::VertexInputRate::VERTEX)
    }

    /// Two attributes: `vec3 pos` at 0, `vec3 color` at 1.
    pub fn attribute_descriptions() -> [vk::VertexInputAttributeDescription; 2] {
        [
            vk::VertexInputAttributeDescription::default()
                .binding(0)
                .location(0)
                .format(vk::Format::R32G32B32_SFLOAT)
                .offset(mem::offset_of!(KartVkVertex, pos) as u32),
            vk::VertexInputAttributeDescription::default()
                .binding(0)
                .location(1)
                .format(vk::Format::R32G32B32_SFLOAT)
                .offset(mem::offset_of!(KartVkVertex, color) as u32),
        ]
    }
}

/// Uniforms for `kart.vert`/`kart.frag`: view-projection, sun, camera.
/// `#[repr(C)]` + explicit padding so the host layout matches std140.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct KartUbo {
    /// `view * proj` (the Y flip lives in the shader, not here).
    pub view_proj: [[f32; 4]; 4],
    /// Fixed sun direction (normalized on upload).
    pub sun_dir: [f32; 3],
    /// std140 pad.
    pub _pad0: f32,
    /// Chase-cam world position (the fog origin).
    pub cam_pos: [f32; 3],
    /// std140 pad.
    pub _pad1: f32,
}

impl Default for KartUbo {
    fn default() -> Self {
        Self {
            view_proj: glam::Mat4::IDENTITY.to_cols_array_2d(),
            sun_dir: [0.35, -1.0, 0.45],
            _pad0: 0.0,
            cam_pos: [0.0, 4.0, 20.0],
            _pad1: 0.0,
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
