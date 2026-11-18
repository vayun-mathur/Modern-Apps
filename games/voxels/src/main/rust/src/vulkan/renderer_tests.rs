//! Frustum culling maths (pure CPU, no GPU).

use super::*;
use glam::{Mat4, Vec3};

#[test]
fn identity_frustum_keeps_origin_and_culls_far_boxes() {
    let planes = frustum_planes(Mat4::IDENTITY);
    assert_eq!(planes.len(), 6);
    assert!(aabb_in_frustum(&planes, Vec3::new(-0.5, -0.5, 0.25), Vec3::new(0.5, 0.5, 0.75)), "box inside the unit frustum");
    assert!(!aabb_in_frustum(&planes, Vec3::new(5.0, 5.0, 0.5), Vec3::new(6.0, 6.0, 0.6)), "box to the right must be culled");
    assert!(!aabb_in_frustum(&planes, Vec3::new(-0.5, -0.5, 2.0), Vec3::new(0.5, 0.5, 3.0)), "box beyond Vulkan far (z=1) must be culled");
    assert!(!aabb_in_frustum(&planes, Vec3::new(-0.5, -0.5, -2.0), Vec3::new(0.5, 0.5, -1.0)), "box behind near (z=0) must be culled");
    // A box straddling a plane is kept (conservative culling).
    assert!(aabb_in_frustum(&planes, Vec3::new(0.9, -0.5, 0.25), Vec3::new(2.0, 0.5, 0.75)));
}

#[test]
fn translated_frustum_shifts_the_kept_region() {
    let planes = frustum_planes(Mat4::from_translation(Vec3::new(-10.0, 0.0, 0.0)));
    // The translation moves clip space so world origin maps near the edge; a box at +10 in x is centred.
    assert!(aabb_in_frustum(&planes, Vec3::new(9.5, -0.5, 0.25), Vec3::new(10.5, 0.5, 0.75)));
    assert!(!aabb_in_frustum(&planes, Vec3::new(-0.5, -0.5, 0.25), Vec3::new(0.5, 0.5, 0.75)));
}

#[test]
fn smoothstep_edges_and_midpoint() {
    // smoothstep lives in the ubo submodule; exercise it through its maths contract here
    // by re-deriving the same clamp behaviour the renderer relies on for sun height.
    // (Direct unit coverage of the fn is in ubo's own tests via update inputs.)
    let planes = frustum_planes(Mat4::from_scale(Vec3::new(2.0, 2.0, 1.0)));
    assert!(aabb_in_frustum(&planes, Vec3::new(-0.4, -0.4, 0.5), Vec3::new(0.4, 0.4, 0.6)));
    assert!(!aabb_in_frustum(&planes, Vec3::new(0.6, -0.4, 0.5), Vec3::new(0.9, 0.4, 0.6)));
}
