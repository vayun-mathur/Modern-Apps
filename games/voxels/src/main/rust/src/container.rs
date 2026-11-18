// Block-position-keyed storage for chests. Terrain-generated chests fill themselves with loot the
// first time they're opened; chests the player places start empty (they're registered at place time
// so the two cases stay distinguishable). Contents live outside the chunk format — chunks store a
// flat byte per block — so they get their own JSON file next to player.json.

use crate::inventory::InvSlot;
use crate::world::block::Id;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

pub const CONTAINER_SLOTS: usize = 27;

// (dimension, x, y, z)
pub type ContainerKey = (u8, i32, i32, i32);

#[derive(Default)]
pub struct Containers {
    map: HashMap<ContainerKey, Vec<InvSlot>>,
}

#[derive(Serialize, Deserialize)]
struct ContainerEntry { dim: u8, x: i32, y: i32, z: i32, slots: Vec<InvSlot> }

impl Containers {
    pub fn get(&self, key: ContainerKey) -> Option<&Vec<InvSlot>> { self.map.get(&key) }
    pub fn contains(&self, key: ContainerKey) -> bool { self.map.contains_key(&key) }
    // A freshly placed chest is empty by definition: overwrite rather than inherit any entry that
    // outlived the block that owned it.
    pub fn insert_empty(&mut self, key: ContainerKey) {
        self.map.insert(key, vec![InvSlot::default(); CONTAINER_SLOTS]);
    }
    pub fn insert(&mut self, key: ContainerKey, slots: Vec<InvSlot>) { self.map.insert(key, slots); }
    pub fn remove(&mut self, key: ContainerKey) -> Option<Vec<InvSlot>> { self.map.remove(&key) }
    pub fn slot_mut(&mut self, key: ContainerKey, idx: usize) -> Option<&mut InvSlot> {
        self.map.get_mut(&key)?.get_mut(idx)
    }
    // Merge a stack into the container, returning what wouldn't fit.
    pub fn add(&mut self, key: ContainerKey, id: Id, mut count: i32) -> i32 {
        let Some(slots) = self.map.get_mut(&key) else { return count; };
        if crate::item::has_durability(id) {
            // Tools and armor never stack: they need a slot of their own to keep their durability.
            if let Some(s) = slots.iter_mut().find(|s| s.id == 0) { *s = InvSlot { id, count }; return 0; }
            return count;
        }
        for s in slots.iter_mut() {
            if count <= 0 { break; }
            if s.id == id && s.count < crate::inventory::STACK {
                let mv = count.min(crate::inventory::STACK - s.count);
                s.count += mv; count -= mv;
            }
        }
        for s in slots.iter_mut() {
            if count <= 0 { break; }
            if s.id == 0 {
                let mv = count.min(crate::inventory::STACK);
                *s = InvSlot { id, count: mv }; count -= mv;
            }
        }
        count
    }

    pub fn to_json(&self, key: ContainerKey) -> String {
        let empty = vec![InvSlot::default(); CONTAINER_SLOTS];
        let slots = self.map.get(&key).unwrap_or(&empty);
        serde_json::json!({ "slots": slots }).to_string()
    }

    pub fn save(&self, base: &str) -> std::io::Result<()> {
        let entries: Vec<ContainerEntry> = self.map.iter()
            .map(|(&(dim, x, y, z), slots)| ContainerEntry { dim, x, y, z, slots: slots.clone() })
            .collect();
        // Bail out rather than renaming an empty file over a good save and wiping every chest.
        let json = serde_json::to_string(&entries)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))?;
        let path = container_file(base);
        if let Some(p) = path.parent() { fs::create_dir_all(p)?; }
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, json)?;
        fs::rename(tmp, path)?;
        Ok(())
    }

    pub fn load(base: &str) -> Self {
        let Ok(s) = fs::read_to_string(container_file(base)) else { return Self::default(); };
        let Ok(entries): Result<Vec<ContainerEntry>, _> = serde_json::from_str(&s) else { return Self::default(); };
        let mut map = HashMap::new();
        for mut e in entries {
            e.slots.resize(CONTAINER_SLOTS, InvSlot::default());
            map.insert((e.dim, e.x, e.y, e.z), e.slots);
        }
        Self { map }
    }
}

fn container_file(base: &str) -> PathBuf {
    Path::new(base).join("containers.json")
}

// Deterministic loot for a world-generated chest, from its position. The End's chests hold the
// endgame reward pool; everywhere else draws from the general pool.
pub fn roll_loot(x: i32, y: i32, z: i32, dim: u8, lucky: bool) -> Vec<InvSlot> {
    let mut r = ((x as u64).wrapping_mul(73856093) ^ (y as u64).wrapping_mul(19349663) ^ (z as u64).wrapping_mul(83492791)) | 1;
    let next = |r: &mut u64| { *r ^= *r << 13; *r ^= *r >> 7; *r ^= *r << 17; *r };
    // (item id, max stack from this chest).
    let end_pool: [(Id, i32); 9] = [(1084, 1), (1072, 1), (1071, 1), (1066, 1), (1025, 1), (1051, 3), (1052, 4), (1029, 2), (24, 2)];
    let nether_pool: [(Id, i32); 7] = [(1088, 6), (1090, 2), (1091, 2), (1053, 8), (1050, 4), (1029, 1), (1034, 3)];
    let over_pool: [(Id, i32); 12] = [(1053, 8), (1050, 4), (1051, 1), (1052, 2), (1027, 4), (1029, 1), (1064, 1), (1024, 1), (1034, 3), (1033, 2), (1089, 2), (1119, 3)];
    let pool: &[(Id, i32)] = match dim { 2 => &end_pool, 1 => &nether_pool, _ => &over_pool };

    let mut slots = vec![InvSlot::default(); CONTAINER_SLOTS];
    // Glaucus turns up more in every chest.
    let n = 3 + (next(&mut r) % 4) as usize + if lucky { 3 } else { 0 }; // 3..6 stacks, 6..9 when lucky
    for _ in 0..n {
        let (id, maxc) = pool[(next(&mut r) as usize) % pool.len()];
        let count = if crate::item::has_durability(id) {
            crate::item::max_durability(id)
        } else {
            1 + (next(&mut r) % maxc as u64) as i32
        };
        // Scatter the stacks over the grid rather than packing them into the first rows.
        let mut idx = (next(&mut r) as usize) % CONTAINER_SLOTS;
        for _ in 0..CONTAINER_SLOTS {
            if slots[idx].id == 0 { break; }
            idx = (idx + 1) % CONTAINER_SLOTS;
        }
        if slots[idx].id == 0 { slots[idx] = InvSlot { id, count }; }
    }
    slots
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::inventory::{Inventory, InvSlot, STACK};

    #[test]
    fn generated_chests_have_loot_and_player_chests_do_not() {
        let mut c = Containers::default();
        let key = (0u8, 12, 40, -7);
        c.insert(key, roll_loot(12, 40, -7, 0, false));
        let filled = c.get(key).unwrap().iter().filter(|s| s.id != 0).count();
        assert!((3..=6).contains(&filled), "expected 3..6 loot stacks, got {filled}");

        let placed = (0u8, 1, 2, 3);
        c.insert_empty(placed);
        assert!(c.get(placed).unwrap().iter().all(|s| s.id == 0));
    }

    // If a chest is destroyed without its entry being cleaned up, placing a new chest on the same
    // block must not inherit the old loot — that would be a repeatable duplication.
    #[test]
    fn a_placed_chest_never_inherits_a_stale_entry() {
        let mut c = Containers::default();
        let key = (0u8, 4, 61, 4);
        c.insert(key, roll_loot(4, 61, 4, 0, false));
        assert!(c.get(key).unwrap().iter().any(|s| s.id != 0));

        c.insert_empty(key);
        assert!(c.get(key).unwrap().iter().all(|s| s.id == 0), "a new chest must start empty");
    }

    #[test]
    fn every_loot_pool_id_names_something_real() {
        use crate::world::block::is_real_id;
        // Every dimension's pool, sampled across enough positions to hit each entry.
        for dim in 0..3u8 {
            for i in 0..80i32 {
                for s in roll_loot(i * 11, 40, i * 17, dim, i % 2 == 0) {
                    if s.id == 0 { continue; }
                    assert!(is_real_id(s.id), "dim {dim} loot contains {}, which is nothing", s.id);
                }
            }
        }
    }

    #[test]
    fn loot_is_deterministic_per_position() {
        let a = roll_loot(5, 30, 9, 0, false);
        let b = roll_loot(5, 30, 9, 0, false);
        let other = roll_loot(6, 30, 9, 0, false);
        assert_eq!(a.iter().map(|s| (s.id, s.count)).collect::<Vec<_>>(),
                   b.iter().map(|s| (s.id, s.count)).collect::<Vec<_>>());
        assert_ne!(a.iter().map(|s| s.id).collect::<Vec<_>>(),
                   other.iter().map(|s| s.id).collect::<Vec<_>>());
    }

    #[test]
    fn add_returns_the_overflow_when_full() {
        let mut c = Containers::default();
        let key = (0u8, 0, 0, 0);
        c.insert_empty(key);
        // Fill every slot with stone, then try to add one more.
        let left = c.add(key, 1, STACK * CONTAINER_SLOTS as i32);
        assert_eq!(left, 0);
        assert_eq!(c.add(key, 1, 5), 5, "a full chest must reject the whole stack");
    }

    // Taking from a chest into a full inventory must leave the remainder in the chest rather than
    // deleting it.
    #[test]
    fn take_from_leaves_the_remainder_when_inventory_is_full() {
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot { id: 2, count: STACK }; } // all dirt, no room
        let mut slot = InvSlot { id: 1051, count: 7 }; // 7 diamonds in the chest
        inv.take_from(&mut slot);
        assert_eq!(slot.count, 7, "nothing fit, so nothing should have left the chest");
        assert_eq!(slot.id, 1051);
    }

    #[test]
    fn take_from_moves_what_fits() {
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot { id: 2, count: STACK }; }
        inv.slots[5] = InvSlot { id: 1051, count: STACK - 3 }; // room for exactly 3 diamonds
        let mut slot = InvSlot { id: 1051, count: 10 };
        inv.take_from(&mut slot);
        assert_eq!(slot.count, 7, "only 3 of 10 should have moved");
        assert_eq!(inv.slots[5].count, STACK);
    }
}

#[cfg(test)]
#[path = "container_coverage_tests.rs"]
mod container_coverage_tests;
