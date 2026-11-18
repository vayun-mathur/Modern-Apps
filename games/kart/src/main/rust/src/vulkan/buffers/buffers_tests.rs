//! Pure-layout tests for the Vulkan kart vertex/UBO contract.
use super::*;

#[test]
fn binding_and_attributes_match_shader_locations() {
    let b = KartVkVertex::binding_description();
    assert_eq!(b.binding, 0);
    assert_eq!(b.stride as usize, std::mem::size_of::<KartVkVertex>());
    assert!(b.input_rate == vk::VertexInputRate::VERTEX);
    let attrs = KartVkVertex::attribute_descriptions();
    assert_eq!(attrs.len(), 2);
    assert_eq!(attrs[0].location, 0);
    assert_eq!(attrs[1].location, 1);
    assert!(attrs[0].format == vk::Format::R32G32B32_SFLOAT);
    assert!(attrs[1].format == vk::Format::R32G32B32_SFLOAT);
    assert_eq!(attrs[0].offset as usize, std::mem::offset_of!(KartVkVertex, pos));
    assert_eq!(attrs[1].offset as usize, std::mem::offset_of!(KartVkVertex, color));
    assert!(attrs[0].offset < attrs[1].offset);
    // KartVkVertex mirrors KartVertex (pos+color, 24 bytes).
    assert_eq!(std::mem::size_of::<KartVkVertex>(), 24);
    assert_eq!(KartVkVertex::default().pos, [0.0; 3]);
}

#[test]
fn ubo_default_is_identity_with_documented_defaults() {
    let u = KartUbo::default();
    assert_eq!(u.view_proj, glam::Mat4::IDENTITY.to_cols_array_2d());
    assert_eq!(u.sun_dir, [0.35, -1.0, 0.45]);
    assert_eq!(u.cam_pos, [0.0, 4.0, 20.0]);
    assert_eq!(u._pad0, 0.0);
    assert_eq!(u._pad1, 0.0);
    assert_eq!(std::mem::size_of::<KartUbo>(), 64 + 16 + 16);
}
