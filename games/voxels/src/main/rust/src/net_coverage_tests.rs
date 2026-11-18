//! Coverage for the transport-agnostic net layer: DTO helpers, inbound
//! application for both roles, publishing, and mob interpolation.

use super::*;
use crate::engine::new_state;

fn big(f: impl FnOnce() + Send + 'static) {
    let h = std::thread::Builder::new().stack_size(64 << 20).spawn(f);
    if let Ok(h) = h { if let Err(e) = h.join() { std::panic::resume_unwind(e); } }
}
fn temp(tag: &str) -> String {
    let n = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
    let dir = std::env::temp_dir().join(format!("voxels_net_{tag}_{}_{n}", std::process::id()));
    let _ = std::fs::create_dir_all(&dir);
    dir.to_string_lossy().into_owned()
}
fn push(msg: NetMsg) {
    if let Ok(mut q) = inbox().lock() { q.push(msg); }
}
fn drain_msgs() -> Vec<NetMsg> {
    serde_json::from_str(&drain_outbound_json()).unwrap_or_default()
}

#[test]
fn proj_dto_round_trip_preserves_fields() {
    for kind in [ProjKind::Fireball, ProjKind::ShulkerBullet, ProjKind::Firework, ProjKind::Snowball, ProjKind::EnderPearl] {
        let p = Projectile { pos: Vec3::new(1.0, 2.0, 3.0), vel: Vec3::new(0.0, -1.0, 0.0), life: 2.5, kind, from_player: true, damage: 9.0, explosive: true };
        let back = ProjDto::of(&p).to_proj();
        assert!(back.kind == kind);
        assert_eq!(back.pos, p.pos);
        assert_eq!(back.vel, p.vel);
        assert_eq!(back.life, p.life);
        assert!(back.from_player);
        // Damage/explosive are host-local tuning, not wire data.
        assert_eq!(back.damage, 0.0);
        assert!(!back.explosive);
    }
}

#[test]
fn note_edited_chunk_only_when_networked_and_not_suppressed() {
    let _serial = crate::engine::GLOBAL_ENGINE_TEST.lock();
    if let Ok(mut e) = edited().lock() { e.clear(); }
    set_role(ROLE_OFFLINE);
    note_edited_chunk(3, 4);
    assert!(edited().lock().map(|e| e.is_empty()).unwrap_or(false), "offline edits must not queue");
    set_role(ROLE_HOST);
    note_edited_chunk(3, 4);
    assert!(edited().lock().map(|e| e.contains(&(3, 4))).unwrap_or(false));
    if let Ok(mut e) = edited().lock() { e.clear(); }
    set_role(ROLE_OFFLINE);
}

#[test]
fn net_stateful_flows() { big(|| {
    let _serial = crate::engine::GLOBAL_ENGINE_TEST.lock();
    let _ = drain_outbound_json();
    if let Ok(mut q) = inbox().lock() { q.clear(); }
    // Offline: apply and publish are no-ops.
    set_role(ROLE_OFFLINE);
    let mut s = new_state(temp("off"), 77, 2);
    push(NetMsg::WorldClock { world_secs: 5.0, weather: 1, weather_cd: 9.0, rain: 0.5 });
    apply_inbound(&mut s);
    assert_ne!(s.world_secs, 5.0, "offline inbound must be ignored");
    publish(&mut s);
    assert!(drain_msgs().is_empty());

    // Client applies host snapshots.
    set_role(ROLE_CLIENT);
    set_local_device("cov-client".to_string());
    let player = PlayerDto { pos: Vec3::new(8.0, 70.0, 8.0), vel: Vec3::ZERO, yaw: 0.25, pitch: 0.0, on_ground: true, sneaking: false, gliding: false, health: 20.0 };
    push(NetMsg::WorldInit { seed: 77, name: "w".into(), player: player.clone() });
    push(NetMsg::WorldClock { world_secs: 123.0, weather: 2, weather_cd: 33.0, rain: 0.9 });
    push(NetMsg::InventorySync { inv: crate::inventory::Inventory::empty().to_json() });
    push(NetMsg::InventorySync { inv: "not json".into() }); // ignored, no panic
    push(NetMsg::ProjSnapshot { projs: vec![ProjDto { kind: 3, pos: Vec3::ONE, vel: Vec3::ZERO, life: 1.0, from_player: false }] });
    push(NetMsg::ContainerSync { key: [0, 1, 64, 2], slots: vec![InvSlot { id: 5, count: 2 }] });
    push(NetMsg::BlockEdit { dim: s.dim, x: 1, y: 200, z: 1, id: 1, meta: 0 });
    push(NetMsg::BlockEdit { dim: 9, x: 1, y: 200, z: 1, id: 2, meta: 0 }); // wrong dim ignored
    push(NetMsg::MobSnapshot { mobs: vec![MobDto { kind: 5, pos: Vec3::new(2.0, 65.0, 2.0), vel: Vec3::ZERO, yaw: 0.0, health: 10.0, max_health: 20.0 }] });
    push(NetMsg::Join { device: "x".into(), name: "n".into() }); // client ignores join
    apply_inbound(&mut s);
    assert_eq!(s.world_secs, 123.0);
    assert_eq!(s.weather, 2);
    assert_eq!(s.projectiles.len(), 1);
    assert_eq!(s.containers.get((0, 1, 64, 2)).map(|v| v[0].id), Some(5));
    assert_eq!(s.chunks.get_block_world(1, 200, 1), 1);
    assert_eq!(s.mobs.len(), 1);
    // Interpolation eases toward the snapshot and wraps yaw the short way.
    s.mobs[0].pos = Vec3::ZERO;
    s.mobs[0].yaw = 3.0;
    if let Ok(mut t) = mob_targets().lock() { *t = vec![MobDto { kind: 5, pos: Vec3::new(10.0, 0.0, 0.0), vel: Vec3::new(1.0, 0.0, 0.0), yaw: -3.0, health: 7.0, max_health: 20.0 }]; }
    interpolate_mobs(&mut s, 0.05);
    assert!(s.mobs[0].pos.x > 0.0 && s.mobs[0].pos.x < 10.0);
    assert_eq!(s.mobs[0].health, 7.0);
    // A population change rebuilds the local list.
    if let Ok(mut t) = mob_targets().lock() { *t = vec![]; }
    interpolate_mobs(&mut s, 0.05);
    assert!(s.mobs.is_empty());
    // Client publish ships transform + edited chunks, no host snapshots.
    if let Ok(mut e) = edited().lock() { e.insert((0, 0)); }
    // The transform cadence is a process-global tick counter shared with other
    // publishers, so publish a full cadence window and collect what it ships.
    let _ = drain_msgs();
    let mut out = Vec::new();
    for _ in 0..3 { publish(&mut s); out.extend(drain_msgs()); }
    assert!(out.iter().any(|m| matches!(m, NetMsg::PlayerTransform { .. })), "no transform in {} msgs", out.len());
    // Host flows.
    set_role(ROLE_HOST);
    let mut h = new_state(temp("host"), 78, 2);
    h.mobs.push(Mob::new(MobKind::Pig, Vec3::new(1.0, 70.0, 1.0), 3));
    h.projectiles.push(Projectile { pos: Vec3::ZERO, vel: Vec3::ONE, life: 3.0, kind: ProjKind::Fireball, from_player: false, damage: 5.0, explosive: false });
    push(NetMsg::Join { device: "peer".into(), name: "P".into() });
    push(NetMsg::InvIntent { device: "peer".into(), from: 0, to: 1 });
    push(NetMsg::ContainerIntent { device: "peer".into(), key: [0, 5, 64, 5], take: true, idx: u32::MAX });
    apply_inbound(&mut h);
    let out = drain_msgs();
    assert!(out.iter().any(|m| matches!(m, NetMsg::WorldInit { .. })), "join must answer with WorldInit");
    assert!(out.iter().any(|m| matches!(m, NetMsg::MobSnapshot { .. })));
    assert!(out.iter().any(|m| matches!(m, NetMsg::ContainerSync { .. })));
    // Host container ops: put then take via the keyed path.
    h.containers.insert_empty((0, 5, 64, 5));
    h.inventory.slots[0] = InvSlot { id: 1, count: 10 };
    apply_container_op(&mut h, (0, 5, 64, 5), false, 0);
    assert_eq!(h.containers.get((0, 5, 64, 5)).map(|v| v.iter().map(|x| x.count).sum::<i32>()), Some(10));
    apply_container_op(&mut h, (0, 5, 64, 5), true, 0);
    // Out-of-range put and empty take are no-ops.
    apply_container_op(&mut h, (0, 5, 64, 5), false, crate::inventory::SLOTS + 5);
    apply_container_op(&mut h, (9, 0, 0, 0), true, 0);
    host_container_changed(&h, (0, 5, 64, 5));
    let out = drain_msgs();
    assert!(out.iter().any(|m| matches!(m, NetMsg::ContainerSync { .. })));
    // Host publish includes snapshots at the snapshot cadence; run enough ticks.
    for _ in 0..6 { publish(&mut h); }
    let out = drain_msgs();
    assert!(out.iter().any(|m| matches!(m, NetMsg::WorldClock { .. })), "no world clock in {} msgs", out.len());
    assert!(out.iter().any(|m| matches!(m, NetMsg::ProjSnapshot { .. })));
    // Host edit intent applies and client chunk data is accepted only for the active dim.
    push(NetMsg::EditIntent { device: "p".into(), dim: h.dim, x: 2, y: 200, z: 2, id: 9, meta: 0, break_it: false });
    push(NetMsg::EditIntent { device: "p".into(), dim: h.dim, x: 2, y: 200, z: 2, id: 0, meta: 0, break_it: true });
    apply_inbound(&mut h);
    assert_eq!(h.chunks.get_block_world(2, 200, 2), 0, "break intent must clear the block");
    set_role(ROLE_OFFLINE);
}) }
