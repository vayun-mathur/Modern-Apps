//! Villager trade tables: levels, offers, and counts.

use super::*;

#[test]
fn professions_levels_and_offers_are_coherent() {
    assert_eq!(ALL.len(), 11);
    for prof in ALL {
        assert_eq!(Profession::from_index(prof.index()), prof);
        assert!(!prof.name().is_empty());
        let tint = prof.tint();
        assert!(tint.iter().all(|c| (0.0..=1.0).contains(c)));
        // Offers only ever append as levels rise.
        let (o1, o2, o3) = (offers(prof, 1), offers(prof, 2), offers(prof, 3));
        assert!(!o1.is_empty() && o2.len() > o1.len() && o3.len() > o2.len(), "{prof:?} must gain offers per level");
        assert_eq!(offers(prof, 0).len(), o1.len(), "level clamps up to 1");
        assert_eq!(offers(prof, 99).len(), o3.len(), "level clamps down to MAX");
        for (i, o) in o3.iter().enumerate() {
            assert!(o.cost != 0 && o.cost_n > 0, "{prof:?} offer {i} has no cost");
            assert!(o.give != 0 && o.give_n > 0, "{prof:?} offer {i} gives nothing");
            assert_eq!(level_of_offer(prof, i), if i < o1.len() { 1 } else if i < o2.len() { 2 } else { 3 });
            let gc = give_count(o);
            if crate::item::has_durability(o.give) { assert_eq!(gc, crate::item::max_durability(o.give)); } else { assert_eq!(gc, o.give_n); }
        }
        assert_eq!(level_of_offer(prof, 9999), MAX_LEVEL);
    }
    // Level thresholds.
    assert_eq!(level_for(0), 1);
    assert_eq!(level_for(3), 1);
    assert_eq!(level_for(4), 2);
    assert_eq!(level_for(9), 2);
    assert_eq!(level_for(10), 3);
    assert_eq!(level_for(u32::MAX), 3);
    assert_eq!(next_level_at(1), Some(4));
    assert_eq!(next_level_at(2), Some(10));
    assert_eq!(next_level_at(3), None);
    assert_eq!(next_level_at(0), None);
    assert_eq!(Profession::from_index(999), Profession::Farmer);
    // Spawn seeds spread over professions and are stable.
    let mut seen = std::collections::HashSet::new();
    for seed in 0..2000u32 { seen.insert(Profession::from_seed(seed * 7919).index()); }
    assert!(seen.len() > 5, "villagers must vary by profession: {seen:?}");
    assert_eq!(Profession::from_seed(123456), Profession::from_seed(123456));
}

#[test]
fn offer_constructors_at_runtime() {
    // The trade tables are const-evaluated; calling the constructors at runtime
    // must produce the same single/dual-cost layouts.
    let b = buy(3, 1027, 6);
    assert_eq!((b.cost, b.cost_n, b.cost2, b.cost2_n, b.give, b.give_n), (EMERALD, 3, 0, 0, 1027, 6));
    let s = sell(1026, 8, 2);
    assert_eq!((s.cost, s.cost_n, s.cost2, s.give, s.give_n), (1026, 8, 0, EMERALD, 2));
    let f = forge(5, 1050, 2, 1063);
    assert_eq!((f.cost, f.cost_n, f.cost2, f.cost2_n, f.give, f.give_n), (EMERALD, 5, 1050, 2, 1063, 1));
}
