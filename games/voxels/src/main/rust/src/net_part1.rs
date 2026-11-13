// Host: a client just joined — send world identity, every loaded chunk, and a first full snapshot.
fn on_join(state: &mut EngineState) {
    enqueue(NetMsg::WorldInit { seed: state.seed, name: String::new(), player: PlayerDto::of(&state.player) });
    let dim = state.dim;
    let chunks: Vec<(i32, i32, Vec<u8>)> = state.chunks.chunks_iter()
        .map(|(pos, chunk)| (pos.0, pos.1, crate::world::save::encode_chunk(chunk)))
        .collect();
    for (cx, cz, data) in chunks {
        enqueue(NetMsg::ChunkData { dim, cx, cz, data });
    }
    enqueue(NetMsg::MobSnapshot { mobs: state.mobs.iter().map(MobDto::of).collect() });
    enqueue(NetMsg::InventorySync { inv: state.inventory.to_json() });
    enqueue(world_clock_msg(state));
}

fn world_clock_msg(state: &EngineState) -> NetMsg {
    let world_secs = state.world_secs;
    NetMsg::WorldClock { world_secs, weather: state.weather, weather_cd: state.weather_cd, rain: state.rain }
}

// ---- Outbound: enqueue what we owe the network, at the end of each tick ----

pub fn publish(state: &mut EngineState) {
    if !is_networked() { return; }
    let tick = PUBLISH_TICK.fetch_add(1, Ordering::SeqCst);

    // Local player transform (both roles), so peers can draw our avatar.
    if tick % TRANSFORM_EVERY == 0 {
        enqueue(NetMsg::PlayerTransform { device: local_device(), player: PlayerDto::of(&state.player) });
    }

    // Ship edited chunks (host: authoritative broadcast; client: upstream to host).
    let dirty: Vec<(i32, i32)> = edited().lock().map(|mut e| e.drain().collect()).unwrap_or_default();
    if !dirty.is_empty() {
        let dim = state.dim;
        for (cx, cz) in dirty {
            if let Some(chunk) = state.chunks.get(ChunkPos(cx, cz)) {
                enqueue(NetMsg::ChunkData { dim, cx, cz, data: crate::world::save::encode_chunk(chunk) });
            }
        }
    }

    // Host-only authoritative snapshots at a reduced rate.
    if role() == ROLE_HOST && tick % SNAPSHOT_EVERY == 0 {
        enqueue(NetMsg::MobSnapshot { mobs: state.mobs.iter().map(MobDto::of).collect() });
        if !state.projectiles.is_empty() {
            enqueue(NetMsg::ProjSnapshot { projs: state.projectiles.iter().map(ProjDto::of).collect() });
        }
        enqueue(world_clock_msg(state));
        enqueue(NetMsg::InventorySync { inv: state.inventory.to_json() });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn netmsg_roundtrips() {
        let msgs = vec![
            NetMsg::Join { device: "d1".into(), name: "Bob".into() },
            NetMsg::BlockEdit { dim: 0, x: 3, y: 64, z: -7, id: 12, meta: 0 },
            NetMsg::PlayerTransform {
                device: "d1".into(),
                player: PlayerDto { pos: Vec3::new(1.0, 2.0, 3.0), vel: Vec3::ZERO, yaw: 0.5, pitch: -0.2, on_ground: true, sneaking: false, gliding: false, health: 20.0 },
            },
            NetMsg::WorldClock { world_secs: 123.5, weather: 1, weather_cd: 40.0, rain: 0.8 },
        ];
        let s = serde_json::to_string(&msgs).unwrap();
        let back: Vec<NetMsg> = serde_json::from_str(&s).unwrap();
        assert_eq!(back.len(), msgs.len());
        // Spot-check a discriminant survived the round-trip.
        assert!(matches!(back[1], NetMsg::BlockEdit { x: 3, z: -7, id: 12, .. }));
    }

    #[test]
    fn push_and_drain() {
        set_role(ROLE_CLIENT);
        assert!(push_inbound_json(r#"{"type":"MobSnapshot","mobs":[]}"#));
        let drained: Vec<NetMsg> = inbox().lock().map(|mut q| std::mem::take(&mut *q)).unwrap();
        assert_eq!(drained.len(), 1);
        set_role(ROLE_OFFLINE);
    }
}
