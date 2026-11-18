//! Player status/effect and movement helpers beyond the inline tests.

use super::*;
use crate::item::Effect;
use glam::vec3;

#[test]
fn effects_stack_expire_and_drive_stats() {
    let mut p = Player::new(0.0, 64.0, 0.0);
    assert_eq!(p.eye_pos(), vec3(0.0, 65.62, 0.0));
    assert!(p.forward().length() > 0.99);
    assert!(p.right().length() > 0.99);
    p.yaw = std::f32::consts::FRAC_PI_2;
    assert!(p.forward().x < -0.99, "yaw 90deg must look along -X: {:?}", p.forward());
    // Stronger amp wins, duration refreshes to the longer, weaker re-application keeps amp.
    p.add_effect(Effect::Speed, 5.0, 0);
    p.add_effect(Effect::Speed, 2.0, 2);
    assert!(p.speed_mult() > 1.5);
    p.add_effect(Effect::Speed, 9.0, 0);
    assert!(p.has_effect(Effect::Speed));
    p.add_effect(Effect::Slowness, 5.0, 0);
    p.add_effect(Effect::JumpBoost, 5.0, 1);
    assert!(p.jump_bonus() > 0.0);
    p.add_effect(Effect::Strength, 5.0, 0);
    assert!(p.strength_bonus() > 0.0);
    p.add_effect(Effect::NightVision, 5.0, 0);
    assert!(p.night_vision());
    // Absorption effect sets a floor of absorption hearts.
    p.add_effect(Effect::Absorption, 5.0, 1);
    assert_eq!(p.absorption, 8.0);
    // Regen heals, poison hurts but never below 1 HP, expiry clears everything.
    p.health = 10.0;
    p.add_effect(Effect::Regeneration, 2.0, 0);
    p.tick_status(1.0);
    assert!(p.health > 10.0);
    p.add_effect(Effect::Poison, 5.0, 2);
    let hp = p.health;
    p.tick_status(0.5);
    assert!(p.health < hp && p.health >= 1.0);
    p.health = 1.5;
    p.tick_status(1.0);
    assert_eq!(p.health, 1.0, "poison never kills");
    p.tick_status(30.0);
    assert!(p.effects.is_empty());
    assert!(!p.night_vision());
}

#[test]
fn damage_heal_and_boost_edges() {
    let mut p = Player::new(0.0, 64.0, 0.0);
    p.damage(0.0);
    p.damage(-5.0);
    assert_eq!(p.health, 20.0, "non-positive damage is ignored");
    p.damage(5.0);
    assert_eq!(p.health, 15.0);
    p.damage(5.0); // hurt cooldown: ignored
    assert_eq!(p.health, 15.0);
    p.hurt_cd = 0.0;
    p.heal(100.0);
    assert_eq!(p.health, 20.0, "healing caps at max");
    p.firework_boost(); // not gliding: no-op
    assert_eq!(p.vel.y, 0.0);
    p.gliding = true;
    p.firework_boost();
    assert!(p.vel.y > 0.0);
    // Lethal damage marks dead exactly once at zero.
    p.hurt_cd = 0.0;
    p.damage(999.0);
    assert!(p.dead && p.health == 0.0);
    p.damage(5.0);
    assert_eq!(p.health, 0.0);
    // Resistance effect reduces a hit.
    let mut q = Player::new(0.0, 64.0, 0.0);
    q.add_effect(Effect::Resistance, 10.0, 0);
    q.damage(10.0);
    assert!((q.health - 12.0).abs() < 1e-3, "20% resistance: {}", q.health);
}

#[test]
fn flying_and_gliding_tick_in_empty_world() {
    let dir = std::env::temp_dir().join("voxels_player_cov").to_string_lossy().into_owned();
    let w = crate::world::ChunkMap::new(2, dir);
    let mut p = Player::new(0.5, 90.0, 0.5);
    p.flying = true;
    let input = crate::input::InputState { move_forward: 1.0, jump_held: true, ..Default::default() };
    for _ in 0..30 { p.tick(1.0 / 60.0, &input, &w); }
    assert!(p.pos.y > 90.0 && p.walk_dist > 0.0, "flight must climb and travel: {:?}", p.pos);
    // Elytra: a mid-air jump tap deploys the glide.
    let mut g = Player::new(0.5, 90.0, 0.5);
    g.elytra = true;
    g.on_ground = false;
    let tap = crate::input::InputState { jump_held: true, ..Default::default() };
    g.tick(1.0 / 60.0, &tap, &w);
    let release = crate::input::InputState::default();
    g.tick(1.0 / 60.0, &release, &w);
    assert!(g.gliding || g.glide_armed, "wings must deploy mid-air");
    // Sneaking is mirrored from input and the void floor catches the player.
    let mut f = Player::new(0.5, 0.05, 0.5);
    let sneak = crate::input::InputState { sneak: true, ..Default::default() };
    f.tick(1.0 / 60.0, &sneak, &w);
    assert!(f.sneaking);
    assert!(f.pos.y >= 0.1);
}
