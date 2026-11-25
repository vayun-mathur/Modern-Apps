# AGENTS.md — games/pipes/ (':games:pipes')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/pipes/` + allowed shared modules. Do not root-scan.

_Install: ./install games/pipes (dev by default)._
- Gradle: :games:pipes / dir: games/pipes/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games, :library:work
- Metadata: metadata_data/games-pipes.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Numberlink-style pipe connection — connect colors to fill the board; packs + levels + shared daily board. 100% offline.
**Game shape:** `ui/` board + pack/level screens + daily → `platform/PipesViewModel` → `domain/` generator + solver → `data/` completed-levels + models; dailies via `:library:work`; achievements → `:sdk:games` → hub.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/GameBoardScreen.kt` → `platform/PipesViewModel.kt` → `domain/LevelGenerator.kt`.
**Gotchas:** Daily boards derive from the date — same date must yield the same board on every device.

## Key files
### Root — entry + navigation
- com/vayunmathur/games/pipes/MainActivity.kt
- com/vayunmathur/games/pipes/Navigation.kt
- com/vayunmathur/games/pipes/Route.kt
### data/ + domain/ — levels + generation
- com/vayunmathur/games/pipes/data/PipeModels.kt
- com/vayunmathur/games/pipes/data/CompletedLevelsRepository.kt
- com/vayunmathur/games/pipes/domain/LevelGenerator.kt
- com/vayunmathur/games/pipes/domain/NumberlinkSolver.kt
- com/vayunmathur/games/pipes/domain/DailyLevels.kt
### platform/ — ViewModel + achievements
- com/vayunmathur/games/pipes/platform/PipesViewModel.kt
- com/vayunmathur/games/pipes/platform/PipesUiContract.kt
- com/vayunmathur/games/pipes/platform/PipesAchievementsManager.kt
- com/vayunmathur/games/pipes/platform/AppBackupAgent.kt
### ui/ — board, packs, daily
- com/vayunmathur/games/pipes/ui/GameBoardScreen.kt
- com/vayunmathur/games/pipes/ui/GameBoard.kt
- com/vayunmathur/games/pipes/ui/GameScreen.kt
- com/vayunmathur/games/pipes/ui/LevelScreen.kt
- com/vayunmathur/games/pipes/ui/PackScreen.kt
- com/vayunmathur/games/pipes/ui/PackListScreen.kt
- com/vayunmathur/games/pipes/ui/DailyLevelScreen.kt
- com/vayunmathur/games/pipes/ui/SettingsScreen.kt
- com/vayunmathur/games/pipes/ui/PipeRenderer.kt
- com/vayunmathur/games/pipes/ui/PipeColors.kt
- com/vayunmathur/games/pipes/ui/Theme.kt
- com/vayunmathur/games/pipes/ui/components/LevelThumbnail.kt

## Verify (this module only)
```
./gradlew :games:pipes:compileDevKotlin
./gradlew :games:pipes:lint
./gradlew :games:pipes:checkMetadata
```
