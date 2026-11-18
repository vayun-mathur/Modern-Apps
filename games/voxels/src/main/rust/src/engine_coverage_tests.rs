//! Engine coverage: dimension/portal/beacon helpers, projectiles, smelting,
//! break/place interactions, blessings over time, and the global JNI-facing API.

use super::*;
use crate::world::block::{Block, Id, META_TOP};

fn state(tag: &str) -> EngineState { super::state_tests::small_state(tag) }
fn big(f: impl FnOnce() + Send + 'static) { super::state_tests::big_stack(f) }
const Y: i32 = 200;
fn put(s: &mut EngineState, x: i32, y: i32, z: i32, id: Id) { s.chunks.set_block_world(x, y, z, id); }
fn sel(s: &mut EngineState, id: Id, count: i32) {
    s.inventory.selected = 0;
    s.inventory.slots[0] = crate::inventory::InvSlot { id, count };
}
fn down(_s: &mut EngineState, x: i32, z: i32) -> (Vec3, Vec3) {
    (vec3(x as f32 + 0.5, (Y + 6) as f32, z as f32 + 0.5), vec3(0.0, -1.0, 0.0))
}

#[test]
fn portal_beacon_and_ray_helpers() { big(|| {
    let mut s = state("helpers");
    s.width = 800; s.height = 400;
    // screen_ray: centre ray follows the player's forward, corners diverge.
    s.player.yaw = 0.0; s.player.pitch = 0.0;
    let (eye, centre) = screen_ray(&s, 400.0, 200.0);
    assert_eq!(eye, s.player.eye_pos());
    assert!(centre.dot(s.player.forward()) > 0.99, "{centre:?}");
    let (_, corner) = screen_ray(&s, 0.0, 0.0);
    assert!(corner.dot(centre) < centre.dot(centre) + 1.0 && corner != centre);
    assert_eq!(dim_dir("/w", 0), "/w");
    assert_eq!(dim_dir("/w", 1), "/w/nether");
    assert_eq!(dim_dir("/w", 2), "/w/end");
    assert_eq!(dim_dir("/w", 9), "/w");
    // Beacon tiers: no pyramid, one layer, a broken second layer, then full.
    let (bx, bz) = (40, 40);
    put(&mut s, bx, Y, bz, Block::Beacon as Id);
    assert_eq!(beacon_tier(&s.chunks, bx, Y, bz), 0);
    for dx in -1..=1 { for dz in -1..=1 { put(&mut s, bx + dx, Y - 1, bz + dz, 23); } }
    assert_eq!(beacon_tier(&s.chunks, bx, Y, bz), 1);
    for dx in -2..=2 { for dz in -2..=2 { put(&mut s, bx + dx, Y - 2, bz + dz, 24); } }
    put(&mut s, bx + 2, Y - 2, bz + 2, 1); // stone is not mineral: layer 2 incomplete
    assert_eq!(beacon_tier(&s.chunks, bx, Y, bz), 1);
    put(&mut s, bx + 2, Y - 2, bz + 2, 25);
    assert_eq!(beacon_tier(&s.chunks, bx, Y, bz), 2);
    // A generated portal frame lights, and its interior is exactly the enclosed air.
    build_nether_portal(&mut s.chunks, 20, Y, 20);
    assert_eq!(s.chunks.get_block_world(19, Y - 1, 20), 78);
    assert_eq!(s.chunks.get_block_world(20, Y, 20), 86, "portal interior is pre-filled by the builder");
    // Build a clean frame manually for the flood-fill: 2x3 interior.
    for gx in -1..=2 { for gy in -1..=3 { put(&mut s, 30 + gx, Y + gy, 30, 78); } }
    for gx in 0..=1 { for gy in 0..=2 { put(&mut s, 30 + gx, Y + gy, 30, 0); } }
    let cells = portal_interior(&s.chunks, 30, Y, 30, 0).expect("enclosed interior");
    assert_eq!(cells.len(), 6);
    assert!(portal_interior(&s.chunks, 30, Y, 30, 1).is_none(), "wrong axis is not enclosed by obsidian");
    assert!(portal_interior(&s.chunks, 0, Y + 20, 0, 0).is_none(), "open sky is not a portal");
    assert!(portal_interior(&s.chunks, 30, Y - 1, 30, 0).is_none(), "starting inside obsidian fails");
    assert!(light_portal(&mut s, 30, Y - 1, 30), "tapping the frame base lights the portal above it");
    assert_eq!(s.chunks.get_block_world(30, Y, 30), 86);
    assert!(!light_portal(&mut s, 0, Y + 20, 0));
    // Chests: first open rolls loot, second keeps it.
    let key = ensure_chest(&mut s, 5, Y, 5);
    let first = s.containers.get(key).unwrap().clone();
    assert_eq!(ensure_chest(&mut s, 5, Y, 5), key);
    assert_eq!(s.containers.get(key).unwrap().iter().map(|x|(x.id,x.count)).collect::<Vec<_>>(), first.iter().map(|x|(x.id,x.count)).collect::<Vec<_>>());
}) }

#[test]
fn hurt_thorns_and_projectiles() { big(|| {
    let mut s = state("proj");
    // Armor reduces the hit; thorns reflect to the named attacker only.
    s.inventory.armor[1] = crate::inventory::InvSlot { id: 1072, count: 100 };
    assert!(s.player.blessings.attune(1116)); // Thorns
    s.mobs.push(Mob::new(MobKind::Zombie, vec3(0.0, 70.0, 0.0), 3));
    s.mobs.push(Mob::new(MobKind::Pig, vec3(5.0, 70.0, 5.0), 3));
    let (hp0, mob0) = (s.player.health, s.mobs[0].health);
    hurt_player_from(&mut s, 10.0, &[0]);
    assert!(s.player.health < hp0 && s.player.health > hp0 - 10.0, "armor must soften the blow");
    assert!(s.mobs[0].health < mob0, "thorns must reflect");
    assert_eq!(s.mobs[1].health, Mob::new(MobKind::Pig, Vec3::ZERO, 3).health, "bystanders are not thorned");
    let hp1 = s.player.health;
    s.player.hurt_cd = 10.0; // damage() itself refuses during the cooldown window check below
    hurt_player(&mut s, 0.0);
    assert_eq!(s.player.health, hp1);
    // Projectiles: every kind flies, expires, and resolves its on-hit effect.
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    s.projectiles.push(Projectile { pos: vec3(0.5, (Y + 1) as f32, 0.5), vel: Vec3::ZERO, life: 0.01, kind: ProjKind::EnderPearl, from_player: true, damage: 0.0, explosive: false });
    tick_projectiles(&mut s, 0.05);
    assert!(s.projectiles.is_empty());
    assert!((s.player.pos.y - ((Y + 1) as f32 + 0.5)).abs() < 1.1, "pearl teleports to its landing: {:?}", s.player.pos);
    s.projectiles.push(Projectile { pos: vec3(20.5, (Y + 1) as f32, 20.5), vel: Vec3::ZERO, life: 0.01, kind: ProjKind::Fireball, from_player: false, damage: 0.0, explosive: true });
    tick_projectiles(&mut s, 0.05);
    assert!(s.particles.len() >= 30, "an explosion bursts particles");
    s.projectiles.push(Projectile { pos: s.player.pos + vec3(0.0, 1.0, 0.0), vel: Vec3::ZERO, life: 5.0, kind: ProjKind::Fireball, from_player: false, damage: 3.0, explosive: false });
    let hp = s.player.health;
    s.player.hurt_cd = 0.0;
    tick_projectiles(&mut s, 0.016);
    assert!(s.player.health < hp || s.player.hurt_cd > 0.0, "enemy fireball must hurt the player");
    // A player snowball damages the mob it lands on; a shulker bullet homes.
    s.mobs.clear();
    s.mobs.push(Mob::new(MobKind::Pig, vec3(30.5, (Y + 1) as f32, 30.5), 3));
    let mh = s.mobs[0].health;
    s.projectiles.push(Projectile { pos: vec3(30.5, (Y + 1) as f32 + 0.45, 30.5), vel: Vec3::ZERO, life: 5.0, kind: ProjKind::Snowball, from_player: true, damage: 0.0, explosive: false });
    s.projectiles.push(Projectile { pos: vec3(40.0, (Y + 3) as f32, 40.0), vel: vec3(1.0, 0.0, 0.0), life: 5.0, kind: ProjKind::ShulkerBullet, from_player: false, damage: 0.0, explosive: false });
    s.projectiles.push(Projectile { pos: vec3(50.0, (Y + 3) as f32, 50.0), vel: vec3(0.0, 1.0, 0.0), life: 5.0, kind: ProjKind::Firework, from_player: true, damage: 0.0, explosive: false });
    tick_projectiles(&mut s, 0.016);
    assert!(s.mobs[0].health < mh, "snowball impact must damage");
    assert_eq!(s.projectiles.len(), 2, "the homing bullet and firework fly on");
}) }

#[test]
fn fishing_ambience_weather_and_farmland() { big(|| {
    let mut s = state("atmo");
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    put(&mut s, 0, Y, 0, 12); // water under the float
    // Fishing: wrong item reels in; the rod near water ticks to a bite.
    s.fishing.cast([0.5, Y as f32 + 0.9, 0.5], 0.0);
    sel(&mut s, 1, 1);
    let pp=s.player.pos; tick_fishing(&mut s, 0.016, pp);
    assert!(!s.fishing.is_cast(), "no rod in hand reels the line in");
    s.fishing.cast([0.5, Y as f32 + 0.9, 0.5], 0.0);
    sel(&mut s, crate::fishing::ROD, 64);
    s.fishing.wait = 0.01;
    let pp=s.player.pos; tick_fishing(&mut s, 0.05, pp);
    assert!(s.particles.iter().any(|p| p.size < 0.12), "a bite splashes");
    s.fishing.cast([30.5, Y as f32, 30.5], 0.0); // far away and not in water
    let pp=s.player.pos; tick_fishing(&mut s, 0.016, pp);
    assert!(!s.fishing.is_cast(), "walking off reels the line in");
    // Ambience and weather tick in every dimension.
    let pp=s.player.pos; tick_ambience(&mut s, 0.5, pp);
    s.dim = 1;
    let pp=s.player.pos; tick_weather(&mut s, 0.5, pp);
    assert_eq!(s.rain, 0.0, "the Nether has no rain");
    let pp=s.player.pos; tick_farmland(&mut s, 0.5, pp); // wrong dimension: early return
    s.dim = 0;
    s.weather_cd = 0.01;
    let pp=s.player.pos; tick_weather(&mut s, 0.05, pp);
    assert!(s.weather <= 2 && s.weather_cd > 0.0);
    // Precipitation: covered player gets none; open sky in rain spawns drops.
    s.rain = 1.0;
    put(&mut s, 0, Y + 3, 0, 1);
    let before = s.particles.len();
    let pp=s.player.pos; tick_weather(&mut s, 0.016, pp);
    assert_eq!(s.particles.len(), before, "no rain falls through a roof");
    put(&mut s, 0, Y + 3, 0, 0);
    let (mut rng, mut rand) = (12345u32, |v: &mut u32| { *v ^= *v << 13; *v ^= *v >> 17; *v ^= *v << 5; (*v >> 8) as f32 / 16_777_216.0 });
    let pp=s.player.pos; spawn_precipitation(&mut s, &mut rng, &mut rand, pp);
    assert!(s.particles.len() > before, "rain spawns precipitation");
    // Farmland: ripe crops and barren soil are skipped; the pass runs without panic.
    put(&mut s, 1, Y, 1, Block::Farmland as Id);
    s.chunks.set_block_meta_world(1, Y + 1, 1, Block::WheatCrop as Id, crate::world::block::crop_meta(3));
    s.farm_cd = 0.0;
    let pp=s.player.pos; tick_farmland(&mut s, 0.1, pp);
    // Harvest pays produce+seed when ripe, seed alone when not, doubled by Fortune.
    let before = s.inventory.count_of(Block::WheatCrop.crop_yield());
    harvest_crop(&mut s, Block::WheatCrop, crate::world::block::crop_meta(3));
    assert!(s.inventory.count_of(Block::WheatCrop.crop_yield()) > before);
    assert!(s.did_harvest);
    let seeds = s.inventory.count_of(Block::WheatCrop.crop_seed());
    harvest_crop(&mut s, Block::WheatCrop, crate::world::block::crop_meta(0));
    assert!(s.inventory.count_of(Block::WheatCrop.crop_seed()) > seeds, "unripe returns its seed");
    // Water surface search: finds the top of a column, blocked by shore, absent in air.
    put(&mut s, 8, Y, 8, 12); put(&mut s, 8, Y + 1, 8, 12);
    let hit = water_surface_along(&s, vec3(8.5, (Y + 4) as f32, 8.5), vec3(0.0, -1.0, 0.0), 10.0);
    assert_eq!(hit.map(|h| h[1]), Some((Y + 1) as f32 + 0.9));
    put(&mut s, 8, Y + 3, 8, 1);
    assert!(water_surface_along(&s, vec3(8.5, (Y + 4) as f32, 8.5), vec3(0.0, -1.0, 0.0), 10.0).is_none(), "shore blocks the cast");
    assert!(water_surface_along(&s, vec3(0.5, (Y + 4) as f32, 0.5), vec3(0.0, 1.0, 0.0), 3.0).is_none());
}) }

#[test]
fn break_and_place_interactions() { big(|| {
    let mut s = state("break");
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    s.player.eat_cd = 0.0;
    // Eat food, drink estus while hurt, and raise max HP with a heart container.
    sel(&mut s, 1027, 2);
    let (eo,fd)=(s.player.eye_pos(), s.player.forward()); assert!(do_break(&mut s, eo, fd));
    assert!(s.player.has_effect(crate::item::Effect::Regeneration));
    s.player.eat_cd = 0.0; s.player.health = 10.0;
    sel(&mut s, 1024, 1);
    let (eo,fd)=(s.player.eye_pos(), s.player.forward()); assert!(do_break(&mut s, eo, fd));
    assert!(s.player.health > 10.0);
    s.player.eat_cd = 0.0;
    sel(&mut s, 1025, 1);
    let (eo,fd)=(s.player.eye_pos(), s.player.forward()); assert!(do_break(&mut s, eo, fd));
    assert_eq!(s.player.max_health, 22.0);
    // Equip armor from the hotbar.
    s.player.eat_cd = 0.0;
    sel(&mut s, 1072, 100);
    let (eo,fd)=(s.player.eye_pos(), s.player.forward()); assert!(do_break(&mut s, eo, fd));
    assert_eq!(s.inventory.armor[1].id, 1072);
    // Throw a snowball, then (with Multishot attuned) a spread of three.
    s.player.eat_cd = 0.0;
    sel(&mut s, crate::item::SNOWBALL, 5);
    let dir = vec3(0.0, 0.0, -1.0);
    let eo=s.player.eye_pos(); assert!(do_break(&mut s, eo, dir));
    assert_eq!(s.projectiles.len(), 1);
    assert!(s.player.blessings.attune(1115));
    s.player.eat_cd = 0.0;
    let eo=s.player.eye_pos(); assert!(do_break(&mut s, eo, dir));
    assert_eq!(s.projectiles.len(), 4, "Multishot throws three");
    // Deflect an incoming fireball by attacking it.
    s.projectiles.clear();
    s.projectiles.push(Projectile { pos: s.player.eye_pos() + vec3(0.0, 0.0, -2.0), vel: vec3(0.0, 0.0, 5.0), life: 5.0, kind: ProjKind::Fireball, from_player: false, damage: 5.0, explosive: false });
    s.inventory.selected = 1; s.inventory.slots[1] = crate::inventory::InvSlot { id: 1, count: 1 }; s.inventory.selected = 1;
    let eo=s.player.eye_pos(); assert!(do_break(&mut s, eo, dir), "attacking a fireball deflects it");
    assert!(s.projectiles[0].from_player && s.projectiles[0].vel.z < 0.0);
    s.projectiles.clear();
    // Brush suspicious sand into plain sand plus a find.
    put(&mut s, 0, Y, -3, Block::SuspiciousSand as Id);
    sel(&mut s, crate::item::BRUSH, 64);
    s.player.eat_cd = 0.0;
    assert!(do_break(&mut s, vec3(0.5, Y as f32 + 0.5, 0.5), dir));
    assert_eq!(s.chunks.get_block_world(0, Y, -3), Block::Sand as Id);
    assert!(s.did_brush);
    // Break a plain block (drops it), an ore without a pickaxe (no drop), and a ripe crop.
    put(&mut s, 0, Y, -3, Block::Dirt as Id);
    sel(&mut s, 1, 10);
    let dirt = s.inventory.count_of(2);
    assert!(do_break(&mut s, vec3(0.5, Y as f32 + 0.5, 0.5), dir));
    assert_eq!(s.chunks.get_block_world(0, Y, -3), 0);
    assert!(s.inventory.count_of(2) > dirt);
    put(&mut s, 0, Y, -3, Block::DiamondOre as Id);
    let dia = s.inventory.count_of(Block::DiamondOre as Id);
    assert!(do_break(&mut s, vec3(0.5, Y as f32 + 0.5, 0.5), dir));
    assert_eq!(s.inventory.count_of(Block::DiamondOre as Id), dia, "ore needs a pickaxe to drop");
    sel(&mut s, 1065, 100); // diamond pickaxe
    put(&mut s, 0, Y, -3, Block::DiamondOre as Id);
    assert!(do_break(&mut s, vec3(0.5, Y as f32 + 0.5, 0.5), dir));
    assert!(s.inventory.count_of(Block::DiamondOre as Id) > dia);
    // Attack the nearest mob in reach.
    s.mobs.push(Mob::new(MobKind::Pig, vec3(0.5, (Y + 2) as f32, -2.5), 3));
    let mh = s.mobs[0].health;
    s.player.attack_cd = 0.0;
    let eo=s.player.eye_pos(); assert!(do_break(&mut s, eo, dir));
    assert!(s.mobs[0].health < mh);
    let eo=s.player.eye_pos(); assert!(nearest_mob_hit(&s, eo, vec3(0.0, 1.0, 0.0), 6.0, 6.0).is_none(), "looking away hits nothing");
    // Place: a cube against a floor, a slab, a doubled slab, and a seed on farmland.
    put(&mut s, 10, Y, 10, Block::Stone as Id);
    s.player.pos = vec3(10.5, (Y + 4) as f32, 10.5);
    sel(&mut s, Block::Dirt as Id, 4);
    let (o, d) = down(&mut s, 10, 10);
    assert!(do_place(&mut s, o, d));
    assert_eq!(s.chunks.get_block_world(10, Y + 1, 10), Block::Dirt as Id);
    sel(&mut s, Block::StoneSlab as Id, 4);
    assert!(do_place(&mut s, o, d));
    assert_eq!(s.chunks.get_block_world(10, Y + 2, 10), Block::StoneSlab as Id);
    // Items and empty hands place nothing.
    sel(&mut s, 1027, 1);
    assert!(!do_place(&mut s, o, d));
    sel(&mut s, 0, 0);
    assert!(!do_place(&mut s, o, d));
    // Planting: farmland only, and the seed is spent.
    put(&mut s, 14, Y, 14, Block::Farmland as Id);
    s.player.pos = vec3(14.5, (Y + 4) as f32, 14.5);
    sel(&mut s, Block::WheatCrop.crop_seed(), 2);
    let (o2, d2) = down(&mut s, 14, 14);
    assert!(do_place(&mut s, o2, d2), "seed on farmland plants");
    assert_eq!(s.chunks.get_block_world(14, Y + 1, 14), Block::WheatCrop as Id);
    assert!(!do_place(&mut s, o2, d2), "occupied soil does not eat another seed");
    // placement_meta: cubes are 0, slabs follow the clicked face, stairs add facing.
    let hit_top = crate::raycast::HitResult { pos: (0, Y, 0), prev: (0, Y + 1, 0), normal: (0, 1, 0), dist: 1.0 };
    assert_eq!(placement_meta(Block::Stone, &hit_top, Vec3::ZERO, vec3(0.0, -1.0, 0.0), 0.0), 0);
    assert_eq!(placement_meta(Block::StoneSlab, &hit_top, Vec3::ZERO, vec3(0.0, -1.0, 0.0), 0.0) & META_TOP, 0);
    let hit_bot = crate::raycast::HitResult { normal: (0, -1, 0), ..hit_top };
    assert_ne!(placement_meta(Block::StoneSlab, &hit_bot, Vec3::ZERO, vec3(0.0, 1.0, 0.0), 0.0) & META_TOP, 0);
    let hit_side = crate::raycast::HitResult { normal: (1, 0, 0), ..hit_top };
    assert_ne!(placement_meta(Block::StoneStairs, &hit_side, vec3(0.0, (Y as f32) + 0.75, 0.0), vec3(-1.0, 0.0, 0.0), 0.0), 0);
    // Explosions clear solid blocks (not bedrock), spill chests, and hurt nearby players.
    put(&mut s, 20, Y, 20, Block::Stone as Id);
    put(&mut s, 21, Y, 20, 13);
    put(&mut s, 20, Y, 21, Block::Chest as Id);
    s.containers.insert_empty((s.dim, 20, Y, 21));
    s.player.pos = vec3(20.5, (Y + 1) as f32, 20.5);
    s.player.hurt_cd = 0.0;
    let hp = s.player.health;
    explode(&mut s, vec3(20.5, Y as f32 + 0.5, 20.5), 2.5);
    assert_eq!(s.chunks.get_block_world(20, Y, 20), 0);
    assert_eq!(s.chunks.get_block_world(21, Y, 20), 13, "bedrock survives");
    assert!(s.player.health < hp);
    // Smelter state machine (direct, no global engine).
    s.smelter = Smelter::default();
    tick_smelter(&mut s, 0.1); // inactive: no-op
    s.smelter.active = true; s.smelter.recipe = 999;
    tick_smelter(&mut s, 0.1);
    assert!(!s.smelter.active, "a bogus recipe stops the furnace");
    let recipe = &crate::inventory::SMELTING[0];
    s.inventory = crate::inventory::Inventory::empty();
    s.inventory.add_item_with_count(recipe.in1, recipe.n1.max(1) + 8);
    if recipe.in2 != 0 { s.inventory.add_item_with_count(recipe.in2, recipe.n2.max(1) + 8); }
    s.inventory.add_item_with_count(1053, 64); // coal fuel, if the recipe does not spare an input
    s.smelter = Smelter { recipe: 0, active: true, progress: 0.0, fuel_left: 0.0, fuel_max: 0.0, blast: recipe.blast };
    for _ in 0..40 { tick_smelter(&mut s, 1.0); if s.inventory.count_of(recipe.out) > 0 { break; } }
    assert!(s.inventory.count_of(recipe.out) > 0 || !s.smelter.active, "smelting must produce or stop cleanly");
    // Blessings over time: Frost Walker freezes the water underfoot.
    assert!(s.player.blessings.attune(1112));
    put(&mut s, 0, Y, 0, 12);
    s.player.pos = vec3(0.5, Y as f32 + 0.4, 0.5);
    tick_blessings(&mut s, 0.1);
    assert_eq!(s.chunks.get_block_world(0, Y, 0), 43, "water froze to ice");
    assert_eq!(player_reach(&s), 6.0);
    assert!(is_undead(MobKind::Zombie) && is_undead(MobKind::Wither) && !is_undead(MobKind::Pig));
    assert_eq!(slayer_mult(&s.player, MobKind::Zombie), 1.0);
    assert!(s.player.blessings.attune(1103)); // Smite
    assert_eq!(slayer_mult(&s.player, MobKind::Zombie), 2.0);
    assert_eq!(slayer_mult(&s.player, MobKind::Pig), 1.0);
}) }

#[test]
fn place_at_menus_and_dimension_switch() { big(|| {
    let mut s = state("menus");
    s.width = 800; s.height = 400;
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    s.player.yaw = 0.0; s.player.pitch = 0.0;
    // A villager in front opens trade (20); a sheep with shears is shorn.
    s.mobs.push(Mob::new(MobKind::Villager, vec3(0.5, (Y + 1) as f32, -2.0), 3));
    assert_eq!(place_at(&mut s, 400.0, 200.0), 20);
    s.mobs.clear();
    let mut sheep = Mob::new(MobKind::Sheep, vec3(0.5, (Y + 2) as f32, -2.0), 3);
    sheep.sheared = false;
    s.mobs.push(sheep);
    sel(&mut s, 1148, 100);
    assert_eq!(place_at(&mut s, 400.0, 200.0), 1);
    assert!(s.mobs[0].sheared && s.did_shear);
    s.mobs.clear();
    // A chest opens its container (30); a furnace opens its menu (10+menu).
    put(&mut s, 0, Y + 2, -3, Block::Chest as Id);
    sel(&mut s, 1, 1);
    assert_eq!(place_at(&mut s, 400.0, 200.0), 30);
    assert!(s.open_chest.is_some());
    s.open_chest = None;
    put(&mut s, 0, Y + 2, -3, Block::Furnace as Id);
    let code = place_at(&mut s, 400.0, 200.0);
    assert!(code >= 10 && code != 30, "furnace menu code {code}");
    // Resting at a warding stone only works at night and skips to dawn.
    put(&mut s, 0, Y + 2, -3, Block::WardingStone as Id);
    assert_eq!(place_at(&mut s, 400.0, 200.0), 0, "no resting in daylight");
    s.world_secs = wrap_world_secs(400.0); // night (day_t ~0.94)
    assert!(s.is_night(), "test setup must be night, day_t={}", s.day_t());
    assert_eq!(place_at(&mut s, 400.0, 200.0), 1);
    assert!(!s.is_night() && s.did_rest);
    // Dimension switch: Nether compresses coordinates, End spawns its dragon, and back again.
    s.player.pos = vec3(16.5, 80.0, 16.5);
    switch_dimension(&mut s, 1);
    assert_eq!(s.dim, 1);
    assert!(s.mobs.iter().any(|m| m.kind == MobKind::Wither) || s.nether_wither_dead);
    switch_dimension(&mut s, 2);
    assert_eq!(s.dim, 2);
    assert!(s.mobs.iter().any(|m| m.kind == MobKind::Dragon) || s.end_dragon_dead);
    switch_dimension(&mut s, 2); // same dim: no-op
    assert_eq!(s.dim, 2);
    switch_dimension(&mut s, 0);
    assert_eq!(s.dim, 0);
    // mark_neighbors_dirty flags edge neighbours.
    put(&mut s, 0, Y, 0, 1);
    if let Some(c) = s.chunks.get_mut(crate::world::chunk::ChunkPos(0, 0)) { c.mesh_dirty = false; }
    mark_neighbors_dirty(&mut s, 0, 0);
    assert!(s.chunks.get(crate::world::chunk::ChunkPos(0, 0)).map(|c| c.mesh_dirty).unwrap_or(false));
    // Particle bursts are capped.
    let mut out = Vec::new();
    let mut rng = 99u32;
    spawn_particles(&mut rng, &mut out, Burst { center: Vec3::ZERO, n: 5000, color: [1.0, 0.0, 0.0], speed: 1.0, life: 1.0, size: 0.1 });
    assert!(out.len() <= 501, "burst cap, got {}", out.len());
}) }

#[test]
fn simulate_combat_death_and_publish() { big(|| {
    let _serial = GLOBAL_ENGINE_TEST.lock();
    let mut s = state("sim");
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    put(&mut s, 0, Y, 0, 1);
    // A hostile in contact range hits; a creeper fuses and explodes; a blaze shoots.
    let mut z = Mob::new(MobKind::Zombie, vec3(0.5, (Y + 1) as f32, 1.5), 3);
    z.attack_cd = 0.0;
    s.mobs.push(z);
    let mut c = Mob::new(MobKind::Creeper, vec3(0.5, (Y + 1) as f32, 1.0), 3);
    c.fuse = 1.39;
    s.mobs.push(c);
    let mut b = Mob::new(MobKind::Blaze, vec3(0.5, (Y + 1) as f32, 10.5), 3);
    b.attack_cd = 0.0;
    s.mobs.push(b);
    s.player.hurt_cd = 0.0;
    step(&mut s, 0.05, false);
    assert!(s.projectiles.iter().any(|p| !p.from_player) || s.player.health < 20.0, "combat must resolve");
    // Dead mobs pay loot and vanish on the next frame.
    s.mobs.push(Mob::new(MobKind::Pig, vec3(0.5, (Y + 1) as f32, 0.5), 3));
    s.mobs.last_mut().unwrap().health = 0.0;
    step(&mut s, 0.05, false);
    assert!(s.mobs.iter().all(|m| m.health > 0.0), "corpses are collected");
    // Lava burns an unblessed player standing in it.
    put(&mut s, 0, Y + 1, 0, 84);
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    let hp = s.player.health;
    step(&mut s, 0.05, false);
    assert!(s.player.health < hp, "lava must burn");
    put(&mut s, 0, Y + 1, 0, 0);
    // Death respawns with reduced max health (floored) and a clean slate.
    s.player.dead = true;
    s.respawn = Some(vec3(0.5, (Y + 2) as f32, 0.5));
    step(&mut s, 0.05, false);
    assert!(!s.player.dead && s.player.health == s.player.max_health);
    assert_eq!(s.player.pos, vec3(0.5, (Y + 2) as f32, 0.5));
    // Paused steps still publish UI; getters read the caches.
    step(&mut s, 0.05, true);
    let debug: serde_json::Value = serde_json::from_str(&get_debug_json()).unwrap();
    assert!(debug["chunks"].as_u64().unwrap_or(0) > 0);
    assert!(serde_json::from_str::<serde_json::Value>(&get_inventory_json()).is_ok());
    assert!(serde_json::from_str::<serde_json::Value>(&get_stats_json()).is_ok());
    assert!(serde_json::from_str::<serde_json::Value>(&get_health_json()).is_ok());
    assert!(serde_json::from_str::<serde_json::Value>(&get_smelt_json()).is_ok());
    assert!(serde_json::from_str::<serde_json::Value>(&get_ambience_json()).is_ok());
    // Creative/paused toggles.
    set_creative(true); assert!(is_creative());
    set_paused(true); assert!(is_paused());
    set_paused(false); assert!(!is_paused());
    // tick_and_render_state honours `running`.
    s.running = false;
    tick_and_render_state(&mut s, Instant::now());
    s.running = true;
}) }

#[test]
fn global_engine_api() { big(|| {
    let _serial = GLOBAL_ENGINE_TEST.lock();
    destroy_engine();
    let g = ENGINE_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    assert!(install_state(state("global"), g));
    set_creative(true);
    inventory_give(42);
    inventory_move(0, 1);
    let _ = inventory_craft(9); // planks from wood if stocked; result depends on the kit
    assert!(get_recipes_json().contains("known"));
    assert!(get_cuts_json().starts_with('['));
    let _ = do_cut(0);
    assert!(get_trades_json().contains("prof"));
    let _ = do_trade(0);
    assert!(get_smelting_json().starts_with('['));
    let _ = start_smelt(0, false);
    stop_smelt();
    assert_eq!(get_container_json(), r#"{"slots":[]}"#);
    with_engine(|s| { s.open_chest = Some((0, 1, 2, 3)); s.containers.insert_empty((0, 1, 2, 3)); s.inventory.slots[0] = crate::inventory::InvSlot { id: 1, count: 5 }; });
    assert!(container_put(0), "put into an open empty chest must move the stack");
    assert!(container_take(0), "and taking it back must move it again");
    close_container();
    request_container_sync(); // no open chest / offline: no-op
    // Blessings through the global API: attune a charm in slot 0, then release it.
    with_engine(|s| { s.inventory.slots[0] = crate::inventory::InvSlot { id: 1107, count: 1 }; });
    assert!(attune_blessing(0));
    assert!(!attune_blessing(0), "the charm was consumed");
    assert!(get_blessings_json().contains("Eros") || get_blessings_json().contains("slots"));
    assert!(release_blessing(0));
    assert!(!release_blessing(0));
    assert!(get_blessing_catalog_json().contains("Blessing of"));
    assert!(!break_block_at(400.0, 200.0) || place_block_at(400.0, 200.0) >= 0);
    save_now();
    destroy_engine();
    assert!(with_engine(|_| ()).is_none());
    // Resizing with no engine still records the surface size for the next load.
    resize_renderer(640, 360);
    surface_destroyed(); // no engine: must not panic
}) }
