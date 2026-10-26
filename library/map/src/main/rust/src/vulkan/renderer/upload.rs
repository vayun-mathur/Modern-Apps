use super::Renderer;
use super::upload_tail;
use super::{BuildingBuffers, FRAMES_IN_FLIGHT, ResidentTile, TerrainBuffers};
use crate::tile::geometry::TileMesh;
use crate::tile::select;
use crate::vulkan::buffers::Buffer;
use crate::vulkan::context::Context;
use crate::vulkan::images::{AtlasSet, SampledImage};
use ash::vk;

impl Renderer {
    /// Upload a tile's geometry, replacing anything already resident for it.
    ///
    /// Same-format meshes share one vertex + one index buffer per pool (see
    /// [`upload_packed`](crate::vulkan::buffers::upload_packed)): fills (+ the region-mask
    /// shapes, which are position-only fills) in [`flat`](ResidentTile::flat), strokes (+ the
    /// traffic segments, which ride the stroke vertex) in [`lines`](ResidentTile::lines), and
    /// ribbon carriageways in [`ribbons`](ResidentTile::ribbons). Buildings and terrain are
    /// already one mesh per tile and keep their own pair. A dense tile costs ~5 allocations
    /// instead of ~60-100, on the same Choreographer callback as before.
    pub fn upload(&mut self, key: u64, mesh: &TileMesh) -> Result<(), String> {
        use crate::style::LayerKind;
        use crate::tess::{fill, ribbon, stroke};
        use crate::vulkan::buffers::upload_packed;
        use super::upload_pool::UploadPool;
        // Read the context handles once, before the `&mut self` pooled commit below:
        // `commit_tile_groups` grows the pool through `&mut self`, so no borrow of
        // `self.context` may live across it. Copied values, never borrows.
        let instance = self.context.instance.clone();
        let physical = self.context.physical_device;
        let device = self.context.device.clone();
        // Fills and region shapes share the position-only flat pool; collect which flat mesh
        // each belongs to so the draw slices line up with the packed order below.
        let mut flat_meshes: Vec<(&[f32], &[u32])> = Vec::new();
        let mut flat_region_at: Vec<usize> = Vec::new();
        let mut flat_layer_at: Vec<usize> = Vec::new();
        for layer_mesh in &mesh.meshes {
            if layer_mesh.kind != LayerKind::Fill || layer_mesh.indices.is_empty() {
                continue;
            }
            flat_layer_at.push(flat_meshes.len());
            flat_meshes.push((&layer_mesh.vertices, &layer_mesh.indices));
        }
        for region in &mesh.regions {
            if region.indices.is_empty() {
                continue;
            }
            flat_region_at.push(flat_meshes.len());
            flat_meshes.push((&region.vertices, &region.indices));
        }
        // Strokes and traffic segments share the 7-float stroke pool.
        let mut line_meshes: Vec<(&[f32], &[u32])> = Vec::new();
        let mut line_layer_at: Vec<usize> = Vec::new();
        let mut line_traffic_at: Vec<usize> = Vec::new();
        for layer_mesh in &mesh.meshes {
            if layer_mesh.kind != LayerKind::Line || layer_mesh.indices.is_empty() {
                continue;
            }
            line_layer_at.push(line_meshes.len());
            line_meshes.push((&layer_mesh.vertices, &layer_mesh.indices));
        }
        for segment in &mesh.traffic {
            if segment.indices.is_empty() {
                continue;
            }
            line_traffic_at.push(line_meshes.len());
            line_meshes.push((&segment.vertices, &segment.indices));
        }
        let mut ribbon_meshes: Vec<(&[f32], &[u32])> = Vec::new();
        for road in &mesh.carriageways {
            if road.indices.is_empty() {
                continue;
            }
            ribbon_meshes.push((&road.vertices, &road.indices));
        }
        // Pooled staging first: pack the three mesh groups into the upload arenas, which
        // costs copies but no `vkAllocateMemory`. `None` (no room, copy failed) falls
        // through to fresh buffers below, counting the allocation — so `Step::UploadAllocs`
        // reads zero in the steady state and names every fallback.
        //
        // SAFETY: arena ranges are bump-allocated and never reused for another tile, so no
        // live draw can reference the ranges this commit writes; draws bind the committed
        // offsets below, which outlive the tile like every other resident buffer.
        //
        // The pooled path needs the same `first_index` bookkeeping the fresh path does
        // below (layer slices, region/traffic/carriageway lookups), so both paths funnel
        // through one tail: `pooled` carries arena slices, `fresh_*` carry fresh buffers,
        // and exactly one of them is `Some`. The pooled slices name arena ranges; the
        // draws bind them with byte offsets (see `pool_draw_buffers`), which is the only
        // place the two paths differ.
        let pooled = unsafe {
            super::upload_pool::commit_tile_groups(
                self,
                super::upload_pool::UploadGroups {
                    flat: &flat_meshes,
                    lines: &line_meshes,
                    ribbons: &ribbon_meshes,
                },
            )
        };
        // SAFETY: uploading fresh GPU buffers for a tile whose previous buffers (if any)
        // are retired below, after every buffer below has landed. Skipped entirely on
        // the pooled path — that is the whole point. Borrows `&` (not `&mut self`)
        // locals: `instance`/`physical`/`device` above are owned clones.
        let (flat, lines, ribbons) = match &pooled {
            Some(_) => (None, None, None),
            None => {
                UploadPool::record_allocs(self, 6);
                let flat = unsafe {
                    upload_packed(
                        &instance,
                        physical,
                        &device,
                        fill::FLOATS_PER_VERTEX,
                        &flat_meshes,
                    )?
                };
                let lines = unsafe {
                    upload_packed(
                        &instance,
                        physical,
                        &device,
                        stroke::FLOATS_PER_VERTEX,
                        &line_meshes,
                    )?
                };
                let ribbons = unsafe {
                    upload_packed(
                        &instance,
                        physical,
                        &device,
                        ribbon::FLOATS_PER_VERTEX,
                        &ribbon_meshes,
                    )?
                };
                (flat, lines, ribbons)
            }
        };
        // The draws bind arena ranges with byte offsets: the pooled slices name
        // (arena, offset) pairs, while the fresh path names whole buffers at offset 0.
        // `PooledPair` (see `upload_tail`) carries one pool's bind inputs either way.
        // Handles come out by value through `arena_buffers` — no borrow of the pool
        // lives past this closure.
        let pooled_buffers = pooled.as_ref().map(|s| {
            let pool = &self.upload_pool;
            let (flat_v, flat_i) = pool.arena_buffers(s.flat.arena);
            let (lines_v, lines_i) = pool.arena_buffers(s.lines.arena);
            let (ribbons_v, ribbons_i) = pool.arena_buffers(s.ribbons.arena);
            (
                upload_tail::PooledPair {
                    vbuf: flat_v,
                    voff: s.flat.vert_offset,
                    ibuf: flat_i,
                    ioff: s.flat.index_offset,
                    first: s.flat.first_index.clone(),
                },
                upload_tail::PooledPair {
                    vbuf: lines_v,
                    voff: s.lines.vert_offset,
                    ibuf: lines_i,
                    ioff: s.lines.index_offset,
                    first: s.lines.first_index.clone(),
                },
                upload_tail::PooledPair {
                    vbuf: ribbons_v,
                    voff: s.ribbons.vert_offset,
                    ibuf: ribbons_i,
                    ioff: s.ribbons.index_offset,
                    first: s.ribbons.first_index.clone(),
                },
            )
        });
        let (flat_first_src, line_first_src, ribbon_first_src) =
            upload_tail::first_index_lists(&pooled_buffers, &flat, &lines, &ribbons);
        let layers = upload_tail::build_layers(
            mesh,
            &flat_layer_at,
            &line_layer_at,
            &flat_first_src,
            &line_first_src,
        );
        let flat_first: Vec<u32> = flat_first_src;
        let line_first: Vec<u32> = line_first_src;
        let regions = upload_tail::build_regions(mesh, &flat_region_at, &flat_first);
        let traffic = upload_tail::build_traffic(mesh, &line_traffic_at, &line_first);
        let ribbon_first: Vec<u32> = ribbon_first_src;
        let carriageways = upload_tail::build_carriageways(mesh, &ribbon_first);
        // The tile's 3D buildings, if any: one combined mesh, uploaded fresh — 2 allocs
        // when present, counted below. Buildings and terrain stay on the fresh path
        // deliberately: they are single meshes per tile (not packed groups), rare (z14+
        // with extruded detail only), and pooling them would complicate the arena
        // bookkeeping for no steady-state gain — the packed groups are the per-frame cost.
        let buildings = if mesh.buildings.indices.is_empty() {
            None
        } else {
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &mesh.buildings.vertices,
                )?;
                let indices = match Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &mesh.buildings.indices,
                ) {
                    Ok(buffer) => {
                        UploadPool::record_allocs(self, 2);
                        buffer
                    }
                    Err(e) => {
                        vertices.destroy(&self.context.device);
                        return Err(e);
                    }
                };
                Some(BuildingBuffers {
                    vertices,
                    indices,
                    index_count: mesh.buildings.indices.len() as u32,
                })
            }
        };
        // The tile's terrain grid, if any: one combined mesh, uploaded fresh like the
        // buildings above (see that comment for why these stay off the pooled path).
        let terrain = if mesh.terrain.indices.is_empty() {
            None
        } else {
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &mesh.terrain.vertices,
                )?;
                let indices = match Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &mesh.terrain.indices,
                ) {
                    Ok(buffer) => {
                        UploadPool::record_allocs(self, 2);
                        buffer
                    }
                    Err(e) => {
                        vertices.destroy(&self.context.device);
                        return Err(e);
                    }
                };
                Some(TerrainBuffers {
                    vertices,
                    indices,
                    index_count: mesh.terrain.indices.len() as u32,
                })
            }
        };
        // A same-key replacement (toggle/palette re-tessellation) keeps the original stamp:
        // its pixels were already on screen, so restarting the fade would flash it transparent.
        let uploaded_at = self
            .tiles
            .get(&key)
            .map(|previous| previous.uploaded_at)
            .unwrap_or_else(|| self.last_camera.map(|c| c.time_seconds).unwrap_or(0.0));
        let tile = upload_tail::assemble_tile(
            &pooled_buffers,
            (flat, lines, ribbons),
            layers,
            regions,
            traffic,
            carriageways,
            buildings,
            terrain,
            mesh,
            uploaded_at,
        );
        if let Some(previous) = self.tiles.insert(key, tile) {
            self.retire(previous);
        }
        self.bump_residency();
        Ok(())
    }

    /// Hold a tile's buffers until every in-flight frame that might reference them has
    /// finished.
    ///
    /// Freeing them immediately is the classic Vulkan use-after-free: a command buffer
    /// submitted last frame can still be executing, and destroying its vertex buffer is
    /// undefined behaviour that usually looks like corrupted geometry rather than a crash.
    pub(super) fn retire(&mut self, tile: ResidentTile) {
        self.retiring.push((FRAMES_IN_FLIGHT, tile));
    }

    /// Drop resident tiles that are neither wanted nor useful as a stand-in, and enforce
    /// the residency cap. Split into `upload_tail` (file-length limit); see it for the
    /// ranking contract — this only forwards, so the call sites are unchanged.
    pub fn retain(&mut self, keep: &[u64], visible: &[select::TileId], cap: usize) {
        upload_tail::retain(self, keep, visible, cap);
    }

    /// Free anything whose grace period has expired. Split into `upload_tail`
    /// (file-length limit): `fresh_owned` destroys, arena ranges free nothing — see
    /// `free_retired_tile` for the ownership rule.
    pub(super) fn collect_retired(&mut self) {
        self.collect_moon_retired();
        let device = self.context.device.clone();
        self.retiring.retain_mut(|(remaining, tile)| {
            if *remaining > 0 {
                *remaining -= 1;
                return true;
            }
            upload_tail::free_retired_tile_owned(tile, &device);
            false
        });
        // Transient per-frame symbol buffers retire on the same grace count, in
        // their own queue — no device_wait_idle stall on the frame path.
        // `device` is the owned clone above, so borrows are explicit: `destroy`
        // takes `&Device`, not `Device`.
        self.transients.retain_mut(|t| {
            if t.frames > 0 {
                t.frames -= 1;
                return true;
            }
            unsafe {
                t.vbuf.destroy(&device);
                t.ibuf.destroy(&device);
            }
            false
        });
    }
}

/// Upload the process-global glyph atlas and allocate its descriptor set.
///
/// Separate from [`Renderer::new`] so the error paths read linearly. Called once
/// at startup; the bytes come from `tile::glyph::atlas()` (SDF R8 built from the
/// bundled Noto Sans at first use).
///
/// # Safety
///
/// Same rules as the surrounding constructors: live device, idle queue.
pub(super) unsafe fn try_upload_glyph_atlas(
    context: &Context,
    atlas_set: &AtlasSet,
    command_pool: vk::CommandPool,
) -> Result<(SampledImage, vk::DescriptorSet), String> {
    use crate::tile::glyph;
    if !glyph::fonts_staged() {
        return Err("bundled fonts are not valid TTFs".into());
    }
    let atlas = glyph::atlas();
    let image = SampledImage::upload(
        &context.instance,
        context.physical_device,
        &context.device,
        context.queue,
        context.queue_family_index,
        command_pool,
        &atlas.pixels,
        glyph::ATLAS_PX,
        glyph::ATLAS_PX,
        vk::Format::R8_UNORM,
    )?;
    let set = atlas_set.allocate(&context.device, &image)?;
    Ok((image, set))
}

/// Upload the process-global POI sprite sheet and allocate its descriptor set.
///
/// [`try_upload_glyph_atlas`]'s counterpart, and the second of the two sets
/// [`AtlasSet`]'s pool is sized for. The sheet is already RGBA8, so
/// [`SampledImage::upload`] takes it unchanged — no expansion step like the glyph
/// atlas's R8 one.
///
/// # Safety
///
/// Same rules as the surrounding constructors: live device, idle queue.
pub(super) unsafe fn try_upload_sprite_atlas(
    context: &Context,
    atlas_set: &AtlasSet,
    command_pool: vk::CommandPool,
) -> Result<(SampledImage, vk::DescriptorSet), String> {
    use crate::tile::sprite;
    let atlas = sprite::atlas();
    // An empty atlas is what `sprite::atlas()` leaves behind when the sheet will not
    // decode; uploading a zero-sized image would fail in the driver instead of here.
    if atlas.is_empty() {
        return Err("the sprite sheet carries no icons".into());
    }
    let image = SampledImage::upload(
        &context.instance,
        context.physical_device,
        &context.device,
        context.queue,
        context.queue_family_index,
        command_pool,
        &atlas.pixels,
        atlas.width,
        atlas.height,
        vk::Format::R8G8B8A8_UNORM,
    )?;
    let set = atlas_set.allocate(&context.device, &image)?;
    Ok((image, set))
}
