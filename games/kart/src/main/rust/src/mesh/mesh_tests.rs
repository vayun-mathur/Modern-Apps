//! Extra deterministic tests for mesh builders (sibling of `mesh::tests`).
use super::*;

fn spec(x: f32, z: f32, yaw: f32, seat: usize, ai: bool, spin: f32, boost: bool) -> KartMeshSpec {
    KartMeshSpec { x, z, yaw, seat, ai, spin, boost }
}
fn build(s: KartMeshSpec) -> Vec<KartVertex> {
    let mut out = Vec::new();
    build_kart_mesh(&mut out, s);
    out
}
fn same_mesh(a: &[KartVertex], b: &[KartVertex]) -> bool {
    a.len() == b.len()
        && a.iter().zip(b).all(|(u, v)| u.pos == v.pos && u.color == v.color)
}

#[test]
fn darken_scales_each_channel() {
    assert_eq!(darken([1.0, 0.5, 0.25], 0.5), [0.5, 0.25, 0.125]);
    assert_eq!(darken([0.3, 0.6, 0.9], 0.0), [0.0, 0.0, 0.0]);
    assert_eq!(darken([0.3, 0.6, 0.9], 1.0), [0.3, 0.6, 0.9]);
    assert_eq!(darken([0.0, 0.0, 0.0], 0.55), [0.0, 0.0, 0.0]);
}

#[test]
fn quad_emits_two_triangles_in_order() {
    let mut out = Vec::new();
    let (a, b, c, d) = ([0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 0.0, 1.0], [1.0, 0.0, 1.0]);
    quad(&mut out, a, b, c, d, [1.0, 0.0, 0.0]);
    assert_eq!(out.len(), 6);
    assert_eq!([out[0].pos, out[1].pos, out[2].pos], [a, b, c]);
    assert_eq!([out[3].pos, out[4].pos, out[5].pos], [c, b, d]);
    assert!(out.iter().all(|v| v.color == [1.0, 0.0, 0.0]));
}

#[test]
fn box_at_emits_five_faces_within_bounds() {
    let mut out = Vec::new();
    box_at(&mut out, [10.0, 2.0, -5.0], [4.0, 3.0, 2.0], [0.8, 0.4, 0.2]);
    assert_eq!(out.len(), 30, "5 quads x 6 verts, no bottom face");
    for v in &out {
        assert!((8.0..=12.0).contains(&v.pos[0]));
        assert!((2.0..=5.0).contains(&v.pos[1]), "y is bottom-centre based");
        assert!((-6.0..=-4.0).contains(&v.pos[2]));
        assert!(v.color.iter().all(|c| (0.0..=0.8).contains(c)));
    }
    // Top face reaches y=5, and some face uses the undarkened colour.
    assert!(out.iter().any(|v| v.pos[1] == 5.0));
    assert!(out.iter().any(|v| v.color == [0.8, 0.4, 0.2]));
}

#[test]
fn kart_mesh_vertex_count_is_stable_across_variants() {
    let base = build(spec(0.0, 0.0, 0.0, 0, false, 0.0, false));
    assert_eq!(base.len(), 168, "body 30 + cabin 18 + 4 wheels x 30");
    for s in [
        spec(5.0, -3.0, 1.2, 3, true, 0.0, false),
        spec(0.0, 0.0, 0.0, 0, false, 0.7, false),
        spec(0.0, 0.0, 0.0, 0, false, 0.0, true),
        spec(0.0, 0.0, 0.0, 7, true, 0.3, true),
    ] {
        assert_eq!(build(s).len(), base.len());
    }
    assert!(base.iter().all(|v| v.pos.iter().all(|c| c.is_finite())));
    assert!(base.iter().all(|v| v.color.iter().all(|c| (0.0..=1.0).contains(c))));
}

#[test]
fn kart_mesh_seat_color_wraps_and_ai_darkens() {
    assert_eq!(KART_COLORS.len(), 8);
    let seat0 = build(spec(0.0, 0.0, 0.0, 0, false, 0.0, false));
    let seat8 = build(spec(0.0, 0.0, 0.0, 8, false, 0.0, false));
    assert!(same_mesh(&seat0, &seat8), "seat index wraps modulo palette");
    // First vertex is the body top in the seat colour.
    assert_eq!(seat0[0].color, KART_COLORS[0]);
    let ai = build(spec(0.0, 0.0, 0.0, 0, true, 0.0, false));
    assert_eq!(ai[0].color, darken(KART_COLORS[0], AI_DARKEN));
    let seat1 = build(spec(0.0, 0.0, 0.0, 1, false, 0.0, false));
    assert_eq!(seat1[0].color, KART_COLORS[1]);
    assert_ne!(seat0[0].color, seat1[0].color);
    // Palette entries are pairwise distinct.
    for i in 0..KART_COLORS.len() {
        for j in i + 1..KART_COLORS.len() {
            assert_ne!(KART_COLORS[i], KART_COLORS[j]);
        }
    }
}

#[test]
fn kart_mesh_translates_with_position_and_changes_with_spin_and_boost() {
    let a = build(spec(0.0, 0.0, 0.3, 0, false, 0.0, false));
    let b = build(spec(10.0, -7.0, 0.3, 0, false, 0.0, false));
    for (u, v) in a.iter().zip(&b) {
        assert!((v.pos[0] - u.pos[0] - 10.0).abs() < 1e-4);
        assert!((v.pos[1] - u.pos[1]).abs() < 1e-4);
        assert!((v.pos[2] - u.pos[2] + 7.0).abs() < 1e-4);
    }
    assert!(!same_mesh(&a, &build(spec(0.0, 0.0, 0.3, 0, false, 0.9, false))), "spin rotates");
    assert!(!same_mesh(&a, &build(spec(0.0, 0.0, 0.3, 0, false, 0.0, true))), "boost stretches");
    // Deterministic for identical specs.
    assert!(same_mesh(&a, &build(spec(0.0, 0.0, 0.3, 0, false, 0.0, false))));
}

#[test]
fn box_and_shell_markers_have_expected_shape() {
    let mut out = Vec::new();
    build_box_mesh(&mut out, 4.0, -2.0, 0);
    assert_eq!(out.len(), 30);
    // box_at darkens side faces; the top keeps the base colour.
    assert!(out.iter().any(|v| v.color == BOX_COLOR));
    assert!(out.iter().all(|v| v.color[0] <= BOX_COLOR[0] && v.color[1] <= BOX_COLOR[1] && v.color[2] <= BOX_COLOR[2]));
    // At ticks=0 the bob is 0.9, box is 1.2 tall starting there.
    assert!(out.iter().all(|v| (0.9..=2.1 + 1e-4).contains(&v.pos[1])));
    assert!(out.iter().all(|v| (3.4..=4.6).contains(&v.pos[0])));
    // Later ticks bob the box to a different height.
    let mut later = Vec::new();
    build_box_mesh(&mut later, 4.0, -2.0, 15);
    assert_ne!(out[0].pos[1], later[0].pos[1]);

    let mut s0 = Vec::new();
    build_shell_mesh(&mut s0, 1.0, 1.0, 0);
    let mut s1 = Vec::new();
    build_shell_mesh(&mut s1, 1.0, 1.0, 999_999);
    assert_eq!(s0.len(), 30);
    assert!(s0.iter().any(|v| v.color == SHELL_COLOR));
    assert!(same_mesh(&s0, &s1), "shell mesh ignores ticks");
}

#[test]
fn track_mesh_contains_each_surface_kind() {
    let mesh = build_track_mesh();
    assert!(mesh.iter().any(|v| v.pos[1] == WALL_HEIGHT), "wall tops exist");
    assert!(mesh.iter().any(|v| (v.pos[1] + 0.05).abs() < 1e-6), "grass at -0.05");
    assert!(mesh.iter().any(|v| v.pos[1] == 0.0), "asphalt/walls at ground");
    assert!(mesh.iter().all(|v| (-0.05..=WALL_HEIGHT).contains(&v.pos[1])));
    // Start-line checker appears before plain asphalt does.
    assert!(mesh.iter().take(6).all(|v| v.color == CHECKER_A));
    assert!(mesh.iter().any(|v| v.color == CHECKER_B));
    assert!(mesh.iter().any(|v| v.color == ASPHALT));
    assert!(mesh.iter().any(|v| v.color == GRASS_A || v.color == GRASS_B));
    assert!(mesh.iter().any(|v| v.color == WALL_A));
    assert!(mesh.iter().any(|v| v.color == WALL_B));
}
