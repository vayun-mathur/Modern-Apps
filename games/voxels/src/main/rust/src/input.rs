use std::sync::{Mutex, OnceLock};
#[derive(Debug, Clone, Copy, Default)]
pub struct InputState {
    pub move_forward: f32, pub move_right: f32,
    // Look is a rate (normalized stick displacement, -1..1) applied every frame, not a one-shot delta.
    pub look_yaw_rate: f32, pub look_pitch_rate: f32,
    pub jump_held: bool, // top button held: jump when walking, ascend when flying
    pub down_held: bool, // bottom button held while flying: descend
    pub sneak: bool,     // walking sneak mode (toggle); disables jumping and prevents walking off edges
    pub sprint: bool,
    pub toggle_fly: bool, // edge-triggered
}
static INPUT: OnceLock<Mutex<InputState>> = OnceLock::new();
fn mutex() -> &'static Mutex<InputState> { INPUT.get_or_init(|| Mutex::new(InputState::default())) }
pub fn set_move(x: f32, y: f32) { if let Ok(mut g)=mutex().lock() { g.move_right=x.clamp(-1.0,1.0); g.move_forward=y.clamp(-1.0,1.0); } }
pub fn set_look_rate(rx: f32, ry: f32) { if let Ok(mut g)=mutex().lock() { g.look_yaw_rate=rx.clamp(-1.0,1.0); g.look_pitch_rate=ry.clamp(-1.0,1.0); } }
pub fn set_jump(held: bool) { if let Ok(mut g)=mutex().lock() { g.jump_held=held; } }
pub fn set_down(held: bool) { if let Ok(mut g)=mutex().lock() { g.down_held=held; } }
pub fn set_sneak(on: bool) { if let Ok(mut g)=mutex().lock() { g.sneak=on; } }
/// Drops every held control (move, look, jump, descend). Used when the game pauses.
pub fn release_all() {
    if let Ok(mut g)=mutex().lock() {
        g.move_forward=0.0; g.move_right=0.0; g.look_yaw_rate=0.0; g.look_pitch_rate=0.0;
        g.jump_held=false; g.down_held=false; g.toggle_fly=false;
    }
}
pub fn request_toggle_fly() { if let Ok(mut g)=mutex().lock() { g.toggle_fly=true; } }
pub fn snapshot_and_clear_look() -> InputState {
    // Held/persistent values (move, look, jump, down, sneak) stay set until the UI reports a change;
    // only the edge-triggered fly toggle is consumed here.
    if let Ok(mut g)=mutex().lock() { let c=*g; g.toggle_fly=false; return c; }
    InputState::default()
}
