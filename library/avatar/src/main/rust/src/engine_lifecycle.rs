// Surface/engine teardown and render-thread generation counters.
//
// Split out of `engine.rs` so each file stays under the 500-line Rust limit.
// Same module scope via `engine.rs` (`include!`), so item order is irrelevant.

/// Tear down one surface's renderer, stop its thread. Traits/pose survive in
/// the state until the surface entry is removed.
pub fn destroy_renderer(surface_id: i32) {
    if let Ok(mut eng) = engine().lock() {
        if let Some(state) = eng.surfaces.get_mut(&surface_id) {
            if let Some(mut renderer) = state.renderer.take() {
                unsafe { renderer.destroy() };
            }
            state.mesh_count = 0;
        }
    }
}

/// Remove a surface entry entirely (after its thread has exited).
pub fn remove_surface(surface_id: i32) {
    if let Ok(mut eng) = engine().lock() {
        eng.surfaces.remove(&surface_id);
    }
}

/// Bump a surface's render-thread generation (rotation path) and return it.
pub fn next_generation(surface_id: i32) -> u64 {
    if let Ok(mut eng) = engine().lock() {
        let state = eng.surface_mut(surface_id, 512, 512);
        state.generation = state.generation.wrapping_add(1);
        state.generation
    } else {
        0
    }
}

/// A surface's current render-thread generation.
pub fn generation(surface_id: i32) -> u64 {
    engine()
        .lock()
        .map(|e| e.surfaces.get(&surface_id).map(|s| s.generation).unwrap_or(0))
        .unwrap_or(0)
}

/// Destroy everything.
pub fn destroy_engine() {
    if let Ok(mut eng) = engine().lock() {
        for state in eng.surfaces.values_mut() {
            if let Some(mut renderer) = state.renderer.take() {
                unsafe { renderer.destroy() };
            }
            state.mesh_count = 0;
        }
        eng.surfaces.clear();
    }
}
