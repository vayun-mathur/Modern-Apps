fn detect_points(gray: &Gray) -> Vec<(f32, f32)> {
    let f = detect_and_describe(gray, TARGET_FEATURES, FAST_THRESHOLD);
    f.kps.iter().map(|k| (k.x, k.y)).collect()
}

/// Add fresh detections that are not already covered, up to `target`.
fn merge_points(existing: &mut Vec<(f32, f32)>, fresh: &[(f32, f32)], target: usize) {
    const MIN_SEP2: f32 = 100.0; // 10 px
    for &f in fresh {
        if existing.len() >= target {
            break;
        }
        let too_close = existing
            .iter()
            .any(|&e| (e.0 - f.0).powi(2) + (e.1 - f.1).powi(2) < MIN_SEP2);
        if !too_close {
            existing.push(f);
        }
    }
}

fn median(v: &mut [f32]) -> f32 {
    if v.is_empty() {
        return 0.0;
    }
    v.sort_by(|a, b| a.total_cmp(b));
    v[v.len() / 2]
}


#[cfg(test)]
mod tests {
    use super::*;

    fn intr() -> Intrinsics {
        Intrinsics { fx: 500.0, fy: 500.0, cx: 320.0, cy: 240.0 }
    }

    #[test]
    fn unproject_maps_the_principal_point_to_the_origin() {
        let i = intr();
        let (x, y) = i.unproject(320.0, 240.0);
        assert!(x.abs() < 1e-12 && y.abs() < 1e-12);
    }

    #[test]
    fn unproject_scales_by_focal_length() {
        let i = intr();
        let (x, _) = i.unproject(320.0 + 500.0, 240.0);
        assert!((x - 1.0).abs() < 1e-12);
    }

    #[test]
    fn a_new_session_is_initialising_and_unmeasurable() {
        let s = VioSession::new(intr());
        assert_eq!(s.quality(), Quality::Initialising);
        assert!(s.ray_to_world(320.0, 240.0).is_none());
        assert_eq!(s.landmark_count(), 0);
        assert!(!s.has_plane());
    }

    #[test]
    fn reset_returns_to_the_initial_state() {
        let mut s = VioSession::new(intr());
        s.scale = 3.0;
        s.quality = Quality::Good;
        s.reset();
        assert_eq!(s.quality(), Quality::Initialising);
        assert!((s.scale - 1.0).abs() < 1e-12);
    }

    #[test]
    fn imu_queue_is_bounded() {
        let mut s = VioSession::new(intr());
        let batch: Vec<ImuSample> = (0..1000)
            .map(|i| ImuSample {
                t_ns: i as i64 * 2_500_000,
                gyro: Vector3::zeros(),
                accel: Vector3::zeros(),
            })
            .collect();
        for _ in 0..20 {
            s.push_imu(&batch);
        }
        assert!(s.pending_imu.len() <= 8000, "queue grew to {}", s.pending_imu.len());
    }

    #[test]
    fn merge_points_respects_separation_and_target() {
        let mut existing = vec![(10.0f32, 10.0f32)];
        let fresh = vec![(11.0, 11.0), (100.0, 100.0), (200.0, 200.0)];
        merge_points(&mut existing, &fresh, 3);
        // (11,11) is within 10 px of (10,10) so must be skipped.
        assert_eq!(existing.len(), 3);
        assert!(existing.contains(&(100.0, 100.0)));
        assert!(!existing.contains(&(11.0, 11.0)));
    }

    #[test]
    fn median_of_even_and_odd_lengths() {
        assert!((median(&mut [3.0, 1.0, 2.0]) - 2.0).abs() < 1e-9);
        assert!((median(&mut [1.0]) - 1.0).abs() < 1e-9);
        assert!((median(&mut []) - 0.0).abs() < 1e-9);
    }
}
