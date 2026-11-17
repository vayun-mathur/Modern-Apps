//! Track + kart meshes: flat-shaded vertex-color triangles, no textures.
//!
//! The oval is a triangle strip swept along the centerline: asphalt quads in
//! the middle, grass skirts on both sides out to the draw distance, and a
//! low outer wall so karts read as contained. Karts are box bodies (body +
//! cabin + four wheels) tinted per seat; shells and item boxes are small
//! spinning markers. Everything is rebuilt only when the race starts — per
//! frame the renderer re-uploads the dynamic parts (karts, shells).

use crate::track;

/// One vertex: position + flat color. Matches `kart.vert` locations 0/1.
#[derive(Clone, Copy, Debug, Default)]
#[repr(C)]
pub struct KartVertex {
    /// World position.
    pub pos: [f32; 3],
    /// Flat RGB.
    pub color: [f32; 3],
}

/// Asphalt gray.
pub const ASPHALT: [f32; 3] = [0.22, 0.23, 0.25];
/// Start/finish checker: alternating squares near s = 0.
pub const CHECKER_A: [f32; 3] = [0.92, 0.92, 0.92];
/// Start/finish checker dark squares.
pub const CHECKER_B: [f32; 3] = [0.08, 0.08, 0.08];
/// Grass green (two tones checkerboarded so speed reads).
pub const GRASS_A: [f32; 3] = [0.24, 0.55, 0.25];
/// Grass green dark.
pub const GRASS_B: [f32; 3] = [0.19, 0.47, 0.21];
/// Outer wall red/white.
pub const WALL_A: [f32; 3] = [0.80, 0.16, 0.16];
/// Outer wall white.
pub const WALL_B: [f32; 3] = [0.92, 0.92, 0.92];
/// Item-box cyan.
pub const BOX_COLOR: [f32; 3] = [0.15, 0.75, 0.85];
/// Shell green.
pub const SHELL_COLOR: [f32; 3] = [0.25, 0.80, 0.30];
/// Per-seat kart body colors (up to 8 seats).
pub const KART_COLORS: [[f32; 3]; 8] = [
    [0.85, 0.20, 0.20],
    [0.20, 0.45, 0.85],
    [0.20, 0.70, 0.30],
    [0.90, 0.75, 0.15],
    [0.70, 0.30, 0.85],
    [0.90, 0.45, 0.15],
    [0.20, 0.80, 0.80],
    [0.85, 0.85, 0.85],
];
/// AI karts read as rivals: darkened version of the seat color.
pub const AI_DARKEN: f32 = 0.55;

/// Metres between centerline samples for the mesh sweep.
pub const MESH_STEP: f32 = 4.0;
/// Grass skirt extent on each side of the asphalt, in metres.
pub const GRASS_EXTENT: f32 = 40.0;
/// Wall height in metres.
pub const WALL_HEIGHT: f32 = 1.2;

/// Push a quad (two triangles) into `out`.
fn quad(
    out: &mut Vec<KartVertex>,
    a: [f32; 3],
    b: [f32; 3],
    c: [f32; 3],
    d: [f32; 3],
    color: [f32; 3],
) {
    for p in [a, b, c, c, b, d] {
        out.push(KartVertex { pos: p, color });
    }
}

/// Push an axis-aligned box into `out`: `center` is the bottom-centre
/// ([x, y, z]) and `size` the full extents ([sx, sy, sz]).
fn box_at(out: &mut Vec<KartVertex>, center: [f32; 3], size: [f32; 3], color: [f32; 3]) {
    let (x, y, z) = (center[0], center[1], center[2]);
    let (sx, sy, sz) = (size[0], size[1], size[2]);
    let x0 = x - sx / 2.0;
    let x1 = x + sx / 2.0;
    let y0 = y;
    let y1 = y + sy;
    let z0 = z - sz / 2.0;
    let z1 = z + sz / 2.0;
    // Top.
    quad(
        out,
        [x0, y1, z0],
        [x1, y1, z0],
        [x0, y1, z1],
        [x1, y1, z1],
        color,
    );
    // Front (+Z face toward the viewer on the top straight).
    quad(
        out,
        [x0, y0, z1],
        [x1, y0, z1],
        [x0, y1, z1],
        [x1, y1, z1],
        darken(color, 0.85),
    );
    // Back.
    quad(
        out,
        [x1, y0, z0],
        [x0, y0, z0],
        [x1, y1, z0],
        [x0, y1, z0],
        darken(color, 0.85),
    );
    // Left.
    quad(
        out,
        [x0, y0, z0],
        [x0, y0, z1],
        [x0, y1, z0],
        [x0, y1, z1],
        darken(color, 0.7),
    );
    // Right.
    quad(
        out,
        [x1, y0, z1],
        [x1, y0, z0],
        [x1, y1, z1],
        [x1, y1, z0],
        darken(color, 0.7),
    );
}

fn darken(c: [f32; 3], k: f32) -> [f32; 3] {
    [c[0] * k, c[1] * k, c[2] * k]
}

/// Build the static track mesh: asphalt + start line + grass + walls.
pub fn build_track_mesh() -> Vec<KartVertex> {
    let mut out = Vec::new();
    let steps = (track::TRACK_LENGTH / MESH_STEP).ceil() as usize;
    for i in 0..steps {
        let s0 = i as f32 * MESH_STEP;
        let s1 = ((i + 1) as f32 * MESH_STEP).min(track::TRACK_LENGTH);
        let p0 = track::point_at(s0);
        let p1 = track::point_at(s1);
        let hw = track::HALF_WIDTH;
        // Asphalt quad.
        let a0 = [p0.x - p0.nx * hw, 0.0, p0.z - p0.nz * hw];
        let b0 = [p0.x + p0.nx * hw, 0.0, p0.z + p0.nz * hw];
        let a1 = [p1.x - p1.nx * hw, 0.0, p1.z - p1.nz * hw];
        let b1 = [p1.x + p1.nx * hw, 0.0, p1.z + p1.nz * hw];
        // Start/finish checker for the first two quads.
        let color = if s0 < MESH_STEP * 2.0 {
            if i % 2 == 0 { CHECKER_A } else { CHECKER_B }
        } else {
            ASPHALT
        };
        quad(&mut out, a0, b0, a1, b1, color);
        // Grass skirts: 2 bands per side, checkerboarded.
        for side in [-1.0, 1.0] {
            for band in 0..2 {
                let g0 = hw + band as f32 * GRASS_EXTENT / 2.0;
                let g1 = hw + (band + 1) as f32 * GRASS_EXTENT / 2.0;
                let c0 = [p0.x + p0.nx * side * g0, -0.05, p0.z + p0.nz * side * g0];
                let d0 = [p0.x + p0.nx * side * g1, -0.05, p0.z + p0.nz * side * g1];
                let c1 = [p1.x + p1.nx * side * g0, -0.05, p1.z + p1.nz * side * g0];
                let d1 = [p1.x + p1.nx * side * g1, -0.05, p1.z + p1.nz * side * g1];
                let g = if (i + band + (if side > 0.0 { 1 } else { 0 })) % 2 == 0 {
                    GRASS_A
                } else {
                    GRASS_B
                };
                if side > 0.0 {
                    quad(&mut out, c0, d0, c1, d1, g);
                } else {
                    quad(&mut out, d0, c0, d1, c1, g);
                }
            }
        }
        // Outer walls at the asphalt edge (both sides), red/white blocks.
        let wall = if i % 2 == 0 { WALL_A } else { WALL_B };
        for side in [-1.0, 1.0] {
            let w0 = hw + 0.6;
            let e0 = [p0.x + p0.nx * side * w0, 0.0, p0.z + p0.nz * side * w0];
            let e1 = [p1.x + p1.nx * side * w0, 0.0, p1.z + p1.nz * side * w0];
            let f0 = [e0[0], WALL_HEIGHT, e0[2]];
            let f1 = [e1[0], WALL_HEIGHT, e1[2]];
            quad(&mut out, e0, e1, f0, f1, wall);
        }
    }
    out
}

/// Placement and identity for one kart in [`build_kart_mesh`].
#[derive(Clone, Copy, Debug)]
pub struct KartMeshSpec {
    /// World X of the kart centre.
    pub x: f32,
    /// World Z of the kart centre.
    pub z: f32,
    /// Heading in radians.
    pub yaw: f32,
    /// Seat index (selects the body colour).
    pub seat: usize,
    /// AI karts use a darkened body colour.
    pub ai: bool,
    /// Extra rotation in radians (item-hit feedback).
    pub spin: f32,
    /// Stretch the body slightly (boost feedback).
    pub boost: bool,
}

/// Build one kart mesh from `spec` into `out`.
pub fn build_kart_mesh(out: &mut Vec<KartVertex>, spec: KartMeshSpec) {
    let KartMeshSpec { x, z, yaw, seat, ai, spin, boost } = spec;
    let mut color = KART_COLORS[seat % KART_COLORS.len()];
    if ai {
        color = darken(color, AI_DARKEN);
    }
    let yaw = yaw + spin;
    let (fx, fz) = (yaw.sin(), -yaw.cos());
    let (rx, rz) = (-fz, fx);
    // Local-to-world: forward 2.6 m, right 1.6 m.
    let put = |lx: f32, y: f32, lz: f32| -> [f32; 3] {
        [x + rx * lx + fx * lz, y, z + rz * lx + fz * lz]
    };
    let stretch = if boost { 1.12 } else { 1.0 };
    // Body: low box.
    let body = color;
    let y0 = 0.35;
    let (hx, hz) = (1.6 / 2.0, 2.6 * stretch / 2.0);
    let corners = [
        put(-hx, y0, -hz),
        put(hx, y0, -hz),
        put(-hx, y0, hz),
        put(hx, y0, hz),
    ];
    let top = [
        put(-hx, y0 + 0.55, -hz),
        put(hx, y0 + 0.55, -hz),
        put(-hx, y0 + 0.55, hz),
        put(hx, y0 + 0.55, hz),
    ];
    quad(out, top[0], top[1], top[2], top[3], body);
    quad(out, corners[2], corners[3], top[2], top[3], darken(body, 0.9));
    quad(out, corners[1], corners[0], top[1], top[0], darken(body, 0.85));
    quad(out, corners[0], corners[2], top[0], top[2], darken(body, 0.75));
    quad(out, corners[3], corners[1], top[3], top[1], darken(body, 0.75));
    // Cabin: smaller darker box on top.
    let cabin = darken(color, 0.6);
    let (cx, cz) = (1.0 / 2.0, 1.1 / 2.0);
    let cc = [
        put(-cx, y0 + 0.55, -cz),
        put(cx, y0 + 0.55, -cz),
        put(-cx, y0 + 0.55, cz),
        put(cx, y0 + 0.55, cz),
    ];
    let ct = [
        put(-cx, y0 + 1.15, -cz),
        put(cx, y0 + 1.15, -cz),
        put(-cx, y0 + 1.15, cz),
        put(cx, y0 + 1.15, cz),
    ];
    quad(out, ct[0], ct[1], ct[2], ct[3], cabin);
    quad(out, cc[0], cc[2], ct[0], ct[2], darken(cabin, 0.85));
    quad(out, cc[3], cc[1], ct[3], ct[1], darken(cabin, 0.85));
    // Wheels: four dark boxes.
    let wheel = [0.10, 0.10, 0.10];
    for (wx, wz) in [(-0.85, -0.85), (0.85, -0.85), (-0.85, 0.85), (0.85, 0.85)] {
        let c = put(wx, 0.0, wz * stretch);
        box_at(out, c, [0.42, 0.5, 0.42], wheel);
    }
    let _ = corners;
}

/// Build one item box marker (floating cube, bobbed by `ticks`).
pub fn build_box_mesh(out: &mut Vec<KartVertex>, x: f32, z: f32, ticks: u64) {
    let bob = 0.9 + 0.25 * ((ticks as f32 / 30.0).sin());
    box_at(out, [x, bob, z], [1.2, 1.2, 1.2], BOX_COLOR);
}

/// Build one shell marker (low spinning tetra-ish box).
pub fn build_shell_mesh(out: &mut Vec<KartVertex>, x: f32, z: f32, ticks: u64) {
    let _ = ticks;
    box_at(out, [x, 0.35, z], [0.9, 0.7, 0.9], SHELL_COLOR);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn track_mesh_is_nonempty_and_flat() {
        let mesh = build_track_mesh();
        assert!(!mesh.is_empty());
        for v in mesh.iter().take(1000) {
            assert!(v.pos[1] <= WALL_HEIGHT + 0.01, "y={}", v.pos[1]);
        }
    }

    #[test]
    fn kart_mesh_has_body_cabin_wheels() {
        let mut out = Vec::new();
        build_kart_mesh(
            &mut out,
            KartMeshSpec { x: 0.0, z: 0.0, yaw: 0.0, seat: 0, ai: false, spin: 0.0, boost: false },
        );
        // Body top (6) + front/back/sides (24) + cabin (12) + 4 wheels (4x30).
        assert!(out.len() >= 100, "verts={}", out.len());
    }

    #[test]
    fn track_mesh_counts_match_quads() {
        let mesh = build_track_mesh();
        let steps = (track::TRACK_LENGTH / MESH_STEP).ceil() as usize;
        assert_eq!(mesh.len(), steps * 42, "verts={}", mesh.len());
        assert_eq!(mesh.len() % 6, 0);
        for v in &mesh {
            assert!(v.pos.iter().all(|c| c.is_finite()));
            assert!(v.color.iter().all(|c| (0.0..=1.0).contains(c)));
        }
    }
}
