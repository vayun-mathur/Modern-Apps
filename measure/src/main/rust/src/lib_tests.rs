//! Tests for `CvSession` state handling and the pixel->bearing calibration.
//! End-to-end frame behaviour is also covered by `tests/cv_core.rs`; these pin
//! the diagnostics contract and recovery paths the Kotlin engine reads.

use super::*;
use vision_core::imgbuf::Gray;

/// Smooth aperiodic texture (seeded random grid, upsampled), shifted by whole
/// pixels. No period for the tracker or the corner detector to alias onto.
fn aperiodic(w: usize, h: usize, shift_x: i32, shift_y: i32) -> Gray {
    let mut seed = 20240u64;
    let mut rnd = || {
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
        ((seed >> 40) & 0xff) as f32
    };
    let gw = w / 4 + 8;
    let gh = h / 4 + 8;
    let grid: Vec<f32> = (0..gw * gh).map(|_| rnd()).collect();
    let mut g = Gray::new(w, h);
    for y in 0..h {
        for x in 0..w {
            let gx = (x as i32 - shift_x) as f32 / 4.0 + 4.0;
            let gy = (y as i32 - shift_y) as f32 / 4.0 + 4.0;
            let (x0, y0) = (gx.floor() as usize, gy.floor() as usize);
            let (fx, fy) = (gx - x0 as f32, gy - y0 as f32);
            let v = grid[y0 * gw + x0] * (1.0 - fx) * (1.0 - fy)
                + grid[y0 * gw + x0 + 1] * fx * (1.0 - fy)
                + grid[(y0 + 1) * gw + x0] * (1.0 - fx) * fy
                + grid[(y0 + 1) * gw + x0 + 1] * fx * fy;
            g.px[y * w + x] = v.clamp(0.0, 255.0) as u8;
        }
    }
    g.gaussian_blur_3x3()
}

fn blank(w: usize, h: usize) -> Gray {
    let mut g = Gray::new(w, h);
    for v in g.px.iter_mut() {
        *v = 128;
    }
    g
}

// --- Calibration ---------------------------------------------------------------

#[test]
fn calibrated_maps_the_principal_point_to_the_origin() {
    let s = CvSession::new(300.0, 280.0, 160.0, 120.0);
    assert_eq!(s.calibrated((160.0, 120.0)), (0.0, 0.0));
}

#[test]
fn calibrated_applies_focal_length_and_principal_point_per_axis() {
    let s = CvSession::new(200.0, 100.0, 50.0, 40.0);
    assert_eq!(s.calibrated((250.0, 140.0)), (1.0, 1.0));
    assert_eq!(s.calibrated((0.0, 0.0)), (-0.25, -0.4));
    // One focal length right of centre on x, centre on y.
    assert_eq!(s.calibrated((250.0, 40.0)), (1.0, 0.0));
}

// --- Session state machine -------------------------------------------------------

#[test]
fn first_frame_reports_the_full_diagnostics_contract() {
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    let flow = s.process_frame(&aperiodic(320, 240, 0, 0));
    assert!(flow.first_frame);
    assert!(flow.pairs.is_empty());
    assert!(flow.pose.is_none());
    assert_eq!(flow.seeded, flow.point_count, "first frame seeds what it reports");
    assert_eq!(flow.forward_ok, 0);
    assert_eq!(flow.fb_survivors, 0);
    assert_eq!(flow.ransac_inliers, -1, "RANSAC did not run");
    assert!(flow.point_count > 50);
}

#[test]
fn diagnostics_are_ordered_and_consistent_on_a_tracked_frame() {
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    let f1 = s.process_frame(&aperiodic(320, 240, 0, 0));
    let f2 = s.process_frame(&aperiodic(320, 240, 3, 1));
    assert!(!f2.first_frame);
    assert_eq!(f2.seeded, f1.point_count, "seeded is what the last frame left");
    assert!(f2.forward_ok >= f2.fb_survivors, "FB check can only remove tracks");
    assert!(f2.fb_survivors >= f2.pairs.len(), "RANSAC can only remove pairs");
    assert!(f2.point_count >= f2.pairs.len());
    assert!(f2.point_count <= corners::MAX_CORNERS);
    if f2.pose.is_some() {
        // When a pose was recovered, pairs are exactly the RANSAC inliers.
        assert_eq!(f2.ransac_inliers as usize, f2.pairs.len());
    }
}

#[test]
fn a_sideways_shift_recovers_a_pose_with_dominant_x_translation() {
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    s.process_frame(&aperiodic(320, 240, 0, 0));
    let f2 = s.process_frame(&aperiodic(320, 240, 3, 0));
    let pose = f2.pose.expect("pose from a clean sideways shift");
    // Near-identity rotation for a pure translation.
    assert!((pose.r[(0, 0)] - 1.0).abs() < 0.02, "R00 {}", pose.r[(0, 0)]);
    assert!((pose.r[(1, 1)] - 1.0).abs() < 0.02, "R11 {}", pose.r[(1, 1)]);
    assert!(pose.r[(0, 1)].abs() < 0.02 && pose.r[(1, 0)].abs() < 0.02);
    // Unit translation, dominated by the x axis (sign fixed by the epipolar
    // convention and asserted exactly in the epipolar unit tests).
    let n = pose.t.norm();
    assert!((n - 1.0).abs() < 1e-9, "|t| {n}");
    assert!(pose.t[0].abs() / n > 0.8, "t {:?}", pose.t);
}

#[test]
fn too_few_pairs_skip_ransac_and_pose() {
    // A small frame yields only a couple of tracks: below the 8-point minimum
    // RANSAC must not run (ransac_inliers stays -1) and no pose is reported.
    let mut s = CvSession::new(300.0, 300.0, 50.0, 40.0);
    s.process_frame(&aperiodic(100, 80, 0, 0));
    let f2 = s.process_frame(&aperiodic(100, 80, 2, 0));
    assert!(f2.pairs.len() < 8, "pairs {}", f2.pairs.len());
    assert_eq!(f2.ransac_inliers, -1);
    assert!(f2.pose.is_none());
}

#[test]
fn an_occluded_frame_produces_no_pairs_and_the_session_recovers() {
    // Texture -> blank: forward LK alone drifts, but every backward track
    // starts from a flat patch and is rejected, so the FB check must kill
    // all pairs. The next textured frame then reseeds via the top-up path.
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    s.process_frame(&aperiodic(320, 240, 0, 0));
    let f2 = s.process_frame(&blank(320, 240));
    assert!(f2.pairs.is_empty(), "occluded frame kept {} pairs", f2.pairs.len());
    assert!(f2.pose.is_none());
    assert_eq!(f2.point_count, 0, "a blank frame seeds nothing");
    let f3 = s.process_frame(&aperiodic(320, 240, 1, 0));
    assert!(f3.point_count > 50, "session did not reseed, got {}", f3.point_count);
    let f4 = s.process_frame(&aperiodic(320, 240, 4, 1));
    assert!(f4.pairs.len() >= 15, "tracking did not resume: {}", f4.pairs.len());
}

#[test]
fn losing_most_tracks_triggers_a_top_up_reseed() {
    // A 60 px jump decorrelates the texture: nearly all tracks die, and the
    // session must top the point set back up from fresh corners instead of
    // starving on the handful of survivors.
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    s.process_frame(&aperiodic(320, 240, 0, 0));
    let f2 = s.process_frame(&aperiodic(320, 240, 60, 40));
    assert!(f2.pairs.len() < 20, "jump kept {} pairs", f2.pairs.len());
    assert!(
        f2.point_count >= corners::MAX_CORNERS / 2,
        "no top-up: point_count {}",
        f2.point_count
    );
    // Freshly seeded points are trackable on the next small motion.
    let f3 = s.process_frame(&aperiodic(320, 240, 63, 41));
    assert!(f3.pairs.len() >= 15, "reseeded points did not track: {}", f3.pairs.len());
}

#[test]
fn point_ids_never_repeat_within_a_frame_even_after_top_up() {
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    s.process_frame(&aperiodic(320, 240, 0, 0));
    s.process_frame(&aperiodic(320, 240, 60, 40)); // forces a top-up
    let f3 = s.process_frame(&aperiodic(320, 240, 63, 41));
    let mut ids: Vec<u64> = f3.pairs.iter().map(|p| p.id).collect();
    assert!(ids.len() >= 15);
    ids.sort_unstable();
    ids.dedup();
    assert_eq!(ids.len(), f3.pairs.len(), "duplicate id after top-up");
    assert!(ids.iter().all(|&id| id >= 1), "ids are 1-based");
}

#[test]
fn tiny_frames_never_track_and_never_panic() {
    let mut s = CvSession::new(300.0, 300.0, 10.0, 10.0);
    let f1 = s.process_frame(&Gray::new(20, 20));
    assert!(f1.first_frame);
    assert_eq!(f1.point_count, 0);
    let f2 = s.process_frame(&Gray::new(20, 20));
    assert!(f2.pairs.is_empty());
    assert!(f2.pose.is_none());
}

// --- Strided / direct-buffer path -------------------------------------------------

/// Pack [gray] into a padded plane: `pad` junk bytes after every row.
fn padded(gray: &Gray, pad: usize) -> (Vec<u8>, usize) {
    let stride = gray.w + pad;
    let mut out = vec![0xEEu8; stride * gray.h];
    for y in 0..gray.h {
        out[y * stride..y * stride + gray.w]
            .copy_from_slice(&gray.px[y * gray.w..(y + 1) * gray.w]);
    }
    (out, stride)
}

#[test]
fn destride_drops_row_padding_and_ignores_the_junk_bytes() {
    // Hand-computed: 4x3 image, stride 7, padding filled with 0xEE.
    let mut src = Vec::new();
    for row in 0..3u8 {
        src.extend_from_slice(&[row * 10 + 1, row * 10 + 2, row * 10 + 3, row * 10 + 4]);
        src.extend_from_slice(&[0xEE, 0xEE, 0xEE]);
    }
    let mut dst = Gray::new(1, 1);
    destride_into(&src, 4, 3, 7, &mut dst);
    assert_eq!((dst.w, dst.h), (4, 3));
    assert_eq!(dst.px, vec![1, 2, 3, 4, 11, 12, 13, 14, 21, 22, 23, 24]);
    // A tight plane (stride == width) round-trips too, reusing the buffer.
    let tight: Vec<u8> = (0..12).collect();
    destride_into(&tight, 4, 3, 4, &mut dst);
    assert_eq!(dst.px, tight);
}

#[test]
fn strided_path_matches_the_byte_array_path_frame_for_frame() {
    let f1 = aperiodic(320, 240, 0, 0);
    let f2 = aperiodic(320, 240, 3, 1);
    let (p1, stride1) = padded(&f1, 17);
    let (p2, stride2) = padded(&f2, 17);
    assert_eq!(stride1, 337);

    let mut via_array = CvSession::new(300.0, 300.0, 160.0, 120.0);
    let a1 = via_array.process_frame(&f1);
    let a2 = via_array.process_frame(&f2);

    let mut via_strided = CvSession::new(300.0, 300.0, 160.0, 120.0);
    let b1 = via_strided
        .process_frame_strided(&p1, f1.w, f1.h, stride1)
        .expect("padded first frame");
    let b2 = via_strided
        .process_frame_strided(&p2, f2.w, f2.h, stride2)
        .expect("padded second frame");

    assert_eq!(b1.first_frame, a1.first_frame);
    assert_eq!(b1.point_count, a1.point_count);
    assert_eq!(b2.first_frame, a2.first_frame);
    assert_eq!(b2.point_count, a2.point_count);
    assert_eq!(b2.seeded, a2.seeded);
    assert_eq!(b2.forward_ok, a2.forward_ok);
    assert_eq!(b2.fb_survivors, a2.fb_survivors);
    assert_eq!(b2.ransac_inliers, a2.ransac_inliers);
    assert_eq!(b2.pairs, a2.pairs, "tracked pairs must be identical");
    assert_eq!(b2.pose.is_some(), a2.pose.is_some());
    if let (Some(pa), Some(pb)) = (&a2.pose, &b2.pose) {
        for i in 0..3 {
            assert_eq!(pa.t[i], pb.t[i]);
            for j in 0..3 {
                assert_eq!(pa.r[(i, j)], pb.r[(i, j)]);
            }
        }
    }
    assert!(b2.pairs.len() >= 15, "strided path tracked {}", b2.pairs.len());
}

#[test]
fn strided_path_rejects_short_or_malformed_planes() {
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    assert!(s.process_frame_strided(&[0u8; 10], 320, 240, 320).is_none());
    assert!(s.process_frame_strided(&[0u8; 4096], 320, 240, 100).is_none(), "stride < width");
    assert!(s.process_frame_strided(&[0u8; 4096], 0, 240, 320).is_none());
    // Rejection must not poison the session: a valid frame still seeds.
    let f = aperiodic(320, 240, 0, 0);
    let (p, stride) = padded(&f, 5);
    let flow = s.process_frame_strided(&p, f.w, f.h, stride).unwrap();
    assert!(flow.first_frame);
    assert!(flow.point_count > 50);
}

#[test]
fn reset_clears_points_so_the_next_frame_reseeds_from_scratch() {
    let mut s = CvSession::new(300.0, 300.0, 160.0, 120.0);
    let f1 = s.process_frame(&aperiodic(320, 240, 0, 0));
    s.process_frame(&aperiodic(320, 240, 3, 0));
    s.reset();
    let f3 = s.process_frame(&aperiodic(320, 240, 5, 5));
    assert!(f3.first_frame);
    assert_eq!(f3.point_count, f1.point_count, "same frame must reseed identically");
}
