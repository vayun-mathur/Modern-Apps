//! Farmland growth, slab completion, smelter pause, and input-driven simulation.

use super::*;
use crate::world::block::Block;

fn state(tag: &str) -> EngineState { super::state_tests::small_state(tag) }
fn big(f: impl FnOnce() + Send + 'static) { super::state_tests::big_stack(f) }
const Y: i32 = 200;

#[test]
fn crops_grow_on_farmland_and_smelter_pauses_when_full() { big(|| {
    let mut s = state("farm2");
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    // A field filling the whole sampling window, so a growth pass must land on crops.
    for x in -24..=24 { for z in -24..=24 {
        s.chunks.set_block_world(x, Y, z, Block::Farmland as Id);
        s.chunks.set_block_meta_world(x, Y + 1, z, Block::WheatCrop as Id, crate::world::block::crop_meta(0));
    }}
    s.farm_cd = 0.0;
    let pp = s.player.pos; tick_farmland(&mut s, 0.1, pp);
    let mut grown = 0;
    for x in -24..=24 { for z in -24..=24 {
        if crate::world::block::crop_stage(s.chunks.get_meta_world(x, Y + 1, z)) > 0 { grown += 1; }
    }}
    assert!(grown > 0, "a growth pass over a full field must advance some crops");
    // The next pass is throttled by the farm interval.
    let cd = s.farm_cd;
    let pp = s.player.pos; tick_farmland(&mut s, 0.1, pp);
    assert!(s.farm_cd < cd, "the interval must count down without growing early");
    // Smelter: output with nowhere to go pauses instead of stopping or eating inputs.
    let recipe = &crate::inventory::SMELTING[0];
    s.inventory = crate::inventory::Inventory::empty();
    for slot in s.inventory.slots.iter_mut() { *slot = crate::inventory::InvSlot { id: 1, count: crate::inventory::STACK }; }
    // Carve out exactly the input/fuel slots from the full inventory.
    s.inventory.slots[0] = crate::inventory::InvSlot { id: recipe.in1, count: recipe.n1.max(1) };
    s.inventory.slots[1] = crate::inventory::InvSlot { id: 1053, count: 8 };
    if recipe.in2 != 0 { s.inventory.slots[2] = crate::inventory::InvSlot { id: recipe.in2, count: recipe.n2.max(1) }; }
    // Ensure the output genuinely has no room (it is not stone, the filler).
    assert_ne!(recipe.out, 1);
    if !s.inventory.has_room_for(recipe.out, recipe.out_n) {
        s.smelter = Smelter { recipe: 0, active: true, progress: 3.0, fuel_left: 5.0, fuel_max: 5.0, blast: false };
        tick_smelter(&mut s, 0.1);
        assert!(s.smelter.active && s.smelter.progress == 3.0, "a blocked furnace must pause, not consume");
    }
}) }

#[test]
fn slab_completion_and_input_driven_step() { big(|| {
    let _serial = GLOBAL_ENGINE_TEST.lock();
    let mut s = state("extra2");
    // Two matching slabs in one cell complete the parent cube.
    s.chunks.set_block_world(10, Y, 10, Block::Stone as Id);
    s.player.pos = vec3(10.5, (Y + 4) as f32, 10.5);
    s.inventory.selected = 0;
    s.inventory.slots[0] = crate::inventory::InvSlot { id: Block::StoneSlab as Id, count: 4 };
    let from_above = (vec3(10.5, (Y + 5) as f32, 10.5), vec3(0.0, -1.0, 0.0));
    assert!(do_place(&mut s, from_above.0, from_above.1), "first slab places on the floor");
    assert_eq!(s.chunks.get_block_world(10, Y + 1, 10), Block::StoneSlab as Id);
    assert!(do_place(&mut s, from_above.0, from_above.1), "second slab completes the cube");
    assert_eq!(s.chunks.get_block_world(10, Y + 1, 10), Block::Stone as Id, "two slabs make the parent block");
    // A placed chest starts empty (registered in the container store).
    s.inventory.slots[0] = crate::inventory::InvSlot { id: Block::Chest as Id, count: 2 };
    assert!(do_place(&mut s, from_above.0, from_above.1), "chest places on the completed cube");
    let placed = s.chunks.get_block_world(10, Y + 2, 10);
    assert_eq!(placed, Block::Chest as Id);
    assert!(s.containers.contains((s.dim, 10, Y + 2, 10)), "a placed chest is registered empty");
    // Input: look deltas turn the player, held movement sprints at full tilt, fly toggles in creative.
    set_creative(true);
    crate::input::release_all();
    crate::input::add_look_delta(0.5, -0.25);
    crate::input::set_move(0.0, 1.0);
    crate::input::request_toggle_fly();
    let (yaw0, pos0) = (s.player.yaw, s.player.pos);
    step(&mut s, 0.05, false);
    assert!(s.player.yaw != yaw0, "swipe-look must turn the player");
    assert!(s.player.flying, "creative fly toggle must engage");
    assert!(s.player.pos != pos0, "held movement must move the player");
    crate::input::release_all();
    set_creative(false);
    // Refusing to place into the player's own body.
    s.player.flying = false;
    s.player.pos = vec3(10.5, (Y + 2) as f32, 10.5);
    s.inventory.slots[0] = crate::inventory::InvSlot { id: Block::Stone as Id, count: 4 };
    let into_self = (vec3(10.5, (Y + 5) as f32, 10.5), vec3(0.0, -1.0, 0.0));
    // The cell directly under the ray is the chest at Y+2; placing against its top puts a cube
    // where the player stands, which must be refused.
    assert!(!do_place(&mut s, into_self.0, into_self.1) || s.chunks.get_block_world(10, Y + 3, 10) != Block::Stone as Id);
}) }
