//! Sweeps over the whole block table: id round-trips, shape families,
//! collision/occlusion geometry, atlas tiles and crop wiring. The tables in
//! `block_part1.rs` are hand-written per variant, so only a full-population
//! sweep catches a variant that was left behind in one of them.

use super::*;

fn all_blocks() -> impl Iterator<Item = Block> {
    (0..=MAX_BLOCK_ID).map(Block::from_id)
}

#[test]
fn every_block_id_round_trips_and_out_of_range_is_air() {
    for id in 0..=MAX_BLOCK_ID {
        assert_eq!(Block::from_id(id).id(), id, "id {id} did not round-trip");
    }
    assert_eq!(Block::from_id(MAX_BLOCK_ID + 1), Block::Air);
    assert_eq!(Block::from_id(u16::MAX), Block::Air);
    assert!(Block::Air.is_air());
    assert!(!Block::Stone.is_air());
}

#[test]
fn crop_meta_packs_and_clamps_the_stage() {
    for stage in 0..=CROP_RIPE {
        assert_eq!(crop_stage(crop_meta(stage)), stage);
    }
    assert_eq!(crop_meta(99), crop_meta(CROP_RIPE), "stages above ripe must clamp");
    assert_eq!(crop_meta(0), 0);
    // Facing/half bits (0..2) must survive a stage read untouched by the mask.
    assert_eq!(crop_stage(META_TOP | META_FACING), 0);
}

#[test]
fn crop_seed_and_yield_form_a_cycle() {
    for crop in [Block::WheatCrop, Block::CarrotCrop, Block::MelonCrop] {
        assert!(crop.is_crop());
        let seed = crop.crop_seed();
        assert_ne!(seed, 0, "{crop:?} has no seed");
        assert_eq!(Block::crop_from_seed(seed), Some(crop), "seed {seed} does not replant {crop:?}");
        assert_ne!(crop.crop_yield(), 0, "{crop:?} yields nothing");
    }
    assert_eq!(Block::Stone.crop_seed(), 0);
    assert_eq!(Block::Stone.crop_yield(), 0);
    assert_eq!(Block::crop_from_seed(0), None);
    assert_eq!(Block::crop_from_seed(1), None);
    assert!(!Block::Stone.is_crop());
}

#[test]
fn slab_and_stair_families_are_closed_under_parent() {
    let mut families = 0;
    for b in all_blocks() {
        if let Some(slab) = b.slab_of() {
            families += 1;
            assert_eq!(slab.shape(), Shape::Slab, "{slab:?} is not slab-shaped");
            assert_eq!(slab.parent(), b, "{slab:?} lost its parent material");
            let stairs = b.stairs_of().expect("a slab material must also have stairs");
            assert_eq!(stairs.shape(), Shape::Stairs);
            assert_eq!(stairs.parent(), b);
            // The shapes themselves have no further shapes.
            assert_eq!(slab.slab_of(), None);
            assert_eq!(stairs.stairs_of(), None);
        } else {
            assert_eq!(b.stairs_of(), None, "{b:?} has stairs but no slab");
        }
        // Every shaped block resolves back to a cube parent that names it.
        if b.shape() != Shape::Cube {
            assert_eq!(b.parent().shape(), Shape::Cube, "{b:?} parent is not a cube");
            let back = if b.shape() == Shape::Slab { b.parent().slab_of() } else { b.parent().stairs_of() };
            assert_eq!(back, Some(b), "{b:?} is not the shape its parent claims");
        } else {
            assert_eq!(b.parent(), b, "cube {b:?} changed under parent()");
        }
    }
    assert_eq!(families, 8, "expected the 8 slab/stair material families");
}

#[test]
fn collision_boxes_stay_inside_the_cell() {
    for b in all_blocks() {
        for meta in [0u8, META_TOP, 1, 2, 3, META_TOP | 3] {
            let boxes = b.collision_boxes(meta);
            if !b.is_solid() {
                assert!(boxes.as_slice().is_empty(), "non-solid {b:?} must not collide");
                continue;
            }
            assert!(!boxes.as_slice().is_empty(), "solid {b:?} must collide");
            for a in boxes.as_slice() {
                for i in 0..3 {
                    assert!(a.min[i] >= 0.0 && a.max[i] <= 1.0 && a.min[i] < a.max[i],
                        "{b:?} meta {meta} box {a:?} escapes its cell");
                }
            }
        }
    }
    // Exact shapes: full cube, bottom/top slab.
    assert_eq!(Block::Stone.collision_boxes(0).as_slice(), &[FULL_CUBE]);
    assert_eq!(Block::StoneSlab.collision_boxes(0).as_slice()[0], Aabb::new([0.0, 0.0, 0.0], [1.0, 0.5, 1.0]));
    assert_eq!(Block::StoneSlab.collision_boxes(META_TOP).as_slice()[0], Aabb::new([0.0, 0.5, 0.0], [1.0, 1.0, 1.0]));
    // Every stair, in every facing and half, spans the full cell height somewhere.
    for facing in 0..=3u8 {
        for meta in [facing, facing | META_TOP] {
            let boxes = Block::StoneStairs.collision_boxes(meta);
            assert_eq!(boxes.as_slice().len(), 2, "a stair is base + step");
            let (lo, hi) = boxes.as_slice().iter().fold((1.0f32, 0.0f32), |(lo, hi), a| (lo.min(a.min[1]), hi.max(a.max[1])));
            assert_eq!((lo, hi), (0.0, 1.0), "stair meta {meta} does not span the cell");
        }
    }
}

#[test]
fn occlusion_matches_the_filled_half() {
    let dirs = [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)];
    for (dx, dy, dz) in dirs {
        assert!(Block::Stone.occludes_face(0, dx, dy, dz), "opaque stone must hide every face");
        assert!(!Block::Air.occludes_face(0, dx, dy, dz));
        assert!(!Block::Glass.occludes_face(0, dx, dy, dz), "glass hides no face");
        assert!(!Block::Water.occludes_face(0, dx, dy, dz));
    }
    // A bottom slab seals only its underside; a top slab only its top.
    assert!(Block::StoneSlab.occludes_face(0, 0, -1, 0));
    assert!(!Block::StoneSlab.occludes_face(0, 0, 1, 0));
    assert!(!Block::StoneSlab.occludes_face(0, 1, 0, 0));
    assert!(Block::StoneSlab.occludes_face(META_TOP, 0, 1, 0));
    assert!(!Block::StoneSlab.occludes_face(META_TOP, 0, -1, 0));
    // Stairs: the sealed horizontal side follows the half, the tall side is
    // opposite the facing (north-facing stairs are tall toward +Z).
    assert!(Block::StoneStairs.occludes_face(0, 0, -1, 0));
    assert!(Block::StoneStairs.occludes_face(0, 0, 0, 1), "north stair tall side is +Z");
    assert!(!Block::StoneStairs.occludes_face(0, 0, 0, -1));
    assert!(Block::StoneStairs.occludes_face(1, -1, 0, 0), "east stair tall side is -X");
    assert!(Block::StoneStairs.occludes_face(2, 0, 0, -1), "south stair tall side is -Z");
    assert!(Block::StoneStairs.occludes_face(3, 1, 0, 0), "west stair tall side is +X");
    assert!(Block::StoneStairs.occludes_face(META_TOP, 0, 1, 0));
}

#[test]
fn atlas_tiles_fit_the_atlas_and_follow_the_parent() {
    let max_tile = (ATLAS_COLS * ATLAS_ROWS) as u32;
    for b in all_blocks() {
        for tile in [b.tile_top(), b.tile_bottom(), b.tile_side()] {
            assert!(tile < max_tile, "{b:?} tile {tile} is outside the {ATLAS_COLS}x{ATLAS_ROWS} atlas");
        }
        assert_eq!(b.tile_for_dir(0, 1, 0), b.parent().tile_top());
        assert_eq!(b.tile_for_dir(0, -1, 0), b.parent().tile_bottom());
        assert_eq!(b.tile_for_dir(1, 0, 0), b.parent().tile_side());
        assert_eq!(b.tile_for_dir(0, 0, 1), b.parent().tile_side());
        for c in b.color() {
            assert!((0.0..=1.0).contains(&c), "{b:?} colour {c} out of range");
        }
    }
    // Faces that differ from the top are the exception, not the rule.
    assert_eq!(Block::Grass.tile_top(), 2);
    assert_eq!(Block::Grass.tile_bottom(), 1);
    assert_eq!(Block::Grass.tile_side(), 3);
    assert_eq!(Block::Stone.tile_bottom(), Block::Stone.tile_top());
}

#[test]
fn tile_uv_maps_corners_and_stays_in_unit_space() {
    assert_eq!(tile_uv(0), (0.0, 0.0, 0.0625, 0.0625));
    assert_eq!(tile_uv(17), (0.0625, 0.0625, 0.125, 0.125));
    let (x0, y0, x1, y1) = tile_uv(255);
    assert_eq!((x0, y0, x1, y1), (0.9375, 0.9375, 1.0, 1.0));
    for tile in [0u32, 1, 15, 16, 100, 254] {
        let (x0, y0, x1, y1) = tile_uv(tile);
        assert!(0.0 <= x0 && x0 < x1 && x1 <= 1.0 && 0.0 <= y0 && y0 < y1 && y1 <= 1.0);
        assert!((x1 - x0 - 0.0625).abs() < 1e-6 && (y1 - y0 - 0.0625).abs() < 1e-6);
    }
    assert_eq!(GRASS_SIDE_OVERLAY, 16);
    assert!(GRASS_SIDE_TILE >= 256, "the sentinel must never collide with a real tile");
}

#[test]
fn light_and_shadow_predicates_agree_with_material() {
    for (b, want) in [
        (Block::Glowstone, 15u8), (Block::SeaLantern, 15), (Block::Lava, 15), (Block::Beacon, 15),
        (Block::WardingStone, 13), (Block::NetherPortal, 12), (Block::EndPortal, 12),
        (Block::Magma, 11), (Block::Stone, 0), (Block::Air, 0),
    ] {
        assert_eq!(b.light_emission(), want, "{b:?} emits the wrong light");
    }
    for b in all_blocks() {
        assert!(b.light_emission() <= 15);
        assert_eq!(b.is_opaque(), !b.is_transparent(), "{b:?} opacity is inconsistent");
    }
    assert!(Block::Stone.blocks_light());
    assert!(!Block::Glass.blocks_light(), "glass fills its cell but passes light");
    assert!(!Block::Air.blocks_light());
    assert!(Block::StoneSlab.blocks_light(), "a stone slab is still stone for shadowing");
    assert!(!Block::Leaves.blocks_light() || Block::Leaves.is_opaque());
}

#[test]
fn menus_pickaxes_and_solidity_are_pinned() {
    assert_eq!(Block::CraftingTable.menu(), 1);
    assert_eq!(Block::Furnace.menu(), 2);
    assert_eq!(Block::Jukebox.menu(), 3);
    assert_eq!(Block::BlastFurnace.menu(), 4);
    assert_eq!(Block::Stonecutter.menu(), 5);
    assert_eq!(Block::Stone.menu(), 0);
    assert!(Block::Stone.needs_pickaxe() && Block::Obsidian.needs_pickaxe());
    assert!(!Block::Dirt.needs_pickaxe() && !Block::Planks.needs_pickaxe());
    for b in all_blocks() {
        if b.shape() != Shape::Cube {
            assert_eq!(b.needs_pickaxe(), b.parent().needs_pickaxe(),
                "{b:?} pickaxe rule differs from its parent material");
        }
    }
    assert!(Block::Stone.is_solid() && Block::Glass.is_solid() == false);
    assert!(!Block::Water.is_solid() && !Block::WheatCrop.is_solid());
}

#[test]
fn aabb_overlap_is_strict_on_touching_faces() {
    assert!(FULL_CUBE.overlaps_at([0.0, 0.0, 0.0], [0.5, 0.5, 0.5], [2.0, 2.0, 2.0]));
    assert!(!FULL_CUBE.overlaps_at([0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [2.0, 1.0, 1.0]),
        "boxes that only share a face do not overlap");
    assert!(!FULL_CUBE.overlaps_at([3.0, 0.0, 0.0], [0.0, 0.0, 0.0], [1.0, 1.0, 1.0]));
    assert!(FULL_CUBE.overlaps_at([3.0, 0.0, 0.0], [3.25, 0.0, 0.0], [4.0, 1.0, 1.0]));
}
