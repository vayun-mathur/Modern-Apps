//! Crafting, smelting, trading and container-transfer rules driven
//! through the real recipe tables (looked up by output, never by a
//! hard-coded index, so a table reorder cannot silently retarget them).

use super::*;
use crate::item::{has_durability, max_durability};
use crate::villager::Offer;

fn recipe_for(out: Id) -> usize {
    RECIPES.iter().position(|r| r.out == out).expect("recipe exists")
}

fn unlocked_crafted() -> Vec<bool> {
    vec![true; RECIPES.len()]
}

#[test]
fn recipe_unlocking_follows_the_tech_tree() {
    assert!(!recipe_unlocked(RECIPES.len(), &[]), "no recipe lives past the table");
    assert!(!recipe_unlocked(usize::MAX, &[]));
    let gated = RECIPES.iter().position(|r| r.unlocked_by != 0).expect("the tree has gated recipes");
    let mut crafted = vec![false; RECIPES.len()];
    assert!(!recipe_unlocked(gated, &crafted), "a gated recipe starts locked");
    let parent = RECIPES.iter().position(|r| r.out == RECIPES[gated].unlocked_by).unwrap();
    crafted[parent] = true;
    assert!(recipe_unlocked(gated, &crafted), "crafting the prerequisite unlocks it");
    let free = RECIPES.iter().position(|r| r.unlocked_by == 0).expect("some recipes are free");
    assert!(recipe_unlocked(free, &[]), "prerequisite-free recipes are always open");
    // Categories are a pure function of the output id.
    assert_eq!(recipe_category(1026), "food"); // apple dishes classify as food
    assert_eq!(recipe_category(Block::Stone as Id), "block");
    assert_eq!(recipe_category(1056), "blessing");
}

#[test]
fn crafting_consumes_inputs_and_respects_locks() {
    // Flint & steel: 1 iron (1050) + 1 coal item (1053) -> 1082, gated on
    // having crafted whatever produces 1061. Use the table itself.
    let idx = recipe_for(1082);
    let recipe = &RECIPES[idx];
    let mut inv = Inventory::empty();
    for _ in 0..recipe.n1 { inv.add_block(recipe.in1); }
    if recipe.in2 != 0 { for _ in 0..recipe.n2 { inv.add_block(recipe.in2); } }
    assert!(!inv.craft(idx, &[]), "a locked recipe consumes nothing and fails");
    assert_eq!(inv.count_of(recipe.in1), recipe.n1, "the failed craft ate an input");
    assert!(inv.craft(idx, &unlocked_crafted()));
    assert_eq!(inv.count_of(recipe.in1), 0);
    if recipe.in2 != 0 { assert_eq!(inv.count_of(recipe.in2), 0); }
    assert_eq!(inv.count_of(1082), max_durability(1082), "tools arrive at full durability");
    assert!(!inv.craft(idx, &unlocked_crafted()), "without inputs a craft fails");
    assert!(!inv.craft(RECIPES.len(), &unlocked_crafted()), "past-the-table crafts fail");
}

#[test]
fn smelting_and_fuel_rules_hold() {
    let iron = SMELTING.iter().find(|s| s.out == 1050).expect("iron smelts");
    let mut inv = Inventory::empty();
    assert!(!inv.can_smelt(iron));
    inv.add_block(iron.in1);
    assert!(inv.can_smelt(iron));
    inv.take_smelt_inputs(iron);
    assert_eq!(inv.count_of(iron.in1), 0);
    inv.give_smelt_output(iron);
    assert_eq!(inv.count_of(1050), iron.out_n);
    // Fuel values: coal burns long, lava longest, stone never.
    assert_eq!(fuel_secs(1053), 80.0);
    assert_eq!(fuel_secs(84), 200.0);
    assert!(fuel_secs(Block::Stone as Id) == 0.0 && fuel_secs(0) == 0.0);
    // Dedicated fuel is burned before building wood, and spared ingredients
    // are never eaten (sulfur is both a fuel and the steel ingredient).
    let mut stove = Inventory::empty();
    stove.slots[0] = InvSlot { id: Block::Wood as Id, count: 2 }; // 4: a log
    stove.slots[1] = InvSlot { id: 1053, count: 1 }; // coal
    assert_eq!(stove.consume_fuel(&[]), Some(80.0), "coal is preferred over logs");
    assert_eq!(stove.slots[1].id, 0, "the coal is spent");
    let mut spared = Inventory::empty();
    spared.slots[0] = InvSlot { id: 1088, count: 1 }; // sulfur
    assert_eq!(spared.consume_fuel(&[1088]), None, "a spared ingredient is not fuel");
    assert_eq!(spared.consume_fuel(&[]), Some(60.0));
    assert_eq!(Inventory::empty().consume_fuel(&[]), None);
}

#[test]
fn stonecutting_and_trading_move_goods_atomically() {
    let cuts = cut_variants();
    assert!(cuts.len() >= 32, "8 materials x 4 conversions plus decor cuts");
    let slab_cut = cuts.iter().position(|c| c.input == Block::Stone as Id && c.output == Block::StoneSlab as Id)
        .expect("stone cuts into slabs");
    assert_eq!(cuts[slab_cut].count, 2, "one cube yields two slabs");
    let mut inv = Inventory::empty();
    assert!(!inv.cut(slab_cut), "cutting without the input fails");
    inv.add_block(Block::Stone as Id);
    assert!(inv.cut(slab_cut));
    assert_eq!(inv.count_of(Block::Stone as Id), 0);
    assert_eq!(inv.count_of(Block::StoneSlab as Id), 2);
    assert!(!inv.cut(cuts.len()), "past-the-table cuts fail");

    // A trade checks both costs before taking either.
    let offer = Offer { cost: 1052, cost_n: 2, cost2: 1050, cost2_n: 1, give: 1027, give_n: 1 };
    let mut trader = Inventory::empty();
    trader.add_block(1052); // one emerald, needs two
    trader.add_block(1050);
    assert!(!trader.trade_offer(&offer));
    assert_eq!(trader.count_of(1050), 1, "a half-affordable trade took the second cost");
    trader.add_block(1052);
    assert!(trader.trade_offer(&offer));
    assert_eq!(trader.count_of(1052), 0);
    assert_eq!(trader.count_of(1027), 1);
}

#[test]
fn container_transfers_and_gear_wear_are_lossless() {
    // take_from moves what fits and leaves the rest behind.
    let mut inv = Inventory::empty();
    let mut chest_slot = InvSlot { id: Block::Stone as Id, count: 70 };
    inv.take_from(&mut chest_slot);
    assert_eq!(inv.count_of(Block::Stone as Id), 64 + 6, "70 stones fill one stack and start another");
    assert_eq!(chest_slot.id, 0, "a fully moved stack empties the container slot");
    let mut full = Inventory::empty();
    for s in full.slots.iter_mut() { *s = InvSlot { id: Block::Dirt as Id, count: STACK }; }
    let mut stuck = InvSlot { id: Block::Stone as Id, count: 3 };
    full.take_from(&mut stuck);
    assert_eq!(stuck.count, 3, "a full inventory must not void container contents");
    // A durable item moves whole (its count is durability, not a stack).
    let mut tool_inv = Inventory::empty();
    let mut tool = InvSlot { id: 1065, count: 250 };
    tool_inv.take_from(&mut tool);
    assert_eq!(tool.id, 0);
    assert_eq!(tool_inv.count_of(1065), 250);
    assert!(has_durability(1065));
    // Armor equips into its own slot and returns the displaced piece.
    let mut wearer = Inventory::empty();
    assert!(wearer.equip_armor(1067, 240).is_none());
    let displaced = wearer.equip_armor(1071, 528).expect("the iron helm is displaced");
    assert_eq!((displaced.id, displaced.count), (1067, 240));
    assert!((wearer.armor_defense() - crate::item::armor_defense(1071)).abs() < 1e-6);
    wearer.damage_armor();
    assert_eq!(wearer.armor[0].count, 527, "a hit wears every equipped piece by one");
    wearer.mend_one();
    assert_eq!(wearer.armor[0].count, 528, "mending restores the most worn piece first");
    // selected_count / take_selected guard the hotbar bounds.
    let mut held = Inventory::empty();
    held.slots[0] = InvSlot { id: 5, count: 2 };
    assert_eq!(held.selected_count(), 2);
    assert_eq!(held.take_selected(), Some((5, 2)));
    assert_eq!(held.take_selected(), None);
}

#[test]
fn dragging_stacks_merges_but_durable_gear_swaps() {
    // Stackables merge up to the stack cap and keep the remainder.
    let mut inv = Inventory::empty();
    inv.slots[0] = InvSlot { id: Block::Stone as Id, count: 60 };
    inv.slots[1] = InvSlot { id: Block::Stone as Id, count: 10 };
    inv.move_item(0, 1);
    assert_eq!((inv.slots[1].id, inv.slots[1].count), (Block::Stone as Id, STACK));
    assert_eq!((inv.slots[0].id, inv.slots[0].count), (Block::Stone as Id, 6));
    // A tool's count is its durability, not a stack size: dragging one
    // pickaxe onto another must swap them, never fuse two tools into a
    // single slot with summed (and over-maximum) durability.
    let mut gear = Inventory::empty();
    gear.slots[0] = InvSlot { id: 1065, count: 100 };
    gear.slots[1] = InvSlot { id: 1065, count: 200 };
    gear.move_item(0, 1);
    assert_eq!((gear.slots[0].id, gear.slots[0].count), (1065, 200), "the tools must swap");
    assert_eq!((gear.slots[1].id, gear.slots[1].count), (1065, 100));
    assert!(gear.slots.iter().all(|s| s.count <= max_durability(s.id) || s.id == 0));
    // Out-of-range and self moves are no-ops.
    gear.move_item(0, SLOTS);
    gear.move_item(1, 1);
    assert_eq!(gear.slots[1].count, 100);
}

#[test]
fn inventory_json_round_trips() {
    let mut inv = Inventory::empty();
    inv.slots[3] = InvSlot { id: 42, count: 7 };
    inv.armor[1] = InvSlot { id: 1068, count: 200 };
    inv.selected = 2;
    let back: Inventory = serde_json::from_str(&inv.to_json()).unwrap();
    assert_eq!((back.slots[3].id, back.slots[3].count), (42, 7));
    assert_eq!(back.armor[1].id, 1068);
    assert_eq!(back.selected, 2);
    assert_eq!(back.slots.len(), SLOTS);
    // The starter kit is exactly the documented grant.
    let starter = Inventory::default();
    assert_eq!(starter.count_of(Block::Grass as Id), 64);
    assert_eq!(starter.count_of(Block::Brick as Id), 16);
    assert_eq!(starter.placed, 0);
}
