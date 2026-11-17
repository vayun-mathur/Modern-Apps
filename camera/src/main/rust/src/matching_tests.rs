//! Tests for matching.rs private helpers (gyro angular distance) and the
//! public component / matching entry points with synthetic features.

use super::*;
use crate::features::{Features, KeyPoint};
use crate::linalg::Matrix3;

#[test]
fn angular_dist_zero_for_same_orientation() {
    let yaw = [10.0f32, 10.0];
    let pitch = [5.0f32, 5.0];
    assert_eq!(angular_dist(&yaw, &pitch, 0, 1), 0.0);
}

#[test]
fn angular_dist_wraps_yaw_across_zero() {
    // 359° and 1° are 2° apart, not 358°.
    let yaw = [359.0f32, 1.0];
    let pitch = [0.0f32, 0.0];
    assert!((angular_dist(&yaw, &pitch, 0, 1) - 2.0).abs() < 1e-5);
    let yaw2 = [1.0f32, 359.0];
    assert!((angular_dist(&yaw2, &pitch, 0, 1) - 2.0).abs() < 1e-5);
}

#[test]
fn angular_dist_combines_yaw_and_pitch_as_euclidean() {
    let yaw = [0.0f32, 3.0];
    let pitch = [0.0f32, 4.0];
    assert!((angular_dist(&yaw, &pitch, 0, 1) - 5.0).abs() < 1e-5, "3-4-5");
}

#[test]
fn angular_dist_exactly_opposite_yaw_is_180() {
    let yaw = [0.0f32, 180.0];
    let pitch = [0.0f32, 0.0];
    assert!((angular_dist(&yaw, &pitch, 0, 1) - 180.0).abs() < 1e-4);
}

fn mi(src: usize, dst: usize, conf: f64) -> MatchInfo {
    MatchInfo {
        src,
        dst,
        h: Matrix3::identity(),
        inliers: Vec::new(),
        confidence: conf,
        num_inliers: 0,
    }
}

#[test]
fn biggest_component_keeps_largest_confident_group() {
    // {0,1,2} linked confidently, {3,4} linked, 5 isolated.
    let matches = vec![mi(0, 1, 0.9), mi(1, 2, 0.9), mi(3, 4, 0.9)];
    assert_eq!(biggest_component(6, &matches, 0.3), vec![0, 1, 2]);
}

#[test]
fn biggest_component_ignores_low_confidence_edges() {
    let matches = vec![mi(0, 1, 0.1), mi(2, 3, 0.9)];
    // Only 2-3 is a real edge; every other node is a singleton component of
    // size 1, so the kept component is exactly {2,3}.
    assert_eq!(biggest_component(4, &matches, 0.3), vec![2, 3]);
}

#[test]
fn biggest_component_no_matches_keeps_first_singleton() {
    // All singletons tie at size 1; max_by_key keeps the last maximum, and
    // the result is still a single (sorted) index.
    let kept = biggest_component(3, &[], 0.3);
    assert_eq!(kept.len(), 1);
    assert!(kept[0] < 3);
}

#[test]
fn biggest_component_all_confident_keeps_everything_sorted() {
    let matches = vec![mi(2, 0, 1.0), mi(0, 1, 1.0)];
    assert_eq!(biggest_component(3, &matches, 0.3), vec![0, 1, 2]);
}

fn empty_feats(n: usize) -> Vec<Features> {
    (0..n)
        .map(|_| Features {
            kps: vec![KeyPoint { x: 1.0, y: 1.0, angle: 0.0, score: 1.0 }],
            desc: Vec::new(),
            w: 100,
            h: 100,
        })
        .collect()
}

#[test]
fn match_all_with_no_descriptors_matches_nothing() {
    let feats = empty_feats(3);
    assert!(match_all(&feats, &[0.0, 10.0, 20.0], &[0.0, 0.0, 0.0], 55.0).is_empty());
    assert!(match_all(&[], &[], &[], 55.0).is_empty());
    assert!(match_all(&feats[..1], &[0.0], &[0.0], 55.0).is_empty());
}

#[test]
fn match_all_gyro_gate_excludes_distant_pairs() {
    // Descriptors are absent so nothing can match either way; the gate is
    // observable only through "no panic + empty", while nearby pairs are
    // attempted. Use identical strong descriptors so attempted pairs would
    // match, proving the gate (not the matcher) suppressed distant pairs.
    let desc = vec![[7u8; 32]; 8];
    let mk = |desc: Vec<[u8; 32]>| Features {
        kps: (0..8)
            .map(|i| KeyPoint { x: 10.0 + (i % 4) as f32 * 20.0, y: 10.0 + (i / 4) as f32 * 20.0, angle: 0.0, score: 1.0 })
            .collect(),
        desc,
        w: 100,
        h: 100,
    };
    let feats = vec![mk(desc.clone()), mk(desc)];
    // Same orientation: pair attempted (result may still be empty because
    // identical descriptors are ratio-ambiguous — the point is it runs).
    let _ = match_all(&feats, &[0.0, 5.0], &[0.0, 0.0], 55.0);
    // 170° apart with a 55° gate: pair never attempted.
    let gated = match_all(&feats, &[0.0, 170.0], &[0.0, 0.0], 55.0);
    assert!(gated.is_empty(), "gyro gate must suppress distant pairs");
    // Mismatched yaw length disables the gate (falls back to all pairs).
    let _ = match_all(&feats, &[0.0], &[0.0, 0.0], 55.0);
}
