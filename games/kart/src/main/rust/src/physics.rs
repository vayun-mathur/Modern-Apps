//! Arcade kart physics: one rigid body per seat, integrated on the race tick.
//!
//! The model is deliberately light: karts steer by heading (no slip angle),
//! drift is a grip/charge tradeoff, and off-track pays a flat penalty. The
//! constants mirror the placeholder ([`crate::race`] re-exports its tuning),
//! so the software race and the native engine drive the same line at the same
//! pace and the Kotlin side can swap them behind `KartNative.isAvailable`.
//!
//! Coordinates: +X right, +Z toward the viewer on the top straight, Y up.
//! A kart with yaw `y` faces `(sin y, -cos y)` in (x, z).

use crate::track;

/// Top speed in m/s at full throttle on asphalt. A 640 m lap takes ~25 s.
pub const TOP_SPEED: f32 = 40.0;
/// Seconds 0 → top speed at full throttle. Also the drag time constant:
///
/// the tick integrates `speed += (accel - braking) * dt - speed * dt /
/// ACCEL_TIME`, so sustained speed settles exactly at `TOP_SPEED * throttle`
/// (at `TOP_SPEED * BOOST_MULT * throttle` while boosting: boost scales the
/// thrust, not just the cap — see [`BOOST_MULT`]).
/// An AI pace of 0.78 then genuinely runs at 78% (mirroring the placeholder's
/// `AI_PACE`), and lifting off coasts down over a couple of seconds.
pub const ACCEL_TIME: f32 = 2.2;
/// Seconds top speed → standstill at full brake.
pub const BRAKE_TIME: f32 = 1.1;
/// Yaw rate in rad/s at full steering lock and full speed.
pub const TURN_RATE: f32 = 2.4;
/// Fraction of speed kept through full lock while drifting (0.88: drifting
/// trades grip for boost charge, not raw pace — matches the placeholder).
pub const DRIFT_KEEP: f32 = 0.88;
/// Fraction lost through full lock without drifting (~18% scrub).
pub const GRIP_SCRUB: f32 = 0.18;
/// Seconds of continuous drift to earn a boost.
pub const DRIFT_CHARGE_TIME: f32 = 1.2;
/// Boost multiplier while the charge burns: scales both the top-speed cap
/// and the engine thrust, so the accel/drag equilibrium rises to
/// `TOP_SPEED * BOOST_MULT * throttle`. Scaling only the cap would grant
/// ~nothing on a straight, where the unboosted equilibrium
/// (`TOP_SPEED * throttle`) already sits below the boosted cap.
pub const BOOST_MULT: f32 = 1.35;
/// Seconds a drift boost burns.
pub const BOOST_TIME: f32 = 1.6;
/// World-space radius that counts as a kart-kart hit.
pub const HIT_RADIUS: f32 = 2.2;

/// One frame of control for one kart, already normalized by the Kotlin
/// fan-in (`GameInputLoop.normalize`): throttle/brake 0..1, steering -1..1,
/// drift held, item edge.
#[derive(Debug, Clone, Copy, Default)]
pub struct DriveInput {
    /// 0..1 forward pedal.
    pub throttle: f32,
    /// -1..1, left negative.
    pub steering: f32,
    /// 0..1 brake pedal.
    pub brake: f32,
    /// Right/left shoulder held.
    pub drift: bool,
    /// Item button, edge-triggered by the caller.
    pub item: bool,
}

/// A kart body: where it is, where it points, how fast it goes.
#[derive(Debug, Clone)]
pub struct KartBody {
    /// World position (y stays 0: the oval is flat).
    pub x: f32,
    /// World position (y stays 0: the oval is flat).
    pub z: f32,
    /// Heading; forward is `(sin yaw, -cos yaw)`.
    pub yaw: f32,
    /// Forward speed in m/s, never negative (braking holds at standstill).
    pub speed: f32,
    /// Arc distance along the centerline; the source of laps and progress.
    pub track_s: f32,
    /// Completed laps.
    pub lap: u32,
    /// Seconds of continuous drift this corner. Resets on release.
    pub drift_charge: f32,
    /// Seconds of boost burn remaining.
    pub boost_left: f32,
    /// Seconds of spin (item hit) remaining; steering is locked while > 0.
    pub spin_left: f32,
}

impl KartBody {
    /// Spawn on the grid: staggered behind the start/finish line, facing -X
    /// down the top straight.
    pub fn spawn(track_s: f32, lane: f32) -> Self {
        let p = track::point_at(track_s);
        Self {
            x: p.x + p.nx * lane,
            z: p.z + p.nz * lane,
            yaw: track::tangent_yaw(&p),
            speed: 0.0,
            track_s,
            lap: 0,
            drift_charge: 0.0,
            boost_left: 0.0,
            spin_left: 0.0,
        }
    }

    /// Advance one tick of `dt` seconds. Clamped to one second: a hitch costs
    /// a frozen frame, never a teleport.
    pub fn tick(&mut self, input: &DriveInput, dt: f32) {
        let dt = dt.clamp(0.0, 1.0);
        if dt <= 0.0 {
            return;
        }
        let throttle = input.throttle.clamp(0.0, 1.0);
        let brake = input.brake.clamp(0.0, 1.0);
        let steering = input.steering.clamp(-1.0, 1.0);

        if self.spin_left > 0.0 {
            // Spun out: bleed speed, rotate, ignore the wheel.
            self.spin_left -= dt;
            self.speed = (self.speed - TOP_SPEED * 1.5 * dt).max(0.0);
            self.yaw += 7.0 * dt;
        } else {
            let grip_loss = steering.abs()
                * if input.drift {
                    1.0 - DRIFT_KEEP
                } else {
                    GRIP_SCRUB
                };
            let mut top = TOP_SPEED * (1.0 - grip_loss);
            let boosting = self.boost_left > 0.0;
            if boosting {
                self.boost_left -= dt;
                top *= BOOST_MULT;
            }
            // Asphalt vs grass: the track sample at our position decides.
            let p = track::point_at(self.track_s);
            // Re-project: nearest sample by lateral distance is approximated
            // by the current arc position (karts move < 1 m per tick at 60 Hz,
            // so the sample never goes stale within a tick).
            if !track::on_track(self.x, self.z, &p) {
                top *= track::OFF_TRACK_KEEP;
            }
            // Boost scales thrust as well as the cap (`top` above), so the
            // equilibrium below rises with it; otherwise a mushroom fired at
            // full speed on a straight (exactly what the AI does) would add
            // no speed at all. Unboosted thrust is unchanged.
            let thrust_mult = if boosting { BOOST_MULT } else { 1.0 };
            let accel = TOP_SPEED / ACCEL_TIME * throttle * thrust_mult;
            let braking = TOP_SPEED / BRAKE_TIME * brake;
            self.speed += (accel - braking) * dt;
            // Linear drag with time constant ACCEL_TIME: sustained speed
            // settles at TOP * throttle (TOP * BOOST_MULT * throttle while
            // boosting), so an AI pace of 0.78 genuinely runs at 78%
            // (mirroring the placeholder's AI_PACE), and coasting to a
            // standstill takes a couple of seconds rather than forever.
            self.speed -= self.speed * dt / ACCEL_TIME;
            self.speed = self.speed.clamp(0.0, top.max(0.0));

            // Steering authority scales with speed (a parked kart turns in
            // place slowly, a fast one bites). Drift keeps angle at speed.
            let authority = (self.speed / TOP_SPEED).clamp(0.15, 1.0);
            let rate = TURN_RATE * authority * if input.drift { 1.35 } else { 1.0 };
            self.yaw += steering * rate * dt;

            if input.drift && steering.abs() > 0.35 && self.speed > TOP_SPEED * 0.4 {
                self.drift_charge += dt;
                if self.drift_charge >= DRIFT_CHARGE_TIME {
                    self.drift_charge = 0.0;
                    self.boost_left = BOOST_TIME;
                }
            } else {
                self.drift_charge = 0.0;
            }
        }

        let (fx, fz) = (self.yaw.sin(), -self.yaw.cos());
        self.x += fx * self.speed * dt;
        self.z += fz * self.speed * dt;
        // Snap the arc position back to the centerline: project forward along
        // the tangent from the old sample. (A full closest-point solve runs in
        // the renderer path only when standings need exact progress.)
        let p = track::point_at(self.track_s);
        let along = (self.x - p.x) * p.tx + (self.z - p.z) * p.tz;
        let advanced = track::advance(self.track_s, along);
        if advanced < self.track_s {
            self.lap += 1;
        }
        self.track_s = advanced;
    }

    /// Fire the held item. Returns true on the rising edge (the caller owns
    /// the latch), applying the boost or shell via the race state.
    pub fn boost_now(&mut self) {
        self.boost_left = BOOST_TIME;
    }

    /// Spin from a shell hit. Steering locks until it burns off.
    pub fn spin(&mut self) {
        self.spin_left = 1.2;
        self.boost_left = 0.0;
    }
}

#[cfg(test)]
mod physics_tests;
#[cfg(test)]
mod tests {
    use super::*;

    fn flat_out() -> DriveInput {
        DriveInput {
            throttle: 1.0,
            ..Default::default()
        }
    }

    #[test]
    fn reaches_top_speed_and_holds_line() {
        // Steer with the track (a lookahead like the AI): the top straight
        // is only 200 m, so a kart that never turns runs wide onto the grass
        // and settles at the off-track equilibrium instead.
        let mut k = KartBody::spawn(8.0, 0.0);
        for _ in 0..1800 {
            let ahead = track::point_at(track::advance(k.track_s, 12.0));
            let desired = (ahead.x - k.x).atan2(-(ahead.z - k.z));
            let err = track::wrap_angle(desired - k.yaw);
            k.tick(
                &DriveInput {
                    throttle: 1.0,
                    steering: (err * 1.6).clamp(-1.0, 1.0),
                    ..Default::default()
                },
                1.0 / 60.0,
            );
        }
        assert!(k.speed > TOP_SPEED * 0.85, "speed={}", k.speed);
        assert!(k.lap >= 1, "lap={} s={}", k.lap, k.track_s);
        let p = track::point_at(k.track_s);
        assert!(track::on_track(k.x, k.z, &p), "ran off at {}", k.track_s);
    }

    #[test]
    fn drift_charges_then_boosts() {
        let mut k = KartBody::spawn(0.0, 0.0);
        k.speed = TOP_SPEED;
        let drift = DriveInput {
            throttle: 1.0,
            steering: 0.8,
            drift: true,
            ..Default::default()
        };
        for _ in 0..90 {
            k.tick(&drift, 1.0 / 60.0);
        }
        assert!(k.boost_left > 0.0, "no boost after 1.5 s of drift");
    }

    #[test]
    fn spin_bleeds_speed() {
        let mut k = KartBody::spawn(0.0, 0.0);
        k.speed = TOP_SPEED;
        k.spin();
        k.tick(&flat_out(), 0.5);
        assert!(k.speed < TOP_SPEED * 0.6, "speed={}", k.speed);
    }

    #[test]
    fn lap_increments_past_the_line() {
        let mut k = KartBody::spawn(track::TRACK_LENGTH - 5.0, 0.0);
        k.speed = TOP_SPEED;
        for _ in 0..120 {
            k.tick(&flat_out(), 1.0 / 60.0);
        }
        assert!(k.lap >= 1, "lap={} s={}", k.lap, k.track_s);
    }
}
