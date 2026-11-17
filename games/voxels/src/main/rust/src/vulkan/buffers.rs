use ash::vk;
use std::mem;

#[derive(Clone, Copy, Debug, Default)]
#[repr(C)]
pub struct Vertex {
    pub pos: [f32; 3],
    pub uv: [f32; 2],
    pub color: [f32; 3],
    pub ao: f32,
    pub tile_idx: f32,
    pub normal: [f32; 3],
    pub light: f32,
}

impl Vertex {
    pub fn binding_description() -> vk::VertexInputBindingDescription {
        vk::VertexInputBindingDescription::default().binding(0).stride(mem::size_of::<Self>() as u32).input_rate(vk::VertexInputRate::VERTEX)
    }
    pub fn attribute_descriptions() -> [vk::VertexInputAttributeDescription; 7] {
        [
            vk::VertexInputAttributeDescription::default().binding(0).location(0).format(vk::Format::R32G32B32_SFLOAT).offset(mem::offset_of!(Vertex, pos) as u32),
            vk::VertexInputAttributeDescription::default().binding(0).location(1).format(vk::Format::R32G32_SFLOAT).offset(mem::offset_of!(Vertex, uv) as u32),
            vk::VertexInputAttributeDescription::default().binding(0).location(2).format(vk::Format::R32G32B32_SFLOAT).offset(mem::offset_of!(Vertex, color) as u32),
            vk::VertexInputAttributeDescription::default().binding(0).location(3).format(vk::Format::R32_SFLOAT).offset(mem::offset_of!(Vertex, ao) as u32),
            vk::VertexInputAttributeDescription::default().binding(0).location(4).format(vk::Format::R32_SFLOAT).offset(mem::offset_of!(Vertex, tile_idx) as u32),
            vk::VertexInputAttributeDescription::default().binding(0).location(5).format(vk::Format::R32G32B32_SFLOAT).offset(mem::offset_of!(Vertex, normal) as u32),
            vk::VertexInputAttributeDescription::default().binding(0).location(6).format(vk::Format::R32_SFLOAT).offset(mem::offset_of!(Vertex, light) as u32),
        ]
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct UboData {
    pub view_proj: [[f32; 4]; 4],
    pub sun_dir: [f32; 3],
    pub time: f32,
    pub fog_color: [f32; 3],
    pub fog_density: f32,
    pub player_pos: [f32; 4],
    pub day_factor: f32,
    // Precipitation intensity, 0..1. Sits in what used to be padding, so the std140 layout the shaders
    // see is unchanged.
    pub rain: f32,
    pub _pad: [f32; 2],
    // Inverse of view_proj; the sky pass reconstructs per-pixel world rays from it.
    pub inv_view_proj: [[f32; 4]; 4],
    // Shared lighting used by sky, clouds AND terrain so the whole scene reads as one system.
    pub sun_color: [f32; 3],
    pub cloud_shadow: f32, // strength of cloud shadows cast on terrain (0..1)
    pub ambient_color: [f32; 3],
    pub _pad2: f32,
    // Light-space view-projection for sun shadow mapping (orthographic, from the sun toward the scene).
    pub light_view_proj: [[f32; 4]; 4],
}

impl Default for UboData {
    fn default() -> Self {
        Self { view_proj: glam::Mat4::IDENTITY.to_cols_array_2d(), sun_dir: [0.2, -1.0, 0.3], time: 0.0, fog_color: [0.53, 0.81, 0.92], fog_density: 0.01, player_pos: [0.0, 70.0, 0.0, 0.0], day_factor: 1.0, rain: 0.0, _pad: [0.0;2], inv_view_proj: glam::Mat4::IDENTITY.to_cols_array_2d(), sun_color: [1.0, 0.97, 0.9], cloud_shadow: 0.6, ambient_color: [0.45, 0.55, 0.72], _pad2: 0.0, light_view_proj: glam::Mat4::IDENTITY.to_cols_array_2d() }
    }
}

pub struct AllocatedBuffer {
    pub buffer: vk::Buffer,
    pub memory: vk::DeviceMemory,
    pub size: vk::DeviceSize,
}

impl AllocatedBuffer {
    /// Allocates a Vulkan buffer.
    ///
    /// # Safety
    /// The Vulkan handles passed in must be valid, created from the same instance/device, and must not be destroyed or used concurrently while this call runs.
    pub unsafe fn new(instance: &ash::Instance, device: &ash::Device, phys: vk::PhysicalDevice, size: vk::DeviceSize, usage: vk::BufferUsageFlags, properties: vk::MemoryPropertyFlags) -> Result<Self, String> {
        let info = vk::BufferCreateInfo::default().size(size.max(1)).usage(usage).sharing_mode(vk::SharingMode::EXCLUSIVE);
        let buffer = device.create_buffer(&info, None).map_err(|e| format!("create buffer {e:?}"))?;
        let mem_reqs = device.get_buffer_memory_requirements(buffer);
        let mem_props = instance.get_physical_device_memory_properties(phys);
        let mem_type = (0..mem_props.memory_type_count).find(|&i| (mem_reqs.memory_type_bits & (1<<i))!=0 && mem_props.memory_types[i as usize].property_flags.contains(properties)).ok_or_else(|| format!("no mem type {}", size))?;
        let alloc = vk::MemoryAllocateInfo::default().allocation_size(mem_reqs.size).memory_type_index(mem_type);
        let memory = device.allocate_memory(&alloc, None).map_err(|e| format!("alloc {e:?}"))?;
        device.bind_buffer_memory(buffer, memory, 0).map_err(|e| format!("bind {e:?}"))?;
        Ok(Self { buffer, memory, size })
    }
    /// Copies `data` into the mapped buffer memory.
    ///
    /// # Safety
    /// `device` must be the device this buffer was created on, `data` must fit in the allocation, and the memory must not be in use by the GPU.
    pub unsafe fn upload(&self, device: &ash::Device, data: &[u8]) {
        if data.is_empty() { return; }
        let ptr = device.map_memory(self.memory, 0, data.len() as u64, vk::MemoryMapFlags::empty()).unwrap() as *mut u8;
        ptr.copy_from_nonoverlapping(data.as_ptr(), data.len());
        device.unmap_memory(self.memory);
    }
    /// Destroys the buffer and frees its memory.
    ///
    /// # Safety
    /// The handles owned by `self` must have been created on `device`, must not already have been destroyed, and no in-flight GPU work may still use them.
    pub unsafe fn destroy(&mut self, device: &ash::Device) {
        device.destroy_buffer(self.buffer, None);
        device.free_memory(self.memory, None);
    }
    /// Destroys the buffer and frees its memory (shared-reference form).
    ///
    /// # Safety
    /// The handles owned by `self` must have been created on `device`, must not already have been destroyed, and no in-flight GPU work may still use them. The caller must guarantee no other reference will use or destroy them again.
    pub unsafe fn destroy_inner(&self, device: &ash::Device) {
        device.destroy_buffer(self.buffer, None);
        device.free_memory(self.memory, None);
    }
}
