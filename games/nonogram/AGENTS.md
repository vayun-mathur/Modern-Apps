# AGENTS.md — games/nonogram/ (':games:nonogram')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/nonogram/` + allowed shared modules. Do not root-scan.

_Install: ./install games/nonogram (dev by default)._
- Gradle: :games:nonogram / dir: games/nonogram/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games, :library:work
- Metadata: metadata_data/games-nonogram.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Picture-logic puzzles — fill from number clues to reveal the picture; gated levels + shared daily board; every puzzle solvable by reasoning alone. 100% offline.
**Game shape:** `ui/` board + settings → `platform/NonogramViewModel` → `domain/` generator + solver → `data/` store; dailies via `:library:work`; achievements → `:sdk:games` → hub.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/NonogramGameScreen.kt` → `platform/NonogramViewModel.kt` → `domain/NonogramGenerator.kt`.
**Gotchas:** Solvability-by-reasoning is the guarantee — generator changes must pass the solver check.

## Key files
### Root — entry + navigation
- com/vayunmathur/games/nonogram/MainActivity.kt
- com/vayunmathur/games/nonogram/Navigation.kt
- com/vayunmathur/games/nonogram/Route.kt
### data/ + domain/ — puzzles + generation
- com/vayunmathur/games/nonogram/data/NonogramModels.kt
- com/vayunmathur/games/nonogram/data/NonogramPuzzle.kt
- com/vayunmathur/games/nonogram/data/NonogramDataStore.kt
- com/vayunmathur/games/nonogram/domain/NonogramGenerator.kt
- com/vayunmathur/games/nonogram/domain/NonogramSolver.kt
### platform/ — ViewModel + achievements
- com/vayunmathur/games/nonogram/platform/NonogramViewModel.kt
- com/vayunmathur/games/nonogram/platform/NonogramUiContract.kt
- com/vayunmathur/games/nonogram/platform/NonogramAchievementsManager.kt
- com/vayunmathur/games/nonogram/platform/AppBackupAgent.kt
### ui/ — board + settings
- com/vayunmathur/games/nonogram/ui/NonogramGameScreen.kt
- com/vayunmathur/games/nonogram/ui/NonogramGamePage.kt
- com/vayunmathur/games/nonogram/ui/SettingsPage.kt
- com/vayunmathur/games/nonogram/ui/components/NonogramBoard.kt

## Verify (this module only)
```
./gradlew :games:nonogram:compileDevKotlin
./gradlew :games:nonogram:lint
./gradlew :games:nonogram:checkMetadata
```
