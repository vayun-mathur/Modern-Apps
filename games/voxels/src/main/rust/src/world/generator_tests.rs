//! Structure builders and terrain classification with fixed seeds. The
//! builders write only columns inside the target chunk and must no-op
//! (never panic) when their footprint or height would leave the world.

use super::*;
use crate::world::chunk::ChunkPos;

fn empty_chunk(x: i32, z: i32) -> Chunk {
    Chunk::new(ChunkPos(x, z))
}

fn count_id(chunk: &Chunk, id: Id) -> usize {
    let mut n = 0;
    for y in 0..CHUNK_HEIGHT {
        for z in 0..16 {
            for x in 0..16 {
                if chunk.get_block(x, y, z) == id { n += 1; }
            }
        }
    }
    n
}

#[test]
fn in_chunk_and_hash3_are_exact() {
    assert!(in_chunk(0, 0, 0, 0) && in_chunk(15, 15, 0, 0));
    assert!(!in_chunk(16, 0, 0, 0) && !in_chunk(-1, 0, 0, 0) && !in_chunk(0, 16, 0, 0));
    assert!(in_chunk(-16, -1, -16, -16) && !in_chunk(-17, 0, -16, -16));
    assert_eq!(hash3(3, -7, 42), hash3(3, -7, 42), "the world hash must be deterministic");
    assert_ne!(hash3(3, -7, 42), hash3(3, -7, 43));
    assert_ne!(hash3(3, -7, 42), hash3(-7, 3, 42));
}

#[test]
fn level_column_lays_foundation_floor_and_clearing() {
    let mut c = empty_chunk(0, 0);
    for y in 7..=14 { c.set_block(4, y, 4, 1); }
    level_column(&mut c, 4, 4, 10, 3, 8);
    assert_eq!(c.get_block(4, 7, 4), 2, "foundation is dirt below the floor");
    assert_eq!(c.get_block(4, 9, 4), 2);
    assert_eq!(c.get_block(4, 10, 4), 8, "the floor itself is the given block");
    for y in 11..=13 { assert_eq!(c.get_block(4, y, 4), 0, "y={y} above the floor must be cleared"); }
    assert_eq!(c.get_block(4, 14, 4), 1, "clearing stops at clear_to");
    // At the very bottom the foundation must clamp instead of underflowing.
    let mut low = empty_chunk(0, 0);
    level_column(&mut low, 0, 0, 1, 2, 8);
    assert_eq!(low.get_block(0, 0, 0), 2);
    assert_eq!(low.get_block(0, 1, 0), 8);
}

#[test]
fn village_buildings_write_their_footprint() {
    // Well: water centre, cobble rim and corner posts.
    let mut well = empty_chunk(0, 0);
    render_building(&mut well, 0, 0, Building { bx: 2, bz: 2, w: 3, d: 3, base: 20, kind: B_WELL });
    assert_eq!(well.get_block(3, 20, 3), 12);
    assert_eq!(well.get_block(2, 20, 2), 8);
    assert_eq!(well.get_block(2, 22, 2), 8, "corner posts rise two above the rim");
    assert_eq!(well.get_block(3, 21, 2), 0, "non-corner rim stays low");
    // Lamp: a wood post topped with glowstone.
    let mut lamp = empty_chunk(0, 0);
    render_building(&mut lamp, 0, 0, Building { bx: 5, bz: 5, w: 1, d: 1, base: 20, kind: B_LAMP });
    assert_eq!(lamp.get_block(5, 21, 5), 4);
    assert_eq!(lamp.get_block(5, 23, 5), 4);
    assert_eq!(lamp.get_block(5, 24, 5), 77);
    // Farm: a central water channel, farmland elsewhere, crops on alternate plots.
    let mut farm = empty_chunk(0, 0);
    render_building(&mut farm, 0, 0, Building { bx: 1, bz: 1, w: 5, d: 3, base: 20, kind: B_FARM });
    assert_eq!(farm.get_block(3, 20, 2), 12, "middle row is the irrigation channel");
    assert_eq!(farm.get_block(1, 20, 1), 59);
    assert_eq!(farm.get_block(1, 21, 1), 58, "(0+0) is even, so a crop stands here");
    assert_eq!(farm.get_block(2, 21, 1), 0, "odd plots stay bare");
    // House: cobble floor, plank walls, a doorway gap, glass window, roof, lamp inside.
    let mut house = empty_chunk(0, 0);
    render_building(&mut house, 0, 0, Building { bx: 2, bz: 2, w: 4, d: 4, base: 20, kind: B_HOUSE });
    assert_eq!(house.get_block(3, 20, 3), 8);
    assert_eq!(house.get_block(2, 21, 3), 10, "edge columns are walls");
    assert_eq!(house.get_block(4, 21, 2), 0, "the doorway (front middle) stays open");
    assert_eq!(house.get_block(4, 23, 2), 10, "the wall resumes above the door");
    assert_eq!(house.get_block(2, 22, 4), 7, "side window at wall height 2");
    assert_eq!(house.get_block(3, 24, 3), 10, "roof caps the house");
    assert_eq!(house.get_block(4, 20, 4), 77, "a plain house is lit by glowstone");
    // Big house / church / blacksmith variants.
    let mut big = empty_chunk(0, 0);
    render_building(&mut big, 0, 0, Building { bx: 1, bz: 1, w: 5, d: 5, base: 20, kind: B_BIGHOUSE });
    assert_eq!(big.get_block(3, 20, 3), 81, "a big house centres on a warding stone");
    assert_eq!(big.get_block(3, 26, 3), 10, "big houses are six tall");
    let mut church = empty_chunk(0, 0);
    render_building(&mut church, 0, 0, Building { bx: 1, bz: 1, w: 5, d: 5, base: 20, kind: B_CHURCH });
    assert_eq!(church.get_block(3, 30, 3), 67, "the church beacon sits above its nine-tall roof");
    let mut smith = empty_chunk(0, 0);
    render_building(&mut smith, 0, 0, Building { bx: 1, bz: 1, w: 6, d: 6, base: 20, kind: B_BLACKSMITH });
    assert_eq!(smith.get_block(2, 21, 2), 35);
    assert_eq!(smith.get_block(3, 20, 2), 76);
    assert_eq!(smith.get_block(4, 21, 2), 23);
}

#[test]
fn buildings_clip_at_chunk_borders_and_the_sky() {
    // Only the columns inside this chunk may be written.
    let mut c = empty_chunk(0, 0);
    render_building(&mut c, 0, 0, Building { bx: 14, bz: 2, w: 4, d: 4, base: 20, kind: B_HOUSE });
    assert_eq!(c.get_block(14, 20, 2), 8);
    assert_eq!(c.get_block(15, 20, 2), 8);
    assert_eq!(c.get_block(14, 24, 2), 10, "the roof is written for in-chunk columns only");
    assert_eq!(c.get_block(15, 21, 3), 0, "the out-of-chunk half of the house is not built");
    // A building whose roof would leave the world builds nothing at all.
    let mut sky = empty_chunk(0, 0);
    render_building(&mut sky, 0, 0, Building { bx: 2, bz: 2, w: 4, d: 4, base: CHUNK_HEIGHT - 7, kind: B_HOUSE });
    assert_eq!(count_id(&sky, 10), 0);
    assert_eq!(count_id(&sky, 8), 0);
}

#[test]
fn ruined_portal_dungeon_and_temple_have_their_loot() {
    let mut portal = empty_chunk(0, 0);
    build_ruined_portal(&mut portal, 2, 5, 30);
    assert_eq!(portal.get_block(2, 30, 5), 76, "the broken frame corner is covered by magma rubble");
    assert_eq!(portal.get_block(3, 30, 5), 78);
    assert_eq!(portal.get_block(6, 31, 5), 83, "the loot chest stands beside the frame");
    let mut clipped = empty_chunk(0, 0);
    build_ruined_portal(&mut clipped, 13, 5, 30);
    assert_eq!(count_id(&clipped, 78), 0, "a portal that would leave the chunk is skipped");
    let mut dungeon = empty_chunk(0, 0);
    build_dungeon(&mut dungeon, 2, 2, 10, 7);
    assert_eq!(dungeon.get_block(2, 10, 2), 15, "(0+0+0)%3==0 is mossy cobble");
    assert_eq!(dungeon.get_block(3, 10, 2), 8);
    assert_eq!(dungeon.get_block(4, 13, 4), 77);
    assert_eq!(dungeon.get_block(3, 11, 3), 83);
    assert_eq!(dungeon.get_block(5, 11, 5), 83);
    assert_eq!(dungeon.get_block(4, 12, 4), 0, "the room interior is hollow");
    let mut temple = empty_chunk(0, 0);
    build_temple(&mut temple, 8, 8, 30);
    assert_eq!(temple.get_block(8, 30, 8), 38);
    assert_eq!(temple.get_block(4, 30, 8), 38, "the base layer spans the full 9x9");
    assert_eq!(temple.get_block(4, 31, 8), 0, "upper layers step inward");
    assert_eq!(temple.get_block(7, 27, 7), 83);
    assert_eq!(temple.get_block(9, 27, 9), 83);
    assert_eq!(temple.get_block(8, 29, 8), 77);
    let mut edge = empty_chunk(0, 0);
    build_temple(&mut edge, 2, 8, 30);
    assert_eq!(count_id(&edge, 38), 0, "a temple centred too near the edge is skipped");
}

#[test]
fn stronghold_fortress_and_end_city_are_built() {
    let mut hold = empty_chunk(0, 0);
    build_stronghold(&mut hold, 2, 2, 10);
    assert_eq!(hold.get_block(3, 11, 3), 83);
    assert_eq!(hold.get_block(5, 13, 5), 78, "the portal frame's top rail overwrites the light at its centre");
    assert_eq!(hold.get_block(4, 10, 4), 85, "end-stone rim under the portal");
    assert_eq!(hold.get_block(4, 11, 4), 87, "the End portal itself");
    assert_eq!(hold.get_block(5, 12, 5), 0, "interior stays hollow");
    let mut fort = empty_chunk(0, 0);
    build_nether_fortress(&mut fort, 3, 3, 40, 9);
    assert_eq!(fort.get_block(3, 40, 3), 55);
    assert_eq!(fort.get_block(7, 46, 7), 77, "central lamp under the beams");
    assert_eq!(fort.get_block(4, 41, 7), 83);
    assert_eq!(fort.get_block(7, 42, 7), 0, "the hall is hollowed out");
    let mut city = empty_chunk(0, 0);
    build_end_city(&mut city, 3, 3, 40, 0); // r % 6 == 0, so the tower is 12 tall
    assert_eq!(city.get_block(3, 40, 3), 89);
    assert_eq!(city.get_block(5, 42, 5), 0, "between floors the tower is hollow");
    assert_eq!(city.get_block(4, 48, 4), 83, "loot chest in the top room");
    assert_eq!(city.get_block(5, 51, 5), 77, "lantern at the top");
    // Out-of-world placements are skipped whole, never partially written.
    let mut clipped = empty_chunk(0, 0);
    build_stronghold(&mut clipped, 11, 2, 10);
    build_nether_fortress(&mut clipped, 9, 3, 40, 9);
    build_end_city(&mut clipped, 12, 3, 40, 0);
    build_dungeon(&mut clipped, 2, 2, 1, 7);
    assert_eq!(count_id(&clipped, 83), 0);
}

#[test]
fn mineshaft_corridors_have_floors_and_supports() {
    let mut c = empty_chunk(0, 0);
    carve_corridor(&mut c, 0, 0, Corridor { cx: 2, cz: 8, dirx: 1, dirz: 0, len: 7, fy: 20 });
    assert_eq!(c.get_block(2, 20, 8), 10, "plank floor along the arm");
    assert_eq!(c.get_block(8, 20, 8), 10);
    assert_eq!(c.get_block(5, 21, 8), 0, "the passage is carved open");
    assert_eq!(c.get_block(2, 21, 7), 4, "support posts flank the first frame");
    assert_eq!(c.get_block(2, 23, 8), 4, "and a beam crosses above it");
    assert_eq!(c.get_block(3, 21, 7), 0, "frames repeat only every six blocks");
    // A corridor running off the chunk edge writes only its in-chunk part.
    let mut off = empty_chunk(1, 0);
    carve_corridor(&mut off, 16, 0, Corridor { cx: 2, cz: 8, dirx: 1, dirz: 0, len: 7, fy: 20 });
    assert_eq!(count_id(&off, 10), 0);
}

#[test]
fn terrain_classification_is_deterministic_and_bounded() {
    let gen = TerrainGen::new(42);
    let twin = TerrainGen::new(42);
    for wx in -200..200 {
        for wz in [-137, 0, 91] {
            let (wx, wz) = (wx as f64, wz as f64);
            let h = gen.height_at(wx, wz);
            assert_eq!(h, twin.height_at(wx, wz), "same seed must give the same height");
            assert!((12..=128).contains(&h), "height {h} outside the clamped band");
            assert!(gen.biome_at(wx, wz, h) == twin.biome_at(wx, wz, h));
        }
    }
    // Elevation bands win over the lowland climate grid.
    assert!(matches!(gen.biome_at(0.0, 0.0, 200), Biome::FrozenPeaks | Biome::StonyPeaks | Biome::JaggedPeaks));
    assert!(matches!(gen.biome_at(0.0, 0.0, 110), Biome::SnowySlopes | Biome::Grove | Biome::WindsweptHills));
    assert!(!gen.cave_at(0.0, 0.0, 0.0) && !gen.cave_at(0.0, 200.0, 0.0), "caves stay inside 6..=112");
    assert_eq!(gen.cave_kind(0.0, 0.0, 10), 0, "the deep dark owns y<20");
    assert!(gen.cave_kind(0.0, 0.0, 60) <= 2 && gen.ocean_kind(0.0, 0.0) <= 3);
}

#[test]
fn surface_and_stone_selection_follow_the_tables() {
    let gen = TerrainGen::new(7);
    assert_eq!(gen.surface_blocks(Biome::Desert, 70, 0.0, 0.0), (6, 38));
    assert_eq!(gen.surface_blocks(Biome::Badlands, 70, 0.0, 0.0), (36, 37));
    assert_eq!(gen.surface_blocks(Biome::IceSpikes, 70, 0.0, 0.0), (11, 42));
    assert_eq!(gen.surface_blocks(Biome::FrozenPeaks, 130, 0.0, 0.0), (42, 42));
    assert_eq!(gen.surface_blocks(Biome::StonyPeaks, 130, 0.0, 0.0), (1, 1));
    assert_eq!(gen.surface_blocks(Biome::JaggedPeaks, 130, 0.0, 0.0), (11, 1), "high jagged peaks wear snow");
    assert_eq!(gen.surface_blocks(Biome::JaggedPeaks, 100, 0.0, 0.0), (1, 1));
    assert_eq!(gen.surface_blocks(Biome::MushroomFields, 70, 0.0, 0.0), (41, 2));
    assert_eq!(gen.surface_blocks(Biome::Swamp, 70, 0.0, 0.0), (45, 45));
    assert_eq!(gen.surface_blocks(Biome::Plains, 70, 0.0, 0.0), (3, 2));
    // A vein table whose threshold always passes must win inside its y-band
    // and fall back to the base stone outside it.
    let always: [Vein; 1] = [(20, 0.0, 0.1, -2.0, 5, 20)];
    assert_eq!(gen.stone_at(&always, 3.0, 10, 4.0, 1), 20);
    assert_eq!(gen.stone_at(&always, 3.0, 21, 4.0, 1), 1);
    assert_eq!(gen.stone_at(&always, 3.0, 4, 4.0, 1), 1);
    assert_eq!(gen.stone_at(&[], 3.0, 10, 4.0, 57), 57);
    // Richest-first: the first matching vein in the table wins.
    let two: [Vein; 2] = [(22, 0.0, 0.1, -2.0, 5, 20), (20, 0.0, 0.1, -2.0, 5, 20)];
    assert_eq!(gen.stone_at(&two, 3.0, 10, 4.0, 1), 22);
    assert_eq!(gen.deep_base(0.0, 0, 0.0), 57, "the world floor is deepslate");
    assert_eq!(gen.deep_base(0.0, 100, 0.0), 1, "the upper world is plain stone");
}

#[test]
fn dimension_fills_have_their_signatures() {
    // Nether: bedrock floor and roof, netherrack/lava between them.
    let nether = TerrainGen::new_dim(99, 1);
    let mut nc = empty_chunk(0, 0);
    nether.fill_chunk(&mut nc);
    assert!(nc.generated && nc.mesh_dirty);
    assert_eq!(nc.get_block(0, 0, 0), 13);
    assert_eq!(nc.get_block(0, 120, 0), 13);
    assert!(count_id(&nc, 32) + count_id(&nc, 84) > 1000, "the nether is netherrack and lava");
    // End: a guaranteed central island around y=62, void above it.
    let end = TerrainGen::new_dim(99, 2);
    let mut ec = empty_chunk(0, 0);
    end.fill_chunk(&mut ec);
    assert_eq!(ec.get_block(8, 62, 8), 85, "the central island is guaranteed");
    assert_eq!(ec.get_block(8, 100, 8), 0, "the End is void above its islands");
    assert_eq!(ec.get_block(2, 65, 2), 78, "obsidian pillars stand on the central island");
    // Overworld: bedrock at the very bottom, solid ground somewhere.
    let over = TerrainGen::new(42);
    let mut oc = empty_chunk(0, 0);
    over.fill_chunk(&mut oc);
    for x in 0..16 { assert_eq!(oc.get_block(x, 0, 0), 13); }
    assert!(count_id(&oc, 1) > 100, "the overworld has stone");
    // A different dimension value falls back to the overworld.
    let other = TerrainGen::new_dim(42, 9);
    let mut dc = empty_chunk(0, 0);
    other.fill_chunk(&mut dc);
    assert_eq!(dc.get_block(0, 0, 0), 13);
}

#[test]
fn grass_tint_and_ensure_water_behave() {
    let gen = TerrainGen::new(42);
    for wx in (-300..300).step_by(37) {
        for wz in (-300..300).step_by(41) {
            for c in gen.grass_tint(wx as f64, wz as f64) {
                assert!(c.is_finite() && (0.0..=1.0).contains(&c), "tint {c} out of range");
            }
        }
    }
    // Find a lowland column, then flooding it must reach sea level exactly.
    let mut flooded = None;
    'scan: for cx in -4..4 {
        for cz in -4..4 {
            let mut c = empty_chunk(cx, cz);
            gen.ensure_water(&mut c);
            for dz in 0..16 {
                for dx in 0..16 {
                    let wx = (cx * 16 + dx as i32) as f64;
                    let wz = (cz * 16 + dz as i32) as f64;
                    if gen.height_at(wx, wz) < TerrainGen::SEA_LEVEL as i32
                        && matches!(c.get_block(dx, TerrainGen::SEA_LEVEL, dz), 12 | 43) {
                        flooded = Some(c.get_block(dx, TerrainGen::SEA_LEVEL, dz));
                        break 'scan;
                    }
                }
            }
        }
    }
    assert!(flooded.is_some(), "some lowland column must flood to sea level");
    // Water is a no-op outside the overworld and idempotent inside it.
    let nether = TerrainGen::new_dim(42, 1);
    let mut nc = empty_chunk(0, 0);
    nether.ensure_water(&mut nc);
    assert_eq!(count_id(&nc, 12), 0);
    let mut once = empty_chunk(-1, -1);
    gen.ensure_water(&mut once);
    let before = count_id(&once, 12) + count_id(&once, 43);
    gen.ensure_water(&mut once);
    assert_eq!(count_id(&once, 12) + count_id(&once, 43), before, "flooding twice changes nothing");
}
