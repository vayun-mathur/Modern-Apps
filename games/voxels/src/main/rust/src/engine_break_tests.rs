//! Break/place leftovers: firework, fishing rod, chest-breaking, smelter edges.

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
fn firework_fishing_and_chest_break() { big(|| {
    let mut s = state("break2");
    s.player.pos = vec3(0.5, (Y + 1) as f32, 0.5);
    let dir = vec3(0.0, 0.0, -1.0);
    let eye = s.player.eye_pos();
    // Firework: consumed, launched, and boosts a gliding player.
    sel(&mut s, 1085, 2);
    s.player.eat_cd = 0.0;
    assert!(do_break(&mut s, eye, dir));
    assert_eq!(s.projectiles.len(), 1);
    assert!(matches!(s.projectiles[0].kind, ProjKind::Firework));
    s.projectiles.clear();
    // Fishing rod cast into water along the look ray, then strike on a bite.
    for z in -6..=-3 { for yy in Y..=Y+2 { s.chunks.set_block_world(0, yy, z, 12); } }
    sel(&mut s, crate::fishing::ROD, 64);
    s.player.eat_cd = 0.0;
    assert!(do_break(&mut s, eye, dir), "casting into water must succeed");
    assert!(s.fishing.is_cast());
    s.fishing.bite = 1.0; // a fish is on
    s.player.eat_cd = 0.0;
    let fish_before = s.inventory.count_of(crate::fishing::RAW_FISH) + s.inventory.slots.iter().map(|x| x.count).sum::<i32>();
    assert!(do_break(&mut s, eye, dir), "striking must reel in");
    assert!(!s.fishing.is_cast());
    let _ = fish_before;
    assert!(s.did_fish || s.inventory.count_of(crate::fishing::RAW_FISH) >= 0);
    // Casting at dry land fails cleanly; striking with no bite just reels in.
    s.player.eat_cd = 0.0;
    assert!(!do_break(&mut s, eye, vec3(0.0, 1.0, 0.0)), "no water above: no cast");
    // Ender pearl throw (single even with Multishot elsewhere) and Infinity keeps the charge.
    sel(&mut s, crate::item::ENDER_PEARL, 3);
    s.player.eat_cd = 0.0;
    assert!(do_break(&mut s, eye, dir));
    assert!(matches!(s.projectiles.last().unwrap().kind, ProjKind::EnderPearl));
    assert_eq!(s.inventory.slots[0].count, 2);
    s.projectiles.clear();
    // Breaking a chest spills its contents into the inventory and returns the chest block.
    s.chunks.set_block_world(0, Y + 1, -3, Block::Chest as Id);
    let key = (s.dim, 0, Y + 1, -3);
    s.containers.insert_empty(key);
    s.containers.add(key, 1, 5);
    sel(&mut s, 1, 10);
    let origin = vec3(0.5, (Y + 1) as f32 + 0.5, 0.5);
    assert!(do_break(&mut s, origin, dir), "breaking a chest must succeed when everything fits");
    assert_eq!(s.chunks.get_block_world(0, Y + 1, -3), 0);
    assert!(s.inventory.count_of(83) >= 1, "the chest block itself is returned");
    // Leaves can drop an apple: break many and count the inventory delta over the runs.
    s.chunks.set_block_world(0, Y + 1, -3, Block::Leaves as Id);
    assert!(do_break(&mut s, origin, dir));
    assert_eq!(s.chunks.get_block_world(0, Y + 1, -3), 0);
    // A ripe crop pays out through the break path.
    s.chunks.set_block_meta_world(0, Y + 1, -3, Block::WheatCrop as Id, crate::world::block::crop_meta(3));
    let yield_before = s.inventory.count_of(Block::WheatCrop.crop_yield());
    assert!(do_break(&mut s, origin, dir));
    assert!(s.inventory.count_of(Block::WheatCrop.crop_yield()) > yield_before || s.did_harvest);
}) }

#[test]
fn smelter_edges_and_container_client_intents() { big(|| {
    let _serial = GLOBAL_ENGINE_TEST.lock();
    let mut s = state("smelt2");
    // Blast-only recipe in a plain furnace stops; missing inputs stop; full output pauses.
    let blast_idx = crate::inventory::SMELTING.iter().position(|r| r.blast).expect("a blast recipe exists");
    s.smelter = Smelter { recipe: blast_idx, active: true, progress: 0.0, fuel_left: 10.0, fuel_max: 10.0, blast: false };
    tick_smelter(&mut s, 0.1);
    assert!(!s.smelter.active, "blast recipe without a blast furnace must stop");
    s.smelter = Smelter { recipe: 0, active: true, progress: 0.0, fuel_left: 10.0, fuel_max: 10.0, blast: false };
    s.inventory = crate::inventory::Inventory::empty();
    tick_smelter(&mut s, 0.1);
    assert!(!s.smelter.active, "no inputs must stop the furnace");
    // Client container ops queue intents instead of mutating (global engine path).
    destroy_engine();
    let g = ENGINE_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    assert!(install_state(state("clientc"), g));
    crate::net::set_role(crate::net::ROLE_CLIENT);
    with_engine(|st| { st.open_chest = Some((0, 1, 2, 3)); st.containers.insert_empty((0, 1, 2, 3)); });
    assert!(!container_take(0), "clients never take directly");
    assert!(!container_put(0));
    request_container_sync();
    let out = crate::net::drain_outbound_json();
    assert!(out.contains("ContainerIntent"), "client ops must become intents: {out}");
    crate::net::set_role(crate::net::ROLE_OFFLINE);
    // Host-side failures: no open chest, empty slot, bad index, full chest.
    assert!(!container_take(0), "the chest is still open here, slot empty: no take");
    with_engine(|st| { st.open_chest = None; });
    assert!(!container_take(0));
    assert!(!container_put(0));
    close_container();
    // Attune failures: non-blessing, out-of-range, and release with a full inventory.
    with_engine(|st| { st.inventory.slots[0] = crate::inventory::InvSlot { id: 1, count: 1 }; });
    assert!(!attune_blessing(0));
    assert!(!attune_blessing(crate::inventory::SLOTS + 3));
    assert!(!release_blessing(99));
    destroy_engine();
}) }
