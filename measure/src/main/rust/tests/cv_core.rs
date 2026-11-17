//! Host tests for the per-frame CV core on synthetic frames: corner seeding,
//! Lucas-Kanade tracking of a shifted pattern, and session behaviour.
//! Essential-matrix recovery on synthetic correspondences is covered by the
//! unit tests in `src/epipolar.rs`; corner quality by `src/corners.rs`.

use measure_cv::CvSession;
use vision_core::imgbuf::Gray;

/// Smooth but richly textured frame, shifted by whole pixels.
fn frame(w: usize, h: usize, shift_x: i32, shift_y: i32) -> Gray {
    let mut g = Gray::new(w, h);
    for y in 0..h {
        for x in 0..w {
            let sx = x as i32 - shift_x;
            let sy = y as i32 - shift_y;
            let v = ((sx as f32 * 0.31).sin() * (sy as f32 * 0.23).cos()
                + ((sx + sy) as f32 * 0.13).sin()
                + (sx as f32 * 0.07 - sy as f32 * 0.09).cos())
                * 55.0
                + 128.0;
            g.px[y * w + x] = v.clamp(0.0, 255.0) as u8;
        }
    }
    g
}

#[test]
fn first_frame_seeds_points_and_reports_first() {
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    let flow = s.process_frame(&frame(320, 240, 0, 0));
    assert!(flow.first_frame);
    assert!(flow.pairs.is_empty());
    assert!(flow.pose.is_none());
    assert!(flow.point_count >= 20, "seeded {}", flow.point_count);
}

#[test]
fn tracks_a_shifted_pattern() {
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    s.process_frame(&frame(320, 240, 0, 0));
    let flow = s.process_frame(&frame(320, 240, 4, 2));
    assert!(!flow.first_frame);
    assert!(flow.pairs.len() >= 15, "tracked {}", flow.pairs.len());
    // Mean displacement must match the synthetic shift.
    let (mut mx, mut my) = (0.0, 0.0);
    for p in &flow.pairs {
        mx += p.curr.0 - p.prev.0;
        my += p.curr.1 - p.prev.1;
    }
    mx /= flow.pairs.len() as f64;
    my /= flow.pairs.len() as f64;
    assert!((mx - 4.0).abs() < 0.75, "mean dx {mx}");
    assert!((my - 2.0).abs() < 0.75, "mean dy {my}");
    assert!(flow.point_count >= flow.pairs.len());
}

#[test]
fn identical_frames_track_with_zero_motion() {
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    let f = frame(320, 240, 0, 0);
    s.process_frame(&f);
    let flow = s.process_frame(&f);
    assert!(flow.pairs.len() >= 15);
    for p in &flow.pairs {
        assert!((p.curr.0 - p.prev.0).abs() < 0.5);
        assert!((p.curr.1 - p.prev.1).abs() < 0.5);
    }
}

#[test]
fn flat_frames_never_track() {
    let mut g = Gray::new(320, 240);
    for v in g.px.iter_mut() {
        *v = 77;
    }
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    let first = s.process_frame(&g);
    assert_eq!(first.point_count, 0);
    let second = s.process_frame(&g);
    assert!(second.pairs.is_empty());
    assert!(second.pose.is_none());
}

#[test]
fn pair_ids_are_stable_across_frames() {
    // Keyframe triangulation matches keyframe<->current points by id, so
    // an id must name the same feature for its whole tracked life and
    // must not repeat within one frame.
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    s.process_frame(&frame(320, 240, 0, 0));
    let f2 = s.process_frame(&frame(320, 240, 3, 1));
    let f3 = s.process_frame(&frame(320, 240, 6, 2));
    assert!(f2.pairs.len() >= 15 && f3.pairs.len() >= 15);
    for flow in [&f2, &f3] {
        let mut ids: Vec<u64> = flow.pairs.iter().map(|p| p.id).collect();
        ids.sort_unstable();
        ids.dedup();
        assert_eq!(ids.len(), flow.pairs.len(), "duplicate id in one frame");
    }
    let persisted = f3
        .pairs
        .iter()
        .filter(|p| f2.pairs.iter().any(|q| q.id == p.id))
        .count();
    assert!(
        persisted >= 10,
        "expected most features to persist with stable ids, got {persisted}"
    );
}

#[test]
fn reset_returns_to_first_frame() {
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    s.process_frame(&frame(320, 240, 0, 0));
    s.reset();
    let flow = s.process_frame(&frame(320, 240, 1, 0));
    assert!(flow.first_frame);
}

#[test]
fn a_size_change_restarts_the_session() {
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    s.process_frame(&frame(320, 240, 0, 0));
    let flow = s.process_frame(&frame(160, 120, 0, 0));
    assert!(flow.first_frame, "size change must reseed, not track across sizes");
}
