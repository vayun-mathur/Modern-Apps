//! kart_engine: Sunset Oval racer — track, physics, items, AI, Vulkan.
//!
//! The JNI surface mirrors the Kotlin `KartNative` object exactly: FQNs are
//! frozen (`com.vayunmathur.games.kart.util.KartNative`), so the package
//! exception marker on the Kotlin file covers this seam too. jni 0.22 API:
//! native methods receive `EnvUnowned` and adapt via `with_env`.

pub mod engine;
pub mod gles;
pub mod items;
pub mod mesh;
pub mod physics;
pub mod race;
pub mod renderer;
pub mod track;
pub mod vulkan;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jstring};
use jni::{Env, EnvUnowned};

#[cfg(target_os = "android")]
use std::os::raw::c_void as CVoid;

fn get_string(env: &Env, s: &JString) -> Option<String> {
    s.try_to_string(env).ok()
}

/// Parse the comma-separated human seat ids from the JNI start-race call:
/// trims whitespace and drops empty entries (trailing commas, blank csv).
fn parse_player_ids(csv: &str) -> Vec<String> {
    csv.split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .collect()
}

#[cfg(test)]
mod lib_tests;

/// Adopt the engine state (idempotent; called once from `KartNative` init).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_kart_util_KartNative_nativeInit<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jboolean {
    let _ = &mut env;
    engine::ensure_engine();
    true
}

/// Start (or restart) a race. `player_ids_csv` is the comma-separated human
/// seat ids in grid order; AI fills the rest of the grid.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_kart_util_KartNative_nativeStartRace<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    player_ids_csv: JString<'l>,
) {
    engine::ensure_engine();
    let ids: Vec<String> = env
        .with_env(|env| -> jni::errors::Result<Vec<String>> {
            match get_string(env, &player_ids_csv) {
                Some(csv) => Ok(parse_player_ids(&csv)),
                None => Ok(Vec::new()),
            }
        })
        .resolve::<jni::errors::LogErrorAndDefault>();
    engine::start_race(ids);
}

/// One player's drive sample for this tick (throttle/steering/brake
/// normalized; drift and item arrive via their own setters and merge).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_kart_util_KartNative_nativeOnDriveInput<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    player: jint,
    throttle: jfloat,
    steering: jfloat,
    brake: jfloat,
) {
    if player >= 0 {
        engine::on_drive_input(player as usize, throttle, steering, brake);
    }
}

/// Drift shoulder held (sticky until the tick consumes the frame).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_kart_util_KartNative_nativeSetDrift<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    player: jint,
    held: jboolean,
) {
    if player >= 0 {
        engine::set_drift(player as usize, held != jni::sys::JNI_FALSE);
    }
}

/// Fire the held item (edge-triggered).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_kart_util_KartNative_nativeUseItem<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    player: jint,
) {
    if player >= 0 {
        engine::use_item(player as usize);
    }
}

/// Snapshot for the HUD/standings: JSON `[{player,lap,progress,place}]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_kart_util_KartNative_nativeGetStandingsJson<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::standings_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Surface ready: adopt the window, build the renderer, start the 60 Hz
/// render thread. A second call (rotation) rebuilds against the new surface.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_kart_util_KartNative_nativeSurfaceCreated<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    surface: JObject<'l>,
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
        if !unsafe { engine::create_renderer(window, 1280, 720) } {
            unsafe { vulkan::context::ANativeWindow_release(window) };
            return;
        }
        std::thread::spawn(|| {
            let mut last = std::time::Instant::now();
            loop {
                if !engine::has_renderer() {
                    break;
                }
                engine::tick_and_render();
                let elapsed = last.elapsed();
                let target = std::time::Duration::from_millis(16);
                if elapsed < target {
                    std::thread::sleep(target - elapsed);
                }
                last = std::time::Instant::now();
                if !engine::is_running() {
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

/// Surface resized: recreate the swapchain at the new size.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_kart_util_KartNative_nativeSurfaceChanged<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    width: jint,
    height: jint,
) {
    engine::resize_renderer(width, height);
}

/// Surface gone: destroy the renderer, stop the thread. The race survives.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_kart_util_KartNative_nativeSurfaceDestroyed<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    engine::destroy_renderer();
}

/// Activity gone: destroy everything.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_kart_util_KartNative_nativeOnDestroy<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    engine::destroy_engine();
}

#[cfg(test)]
mod tests {
    use crate::mesh::build_track_mesh;
    use crate::race::Race;

    #[test]
    fn engine_smoke() {
        let mesh = build_track_mesh();
        assert!(!mesh.is_empty());
        let mut race = Race::new(&["p1".to_string()]);
        race.tick(1.0 / 60.0);
        assert!(!race.standings().is_empty());
    }
}
