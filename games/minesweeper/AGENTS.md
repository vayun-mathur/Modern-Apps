# AGENTS.md — games/minesweeper/ (':games:minesweeper')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/minesweeper/` + allowed shared modules. Do not root-scan.

_Install: ./install games/minesweeper (dev by default)._
- Gradle: :games:minesweeper / dir: games/minesweeper/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games
- Metadata: metadata_data/games-minesweeper.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Classic mine-clearing — generated fields, safe-opening first tap, dig/flag modes, chord-on-satisfied-number. 100% offline.
**Game shape:** `ui/` board + home + config dialog → `platform/MinesweeperViewModel` → `domain/` generator + rules → `data/` models + stats repo; achievements → `:sdk:games` → hub.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/GameBoardScreen.kt` → `platform/MinesweeperViewModel.kt` → `domain/FieldGenerator.kt`.
**Gotchas:** First tap must always safe-open an area — generator + open logic are coupled, test them together.

## Key files
### Root — entry + navigation
- com/vayunmathur/games/minesweeper/MainActivity.kt
- com/vayunmathur/games/minesweeper/Navigation.kt
- com/vayunmathur/games/minesweeper/Route.kt
### data/ + domain/ — models, stats, generation
- com/vayunmathur/games/minesweeper/data/MinesweeperModels.kt
- com/vayunmathur/games/minesweeper/data/MinesweeperStatsRepository.kt
- com/vayunmathur/games/minesweeper/domain/FieldGenerator.kt
- com/vayunmathur/games/minesweeper/domain/MinesweeperRules.kt
### platform/ — ViewModel + achievements
- com/vayunmathur/games/minesweeper/platform/MinesweeperViewModel.kt
- com/vayunmathur/games/minesweeper/platform/MinesweeperUiContract.kt
- com/vayunmathur/games/minesweeper/platform/MinesweeperAchievementsManager.kt
- com/vayunmathur/games/minesweeper/platform/AppBackupAgent.kt
### ui/ — board + home + config
- com/vayunmathur/games/minesweeper/ui/GameBoardScreen.kt
- com/vayunmathur/games/minesweeper/ui/GameScreen.kt
- com/vayunmathur/games/minesweeper/ui/HomeScreen.kt
- com/vayunmathur/games/minesweeper/ui/DisplayNames.kt
- com/vayunmathur/games/minesweeper/ui/components/MineFieldGrid.kt
- com/vayunmathur/games/minesweeper/ui/dialogs/GameConfigDialog.kt

## Verify (this module only)
```
./gradlew :games:minesweeper:compileDevKotlin
./gradlew :games:minesweeper:lint
./gradlew :games:minesweeper:checkMetadata
```
