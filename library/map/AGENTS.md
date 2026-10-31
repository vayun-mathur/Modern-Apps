# AGENTS.md — library/map/ (':library:map')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/map/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:map / dir: library/map/
- Package roots present: (vector-map renderer — one package + Rust core)
- Entry files: VectorMap.kt + MapScope.kt (no MainActivity/Route — library)
- Deps: (none — no project dependencies by design, #884)
- Network: tile range fetches reach the host app's `NetworkClient` over JNI
  (`NativeHttpBridge`, resolved at runtime from the app classpath — every map
  host already depends on `:library:network` directly); the live
  online/offline signal is injected per call site via `VectorMap(online = …)`
  for the same reason. This module declares INTERNET itself.
- Metadata: metadata_data/library-map.md (absent — libraries have none by design)
- Rust: yes / screenshotTest: no

## Overview
**Purpose:** Offline vector-map renderer on a Rust core — Vulkan surface, layers/markers/routes/traffic, gestures, camera state. Declares INTERNET itself (tile fetches). Used by maps, weather, taxi, fooddelivery.
**How to use:** `VectorMap` composable in a `MapScope` with `MapStyle` + `LayerOptions`; camera via `CameraState`; overlays for routes/markers/image layers; gestures via `MapGestures`. Tile/bridge work crosses JNI (`MapNative`).
**Entry points:** `VectorMap.kt` → `MapScope.kt` → `MapStyle.kt` → `SurfaceMapRenderer.kt`.
**Gotchas:** Another agent is mid-refactor here (tile cache moved `tile/cache.rs` → `tile/cache/` dir, `MapTileCache` touched in maps/) — check `git status`/`git log` on this module before deep work; rendering bugs are usually Rust-side.

## Key files
- com/vayunmathur/library/map/VectorMap.kt
- com/vayunmathur/library/map/MapScope.kt
- com/vayunmathur/library/map/MapStyle.kt
- com/vayunmathur/library/map/MapNative.kt — Rust bridge
- com/vayunmathur/library/map/SurfaceMapRenderer.kt
- com/vayunmathur/library/map/VulkanMapSurface.kt
- com/vayunmathur/library/map/CameraState.kt
- com/vayunmathur/library/map/MapRenderState.kt
- com/vayunmathur/library/map/MapGestures.kt
- com/vayunmathur/library/map/MapMarker.kt
- com/vayunmathur/library/map/LayerOptions.kt
- com/vayunmathur/library/map/RouteOverlay.kt
- com/vayunmathur/library/map/RouteStyle.kt
- com/vayunmathur/library/map/SurfaceMapOverlays.kt
- com/vayunmathur/library/map/ImageOverlay.kt
- com/vayunmathur/library/map/UserPuck.kt
- com/vayunmathur/library/map/TrafficColorTable.kt
- com/vayunmathur/library/map/GeoTypes.kt
- com/vayunmathur/library/map/Mercator.kt
- com/vayunmathur/library/map/Projection.kt
- com/vayunmathur/library/map/RegionLevel.kt
- com/vayunmathur/library/map/FrameStatsOverlay.kt

## Verify (this module only)
```
./gradlew :library:map:compileDevKotlin
./gradlew :library:map:lint
```
