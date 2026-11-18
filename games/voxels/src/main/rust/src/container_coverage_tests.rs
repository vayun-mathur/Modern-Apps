//! Container storage beyond the inline tests: JSON, persistence, stacking.

use super::*;
use crate::inventory::STACK;

#[test]
fn to_json_for_missing_and_present_keys() {
    let mut c = Containers::default();
    let missing: serde_json::Value = serde_json::from_str(&c.to_json((0, 9, 9, 9))).unwrap();
    assert_eq!(missing["slots"].as_array().map(|a| a.len()), Some(CONTAINER_SLOTS));
    assert!(missing["slots"].as_array().unwrap().iter().all(|s| s["id"] == 0));
    let key = (1u8, 2, 3, 4);
    c.insert_empty(key);
    assert!(c.contains(key));
    assert_eq!(c.slot_mut(key, 0).map(|s| s.id), Some(0));
    assert!(c.slot_mut(key, CONTAINER_SLOTS).is_none(), "out-of-range slot must be None");
    assert!(c.slot_mut((9, 0, 0, 0), 0).is_none());
    let removed = c.remove(key);
    assert_eq!(removed.map(|v| v.len()), Some(CONTAINER_SLOTS));
    assert!(!c.contains(key));
    assert!(c.remove(key).is_none());
}

#[test]
fn add_stacks_merges_then_fills_and_tools_take_whole_slots() {
    let mut c = Containers::default();
    let key = (0u8, 0, 0, 0);
    // No container at all: everything comes back.
    assert_eq!(c.add(key, 1, 7), 7);
    c.insert_empty(key);
    assert_eq!(c.add(key, 1, 70), 0, "70 stone fits as 64+6");
    let slots = c.get(key).unwrap();
    assert_eq!(slots[0].count, STACK);
    assert_eq!(slots[1].count, 6);
    // A durable tool never stacks and reports its durability as the count.
    let tool = 1065u16; // diamond pickaxe
    assert_eq!(c.add(key, tool, crate::item::max_durability(tool)), 0);
    let slots = c.get(key).unwrap();
    assert!(slots.iter().any(|s| s.id == tool && s.count == crate::item::max_durability(tool)));
}

#[test]
fn save_load_round_trip_and_corrupt_files_degrade_to_empty() {
    let dir = std::env::temp_dir().join(format!("voxels_cont_{}", std::process::id()));
    let _ = std::fs::create_dir_all(&dir);
    let base = dir.to_string_lossy().into_owned();
    let mut c = Containers::default();
    let key = (2u8, -3, 64, 8);
    c.insert_empty(key);
    c.add(key, 24, 3);
    c.save(&base).expect("save");
    let back = Containers::load(&base);
    assert_eq!(back.get(key).map(|v| v.iter().map(|s| s.count).sum::<i32>()), Some(3));
    // Short slot lists in a hand-edited file are padded, not dropped.
    std::fs::write(dir.join("containers.json"), r#"[{"dim":0,"x":1,"y":2,"z":3,"slots":[{"id":5,"count":1}]}]"#).unwrap();
    let padded = Containers::load(&base);
    assert_eq!(padded.get((0, 1, 2, 3)).map(|v| v.len()), Some(CONTAINER_SLOTS));
    // Garbage and missing files are an empty store, never a panic.
    std::fs::write(dir.join("containers.json"), "not json").unwrap();
    assert!(Containers::load(&base).get(key).is_none());
    assert!(Containers::load("/nonexistent/voxels/nope").get(key).is_none());
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn loot_counts_and_lucky_bonus() {
    for dim in 0..3u8 {
        let plain = roll_loot(10, 40, -4, dim, false).iter().filter(|s| s.id != 0).count();
        assert!((3..=6).contains(&plain), "dim {dim} plain loot {plain}");
        let lucky = roll_loot(10, 40, -4, dim, true).iter().filter(|s| s.id != 0).count();
        assert!((6..=9).contains(&lucky), "dim {dim} lucky loot {lucky}");
        // Counts never exceed the pool's stated maxima (durables carry durability instead).
        for s in roll_loot(3, 40, 3, dim, false) {
            if s.id != 0 && !crate::item::has_durability(s.id) { assert!(s.count >= 1 && s.count <= 8); }
        }
    }
    // Negative coordinates hash without panicking.
    assert!(roll_loot(-100, -5, -100, 0, false).iter().any(|s| s.id != 0));
}

#[test]
fn add_merges_into_partial_stacks_and_full_chests_refuse_tools() {
    let mut c = Containers::default();
    let key = (0u8, 1, 1, 1);
    c.insert_empty(key);
    assert_eq!(c.add(key, 1, 70), 0);
    assert_eq!(c.add(key, 1, 5), 0, "five more stone merge into the partial stack");
    assert_eq!(c.get(key).unwrap()[1].count, 11);
    // Fill every slot, then a durable tool (which needs a whole slot) is refused whole.
    assert_eq!(c.add(key, 1, STACK * CONTAINER_SLOTS as i32), 75, "only the (64-11) + 25*64 free spaces fill");
    assert_eq!(c.add(key, 1065, crate::item::max_durability(1065)), crate::item::max_durability(1065));
}
