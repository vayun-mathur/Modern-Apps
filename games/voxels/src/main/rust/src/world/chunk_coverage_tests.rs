//! Chunk/section and ChunkMap edges: meta packing, out-of-range, pending saves.

use super::*;
use crate::world::chunk_map::ChunkMap;
use crate::world::block::Block;

fn temp(tag: &str) -> String {
    let dir = std::env::temp_dir().join(format!("voxels_chunk_{tag}_{}", std::process::id()));
    let _ = std::fs::create_dir_all(&dir);
    dir.to_string_lossy().into_owned()
}

#[test]
fn section_and_chunk_edges() {
    let mut sec = BlockSection::empty();
    assert!(sec.is_empty());
    sec.set(1, 2, 3, 5);
    sec.set(1, 2, 3, 5); // same id twice: still one non-air cell
    assert_eq!(sec.non_air, 1);
    assert_eq!(sec.get(1, 2, 3), 5);
    sec.set_meta(1, 2, 3, 7);
    assert_eq!(sec.get_meta(1, 2, 3), 7);
    sec.set(1, 2, 3, 0); // clearing a cell clears its meta too
    assert!(sec.is_empty());
    assert_eq!(sec.get_meta(1, 2, 3), 0);
    // Zero meta on a fresh section allocates nothing and reads back zero.
    let mut sec2 = BlockSection::empty();
    sec2.set_meta(0, 0, 0, 0);
    assert_eq!(sec2.get_meta(0, 0, 0), 0);

    let mut c = Chunk::new(ChunkPos(-2, 3));
    assert!(!c.generated && c.mesh_dirty);
    assert_eq!(c.get_block(0, CHUNK_HEIGHT, 0), 0, "above the world is air");
    assert_eq!(c.get_meta(0, CHUNK_HEIGHT, 0), 0);
    c.set_block(0, CHUNK_HEIGHT, 0, 9); // ignored, no panic
    c.set_block(0, 0, 0, 0); // air into an absent section: still absent
    assert!(c.sections[0].is_none());
    c.set_block_meta(3, 65, 3, Block::StoneSlab as u16, 4);
    assert_eq!(c.get_block(3, 65, 3), Block::StoneSlab as u16);
    assert_eq!(c.get_meta(3, 65, 3), 4);
    assert!(c.dirty && c.mesh_dirty);
    // ChunkPos maths around the origin (negative coords round toward -infinity).
    assert_eq!(ChunkPos::from_world(-1, -17), ChunkPos(-1, -2));
    assert_eq!(ChunkPos::from_world(15, 16), ChunkPos(0, 1));
    assert_eq!(ChunkPos(-2, 3).world_origin(), (-32, 48));
}

#[test]
fn chunk_map_world_edges_and_tint() {
    let mut map = ChunkMap::new(4, temp("edges"));
    map.render_distance = 1;
    assert!(map.is_empty());
    assert_eq!(map.get_block_world(0, -1, 0), 0);
    assert_eq!(map.get_block_world(0, CHUNK_HEIGHT as i32, 0), 0);
    assert!(!map.set_block_world(0, -1, 0, 9));
    assert!(!map.set_block_world(0, CHUNK_HEIGHT as i32, 0, 9));
    assert!(map.set_block_world(-3, 100, -3, 9));
    assert_eq!(map.get_block_world(-3, 100, -3), 9);
    assert_eq!(map.get(ChunkPos::from_world(-3, -3)).map(|c| c.pos), Some(ChunkPos(-1, -1)));
    assert!(map.get_mut(ChunkPos(99, 99)).is_none());
    assert!(!map.is_empty() && map.len() >= 1);
    assert_eq!(map.dim(), 0);
    assert_eq!(ChunkMap::new_dim(4, temp("nether"), 1).dim(), 1);
    // Grass tint is a colour: finite, in range, deterministic.
    let t = map.grass_tint(10, -20);
    assert!(t.iter().all(|v| v.is_finite() && (0.0..=2.0).contains(v)));
    assert_eq!(t, map.grass_tint(10, -20));
    // solid_at/surface_below agree on a slab placed in the map.
    map.set_block_meta_world(0, 100, 0, Block::StoneSlab as u16, 0);
    assert!(map.solid_at(0.5, 100.25, 0.5), "the slab's lower half is solid");
    assert!(!map.solid_at(0.5, 100.75, 0.5), "its upper half is air");
    assert_eq!(map.surface_below(0.5, 0.5, 101.0, 3), Some(100.5));
    assert_eq!(map.surface_below(0.5, 0.5, 90.0, 3), None, "nothing at or below the ceiling there");
    // discard_pending drops unloaded edits without writing them.
    map.discard_pending();
    assert_eq!(map.pending_len(), 0);
}
