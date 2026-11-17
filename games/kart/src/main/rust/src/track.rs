//! Sunset Oval: the only track. A rounded-rectangle loop built from two
//! straights and two semicircle end caps, centred on the origin.
//!
//! The centerline is parameterized by arc length ([TRACK_LENGTH] metres), so
//! progress, laps, and AI steering all share one notion of "where on the
//! track". The half-width decides asphalt vs grass; karts outside it pay the
//! off-track penalty from physics.
//!
//! Yaw convention (shared with physics and the camera): a kart with yaw `y`
//! faces `(sin y, -cos y)` in (x, z), so yaw 0 is -Z and positive yaw turns
//! toward +X.

/// Total metres of centerline per lap. Derived from the geometry below
/// (`2 * STRAIGHT + 2 * PI * RADIUS` ≈ 777 m), never hand-set: a length that
/// disagrees with the segments truncates the last cap and the wrap joint jumps.
pub const TRACK_LENGTH: f32 = 2.0 * STRAIGHT + 2.0 * std::f32::consts::PI * RADIUS;
/// Straight section length in metres (two of them).
pub const STRAIGHT: f32 = 200.0;
/// End-cap radius in metres. `2 * STRAIGHT + 2 * PI * RADIUS` is the length.
pub const RADIUS: f32 = 60.0;
/// Half the asphalt width in metres. Karts wider than this are off track.
pub const HALF_WIDTH: f32 = 9.0;
/// Fraction of top speed kept on grass. Punishing but recoverable.
pub const OFF_TRACK_KEEP: f32 = 0.55;

/// A point on the centerline plus the direction of travel and the left normal.
#[derive(Debug, Clone, Copy)]
pub struct TrackPoint {
    /// Centre of the asphalt at this distance.
    pub x: f32,
    /// Centre of the asphalt at this distance.
    pub z: f32,
    /// Unit tangent of travel.
    pub tx: f32,
    /// Unit tangent of travel.
    pub tz: f32,
    /// Unit normal pointing left of travel (`(-tz, tx)`).
    pub nx: f32,
    /// Unit normal pointing left of travel (`(-tz, tx)`).
    pub nz: f32,
}

/// Centre x of the right-hand end cap (the +X semicircle bulge).
const CAP_CX: f32 = STRAIGHT / 2.0;

/// Centre x of the left-hand end cap. Both caps sit on the z = 0 axis.
const CAP_CX_LEFT: f32 = -STRAIGHT / 2.0;

fn wrap(s: f32) -> f32 {
    s.rem_euclid(TRACK_LENGTH)
}

/// Centerline sample at arc distance `s` metres. Wraps past the line.
///
/// Segments: top straight (travel -X at z = -R), left cap (around
/// `CAP_CX_LEFT`, from the top round the -X bulge to the bottom), bottom
/// straight (travel +X at z = +R), right cap (around `CAP_CX` back to the top).
/// Tangents and normals are continuous across every joint.
pub fn point_at(s: f32) -> TrackPoint {
    let s = wrap(s);
    let cap_len = std::f32::consts::PI * RADIUS;
    if s < STRAIGHT {
        // Top straight: from (+CAP_CX, -R) to (-CAP_CX, -R), travel -X.
        TrackPoint {
            x: CAP_CX - s,
            z: -RADIUS,
            tx: -1.0,
            tz: 0.0,
            nx: 0.0,
            nz: -1.0,
        }
    } else if s < STRAIGHT + cap_len {
        // Left cap: theta 0..PI. pos = (cx - R sin t, -R cos t):
        // t=0 is the top joint (-CAP_CX, -R), t=PI/2 the -X bulge, t=PI the
        // bottom joint. Tangent (-cos t, sin t) is -X at t=0 and +X at t=PI.
        let theta = (s - STRAIGHT) / RADIUS;
        let (sin, cos) = theta.sin_cos();
        TrackPoint {
            x: CAP_CX_LEFT - RADIUS * sin,
            z: -RADIUS * cos,
            tx: -cos,
            tz: sin,
            nx: -sin,
            nz: -cos,
        }
    } else if s < 2.0 * STRAIGHT + cap_len {
        // Bottom straight: from (-CAP_CX, +R) to (+CAP_CX, +R), travel +X.
        let t = s - STRAIGHT - cap_len;
        TrackPoint {
            x: CAP_CX_LEFT + t,
            z: RADIUS,
            tx: 1.0,
            tz: 0.0,
            nx: 0.0,
            nz: 1.0,
        }
    } else {
        // Right cap: theta 0..PI. pos = (cx + R sin t, +R cos t):
        // t=0 is the bottom joint (+CAP_CX, +R), t=PI/2 the +X bulge, t=PI
        // the top joint. Tangent (cos t, -sin t) is +X at t=0, -X at t=PI.
        let theta = (s - 2.0 * STRAIGHT - cap_len) / RADIUS;
        let (sin, cos) = theta.sin_cos();
        TrackPoint {
            x: CAP_CX + RADIUS * sin,
            z: RADIUS * cos,
            tx: cos,
            tz: -sin,
            nx: sin,
            nz: cos,
        }
    }
}

/// Signed lateral offset in metres: positive is left of centre, negative
/// right — i.e. along the point's normal (`(-tz, tx)`, see [`TrackPoint`]).
/// Lane offsets everywhere (`KartBody::spawn`, item boxes, the mesh) are
/// likewise `centre + normal * lane`, so `lateral` of a point placed at
/// lane `L` is exactly `L`; the only production caller, [`on_track`], uses
/// the absolute value.
pub fn lateral(x: f32, z: f32, p: &TrackPoint) -> f32 {
    (x - p.x) * p.nx + (z - p.z) * p.nz
}

/// True when the point is on asphalt (with a half-metre kerb of forgiveness).
pub fn on_track(x: f32, z: f32, p: &TrackPoint) -> bool {
    lateral(x, z, p).abs() <= HALF_WIDTH + 0.5
}

/// Advance `s` by `ds` metres, wrapping past the start/finish line.
pub fn advance(s: f32, ds: f32) -> f32 {
    wrap(s + ds)
}

/// Yaw whose forward (`(sin y, -cos y)`) equals the tangent.
pub fn tangent_yaw(p: &TrackPoint) -> f32 {
    p.tx.atan2(-p.tz)
}

/// Signed heading error in radians between kart yaw and track tangent.
pub fn heading_error(yaw: f32, p: &TrackPoint) -> f32 {
    wrap_angle(tangent_yaw(p) - yaw)
}

/// Wrap to [-PI, PI].
pub fn wrap_angle(a: f32) -> f32 {
    let two_pi = 2.0 * std::f32::consts::PI;
    let mut v = a.rem_euclid(two_pi);
    if v > std::f32::consts::PI {
        v -= two_pi;
    }
    v
}

#[cfg(test)]
mod track_tests;
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn length_matches_geometry() {
        let cap = 2.0 * std::f32::consts::PI * RADIUS;
        assert!((TRACK_LENGTH - (2.0 * STRAIGHT + cap)).abs() < 0.01);
    }

    #[test]
    fn straights_run_opposite_directions() {
        let top = point_at(10.0);
        assert!((top.tx + 1.0).abs() < 0.001 && top.tz.abs() < 0.001);
        let bottom = point_at(STRAIGHT + std::f32::consts::PI * RADIUS + 10.0);
        assert!((bottom.tx - 1.0).abs() < 0.001 && bottom.tz.abs() < 0.001);
    }

    #[test]
    fn joints_are_continuous() {
        // Sample densely across every joint; consecutive samples must stay
        // within one metre (at 2 m steps the cap moves ~2 m per step).
        let mut prev = point_at(0.0);
        let mut s = 2.0;
        while s < TRACK_LENGTH + 2.0 {
            let p = point_at(s);
            let dx = p.x - prev.x;
            let dz = p.z - prev.z;
            assert!(dx.hypot(dz) < 3.0, "jump at s={s}: {dx},{dz}");
            prev = p;
            s += 2.0;
        }
    }

    #[test]
    fn cap_midpoints_bulge_outward() {
        let cap_len = std::f32::consts::PI * RADIUS;
        let left = point_at(STRAIGHT + cap_len / 2.0);
        assert!(left.x < CAP_CX_LEFT - RADIUS + 1.0, "left bulge x={}", left.x);
        let right = point_at(2.0 * STRAIGHT + cap_len * 1.5);
        assert!(right.x > CAP_CX + RADIUS - 1.0, "right bulge x={}", right.x);
    }

    #[test]
    fn centre_is_on_track_edges_are_not() {
        let p = point_at(50.0);
        assert!(on_track(p.x, p.z, &p));
        assert!(!on_track(
            p.x + p.nx * (HALF_WIDTH + 2.0),
            p.z + p.nz * (HALF_WIDTH + 2.0),
            &p
        ));
    }

    #[test]
    fn wraps_past_the_line() {
        assert!(advance(TRACK_LENGTH - 1.0, 2.0) < 2.0);
        let a = point_at(TRACK_LENGTH + 5.0);
        let b = point_at(5.0);
        assert!((a.x - b.x).abs() < 0.001 && (a.z - b.z).abs() < 0.001);
    }

    #[test]
    fn tangent_yaw_matches_forward() {
        for s in [0.0, 50.0, 250.0, 400.0, 600.0] {
            let p = point_at(s);
            let yaw = tangent_yaw(&p);
            assert!((yaw.sin() - p.tx).abs() < 1e-5, "s={s} fx mismatch");
            assert!((-yaw.cos() - p.tz).abs() < 1e-5, "s={s} fz mismatch");
            assert!(heading_error(yaw, &p).abs() < 1e-5, "s={s} err");
        }
    }
}
