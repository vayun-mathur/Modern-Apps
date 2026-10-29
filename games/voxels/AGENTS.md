# AGENTS.md — games/voxels/ (':games:voxels')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/voxels/` + allowed shared modules. Do not root-scan.

_Install: ./install games/voxels (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :games:voxels / dir: games/voxels/
- Package roots present: ui, data, platform, network
- Entry files: MainActivity.kt + platform/MenuActivity.kt (menu/world select; no Route.kt)
- Deps: :sdk:games, :library:network, :library:e2ee-p2p
- Metadata: metadata_data/games-voxels.md (present)
- Rust: yes / screenshotTest: hand-committed

### Relay server (backend, outside this repo)
Host: `api.maproj.org` (Cloudflare FIRST_PARTY pins). Server checkout: `../location_share_server` (sibling of this repo root, `C:\Users\Vayun\Documents\code\location_share_server`). Live SSH: `ssh root@87.86.80.39`. Env template: `../location_share_server/.env.example` (copy to `.env`, never commit secrets). Full details: root `AGENTS.md` → "Backend server (outside this repo)".

## Overview
**Purpose:** Voxel sandbox on a hand-written Rust + Vulkan engine — infinite procedural worlds (25 biomes, villages), crafting/fishing/trading, saves, optional E2EE multiplayer through an untrusted relay. 19 languages.
**NOT a standard MA game:** `ui/` is thin Compose chrome (menus, inventory, hotbar, joystick) over `VoxelSurfaceView`; the world, physics and rendering live in Rust behind `platform/VoxelsNative`. Multiplayer sync (`network/VoxelsSync`) reuses the everysync E2EE-relay pattern.
**Entry points:** `MainActivity.kt` → `platform/MenuActivity.kt` (`parseJoinLink`-style world invites live here, mirroring everysync) → `ui/VoxelSurfaceView.kt` → `platform/VoxelsNative.kt` → `data/WorldManager.kt`.
**Gotchas:**
- Screenshots are hand-committed (Layoutlib can't render Vulkan) — never expect `:metadata` to regenerate them
- Engine bugs are Rust bugs — scope Kotlin-side work to `ui/`/`platform/` glue unless the trace crosses JNI
- Multiplayer is E2EE through an untrusted relay — relay sees ciphertext only; keep it that way

## Key files
### Root + platform/ — entry, menus, Rust bridge
- com/vayunmathur/games/voxels/MainActivity.kt
- com/vayunmathur/games/voxels/platform/MenuActivity.kt — world select + invites
- com/vayunmathur/games/voxels/platform/VoxelsNative.kt — JNI bridge (Kotlin side is glue)
- com/vayunmathur/games/voxels/platform/VoxelsAchievementsManager.kt
- com/vayunmathur/games/voxels/platform/AppBackupAgent.kt
- com/vayunmathur/games/voxels/platform/SoundFx.kt
- com/vayunmathur/games/voxels/platform/MusicFx.kt
### data/ + network/ — worlds + E2EE sync
- com/vayunmathur/games/voxels/data/WorldManager.kt
- com/vayunmathur/games/voxels/network/VoxelsSync.kt — relay sync (everysync pattern)
### ui/ — menus, inventory, HUD (thin chrome over the engine)
- com/vayunmathur/games/voxels/ui/VoxelSurfaceView.kt
- com/vayunmathur/games/voxels/ui/MenuScreens.kt
- com/vayunmathur/games/voxels/ui/InventoryScreen.kt
- com/vayunmathur/games/voxels/ui/InventoryCrafting.kt
- com/vayunmathur/games/voxels/ui/InventoryBlessings.kt
- com/vayunmathur/games/voxels/ui/Hotbar.kt
- com/vayunmathur/games/voxels/ui/Joystick.kt
- com/vayunmathur/games/voxels/ui/FurnaceScreen.kt
- com/vayunmathur/games/voxels/ui/StonecutterScreen.kt
- com/vayunmathur/games/voxels/ui/TradeScreen.kt
- com/vayunmathur/games/voxels/ui/VoxelCatalog.kt
- com/vayunmathur/games/voxels/ui/Theme.kt — game theme (not DynamicTheme)

## Verify (this module only)
```
./gradlew :games:voxels:compileDevKotlin
./gradlew :games:voxels:lint
./gradlew :games:voxels:checkMetadata
```
