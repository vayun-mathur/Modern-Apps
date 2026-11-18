// Mobs: simple entities with wander/chase AI, gravity + block collision, and skinned box-model
// rendering into the entity atlas (see texture_atlas::load_entity_atlas_bin / build_entity_atlas.py).
use glam::Vec3;
use crate::vulkan::buffers::Vertex;
use crate::texture_atlas::{ENTITY_ATLAS_W, ENTITY_CELL};
use crate::world::block::Id;

const ATLAS: f32 = ENTITY_ATLAS_W as f32;
const CELL: f32 = ENTITY_CELL as f32;
const PX: f32 = 1.0 / 16.0; // one skin pixel in blocks

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum MobKind { Pig, Cow, Sheep, Chicken, Creeper, Zombie, Villager, Dragon, Wither, Blaze, WitherSkeleton, Shulker, Ghast }

impl MobKind {
    /// Every kind, for exhaustive checks over loot and spawning.
    pub const ALL: [MobKind; 13] = [
        MobKind::Pig, MobKind::Cow, MobKind::Sheep, MobKind::Chicken, MobKind::Creeper,
        MobKind::Zombie, MobKind::Villager, MobKind::Dragon, MobKind::Wither, MobKind::Blaze,
        MobKind::WitherSkeleton, MobKind::Shulker, MobKind::Ghast,
    ];
}

// A model part: an axis-aligned box textured from the skin.
struct Part {
    size: [f32; 3], // pixels (w, h, d)
    pos: [f32; 3],  // centre in blocks, feet at y=0, y up
    uv: [f32; 2],   // skin-space origin (pixels)
    x_rot: f32,     // static rotation about X (radians) around `pos`
    leg: f32,       // walk-swing phase sign (0 = not animated); rotates about the part's top
}

const fn p(size: [f32;3], pos: [f32;3], uv: [f32;2], x_rot: f32, leg: f32) -> Part { Part{size,pos,uv,x_rot,leg} }
const HP: f32 = std::f32::consts::FRAC_PI_2;

// Quadruped (pig/cow/sheep): head, laid-down body, four legs.
static QUAD: &[Part] = &[
    p([8.,8.,8.],   [0.0, 0.70, -0.44], [0.,0.],   0.0, 0.0),
    p([10.,16.,8.], [0.0, 0.56, 0.06],  [28.,8.],  HP,  0.0),
    p([4.,6.,4.],   [-0.16, 0.1875, -0.22], [0.,16.], 0.0,  1.0),
    p([4.,6.,4.],   [0.16, 0.1875, -0.22],  [0.,16.], 0.0, -1.0),
    p([4.,6.,4.],   [-0.16, 0.1875, 0.28],  [0.,16.], 0.0, -1.0),
    p([4.,6.,4.],   [0.16, 0.1875, 0.28],   [0.,16.], 0.0,  1.0),
];
// Biped (zombie): head, body, two legs, two arms.
static BIPED: &[Part] = &[
    p([8.,8.,8.],   [0.0, 1.50, 0.0], [0.,0.],   0.0, 0.0),
    p([8.,12.,4.],  [0.0, 1.125, 0.0], [16.,16.], 0.0, 0.0),
    p([4.,12.,4.],  [-0.125, 0.375, 0.0], [0.,16.],  0.0,  1.0),
    p([4.,12.,4.],  [0.125, 0.375, 0.0],  [0.,16.],  0.0, -1.0),
    p([4.,12.,4.],  [-0.375, 1.125, 0.0], [40.,16.], 0.0, -1.0),
    p([4.,12.,4.],  [0.375, 1.125, 0.0],  [40.,16.], 0.0,  1.0),
];
// Creeper: head, tall body, four short legs.
static CREEPER: &[Part] = &[
    p([8.,8.,8.],  [0.0, 1.375, 0.0], [0.,0.],   0.0, 0.0),
    p([8.,12.,4.], [0.0, 0.75, 0.0],  [16.,16.], 0.0, 0.0),
    p([4.,6.,4.],  [-0.125, 0.1875, -0.125], [0.,16.], 0.0,  1.0),
    p([4.,6.,4.],  [0.125, 0.1875, -0.125],  [0.,16.], 0.0, -1.0),
    p([4.,6.,4.],  [-0.125, 0.1875, 0.125],  [0.,16.], 0.0, -1.0),
    p([4.,6.,4.],  [0.125, 0.1875, 0.125],   [0.,16.], 0.0,  1.0),
];
// Ender Dragon: a large flyer — head, body, two broad wings, tail. Uniform purple skin.
static DRAGON: &[Part] = &[
    p([12.,10.,16.], [0.0, 3.1, -1.9], [0.,0.], 0.0, 0.0),  // head
    p([16.,14.,26.], [0.0, 2.8, 0.4],  [0.,0.], 0.0, 0.0),  // body
    p([40.,3.,20.],  [-1.7, 3.3, 0.3], [0.,0.], 0.0, 1.0),  // left wing (flaps)
    p([40.,3.,20.],  [1.7, 3.3, 0.3],  [0.,0.], 0.0, -1.0), // right wing
    p([8.,8.,24.],   [0.0, 2.9, 2.2],  [0.,0.], 0.0, 0.0),  // tail
];

// Wither: a floating three-headed boss (central + two side heads on a ribcage body).
static WITHER: &[Part] = &[
    p([8.,8.,8.],   [0.0, 2.6, 0.0],  [0.,0.], 0.0, 0.0),  // central head
    p([6.,6.,6.],   [-0.55, 2.4, 0.0], [0.,0.], 0.0, 0.0), // left head
    p([6.,6.,6.],   [0.55, 2.4, 0.0],  [0.,0.], 0.0, 0.0), // right head
    p([4.,14.,4.],  [0.0, 1.7, 0.0],  [0.,0.], 0.0, 0.0),  // spine
    p([18.,3.,3.],  [0.0, 2.0, 0.0],  [0.,0.], 0.0, 0.0),  // ribs
];

// Blaze: a floating head ringed by spinning rods (no legs). Uniform procedural skin.
static BLAZE: &[Part] = &[
    p([8.,8.,8.],  [0.0, 1.4, 0.0],  [0.,0.], 0.0, 0.0), // head
    p([2.,8.,2.],  [-0.2, 0.9, -0.2], [0.,0.], 0.0, 1.0),
    p([2.,8.,2.],  [0.2, 0.9, 0.2],   [0.,0.], 0.0, -1.0),
    p([2.,8.,2.],  [0.2, 0.9, -0.2],  [0.,0.], 0.0, -1.0),
    p([2.,8.,2.],  [-0.2, 0.9, 0.2],  [0.,0.], 0.0, 1.0),
];

// Ghast: a big floating pale body with nine dangling tentacles. Uniform white skin.
static GHAST: &[Part] = &[
    p([16.,16.,16.], [0.0, 2.0, 0.0], [0.,0.], 0.0, 0.0), // body
    p([2.,10.,2.], [-0.4, 0.6, -0.4], [0.,0.], 0.0, 1.0),
    p([2.,10.,2.], [0.0, 0.6, -0.4],  [0.,0.], 0.0, -1.0),
    p([2.,10.,2.], [0.4, 0.6, -0.4],  [0.,0.], 0.0, 1.0),
    p([2.,10.,2.], [-0.4, 0.6, 0.0],  [0.,0.], 0.0, -1.0),
    p([2.,10.,2.], [0.4, 0.6, 0.0],   [0.,0.], 0.0, 1.0),
    p([2.,10.,2.], [-0.4, 0.6, 0.4],  [0.,0.], 0.0, -1.0),
    p([2.,10.,2.], [0.0, 0.6, 0.4],   [0.,0.], 0.0, 1.0),
    p([2.,10.,2.], [0.4, 0.6, 0.4],   [0.,0.], 0.0, -1.0),
];

// Shulker: a stationary boxy End-city turret — a thick shell with a small head peeking out.
static SHULKER: &[Part] = &[
    p([12.,8.,12.], [0.0, 0.25, 0.0], [0.,0.], 0.0, 0.0), // shell base
    p([8.,8.,8.],   [0.0, 0.75, 0.0], [0.,0.], 0.0, 0.0), // shell lid
    p([6.,6.,6.],   [0.0, 0.65, 0.0], [0.,0.], 0.0, 0.0), // head peeking out
];

// Chicken: small body, head, two thin legs.
static CHICKEN: &[Part] = &[
    p([6.,8.,6.],  [0.0, 0.38, 0.0],  [0.,9.],  0.0, 0.0),
    p([4.,6.,3.],  [0.0, 0.62, -0.18], [0.,0.], 0.0, 0.0),
    p([2.,5.,2.],  [-0.09, 0.15, 0.0], [26.,2.], 0.0,  1.0),
    p([2.,5.,2.],  [0.09, 0.15, 0.0],  [26.,2.], 0.0, -1.0),
];

impl MobKind {
    pub fn cell(self) -> u32 { match self { MobKind::Pig=>0, MobKind::Cow=>1, MobKind::Sheep=>2, MobKind::Chicken=>3, MobKind::Creeper=>4, MobKind::Zombie=>5, MobKind::Villager=>6, MobKind::Dragon=>7, MobKind::Wither=>8, MobKind::Blaze=>9, MobKind::WitherSkeleton=>10, MobKind::Shulker=>11, MobKind::Ghast=>12 } }
    /// Inverse of [`cell`] for reconstructing a mob kind off the wire; unknown tags fall back to Pig.
    pub fn from_cell(c: u32) -> MobKind { MobKind::ALL.get(c as usize).copied().unwrap_or(MobKind::Pig) }
    pub fn hostile(self) -> bool { matches!(self, MobKind::Creeper | MobKind::Zombie | MobKind::Dragon | MobKind::Wither | MobKind::Blaze | MobKind::WitherSkeleton | MobKind::Shulker | MobKind::Ghast) }
    pub fn is_boss(self) -> bool { matches!(self, MobKind::Dragon | MobKind::Wither) }
    pub fn flies(self) -> bool { matches!(self, MobKind::Dragon | MobKind::Wither | MobKind::Ghast) }
    // Procedurally-skinned mobs sample their cell centre uniformly (no real texture map).
    pub fn uniform_skin(self) -> bool { matches!(self, MobKind::Dragon | MobKind::Wither | MobKind::Blaze | MobKind::WitherSkeleton | MobKind::Shulker | MobKind::Ghast) }
    pub fn max_health(self) -> f32{ match self {
        MobKind::Dragon => 200.0, MobKind::Wither => 150.0,
        // Matcha rebalances hostiles away from vanilla's flat 20: creepers are chunkier than skeletons,
        // and a ghast is a big fragile balloon rather than a tank.
        MobKind::Shulker => 30.0, MobKind::Ghast => 12.0,
        MobKind::Zombie | MobKind::Villager | MobKind::Blaze => 20.0,
        MobKind::Creeper => 16.0, MobKind::WitherSkeleton => 14.0,
        MobKind::Chicken => 4.0,
        _ => 10.0,
    } }
    pub fn height(self) -> f32 { match self { MobKind::Dragon => 4.0, MobKind::Ghast => 4.0, MobKind::Wither => 3.2, MobKind::WitherSkeleton => 2.4, MobKind::Zombie | MobKind::Villager => 1.9, MobKind::Blaze => 1.8, MobKind::Creeper => 1.6, MobKind::Shulker => 1.0, MobKind::Chicken => 0.7, _ => 0.9 } }
    pub fn hit_radius(self) -> f32 { match self { MobKind::Dragon => 2.2, MobKind::Ghast => 2.0, MobKind::Wither => 1.3, MobKind::Shulker => 0.55, _ => 0.45 } }
    pub fn contact_damage(self) -> f32 { match self { MobKind::Zombie => 4.0, MobKind::Dragon => 7.0, MobKind::Wither => 6.0, MobKind::Blaze | MobKind::WitherSkeleton => 5.0, MobKind::Shulker => 4.0, _ => 0.0 } }
    // Item ids dropped on death (auto-collected into the inventory).
    pub fn loot(self) -> &'static [Id] {
        match self {
            // Livestock give up raw meat (1118); it has to be cooked in a furnace before it feeds you.
            MobKind::Pig => &[1118], MobKind::Cow => &[1033, 1118], MobKind::Sheep => &[1118], MobKind::Chicken => &[1118, 1045],
            MobKind::Creeper => &[1034], MobKind::Zombie => &[1027], MobKind::Villager => &[], MobKind::Dragon => &[85, 25, 25],
            MobKind::Wither => &[1083, 1051, 1051],
            MobKind::Blaze => &[1053], MobKind::WitherSkeleton => &[1053, 1050], MobKind::Shulker => &[89, 1087],
            MobKind::Ghast => &[1034, 1034],
        }
    }
    // Matcha makes zombies notably fast; they're the pressure mob, so they out-run a walking player.
    fn speed(self) -> f32 { match self { MobKind::Chicken=>1.6, MobKind::Creeper=>1.5, MobKind::Zombie=>2.0, MobKind::Blaze=>1.4, MobKind::WitherSkeleton=>1.8, MobKind::Shulker=>0.0, MobKind::Villager=>0.9, _=>1.15 } }
    fn parts(self) -> &'static [Part] {
        match self {
            MobKind::Pig | MobKind::Cow | MobKind::Sheep => QUAD,
            MobKind::Zombie | MobKind::Villager | MobKind::WitherSkeleton => BIPED,
            MobKind::Creeper => CREEPER,
            MobKind::Chicken => CHICKEN,
            MobKind::Dragon => DRAGON,
            MobKind::Wither => WITHER,
            MobKind::Blaze => BLAZE,
            MobKind::Shulker => SHULKER,
            MobKind::Ghast => GHAST,
        }
    }
}

pub struct Mob {
    pub kind: MobKind,
    pub pos: Vec3,
    pub vel: Vec3,
    pub yaw: f32,
    pub health: f32,
    pub attack_cd: f32,
    pub fuse: f32, // creeper explosion timer
    target_yaw: f32,
    wander: f32,
    anim: f32,
    on_ground: bool,
    rng: u32,
    /// Villagers only: which trade table this one keeps. Drawn from the spawn seed, so it's stable for
    /// the mob's lifetime even though mobs aren't saved.
    pub profession: u8,
    /// This individual's rolled stats, so two zombies aren't interchangeable.
    pub max_health: f32,
    pub speed: f32,
    pub elite: bool,
    /// Radius the mob refuses to come inside, set by the engine each tick. Anubis pushes the undead
    /// back; everything else leaves this at zero.
    pub repelled: f32,
    /// Sheep only: its coat has already been taken.
    pub sheared: bool,
}

/// Per-spawn stat jitter. Matcha retunes mobs by type; this adds variation *within* a type so an
/// encounter isn't fully predictable, plus a rare elite that reads as visibly dangerous.
pub struct Variation {
    pub health: f32,
    pub speed: f32,
    pub elite: bool,
}

const ELITE_CHANCE: f32 = 0.07;
const ELITE_HEALTH: f32 = 1.9;
const ELITE_SPEED: f32 = 1.15;
/// Jitter bounds, as a fraction either side of the type's base stat.
const HEALTH_JITTER: f32 = 0.15;
const SPEED_JITTER: f32 = 0.10;

/// Roll one mob's stats from its spawn seed. Bosses are excluded: a set-piece fight has to be the
/// same fight every time.
pub fn roll_variation(kind: MobKind, seed: u32) -> Variation {
    let base_health = kind.max_health();
    let base_speed = kind.speed();
    if kind.is_boss() {
        return Variation { health: base_health, speed: base_speed, elite: false };
    }
    let mut s = seed | 1;
    let hj = 1.0 + (xorshift(&mut s) * 2.0 - 1.0) * HEALTH_JITTER;
    let sj = 1.0 + (xorshift(&mut s) * 2.0 - 1.0) * SPEED_JITTER;
    // Only things that fight the player can be elite; an elite cow is just a confusing cow.
    let elite = kind.hostile() && xorshift(&mut s) < ELITE_CHANCE;
    Variation {
        health: base_health * hj * if elite { ELITE_HEALTH } else { 1.0 },
        speed: base_speed * sj * if elite { ELITE_SPEED } else { 1.0 },
        elite,
    }
}

fn xorshift(s: &mut u32) -> f32 {
    let mut x = *s; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *s = x;
    (x >> 8) as f32 / 16_777_216.0 // [0,1)
}

/// The world queries a mob's physics needs, both understanding slab and stair geometry:
/// whether a point is inside solid material, and how high the surface under a column is.
pub struct Terrain<'a> {
    pub solid: &'a dyn Fn(f32, f32, f32) -> bool,
    /// (x, z, ceiling) -> the highest surface at or below `ceiling` in that column.
    pub surface: &'a dyn Fn(f32, f32, f32) -> Option<f32>,
}

impl Mob {
    pub fn new(kind: MobKind, pos: Vec3, seed: u32) -> Self {
        let v = roll_variation(kind, seed);
        Mob { kind, pos, vel: Vec3::ZERO, yaw: 0.0, health: v.health, attack_cd: 0.0, fuse: 0.0,
            target_yaw: 0.0, wander: 0.0, anim: 0.0, on_ground: false, rng: seed | 1,
            profession: crate::villager::Profession::from_seed(seed | 1).index() as u8,
            max_health: v.health, speed: v.speed, elite: v.elite, repelled: 0.0, sheared: false }
    }

    pub fn tick(&mut self, dt: f32, player: Vec3, terrain: &Terrain) {
        // Flying bosses (Dragon/Wither): no gravity/collision; orbit + periodic swoop at the player.
        if self.kind.flies() {
            let _ = terrain;
            self.attack_cd = (self.attack_cd - dt).max(0.0);
            self.wander -= dt;
            if self.wander <= 0.0 { self.wander = 9.0; }
            // Ghasts hover high and never dive (they attack purely at range); bosses swoop.
            let (center, radius, spd, diving) = match self.kind {
                MobKind::Dragon => (Vec3::new(0.0, 86.0, 0.0), 30.0, 13.0, self.wander < 2.0),
                MobKind::Ghast => (player + Vec3::new(0.0, 14.0, 0.0), 20.0, 6.0, false),
                _ => (player + Vec3::new(0.0, 6.0, 0.0), 9.0, 9.0, self.wander < 2.0),
            };
            let target = if diving {
                player + Vec3::new(0.0, 1.0, 0.0)
            } else {
                let a = self.anim * 0.5;
                center + Vec3::new(a.cos() * radius, (a * 1.3).sin() * 3.0, a.sin() * radius)
            };
            let dir = target - self.pos;
            let d = dir.length();
            if d > 0.5 { let v = dir / d; self.pos += v * spd * dt; self.yaw = v.x.atan2(-v.z); }
            self.anim += dt * 3.0;
            return;
        }
        self.wander -= dt;
        let to_player = player - self.pos;
        let pdist = to_player.length();
        // Anubis: a warded player is walked away from, not toward.
        let warded = self.repelled > 0.0 && pdist < self.repelled;
        if self.wander <= 0.0 {
            self.wander = 1.5 + xorshift(&mut self.rng) * 3.0;
            if warded {
                self.target_yaw = (-to_player.x).atan2(to_player.z); // turn tail
            } else if self.kind.hostile() && pdist < 22.0 {
                self.target_yaw = to_player.x.atan2(-to_player.z); // face the player
            } else {
                self.target_yaw = (xorshift(&mut self.rng) - 0.5) * std::f32::consts::TAU;
            }
            // Occasionally idle (stand still).
            if !self.kind.hostile() && xorshift(&mut self.rng) < 0.3 { self.wander = 1.0; self.target_yaw = self.yaw; }
        }
        // A warded mob flees at once rather than waiting for its next wander roll.
        if warded { self.target_yaw = (-to_player.x).atan2(to_player.z); }
        // Ease yaw toward the target.
        let mut dyaw = self.target_yaw - self.yaw;
        while dyaw > std::f32::consts::PI { dyaw -= std::f32::consts::TAU; }
        while dyaw < -std::f32::consts::PI { dyaw += std::f32::consts::TAU; }
        self.yaw += dyaw.clamp(-3.0 * dt, 3.0 * dt);

        let chasing = self.kind.hostile() && pdist < 22.0 && !warded;
        let idle = !chasing && (self.target_yaw - self.yaw).abs() < 0.05 && self.wander < 1.05 && !self.kind.hostile();
        let speed = if idle { 0.0 } else { self.speed * if chasing { 1.25 } else { 1.0 } };
        // Model front is -Z; rotating (0,0,-1) by yaw gives (sin, 0, -cos).
        let fwd = Vec3::new(self.yaw.sin(), 0.0, -self.yaw.cos());

        // Gravity.
        self.vel.y -= 24.0 * dt;
        self.vel.y = self.vel.y.max(-40.0);

        let at = |x: f32, y: f32, z: f32| (terrain.solid)(x, y, z);
        let r = 0.3;
        // Horizontal move per axis, blocked by walls, stepping up onto whatever is actually there.
        let dx = fwd.x * speed * dt;
        if dx != 0.0 {
            let nx = self.pos.x + dx + r * dx.signum();
            if !at(nx, self.pos.y + 0.2, self.pos.z) && !at(nx, self.pos.y + 1.0, self.pos.z) {
                self.pos.x += dx;
            } else if let Some(top) = self.step_target(terrain, nx, self.pos.z) {
                self.pos.x += dx; self.pos.y = top;
            }
        }
        let dz = fwd.z * speed * dt;
        if dz != 0.0 {
            let nz = self.pos.z + dz + r * dz.signum();
            if !at(self.pos.x, self.pos.y + 0.2, nz) && !at(self.pos.x, self.pos.y + 1.0, nz) {
                self.pos.z += dz;
            } else if let Some(top) = self.step_target(terrain, self.pos.x, nz) {
                self.pos.z += dz; self.pos.y = top;
            }
        }
        // Vertical: fall until something is underfoot, then rest on its actual surface.
        let prev_y = self.pos.y;
        self.pos.y += self.vel.y * dt;
        self.on_ground = false;
        if self.vel.y <= 0.0 {
            if let Some(top) = (terrain.surface)(self.pos.x, self.pos.z, prev_y) {
                if self.pos.y <= top {
                    self.pos.y = top;
                    self.vel.y = 0.0;
                    self.on_ground = true;
                }
            }
        }
        // Hostiles hop toward the player over small ledges.
        if chasing && self.on_ground && speed > 0.0 && at(self.pos.x + fwd.x * 0.4, self.pos.y + 0.1, self.pos.z + fwd.z * 0.4) {
            self.vel.y = 8.0;
        }
        self.anim += speed * dt * 5.5;
    }

    /// The height to climb to when a mob walks into something at (x, z): the surface just above its
    /// feet, if the rise is at most a block and the mob's body fits standing there. Following the
    /// real surface means a slab lifts a mob half a block, not a whole one.
    fn step_target(&self, terrain: &Terrain, x: f32, z: f32) -> Option<f32> {
        if !self.on_ground { return None; }
        let top = (terrain.surface)(x, z, self.pos.y + 1.0)?;
        let rise = top - self.pos.y;
        if rise <= 1e-4 || rise > 1.0 { return None; }
        let head = self.kind.height().max(1.0);
        if (terrain.solid)(x, top + 0.2, z) || (terrain.solid)(x, top + head, z) { return None; }
        Some(top)
    }

    /// A villager wears its profession's colours, an elite burns red, and everything else uses its skin
    /// unmodified.
    fn tint(&self) -> [f32; 3] {
        if self.kind == MobKind::Villager {
            crate::villager::Profession::from_index(self.profession as usize).tint()
        } else if self.elite {
            [1.35, 0.62, 0.55]
        } else {
            [1.0, 1.0, 1.0]
        }
    }

    fn append_mesh(&self, verts: &mut Vec<Vertex>, indices: &mut Vec<u32>) {
        let col = (self.kind.cell() % 4) as f32 * CELL;
        let row = (self.kind.cell() / 4) as f32 * CELL;
        // Procedural skins are uniform, so sample the cell centre for every face.
        let uniform = self.kind.uniform_skin();
        let tint = self.tint();
        let (yc, ys) = (self.yaw.cos(), self.yaw.sin());
        for part in self.kind.parts() {
            let swing = if part.leg != 0.0 { self.anim.sin() * 0.55 * part.leg } else { 0.0 };
            let xr = part.x_rot + swing;
            let (hx, hy, hz) = (part.size[0]*PX*0.5, part.size[1]*PX*0.5, part.size[2]*PX*0.5);
            let center = Vec3::new(part.pos[0], part.pos[1], part.pos[2]);
            // Legs swing about their top; static-rotated parts rotate about their centre.
            let pivot_y = if part.leg != 0.0 { center.y + hy } else { center.y };
            let (xc, xs) = (xr.cos(), xr.sin());
            // Skin-space box unwrap (pixels) for the 6 faces in order +X,-X,+Y,-Y,+Z,-Z.
            let (w, h, d) = (part.size[0], part.size[1], part.size[2]);
            let (u, v) = (part.uv[0], part.uv[1]);
            let rects = [
                [u, v+d, u+d, v+d+h],               // +X east
                [u+d+w, v+d, u+2.0*d+w, v+d+h],      // -X west
                [u+d, v, u+d+w, v+d],               // +Y up
                [u+d+w, v, u+d+2.0*w, v+d],          // -Y down
                [u+2.0*d+w, v+d, u+2.0*d+2.0*w, v+d+h], // +Z south (back)
                [u+d, v+d, u+d+w, v+d+h],           // -Z north (front)
            ];
            // Face corners (CCW) as (x,y,z) multipliers of half-extents.
            let faces: [[[f32;3];4];6] = [
                [[1.,-1.,-1.],[1.,1.,-1.],[1.,1.,1.],[1.,-1.,1.]],   // +X
                [[-1.,-1.,1.],[-1.,1.,1.],[-1.,1.,-1.],[-1.,-1.,-1.]], // -X
                [[-1.,1.,-1.],[-1.,1.,1.],[1.,1.,1.],[1.,1.,-1.]],   // +Y
                [[-1.,-1.,1.],[-1.,-1.,-1.],[1.,-1.,-1.],[1.,-1.,1.]], // -Y
                [[1.,-1.,1.],[1.,1.,1.],[-1.,1.,1.],[-1.,-1.,1.]],   // +Z
                [[-1.,-1.,-1.],[-1.,1.,-1.],[1.,1.,-1.],[1.,-1.,-1.]], // -Z
            ];
            let normals = [[1.,0.,0.],[-1.,0.,0.],[0.,1.,0.],[0.,-1.,0.],[0.,0.,1.],[0.,0.,-1.]];
            for f in 0..6 {
                let base = verts.len() as u32;
                let rect = rects[f];
                let uvc = [[rect[0],rect[3]],[rect[0],rect[1]],[rect[2],rect[1]],[rect[2],rect[3]]];
                // Transform normal (x-rot then yaw).
                let mut nrm = normals[f];
                { let (ny, nz) = (nrm[1]*xc - nrm[2]*xs, nrm[1]*xs + nrm[2]*xc); nrm[1]=ny; nrm[2]=nz;
                  let (nx, nz2) = (nrm[0]*yc - nrm[2]*ys, nrm[0]*ys + nrm[2]*yc); nrm[0]=nx; nrm[2]=nz2; }
                for c in 0..4 {
                    let m = faces[f][c];
                    let mut wp = Vec3::new(center.x + m[0]*hx, center.y + m[1]*hy, center.z + m[2]*hz);
                    // x-rotation about pivot.
                    let (dy, dz) = (wp.y - pivot_y, wp.z - center.z);
                    wp.y = pivot_y + dy*xc - dz*xs;
                    wp.z = center.z + dy*xs + dz*xc;
                    // yaw about Y through the mob origin.
                    let (rx, rz) = (wp.x*yc - wp.z*ys, wp.x*ys + wp.z*yc);
                    wp = Vec3::new(rx, wp.y, rz) + self.pos;
                    let uv = if uniform { [(col + 32.0) / ATLAS, (row + 32.0) / ATLAS] }
                             else { [(col + uvc[c][0]) / ATLAS, (row + uvc[c][1]) / ATLAS] };
                    verts.push(Vertex { pos: [wp.x, wp.y, wp.z], uv, color: tint, ao: 1.0, tile_idx: 0.0, normal: nrm, light: 0.0 });
                }
                indices.extend_from_slice(&[base, base+1, base+2, base, base+2, base+3]);
            }
        }
    }
}

// Build one vertex/index buffer for all mobs.
pub fn build_entity_mesh(mobs: &[Mob]) -> (Vec<Vertex>, Vec<u32>) {
    let mut verts = Vec::new();
    let mut indices = Vec::new();
    for m in mobs { m.append_mesh(&mut verts, &mut indices); }
    (verts, indices)
}

include!("entity_part1.rs");
#[cfg(test)]
#[path = "entity_coverage_tests.rs"]
mod entity_coverage_tests;
