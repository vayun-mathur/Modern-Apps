# AGENTS.md — games/kart/ (':games:kart')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/kart/` + allowed shared modules. Do not root-scan.

_Install: ./install games/kart (dev by default)._
<!-- last-reviewed: 2026-11-21 -->
- Gradle: :games:kart / dir: games/kart/
- Package roots present: data, domain, platform, ui
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games, :sdk:controller, :library:e2ee-p2p
- Metadata: metadata_data/games-kart.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** 3D kart racer (Sunset Oval) — native Rust engine (Vulkan, with a GLES fallback for 32-bit TV hardware), kart physics, chase cam, items and AI; phones join as controllers.
**Game shape:** `Route` (`Lobby` / `Race` / `Results`) → `platform/KartViewModel` → `domain/` race/input rules + `platform/KartHost` (`:sdk:controller` lobby fan-in) → `platform/KartNative` → `src/main/rust/` engine; achievements → `:sdk:games` → hub.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/KartGameScreen.kt` → `platform/KartViewModel.kt` → `platform/KartNative.kt` / `domain/PlaceholderRace.kt`.
**Gotchas:**
- Native Rust: crate `kart_engine` in `src/main/rust/` (engine/physics/track/mesh/items + `vulkan/` and `gles/` renderers, GLSL `kart.vert`/`kart.frag` compiled at runtime by the GLES path). `rustNativeLib` also opts into `armeabi-v7a` for the ONN 4K box (32-bit, Mali-G31, no Vulkan) — the only such game; don't drop the armv7 ABI.
- `platform/KartNative` is the sole `kart_engine` wrapper: every call is gated on `KartNative.isAvailable`; without the `.so` the game runs `domain/PlaceholderRace` — never call native entry points unguarded.
- Controller dep IS present (`:sdk:controller`): `platform/KartHost` advertises `_gamepad._tcp`, accepts phones and fans `ControllerInput` into the race loop (`domain/GameInputLoop`); `platform/GamepadDirect` handles a locally attached gamepad in `MainActivity`. PQC identity stays behind lambdas in `KartHost` — this module never touches the `:library:e2ee-p2p` native library directly.
- Listing images are hand-captured — Layoutlib can't render the Vulkan surface; `src/screenshotTest` previews cover only the non-surface UI.
- `KartDataStore` (`data/`) is the sole prefs owner (cup pointer + lifetime counters); countdown is in-screen state of `Route.Race`, not a destination.

## Key files
### Root — entry + navigation
- com/vayunmathur/games/kart/MainActivity.kt
- com/vayunmathur/games/kart/Route.kt
- com/vayunmathur/games/kart/Navigation.kt
### data/ — progress store + models
- com/vayunmathur/games/kart/data/KartDataStore.kt
- com/vayunmathur/games/kart/data/KartModels.kt
### domain/ — pure race + input rules
- com/vayunmathur/games/kart/domain/KartRules.kt
- com/vayunmathur/games/kart/domain/LapTracker.kt
- com/vayunmathur/games/kart/domain/GameInputLoop.kt
- com/vayunmathur/games/kart/domain/PlaceholderRace.kt
- com/vayunmathur/games/kart/domain/RumbleDecider.kt
### platform/ — ViewModel, native wrapper, controller host
- com/vayunmathur/games/kart/platform/KartViewModel.kt
- com/vayunmathur/games/kart/platform/KartNative.kt — sole guarded `kart_engine` wrapper
- com/vayunmathur/games/kart/platform/KartHost.kt — `:sdk:controller` lobby host
- com/vayunmathur/games/kart/platform/KartPairingStore.kt
- com/vayunmathur/games/kart/platform/GamepadDirect.kt
- com/vayunmathur/games/kart/platform/RacePhase.kt
- com/vayunmathur/games/kart/platform/KartUiContract.kt
- com/vayunmathur/games/kart/platform/KartAchievementsManager.kt
- com/vayunmathur/games/kart/platform/AppBackupAgent.kt
### ui/ — race screens + native surface
- com/vayunmathur/games/kart/ui/KartGameScreen.kt
- com/vayunmathur/games/kart/ui/KartGamePage.kt
- com/vayunmathur/games/kart/ui/KartSurfaceView.kt
### src/main/rust/ — native engine (kart_engine)
- src/main/rust/src/lib.rs — JNI entry points
- src/main/rust/src/engine.rs
- src/main/rust/src/physics.rs
- src/main/rust/src/track.rs
- src/main/rust/src/race.rs
- src/main/rust/src/items.rs
- src/main/rust/src/mesh.rs
- src/main/rust/src/renderer.rs
- src/main/rust/src/vulkan/mod.rs
- src/main/rust/src/gles/mod.rs

## Verify (this module only)
```
./gradlew :games:kart:compileDevKotlin
./gradlew :games:kart:lint
./gradlew :games:kart:checkMetadata
```
