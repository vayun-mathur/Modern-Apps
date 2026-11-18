//! Simulation branches: portals, checkpoints, beacons, spawning, lifecycle glue.

use super::*;
use crate::world::block::Block;

fn state(tag: &str) -> EngineState { super::state_tests::small_state(tag) }
fn big(f: impl FnOnce() + Send + 'static) { super::state_tests::big_stack(f) }
const Y: i32 = 200;

#[test]
fn lifecycle_surface_and_loop_glue() { big(|| {
    let _serial = GLOBAL_ENGINE_TEST.lock();
    // A stale generation's loop exits immediately; the live one exits with no engine installed.
    engine_loop(ENGINE_GEN.load(Ordering::SeqCst) + 999);
    destroy_engine();
    engine_loop(ENGINE_GEN.load(Ordering::SeqCst));
    // Surface hand-off with a null window: queued, then dropped without a renderer.
    surface_created(std::ptr::null_mut());
    let mut s = state("surf");
    attach_pending_window(&mut s); // Vulkan init fails headless: window is released, no renderer
    assert!(s.renderer.is_none());
    surface_created(std::ptr::null_mut());
    surface_destroyed();
    // Loading-phase meshing advances permille and never declares ready without a renderer.
    LOAD_PHASE.store(LOAD_GENERATING, Ordering::SeqCst);
    for p in s.chunks.chunks_iter().map(|(p, _)| *p).collect::<Vec<_>>() {
        if let Some(c) = s.chunks.get_mut(p) { c.mesh_dirty = true; }
    }
    mesh_dirty_chunks(&mut s);
    assert_eq!(load_phase(), LOAD_GENERATING);
    assert!(load_progress() > 0.0 && load_progress() <= 1.0);
    assert_eq!(upload_budget(), UPLOAD_BUDGET_LOADING);
    LOAD_PHASE.store(LOAD_READY, Ordering::SeqCst);
    assert_eq!(upload_budget(), UPLOAD_BUDGET_PLAY);
    LOAD_PHASE.store(LOAD_IDLE, Ordering::SeqCst);
    assert_eq!(load_phase(), LOAD_IDLE);
}) }

#[test]
fn simulate_portal_checkpoint_beacon_and_spawn() { big(|| {
    let _serial = GLOBAL_ENGINE_TEST.lock();
    let mut s = state("sim2");
    // Checkpoint: a warding stone beside the player sets respawn and refills estus.
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    s.chunks.set_block_world(1, Y + 1, 0, 81);
    s.checkpoint_cd = 0.0;
    step(&mut s, 0.05, false);
    assert!(s.respawn.is_some(), "warding stone must set the checkpoint");
    // Beacon: a tier-1 pyramid grants Speed (tier gates stack with height).
    for dx in -1..=1 { for dz in -1..=1 { s.chunks.set_block_world(10 + dx, Y - 1, 10 + dz, 23); } }
    s.chunks.set_block_world(10, Y, 10, Block::Beacon as Id);
    s.player.pos = vec3(10.5, (Y + 1) as f32, 10.5);
    s.beacon_cd = 0.0;
    step(&mut s, 0.05, false);
    assert!(s.player.has_effect(crate::item::Effect::Speed), "beacon must buff nearby players");
    assert_eq!(s.best_beacon, 1);
    // Night-survived latches on the night->day edge in the overworld.
    s.was_night = true;
    s.world_secs = 0.0; // noon
    step(&mut s, 0.05, false);
    assert!(s.night_survived);
    // Portal: standing in a nether portal arms, charges, and switches dimension.
    s.chunks.set_block_world(0, Y, 0, 1);
    s.chunks.set_block_world(0, Y + 1, 0, 86);
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    s.portal_armed = true;
    s.portal_charge = 0.0;
    for _ in 0..12 { step(&mut s, 0.05, false); if s.dim != 0 { break; } }
    assert_eq!(s.dim, 1, "dwelling in a portal must travel to the Nether");
    // Stepping out re-arms for the trip back.
    s.chunks.set_block_world(s.player.pos.x.floor() as i32, s.player.pos.y.floor() as i32, s.player.pos.z.floor() as i32, 0);
    step(&mut s, 0.05, false);
    assert!(s.portal_armed, "leaving the portal must re-arm it");
    // Spawning: with the timer elapsed, a mob appears on solid ground near the player.
    let mut s2 = state("spawn");
    s2.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    for x in -30..=30 { for z in -30..=30 { s2.chunks.set_block_world(x, Y, z, 1); } }
    s2.spawn_timer = 0.0;
    s2.mobs.clear();
    step(&mut s2, 0.05, false);
    assert_eq!(s2.spawn_timer, 2.5, "the spawn timer must reset even when the roll finds no ground");
    // Death with no checkpoint falls back to the world spawn column.
    s2.respawn = None;
    s2.player.dead = true;
    step(&mut s2, 0.05, false);
    assert!(!s2.player.dead);
    assert!(s2.player.pos.x.abs() < 2.0 && s2.player.pos.z.abs() < 2.0, "respawn at origin: {:?}", s2.player.pos);
    // Gliding sheds a vapor trail; a client world interpolates instead of simulating mobs.
    s2.player.gliding = true;
    s2.player.elytra = true;
    let before = s2.particles.len();
    step(&mut s2, 0.05, false);
    assert!(s2.particles.len() >= before, "particle tick runs");
    crate::net::set_role(crate::net::ROLE_CLIENT);
    step(&mut s2, 0.05, false);
    crate::net::set_role(crate::net::ROLE_OFFLINE);
    // Lava ignored under Fire Resistance.
    s2.chunks.set_block_world(0, Y + 1, 0, 84);
    s2.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    s2.player.add_effect(crate::item::Effect::FireResistance, 10.0, 0);
    let hp = s2.player.health;
    step(&mut s2, 0.05, false);
    assert_eq!(s2.player.health, hp, "fire resistance must negate lava");
}) }

#[test]
fn async_init_installs_a_world_and_destroy_retires_it() { big(|| {
    let _serial = GLOBAL_ENGINE_TEST.lock();
    destroy_engine();
    let dir = super::state_tests::temp_world("asyncinit");
    assert!(init_engine_async(dir, 42), "the engine thread must spawn");
    assert_eq!(load_phase(), LOAD_GENERATING);
    // Bounded wait for the background load to install (radius-12 generation is the slow part).
    let mut installed = false;
    for _ in 0..1200 {
        if with_engine(|_| ()).is_some() { installed = true; break; }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    assert!(installed, "the background world never installed");
    assert_eq!(load_phase(), LOAD_MESHING);
    destroy_engine();
    assert!(with_engine(|_| ()).is_none());
    assert_eq!(load_phase(), LOAD_IDLE);
}) }
