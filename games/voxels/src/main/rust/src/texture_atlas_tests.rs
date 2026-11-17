//! The CPU-side atlas contract: geometry constants and the bundled
//! pixel payloads. (The Vulkan upload itself needs a GPU and is not
//! exercised here.)

use super::*;

#[test]
fn atlas_geometry_is_consistent() {
    assert_eq!(ATLAS_W, ATLAS_TILES_PER_ROW * TILE_SIZE);
    assert_eq!(ATLAS_H, ATLAS_TILE_ROWS * TILE_SIZE);
    assert_eq!((ATLAS_W, ATLAS_H), (256, 256));
    assert_eq!(ENTITY_ATLAS_W % ENTITY_CELL, 0, "entity cells must tile the atlas exactly");
    assert_eq!(ENTITY_ATLAS_H % ENTITY_CELL, 0);
    assert_eq!(ENTITY_ATLAS_W / ENTITY_CELL, 4, "mob kinds index a 4-wide cell grid");
}

#[test]
fn bundled_atlases_are_full_frames() {
    let atlas = load_atlas_bin();
    assert_eq!(atlas.len(), (ATLAS_W * ATLAS_H * 4) as usize, "one RGBA byte set per pixel");
    assert_eq!(atlas, load_atlas_bin(), "the bundled atlas is deterministic");
    let entity = load_entity_atlas_bin();
    assert_eq!(entity.len(), (ENTITY_ATLAS_W * ENTITY_ATLAS_H * 4) as usize);
    // A real texture is not a flat fill: many distinct byte values appear.
    let mut seen = [false; 256];
    for &b in atlas.iter().step_by(997) { seen[b as usize] = true; }
    assert!(seen.iter().filter(|s| **s).count() > 8, "the block atlas looks like a flat fill");
}
