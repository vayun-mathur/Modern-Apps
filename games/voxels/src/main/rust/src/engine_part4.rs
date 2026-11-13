// Blessings that act over time rather than at a single event: Lu Ban repairs gear a point at a
// time, Demeter freezes the water the player walks over.
fn tick_blessings(state: &mut EngineState, dt: f32) {
    if state.player.blessed(crate::blessing::Passive::Mending) {
        state.mend_cd -= dt;
        if state.mend_cd <= 0.0 {
            state.mend_cd = 1.0;
            state.inventory.mend_one();
        }
    }
    if state.player.blessed(Passive::FrostWalker) {
        let p = state.player.pos;
        let y = (p.y - 0.4).floor() as i32;
        let mut froze = Vec::new();
        for dx in -1..=1 { for dz in -1..=1 {
            let (x, z) = (p.x.floor() as i32 + dx, p.z.floor() as i32 + dz);
            if state.chunks.get_block_world(x, y, z) == 12 {
                state.chunks.set_block_world(x, y, z, 43); // ice
                froze.push((x, z));
            }
        }}
        for (x, z) in froze { mark_neighbors_dirty(state, x, z); }
    }
}

// How far the player can reach; Will stretches it well past arm's length.
fn player_reach(state: &EngineState) -> f32 {
    if state.player.blessed(Passive::Reach) { 9.0 } else { 6.0 }
}
// Damage multiplier against a particular kind of foe, from the slayer blessings.
fn slayer_mult(player: &Player, kind: MobKind) -> f32 {
    let horror = matches!(kind, MobKind::Creeper | MobKind::Shulker | MobKind::Ghast);
    if (is_undead(kind) && player.blessed(Passive::SmiteUndead)) || (horror && player.blessed(Passive::BaneOfHorrors)) { 2.0 } else { 1.0 }
}

/// Seconds between growth passes, and how many random spots each pass checks. Sampling beats
/// scanning: a full sweep of the loaded world every tick would dwarf everything else the engine does.
const FARM_INTERVAL: f32 = 2.0;
const FARM_SAMPLES: usize = 40;
const FARM_RANGE: i32 = 24;

fn tick_farmland(state: &mut EngineState, dt: f32, player_pos: Vec3) {
    if state.dim != 0 { return; }
    state.farm_cd -= dt;
    if state.farm_cd > 0.0 { return; }
    state.farm_cd = FARM_INTERVAL;

    let (px, py, pz) = (player_pos.x.floor() as i32, player_pos.y.floor() as i32, player_pos.z.floor() as i32);
    let mut r = state.spawn_rng;
    let mut next = |s: &mut u32| { *s ^= *s << 13; *s ^= *s >> 17; *s ^= *s << 5; *s };
    let mut grown: Vec<(i32, i32)> = Vec::new();
    for _ in 0..FARM_SAMPLES {
        let a = next(&mut r);
        let x = px + (a % (FARM_RANGE as u32 * 2 + 1)) as i32 - FARM_RANGE;
        let b = next(&mut r);
        let z = pz + (b % (FARM_RANGE as u32 * 2 + 1)) as i32 - FARM_RANGE;
        let c = next(&mut r);
        let y = py + (c % 9) as i32 - 4;

        let id = state.chunks.get_block_world(x, y, z);
        if !Block::from_id(id).is_crop() { continue; }
        let meta = state.chunks.get_meta_world(x, y, z);
        let stage = crate::world::block::crop_stage(meta);
        if stage >= crate::world::block::CROP_RIPE { continue; }
        // Crops only grow on tended ground; break the farmland and the field stalls.
        if state.chunks.get_block_world(x, y - 1, z) != Block::Farmland as Id { continue; }
        state.chunks.set_block_meta_world(x, y, z, id, crate::world::block::crop_meta(stage + 1));
        grown.push((x, z));
    }
    state.spawn_rng = r;
    for (x, z) in grown { mark_neighbors_dirty(state, x, z); }
}

/// A ripe crop pays out; an unripe one only returns the seed that was put in.
fn harvest_crop(state: &mut EngineState, crop: Block, meta: u8) {
    let seed = crop.crop_seed();
    if crate::world::block::crop_stage(meta) < crate::world::block::CROP_RIPE {
        state.inventory.add_block(seed);
        return;
    }
    let produce = crop.crop_yield();
    // Eros is a harvest blessing as much as a mining one.
    let n = if state.player.blessed(Passive::Fortune) { 5 } else { 3 };
    for _ in 0..n { state.inventory.add_block(produce); }
    state.did_harvest = true;
    // And the seed back, so a field is self-sustaining once it's planted.
    if seed != produce { state.inventory.add_block(seed); }
}

/// What a dig site gives up. Matcha's archaeology yields pottery sherds, which this game doesn't
/// model, so the pool is the small treasures a buried cache would plausibly hold.
pub fn buried_find(roll: f32) -> Id {
    match (roll.clamp(0.0, 0.999) * 100.0) as u32 {
        0..=29 => 1033,                                 // leather scraps
        30..=54 => 1132,                                // copper ingot
        55..=74 => Block::Amethyst as Id,
        75..=89 => 1133,                                // gold ingot
        90..=96 => 1052,                                // emerald
        _ => 1051,                                      // diamond
    }
}

/// March along the aim direction looking for the top of a body of water to drop a float onto.
fn water_surface_along(state: &EngineState, origin: Vec3, dir: Vec3, max_dist: f32) -> Option<[f32; 3]> {
    let mut t = 1.0f32;
    while t < max_dist {
        let p = origin + dir * t;
        let (x, y, z) = (p.x.floor() as i32, p.y.floor() as i32, p.z.floor() as i32);
        let id = state.chunks.get_block_world(x, y, z);
        if id == 12 {
            // Float on the surface of this column, not wherever the ray happened to enter it.
            let mut top = y;
            while state.chunks.get_block_world(x, top + 1, z) == 12 { top += 1; }
            return Some([x as f32 + 0.5, top as f32 + 0.9, z as f32 + 0.5]);
        }
        if id != 0 && Block::from_id(id).is_solid() { return None; } // the shore is in the way
        t += 0.4;
    }
    None
}

/// Wool from one shearing. A sheep only carries one coat, so this is the whole yield.
const WOOL_PER_SHEARING: i32 = 3;
/// Anubis keeps the dead this far back.
const WARD_UNDEAD_RADIUS: f32 = 6.0;
pub fn is_undead(kind: MobKind) -> bool {
    matches!(kind, MobKind::Zombie | MobKind::WitherSkeleton | MobKind::Wither)
}
// Wear the held tool, unless Daedalus is holding it together.
fn damage_tool(state: &mut EngineState) {
    if !state.player.blessed(Passive::ToolWard) { state.inventory.damage_selected(); }
}

// Destroy blocks in a sphere and hurt the player — creeper explosions.
fn explode(state: &mut EngineState, center: Vec3, radius: f32) {
    let r = radius.ceil() as i32;
    let (cx, cy, cz) = (center.x.floor() as i32, center.y.floor() as i32, center.z.floor() as i32);
    let mut cols: Vec<(i32, i32)> = Vec::new();
    for dx in -r..=r { for dy in -r..=r { for dz in -r..=r {
        if ((dx*dx + dy*dy + dz*dz) as f32).sqrt() > radius { continue; }
        let (x, y, z) = (cx + dx, cy + dy, cz + dz);
        let id = state.chunks.get_block_world(x, y, z);
        if id != 0 && id != 13 && Block::from_id(id).is_solid() {
            // A blown-up chest hands what it can to the player; the rest goes with the blast. Leaving
            // the entry behind would let a chest placed here later inherit the old loot.
            if id == 83 {
                let key = (state.dim, x, y, z);
                if let Some(mut slots) = state.containers.remove(key) {
                    for s in slots.iter_mut() { state.inventory.take_from(s); }
                }
                if state.open_chest == Some(key) { state.open_chest = None; }
            }
            state.chunks.set_block_world(x, y, z, 0);
            cols.push((x, z));
        }
    }}}
    for (x, z) in cols { mark_neighbors_dirty(state, x, z); }
    let pd = (state.player.pos - center).length();
    if pd < radius * 2.0 { hurt_player(state, (1.0 - pd / (radius * 2.0)).max(0.0) * 22.0); }
    spawn_particles(&mut state.spawn_rng, &mut state.particles, center, 30, [0.28, 0.25, 0.22], 6.0, 0.9, 0.3);
    spawn_particles(&mut state.spawn_rng, &mut state.particles, center, 12, [1.0, 0.55, 0.15], 5.0, 0.5, 0.22);
}

/// Which way a stair placed now should face. Its low side looks back at the player so that walking
/// forward climbs it. Yaw 0 looks north (-Z).
fn stair_facing(yaw: f32) -> u8 {
    use crate::world::block::{FACE_EAST, FACE_NORTH, FACE_SOUTH, FACE_WEST};
    let turns = (yaw / std::f32::consts::FRAC_PI_2).round() as i32;
    match turns.rem_euclid(4) {
        0 => FACE_SOUTH, // looking north, so approach from the south
        1 => FACE_EAST,
        2 => FACE_NORTH,
        _ => FACE_WEST,
    }
}

/// The meta byte for placing `block` against `hit`. Cubes get 0; slabs and stairs pick their half
/// from the face that was clicked, falling back to which half of a side face was hit.
fn placement_meta(block: Block, hit: &crate::raycast::HitResult, origin: Vec3, dir: Vec3, yaw: f32) -> u8 {
    use crate::world::block::META_TOP;
    if block.shape() == Shape::Cube { return 0; }
    let top = match hit.normal.1 {
        1 => false,  // placed on a surface: rests on the floor of its cell
        -1 => true,  // placed under a ceiling: hangs from the top
        _ => {
            // A side face: the half of the face that was clicked decides.
            let point = origin + dir.normalize_or_zero() * hit.dist;
            point.y - point.y.floor() > 0.5
        }
    };
    let mut meta = if top { META_TOP } else { 0 };
    if block.shape() == Shape::Stairs { meta |= stair_facing(yaw); }
    meta
}

fn do_place(state: &mut EngineState, origin: Vec3, dir: Vec3) -> bool {
    let sel = state.inventory.selected_block();
    // Seeds are items, so planting has to be handled before the is_item bail-out below.
    if let Some(crop) = Block::crop_from_seed(sel) {
        if let Some(hit) = crate::raycast::raycast(&state.chunks, origin, dir, player_reach(state)) {
            let (x, y, z) = hit.pos;
            let (tx, ty, tz) = (x + hit.normal.0, y + hit.normal.1, z + hit.normal.2);
            let on_farmland = state.chunks.get_block_world(x, y, z) == Block::Farmland as Id && hit.normal.1 == 1;
            // Plant only once the write is known to have landed, so an unloaded chunk can't eat the seed.
            if on_farmland && state.chunks.get_block_world(tx, ty, tz) == 0
                && state.chunks.set_block_meta_world(tx, ty, tz, crop as Id, crate::world::block::crop_meta(0))
            {
                if state.inventory.consume_selected().is_none() {
                    state.chunks.set_block_world(tx, ty, tz, 0);
                    return false;
                }
                mark_neighbors_dirty(state, tx, tz);
                return true;
            }
        }
        return false;
    }
    // Items (food, estus, materials) are never placeable as blocks.
    if sel == 0 || crate::item::is_item(sel) { return false; }
    let block = Block::from_id(sel);
    let Some(hit) = crate::raycast::raycast(&state.chunks, origin, dir, player_reach(state)) else { return false; };

    // Two matching slabs in one cell make the full block again.
    if block.shape() == Shape::Slab {
        let (tx, ty, tz) = hit.pos;
        if state.chunks.get_block_world(tx, ty, tz) == sel {
            let existing_top = state.chunks.get_meta_world(tx, ty, tz) & crate::world::block::META_TOP != 0;
            // Only the face on the cell's empty side can complete it.
            if (existing_top && hit.normal.1 == -1) || (!existing_top && hit.normal.1 == 1) {
                if state.inventory.consume_selected().is_some() {
                    state.chunks.set_block_world(tx, ty, tz, block.parent() as Id);
                    mark_neighbors_dirty(state, tx, tz);
                    return true;
                }
            }
        }
    }

    let (px, py, pz) = hit.prev;
    if state.chunks.get_block_world(px, py, pz) != 0 { return false; }
    let meta = placement_meta(block, &hit, origin, dir, state.player.yaw);
    // Refuse only if the block's actual geometry would intersect the player — a slab at their feet
    // is fine even though a full cube there would not be.
    let pmin = [state.player.pos.x - 0.3, state.player.pos.y, state.player.pos.z - 0.3];
    let pmax = [state.player.pos.x + 0.3, state.player.pos.y + 1.8, state.player.pos.z + 0.3];
    let cell = [px as f32, py as f32, pz as f32];
    if block.collision_boxes(meta).as_slice().iter().any(|b| b.overlaps_at(cell, pmin, pmax)) { return false; }

    if let Some(id) = state.inventory.consume_selected() {
        state.chunks.set_block_meta_world(px, py, pz, id, meta);
        // A chest the player places starts empty; only chests already in the world roll loot.
        if id == 83 { state.containers.insert_empty((state.dim, px, py, pz)); }
        mark_neighbors_dirty(state, px, pz);
        return true;
    }
    false
}

pub fn break_block_at(px: f32, py: f32) -> bool { with_engine(|state| break_at(state, px, py)).unwrap_or(false) }

/// Mine / attack / use along the ray through screen pixel (`px`, `py`).
fn break_at(state: &mut EngineState, px: f32, py: f32) -> bool {
    let (o, d) = screen_ray(state, px, py);
    do_break(state, o, d)
}

// Returns: 0 = nothing, 1 = placed a block, 10+menu = tapped an interactive block (open its menu).
pub fn place_block_at(px: f32, py: f32) -> i32 { with_engine(|state| place_at(state, px, py)).unwrap_or(0) }

/// Place / interact along the ray through screen pixel (`px`, `py`); see `place_block_at`.
fn place_at(state: &mut EngineState, px: f32, py: f32) -> i32 {
    {
        let (o, d) = screen_ray(state, px, py);
        // Tapping a villager opens trade; tapping an interactive block opens its menu — unless
        // sneaking, which places instead.
        if !state.player.sneaking {
            let reach = player_reach(state);
            let block_hit = crate::raycast::raycast(&state.chunks, o, d, reach);
            let bdist = block_hit.as_ref().map(|h| (vec3(h.pos.0 as f32 + 0.5, h.pos.1 as f32 + 0.5, h.pos.2 as f32 + 0.5) - o).length()).unwrap_or(f32::INFINITY);
            if let Some(idx) = nearest_mob_hit(state, o, d, reach - 1.5, bdist) {
                if state.mobs[idx].kind == MobKind::Villager {
                    state.trade_prof = state.mobs[idx].profession;
                    return 20;
                }
                // Shearing a sheep: wool now, and the sheep walks away unharmed to regrow it.
                let held = state.inventory.selected_block();
                if crate::item::is_shears(held) && state.mobs[idx].kind == MobKind::Sheep && !state.mobs[idx].sheared {
                    if !state.inventory.has_room_for(Block::Wool as Id, WOOL_PER_SHEARING) { return 0; }
                    state.mobs[idx].sheared = true;
                    state.did_shear = true;
                    for _ in 0..WOOL_PER_SHEARING { state.inventory.add_block(Block::Wool as Id); }
                    damage_tool(state);
                    return 1;
                }
            }
            if let Some(hit) = block_hit {
                let (x, y, z) = hit.pos;
                let id = state.chunks.get_block_world(x, y, z);
                // Resting at a Warding Stone burns off the night. Matcha sets `can_sleep: always` and
                // fast-forwards time; there are no beds here, so the bonfire does the job.
                if id == 81 {
                    if !state.is_night() { return 0; }
                    let skip = secs_until_dawn(state.day_t());
                    state.world_secs = wrap_world_secs(state.world_secs + skip);
                    state.did_rest = true;
                    state.player.heal(state.player.max_health);
                    // You wake to whatever weather the night left behind, but never mid-downpour.
                    state.weather = WEATHER_CLEAR;
                    state.weather_cd = weather_duration(0.4);
                    let c = vec3(x as f32 + 0.5, y as f32 + 1.2, z as f32 + 0.5);
                    spawn_particles(&mut state.spawn_rng, &mut state.particles, c, 20, [1.0, 0.82, 0.42], 2.0, 1.0, 0.12);
                    return 1;
                }
                if id == 83 { // chest: open its container
                    let key = ensure_chest(state, x, y, z);
                    state.open_chest = Some(key);
                    return 30;
                }
                // Tap an obsidian frame with flint & steel to ignite a nether portal.
                if id == 78 && state.dim != 2 && crate::item::is_flint_steel(state.inventory.selected_block()) {
                    if light_portal(state, x, y, z) { state.inventory.damage_selected(); return 41; }
                }
                let m = crate::world::block::Block::from_id(id).menu();
                if m != 0 { return 10 + m; }
            }
        }
        if do_place(state, o, d) { 1 } else { 0 }
    }
}
