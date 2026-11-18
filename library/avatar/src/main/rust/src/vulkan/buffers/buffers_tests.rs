//! Pure-layout tests for the Vulkan vertex/UBO contract (no device needed).
use super::*;
use crate::mesh::YuuVertex;

#[test]
fn vk_vertex_conversion_copies_every_field() {
    let v = YuuVertex {
        pos: [1.0, 2.0, 3.0],
        normal: [0.0, 1.0, 0.0],
        uv: [0.25, 0.75],
        joints: [1.0, 2.0, 3.0, 0.0],
        weights: [0.5, 0.3, 0.2, 0.0],
    };
    let k = YuuVkVertex::from(&v);
    assert_eq!(k.pos, v.pos);
    assert_eq!(k.normal, v.normal);
    assert_eq!(k.uv, v.uv);
    assert_eq!(k.joints, v.joints);
    assert_eq!(k.weights, v.weights);
    // Default is all-zero (matches a T-posed unweighted vertex before skinning).
    assert_eq!(YuuVkVertex::default().pos, [0.0; 3]);
}

#[test]
fn binding_and_attributes_match_shader_locations() {
    let b = YuuVkVertex::binding_description();
    assert_eq!(b.binding, 0);
    assert_eq!(b.stride as usize, std::mem::size_of::<YuuVkVertex>());
    assert!(b.input_rate == vk::VertexInputRate::VERTEX);
    let attrs = YuuVkVertex::attribute_descriptions();
    assert_eq!(attrs.len(), 5);
    for (i, a) in attrs.iter().enumerate() {
        assert_eq!(a.binding, 0);
        assert_eq!(a.location, i as u32);
    }
    assert!(attrs[0].format == vk::Format::R32G32B32_SFLOAT);
    assert!(attrs[1].format == vk::Format::R32G32B32_SFLOAT);
    assert!(attrs[2].format == vk::Format::R32G32_SFLOAT);
    assert!(attrs[3].format == vk::Format::R32G32B32A32_SFLOAT);
    assert!(attrs[4].format == vk::Format::R32G32B32A32_SFLOAT);
    assert_eq!(attrs[0].offset as usize, std::mem::offset_of!(YuuVkVertex, pos));
    assert_eq!(attrs[1].offset as usize, std::mem::offset_of!(YuuVkVertex, normal));
    assert_eq!(attrs[2].offset as usize, std::mem::offset_of!(YuuVkVertex, uv));
    assert_eq!(attrs[3].offset as usize, std::mem::offset_of!(YuuVkVertex, joints));
    assert_eq!(attrs[4].offset as usize, std::mem::offset_of!(YuuVkVertex, weights));
    // Offsets are strictly increasing (no overlapping attributes).
    assert!(attrs.windows(2).all(|w| w[0].offset < w[1].offset));
}

#[test]
fn ubo_default_is_identity_viewproj_with_documented_defaults() {
    let u = YuuUbo::default();
    assert_eq!(u.view_proj, glam::Mat4::IDENTITY.to_cols_array_2d());
    assert_eq!(u.sun_dir, [0.35, -1.0, 0.45]);
    assert_eq!(u.cam_pos, [0.0, 1.1, 2.6]);
    assert_eq!(u._pad0, 0.0);
    assert_eq!(u._pad1, 0.0);
    assert_eq!(u.bones.len(), MAX_BONES);
    // std140 layout: vec3+pad, vec3+pad, then bones.
    assert_eq!(
        std::mem::size_of::<YuuUbo>(),
        64 + 16 + 16 + MAX_BONES * 64
    );
}
