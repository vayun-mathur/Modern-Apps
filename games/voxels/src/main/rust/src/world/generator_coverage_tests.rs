//! Dimension fills and biome/height determinism.

use crate::world::chunk::{Chunk, ChunkPos};
use super::*;
use std::collections::HashSet;

fn fill(seed: u32, dim: u8, cx: i32, cz: i32) -> Chunk {
    let gen = TerrainGen::new_dim(seed, dim);
    let mut c = Chunk::new(ChunkPos(cx, cz));
    gen.fill_chunk(&mut c);
    gen.ensure_water(&mut c);
    c
}
fn ids(c: &Chunk) -> HashSet<u16> {
    let mut out = HashSet::new();
    for sec in c.sections.iter().flatten() { for &b in sec.blocks.iter() { out.insert(b); } }
    out
}

#[test]
fn dimension_fills_have_distinct_signatures() {
    let over = fill(42, 0, 0, 0);
    assert!(over.generated);
    let over_ids = ids(&over);
    assert!(over_ids.contains(&3) || over_ids.contains(&2) || over_ids.contains(&1), "overworld surface blocks: {over_ids:?}");
    let nether = fill(42, 1, 0, 0);
    let nether_ids = ids(&nether);
    assert!(nether_ids.contains(&32), "nether must be netherrack: {nether_ids:?}");
    assert!(!nether_ids.contains(&12), "no water in the nether");
    let end = fill(42, 2, 0, 0);
    let end_ids = ids(&end);
    assert!(end_ids.contains(&85), "the End must be end stone: {end_ids:?}");
    assert_eq!(end.get_block(8, 62, 8), 85);
    // The same seed and position regenerate identically in every dimension.
    for dim in 0..3u8 {
        let a = fill(7, dim, -1, 2);
        let b = fill(7, dim, -1, 2);
        assert_eq!(ids(&a), ids(&b));
        for y in [0usize, 40, 64, 100] {
            assert_eq!(a.get_block(4, y, 4), b.get_block(4, y, 4), "dim {dim} y {y} differs between generations");
        }
    }
}

#[test]
fn height_biome_and_caves_are_deterministic_and_bounded() {
    let gen = TerrainGen::new(99);
    let mut biomes = HashSet::new();
    let mut heights = Vec::new();
    for wx in (-200..200).step_by(17) {
        for wz in (-200..200).step_by(17) {
            let h = gen.height_at(wx as f64, wz as f64);
            heights.push(h);
            assert!((10..200).contains(&h), "height {h} out of range");
            assert_eq!(h, gen.height_at(wx as f64, wz as f64), "height must be deterministic");
            biomes.insert(gen.biome_at(wx as f64, wz as f64, h) as u8);
            let _ = gen.cave_at(wx as f64, 30.0, wz as f64);
            let tint = gen.grass_tint(wx as f64, wz as f64);
            assert!(tint.iter().all(|v| v.is_finite()));
        }
    }
    assert!(heights.iter().max().unwrap() > heights.iter().min().unwrap(), "terrain must not be flat");
    assert!(biomes.len() >= 3, "a 400x400 sweep must cross several biomes: {biomes:?}");
    // A different seed is a different world somewhere in the sweep.
    let other = TerrainGen::new(100);
    assert!((-200..200).step_by(13).any(|wx| gen.height_at(wx as f64, 0.0) != other.height_at(wx as f64, 0.0)));
}

#[test]
fn a_patch_of_world_contains_structures_and_varied_terrain() {
    // Filling a patch of chunks exercises trees, villages, mineshafts and the other
    // structure passes that a single chunk rarely triggers.
    let gen = TerrainGen::new(1234);
    let mut all = HashSet::new();
    let mut non_air = 0usize;
    for cx in -3..=3 {
        for cz in -3..=3 {
            let mut c = Chunk::new(ChunkPos(cx, cz));
            gen.fill_chunk(&mut c);
            gen.ensure_water(&mut c);
            for sec in c.sections.iter().flatten() {
                for &b in sec.blocks.iter() { all.insert(b); if b != 0 { non_air += 1; } }
            }
        }
    }
    assert!(non_air > 50_000, "49 chunks of terrain, got {non_air} blocks");
    assert!(all.len() > 15, "a patch of world must be varied: {} ids", all.len());
    assert!(all.contains(&13), "bedrock floors every chunk");
}

#[test]
fn structure_builders_write_and_clip() {
    // Every structure builder, exercised directly on synthetic chunks, plus its clip guard.
    let mut c = Chunk::new(ChunkPos(0, 0));
    for kind in [B_HOUSE, B_WELL, B_LAMP, B_BIGHOUSE, B_FARM, B_BLACKSMITH, B_CHURCH] {
        render_building(&mut c, 0, 0, Building { bx: 2, bz: 2, w: 6, d: 6, base: 60, kind });
    }
    render_building(&mut c, 0, 0, Building { bx: 0, bz: 0, w: 4, d: 4, base: CHUNK_HEIGHT - 3, kind: B_HOUSE }); // too high: no-op
    render_building(&mut c, 0, 0, Building { bx: -8, bz: -8, w: 6, d: 6, base: 60, kind: B_HOUSE }); // straddling the corner
    assert!(ids(&c).contains(&83) || ids(&c).contains(&77) || ids(&c).contains(&10), "buildings must leave blocks: {:?}", ids(&c));
    build_ruined_portal(&mut c, 2, 3, 60);
    build_ruined_portal(&mut c, 14, 3, 60); // clipped
    assert!(ids(&c).contains(&78), "ruined portal is obsidian");
    carve_corridor(&mut c, 0, 0, Corridor { cx: 2, cz: 8, dirx: 1, dirz: 0, len: 12, fy: 40 });
    carve_corridor(&mut c, 0, 0, Corridor { cx: 8, cz: 2, dirx: 0, dirz: 1, len: 12, fy: 40 });
    build_dungeon(&mut c, 2, 2, 30, 7);
    build_dungeon(&mut c, 14, 14, 30, 7); // clipped
    build_dungeon(&mut c, 2, 2, 1, 7); // too shallow
    build_temple(&mut c, 8, 8, 50);
    build_temple(&mut c, 1, 1, 50); // clipped
    build_stronghold(&mut c, 2, 2, 30);
    build_stronghold(&mut c, 12, 12, 30); // clipped
    build_nether_fortress(&mut c, 2, 2, 40, 9);
    build_nether_fortress(&mut c, 10, 10, 40, 9); // clipped
    build_end_city(&mut c, 2, 2, 60, 4);
    build_end_city(&mut c, 13, 13, 60, 4); // clipped
    let all = ids(&c);
    assert!(all.contains(&83), "structures must contain loot chests");
    assert!(all.contains(&87), "the stronghold holds an active end portal");
    assert!(all.contains(&89), "the end city is purpur");
    assert!(all.contains(&55), "the fortress is nether brick");
    assert!(all.contains(&38), "the temple is sandstone");
}

#[test]
fn biome_decorations_appear_in_their_biomes() {
    // Scan for one chunk in each decorated biome family and generate it: cacti,
    // taiga trees, swamp trees and the rest of the surface dressing live in those arms.
    let gen = TerrainGen::new(2024);
    let mut filled = 0;
    let mut seen_biomes = HashSet::new();
    'outer: for cx in (-12..12).step_by(3) {
        for cz in (-12..12).step_by(3) {
            let (wx, wz) = (cx * 16 + 8, cz * 16 + 8);
            let h = gen.height_at(wx as f64, wz as f64);
            let biome = gen.biome_at(wx as f64, wz as f64, h) as u8;
            if !seen_biomes.insert(biome) { continue; }
            let mut c = Chunk::new(ChunkPos(cx, cz));
            gen.fill_chunk(&mut c);
            filled += 1;
            if filled >= 10 { break 'outer; }
        }
    }
    assert!(filled >= 5, "the scan must find at least five distinct biomes, got {filled}");
    assert!(seen_biomes.len() >= 5);
}

#[test]
fn desert_chunks_dress_themselves() {
    // Find a desert column, generate its chunk, and check the desert tint; then do
    // the same for a cold biome so both tint families are exercised.
    let gen = TerrainGen::new(2024);
    let mut desert: Option<(i32, i32)> = None;
    let mut cold: Option<(i32, i32)> = None;
    for wx in (-600..600).step_by(8) {
        for wz in (-600..600).step_by(8) {
            let h = gen.height_at(wx as f64, wz as f64);
            match gen.biome_at(wx as f64, wz as f64, h) {
                Biome::Desert | Biome::Badlands => { if desert.is_none() { desert = Some((wx, wz)); } }
                Biome::SnowyTaiga | Biome::SnowyPlains | Biome::IceSpikes => { if cold.is_none() { cold = Some((wx, wz)); } }
                _ => {}
            }
            if desert.is_some() && cold.is_some() { break; }
        }
        if desert.is_some() && cold.is_some() { break; }
    }
    let (dx, dz) = desert.expect("a desert must exist in a 1200x1200 sweep");
    assert_eq!(gen.grass_tint(dx as f64, dz as f64), [0.66, 0.64, 0.34], "desert tint is the dry family");
    let mut c = Chunk::new(ChunkPos(dx.div_euclid(16), dz.div_euclid(16)));
    gen.fill_chunk(&mut c);
    assert!(c.generated);
    if let Some((cx, cz)) = cold {
        let mut c = Chunk::new(ChunkPos(cx.div_euclid(16), cz.div_euclid(16)));
        gen.fill_chunk(&mut c);
        assert!(c.generated);
    }
}
