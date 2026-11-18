//! Shader-source contract tests (compilation itself needs a GL context).
use super::*;

#[test]
fn uniform_name_constants_appear_in_shader_sources() {
    for name in [U_VIEW_PROJ, U_SUN_DIR, U_CAM_POS] {
        assert!(
            VERT_SRC.contains(name) || FRAG_SRC.contains(name),
            "uniform {name} must be declared in a shader"
        );
    }
    assert!(VERT_SRC.starts_with("#version 300 es"));
    assert!(FRAG_SRC.starts_with("#version 300 es"));
}

#[test]
fn shader_sources_are_nonempty_and_distinct() {
    assert!(VERT_SRC.len() > 100);
    assert!(FRAG_SRC.len() > 100);
    assert_ne!(VERT_SRC, FRAG_SRC);
}
