use crate::world::{ChunkMap, block::{Block, Id, Shape}};
use crate::player::Player;
use crate::inventory::Inventory;
use crate::entity::{Mob, MobKind, Particle, Projectile, ProjKind, build_entity_mesh, tick_particles, append_particles, append_projectiles};
use crate::blessing::Passive;
use crate::world::mesher;
use crate::vulkan::context::{VulkanContext, ANativeWindow};
use crate::vulkan::renderer::VulkanRenderer;
use glam::{Mat4, Vec3, vec3};
use std::sync::{Mutex, OnceLock, atomic::{AtomicBool, Ordering}};
use std::time::Instant;

pub struct EngineState {
    pub chunks: ChunkMap,
    pub player: Player,
    pub inventory: Inventory,
    pub save_dir: String,
    pub renderer: Option<VulkanRenderer>,
    pub width: u32,
    pub height: u32,
    pub start_time: Instant,
    pub last_tick: Instant,
    pub window_ptr: Option<*mut ANativeWindow>,
    pub needs_resize: bool,
    pub running: bool,
    pub mobs: Vec<Mob>,
    pub spawn_timer: f32,
    pub spawn_rng: u32,
    pub respawn: Option<Vec3>,
    pub checkpoint_cd: f32,
    pub beacon_cd: f32,
    pub particles: Vec<Particle>,
    pub projectiles: Vec<Projectile>,
    // Dimensions: 0 overworld, 1 nether, 2 end. `chunks` is the active dimension; the others are
    // stashed here while inactive.
    pub dim: u8,
    pub seed: u32,
    pub stored: [Option<ChunkMap>; 3],
    pub dim_pos: [Vec3; 3],
    pub dim_visited: [bool; 3],
    pub portal_armed: bool, // must step out of a portal before it fires again (no auto-bounce)
    pub portal_charge: f32,
    pub end_dragon_dead: bool,
    pub nether_wither_dead: bool,
    // Night tracking: `was_night` edge-detects the night->day flip so `night_survived` only latches
    // after the player has actually lived through a full night.
    pub was_night: bool,
    pub night_survived: bool,
    pub containers: crate::container::Containers,
    pub open_chest: Option<crate::container::ContainerKey>,
    pub smelter: Smelter,
    // Milestone tracking for achievements.
    pub best_beacon: i32,
    pub deepest_y: i32,
    // Throttle for the Lu Ban mending blessing.
    pub mend_cd: f32,
    // Which villager's trade list is open, and how many trades each profession has completed (the
    // latter is what levels them up, since villager mobs aren't persisted).
    pub trade_prof: u8,
    pub trades_done: [u32; crate::villager::ALL.len()],
    // Weather (overworld only): the current state, seconds until the next roll, and the eased
    // intensity the renderer and the mob spawner actually read.
    pub weather: u8,
    pub weather_cd: f32,
    pub rain: f32,
    // Footsteps, cave ambience and the eerie score; see ambience.rs.
    pub ambience: crate::ambience::Ambience,
    prev_walk: f32,
    pub fishing: crate::fishing::Fishing,
    farm_cd: f32,
    // Which recipes have been crafted at least once. This is what drives the crafting tech tree:
    // a recipe unlocks when its prerequisite has been made (see inventory::recipe_unlocked).
    pub crafted_recipes: Vec<bool>,
    // One-shot latches for the tutorial advancements. Not saved: the achievements manager persists
    // the unlock itself, so re-earning them in a later session costs nothing.
    pub did_shear: bool,
    pub did_fish: bool,
    pub did_brush: bool,
    pub did_harvest: bool,
    pub did_rest: bool,
    /// Seconds of simulated play until the next autosave.
    pub autosave_cd: f32,
}

// One furnace job at a time, owned by the world rather than by a specific furnace block: the player
// lights a recipe and it keeps burning while they walk away or close the menu.
#[derive(Default)]
pub struct Smelter {
    pub recipe: usize,
    pub active: bool,
    pub progress: f32,
    pub fuel_left: f32,
    pub fuel_max: f32,
    // Whether the furnace the player last opened was a Blast Furnace (gates the alloy recipes).
    pub blast: bool,
}

// Seconds per full day. Matcha's day_cycle_extender stretches vanilla's 1200s toward hour-long days;
// 900 keeps that unhurried feel without asking a mobile session to sit through a 10-minute night.
pub const DAY_CYCLE: f32 = 900.0;
// The clock starts half a cycle in so a fresh world opens at midday (day_t = 0.5 -> sun overhead).
const NOON_OFFSET: f32 = DAY_CYCLE * 0.5;
// Pure clock maths, split out from EngineState so they're testable without an Instant.
pub fn day_t_at(elapsed: f32) -> f32 { ((NOON_OFFSET + elapsed) / DAY_CYCLE) % 1.0 }
// The sun is below the horizon at both ends of the cycle (day_t = 0 is midnight). This band is not
// arbitrary: renderer.rs derives sun height as -cos(day_t * TAU), which is negative exactly here.
pub fn is_night_at(day_t: f32) -> bool { day_t < 0.25 || day_t > 0.75 }

// ---- Weather ----
// Matcha drives rain and clear weather explicitly from its mechanics. Here it's a slow random walk
// between three states, re-rolled every few minutes and persisted, with `rain` easing toward the
// target so the sky fades over rather than snapping.
pub const WEATHER_CLEAR: u8 = 0;
pub const WEATHER_RAIN: u8 = 1;
pub const WEATHER_STORM: u8 = 2;
/// How long a weather state lasts before the next roll.
const WEATHER_MIN_SECS: f32 = 150.0;
const WEATHER_MAX_SECS: f32 = 420.0;
/// Rain fades in and out over ~7 seconds.
const RAIN_FADE_PER_SEC: f32 = 0.14;
/// Rain this heavy lets hostile mobs spawn in daylight, the way an overcast sky does in Minecraft.
pub const RAIN_SPAWN_THRESHOLD: f32 = 0.55;

pub fn rain_target(weather: u8) -> f32 {
    match weather {
        WEATHER_RAIN => 0.65,
        WEATHER_STORM => 1.0,
        _ => 0.0,
    }
}

/// Pick the next weather state. `r` is a uniform sample in [0, 1). Clear is the most common state and
/// storms only arrive by way of rain, so the sky doesn't flip from sunshine to downpour.
pub fn next_weather(current: u8, r: f32) -> u8 {
    match current {
        WEATHER_RAIN => if r < 0.30 { WEATHER_STORM } else if r < 0.75 { WEATHER_CLEAR } else { WEATHER_RAIN },
        WEATHER_STORM => if r < 0.65 { WEATHER_RAIN } else { WEATHER_CLEAR },
        _ => if r < 0.28 { WEATHER_RAIN } else { WEATHER_CLEAR },
    }
}

pub fn weather_duration(r: f32) -> f32 {
    WEATHER_MIN_SECS + r.clamp(0.0, 1.0) * (WEATHER_MAX_SECS - WEATHER_MIN_SECS)
}

/// Just past sunrise, where resting at a Warding Stone lands you.
pub const DAWN: f32 = 0.27;
/// Seconds of world time between `day_t` and the next dawn. Always forward: the clock never rewinds.
pub fn secs_until_dawn(day_t: f32) -> f32 {
    let ahead = (DAWN - day_t).rem_euclid(1.0);
    ahead * DAY_CYCLE
}

impl EngineState {
    pub fn world_time(&self) -> f32 { NOON_OFFSET + self.start_time.elapsed().as_secs_f32() }
    pub fn day_t(&self) -> f32 { day_t_at(self.start_time.elapsed().as_secs_f32()) }
    pub fn is_night(&self) -> bool { is_night_at(self.day_t()) }
    /// Whether hostile mobs may spawn: after dark, or under heavy rain.
    pub fn mobs_can_spawn_hostile(&self) -> bool { self.is_night() || self.rain >= RAIN_SPAWN_THRESHOLD }
}

unsafe impl Send for EngineState {}

static ENGINE: OnceLock<Mutex<Option<EngineState>>> = OnceLock::new();
static INIT_DONE: AtomicBool = AtomicBool::new(false);

fn engine_lock() -> &'static Mutex<Option<EngineState>> {
    ENGINE.get_or_init(|| Mutex::new(None))
}

pub fn init_engine(files_dir: String, seed: u32) -> bool {
    let Ok(mut guard) = engine_lock().lock() else { return false; };
    if guard.is_some() { return true; }
    *guard = Some(new_state(files_dir, seed, DEFAULT_RENDER_DISTANCE));
    INIT_DONE.store(true, Ordering::SeqCst);
    true
}

/// Chunk radius kept loaded around the player.
pub const DEFAULT_RENDER_DISTANCE: i32 = 12;

/// Loads (or creates) a world and generates the chunks around the player. Pure construction: it
/// touches no globals, so it can run off the main thread and in tests.
pub fn new_state(files_dir: String, seed: u32, render_distance: i32) -> EngineState {
    let save_dir = files_dir.clone();
    let player_save = crate::world::save::load_player(&save_dir);
    let had_save = player_save.is_some();
    let progress = player_save.as_ref().map(|ps| ps.progress.clone()).unwrap_or_default();
    let (px, py, pz, yaw, pitch, inv) = if let Some(ps) = &player_save {
        let inv = {
            let mut inv = Inventory::default();
            inv.selected = ps.inventory.selected.min(8);
            for (i, slot) in ps.inventory.slots.iter().enumerate().take(inv.slots.len()) {
                inv.slots[i].id = slot.id;
                inv.slots[i].count = slot.count;
            }
            for (i, slot) in progress.armor.iter().enumerate().take(inv.armor.len()) {
                inv.armor[i] = crate::inventory::InvSlot { id: slot.id, count: slot.count };
            }
            inv.placed = ps.stats.placed;
            inv.broken = ps.stats.broken;
            inv
        };
        (ps.x, ps.y, ps.z, ps.yaw, ps.pitch, inv)
    } else {
        (0.0, 80.0, 0.0, 0.0, 0.0, Inventory::default())
    };

    let mut player = Player::new(px, py, pz);
    player.yaw = yaw;
    player.pitch = pitch;
    if had_save {
        player.max_health = progress.max_health.clamp(crate::player::MIN_MAX_HEALTH, crate::player::CAP_MAX_HEALTH);
        player.health = player.max_health;
        player.blessings = progress.blessings;
    }

    let dim = if progress.dim < 3 { progress.dim } else { 0 };
    let mut chunks = ChunkMap::new_dim(seed, dim_dir(&save_dir, dim), dim);
    chunks.render_distance = render_distance;
    chunks.ensure_radius(px as i32, pz as i32);

    if !had_save {
        for y in (0..128).rev() {
            if chunks.get_block_world(0, y, 0) != 0 && Block::from_id(chunks.get_block_world(0, y, 0)).is_solid() {
                player.pos.y = y as f32 + 1.1;
                break;
            }
        }
    }

    let mut dim_pos = [Vec3::ZERO; 3];
    for (i, p) in progress.dim_pos.iter().enumerate().take(3) { dim_pos[i] = vec3(p[0], p[1], p[2]); }
    let mut dim_visited = [false; 3];
    for (i, v) in progress.dim_visited.iter().enumerate().take(3) { dim_visited[i] = *v; }
    dim_visited[dim as usize] = true;
    // Crafted recipes, unpacked from the saved bitmask.
    let mut crafted_recipes = vec![false; crate::inventory::RECIPES.len()];
    for (i, c) in crafted_recipes.iter_mut().enumerate() {
        *c = progress.crafted_recipes.get(i / 8).is_some_and(|b| b & (1 << (i % 8)) != 0);
    }

    // A Vec rather than a fixed array so adding a profession doesn't invalidate a save mid-development.
    let mut trades_done = [0u32; crate::villager::ALL.len()];
    for (i, n) in progress.trades_done.iter().enumerate().take(trades_done.len()) { trades_done[i] = *n; }

    // Rewind the clock so the world resumes at the time of day it was saved at.
    let start_time = Instant::now()
        .checked_sub(std::time::Duration::from_secs_f32(progress.world_secs.clamp(0.0, 86_400.0)))
        .unwrap_or_else(Instant::now);

    EngineState {
        chunks,
        player,
        inventory: inv,
        save_dir,
        renderer: None,
        width: 0,
        height: 0,
        start_time,
        last_tick: Instant::now(),
        window_ptr: None,
        needs_resize: false,
        running: true,
        mobs: Vec::new(),
        spawn_timer: 2.0,
        spawn_rng: seed ^ 0x9E3779B9,
        respawn: progress.respawn.map(|p| vec3(p[0], p[1], p[2])),
        checkpoint_cd: 0.0,
        beacon_cd: 0.0,
        particles: Vec::new(),
        projectiles: Vec::new(),
        dim,
        seed,
        stored: [None, None, None],
        dim_pos,
        dim_visited,
        portal_armed: true,
        portal_charge: 0.0,
        end_dragon_dead: progress.end_dragon_dead,
        nether_wither_dead: progress.nether_wither_dead,
        was_night: false,
        night_survived: player_save.as_ref().map(|ps| ps.stats.night_seen).unwrap_or(false),
        containers: crate::container::Containers::load(&files_dir),
        open_chest: None,
        smelter: Smelter::default(),
        best_beacon: progress.best_beacon,
        deepest_y: if had_save { progress.deepest_y } else { py as i32 },
        mend_cd: 0.0,
        trade_prof: 0,
        trades_done,
        weather: if progress.weather <= WEATHER_STORM { progress.weather } else { WEATHER_CLEAR },
        weather_cd: progress.weather_cd.clamp(0.0, WEATHER_MAX_SECS),
        // Resume mid-downpour rather than fading in from a clear sky.
        rain: rain_target(progress.weather),
        ambience: crate::ambience::Ambience::default(),
        prev_walk: 0.0,
        fishing: crate::fishing::Fishing::default(),
        farm_cd: 0.0,
        crafted_recipes,
        did_shear: false, did_fish: false, did_brush: false, did_harvest: false, did_rest: false,
        autosave_cd: AUTOSAVE_SECS,
    }
}

pub fn with_engine<F, R>(f: F) -> Option<R>
where F: FnOnce(&mut EngineState) -> R {
    let mut guard = engine_lock().lock().ok()?;
    let state = guard.as_mut()?;
    Some(f(state))
}

// Non-blocking variant: if the render thread currently holds the engine lock (it holds it for the
// whole frame, including the GPU fence wait), this returns None instead of stalling the caller.
// The Compose UI polls read-only JSON on the main thread, so it must never block on the 3D frame.
pub fn with_engine_try<F, R>(f: F) -> Option<R>
where F: FnOnce(&mut EngineState) -> R {
    let mut guard = engine_lock().try_lock().ok()?;
    let state = guard.as_mut()?;
    Some(f(state))
}

/// Seconds between autosaves while the world is being simulated.
pub const AUTOSAVE_SECS: f32 = 60.0;

/// Snapshot of everything `player.json` holds.
fn player_save(state: &EngineState) -> crate::world::save::PlayerSave {
    use crate::world::save::{InvSlotSave, InventorySave, PlayerSave, ProgressSave, StatsSave};
    PlayerSave {
        x: state.player.pos.x,
        y: state.player.pos.y,
        z: state.player.pos.z,
        yaw: state.player.yaw,
        pitch: state.player.pitch,
        inventory: InventorySave {
            selected: state.inventory.selected,
            slots: state.inventory.slots.iter().map(|s| InvSlotSave { id: s.id, count: s.count }).collect(),
        },
        stats: StatsSave {
            placed: state.inventory.placed,
            broken: state.inventory.broken,
            walked: state.player.walk_dist as i32,
            night_seen: state.night_survived,
        },
        progress: ProgressSave {
            armor: state.inventory.armor.iter().map(|s| InvSlotSave { id: s.id, count: s.count }).collect(),
            max_health: state.player.max_health,
            dim: state.dim,
            dim_pos: state.dim_pos.iter().map(|p| [p.x, p.y, p.z]).collect(),
            dim_visited: state.dim_visited.to_vec(),
            respawn: state.respawn.map(|p| [p.x, p.y, p.z]),
            end_dragon_dead: state.end_dragon_dead,
            nether_wither_dead: state.nether_wither_dead,
            world_secs: state.start_time.elapsed().as_secs_f32(),
            best_beacon: state.best_beacon,
            deepest_y: state.deepest_y,
            blessings: state.player.blessings,
            trades_done: state.trades_done.to_vec(),
            weather: state.weather,
            weather_cd: state.weather_cd,
            crafted_recipes: {
                let mut bits = vec![0u8; state.crafted_recipes.len().div_ceil(8)];
                for (i, c) in state.crafted_recipes.iter().enumerate() {
                    if *c { bits[i / 8] |= 1 << (i % 8); }
                }
                bits
            },
        },
    }
}

/// Persists the whole world in one consistent pass: every edited chunk (all dimensions, including
/// ones that already scrolled out of range), then the chests, then the player. Called on autosave,
/// from `onStop`, and on teardown, so a background kill or crash loses at most one autosave
/// interval and never leaves chunk edits on disk without the inventory changes that went with them.
pub fn save_world(state: &mut EngineState) {
    state.autosave_cd = AUTOSAVE_SECS;
    // Only the host persists an online world; clients are transient mirrors of host state.
    if crate::net::role() == crate::net::ROLE_CLIENT {
        state.chunks.discard_pending();
        for m in state.stored.iter_mut().flatten() { m.discard_pending(); }
        return;
    }
    state.dim_pos[state.dim as usize] = state.player.pos;
    state.chunks.save_dirty();
    // The stashed dimensions too (each writes to its own save subdir).
    for m in state.stored.iter_mut().flatten() { m.save_dirty(); }
    let _ = state.containers.save(&state.save_dir);
    let _ = crate::world::save::save_player(&state.save_dir, &player_save(state));
}

/// Counts down the autosave timer by one simulated frame and saves when it runs out.
fn tick_autosave(state: &mut EngineState, dt: f32) {
    state.autosave_cd -= dt;
    if state.autosave_cd <= 0.0 { save_world(state); }
}

/// JNI `nativeSave`: saves now (the activity calls it from `onStop`). No-op before the engine exists.
pub fn save_now() { with_engine(save_world); }

pub fn destroy_engine() {
    let mut guard = match engine_lock().lock() { Ok(g) => g, Err(_) => return, };
    if let Some(mut state) = guard.take() {
        save_world(&mut state);
        if let Some(mut renderer) = state.renderer.take() {
            unsafe {
                renderer.destroy();
                renderer.ctx.destroy();
            }
            if let Some(win) = state.window_ptr {
                unsafe { crate::vulkan::context::ANativeWindow_release(win); }
            }
        }
        INIT_DONE.store(false, Ordering::SeqCst);
    }
}

pub fn create_renderer(window: *mut ANativeWindow, width: i32, height: i32) -> Result<(), String> {
    let w = width.max(1) as u32;
    let h = height.max(1) as u32;
    with_engine(|state| {
        state.window_ptr = Some(window);
        state.width = w;
        state.height = h;
        let ctx = unsafe { VulkanContext::new(window, width, height)? };
        let renderer = unsafe { VulkanRenderer::new(ctx, w, h)? };
        state.renderer = Some(renderer);
        state.chunks.ensure_radius(state.player.pos.x as i32, state.player.pos.z as i32);
        Ok::<(), String>(())
    }).unwrap_or(Err("engine not init".into()))?;
    rebuild_all_meshes();
    Ok(())
}

pub fn rebuild_all_meshes() {
    with_engine(|state| {
        if state.renderer.is_none() { return; }
        let positions: Vec<_> = state.chunks.chunks_iter().map(|(pos, _)| *pos).collect();
        for pos in positions { rebuild_chunk_meshes(state, pos); }
    });
}

pub fn rebuild_chunk_meshes(state: &mut EngineState, chunk_pos: crate::world::chunk::ChunkPos) {
    if state.chunks.get(chunk_pos).is_none() { return; }
    let meshes = {
        let chunk = state.chunks.get(chunk_pos).unwrap();
        let map_ptr = &state.chunks as *const ChunkMap;
        let closure = move |wx: i32, wy: i32, wz: i32| -> (Id, u8) {
            unsafe { ((*map_ptr).get_block_world(wx, wy, wz), (*map_ptr).get_meta_world(wx, wy, wz)) }
        };
        let tint = move |wx: i32, wz: i32| -> [f32;3] {
            unsafe { (*map_ptr).grass_tint(wx, wz) }
        };
        mesher::mesh_chunk(chunk, &closure, &tint)
    };
    if let Some(renderer) = state.renderer.as_mut() {
        for (sec_idx, mesh_opt) in meshes.into_iter().enumerate() {
            if let Some(mesh) = mesh_opt {
                if mesh.is_empty() { continue; }
                unsafe { renderer.enqueue_mesh(chunk_pos.0, sec_idx as i32, chunk_pos.1, Some(mesh)); }
            } else {
                unsafe { renderer.enqueue_mesh(chunk_pos.0, sec_idx as i32, chunk_pos.1, None); }
            }
        }
    }
}

pub fn resize_renderer(width: i32, height: i32) {
    with_engine(|state| {
        state.width = width.max(1) as u32;
        state.height = height.max(1) as u32;
        state.needs_resize = true;
    });
}

pub fn destroy_renderer() {
    with_engine(|state| {
        if let Some(mut r) = state.renderer.take() {
            unsafe { r.destroy(); r.ctx.destroy(); }
        }
        if let Some(win) = state.window_ptr.take() {
            unsafe { crate::vulkan::context::ANativeWindow_release(win); }
        }
    });
}

include!("engine_part1.rs");
include!("engine_part2.rs");
include!("engine_part3.rs");
include!("engine_part4.rs");
include!("engine_part5.rs");include!("engine_part6.rs");
