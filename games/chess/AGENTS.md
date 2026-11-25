# AGENTS.md — games/chess/ (':games:chess')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/chess/` + allowed shared modules. Do not root-scan.

_Install: ./install games/chess (dev by default)._
- Gradle: :games:chess / dir: games/chess/
- Package roots present: data
- Entry files: MainActivity.kt, Route.kt (screens live at module root, not ui/)
- Deps: :sdk:games, :library:ml
- Metadata: metadata_data/games-chess.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Offline chess — local 2-player, human-like Maia AI (4 levels, `:library:ml`), ~50k Lichess puzzles by rating, staged Learn mode. 20 languages.
**Game shape:** Root-level screens (`ChessGameScreens`, `ChessPuzzleScreens`, `ChessLearnScreens`) → `util/*ViewModel` → `data/` models/lessons/puzzles; achievements → `:sdk:games` → hub.
**Entry points:** `Route` → `MainActivity.kt` → `ChessGameScreens.kt` → `util/ChessViewModel.kt` → `data/ChessModel.kt` → `util/MaiaEngine.kt`.
**Gotchas:**
- `util/` is NOT a closed package root — don't add more files there
- Screens are at module root (not `ui/`) — follow this layout here, don't "fix" it

## Key files
### Root — entry + screens (flat layout, no ui/)
- com/vayunmathur/games/chess/MainActivity.kt
- com/vayunmathur/games/chess/Route.kt
- com/vayunmathur/games/chess/ChessGameScreens.kt
- com/vayunmathur/games/chess/ChessLearnScreens.kt
- com/vayunmathur/games/chess/ChessPuzzleScreens.kt
### data/ — models, lessons, puzzles
- com/vayunmathur/games/chess/data/ChessModel.kt
- com/vayunmathur/games/chess/data/Learn.kt
- com/vayunmathur/games/chess/data/Puzzle.kt
### util/ — ViewModels + Maia AI
- com/vayunmathur/games/chess/util/ChessViewModel.kt
- com/vayunmathur/games/chess/util/LearnViewModel.kt
- com/vayunmathur/games/chess/util/PuzzleViewModel.kt
- com/vayunmathur/games/chess/util/ChessUiContract.kt
- com/vayunmathur/games/chess/util/MaiaEngine.kt — bundled human-like AI
- com/vayunmathur/games/chess/util/ChessApi.kt
- com/vayunmathur/games/chess/util/ChessAchievementsManager.kt
- com/vayunmathur/games/chess/util/AppBackupAgent.kt

## Verify (this module only)
```
./gradlew :games:chess:compileDevKotlin
./gradlew :games:chess:lint
./gradlew :games:chess:checkMetadata
```
