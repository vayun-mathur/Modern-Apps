#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_id_up_to_the_max_maps_to_a_distinct_block() {
        for id in 0..=MAX_BLOCK_ID {
            assert_eq!(Block::from_id(id).id(), id, "id {id} did not round-trip");
        }
        // Anything past the last block reads back as air rather than transmuting into a variant
        // that doesn't exist.
        assert_eq!(Block::from_id(MAX_BLOCK_ID + 1), Block::Air);
        assert_eq!(Block::from_id(Id::MAX), Block::Air);
    }

    // One boundary, and blocks have to stay on their side of it.
    #[test]
    fn blocks_and_items_never_share_an_id() {
        const { assert!(MAX_BLOCK_ID < crate::item::ITEM_BASE) };
        for id in 0..=MAX_BLOCK_ID { assert!(!crate::item::is_item(id), "block {id} reads as an item"); }
        assert!(crate::item::is_item(crate::item::ITEM_BASE));
        // Every item id decodes to air, so a stray one in a chunk is a hole rather than a wrong block.
        for id in crate::item::ITEM_BASE..crate::item::ITEM_BASE + 256 {
            assert_eq!(Block::from_id(id), Block::Air, "item id {id} decoded as a block");
        }
    }

    #[test]
    fn slabs_and_stairs_know_their_material() {
        for m in [Block::Stone, Block::Cobble, Block::Planks, Block::Brick,
                  Block::Sandstone, Block::DeepslateBricks, Block::NetherBricks, Block::Purpur] {
            let slab = m.slab_of().unwrap_or_else(|| panic!("{m:?} needs a slab"));
            let stairs = m.stairs_of().unwrap_or_else(|| panic!("{m:?} needs stairs"));
            assert_eq!(slab.shape(), Shape::Slab);
            assert_eq!(stairs.shape(), Shape::Stairs);
            assert_eq!(slab.parent(), m);
            assert_eq!(stairs.parent(), m);
            // They borrow the parent's faces, so no new atlas art is needed.
            for (dx, dy, dz) in [(1, 0, 0), (0, 1, 0), (0, -1, 0)] {
                assert_eq!(slab.tile_for_dir(dx, dy, dz), m.tile_for_dir(dx, dy, dz));
                assert_eq!(stairs.tile_for_dir(dx, dy, dz), m.tile_for_dir(dx, dy, dz));
            }
            // A partial block can never be treated as a solid occluder wholesale.
            assert!(!slab.is_opaque());
            assert!(!stairs.is_opaque());
            assert!(slab.is_solid() && stairs.is_solid());
        }
        assert_eq!(Block::Stone.shape(), Shape::Cube);
        assert_eq!(Block::Stone.parent(), Block::Stone);
        assert_eq!(Block::Dirt.slab_of(), None);
    }

    #[test]
    fn a_slab_fills_the_half_its_meta_says() {
        let bottom = Block::StoneSlab.collision_boxes(0);
        assert_eq!(bottom.as_slice(), &[Aabb::new([0.0, 0.0, 0.0], [1.0, 0.5, 1.0])]);
        let top = Block::StoneSlab.collision_boxes(META_TOP);
        assert_eq!(top.as_slice(), &[Aabb::new([0.0, 0.5, 0.0], [1.0, 1.0, 1.0])]);

        // A bottom slab seals only the floor; a top slab only the ceiling.
        assert!(Block::StoneSlab.occludes_face(0, 0, -1, 0));
        assert!(!Block::StoneSlab.occludes_face(0, 0, 1, 0));
        assert!(!Block::StoneSlab.occludes_face(0, 1, 0, 0));
        assert!(Block::StoneSlab.occludes_face(META_TOP, 0, 1, 0));
        assert!(!Block::StoneSlab.occludes_face(META_TOP, 0, -1, 0));
    }

    #[test]
    fn a_stair_is_a_slab_plus_a_step_opposite_its_facing() {
        let boxes = Block::StoneStairs.collision_boxes(FACE_NORTH);
        let s = boxes.as_slice();
        assert_eq!(s.len(), 2);
        assert_eq!(s[0], Aabb::new([0.0, 0.0, 0.0], [1.0, 0.5, 1.0]), "base half-slab");
        assert_eq!(s[1], Aabb::new([0.0, 0.5, 0.5], [1.0, 1.0, 1.0]), "step on the far side of north");

        // The tall side is fully covered, so it may hide a neighbour; the low side may not.
        assert!(Block::StoneStairs.occludes_face(FACE_NORTH, 0, 0, 1), "tall side seals");
        assert!(!Block::StoneStairs.occludes_face(FACE_NORTH, 0, 0, -1), "low side is only half filled");
        assert!(Block::StoneStairs.occludes_face(FACE_NORTH, 0, -1, 0), "the base covers the floor");
        assert!(!Block::StoneStairs.occludes_face(FACE_NORTH, 0, 1, 0));

        // Flipping to the top half mirrors the geometry vertically.
        let flipped = Block::StoneStairs.collision_boxes(FACE_NORTH | META_TOP);
        let f = flipped.as_slice();
        assert_eq!(f[0], Aabb::new([0.0, 0.5, 0.0], [1.0, 1.0, 1.0]));
        assert_eq!(f[1], Aabb::new([0.0, 0.0, 0.5], [1.0, 0.5, 1.0]));
        assert!(Block::StoneStairs.occludes_face(FACE_NORTH | META_TOP, 0, 1, 0));
    }

    #[test]
    fn each_facing_puts_the_step_on_the_opposite_side() {
        let step = |facing: u8| Block::StoneStairs.collision_boxes(facing).as_slice()[1];
        assert_eq!(step(FACE_NORTH).min[2], 0.5, "north-facing steps sit on +Z");
        assert_eq!(step(FACE_SOUTH).max[2], 0.5, "south-facing steps sit on -Z");
        assert_eq!(step(FACE_EAST).max[0], 0.5, "east-facing steps sit on -X");
        assert_eq!(step(FACE_WEST).min[0], 0.5, "west-facing steps sit on +X");
    }

    // Occluding a face and blocking light are different questions, and slabs and glass sit on
    // opposite sides of both.
    #[test]
    fn slabs_cast_shadow_even_though_they_cannot_cull_faces() {
        // A slab fills half its cell, so it can never hide a neighbour's face...
        assert!(!Block::StoneSlab.is_opaque());
        assert!(!Block::StoneStairs.is_opaque());
        // ...but it is cut from solid stone, so light must not pass through it.
        assert!(Block::StoneSlab.blocks_light());
        assert!(Block::StoneStairs.blocks_light());
        assert!(Block::PlankSlab.blocks_light());
    }

    // Growth stage shares a meta byte with slab/stair facing, so the two must not tread on each other.
    #[test]
    fn crop_stage_survives_the_meta_it_shares() {
        for stage in 0..=CROP_RIPE {
            let m = crop_meta(stage);
            assert_eq!(crop_stage(m), stage, "stage {stage} didn't round-trip");
            assert_eq!(m & META_FACING, 0, "stage {stage} bled into the facing bits");
            assert_eq!(m & META_TOP, 0, "stage {stage} bled into the top-half bit");
        }
        // Over-ripe input clamps rather than wrapping to stage 0.
        assert_eq!(crop_stage(crop_meta(200)), CROP_RIPE);
        // And reading a slab's meta as a crop stage can't produce a ripe crop by accident.
        assert_eq!(crop_stage(META_TOP | FACE_WEST), 0);
    }

    // A crop has to be walkable, unlit and self-consistent about what it plants and yields.
    #[test]
    fn crops_are_walkable_and_plantable() {
        let mut seen = 0;
        for id in 0..=MAX_BLOCK_ID {
            let b = Block::from_id(id);
            if !b.is_crop() { continue; }
            seen += 1;
            assert!(!b.is_solid(), "{id} blocks the field it grows in");
            assert!(!b.blocks_light(), "{id} casts a shadow");
            assert!(b.is_transparent());
            let seed = b.crop_seed();
            assert!(seed != 0 && b.crop_yield() != 0, "{id} has no seed or no yield");
            assert_eq!(Block::crop_from_seed(seed), Some(b), "{id} can't be planted from its own seed");
        }
        assert_eq!(seen, 3, "expected three crops");
        assert_eq!(Block::crop_from_seed(0), None);
        assert_eq!(Block::crop_from_seed(Block::Stone as Id), None);

        // Glass is the mirror image: it fills the cell but lets light straight through.
        assert!(!Block::Glass.blocks_light());
        assert!(!Block::Air.blocks_light());
        assert!(Block::Stone.blocks_light());
    }

    #[test]
    fn cubes_are_unchanged() {
        assert_eq!(Block::Stone.collision_boxes(0).as_slice(), &[FULL_CUBE]);
        assert!(Block::Stone.occludes_face(0, 1, 0, 0));
        assert!(!Block::Glass.occludes_face(0, 1, 0, 0), "glass never hid faces");
        assert!(Block::Air.collision_boxes(0).as_slice().is_empty(), "air has no collision");
        assert!(Block::Water.collision_boxes(0).as_slice().is_empty());
    }
}
