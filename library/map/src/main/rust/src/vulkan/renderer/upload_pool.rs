//! Pooled tile-upload staging: one reusable device allocation per pool, not per tile.
//!
//! A tile upload used to cost up to 5 fresh `vkAllocateMemory` pairs (flat, lines, ribbons,
//! buildings, terrain) on the Choreographer callback — and before format pooling, ~60-100.
//! `vkAllocateMemory` is a driver-global lock plus bookkeeping on most mobile drivers, so a
//! burst of finished tiles landing in one frame is the frame-time tail P0 measured.
//!
//! [`UploadPool`] fixes the remaining per-tile allocation: one persistent 8 MiB vertex arena
//! and one 4 MiB index arena, sub-allocated per upload from a first-fit free list
//! ([`RangeAlloc`]). A tile's meshes pack into the arenas exactly as [`upload_packed`] packs
//! them into fresh buffers — same slices, same `first_index` rebasing — except the slices name
//! (arena, byte offset) ranges instead of whole buffers, and draws bind those offsets.
//!
//! Lifetime: a tile's ranges ([`ArenaRange`]) go back to the free list only when the tile
//! leaves the retiring queue, i.e. after the frames-in-flight grace, so no in-flight draw can
//! read a range that a later upload overwrites. When an arena fills, the pool allocates the
//! next (up to [`MAX_ARENAS`]); a tile larger than one arena falls back to fresh buffers. An
//! arena past the first that empties out is destroyed, so a burst does not pin its high-water
//! mark for the session.
//!
//! Every fresh allocation the drain still issues (fallback path, first-fill growth) is
//! counted into `Step::UploadAllocs`: the overlay row reads zero in the steady state, and
//! any nonzero names the path to fix. Pooled uploads record nothing.
//!
//! Split out (file-length limit); `upload.rs` calls in, never the reverse.
use crate::range_alloc::{align_up, RangeAlloc};
use crate::vulkan::buffers::Buffer;
use crate::timing::Step;
use super::Renderer;
use ash::vk;

/// Vertex arena size: 8 MiB holds several dense z14 tiles' pooled vertices, so the steady
/// state is one arena for the session. Index arenas are half this.
const VERTEX_ARENA_BYTES: vk::DeviceSize = 8 * 1024 * 1024;
/// How many arenas the pool grows to before falling back to fresh buffers: enough for a
/// cold-start burst, few enough that a pathological session cannot eat the device.
const MAX_ARENAS: usize = 4;

/// One upload arena pair and the free lists over it.
///
/// `vertices`/`indices` are whole-arena buffers: tiles own *ranges* of them, never the
/// buffers, so nothing destroys these through a tile — only [`destroy_pool`] and
/// [`UploadPool::release_empty_arenas`] do.
struct Arena {
    vertices: Buffer,
    indices: Buffer,
    vert_alloc: RangeAlloc,
    index_alloc: RangeAlloc,
}

/// A tile's claim on one arena, handed back by [`UploadPool::release`] once the tile retires.
#[derive(Clone, Copy)]
pub(super) struct ArenaRange {
    arena: usize,
    vert_offset: vk::DeviceSize,
    vert_len: vk::DeviceSize,
    index_offset: vk::DeviceSize,
    index_len: vk::DeviceSize,
}

impl ArenaRange {
    pub(super) fn bytes(&self) -> u64 {
        self.vert_len + self.index_len
    }
}

/// The renderer's tile-upload pool. Slots stay put (a destroyed arena leaves `None`) so the
/// arena index in an [`ArenaRange`] never shifts under a live tile.
pub(super) struct UploadPool {
    arenas: Vec<Option<Arena>>,
}

impl UploadPool {
    pub(super) fn new() -> UploadPool {
        UploadPool { arenas: Vec::new() }
    }

    /// Borrow one arena's buffer pair by index, for building a tile's draw binds.
    /// Null handles when the index names no arena — never panics on a stale slice.
    /// A null bind draws nothing, exactly as a missing pool did on the fresh path.
    pub(super) fn arena_buffers(&self, index: usize) -> (vk::Buffer, vk::Buffer) {
        self.arenas.get(index).and_then(Option::as_ref).map_or_else(
            || (vk::Buffer::null(), vk::Buffer::null()),
            |a| (a.vertices.buffer, a.indices.buffer),
        )
    }

    /// Hand a retired tile's range back to its arena's free lists.
    ///
    /// Only after the tile's grace period: the range may be overwritten by the next upload.
    pub(super) fn release(&mut self, range: &ArenaRange) {
        let Some(Some(arena)) = self.arenas.get_mut(range.arena) else {
            return;
        };
        if range.vert_len > 0 {
            arena.vert_alloc.release(range.vert_offset, range.vert_len);
        }
        if range.index_len > 0 {
            arena.index_alloc.release(range.index_offset, range.index_len);
        }
    }

    /// Destroy arenas no tile holds a range in. The first is kept while `keep_first`, since
    /// an empty mesh group's placeholder names arena 0's handle.
    ///
    /// # Safety
    ///
    /// Ranges are only released after the grace period, so an empty arena has no in-flight
    /// reader; the caller must uphold that by releasing through [`release`](Self::release).
    pub(super) unsafe fn release_empty_arenas(&mut self, device: &ash::Device, keep_first: bool) {
        let skip = usize::from(keep_first);
        for slot in self.arenas.iter_mut().skip(skip) {
            if slot
                .as_ref()
                .is_some_and(|a| a.vert_alloc.is_empty() && a.index_alloc.is_empty())
            {
                if let Some(arena) = slot.take() {
                    arena.vertices.destroy(device);
                    arena.indices.destroy(device);
                }
            }
        }
        while matches!(self.arenas.last(), Some(None)) {
            self.arenas.pop();
        }
    }

    /// Whether `buffer` is an arena buffer owned by this pool (never destroyed through
    /// a tile) rather than a fresh per-tile buffer.
    ///
    /// Offset 0 is NOT the discriminator: the first tile in an arena starts at 0, so
    /// only the handle comparison is exact. Destroying an arena buffer through a tile
    /// would double-free it at renderer teardown.
    pub(super) fn owns_buffer(&self, b: vk::Buffer) -> bool {
        self.arenas
            .iter()
            .flatten()
            .any(|a| a.vertices.buffer == b || a.indices.buffer == b)
    }

    /// Record fresh allocations into `Step::UploadAllocs`. Called by `upload.rs` for the
    /// fallback path and arena growth — pooled uploads record nothing, so the row reads
    /// zero in the steady state.
    pub(super) fn record_allocs(renderer: &Renderer, allocs: u64) {
        if allocs > 0 {
            renderer
                .step_times
                .borrow_mut()
                .record(Step::UploadAllocs, allocs);
        }
    }
}

/// One packed mesh group's slice of an arena: byte offsets into the arena pair plus the
/// per-mesh `firstIndex` list, in the same shape [`upload_packed`] returns.
pub(super) struct PooledPacked {
    /// Byte offset of this group's vertices in the arena's vertex buffer.
    pub vert_offset: vk::DeviceSize,
    /// Byte offset of this group's indices in the arena's index buffer.
    pub index_offset: vk::DeviceSize,
    /// Arena index holding this group.
    pub arena: usize,
    /// Reserved (aligned) byte lengths; zero for an empty group's placeholder.
    pub vert_len: vk::DeviceSize,
    pub index_len: vk::DeviceSize,
    /// Per-mesh `firstIndex` values, rebased to absolute like [`upload_packed`].
    pub first_index: Vec<u32>,
}

impl PooledPacked {
    fn placeholder() -> PooledPacked {
        PooledPacked {
            vert_offset: 0,
            index_offset: 0,
            arena: 0,
            vert_len: 0,
            index_len: 0,
            first_index: Vec::new(),
        }
    }

    fn range(&self) -> ArenaRange {
        ArenaRange {
            arena: self.arena,
            vert_offset: self.vert_offset,
            vert_len: self.vert_len,
            index_offset: self.index_offset,
            index_len: self.index_len,
        }
    }
}

/// Arena-backed slices for one tile's three packed groups.
pub(super) struct TileArenaSlices {
    pub flat: PooledPacked,
    pub lines: PooledPacked,
    pub ribbons: PooledPacked,
}

impl TileArenaSlices {
    /// The ranges the tile must hand back on retire (placeholders hold none).
    pub(super) fn ranges(&self) -> Vec<ArenaRange> {
        [&self.flat, &self.lines, &self.ribbons]
            .into_iter()
            .filter(|p| p.vert_len > 0 || p.index_len > 0)
            .map(PooledPacked::range)
            .collect()
    }
}

/// The per-tile mesh groups `upload` packs, in arena-commit order.
pub(super) struct UploadGroups<'a> {
    pub flat: &'a [(&'a [f32], &'a [u32])],
    pub lines: &'a [(&'a [f32], &'a [u32])],
    pub ribbons: &'a [(&'a [f32], &'a [u32])],
}

/// Byte sizes of one packed group's vertices and indices (f32/u32, 4 bytes each).
fn group_bytes(meshes: &[(&[f32], &[u32])]) -> (vk::DeviceSize, vk::DeviceSize) {
    let mut verts = 0usize;
    let mut indices = 0usize;
    for (v, i) in meshes {
        if i.is_empty() {
            continue;
        }
        verts += v.len();
        indices += i.len();
    }
    ((verts * 4) as vk::DeviceSize, (indices * 4) as vk::DeviceSize)
}

/// Rebase each mesh's slice offset, in input order — the same `firstIndex` list
/// [`upload_packed`] returns (packed *index* offsets), so draw slices line up with the
/// packed order. The per-mesh index *values* still need the vertex-base rebase at pack
/// time (see `pack_group`): the two rebases are the same pair `upload_packed` applies.
fn rebase_first_index(meshes: &[(&[f32], &[u32])]) -> Vec<u32> {
    let mut first_index = Vec::with_capacity(meshes.len());
    let mut packed_indices = 0u32;
    for (_, indices) in meshes {
        if indices.is_empty() {
            continue;
        }
        first_index.push(packed_indices);
        packed_indices += indices.len() as u32;
    }
    first_index
}

/// Concatenate one group's meshes and rebase index values by vertex base — the packed
/// bytes [`upload_packed`] would upload, returned instead of uploaded so the caller can
/// commit them into an arena.
fn pack_group(stride_floats: usize, meshes: &[(&[f32], &[u32])]) -> (Vec<f32>, Vec<u32>) {
    let mut packed_verts = Vec::new();
    let mut packed_indices = Vec::new();
    for (verts, indices) in meshes {
        if indices.is_empty() {
            continue;
        }
        let vertex_base = (packed_verts.len() / stride_floats) as u32;
        packed_verts.extend_from_slice(verts);
        packed_indices.extend(indices.iter().map(|i| i + vertex_base));
    }
    (packed_verts, packed_indices)
}

/// Reserve one packed group's ranges in the first arena with room for both; `None` when
/// none has.
fn try_reserve(
    pool: &mut UploadPool,
    meshes: &[(&[f32], &[u32])],
    vert_bytes: vk::DeviceSize,
    index_bytes: vk::DeviceSize,
) -> Option<PooledPacked> {
    if vert_bytes == 0 || index_bytes == 0 {
        return None;
    }
    for (n, slot) in pool.arenas.iter_mut().enumerate() {
        let Some(arena) = slot else { continue };
        if !arena.vert_alloc.fits(vert_bytes) || !arena.index_alloc.fits(index_bytes) {
            continue;
        }
        let vert_offset = arena.vert_alloc.alloc(vert_bytes)?;
        let Some(index_offset) = arena.index_alloc.alloc(index_bytes) else {
            arena.vert_alloc.release(vert_offset, vert_bytes);
            return None;
        };
        return Some(PooledPacked {
            vert_offset,
            index_offset,
            arena: n,
            vert_len: align_up(vert_bytes),
            index_len: align_up(index_bytes),
            first_index: rebase_first_index(meshes),
        });
    }
    None
}

/// Grow the pool by one arena pair (2 fresh allocations, counted by the caller).
fn grow_arenas(renderer: &mut Renderer) -> Result<(), String> {
    let instance = &renderer.context.instance;
    let physical = renderer.context.physical_device;
    let device = &renderer.context.device;
    // SAFETY: fresh buffers; owned by the pool and destroyed with the renderer.
    unsafe {
        let vertices = Buffer::upload(
            instance,
            physical,
            device,
            vk::BufferUsageFlags::VERTEX_BUFFER,
            &vec![0u8; VERTEX_ARENA_BYTES as usize],
        )?;
        let indices = match Buffer::upload(
            instance,
            physical,
            device,
            vk::BufferUsageFlags::INDEX_BUFFER,
            &vec![0u8; (VERTEX_ARENA_BYTES / 2) as usize],
        ) {
            Ok(b) => b,
            Err(e) => {
                vertices.destroy(device);
                return Err(e);
            }
        };
        let arena = Arena {
            vert_alloc: RangeAlloc::new(vertices.size),
            index_alloc: RangeAlloc::new(indices.size),
            vertices,
            indices,
        };
        let pool = &mut renderer.upload_pool;
        match pool.arenas.iter_mut().find(|slot| slot.is_none()) {
            Some(slot) => *slot = Some(arena),
            None => pool.arenas.push(Some(arena)),
        }
    }
    Ok(())
}

/// Copy packed bytes into a reserved arena range. On failure the caller releases the
/// range and falls back rather than drawing it.
unsafe fn commit_bytes(
    renderer: &Renderer,
    packed: &PooledPacked,
    packed_verts: &[f32],
    packed_indices: &[u32],
) -> bool {
    let device = &renderer.context.device;
    let Some(Some(arena)) = renderer.upload_pool.arenas.get(packed.arena) else {
        return false;
    };
    let vert_bytes = std::mem::size_of_val(packed_verts) as vk::DeviceSize;
    let index_bytes = std::mem::size_of_val(packed_indices) as vk::DeviceSize;
    // HOST_COHERENT arena memory: map, copy, unmap — no flush. Two maps per group; the
    // allocator calls they replace were the cost, not the maps.
    let ok_v = match device.map_memory(
        arena.vertices.memory,
        packed.vert_offset,
        vert_bytes,
        vk::MemoryMapFlags::empty(),
    ) {
        Ok(mapped) => {
            std::ptr::copy_nonoverlapping(
                packed_verts.as_ptr() as *const u8,
                mapped as *mut u8,
                vert_bytes as usize,
            );
            device.unmap_memory(arena.vertices.memory);
            true
        }
        Err(_) => false,
    };
    let ok_i = match device.map_memory(
        arena.indices.memory,
        packed.index_offset,
        index_bytes,
        vk::MemoryMapFlags::empty(),
    ) {
        Ok(mapped) => {
            std::ptr::copy_nonoverlapping(
                packed_indices.as_ptr() as *const u8,
                mapped as *mut u8,
                index_bytes as usize,
            );
            device.unmap_memory(arena.indices.memory);
            true
        }
        Err(_) => false,
    };
    ok_v && ok_i
}

/// Reserve one group's arena space, growing the pool once on a miss.
///
/// Returns `None` for an empty group (no reservation, no slices) and when the group
/// fits nowhere even after growth — the caller falls back for the whole tile. Growth
/// counts 2 into `Step::UploadAllocs` (one vertex + one index arena).
///
/// The range comes off the free list at reserve time, not after its bytes land: the three
/// groups reserve in turn against one pool, so a later group must see the earlier group's
/// range as taken. A later commit failure releases it again (see `commit_tile_groups`).
fn reserve_group(
    renderer: &mut Renderer,
    meshes: &[(&[f32], &[u32])],
    bytes: (vk::DeviceSize, vk::DeviceSize),
) -> Option<PooledPacked> {
    if bytes == (0, 0) {
        return None;
    }
    // A group larger than one arena never fits: fall back rather than splitting draws
    // across arenas, which would complicate every bind site for a case (a single mesh
    // group over 8 MiB) that never occurs.
    if bytes.0 > VERTEX_ARENA_BYTES || bytes.1 > VERTEX_ARENA_BYTES / 2 {
        return None;
    }
    if let Some(reserved) = try_reserve(&mut renderer.upload_pool, meshes, bytes.0, bytes.1) {
        return Some(reserved);
    }
    if renderer.upload_pool.arenas.iter().flatten().count() >= MAX_ARENAS {
        return None;
    }
    grow_arenas(renderer).ok()?;
    UploadPool::record_allocs(renderer, 2);
    // The new arena is empty, so the size check above guarantees the fit.
    try_reserve(&mut renderer.upload_pool, meshes, bytes.0, bytes.1)
}

/// Commit one tile's three packed groups into the pool.
///
/// Reserves all three groups first (growing the pool if an empty arena is needed), then
/// packs and copies each group. Returns the arena slices, or `None` when any group did
/// not fit or any copy failed — every range reserved for the tile is released and the
/// caller falls back to fresh buffers for the whole tile. Mixing pooled and fresh slices
/// per group would double the slice bookkeeping for no steady-state gain, since the
/// common case fits entirely.
///
/// Growth counts 2 into `Step::UploadAllocs` (one vertex + one index arena); a fully
/// pooled tile counts nothing.
pub(super) unsafe fn commit_tile_groups(
    renderer: &mut Renderer,
    groups: UploadGroups<'_>,
) -> Option<TileArenaSlices> {
    use crate::tess::{fill, ribbon, stroke};
    let sized = [
        (groups.flat, group_bytes(groups.flat)),
        (groups.lines, group_bytes(groups.lines)),
        (groups.ribbons, group_bytes(groups.ribbons)),
    ];
    // A group with no bytes gets a placeholder slice so the tile still carries all three; a
    // group WITH bytes that reserved nothing means the tile does not fit.
    let reserved = sized.map(|(meshes, bytes)| {
        if bytes == (0, 0) {
            Some(PooledPacked::placeholder())
        } else {
            reserve_group(renderer, meshes, bytes)
        }
    });
    let slices = match reserved {
        [Some(flat), Some(lines), Some(ribbons)] => TileArenaSlices { flat, lines, ribbons },
        partial => {
            for packed in partial.iter().flatten() {
                renderer.upload_pool.release(&packed.range());
            }
            return None;
        }
    };
    let (fv, fi) = pack_group(fill::FLOATS_PER_VERTEX, groups.flat);
    let (lv, li) = pack_group(stroke::FLOATS_PER_VERTEX, groups.lines);
    let (rv, ri) = pack_group(ribbon::FLOATS_PER_VERTEX, groups.ribbons);
    let committed = (fv.is_empty() || commit_bytes(renderer, &slices.flat, &fv, &fi))
        && (lv.is_empty() || commit_bytes(renderer, &slices.lines, &lv, &li))
        && (rv.is_empty() || commit_bytes(renderer, &slices.ribbons, &rv, &ri));
    if !committed {
        for range in slices.ranges() {
            renderer.upload_pool.release(&range);
        }
        return None;
    }
    Some(slices)
}

/// Destroy every arena. Called from the renderer's `Drop`, after the device goes idle —
/// the same contract as every other buffer the renderer owns.
pub(super) unsafe fn destroy_pool(pool: &mut UploadPool, device: &ash::Device) {
    for arena in pool.arenas.drain(..).flatten() {
        arena.vertices.destroy(device);
        arena.indices.destroy(device);
    }
}
