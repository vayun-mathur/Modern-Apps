/// JNI bridge for `VoxelsNative#selectSlot`: selects a hotbar slot, clamped
/// to the valid range.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_selectSlot<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    slot: jint,
) {
    engine::with_engine(|s| {
        s.inventory.select(slot.clamp(0, 8) as usize);
    });
}

/// JNI bridge for `VoxelsNative#moveItem`: moves an inventory stack from one
/// slot to another.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_moveItem<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, from: jint, to: jint,
) {
    if from >= 0 && to >= 0 { engine::inventory_move(from as usize, to as usize); }
}

/// JNI bridge for `VoxelsNative#giveBlock`: gives the player one of the given
/// block id.
///
/// The only id that crosses JNI as a number rather than as JSON. `as` would
/// wrap a jint that doesn't fit into a different, real block, so refuse it
/// instead.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_giveBlock<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, id: jint,
) {
    if let Ok(id) = crate::world::block::Id::try_from(id) { if id > 0 { engine::inventory_give(id); } }
}

/// JNI bridge for `VoxelsNative#craft`: crafts the given recipe index.
/// Returns true if crafting succeeded.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_craft<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, recipe: jint,
) -> jboolean {
    recipe >= 0 && engine::inventory_craft(recipe as usize)
}

/// JNI bridge for `VoxelsNative#startSmelt`: starts the given furnace recipe,
/// optionally in blast-furnace mode. Returns true if smelting started.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_startSmelt<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, recipe: jint, blast: jboolean,
) -> jboolean {
    engine::start_smelt(recipe.max(0) as usize, blast)
}

/// JNI bridge for `VoxelsNative#stopSmelt`: stops the active furnace recipe.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_stopSmelt<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>,
) {
    engine::stop_smelt();
}

/// JNI bridge for `VoxelsNative#containerTake`: takes the stack at the given
/// open-container slot into the player inventory.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_containerTake<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, idx: jint,
) -> jboolean {
    engine::container_take(idx.max(0) as usize) as jboolean
}

/// JNI bridge for `VoxelsNative#containerPut`: puts the carried stack into the
/// given open-container slot.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_containerPut<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, idx: jint,
) -> jboolean {
    engine::container_put(idx.max(0) as usize) as jboolean
}

/// JNI bridge for `VoxelsNative#closeContainer`: closes the open container.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_closeContainer<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>,
) {
    engine::close_container();
}

/// JNI bridge for `VoxelsNative#attuneBlessing`: attunes the blessing at the
/// given catalog index. Returns true if attunement succeeded.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_attuneBlessing<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, idx: jint,
) -> jboolean {
    engine::attune_blessing(idx.max(0) as usize) as jboolean
}

/// JNI bridge for `VoxelsNative#releaseBlessing`: releases the blessing in the
/// given attunement slot. Returns true if a blessing was released.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_releaseBlessing<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, slot: jint,
) -> jboolean {
    engine::release_blessing(slot.max(0) as usize) as jboolean
}

/// JNI bridge for `VoxelsNative#cut`: applies the stonecutter recipe at the
/// given index. Returns true if cutting succeeded.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_cut<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, idx: jint,
) -> jboolean {
    idx >= 0 && engine::do_cut(idx as usize)
}
