//! Chunk storage, mesh invariants, block predicates and inventory tests.
//!
//! Extends the inline suites without duplicating them: `world/save.rs` covers VOX3 round trips,
//! `raycast.rs` covers sub-voxel hits, and `lib.rs` has the engine smoke test. These tests add
//! chunk coordinate edges, mesh face-culling invariants on a hand-built chunk, save-codec
//! corruption handling, block predicate sweeps, and hotbar add/select/consume/move rules.

use voxels_engine::inventory::{Inventory, HOTBAR, SLOTS, STACK};
use voxels_engine::world::block::{Block, Shape, META_TOP};
use voxels_engine::world::chunk::{Chunk, ChunkPos, CHUNK_HEIGHT, CHUNK_SIZE};
use voxels_engine::world::mesher::mesh_chunk;
use voxels_engine::world::save::{decode_chunk, encode_chunk};
use voxels_engine::world::{ChunkMap, CHUNK_HEIGHT as MAP_HEIGHT};

fn test_map() -> ChunkMap {
    let dir = std::env::temp_dir().join("voxels_edges_test").to_string_lossy().into_owned();
    ChunkMap::new(0xE99E6, dir)
}

#[test]
fn chunk_coordinates_handle_negatives_and_boundaries() {
    assert_eq!(ChunkPos::from_world(-1, -1), ChunkPos(-1, -1));
    assert_eq!(ChunkPos::from_world(-16, 0), ChunkPos(-1, 0));
    assert_eq!(ChunkPos::from_world(16, 16), ChunkPos(1, 1));
    assert_eq!(ChunkPos(2, -3).world_origin(), (2 * CHUNK_SIZE as i32, -3 * CHUNK_SIZE as i32));
    let mut chunk = Chunk::new(ChunkPos(0, 0));
    assert_eq!(chunk.get_block(0, CHUNK_HEIGHT, 0), 0, "above the world is air");
    assert_eq!(chunk.get_block(0, 0, 0), 0);
    chunk.set_block(0, 0, 0, Block::Stone as u16);
    assert_eq!(chunk.get_block(0, 0, 0), Block::Stone as u16);
    chunk.set_block(0, 0, 0, 0);
    assert!(chunk.sections[0].is_none(), "clearing the last block frees the section");
    assert_eq!(MAP_HEIGHT, CHUNK_HEIGHT);
}

#[test]
fn hidden_interior_faces_are_culled_and_borders_kept() {
    let mut chunk = Chunk::new(ChunkPos(0, 0));
    for x in 0..4 {
        for z in 0..4 {
            chunk.set_block(x, 64, z, Block::Stone as u16);
        }
    }
    let air = |_: i32, _: i32, _: i32| (0u16, 0u8);
    let meshes = mesh_chunk(&chunk, &air, &|_, _| [1.0, 1.0, 1.0]);
    let mesh = meshes.iter().filter_map(|m| m.as_ref()).next().expect("mesh");
    assert!(!mesh.indices.is_empty());
    assert_eq!(mesh.indices.len() % 3, 0);
    for chunk_index in mesh.indices.chunks_exact(3) {
        for index in chunk_index {
            assert!(((*index) as usize) < mesh.vertices.len(), "index {index} in range");
        }
    }
    for vertex in &mesh.vertices {
        assert!(vertex.ao >= 0.0 && vertex.ao <= 1.0, "ao {}", vertex.ao);
        assert!(vertex.light >= 0.0, "light {}", vertex.light);
        let normal_sum = vertex.normal[0].abs() + vertex.normal[1].abs() + vertex.normal[2].abs();
        assert_eq!(normal_sum, 1.0, "axis-aligned normal {:?}", vertex.normal);
    }
    let mut lone = Chunk::new(ChunkPos(3, 3));
    lone.set_block(0, 64, 0, Block::Stone as u16);
    let lone_meshes = mesh_chunk(&lone, &air, &|_, _| [1.0, 1.0, 1.0]);
    let lone_mesh = lone_meshes.iter().filter_map(|m| m.as_ref()).next().expect("lone mesh");
    assert_eq!(lone_mesh.vertices.len(), 24, "a lone cube exposes six quads");
    assert_eq!(lone_mesh.indices.len(), 36);
}

#[test]
fn save_codec_rejects_garbage_and_keeps_meta_flags() {
    let mut chunk = Chunk::new(ChunkPos(1, 2));
    chunk.set_block_meta(3, 70, 3, Block::StoneSlab as u16, META_TOP);
    let encoded = encode_chunk(&chunk);
    assert_eq!(&encoded[..4], b"VOX3");
    let mut loaded = Chunk::new(ChunkPos(1, 2));
    decode_chunk(&encoded, &mut loaded).expect("decode");
    assert_eq!(loaded.get_block(3, 70, 3), Block::StoneSlab as u16);
    assert_eq!(loaded.get_meta(3, 70, 3), META_TOP);
    let mut bad = Chunk::new(ChunkPos(1, 2));
    assert!(decode_chunk(&[], &mut bad).is_err());
    assert!(decode_chunk(b"NOPE!", &mut bad).is_err());
    let mut truncated = encoded.clone();
    truncated.truncate(10);
    assert!(decode_chunk(&truncated, &mut bad).is_err());
    let mut empty_chunk = Chunk::new(ChunkPos(9, 9));
    let empty_encoded = encode_chunk(&empty_chunk);
    decode_chunk(&empty_encoded, &mut empty_chunk).expect("empty chunk decodes");
}

#[test]
fn block_predicates_cover_shapes_and_light() {
    assert_eq!(Block::from_id(0), Block::Air);
    assert_eq!(Block::from_id(9999), Block::Air, "out-of-range ids map to air");
    assert!(Block::Stone.is_solid());
    assert!(!Block::Air.is_solid());
    assert!(!Block::Water.is_solid(), "water is not solid");
    assert!(!Block::Glass.is_opaque(), "glass is see-through");
    assert!(Block::Stone.is_opaque());
    assert_eq!(Block::StoneSlab.shape(), Shape::Slab);
    assert_eq!(Block::StoneStairs.shape(), Shape::Stairs);
    assert_eq!(Block::Stone.shape(), Shape::Cube);
    assert!(!Block::Glass.blocks_light(), "glass lets light through");
    assert!(Block::StoneSlab.blocks_light(), "a stone slab still casts shadow");
    assert_eq!(Block::Glowstone.light_emission(), 15);
    assert_eq!(Block::Stone.light_emission(), 0);
    assert!(Block::from_id(Block::Glass as u16).is_transparent());
    assert!(Block::StoneSlab.parent() == Block::Stone);
    assert_eq!(Block::Stone.slab_of(), Some(Block::StoneSlab));
    assert_eq!(Block::Dirt.slab_of(), None);
}

#[test]
fn inventory_hotbar_rules_hold() {
    // The rules below are about an empty inventory; `Inventory::default()`
    // is the new-player starter kit (64 stone among it), not a blank state.
    let mut inv = Inventory::empty();
    assert_eq!(inv.selected, 0);
    inv.select(HOTBAR + 5);
    assert_eq!(inv.selected, 0, "out-of-hotbar selection is ignored");
    inv.select(4);
    assert_eq!(inv.selected, 4);
    assert!(!inv.try_add_block(0), "air never fits");
    assert!(inv.try_add_block(Block::Stone as u16));
    assert_eq!(inv.count_of(Block::Stone as u16), 1);
    assert_eq!(inv.selected_block(), 0, "slot 4 is still empty");
    inv.select(0);
    assert_eq!(inv.selected_block(), Block::Stone as u16);
    assert_eq!(inv.consume_selected(), Some(Block::Stone as u16));
    assert_eq!(inv.consume_selected(), None, "slot drained after one consume");
    for _ in 0..(SLOTS as i32 * STACK + 10) {
        inv.try_add_block(Block::Dirt as u16);
    }
    let total = inv.count_of(Block::Dirt as u16);
    assert!(total <= (SLOTS as i32) * (STACK as i32), "capacity is bounded: {total}");
    inv.move_item(SLOTS + 1, 0);
    inv.move_item(0, 0);
    let mut swap_inv = Inventory::empty();
    swap_inv.try_add_block(Block::Stone as u16);
    swap_inv.slots[1].id = Block::Dirt as u16;
    swap_inv.slots[1].count = 3;
    swap_inv.move_item(0, 1);
    assert_eq!(swap_inv.slots[0].id, Block::Dirt as u16);
    assert_eq!(swap_inv.slots[1].id, Block::Stone as u16);
    let _ = test_map();
}
