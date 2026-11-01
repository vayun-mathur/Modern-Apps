# AGENTS.md — games/sudoku/ (':games:sudoku')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/sudoku/` + allowed shared modules. Do not root-scan.

_Install: ./install games/sudoku (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :games:sudoku / dir: games/sudoku/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games
- Metadata: metadata_data/games-sudoku.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Classic sudoku — 6×6/9×9/12×12 generated boards, exactly one solution each; pencil marks, hints, undo; illegal digits rejected. 100% offline.
**Game shape:** `ui/` board + home → `platform/SudokuViewModel` → `domain/` generator + solver → `data/` models + stats; achievements → `:sdk:games` → hub.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/GameBoardScreen.kt` → `platform/SudokuViewModel.kt` → `domain/SudokuGenerator.kt`.
**Gotchas:** Unique-solution guarantee is the product — generator changes must pass the solver's uniqueness check.

## Key files
### Root — entry + navigation
- com/vayunmathur/games/sudoku/MainActivity.kt
- com/vayunmathur/games/sudoku/Navigation.kt
- com/vayunmathur/games/sudoku/Route.kt
### data/ + domain/ — models, stats, generation
- com/vayunmathur/games/sudoku/data/SudokuModels.kt
- com/vayunmathur/games/sudoku/data/SudokuStatsRepository.kt
- com/vayunmathur/games/sudoku/domain/SudokuGenerator.kt
- com/vayunmathur/games/sudoku/domain/SudokuSolver.kt
### platform/ — ViewModel + achievements
- com/vayunmathur/games/sudoku/platform/SudokuViewModel.kt
- com/vayunmathur/games/sudoku/platform/SudokuUiContract.kt
- com/vayunmathur/games/sudoku/platform/SudokuAchievementsManager.kt
- com/vayunmathur/games/sudoku/platform/AppBackupAgent.kt
### ui/ — board + home
- com/vayunmathur/games/sudoku/ui/GameBoardScreen.kt
- com/vayunmathur/games/sudoku/ui/GameScreen.kt
- com/vayunmathur/games/sudoku/ui/HomeScreen.kt
- com/vayunmathur/games/sudoku/ui/DisplayNames.kt
- com/vayunmathur/games/sudoku/ui/components/NumberPad.kt
- com/vayunmathur/games/sudoku/ui/components/SudokuGrid.kt

## Verify (this module only)
```
./gradlew :games:sudoku:compileDevKotlin
./gradlew :games:sudoku:lint
./gradlew :games:sudoku:checkMetadata
```
