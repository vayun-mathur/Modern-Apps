//! Inventory tables and helpers: const constructors at runtime, fuel, categories.

use super::*;

#[test]
fn recipe_and_smelt_constructors_and_fuel() {
    // The table constructors are const fns; calling them at runtime must produce
    // exactly the field layout the static tables rely on.
    let rec = r(10, 2, 20, 3, 30, 4, 40);
    assert_eq!((rec.in1, rec.n1, rec.in2, rec.n2, rec.out, rec.out_n, rec.unlocked_by), (10, 2, 20, 3, 30, 4, 40));
    let sm = smelt((10, 2), (0, 0), (30, 1), 5.5, true);
    assert_eq!((sm.in1, sm.n1, sm.in2, sm.n2, sm.out, sm.out_n, sm.blast), (10, 2, 0, 0, 30, 1, true));
    assert!((sm.secs - 5.5).abs() < 1e-6);
    // Every table entry is well-formed.
    for rec in RECIPES.iter() {
        assert!(rec.in1 != 0 && rec.n1 > 0 && rec.out != 0 && rec.out_n > 0, "recipe for {} is malformed", rec.out);
        if rec.in2 == 0 { assert_eq!(rec.n2, 0); } else { assert!(rec.n2 > 0); }
    }
    for sm in SMELTING.iter() {
        assert!(sm.in1 != 0 && sm.out != 0 && sm.secs > 0.0, "smelt for {} is malformed", sm.out);
    }
    // Fuel values: coal family burns longest, sticks barely, non-fuel never.
    assert!(fuel_secs(1053) > fuel_secs(1055));
    assert_eq!(fuel_secs(1055), 5.0, "sticks are the emergency fuel");
    assert_eq!(fuel_secs(1), 0.0, "stone does not burn");
    assert_eq!(fuel_secs(0), 0.0);
    for id in [1053u16, 1055, 4, 10] { assert!(fuel_secs(id) >= 0.0); }
    // Categories and the tech-tree gate.
    assert!(!recipe_category(1059).is_empty());
    let crafted = vec![false; RECIPES.len()];
    let gated = RECIPES.iter().position(|x| x.unlocked_by != 0);
    if let Some(i) = gated { assert!(!recipe_unlocked(i, &crafted) || RECIPES[i].unlocked_by == 0); }
    assert!(recipe_unlocked(9, &crafted), "planks are available from the first minute");
    assert!(!recipe_unlocked(RECIPES.len() + 5, &crafted), "out-of-range recipes are locked, never panic");
    // Cut variants only turn real blocks into their slab/stair family.
    for cut in cut_variants() {
        assert!(cut.input != 0 && cut.output != 0 && cut.count > 0);
        assert!(cut.output <= crate::world::block::MAX_BLOCK_ID, "a cut must produce a real block");
    }
}

#[test]
fn select_move_give_and_full_inventory_edges() {
    let mut inv = Inventory::empty();
    inv.select(8);
    assert_eq!(inv.selected, 8);
    inv.select(9);
    assert_eq!(inv.selected, 8, "selection is a hotbar index only");
    inv.select(usize::MAX);
    assert_eq!(inv.selected, 8);
    // Move into an empty slot relocates the stack.
    inv.slots[0] = InvSlot { id: 5, count: 3 };
    inv.move_item(0, 20);
    assert_eq!(inv.slots[0].id, 0);
    assert_eq!((inv.slots[20].id, inv.slots[20].count), (5, 3));
    // Out-of-range and self moves are no-ops.
    inv.move_item(20, 20);
    inv.move_item(99, 0);
    inv.move_item(0, 99);
    assert_eq!(inv.slots[20].id, 5);
    // give() tops up a matching stack when there is no empty slot left.
    for (i, slot) in inv.slots.iter_mut().enumerate() { *slot = InvSlot { id: 100 + i as u16, count: 1 }; }
    inv.give(105);
    assert_eq!(inv.slots[5].count, STACK, "give must refill the matching slot when full");
    assert!(!inv.try_add_block(777), "a full inventory of other ids accepts nothing");
    inv.give(0);
    assert!(inv.slots.iter().all(|s| s.id != 0), "giving air changes nothing");
}
