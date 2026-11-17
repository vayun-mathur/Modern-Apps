//! Extra deterministic tests for pure engine helpers (sibling of
//! `engine::tests`). Avoids the global ENGINE lock except via constructed
//! `EngineState` values, so tests stay parallel-safe.
use super::*;
use crate::physics::DriveInput;
use std::time::Instant;

fn state_with_pending(n: usize) -> EngineState {
    EngineState {
        race: None,
        renderer: None,
        track_count: 0,
        pending: (0..n).map(|_| DriveInput::default()).collect(),
        window_ptr: None,
        width: 0,
        height: 0,
        last_tick: Instant::now(),
        accumulator: 0.0,
        running: false,
    }
}

#[test]
fn escape_only_escapes_backslash_and_quote() {
    assert_eq!(escape("plain p1"), "plain p1");
    assert_eq!(escape(""), "");
    assert_eq!(escape("a\"b"), "a\\\"b");
    assert_eq!(escape("a\\b"), "a\\\\b");
    // Backslash first: an existing \" becomes \\\" (not a double escape of
    // the quote alone).
    assert_eq!(escape("\\\""), "\\\\\\\"");
    assert_eq!(escape("a\nb\tc"), "a\nb\tc", "other controls pass through");
}

#[test]
fn ensure_pending_grows_preserves_and_never_shrinks() {
    let mut s = state_with_pending(0);
    ensure_pending(&mut s, 0);
    assert_eq!(s.pending.len(), 0);
    ensure_pending(&mut s, 3);
    assert_eq!(s.pending.len(), 3);
    assert!(s.pending.iter().all(|p| p.throttle == 0.0 && !p.drift && !p.item));
    s.pending[0].throttle = 0.7;
    s.pending[0].drift = true;
    ensure_pending(&mut s, 5);
    assert_eq!(s.pending.len(), 5);
    assert_eq!(s.pending[0].throttle, 0.7, "existing samples survive growth");
    assert!(s.pending[0].drift);
    ensure_pending(&mut s, 2);
    assert_eq!(s.pending.len(), 5, "seats never shrink mid-race");
    ensure_pending(&mut s, 5);
    assert_eq!(s.pending.len(), 5);
}

#[test]
fn fixed_step_is_sixty_hertz() {
    assert!((STEP - 1.0 / 60.0).abs() < 1e-9);
    assert!(STEP > 0.0 && STEP < 0.1);
}

#[test]
fn view_proj_is_finite_for_degenerate_sizes() {
    for (w, h) in [(0, 0), (0, 720), (1280, 0), (1, 1)] {
        let m = view_proj_for([0.0, 4.0, 20.0], [0.0, 1.0, 0.0], w, h, (false, 0.0));
        assert!(m.iter().flatten().all(|v| v.is_finite()), "size {w}x{h}");
        let swapped = view_proj_for([0.0, 4.0, 20.0], [0.0, 1.0, 0.0], w, h, (true, 0.0));
        assert!(swapped.iter().flatten().all(|v| v.is_finite()));
    }
}

#[test]
fn view_proj_depends_on_camera_aspect_swap_and_rotation() {
    let cam = [10.0, 4.2, -30.0];
    let look = [10.0, 1.0, -40.0];
    let a = view_proj_for(cam, look, 1280, 720, (false, 0.0));
    // Deterministic.
    assert_eq!(a, view_proj_for(cam, look, 1280, 720, (false, 0.0)));
    // Moving the camera changes the matrix.
    assert_ne!(a, view_proj_for([0.0, 4.2, 0.0], look, 1280, 720, (false, 0.0)));
    // Looking elsewhere changes it too.
    assert_ne!(a, view_proj_for(cam, [0.0, 1.0, 0.0], 1280, 720, (false, 0.0)));
    // Swapped aspect (portrait panel) and pre-rotation both change it.
    assert_ne!(a, view_proj_for(cam, look, 1280, 720, (true, 0.0)));
    assert_ne!(a, view_proj_for(cam, look, 1280, 720, (false, 0.5)));
    // Portrait vs landscape aspect differ.
    assert_ne!(
        view_proj_for(cam, look, 720, 1280, (false, 0.0)),
        view_proj_for(cam, look, 1280, 720, (false, 0.0))
    );
}
