//! Global-engine tests. The engine is a singleton, so this is ONE
//! sequential test (plus retries around the pre-existing parallel
//! `race_flows_without_renderer` test, which may restart the race once).
use super::*;

fn standings_contains(want: &str) -> bool {
    standings_json().contains(want)
}

#[test]
fn engine_global_lifecycle_inputs_and_standings() {
    // No renderer on the host: every renderer-adjacent call must be a
    // clean no-op / false, never a panic.
    ensure_engine();
    tick_and_render();
    resize_renderer(800, 600);
    assert!(!has_renderer());
    assert!(!is_running());

    // Start a race and drive it through the public setters. Retry the
    // start+check pair: a parallel test restarting the race must not
    // make this one flake — the last start wins and is then stable.
    let mut ok = false;
    for _ in 0..50 {
        start_race(vec!["cov-p1".to_string(), "cov \"q\" \\z".to_string()]);
        if standings_contains("cov-p1") {
            ok = true;
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(2));
    }
    assert!(ok, "standings must list the human seats: {}", standings_json());
    // Standings JSON is well-formed and escapes quotes/backslashes in ids.
    let json = standings_json();
    let parsed: serde_json::Value =
        serde_json::from_str(&json).expect("standings json must parse");
    let arr = parsed.as_array().expect("standings is an array");
    assert!(!arr.is_empty());
    assert!(arr.iter().all(|e| e.get("player").is_some() && e.get("lap").is_some()
        && e.get("progress").is_some() && e.get("place").is_some()));
    assert!(json.contains("\\\"q\\\""), "quotes escaped: {json}");
    assert!(json.contains("\\\\z"), "backslashes escaped: {json}");

    // Input clamping: out-of-range samples clamp into the pending input.
    on_drive_input(0, 5.0, -9.0, 2.0);
    set_drift(0, true);
    use_item(0);
    on_drive_input(99, 1.0, 0.0, 0.0); // grows pending, no panic
    let pending_ok = with_engine(|s| {
        let p = s.pending[0];
        (p.throttle, p.steering, p.brake, p.drift, p.item)
    });
    assert_eq!(
        pending_ok,
        Some((1.0, -1.0, 1.0, true, true)),
        "drive clamps, drift/item edges merge and survive a drive sample"
    );
    // A second drive sample must not clear the sticky drift/item edges.
    // Retry-convergent: a parallel test may restart the race once (which
    // legitimately resets pending); re-applying the setters must then stick.
    let mut merged = None;
    for _ in 0..50 {
        set_drift(0, true);
        use_item(0);
        on_drive_input(0, 0.5, 0.25, 0.0);
        merged = with_engine(|s| {
            let p = s.pending[0];
            (p.throttle, p.steering, p.drift, p.item)
        });
        if merged == Some((0.5, 0.25, true, true)) {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(2));
    }
    assert_eq!(merged, Some((0.5, 0.25, true, true)));

    // Negative-size resize clamps; tick without a renderer is a no-op.
    resize_renderer(-4, 0);
    let size = with_engine(|s| (s.width, s.height));
    assert_eq!(size, Some((1, 1)));
    tick_and_render();

    // create_renderer with a null window fails cleanly and records size.
    let ok = unsafe { create_renderer(std::ptr::null_mut(), 320, 240) };
    assert!(!ok, "host renderer creation must fail cleanly");
    assert!(!has_renderer());

    // Destroy paths keep the race (renderer destroy) / clear state.
    destroy_renderer();
    assert!(!is_running());
    destroy_engine();
    assert!(!has_renderer());
    assert_eq!(standings_json(), "[]", "destroyed engine reports empty standings");
    ensure_engine();
    assert_eq!(standings_json(), "[]");
    log_backend("coverage-test");
}
