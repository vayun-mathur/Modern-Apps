use serde::{Deserialize, Serialize};
use crate::world::block::{Block, Id};

pub const SLOTS: usize = 36;   // 0..9 = hotbar, 9..36 = main inventory
pub const HOTBAR: usize = 9;
pub const STACK: i32 = 64;

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct InvSlot { pub id: Id, pub count: i32 }
impl Default for InvSlot { fn default() -> Self { Self { id: 0, count: 0 } } }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Inventory {
    pub selected: usize,
    #[serde(with = "serde_slots")]
    pub slots: [InvSlot; SLOTS],
    pub placed: i32,
    pub broken: i32,
    // Equipped armor: 0 helmet, 1 chestplate, 2 leggings, 3 boots. count = remaining durability.
    #[serde(default)]
    pub armor: [InvSlot; 4],
}

// Item ids 1050+ are materials/tools (see item.rs). Ore -> material conversions live in SMELTING
// instead, since those need a furnace, fuel and time.
/// One crafting recipe. `in2 == 0` means a single ingredient.
///
/// `unlocked_by` is the tech tree: the recipe stays hidden and uncraftable until the recipe whose
/// output is `unlocked_by` has actually been crafted. 0 means it is available from the first minute.
/// Naming the prerequisite by its *output id* rather than by index keeps the table readable and
/// survives reordering.
pub struct Recipe {
    pub in1: Id, pub n1: i32,
    pub in2: Id, pub n2: i32,
    pub out: Id, pub out_n: i32,
    pub unlocked_by: Id,
}
#[allow(clippy::too_many_arguments)]
const fn r(in1: Id, n1: i32, in2: Id, n2: i32, out: Id, out_n: i32, unlocked_by: Id) -> Recipe {
    Recipe { in1, n1, in2, n2, out, out_n, unlocked_by }
}

pub const RECIPES: [Recipe; 96] = [
    r(1050, 1, 1053, 1, 1082, 1, 1061), // iron + coal -> flint & steel
    r(1050, 2, 0, 0, 1148, 1, 1061),   // iron            -> shears
    r(1055, 3, 1033, 2, 1149, 1, 1055), // sticks + leather -> fishing rod
    r(1055, 1, 1132, 1, 1151, 1, 1055), // stick + copper   -> archaeologist's brush
    r(1147, 3, 0, 0, 1027, 1, 1146),   // wheat            -> bread
    r(Block::HayBlock as Id, 1, 0, 0, 1146, 4, 0), // hay bale -> wheat seeds
    r(1083, 1, Block::Glass as Id, 5, Block::Beacon as Id, 1, 1065), // nether star + glass -> beacon
    r(1034, 2, 0, 0, 1085, 3, 0), // gunpowder -> firework rockets
    r(Block::Snow as Id, 1, 0, 0, 1086, 4, 0), // snow -> snowballs
    r(Block::Wood as Id, 1, 0, 0, Block::Planks as Id, 4, 0),
    r(Block::BirchLog as Id, 1, 0, 0, Block::BirchPlanks as Id, 4, 0),
    r(Block::SpruceLog as Id, 1, 0, 0, Block::SprucePlanks as Id, 4, 0),
    r(Block::Planks as Id, 4, 0, 0, Block::CraftingTable as Id, 1, 0),
    r(Block::Cobble as Id, 8, 0, 0, Block::Furnace as Id, 1, 0),
    r(Block::Diorite as Id, 4, 0, 0, Block::PolishedDiorite as Id, 4, 0),
    r(Block::Planks as Id, 2, 0, 0, 1055 /*Stick*/, 4, 0),
    // Material -> block.
    r(1050, 9, 0, 0, Block::IronBlock as Id, 1, 1061),
    r(1051, 9, 0, 0, Block::DiamondBlock as Id, 1, 1063),
    r(1052, 9, 0, 0, Block::EmeraldBlock as Id, 1, 1063),
    // Tools: material + stick(1055).
    r(Block::Planks as Id, 3, 1055, 2, 1059, 1, 0), // wood pickaxe
    r(Block::Planks as Id, 2, 1055, 1, 1060, 1, 0), // wood sword
    r(Block::Cobble as Id, 3, 1055, 2, 1061, 1, 1059), // stone pickaxe
    r(Block::Cobble as Id, 2, 1055, 1, 1062, 1, 1059), // stone sword
    r(1050, 3, 1055, 2, 1063, 1, 1061),                 // iron pickaxe
    r(1050, 2, 1055, 1, 1064, 1, 1061),                 // iron sword
    r(1051, 3, 1055, 2, 1065, 1, 1063),                 // diamond pickaxe
    r(1051, 2, 1055, 1, 1066, 1, 1063),                 // diamond sword
    // Armor: material only.
    r(1050, 5, 0, 0, 1067, 1, 1063), r(1050, 8, 0, 0, 1068, 1, 1063), r(1050, 7, 0, 0, 1069, 1, 1063), r(1050, 4, 0, 0, 1070, 1, 1063), // iron
    r(1051, 5, 0, 0, 1071, 1, 1065), r(1051, 8, 0, 0, 1072, 1, 1065), r(1051, 7, 0, 0, 1073, 1, 1065), r(1051, 4, 0, 0, 1074, 1, 1065), // diamond
    // --- Matcha alloy tier ---
    // Adamant gear: the tier above diamond.
    r(1092, 3, 1055, 2, 1093, 1, Block::BlastFurnace as Id), // adamant pickaxe
    r(1092, 2, 1055, 1, 1094, 1, 1093), // adamant sword
    r(1092, 5, 0, 0, 1095, 1, 1093), r(1092, 8, 0, 0, 1096, 1, 1093), r(1092, 7, 0, 0, 1097, 1, 1093), r(1092, 4, 0, 0, 1098, 1, 1093),
    // Metal storage blocks (silver/steel/adamant also power beacons).
    r(1089, 9, 0, 0, Block::SilverBlock as Id, 1, Block::BlastFurnace as Id),
    r(1091, 9, 0, 0, Block::SteelBlock as Id, 1, Block::BlastFurnace as Id),
    r(1092, 9, 0, 0, Block::AdamantBlock as Id, 1, Block::BlastFurnace as Id),
    // A steel-lined furnace: the only place the alloy recipes will smelt.
    r(Block::Furnace as Id, 1, 1091, 5, Block::BlastFurnace as Id, 1, Block::Furnace as Id),
    // Blessings: quicksilver charms bound to a thematic offering. Attuning one grants a permanent
    // passive (see blessing.rs), so the ingredient cost tracks roughly how strong the passive is.
    r(1090, 2, 1089, 1, 1056, 1, Block::BlastFurnace as Id),  // silver          -> Clement, swift of foot
    r(1090, 2, 1050, 4, 1057, 1, Block::BlastFurnace as Id),  // iron            -> Ares, might
    r(1090, 2, 1052, 2, 1058, 1, Block::BlastFurnace as Id),  // emerald         -> Yamm, the deep
    r(1090, 2, 1091, 3, 1099, 1, Block::BlastFurnace as Id),  // steel           -> Daedalus, tools never wear
    r(1090, 2, 1085, 4, 1100, 1, Block::BlastFurnace as Id),  // fireworks       -> Icarus, no fall damage
    r(1090, 2, 1088, 6, 1101, 1, Block::BlastFurnace as Id),  // sulfur          -> Yama, immune to fire
    r(1090, 2, Block::Obsidian as Id, 4, 1102, 1, Block::BlastFurnace as Id),   // Talos, crushing blows
    r(1090, 2, 1083, 1, 1103, 1, Block::BlastFurnace as Id),  // nether star     -> the God King, smite the undead
    r(1090, 2, 1034, 8, 1104, 1, Block::BlastFurnace as Id),  // gunpowder       -> Arachnae, bane of horrors
    r(1090, 2, 1092, 2, 1105, 1, Block::BlastFurnace as Id),  // adamant         -> Prometheus, armor never wears
    r(1090, 2, 1051, 3, 1106, 1, Block::BlastFurnace as Id),  // diamond         -> Lu Ban, mending
    r(1090, 2, Block::EmeraldBlock as Id, 1, 1107, 1, Block::BlastFurnace as Id), // Eros, fortune
    r(1090, 2, 1087, 3, 1108, 1, Block::BlastFurnace as Id),  // ender pearls    -> Will, reach
    r(1090, 2, Block::Glowstone as Id, 4, 1109, 1, Block::BlastFurnace as Id),  // Hyacinthus, second jump
    r(1090, 2, Block::Purpur as Id, 6, 1110, 1, Block::BlastFurnace as Id),     // Aeolus, wind burst
    r(1090, 2, Block::Sculk as Id, 8, 1111, 1, Block::BlastFurnace as Id),      // Cronus, swift sneak
    r(1090, 2, Block::BlueIce as Id, 4, 1112, 1, Block::BlastFurnace as Id),    // Demeter, frost walker
    r(1090, 2, Block::SeaLantern as Id, 4, 1113, 1, Block::BlastFurnace as Id), // Glaucus, sea luck
    r(1090, 2, Block::Amethyst as Id, 6, 1114, 1, Block::BlastFurnace as Id),   // Apollo, marksman
    r(1090, 2, 1086, 16, 1115, 1, Block::BlastFurnace as Id), // snowballs       -> Artemis, multishot
    r(1090, 2, Block::WardingStone as Id, 2, 1116, 1, Block::BlastFurnace as Id), // Warding, thorns
    r(1090, 2, Block::DiamondBlock as Id, 1, 1117, 1, Block::BlastFurnace as Id), // Paris, infinity
    // The five late additions cost the alloy tier, so they arrive after the Blast Furnace does.
    r(1090, 2, Block::IronBlock as Id, 2, 1141, 1, Block::BlastFurnace as Id),    // Athena, absorption shield
    r(1090, 2, Block::Magma as Id, 4, 1142, 1, Block::BlastFurnace as Id),        // Sekhmet, bloodrage
    r(1090, 2, 1118, 12, 1143, 1, Block::BlastFurnace as Id), // raw meat        -> Camazotz, lifesteal
    r(1090, 2, Block::Prismarine as Id, 8, 1144, 1, Block::BlastFurnace as Id),   // Tangaroa, conduit
    r(1090, 2, Block::Sculk as Id, 4, 1145, 1, Block::BlastFurnace as Id),        // Anubis, ward undead
    // --- Matcha's kitchen. Cooked meat is the base ingredient; everything else builds on it. ---
    r(1119, 1, 1027, 1, 1120, 1, 1027),  // cooked meat + bread          -> ramen
    r(1119, 2, 1031, 2, 1121, 1, 1027),  // cooked meat + carrot         -> japanese curry
    r(1028, 2, 1031, 2, 1122, 1, 1027),  // cooked fish + carrot         -> green curry
    r(1042, 2, 1027, 1, 1123, 1, 1027),  // baked potato + bread         -> gnocchi
    r(1027, 2, 0, 0, 1124, 2, 1027),    // bread                        -> naan
    r(1027, 1, 1119, 2, 1125, 1, 1027),  // bread + cooked meat          -> pupusa
    r(1042, 3, 0, 0, 1126, 1, 1027),    // baked potato                 -> latke
    r(1027, 1, 1026, 2, 1127, 1, 1027),  // bread + apple                -> bruschetta
    r(1027, 1, 1045, 1, 1128, 1, 1027),  // bread + fried egg            -> french toast
    r(1027, 1, 1048, 1, 1129, 1, 1027),  // bread + glow berry crumble   -> sweet berry danish
    r(1032, 2, Block::Snow as Id, 2, 1130, 1, 1027), // melon + snow    -> melon sorbet
    r(1119, 3, 1027, 1, 1131, 1, 1027),  // cooked meat + bread          -> stroganoff
    r(1026, 2, 1027, 1, 1047, 1, 1027),  // apple + bread                -> apple empanada
    r(1026, 1, Block::Glowstone as Id, 1, 1048, 1, 1027), // apple + glowstone -> glow berry crumble
    // --- Bronze: Matcha alloys copper with gold, landing between iron and diamond. ---
    // 1132 copper ingot, 1133 gold ingot, 1134 bronze ingot.
    r(1134, 3, 1055, 2, 1135, 1, 1063), // bronze pickaxe
    r(1134, 2, 1055, 1, 1136, 1, 1135), // bronze sword
    r(1134, 5, 0, 0, 1137, 1, 1135), r(1134, 8, 0, 0, 1138, 1, 1135), r(1134, 7, 0, 0, 1139, 1, 1135), r(1134, 4, 0, 0, 1140, 1, 1135),
    r(1132, 9, 0, 0, Block::CopperBlock as Id, 1, 1063),
    r(1133, 9, 0, 0, Block::GoldBlock as Id, 1, 1063),
    r(1134, 9, 0, 0, Block::BronzeBlock as Id, 1, 1063),
    // The stonecutter itself: an iron blade on a stone bed.
    r(Block::Stone as Id, 3, 1050, 1, Block::Stonecutter as Id, 1, 1061),
];

// Furnace recipes. Unlike crafting these cost fuel and take `secs` of real time, and the ones marked
// `blast` only run in a Blast Furnace — that gate is what makes the steel/adamant line an unlock
// rather than just another recipe.
pub struct Smelt {
    pub in1: Id, pub n1: i32,
    pub in2: Id, pub n2: i32,
    pub out: Id, pub out_n: i32,
    pub secs: f32,
    pub blast: bool,
}
const fn smelt(in1: Id, n1: i32, in2: Id, n2: i32, out: Id, out_n: i32, secs: f32, blast: bool) -> Smelt {
    Smelt { in1, n1, in2, n2, out, out_n, secs, blast }
}
pub const SMELTING: [Smelt; 19] = [
    smelt(1150, 1, 0, 0, 1028, 1, 6.0, false), // raw fish -> cooked fish
    smelt(Block::CoalOre as Id,     1, 0, 0, 1053, 1,  6.0, false), // coal
    smelt(Block::IronOre as Id,     1, 0, 0, 1050, 1,  8.0, false), // iron ingot
    smelt(Block::DiamondOre as Id,  1, 0, 0, 1051, 1, 10.0, false),
    smelt(Block::EmeraldOre as Id,  1, 0, 0, 1052, 1, 10.0, false),
    smelt(Block::RedstoneOre as Id, 1, 0, 0, 1054, 1,  6.0, false),
    smelt(Block::SilverOre as Id,   1, 0, 0, 1089, 1,  9.0, false), // silver ingot
    smelt(Block::Sand as Id,        2, 0, 0, Block::Glass as Id,  1, 5.0, false),
    smelt(Block::Cobble as Id,      4, 0, 0, Block::Stone as Id,  1, 5.0, false),
    smelt(Block::Clay as Id,        4, 0, 0, Block::Brick as Id,  1, 6.0, false),
    smelt(1118, 1, 0, 0, 1119, 1, 6.0, false), // raw meat -> cooked meat
    smelt(1027, 1, 0, 0, 1043, 3, 5.0, false), // bread -> cookies (baking)
    smelt(Block::CopperOre as Id, 1, 0, 0, 1132, 1, 6.0, false), // copper ingot
    smelt(Block::GoldOre as Id,   1, 0, 0, 1133, 1, 8.0, false), // gold ingot
    smelt(1132, 6, 1133, 1, 1134, 1, 14.0, true),                  // copper + gold -> bronze
    // Blast furnace only: the alloy line.
    smelt(Block::SulfurOre as Id,   1, 0, 0, 1088, 2,  5.0, true),  // sulfur
    smelt(Block::CinnabarOre as Id, 1, 0, 0, 1090, 1,  7.0, true),  // quicksilver
    smelt(1050, 1, 1088, 1, 1091, 1, 12.0, true),                     // iron + sulfur -> steel
    smelt(1091, 4, 1090, 2, 1092, 1, 20.0, true),                     // steel + quicksilver -> adamant
];

// Seconds of furnace burn a stack item is worth. Anything not listed can't be used as fuel.
pub fn fuel_secs(id: Id) -> f32 {
    match id {
        1053 => 80.0,                                        // coal
        84 => 200.0,                                        // a lava block
        1088 => 60.0,                                        // sulfur burns hot
        4  |  26  |  29  |  47  |  50  |  51 => 15.0,                 // logs
        10  |  27  |  30  |  49  |  52 => 15.0,                     // planks
        1055 => 5.0,                                         // sticks
        _ => 0.0,
    }
}
// Fuels with no use other than burning, tried before anything a player might be saving.
const DEDICATED_FUELS: [Id; 3] = [1053, 84, 1088];

// Villager trades live in `villager.rs`, tiered per profession. Emerald = item 156.

/// Whether recipe `idx` is available, given which recipes have already been crafted. A recipe with no
/// prerequisite is always available; otherwise the recipe that produces `unlocked_by` must have been
/// crafted at least once.
pub fn recipe_unlocked(idx: usize, crafted: &[bool]) -> bool {
    let Some(recipe) = RECIPES.get(idx) else { return false; };
    if recipe.unlocked_by == 0 { return true; }
    RECIPES.iter().enumerate()
        .any(|(i, other)| other.out == recipe.unlocked_by && crafted.get(i).copied().unwrap_or(false))
}

// Which shelf of the crafting menu a recipe belongs on. Purely for the UI — the recipe table itself
// stays a flat list so indices remain stable.
pub fn recipe_category(out: Id) -> &'static str {
    if crate::blessing::is_blessing(out) { return "blessing"; }
    if crate::item::food_effects(out).is_some() { return "food"; }
    if crate::item::is_tool(out) { return "tool"; }
    if crate::item::is_armor(out) { return "armor"; }
    if crate::item::is_item(out) { return "material"; }
    "block"
}

// The stonecutter: one block in, one shape out, no fuel and no waiting. This is Matcha's stonecutting
// book condensed — the pack's hundreds of recipes are almost all "material -> slab/stairs/variant",
// which is exactly what `cut_variants` enumerates.
pub struct Cut { pub input: Id, pub output: Id, pub count: i32 }

/// Every stonecutter conversion, derived from the block table so a new slab family is picked up for
/// free. A cube yields two slabs or one stair, and either shape converts back to the other.
pub fn cut_variants() -> Vec<Cut> {
    let mut out = Vec::new();
    for material in CUTTABLE {
        let (Some(slab), Some(stairs)) = (material.slab_of(), material.stairs_of()) else { continue; };
        let (m, s, st) = (material as Id, slab as Id, stairs as Id);
        out.push(Cut { input: m, output: s, count: 2 });
        out.push(Cut { input: m, output: st, count: 1 });
        out.push(Cut { input: s, output: st, count: 1 });
        out.push(Cut { input: st, output: s, count: 1 });
    }
    // Decorative conversions between whole blocks of the same family.
    for &(input, output) in DECOR_CUTS {
        out.push(Cut { input: input as Id, output: output as Id, count: 1 });
    }
    out
}

// Materials that have a slab and a stair shape.
const CUTTABLE: [Block; 8] = [
    Block::Stone, Block::Cobble, Block::Planks, Block::Brick,
    Block::Sandstone, Block::DeepslateBricks, Block::NetherBricks, Block::Purpur,
];
// Whole-block decorative swaps the stonecutter also offers.
const DECOR_CUTS: &[(Block, Block)] = &[
    (Block::Stone, Block::Cobble),
    (Block::Stone, Block::Brick),
    (Block::Cobble, Block::MossyCobble),
    (Block::Diorite, Block::PolishedDiorite),
    (Block::CobbledDeepslate, Block::DeepslateBricks),
    (Block::Sandstone, Block::RedSandstone),
    (Block::EndStone, Block::EndStoneBricks),
    (Block::Netherrack, Block::NetherBricks),
];

impl Inventory {
    /// A blank inventory: no starter kit (see `Default`, which is the
    /// new-player grant). For tests and anywhere an empty state is meant.
    pub fn empty() -> Self {
        Self {
            selected: 0,
            slots: [InvSlot::default(); SLOTS],
            placed: 0,
            broken: 0,
            armor: [InvSlot::default(); 4],
        }
    }
}

impl Default for Inventory {
    fn default() -> Self {
        let mut slots = [InvSlot::default(); SLOTS];
        let start = [
            (Block::Grass, 64), (Block::Dirt, 64), (Block::Stone, 64), (Block::Wood, 16),
            (Block::Planks, 32), (Block::Sand, 32), (Block::Glass, 16), (Block::Cobble, 16), (Block::Brick, 16),
        ];
        for (i, (b, c)) in start.iter().enumerate() { slots[i] = InvSlot { id: *b as Id, count: *c }; }
        Self { selected: 0, slots, placed: 0, broken: 0, armor: [InvSlot::default(); 4] }
    }
}

include!("inventory_part1.rs");
include!("inventory_part2.rs");