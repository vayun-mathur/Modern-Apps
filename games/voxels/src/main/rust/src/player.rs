use glam::{Vec3, vec3};
use crate::world::{ChunkMap, block::Block};
use crate::item::{Effect, ActiveEffect};
use crate::blessing::{Attunement, Passive};

pub const MIN_MAX_HEALTH: f32 = 20.0; // 10 hearts floor (deaths never take you below this)
pub const CAP_MAX_HEALTH: f32 = 60.0; // 30 hearts cap (heart containers)

pub struct Player {
    pub pos: Vec3,
    pub vel: Vec3,
    pub yaw: f32,
    pub pitch: f32,
    pub on_ground: bool,
    pub flying: bool,
    pub elytra: bool,      // an elytra is equipped (gliding is possible)
    pub gliding: bool,     // currently in an elytra glide (published for UI/animation)
    glide_armed: bool,     // wings deployed: toggled with a mid-air jump tap
    prev_jump: bool,       // for edge-detecting jump taps
    glide_boost: f32,      // extra glide speed from a firework rocket (decays)
    jumps_left: u8,        // mid-air jumps remaining (Hyacinthus grants a second one)
    pub walk_dist: f32,
    pub sneaking: bool,
    // Attuned blessings, granting permanent passives.
    pub blessings: Attunement,
    // Survival state.
    pub health: f32,
    pub max_health: f32,
    pub absorption: f32,
    pub effects: Vec<ActiveEffect>,
    pub hurt_cd: f32,
    pub attack_cd: f32,
    pub eat_cd: f32,
    pub air_max_y: f32,
    pub dead: bool,
    /// Seconds since the last hit taken. Athena's shield only reforms once this runs out.
    pub combat_cd: f32,
}

/// Below this fraction of max health, Sekhmet's bloodrage kicks in.
pub const BLOODRAGE_AT: f32 = 0.3;
const BLOODRAGE_DAMAGE: f32 = 1.5;
const BLOODRAGE_RESIST: f32 = 0.7;
/// Athena's shield: how much it holds, how long after a hit it starts reforming, and how fast.
const DIVINITY_SHIELD: f32 = 8.0;
const DIVINITY_DELAY: f32 = 6.0;
const DIVINITY_REGEN: f32 = 2.0;
/// Camazotz returns this share of melee damage dealt as health.
pub const LIFESTEAL_SHARE: f32 = 0.15;

/// Damage taken on landing after falling from `peak` to `y`. Drops under the safe margin are free,
/// and Icarus removes it entirely. Swimming keeps `peak` pinned to the current height, which is how
/// a controlled descent avoids being treated as a fall.
fn landing_damage(peak: f32, y: f32, feather_fall: bool) -> f32 {
    if feather_fall { return 0.0; }
    let fall = peak - y;
    if fall > 3.5 { fall - 3.5 } else { 0.0 }
}

impl Player {
    pub fn new(x: f32, y: f32, z: f32) -> Self {
        Self { pos: vec3(x,y,z), vel: Vec3::ZERO, yaw: 0.0, pitch: 0.0, on_ground: false, flying: false, elytra: false, gliding: false, glide_armed: false, prev_jump: false, glide_boost: 0.0, jumps_left: 0, walk_dist: 0.0, sneaking: false, blessings: Attunement::default(),
            health: 20.0, max_health: 20.0, absorption: 0.0, effects: Vec::new(), hurt_cd: 0.0, attack_cd: 0.0, eat_cd: 0.0, air_max_y: y, dead: false, combat_cd: 0.0 }
    }

    pub fn blessed(&self, p: Passive) -> bool { self.blessings.has(p) }

    fn effect_amp(&self, k: Effect) -> Option<u8> { self.effects.iter().filter(|e| e.kind == k).map(|e| e.amp).max() }
    pub fn has_effect(&self, k: Effect) -> bool { self.effect_amp(k).is_some() }
    pub fn speed_mult(&self) -> f32 {
        let sp = self.effect_amp(Effect::Speed).map(|a| a as f32 + 1.0).unwrap_or(0.0);
        let sl = self.effect_amp(Effect::Slowness).map(|a| a as f32 + 1.0).unwrap_or(0.0);
        let traversal = if self.blessed(Passive::Traversal) { 0.25 } else { 0.0 };
        (1.0 + 0.2 * sp - 0.15 * sl + traversal).max(0.25)
    }
    pub fn jump_bonus(&self) -> f32 { 2.2 * self.effect_amp(Effect::JumpBoost).map(|a| a as f32 + 1.0).unwrap_or(0.0) }
    pub fn night_vision(&self) -> bool { self.has_effect(Effect::NightVision) }
    pub fn strength_bonus(&self) -> f32 { 3.0 * self.effect_amp(Effect::Strength).map(|a| a as f32 + 1.0).unwrap_or(0.0) }
    // Ares makes every melee swing land far harder. Sekhmet stacks on top of it once you're bloodied.
    pub fn might_mult(&self) -> f32 {
        let ares = if self.blessed(Passive::Might) { 1.5 } else { 1.0 };
        ares * if self.bloodraging() { BLOODRAGE_DAMAGE } else { 1.0 }
    }
    /// Sekhmet: cornered and bleeding, you hit harder and soak more.
    pub fn bloodraging(&self) -> bool {
        self.blessed(Passive::Bloodrage) && !self.dead && self.health <= self.max_health * BLOODRAGE_AT
    }
    fn resistance_mult(&self) -> f32 {
        let effects = (1.0 - 0.2 * self.effect_amp(Effect::Resistance).map(|a| a as f32 + 1.0).unwrap_or(0.0)).max(0.0);
        effects * if self.bloodraging() { BLOODRAGE_RESIST } else { 1.0 }
    }
    /// Camazotz: how much health a melee hit for `dealt` damage gives back.
    pub fn lifesteal(&mut self, dealt: f32) {
        if self.blessed(Passive::Lifesteal) { self.heal(dealt * LIFESTEAL_SHARE); }
    }

    pub fn add_effect(&mut self, kind: Effect, secs: f32, amp: u8) {
        if kind == Effect::Absorption { self.absorption = self.absorption.max((amp as f32 + 1.0) * 4.0); }
        if let Some(e) = self.effects.iter_mut().find(|e| e.kind == kind) {
            if amp >= e.amp { e.amp = amp; }
            e.secs = e.secs.max(secs);
        } else {
            self.effects.push(ActiveEffect { kind, secs, amp });
        }
    }
    pub fn heal(&mut self, amt: f32) { self.health = (self.health + amt).min(self.max_health); }
    // Firework rocket: while gliding, surge forward and gain a little altitude.
    pub fn firework_boost(&mut self) {
        if self.gliding {
            self.glide_boost = (self.glide_boost + 16.0).min(26.0);
            self.vel.y = self.vel.y.max(0.0) + 5.0;
            self.air_max_y = self.pos.y;
        }
    }
    pub fn damage(&mut self, amt: f32) {
        if self.dead || self.hurt_cd > 0.0 || amt <= 0.0 { return; }
        let amt = amt * self.resistance_mult();
        let soak = amt.min(self.absorption);
        self.absorption -= soak;
        self.health -= amt - soak;
        self.hurt_cd = 0.4;
        self.combat_cd = DIVINITY_DELAY;
        if self.health <= 0.0 { self.health = 0.0; self.dead = true; }
    }
    // Aeolus: a landed hit throws you skyward, which is what makes its combos work.
    pub fn wind_burst(&mut self) {
        if self.blessed(Passive::WindBurst) {
            self.vel.y = self.vel.y.max(0.0) + 9.0;
            self.on_ground = false;
            self.air_max_y = self.pos.y;
        }
    }

    // Regeneration/poison/absorption expiry + cooldowns. Called every frame.
    pub fn tick_status(&mut self, dt: f32) {
        if self.hurt_cd > 0.0 { self.hurt_cd -= dt; }
        if self.combat_cd > 0.0 { self.combat_cd -= dt; }
        if self.attack_cd > 0.0 { self.attack_cd -= dt; }
        if self.eat_cd > 0.0 { self.eat_cd -= dt; }
        for e in self.effects.iter_mut() { e.secs -= dt; }
        self.effects.retain(|e| e.secs > 0.0);
        if let Some(a) = self.effect_amp(Effect::Regeneration) { self.heal((a as f32 + 1.0) * dt); }
        if let Some(a) = self.effect_amp(Effect::Poison) {
            if self.health > 1.0 { self.health = (self.health - (a as f32 + 1.0) * dt).max(1.0); }
        }
        // Absorption from food expires; Athena's shield doesn't, so it sets the floor the potion
        // value decays back down to rather than being wiped along with it.
        if !self.has_effect(Effect::Absorption) {
            let floor = if self.blessed(Passive::Divinity) { DIVINITY_SHIELD } else { 0.0 };
            self.absorption = self.absorption.min(floor);
        }
        // Athena's shield reforms between fights. A larger absorption from food still wins while it
        // lasts, since the cap is whichever is higher.
        if self.blessed(Passive::Divinity) && self.combat_cd <= 0.0 && !self.dead {
            let cap = DIVINITY_SHIELD.max(self.absorption);
            self.absorption = (self.absorption + DIVINITY_REGEN * dt).min(cap);
        }
    }
    pub fn eye_pos(&self) -> Vec3 { self.pos + vec3(0.0, 1.62, 0.0) }
    pub fn forward(&self) -> Vec3 {
        let cp = self.pitch.cos();
        vec3(-self.yaw.sin() * cp, -self.pitch.sin(), -self.yaw.cos() * cp).normalize_or_zero()
    }
    pub fn right(&self) -> Vec3 { vec3(self.yaw.cos(), 0.0, -self.yaw.sin()).normalize_or_zero() }

    pub fn tick(&mut self, dt: f32, input: &crate::input::InputState, chunks: &ChunkMap) {
        self.sneaking = input.sneak;
        let jump_edge = input.jump_held && !self.prev_jump;
        self.prev_jump = input.jump_held;
        // Deploy/retract elytra wings with a mid-air jump tap; retract automatically on the ground.
        if self.elytra && jump_edge && !self.on_ground && !self.flying { self.glide_armed = !self.glide_armed; }
        if self.on_ground || !self.elytra { self.glide_armed = false; }
        self.glide_boost = (self.glide_boost - 10.0 * dt).max(0.0);
        let fwd = if self.flying {
            self.forward()
        } else {
            vec3(-self.yaw.sin(), 0.0, -self.yaw.cos())
        };
        let right = self.right();
        let mut wish = Vec3::ZERO;
        if input.move_forward.abs() > 0.001 { wish += fwd * input.move_forward; }
        if input.move_right.abs() > 0.001 { wish += right * input.move_right; }
        if wish.length_squared() > 0.0 { wish = wish.normalize_or_zero(); }

        if self.flying {
            let speed = 10.0 * self.speed_mult() * if input.sprint { 2.0 } else { 1.0 };
            let mut vel = wish * speed;
            if input.jump_held { vel.y += speed; } // top button (Up)
            if input.down_held { vel.y -= speed; } // bottom button (Down)
            let old = self.pos;
            self.pos += vel * dt;
            if self.collides_at(self.pos, chunks) { self.pos = old; }
            self.walk_dist += (vel * dt).length();
        } else {
            let sneaking = input.sneak;
            // Cronus lets you sneak at a walking pace instead of a crawl.
            let sneak_mult = if self.blessed(Passive::SwiftSneak) { 1.0 } else { 0.3 };
            let speed = 4.3 * self.speed_mult() * if sneaking { sneak_mult } else if input.sprint { 1.3 } else { 1.0 };
            // Auto-unstuck: if we ended up inside terrain (bad save, block placed onto us, spawn
            // fractionally embedded) rise until free so the player is never permanently trapped.
            if self.collides_at(self.pos, chunks) {
                let mut lift = 0.0;
                while lift < 3.0 && self.collides_at(self.pos, chunks) { self.pos.y += 0.1; lift += 0.1; }
                self.vel.y = 0.0;
            }
            // Elytra glide: once wings are deployed (mid-air jump tap), sail forward along the look
            // direction with a slow, controlled fall instead of walking control.
            self.gliding = self.glide_armed && !self.on_ground;
            let horiz_vel = if self.gliding {
                let f = self.forward();
                vec3(f.x, 0.0, f.z).normalize_or_zero() * (11.0 + self.glide_boost)
            } else {
                vec3(wish.x * speed, 0.0, wish.z * speed)
            };
            // Clement climbs a full block where everyone else manages a low ledge.
            let step_h = if self.blessed(Passive::Traversal) { 1.05 } else { 0.6 };
            let mut new_pos = self.pos;
            // X axis: step up small ledges when grounded; when sneaking, refuse to walk off edges.
            let try_x = vec3(new_pos.x + horiz_vel.x * dt, new_pos.y, new_pos.z);
            if !self.collides_at(try_x, chunks) {
                if !(sneaking && self.on_ground) || self.supported_at(try_x, chunks) { new_pos.x = try_x.x; }
            } else if self.on_ground && !sneaking {
                // Climb to the surface that blocked us, not by a fixed amount.
                if let Some(top) = self.step_up_to(try_x, chunks, new_pos.y, step_h) {
                    new_pos.x = try_x.x; new_pos.y = top;
                }
            }
            // Z axis, same rules.
            let try_z = vec3(new_pos.x, new_pos.y, new_pos.z + horiz_vel.z * dt);
            if !self.collides_at(try_z, chunks) {
                if !(sneaking && self.on_ground) || self.supported_at(try_z, chunks) { new_pos.z = try_z.z; }
            } else if self.on_ground && !sneaking {
                if let Some(top) = self.step_up_to(try_z, chunks, new_pos.y, step_h) {
                    new_pos.z = try_z.z; new_pos.y = top;
                }
            }
            self.walk_dist += (vec3(new_pos.x, 0.0, new_pos.z) - vec3(self.pos.x, 0.0, self.pos.z)).length();
            // Vertical: gravity + collision, resting at whatever height horizontal step-up left us at.
            // Yamm turns water into something you can actually swim in: buoyant, with jump to rise.
            let submerged = (self.blessed(Passive::Deep) || self.blessed(Passive::Conduit)) && self.in_water(chunks);
            let base_y = new_pos.y;
            if submerged {
                self.vel.y = if input.jump_held { 4.5 } else { (self.vel.y - 6.0 * dt).max(-2.0) };
            } else {
                self.vel.y -= 28.0 * dt;
            }
            if self.gliding { self.vel.y = self.vel.y.max(-3.5); } // slow, gliding descent
            new_pos.y = base_y + self.vel.y * dt;
            if self.collides_at(new_pos, chunks) {
                if self.vel.y <= 0.0 {
                    // Landing: come to rest exactly on the surface, so a slab holds you half a
                    // block up instead of at the cell boundary.
                    let depth = ((base_y - new_pos.y).ceil() as i32 + 2).max(2);
                    new_pos.y = self.surface_under(new_pos, chunks, base_y, depth).unwrap_or(base_y);
                    self.on_ground = true;
                } else {
                    new_pos.y = base_y; // knocked our head on something above
                }
                self.vel.y = 0.0;
            } else {
                let mut probe = new_pos; probe.y -= 0.05;
                self.on_ground = probe.y <= 0.0 || (self.collides_at(probe, chunks) && self.vel.y <= 0.0);
                if self.on_ground && self.vel.y <= 0.0 { self.vel.y = 0.0; }
            }
            if self.on_ground { self.jumps_left = self.extra_jumps(); }
            // Swimming is controlled descent, not a fall — don't let a dive bank landing damage.
            if submerged { self.air_max_y = new_pos.y; }
            // Can't jump while sneaking (must toggle sneak off first).
            if input.jump_held && self.on_ground && !sneaking {
                self.vel.y = 8.5 + self.jump_bonus();
                self.on_ground = false;
            } else if jump_edge && !self.on_ground && !sneaking && !submerged && !self.gliding && !self.flying && self.jumps_left > 0 {
                // Hyacinthus: a second leap out of mid-air.
                self.jumps_left -= 1;
                self.vel.y = 8.5 + self.jump_bonus();
                self.air_max_y = self.pos.y;
            }
            self.pos = new_pos;
        }
        if self.pos.y < 0.1 { self.pos.y = 0.1; self.vel.y = 0.0; self.on_ground = true; }
        // Fall damage: track peak airborne height, hurt on landing beyond a safe margin.
        // Flying and elytra gliding never accrue fall damage.
        if self.flying || self.gliding {
            self.air_max_y = self.pos.y;
        } else if !self.on_ground {
            if self.pos.y > self.air_max_y { self.air_max_y = self.pos.y; }
        } else {
            let dmg = landing_damage(self.air_max_y, self.pos.y, self.blessed(Passive::FeatherFall));
            if dmg > 0.0 { self.damage(dmg); }
            self.air_max_y = self.pos.y;
        }
    }

    /// How many mid-air jumps the attuned blessings allow.
    fn extra_jumps(&self) -> u8 { if self.blessed(Passive::DoubleJump) { 1 } else { 0 } }

    /// True when the player's body is inside water.
    fn in_water(&self, chunks: &ChunkMap) -> bool {
        let (x, z) = (self.pos.x.floor() as i32, self.pos.z.floor() as i32);
        let feet = chunks.get_block_world(x, self.pos.y.floor() as i32, z);
        let chest = chunks.get_block_world(x, (self.pos.y + 1.0).floor() as i32, z);
        feet == 12 || chest == 12
    }

    /// The highest surface under the player's footprint at or below `ceiling` — the height they
    /// should come to rest at. Probing all four corners means the tallest thing under any part of
    /// the body wins, so you stand on a stair's step rather than sinking into it.
    fn surface_under(&self, pos: Vec3, chunks: &ChunkMap, ceiling: f32, depth: i32) -> Option<f32> {
        let hw = 0.3;
        let mut best: Option<f32> = None;
        for &dx in &[-hw, hw] {
            for &dz in &[-hw, hw] {
                if let Some(t) = chunks.surface_below(pos.x + dx, pos.z + dz, ceiling, depth) {
                    if best.is_none_or(|b| t > b) { best = Some(t); }
                }
            }
        }
        best
    }

    /// The height to climb to when walking into something: the surface just above the feet, if it
    /// is within `max_step` and the player actually fits standing on it. Returns the real surface
    /// height, so a slab lifts you half a block rather than the full step allowance.
    fn step_up_to(&self, pos: Vec3, chunks: &ChunkMap, from_y: f32, max_step: f32) -> Option<f32> {
        let target = self.surface_under(pos, chunks, from_y + max_step, 2)?;
        if target <= from_y + 1e-4 { return None; }
        if self.collides_at(vec3(pos.x, target, pos.z), chunks) { return None; }
        Some(target)
    }

    // True if there is something solid to stand on just under the player's footprint at `pos` (used
    // so a sneaking player won't walk off ledges). A slab counts only where its solid half reaches.
    fn supported_at(&self, pos: Vec3, chunks: &ChunkMap) -> bool {
        self.surface_under(pos, chunks, pos.y + 1e-3, 1).is_some_and(|t| pos.y - t < 0.1)
    }

    fn collides_at(&self, pos: Vec3, chunks: &ChunkMap) -> bool {
        let hw = 0.3;
        let min = [pos.x - hw, pos.y, pos.z - hw];
        let max = [pos.x + hw, pos.y + 1.8, pos.z + hw];
        let x0 = min[0].floor() as i32; let y0 = min[1].floor() as i32; let z0 = min[2].floor() as i32;
        let x1 = max[0].ceil() as i32; let y1 = max[1].ceil() as i32; let z1 = max[2].ceil() as i32;
        for x in x0..=x1 { for y in y0..=y1 { for z in z0..=z1 {
            let id = chunks.get_block_world(x, y, z);
            if id == 0 || !Block::from_id(id).is_solid() { continue; }
            let meta = chunks.get_meta_world(x, y, z);
            let cell = [x as f32, y as f32, z as f32];
            for b in Block::from_id(id).collision_boxes(meta).as_slice() {
                if b.overlaps_at(cell, min, max) { return true; }
            }
        }}}
        false
    }
}

include!("player_part1.rs");
#[cfg(test)]
#[path = "player_coverage_tests.rs"]
mod player_coverage_tests;

#[cfg(test)]
#[path = "player_tick_tests.rs"]
mod player_tick_tests;
