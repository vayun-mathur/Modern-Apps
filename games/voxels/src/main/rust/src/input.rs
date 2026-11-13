use std::sync::{Mutex, OnceLock, atomic::{AtomicU32, Ordering}};
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
    // Swipe-look: radians of yaw/pitch accumulated from drags since the last frame (consumed per frame).
    pub look_dyaw: f32, pub look_dpitch: f32,
}
static INPUT: OnceLock<Mutex<InputState>> = OnceLock::new();
fn mutex() -> &'static Mutex<InputState> { INPUT.get_or_init(|| Mutex::new(InputState::default())) }
pub fn set_move(x: f32, y: f32) { if let Ok(mut g)=mutex().lock() { g.move_right=x.clamp(-1.0,1.0); g.move_forward=y.clamp(-1.0,1.0); } }
pub fn set_look_rate(rx: f32, ry: f32) { if let Ok(mut g)=mutex().lock() { g.look_yaw_rate=rx.clamp(-1.0,1.0); g.look_pitch_rate=ry.clamp(-1.0,1.0); } }
/// Swipe-look: add a drag's worth of rotation (radians, before sensitivity) for the next frame.
pub fn add_look_delta(dyaw: f32, dpitch: f32) {
    if !dyaw.is_finite() || !dpitch.is_finite() { return; }
    if let Ok(mut g)=mutex().lock() { g.look_dyaw += dyaw; g.look_dpitch += dpitch; }
}
pub fn set_jump(held: bool) { if let Ok(mut g)=mutex().lock() { g.jump_held=held; } }
pub fn set_down(held: bool) { if let Ok(mut g)=mutex().lock() { g.down_held=held; } }
pub fn set_sneak(on: bool) { if let Ok(mut g)=mutex().lock() { g.sneak=on; } }
/// Drops every held control (move, look, jump, descend). Used when the game pauses.
pub fn release_all() {
    if let Ok(mut g)=mutex().lock() {
        g.move_forward=0.0; g.move_right=0.0; g.look_yaw_rate=0.0; g.look_pitch_rate=0.0;
        g.jump_held=false; g.down_held=false; g.toggle_fly=false; g.look_dyaw=0.0; g.look_dpitch=0.0;
    }
}
pub fn request_toggle_fly() { if let Ok(mut g)=mutex().lock() { g.toggle_fly=true; } }
pub fn snapshot_and_clear_look() -> InputState {
    // Held/persistent values (move, look rate, jump, down, sneak) stay set until the UI reports a
    // change; the edge-triggered fly toggle and the accumulated swipe-look deltas are consumed here.
    if let Ok(mut g)=mutex().lock() { let c=*g; g.toggle_fly=false; g.look_dyaw=0.0; g.look_dpitch=0.0; return c; }
    InputState::default()
}
// Camera turn-speed multiplier from the settings sheet (f32 bits; 1.0 by default).
static LOOK_SENS: AtomicU32 = AtomicU32::new(0x3F80_0000);
/// Sets the look-sensitivity multiplier (clamped to 0.25..3).
pub fn set_look_sensitivity(s: f32) {
    let v = if s.is_finite() { s.clamp(0.25, 3.0) } else { 1.0 };
    LOOK_SENS.store(v.to_bits(), Ordering::Relaxed);
}
/// The look-sensitivity multiplier.
pub fn look_sensitivity() -> f32 { f32::from_bits(LOOK_SENS.load(Ordering::Relaxed)) }

#[cfg(test)]
mod tests {
    #[test]
    fn look_sensitivity_defaults_to_one_and_clamps() {
        let _serial = crate::engine::GLOBAL_ENGINE_TEST.lock();
        assert!((super::look_sensitivity() - 1.0).abs() < 1e-6 || (0.25..=3.0).contains(&super::look_sensitivity()));
        super::set_look_sensitivity(10.0);
        assert!((super::look_sensitivity() - 3.0).abs() < 1e-6);
        super::set_look_sensitivity(f32::NAN);
        assert!((super::look_sensitivity() - 1.0).abs() < 1e-6);
    }

    // Swipe deltas accumulate between frames and are consumed exactly once.
    #[test]
    fn swipe_look_deltas_are_consumed_once() {
        let _serial = crate::engine::GLOBAL_ENGINE_TEST.lock();
        let _ = super::snapshot_and_clear_look();
        super::add_look_delta(0.1, -0.05);
        super::add_look_delta(0.2, 0.0);
        super::add_look_delta(f32::NAN, 1.0);
        let a = super::snapshot_and_clear_look();
        assert!((a.look_dyaw - 0.3).abs() < 1e-5 && (a.look_dpitch + 0.05).abs() < 1e-5);
        let b = super::snapshot_and_clear_look();
        assert_eq!((b.look_dyaw, b.look_dpitch), (0.0, 0.0));
    }
}
