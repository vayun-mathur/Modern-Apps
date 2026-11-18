//! Pure-layout tests for the GLES kart vertex buffer contract.
use super::*;

#[test]
fn kart_vertex_layout_and_bytes_match_shader_contract() {
    assert_eq!(std::mem::size_of::<KartVertex>(), 24);
    assert_eq!(std::mem::offset_of!(KartVertex, pos), 0);
    assert_eq!(std::mem::offset_of!(KartVertex, color), 12);
    assert!(bytes_of(&[]).is_empty());
    let mesh = vec![
        KartVertex { pos: [1.0, 2.0, 3.0], color: [0.5, 0.25, 1.0] };
        2
    ];
    let bytes = bytes_of(&mesh);
    assert_eq!(bytes.len(), 2 * 24);
    assert_eq!(&bytes[0..4], &1.0f32.to_le_bytes());
    assert_eq!(&bytes[12..16], &0.5f32.to_le_bytes(), "color follows pos");
}

#[test]
fn caps_are_documented_mesh_budgets() {
    assert_eq!(TRACK_VERT_CAP, 65_536);
    assert_eq!(DYNAMIC_VERT_CAP, 8_192);
}
