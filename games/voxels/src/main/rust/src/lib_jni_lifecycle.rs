/// JNI bridge for `VoxelsNative#nativeInit`: creates the engine with the app
/// files directory and world seed. Returns true on success.
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
        Ok(engine::init_engine(dir, seed as u32))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#surfaceCreated`: attaches the Android native
/// window, creates the renderer, and starts the tick-and-render thread.
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
        let _ = engine::create_renderer(window, 1280, 720);
        std::thread::spawn(|| {
            let mut last = std::time::Instant::now();
            loop {
                let has_renderer = engine::with_engine(|s| s.renderer.is_some()).unwrap_or(false);
                if !has_renderer {
                    break;
                }
                engine::tick_and_render();
                let elapsed = last.elapsed();
                let target = std::time::Duration::from_millis(16);
                if elapsed < target {
                    std::thread::sleep(target - elapsed);
                }
                last = std::time::Instant::now();
                let running = engine::with_engine(|s| s.running).unwrap_or(false);
                if !running {
                    break;
                }
            }
        });
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

/// JNI bridge for `VoxelsNative#surfaceDestroyed`: tears down the renderer.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_surfaceDestroyed<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    engine::destroy_renderer();
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
