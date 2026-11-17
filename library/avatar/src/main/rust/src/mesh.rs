//! Skinned avatar mesh: vertex format, procedural fallback, glTF loading.
//!
//! The v1 path is procedural: [`build_avatar_mesh`] emits an upper-body
//! approximation (head sphere-ish, torso, arms) bound to the [`crate::avatar`]
//! skeleton with 4-bone weights. [`load_gltf_mesh`] parses a rigged
//! `base_upperbody.glb` when one is bundled, remapping its joints onto the
//! same UBO order so poses behave identically either way.

use crate::atlas::{face_cell_uv, overlay_cell_uv};
use crate::avatar::{joint_by_name, ALL_JOINTS, JOINT_COUNT};
use crate::traits::YuuTraits;

/// One skinned vertex. Matches `yuu_avatar.vert` locations 0..4.
///
/// NOTE(Mali): `joints` is `[f32; 4]`, not `[u32; 4]` — integer vertex
/// attributes crash the Mali SPIR-V frontend (see the shader note). Exact
/// for our small joint ids; converted with `int()` in-shader.
#[derive(Clone, Copy, Debug, Default)]
#[repr(C)]
pub struct YuuVertex {
    /// Model-space position.
    pub pos: [f32; 3],
    /// Model-space normal.
    pub normal: [f32; 3],
    /// Albedo UV.
    pub uv: [f32; 2],
    /// Bone indices into the UBO order (as floats — see note above).
    pub joints: [f32; 4],
    /// Bone weights (normalized).
    pub weights: [f32; 4],
}

/// A rigid part swept into skinned vertices with a single bone weight.
struct Part {
    /// Center of the box.
    center: [f32; 3],
    /// Full size of the box.
    size: [f32; 3],
    /// Primary bone index.
    joint: f32,
    /// Secondary bone index (blends toward the parent at the seam).
    blend_joint: f32,
    /// Blend factor 0..1 toward the secondary bone.
    blend: f32,
    /// Skin-tone albedo flag (true = skin, false = shirt).
    skin: bool,
}

/// Subdivisions per face edge for the rounded-box mesh (S=4 → visibly
/// round, ~7.5k verts total, fits both GPU budgets with no constant change).
const ROUND_SUBDIV: usize = 4;

// Procedural mesh builders, glTF loading, and packed attachments live in part
// files so each file stays under the 500-line Rust limit (same module scope
// via `include!`).
include!("mesh_procedural.rs");
include!("mesh_gltf.rs");
include!("mesh_attachments.rs");

#[cfg(test)]
mod tests {
    use super::*;
    use crate::traits::YuuTraits;

    #[test]
    fn fallback_mesh_is_nonempty_and_weighted() {
        let mesh = build_avatar_mesh(&YuuTraits::default());
        assert!(!mesh.is_empty());
        // Rounded-box S=4 budget: 13 parts × 6 faces × 16 cells × 6 verts,
        // plus face decal quads (4 bands × 6 verts, overlays off by default).
        assert!(
            mesh.len() <= 8_192,
            "mesh {} verts exceeds dynamic budget",
            mesh.len()
        );
        for v in mesh.iter().take(64) {
            let w: f32 = v.weights.iter().sum();
            assert!((w - 1.0).abs() < 1e-4, "weights must sum to 1, got {w}");
        }
    }

    #[test]
    fn corrupt_gltf_falls_back() {
        let mesh = load_gltf_mesh(b"not a glb");
        assert!(!mesh.is_empty());
    }

    #[test]
    fn wave_pose_lifts_right_hand() {
        // End-to-end CPU skinning: mesh verts x bone matrices must move the
        // waving hand UP. Catches FK/weight/remap breakage that unit tests on
        // bones alone would miss.
        use crate::avatar::compute_bone_matrices;
        use crate::pose::preset_by_id;
        use glam::Vec3;
        let traits = YuuTraits::default();
        let mesh = build_avatar_mesh(&traits);
        let wave = preset_by_id("wave").expect("wave preset");
        let bones = compute_bone_matrices(&traits, &wave, 0.0);
        // Skin the hand-box verts (fully hand-weighted region near x=0.31).
        let mut hand_max_y = f32::MIN;
        let mut hand_count = 0;
        for v in &mesh {
            // Hand verts: authored near the right hand center.
            let dx = v.pos[0] - 0.31;
            let dy = v.pos[1] - 0.34;
            let dz = v.pos[2];
            if dx.abs() < 0.06 && dy.abs() < 0.06 && dz.abs() < 0.06 {
                let p = Vec3::from_array(v.pos);
                let mut skinned = Vec3::ZERO;
                for k in 0..4 {
                    let j = v.joints[k] as usize;
                    let w = v.weights[k];
                    if w > 0.0 && j < 32 {
                        let m = glam::Mat4::from_cols_array_2d(&bones[j].to_cols_array_2d());
                        skinned += (m * glam::Vec4::new(p.x, p.y, p.z, 1.0)).truncate() * w;
                    }
                }
                hand_max_y = hand_max_y.max(skinned.y);
                hand_count += 1;
            }
        }
        assert!(hand_count > 0, "no hand verts found");
        assert!(
            hand_max_y > 1.0,
            "waving hand should rise above y=1.0, got max {hand_max_y}"
        );
    }

    #[test]
    fn face_quad_verts_use_face_block_uvs() {
        // Face decal verts: forward normal, head-bound, UVs inside the painted
        // face block (memory rows 128..256 → v 0.5..1.0, no V flip — samplers
        // read memory row 0 at v=0 on both backends).
        use crate::atlas::face_cell_uv;
        let traits = YuuTraits::default();
        let mesh = build_avatar_mesh(&traits);
        assert!(mesh.len() <= 8_192, "mesh {} verts exceeds budget", mesh.len());
        let head = j("head");
        let rows = [
            face_cell_uv(0, traits.eye_style),
            face_cell_uv(1, traits.brow_style),
            face_cell_uv(2, traits.nose_style),
            face_cell_uv(3, traits.mouth_style),
        ];
        let mut face_count = 0;
        for v in &mesh {
            // Rounded-box front-face centers also carry a [0,0,1] normal, so
            // the decal plane z is the real discriminator (body fronts sit at
            // z <= 0.14, decals at FACE_Z = 0.145).
            if v.normal != [0.0, 0.0, 1.0] || (v.pos[2] - FACE_Z).abs() >= 1e-6 {
                continue;
            }
            assert_eq!(v.joints, [head, 0.0, 0.0, 0.0]);
            assert_eq!(v.weights, [1.0, 0.0, 0.0, 0.0]);
            // Overlays are off under default traits: every head-on quad vert
            // is one of the four band quads (6 verts each).
            assert!((v.pos[2] - FACE_Z).abs() < 1e-6);
            assert!(v.uv[0] >= 0.0 && v.uv[0] <= 1.0);
            assert!(v.uv[1] >= 0.5 && v.uv[1] <= 1.0);
            assert!(
                rows.iter().any(|r| {
                    v.uv[0] >= r[0] - 1e-6
                        && v.uv[0] <= r[2] + 1e-6
                        && v.uv[1] >= r[1] - 1e-6
                        && v.uv[1] <= r[3] + 1e-6
                }),
                "face vert uv {:?} outside the four band cells",
                v.uv
            );
            face_count += 1;
        }
        assert_eq!(face_count, 24, "expected 4 bands x 6 verts, got {face_count}");
    }

    #[test]
    fn overlay_styles_append_extra_quads() {
        // Nonzero mole/wrinkle/blush each append quads pointing at the exact
        // spare brow-row cells generate_atlas paints.
        use crate::atlas::{face_cell_uv, overlay_cell_uv};
        fn is_decal(v: &&YuuVertex) -> bool {
            v.normal == [0.0, 0.0, 1.0] && (v.pos[2] - FACE_Z).abs() < 1e-6
        }
        let traits = YuuTraits {
            mole_style: 1,
            wrinkle_style: 2,
            blush_style: 3,
            ..Default::default()
        };
        let mesh = build_avatar_mesh(&traits);
        // 24 band verts + mole 6 + wrinkle 6 + blush 12.
        let face_count = mesh.iter().filter(is_decal).count();
        assert_eq!(face_count, 48, "overlay quads missing, got {face_count}");
        let cells = [
            face_cell_uv(0, traits.eye_style),
            face_cell_uv(1, traits.brow_style),
            face_cell_uv(2, traits.nose_style),
            face_cell_uv(3, traits.mouth_style),
            overlay_cell_uv(traits.mole_style),
            overlay_cell_uv(traits.wrinkle_style),
            overlay_cell_uv(traits.blush_style),
        ];
        for v in mesh.iter().filter(is_decal) {
            assert!(
                cells.iter().any(|r| {
                    v.uv[0] >= r[0] - 1e-6
                        && v.uv[0] <= r[2] + 1e-6
                        && v.uv[1] >= r[1] - 1e-6
                        && v.uv[1] <= r[3] + 1e-6
                }),
                "overlay vert uv {:?} outside band/overlay cells",
                v.uv
            );
        }
        assert!(mesh.len() <= 8_192);
    }
}
