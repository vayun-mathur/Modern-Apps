// ---- Queued world taps ----
// The UI used to call place/break straight into the engine on the main thread, blocking on the engine
// lock for up to a whole frame (the render thread holds it through the GPU fence wait), which made
// taps lag and could stall the UI. Taps are now queued and handled at the start of the next frame;
// their result codes come back through a second queue the UI drains.
/// Place / interact (tap). Result: the `place_block_at` code.
pub const ACTION_PLACE: i32 = 0;
/// Mine / attack / use (hold, Use button). Result: 1 if something happened, else 0.
pub const ACTION_ACT: i32 = 1;
static ACTIONS: Mutex<Vec<(i32, f32, f32)>> = Mutex::new(Vec::new());
static RESULTS: Mutex<Vec<(i32, i32)>> = Mutex::new(Vec::new());
/// Most queued taps kept; a backlog beyond this (engine stalled) drops the oldest.
const MAX_QUEUED_ACTIONS: usize = 32;

/// Queues a world tap at screen pixel (`x`, `y`) for the next frame. Never blocks on the engine.
pub fn queue_action(kind: i32, x: f32, y: f32) {
    if let Ok(mut q) = ACTIONS.lock() {
        if q.len() >= MAX_QUEUED_ACTIONS { let _ = q.remove(0); }
        q.push((kind, x, y));
    }
}

/// Results of handled taps since the last call, as `[[kind, code], ...]` JSON.
pub fn drain_action_results() -> String {
    let taken: Vec<(i32, i32)> = RESULTS.lock().map(|mut r| std::mem::take(&mut *r)).unwrap_or_default();
    serde_json::to_string(&taken).unwrap_or_else(|_| "[]".into())
}

/// Handles every queued tap against the current world (called at the top of each frame).
fn process_actions(state: &mut EngineState) {
    let taken: Vec<(i32, f32, f32)> = ACTIONS.lock().map(|mut q| std::mem::take(&mut *q)).unwrap_or_default();
    if taken.is_empty() { return; }
    let results: Vec<(i32, i32)> = taken.into_iter().map(|(kind, x, y)| {
        let code = if kind == ACTION_PLACE { place_at(state, x, y) } else { i32::from(break_at(state, x, y)) };
        (kind, code)
    }).collect();
    if let Ok(mut r) = RESULTS.lock() { r.extend(results); }
}
