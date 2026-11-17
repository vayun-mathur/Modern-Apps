// glTF asset loading plus mesh/joint budget helpers.
//
// Split out of `mesh.rs` so each file stays under the 500-line Rust limit.
// Same module scope via `mesh.rs` (`include!`), so item order is irrelevant.

/// Load a rigged glTF mesh, remapping its joints onto the UBO order.
///
/// Parses the packed asset once and assembles with default traits (no
/// attachments selected beyond `hair_00`); the trait-aware path is
/// [`assemble_pack`](crate::mesh::assemble_pack) via [`GltfPack`]. Returns the
/// procedural fallback when `bytes` fail to parse or nothing assembles, so a
/// missing or corrupt `base_upperbody.glb` never breaks the preview — the
/// skeleton and poses behave identically since both paths share bone order.
pub fn load_gltf_mesh(bytes: &[u8]) -> Vec<YuuVertex> {
    match GltfPack::parse(bytes) {
        Some(pack) => assemble_pack(&pack, &crate::traits::YuuTraits::default())
            .unwrap_or_else(|| build_avatar_mesh(&crate::traits::YuuTraits::default())),
        None => build_avatar_mesh(&crate::traits::YuuTraits::default()),
    }
}

/// Skin-joint-slot -> UBO order remap via node names. JOINTS_0 indexes
/// `skin.joints` (an indirection into nodes), NOT global node order, so this
/// resolves through the skin's joint list. Stored as floats (Mali note on
/// `YuuVertex::joints`). No skin (unrigged attachments) maps everything to
/// the root default — the caller rebinds unskinned attachments to the head.
fn skin_remap(doc: &gltf::Document) -> Vec<f32> {
    let mut remap = vec![0.0f32; 256];
    if let Some(skin) = doc.skins().next() {
        for (slot, node) in skin.joints().enumerate() {
            if slot >= remap.len() {
                break;
            }
            if let Some(name) = node.name() {
                if let Some(jj) = joint_by_name(name) {
                    remap[slot] = jj as u32 as f32;
                }
            }
        }
    }
    remap
}

/// Read one mesh primitive into skinned verts, with OOB-index rejection.
///
/// Returns the verts plus whether the primitive carried explicit JOINTS_0
/// weights (`false` = unweighted, bound to the joint-0 default by
/// [`normalize_weights`]). `None` means a corrupt primitive — the index buffer
/// references verts that don't exist, so bail instead of clamping (which
/// would smear the last vertex across the mesh).
fn read_primitive(
    buffers: &[gltf::buffer::Data],
    remap: &[f32],
    prim: gltf::Primitive<'_>,
) -> Option<(Vec<YuuVertex>, bool)> {
    // Buffer indices come from the same parsed doc as `buffers`, so they are
    // always in range (vertex-index OOB is checked separately below).
    let reader = prim.reader(|buffer| Some(&buffers[buffer.index()]));
    let positions: Vec<[f32; 3]> = reader.read_positions()?.collect();
    let normals: Vec<[f32; 3]> = reader
        .read_normals()
        .map(|it| it.collect())
        .unwrap_or_else(|| vec![[0.0, 1.0, 0.0]; positions.len()]);
    let uvs: Vec<[f32; 2]> = reader
        .read_tex_coords(0)
        .map(|tc| tc.into_f32().collect())
        .unwrap_or_else(|| vec![[0.0, 0.0]; positions.len()]);
    let had_joints = reader.read_joints(0).is_some();
    let joints_raw: Vec<[u16; 4]> = reader
        .read_joints(0)
        .map(|it| it.into_u16().collect())
        .unwrap_or_default();
    let weights_raw: Vec<[f32; 4]> = reader
        .read_weights(0)
        .map(|it| it.into_f32().collect())
        .unwrap_or_default();
    let mut out = Vec::with_capacity(positions.len());
    for (i, pos) in positions.iter().enumerate() {
        let js = joints_raw.get(i).copied().unwrap_or([0; 4]);
        let ws = normalize_weights(weights_raw.get(i).copied().unwrap_or([1.0, 0.0, 0.0, 0.0]));
        out.push(YuuVertex {
            pos: *pos,
            normal: *normals.get(i).unwrap_or(&[0.0, 1.0, 0.0]),
            uv: *uvs.get(i).unwrap_or(&[0.0, 0.0]),
            joints: [
                *remap.get(js[0] as usize).unwrap_or(&0.0),
                *remap.get(js[1] as usize).unwrap_or(&0.0),
                *remap.get(js[2] as usize).unwrap_or(&0.0),
                *remap.get(js[3] as usize).unwrap_or(&0.0),
            ],
            weights: ws,
        });
    }
    // Indexed or flat: expand indices when present.
    if let Some(indices) = reader.read_indices() {
        let idx: Vec<u32> = indices.into_u32().collect();
        if idx.iter().any(|ii| (*ii as usize) >= out.len()) {
            return None;
        }
        let expanded: Vec<YuuVertex> = idx.iter().map(|ii| out[*ii as usize]).collect();
        return Some((expanded, had_joints));
    }
    Some((out, had_joints))
}

/// Normalize skin weights (guard degenerate sums); unweighted verts bind
/// fully to joint 0 via the caller's joint default.
fn normalize_weights(ws: [f32; 4]) -> [f32; 4] {
    let sum: f32 = ws.iter().sum();
    if sum < 1e-6 {
        [1.0, 0.0, 0.0, 0.0]
    } else {
        [ws[0] / sum, ws[1] / sum, ws[2] / sum, ws[3] / sum]
    }
}

/// Vertex count sanity for the Vulkan budget check.
pub fn mesh_budgets() -> (usize, usize) {
    // Static avatar mesh cap + one dynamic upload (same buffer reused).
    (65_536, 8_192)
}

/// Joint count exposed for UBO sizing asserts.
pub fn joint_count() -> usize {
    JOINT_COUNT
}

/// All joint names in UBO order (for asset validation logs).
pub fn joint_names() -> Vec<&'static str> {
    ALL_JOINTS.iter().copied().map(crate::avatar::joint_name).collect()
}

#[cfg(test)]
mod tests_extra {
    use super::*;

    #[test]
    fn normalize_weights_handles_degenerate_and_scales() {
        assert_eq!(normalize_weights([0.0; 4]), [1.0, 0.0, 0.0, 0.0]);
        assert_eq!(normalize_weights([1e-9, 0.0, 0.0, 0.0]), [1.0, 0.0, 0.0, 0.0], "tiny sum is degenerate");
        assert_eq!(normalize_weights([2.0, 2.0, 0.0, 0.0]), [0.5, 0.5, 0.0, 0.0]);
        assert_eq!(normalize_weights([1.0, 1.0, 1.0, 1.0]), [0.25; 4]);
        let w = normalize_weights([0.1, 0.2, 0.3, 0.4]);
        assert!((w.iter().sum::<f32>() - 1.0).abs() < 1e-6);
        assert!((w[3] - 0.4).abs() < 1e-6, "already-normalized weights unchanged");
    }

    #[test]
    fn budget_and_joint_helpers_match_contract() {
        assert_eq!(mesh_budgets(), (65_536, 8_192));
        assert_eq!(joint_count(), JOINT_COUNT);
        let names = joint_names();
        assert_eq!(names.len(), JOINT_COUNT);
        assert_eq!(names[0], "root");
        assert_eq!(names[4], "head");
        assert_eq!(names[12], "hand_R");
        for (name, joint) in names.iter().zip(ALL_JOINTS.iter()) {
            assert_eq!(*name, crate::avatar::joint_name(*joint));
        }
    }

    #[test]
    fn corrupt_and_empty_gltf_fall_back_to_procedural_mesh() {
        let fallback = build_avatar_mesh(&crate::traits::YuuTraits::default());
        for bytes in [&[][..], b"not a glb", b"glTF....garbagegarbage"] {
            let mesh = load_gltf_mesh(bytes);
            assert_eq!(mesh.len(), fallback.len(), "bytes {bytes:?}");
            assert_eq!(mesh[0].pos, fallback[0].pos);
        }
    }
}
