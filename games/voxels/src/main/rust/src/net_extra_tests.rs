//! Host-side inbound chunk/container/edit application.

use super::*;

fn big(f: impl FnOnce() + Send + 'static) {
    let h = std::thread::Builder::new().stack_size(64 << 20).spawn(f);
    if let Ok(h) = h { if let Err(e) = h.join() { std::panic::resume_unwind(e); } }
}
fn temp(tag: &str) -> String {
    let n = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
    let dir = std::env::temp_dir().join(format!("voxels_netx_{tag}_{}_{n}", std::process::id()));
    let _ = std::fs::create_dir_all(&dir);
    dir.to_string_lossy().into_owned()
}
fn push(msg: NetMsg) { if let Ok(mut q) = inbox().lock() { q.push(msg); } }

#[test]
fn host_applies_chunk_container_and_place_intents() { big(|| {
    let _serial = crate::engine::GLOBAL_ENGINE_TEST.lock();
    let _ = drain_outbound_json();
    if let Ok(mut q) = inbox().lock() { q.clear(); }
    set_role(ROLE_HOST);
    let mut s = crate::engine::new_state(temp("hostx"), 55, 2);
    // Place (not break) an edit intent.
    push(NetMsg::EditIntent { device: "p".into(), dim: s.dim, x: 20, y: 200, z: 20, id: 9, meta: 0, break_it: false });
    // A real chunk payload from the host's own chunk, and a corrupt one.
    let data = s.chunks.get(ChunkPos(0, 0)).map(|c| crate::world::save::encode_chunk(c)).unwrap_or_default();
    push(NetMsg::ChunkData { dim: s.dim, cx: 0, cz: 0, data });
    push(NetMsg::ChunkData { dim: s.dim, cx: 1, cz: 1, data: vec![0xFF, 0x00, 0x13] });
    // A mutating container intent (put path through the inbox).
    s.containers.insert_empty((0, 7, 64, 7));
    s.inventory.slots[2] = InvSlot { id: 1, count: 4 };
    push(NetMsg::ContainerIntent { device: "p".into(), key: [0, 7, 64, 7], take: false, idx: 2 });
    apply_inbound(&mut s);
    assert_eq!(s.chunks.get_block_world(20, 200, 20), 9, "place intent must write the block");
    assert!(edited().lock().map(|e| e.contains(&(0, 0))).unwrap_or(false), "applied chunks are re-marked for broadcast");
    if let Ok(mut e) = edited().lock() { e.clear(); }
    // A direct player-transform frame upserts the remote player table.
    push(NetMsg::PlayerTransform { device: "peer-z".into(), player: PlayerDto { pos: Vec3::new(4.0, 65.0, 4.0), vel: Vec3::ZERO, yaw: 2.0, pitch: 0.0, on_ground: true, sneaking: false, gliding: false, health: 20.0 } });
    apply_inbound(&mut s);
    assert!(remote_player_poses().iter().any(|(p, y)| *p == Vec3::new(4.0, 65.0, 4.0) && *y == 2.0));
    // A container put that only partly fits leaves the remainder in the source slot.
    s.containers.insert_empty((0, 8, 64, 8));
    for i in 0..crate::container::CONTAINER_SLOTS {
        if let Some(slot) = s.containers.slot_mut((0, 8, 64, 8), i) { *slot = InvSlot { id: 1, count: crate::inventory::STACK }; }
    }
    if let Some(slot) = s.containers.slot_mut((0, 8, 64, 8), 0) { slot.count = crate::inventory::STACK - 2; }
    s.inventory.slots[3] = InvSlot { id: 1, count: 9 };
    apply_container_op(&mut s, (0, 8, 64, 8), false, 3);
    assert_eq!(s.inventory.slots[3].count, 7, "only what fits may leave the hand");
    // The same chunk data as a client: applied locally, not re-marked.
    set_role(ROLE_CLIENT);
    let mut c = crate::engine::new_state(temp("clientx"), 56, 2);
    let data = c.chunks.get(ChunkPos(0, 0)).map(|ch| crate::world::save::encode_chunk(ch)).unwrap_or_default();
    push(NetMsg::ChunkData { dim: c.dim, cx: 0, cz: 0, data });
    push(NetMsg::ChunkData { dim: 9, cx: 0, cz: 0, data: vec![] }); // wrong dim: ignored
    apply_inbound(&mut c);
    set_role(ROLE_OFFLINE);
}) }
