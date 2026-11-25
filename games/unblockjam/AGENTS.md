# AGENTS.md — games/unblockjam/ (':games:unblockjam')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/unblockjam/` + allowed shared modules. Do not root-scan.

_Install: ./install games/unblockjam (dev by default)._
- Gradle: :games:unblockjam / dir: games/unblockjam/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games, :library:work
- Metadata: metadata_data/games-unblockjam.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Rush Hour sliding-block jam — 250 graded levels with optimal-move records + solver-checked daily level. 19 languages. 100% offline.
**Game shape:** Minimal `ui/` (theme/type) + board gestures in `platform/BlockDragGestures` → `platform/UnblockJamViewModel` → `domain/RushHourSolver` + `data/` level data/generator; dailies via `:library:work`; achievements → `:sdk:games` → hub.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `platform/UnblockJamViewModel.kt` → `domain/RushHourSolver.kt`.
**Gotchas:** Level solvability + optimal-move counts come from the solver — level-pack edits must re-run it.

## Key files
### Root — entry + navigation
- com/vayunmathur/games/unblockjam/MainActivity.kt
- com/vayunmathur/games/unblockjam/Navigation.kt
- com/vayunmathur/games/unblockjam/Route.kt
### data/ + domain/ — levels + solver
- com/vayunmathur/games/unblockjam/data/LevelData.kt
- com/vayunmathur/games/unblockjam/data/DailyLevelGenerator.kt
- com/vayunmathur/games/unblockjam/data/CompletedLevelsRepository.kt
- com/vayunmathur/games/unblockjam/domain/RushHourSolver.kt
### platform/ — ViewModel + gestures + achievements
- com/vayunmathur/games/unblockjam/platform/UnblockJamViewModel.kt
- com/vayunmathur/games/unblockjam/platform/GameUiContract.kt
- com/vayunmathur/games/unblockjam/platform/BlockDragGestures.kt
- com/vayunmathur/games/unblockjam/platform/UnblockJamAchievementsManager.kt
- com/vayunmathur/games/unblockjam/platform/AppBackupAgent.kt
### ui/ — theme only (board renders via platform gestures + game UI)
- com/vayunmathur/games/unblockjam/ui/Theme.kt
- com/vayunmathur/games/unblockjam/ui/Color.kt
- com/vayunmathur/games/unblockjam/ui/Type.kt

## Verify (this module only)
```
./gradlew :games:unblockjam:compileDevKotlin
./gradlew :games:unblockjam:lint
./gradlew :games:unblockjam:checkMetadata
```
