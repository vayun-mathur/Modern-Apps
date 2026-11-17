// Procedural fallback mesh: rounded-box parts, face decals, body assembly.
//
// Split out of `mesh.rs` so each file stays under the 500-line Rust limit.
// Same module scope via `mesh.rs` (`include!`), so item order is irrelevant.

/// One rounded-box face grid: rows of (rounded position, normal, UV).
type RoundedGrid = Vec<Vec<([f32; 3], [f32; 3], [f32; 2])>>;

/// Push a rounded axis-aligned box as skinned triangles into `out`.
///
/// Same `Part` contract as the old sharp box (centers/sizes, joint/blend,
/// skin/shirt UV split) but each face is subdivided `ROUND_SUBDIV ×
/// ROUND_SUBDIV` and pulled toward a rounded box: `p_clamped = clamp(p,
/// -half+r, half-r)`, `normal = normalize(p - p_clamped)`, `p' = p_clamped +
/// normal * r`, with `r = min(size) / 4`. Corners/edges round over, face
/// centers stay flat, and part centers never move (wave/idle test queries
/// still hit).
fn box_skinned(out: &mut Vec<YuuVertex>, part: &Part) {
    let (cx, cy, cz) = (part.center[0], part.center[1], part.center[2]);
    let (sx, sy, sz) = (part.size[0] / 2.0, part.size[1] / 2.0, part.size[2] / 2.0);
    let r = (part.size[0].min(part.size[1]).min(part.size[2]) / 4.0).max(0.005);
    // 8 corners of the sharp box.
    let c = [
        [cx - sx, cy - sy, cz - sz],
        [cx + sx, cy - sy, cz - sz],
        [cx + sx, cy + sy, cz - sz],
        [cx - sx, cy + sy, cz - sz],
        [cx - sx, cy - sy, cz + sz],
        [cx + sx, cy - sy, cz + sz],
        [cx + sx, cy + sy, cz + sz],
        [cx - sx, cy + sy, cz + sz],
    ];
    // 6 faces: corners + base UVs. Grid (iu, iv) walks each face so the
    // rounded push has vertices to move near edges/corners.
    let faces: [([usize; 4], [[f32; 2]; 4]); 6] = [
        ([0, 1, 2, 3], [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]),
        ([5, 4, 7, 6], [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]),
        ([4, 0, 3, 7], [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]),
        ([1, 5, 6, 2], [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]),
        ([4, 5, 1, 0], [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]),
        ([3, 2, 6, 7], [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]),
    ];
    let w0 = 1.0 - part.blend;
    let s = ROUND_SUBDIV;
    for (corners, face_uv) in faces {
        // Bilinear grid over the quad, storing (rounded pos, smooth normal, uv).
        let mut grid: RoundedGrid = Vec::with_capacity(s + 1);
        for iy in 0..=s {
            let v = iy as f32 / s as f32;
            let mut row = Vec::with_capacity(s + 1);
            for ix in 0..=s {
                let u = ix as f32 / s as f32;
                // Bilinear interpolate corners and UVs.
                let mut p = [0.0f32; 3];
                let mut uv = [0.0f32; 2];
                let ws = [(1.0 - u) * (1.0 - v), u * (1.0 - v), u * v, (1.0 - u) * v];
                for k in 0..4 {
                    for a in 0..3 {
                        p[a] += ws[k] * c[corners[k]][a];
                    }
                    uv[0] += ws[k] * face_uv[k][0];
                    uv[1] += ws[k] * face_uv[k][1];
                }
                // Rounded-box push in part-local space.
                let local = [p[0] - cx, p[1] - cy, p[2] - cz];
                let half = [sx, sy, sz];
                let mut clamped = [0.0f32; 3];
                for a in 0..3 {
                    clamped[a] = local[a].clamp(-(half[a] - r), half[a] - r);
                }
                let n = rounded_normal_from_offset(local, clamped);
                let rounded = [
                    cx + clamped[0] + n[0] * r,
                    cy + clamped[1] + n[1] * r,
                    cz + clamped[2] + n[2] * r,
                ];
                let tinted = if part.skin {
                    [uv[0] * 0.25, uv[1] * 0.25]
                } else {
                    [0.5 + uv[0] * 0.5, uv[1] * 0.5]
                };
                row.push((rounded, n, tinted));
            }
            grid.push(row);
        }
        // Emit two triangles per grid cell with the smooth normals baked.
        for iy in 0..s {
            for ix in 0..s {
                let quad = [
                    grid[iy][ix],
                    grid[iy][ix + 1],
                    grid[iy + 1][ix + 1],
                    grid[iy + 1][ix],
                ];
                for k in [0, 1, 2, 0, 2, 3] {
                    let (pos, normal, uv) = quad[k];
                    out.push(YuuVertex {
                        pos,
                        normal,
                        uv,
                        joints: [part.joint, part.blend_joint, 0.0, 0.0],
                        weights: [w0, part.blend, 0.0, 0.0],
                    });
                }
            }
        }
    }
}

/// Analytic rounded-box normal from a local offset and its clamped form.
/// Face interiors (zero offset) fall back to the dominant-axis flat normal.
fn rounded_normal_from_offset(local: [f32; 3], clamped: [f32; 3]) -> [f32; 3] {
    let mut n = [
        local[0] - clamped[0],
        local[1] - clamped[1],
        local[2] - clamped[2],
    ];
    let len = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt();
    if len < 1e-6 {
        let mut ax = 0;
        let mut best = local[0].abs();
        for (a, value) in local.iter().enumerate().skip(1) {
            if value.abs() > best {
                best = value.abs();
                ax = a;
            }
        }
        let mut nn = [0.0f32; 3];
        nn[ax] = if local[ax] >= 0.0 { 1.0 } else { -1.0 };
        return nn;
    }
    n[0] /= len;
    n[1] /= len;
    n[2] /= len;
    n
}

/// Joint index helper by name (0 when unknown — weighted to root).
fn j(name: &str) -> f32 {
    joint_by_name(name).map(|jj| jj as u32 as f32).unwrap_or(0.0)
}

/// Face decal plane: just proud of the head front (the head box front face
/// sits at z = 0.14 and stays flat under rounding) so the decal never
/// z-fights the skull.
const FACE_Z: f32 = 0.145;

/// Push one axis-aligned decal quad on the face plane as two triangles.
///
/// Fully bound to the head joint with a forward normal; `uv` is a v-up atlas
/// rect `[u0, v0, u1, v1]` (see [`face_cell_uv`](crate::atlas::face_cell_uv)).
fn push_decal_quad(out: &mut Vec<YuuVertex>, cx: f32, cy: f32, hw: f32, hh: f32, uv: [f32; 4]) {
    let head = j("head");
    let corners = [
        ([cx - hw, cy - hh], [uv[0], uv[1]]),
        ([cx + hw, cy - hh], [uv[2], uv[1]]),
        ([cx + hw, cy + hh], [uv[2], uv[3]]),
        ([cx - hw, cy + hh], [uv[0], uv[3]]),
    ];
    for k in [0, 1, 2, 0, 2, 3] {
        let (xy, t) = corners[k];
        out.push(YuuVertex {
            pos: [xy[0], xy[1], FACE_Z],
            normal: [0.0, 0.0, 1.0],
            uv: t,
            joints: [head, 0.0, 0.0, 0.0],
            weights: [1.0, 0.0, 0.0, 0.0],
        });
    }
}

/// Append the face decal quads for `traits`.
///
/// Four horizontal bands (eyes / brows / nose / mouth) tile the head front;
/// each band's rectangle is derived from the matching trait sliders so a
/// later slider drag can shift it live, and each band's UVs point at the
/// matching atlas cell. Mole/wrinkle/blush append small overlay quads (same
/// cells [`generate_atlas`](crate::atlas::generate_atlas) paints) when set.
fn push_face_decals(out: &mut Vec<YuuVertex>, traits: &YuuTraits) {
    // Eye band: the eye cell draws a twin-eye pair, so the band spans outer
    // edge to outer edge — per-eye offset plus per-eye half-width.
    let eye_off = 0.03 + traits.eye_spacing.clamp(0.0, 1.0) * 0.03;
    let eye_hw = eye_off + 0.035 + traits.eye_size.clamp(0.0, 1.0) * 0.03;
    let eye_cy = 1.32 + (traits.eye_height.clamp(0.0, 1.0) - 0.5) * 0.06;
    let eye_hh = 0.015 + traits.eye_size.clamp(0.0, 1.0) * 0.02;
    push_decal_quad(out, 0.0, eye_cy, eye_hw, eye_hh, face_cell_uv(0, traits.eye_style));
    // Brow band above the eyes; height slider slides it vertically.
    let brow_cy = 1.375 + (traits.brow_height.clamp(0.0, 1.0) - 0.5) * 0.05;
    push_decal_quad(out, 0.0, brow_cy, 0.085, 0.012, face_cell_uv(1, traits.brow_style));
    // Nose band between eyes and mouth; height slider slides it vertically.
    let nose_cy = 1.26 + (traits.nose_height.clamp(0.0, 1.0) - 0.5) * 0.05;
    push_decal_quad(out, 0.0, nose_cy, 0.02, 0.02, face_cell_uv(2, traits.nose_style));
    // Mouth band; width follows mouth_size, height follows mouth_height.
    let mouth_cy = 1.19 + (traits.mouth_height.clamp(0.0, 1.0) - 0.5) * 0.05;
    let mouth_hw = 0.03 + traits.mouth_size.clamp(0.0, 1.0) * 0.04;
    push_decal_quad(out, 0.0, mouth_cy, mouth_hw, 0.015, face_cell_uv(3, traits.mouth_style));
    // Mole: one cheek quad, side + offset picked by style index.
    if traits.mole_style > 0 {
        let idx = traits.mole_style.max(0) as usize;
        let side = if idx % 2 == 0 { -1.0 } else { 1.0 };
        let mx = side * (0.055 + 0.01 * (idx % 4) as f32);
        let my = 1.25 - 0.008 * (idx % 3) as f32;
        push_decal_quad(out, mx, my, 0.012, 0.012, overlay_cell_uv(traits.mole_style));
    }
    // Wrinkle: thin wide quad across the forehead.
    if traits.wrinkle_style != 0 {
        push_decal_quad(out, 0.0, 1.40, 0.06, 0.008, overlay_cell_uv(traits.wrinkle_style));
    }
    // Blush: one small quad per cheek.
    if traits.blush_style > 0 {
        let uv = overlay_cell_uv(traits.blush_style);
        push_decal_quad(out, -0.07, 1.22, 0.015, 0.012, uv);
        push_decal_quad(out, 0.07, 1.22, 0.015, 0.012, uv);
    }
}

/// Build the procedural fallback upper-body mesh bound to the v1 skeleton.
///
/// Proportions are Mii-like (big head, simple torso/arms); the renderer tints
/// skin vs shirt regions via the UV split in [`box_skinned`]. `traits` only
/// feeds the face decal quads (band placement + atlas style cells); the body
/// boxes are trait-independent.
pub fn build_avatar_mesh(traits: &YuuTraits) -> Vec<YuuVertex> {
    let mut out = Vec::with_capacity(1024);
    // Head: stacked boxes approximating a rounded skull + jaw.
    let head = Part { center: [0.0, 1.28, 0.0], size: [0.30, 0.30, 0.28], joint: j("head"), blend_joint: j("neck"), blend: 0.15, skin: true };
    box_skinned(&mut out, &head);
    let jaw = Part { center: [0.0, 1.10, 0.02], size: [0.24, 0.12, 0.24], joint: j("head"), blend_joint: j("neck"), blend: 0.25, skin: true };
    box_skinned(&mut out, &jaw);
    // Neck.
    let neck = Part { center: [0.0, 1.02, 0.0], size: [0.10, 0.10, 0.10], joint: j("neck"), blend_joint: j("chest"), blend: 0.3, skin: true };
    box_skinned(&mut out, &neck);
    // Torso (shirt region).
    let chest = Part { center: [0.0, 0.82, 0.0], size: [0.44, 0.30, 0.26], joint: j("chest"), blend_joint: j("spine"), blend: 0.25, skin: false };
    box_skinned(&mut out, &chest);
    let waist = Part { center: [0.0, 0.58, 0.0], size: [0.38, 0.22, 0.24], joint: j("spine"), blend_joint: j("root"), blend: 0.3, skin: false };
    box_skinned(&mut out, &waist);
    // Arms (skin hands, shirt sleeves).
    for side in [-1.0f32, 1.0] {
        let (clav, upper, fore, hand) = if side < 0.0 {
            ("clavicle_L", "upperArm_L", "forearm_L", "hand_L")
        } else {
            ("clavicle_R", "upperArm_R", "forearm_R", "hand_R")
        };
        let shoulder = Part { center: [side * 0.26, 0.90, 0.0], size: [0.12, 0.12, 0.14], joint: j(upper), blend_joint: j(clav), blend: 0.4, skin: false };
        box_skinned(&mut out, &shoulder);
        let upper_arm = Part { center: [side * 0.30, 0.74, 0.0], size: [0.11, 0.26, 0.12], joint: j(upper), blend_joint: j(clav), blend: 0.2, skin: false };
        box_skinned(&mut out, &upper_arm);
        let forearm = Part { center: [side * 0.31, 0.50, 0.0], size: [0.10, 0.24, 0.11], joint: j(fore), blend_joint: j(upper), blend: 0.25, skin: true };
        box_skinned(&mut out, &forearm);
        let hand = Part { center: [side * 0.31, 0.34, 0.0], size: [0.10, 0.10, 0.10], joint: j(hand), blend_joint: j(fore), blend: 0.3, skin: true };
        box_skinned(&mut out, &hand);
    }
    push_face_decals(&mut out, traits);
    out
}

#[cfg(test)]
mod procedural_tests {
    use super::*;

    #[test]
    fn rounded_normal_face_corner_and_zero_cases() {
        // Face interior: offset is zero, dominant axis wins (sign preserved).
        assert_eq!(rounded_normal_from_offset([0.1, 0.02, 0.0], [0.1, 0.02, 0.0]), [1.0, 0.0, 0.0]);
        assert_eq!(rounded_normal_from_offset([0.0, -0.3, 0.1], [0.0, -0.3, 0.1]), [0.0, -1.0, 0.0]);
        assert_eq!(rounded_normal_from_offset([0.0; 3], [0.0; 3]), [1.0, 0.0, 0.0], "zero local defaults +X");
        // Corner: normalized offset from the clamped point.
        let n = rounded_normal_from_offset([1.0, 1.0, 0.0], [0.5, 0.5, 0.0]);
        let inv = std::f32::consts::FRAC_1_SQRT_2;
        assert!((n[0] - inv).abs() < 1e-6 && (n[1] - inv).abs() < 1e-6 && n[2] == 0.0, "{n:?}");
        assert!((n.iter().map(|v| v * v).sum::<f32>() - 1.0).abs() < 1e-6);
    }

    #[test]
    fn joint_index_helper_maps_names_and_defaults_to_root() {
        assert_eq!(j("head"), 4.0);
        assert_eq!(j("root"), 0.0);
        assert_eq!(j("hand_L"), 8.0);
        assert_eq!(j("nope"), 0.0);
        assert_eq!(j(""), 0.0);
    }

    #[test]
    fn single_box_part_vertex_contract() {
        let part = Part {
            center: [0.0, 1.0, 0.0], size: [0.2, 0.4, 0.2],
            joint: 4.0, blend_joint: 3.0, blend: 0.25, skin: true,
        };
        let mut out = Vec::new();
        box_skinned(&mut out, &part);
        assert_eq!(out.len(), 6 * ROUND_SUBDIV * ROUND_SUBDIV * 6);
        assert_eq!(out.len() % 3, 0);
        for v in &out {
            assert_eq!(v.joints, [4.0, 3.0, 0.0, 0.0]);
            assert_eq!(v.weights, [0.75, 0.25, 0.0, 0.0]);
            assert!((v.normal.iter().map(|n| n * n).sum::<f32>() - 1.0).abs() < 1e-4, "normal {:?}", v.normal);
            // Rounded box never escapes the sharp box bounds.
            assert!(v.pos[0].abs() <= 0.1 + 1e-5 && (v.pos[1] - 1.0).abs() <= 0.2 + 1e-5 && v.pos[2].abs() <= 0.1 + 1e-5, "{:?}", v.pos);
            // Skin parts sample the skin UV quadrant.
            assert!(v.uv[0] <= 0.25 + 1e-6 && v.uv[1] <= 0.25 + 1e-6, "{:?}", v.uv);
        }
        // Shirt twin samples the shirt half instead.
        let shirt = Part { skin: false, ..Part {
            center: [0.0; 3], size: [0.2; 3], joint: 0.0, blend_joint: 0.0, blend: 0.0, skin: false,
        } };
        let mut out = Vec::new();
        box_skinned(&mut out, &shirt);
        assert!(out.iter().all(|v| v.uv[0] >= 0.5 && v.uv[1] <= 0.5));
    }

    #[test]
    fn procedural_mesh_global_invariants_and_determinism() {
        let traits = YuuTraits::default();
        let (a, b) = (build_avatar_mesh(&traits), build_avatar_mesh(&traits));
        assert_eq!(a.len(), b.len());
        assert!(a.iter().zip(&b).all(|(x, y)| x.pos == y.pos && x.uv == y.uv), "build must be deterministic");
        assert_eq!(a.len() % 3, 0, "triangle soup");
        for v in &a {
            assert!(v.pos.iter().all(|p| p.is_finite()));
            assert!((v.weights.iter().sum::<f32>() - 1.0).abs() < 1e-4, "weights {:?}", v.weights);
            assert!(v.joints.iter().all(|j| (0.0..JOINT_COUNT as f32).contains(j)), "joints {:?}", v.joints);
            assert!(v.uv.iter().all(|u| (0.0..=1.0).contains(u)), "uv {:?}", v.uv);
            assert!((v.normal.iter().map(|n| n * n).sum::<f32>() - 1.0).abs() < 1e-3);
        }
        // Bounding box is a plausible upper body (metres).
        let (mut lo, mut hi) = ([f32::MAX; 3], [f32::MIN; 3]);
        for v in &a {
            for k in 0..3 { lo[k] = lo[k].min(v.pos[k]); hi[k] = hi[k].max(v.pos[k]); }
        }
        assert!(lo[1] > 0.2 && hi[1] < 1.6, "y range {lo:?}..{hi:?}");
        assert!(hi[0] < 0.5 && lo[0] > -0.5, "x range {lo:?}..{hi:?}");
    }

    #[test]
    fn trait_sliders_move_decals_without_changing_body_count() {
        let base = build_avatar_mesh(&YuuTraits::default());
        let moved = build_avatar_mesh(&YuuTraits {
            eye_height: 1.0, eye_size: 1.0, mouth_size: 1.0, ..Default::default()
        });
        assert_eq!(base.len(), moved.len(), "sliders move quads, never add/remove body verts");
        assert_ne!(
            base.iter().map(|v| v.pos).collect::<Vec<_>>(),
            moved.iter().map(|v| v.pos).collect::<Vec<_>>(),
            "slider extremes must move at least one decal vertex"
        );
        // Negative overlay styles are out of contract and treated as unset:
        // no mole quad is emitted, exactly like style 0.
        let neg = build_avatar_mesh(&YuuTraits { mole_style: -1, ..Default::default() });
        assert_eq!(neg.len(), base.len());
    }
}
