// Packed-mesh attachments: multi-primitive glTF parsing + per-style selection.
//
// Split out of `mesh.rs` so each file stays under the 500-line Rust limit.
// Same module scope via `mesh.rs` (`include!`), so item order is irrelevant.
//
// The v1 pack contract (`models/README.md`): one `base_upperbody.glb` holding
// the base body under rig-joint nodes plus named attachment nodes —
// `hair_00`..`hair_11` (all real), `glasses_01`..`glasses_06` (0 = hidden),
// `beard_01`..`beard_05` (0 = hidden) — parented under `Head`. [`GltfPack`]
// parses the pack once; [`assemble_pack`] concatenates the base body with the
// trait-selected attachments (capped at [`PACK_VERT_CAP`]) per surface.

use std::collections::HashMap;

/// Assembled-vertex budget: matches the Vulkan/GLES static caps.
const PACK_VERT_CAP: usize = 65_536;

/// One parsed primitive with its owning node's attachment tag.
struct PackPrim {
    /// Attachment tag from the owner node or its ancestors (`None` = base body).
    attach: Option<(AttachmentKind, usize)>,
    /// Decoded verts (joints already remapped onto the UBO order).
    verts: Vec<YuuVertex>,
}

/// Attachment group selected by one trait field.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum AttachmentKind {
    /// `hairStyle` 0..11 — always visible, no hidden slot.
    Hair,
    /// `glassesStyle` — 0 = hidden, 1..6 select.
    Glasses,
    /// `beardStyle` — 0 = hidden, 1..5 select.
    Beard,
}

/// Classify an attachment node name (`hair_03`, `glasses_02`, `beard_05`).
///
/// Out-of-contract names (bad prefix or number) return `None` and parse as
/// base body, so a future pack addition never breaks an old engine.
fn classify_attachment(name: &str) -> Option<(AttachmentKind, usize)> {
    let (prefix, num) = name.split_once('_')?;
    let idx: usize = num.parse().ok()?;
    match prefix {
        "hair" if idx < 12 => Some((AttachmentKind::Hair, idx)),
        "glasses" if (1..=6).contains(&idx) => Some((AttachmentKind::Glasses, idx)),
        "beard" if (1..=5).contains(&idx) => Some((AttachmentKind::Beard, idx)),
        _ => None,
    }
}

/// Parsed packed GLB: every mesh primitive with its attachment tag.
///
/// Parse once per bundled asset ([`GltfPack::parse`]); assemble per surface
/// per trait change ([`assemble_pack`]).
pub struct GltfPack {
    prims: Vec<PackPrim>,
}

impl GltfPack {
    /// Parse all mesh primitives, tagging each with its owner node's (or
    /// ancestor's) attachment name. `None` on corrupt input — the caller falls
    /// back to the procedural mesh.
    pub fn parse(bytes: &[u8]) -> Option<Self> {
        let (doc, buffers, _) = gltf::import_slice(bytes).ok()?;
        let remap = skin_remap(&doc);
        // Mesh index -> first owning node index.
        let mut owner: HashMap<usize, usize> = HashMap::new();
        // Child node index -> parent node index (for ancestor walks).
        let mut parent: HashMap<usize, usize> = HashMap::new();
        // Node index -> name.
        let mut names: Vec<Option<String>> = Vec::new();
        for node in doc.nodes() {
            let ni = node.index();
            if ni >= names.len() {
                names.resize(ni + 1, None);
            }
            names[ni] = node.name().map(str::to_string);
            if let Some(m) = node.mesh() {
                owner.entry(m.index()).or_insert(ni);
            }
            for child in node.children() {
                parent.insert(child.index(), ni);
            }
        }
        let mut prims = Vec::new();
        for mesh in doc.meshes() {
            // Nearest attachment name wins: the node itself, then ancestors
            // (covers packs that group variants under `hair`/`glasses` parents).
            let tag = owner.get(&mesh.index()).copied().and_then(|start| {
                let mut ni = start;
                for _ in 0..64 {
                    if let Some(tag) = names
                        .get(ni)
                        .and_then(|n| n.as_deref())
                        .and_then(classify_attachment)
                    {
                        return Some(tag);
                    }
                    match parent.get(&ni) {
                        Some(&p) => ni = p,
                        None => return None,
                    }
                }
                None
            });
            for prim in mesh.primitives() {
                let (mut verts, skinned) = read_primitive(&buffers, &remap, prim)?;
                if tag.is_some() && !skinned {
                    // Unskinned attachments (card/cap shells with no weights)
                    // ride the head rigidly instead of the root default.
                    let head = j("head");
                    for v in &mut verts {
                        v.joints = [head, 0.0, 0.0, 0.0];
                        v.weights = [1.0, 0.0, 0.0, 0.0];
                    }
                }
                prims.push(PackPrim { attach: tag, verts });
            }
        }
        if prims.is_empty() {
            return None;
        }
        Some(Self { prims })
    }
}

/// Assemble base body + trait-selected attachments for one surface.
///
/// `hairStyle` 0..11 selects `hair_NN` 1:1 (clamped); `glassesStyle` /
/// `beardStyle` 0 hides, otherwise selects `glasses_0N` / `beard_0N`
/// (clamped). `None` when over the vertex cap or when nothing assembles —
/// both fall back to the procedural mesh.
pub fn assemble_pack(pack: &GltfPack, traits: &YuuTraits) -> Option<Vec<YuuVertex>> {
    let hair_sel = traits.hair_style.clamp(0, 11) as usize;
    let glasses_sel = traits.glasses_style.clamp(0, 6);
    let beard_sel = traits.beard_style.clamp(0, 5);
    let mut out = Vec::new();
    for prim in &pack.prims {
        let keep = match prim.attach {
            None => true,
            Some((AttachmentKind::Hair, i)) => i == hair_sel,
            Some((AttachmentKind::Glasses, i)) => glasses_sel != 0 && i == glasses_sel as usize,
            Some((AttachmentKind::Beard, i)) => beard_sel != 0 && i == beard_sel as usize,
        };
        if !keep {
            continue;
        }
        if out.len() + prim.verts.len() > PACK_VERT_CAP {
            // Over budget: bail to the procedural fallback rather than ship a
            // half-assembled avatar (present glasses, missing hair).
            return None;
        }
        out.extend_from_slice(&prim.verts);
    }
    if out.is_empty() {
        return None;
    }
    Some(out)
}

#[cfg(test)]
mod pack_tests {
    use super::*;
    use crate::traits::YuuTraits;

    fn push_u32_le(out: &mut Vec<u8>, v: u32) {
        out.extend_from_slice(&v.to_le_bytes());
    }

    /// Minimal 3-node GLB: `body` triangle at y 0..1, `hair_03` triangle at
    /// y ~2, `glasses_02` triangle at y ~3. No skin weights (attachments ride
    /// the head rigidly). Built in-test — no checked-in binary.
    fn test_pack_glb() -> Vec<u8> {
        let mut bin = Vec::new();
        for tri in [
            [0.0f32, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0],
            [0.0, 2.0, 0.0, 1.0, 2.0, 0.0, 0.0, 3.0, 0.0],
            [0.0, 3.0, 0.0, 1.0, 3.0, 0.0, 0.0, 4.0, 0.0],
        ] {
            for v in tri {
                bin.extend_from_slice(&v.to_le_bytes());
            }
        }
        let json = [
            r#"{"asset":{"version":"2.0"},"scene":0,"#,
            r#""scenes":[{"nodes":[0,1,2]}],"#,
            r#""nodes":[{"name":"body","mesh":0},"#,
            r#"{"name":"hair_03","mesh":1},"#,
            r#"{"name":"glasses_02","mesh":2}],"#,
            r#""meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}, "#,
            r#"{"primitives":[{"attributes":{"POSITION":1}}]}, "#,
            r#"{"primitives":[{"attributes":{"POSITION":2}}]}],"#,
            &format!(r#""buffers":[{{"byteLength":{}}}],"#, bin.len()),
            r#""bufferViews":["#,
            r#"{"buffer":0,"byteOffset":0,"byteLength":36},"#,
            r#"{"buffer":0,"byteOffset":36,"byteLength":36},"#,
            r#"{"buffer":0,"byteOffset":72,"byteLength":36}],"#,
            r#""accessors":["#,
            r#"{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","#,
            r#""min":[0.0,0.0,0.0],"max":[1.0,1.0,0.0]},"#,
            r#"{"bufferView":1,"componentType":5126,"count":3,"type":"VEC3","#,
            r#""min":[0.0,2.0,0.0],"max":[1.0,3.0,0.0]},"#,
            r#"{"bufferView":2,"componentType":5126,"count":3,"type":"VEC3","#,
            r#""min":[0.0,3.0,0.0],"max":[1.0,4.0,0.0]}]}"#,
        ]
        .concat();
        let mut json_bytes = json.into_bytes();
        while json_bytes.len() % 4 != 0 {
            json_bytes.push(0x20);
        }
        while bin.len() % 4 != 0 {
            bin.push(0);
        }
        let total = (12 + 8 + json_bytes.len() + 8 + bin.len()) as u32;
        let mut out = Vec::with_capacity(total as usize);
        push_u32_le(&mut out, 0x46546C67);
        push_u32_le(&mut out, 2);
        push_u32_le(&mut out, total);
        push_u32_le(&mut out, json_bytes.len() as u32);
        push_u32_le(&mut out, 0x4E4F534A);
        out.extend_from_slice(&json_bytes);
        push_u32_le(&mut out, bin.len() as u32);
        push_u32_le(&mut out, 0x004E4942);
        out.extend_from_slice(&bin);
        out
    }

    fn max_y(mesh: &[YuuVertex]) -> f32 {
        mesh.iter().map(|v| v.pos[1]).fold(f32::MIN, f32::max)
    }

    #[test]
    fn pack_parses_three_tagged_prims() {
        let pack = GltfPack::parse(&test_pack_glb()).expect("test pack parses");
        assert_eq!(pack.prims.len(), 3);
        assert!(pack.prims[0].attach.is_none());
        assert_eq!(pack.prims[1].attach, Some((AttachmentKind::Hair, 3)));
        assert_eq!(pack.prims[2].attach, Some((AttachmentKind::Glasses, 2)));
    }

    #[test]
    fn assemble_selects_per_traits() {
        let pack = GltfPack::parse(&test_pack_glb()).expect("test pack parses");
        // Defaults: hair 0 (no such node — hair has no hidden slot) and
        // glasses 0 (hidden) → base body only.
        let base = assemble_pack(&pack, &YuuTraits::default()).expect("assemble");
        assert_eq!(base.len(), 3);
        assert!(max_y(&base) < 1.5);
        // hairStyle 3 → hair_03 joins the assembly.
        let mut t = YuuTraits { hair_style: 3, ..Default::default() };
        let hairy = assemble_pack(&pack, &t).expect("assemble");
        assert_eq!(hairy.len(), 6);
        assert!(max_y(&hairy) > 1.5);
        // Unskinned attachments ride the head rigidly, not the root default.
        let head = j("head");
        assert!(hairy.iter().skip(3).all(|v| v.joints == [head, 0.0, 0.0, 0.0]));
        assert!(hairy.iter().skip(3).all(|v| v.weights == [1.0, 0.0, 0.0, 0.0]));
        // glassesStyle 0 hides; 2 shows glasses_02.
        t.glasses_style = 2;
        let full = assemble_pack(&pack, &t).expect("assemble");
        assert_eq!(full.len(), 9);
        assert!(max_y(&full) > 2.5);
        // Out-of-range styles clamp onto real nodes (never panic, never leak).
        t.glasses_style = 0;
        t.beard_style = 99;
        let clamped = assemble_pack(&pack, &t).expect("assemble");
        assert_eq!(clamped.len(), 6);
    }

    #[test]
    fn classify_rejects_out_of_contract() {
        assert_eq!(classify_attachment("hair_00"), Some((AttachmentKind::Hair, 0)));
        assert_eq!(classify_attachment("hair_11"), Some((AttachmentKind::Hair, 11)));
        assert_eq!(classify_attachment("hair_12"), None);
        // Style 0 is hidden for glasses/beard — never a node name.
        assert_eq!(classify_attachment("glasses_00"), None);
        assert_eq!(classify_attachment("glasses_06"), Some((AttachmentKind::Glasses, 6)));
        assert_eq!(classify_attachment("glasses_07"), None);
        assert_eq!(classify_attachment("beard_05"), Some((AttachmentKind::Beard, 5)));
        assert_eq!(classify_attachment("beard_06"), None);
        assert_eq!(classify_attachment("head"), None);
        assert_eq!(classify_attachment("hair"), None);
    }

    #[test]
    fn corrupt_pack_parses_to_none() {
        assert!(GltfPack::parse(b"not a glb").is_none());
        assert!(GltfPack::parse(&[]).is_none());
    }
}
