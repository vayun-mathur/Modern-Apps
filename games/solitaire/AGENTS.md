# AGENTS.md — games/solitaire/ (':games:solitaire')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/solitaire/` + allowed shared modules. Do not root-scan.

_Install: ./install games/solitaire (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :games:solitaire / dir: games/solitaire/
- Package roots present: ui, data, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games
- Metadata: metadata_data/games-solitaire.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Multi-variant solitaire — Klondike, FreeCell, Spider (+ Pyramid ops present), on-device deals, undo, win overlay. 100% offline.
**Game shape:** Per-variant boards (`KlondikeBoard`, `FreeCellBoard`, `SpiderBoard`, `PyramidBoard`) over shared card/drag components → `platform/SolitaireViewModel` + per-variant `*Ops` → `data/` game state + stats; achievements → `:sdk:games` → hub.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/GameBoardScreen.kt` → `platform/SolitaireViewModel.kt` → `data/GameState.kt`.
**Gotchas:** Variant rules live in `platform/Solitaire*Ops` — shared board components must stay variant-agnostic.

## Key files
### Root — entry + navigation
- com/vayunmathur/games/solitaire/MainActivity.kt
- com/vayunmathur/games/solitaire/Navigation.kt
- com/vayunmathur/games/solitaire/Route.kt
### data/ — state + stats + settings
- com/vayunmathur/games/solitaire/data/GameState.kt
- com/vayunmathur/games/solitaire/data/CardModels.kt
- com/vayunmathur/games/solitaire/data/CardColorScheme.kt
- com/vayunmathur/games/solitaire/data/SolitaireStatsRepository.kt
- com/vayunmathur/games/solitaire/data/SolitaireSettingsRepository.kt
### platform/ — ViewModel + per-variant rules
- com/vayunmathur/games/solitaire/platform/SolitaireViewModel.kt
- com/vayunmathur/games/solitaire/platform/SolitaireUiContract.kt
- com/vayunmathur/games/solitaire/platform/SolitaireKlondikeOps.kt
- com/vayunmathur/games/solitaire/platform/SolitaireFreeCellOps.kt
- com/vayunmathur/games/solitaire/platform/SolitaireSpiderOps.kt
- com/vayunmathur/games/solitaire/platform/SolitairePyramidOps.kt
- com/vayunmathur/games/solitaire/platform/SolitaireDragOps.kt
- com/vayunmathur/games/solitaire/platform/SolitaireFlight.kt — in-flight moves (visual only; game state updates first)
- com/vayunmathur/games/solitaire/platform/SolitaireAchievementsManager.kt
- com/vayunmathur/games/solitaire/platform/AppBackupAgent.kt
### ui/ — boards + shared card/drag components
- com/vayunmathur/games/solitaire/ui/GameBoardScreen.kt
- com/vayunmathur/games/solitaire/ui/GameScreen.kt
- com/vayunmathur/games/solitaire/ui/HomeScreen.kt
- com/vayunmathur/games/solitaire/ui/KlondikeBoard.kt
- com/vayunmathur/games/solitaire/ui/FreeCellBoard.kt
- com/vayunmathur/games/solitaire/ui/SpiderBoard.kt
- com/vayunmathur/games/solitaire/ui/PyramidBoard.kt
- com/vayunmathur/games/solitaire/ui/TableauColumn.kt
- com/vayunmathur/games/solitaire/ui/FreeCellTableauColumn.kt
- com/vayunmathur/games/solitaire/ui/FoundationSlot.kt
- com/vayunmathur/games/solitaire/ui/FreeCellSlot.kt
- com/vayunmathur/games/solitaire/ui/CardView.kt
- com/vayunmathur/games/solitaire/ui/DraggableCard.kt — pick-up/tap source; hidden while it is carried or in flight
- com/vayunmathur/games/solitaire/ui/DropTarget.kt
- com/vayunmathur/games/solitaire/ui/CardMotionLayer.kt — top-level layer drawing the drag + every flight (outside the scroll clip)
- com/vayunmathur/games/solitaire/ui/PileLayout.kt — card slot offsets shared by piles (draw) and flights (land)
- com/vayunmathur/games/solitaire/ui/GameActionBar.kt
- com/vayunmathur/games/solitaire/ui/GameModeDisplayName.kt
- com/vayunmathur/games/solitaire/ui/WinOverlay.kt
- com/vayunmathur/games/solitaire/ui/SettingsScreen.kt
- com/vayunmathur/games/solitaire/ui/dialogs/GameConfigDialog.kt

## Verify (this module only)
```
./gradlew :games:solitaire:compileDevKotlin
./gradlew :games:solitaire:lint
./gradlew :games:solitaire:checkMetadata
```
