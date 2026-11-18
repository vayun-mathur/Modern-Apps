//! Pick-ray targetability regression tests.

use super::*;
use crate::world::block::{Block, Id};
use glam::vec3;

const SKY: i32 = 200;
fn world() -> ChunkMap {
    let dir = std::env::temp_dir().join("voxels_raycast_cov").to_string_lossy().into_owned();
    ChunkMap::new(1, dir)
}

#[test]
fn crops_and_glass_are_pickable_but_fluids_and_portals_are_not() {
    // A crop has no collision box (the player walks through a field), yet a ray must
    // stop on it or the crop can never be harvested. This is the regression test for
    // the raycast using collision solidity as its target predicate.
    let mut w = world();
    w.set_block_world(4, SKY, 0, Block::WheatCrop as Id);
    let hit = raycast(&w, vec3(0.0, SKY as f32 + 0.5, 0.5), vec3(1.0, 0.0, 0.0), 20.0).expect("a crop must be pickable");
    assert_eq!(hit.pos, (4, SKY, 0));
    // Glass fills its cell for interaction even though it is not collision-solid.
    let mut w = world();
    w.set_block_world(4, SKY, 0, Block::Glass as Id);
    assert_eq!(raycast(&w, vec3(0.0, SKY as f32 + 0.5, 0.5), vec3(1.0, 0.0, 0.0), 20.0).map(|h| h.pos), Some((4, SKY, 0)));
    // Water, lava and portals are passed through.
    for id in [Block::Water as Id, Block::Lava as Id, Block::NetherPortal as Id] {
        let mut w = world();
        w.set_block_world(4, SKY, 0, id);
        assert!(raycast(&w, vec3(0.0, SKY as f32 + 0.5, 0.5), vec3(1.0, 0.0, 0.0), 20.0).is_none(), "block {id} must not stop a pick ray");
    }
}

#[test]
fn degenerate_and_out_of_range_rays_miss() {
    let w = world();
    assert!(raycast(&w, vec3(0.5, SKY as f32, 0.5), Vec3::ZERO, 10.0).is_none(), "a zero direction goes nowhere");
    assert!(raycast(&w, vec3(0.5, SKY as f32, 0.5), vec3(1.0, 0.0, 0.0), 0.5).is_none(), "empty air within range");
    // Starting inside a crop cell still counts as a hit on that cell.
    let mut w = world();
    w.set_block_world(0, SKY, 0, Block::CarrotCrop as Id);
    let hit = raycast(&w, vec3(0.5, SKY as f32 + 0.5, 0.5), vec3(1.0, 0.0, 0.0), 10.0).expect("inside a crop");
    assert_eq!(hit.pos, (0, SKY, 0));
}
