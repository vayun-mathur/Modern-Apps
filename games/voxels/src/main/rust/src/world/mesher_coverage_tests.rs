//! Mesher emptiness and single-block geometry.

use super::*;
use crate::world::chunk::{Chunk, ChunkPos};
use crate::world::block::Block;

#[test]
fn empty_and_single_block_meshes() {
    let empty = Chunk::new(ChunkPos(0, 0));
    let meshes = mesh_chunk(&empty, &|_, _, _| (0, 0), &|_, _| [0.4, 0.7, 0.3]);
    assert!(meshes.iter().all(|m| m.is_none()), "an empty chunk meshes to nothing");
    // MeshData emptiness looks at both the opaque and water streams.
    let blank = MeshData { vertices: Vec::new(), indices: Vec::new(), water_vertices: Vec::new(), water_indices: Vec::new() };
    assert!(blank.is_empty());
    let mut one = Chunk::new(ChunkPos(0, 0));
    one.set_block(8, 64, 8, Block::Stone as u16);
    let meshes = mesh_chunk(&one, &|_, _, _| (0, 0), &|_, _| [0.4, 0.7, 0.3]);
    let filled: Vec<&MeshData> = meshes.iter().filter_map(|m| m.as_ref()).collect();
    assert!(!filled.is_empty());
    assert!(filled.iter().all(|m| !m.is_empty()));
    // A lone cube exposes all six faces: 24 vertices, 36 indices in its section.
    let total_verts: usize = filled.iter().map(|m| m.vertices.len()).sum();
    let total_idx: usize = filled.iter().map(|m| m.indices.len()).sum();
    assert_eq!(total_verts, 24);
    assert_eq!(total_idx, 36);
    assert!(filled.iter().flat_map(|m| m.indices.iter()).all(|&i| (i as usize) < total_verts));
}
