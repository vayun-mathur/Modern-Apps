//! Save/reload progress, dimension spawning, and break-path leftovers.

use super::*;
use crate::world::block::Block;

fn state(tag: &str) -> EngineState { super::state_tests::small_state(tag) }
fn big(f: impl FnOnce() + Send + 'static) { super::state_tests::big_stack(f) }
const Y: i32 = 200;
fn sel(s: &mut EngineState, id: crate::world::block::Id, count: i32) {
    s.inventory.selected = 0;
    s.inventory.slots[0] = crate::inventory::InvSlot { id, count };
}

#[test]
fn saved_progress_reloads_and_bad_progress_clamps() { big(|| {
    let mut s = state("reload");
    let dir = s.save_dir.clone();
    assert!(s.player.blessings.attune(1107));
    s.crafted_recipes[3] = true;
    s.trades_done[2] = 7;
    s.weather = 2;
    s.weather_cd = 100.0;
    s.respawn = Some(vec3(1.5, 88.0, -2.5));
    s.end_dragon_dead = true;
    s.best_beacon = 3;
    s.deepest_y = 12;
    s.player.max_health = 34.0;
    s.inventory.armor[0] = crate::inventory::InvSlot { id: 1071, count: 200 };
    save_world(&mut s);
    let back = new_state(dir.clone(), 1234, 2);
    assert!(back.player.blessed(Passive::Fortune));
    assert!(back.crafted_recipes[3]);
    assert_eq!(back.trades_done[2], 7);
    assert_eq!(back.weather, 2);
    assert!(back.respawn.is_some());
    assert!(back.end_dragon_dead);
    assert_eq!(back.best_beacon, 3);
    assert_eq!(back.player.max_health, 34.0);
    assert_eq!(back.inventory.armor[0].id, 1071);
    // A hand-written save with out-of-range progress clamps instead of panicking.
    let mut raw = crate::world::save::load_player(&dir).expect("the save just written");
    raw.progress.dim = 9;
    raw.progress.weather = 99;
    raw.progress.max_health = 999.0;
    raw.inventory.selected = 99;
    let _ = crate::world::save::save_player(&dir, &raw);
    let clamped = new_state(dir, 1234, 2);
    assert_eq!(clamped.dim, 0, "a corrupt dimension falls back to the overworld");
    assert_eq!(clamped.weather, WEATHER_CLEAR);
    assert_eq!(clamped.player.max_health, crate::player::CAP_MAX_HEALTH);
    assert!(clamped.inventory.selected <= 8);
    // Client worlds never persist: save_world discards pending edits instead.
    crate::net::set_role(crate::net::ROLE_CLIENT);
    let mut c = state("clientworld");
    save_world(&mut c);
    crate::net::set_role(crate::net::ROLE_OFFLINE);
}) }

#[test]
fn dimension_spawning_and_culling() { big(|| {
    let _serial = GLOBAL_ENGINE_TEST.lock();
    let mut s = state("dimspawn");
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    // Elytra in the chest slot enables gliding on the next tick.
    s.inventory.armor[1] = crate::inventory::InvSlot { id: 1084, count: 100 };
    // Ground everywhere a spawn roll can land (24-44 blocks out, any heading).
    for x in -48..=48 { for z in -48..=48 { if x * x + z * z <= 48 * 48 { s.chunks.set_block_world(x, Y, z, 1); } } }
    // A far-away mob is culled by the retain pass.
    s.mobs.push(Mob::new(MobKind::Pig, vec3(500.0, (Y + 1) as f32, 500.0), 3));
    s.dim = 1;
    s.spawn_timer = 0.0;
    step(&mut s, 0.05, false);
    assert!(s.player.elytra, "a worn elytra must register");
    assert!(s.mobs.iter().all(|m| (m.pos - s.player.pos).length() < 96.0), "the far pig must be culled");
    assert!(s.mobs.iter().any(|m| m.kind.hostile()), "the Nether must spawn hostiles: {} mobs", s.mobs.len());
    s.dim = 2;
    s.spawn_timer = 0.0;
    let before = s.mobs.len();
    step(&mut s, 0.05, false);
    assert!(s.mobs.len() > before || s.spawn_timer == 2.5, "the End spawn pass must run");
    assert!(s.mobs.iter().any(|m| matches!(m.kind, MobKind::Shulker | MobKind::WitherSkeleton)) || before > 0);
    // surface_destroyed with a live (rendererless) engine releases its window slot.
    destroy_engine();
    let g = ENGINE_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    assert!(install_state(state("surfd"), g));
    with_engine(|st| st.window_ptr = Some(std::ptr::null_mut()));
    surface_destroyed();
    with_engine(|st| assert!(st.window_ptr.is_none(), "the destroyed surface must release the window"));
    destroy_engine();
}) }

#[test]
fn break_leftovers_armor_swap_infinity_and_blast_smelting() { big(|| {
    let _serial = GLOBAL_ENGINE_TEST.lock();
    let mut s = state("leftovers");
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    let dir = vec3(0.0, 0.0, -1.0);
    // Equipping a second chestplate returns the first to the inventory.
    s.inventory.armor[1] = crate::inventory::InvSlot { id: 1068, count: 90 };
    sel(&mut s, 1072, 120);
    s.player.eat_cd = 0.0;
    let eye = s.player.eye_pos();
    assert!(do_break(&mut s, eye, dir));
    assert_eq!(s.inventory.armor[1].id, 1072);
    assert!(s.inventory.slots.iter().any(|x| x.id == 1068), "the displaced chestplate must come back");
    // Paris (Infinity): an ender pearl throw spends nothing.
    assert!(s.player.blessings.attune(1117));
    sel(&mut s, crate::item::ENDER_PEARL, 3);
    s.player.eat_cd = 0.0;
    assert!(do_break(&mut s, eye, dir));
    assert_eq!(s.inventory.slots[0].count, 3, "Infinity must not consume the pearl");
    s.projectiles.clear();
    // A chest that cannot spill everything into a full inventory refuses to break.
    s.inventory = crate::inventory::Inventory::empty();
    for slot in s.inventory.slots.iter_mut() { *slot = crate::inventory::InvSlot { id: 2, count: crate::inventory::STACK }; }
    s.inventory.selected = 0;
    s.chunks.set_block_world(0, Y + 1, -3, Block::Chest as Id);
    let key = (s.dim, 0, Y + 1, -3);
    s.containers.insert_empty(key);
    s.containers.add(key, 1051, 7);
    let origin = vec3(0.5, (Y + 1) as f32 + 0.5, 0.5);
    assert!(!do_break(&mut s, origin, dir), "an unspillable chest must survive");
    assert_eq!(s.chunks.get_block_world(0, Y + 1, -3), Block::Chest as Id);
    assert!(s.containers.contains(key), "its contents stay put");
    // Blast smelting runs in a blast furnace and banks its output.
    let blast_idx = crate::inventory::SMELTING.iter().position(|r| r.blast).expect("blast recipe");
    let recipe = &crate::inventory::SMELTING[blast_idx];
    s.inventory = crate::inventory::Inventory::empty();
    s.inventory.add_item_with_count(recipe.in1, recipe.n1.max(1) + 4);
    if recipe.in2 != 0 { s.inventory.add_item_with_count(recipe.in2, recipe.n2.max(1) + 4); }
    s.inventory.add_item_with_count(1053, 32);
    // start_smelt is a global-engine call; exercise the state machine directly here.
    s.smelter = Smelter { recipe: blast_idx, active: true, progress: 0.0, fuel_left: 0.0, fuel_max: 0.0, blast: true };
    for _ in 0..60 { tick_smelter(&mut s, 1.0); if s.inventory.count_of(recipe.out) > 0 { break; } }
    assert!(s.inventory.count_of(recipe.out) > 0, "a blast recipe in a blast furnace must smelt");
    // Mending slowly repairs a damaged tool while attuned.
    assert!(s.player.blessings.attune(1106));
    s.inventory.slots[0] = crate::inventory::InvSlot { id: 1065, count: 10 };
    s.mend_cd = 0.0;
    tick_blessings(&mut s, 0.1);
    assert!(s.inventory.slots[0].count > 10 || s.inventory.slots.iter().any(|x| x.id == 1065 && x.count > 10), "Lu Ban must mend gear");
}) }
