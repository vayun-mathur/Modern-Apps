// Spawn a small burst of particles (capped so the buffer never overflows).
fn spawn_particles(rng: &mut u32, out: &mut Vec<Particle>, center: Vec3, n: usize, color: [f32; 3], speed: f32, life: f32, size: f32) {
    let mut r = |s: &mut u32| { let mut x = *s; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *s = x; (x >> 8) as f32 / 16_777_216.0 * 2.0 - 1.0 };
    for _ in 0..n {
        if out.len() > 500 { break; }
        let v = vec3(r(rng) * speed, r(rng).abs() * speed + 1.0, r(rng) * speed);
        out.push(Particle { pos: center, vel: v, life, max_life: life, size, color, gravity: crate::entity::BURST_GRAVITY });
    }
}

fn mark_neighbors_dirty(state: &mut EngineState, x: i32, z: i32) {
    use crate::world::chunk::ChunkPos;
    let cp = ChunkPos::from_world(x, z);
    // Every runtime block edit funnels through here, so it's the one place to record what changed for
    // the network to ship (no-op offline / when applying a remote edit).
    crate::net::note_edited_chunk(cp.0, cp.1);
    if let Some(ch) = state.chunks.get_mut(cp) { ch.mesh_dirty = true; }
    let lx = x.rem_euclid(16); let lz = z.rem_euclid(16);
    if lx==0 { if let Some(ch) = state.chunks.get_mut(ChunkPos(cp.0-1, cp.1)) { ch.mesh_dirty=true; } }
    if lx==15 { if let Some(ch) = state.chunks.get_mut(ChunkPos(cp.0+1, cp.1)) { ch.mesh_dirty=true; } }
    if lz==0 { if let Some(ch) = state.chunks.get_mut(ChunkPos(cp.0, cp.1-1)) { ch.mesh_dirty=true; } }
    if lz==15 { if let Some(ch) = state.chunks.get_mut(ChunkPos(cp.0, cp.1+1)) { ch.mesh_dirty=true; } }
}

// Ray vs mob AABBs: index of the nearest mob hit within `reach` and nearer than `limit`.
fn nearest_mob_hit(state: &EngineState, origin: Vec3, dir: Vec3, reach: f32, limit: f32) -> Option<usize> {
    let mut best: Option<(usize, f32)> = None;
    for (i, m) in state.mobs.iter().enumerate() {
        let h = m.kind.height();
        let hr = m.kind.hit_radius();
        let min = vec3(m.pos.x - hr, m.pos.y, m.pos.z - hr);
        let max = vec3(m.pos.x + hr, m.pos.y + h, m.pos.z + hr);
        let mut tmin = 0.0f32; let mut tmax = reach.min(limit);
        let mut hit = true;
        for a in 0..3 {
            let (o, d, lo, hi) = (origin[a], dir[a], min[a], max[a]);
            if d.abs() < 1e-6 { if o < lo || o > hi { hit = false; break; } }
            else {
                let (mut t1, mut t2) = ((lo - o) / d, (hi - o) / d);
                if t1 > t2 { std::mem::swap(&mut t1, &mut t2); }
                tmin = tmin.max(t1); tmax = tmax.min(t2);
                if tmin > tmax { hit = false; break; }
            }
        }
        if hit && tmin >= 0.0 && best.map(|(_, t)| tmin < t).unwrap_or(true) { best = Some((i, tmin)); }
    }
    best.map(|(i, _)| i)
}

fn do_break(state: &mut EngineState, origin: Vec3, dir: Vec3) -> bool {
    use crate::item::{self, Effect};
    let sel = state.inventory.selected_block();
    // Equip armor (hold): swap it into its slot, returning any displaced piece to the inventory.
    if item::is_armor(sel) && state.player.eat_cd <= 0.0 {
        if let Some((id, dur)) = state.inventory.take_selected() {
            if let Some(old) = state.inventory.equip_armor(id, dur) { state.inventory.add_item_with_count(old.id, old.count); }
            state.player.eat_cd = 0.4;
            return true;
        }
    }
    // Firework rocket: consume one to launch a burst; boosts the player forward while gliding.
    if item::is_firework(sel) && state.player.eat_cd <= 0.0 {
        if state.inventory.consume_selected().is_some() {
            state.player.firework_boost();
            let f = state.player.forward();
            let origin = state.player.eye_pos() + f * 0.5;
            state.projectiles.push(Projectile { pos: origin, vel: f * 5.0 + vec3(0.0, 7.0, 0.0), life: 1.1, kind: ProjKind::Firework, from_player: true, damage: 0.0, explosive: false });
            state.player.eat_cd = 0.4;
            return true;
        }
    }
    // Brushing: sweep suspicious sand for whatever was buried in it. The sand stays behind either
    // way, so a dig site can't be turned into a hole by accident.
    if sel == crate::item::BRUSH {
        if let Some(hit) = crate::raycast::raycast(&state.chunks, origin, dir, player_reach(state)) {
            let (x, y, z) = hit.pos;
            if state.chunks.get_block_world(x, y, z) == Block::SuspiciousSand as Id {
                let mut r = state.spawn_rng;
                r ^= r << 13; r ^= r >> 17; r ^= r << 5;
                state.spawn_rng = r;
                let id = buried_find((r >> 8) as f32 / 16_777_216.0);
                if !state.inventory.has_room_for(id, 1) { return false; }
                state.inventory.add_block(id);
                state.did_brush = true;
                state.chunks.set_block_world(x, y, z, Block::Sand as Id);
                mark_neighbors_dirty(state, x, z);
                damage_tool(state);
                let c = vec3(x as f32 + 0.5, y as f32 + 1.0, z as f32 + 0.5);
                spawn_particles(&mut state.spawn_rng, &mut state.particles, c, 12, [0.86, 0.78, 0.58], 2.5, 0.6, 0.1);
                return true;
            }
        }
        return false;
    }
    // Fishing: the first tap casts into water, the second strikes. Striking on a bite lands a catch;
    // striking early just reels the line back in.
    if sel == crate::fishing::ROD && state.player.eat_cd <= 0.0 {
        state.player.eat_cd = 0.4;
        if state.fishing.is_cast() {
            if state.fishing.biting() {
                let mut r = state.spawn_rng;
                r ^= r << 13; r ^= r >> 17; r ^= r << 5;
                state.spawn_rng = r;
                let roll = (r >> 8) as f32 / 16_777_216.0;
                let id = crate::fishing::catch_of_the_day(roll, state.player.blessed(Passive::SeaLuck));
                if state.inventory.has_room_for(id, 1) {
                    state.inventory.add_block(id);
                    state.did_fish = true;
                    damage_tool(state);
                }
            }
            state.fishing.reel_in();
            return true;
        }
        // Cast: find open water along the look direction and drop the float on its surface.
        if let Some(at) = water_surface_along(state, origin, dir, player_reach(state) + 6.0) {
            let mut r = state.spawn_rng;
            r ^= r << 13; r ^= r >> 17; r ^= r << 5;
            state.spawn_rng = r;
            state.fishing.cast(at, (r >> 8) as f32 / 16_777_216.0);
            return true;
        }
        return false;
    }
    // Throwables: snowball (light damage/knock) and ender pearl (teleport to impact). They fly along
    // `dir`, the ray through the touch (or the crosshair, for the Use button), so they land where the
    // player pointed. These checks used the pre-1024 ids 190/191, so throwables never fired at all.
    if (sel == item::SNOWBALL || sel == item::ENDER_PEARL) && state.player.eat_cd <= 0.0 {
        // Paris never spends the charge; everyone else does.
        let free = state.player.blessed(Passive::Infinity);
        if free || state.inventory.consume_selected().is_some() {
            let f = dir.normalize_or_zero();
            let origin = state.player.eye_pos() + f * 0.5;
            let (kind, spd) = if sel == item::ENDER_PEARL { (ProjKind::EnderPearl, 16.0) } else { (ProjKind::Snowball, 20.0) };
            // Artemis throws a spread of three; an ender pearl always flies alone so the
            // teleport destination stays predictable.
            let spread: &[f32] = if state.player.blessed(Passive::Multishot) && kind == ProjKind::Snowball {
                &[-0.12, 0.0, 0.12]
            } else {
                &[0.0]
            };
            let right = { let r = f.cross(Vec3::Y).normalize_or_zero(); if r == Vec3::ZERO { state.player.right() } else { r } };
            for &off in spread {
                let dir = (f + right * off).normalize_or_zero();
                state.projectiles.push(Projectile { pos: origin, vel: dir * spd, life: 5.0, kind, from_player: true, damage: 0.0, explosive: false });
            }
            state.player.eat_cd = 0.4;
            return true;
        }
    }
    // Deflect an incoming fireball by attacking it — bats it back (now player-owned) at the shooter.
    // Aimed along the touch ray like everything else here.
    {
        let eye_pos = state.player.eye_pos();
        let f = dir.normalize_or_zero();
        let mut best: Option<(usize, f32)> = None;
        for (i, p) in state.projectiles.iter().enumerate() {
            if p.from_player || p.kind != ProjKind::Fireball { continue; }
            let to = p.pos - eye_pos;
            let d = to.length();
            if d < 4.5 && to.normalize_or_zero().dot(f) > 0.5 && best.map_or(true, |b| d < b.1) { best = Some((i, d)); }
        }
        if let Some((i, _)) = best {
            let p = &mut state.projectiles[i];
            p.vel = -p.vel * 1.15;
            p.from_player = true;
            return true;
        }
    }
    // Consumables (hold to use): eat food any time, drink estus / use heart container when applicable.
    if state.player.eat_cd <= 0.0 {
        // Food can be eaten any time (even at full health) to gain its effects.
        if item::is_food(sel) {
            if let Some(id) = state.inventory.consume_selected() {
                if let Some(effs) = item::food_effects(id) { for &(k, s, a) in effs { state.player.add_effect(k, s, a); } }
                state.player.eat_cd = 1.2;
                return true;
            }
        } else if item::is_estus(sel) && state.player.health < state.player.max_health {
            if state.inventory.consume_selected().is_some() {
                state.player.heal(8.0);
                state.player.add_effect(Effect::Regeneration, 3.0, 1);
                state.player.add_effect(Effect::Resistance, 3.0, 0);
                state.player.eat_cd = 0.8;
                return true;
            }
        } else if item::is_heart_container(sel) && state.player.max_health < crate::player::CAP_MAX_HEALTH {
            if state.inventory.consume_selected().is_some() {
                state.player.max_health = (state.player.max_health + 2.0).min(crate::player::CAP_MAX_HEALTH);
                state.player.heal(2.0);
                state.player.eat_cd = 0.8;
                return true;
            }
        }
    }
    // Attack a mob if one is under the cursor within reach and nearer than any block.
    let reach = player_reach(state);
    let block_hit = crate::raycast::raycast(&state.chunks, origin, dir, reach);
    let block_dist = block_hit.as_ref().map(|h| (vec3(h.pos.0 as f32 + 0.5, h.pos.1 as f32 + 0.5, h.pos.2 as f32 + 0.5) - origin).length()).unwrap_or(f32::INFINITY);
    if state.player.attack_cd <= 0.0 {
        if let Some(idx) = nearest_mob_hit(state, origin, dir, reach - 1.5, block_dist) {
            state.player.attack_cd = 0.45;
            let base = 4.0 + item::sword_damage(sel) + item::pick_damage(sel) + state.player.strength_bonus();
            let dmg = base * state.player.might_mult() * slayer_mult(&state.player, state.mobs[idx].kind);
            damage_tool(state);
            let ppos = state.player.pos;
            // Talos turns every swing into a launch.
            let (kb_h, kb_v) = if state.player.blessed(Passive::Impact) { (2.4, 12.0) } else { (0.45, 6.0) };
            let mpos;
            {
                let m = &mut state.mobs[idx];
                m.health -= dmg;
                let kb = { let k = m.pos - ppos; vec3(k.x, 0.0, k.z).normalize_or_zero() };
                m.pos += kb * kb_h; m.vel.y = kb_v;
                mpos = m.pos + vec3(0.0, 0.5, 0.0);
            }
            state.player.lifesteal(dmg);
            state.player.wind_burst();
            spawn_particles(&mut state.spawn_rng, &mut state.particles, mpos, 6, [0.85, 0.12, 0.12], 3.0, 0.4, 0.09);
            return true;
        }
    }
    // Break a block.
    if let Some(hit) = block_hit {
        let (x, y, z) = hit.pos;
        let id = state.chunks.get_block_world(x, y, z);
        if id == 83 { // chest: breaking it spills its contents and returns the chest itself
            let key = ensure_chest(state, x, y, z);
            // Only break the chest once everything actually fit — otherwise the leftovers would be
            // dropped on the floor, which this game has no representation for.
            let mut left = match state.containers.remove(key) {
                Some(slots) => slots,
                None => Vec::new(),
            };
            for s in left.iter_mut() { state.inventory.take_from(s); }
            if left.iter().any(|s| s.id != 0) {
                state.containers.insert(key, left);
                return false;
            }
            state.inventory.add_block(83);
            if state.open_chest == Some(key) { state.open_chest = None; }
            state.chunks.set_block_world(x, y, z, 0);
            mark_neighbors_dirty(state, x, z);
            return true;
        }
        if id != 0 {
            // Stone/ore only drops when mined with a pickaxe; soft blocks always drop.
            let drops = !Block::from_id(id).needs_pickaxe() || item::is_pickaxe(sel);
            let meta_before = state.chunks.get_meta_world(x, y, z);
            state.chunks.set_block_world(x, y, z, 0);
            // Leaves occasionally give up an apple. Rolled against the world RNG rather than the
            // block position, so one lucky coordinate can't be replanted into an apple farm.
            if is_leaves(id) {
                let r = &mut state.spawn_rng;
                *r ^= *r << 13; *r ^= *r >> 17; *r ^= *r << 5;
                if *r % 20 == 0 { state.inventory.add_block(1026); }
            }
            let crop = Block::from_id(id);
            if crop.is_crop() {
                harvest_crop(state, crop, meta_before);
                state.inventory.broken += 1;
            } else if drops {
                // Eros doubles what an ore gives up.
                let n = if state.player.blessed(Passive::Fortune) && is_ore(id) { 2 } else { 1 };
                for _ in 0..n { state.inventory.add_block(id); }
                state.inventory.broken += 1;
            }
            damage_tool(state);
            mark_neighbors_dirty(state, x, z);
            let c = vec3(x as f32 + 0.5, y as f32 + 0.5, z as f32 + 0.5);
            spawn_particles(&mut state.spawn_rng, &mut state.particles, c, 7, [0.55, 0.45, 0.35], 2.6, 0.5, 0.11);
            return true;
        }
    }
    false
}

// Advance the active furnace job: burn fuel, accumulate progress and bank finished batches. Stops
// itself when it runs out of inputs or fuel so the player never silently loses items.
fn tick_smelter(state: &mut EngineState, dt: f32) {
    if !state.smelter.active { return; }
    let Some(recipe) = crate::inventory::SMELTING.get(state.smelter.recipe) else {
        state.smelter.active = false;
        return;
    };
    let stop = |s: &mut Smelter| { s.active = false; s.progress = 0.0; };
    if recipe.blast && !state.smelter.blast { stop(&mut state.smelter); return; }
    if !state.inventory.can_smelt(recipe) { stop(&mut state.smelter); return; }
    // Pause instead of burning fuel when the result would have nowhere to go.
    if !state.inventory.has_room_for(recipe.out, recipe.out_n) { return; }
    if state.smelter.fuel_left <= 0.0 {
        let spare = [recipe.in1, recipe.in2];
        match state.inventory.consume_fuel(&spare) {
            Some(secs) => { state.smelter.fuel_left = secs; state.smelter.fuel_max = secs; }
            None => { stop(&mut state.smelter); return; }
        }
    }
    state.smelter.fuel_left -= dt;
    state.smelter.progress += dt;
    if state.smelter.progress >= recipe.secs {
        state.smelter.progress -= recipe.secs;
        state.inventory.take_smelt_inputs(recipe);
        state.inventory.give_smelt_output(recipe);
        if !state.inventory.can_smelt(recipe) { stop(&mut state.smelter); }
    }
}

// Light a furnace recipe. `blast` reports which kind of furnace the player tapped. A job already in
// flight is left alone: switching would throw away its progress and the fuel already spent on it.
pub fn start_smelt(recipe: usize, blast: bool) -> bool {
    with_engine(|state| {
        let Some(r) = crate::inventory::SMELTING.get(recipe) else { return false; };
        if r.blast && !blast { return false; }
        if state.smelter.active && state.smelter.recipe != recipe { return false; }
        if !state.inventory.can_smelt(r) { return false; }
        state.smelter.blast = blast;
        if state.smelter.recipe != recipe { state.smelter.progress = 0.0; }
        state.smelter.recipe = recipe;
        state.smelter.active = true;
        true
    }).unwrap_or(false)
}
pub fn stop_smelt() { with_engine(|s| { s.smelter.active = false; s.smelter.progress = 0.0; }); }
pub fn get_smelting_json() -> String {
    let items: Vec<_> = crate::inventory::SMELTING.iter()
        .map(|s| serde_json::json!({
            "in": s.in1, "inN": s.n1, "in2": s.in2, "in2N": s.n2,
            "out": s.out, "outN": s.out_n, "secs": s.secs, "blast": s.blast,
        }))
        .collect();
    serde_json::json!(items).to_string()
}

// ---- Chest containers ----
pub fn get_container_json() -> String {
    with_engine_try(|state| match state.open_chest {
        Some(key) => state.containers.to_json(key),
        None => r#"{"slots":[]}"#.to_string(),
    }).unwrap_or_else(|| r#"{"slots":[]}"#.to_string())
}
// Move a chest stack into the player's inventory, leaving behind whatever didn't fit.
pub fn container_take(idx: usize) -> bool {
    with_engine(|state| {
        let Some(key) = state.open_chest else { return false; };
        // Clients don't mutate authoritative state: send the intent and wait for the host's sync.
        if crate::net::is_client() {
            crate::net::client_container_intent([key.0 as i32, key.1, key.2, key.3], true, idx as u32);
            return false;
        }
        let Some(slot) = state.containers.slot_mut(key, idx) else { return false; };
        if slot.id == 0 { return false; }
        let mut held = *slot;
        state.inventory.take_from(&mut held);
        let moved = held.count < slot.count || held.id == 0;
        if let Some(slot) = state.containers.slot_mut(key, idx) { *slot = held; }
        if moved { crate::net::host_container_changed(state, key); }
        moved
    }).unwrap_or(false)
}
// Move a whole player stack into the chest, putting back anything that didn't fit.
pub fn container_put(inv_idx: usize) -> bool {
    with_engine(|state| {
        let Some(key) = state.open_chest else { return false; };
        if crate::net::is_client() {
            crate::net::client_container_intent([key.0 as i32, key.1, key.2, key.3], false, inv_idx as u32);
            return false;
        }
        if inv_idx >= crate::inventory::SLOTS { return false; }
        let s = state.inventory.slots[inv_idx];
        if s.id == 0 { return false; }
        let left = state.containers.add(key, s.id, s.count);
        if left >= s.count { return false; } // chest full, nothing moved
        state.inventory.slots[inv_idx] = if left > 0 {
            crate::inventory::InvSlot { id: s.id, count: left }
        } else {
            crate::inventory::InvSlot::default()
        };
        crate::net::host_container_changed(state, key);
        true
    }).unwrap_or(false)
}
pub fn close_container() { with_engine(|s| s.open_chest = None); }

/// Client: ask the host to send the currently-open chest's contents (so a freshly-opened chest
/// shows what's really inside rather than an empty grid). No-op offline or on the host.
pub fn request_container_sync() {
    with_engine(|state| {
        if let Some(key) = state.open_chest {
            if crate::net::is_client() {
                crate::net::client_container_intent([key.0 as i32, key.1, key.2, key.3], true, u32::MAX);
            }
        }
    });
}

// ---- Blessings ----
// Bind the blessing held in an inventory slot, consuming the charm. Fails if the slot doesn't hold
// a blessing, it's already bound, or every attunement slot is taken.
pub fn attune_blessing(inv_idx: usize) -> bool {
    with_engine(|state| {
        if inv_idx >= crate::inventory::SLOTS { return false; }
        let id = state.inventory.slots[inv_idx].id;
        if !crate::blessing::is_blessing(id) { return false; }
        if !state.player.blessings.attune(id) { return false; }
        let slot = &mut state.inventory.slots[inv_idx];
        slot.count -= 1;
        if slot.count <= 0 { *slot = crate::inventory::InvSlot::default(); }
        true
    }).unwrap_or(false)
}
// Unbind an attunement, handing the charm back. Refuses if there's nowhere to put it.
pub fn release_blessing(slot: usize) -> bool {
    with_engine(|state| {
        let Some(&id) = state.player.blessings.slots.get(slot) else { return false; };
        if id == 0 { return false; }
        if !state.inventory.has_room_for(id, 1) { return false; }
        state.player.blessings.release(slot);
        state.inventory.add_block(id);
        true
    }).unwrap_or(false)
}
pub fn get_blessings_json() -> String {
    with_engine_try(|state| state.player.blessings.to_json())
        .unwrap_or_else(|| crate::blessing::Attunement::default().to_json())
}
pub fn get_blessing_catalog_json() -> String { crate::blessing::catalog_json() }

// Ore blocks, for the Fortune blessing.
fn is_ore(id: Id) -> bool { matches!(id, 18..=22 | 90 | 91 | 92) }
// Leaf blocks, which sometimes drop an apple.
fn is_leaves(id: Id) -> bool { matches!(id, 5 | 28 | 31 | 48 | 80) }
