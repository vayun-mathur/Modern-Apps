//! yuu_avatar: glTF upper-body avatar with skeletal skinning + preset poses.
//!
//! The JNI surface mirrors the Kotlin `YuuAvatarNative` object exactly: FQNs
//! are frozen (`com.vayunmathur.games.hub.util.YuuAvatarNative`), so the
//! package exception marker on the Kotlin file covers this seam too. jni 0.22
//! API: native methods receive `EnvUnowned` and adapt via `with_env`.

pub mod avatar;
pub mod atlas;
pub mod engine;
pub mod gles;
pub mod mesh;
pub mod pose;
pub mod renderer;
pub mod traits;
pub mod vulkan;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring};
use jni::{Env, EnvUnowned};

#[cfg(target_os = "android")]
use std::os::raw::c_void as CVoid;

fn get_string(env: &Env, s: &JString) -> Option<String> {
    s.try_to_string(env).ok()
}

/// Adopt the engine state (idempotent; called once from native init).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_avatar_YuuAvatarNative_nativeInit<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jboolean {
    let _ = &mut env;
    engine::ensure_engine();
    true
}

/// Set traits + pose from JSON (`YuuFaceConfig` JSON, pose id or JSON) for a
/// surface. Phase A compat: the id is stored as `currentSurfaceId` and the
/// single renderer keeps drawing (multi-surface routing lands in Phase B).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_avatar_YuuAvatarNative_nativeSetConfig<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    surface_id: jint,
    config_json: JString<'l>,
    pose_json: JString<'l>,
) {
    engine::ensure_engine();
    let (config, pose) = env
        .with_env(|env| -> jni::errors::Result<(String, String)> {
            Ok((
                get_string(env, &config_json).unwrap_or_default(),
                get_string(env, &pose_json).unwrap_or_default(),
            ))
        })
        .resolve::<jni::errors::LogErrorAndDefault>();
    engine::set_config(surface_id, &config, &pose);
}

/// Surface ready: adopt the window, build this surface's renderer, start its
/// 60 Hz render thread. A second call with the same id (rotation) destroys
/// that surface's old renderer first and bumps its thread generation so the
/// stale thread exits instead of racing the new one. Other surfaces are
/// untouched.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_avatar_YuuAvatarNative_nativeSurfaceCreated<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    surface: JObject<'l>,
    surface_id: jint,
) {
    engine::ensure_engine();
    #[cfg(target_os = "android")]
    {
        let env_raw = env.as_raw() as *mut CVoid;
        let surf_raw = surface.as_raw() as *mut CVoid;
        let window = unsafe { vulkan::context::ANativeWindow_fromSurface(env_raw, surf_raw) };
        if window.is_null() {
            return;
        }
        unsafe { vulkan::context::ANativeWindow_acquire(window) };
        // Rotation path: tear down this surface's previous renderer before
        // adopting the new window, so at most one thread runs per surface.
        engine::destroy_renderer(surface_id);
        let generation = engine::next_generation(surface_id);
        if !unsafe { engine::create_renderer(surface_id, window, 1280, 720) } {
            unsafe { vulkan::context::ANativeWindow_release(window) };
            return;
        }
        std::thread::spawn(move || {
            let mut last = std::time::Instant::now();
            loop {
                if !engine::has_renderer(surface_id) || engine::generation(surface_id) != generation {
                    break;
                }
                engine::tick_surface(surface_id);
                let elapsed = last.elapsed();
                let target = std::time::Duration::from_millis(16);
                if elapsed < target {
                    std::thread::sleep(target - elapsed);
                }
                last = std::time::Instant::now();
                if !engine::is_running(surface_id) || engine::generation(surface_id) != generation {
                    break;
                }
            }
            engine::remove_surface(surface_id);
        });
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (env, surface, surface_id);
    }
}

/// Surface resized: recreate the swapchain at the new size.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_avatar_YuuAvatarNative_nativeSurfaceChanged<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    surface_id: jint,
    width: jint,
    height: jint,
) {
    engine::resize_renderer(surface_id, width, height);
}

/// Surface gone: destroy that surface's renderer, stop its thread.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_avatar_YuuAvatarNative_nativeSurfaceDestroyed<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    surface_id: jint,
) {
    engine::destroy_renderer(surface_id);
}

/// Pause or resume drawing (keeps the renderer alive).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_avatar_YuuAvatarNative_nativeSetPaused<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    surface_id: jint,
    paused: jboolean,
) {
    engine::set_paused(surface_id, paused != jni::sys::JNI_FALSE);
}

/// Bundled mesh handoff: read `length` bytes at `offset` from the detached
/// APK fd and store them for the next surface creation. The fd is adopted
/// (owned) here — always closed exactly once, on every path.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_avatar_YuuAvatarNative_nativeSetBundledMesh<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
) {
    let bytes = read_fd(fd, offset, length);
    // Adopt-then-close: the fd is ours now regardless of read outcome.
    close_fd(fd);
    if let Some(bytes) = bytes {
        if !bytes.is_empty() {
            engine::set_bundled_mesh(bytes);
        }
    }
}

#[cfg(target_os = "android")]
fn read_fd(fd: jint, offset: jlong, length: jlong) -> Option<Vec<u8>> {
    use std::os::unix::io::FromRawFd;
    if fd < 0 || length <= 0 || length > 64 * 1024 * 1024 {
        return None;
    }
    // Borrow (don't own): close_fd below owns the single close.
    let mut file = unsafe { std::mem::ManuallyDrop::new(std::fs::File::from_raw_fd(fd)) };
    use std::io::{Read, Seek, SeekFrom};
    file.seek(SeekFrom::Start(offset.max(0) as u64)).ok()?;
    let mut buf = vec![0u8; length as usize];
    file.read_exact(&mut buf).ok()?;
    Some(buf)
}

#[cfg(not(target_os = "android"))]
fn read_fd(_fd: jint, _offset: jlong, _length: jlong) -> Option<Vec<u8>> {
    None
}

#[cfg(target_os = "android")]
fn close_fd(fd: jint) {
    if fd >= 0 {
        unsafe {
            extern "C" {
                fn close(fd: i32) -> i32;
            }
            close(fd);
        }
    }
}

#[cfg(not(target_os = "android"))]
fn close_fd(_fd: jint) {}

/// Pose preset ids as JSON (`["idle","wave",...]`) for the gallery.
fn poses_json() -> String {
    serde_json::to_string(&pose::preset_poses().iter().map(|p| &p.id).collect::<Vec<_>>())
        .unwrap_or_else(|_| "[]".to_string())
}

#[cfg(test)]
mod lib_tests;

/// Pose preset ids as JSON (`["idle","wave",...]`) for the gallery.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_avatar_YuuAvatarNative_nativeGetPosesJson<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    let json = poses_json();
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(json) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// User drag-rotate delta for a surface (radians). Accumulates into the
/// surface's yaw/pitch; pitch clamps native-side.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_avatar_YuuAvatarNative_nativeOnRotateInput<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    surface_id: jint,
    yaw_delta: jfloat,
    pitch_delta: jfloat,
) {
    engine::on_rotate_input(surface_id, yaw_delta, pitch_delta);
}

/// Set whether a surface auto-orbits (editor disables on creation).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_avatar_YuuAvatarNative_nativeSetAutoRotate<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    surface_id: jint,
    auto_rotate: jboolean,
) {
    engine::set_auto_rotate(surface_id, auto_rotate != jni::sys::JNI_FALSE);
}

/// Activity gone: destroy everything.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_avatar_YuuAvatarNative_nativeOnDestroy<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    engine::destroy_engine();
}

#[cfg(test)]
mod tests {
    use crate::mesh::build_avatar_mesh;

    #[test]
    fn engine_smoke() {
        let mesh = build_avatar_mesh(&crate::traits::YuuTraits::default());
        assert!(!mesh.is_empty());
        let poses = crate::pose::preset_poses();
        assert!(poses.iter().any(|p| p.id == "wave"));
    }

    #[test]
    fn surfaces_are_independent() {
        let json_a = r#"{"body_height": 0.2, "face_shape": 1}"#;
        let json_b = r#"{"body_height": 0.9, "face_shape": 5}"#;
        crate::engine::set_config(11, json_a, "wave");
        crate::engine::set_config(22, json_b, "cheering");
        // Per-surface pause must not leak across surfaces.
        crate::engine::set_paused(11, true);
        assert!(crate::engine::has_renderer(11) == crate::engine::has_renderer(22));
        crate::engine::destroy_renderer(11);
        crate::engine::remove_surface(11);
        crate::engine::remove_surface(22);
    }
}


