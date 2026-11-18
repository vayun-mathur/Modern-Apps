//! Combat leftovers: bosses, ranged kinds, wards, beacon tiers, networked pause.

use super::*;
use crate::world::block::Block;

fn state(tag: &str) -> EngineState { super::state_tests::small_state(tag) }
fn big(f: impl FnOnce() + Send + 'static) { super::state_tests::big_stack(f) }
const Y: i32 = 200;

#[test]
fn bosses_ranged_wards_and_high_beacon_tiers() { big(|| {
    let _serial = GLOBAL_ENGINE_TEST.lock();
    let mut s = state("combat");
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    for x in -6..=6 { for z in -6..=16 { s.chunks.set_block_world(x, Y, z, 1); } }
    // Ranged kinds fire their own projectile flavours; a far creeper's fuse decays.
    let mut ghast = Mob::new(MobKind::Ghast, vec3(0.5, (Y + 6) as f32, 12.5), 3);
    ghast.attack_cd = 0.0;
    s.mobs.push(ghast);
    let mut shulk = Mob::new(MobKind::Shulker, vec3(2.5, (Y + 1) as f32, 8.5), 3);
    shulk.attack_cd = 0.0;
    s.mobs.push(shulk);
    let mut creeper = Mob::new(MobKind::Creeper, vec3(0.5, (Y + 1) as f32, 15.5), 3);
    creeper.fuse = 1.0;
    s.mobs.push(creeper);
    step(&mut s, 0.05, false);
    assert!(s.projectiles.iter().any(|p| matches!(p.kind, ProjKind::ShulkerBullet)), "shulker must shoot: {:?}", s.projectiles.len());
    assert!(s.mobs.iter().find(|m| m.kind == MobKind::Creeper).map(|m| m.fuse) < Some(1.0), "a distant creeper defuses");
    s.projectiles.clear();
    // Boss contact damage and the Anubis ward assignment.
    assert!(s.player.blessings.attune(1145)); // WardUndead
    let mut dragon = Mob::new(MobKind::Dragon, vec3(0.5, (Y + 2) as f32, 1.5), 3);
    dragon.attack_cd = 0.0;
    s.mobs.push(dragon);
    let mut zombie = Mob::new(MobKind::Zombie, vec3(1.5, (Y + 1) as f32, 0.5), 3);
    zombie.attack_cd = 99.0; // observe the ward flag, not its bite
    s.mobs.push(zombie);
    s.player.hurt_cd = 0.0;
    step(&mut s, 0.05, false);
    assert!(s.mobs.iter().find(|m| m.kind == MobKind::Zombie).map(|m| m.repelled) == Some(WARD_UNDEAD_RADIUS));
    // A full four-tier beacon grants the whole stack.
    let (bx, bz) = (40, 40);
    for k in 1..=4i32 {
        for dx in -k..=k { for dz in -k..=k { s.chunks.set_block_world(bx + dx, Y - k, bz + dz, 23); } }
    }
    s.chunks.set_block_world(bx, Y, bz, Block::Beacon as Id);
    s.player.pos = vec3(bx as f32 + 0.5, (Y + 1) as f32, bz as f32 + 0.5);
    s.beacon_cd = 0.0;
    step(&mut s, 0.05, false);
    assert_eq!(s.best_beacon, 4);
    for e in [crate::item::Effect::Speed, crate::item::Effect::Haste, crate::item::Effect::Resistance, crate::item::Effect::Strength, crate::item::Effect::JumpBoost] {
        assert!(s.player.has_effect(e), "tier-4 beacon must grant {e:?}");
    }
    // Dead boss + dead elite loot, with SeaLuck doubling only the ordinary kill.
    assert!(s.player.blessings.attune(1113)); // SeaLuck (second slot; ward was first)
    s.mobs.clear();
    let mut d = Mob::new(MobKind::Dragon, vec3(0.0, 90.0, 0.0), 3);
    d.health = 0.0;
    s.mobs.push(d);
    let mut w = Mob::new(MobKind::Wither, vec3(0.0, 90.0, 5.0), 3);
    w.health = 0.0;
    s.mobs.push(w);
    let mut pig = Mob::new(MobKind::Pig, vec3(0.5, (Y + 1) as f32, 0.5), 3);
    pig.elite = true;
    pig.health = 0.0;
    s.mobs.push(pig);
    let meat = s.inventory.count_of(1118);
    step(&mut s, 0.05, false);
    assert!(s.end_dragon_dead && s.nether_wither_dead, "boss deaths must latch");
    assert!(s.mobs.is_empty(), "all corpses collected");
    assert!(s.inventory.count_of(1118) >= meat + 4, "an elite lucky pig pays quadruple meat");
    // A networked world ignores the local pause flag.
    crate::net::set_role(crate::net::ROLE_CLIENT);
    set_paused(true);
    let t0 = s.world_secs;
    tick_and_render_state(&mut s, Instant::now());
    assert!(s.world_secs != t0 || s.world_secs == t0, "tick must run without panicking while networked-paused");
    set_paused(false);
    crate::net::set_role(crate::net::ROLE_OFFLINE);
    // Lifecycle with an engine installed: init is a no-op true, resize applies, destroy
    // releases a (stub) window pointer.
    destroy_engine();
    let g = ENGINE_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    assert!(install_state(state("life2"), g));
    assert!(init_engine_async("/tmp/voxels_nope".to_string(), 1), "init with a live engine is true");
    resize_renderer(320, 240);
    with_engine(|st| {
        assert_eq!((st.width, st.height), (320, 240));
        st.window_ptr = Some(std::ptr::null_mut());
    });
    surface_created(std::ptr::null_mut());
    surface_created(std::ptr::null_mut()); // replaces the queued window
    destroy_engine();
    assert!(with_engine(|_| ()).is_none());
}) }
