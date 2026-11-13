pub fn inventory_move(from: usize, to: usize) {
    with_engine(|s| {
        // Clients send the move as an intent; the host applies it and echoes the inventory back.
        if crate::net::is_client() { crate::net::client_inv_intent(from as u32, to as u32); return; }
        s.inventory.move_item(from, to)
    });
}
pub fn inventory_give(id: Id) { with_engine(|s| s.inventory.give(id)); }
pub fn inventory_craft(recipe: usize) -> bool {
    with_engine(|s| {
        if !s.inventory.craft(recipe, &s.crafted_recipes) { return false; }
        // Crafting it is what opens whatever it gates.
        if let Some(flag) = s.crafted_recipes.get_mut(recipe) { *flag = true; }
        true
    }).unwrap_or(false)
}
pub fn do_trade(idx: usize) -> bool {
    with_engine(|s| {
        let prof = crate::villager::Profession::from_index(s.trade_prof as usize);
        let level = crate::villager::level_for(s.trades_done[prof.index()]);
        let Some(offer) = crate::villager::offers(prof, level).get(idx).copied() else { return false; };
        if !s.inventory.trade_offer(&offer) { return false; }
        s.trades_done[prof.index()] = s.trades_done[prof.index()].saturating_add(1);
        true
    }).unwrap_or(false)
}
pub fn get_trades_json() -> String {
    with_engine(|s| {
        let prof = crate::villager::Profession::from_index(s.trade_prof as usize);
        let done = s.trades_done[prof.index()];
        let level = crate::villager::level_for(done);
        let items: Vec<_> = crate::villager::offers(prof, level).iter().enumerate()
            .map(|(i, o)| serde_json::json!({
                "cost": o.cost, "costN": o.cost_n,
                "cost2": o.cost2, "cost2N": o.cost2_n,
                "give": o.give, "giveN": crate::villager::give_count(o),
                "level": crate::villager::level_of_offer(prof, i),
            }))
            .collect();
        serde_json::json!({
            "prof": prof.name(), "level": level, "maxLevel": crate::villager::MAX_LEVEL,
            "done": done, "nextAt": crate::villager::next_level_at(level), "trades": items,
        }).to_string()
    }).unwrap_or_else(|| "{}".to_string())
}

// The stonecutter's whole catalog; the UI filters it down to what the player is carrying.
pub fn get_cuts_json() -> String {
    let items: Vec<_> = crate::inventory::cut_variants().iter()
        .map(|c| serde_json::json!({ "in": c.input, "out": c.output, "outN": c.count }))
        .collect();
    serde_json::json!(items).to_string()
}
pub fn do_cut(idx: usize) -> bool { with_engine(|s| s.inventory.cut(idx)).unwrap_or(false) }
pub fn get_recipes_json() -> String {
    let crafted = with_engine(|s| s.crafted_recipes.clone()).unwrap_or_default();
    let items: Vec<_> = crate::inventory::RECIPES.iter().enumerate()
        .map(|(i, rec)| serde_json::json!({
            "in": rec.in1, "inN": rec.n1, "in2": rec.in2, "in2N": rec.n2,
            "out": rec.out, "outN": rec.out_n,
            "cat": crate::inventory::recipe_category(rec.out),
            "known": crate::inventory::recipe_unlocked(i, &crafted),
            // What the player has to craft first, so a locked row can say why.
            "requires": rec.unlocked_by,
        }))
        .collect();
    serde_json::json!(items).to_string()
}

// Build a world-space pick ray from a tap at pixel (px, py) using a pinhole model matching the
// renderer's 70deg vertical FOV. Avoids the swapchain/matrix Y-flip quirks entirely.
fn screen_ray(state: &EngineState, px: f32, py: f32) -> (Vec3, Vec3) {
    let eye = state.player.eye_pos();
    let forward = state.player.forward();
    let world_up = Vec3::Y;
    let right = forward.cross(world_up).normalize_or_zero();
    let up = right.cross(forward).normalize_or_zero();
    let w = state.width.max(1) as f32;
    let h = state.height.max(1) as f32;
    let aspect = w / h;
    let thf_y = (70f32.to_radians() * 0.5).tan();
    let thf_x = thf_y * aspect;
    let ndc_x = (px / w) * 2.0 - 1.0;
    let ndc_y = (py / h) * 2.0 - 1.0; // screen y grows downward
    let dir = (forward + right * (ndc_x * thf_x) + up * (-ndc_y * thf_y)).normalize_or_zero();
    (eye, dir)
}

fn dim_dir(base: &str, dim: u8) -> String {
    match dim { 1 => format!("{}/nether", base), 2 => format!("{}/end", base), _ => base.to_string() }
}

// Travel to another dimension: stash the current world, swap in the target, place the player on a
// safe landing (or their saved position), and rebuild all chunk meshes (mesh keys are per-coord).
// A beacon's power tier = how many complete pyramid layers of mineral blocks sit beneath it.
// Layer k (k=1..4) at depth k must be a full (2k+1)x(2k+1) square of iron/diamond/emerald blocks
// centred under the beacon. Tier stops at the first incomplete layer.
fn beacon_tier(chunks: &ChunkMap, x: i32, y: i32, z: i32) -> i32 {
    let is_mineral = |id: Id| matches!(id, 23 | 24 | 25 | 93 | 94 | 95);
    let mut tier = 0;
    for k in 1..=4i32 {
        let mut full = true;
        'layer: for dx in -k..=k { for dz in -k..=k {
            if !is_mineral(chunks.get_block_world(x + dx, y - k, z + dz)) { full = false; break 'layer; }
        }}
        if full { tier = k; } else { break; }
    }
    tier
}

fn switch_dimension(state: &mut EngineState, target: u8) {
    if target == state.dim { return; }
    let from = state.dim;
    state.dim_pos[from as usize] = state.player.pos;
    let placeholder = ChunkMap::new_dim(state.seed, dim_dir(&state.save_dir, target), target);
    let cur = std::mem::replace(&mut state.chunks, placeholder);
    state.stored[from as usize] = Some(cur);
    if let Some(m) = state.stored[target as usize].take() { state.chunks = m; }
    state.dim = target;

    let prev = state.player.pos;
    let (mut ax, mut az) = (prev.x, prev.z);
    if target == 1 { ax /= 8.0; az /= 8.0; }               // nether is 8x compressed
    else if target == 0 && from == 1 { ax *= 8.0; az *= 8.0; }
    if target == 2 { ax = 0.0; az = 0.0; }                 // end: central island
    state.chunks.ensure_radius(ax as i32, az as i32);

    if state.dim_visited[target as usize] {
        state.player.pos = state.dim_pos[target as usize];
    } else {
        let (bx, bz) = (ax as i32, az as i32);
        let py: i32 = match target { 1 => 42, 2 => 66, _ => 70 };
        let mat = if target == 2 { 85 } else { 32 };
        for dx in -2..=2 { for dz in -2..=2 {
            state.chunks.set_block_world(bx + dx, py - 1, bz + dz, mat);
            state.chunks.set_block_world(bx + dx, py, bz + dz, 0);
            state.chunks.set_block_world(bx + dx, py + 1, bz + dz, 0);
            state.chunks.set_block_world(bx + dx, py + 2, bz + dz, 0);
        }}
        // Build a return portal beside the landing so the player can travel back.
        if target == 1 || target == 0 {
            build_nether_portal(&mut state.chunks, bx + 2, py, bz);
        } else if target == 2 {
            state.chunks.set_block_world(bx + 2, py - 1, bz, 85);
            state.chunks.set_block_world(bx + 2, py, bz, 87); // end return portal
        }
        state.player.pos = vec3(bx as f32 + 0.5, py as f32 + 0.2, bz as f32 + 0.5);
        state.dim_pos[target as usize] = state.player.pos;
    }
    state.dim_visited[target as usize] = true;
    state.portal_armed = false;
    state.portal_charge = 0.0;
    state.player.vel = Vec3::ZERO;
    state.player.air_max_y = state.player.pos.y;
    state.mobs.clear();
    state.particles.clear();
    state.projectiles.clear();
    // Summon the Ender Dragon on arrival in the End (once).
    if target == 2 && !state.end_dragon_dead {
        state.mobs.push(Mob::new(MobKind::Dragon, vec3(0.0, 86.0, 30.0), 0xD2A6));
    }
    // Summon the Wither on arrival in the Nether (once).
    if target == 1 && !state.nether_wither_dead {
        let wpos = vec3(state.player.pos.x, state.player.pos.y + 12.0, state.player.pos.z - 16.0);
        state.mobs.push(Mob::new(MobKind::Wither, wpos, 0x175E));
    }

    if let Some(r) = state.renderer.as_mut() { unsafe { r.clear_meshes(); } }
    // Re-mesh the new dimension over the next frames, nearest first (see mesh_dirty_chunks), instead
    // of all ~450 chunks inside this one frame.
    let positions: Vec<_> = state.chunks.chunks_iter().map(|(p, _)| *p).collect();
    for pos in positions { if let Some(ch) = state.chunks.get_mut(pos) { ch.mesh_dirty = true; } }
}

// Build a small obsidian nether-portal frame (interior filled with portal blocks) in the X-Y plane.
fn build_nether_portal(chunks: &mut ChunkMap, x: i32, y: i32, z: i32) {
    for gx in -1..=2 { for gy in -1..=3 {
        let border = gx == -1 || gx == 2 || gy == -1 || gy == 3;
        chunks.set_block_world(x + gx, y + gy, z, if border { 78 } else { 86 });
    }}
}

// Flood the air interior enclosed by obsidian in a vertical plane (axis 0 = X-Y, 1 = Z-Y).
fn portal_interior(chunks: &ChunkMap, sx: i32, sy: i32, sz: i32, axis: u8) -> Option<Vec<(i32, i32, i32)>> {
    if chunks.get_block_world(sx, sy, sz) != 0 { return None; }
    let mut region: Vec<(i32, i32, i32)> = Vec::new();
    let mut stack = vec![(sx, sy, sz)];
    while let Some(c) = stack.pop() {
        if region.contains(&c) { continue; }
        if chunks.get_block_world(c.0, c.1, c.2) != 0 { continue; }
        if region.len() > 18 { return None; } // not enclosed
        region.push(c);
        let nbrs = if axis == 0 {
            [(c.0 + 1, c.1, c.2), (c.0 - 1, c.1, c.2), (c.0, c.1 + 1, c.2), (c.0, c.1 - 1, c.2)]
        } else {
            [(c.0, c.1, c.2 + 1), (c.0, c.1, c.2 - 1), (c.0, c.1 + 1, c.2), (c.0, c.1 - 1, c.2)]
        };
        for n in nbrs {
            let nb = chunks.get_block_world(n.0, n.1, n.2);
            if nb == 0 { if !region.contains(&n) && !stack.contains(&n) { stack.push(n); } }
            else if nb != 78 { return None; } // border must be obsidian
        }
    }
    if (2..=15).contains(&region.len()) { Some(region) } else { None }
}

// Ignite a nether portal by tapping its obsidian frame: fill the enclosed interior with portal blocks.
fn light_portal(state: &mut EngineState, x: i32, y: i32, z: i32) -> bool {
    for axis in 0..2u8 {
        if let Some(cells) = portal_interior(&state.chunks, x, y + 1, z, axis) {
            for (cx, cy, cz) in &cells {
                state.chunks.set_block_world(*cx, *cy, *cz, 86);
                mark_neighbors_dirty(state, *cx, *cz);
            }
            return true;
        }
    }
    false
}

// Make sure a chest at this position has a container, rolling world loot the first time a chest the
// player never placed is opened.
fn ensure_chest(state: &mut EngineState, x: i32, y: i32, z: i32) -> crate::container::ContainerKey {
    let key = (state.dim, x, y, z);
    if !state.containers.contains(key) {
        let lucky = state.player.blessed(Passive::SeaLuck);
        state.containers.insert(key, crate::container::roll_loot(x, y, z, state.dim, lucky));
    }
    key
}

// Apply damage to the player through equipped armor (each defense point cuts ~4%, capped), wearing
// the armor down when a hit actually lands. Warding reflects a share back at whoever landed the
// blow, which is only known for melee — ranged and explosive hits pass None and reflect nothing.
fn hurt_player(state: &mut EngineState, amt: f32) { hurt_player_from(state, amt, &[]) }

fn hurt_player_from(state: &mut EngineState, amt: f32, attackers: &[usize]) {
    let def = state.inventory.armor_defense();
    let reduced = amt * (1.0 - (def * 0.04)).max(0.2);
    let before = state.player.health;
    state.player.damage(reduced);
    if state.player.health < before {
        if !state.player.blessed(Passive::ArmorWard) { state.inventory.damage_armor(); }
        if state.player.blessed(Passive::Thorns) {
            for &i in attackers {
                if let Some(m) = state.mobs.get_mut(i) { m.health -= reduced * 0.5; }
            }
        }
    }
}

// Advance projectiles: move, home (shulker bullets), trail sparks, and resolve block/mob/player hits.
// Player-thrown projectiles damage mobs; ender pearls teleport the player; ghast fireballs explode.
fn tick_projectiles(state: &mut EngineState, dt: f32) {
    let eye = state.player.pos + vec3(0.0, 1.0, 0.0);
    let mut projs = std::mem::take(&mut state.projectiles);
    let mut survivors: Vec<Projectile> = Vec::with_capacity(projs.len());
    let mut explosions: Vec<Vec3> = Vec::new();
    let mut teleport: Option<Vec3> = None;
    let mut bursts: Vec<(Vec3, [f32; 3], bool)> = Vec::new(); // (pos, color, big)
    let mut incoming = 0.0f32;
    for mut p in projs.drain(..) {
        p.life -= dt;
        // Motion: shulker bullets home; fireworks rise; snowballs/pearls fall; fireballs fly straight.
        match p.kind {
            ProjKind::ShulkerBullet => {
                let want = (eye - p.pos).normalize_or_zero() * p.vel.length();
                p.vel = (p.vel * 0.90 + want * 0.10).normalize_or_zero() * p.vel.length();
            }
            ProjKind::Firework => { p.vel.y += 6.0 * dt; }
            _ => { p.vel.y -= p.gravity() * dt; }
        }
        p.pos += p.vel * dt;
        // Block collision.
        let (bx, by, bz) = (p.pos.x.floor() as i32, p.pos.y.floor() as i32, p.pos.z.floor() as i32);
        let solid = { let id = state.chunks.get_block_world(bx, by, bz); id != 0 && Block::from_id(id).is_solid() };
        let mut hit = p.life <= 0.0 || solid;
        // Player-thrown projectiles hit mobs.
        if !hit && p.from_player {
            // Apollo doubles what a thrown weapon does on impact.
            let marksman = if state.player.blessed(Passive::Marksman) { 2.0 } else { 1.0 };
            for m in state.mobs.iter_mut() {
                let center = m.pos + vec3(0.0, m.kind.height() * 0.5, 0.0);
                if (center - p.pos).length() < m.kind.hit_radius() + 0.4 {
                    let dmg = match p.kind { ProjKind::Fireball => 6.0, ProjKind::Snowball => 1.0, _ => 0.0 };
                    if dmg > 0.0 { m.health -= dmg * marksman; }
                    hit = true; break;
                }
            }
        }
        // Enemy projectiles hit the player.
        if !hit && !p.from_player && (p.pos - eye).length() < 0.8 { incoming += p.damage; hit = true; }
        if hit {
            match p.kind {
                ProjKind::EnderPearl => teleport = Some(p.pos),
                ProjKind::Fireball if p.explosive => explosions.push(p.pos),
                _ => bursts.push((p.pos, p.color(), matches!(p.kind, ProjKind::Fireball | ProjKind::Firework))),
            }
            continue;
        }
        // Trail spark.
        if state.particles.len() < 400 { state.particles.push(Particle { pos: p.pos, vel: Vec3::ZERO, life: 0.3, max_life: 0.3, size: p.size() * 0.7, color: p.color(), gravity: crate::entity::BURST_GRAVITY }); }
        survivors.push(p);
    }
    state.projectiles = survivors;
    if incoming > 0.0 { hurt_player(state, incoming); }
    for c in explosions { explode(state, c, 2.5); }
    if let Some(tp) = teleport {
        state.player.pos = tp + vec3(0.0, 0.5, 0.0);
        state.player.vel = Vec3::ZERO;
        state.player.air_max_y = state.player.pos.y; // no fall damage from the teleport
        state.player.damage(2.0); // ender pearls jar you a little
        spawn_particles(&mut state.spawn_rng, &mut state.particles, tp, 16, [0.25, 0.85, 0.7], 4.0, 0.6, 0.14);
    }
    for (pos, color, big) in bursts {
        let n = if big { 24 } else { 10 };
        spawn_particles(&mut state.spawn_rng, &mut state.particles, pos, n, color, if big { 5.0 } else { 3.0 }, 0.6, if big { 0.22 } else { 0.13 });
    }
}

// Run the line: hold the float in place, count down to a bite, and give up if the player walks off.
fn tick_fishing(state: &mut EngineState, dt: f32, player_pos: Vec3) {
    let Some(b) = state.fishing.bobber else { return; };
    let bob = vec3(b[0], b[1], b[2]);
    // The rod has to stay in hand and the player within reach of the water.
    if state.inventory.selected_block() != crate::fishing::ROD
        || (bob - player_pos).length() > crate::fishing::LEASH
        || state.chunks.get_block_world(b[0].floor() as i32, b[1].floor() as i32, b[2].floor() as i32) != 12
    {
        state.fishing.reel_in();
        return;
    }
    if state.fishing.tick(dt) {
        // A bite: splash, so the player knows to strike without needing to watch a HUD element.
        spawn_particles(&mut state.spawn_rng, &mut state.particles, bob, 10, [0.60, 0.80, 0.95], 3.0, 0.5, 0.09);
    }
    // The float itself, redrawn each frame as a short-lived particle.
    if state.particles.len() < 480 {
        let color = if state.fishing.biting() { [1.0, 0.85, 0.35] } else { [0.90, 0.25, 0.20] };
        state.particles.push(Particle {
            pos: bob + vec3(0.0, 0.1, 0.0), vel: Vec3::ZERO,
            life: 0.12, max_life: 0.12, size: 0.11, color, gravity: 0.0,
        });
    }
}

// Feed the atmosphere system: how far the player walked, what they're standing on, and how dark and
// deep it is where they are.
fn tick_ambience(state: &mut EngineState, dt: f32, player_pos: Vec3) {
    let walked = (state.player.walk_dist - state.prev_walk).max(0.0);
    state.prev_walk = state.player.walk_dist;

    let (px, pz) = (player_pos.x.floor() as i32, player_pos.z.floor() as i32);
    let under = state.chunks.get_block_world(px, (player_pos.y - 0.1).floor() as i32, pz);

    // "Deep" means genuinely enclosed: a solid ceiling somewhere overhead, well below the surface.
    let head = player_pos.y.floor() as i32 + 2;
    let covered = (head..head + 40).any(|y| {
        let id = state.chunks.get_block_world(px, y, pz);
        id != 0 && Block::from_id(id).blocks_light()
    });
    let deep = covered && player_pos.y < 50.0;
    // The Nether and End are always oppressive; the overworld only after dark.
    let dark = state.dim != 0 || state.is_night();

    let mut r = state.spawn_rng;
    r ^= r << 13; r ^= r >> 17; r ^= r << 5;
    state.spawn_rng = r;
    let roll = (r >> 8) as f32 / 16_777_216.0;

    state.ambience.tick(dt, walked, state.player.on_ground, under, dark, deep, roll);
}

// Advance the weather random walk and drop precipitation around the player. Only the overworld has a
// sky to rain from; the Nether and End are left alone.
fn tick_weather(state: &mut EngineState, dt: f32, player_pos: Vec3) {
    if state.dim != 0 {
        state.rain = 0.0;
        return;
    }
    let mut rng = state.spawn_rng;
    let mut rand = |s: &mut u32| { let mut x = *s; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *s = x; (x >> 8) as f32 / 16_777_216.0 };

    state.weather_cd -= dt;
    if state.weather_cd <= 0.0 {
        state.weather = next_weather(state.weather, rand(&mut rng));
        state.weather_cd = weather_duration(rand(&mut rng));
    }
    // Ease toward the target so the sky fades between states instead of snapping.
    let target = rain_target(state.weather);
    let step = RAIN_FADE_PER_SEC * dt;
    state.rain += (target - state.rain).clamp(-step, step);

    if state.rain > 0.02 {
        spawn_precipitation(state, &mut rng, &mut rand, player_pos);
    }
    state.spawn_rng = rng;
}

/// Precipitation sits in its own slice of the particle budget so a downpour can never crowd out the
/// combat and mining bursts that the player actually needs to see.
const RAIN_BUDGET: usize = 260;
/// How far above the player drops appear, and the radius they fall inside.
const RAIN_HEIGHT: f32 = 13.0;
const RAIN_RADIUS: f32 = 13.0;

fn spawn_precipitation(
    state: &mut EngineState,
    rng: &mut u32,
    rand: &mut impl FnMut(&mut u32) -> f32,
    player_pos: Vec3,
) {
    // Nothing falls on a player who is under cover: if there's solid material overhead, skip it. This
    // stands in for a per-column sky test and costs one short scan instead of one per drop.
    let (px, pz) = (player_pos.x.floor() as i32, player_pos.z.floor() as i32);
    let head = player_pos.y.floor() as i32 + 2;
    for y in head..=(head + RAIN_HEIGHT as i32) {
        let id = state.chunks.get_block_world(px, y, pz);
        if id != 0 && Block::from_id(id).blocks_light() { return; }
    }
    // Snow rather than rain wherever the ground is frozen over.
    let ground = state.chunks.get_block_world(px, player_pos.y.floor() as i32 - 1, pz);
    let snowy = matches!(ground, 11 | 42 | 43 | 44);

    let want = (state.rain * 22.0) as usize;
    for _ in 0..want {
        if state.particles.len() >= RAIN_BUDGET { break; }
        let ang = rand(rng) * std::f32::consts::TAU;
        let r = rand(rng).sqrt() * RAIN_RADIUS;
        let pos = vec3(
            player_pos.x + ang.cos() * r,
            player_pos.y + RAIN_HEIGHT * (0.6 + 0.4 * rand(rng)),
            player_pos.z + ang.sin() * r,
        );
        let p = if snowy {
            // Flakes drift: almost no gravity, a little sideways wander, and a long life.
            Particle {
                pos, vel: vec3((rand(rng) - 0.5) * 0.8, -1.6, (rand(rng) - 0.5) * 0.8),
                life: 6.0, max_life: 6.0, size: 0.075, color: [0.95, 0.97, 1.0], gravity: 0.35,
            }
        } else {
            Particle {
                pos, vel: vec3(0.0, -14.0, 0.0),
                life: 1.3, max_life: 1.3, size: 0.045, color: [0.62, 0.72, 0.85], gravity: 6.0,
            }
        };
        state.particles.push(p);
    }
}
