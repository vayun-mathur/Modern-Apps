/// JNI bridge for `VoxelsNative#trade`: executes the villager trade at the
/// given index. Returns true if the trade succeeded.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_trade<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, idx: jint,
) -> jboolean {
    idx >= 0 && engine::do_trade(idx as usize)
}

/// JNI bridge for `VoxelsNative#getTradesJson`: returns the active villager
/// trade list as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getTradesJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_trades_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#nativeSetRole`: sets the multiplayer role
/// (host/client), clamped to the valid range.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeSetRole<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, role: jint,
) {
    crate::net::set_role(role.clamp(0, 2) as u8);
}

/// JNI bridge for `VoxelsNative#nativeSetDevice`: records the local device
/// id used for multiplayer identity.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeSetDevice<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>, device: JString<'l>,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        if let Some(d) = get_string(env, &device) { crate::net::set_local_device(d); }
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#netPushInbound`: queues an inbound
/// multiplayer message (JSON) for the engine to apply. Returns true if the
/// message was accepted.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_netPushInbound<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>, json: JString<'l>,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
        match get_string(env, &json) {
            Some(s) => Ok(crate::net::push_inbound_json(&s)),
            None => Ok(false),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#netDrainOutbound`: drains queued outbound
/// multiplayer messages as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_netDrainOutbound<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(crate::net::drain_outbound_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#netDrainOutboundBin`: drains queued outbound
/// multiplayer messages as a binary frame (see net_bin.rs) — chunk payloads
/// cross as raw bytes instead of JSON numbers. Local representation only;
/// Kotlin reconstructs the legacy signed-op JSON before signing/persisting.
/// Returns null on failure, mirroring the byte-array-out pattern used by the
/// e2ee-p2p JNI bridge (`byte_array_from_slice`).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_netDrainOutboundBin<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jni::sys::jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jbyteArray> {
        match env.byte_array_from_slice(&crate::net::drain_outbound_bin()) {
            Ok(a) => Ok(a.into_raw()),
            Err(_) => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#getPeersJson`: returns the known multiplayer
/// peers as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getPeersJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(crate::net::peers_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#netRequestContainer`: requests a container
/// sync from the multiplayer host.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_netRequestContainer<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>,
) {
    crate::engine::request_container_sync();
}
