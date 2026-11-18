//! Player tick in synthetic terrain: landing damage, swimming, double jump, sneak ledges.

use super::*;
use crate::world::block::{Block, Id};

const SKY: i32 = 200;
fn world() -> ChunkMap {
    let dir = std::env::temp_dir().join(format!("voxels_ptick_{}", std::process::id())).to_string_lossy().into_owned();
    ChunkMap::new(2, dir)
}
fn floor(w: &mut ChunkMap) {
    for x in -4..=4 { for z in -20..=4 { w.set_block_world(x, SKY, z, Block::Stone as Id); } }
}
fn idle(p: &mut Player, w: &ChunkMap, ticks: usize) {
    let input = crate::input::InputState::default();
    for _ in 0..ticks { p.tick(1.0 / 60.0, &input, w); }
}

#[test]
fn a_long_fall_hurts_and_a_short_one_does_not() {
    let mut w = world();
    floor(&mut w);
    // Drop from 30 blocks up: lands hard.
    let mut p = Player::new(0.5, (SKY + 31) as f32, 0.5);
    idle(&mut p, &w, 240);
    assert!(p.on_ground);
    assert!((p.pos.y - (SKY + 1) as f32).abs() < 1e-3);
    assert!(p.health < 20.0, "a 30-block fall must hurt, hp {}", p.health);
    // Drop from 3 blocks: free.
    let mut q = Player::new(0.5, (SKY + 4) as f32, 0.5);
    q.hurt_cd = 0.0;
    idle(&mut q, &w, 240);
    assert_eq!(q.health, 20.0, "a short hop must not hurt");
}

#[test]
fn blessed_swimming_and_double_jump() {
    let mut w = world();
    floor(&mut w);
    // A pool on the floor: Deep attunement swims instead of sinking like a stone.
    for x in -2..=2 { for z in -2..=2 { w.set_block_world(x, SKY + 1, z, 12); w.set_block_world(x, SKY + 2, z, 12); } }
    let mut p = Player::new(0.5, (SKY + 2) as f32, 0.5);
    assert!(p.blessings.attune(1058)); // Deep
    let rise = crate::input::InputState { jump_held: true, ..Default::default() };
    let y0 = p.pos.y;
    for _ in 0..30 { p.tick(1.0 / 60.0, &rise, &w); }
    assert!(p.pos.y >= y0 - 0.5, "swimming must not plummet: {} -> {}", y0, p.pos.y);
    // Double jump: Hyacinthus grants exactly one extra mid-air leap.
    let mut j = Player::new(0.5, (SKY + 6) as f32, 0.5);
    assert!(j.blessings.attune(1109));
    idle(&mut j, &w, 5);
    // First jump from the ground.
    let jump = crate::input::InputState { jump_held: true, ..Default::default() };
    // Land on the floor first so a ground jump is available.
    let mut g = Player::new(0.5, (SKY + 1) as f32, -10.5);
    assert!(g.blessings.attune(1109));
    idle(&mut g, &w, 60);
    assert!(g.on_ground);
    g.tick(1.0 / 60.0, &jump, &w);
    let after_first = g.vel.y;
    assert!(after_first != 0.0 || !g.on_ground, "ground jump must leave the floor");
    // Release, fall a little, then tap again mid-air for the second leap.
    idle(&mut g, &w, 20);
    g.vel.y = -2.0;
    g.tick(1.0 / 60.0, &jump, &w);
    assert!(g.vel.y > 0.0, "the second leap must relaunch, vel {}", g.vel.y);
    assert_eq!(j.pos.x, 0.5, "the idle bystander is untouched");
}

#[test]
fn sprint_sneak_and_walk_distance() {
    let mut w = world();
    floor(&mut w);
    let mut p = Player::new(0.5, (SKY + 1) as f32, 0.5);
    p.yaw = 0.0;
    idle(&mut p, &w, 30);
    assert!(p.on_ground);
    let walk = crate::input::InputState { move_forward: 1.0, ..Default::default() };
    for _ in 0..60 { p.tick(1.0 / 60.0, &walk, &w); }
    assert!(p.pos.z < -3.0, "walking north must travel: {}", p.pos.z);
    assert!(p.walk_dist > 3.0);
    // Sneaking is slower than walking (without Cronus).
    let mut s = Player::new(0.5, (SKY + 1) as f32, 0.5);
    idle(&mut s, &w, 30);
    let sneak = crate::input::InputState { move_forward: 1.0, sneak: true, ..Default::default() };
    for _ in 0..60 { s.tick(1.0 / 60.0, &sneak, &w); }
    assert!(s.pos.z > p.pos.z, "sneaking must be the slow way north: sneak {} vs walk {}", s.pos.z, p.pos.z);
    // Embedded in terrain, the player is lifted out rather than trapped.
    let mut e = Player::new(0.5, SKY as f32 - 0.5, 0.5);
    idle(&mut e, &w, 5);
    assert!(!e.collides_at(e.pos, &w), "auto-unstuck must free an embedded player");
}

#[test]
fn x_axis_step_up_and_head_knock() {
    let mut w = world();
    floor(&mut w);
    // A slab ledge across the +X path: walking right (strafe) steps onto it.
    for z in -4..=4 { w.set_block_meta_world(3, SKY + 1, z, Block::StoneSlab as Id, 0); }
    let mut p = Player::new(0.5, (SKY + 1) as f32, 0.5);
    let idle_input = crate::input::InputState::default();
    for _ in 0..30 { p.tick(1.0 / 60.0, &idle_input, &w); }
    assert!(p.on_ground);
    let right = crate::input::InputState { move_right: 1.0, ..Default::default() };
    for _ in 0..40 { p.tick(1.0 / 60.0, &right, &w); }
    assert!(p.pos.x > 3.0, "strafing east must cross the ledge, x={}", p.pos.x);
    assert!((p.pos.y - (SKY as f32 + 1.5)).abs() < 0.2, "and rest on the slab, y={}", p.pos.y);
    // Jumping under a low ceiling knocks your head and stops the rise.
    for x in -2..=2 { for z in -2..=2 { w.set_block_world(x, SKY + 3, z, Block::Stone as Id); } }
    let mut q = Player::new(0.5, (SKY + 1) as f32, 0.5);
    for _ in 0..30 { q.tick(1.0 / 60.0, &idle_input, &w); }
    let jump = crate::input::InputState { jump_held: true, ..Default::default() };
    let mut max_y = q.pos.y;
    for _ in 0..60 { q.tick(1.0 / 60.0, &jump, &w); max_y = max_y.max(q.pos.y); }
    assert!(max_y < (SKY + 2) as f32, "the ceiling must cap the jump, max {max_y}");
}
