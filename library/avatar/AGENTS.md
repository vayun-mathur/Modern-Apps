# AGENTS.md — library/avatar/ (':library:avatar')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/avatar/` + allowed shared modules. Do not root-scan.

_Install: dev by default (library, no launcher)._
<!-- last-reviewed: 2026-10-19 -->
- Gradle: :library:avatar / dir: library/avatar/
- Package roots present: `com.vayunmathur.library.avatar` (bridge), `...avatar.components` (surface/composable), `...avatar.yuu` (pose contract)
- Entry files: YuuAvatarNative.kt (JNI bridge), components/YuuAvatar3D.kt (hero composable)
- Deps: :library:ui (permission-free since #884), :library:log, androidx.lifecycle.runtime.compose
- Rust: yes (`src/main/rust/`, crate `yuu_avatar`, glTF + skinning)
- Metadata: none (library, no listing)

## Overview
**Purpose:** Shared 3D Yuu avatar engine: glTF upper-body base mesh, skeletal skinning, preset poses, Vulkan-first + GLES fallback. First consumer: `:games:hub` hero surfaces; future 3D games reuse the same bridge.
**Key features:** 13-joint upper-body rig, 5 preset poses, per-surface multi-renderer engine, procedural fallback mesh, turntable preview camera.
**Architecture:** Rust crate (`src/main/rust/`: `avatar.rs` skeleton/traits/poses → `engine.rs` per-surface lifecycle → `renderer.rs` dispatch → `vulkan/` + `gles/`) ↔ JNI (`YuuAvatarNative`, `internal object`, package matches directory) ↔ Compose (`YuuAvatar3D` + `YuuAvatarSurfaceView`).
**Entry points:** `YuuAvatar3D(configJson, poseId, surfaceId, modifier, fallback)` → `YuuAvatarNative` → Rust `lib.rs`.
## Gotchas
- Rust `preset_poses` is authoritative; `yuu/YuuPose.kt` `YUU_POSES` is the hand-kept mirror — update both.
- Vulkan `new()` brings up the swapchain; GLES is the fallback via `Renderer::select`. Layoutlib can't render either — previews must be 2D/HUD-only.
- Surface ids are Kotlin-owned ints; the engine routes config/pose/lifecycle per id. Dispose must call `surfaceDestroyed(id)`.

## Base mesh asset (`src/main/assets/models/base_upperbody.glb`)
- Handoff: `YuuAvatarAssets.adoptBundledMesh` (detached fd, `noCompress ".glb"`)
  → `nativeSetBundledMesh` → `Engine::set_bundled_mesh` → parsed on next
  surface creation. Absent asset keeps the procedural fallback.
- Authoring contract: ~13–15 joints named per `joint_name()` in `avatar.rs`
  (`root/spine/chest/neck/head`, `clavicle_L/R`, `upperArm_L/R`,
  `forearm_L/R`, `hand_L/R`); 4-weight skinning (`JOINTS_0`/`WEIGHTS_0`
  required); distinct `glasses` / `beard` / hair nodes for attachments; 2K
  albedo honoring the skin `[0,.25]` / shirt `[.5,1]` UV split from `mesh.rs`;
  ≤65536 verts (tighter cap for grid cells).

## Key files
### Kotlin — bridge + surface
- com/vayunmathur/library/avatar/YuuAvatarNative.kt
- com/vayunmathur/library/avatar/components/YuuAvatarSurfaceView.kt
- com/vayunmathur/library/avatar/components/YuuAvatar3D.kt
- com/vayunmathur/library/avatar/yuu/YuuPose.kt
### Rust — engine crate (see src/main/rust/AGENTS.md for the skeleton contract)
- src/main/rust/src/avatar.rs — joints, traits, poses, FK
- src/main/rust/src/engine.rs — per-surface lifecycle + camera
- src/main/rust/src/mesh.rs — procedural fallback + glTF loader
- src/main/rust/src/lib.rs — JNI entry points
- src/main/rust/src/vulkan/ + src/main/rust/src/gles/ — backends

## Verify (this module only)
```
./gradlew :library:avatar:compileDevKotlin
./gradlew :library:avatar:lint
```
Plus `cargo test -p yuu_avatar` in `src/main/rust/`.
