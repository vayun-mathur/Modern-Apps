// Villager professions and their leveled trade tables.
//
// Matcha ships 14 professions across 235 trade files, tiered into 5 levels. Two of them (shepherd,
// fletcher) sell wool and arrows, which this game doesn't model, and the wandering trader isn't a
// village resident — so 11 professions are carried over here, each with 3 levels stocked from items
// the game actually has.
//
// Levels are earned **per profession, globally**, not per villager. Mobs aren't persisted (they're
// cleared on quit and respawned), so a per-villager level would silently reset every session and the
// player could never find "their" librarian again. A shared counter in `ProgressSave` keeps the
// progression real.

use crate::world::block::Id;

use crate::item::{has_durability, max_durability};

pub const EMERALD: Id = 1052;

/// One line on a villager's trade list. `cost2` of 0 means a single-ingredient trade.
#[derive(Clone, Copy)]
pub struct Offer {
    pub cost: Id,
    pub cost_n: i32,
    pub cost2: Id,
    pub cost2_n: i32,
    pub give: Id,
    pub give_n: i32,
}

// Constructors, so the tables below read as sentences and can't transpose a cost for a give.
const fn buy(emeralds: i32, give: Id, give_n: i32) -> Offer {
    Offer { cost: EMERALD, cost_n: emeralds, cost2: 0, cost2_n: 0, give, give_n }
}
const fn sell(cost: Id, cost_n: i32, emeralds: i32) -> Offer {
    Offer { cost, cost_n, cost2: 0, cost2_n: 0, give: EMERALD, give_n: emeralds }
}
/// Emeralds plus raw material for one finished piece — the smiths' signature trade.
const fn forge(emeralds: i32, mat: Id, mat_n: i32, give: Id) -> Offer {
    Offer { cost: EMERALD, cost_n: emeralds, cost2: mat, cost2_n: mat_n, give, give_n: 1 }
}

type Level = &'static [Offer];

pub const MAX_LEVEL: usize = 3;
// Trades with one profession needed to unlock its next tier.
const LEVEL_2_AT: u32 = 4;
const LEVEL_3_AT: u32 = 10;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Profession {
    Farmer,
    Fisherman,
    Butcher,
    Toolsmith,
    Weaponsmith,
    Armorer,
    Mason,
    Leatherworker,
    Librarian,
    Cleric,
    Cartographer,
}

// The discriminant order is the save format for `ProgressSave.trades_done` — append, never reorder.
pub const ALL: [Profession; 11] = [
    Profession::Farmer,
    Profession::Fisherman,
    Profession::Butcher,
    Profession::Toolsmith,
    Profession::Weaponsmith,
    Profession::Armorer,
    Profession::Mason,
    Profession::Leatherworker,
    Profession::Librarian,
    Profession::Cleric,
    Profession::Cartographer,
];

// Produce: the things a player can't grow, since there's no farming yet.
const FARMER: &[Level] = &[
    &[buy(1, 1031, 4), buy(1, 1042, 4), sell(1026, 8, 1)],
    &[buy(1, 1032, 3), buy(1, 1027, 6), buy(1, 58, 2)],
    &[buy(2, 1048, 4), buy(3, 1029, 1)],
];

// Matcha's fisherman lists 49 species; the game has two fish and the reef they came from.
const FISHERMAN: &[Level] = &[
    &[buy(2, 1028, 4), buy(1, 66, 3)],
    &[buy(2, 1044, 4), sell(1028, 8, 2)],
    &[buy(3, 67, 2), buy(2, 68, 4)],
];

// The butcher sells prepared dishes, and will cook yours if you bring the meat. The counter also
// carries the baking Matcha's butcher does — brownies and cookies have no other source.
const BUTCHER: &[Level] = &[
    &[sell(1118, 8, 1), buy(2, 1119, 3), buy(2, 1030, 3), buy(2, 1049, 4)],
    &[buy(2, 1046, 3), forge(3, 1119, 2, 1120)],
    &[forge(4, 1119, 2, 1131), buy(3, 1121, 2)],
];

const TOOLSMITH: &[Level] = &[
    &[sell(1053, 12, 1), buy(1, 1055, 8)],
    &[forge(4, 1050, 2, 1063), buy(5, 1050, 3)],
    &[forge(8, 1051, 2, 1065), forge(5, 1134, 2, 1135)],
];

const WEAPONSMITH: &[Level] = &[
    &[sell(1034, 5, 1), buy(2, 1062, 1)],
    &[forge(4, 1050, 2, 1064), forge(6, 1134, 2, 1136)],
    &[forge(9, 1051, 2, 1066)],
];

const ARMORER: &[Level] = &[
    &[sell(1050, 4, 1), buy(3, 1067, 1)],
    &[buy(6, 1068, 1), buy(4, 1069, 1), buy(3, 1070, 1)],
    &[forge(7, 1051, 2, 1071), forge(10, 1051, 3, 1072)],
];

// Matcha's mason walks the player up a ladder of stone variants; the game's stonecutter turns each
// one into slabs and stairs, so selling the stonecutter itself is the level-3 prize.
const MASON: &[Level] = &[
    &[buy(1, 1, 8), buy(1, 16, 6), sell(8, 8, 1)],
    &[buy(1, 57, 6), buy(1, 38, 4), buy(1, 75, 4)],
    &[buy(2, 74, 4), buy(2, 53, 4), buy(4, 118, 1)],
];

const LEATHERWORKER: &[Level] = &[
    &[sell(1033, 6, 2), buy(1, 4, 6)],
    &[buy(1, 26, 6), buy(1, 29, 6)],
    &[buy(1, 47, 4), buy(1, 51, 4), buy(2, 1033, 3)],
];

// Matcha's librarian sells tomes; here the tomes are Blessings, priced steeply so crafting them stays
// the main route.
const LIBRARIAN: &[Level] = &[
    &[buy(3, 33, 1), buy(1, 7, 4)],
    &[buy(5, 1087, 1), buy(9, 1114, 1)],
    &[buy(10, 1106, 1), buy(10, 1099, 1)],
];

// Matcha's cleric deals in splash potions. This game has no potions, so the cleric deals in the
// things that keep you alive: Estus, golden apples, and the one merchant who will sell you a heart.
const CLERIC: &[Level] = &[
    &[buy(4, 1024, 1), sell(1054, 6, 1)],
    &[buy(3, 1029, 2), buy(9, 1056, 1)],
    &[forge(12, 1051, 1, 1025), buy(10, 1101, 1)],
];

// Matcha's cartographer sells structure maps and a brush; without maps or archaeology this is the
// expedition outfitter instead — fire, throwables, and the obsidian for a portal.
const CARTOGRAPHER: &[Level] = &[
    &[buy(2, 1082, 1), buy(2, 1086, 6)],
    &[buy(6, 78, 2), forge(3, 1034, 2, 1085)],
    &[buy(10, 78, 4), buy(6, 1087, 2)],
];

impl Profession {
    pub fn index(self) -> usize {
        ALL.iter().position(|&p| p == self).unwrap_or(0)
    }

    pub fn from_index(i: usize) -> Self {
        ALL.get(i).copied().unwrap_or(Profession::Farmer)
    }

    /// A villager's trade is fixed for its lifetime, drawn from its spawn seed.
    pub fn from_seed(seed: u32) -> Self {
        Self::from_index((seed >> 8) as usize % ALL.len())
    }

    pub fn name(self) -> &'static str {
        match self {
            Profession::Farmer => "Farmer",
            Profession::Fisherman => "Fisherman",
            Profession::Butcher => "Butcher",
            Profession::Toolsmith => "Toolsmith",
            Profession::Weaponsmith => "Weaponsmith",
            Profession::Armorer => "Armorer",
            Profession::Mason => "Mason",
            Profession::Leatherworker => "Leatherworker",
            Profession::Librarian => "Librarian",
            Profession::Cleric => "Cleric",
            Profession::Cartographer => "Cartographer",
        }
    }

    /// Robe colour, so a village reads at a glance instead of every resident looking identical.
    pub fn tint(self) -> [f32; 3] {
        match self {
            Profession::Farmer => [0.72, 0.85, 0.42],
            Profession::Fisherman => [0.42, 0.68, 0.85],
            Profession::Butcher => [0.90, 0.52, 0.48],
            Profession::Toolsmith => [0.80, 0.66, 0.38],
            Profession::Weaponsmith => [0.62, 0.62, 0.70],
            Profession::Armorer => [0.48, 0.52, 0.60],
            Profession::Mason => [0.78, 0.74, 0.66],
            Profession::Leatherworker => [0.70, 0.50, 0.32],
            Profession::Librarian => [0.86, 0.82, 0.60],
            Profession::Cleric => [0.72, 0.52, 0.86],
            Profession::Cartographer => [0.52, 0.80, 0.72],
        }
    }

    fn levels(self) -> &'static [Level] {
        match self {
            Profession::Farmer => FARMER,
            Profession::Fisherman => FISHERMAN,
            Profession::Butcher => BUTCHER,
            Profession::Toolsmith => TOOLSMITH,
            Profession::Weaponsmith => WEAPONSMITH,
            Profession::Armorer => ARMORER,
            Profession::Mason => MASON,
            Profession::Leatherworker => LEATHERWORKER,
            Profession::Librarian => LIBRARIAN,
            Profession::Cleric => CLERIC,
            Profession::Cartographer => CARTOGRAPHER,
        }
    }
}

/// How far a profession has been levelled by `done` completed trades.
pub fn level_for(done: u32) -> usize {
    if done >= LEVEL_3_AT { 3 } else if done >= LEVEL_2_AT { 2 } else { 1 }
}

/// The trade count that unlocks the tier after `level`, or None once it's maxed.
pub fn next_level_at(level: usize) -> Option<u32> {
    match level {
        1 => Some(LEVEL_2_AT),
        2 => Some(LEVEL_3_AT),
        _ => None,
    }
}

/// Everything a villager of this profession will offer at this level: earlier tiers stay on the list,
/// so unlocking a level only ever appends and never invalidates an index the UI is already holding.
pub fn offers(prof: Profession, level: usize) -> Vec<Offer> {
    prof.levels().iter().take(level.clamp(1, MAX_LEVEL)).flat_map(|l| l.iter().copied()).collect()
}

/// The level a given offer index belongs to, for grouping in the UI.
pub fn level_of_offer(prof: Profession, idx: usize) -> usize {
    let mut seen = 0;
    for (i, l) in prof.levels().iter().enumerate() {
        seen += l.len();
        if idx < seen { return i + 1; }
    }
    MAX_LEVEL
}

/// Tools and armour always arrive at full durability; stacks arrive as a count.
pub fn give_count(offer: &Offer) -> i32 {
    if has_durability(offer.give) { max_durability(offer.give) } else { offer.give_n }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::world::block::is_real_id;

    // A typo in a trade table is invisible until a player taps it and receives Air, so every id in
    // every tier of every profession gets checked.
    #[test]
    fn every_trade_moves_real_goods() {
        for &p in ALL.iter() {
            let all = offers(p, MAX_LEVEL);
            assert!(!all.is_empty(), "{} has nothing to sell", p.name());
            for (i, o) in all.iter().enumerate() {
                let where_ = format!("{} offer {i}", p.name());
                assert!(is_real_id(o.cost), "{where_}: cost {} is not an id", o.cost);
                assert!(is_real_id(o.give), "{where_}: gives {} which is not an id", o.give);
                assert!(o.cost2 == 0 || is_real_id(o.cost2), "{where_}: second cost {} is not an id", o.cost2);
                assert!(o.cost_n > 0 && o.give_n > 0, "{where_}: a trade must cost and give something");
                assert!(o.cost2 == 0 || o.cost2_n > 0, "{where_}: a second cost needs a count");
                assert_ne!(o.cost, o.give, "{where_}: trades {} for itself", o.give);
                assert_ne!(o.cost2, o.give, "{where_}: trades {} for itself", o.give);
            }
        }
    }

    // The emerald economy needs a source as well as a sink, or the whole village is unreachable to a
    // player who has never found an emerald ore.
    #[test]
    fn emeralds_can_be_earned_and_spent() {
        let mut sellers = 0;
        for &p in ALL.iter() {
            let all = offers(p, MAX_LEVEL);
            assert!(all.iter().any(|o| o.cost == EMERALD), "{} gives nothing for emeralds", p.name());
            if all.iter().any(|o| o.give == EMERALD) { sellers += 1; }
            // Level 1 has to be usable on its own, since that's where every villager starts.
            assert!(!offers(p, 1).is_empty(), "{} is mute until it levels up", p.name());
        }
        assert!(sellers >= 5, "only {sellers} professions buy goods for emeralds");
    }

    // Levelling appends; it must never renumber an offer the trade screen is already showing.
    #[test]
    fn levelling_only_appends_offers() {
        for &p in ALL.iter() {
            let (l1, l2, l3) = (offers(p, 1), offers(p, 2), offers(p, 3));
            assert!(l1.len() <= l2.len() && l2.len() <= l3.len(), "{} shrinks as it levels", p.name());
            for (i, o) in l1.iter().enumerate() {
                assert_eq!(o.give, l3[i].give, "{} offer {i} moved when it levelled", p.name());
            }
            for (i, o) in l2.iter().enumerate() {
                assert_eq!(o.give, l3[i].give, "{} offer {i} moved when it levelled", p.name());
            }
            assert_eq!(level_of_offer(p, 0), 1);
            assert_eq!(level_of_offer(p, l1.len()), 2, "{} mislabels its first level-2 offer", p.name());
        }
    }

    #[test]
    fn levels_unlock_in_order() {
        assert_eq!(level_for(0), 1);
        assert_eq!(level_for(LEVEL_2_AT - 1), 1);
        assert_eq!(level_for(LEVEL_2_AT), 2);
        assert_eq!(level_for(LEVEL_3_AT), 3);
        assert_eq!(level_for(u32::MAX), MAX_LEVEL, "the level can't run past the table");
    }

    // Professions are drawn from the spawn seed, so a village has to end up mixed rather than eleven
    // farmers.
    #[test]
    fn spawn_seeds_spread_across_professions() {
        let mut seen = [0usize; ALL.len()];
        let mut s = 0x9E37_79B9u32;
        for _ in 0..4000 {
            s ^= s << 13; s ^= s >> 17; s ^= s << 5;
            seen[Profession::from_seed(s).index()] += 1;
        }
        for (i, n) in seen.iter().enumerate() {
            assert!(*n > 100, "{} almost never spawns ({n} of 4000)", Profession::from_index(i).name());
        }
    }

    #[test]
    fn professions_round_trip_through_their_index() {
        for (i, &p) in ALL.iter().enumerate() {
            assert_eq!(p.index(), i);
            assert_eq!(Profession::from_index(i), p);
        }
        assert_eq!(Profession::from_index(999), Profession::Farmer, "a corrupt save falls back, not panics");
    }
}

#[cfg(test)]
#[path = "villager_coverage_tests.rs"]
mod villager_coverage_tests;
