// ---- Engine lifecycle: background world loading, one engine thread, surface loss and return ----
//
// `init_engine_async` returns at once; a single engine thread per session generates the world off
// the main thread, installs it, then ticks and renders. Android surfaces come and go
// (`surfaceCreated` / `surfaceDestroyed` on every Home/lock), so the window is handed over through
// PENDING_WINDOW and the engine thread attaches it: the first time by building the renderer, later
// by `VulkanRenderer::resume`, which keeps the device and every chunk mesh and only rebuilds the
// swapchain. A generation counter retires the thread (and any in-flight world load) when the session
// is destroyed, so a quick destroy/create can never leave two threads ticking one world.

/// Load phases reported to the UI's loading overlay.
pub const LOAD_IDLE: u8 = 0;
/// Generating / reading chunks around the player.
pub const LOAD_GENERATING: u8 = 1;
/// World loaded; meshing the area around the player.
pub const LOAD_MESHING: u8 = 2;
/// The area around the player is drawn: hide the overlay.
pub const LOAD_READY: u8 = 3;

static LOAD_PHASE: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(LOAD_IDLE);
static LOAD_PERMILLE: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
// Bumped by every init and every destroy. Each engine thread remembers the value it was started
// with and exits as soon as it changes.
static ENGINE_GEN: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
// Last surface size reported by surfaceChanged, kept outside the engine so a size that arrives while
// the world is still loading isn't lost (taps are mapped to rays with it).
static SURFACE_SIZE: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// A native window handed from the UI thread to the engine thread.
struct PendingWindow(*mut ANativeWindow);
// The pointer is an acquired ANativeWindow reference; ownership moves with the value.
unsafe impl Send for PendingWindow {}
static PENDING_WINDOW: OnceLock<Mutex<Option<PendingWindow>>> = OnceLock::new();
fn pending_window() -> &'static Mutex<Option<PendingWindow>> { PENDING_WINDOW.get_or_init(|| Mutex::new(None)) }

/// Chunks around the player that must be meshed before the loading overlay lifts.
const READY_RADIUS: i32 = 4;
/// Chunk meshes built per frame while the loading overlay is up / during normal play.
const MESH_BUDGET_LOADING: usize = 48;
const MESH_BUDGET_PLAY: usize = 4;
/// Section uploads per frame while loading / during normal play.
const UPLOAD_BUDGET_LOADING: usize = 128;
const UPLOAD_BUDGET_PLAY: usize = 12;
/// Engine-thread frame pacing.
const FRAME: std::time::Duration = std::time::Duration::from_millis(16);
const IDLE_FRAME: std::time::Duration = std::time::Duration::from_millis(33);
/// World generation runs a few frames deep with 130 KB chunks on the stack; give it room.
const ENGINE_STACK: usize = 32 << 20;

/// Current load phase (one of the LOAD_* constants).
pub fn load_phase() -> u8 { LOAD_PHASE.load(Ordering::SeqCst) }
/// Meshing progress, 0..1, for the loading overlay.
pub fn load_progress() -> f32 { LOAD_PERMILLE.load(Ordering::SeqCst) as f32 / 1000.0 }

/// Starts loading the world on the engine thread and returns immediately (JNI `nativeInit`). The
/// thread then keeps ticking and rendering until `destroy_engine`.
pub fn init_engine_async(files_dir: String, seed: u32) -> bool {
    if engine_lock().lock().map(|g| g.is_some()).unwrap_or(false) { return true; }
    let gen = ENGINE_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    LOAD_PHASE.store(LOAD_GENERATING, Ordering::SeqCst);
    LOAD_PERMILLE.store(0, Ordering::SeqCst);
    std::thread::Builder::new()
        .name("voxels-engine".into())
        .stack_size(ENGINE_STACK)
        .spawn(move || {
            let state = new_state(files_dir, seed, DEFAULT_RENDER_DISTANCE);
            if !install_state(state, gen) { return; }
            engine_loop(gen);
        })
        .is_ok()
}

/// Installs a freshly loaded world unless the session was destroyed (or superseded) meanwhile.
fn install_state(mut state: EngineState, gen: u64) -> bool {
    let Ok(mut guard) = engine_lock().lock() else { return false; };
    if ENGINE_GEN.load(Ordering::SeqCst) != gen || guard.is_some() { return false; }
    let (w, h) = unpack_size(SURFACE_SIZE.load(Ordering::SeqCst));
    if w > 0 { state.width = w; state.height = h; }
    *guard = Some(state);
    LOAD_PHASE.store(LOAD_MESHING, Ordering::SeqCst);
    true
}

fn pack_size(w: u32, h: u32) -> u64 { (u64::from(w) << 32) | u64::from(h) }
fn unpack_size(v: u64) -> (u32, u32) { ((v >> 32) as u32, (v & 0xFFFF_FFFF) as u32) }

/// The one engine thread: attach windows as they arrive, tick + render while a surface is shown,
/// idle (without advancing time) while backgrounded, exit when the session ends.
fn engine_loop(gen: u64) {
    loop {
        if ENGINE_GEN.load(Ordering::SeqCst) != gen { break; }
        let started = Instant::now();
        let alive = with_engine(|state| {
            attach_pending_window(state);
            let drawing = state.renderer.as_ref().is_some_and(|r| !r.suspended);
            if drawing && state.running {
                tick_and_render_state(state, started);
            } else {
                // Backgrounded: hold the clock so the first frame back doesn't see a huge dt.
                state.last_tick = started;
            }
            drawing
        });
        let Some(drawing) = alive else { break; };
        let frame = if drawing { FRAME } else { IDLE_FRAME };
        let spent = started.elapsed();
        if spent < frame { std::thread::sleep(frame - spent); }
    }
}

/// Takes a window handed over by `surfaceCreated` and binds it: resume the kept renderer when there
/// is one, otherwise build a renderer and queue the world for (budgeted, nearest-first) meshing.
fn attach_pending_window(state: &mut EngineState) {
    let Some(PendingWindow(window)) = pending_window().lock().ok().and_then(|mut p| p.take()) else { return; };
    let (w, h) = if state.width > 0 { (state.width, state.height) } else { (1280, 720) };
    if let Some(r) = state.renderer.as_mut() {
        if unsafe { r.resume(window, w, h) }.is_ok() {
            state.window_ptr = Some(window);
            state.needs_resize = false;
            return;
        }
        // The device can't present to the new window: start over with a fresh renderer.
        let mut old = state.renderer.take();
        if let Some(r) = old.as_mut() { unsafe { r.destroy(); r.ctx.destroy(); } }
    }
    let built = unsafe { VulkanContext::new(window, w as i32, h as i32) }
        .and_then(|ctx| unsafe { VulkanRenderer::new(ctx, w, h) });
    match built {
        Ok(r) => {
            state.renderer = Some(r);
            state.window_ptr = Some(window);
            state.width = w; state.height = h;
            for pos in state.chunks.chunks_iter().map(|(p, _)| *p).collect::<Vec<_>>() {
                if let Some(ch) = state.chunks.get_mut(pos) { ch.mesh_dirty = true; }
            }
            LOAD_PHASE.store(LOAD_MESHING, Ordering::SeqCst);
        }
        Err(_) => unsafe { crate::vulkan::context::ANativeWindow_release(window); },
    }
}

/// JNI `surfaceCreated`: hand the (already acquired) window to the engine thread. Returns at once;
/// all Vulkan work happens on the engine thread.
pub fn surface_created(window: *mut ANativeWindow) {
    if let Ok(mut p) = pending_window().lock() {
        if let Some(PendingWindow(old)) = p.replace(PendingWindow(window)) {
            unsafe { crate::vulkan::context::ANativeWindow_release(old); }
        }
    }
}

/// JNI `surfaceDestroyed`: by the time this returns nothing may touch the window. Waits for the
/// current frame (engine lock), then suspends the renderer, keeping its meshes for the return.
pub fn surface_destroyed() {
    let mut guard = engine_lock().lock().ok();
    if let Ok(mut p) = pending_window().lock() {
        if let Some(PendingWindow(w)) = p.take() { unsafe { crate::vulkan::context::ANativeWindow_release(w); } }
    }
    if let Some(state) = guard.as_mut().and_then(|g| g.as_mut()) {
        if let Some(r) = state.renderer.as_mut() { unsafe { r.suspend(); } }
        if let Some(win) = state.window_ptr.take() {
            unsafe { crate::vulkan::context::ANativeWindow_release(win); }
        }
    }
}

/// JNI `surfaceChanged`.
pub fn resize_renderer(width: i32, height: i32) {
    let (w, h) = (width.max(1) as u32, height.max(1) as u32);
    SURFACE_SIZE.store(pack_size(w, h), Ordering::SeqCst);
    with_engine(|state| {
        state.width = w;
        state.height = h;
        state.needs_resize = true;
    });
}

/// Meshes up to a frame's budget of dirty chunks, nearest to the player first, and advances the
/// loading overlay once everything within READY_RADIUS is meshed and uploaded.
fn mesh_dirty_chunks(state: &mut EngineState) {
    let center = crate::world::chunk::ChunkPos::from_world(state.player.pos.x as i32, state.player.pos.z as i32);
    let dist = |p: &crate::world::chunk::ChunkPos| (p.0 - center.0).pow(2) + (p.1 - center.1).pow(2);
    let mut dirty: Vec<_> = state.chunks.chunks_iter().filter(|(_, c)| c.mesh_dirty).map(|(p, _)| *p).collect();
    let loading = load_phase() != LOAD_READY;
    // A dimension switch dirties everything at once; let the budget grow with the backlog.
    let budget = if loading { MESH_BUDGET_LOADING } else { MESH_BUDGET_PLAY.max(dirty.len() / 24).min(24) };
    dirty.sort_by_key(dist);
    for pos in dirty.iter().take(budget) {
        if let Some(ch) = state.chunks.get_mut(*pos) { ch.mesh_dirty = false; }
        rebuild_chunk_meshes(state, *pos);
    }
    if loading {
        let total = state.chunks.len().max(1);
        let left = dirty.len().saturating_sub(budget);
        LOAD_PERMILLE.store((((total - left.min(total)) * 1000) / total) as u32, Ordering::SeqCst);
        let near_dirty = dirty.iter().skip(budget).any(|p| (p.0 - center.0).abs() <= READY_RADIUS && (p.1 - center.1).abs() <= READY_RADIUS);
        let near_queued = state.renderer.as_ref().map(|r| r.queued_near(center.0, center.1, READY_RADIUS)).unwrap_or(0);
        if !near_dirty && near_queued == 0 && state.renderer.is_some() {
            LOAD_PHASE.store(LOAD_READY, Ordering::SeqCst);
        }
    }
}

/// Upload budget for this frame.
fn upload_budget() -> usize { if load_phase() == LOAD_READY { UPLOAD_BUDGET_PLAY } else { UPLOAD_BUDGET_LOADING } }

pub fn destroy_engine() {
    // Retire the engine thread and any world load still in flight.
    let _ = ENGINE_GEN.fetch_add(1, Ordering::SeqCst);
    let mut guard = match engine_lock().lock() { Ok(g) => g, Err(_) => return, };
    if let Ok(mut p) = pending_window().lock() {
        if let Some(PendingWindow(w)) = p.take() { unsafe { crate::vulkan::context::ANativeWindow_release(w); } }
    }
    if let Some(mut state) = guard.take() {
        save_world(&mut state);
        if let Some(mut renderer) = state.renderer.take() {
            unsafe {
                renderer.destroy();
                renderer.ctx.destroy();
            }
        }
        if let Some(win) = state.window_ptr.take() {
            unsafe { crate::vulkan::context::ANativeWindow_release(win); }
        }
    }
    LOAD_PHASE.store(LOAD_IDLE, Ordering::SeqCst);
}

#[cfg(test)]
mod lifecycle_tests {
    use super::*;

    #[test]
    fn surface_size_packs_round_trip() {
        for (w, h) in [(1u32, 1u32), (2400, 1080), (u32::MAX, 7)] {
            assert_eq!(unpack_size(pack_size(w, h)), (w, h));
        }
    }

    // A session destroyed while its world is still loading must not install that world later, and
    // every destroy retires the running engine thread's generation.
    #[test]
    fn a_stale_generation_never_installs_its_world() { super::state_tests::big_stack(|| {
        let _serial = GLOBAL_ENGINE_TEST.lock();
        let gen = ENGINE_GEN.fetch_add(1, Ordering::SeqCst) + 1;
        destroy_engine(); // bumps the generation, as onDestroy does mid-load
        assert!(!install_state(super::state_tests::small_state("stale"), gen), "a destroyed load installed its world");
        assert!(with_engine(|_| ()).is_none());
        // The current generation installs normally, and destroy clears it again.
        let gen = ENGINE_GEN.fetch_add(1, Ordering::SeqCst) + 1;
        assert!(install_state(super::state_tests::small_state("fresh"), gen));
        assert_eq!(load_phase(), LOAD_MESHING);
        assert!(!install_state(super::state_tests::small_state("dup"), gen), "a second world replaced the first");
        destroy_engine();
        assert!(with_engine(|_| ()).is_none());
        assert_eq!(load_phase(), LOAD_IDLE);
    }) }

    // Without a renderer nothing is "near and queued", so meshing alone must not declare ready, and
    // meshing works through dirty chunks nearest-first within the budget.
    #[test]
    fn meshing_is_budgeted_and_nearest_first() { super::state_tests::big_stack(|| {
        let mut s = super::state_tests::small_state("mesh");
        for p in s.chunks.chunks_iter().map(|(p, _)| *p).collect::<Vec<_>>() {
            if let Some(c) = s.chunks.get_mut(p) { c.mesh_dirty = true; }
        }
        let total = s.chunks.len();
        let _serial = GLOBAL_ENGINE_TEST.lock();
        LOAD_PHASE.store(LOAD_READY, Ordering::SeqCst);
        mesh_dirty_chunks(&mut s);
        let left = s.chunks.chunks_iter().filter(|(_, c)| c.mesh_dirty).count();
        assert_eq!(left, total - MESH_BUDGET_PLAY.max(total / 24).min(24).min(total));
        let home = crate::world::chunk::ChunkPos::from_world(s.player.pos.x as i32, s.player.pos.z as i32);
        assert!(!s.chunks.get(home).is_some_and(|c| c.mesh_dirty), "the player's own chunk was not meshed first");
        LOAD_PHASE.store(LOAD_IDLE, Ordering::SeqCst);
    }) }
}

/// Serialises tests that touch the global engine / load-phase statics.
#[cfg(test)]
static GLOBAL_ENGINE_TEST: Mutex<()> = Mutex::new(());
