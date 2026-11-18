//! Item classification tables beyond the inline tests.

use super::*;

#[test]
fn every_food_effect_is_sane_and_named() {
    let mut foods = 0;
    for id in ITEM_BASE..=ITEM_BASE + 200 {
        if let Some(effs) = food_effects(id) {
            foods += 1;
            assert!(!effs.is_empty(), "food {id} must do something");
            for &(kind, secs, _amp) in effs {
                assert!(secs > 0.0 && secs <= 300.0, "food {id} effect {kind:?} lasts {secs}s");
                assert!(!kind.key().is_empty());
            }
        }
    }
    assert!(foods >= 25);
    for kind in [Effect::Regeneration, Effect::Poison, Effect::Resistance, Effect::Strength, Effect::Speed, Effect::Haste, Effect::Absorption, Effect::FireResistance, Effect::NightVision, Effect::JumpBoost, Effect::Slowness] {
        assert!(!kind.key().is_empty());
    }
    // Keys are unique per effect kind.
    let keys: std::collections::HashSet<_> = [Effect::Regeneration, Effect::Poison, Effect::Resistance, Effect::Strength, Effect::Speed, Effect::Haste, Effect::Absorption, Effect::FireResistance, Effect::NightVision, Effect::JumpBoost, Effect::Slowness].iter().map(|k| k.key()).collect();
    assert_eq!(keys.len(), 11);
}

#[test]
fn damage_and_defense_ladders_are_monotonic() {
    // Swords and picks climb wood < stone < iron < bronze < diamond < adamant where tiers exist.
    assert!(sword_damage(1060) < sword_damage(1062));
    assert!(sword_damage(1062) < sword_damage(1064));
    assert!(sword_damage(1064) < sword_damage(1066));
    assert!(pick_damage(1059) < pick_damage(1061));
    assert!(pick_damage(1061) < pick_damage(1063));
    assert!(pick_damage(1063) < pick_damage(1065));
    assert_eq!(sword_damage(1), 0.0, "a block is not a sword");
    assert_eq!(pick_damage(1027), 0.0);
    assert_eq!(armor_defense(1), 0.0);
    // Every armor piece defends, every tool/armor has durability, and slots cycle helm..boots.
    for base in [1067u16, 1071, 1095, 1137] {
        for off in 0..4u16 {
            assert!(armor_defense(base + off) > 0.0);
            assert_eq!(armor_slot(base + off), off as usize);
        }
    }
    assert_eq!(max_durability(1082), 64);
    assert_eq!(max_durability(1149), 64, "the fishing rod wears out");
    assert_eq!(max_durability(1), 0, "blocks have no durability");
    assert!(has_durability(1084) && has_durability(1151) && !has_durability(1027));
    assert!(is_tool(1149), "the fishing rod is a durable tool even though it neither mines nor fights");
    assert!(is_flint_steel(1082) && is_firework(1085) && is_estus(1024) && is_heart_container(1025));
    assert!(is_item(1024) && !is_item(1023));
    assert_eq!(armor_defense(1084), 1.0, "the elytra defends a little while it flies you");
}
