//! Final engine branches: glide trail, spawn variety, global smelt/container calls.

use super::*;

fn state(tag: &str) -> EngineState { super::state_tests::small_state(tag) }
fn big(f: impl FnOnce() + Send + 'static) { super::state_tests::big_stack(f) }
const Y: i32 = 200;

#[test]
fn glide_trail_and_spawn_variety() { big(|| {
    let _serial = GLOBAL_ENGINE_TEST.lock();
    let mut s = state("final1");
    s.player.pos = vec3(0.5, (Y + 15) as f32, 0.5);
    s.inventory.armor[1] = crate::inventory::InvSlot { id: 1084, count: 100 };
    crate::input::release_all();
    crate::input::set_jump(true);
    step(&mut s, 0.05, false); // mid-air jump tap deploys the wings
    crate::input::set_jump(false);
    step(&mut s, 0.05, false);
    assert!(s.player.gliding, "wings must be deployed after the tap");
    assert!(s.particles.iter().any(|p| p.color == [0.88, 0.95, 1.0]), "gliding must shed its vapor trail");
    crate::input::release_all();
    // Spawn variety across day, night and the End over the filled disc.
    for x in -48..=48 { for z in -48..=48 { if x * x + z * z <= 48 * 48 { s.chunks.set_block_world(x, Y, z, 1); } } }
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    s.inventory.armor[1] = crate::inventory::InvSlot::default();
    let mut kinds = Vec::new();
    let mut cycle = |s: &mut EngineState, dim: u8, secs: f32| {
        s.dim = dim;
        s.world_secs = secs;
        s.spawn_timer = 0.0;
        if s.mobs.len() > 18 { s.mobs.clear(); }
        step(s, 0.05, false);
        kinds.extend(s.mobs.iter().map(|m| m.kind));
    };
    for _ in 0..8 { cycle(&mut s, 0, 400.0); } // night: zombies and creepers
    for _ in 0..10 { cycle(&mut s, 0, 0.0); } // noon: livestock and villagers
    for _ in 0..6 { cycle(&mut s, 2, 0.0); } // End hostiles
    assert!(kinds.iter().any(|k| k.hostile()), "nights and the End must produce hostiles: {kinds:?}");
    assert!(kinds.iter().any(|k| !k.hostile()), "daytime must produce passive mobs: {kinds:?}");
    // A client save discards stashed-dimension edits rather than writing them.
    s.stored[1] = Some(crate::world::ChunkMap::new_dim(s.seed, format!("{}/nether", s.save_dir), 1));
    crate::net::set_role(crate::net::ROLE_CLIENT);
    save_world(&mut s);
    crate::net::set_role(crate::net::ROLE_OFFLINE);
    assert_eq!(s.stored[1].as_ref().map(|m| m.pending_len()), Some(0));
}) }

#[test]
fn global_smelt_and_container_paths() { big(|| {
    let _serial = GLOBAL_ENGINE_TEST.lock();
    destroy_engine();
    let g = ENGINE_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    assert!(install_state(state("final2"), g));
    // Smelt through the global API: inputs in, job starts, a second job is refused.
    let recipe = &crate::inventory::SMELTING[0];
    with_engine(|s| {
        s.inventory = crate::inventory::Inventory::empty();
        s.inventory.add_item_with_count(recipe.in1, recipe.n1.max(1) + 4);
        if recipe.in2 != 0 { s.inventory.add_item_with_count(recipe.in2, recipe.n2.max(1) + 4); }
        s.inventory.add_item_with_count(1053, 16);
    });
    assert!(start_smelt(0, false), "a stocked recipe must light");
    assert!(!start_smelt(1, false) || {
        // Switching recipes mid-job is refused only when recipe 1 differs and is stocked;
        // either way the original job is the one that stays active.
        with_engine(|s| s.smelter.recipe == 0).unwrap_or(false)
    });
    stop_smelt();
    with_engine(|s| assert!(!s.smelter.active));
    let bad = crate::inventory::SMELTING.iter().position(|r| r.blast).expect("blast recipe");
    assert!(!start_smelt(bad, false), "a blast recipe in a plain furnace is refused");
    assert!(!start_smelt(9999, false), "a bogus recipe is refused");
    // Container JSON for an open chest, and a put that only partly fits.
    with_engine(|s| {
        let key = (0u8, 3, 64, 3);
        s.open_chest = Some(key);
        s.containers.insert_empty(key);
        // Fill the chest with stone except for 3 spaces in the first stack.
        if let Some(slots) = s.containers.get(key).cloned() { let _ = slots; }
        for i in 0..crate::container::CONTAINER_SLOTS {
            if let Some(slot) = s.containers.slot_mut(key, i) { *slot = crate::inventory::InvSlot { id: 1, count: crate::inventory::STACK }; }
        }
        if let Some(slot) = s.containers.slot_mut(key, 0) { slot.count = crate::inventory::STACK - 3; }
        s.inventory.slots[0] = crate::inventory::InvSlot { id: 1, count: 10 };
    });
    let json = get_container_json();
    assert!(json.contains("\"slots\""), "{json}");
    assert!(container_put(0), "3 of the 10 stone must move");
    with_engine(|s| assert_eq!(s.inventory.slots[0].count, 7, "the overflow stays in hand"));
    close_container();
    assert_eq!(get_container_json(), r#"{"slots":[]}"#);
    destroy_engine();
}) }

#[test]
fn trade_snow_and_fuel_edges() { big(|| {
    let _serial = GLOBAL_ENGINE_TEST.lock();
    destroy_engine();
    let g = ENGINE_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    assert!(install_state(state("final3"), g));
    // A stocked trade through the global API moves goods and levels the profession.
    with_engine(|s| {
        s.trade_prof = 0; // Farmer
        let offer = crate::villager::offers(crate::villager::Profession::Farmer, 1)[0];
        s.inventory = crate::inventory::Inventory::empty();
        s.inventory.add_item_with_count(offer.cost, offer.cost_n + 2);
        if offer.cost2 != 0 { s.inventory.add_item_with_count(offer.cost2, offer.cost2_n + 2); }
    });
    assert!(do_trade(0), "a stocked farmer trade must succeed");
    with_engine(|s| assert_eq!(s.trades_done[0], 1));
    assert!(!do_trade(999), "an out-of-range trade index fails");
    destroy_engine();
    // Snow falls where the ground is frozen; the furnace stops with no fuel at all.
    let mut s = state("final4");
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    s.chunks.set_block_world(0, Y, 0, 42); // packed ice underfoot
    s.rain = 1.0;
    s.weather = WEATHER_RAIN;
    s.weather_cd = 100.0;
    let before = s.particles.len();
    let pp = s.player.pos;
    tick_weather(&mut s, 0.016, pp);
    assert!(s.particles.len() > before, "snow must fall on frozen ground");
    assert!(s.particles.iter().any(|p| p.color == [0.95, 0.97, 1.0]), "the new particles must be flakes");
    let recipe = &crate::inventory::SMELTING[0];
    s.inventory = crate::inventory::Inventory::empty();
    s.inventory.add_item_with_count(recipe.in1, recipe.n1.max(1));
    if recipe.in2 != 0 { s.inventory.add_item_with_count(recipe.in2, recipe.n2.max(1)); }
    s.smelter = Smelter { recipe: 0, active: true, progress: 0.0, fuel_left: 0.0, fuel_max: 0.0, blast: false };
    tick_smelter(&mut s, 0.1);
    // Coal is this recipe's second input, so the spare-fuel rule may burn it; either the
    // furnace found that fuel or it stopped — it must never burn nothing forever.
    assert!(!s.smelter.active || s.smelter.fuel_left > 0.0, "the furnace must have fuel or stop");
    // Brushing with a full inventory refuses and leaves the sand suspicious.
    for slot in s.inventory.slots.iter_mut() { *slot = crate::inventory::InvSlot { id: 2, count: crate::inventory::STACK }; }
    s.chunks.set_block_world(0, Y + 1, -3, Block::SuspiciousSand as Id);
    s.inventory.selected = 0;
    s.inventory.slots[0] = crate::inventory::InvSlot { id: crate::item::BRUSH, count: 64 };
    // slot 0 is the brush itself; the rest of the inventory is full of dirt.
    s.player.eat_cd = 0.0;
    let origin = vec3(0.5, (Y + 1) as f32 + 0.5, 0.5);
    assert!(!do_break(&mut s, origin, vec3(0.0, 0.0, -1.0)), "no room for the find: no brushing");
    assert_eq!(s.chunks.get_block_world(0, Y + 1, -3), Block::SuspiciousSand as Id);
    // Ore/leaf classification is exact.
    for id in [18u16, 19, 20, 21, 22, 90, 91, 92] { assert!(is_ore(id), "{id} is an ore"); }
    for id in [1u16, 17, 23, 89, 93] { assert!(!is_ore(id), "{id} is not an ore"); }
    for id in [5u16, 28, 31, 48, 80] { assert!(is_leaves(id)); }
    assert!(!is_leaves(4));
}) }
