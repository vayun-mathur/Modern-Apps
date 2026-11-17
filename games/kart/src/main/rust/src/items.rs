//! Items and AI: what makes a race a race rather than a time trial.
//!
//! One item type per tier: a boost mushroom for the back of the pack, a
//! green shell that spins whoever it touches. AI drivers follow the racing
//! line with per-driver pace and wobble, and fire shells forward when they
//! hold one — beatable but not statues, mirroring the placeholder's 0.78 pace.
//! Backmarkers get a rubber-band pace boost that scales with their deficit
//! behind the leader (capped, so they close in without teleporting past);
//! the leader always runs base pace — no leader slowdown, by design.

use crate::physics::{DriveInput, KartBody};
use crate::track;

/// Races per item box respawn in seconds.
pub const BOX_RESPAWN: f32 = 8.0;
/// Shell travel speed in m/s, slightly above kart top speed.
pub const SHELL_SPEED: f32 = 46.0;
/// Shell lifetime in seconds before it fizzles.
pub const SHELL_LIFE: f32 = 6.0;
/// Item-box grab radius in metres.
pub const BOX_RADIUS: f32 = 3.5;
/// AI pace as a fraction of top speed, before the per-driver wobble.
pub const AI_PACE: f32 = 0.78;
/// Rubber-band gain: pace points earned per [RUBBER_STEP] laps of deficit.
pub const RUBBER_GAIN: f32 = 0.02;
/// Deficit step (in laps, fractional) that earns one [RUBBER_GAIN].
pub const RUBBER_STEP: f32 = 0.05;
/// Hard cap on effective AI pace: backmarkers close in, never teleport past.
pub const AI_PACE_MAX: f32 = 0.95;

/// What a kart is holding. One slot: firing spends it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum HeldItem {
    /// Empty slot.
    #[default]
    None,
    /// Short boost, fired with the item button.
    Mushroom,
    /// Shell fired forward along the kart's nose.
    Shell,
}

/// A pickup box on the centerline. Taken boxes respawn on a timer.
#[derive(Debug, Clone)]
pub struct ItemBox {
    /// Arc position of the box.
    pub track_s: f32,
    /// Lane offset in metres (alternates left/right down the track).
    pub lane: f32,
    /// Seconds until it comes back after being taken.
    pub respawn_left: f32,
}

/// A live shell: owner, position, heading, remaining life.
#[derive(Debug, Clone)]
pub struct Shell {
    /// Who fired it (immune to their own shell for the first half second).
    pub owner: usize,
    /// World position.
    pub x: f32,
    /// World position.
    pub z: f32,
    /// Heading; travels straight.
    pub yaw: f32,
    /// Seconds left before it fizzles.
    pub life: f32,
    /// Grace timer for the owner.
    pub grace: f32,
}

/// Assign an item by race position: leaders get mushrooms (defence), the back
/// gets shells (attack). Position 0 is the leader.
pub fn deal_item(position: usize, kart_count: usize) -> HeldItem {
    if kart_count <= 1 {
        return HeldItem::Mushroom;
    }
    let back_half = position * 2 >= kart_count;
    if back_half {
        HeldItem::Shell
    } else {
        HeldItem::Mushroom
    }
}

/// One AI driver: pace wobble plus a steering lookahead.
#[derive(Debug, Clone)]
pub struct AiDriver {
    /// 0.90..=0.98 per-driver pace multiplier (mirrors the placeholder wobble).
    pub wobble: f32,
    /// Current steering output -1..1, smoothed so AI karts don't jitter.
    pub steer: f32,
}

/// Rubber-band boost in pace points for a deficit of [deficit_laps] laps
/// behind the leader (fractional, clamped at zero below).
///
/// Formula: `floor(deficit / RUBBER_STEP) * RUBBER_GAIN` — +0.02 pace per 5%
/// of a lap down. Steps (not a smooth ramp) so mid-pack pace doesn't jitter
/// every tick as gaps breathe; the leader (`deficit <= 0`) gets nothing.
/// The cap lives in [`effective_pace`], not here.
pub fn rubber_boost(deficit_laps: f32) -> f32 {
    if deficit_laps <= 0.0 {
        return 0.0;
    }
    (deficit_laps / RUBBER_STEP).floor() * RUBBER_GAIN
}

/// Effective AI pace for a driver with [wobble] sitting [deficit_laps] behind
/// the leader: base pace times wobble, plus the rubber-band boost, capped at
/// [`AI_PACE_MAX`].
///
/// The cap is what keeps the field together without teleporting: a far-back
/// rival closes at most ~0.95 pace, still beatable flat-out by a clean human
/// lap, while the leader always runs bare `AI_PACE * wobble` (no slowdown —
/// punishing clean driving up front felt worse in playtesting than letting a
/// spun-out rival catch back up).
pub fn effective_pace(wobble: f32, deficit_laps: f32) -> f32 {
    (AI_PACE * wobble + rubber_boost(deficit_laps)).min(AI_PACE_MAX)
}

impl AiDriver {
    /// Create driver `index` of `count`: spread the wobble across the field.
    pub fn new(index: usize, count: usize) -> Self {
        let spread = if count <= 1 {
            0.0
        } else {
            index as f32 / (count - 1) as f32
        };
        Self {
            wobble: 0.90 + 0.08 * (1.0 - spread),
            steer: 0.0,
        }
    }

    /// Drive one tick: steer toward a lookahead point on the racing line,
    /// hold the throttle at pace (plus the rubber-band boost for
    /// [deficit_laps] behind the leader), drift when the corner is tight.
    pub fn control(&mut self, kart: &KartBody, deficit_laps: f32) -> DriveInput {
        let ahead_s = track::advance(kart.track_s, 12.0);
        let ahead = track::point_at(ahead_s);
        let dx = ahead.x - kart.x;
        let dz = ahead.z - kart.z;
        let desired = dx.atan2(-dz);
        let err = track::wrap_angle(desired - kart.yaw);
        let target = (err * 1.6).clamp(-1.0, 1.0);
        // Low-pass the wheel: AI karts corner, they don't vibrate.
        self.steer += (target - self.steer) * 0.18;
        let tight = err.abs() > 0.45;
        DriveInput {
            throttle: effective_pace(self.wobble, deficit_laps),
            steering: self.steer,
            brake: 0.0,
            drift: tight,
            item: false,
        }
    }
}

/// Tick all live shells: move, age, and spin whoever they touch (except the
/// owner during grace). Returns the indices of karts that were hit.
pub fn tick_shells(shells: &mut Vec<Shell>, karts: &[KartBody], dt: f32) -> Vec<usize> {
    let mut hits = Vec::new();
    shells.retain_mut(|s| {
        s.life -= dt;
        if s.life <= 0.0 {
            return false;
        }
        s.grace -= dt;
        s.x += s.yaw.sin() * SHELL_SPEED * dt;
        s.z += -s.yaw.cos() * SHELL_SPEED * dt;
        for (i, k) in karts.iter().enumerate() {
            if i == s.owner && s.grace > 0.0 {
                continue;
            }
            let dx = k.x - s.x;
            let dz = k.z - s.z;
            if dx.hypot(dz) < crate::physics::HIT_RADIUS {
                hits.push(i);
                return false;
            }
        }
        true
    });
    hits
}

#[cfg(test)]
mod items_tests;
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn leaders_defend_back_attacks() {
        assert_eq!(deal_item(0, 8), HeldItem::Mushroom);
        assert_eq!(deal_item(7, 8), HeldItem::Shell);
    }

    #[test]
    fn ai_stays_on_track() {
        let mut kart = KartBody::spawn(0.0, 0.0);
        kart.speed = crate::physics::TOP_SPEED * AI_PACE;
        let mut ai = AiDriver::new(0, 4);
        for _ in 0..1200 {
            let c = ai.control(&kart, 0.0);
            kart.tick(&c, 1.0 / 60.0);
        }
        let p = track::point_at(kart.track_s);
        assert!(
            track::on_track(kart.x, kart.z, &p),
            "ai off at s={} ({},{})",
            kart.track_s,
            kart.x,
            kart.z
        );
    }

    #[test]
    fn backmarker_gets_pace_boost_leader_gets_none() {
        let wobble = 0.95;
        let base = AI_PACE * wobble;
        assert_eq!(rubber_boost(0.0), 0.0, "leader runs bare base pace");
        assert_eq!(effective_pace(wobble, 0.0), base);
        assert!(
            effective_pace(wobble, 0.10) > base,
            "a tenth of a lap down should earn a boost"
        );
    }

    #[test]
    fn boost_steps_and_shrinks_as_deficit_closes() {
        // One step (5% of a lap) earns exactly RUBBER_GAIN a step.
        assert_eq!(rubber_boost(RUBBER_STEP), RUBBER_GAIN);
        assert_eq!(rubber_boost(RUBBER_STEP * 2.0), RUBBER_GAIN * 2.0);
        // Below one step earns nothing; closing the gap shrinks the boost.
        assert_eq!(rubber_boost(RUBBER_STEP * 0.5), 0.0);
        assert!(
            rubber_boost(0.20) > rubber_boost(0.05),
            "closer to the leader means less boost"
        );
        // Non-positive deficits (leader, rounding noise) never boost.
        assert_eq!(rubber_boost(-0.5), 0.0);
    }

    #[test]
    fn pace_cap_holds_for_far_backmarkers() {
        assert!(
            effective_pace(1.0, 10.0) <= AI_PACE_MAX,
            "even a lap down stays at or under the cap"
        );
        assert!(
            effective_pace(0.90, 10.0) <= AI_PACE_MAX,
            "slow-wobble drivers cap too"
        );
    }

    #[test]
    fn shell_hits_a_kart_ahead() {
        let owner = KartBody::spawn(0.0, 0.0);
        let mut victim = KartBody::spawn(10.0, 0.0);
        victim.speed = 0.0;
        let mut shells = vec![Shell {
            owner: 0,
            x: owner.x,
            z: owner.z,
            yaw: owner.yaw,
            life: SHELL_LIFE,
            grace: 0.0,
        }];
        let karts = vec![owner, victim];
        let mut hit = false;
        for _ in 0..120 {
            if !tick_shells(&mut shells, &karts, 1.0 / 60.0).is_empty() {
                hit = true;
                break;
            }
        }
        assert!(hit, "shell never reached the kart ahead");
    }

    #[test]
    fn shell_grace_spares_owner_then_hits() {
        let owner = KartBody::spawn(0.0, 0.0);
        let karts = vec![owner.clone()];
        let mut shells = vec![Shell {
            owner: 0,
            x: owner.x,
            z: owner.z,
            yaw: owner.yaw,
            life: SHELL_LIFE,
            grace: 0.5,
        }];
        let hits = tick_shells(&mut shells, &karts, 1.0 / 60.0);
        assert!(hits.is_empty(), "grace should spare the owner");
        assert_eq!(shells.len(), 1, "graced shell must survive");
        let mut shells = vec![Shell {
            owner: 0,
            x: owner.x,
            z: owner.z,
            yaw: owner.yaw,
            life: SHELL_LIFE,
            grace: 0.0,
        }];
        let hits = tick_shells(&mut shells, &karts, 1.0 / 60.0);
        assert_eq!(hits, vec![0], "expired grace must hit the owner");
        assert!(shells.is_empty(), "hit shell is consumed");
    }
}
