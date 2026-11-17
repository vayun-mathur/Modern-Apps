//! Avatar engine: per-surface lifecycle, preview camera, per-frame bone upload.
//!
//! The engine owns a map of live surfaces (`surfaceId -> SurfaceState`), each
//! with its own renderer, render thread generation, traits + pose, and size.
//! The CPU mesh bytes are shared across surfaces (single `Arc` allocation);
//! GPU buffers + UBO/pose stay per-surface. The render threads tick here;
//! [`tick_surface`] uploads the bone-matrix UBO and draws one surface per call.

use crate::avatar::{compute_bone_matrices, MAX_BONES};
use crate::pose::{preset_by_id, preset_poses, YuuPose};
use crate::traits::YuuTraits;
use crate::mesh::{assemble_pack, build_avatar_mesh, load_gltf_mesh, GltfPack, YuuVertex};
use crate::renderer::Renderer;
use glam::Mat4;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Instant;

/// Per-surface state: one renderer + thread + config behind one surface id.
pub struct SurfaceState {
    /// Active GPU backend, if the surface is live.
    pub renderer: Option<Renderer>,
    /// Current trait mapping (from `YuuFaceConfig` JSON).
    pub traits: YuuTraits,
    /// Current pose preset.
    pub pose: YuuPose,
    /// Turntable clock start.
    pub started: Instant,
    /// Vertex count from the last upload.
    pub mesh_count: usize,
    /// Surface width for the camera aspect.
    pub width: u32,
    /// Surface height for the camera aspect.
    pub height: u32,
    /// Paused (surface alive but hidden): skip draws, keep state.
    pub paused: bool,
    /// Render-thread generation: bumped on recreate so a stale thread
    /// (rotation) exits instead of racing the new one.
    pub generation: u64,
    /// User-driven orbit yaw radians (unlimited). Used when `autoRotate` is
    /// false (editor); heroes keep the time-based turntable.
    pub yaw: f32,
    /// User-driven orbit pitch radians, clamped to ±MAX_PITCH.
    pub pitch: f32,
    /// Whether the camera auto-orbits. The editor sets false on creation.
    pub auto_rotate: bool,
    /// Face atlas needs (re)upload: set on creation + every trait change,
    /// consumed by the tick thread (which owns the GPU context).
    pub atlas_dirty: bool,
    /// Procedural mesh needs rebuild + re-upload (trait-dependent decal UVs):
    /// set on trait change when no bundled glb is active, consumed by the
    /// tick thread. glTF surfaces never set this (asset UVs are static).
    pub mesh_dirty: bool,
}

/// Max orbit pitch: enough to inspect top/bottom, never straight down/up.
pub const MAX_PITCH: f32 = 0.6;

impl SurfaceState {
    /// Fresh surface with the idle pose.
    fn new(width: u32, height: u32) -> Self {
        Self {
            renderer: None,
            traits: YuuTraits::default(),
            pose: preset_poses().into_iter().next().unwrap_or(YuuPose {
                id: "idle".into(),
                name: "Idle".into(),
                joints: Default::default(),
            }),
            started: Instant::now(),
            mesh_count: 0,
            width: width.max(1),
            height: height.max(1),
            paused: false,
            generation: 0,
            yaw: 0.0,
            pitch: 0.0,
            auto_rotate: true,
            atlas_dirty: true,
            mesh_dirty: false,
        }
    }
}

/// User orbit preference: survives surface removal (rotation) so the editor
/// keeps its drag position and auto-rotate opt-out.
#[derive(Clone, Copy, Debug)]
pub struct OrbitPrefs {
    yaw: f32,
    pitch: f32,
    auto_rotate: bool,
}

impl Default for OrbitPrefs {
    fn default() -> Self {
        Self { yaw: 0.0, pitch: 0.0, auto_rotate: true }
    }
}

/// Engine state behind the global lock: the surface map plus shared assets.
pub struct Engine {
    /// Live surfaces by Kotlin-owned surface id.
    pub surfaces: HashMap<i32, SurfaceState>,
    /// Orbit prefs by surface id: written on input, restored on (re)create,
    /// never cleared by surface removal.
    pub orbit: HashMap<i32, OrbitPrefs>,
    /// Shared CPU mesh (parsed glb when bundled, else the procedural fallback
    /// with default-trait decals), uploaded per-surface to GPU at creation.
    /// Procedural surfaces rebuild their own mesh from traits on change (the
    /// decal UVs are trait-dependent); glTF surfaces keep sharing this.
    pub mesh: Arc<Vec<YuuVertex>>,
    /// Bundled glb bytes, when the APK ships `base_upperbody.glb`.
    pub bundled_mesh: Option<Vec<u8>>,
    /// Parsed packed GLB (base body + named attachments), when bundled bytes
    /// exist. Parsed once per asset; assembled per surface per trait change.
    pub pack: Option<GltfPack>,
}

impl Engine {
    /// Fresh engine with the procedural fallback mesh.
    pub fn new() -> Self {
        Self {
            surfaces: HashMap::new(),
            orbit: HashMap::new(),
            mesh: Arc::new(build_avatar_mesh(&crate::traits::YuuTraits::default())),
            bundled_mesh: None,
            pack: None,
        }
    }

    /// Get or create the state for a surface.
    fn surface_mut(&mut self, surface_id: i32, width: u32, height: u32) -> &mut SurfaceState {
        self.surfaces
            .entry(surface_id)
            .or_insert_with(|| SurfaceState::new(width, height))
    }
}

impl Default for Engine {
    fn default() -> Self {
        Self::new()
    }
}

// SAFETY: guarded by the mutex; each renderer (Vulkan/GLES handles) is only
// touched on its own surface's render thread, same arrangement as games/kart.
unsafe impl Send for Engine {}

static ENGINE: OnceLock<Mutex<Engine>> = OnceLock::new();

fn engine() -> &'static Mutex<Engine> {
    ENGINE.get_or_init(|| Mutex::new(Engine::new()))
}

/// Adopt the engine state (idempotent).
pub fn ensure_engine() {
    let _ = engine();
}

// Config setters, per-frame tick/render, and teardown live in part files so
// each file stays under the 500-line Rust limit (same module scope via `include!`).
include!("engine_config.rs");
include!("engine_frame.rs");
include!("engine_lifecycle.rs");
