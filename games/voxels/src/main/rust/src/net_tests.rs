//! Wire-format tests: DTO conversions and the serde shape of every
//! `NetMsg` variant. Global-queue tests live in one sequential test so
//! they cannot interleave with each other.

use super::*;

#[test]
fn projectile_cells_round_trip_and_unknown_is_fireball() {
    for kind in [ProjKind::Fireball, ProjKind::ShulkerBullet, ProjKind::Firework, ProjKind::Snowball, ProjKind::EnderPearl] {
        assert!(proj_from_cell(proj_cell(kind)) == kind);
    }
    assert!(proj_from_cell(99) == ProjKind::Fireball, "unknown cells fall back, never panic");
    assert!(proj_from_cell(u32::MAX) == ProjKind::Fireball);
}

#[test]
fn mob_and_player_dtos_preserve_their_fields() {
    let mob = Mob::new(MobKind::Zombie, Vec3::new(1.0, 64.0, -3.0), 7);
    let dto = MobDto::of(&mob);
    assert_eq!(dto.kind, MobKind::Zombie.cell());
    let back = dto.to_mob();
    assert_eq!(back.kind, MobKind::Zombie);
    assert_eq!(back.pos, mob.pos);
    assert_eq!(back.health, mob.health);
    assert_eq!(back.max_health, mob.max_health);
    // Every mob kind survives the cell round-trip.
    for kind in MobKind::ALL {
        let m = Mob::new(kind, Vec3::ZERO, 1);
        assert_eq!(MobDto::of(&m).to_mob().kind, kind, "{kind:?} lost in the DTO");
    }
    assert_eq!(MobKind::from_cell(999), MobKind::Pig, "corrupt cells degrade to a pig");

    let player = Player::new(2.0, 65.0, 4.0);
    let pdto = PlayerDto::of(&player);
    assert_eq!(pdto.pos, player.pos);
    assert_eq!(pdto.health, player.health);
    assert!(!pdto.on_ground && !pdto.sneaking && !pdto.gliding);
}

#[test]
fn every_netmsg_variant_survives_json() {
    let player = PlayerDto { pos: Vec3::new(1.0, 2.0, 3.0), vel: Vec3::ZERO, yaw: 0.5, pitch: -0.2, on_ground: true, sneaking: false, gliding: false, health: 20.0 };
    let msgs: Vec<NetMsg> = vec![
        NetMsg::Join { device: "d1".into(), name: "Bob".into() },
        NetMsg::WorldInit { seed: 42, name: "world".into(), player: player.clone() },
        NetMsg::ChunkData { dim: 1, cx: -2, cz: 3, data: vec![0, 1, 2, 255] },
        NetMsg::BlockEdit { dim: 0, x: 3, y: 64, z: -7, id: 12, meta: 5 },
        NetMsg::PlayerTransform { device: "d1".into(), player },
        NetMsg::MobSnapshot { mobs: vec![MobDto { kind: 5, pos: Vec3::ONE, vel: Vec3::ZERO, yaw: 1.0, health: 10.0, max_health: 20.0 }] },
        NetMsg::ProjSnapshot { projs: vec![ProjDto { kind: 3, pos: Vec3::ZERO, vel: Vec3::ONE, life: 2.5, from_player: true }] },
        NetMsg::InventorySync { inv: "{}".into() },
        NetMsg::ContainerSync { key: [0, 1, 2, 3], slots: vec![InvSlot { id: 5, count: 2 }] },
        NetMsg::WorldClock { world_secs: 123.5, weather: 1, weather_cd: 40.0, rain: 0.8 },
        NetMsg::EditIntent { device: "d1".into(), dim: 0, x: 1, y: 2, z: 3, id: 9, meta: 0, break_it: true },
        NetMsg::InvIntent { device: "d1".into(), from: 3, to: 9 },
        NetMsg::ContainerIntent { device: "d1".into(), key: [1, 4, 5, 6], take: false, idx: u32::MAX },
    ];
    for msg in &msgs {
        let s = serde_json::to_string(msg).unwrap();
        assert!(s.contains("\"type\":\""), "messages are internally tagged: {s}");
        let back: NetMsg = serde_json::from_str(&s).unwrap();
        // Re-serialising the parsed copy must reproduce the wire bytes.
        assert_eq!(serde_json::to_string(&back).unwrap(), s, "{msg:?} changed on the wire");
    }
    // Spot-check the fields that routing depends on.
    let edit: NetMsg = serde_json::from_str(r#"{"type":"BlockEdit","dim":0,"x":3,"y":64,"z":-7,"id":12,"meta":0}"#).unwrap();
    assert!(matches!(edit, NetMsg::BlockEdit { x: 3, z: -7, id: 12, .. }));
    let sync: NetMsg = serde_json::from_str(r#"{"type":"ContainerIntent","device":"d","key":[1,4,5,6],"take":true,"idx":4294967295}"#).unwrap();
    assert!(matches!(sync, NetMsg::ContainerIntent { idx: u32::MAX, take: true, .. }), "u32::MAX is the sync-me sentinel");
}

#[test]
fn inbound_json_rejects_garbage_without_panicking() {
    let _serial = crate::engine::GLOBAL_ENGINE_TEST.lock();
    assert!(!push_inbound_json("not json"));
    assert!(!push_inbound_json(r#"{"type":"NoSuchVariant"}"#));
    assert!(!push_inbound_json(""));
    assert!(push_inbound_json("[]"), "an empty batch is well-formed");
}

#[test]
fn net_globals_move_messages_end_to_end() {
    // Role, device, outbox and the remote player table are process-global, so
    // hold the shared test lock used by every other global-state test while
    // mutating them (test threads run in parallel).
    let _serial = crate::engine::GLOBAL_ENGINE_TEST.lock();
    let _ = drain_outbound_json(); // drop anything a parallel suite queued
    set_role(ROLE_HOST);
    assert!(is_networked() && is_host() && !is_client());
    set_local_device("net-test-device".to_string());
    client_inv_intent(3, 5);
    client_container_intent([0, 1, 2, 3], true, 7);
    let drained: Vec<NetMsg> = serde_json::from_str(&drain_outbound_json()).unwrap();
    assert!(drained.iter().any(|m| matches!(m, NetMsg::InvIntent { device, from: 3, to: 5 } if device == "net-test-device")),
        "the inv intent was not queued: {drained:?}");
    assert!(drained.iter().any(|m| matches!(m, NetMsg::ContainerIntent { take: true, idx: 7, .. })));
    assert_eq!(drain_outbound_json(), "[]", "draining empties the outbox");

    upsert_remote("peer-a".to_string(), Vec3::new(9.0, 64.0, 9.0), 1.25);
    upsert_remote(String::new(), Vec3::ZERO, 0.0); // anonymous poses are dropped
    upsert_remote("net-test-device".to_string(), Vec3::ZERO, 0.0); // and so are our own
    let poses = remote_player_poses();
    assert!(poses.iter().any(|(p, y)| *p == Vec3::new(9.0, 64.0, 9.0) && *y == 1.25));
    assert!(!poses.iter().any(|(p, _)| *p == Vec3::ZERO), "self/anonymous poses leaked in");
    let peers: Vec<serde_json::Value> = serde_json::from_str(&peers_json()).unwrap();
    assert!(peers.iter().any(|p| p["device"] == "peer-a" && p["x"] == 9.0));
    set_role(ROLE_OFFLINE);
    assert!(!is_networked());
}
