//! Tests for the EGL config attribute tables (pure data, no display needed).
use super::*;

fn pairs(attribs: &[egl::Int]) -> Vec<(egl::Int, egl::Int)> {
    assert_eq!(attribs.last(), Some(&egl::NONE), "table must be NONE-terminated");
    attribs[..attribs.len() - 1]
        .chunks_exact(2)
        .map(|c| (c[0], c[1]))
        .collect()
}

fn depth_of(attribs: &[egl::Int]) -> egl::Int {
    pairs(attribs)
        .into_iter()
        .find(|(k, _)| *k == egl::DEPTH_SIZE)
        .map(|(_, v)| v)
        .expect("depth entry")
}

#[test]
fn config_tables_are_wellformed_and_ordered_richest_first() {
    for t in [CONFIG_ATTRIBS_D24, CONFIG_ATTRIBS_D16, CONFIG_ATTRIBS_565] {
        let p = pairs(t);
        assert!(p.iter().any(|(k, v)| *k == egl::RENDERABLE_TYPE && *v == egl::OPENGL_ES3_BIT));
        assert!(p.iter().any(|(k, v)| *k == egl::SURFACE_TYPE && *v == egl::WINDOW_BIT));
    }
    assert_eq!(depth_of(CONFIG_ATTRIBS_D24), 24);
    assert_eq!(depth_of(CONFIG_ATTRIBS_D16), 16);
    assert_eq!(depth_of(CONFIG_ATTRIBS_565), 16);
    let f = pairs(CONFIG_ATTRIBS_565);
    assert!(f.iter().any(|(k, v)| *k == egl::RED_SIZE && *v == 5));
    assert!(f.iter().any(|(k, v)| *k == egl::GREEN_SIZE && *v == 6));
    assert!(f.iter().any(|(k, v)| *k == egl::BLUE_SIZE && *v == 5));
}

#[test]
fn context_attribs_request_es3() {
    assert_eq!(CONTEXT_ATTRIBS, &[egl::CONTEXT_CLIENT_VERSION, 3, egl::NONE]);
}

#[test]
fn egl_new_fails_cleanly_without_a_window_on_host() {
    let r = unsafe { Egl::new(std::ptr::null_mut()) };
    assert!(r.is_err(), "host without EGL/window must fail cleanly");
}
