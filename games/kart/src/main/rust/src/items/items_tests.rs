//! Extra deterministic tests for items/AI (sibling of `items::tests`).
use super::*;
use crate::physics::TOP_SPEED;

fn kart_at(x: f32, z: f32) -> KartBody {
    let mut k = KartBody::spawn(0.0, 0.0);
    k.x = x;
    k.z = z;
    k
}

#[test]
fn deal_item_table_by_field_size() {
    // Solo always mushroom.
    assert_eq!(deal_item(0, 1), HeldItem::Mushroom);
    assert_eq!(deal_item(0, 0), HeldItem::Mushroom);
    // Two: leader mushroom, trailer shell.
    assert_eq!(deal_item(0, 2), HeldItem::Mushroom);
    assert_eq!(deal_item(1, 2), HeldItem::Shell);
    // Back half is inclusive of the exact middle in even fields.
    for (pos, want) in [
        (0, HeldItem::Mushroom), (1, HeldItem::Mushroom),
        (2, HeldItem::Shell), (3, HeldItem::Shell),
    ] {
        assert_eq!(deal_item(pos, 4), want, "pos {pos}/4");
    }
    // Odd field: position*2 >= count decides.
    assert_eq!(deal_item(1, 3), HeldItem::Mushroom); // 2 < 3
    assert_eq!(deal_item(2, 3), HeldItem::Shell); // 4 >= 3
    assert_eq!(deal_item(3, 8), HeldItem::Mushroom); // 6 < 8
    assert_eq!(deal_item(4, 8), HeldItem::Shell); // 8 >= 8
}

#[test]
fn rubber_boost_is_stepped_and_floored() {
    assert_eq!(rubber_boost(0.0), 0.0);
    assert_eq!(rubber_boost(-10.0), 0.0);
    assert_eq!(rubber_boost(RUBBER_STEP * 0.99), 0.0);
    assert_eq!(rubber_boost(RUBBER_STEP), RUBBER_GAIN);
    assert_eq!(rubber_boost(RUBBER_STEP * 1.99), RUBBER_GAIN);
    assert_eq!(rubber_boost(RUBBER_STEP * 3.0), RUBBER_GAIN * 3.0);
    // Monotonic non-decreasing over a sweep.
    let mut prev = 0.0;
    for i in 0..50 {
        let b = rubber_boost(i as f32 * 0.01);
        assert!(b >= prev);
        prev = b;
    }
}

#[test]
fn effective_pace_combines_wobble_boost_and_cap() {
    assert!((effective_pace(1.0, 0.0) - AI_PACE).abs() < 1e-6);
    assert!((effective_pace(0.5, 0.0) - AI_PACE * 0.5).abs() < 1e-6);
    assert_eq!(effective_pace(1.0, 100.0), AI_PACE_MAX);
    assert_eq!(effective_pace(0.9, 100.0), AI_PACE_MAX);
    // One step of deficit adds exactly RUBBER_GAIN unless capped.
    let base = effective_pace(0.9, 0.0);
    assert!((effective_pace(0.9, RUBBER_STEP) - (base + RUBBER_GAIN)).abs() < 1e-6);
    // Never exceeds cap, never below base for non-negative deficit.
    for deficit in [0.0f32, 0.03, 0.2, 1.0, 5.0] {
        let p = effective_pace(0.95, deficit);
        assert!(p >= AI_PACE * 0.95 - 1e-6 && p <= AI_PACE_MAX);
    }
}

#[test]
fn ai_driver_wobble_spread() {
    assert!((AiDriver::new(0, 1).wobble - 0.98).abs() < 1e-6);
    assert!((AiDriver::new(0, 4).wobble - 0.98).abs() < 1e-6);
    assert!((AiDriver::new(3, 4).wobble - 0.90).abs() < 1e-6);
    let mid = AiDriver::new(1, 3);
    assert!(mid.wobble > 0.90 && mid.wobble < 0.98);
    assert_eq!(mid.steer, 0.0);
    // First driver is never slower-wobbled than the last.
    assert!(AiDriver::new(0, 8).wobble >= AiDriver::new(7, 8).wobble);
    for i in 0..4 {
        let w = AiDriver::new(i, 4).wobble;
        assert!((0.90..=0.98).contains(&w), "wobble {w}");
    }
}

#[test]
fn ai_control_uses_effective_pace_and_never_brakes_or_fires() {
    let kart = KartBody::spawn(10.0, 0.0);
    let mut ai = AiDriver::new(0, 3);
    let c = ai.control(&kart, 0.0);
    assert!((c.throttle - effective_pace(ai.wobble, 0.0)).abs() < 1e-6);
    assert_eq!(c.brake, 0.0);
    assert!(!c.item);
    assert!((-1.0..=1.0).contains(&c.steering));
    let behind = ai.control(&kart, 0.25);
    assert!(behind.throttle > c.throttle, "rubber band must raise throttle");
    assert!(behind.throttle <= AI_PACE_MAX);
}

#[test]
fn ai_control_steering_is_smoothed() {
    // Face away from the lookahead: target saturates, first output is only
    // 18% of the way there.
    let mut kart = KartBody::spawn(10.0, 0.0);
    kart.yaw += std::f32::consts::PI;
    let mut ai = AiDriver::new(0, 3);
    let first = ai.control(&kart, 0.0);
    assert!(first.steering.abs() <= 0.18 + 1e-5, "first {}", first.steering);
    assert!(first.steering.abs() > 0.05, "must steer toward the line");
    let second = ai.control(&kart, 0.0);
    assert!(second.steering.abs() > first.steering.abs(), "smoothing converges");
    assert_eq!(second.steering.signum(), first.steering.signum());
}

#[test]
fn ai_control_drifts_only_when_corner_is_tight() {
    // Aligned with the track on a straight: no drift.
    let kart = KartBody::spawn(10.0, 0.0);
    let mut ai = AiDriver::new(0, 3);
    let c = ai.control(&kart, 0.0);
    assert!(!c.drift);
    // Pointing backwards: heading error is huge, drift engages.
    let mut backwards = KartBody::spawn(10.0, 0.0);
    backwards.yaw += std::f32::consts::PI;
    let mut ai2 = AiDriver::new(0, 3);
    assert!(ai2.control(&backwards, 0.0).drift);
}

#[test]
fn shell_moves_along_yaw_and_ages() {
    let mut shells = vec![Shell { owner: 9, x: 0.0, z: 0.0, yaw: 0.0, life: 5.0, grace: 0.0 }];
    let hits = tick_shells(&mut shells, &[], 0.1);
    assert!(hits.is_empty() && shells.len() == 1);
    // yaw 0 travels -Z at SHELL_SPEED.
    assert!((shells[0].z + SHELL_SPEED * 0.1).abs() < 1e-3);
    assert!(shells[0].x.abs() < 1e-3);
    assert!((shells[0].life - 4.9).abs() < 1e-4);
    // yaw +90deg travels +X.
    let mut shells = vec![Shell { owner: 9, x: 1.0, z: 2.0, yaw: std::f32::consts::FRAC_PI_2, life: 5.0, grace: 0.0 }];
    tick_shells(&mut shells, &[], 0.1);
    assert!((shells[0].x - (1.0 + SHELL_SPEED * 0.1)).abs() < 1e-3);
    assert!((shells[0].z - 2.0).abs() < 1e-3);
}

#[test]
fn shell_expires_without_hitting() {
    let victim = kart_at(0.0, 0.0);
    let mut shells = vec![Shell { owner: 9, x: 0.0, z: 0.0, yaw: 0.0, life: 0.05, grace: 0.0 }];
    let hits = tick_shells(&mut shells, &[victim], 0.1);
    assert!(hits.is_empty(), "expired shell cannot hit");
    assert!(shells.is_empty());
    // Life exactly consumed also expires.
    let mut shells = vec![Shell { owner: 9, x: 0.0, z: 0.0, yaw: 0.0, life: 0.1, grace: 0.0 }];
    assert!(tick_shells(&mut shells, &[], 0.1).is_empty());
    assert!(shells.is_empty());
}

#[test]
fn shell_hit_radius_boundary() {
    // Shell moves 46*0.001 = 0.046 in -Z first, then hit is tested.
    let shell = || Shell { owner: 9, x: 0.0, z: 0.0, yaw: 0.0, life: 5.0, grace: 0.0 };
    let inside = kart_at(0.0, -0.046 + crate::physics::HIT_RADIUS - 0.1);
    let mut shells = vec![shell()];
    assert_eq!(tick_shells(&mut shells, &[inside], 0.001), vec![0]);
    assert!(shells.is_empty(), "hit consumes the shell");
    let outside = kart_at(0.0, -0.046 + crate::physics::HIT_RADIUS + 0.5);
    let mut shells = vec![shell()];
    assert!(tick_shells(&mut shells, &[outside], 0.001).is_empty());
    assert_eq!(shells.len(), 1, "missed shell flies on");
}

#[test]
fn shell_hits_only_first_kart_and_reports_its_index() {
    let a = kart_at(0.0, -1.0);
    let b = kart_at(0.0, -1.0);
    let mut shells = vec![Shell { owner: 9, x: 0.0, z: 0.0, yaw: 0.0, life: 5.0, grace: 0.0 }];
    let hits = tick_shells(&mut shells, &[a, b], 0.01);
    assert_eq!(hits, vec![0], "one shell, one hit, first kart wins");
    // Victim at index 2 is reported as 2.
    let far = kart_at(100.0, 100.0);
    let near = kart_at(0.0, -1.0);
    let mut shells = vec![Shell { owner: 9, x: 0.0, z: 0.0, yaw: 0.0, life: 5.0, grace: 0.0 }];
    assert_eq!(tick_shells(&mut shells, &[far.clone(), far, near], 0.01), vec![2]);
}

#[test]
fn empty_shells_and_empty_field_are_fine() {
    let mut shells: Vec<Shell> = Vec::new();
    assert!(tick_shells(&mut shells, &[], 0.016).is_empty());
    let mut shells = vec![Shell { owner: 0, x: 0.0, z: 0.0, yaw: 1.0, life: 5.0, grace: 0.0 }];
    assert!(tick_shells(&mut shells, &[], 0.016).is_empty());
    assert_eq!(shells.len(), 1);
    let _ = TOP_SPEED;
}
