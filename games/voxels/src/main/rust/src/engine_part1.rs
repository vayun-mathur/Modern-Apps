/// Chunks generated per frame while exploring (beyond the player's immediate neighbourhood).
const CHUNK_GEN_BUDGET: usize = 2;

// World mode, chosen when the world was created. Creative (the default, and what every world
// created before modes existed gets) allows flight and the free block catalog; survival doesn't.
static CREATIVE: AtomicBool = AtomicBool::new(true);

/// Sets the world mode for this session (JNI `nativeSetCreative`, called before the world loads).
pub fn set_creative(creative: bool) { CREATIVE.store(creative, Ordering::SeqCst); }

/// Whether this session is creative.
pub fn is_creative() -> bool { CREATIVE.load(Ordering::SeqCst) }

/// Flight is a creative-only ability: honour a toggle request in creative, and in survival make sure
/// the player is never left flying (e.g. a creative save opened as survival).
fn apply_fly_toggle(state: &mut EngineState, toggle: bool, creative: bool) {
    if !creative { state.player.flying = false; return; }
    if toggle { state.player.flying = !state.player.flying; }
}

// Set by the pause menu. While set, single-player worlds stop simulating (no physics, mobs, clock,
// smelting or autosave) but keep drawing frames so the world stays visible behind the menu.
static PAUSED: AtomicBool = AtomicBool::new(false);

/// Pause or resume the simulation. Pausing also drops any held move/look/jump input so the player
/// doesn't keep walking when the menu closes.
pub fn set_paused(paused: bool) {
    PAUSED.store(paused, Ordering::SeqCst);
    if paused { crate::input::release_all(); }
}

/// Whether the pause menu is up.
pub fn is_paused() -> bool { PAUSED.load(Ordering::SeqCst) }

/// One engine-thread frame at time `now` (the caller holds the engine lock).
fn tick_and_render_state(state: &mut EngineState, now: Instant) {
    let dt = (now - state.last_tick).as_secs_f32().clamp(0.0, 0.05);
    state.last_tick = now;
    if !state.running { return; }
    // Online worlds keep running for everyone else, so the local pause menu only stops input.
    let paused = is_paused() && !crate::net::is_networked();
    step(state, dt, paused);
}

/// One frame: simulate (unless paused), then draw and publish UI state.
fn step(state: &mut EngineState, dt: f32, paused: bool) {
        // Drain any decrypted, already-authorized network messages before simulating this frame, so
        // the net thread never contends for the engine lock (mirrors the publish_ui/CACHE pattern).
        crate::net::apply_inbound(state);
        process_actions(state);
        if !paused { simulate(state, dt); }
        render_frame(state);
}

/// Advances the world by `dt` seconds: input, physics, mobs, weather, farming, autosave.
fn simulate(state: &mut EngineState, dt: f32) {
        // The world clock only moves with simulated time: not while paused, not in the background.
        state.world_secs = wrap_world_secs(state.world_secs + dt);
        let input = crate::input::snapshot_and_clear_look();
        apply_fly_toggle(state, input.toggle_fly, is_creative());

        // Rate-based look: (curved) stick displacement -> angular velocity (rad/s at full deflection).
        let look_speed = 3.2 * crate::input::look_sensitivity();
        state.player.yaw += input.look_yaw_rate * look_speed * dt;
        state.player.pitch += input.look_pitch_rate * look_speed * dt;
        // Swipe-look: the drag since last frame, applied directly (sensitivity scales it too).
        state.player.yaw += input.look_dyaw * crate::input::look_sensitivity();
        state.player.pitch += input.look_dpitch * crate::input::look_sensitivity();
        state.player.pitch = state.player.pitch.clamp(-1.55, 1.55);

        let mut input_mut = input;
        let move_len = (input.move_forward*input.move_forward + input.move_right*input.move_right).sqrt();
        if move_len > 0.9 { input_mut.sprint = true; }

        // Latch "survived a night" on the night -> day flip, as long as the player is alive and in
        // the overworld (the Nether and End have no day cycle).
        let night_now = state.is_night();
        if state.was_night && !night_now && !state.player.dead && state.dim == 0 { state.night_survived = true; }
        state.was_night = night_now;

        tick_smelter(state, dt);
        tick_blessings(state, dt);
        // Overworld-only depth record, so the Nether's low ceiling doesn't hand out the mining badge.
        if state.dim == 0 { state.deepest_y = state.deepest_y.min(state.player.pos.y as i32); }

        // Elytra equipped in the chest slot enables gliding.
        state.player.elytra = state.inventory.armor[1].id == 1084;
        state.player.tick(dt, &input_mut, &state.chunks);
        state.player.tick_status(dt);
        // Lava burns (Fire Resistance negates it).
        {
            let p = state.player.pos;
            let feet = state.chunks.get_block_world(p.x.floor() as i32, p.y.floor() as i32, p.z.floor() as i32);
            let head = state.chunks.get_block_world(p.x.floor() as i32, (p.y + 1.0).floor() as i32, p.z.floor() as i32);
            if (feet == 84 || head == 84) && !state.player.has_effect(crate::item::Effect::FireResistance)
                && !state.player.blessed(Passive::Pyre) {
                state.player.health -= 6.0 * dt;
                if state.player.health <= 0.0 { state.player.health = 0.0; state.player.dead = true; }
            }
        }
        // Portal traversal: step out to re-arm, dwell inside to travel.
        {
            let p = state.player.pos;
            let bl = state.chunks.get_block_world(p.x.floor() as i32, p.y.floor() as i32, p.z.floor() as i32);
            if bl != 86 && bl != 87 {
                state.portal_armed = true;
                state.portal_charge = 0.0;
            } else if state.portal_armed {
                state.portal_charge += dt;
                if state.portal_charge > 0.4 {
                    let target = if bl == 86 { if state.dim == 1 { 0 } else { 1 } } else if state.dim == 2 { 0 } else { 2 };
                    switch_dimension(state, target);
                }
            }
        }

        // Warding stone checkpoint: standing near one sets respawn + slowly refills Estus (bonfire).
        state.checkpoint_cd = (state.checkpoint_cd - dt).max(0.0);
        {
            let p = state.player.pos;
            let (bx, by, bz) = (p.x.floor() as i32, p.y.floor() as i32, p.z.floor() as i32);
            let mut near = None;
            'scan: for dy in -2..=2 { for dx in -4..=4 { for dz in -4..=4 {
                if state.chunks.get_block_world(bx + dx, by + dy, bz + dz) == 81 {
                    near = Some(vec3((bx + dx) as f32 + 0.5, (by + dy) as f32 + 1.0, (bz + dz) as f32 + 0.5));
                    break 'scan;
                }
            }}}
            if let Some(cp) = near {
                state.respawn = Some(cp);
                if state.checkpoint_cd <= 0.0 {
                    state.checkpoint_cd = 2.0;
                    let have: i32 = state.inventory.slots.iter().filter(|s| s.id == 1024).map(|s| s.count).sum();
                    if have < 4 { state.inventory.add_block(1024); }
                    state.player.add_effect(crate::item::Effect::Regeneration, 2.5, 0);
                }
            }
        }

        // Beacon: a lit beacon standing on a pyramid of mineral blocks projects buffs to nearby players.
        // Scan (throttled) for the nearest beacon around the player, grade its pyramid, and grant effects.
        state.beacon_cd = (state.beacon_cd - dt).max(0.0);
        if state.beacon_cd <= 0.0 {
            state.beacon_cd = 0.5;
            let p = state.player.pos;
            let (bx, by, bz) = (p.x.floor() as i32, p.y.floor() as i32, p.z.floor() as i32);
            let mut best: Option<(i32, i32)> = None; // (tier, dist2) of the nearest in-range beacon
            for dy in -12..=12 { for dx in -16..=16 { for dz in -16..=16 {
                if state.chunks.get_block_world(bx + dx, by + dy, bz + dz) == 88 {
                    let tier = beacon_tier(&state.chunks, bx + dx, by + dy, bz + dz);
                    if tier == 0 { continue; }
                    let d2 = dx * dx + dz * dz;
                    let range = tier * 10 + 10;
                    if d2 <= range * range && best.map_or(true, |b| d2 < b.1) {
                        best = Some((tier, d2));
                    }
                }
            }}}
            if let Some((tier, _)) = best {
                use crate::item::Effect::*;
                state.best_beacon = state.best_beacon.max(tier);
                let e = &mut state.player;
                e.add_effect(Speed, 6.0, 0);
                if tier >= 2 { e.add_effect(Haste, 6.0, 0); }
                if tier >= 3 { e.add_effect(Resistance, 6.0, 0); }
                if tier >= 4 { e.add_effect(Strength, 6.0, 0); e.add_effect(Regeneration, 3.0, 0); e.add_effect(JumpBoost, 6.0, 0); }
            }
        }

        let px = state.player.pos.x as i32;
        let pz = state.player.pos.z as i32;
        // A few new chunks per frame (nearest first); the player's own neighbourhood always loads.
        state.chunks.ensure_radius_budget(px, pz, CHUNK_GEN_BUDGET, 1);


        // --- Mobs: AI/physics tick, spawn/despawn near the player. ---
        let player_pos = state.player.pos;
        if crate::net::is_client() {
            // The host owns mob AI, spawning and combat; a client just eases its mobs toward the
            // latest host snapshot (projectiles arrive via ProjSnapshot).
            crate::net::interpolate_mobs(state, dt);
        } else {
        {
            let chunks = &state.chunks;
            let terrain = crate::entity::Terrain {
                solid: &|x, y, z| chunks.solid_at(x, y, z),
                surface: &|x, z, ceiling| chunks.surface_below(x, z, ceiling, 2),
            };
            let ward = if state.player.blessed(Passive::WardUndead) { WARD_UNDEAD_RADIUS } else { 0.0 };
            for m in state.mobs.iter_mut() {
                m.repelled = if is_undead(m.kind) { ward } else { 0.0 };
                m.tick(dt, player_pos, &terrain);
            }
        }
        // Mob melee contact damage + creeper fuse + ranged fire.
        let mut incoming = 0.0f32;
        let mut explosions: Vec<Vec3> = Vec::new();
        let mut new_shots: Vec<Projectile> = Vec::new();
        // Who actually landed a melee blow this tick, so Warding strikes back at them alone.
        let mut melee: Vec<usize> = Vec::new();
        let eye = player_pos + vec3(0.0, 1.2, 0.0);
        for (mi, m) in state.mobs.iter_mut().enumerate() {
            m.attack_cd = (m.attack_cd - dt).max(0.0);
            let d = (m.pos - player_pos).length();
            let ranged = matches!(m.kind, MobKind::Blaze | MobKind::Shulker | MobKind::Ghast);
            let max_range = if m.kind == MobKind::Ghast { 42.0 } else { 30.0 };
            if m.kind == MobKind::Creeper {
                if d < 3.2 { m.fuse += dt; if m.fuse >= 1.4 { explosions.push(m.pos); m.health = 0.0; } }
                else { m.fuse = (m.fuse - dt * 0.6).max(0.0); }
            } else if ranged && d > 3.0 && d < max_range && m.attack_cd <= 0.0 {
                // Fire a projectile at the player's chest.
                m.attack_cd = match m.kind { MobKind::Blaze => 1.6, MobKind::Ghast => 3.0, _ => 2.2 };
                let origin = m.pos + vec3(0.0, m.kind.height() * 0.6, 0.0);
                let dir = (eye - origin).normalize_or_zero();
                let (kind, spd, dmg, explosive) = match m.kind {
                    MobKind::Blaze => (ProjKind::Fireball, 16.0, 5.0, false),
                    MobKind::Ghast => (ProjKind::Fireball, 12.0, 7.0, true),
                    _ => (ProjKind::ShulkerBullet, 9.0, 4.0, false),
                };
                new_shots.push(Projectile { pos: origin, vel: dir * spd, life: 5.0, kind, from_player: false, damage: dmg, explosive });
            } else if m.kind.is_boss() && d < 5.0 && m.attack_cd <= 0.0 {
                m.attack_cd = 1.0; incoming += m.kind.contact_damage(); melee.push(mi);
            } else if m.kind.hostile() && !m.kind.is_boss() && !ranged && d < 1.7 && m.attack_cd <= 0.0 {
                m.attack_cd = 0.8; incoming += m.kind.contact_damage(); melee.push(mi);
            }
        }
        state.projectiles.extend(new_shots);
        tick_projectiles(state, dt);
        if incoming > 0.0 { hurt_player_from(state, incoming, &melee); }
        for c in explosions { explode(state, c, 3.0); }
        // Slain Ender Dragon: mark defeated + a victory burst of particles.
        if let Some(dpos) = state.mobs.iter().find(|m| m.kind == MobKind::Dragon && m.health <= 0.0).map(|m| m.pos) {
            state.end_dragon_dead = true;
            spawn_particles(&mut state.spawn_rng, &mut state.particles, dpos, 60, [0.7, 0.3, 0.95], 8.0, 1.4, 0.4);
        }
        // Slain Wither: mark defeated + drop the Nether Star (via loot) + a dark burst.
        if let Some(wpos) = state.mobs.iter().find(|m| m.kind == MobKind::Wither && m.health <= 0.0).map(|m| m.pos) {
            state.nether_wither_dead = true;
            spawn_particles(&mut state.spawn_rng, &mut state.particles, wpos, 60, [0.15, 0.15, 0.2], 8.0, 1.4, 0.4);
        }
        // Remove dead mobs and auto-collect their drops. Glaucus makes ordinary kills pay double,
        // but not bosses — a second Nether Star would hand out a free extra beacon.
        let mut loot: Vec<(Id, bool)> = Vec::new();
        state.mobs.retain(|m| if m.health <= 0.0 {
            // An elite was twice the fight, so it pays twice.
            let times = if m.elite { 2 } else { 1 };
            for _ in 0..times { loot.extend(m.kind.loot().iter().map(|&id| (id, m.kind.is_boss()))); }
            false
        } else { true });
        let lucky = state.player.blessed(Passive::SeaLuck);
        for (id, boss) in loot {
            let n = if lucky && !boss { 2 } else { 1 };
            for _ in 0..n { state.inventory.add_block(id); }
        }
        state.mobs.retain(|m| (m.pos - player_pos).length() < 96.0 && m.pos.y > -8.0);
        state.spawn_timer -= dt;
        if state.spawn_timer <= 0.0 {
            state.spawn_timer = 2.5;
            if state.mobs.len() < 22 {
                use crate::world::chunk::CHUNK_HEIGHT;
                let mut rng = state.spawn_rng;
                let mut rand = |r: &mut u32| { let mut x = *r; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *r = x; (x >> 8) as f32 / 16_777_216.0 };
                let ang = rand(&mut rng) * std::f32::consts::TAU;
                let dist = 24.0 + rand(&mut rng) * 20.0;
                let sx = player_pos.x + ang.cos() * dist;
                let sz = player_pos.z + ang.sin() * dist;
                let (bx, bz) = (sx.floor() as i32, sz.floor() as i32);
                let mut gy = None;
                for y in (2..CHUNK_HEIGHT as i32).rev() {
                    let id = state.chunks.get_block_world(bx, y, bz);
                    if id != 0 && Block::from_id(id).is_solid() { gy = Some(y); break; }
                }
                if let Some(gy) = gy {
                    // Hostiles come out after dark — and, as in Minecraft, under a heavy enough sky.
                    let hostile = state.mobs_can_spawn_hostile();
                    let kind = if state.dim == 1 {
                        // Nether: hostile natives only (Ghasts are rarer floating threats).
                        match (rand(&mut rng) * 8.0) as u32 { 0 => MobKind::Ghast, 1 | 2 => MobKind::Blaze, 3 | 4 => MobKind::WitherSkeleton, _ => MobKind::Zombie }
                    } else if state.dim == 2 {
                        // Sparse End hostiles: End-city Shulker guardians + wandering wither skeletons.
                        if rand(&mut rng) < 0.4 { MobKind::Shulker } else { MobKind::WitherSkeleton }
                    } else if hostile {
                        if rand(&mut rng) < 0.5 { MobKind::Zombie } else { MobKind::Creeper }
                    } else if rand(&mut rng) < 0.18 {
                        MobKind::Villager
                    } else {
                        match (rand(&mut rng) * 4.0) as u32 { 0 => MobKind::Pig, 1 => MobKind::Cow, 2 => MobKind::Sheep, _ => MobKind::Chicken }
                    };
                    let pos = vec3(sx, gy as f32 + 1.0, sz);
                    let seed = rng ^ (bx as u32).wrapping_mul(2654435761) ^ (bz as u32).wrapping_mul(40503);
                    state.mobs.push(Mob::new(kind, pos, seed));
                }
                state.spawn_rng = rng;
            }
        }
        }
        // Death: burn one heart of max HP (floored) and respawn at world spawn — the "lives" system.
        if state.player.dead {
            use crate::world::chunk::CHUNK_HEIGHT;
            state.player.max_health = (state.player.max_health - 2.0).max(crate::player::MIN_MAX_HEALTH);
            state.player.pos = if let Some(rp) = state.respawn {
                rp
            } else {
                state.chunks.ensure_radius(0, 0);
                let mut ty = 80;
                for y in (1..CHUNK_HEIGHT as i32).rev() {
                    let id = state.chunks.get_block_world(0, y, 0);
                    if id != 0 && Block::from_id(id).is_solid() { ty = y; break; }
                }
                vec3(0.5, ty as f32 + 1.1, 0.5)
            };
            state.player.vel = Vec3::ZERO;
            state.player.health = state.player.max_health;
            state.player.absorption = 0.0;
            state.player.effects.clear();
            state.player.dead = false;
            state.player.air_max_y = state.player.pos.y;
            state.mobs.clear();
        }
        // Elytra glide vapor trail: white streaks off both shoulders (the visible "wings" in 1st person).
        if state.player.gliding {
            let base = state.player.pos + vec3(0.0, 1.25, 0.0);
            let r = state.player.right();
            for &side in &[-1.0f32, 1.0] {
                if state.particles.len() < 400 {
                    state.particles.push(Particle { pos: base + r * (0.55 * side), vel: Vec3::ZERO, life: 0.5, max_life: 0.5, size: 0.09, color: [0.88, 0.95, 1.0], gravity: crate::entity::BURST_GRAVITY });
                }
            }
        }
        tick_particles(&mut state.particles, dt);
        tick_weather(state, dt, player_pos);
        tick_ambience(state, dt, player_pos);
        tick_fishing(state, dt, player_pos);
        tick_farmland(state, dt, player_pos);
        tick_autosave(state, dt);
}

/// Builds the entity/particle meshes, draws the frame (if a surface is attached) and refreshes the
/// UI and network caches.
fn render_frame(state: &mut EngineState) {
        // Meshing runs even while paused, so a world that finishes loading behind the pause menu
        // (or a dimension switch) still fills in.
        mesh_dirty_chunks(state);
        let (mut entity_verts, mut entity_indices) = build_entity_mesh(&state.mobs);
        {
            let right = state.player.right();
            crate::entity::append_remote_players(&mut entity_verts, &mut entity_indices, &crate::net::remote_player_poses());
            append_particles(&mut entity_verts, &mut entity_indices, &state.particles, right, Vec3::Y);
            append_projectiles(&mut entity_verts, &mut entity_indices, &state.projectiles, right, Vec3::Y);
        }

        // Start at midday (day_t=0.5 -> sun overhead) so the world is lit when the app opens.
        let time = state.world_time();
        let (pcx, pcz) = { let c = crate::world::chunk::ChunkPos::from_world(state.player.pos.x as i32, state.player.pos.z as i32); (c.0, c.1) };
        let uploads = upload_budget();
        if let Some(renderer) = state.renderer.as_mut().filter(|r| !r.suspended) {
            if state.needs_resize {
                let w = state.width; let h = state.height;
                unsafe { let _ = renderer.resize(w, h); }
                state.needs_resize = false;
            }
            let eye = state.player.eye_pos();
            let center = eye + state.player.forward();
            let up = Vec3::Y;
            let view = Mat4::look_at_rh(eye, center, up);
            // Aspect + pre-rotation come from the swapchain's surface transform (Android portrait-native
            // panels present landscape via ROTATE_90/270, with images in native orientation).
            let (swap_aspect, pre_rot_angle) = renderer.swapchain.pre_rotation();
            let ext = renderer.swapchain.extent;
            let (ew, eh) = (ext.width.max(1) as f32, ext.height.max(1) as f32);
            let aspect = if swap_aspect { eh / ew } else { ew / eh };
            let proj = Mat4::perspective_rh(70f32.to_radians(), aspect, 0.1, 500.0);
            let vulkan_correction = Mat4::from_cols_array(&[1.0,0.0,0.0,0.0, 0.0,-1.0,0.0,0.0, 0.0,0.0,0.5,0.0, 0.0,0.0,0.5,1.0]);
            let view_proj = Mat4::from_rotation_z(pre_rot_angle) * vulkan_correction * proj * view;
            let eb = (eye.x.floor() as i32, eye.y.floor() as i32, eye.z.floor() as i32);
            let underwater = if state.chunks.get_block_world(eb.0, eb.1, eb.2) == 12 { 1.0 } else { 0.0 };
            // Tangaroa lights the water up the way night vision lights the dark.
            let conduit = underwater > 0.5 && state.player.blessed(Passive::Conduit);
            let nv = if state.player.night_vision() || conduit { 1.0 } else { 0.0 };
            let dim = state.dim;
            let rain = state.rain;
            unsafe {
                renderer.update_ubo(view_proj, state.player.pos, time, underwater, nv, dim, rain);
                renderer.upload_entity_mesh(&entity_verts, &entity_indices);
                let _ = renderer.draw_frame(pcx, pcz, uploads);
            }
        }
        crate::net::publish(state);
        publish_ui(state);
}

// UI state caches. The render thread PUBLISHES fresh JSON into these every tick (while it holds the
// engine lock); the UI getters just read the cache. This avoids the UI ever contending with the render
// thread for the engine lock (which it holds ~continuously), so inventory/debug update every frame.
static DEBUG_CACHE: OnceLock<Mutex<String>> = OnceLock::new();
static INV_CACHE: OnceLock<Mutex<String>> = OnceLock::new();
static STATS_CACHE: OnceLock<Mutex<String>> = OnceLock::new();
static HEALTH_CACHE: OnceLock<Mutex<String>> = OnceLock::new();
static SMELT_CACHE: OnceLock<Mutex<String>> = OnceLock::new();
static AMBIENCE_CACHE: OnceLock<Mutex<String>> = OnceLock::new();
fn cref(c: &'static OnceLock<Mutex<String>>, default: &str) -> &'static Mutex<String> { c.get_or_init(|| Mutex::new(default.to_string())) }

// Called from the render tick (holds the engine lock) to refresh the UI caches.
fn publish_ui(state: &EngineState) {
    let fps = if let Some(r) = &state.renderer {
        let elapsed = (Instant::now() - state.start_time).as_secs_f32();
        if elapsed>0.1 { r.frame_count as f32 / elapsed } else { 0.0 }
    } else { 0.0 };
    let debug = serde_json::json!({
        "fps": format!("{:.1}", fps),
        "pos": format!("{:.1},{:.1},{:.1}", state.player.pos.x, state.player.pos.y, state.player.pos.z),
        "yaw": format!("{:.1}", state.player.yaw.to_degrees()),
        "chunks": state.chunks.len(),
        "flying": state.player.flying,
        "on_ground": state.player.on_ground,
        "time": format!("{:.1}s", state.world_secs),
        "meshes": state.renderer.as_ref().map(|r| r.gpu_meshes.len()).unwrap_or(0),
        "mobs": state.mobs.len(),
        "drawn": state.renderer.as_ref().map(|r| r.drawn_sections).unwrap_or(0),
        "gpu": state.renderer.as_ref().map(|r| {
            let p = r.pass_ms;
            format!("sh{:.1} main{:.1} bloom{:.1} comp{:.1}", p[0], p[1], p[2], p[3])
        }).unwrap_or_default(),
    }).to_string();
    let has = |id: Id| state.inventory.slots.iter().any(|s| s.id == id && s.count > 0);
    let armor_at_least = |lo: Id, hi: Id| state.inventory.armor.iter().all(|s| s.id >= lo && s.id <= hi);
    let stats = serde_json::json!({
        "placed": state.inventory.placed, "broken": state.inventory.broken,
        "walked": state.player.walk_dist as i32, "night": state.night_survived,
        "nether": state.dim_visited[1], "end": state.dim_visited[2],
        "dragon": state.end_dragon_dead, "wither": state.nether_wither_dead,
        "beacon": state.best_beacon,
        "elytra": state.player.elytra,
        "maxHearts": state.player.max_health >= crate::player::CAP_MAX_HEALTH,
        // Full diamond (175..178) or full adamant (199..202) counts as end-tier armor.
        "fullArmor": armor_at_least(1071, 1074) || armor_at_least(1095, 1098),
        "silver": has(1089), "steel": has(1091), "adamant": has(1092),
        "blessing": state.inventory.slots.iter().any(|s| s.count > 0 && crate::blessing::is_blessing(s.id))
            || state.player.blessings.slots.iter().any(|&id| id != 0),
        "depth": state.deepest_y,
        "attuned": state.player.blessings.slots.iter().filter(|&&s| s != 0).count(),
        // The verbs added on top of Matcha's own systems, for the tutorial chain.
        "traded": state.trades_done.iter().any(|&n| n > 0),
        "trader": state.trades_done.iter().any(|&n| crate::villager::level_for(n) >= crate::villager::MAX_LEVEL),
        "sheared": state.did_shear, "fished": state.did_fish, "brushed": state.did_brush,
        "harvested": state.did_harvest, "rested": state.did_rest,
        "recipes": (0..crate::inventory::RECIPES.len())
            .filter(|&i| crate::inventory::recipe_unlocked(i, &state.crafted_recipes)).count(),
    }).to_string();
    let inv = state.inventory.to_json();
    let effects: Vec<_> = state.player.effects.iter().map(|e| serde_json::json!({"k": e.kind.key(), "amp": e.amp, "t": e.secs.ceil() as i32})).collect();
    let estus: i32 = state.inventory.slots.iter().filter(|s| s.id == 1024).map(|s| s.count).sum();
    let boss_mob = state.mobs.iter().find(|m| m.kind.is_boss());
    let boss: f32 = boss_mob.map(|m| (m.health / m.max_health).clamp(0.0, 1.0)).unwrap_or(-1.0);
    let boss_name = match boss_mob.map(|m| m.kind) { Some(MobKind::Dragon) => "Ender Dragon", Some(MobKind::Wither) => "The Wither", _ => "" };
    let health = serde_json::json!({
        "hp": state.player.health, "max": state.player.max_health, "absorb": state.player.absorption,
        "dead": state.player.dead, "estus": estus, "effects": effects, "boss": boss, "bossName": boss_name,
        "elytra": state.player.elytra, "gliding": state.player.gliding,
    }).to_string();
    if let Ok(mut c) = cref(&DEBUG_CACHE, "{}").lock() { *c = debug; }
    if let Ok(mut c) = cref(&STATS_CACHE, "{}").lock() { *c = stats; }
    if let Ok(mut c) = cref(&INV_CACHE, r#"{"selected":0,"slots":[]}"#).lock() { *c = inv; }
    if let Ok(mut c) = cref(&HEALTH_CACHE, "{}").lock() { *c = health; }
    let sm = &state.smelter;
    let secs = crate::inventory::SMELTING.get(sm.recipe).map(|r| r.secs).unwrap_or(1.0);
    let smelt = serde_json::json!({
        "active": sm.active, "recipe": sm.recipe,
        "progress": (sm.progress / secs.max(0.001)).clamp(0.0, 1.0),
        "fuel": if sm.fuel_max > 0.0 { (sm.fuel_left / sm.fuel_max).clamp(0.0, 1.0) } else { 0.0 },
        "blast": sm.blast,
    }).to_string();
    if let Ok(mut c) = cref(&SMELT_CACHE, "{}").lock() { *c = smelt; }
    let a = &state.ambience;
    let ambience = serde_json::json!({
        "stepN": a.step_n, "stepMat": a.step_mat,
        "cueN": a.cue_n, "cueKind": a.cue_kind,
        "cast": state.fishing.is_cast(), "bite": state.fishing.biting(),
        "eerie": a.eerie, "rain": state.rain,
    }).to_string();
    if let Ok(mut c) = cref(&AMBIENCE_CACHE, "{}").lock() { *c = ambience; }
}

pub fn get_debug_json() -> String { cref(&DEBUG_CACHE, r#"{"error":"no engine"}"#).lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }
pub fn get_inventory_json() -> String { cref(&INV_CACHE, r#"{"selected":0,"slots":[]}"#).lock().map(|c| c.clone()).unwrap_or_else(|_| r#"{"selected":0,"slots":[]}"#.into()) }
pub fn get_stats_json() -> String { cref(&STATS_CACHE, "{}").lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }
pub fn get_health_json() -> String { cref(&HEALTH_CACHE, "{}").lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }
pub fn get_smelt_json() -> String { cref(&SMELT_CACHE, "{}").lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }
pub fn get_ambience_json() -> String { cref(&AMBIENCE_CACHE, "{}").lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }
