use super::chunk::{Chunk, CHUNK_SIZE, CHUNK_HEIGHT};
use super::block::Id;
use crate::world::perlin::NoiseFn;
use crate::world::perlin::Perlin;
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Biome {
    Plains, SunflowerPlains, Meadow,
    Forest, BirchForest, DarkForest, FlowerForest,
    Taiga, SnowyTaiga,
    Savanna, Jungle, SparseJungle,
    Swamp, MangroveSwamp,
    Desert, Badlands,
    SnowyPlains, IceSpikes, MushroomFields,
    WindsweptHills, Grove, SnowySlopes,
    JaggedPeaks, FrozenPeaks, StonyPeaks,
}

// ---- Multi-chunk villages (deterministic region grid) ----
const VILLAGE_REGION: i32 = 6; // chunks per region side; at most one village per region

const B_HOUSE: u8 = 0;
const B_WELL: u8 = 1;
const B_LAMP: u8 = 2;
const B_BIGHOUSE: u8 = 3;
const B_FARM: u8 = 4;
const B_BLACKSMITH: u8 = 5;
const B_CHURCH: u8 = 6;

// Level a village column: dirt foundation down a few blocks, clear terrain above up to `clear_to`.
fn level_column(chunk: &mut Chunk, lx: usize, lz: usize, base: usize, clear_to: usize, floor: Id) {
    for dyb in 1..=3 { if base >= dyb { chunk.set_block(lx, base - dyb, lz, 2); } } // dirt foundation
    chunk.set_block(lx, base, lz, floor);
    for dy in 1..=clear_to { if base + dy < CHUNK_HEIGHT { chunk.set_block(lx, base + dy, lz, 0); } }
}

fn hash3(a: i32, b: i32, c: u32) -> u64 {
    let mut h = DefaultHasher::new();
    (a, b, c).hash(&mut h);
    h.finish()
}
fn in_chunk(wx: i32, wz: i32, ox: i32, oz: i32) -> bool { wx >= ox && wx < ox + 16 && wz >= oz && wz < oz + 16 }

/// One tree to plant: its cell, surface height, wood and canopy.
struct Tree { dx: usize, dz: usize, h: usize, log: Id, leaf: Id, trunk_h: usize }
/// One village building: its world corner, footprint, surface base and kind.
struct Building { bx: i32, bz: i32, w: i32, d: i32, base: usize, kind: u8 }
// Render a single village building, writing only the columns that fall inside this chunk (so a
// building straddling a chunk border is completed by whichever chunks overlap it).
fn render_building(chunk: &mut Chunk, ox: i32, oz: i32, building: Building) {
    let Building { bx, bz, w, d, base, kind } = building;
    if base + 7 >= CHUNK_HEIGHT { return; }
    match kind {
        B_WELL => {
            for gx in 0..3 { for gz in 0..3 {
                let (wx, wz) = (bx + gx, bz + gz);
                if !in_chunk(wx, wz, ox, oz) { continue; }
                let (lx, lz) = ((wx - ox) as usize, (wz - oz) as usize);
                let id = if gx == 1 && gz == 1 { 12 } else { 8 };
                chunk.set_block(lx, base, lz, id);
                let corner = (gx == 0 || gx == 2) && (gz == 0 || gz == 2);
                if corner { for dy in 1..=2 { chunk.set_block(lx, base + dy, lz, 8); } }
            }}
        }
        B_LAMP => {
            if in_chunk(bx, bz, ox, oz) {
                let (lx, lz) = ((bx - ox) as usize, (bz - oz) as usize);
                for dy in 1..=3 { chunk.set_block(lx, base + dy, lz, 4); }
                chunk.set_block(lx, base + 4, lz, 77); // glowstone lamp
            }
        }
        B_FARM => {
            for gx in 0..w { for gz in 0..d {
                let (wx, wz) = (bx + gx, bz + gz);
                if !in_chunk(wx, wz, ox, oz) { continue; }
                let (lx, lz) = ((wx - ox) as usize, (wz - oz) as usize);
                let water = gx == w / 2; // central irrigation channel
                level_column(chunk, lx, lz, base, 3, if water { 12 } else { 59 }); // water / farmland
                if !water && (gx + gz) % 2 == 0 && base + 1 < CHUNK_HEIGHT {
                    chunk.set_block(lx, base + 1, lz, 58); // hay = ripe crop stand-in
                }
            }}
        }
        _ => {
            let height: usize = match kind { B_BIGHOUSE => 6, B_CHURCH => 9, _ => 4 };
            for gx in 0..w { for gz in 0..d {
                let (wx, wz) = (bx + gx, bz + gz);
                if !in_chunk(wx, wz, ox, oz) { continue; }
                let (lx, lz) = ((wx - ox) as usize, (wz - oz) as usize);
                level_column(chunk, lx, lz, base, height + 2, 8); // foundation + cobble floor + clear
                let edge = gx == 0 || gx == w - 1 || gz == 0 || gz == d - 1;
                if edge {
                    for dy in 1..height {
                        let door = gz == 0 && gx == w / 2 && dy <= 2;
                        if door { continue; }
                        let window = dy == 2 && (((gx == 0 || gx == w - 1) && gz == d / 2) || (gz == d - 1 && gx == w / 2));
                        chunk.set_block(lx, base + dy, lz, if window { 7 } else { 10 });
                    }
                }
                chunk.set_block(lx, base + height, lz, 10); // roof
            }}
            let (cxw, czw) = (bx + w / 2, bz + d / 2);
            if in_chunk(cxw, czw, ox, oz) {
                chunk.set_block((cxw - ox) as usize, base, (czw - oz) as usize, if kind == B_BIGHOUSE { 81 } else { 77 });
            }
            // Blacksmith forge: furnace + magma fire + iron-block anvil inside.
            if kind == B_BLACKSMITH {
                for &(dx, dz, dy, id) in &[(1i32, 1i32, 1usize, 35 as Id), (2, 1, 0, 76), (3, 1, 1, 23)] {
                    let (wx, wz) = (bx + dx, bz + dz);
                    if in_chunk(wx, wz, ox, oz) { chunk.set_block((wx - ox) as usize, base + dy, (wz - oz) as usize, id); }
                }
            }
            // Church spire: a sea-lantern beacon above the roof.
            if kind == B_CHURCH && in_chunk(cxw, czw, ox, oz) && base + height + 2 < CHUNK_HEIGHT {
                chunk.set_block((cxw - ox) as usize, base + height + 1, (czw - oz) as usize, 67);
            }
        }
    }
}

// A partial obsidian ruined-portal frame standing at the surface.
fn build_ruined_portal(chunk: &mut Chunk, cx: usize, cz: usize, base: usize) {
    if cx + 4 >= 16 || base + 6 >= CHUNK_HEIGHT { return; }
    for gx in 0..4i32 { for gy in 0..5i32 {
        let edge = gx == 0 || gx == 3 || gy == 0 || gy == 4;
        if !edge { continue; }
        // Broken: skip a few frame blocks pseudo-randomly.
        if (gx * 7 + gy * 13) % 5 == 0 { continue; }
        chunk.set_block(cx + gx as usize, base + gy as usize, cz, 78); // obsidian
    }}
    // A little rubble at the base, with scattered magma and glowstone.
    for gx in -1i32..=4 {
        let x = cx as i32 + gx;
        if (0..16).contains(&x) {
            let rub = match (gx.rem_euclid(3), gx.rem_euclid(5)) { (0, _) => 76, (_, 0) => 77, _ => 78 }; // magma/glowstone/obsidian
            chunk.set_block(x as usize, base, cz, rub);
        }
    }
    // Loot chest beside the ruined frame.
    if cx + 4 < 16 { chunk.set_block(cx + 4, base + 1, cz, 83); }
}

// Carve one 3-wide mineshaft corridor arm from a centre along (dirx,dirz), with a plank floor,
// periodic wood support frames, and only the columns inside this chunk written.
/// One mineshaft corridor arm: centre, direction, length and floor height.
struct Corridor { cx: i32, cz: i32, dirx: i32, dirz: i32, len: i32, fy: usize }
fn carve_corridor(chunk: &mut Chunk, ox: i32, oz: i32, corridor: Corridor) {
    let Corridor { cx, cz, dirx, dirz, len, fy } = corridor;
    for i in 0..len {
        let (ax, az) = (cx + dirx * i, cz + dirz * i);
        for p in -1..=1 {
            let wx = ax + if dirx != 0 { 0 } else { p };
            let wz = az + if dirz != 0 { 0 } else { p };
            if !in_chunk(wx, wz, ox, oz) { continue; }
            let (lx, lz) = ((wx - ox) as usize, (wz - oz) as usize);
            chunk.set_block(lx, fy, lz, 10); // plank floor
            for dy in 1..=2 { if fy + dy < CHUNK_HEIGHT { chunk.set_block(lx, fy + dy, lz, 0); } }
        }
        if i % 6 == 0 {
            for &p in &[-1i32, 1] {
                let wx = ax + if dirx != 0 { 0 } else { p };
                let wz = az + if dirz != 0 { 0 } else { p };
                if in_chunk(wx, wz, ox, oz) { let (lx, lz) = ((wx - ox) as usize, (wz - oz) as usize); for dy in 1..=2 { if fy + dy < CHUNK_HEIGHT { chunk.set_block(lx, fy + dy, lz, 4); } } } // posts
            }
            for p in -1..=1 {
                let wx = ax + if dirx != 0 { 0 } else { p };
                let wz = az + if dirz != 0 { 0 } else { p };
                if in_chunk(wx, wz, ox, oz) && fy + 3 < CHUNK_HEIGHT { chunk.set_block((wx - ox) as usize, fy + 3, (wz - oz) as usize, 4); } // beam
            }
        }
    }
}

// A small underground dungeon room: mossy-cobble shell, a light, and a couple of loot blocks.
fn build_dungeon(chunk: &mut Chunk, cx: usize, cz: usize, y: usize, r: u64) {
    if cx + 4 >= 16 || cz + 4 >= 16 || y + 5 >= CHUNK_HEIGHT || y < 2 { return; }
    for gx in 0..5 { for gz in 0..5 { for gy in 0..5 {
        let edge = gx == 0 || gx == 4 || gz == 0 || gz == 4 || gy == 0 || gy == 4;
        let id = if edge { if (gx + gz + gy) % 3 == 0 { 15 } else { 8 } } else { 0 };
        chunk.set_block(cx + gx, y + gy, cz + gz, id);
    }}}
    chunk.set_block(cx + 2, y + 3, cz + 2, 77); // glowstone light
    let _ = r;
    // Loot chests in the corners.
    chunk.set_block(cx + 1, y + 1, cz + 1, 83);
    chunk.set_block(cx + 3, y + 1, cz + 3, 83);
}

// A stepped sandstone desert temple with a small hidden chamber of loot chests underneath.
fn build_temple(chunk: &mut Chunk, cx: usize, cz: usize, base: usize) {
    let (c, cz2) = (cx as i32, cz as i32);
    if c - 4 < 0 || c + 4 >= 16 || cz2 - 4 < 0 || cz2 + 4 >= 16 || base + 6 >= CHUNK_HEIGHT { return; }
    for gy in 0..6i32 {
        let r = 4 - gy;
        for gx in -r..=r { for gz in -r..=r {
            chunk.set_block((c + gx) as usize, base + gy as usize, (cz2 + gz) as usize, 38); // sandstone
        }}
    }
    for gy in 1..=3 { for gx in -1i32..=1 { for gz in -1i32..=1 {
        let y = base as i32 - gy;
        if y >= 1 { chunk.set_block((c + gx) as usize, y as usize, (cz2 + gz) as usize, 0); }
    }}}
    let fy = base as i32 - 3;
    if fy >= 1 {
        chunk.set_block((c - 1) as usize, fy as usize, (cz2 - 1) as usize, 83);
        chunk.set_block((c + 1) as usize, fy as usize, (cz2 + 1) as usize, 83);
        if base >= 1 { chunk.set_block(cx, base - 1, cz, 77); }
    }
}

// An underground stronghold room: brick/cobble shell, loot chests, and a decorative obsidian frame.
fn build_stronghold(chunk: &mut Chunk, cx: usize, cz: usize, y: usize) {
    if cx + 6 >= 16 || cz + 6 >= 16 || y + 5 >= CHUNK_HEIGHT || y < 2 { return; }
    for gx in 0..7 { for gz in 0..7 { for gy in 0..5 {
        let edge = gx == 0 || gx == 6 || gz == 0 || gz == 6 || gy == 0 || gy == 4;
        let id = if edge { if (gx + gz + gy) % 4 == 0 { 54 } else { 8 } } else { 0 };
        chunk.set_block(cx + gx, y + gy, cz + gz, id);
    }}}
    chunk.set_block(cx + 1, y + 1, cz + 1, 83);
    chunk.set_block(cx + 5, y + 1, cz + 5, 83);
    chunk.set_block(cx + 3, y + 3, cz + 3, 77); // glowstone
    for gx in 2..=4 { chunk.set_block(cx + gx, y + 1, cz + 3, 78); chunk.set_block(cx + gx, y + 3, cz + 3, 78); }
    for gy in 1..=3 { chunk.set_block(cx + 2, y + gy, cz + 3, 78); chunk.set_block(cx + 4, y + gy, cz + 3, 78); }
    // Active End portal: a 2x2 pool of end-portal blocks on the floor — walk in to reach the End.
    for gx in 2..=3 { for gz in 2..=3 {
        chunk.set_block(cx + gx, y, cz + gz, 85);       // end-stone rim under the portal
        chunk.set_block(cx + gx, y + 1, cz + gz, 87);   // end portal
    }}
}

// A nether-brick fortress bridge section carved into the netherrack: a walkway with railings,
// arched pillars, glowstone lamps and loot chests. Built above the lava line in the Nether.
fn build_nether_fortress(chunk: &mut Chunk, cx: usize, cz: usize, base: usize, r: u64) {
    if cx + 8 >= 16 || cz + 8 >= 16 || base + 8 >= CHUNK_HEIGHT || base < 2 { return; }
    // Hollow out a hall.
    for gx in 0..9 { for gz in 0..9 { for gy in 1..7 { chunk.set_block(cx + gx, base + gy, cz + gz, 0); }}}
    // Nether-brick floor + surrounding railing.
    for gx in 0..9 { for gz in 0..9 {
        chunk.set_block(cx + gx, base, cz + gz, 55);
        let edge = gx == 0 || gx == 8 || gz == 0 || gz == 8;
        if edge && (gx + gz) % 2 == 0 { chunk.set_block(cx + gx, base + 1, cz + gz, 55); } // low railing
    }}
    // Four arched corner pillars up to the ceiling beam.
    for &(px, pz) in &[(0usize, 0usize), (8, 0), (0, 8), (8, 8)] {
        for gy in 1..=6 { chunk.set_block(cx + px, base + gy, cz + pz, 55); }
    }
    // Ceiling beams across the top.
    for gx in 0..9 { chunk.set_block(cx + gx, base + 6, cz, 55); chunk.set_block(cx + gx, base + 6, cz + 8, 55); }
    for gz in 0..9 { chunk.set_block(cx, base + 6, cz + gz, 55); chunk.set_block(cx + 8, base + 6, cz + gz, 55); }
    // Glowstone lamps hung under the beams + loot chests along the walkway.
    chunk.set_block(cx + 4, base + 6, cz + 4, 77);
    chunk.set_block(cx + 2, base + 5, cz + 2, 77);
    chunk.set_block(cx + 6, base + 5, cz + 6, 77);
    chunk.set_block(cx + 1, base + 1, cz + 4, 83);
    chunk.set_block(cx + 7, base + 1, cz + 4, 83);
    let _ = r;
}

// An End City: a tall purpur tower on an end-stone island, capped with a loot chest. Simple and
// deterministic (single chunk), rising from the island surface.
fn build_end_city(chunk: &mut Chunk, cx: usize, cz: usize, base: usize, r: u64) {
    if cx + 4 >= 16 || cz + 4 >= 16 || base + 18 >= CHUNK_HEIGHT { return; }
    let height = 12 + (r % 6) as usize;
    // Hollow purpur tower shell (5x5) with a room every few floors.
    for gy in 0..height { for gx in 0..5 { for gz in 0..5 {
        let edge = gx == 0 || gx == 4 || gz == 0 || gz == 4;
        let floor = gy % 5 == 0;
        chunk.set_block(cx + gx, base + gy, cz + gz, if edge || floor { 89 } else { 0 });
    }}}
    // Battlement crown of end-stone bricks.
    for gx in 0..5 { for gz in 0..5 {
        if (gx == 0 || gx == 4 || gz == 0 || gz == 4) && (gx + gz) % 2 == 0 {
            chunk.set_block(cx + gx, base + height, cz + gz, 56);
        }
    }}
    // Glowstone lantern + loot chest in the top room.
    let top = base + height - 4;
    chunk.set_block(cx + 2, base + height - 1, cz + 2, 77);
    chunk.set_block(cx + 1, top, cz + 1, 83);
    chunk.set_block(cx + 3, top, cz + 3, 83);
}

pub struct TerrainGen {
    perlin_height: Perlin,
    perlin_detail: Perlin,
    perlin_cave: Perlin,
    perlin_biome: Perlin,
    perlin_ore: Perlin,
    seed: u32,
    pub dim: u8, // 0 overworld, 1 nether, 2 end
}

// Ore/stone-variant veins, richest first so a rare vein wins where two overlap.
// (block id, noise offset, noise scale, threshold, min y, max y)
type Vein = (Id, f64, f64, f64, i32, i32);
const OVERWORLD_VEINS: [Vein; 11] = [
    (22, 947.0, 0.115, 0.80, 62, 118), // emerald — mountains only, the rarest surface find
    (20, 823.0, 0.105, 0.78,  5,  20), // diamond — deepest
    (90, 179.0, 0.095, 0.76,  5,  58), // silver — rarer than iron, never near the surface
    (21, 601.0, 0.100, 0.72,  5,  30), // redstone
    (19, 137.0, 0.090, 0.68,  5,  74), // iron
    (97, 233.0, 0.085, 0.66,  5,  88), // copper — shallow and plentiful
    (98, 389.0, 0.100, 0.76,  5,  40), // gold — deep and scarce
    (18,   7.0, 0.080, 0.62,  5, 112), // coal — large shallow seams
    (14, 311.0, 0.060, 0.70,  5, 100), // gravel pockets
    (16, 419.0, 0.055, 0.66,  5,  96), // diorite blobs
    (75, 733.0, 0.050, 0.66,  5,  34), // tuff blobs, deep
];
// Nether ores: cinnabar and sulfur, the feedstock for the alloy line.
const NETHER_VEINS: [Vein; 2] = [
    (92, 271.0, 0.100, 0.74, 4, 118), // cinnabar
    (91, 563.0, 0.090, 0.68, 4, 118), // sulfur
];
// Below this depth plain stone becomes deepslate (with a noisy transition band above it).
const DEEPSLATE_Y: i32 = 16;

include!("generator_part1.rs");
include!("generator_part2.rs");
#[cfg(test)]
#[path = "generator_tests.rs"]
mod generator_tests;

#[cfg(test)]
#[path = "generator_coverage_tests.rs"]
mod generator_coverage_tests;
