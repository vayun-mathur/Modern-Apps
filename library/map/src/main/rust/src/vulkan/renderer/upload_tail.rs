//! Pooled upload tail: bookkeeping + tile assembly shared by both upload paths.
//!
//! Split from `upload.rs` (file-length limit): the `first_index` fan-out (layer slices,
//! region/traffic/carriageway lookups), the fresh-vs-pooled tile assembly, and the
//! arena-aware retire/drop helpers. `upload()` in `upload.rs` owns the mesh collection,
//! the pooled commit attempt and the fresh fallback; everything below consumes the
//! winning path's pools. The logic itself is unchanged from the pre-pool tail — only
//! the pool shapes (`(buffer, offset)` pairs) are new.
use super::{
    BuildingBuffers, CarriagewayBuffers, LayerBuffers, RegionBuffers, Renderer, ResidentTile,
    TerrainBuffers, TrafficBuffers, FRAMES_IN_FLIGHT,
};
use crate::tile::geometry::TileMesh;
use crate::tile::select;
use crate::vulkan::buffers::Buffer;
use ash::vk;
use std::collections::HashSet;

/// One pooled pool's bind inputs: the arena buffer pair, the byte offsets of this tile's
/// ranges in them, and the rebased per-mesh `firstIndex` list — the same three things a
/// fresh `(Buffer, Buffer, Vec<u32>)` carries, with offsets added.
///
/// Handles are raw `vk::Buffer`s (not `buffers::Buffer`): a tile owns *ranges* of pool
/// buffers, never the buffers, so there is no owned `Buffer` to hold — exactly why
/// `ResidentTile` pools are `(raw handle, offset)` pairs and `is_arena_buffer` compares
/// handles.
pub(super) struct PooledPair {
    pub vbuf: vk::Buffer,
    pub voff: vk::DeviceSize,
    pub ibuf: vk::Buffer,
    pub ioff: vk::DeviceSize,
    pub first: Vec<u32>,
}

/// The three pools' bind inputs on the pooled path, or `None` on the fresh path.
pub(super) type PooledBuffers = Option<(PooledPair, PooledPair, PooledPair)>;

/// The three pools' fresh buffers on the fallback path, or `None` on the pooled path.
/// Each is the `(vertices, indices, first_index)` triple [`upload_packed`] returns.
pub(super) type FreshPools = (
    Option<(Buffer, Buffer, Vec<u32>)>,
    Option<(Buffer, Buffer, Vec<u32>)>,
    Option<(Buffer, Buffer, Vec<u32>)>,
);

/// Resolve the per-group `firstIndex` lists from whichever path produced the pools.
///
/// The pooled commit rebases exactly like `upload_packed`, so the bookkeeping below
/// reads one shape. Borrows the pooled pairs plus each fresh pool separately (not the
/// owned `FreshPools` tuple): the pools are moved into `assemble_tile` right after,
/// and a tuple borrow would hold the move hostage.
pub(super) fn first_index_lists(
    pooled_buffers: &PooledBuffers,
    flat: &Option<(Buffer, Buffer, Vec<u32>)>,
    lines: &Option<(Buffer, Buffer, Vec<u32>)>,
    ribbons: &Option<(Buffer, Buffer, Vec<u32>)>,
) -> (Vec<u32>, Vec<u32>, Vec<u32>) {
    match pooled_buffers {
        Some((f, l, r)) => (f.first.clone(), l.first.clone(), r.first.clone()),
        None => (
            flat.as_ref().map(|(_, _, f)| f.clone()).unwrap_or_default(),
            lines.as_ref().map(|(_, _, f)| f.clone()).unwrap_or_default(),
            ribbons.as_ref().map(|(_, _, f)| f.clone()).unwrap_or_default(),
        ),
    }
}

/// Build the tile's layer slices from the mesh list and the per-group `firstIndex` lists.
///
/// `first_index` here is the *draw* slice offset (an index count into the pool); pooled
/// and fresh pools rebase it identically, so one lookup serves both — the vertex/index
/// *buffers* (and their bind offsets) are the only thing that differs, resolved into
/// `tile.flat/lines/ribbons` by the caller.
pub(super) fn build_layers(
    mesh: &TileMesh,
    flat_layer_at: &[usize],
    line_layer_at: &[usize],
    flat_first: &[u32],
    line_first: &[u32],
) -> Vec<LayerBuffers> {
    use crate::style::LayerKind;
    let mut flat_layer_iter = flat_layer_at.iter();
    let mut line_layer_iter = line_layer_at.iter();
    let mut layers: Vec<LayerBuffers> = Vec::with_capacity(mesh.meshes.len());
    for layer_mesh in &mesh.meshes {
        if layer_mesh.indices.is_empty() {
            continue;
        }
        // The `*_at` vectors parallel their pool's mesh order: the nth fill-kind mesh is
        // the nth entry of the flat pool's first-index list, and likewise for lines.
        let first_index = match layer_mesh.kind {
            LayerKind::Fill => {
                let Some(at) = flat_layer_iter.next() else {
                    continue;
                };
                let Some(first) = flat_first.get(*at) else {
                    continue;
                };
                *first
            }
            LayerKind::Line => {
                let Some(at) = line_layer_iter.next() else {
                    continue;
                };
                let Some(first) = line_first.get(*at) else {
                    continue;
                };
                *first
            }
            LayerKind::Symbol => continue,
        };
        layers.push(LayerBuffers {
            layer_index: layer_mesh.layer_index,
            kind: layer_mesh.kind,
            first_index,
            index_count: layer_mesh.indices.len() as u32,
            color_override: layer_mesh.color_override,
            lane: layer_mesh.lane,
        });
    }
    layers
}

/// Build the region-mask slices from the flat pool's `firstIndex` list.
///
/// Empty regions never reach the pool (`upload()` skips them when packing), so the
/// `*_at` index runs over non-empty regions only — a parallel cursor like
/// `build_carriageways`, never the enumeration position. Indexing by position was the
/// FindFamily SIGABRT: the first empty segment made `*_at` shorter than the mesh list
/// and `[0]` landed on an empty vec.
pub(super) fn build_regions(
    mesh: &mut TileMesh,
    flat_region_at: &[usize],
    flat_first: &[u32],
) -> Vec<RegionBuffers> {
    let mut regions = Vec::with_capacity(mesh.regions.len());
    let mut ri = 0usize;
    for region in &mut mesh.regions {
        if region.indices.is_empty() {
            continue;
        }
        let Some(&at) = flat_region_at.get(ri) else {
            continue;
        };
        ri += 1;
        let Some(&first_index) = flat_first.get(at) else {
            continue;
        };
        regions.push(RegionBuffers {
            id: region.id,
            first_index,
            index_count: region.indices.len() as u32,
            rings: std::mem::take(&mut region.rings),
            area: region.area,
            level: region.level,
        });
    }
    regions
}

/// Build the live-traffic slices from the line pool's `firstIndex` list.
///
/// Same cursor contract as [`build_regions`]: empty segments are skipped at pack time,
/// so the lookup runs over non-empty segments only.
pub(super) fn build_traffic(
    mesh: &TileMesh,
    line_traffic_at: &[usize],
    line_first: &[u32],
) -> Vec<TrafficBuffers> {
    let mut traffic = Vec::with_capacity(mesh.traffic.len());
    let mut ti = 0usize;
    for segment in &mesh.traffic {
        if segment.indices.is_empty() {
            continue;
        }
        let Some(&at) = line_traffic_at.get(ti) else {
            continue;
        };
        ti += 1;
        let Some(&first_index) = line_first.get(at) else {
            continue;
        };
        traffic.push(TrafficBuffers {
            id: segment.id,
            first_index,
            index_count: segment.indices.len() as u32,
        });
    }
    traffic
}

/// Build the carriageway slices from the ribbon pool's `firstIndex` list.
pub(super) fn build_carriageways(
    mesh: &TileMesh,
    ribbon_first: &[u32],
) -> Vec<CarriagewayBuffers> {
    let mut carriageways = Vec::with_capacity(mesh.carriageways.len());
    let mut ri = 0usize;
    for road in &mesh.carriageways {
        if road.indices.is_empty() {
            continue;
        }
        let Some(&first_index) = ribbon_first.get(ri) else {
            continue;
        };
        ri += 1;
        carriageways.push(CarriagewayBuffers {
            layer_index: road.layer_index,
            lanes: road.lanes,
            split: road.split,
            oneway: road.oneway,
            first_index,
            index_count: road.indices.len() as u32,
        });
    }
    carriageways
}

/// Assemble the resident tile from the winning path's pools.
///
/// Fresh pools bind at offset 0; pooled pools bind their arena ranges. The `(handle,
/// offset)` pairs land on the tile, the owned fresh buffers (fresh path only) land in
/// `fresh_owned`, and the draws read the pairs back without caring which path produced
/// them. A pool with no packed meshes is `None` on either path — nothing to draw,
/// exactly as the per-mesh path behaved.
#[allow(clippy::too_many_arguments)]
pub(super) fn assemble_tile(
    pooled_buffers: &PooledBuffers,
    fresh: FreshPools,
    layers: Vec<LayerBuffers>,
    regions: Vec<RegionBuffers>,
    traffic: Vec<TrafficBuffers>,
    carriageways: Vec<CarriagewayBuffers>,
    buildings: Option<BuildingBuffers>,
    terrain: Option<TerrainBuffers>,
    mesh: &mut TileMesh,
    uploaded_at: f32,
) -> ResidentTile {
    // Owned buffers follow the fresh triples out (fresh path only; the pooled path
    // holds none). `first_index` was already fanned out above — only ownership moves.
    let mut fresh_owned: Vec<Buffer> = Vec::new();
    let (flat_handles, lines_handles, ribbons_handles) = match pooled_buffers {
        Some((f, l, r)) => (
            Some((f.vbuf, f.voff, f.ibuf, f.ioff)),
            Some((l.vbuf, l.voff, l.ibuf, l.ioff)),
            Some((r.vbuf, r.voff, r.ibuf, r.ioff)),
        ),
        None => {
            let mut flat_h = None;
            let mut lines_h = None;
            let mut ribbons_h = None;
            let (ff, lf, rf) = fresh;
            if let Some((v, i, _)) = ff {
                flat_h = Some((v.buffer, 0, i.buffer, 0));
                fresh_owned.push(v);
                fresh_owned.push(i);
            }
            if let Some((v, i, _)) = lf {
                lines_h = Some((v.buffer, 0, i.buffer, 0));
                fresh_owned.push(v);
                fresh_owned.push(i);
            }
            if let Some((v, i, _)) = rf {
                ribbons_h = Some((v.buffer, 0, i.buffer, 0));
                fresh_owned.push(v);
                fresh_owned.push(i);
            }
            (flat_h, lines_h, ribbons_h)
        }
    };
    ResidentTile {
        flat: flat_handles,
        lines: lines_handles,
        ribbons: ribbons_handles,
        fresh_owned,
        arena_ranges: Vec::new(),
        layers,
        buildings,
        terrain,
        regions,
        traffic,
        carriageways,
        yellow_centre: mesh.yellow_centre,
        labels: std::mem::take(&mut mesh.labels),
        arrows: std::mem::take(&mut mesh.arrows),
        heightmap: mesh.heightmap.take(),
        z: mesh.z,
        x: mesh.x,
        y: mesh.y,
        // Stamp the tile with the latest frame clock so the fade measures from the
        // moment its GPU buffers landed. Before the first frame there is no clock yet;
        // 0.0 makes `now - uploaded_at` large, so a startup tile is fully opaque at once
        // (nothing is under it to fade over anyway).
        uploaded_at,
        generation: mesh.generation,
        toggle_stamp: mesh.toggle_stamp.clone(),
    }
}

/// Whether `buffer` is an upload-arena buffer (owned by the pool, never destroyed
/// through a tile) rather than a fresh per-tile buffer.
///
/// Kept for the doc contract; with `fresh_owned` the retire path no longer needs it
/// (fresh pools destroy via ownership, arena ranges free nothing). Offset 0 is NOT a
/// discriminator: the first tile in an arena starts at 0, so only ownership is exact.
pub(super) fn is_arena_buffer(pool: &super::upload_pool::UploadPool, b: vk::Buffer) -> bool {
    pool.owns_buffer(b)
}

/// Free a resident tile's owned GPU buffers after its grace period.
///
/// `fresh_owned` destroys its buffers; arena ranges are released by the caller, which holds
/// the pool. Buildings/terrain are always fresh. Takes only the tile + device (never the
/// renderer), so the `retain_mut` in `upload.rs` borrows just the retiring queue.
pub(super) fn free_retired_tile_owned(tile: &ResidentTile, device: &ash::Device) {
    // SAFETY: the device is idle past the grace count (see `collect_retired`).
    unsafe {
        // Draw slices are plain indices into the pools — nothing per draw to free.
        // Each pool is one pair no matter how many meshes packed into it.
        for owned in &tile.fresh_owned {
            owned.destroy(device);
        }
        if let Some(buildings) = &tile.buildings {
            buildings.vertices.destroy(device);
            buildings.indices.destroy(device);
        }
        if let Some(terrain) = &tile.terrain {
            terrain.vertices.destroy(device);
            terrain.indices.destroy(device);
        }
    }
}

/// Legacy renderer-taking form, kept for the teardown path (`free_tile_set`), which
/// already holds no conflicting borrow. Forwards to [`free_retired_tile_owned`].
pub(super) fn free_retired_tile(
    renderer: &Renderer,
    tile: &ResidentTile,
    device: &ash::Device,
) {
    let _ = renderer;
    free_retired_tile_owned(tile, device);
}

/// Free a whole resident set (renderer teardown): `fresh_owned` destroys, arena ranges
/// do not — the arenas die with the pool afterwards. Same ownership rule as
/// [`free_retired_tile`], minus the grace count (the device is already idle).
pub(super) fn free_tile_set(
    renderer: &Renderer,
    tiles: impl IntoIterator<Item = ResidentTile>,
    device: &ash::Device,
) {
    for tile in tiles {
        free_retired_tile(renderer, &tile, device);
    }
}

/// GPU bytes a resident tile holds: its arena ranges plus every buffer it owns.
pub(super) fn tile_gpu_bytes(tile: &ResidentTile) -> u64 {
    let ranges: u64 = tile.arena_ranges.iter().map(|r| r.bytes()).sum();
    let fresh: u64 = tile.fresh_owned.iter().map(|b| b.size).sum();
    let buildings = tile
        .buildings
        .as_ref()
        .map_or(0, |b| b.vertices.size + b.indices.size);
    let terrain = tile
        .terrain
        .as_ref()
        .map_or(0, |t| t.vertices.size + t.indices.size);
    ranges + fresh + buildings + terrain
}

/// Drop resident tiles that are neither wanted nor useful as a stand-in, and enforce the
/// residency cap and byte budget (see [`select::evict`]).
///
/// `keep` is the visible tiles and their ancestors, in the order [`select::resident_set`]
/// produced them — coarsest first. `visible` is needed separately because descendants cannot
/// be enumerated into a keep list without naming tiles that were never fetched; they are
/// recognised here, against what is actually resident.
pub fn retain(
    renderer: &mut Renderer,
    keep: &[u64],
    visible: &[select::TileId],
    cap: usize,
    byte_budget: u64,
) {
    use std::collections::HashSet;
    let visible_keys: HashSet<u64> = visible.iter().map(|t| t.key()).collect();
    let wanted: HashSet<u64> = keep.iter().copied().collect();

    let mut ranked: Vec<(u8, u64, u64)> = renderer
        .tiles
        .iter()
        .map(|(&key, tile)| {
            let rank = if visible_keys.contains(&key) {
                0 // on screen at its own zoom; evicting this is the blank frame itself
            } else if wanted.contains(&key) {
                1 // an ancestor: one coarse tile covers many fine ones, so cheap to hold
            } else if select::stands_in_for_visible(key, visible, select::DESCENDANT_DEPTH) {
                2 // a descendant: only covers a fraction of the screen, so the first to go
            } else {
                3 // unrelated to anything on screen
            };
            (rank, key, tile_gpu_bytes(tile))
        })
        .collect();
    let doomed = select::evict(&mut ranked, cap, byte_budget);
    if doomed.is_empty() {
        return;
    }
    renderer.bump_residency();
    for key in doomed {
        if let Some(tile) = renderer.tiles.remove(&key) {
            renderer.retire(tile);
        }
    }
}
