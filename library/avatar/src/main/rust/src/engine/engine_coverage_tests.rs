//! Global-engine lifecycle tests using unique surface ids (93xxx) so they
//! stay parallel-safe alongside the other engine tests that share the map.
use super::*;
use crate::mesh::build_avatar_mesh;

fn cleanup(id: i32) {
    if let Ok(mut eng) = engine().lock() {
        eng.surfaces.remove(&id);
        eng.orbit.remove(&id);
    }
}

#[test]
fn upload_resize_tick_and_destroy_without_renderer() {
    let id = 93_001;
    cleanup(id);
    // Unknown surface: upload fails, tick/resize/destroy are no-ops.
    let mesh = build_avatar_mesh(&crate::traits::YuuTraits::default());
    assert!(!upload_mesh(id, &mesh));
    resize_renderer(id, 800, 600);
    tick_surface(id);
    destroy_renderer(id);
    assert_eq!(generation(id), 0);

    // Known surface without a renderer: upload still fails, resize records
    // the clamped size, tick returns early, paused tick returns early.
    set_config(id, "{}", "idle");
    assert!(!upload_mesh(id, &mesh));
    resize_renderer(id, 0, -3);
    {
        let eng = engine().lock().expect("engine");
        let s = eng.surfaces.get(&id).expect("surface");
        assert_eq!((s.width, s.height), (1, 1), "sizes clamp to at least 1");
    }
    resize_renderer(id, 640, 480);
    {
        let eng = engine().lock().expect("engine");
        let s = eng.surfaces.get(&id).expect("surface");
        assert_eq!((s.width, s.height), (640, 480));
    }
    tick_surface(id); // no renderer -> early return, no panic
    set_paused(id, true);
    tick_surface(id); // paused -> early return
    set_paused(id, false);
    destroy_renderer(id); // no renderer to destroy, mesh_count resets below
    {
        let eng = engine().lock().expect("engine");
        let s = eng.surfaces.get(&id).expect("surface");
        assert_eq!(s.mesh_count, 0);
        assert!(s.renderer.is_none());
    }
    remove_surface(id);
    assert!(!engine().lock().expect("engine").surfaces.contains_key(&id));
    cleanup(id);
}

#[test]
fn bundled_mesh_and_trait_paths_via_create_renderer_failure() {
    let id = 93_002;
    cleanup(id);
    // With no bundled mesh, create_renderer builds the procedural mesh,
    // sets surface size/orbit, then fails cleanly on the host (no window).
    set_config(id, r#"{"bodyHeight":0.5}"#, "wave");
    let ok = unsafe { create_renderer(id, std::ptr::null_mut(), 640, 480) };
    assert!(!ok, "host without a window must fail renderer creation");
    {
        let eng = engine().lock().expect("engine");
        let s = eng.surfaces.get(&id).expect("surface state is set before backend selection");
        assert_eq!((s.width, s.height), (640, 480));
        assert!(!s.paused);
    }
    // Bundled-mesh branch: corrupt bytes parse to a fallback, still fail cleanly.
    set_bundled_mesh(b"not a glb".to_vec());
    let ok = unsafe { create_renderer(id, std::ptr::null_mut(), 0, 0) };
    assert!(!ok);
    // Clear the bundled bytes for other tests (restore pristine engine state).
    if let Ok(mut eng) = engine().lock() {
        eng.bundled_mesh = None;
        eng.pack = None;
    }
    cleanup(id);
}

#[test]
fn try_vulkan_fails_cleanly_on_host() {
    let r = unsafe { try_vulkan(std::ptr::null_mut(), 640, 480, 640, 480) };
    assert!(r.is_err(), "host Vulkan attempt must fail cleanly: {:?}", r.map(|_| ()));
}

#[test]
fn lifecycle_generation_and_remove() {
    let id = 93_003;
    cleanup(id);
    assert_eq!(generation(id), 0);
    let g = next_generation(id);
    assert_eq!(g, 1);
    assert_eq!(next_generation(id), 2);
    assert_eq!(generation(id), 2);
    destroy_renderer(id);
    remove_surface(id);
    assert_eq!(generation(id), 0, "removed surface reports generation 0");
    cleanup(id);
}
