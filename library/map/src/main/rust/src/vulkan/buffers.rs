//! Device memory and the buffers geometry lives in.
//!
//! # Host-visible vertex buffers, and no staging copies
//!
//! On a desktop GPU you upload through a staging buffer because device memory is across
//! a PCIe bus. Mobile GPUs are **unified memory** — Adreno and Mali both expose memory
//! that is `DEVICE_LOCAL | HOST_VISIBLE` — so the vertex data can be written straight
//! into the buffer the GPU reads, and the staging buffer, its allocation, the copy
//! command and the barrier all disappear.
//!
//! That matters here more than usual: tiles arrive continuously while panning, so an
//! upload happens several times a second, and the plan expects the CPU to be the
//! bottleneck.

use ash::vk;

/// A buffer and the memory behind it.
pub struct Buffer {
    pub buffer: vk::Buffer,
    pub memory: vk::DeviceMemory,
    pub size: vk::DeviceSize,
}

impl Buffer {
    /// Allocate a host-writable buffer and fill it with `contents`.
    ///
    /// # Safety
    ///
    /// `device` must outlive the returned buffer, and the caller must call
    /// [`Buffer::destroy`] before dropping the device.
    pub unsafe fn upload<T: Copy>(
        instance: &ash::Instance,
        physical_device: vk::PhysicalDevice,
        device: &ash::Device,
        usage: vk::BufferUsageFlags,
        contents: &[T],
    ) -> Result<Buffer, String> {
        let size = std::mem::size_of_val(contents) as vk::DeviceSize;
        if size == 0 {
            return Err("refusing to allocate a zero-length buffer".into());
        }

        let create_info = vk::BufferCreateInfo::default()
            .size(size)
            .usage(usage)
            .sharing_mode(vk::SharingMode::EXCLUSIVE);
        let buffer = device
            .create_buffer(&create_info, None)
            .map_err(|e| format!("create_buffer {e:?}"))?;

        let requirements = device.get_buffer_memory_requirements(buffer);
        let memory_type = match host_visible_memory_type(
            instance,
            physical_device,
            requirements.memory_type_bits,
        ) {
            Some(t) => t,
            None => {
                device.destroy_buffer(buffer, None);
                return Err("no host-visible memory type for a vertex buffer".into());
            }
        };

        let allocate = vk::MemoryAllocateInfo::default()
            .allocation_size(requirements.size)
            .memory_type_index(memory_type);
        let memory = match device.allocate_memory(&allocate, None) {
            Ok(m) => m,
            Err(e) => {
                device.destroy_buffer(buffer, None);
                return Err(format!("allocate_memory {e:?}"));
            }
        };
        if let Err(e) = device.bind_buffer_memory(buffer, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_buffer(buffer, None);
            return Err(format!("bind_buffer_memory {e:?}"));
        }

        // HOST_COHERENT, so no explicit flush is needed.
        match device.map_memory(memory, 0, size, vk::MemoryMapFlags::empty()) {
            Ok(mapped) => {
                std::ptr::copy_nonoverlapping(
                    contents.as_ptr() as *const u8,
                    mapped as *mut u8,
                    size as usize,
                );
                device.unmap_memory(memory);
            }
            Err(e) => {
                device.free_memory(memory, None);
                device.destroy_buffer(buffer, None);
                return Err(format!("map_memory {e:?}"));
            }
        }

        Ok(Buffer {
            buffer,
            memory,
            size,
        })
    }

    /// # Safety
    ///
    /// The device must be idle, or the buffer must not be referenced by any command
    /// buffer still executing.
    pub unsafe fn destroy(&self, device: &ash::Device) {
        device.destroy_buffer(self.buffer, None);
        device.free_memory(self.memory, None);
    }
}

fn find_memory_type(
    properties: &vk::PhysicalDeviceMemoryProperties,
    allowed: u32,
    flags: vk::MemoryPropertyFlags,
) -> Option<u32> {
    (0..properties.memory_type_count).find(|&i| {
        allowed & (1 << i) != 0
            && properties.memory_types[i as usize]
                .property_flags
                .contains(flags)
    })
}

/// The memory type a host-written buffer should live in.
///
/// Prefer memory that is both device-local and host-visible, which on a unified mobile GPU is the
/// common case and needs no staging copy. Fall back to plain host-visible, which is always present.
///
/// # Safety
///
/// `physical_device` must belong to `instance`.
unsafe fn host_visible_memory_type(
    instance: &ash::Instance,
    physical_device: vk::PhysicalDevice,
    allowed: u32,
) -> Option<u32> {
    let properties = instance.get_physical_device_memory_properties(physical_device);
    find_memory_type(
        &properties,
        allowed,
        vk::MemoryPropertyFlags::DEVICE_LOCAL
            | vk::MemoryPropertyFlags::HOST_VISIBLE
            | vk::MemoryPropertyFlags::HOST_COHERENT,
    )
    .or_else(|| {
        find_memory_type(
            &properties,
            allowed,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
        )
    })
}

/// Alignment every suballocation starts at.
///
/// Vulkan puts no alignment requirement on a vertex buffer bind offset and requires only 4 for a
/// `UINT32` index buffer, so this is headroom rather than a rule: attribute fetch off a poorly
/// aligned address is measurably slower on some mobile hardware, and at this block size the
/// padding is free.
const SCRATCH_ALIGN: vk::DeviceSize = 64;

/// How big a block is when the ring has to grow. One block covers a whole frame's symbol geometry
/// on any realistic screenful, so the steady state is a single block and no allocation at all.
const SCRATCH_BLOCK: vk::DeviceSize = 2 * 1024 * 1024;

/// A persistent bump allocator for the geometry a single frame throws away.
///
/// Symbol batches used to take a fresh [`Buffer::upload`] per vertex and index buffer per draw —
/// two `vkAllocateMemory` per tile per symbol layer per size batch, several hundred device
/// allocations in a frame. That is the canonical Vulkan antipattern rather than merely a slow one:
/// `maxMemoryAllocationCount` is commonly around 4096, so the old path was a few busy frames from
/// the hard limit, and no driver optimises for it.
///
/// Instead each frame in flight owns one ring. Memory is allocated once, stays mapped for its whole
/// life, and a draw gets an offset into it. [`reset`](Self::reset) hands the whole ring back at the
/// top of the frame, once the fence says that frame's previous commands have retired.
#[derive(Default)]
pub struct ScratchRing {
    blocks: Vec<ScratchBlock>,
    /// The block [`push`](Self::push) is filling. Earlier blocks had something not fit, and their
    /// leftover tail is given up rather than tracked — a bump allocator that reset every frame
    /// gains nothing from a free list.
    cursor: usize,
}

struct ScratchBlock {
    buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    /// Mapped for the block's whole life. The memory is `HOST_COHERENT`, so writes need no flush.
    mapped: *mut u8,
    capacity: vk::DeviceSize,
    used: vk::DeviceSize,
}

impl ScratchRing {
    /// Give the whole ring back, so the next frame writes over it.
    ///
    /// Only the write cursor is rewound; the blocks themselves are kept, which is the point. So a
    /// ring holds its high-water mark — one unusually label-dense frame pins its blocks for the
    /// life of the renderer, in 2 MiB steps. That is the trade being made: a bounded, small amount
    /// of retained memory in exchange for no device allocation on the frame path.
    ///
    /// # Safety
    ///
    /// The frame that last wrote through this ring must have completed — its in-flight fence
    /// signalled. Resetting early lets the next frame overwrite vertices a command buffer is
    /// still reading.
    pub unsafe fn reset(&mut self) {
        for block in &mut self.blocks {
            block.used = 0;
        }
        self.cursor = 0;
    }

    /// Copy `contents` into the ring; return the buffer to bind and the byte offset to bind it at.
    ///
    /// `None` for an empty slice, and when the ring had to grow and the allocation failed. The
    /// caller answers both by skipping the draw, so they are not worth distinguishing. There is
    /// nothing to unwind: a failed push consumed no space, and a successful one that a later push
    /// abandons is reclaimed by the next [`reset`](Self::reset).
    ///
    /// # Safety
    ///
    /// `device` must outlive the ring, and the returned binding is valid only until the next
    /// [`reset`](Self::reset).
    pub unsafe fn push<T: Copy>(
        &mut self,
        instance: &ash::Instance,
        physical_device: vk::PhysicalDevice,
        device: &ash::Device,
        contents: &[T],
    ) -> Option<(vk::Buffer, vk::DeviceSize)> {
        let size = std::mem::size_of_val(contents) as vk::DeviceSize;
        if size == 0 {
            return None;
        }
        loop {
            if self.cursor == self.blocks.len() {
                let capacity = align_up(size).max(SCRATCH_BLOCK);
                self.blocks.push(ScratchBlock::new(
                    instance,
                    physical_device,
                    device,
                    capacity,
                )?);
            }
            let block = &mut self.blocks[self.cursor];
            let offset = align_up(block.used);
            if offset + size <= block.capacity {
                block.used = offset + size;
                std::ptr::copy_nonoverlapping(
                    contents.as_ptr() as *const u8,
                    block.mapped.add(offset as usize),
                    size as usize,
                );
                return Some((block.buffer, offset));
            }
            // A block allocated on the branch above is empty and at least `size` big, so the
            // loop can only reach here for a block that was already in the ring.
            self.cursor += 1;
        }
    }

    /// Free every block past the first, dropping a dense frame's high-water mark.
    ///
    /// # Safety
    ///
    /// The device must be idle, or no command buffer may still reference the ring.
    pub unsafe fn shrink(&mut self, device: &ash::Device) {
        for block in self.blocks.drain(1.min(self.blocks.len())..) {
            device.unmap_memory(block.memory);
            device.destroy_buffer(block.buffer, None);
            device.free_memory(block.memory, None);
        }
        if let Some(first) = self.blocks.first_mut() {
            first.used = 0;
        }
        self.cursor = 0;
    }

    /// # Safety
    ///
    /// The device must be idle, or no command buffer may still reference the ring.
    pub unsafe fn destroy(&self, device: &ash::Device) {
        for block in &self.blocks {
            device.unmap_memory(block.memory);
            device.destroy_buffer(block.buffer, None);
            device.free_memory(block.memory, None);
        }
    }
}

impl ScratchBlock {
    unsafe fn new(
        instance: &ash::Instance,
        physical_device: vk::PhysicalDevice,
        device: &ash::Device,
        capacity: vk::DeviceSize,
    ) -> Option<ScratchBlock> {
        // One usage for both: a draw's vertices and its indices come out of the same block, and
        // splitting them would double the allocation count for no gain.
        let create_info = vk::BufferCreateInfo::default()
            .size(capacity)
            .usage(vk::BufferUsageFlags::VERTEX_BUFFER | vk::BufferUsageFlags::INDEX_BUFFER)
            .sharing_mode(vk::SharingMode::EXCLUSIVE);
        let buffer = device.create_buffer(&create_info, None).ok()?;
        let requirements = device.get_buffer_memory_requirements(buffer);
        let Some(memory_type) =
            host_visible_memory_type(instance, physical_device, requirements.memory_type_bits)
        else {
            device.destroy_buffer(buffer, None);
            return None;
        };
        let allocate = vk::MemoryAllocateInfo::default()
            .allocation_size(requirements.size)
            .memory_type_index(memory_type);
        let memory = match device.allocate_memory(&allocate, None) {
            Ok(m) => m,
            Err(_) => {
                device.destroy_buffer(buffer, None);
                return None;
            }
        };
        if device.bind_buffer_memory(buffer, memory, 0).is_err() {
            device.free_memory(memory, None);
            device.destroy_buffer(buffer, None);
            return None;
        }
        let mapped = match device.map_memory(memory, 0, capacity, vk::MemoryMapFlags::empty()) {
            Ok(p) => p as *mut u8,
            Err(_) => {
                device.free_memory(memory, None);
                device.destroy_buffer(buffer, None);
                return None;
            }
        };
        Some(ScratchBlock {
            buffer,
            memory,
            mapped,
            capacity,
            used: 0,
        })
    }
}

fn align_up(size: vk::DeviceSize) -> vk::DeviceSize {
    (size + SCRATCH_ALIGN - 1) & !(SCRATCH_ALIGN - 1)
}

/// Pack several same-format meshes into one vertex buffer + one index buffer.
///
/// Concatenates the vertex floats and rebases each mesh's indices by its vertex base, then
/// uploads the two results as a single pair: 2 `vkAllocateMemory` for the whole group instead
/// of 2 per mesh. A dense tile used to cost ~60-100 allocations on the Choreographer callback
/// and ~6400 live across 64 resident tiles, near the ~4096 driver hard limit; pooled by format
/// a tile costs at most 3 pairs (flat fills, stroked lines, ribbon carriageways) plus the
/// already-single buildings and terrain meshes. Returns the pair plus each mesh's `firstIndex`
/// in input order — indices are rebased to absolute, so every draw binds both buffers at
/// offset 0 and passes its `firstIndex`. `None` when there is nothing to pack, in which case
/// the caller emits no draws, exactly as the per-mesh path did for empty input.
///
/// The pooled-upload path ([`upload_pool`]) packs the same bytes into long-lived arenas
/// instead of fresh buffers; this stays as the fallback for tiles that do not fit, and as
/// the packing reference both paths share.
///
/// # Safety
///
/// `device` must outlive the returned buffers, and the caller must destroy them before
/// dropping the device — the same contract as [`Buffer::upload`].
pub unsafe fn upload_packed(
    instance: &ash::Instance,
    physical_device: vk::PhysicalDevice,
    device: &ash::Device,
    stride_floats: usize,
    meshes: &[(&[f32], &[u32])],
) -> Result<Option<(Buffer, Buffer, Vec<u32>)>, String> {
    let mut total_verts = 0usize;
    let mut total_indices = 0usize;
    for (verts, indices) in meshes {
        if indices.is_empty() {
            continue;
        }
        debug_assert_eq!(verts.len() % stride_floats, 0, "vertex count must be whole");
        total_verts += verts.len();
        total_indices += indices.len();
    }
    if total_indices == 0 {
        return Ok(None);
    }
    let mut packed_verts = Vec::with_capacity(total_verts);
    let mut packed_indices = Vec::with_capacity(total_indices);
    let mut first_index = Vec::with_capacity(meshes.len());
    for (verts, indices) in meshes {
        if indices.is_empty() {
            continue;
        }
        let vertex_base = (packed_verts.len() / stride_floats) as u32;
        first_index.push(packed_indices.len() as u32);
        packed_verts.extend_from_slice(verts);
        packed_indices.extend(indices.iter().map(|i| i + vertex_base));
    }
    let vertices = Buffer::upload(
        instance,
        physical_device,
        device,
        vk::BufferUsageFlags::VERTEX_BUFFER,
        &packed_verts,
    )?;
    let indices = match Buffer::upload(
        instance,
        physical_device,
        device,
        vk::BufferUsageFlags::INDEX_BUFFER,
        &packed_indices,
    ) {
        Ok(buffer) => buffer,
        Err(e) => {
            vertices.destroy(device);
            return Err(e);
        }
    };
    Ok(Some((vertices, indices, first_index)))
}
