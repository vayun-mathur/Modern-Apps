// Non-block inventory items (food, consumables, materials) and the status-effect model.
// Inventory ids below ITEM_BASE are blocks; ids at or above it are items and are never placeable.
// Design follows the Matcha/Raspberry survival overhaul: no hunger — food applies Regeneration
// (the heal) plus a themed buff; Estus is a fast multi-charge heal; Heart Containers raise max HP.

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Effect { Regeneration, Poison, Resistance, Strength, Speed, Haste, Absorption, FireResistance, NightVision, JumpBoost, Slowness }

impl Effect {
    pub fn key(self) -> &'static str {
        match self {
            Effect::Regeneration => "regen",
            Effect::Poison => "poison",
            Effect::Resistance => "resist",
            Effect::Strength => "strength",
            Effect::Speed => "speed",
            Effect::Haste => "haste",
            Effect::Absorption => "absorb",
            Effect::FireResistance => "fireres",
            Effect::NightVision => "night",
            Effect::JumpBoost => "jump",
            Effect::Slowness => "slow",
        }
    }
}

#[derive(Clone, Copy)]
pub struct ActiveEffect { pub kind: Effect, pub secs: f32, pub amp: u8 }

use crate::world::block::Id;

/// Blocks own ids below this; items own it and everything above. One boundary, 16 bits wide, with
/// room on both sides: blocks have 900 spare, items have 64512.
pub const ITEM_BASE: Id = 1024;
// The item block, in order: 1024 Estus .. 1151 Brush. Matcha alloy line: 1088 Sulfur,
// 1089 Silver Ingot, 1090 Quicksilver, 1091 Steel Ingot, 1092 Adamant Ingot, 1093/1094 adamant
// pickaxe/sword, 1095..1098 adamant armor.
pub fn is_item(id: Id) -> bool { id >= ITEM_BASE }
pub fn is_estus(id: Id) -> bool { id == 1024 }
pub fn is_heart_container(id: Id) -> bool { id == 1025 }
pub fn is_food(id: Id) -> bool { food_effects(id).is_some() }

// ---- Tools & armor ----
// Tools 1059..1066: {wood,stone,iron,diamond} x {pickaxe,sword}. Armor 1067..1074: {iron,diamond} x
// {helmet,chestplate,leggings,boots}. Matcha's bronze sits between iron and diamond at 1135..1140,
// and adamant tops the ladder at 1093..1098. Durability is stored in the inventory slot's `count`.
pub fn is_tool(id: Id) -> bool { (1059..=1066).contains(&id) || matches!(id, 1093 | 1094 | 1135 | 1136 | 1148 | 1149 | 1151) }
/// Throwables: a snowball (light damage and knockback) and an ender pearl (teleport to impact).
pub const SNOWBALL: Id = 1086;
pub const ENDER_PEARL: Id = 1087;
/// Brushing suspicious sand is the only thing it does.
pub const BRUSH: Id = 1151;
/// Shears: used on a sheep rather than on terrain, so they're a tool that mines nothing.
pub fn is_shears(id: Id) -> bool { id == 1148 }
pub fn is_pickaxe(id: Id) -> bool { matches!(id, 1059 | 1061 | 1063 | 1065 | 1093 | 1135) }
pub fn is_sword(id: Id) -> bool { matches!(id, 1060 | 1062 | 1064 | 1066 | 1094 | 1136) }
pub fn is_elytra(id: Id) -> bool { id == 1084 }
pub fn is_armor(id: Id) -> bool {
    (1067..=1074).contains(&id) || (1095..=1098).contains(&id) || (1137..=1140).contains(&id) || id == 1084
} // elytra occupies the chest slot
pub fn armor_slot(id: Id) -> usize {
    if id == 1084 { 1 }
    else if id >= 1137 { (id - 1137) as usize }
    else if id >= 1095 { (id - 1095) as usize }
    else { ((id - 1067) % 4) as usize }
} // 0 helm, 1 chest, 2 legs, 3 boots
pub fn is_flint_steel(id: Id) -> bool { id == 1082 }
pub fn is_firework(id: Id) -> bool { id == 1085 }
pub fn has_durability(id: Id) -> bool { is_tool(id) || is_armor(id) || id == 1082 }

pub fn sword_damage(id: Id) -> f32 { match id { 1060 => 5.0, 1062 => 6.0, 1064 => 7.0, 1136 => 8.0, 1066 => 9.0, 1094 => 11.0, _ => 0.0 } }
pub fn pick_damage(id: Id) -> f32 { match id { 1059 => 2.0, 1061 => 3.0, 1063 => 4.0, 1135 => 4.5, 1065 => 5.0, 1093 => 6.0, _ => 0.0 } }
pub fn armor_defense(id: Id) -> f32 {
    match id {
        1067 => 2.0, 1068 => 6.0, 1069 => 5.0, 1070 => 2.0, // iron helm/chest/legs/boots
        1137 => 2.0, 1138 => 7.0, 1139 => 5.0, 1140 => 3.0, // bronze
        1071 => 3.0, 1072 => 8.0, 1073 => 6.0, 1074 => 3.0, // diamond
        1095 => 4.0, 1096 => 9.0, 1097 => 7.0, 1098 => 4.0, // adamant
        1084 => 1.0,                                        // elytra (mobility, minor defense)
        _ => 0.0,
    }
}
pub fn max_durability(id: Id) -> i32 {
    match id {
        1059 | 1060 => 60, 1061 | 1062 => 132, 1063 | 1064 => 250, 1065 | 1066 => 1562, // wood/stone/iron/diamond tools
        1067..=1070 => 240, 1071..=1074 => 528,                                          // iron/diamond armor
        1082 => 64,                                                                      // flint & steel
        1148 => 238,                                                                     // shears
        1149 => 64,                                                                      // fishing rod
        1151 => 64,                                                                      // brush
        1084 => 432,                                                                     // elytra
        1093 | 1094 => 2031,                                                             // adamant tools
        1095..=1098 => 666,                                                              // adamant armor
        1135 | 1136 => 700,                                                              // bronze tools
        1137..=1140 => 380,                                                              // bronze armor
        _ => 0,
    }
}

// Effects applied when a food item is eaten. First entry is the healing Regeneration.
pub fn food_effects(id: Id) -> Option<&'static [(Effect, f32, u8)]> {
    use Effect::*;
    Some(match id {
        1026 => &[(Regeneration, 4.0, 0)],                                   // apple
        1027 => &[(Regeneration, 6.0, 0)],                                   // bread
        1028 => &[(Regeneration, 5.0, 1)],                                   // cooked fish
        1029 => &[(Regeneration, 5.0, 1), (Absorption, 120.0, 0), (Resistance, 30.0, 0), (Strength, 60.0, 0)], // golden apple
        1030 => &[(Regeneration, 3.6, 2), (Haste, 150.0, 1)],               // brownie
        1031 => &[(Regeneration, 4.0, 0), (NightVision, 30.0, 0)],          // carrot (good for your eyes)
        1032 => &[(Regeneration, 4.0, 1), (Speed, 30.0, 0)],                // glistering melon slice
        1042 => &[(Regeneration, 5.0, 0), (FireResistance, 30.0, 0)],       // baked potato (hot!)
        1043 => &[(Regeneration, 2.0, 0)],                                   // cookie
        1044 => &[(Regeneration, 5.0, 1)],                                   // cooked salmon
        1045 => &[(Regeneration, 4.0, 0)],                                   // fried egg
        1046 => &[(Regeneration, 5.0, 1), (JumpBoost, 45.0, 0)],            // cooked rabbit (leaping)
        1047 => &[(Regeneration, 4.0, 0), (Speed, 20.0, 0)],                // apple empanada
        1048 => &[(Regeneration, 4.0, 0), (NightVision, 90.0, 0)],          // glow berry crumble
        1049 => &[(Regeneration, 3.0, 0), (Haste, 60.0, 0)],                // chocolate chip cookie
        // Matcha's kitchen: cooked meat is the survival staple, and each dish above it trades more
        // ingredients for a longer, more specialised buff.
        1119 => &[(Regeneration, 6.0, 0)],                                   // cooked meat
        1120 => &[(Regeneration, 5.0, 0), (Speed, 60.0, 0)],                 // ramen
        1121 => &[(Regeneration, 6.0, 1), (Strength, 90.0, 0)],              // japanese curry
        1122 => &[(Regeneration, 5.0, 0), (Haste, 90.0, 0)],                 // green curry
        1123 => &[(Regeneration, 5.0, 0), (Resistance, 60.0, 0)],            // gnocchi
        1124 => &[(Regeneration, 6.0, 0)],                                   // naan
        1125 => &[(Regeneration, 5.0, 0), (Absorption, 120.0, 0)],           // pupusa
        1126 => &[(Regeneration, 5.0, 0), (FireResistance, 60.0, 0)],        // latke
        1127 => &[(Regeneration, 4.0, 0), (Speed, 45.0, 0)],                 // bruschetta
        1128 => &[(Regeneration, 5.0, 0), (JumpBoost, 60.0, 0)],             // french toast
        1129 => &[(Regeneration, 5.0, 0), (NightVision, 120.0, 0)],          // sweet berry danish
        1130 => &[(Regeneration, 4.0, 0), (Speed, 60.0, 0), (Haste, 60.0, 0)], // melon sorbet
        1131 => &[(Regeneration, 8.0, 2), (Strength, 120.0, 0), (Resistance, 60.0, 0)], // stroganoff
        _ => return None,
    })
}


#[cfg(test)]
mod tests {
    use super::*;

    // Blocks and items share one id space, split at ITEM_BASE. If a new block ever crossed that
    // line it would silently become unplaceable, so pin the invariant down.
    #[test]
    fn block_ids_stay_below_the_item_base() {
        assert!(crate::world::block::MAX_BLOCK_ID < ITEM_BASE);
        assert!(!is_item(crate::world::block::MAX_BLOCK_ID));
        assert!(is_item(ITEM_BASE), "the boundary itself belongs to items");
    }

    // armor_slot() does range arithmetic over three disjoint id blocks; a wrong mapping would let
    // a helmet occupy the boots slot.
    #[test]
    fn armor_maps_to_the_right_slot() {
        for (id, slot) in [(1067 as Id, 0usize), (1068, 1), (1069, 2), (1070, 3),   // iron
                           (1071, 0), (1072, 1), (1073, 2), (1074, 3),          // diamond
                           (1095, 0), (1096, 1), (1097, 2), (1098, 3),          // adamant
                           (1084, 1)] {                                      // elytra -> chest
            assert!(is_armor(id), "{id} should be armor");
            assert_eq!(armor_slot(id), slot, "{id} mapped to the wrong slot");
        }
    }

    // Every predicate in this file is a hand-written list of ids. If one is ever left behind after a
    // renumbering it would silently classify a block as a tool, so pin the whole population down.
    #[test]
    fn every_classified_id_is_really_an_item() {
        use crate::world::block::MAX_BLOCK_ID;
        let mut tools = 0;
        let mut armor = 0;
        let mut foods = 0;
        for id in 0..=ITEM_BASE + 512 {
            for (what, holds) in [
                ("tool", is_tool(id)), ("armor", is_armor(id)), ("food", is_food(id)),
                ("estus", is_estus(id)), ("heart container", is_heart_container(id)),
                ("elytra", is_elytra(id)), ("shears", is_shears(id)),
                ("flint and steel", is_flint_steel(id)), ("firework", is_firework(id)),
            ] {
                if holds {
                    assert!(is_item(id), "{id} is classified as a {what} but is not an item id");
                    assert!(id > MAX_BLOCK_ID, "{id} is classified as a {what} but is a block");
                }
            }
            if is_tool(id) { tools += 1; }
            if is_armor(id) { armor += 1; }
            if is_food(id) { foods += 1; }
        }
        // Counts, so a range that collapses to nothing fails loudly rather than passing vacuously.
        assert_eq!(tools, 15, "expected 15 tools");
        assert_eq!(armor, 17, "expected 16 armour pieces plus the elytra");
        assert!(foods >= 25, "only {foods} foods found");
        assert_eq!(BRUSH, 1151);
    }

    #[test]
    fn tools_and_armor_do_not_overlap() {
        // Tools and armour all sit in one contiguous stretch of the item range; sweeping the whole
        // 16-bit space would cost 256x for nothing.
        for id in 0..=ITEM_BASE + 255 {
            assert!(!(is_tool(id) && is_armor(id)), "{id} is both a tool and armor");
            // Shears and the fishing rod are the exceptions: tools that neither mine nor fight.
            if is_tool(id) && !is_shears(id) && id != crate::fishing::ROD && id != BRUSH {
                assert!(is_pickaxe(id) ^ is_sword(id), "{id} must be exactly one tool kind");
            }
            if is_shears(id) { assert!(!is_pickaxe(id) && !is_sword(id), "shears are not a weapon"); }
            if is_tool(id) || is_armor(id) { assert!(max_durability(id) > 0, "{id} has no durability"); }
        }
    }

    // Adamant is the top tier: it must beat diamond everywhere it competes.
    // Every dish must be reachable: craftable, smeltable, dropped by a mob or found in a chest.
    // A food with no source is dead content.
    #[test]
    fn every_food_is_obtainable() {
        use crate::inventory::{RECIPES, SMELTING};
        let mut missing = Vec::new();
        for id in 0..=ITEM_BASE + 255 {
            if food_effects(id).is_none() { continue; }
            let crafted = RECIPES.iter().any(|r| r.out == id);
            let smelted = SMELTING.iter().any(|s| s.out == id);
            let dropped = crate::entity::MobKind::ALL.iter().any(|k| k.loot().contains(&id));
            let traded = crate::villager::ALL.iter()
                .any(|&p| crate::villager::offers(p, crate::villager::MAX_LEVEL).iter().any(|o| o.give == id));
            let foraged = id == 1026; // apples fall out of leaves
            let looted = (0..40).any(|i| {
                crate::container::roll_loot(i * 7, 40, i * 13, i as u8 % 3, false)
                    .iter().any(|s| s.id == id)
            });
            if !(crafted || smelted || dropped || looted || traded || foraged) { missing.push(id); }
        }
        assert!(missing.is_empty(), "these foods have no source: {missing:?}");
    }

    // Raw meat is a cooking input, not a snack.
    #[test]
    fn raw_meat_is_not_edible() {
        assert!(food_effects(1118).is_none(), "raw meat must be cooked first");
        assert!(food_effects(1119).is_some(), "cooked meat must feed you");
    }

    #[test]
    fn adamant_outclasses_diamond() {
        assert!(sword_damage(1094) > sword_damage(1066));
        assert!(pick_damage(1093) > pick_damage(1065));
        for (adamant, diamond) in [(1095 as Id, 1071 as Id), (1096, 1072), (1097, 1073), (1098, 1074)] {
            assert!(armor_defense(adamant) > armor_defense(diamond), "{adamant} must beat {diamond}");
        }
        assert!(max_durability(1093) > max_durability(1065));
    }
}
