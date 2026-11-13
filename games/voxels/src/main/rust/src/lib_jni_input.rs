/// JNI bridge for `VoxelsNative#onMoveInput`: forwards the analog stick
/// movement vector to the input state.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_onMoveInput<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    move_x: jfloat,
    move_y: jfloat,
) {
    input::set_move(move_x, move_y);
}

/// JNI bridge for `VoxelsNative#onLookInput`: forwards the look angular rates
/// to the input state.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_onLookInput<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    look_yaw_rate: jfloat,
    look_pitch_rate: jfloat,
) {
    input::set_look_rate(look_yaw_rate, look_pitch_rate);
}

/// JNI bridge for `VoxelsNative#setJump`: records whether jump is held.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_setJump<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    held: jboolean,
) {
    input::set_jump(held);
}

/// JNI bridge for `VoxelsNative#setFlyDown`: records whether fly-down is held.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_setFlyDown<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    held: jboolean,
) {
    input::set_down(held);
}

/// JNI bridge for `VoxelsNative#setSneak`: toggles sneaking.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_setSneak<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    on: jboolean,
) {
    input::set_sneak(on);
}

/// JNI bridge for `VoxelsNative#toggleFly`: requests a fly-mode toggle.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_toggleFly<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    input::request_toggle_fly();
}

/// JNI bridge for `VoxelsNative#breakBlockAt`: breaks the block targeted at
/// the given screen coordinates. Returns true if a block was broken.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_breakBlockAt<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    x: jfloat,
    y: jfloat,
) -> jboolean {
    engine::break_block_at(x, y)
}

/// JNI bridge for `VoxelsNative#placeBlockAt`: places the selected block at
/// the given screen coordinates. Returns the placed id or a negative code.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_placeBlockAt<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    x: jfloat,
    y: jfloat,
) -> jint {
    engine::place_block_at(x, y)
}

/// JNI bridge for `VoxelsNative#setLookSensitivity`: camera turn-speed multiplier from settings.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_setLookSensitivity<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    sensitivity: jfloat,
) {
    input::set_look_sensitivity(sensitivity);
}
