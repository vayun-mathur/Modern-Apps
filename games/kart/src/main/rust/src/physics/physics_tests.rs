//! Extra deterministic tests for kart physics (sibling of `physics::tests`).
use super::*;
use crate::track;

fn inp(throttle: f32, steering: f32, brake: f32, drift: bool) -> DriveInput {
    DriveInput { throttle, steering, brake, drift, item: false }
}

#[test]
fn spawn_places_kart_on_centerline_plus_lane() {
    for (s, lane) in [(0.0f32, 0.0f32), (8.0, -3.0), (100.0, 4.5), (400.0, -6.0)] {
        let k = KartBody::spawn(s, lane);
        let p = track::point_at(s);
        assert!((k.x - (p.x + p.nx * lane)).abs() < 1e-4, "x s={s}");
        assert!((k.z - (p.z + p.nz * lane)).abs() < 1e-4, "z s={s}");
        assert!((k.yaw - track::tangent_yaw(&p)).abs() < 1e-4);
        assert_eq!(k.speed, 0.0);
        assert_eq!(k.lap, 0);
        assert_eq!(k.drift_charge, 0.0);
        assert_eq!(k.boost_left, 0.0);
        assert_eq!(k.spin_left, 0.0);
        assert_eq!(k.track_s, track::advance(s, 0.0));
    }
}

#[test]
fn zero_and_negative_dt_are_noops() {
    for dt in [0.0f32, -1.0, -0.001] {
        let mut k = KartBody::spawn(20.0, 1.0);
        k.speed = 12.0;
        let (x, z, yaw, s) = (k.x, k.z, k.yaw, k.track_s);
        k.tick(&inp(1.0, 1.0, 0.0, false), dt);
        assert_eq!((k.x, k.z, k.yaw, k.track_s, k.speed), (x, z, yaw, s, 12.0));
    }
}

#[test]
fn dt_over_one_second_is_clamped_to_one() {
    let mut a = KartBody::spawn(10.0, 0.0);
    let mut b = KartBody::spawn(10.0, 0.0);
    a.tick(&inp(1.0, 0.2, 0.0, false), 5.0);
    b.tick(&inp(1.0, 0.2, 0.0, false), 1.0);
    assert_eq!(a.x.to_bits(), b.x.to_bits());
    assert_eq!(a.z.to_bits(), b.z.to_bits());
    assert_eq!(a.speed.to_bits(), b.speed.to_bits());
    assert_eq!(a.yaw.to_bits(), b.yaw.to_bits());
}

#[test]
fn inputs_are_clamped_to_normalized_ranges() {
    let mut wild = KartBody::spawn(10.0, 0.0);
    let mut tame = KartBody::spawn(10.0, 0.0);
    wild.tick(&inp(9.0, -9.0, 0.0, false), 1.0 / 60.0);
    tame.tick(&inp(1.0, -1.0, 0.0, false), 1.0 / 60.0);
    assert_eq!(wild.speed.to_bits(), tame.speed.to_bits());
    assert_eq!(wild.yaw.to_bits(), tame.yaw.to_bits());
    // Negative throttle/brake behave as zero.
    let mut neg = KartBody::spawn(10.0, 0.0);
    neg.tick(&inp(-3.0, 0.0, -2.0, false), 0.5);
    assert_eq!(neg.speed, 0.0);
}

#[test]
fn braking_never_makes_speed_negative() {
    let mut k = KartBody::spawn(10.0, 0.0);
    k.speed = 1.0;
    for _ in 0..120 {
        k.tick(&inp(0.0, 0.0, 1.0, false), 1.0 / 60.0);
        assert!(k.speed >= 0.0);
    }
    assert_eq!(k.speed, 0.0);
}

#[test]
fn coasting_decays_but_does_not_stop_instantly() {
    let mut k = KartBody::spawn(10.0, 0.0);
    k.speed = TOP_SPEED;
    let mut prev = k.speed;
    for _ in 0..60 {
        k.tick(&inp(0.0, 0.0, 0.0, false), 1.0 / 60.0);
        assert!(k.speed < prev, "coast must decay");
        assert!(k.speed > 0.0);
        prev = k.speed;
    }
    // After 1s of drag (time constant ACCEL_TIME) still well above zero.
    assert!(k.speed > TOP_SPEED * 0.4, "speed={}", k.speed);
}

#[test]
fn parked_kart_steers_at_floor_authority() {
    let mut k = KartBody::spawn(10.0, 0.0);
    let yaw0 = k.yaw;
    k.tick(&inp(0.0, 1.0, 0.0, false), 0.5);
    let expected = TURN_RATE * 0.15 * 0.5;
    assert!((k.yaw - yaw0 - expected).abs() < 1e-4, "yaw delta {}", k.yaw - yaw0);
    // Negative steering turns the other way.
    let mut k2 = KartBody::spawn(10.0, 0.0);
    k2.tick(&inp(0.0, -1.0, 0.0, false), 0.5);
    assert!(k2.yaw < yaw0);
    assert_eq!(k.speed, 0.0);
}

#[test]
fn steering_authority_and_drift_multiplier_scale_turn() {
    let turn = |speed: f32, drift: bool| {
        let mut k = KartBody::spawn(10.0, 0.0);
        k.speed = speed;
        let y0 = k.yaw;
        k.tick(&inp(0.0, 1.0, 0.0, drift), 1.0 / 60.0);
        k.yaw - y0
    };
    assert!(turn(TOP_SPEED, false) > turn(TOP_SPEED * 0.3, false));
    let normal = turn(TOP_SPEED * 0.5, false);
    let drifting = turn(TOP_SPEED * 0.5, true);
    assert!((drifting / normal - 1.35).abs() < 0.02, "ratio {}", drifting / normal);
}

#[test]
fn forward_motion_follows_yaw_convention() {
    // yaw 0 faces -Z.
    let mut k = KartBody::spawn(200.0, 0.0);
    k.yaw = 0.0;
    k.speed = 10.0;
    let (x0, z0) = (k.x, k.z);
    k.tick(&inp(0.0, 0.0, 0.0, false), 0.1);
    assert!((k.x - x0).abs() < 1e-3);
    assert!(k.z < z0, "yaw 0 must move -Z");
    // yaw +90deg faces +X.
    let mut k = KartBody::spawn(200.0, 0.0);
    k.yaw = std::f32::consts::FRAC_PI_2;
    k.speed = 10.0;
    let (x0, z0) = (k.x, k.z);
    k.tick(&inp(0.0, 0.0, 0.0, false), 0.1);
    assert!(k.x > x0, "yaw pi/2 must move +X");
    assert!((k.z - z0).abs() < 1e-3);
}

#[test]
fn drift_charge_needs_speed_steering_and_hold() {
    // Too slow: no charge.
    let mut k = KartBody::spawn(10.0, 0.0);
    k.speed = TOP_SPEED * 0.39;
    for _ in 0..30 { k.tick(&inp(1.0, 0.9, 0.0, true), 1.0 / 60.0); }
    // Speed may have risen under throttle; use a fresh slow kart with no throttle.
    let mut slow = KartBody::spawn(10.0, 0.0);
    slow.speed = TOP_SPEED * 0.3;
    for _ in 0..30 { slow.tick(&inp(0.0, 0.9, 0.0, true), 1.0 / 60.0); }
    assert_eq!(slow.drift_charge, 0.0);
    let _ = k;
    // Steering at exactly 0.35 does not charge (needs > 0.35).
    let mut edge = KartBody::spawn(10.0, 0.0);
    edge.speed = TOP_SPEED;
    for _ in 0..30 { edge.tick(&inp(1.0, 0.35, 0.0, true), 1.0 / 60.0); }
    assert_eq!(edge.drift_charge, 0.0);
    // Just above threshold accumulates ~dt per tick (before any boost).
    let mut ok = KartBody::spawn(10.0, 0.0);
    ok.speed = TOP_SPEED;
    for _ in 0..12 { ok.tick(&inp(1.0, 0.5, 0.0, true), 1.0 / 60.0); }
    assert!(ok.drift_charge > 0.15 && ok.drift_charge <= 12.0 / 60.0 + 1e-4, "{}", ok.drift_charge);
}

#[test]
fn drift_charge_resets_on_release_or_straightening() {
    let mut k = KartBody::spawn(10.0, 0.0);
    k.speed = TOP_SPEED;
    for _ in 0..20 { k.tick(&inp(1.0, 0.8, 0.0, true), 1.0 / 60.0); }
    assert!(k.drift_charge > 0.0);
    k.tick(&inp(1.0, 0.8, 0.0, false), 1.0 / 60.0);
    assert_eq!(k.drift_charge, 0.0);
    // Recharge then straighten the wheel.
    for _ in 0..20 { k.tick(&inp(1.0, 0.8, 0.0, true), 1.0 / 60.0); }
    assert!(k.drift_charge > 0.0);
    k.tick(&inp(1.0, 0.0, 0.0, true), 1.0 / 60.0);
    assert_eq!(k.drift_charge, 0.0);
}

#[test]
fn completed_drift_grants_boost_and_clears_charge() {
    let mut k = KartBody::spawn(10.0, 0.0);
    k.speed = TOP_SPEED;
    let mut granted = false;
    for _ in 0..200 {
        k.tick(&inp(1.0, 0.8, 0.0, true), 1.0 / 60.0);
        if k.boost_left > 0.0 { granted = true; break; }
    }
    assert!(granted, "drift for >DRIFT_CHARGE_TIME must boost");
    assert_eq!(k.drift_charge, 0.0);
}

#[test]
fn boost_now_and_spin_interactions() {
    let mut k = KartBody::spawn(10.0, 0.0);
    k.boost_now();
    assert_eq!(k.boost_left, BOOST_TIME);
    k.spin();
    assert_eq!(k.spin_left, 1.2);
    assert_eq!(k.boost_left, 0.0, "spin cancels boost");
    k.boost_now();
    assert_eq!(k.boost_left, BOOST_TIME, "boost_now works even while spun (by design)");
}

#[test]
fn boost_at_full_speed_on_straight_gains_speed() {
    // Regression: boost used to raise only the cap, but the accel/drag
    // equilibrium sits at TOP_SPEED * throttle below that cap, so a mushroom
    // at full speed on a straight (what the AI does) granted ~nothing.
    let run = |boost: bool| {
        // s=10 is on the 200 m top straight; ~1 s of travel stays on it.
        let mut k = KartBody::spawn(10.0, 0.0);
        k.speed = TOP_SPEED;
        if boost { k.boost_now(); }
        for _ in 0..60 { k.tick(&inp(1.0, 0.0, 0.0, false), 1.0 / 60.0); }
        k.speed
    };
    let plain = run(false);
    let boosted = run(true);
    assert!((plain - TOP_SPEED).abs() < 0.5, "unboosted equilibrium {plain}");
    assert!(boosted > TOP_SPEED + 3.0, "boost must gain speed, got {boosted}");
    assert!(boosted > plain + 3.0, "boosted {boosted} vs plain {plain}");
    assert!(boosted <= TOP_SPEED * BOOST_MULT + 0.01, "over cap: {boosted}");
}

#[test]
fn non_boost_equilibrium_is_unchanged() {
    // Full throttle settles at TOP_SPEED, part throttle at TOP_SPEED*throttle.
    for (throttle, want) in [(1.0f32, TOP_SPEED), (0.5, TOP_SPEED * 0.5)] {
        let mut k = KartBody::spawn(10.0, 0.0);
        k.speed = want;
        for _ in 0..60 { k.tick(&inp(throttle, 0.0, 0.0, false), 1.0 / 60.0); }
        assert!((k.speed - want).abs() < 0.5, "throttle {throttle}: {}", k.speed);
        assert!(k.speed <= TOP_SPEED + 1e-3, "unboosted over cap: {}", k.speed);
    }
}

#[test]
fn boost_expires_after_boost_time() {
    let mut k = KartBody::spawn(10.0, 0.0);
    k.speed = TOP_SPEED;
    k.boost_now();
    for _ in 0..((BOOST_TIME * 60.0) as usize + 5) {
        k.tick(&inp(1.0, 0.0, 0.0, false), 1.0 / 60.0);
    }
    assert!(k.boost_left <= 0.0, "boost_left={}", k.boost_left);
}

#[test]
fn boost_keeps_more_speed_through_full_lock_than_no_boost() {
    let run = |boost: bool| {
        let mut k = KartBody::spawn(10.0, 0.0);
        k.speed = TOP_SPEED;
        if boost { k.boost_now(); }
        k.tick(&inp(1.0, 1.0, 0.0, false), 1.0 / 60.0);
        k.speed
    };
    assert!(run(true) > run(false) + 1.0, "boost must raise the scrubbed cap");
    // Unboosted full-lock speed is capped near TOP*(1-GRIP_SCRUB).
    assert!(run(false) <= TOP_SPEED * (1.0 - GRIP_SCRUB) + 0.6);
}

#[test]
fn spin_locks_steering_rotates_and_then_recovers() {
    let mut k = KartBody::spawn(10.0, 0.0);
    k.speed = TOP_SPEED * 0.8;
    k.spin();
    let y0 = k.yaw;
    k.tick(&inp(1.0, -1.0, 0.0, false), 0.25);
    // Spin rotation dominates: +7 rad/s regardless of -1 steering.
    assert!((k.yaw - y0 - 7.0 * 0.25).abs() < 1e-3, "delta {}", k.yaw - y0);
    assert!(k.speed < TOP_SPEED * 0.8);
    // Burn off the spin (1.2s total), then steering works again.
    for _ in 0..10 { k.tick(&inp(0.0, 0.0, 0.0, false), 0.25); }
    assert!(k.spin_left <= 0.0);
    k.speed = TOP_SPEED * 0.5;
    let y1 = k.yaw;
    k.tick(&inp(0.0, 1.0, 0.0, false), 0.1);
    let delta = k.yaw - y1;
    assert!(delta > 0.0 && delta < 1.0, "post-spin steering delta {delta}");
}

#[test]
fn off_track_caps_speed_but_on_track_does_not() {
    let mut off = KartBody::spawn(10.0, 0.0);
    let p = track::point_at(off.track_s);
    off.x = p.x + p.nx * (track::HALF_WIDTH + 5.0);
    off.z = p.z + p.nz * (track::HALF_WIDTH + 5.0);
    off.speed = TOP_SPEED;
    off.tick(&inp(1.0, 0.0, 0.0, false), 1.0 / 60.0);
    assert!(off.speed <= TOP_SPEED * track::OFF_TRACK_KEEP + 0.6, "off speed {}", off.speed);

    let mut on = KartBody::spawn(10.0, 0.0);
    on.speed = TOP_SPEED;
    on.tick(&inp(1.0, 0.0, 0.0, false), 1.0 / 60.0);
    assert!(on.speed > TOP_SPEED * 0.9, "on speed {}", on.speed);
}

#[test]
fn grip_scrub_exceeds_drift_speed_loss() {
    let run = |drift: bool| {
        let mut k = KartBody::spawn(10.0, 0.0);
        k.speed = TOP_SPEED;
        k.tick(&inp(1.0, 1.0, 0.0, drift), 1.0 / 60.0);
        k.speed
    };
    let grip = run(false);
    let drift = run(true);
    assert!(drift > grip, "drift keeps more speed: {drift} vs {grip}");
    assert!(grip <= TOP_SPEED * (1.0 - GRIP_SCRUB) + 0.6);
    assert!(drift <= TOP_SPEED * DRIFT_KEEP + 0.6);
}

#[test]
fn no_lap_credit_for_mid_track_motion() {
    let mut k = KartBody::spawn(100.0, 0.0);
    k.speed = TOP_SPEED;
    for _ in 0..60 { k.tick(&inp(1.0, 0.0, 0.0, false), 1.0 / 60.0); }
    assert_eq!(k.lap, 0);
    assert!(k.track_s > 100.0);
}
