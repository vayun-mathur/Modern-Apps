/// JNI bridge for `VoxelsNative#nativeInit`: starts loading the world (save directory + seed) on
/// the engine thread and returns immediately; poll `nativeLoadState` for progress.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeInit<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    files_dir: JString<'l>,
    seed: jint,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
        let dir = match get_string(env, &files_dir) {
            Some(d) => d,
            None => return Ok(false),
        };
        Ok(engine::init_engine_async(dir, seed as u32))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#nativeLoadState`: 0 idle, 1 generating, 2 meshing, 3 ready.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeLoadState<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jint {
    jint::from(engine::load_phase())
}

/// JNI bridge for `VoxelsNative#nativeLoadProgress`: meshing progress, 0..1.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeLoadProgress<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jfloat {
    engine::load_progress()
}

/// JNI bridge for `VoxelsNative#surfaceCreated`: acquires the Android native window and hands it to
/// the engine thread, which builds (or resumes) the renderer off the main thread.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_surfaceCreated<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    surface: JObject<'l>,
) {
    #[cfg(target_os = "android")]
    {
        let env_raw = env.as_raw() as *mut CVoid;
        let surf_raw = surface.as_raw() as *mut CVoid;
        let window = unsafe { vulkan::context::ANativeWindow_fromSurface(env_raw, surf_raw) };
        if window.is_null() {
            return;
        }
        unsafe { vulkan::context::ANativeWindow_acquire(window) };
        engine::surface_created(window);
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (env, surface);
    }
}

/// JNI bridge for `VoxelsNative#surfaceChanged`: forwards the new surface size
/// to the renderer.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_surfaceChanged<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    width: jint,
    height: jint,
) {
    engine::resize_renderer(width, height);
}

/// JNI bridge for `VoxelsNative#surfaceDestroyed`: releases the window, keeping the renderer's
/// device and meshes for when the surface comes back.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_surfaceDestroyed<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    engine::surface_destroyed();
}

/// JNI bridge for `VoxelsNative#nativeOnDestroy`: destroys the whole engine.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeOnDestroy<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    engine::destroy_engine();
}

/// JNI bridge for `VoxelsNative#nativeSave`: writes player, chests and edited chunks now. The
/// activity calls it from `onStop`, so a background kill loses nothing.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeSave<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    engine::save_now();
}

/// JNI bridge for `VoxelsNative#nativeSetPaused`: the pause menu opened or closed. Single-player
/// worlds stop simulating but keep rendering; held input is released.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeSetPaused<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    paused: jboolean,
) {
    engine::set_paused(paused);
}

/// JNI bridge for `VoxelsNative#nativeSetCreative`: the world's mode (creative allows flight and the
/// free catalog; survival doesn't). Call before `nativeInit`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeSetCreative<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    creative: jboolean,
) {
    engine::set_creative(creative);
}
