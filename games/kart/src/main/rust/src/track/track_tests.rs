//! Extra deterministic tests for track geometry (sibling of `track::tests`).
use super::*;
use std::f32::consts::PI;

fn approx(a: f32, b: f32) -> bool {
    (a - b).abs() < 1e-4
}

#[test]
fn wrap_handles_zero_full_lap_and_negative() {
    assert!(approx(wrap(0.0), 0.0));
    assert!(approx(wrap(TRACK_LENGTH), 0.0));
    assert!(approx(wrap(-1.0), TRACK_LENGTH - 1.0));
    assert!(approx(wrap(TRACK_LENGTH + 12.5), 12.5));
    assert!(approx(wrap(-TRACK_LENGTH - 3.0), TRACK_LENGTH - 3.0));
}

#[test]
fn wrap_angle_bounds_and_boundaries() {
    assert!(approx(wrap_angle(0.0), 0.0));
    assert!(approx(wrap_angle(PI), PI));
    assert!(approx(wrap_angle(-PI), PI) || approx(wrap_angle(-PI), -PI));
    assert!(approx(wrap_angle(3.0 * PI), PI) || approx(wrap_angle(3.0 * PI), -PI));
    assert!(approx(wrap_angle(2.0 * PI), 0.0));
    assert!(approx(wrap_angle(-2.0 * PI), 0.0));
    assert!(approx(wrap_angle(PI / 2.0), PI / 2.0));
    assert!(approx(wrap_angle(-PI / 2.0), -PI / 2.0));
    for a in [-100.0f32, -7.3, -3.2, 3.2, 7.3, 100.0, 1000.0] {
        let w = wrap_angle(a);
        assert!((-PI..=PI).contains(&w), "wrap_angle({a})={w}");
    }
    // Wrapping by a full turn is identity.
    assert!(approx(wrap_angle(0.37 + 2.0 * PI), 0.37));
    assert!(approx(wrap_angle(0.37 - 4.0 * PI), 0.37));
}

#[test]
fn advance_zero_full_lap_and_negative() {
    assert!(approx(advance(100.0, 0.0), 100.0));
    assert!(approx(advance(100.0, TRACK_LENGTH), 100.0));
    assert!(approx(advance(10.0, -20.0), TRACK_LENGTH - 10.0));
    assert!(approx(advance(0.0, -0.5), TRACK_LENGTH - 0.5));
    let s = advance(TRACK_LENGTH - 0.25, 0.5);
    assert!(approx(s, 0.25));
}

#[test]
fn point_at_negative_and_over_length_match_wrapped() {
    for s in [0.0f32, 1.0, 77.0, 250.0, 500.0, 776.0] {
        let a = point_at(s - TRACK_LENGTH);
        let b = point_at(s);
        assert!(approx(a.x, b.x) && approx(a.z, b.z), "neg s={s}");
        let c = point_at(s + 2.0 * TRACK_LENGTH);
        assert!(approx(c.x, b.x) && approx(c.z, b.z), "over s={s}");
        // Deterministic: repeated calls agree exactly.
        let d = point_at(s);
        assert_eq!(d.x.to_bits(), b.x.to_bits());
        assert_eq!(d.z.to_bits(), b.z.to_bits());
    }
}

#[test]
fn tangents_and_normals_are_unit_and_orthogonal_everywhere() {
    let mut s = 0.0f32;
    while s < TRACK_LENGTH {
        let p = point_at(s);
        assert!(approx(p.tx.hypot(p.tz), 1.0), "tangent s={s}");
        assert!(approx(p.nx.hypot(p.nz), 1.0), "normal s={s}");
        assert!(approx(p.tx * p.nx + p.tz * p.nz, 0.0), "dot s={s}");
        // Normal is tangent rotated: (-tz, tx) by definition in docs.
        assert!(approx(p.nx, -p.tz) && approx(p.nz, p.tx), "rot s={s}");
        s += 13.7;
    }
}

#[test]
fn joint_positions_are_exact() {
    let cap = PI * RADIUS;
    let cases = [
        (0.0, CAP_CX_FOR_TEST, -RADIUS),
        (STRAIGHT, -CAP_CX_FOR_TEST, -RADIUS),
        (STRAIGHT + cap, -CAP_CX_FOR_TEST, RADIUS),
        (2.0 * STRAIGHT + cap, CAP_CX_FOR_TEST, RADIUS),
    ];
    for (s, x, z) in cases {
        let p = point_at(s);
        assert!(approx(p.x, x) && approx(p.z, z), "s={s} got {},{}", p.x, p.z);
    }
    // Full lap returns to start.
    let p = point_at(TRACK_LENGTH);
    let q = point_at(0.0);
    assert!(approx(p.x, q.x) && approx(p.z, q.z));
}

const CAP_CX_FOR_TEST: f32 = STRAIGHT / 2.0;

#[test]
fn segment_midpoints() {
    let top = point_at(STRAIGHT / 2.0);
    assert!(approx(top.x, 0.0) && approx(top.z, -RADIUS));
    let cap = PI * RADIUS;
    let bottom = point_at(STRAIGHT + cap + STRAIGHT / 2.0);
    assert!(approx(bottom.x, 0.0) && approx(bottom.z, RADIUS));
    // Top straight runs at z=-R for its whole length.
    for s in [0.0f32, 50.0, 199.0] {
        assert!(approx(point_at(s).z, -RADIUS));
    }
}

#[test]
fn lateral_projection_sign_and_magnitude() {
    let p = point_at(40.0);
    assert!(approx(lateral(p.x, p.z, &p), 0.0));
    let along_n = lateral(p.x + p.nx * 5.0, p.z + p.nz * 5.0, &p);
    assert!(approx(along_n, 5.0), "along +n: {along_n}");
    let against_n = lateral(p.x - p.nx * 3.0, p.z - p.nz * 3.0, &p);
    assert!(approx(against_n, -3.0));
    // Moving along tangent does not change lateral offset.
    let along_t = lateral(p.x + p.tx * 20.0, p.z + p.tz * 20.0, &p);
    assert!(approx(along_t, 0.0));
}

#[test]
fn lateral_matches_lane_placement_convention() {
    // Docs: positive lateral is along +normal (left of centre). Every lane
    // placement in the crate is `centre + normal * lane`, so lateral must
    // return that lane verbatim — flipping the sign would break that
    // invariant (on_track itself is sign-insensitive: it uses abs()).
    for s in [10.0f32, 250.0, 450.0, 650.0] {
        let p = point_at(s);
        for lane in [-6.0f32, -3.0, 3.0, 6.0] {
            let got = lateral(p.x + p.nx * lane, p.z + p.nz * lane, &p);
            assert!(approx(got, lane), "s={s} lane={lane} got {got}");
        }
    }
}

#[test]
fn on_track_boundary_is_half_width_plus_kerb() {
    for s in [10.0f32, 250.0, 450.0, 650.0] {
        let p = point_at(s);
        let edge = HALF_WIDTH + 0.5;
        assert!(on_track(p.x + p.nx * edge, p.z + p.nz * edge, &p), "edge s={s}");
        assert!(on_track(p.x - p.nx * edge, p.z - p.nz * edge, &p), "-edge s={s}");
        assert!(!on_track(p.x + p.nx * (edge + 0.01), p.z + p.nz * (edge + 0.01), &p));
        assert!(!on_track(p.x - p.nx * (edge + 1.0), p.z - p.nz * (edge + 1.0), &p));
    }
}

#[test]
fn heading_error_zero_opposite_and_offset() {
    let p = point_at(30.0);
    let yaw = tangent_yaw(&p);
    assert!(approx(heading_error(yaw, &p), 0.0));
    let opp = heading_error(yaw + PI, &p);
    assert!(approx(opp.abs(), PI), "opposite err {opp}");
    // Kart yawed +0.3 from tangent: error is -0.3 (wrapped).
    assert!(approx(heading_error(yaw + 0.3, &p), -0.3));
    assert!(approx(heading_error(yaw - 0.3, &p), 0.3));
}

#[test]
fn successive_samples_have_small_heading_change() {
    let mut prev = point_at(0.0);
    let mut s = 1.0f32;
    while s <= TRACK_LENGTH {
        let p = point_at(s);
        let err = heading_error(tangent_yaw(&prev), &p).abs();
        assert!(err < 0.05, "heading jump {err} at s={s}");
        prev = p;
        s += 1.0;
    }
}
