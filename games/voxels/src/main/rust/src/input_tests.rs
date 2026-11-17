//! Held-control state: setters clamp, persistent axes survive a frame
//! snapshot, and `release_all` drops everything held. These mutate the
//! process-global input state, so they hold the engine test lock and
//! leave the state released behind them.

use super::*;

#[test]
fn held_controls_clamp_snapshot_and_release() {
    let _serial = crate::engine::GLOBAL_ENGINE_TEST.lock();
    release_all();
    set_move(2.5, -7.0);
    set_look_rate(-3.0, 0.5);
    set_jump(true);
    set_down(true);
    set_sneak(true);
    let s = snapshot_and_clear_look();
    assert_eq!((s.move_right, s.move_forward), (1.0, -1.0), "move axes clamp to -1..=1");
    assert_eq!((s.look_yaw_rate, s.look_pitch_rate), (-1.0, 0.5), "look rates clamp to -1..=1");
    assert!(s.jump_held && s.down_held && s.sneak);
    // Held axes are persistent: a second snapshot still reports them.
    let again = snapshot_and_clear_look();
    assert_eq!(again.move_right, 1.0);
    assert!(again.jump_held);
    // The fly toggle is edge-triggered: reported once, then consumed.
    request_toggle_fly();
    assert!(snapshot_and_clear_look().toggle_fly);
    assert!(!snapshot_and_clear_look().toggle_fly, "the fly toggle must not re-fire");
    // Non-finite look deltas are ignored rather than poisoning the camera.
    add_look_delta(f32::INFINITY, 0.25);
    add_look_delta(0.25, f32::NEG_INFINITY);
    let clean = snapshot_and_clear_look();
    assert_eq!((clean.look_dyaw, clean.look_dpitch), (0.0, 0.0));
    release_all();
    let dropped = snapshot_and_clear_look();
    assert_eq!((dropped.move_forward, dropped.move_right, dropped.look_yaw_rate, dropped.look_pitch_rate), (0.0, 0.0, 0.0, 0.0));
    assert!(!dropped.jump_held && !dropped.down_held && !dropped.toggle_fly);
    set_sneak(false); // leave no state behind for other suites
}
