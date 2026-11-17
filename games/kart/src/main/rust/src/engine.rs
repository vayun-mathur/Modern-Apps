//! Engine state: the race, the renderer, and the tick that binds them.
//!
//! The Kotlin loop owns cadence (one `tick_and_render` per frame from the
//! render thread in `lib.rs`); this module owns everything inside it: advance
//! the race at a fixed 60 Hz step, rebuild the dynamic mesh, update the
//! chase camera, draw. Everything fallible returns `Option`/`Result` — the
//! JNI boundary logs and keeps the placeholder running rather than crashing.

use crate::mesh::{self, KartVertex};
use crate::physics::DriveInput;
use crate::race::Race;
use crate::renderer::Renderer;
use crate::vulkan::context::{ANativeWindow, VulkanContext};
use crate::vulkan::renderer::KartRenderer;
use std::sync::{Mutex, OnceLock};
use std::time::Instant;

/// Fixed physics step: the race advances in 60 Hz slices no matter the frame rate.
const STEP: f32 = 1.0 / 60.0;

/// Engine state, behind the global lock (same shape as voxels' `ENGINE`).
pub struct EngineState {
    /// The live race (created by `start_race`).
    pub race: Option<Race>,
    /// The renderer (created by `create_renderer`).
    pub renderer: Option<Renderer>,
    /// Static track vertex count (from the last `upload_track`).
    pub track_count: usize,
    /// Pending human inputs by seat, written by the JNI setters and consumed
    /// by the tick. Kept whole (not per-field) so a drift edge and a drive
    /// sample arriving in either order merge into one control.
    pub pending: Vec<DriveInput>,
    /// Live window handle (released on destroy).
    pub window_ptr: Option<*mut ANativeWindow>,
    /// Surface size.
    pub width: u32,
    /// Surface size.
    pub height: u32,
    /// When the engine started (for tick pacing).
    pub last_tick: Instant,
    /// Leftover fractional step time.
    pub accumulator: f32,
    /// False once the surface is gone: the render thread exits.
    pub running: bool,
}

// SAFETY: guarded by the mutex; the raw window pointer is only touched on the
// render thread between create and destroy, like voxels' `EngineState`.
unsafe impl Send for EngineState {}

static ENGINE: OnceLock<Mutex<Option<EngineState>>> = OnceLock::new();

fn engine_lock() -> &'static Mutex<Option<EngineState>> {
    ENGINE.get_or_init(|| Mutex::new(None))
}

/// Run `f` on the engine state, if it exists.
pub fn with_engine<F, R>(f: F) -> Option<R>
where
    F: FnOnce(&mut EngineState) -> R,
{
    let mut guard = engine_lock().lock().ok()?;
    let state = guard.as_mut()?;
    Some(f(state))
}

/// Start (or restart) a race with these human seat ids. Uploads the track
/// mesh when a renderer is already live.
pub fn start_race(human_ids: Vec<String>) {
    with_engine(|s| {
        let mut race = Race::new(&human_ids);
        race.cam_seat = 0;
        s.pending = vec![DriveInput::default(); race.seat_count()];
        s.race = Some(race);
        s.accumulator = 0.0;
        if let Some(r) = s.renderer.as_mut() {
            let mesh = mesh::build_track_mesh();
            s.track_count = mesh.len();
            unsafe { r.upload_track(&mesh) };
        }
    });
}

/// Grow `pending` to `n` seats (no-op when already sized). Seats never shrink
/// mid-race: a phone that joined late drives from the next race.
fn ensure_pending(s: &mut EngineState, n: usize) {
    while s.pending.len() < n {
        s.pending.push(DriveInput::default());
    }
}

/// Feed one human drive sample. Merges with (never clears) the drift/item
/// edges the setters wrote since the last tick, so arrival order is free.
pub fn on_drive_input(player: usize, throttle: f32, steering: f32, brake: f32) {
    with_engine(|s| {
        ensure_pending(s, player + 1);
        if let Some(p) = s.pending.get_mut(player) {
            p.throttle = throttle.clamp(0.0, 1.0);
            p.steering = steering.clamp(-1.0, 1.0);
            p.brake = brake.clamp(0.0, 1.0);
        }
    });
}

/// Set the drift shoulder for one seat. Sticky until the next tick consumes
/// it (the Kotlin fan-in re-sends the hold every tick while held).
pub fn set_drift(player: usize, held: bool) {
    with_engine(|s| {
        ensure_pending(s, player + 1);
        if let Some(p) = s.pending.get_mut(player) {
            p.drift = held;
        }
    });
}

/// Fire one seat's held item. Edge-triggered: the tick consumes and clears
/// it, so holding the button does not fire twice.
pub fn use_item(player: usize) {
    with_engine(|s| {
        ensure_pending(s, player + 1);
        if let Some(p) = s.pending.get_mut(player) {
            p.item = true;
        }
    });
}

/// Snapshot for the HUD/standings: JSON `[{player,lap,progress,place}]`.
pub fn standings_json() -> String {
    with_engine(|s| {
        s.race.as_ref().map(|race| {
            let standings = race.standings();
            let mut out = String::from("[");
            for (place, (id, lap, progress)) in standings.iter().enumerate() {
                if place > 0 {
                    out.push(',');
                }
                out.push_str(&format!(
                    "{{\"player\":\"{}\",\"lap\":{},\"progress\":{:.4},\"place\":{}}}",
                    escape(id),
                    lap,
                    progress,
                    place + 1
                ));
            }
            out.push(']');
            out
        })
    })
    .flatten()
    .unwrap_or_else(|| "[]".into())
}

fn escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

/// Create the renderer for a live window. Tries Vulkan first, then GLES;
/// fails cleanly (None) — the Kotlin side keeps the placeholder when this
/// returns false. The winning backend is logged to logcat (`kart_engine` tag)
/// so device triage can see which path engaged.
///
/// Backend order is by pointer width: 64-bit tries Vulkan first (drivers are
/// universal there); 32-bit tries GLES first. Rationale: 32-bit Google TV
/// boxes ship a `libvulkan.so` loader stub in `/system/lib` with no driver
/// behind it (`/vendor/lib/vulkan*` absent), and `Entry::load` + a stubbed
/// `create_instance` SIGSEGVs inside the stub instead of returning an error
/// — which no `Result` can catch. GLES-first on 32-bit never touches the
/// stub unless GLES itself fails.
///
/// # Safety
/// `window` must be a live `ANativeWindow` adopted from the JNI surface (the
/// caller acquired it via `ANativeWindow_acquire`); ownership moves here.
pub unsafe fn create_renderer(window: *mut ANativeWindow, width: i32, height: i32) -> bool {
    with_engine(|state| {
        state.window_ptr = Some(window);
        state.width = width.max(1) as u32;
        state.height = height.max(1) as u32;
        let mut renderer = match unsafe {
            Renderer::select(window, width, height, state.width, state.height)
        } {
            Ok(r) => r,
            Err(e) => {
                log_backend(&e);
                return false;
            }
        };
        // A race may already be live (surface recreated on rotation): upload.
        if state.race.is_some() {
            let mesh = mesh::build_track_mesh();
            state.track_count = mesh.len();
            unsafe { renderer.upload_track(&mesh) };
        }
        state.renderer = Some(renderer);
        state.running = true;
        true
    })
    .unwrap_or(false)
}

/// Try the Vulkan path, returning the error string on failure (for the
/// logcat fallback line). Ownership of `window` stays with the engine either
/// way — the caller releases it when creation fails overall.
///
/// # Safety
/// Same contract as [`create_renderer`].
pub(crate) unsafe fn try_vulkan(
    window: *mut ANativeWindow,
    width: i32,
    height: i32,
    state_width: u32,
    state_height: u32,
) -> Result<Renderer, String> {
    let ctx = match unsafe { VulkanContext::new(window, width, height) } {
        Ok(c) => c,
        Err(e) => return Err(format!("context: {e}")),
    };
    match unsafe { KartRenderer::new(ctx, state_width, state_height) } {
        Ok(r) => Ok(Renderer::Vulkan(Box::new(r))),
        Err(e) => Err(format!("renderer: {e}")),
    }
}

/// One logcat line naming the winning backend (the `kart_engine` tag the
/// plan's triage filters on). `__android_log_print` on device; `eprintln` on
/// host builds and tests so the selection stays visible off-device.
pub(crate) fn log_backend(msg: &str) {
    #[cfg(target_os = "android")]
    unsafe {
        use std::ffi::CString;
        use std::os::raw::{c_char, c_int};
        extern "C" {
            fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
        }
        // ANDROID_LOG_INFO = 4.
        if let (Ok(tag), Ok(line), Ok(fmt)) = (
            CString::new("kart_engine"),
            CString::new(format!("backend: {msg}")),
            CString::new("%s"),
        ) {
            __android_log_print(4, tag.as_ptr(), fmt.as_ptr(), line.as_ptr());
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        eprintln!("kart_engine backend: {msg}");
    }
}

/// Resize: Vulkan recreates the swapchain at the new size; GLES re-creates
/// the EGL surface on the same context (GL objects survive, no re-upload).
/// If recreation fails the renderer is dropped and the next frame re-creates
/// it (placeholder covers).
pub fn resize_renderer(width: i32, height: i32) {
    with_engine(|state| {
        state.width = width.max(1) as u32;
        state.height = height.max(1) as u32;
        if let Some(mut r) = state.renderer.take() {
            let window = match state.window_ptr {
                Some(w) => w,
                None => {
                    unsafe { r.destroy() };
                    return;
                }
            };
            match &mut r {
                Renderer::Gles(g) => {
                    match unsafe { g.resize(window, state.width, state.height) } {
                        Ok(()) => {
                            state.renderer = Some(r);
                        }
                        Err(_) => {
                            unsafe { r.destroy() };
                        }
                    }
                    return;
                }
                Renderer::Vulkan(_) => {}
            }
            let ctx = match unsafe { VulkanContext::new(window, width, height) } {
                Ok(c) => c,
                Err(_) => {
                    unsafe { r.destroy() };
                    return;
                }
            };
            match unsafe { KartRenderer::new(ctx, state.width, state.height) } {
                Ok(fresh) => {
                    let mut fresh = Renderer::Vulkan(Box::new(fresh));
                    let mesh = mesh::build_track_mesh();
                    state.track_count = mesh.len();
                    unsafe { fresh.upload_track(&mesh) };
                    state.renderer = Some(fresh);
                    unsafe { r.destroy() };
                }
                Err(_) => {
                    state.renderer = Some(r);
                }
            }
        }
    });
}

/// Destroy the renderer (surface gone). The race survives: rotation
/// re-creates the surface and `create_renderer` re-uploads.
pub fn destroy_renderer() {
    with_engine(|state| {
        if let Some(mut r) = state.renderer.take() {
            unsafe { r.destroy() };
            state.running = false;
        }
        if let Some(win) = state.window_ptr.take() {
            unsafe { crate::vulkan::context::ANativeWindow_release(win) };
        }
    });
}

/// Destroy everything (activity gone).
pub fn destroy_engine() {
    let mut guard = match engine_lock().lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    if let Some(mut state) = guard.take() {
        if let Some(mut r) = state.renderer.take() {
            unsafe { r.destroy() };
            // The context owns instance/device; release them too (GLES: no-op).
            r.destroy_context();
        }
        if let Some(win) = state.window_ptr.take() {
            unsafe { crate::vulkan::context::ANativeWindow_release(win) };
        }
    }
}

/// One frame: step the race at fixed 60 Hz slices, rebuild the dynamic mesh,
/// update the chase camera, draw. Called from the render thread.
pub fn tick_and_render() {
    with_engine(|s| {
        let now = Instant::now();
        let frame_dt = now
            .duration_since(s.last_tick)
            .as_secs_f32()
            .clamp(0.0, 0.25);
        s.last_tick = now;
        let renderer = match s.renderer.as_mut() {
            Some(r) => r,
            None => return,
        };
        let race = match s.race.as_mut() {
            Some(r) => r,
            None => return,
        };
        // Fixed-step the sim so physics never depends on frame rate.
        s.accumulator += frame_dt;
        let mut steps = 0;
        while s.accumulator >= STEP && steps < 5 {
            // Publish the pending human samples, then clear the item edges
            // (drift stays sticky: the Kotlin side re-sends the hold).
            for (seat, input) in s.pending.iter().enumerate() {
                race.set_input(seat, *input);
            }
            for p in s.pending.iter_mut() {
                p.item = false;
            }
            race.tick(STEP);
            s.accumulator -= STEP;
            steps += 1;
        }
        // Dynamic mesh: karts + shells + boxes.
        let mut dynamic: Vec<KartVertex> = Vec::new();
        for (seat, (x, z, yaw, ai)) in race.kart_transforms().iter().enumerate() {
            mesh::build_kart_mesh(
                &mut dynamic,
                mesh::KartMeshSpec {
                    x: *x,
                    z: *z,
                    yaw: *yaw,
                    seat,
                    ai: *ai,
                    spin: 0.0,
                    boost: false,
                },
            );
        }
        for (x, z) in race.shell_positions() {
            mesh::build_shell_mesh(&mut dynamic, x, z, race.ticks);
        }
        for (x, z) in race.box_positions() {
            mesh::build_box_mesh(&mut dynamic, x, z, race.ticks);
        }
        // Chase camera → view-projection.
        let (cam_pos, look) = race.camera();
        let view_proj = view_proj_for(
            cam_pos,
            look,
            renderer.width(),
            renderer.height(),
            renderer.pre_rotation(),
        );
        let track_count = s.track_count;
        let _ = unsafe { renderer.draw(view_proj, cam_pos, track_count, &dynamic) };
    });
}

/// View-projection for the chase camera: look-at + perspective + surface
/// pre-rotation. Aspect swaps on ROTATE_90/270 panels.
pub fn view_proj_for(
    cam_pos: [f32; 3],
    look: [f32; 3],
    width: u32,
    height: u32,
    pre_rotation: (bool, f32),
) -> [[f32; 4]; 4] {
    use glam::{Mat4, Vec3};
    let (swap_aspect, rot) = pre_rotation;
    let (w, h) = if swap_aspect {
        (height.max(1) as f32, width.max(1) as f32)
    } else {
        (width.max(1) as f32, height.max(1) as f32)
    };
    let view = Mat4::look_at_rh(Vec3::from(cam_pos), Vec3::from(look), Vec3::Y);
    let proj = Mat4::perspective_rh(70.0_f32.to_radians(), w / h.max(1.0), 0.1, 800.0);
    let rot_m = Mat4::from_rotation_z(rot);
    (rot_m * proj * view).to_cols_array_2d()
}

/// Create the engine state if absent (called once from JNI init).
pub fn ensure_engine() {
    let mut guard = match engine_lock().lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    if guard.is_none() {
        *guard = Some(EngineState {
            race: None,
            renderer: None,
            track_count: 0,
            pending: Vec::new(),
            window_ptr: None,
            width: 0,
            height: 0,
            last_tick: Instant::now(),
            accumulator: 0.0,
            running: false,
        });
    }
}

/// Whether the render thread should keep running.
pub fn is_running() -> bool {
    with_engine(|s| s.running).unwrap_or(false)
}

/// Whether a renderer is live.
pub fn has_renderer() -> bool {
    with_engine(|s| s.renderer.is_some()).unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn camera_matrix_is_finite() {
        let m = view_proj_for([0.0, 4.0, 20.0], [0.0, 1.0, 0.0], 1280, 720, (false, 0.0));
        for row in &m {
            for v in row {
                assert!(v.is_finite());
            }
        }
    }

    #[test]
    fn race_flows_without_renderer() {
        ensure_engine();
        start_race(vec!["p1".to_string()]);
        on_drive_input(0, 1.0, 0.0, 0.0);
        with_engine(|s| {
            let race = s.race.as_mut().unwrap();
            for _ in 0..600 {
                race.tick(STEP);
            }
        });
        let json = standings_json();
        assert!(json.starts_with('[') && json.contains("p1"));
    }
}
