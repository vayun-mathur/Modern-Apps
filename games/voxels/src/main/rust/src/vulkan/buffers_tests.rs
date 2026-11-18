//! CPU-side buffer layout contract (no GPU needed).

use super::*;

#[test]
fn vertex_layout_matches_the_struct() {
    let bind = Vertex::binding_description();
    assert_eq!(bind.binding, 0);
    assert_eq!(bind.stride as usize, std::mem::size_of::<Vertex>());
    let attrs = Vertex::attribute_descriptions();
    assert_eq!(attrs.len(), 7);
    for (i, a) in attrs.iter().enumerate() {
        assert_eq!(a.location, i as u32);
        assert_eq!(a.binding, 0);
    }
    assert_eq!(attrs[0].offset as usize, std::mem::offset_of!(Vertex, pos));
    assert_eq!(attrs[1].offset as usize, std::mem::offset_of!(Vertex, uv));
    assert_eq!(attrs[5].offset as usize, std::mem::offset_of!(Vertex, normal));
    assert_eq!(attrs[6].offset as usize, std::mem::offset_of!(Vertex, light));
    // Vertex is a plain GPU record: no padding surprises beyond alignment.
    assert_eq!(std::mem::size_of::<Vertex>(), 4 * (3 + 2 + 3 + 1 + 1 + 3 + 1));
}

#[test]
fn ubo_default_is_the_daytime_identity() {
    let u = UboData::default();
    assert_eq!(u.view_proj, glam::Mat4::IDENTITY.to_cols_array_2d());
    assert_eq!(u.inv_view_proj, glam::Mat4::IDENTITY.to_cols_array_2d());
    assert_eq!(u.light_view_proj, glam::Mat4::IDENTITY.to_cols_array_2d());
    assert_eq!(u.day_factor, 1.0);
    assert_eq!(u.rain, 0.0);
    assert_eq!(u.player_pos, [0.0, 70.0, 0.0, 0.0]);
    assert!(u.fog_density > 0.0);
}
