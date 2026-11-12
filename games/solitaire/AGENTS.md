# AGENTS.md — games/solitaire/ (':games:solitaire')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/solitaire/` + allowed shared modules. Do not root-scan.

_Install: ./install games/solitaire (dev by default)._
<!-- last-reviewed: 2026-11-12 -->
- Gradle: :games:solitaire / dir: games/solitaire/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games
- Metadata: metadata_data/games-solitaire.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Multi-variant solitaire — Klondike, FreeCell, Spider (+ Pyramid ops present), on-device deals, undo, win overlay. 100% offline.
**Game shape:** Per-variant boards (`KlondikeBoard`, `FreeCellBoard`, `SpiderBoard`, `PyramidBoard`) over shared card/drag components → `platform/SolitaireViewModel` + per-variant `*Ops` → `data/` game state + stats; achievements → `:sdk:games` → hub.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/GameBoardScreen.kt` → `platform/SolitaireViewModel.kt` → `data/GameState.kt`.
**Gotchas:** Variant rules live in `platform/Solitaire*Ops` — shared board components must stay variant-agnostic. Every new deal comes from a table of solver-proven seeds, so **any rule change in the `*Ops` files means regenerating the tables** (see "Solvable deals").

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
### domain/ — pure Kotlin: deals, solvers, seed tables (no Android)
- com/vayunmathur/games/solitaire/domain/SolitaireDeals.kt (seed → starting board; the only deal code)
- com/vayunmathur/games/solitaire/domain/SolvableDeals.kt (table keys, `solvable_deals.bin` format, pick / daily mapping)
- com/vayunmathur/games/solitaire/domain/DealProofs.kt (what "proven" means: solver + pass-limit ladder)
- com/vayunmathur/games/solitaire/domain/SolverSearch.kt (DFS / beam / portfolio search engine)
- com/vayunmathur/games/solitaire/domain/KlondikeSolver.kt, FreeCellSolver.kt, SpiderSolver.kt, PyramidSolver.kt
### platform/ — ViewModel + per-variant rules
- com/vayunmathur/games/solitaire/platform/SolitaireViewModel.kt
- com/vayunmathur/games/solitaire/platform/SolitaireUiContract.kt
- com/vayunmathur/games/solitaire/platform/SolitaireKlondikeOps.kt
- com/vayunmathur/games/solitaire/platform/SolitaireFreeCellOps.kt
- com/vayunmathur/games/solitaire/platform/SolitaireSpiderOps.kt
- com/vayunmathur/games/solitaire/platform/SolitairePyramidOps.kt
- com/vayunmathur/games/solitaire/platform/SolitaireDealSource.kt (new game = random table seed; `restartDeal()`)
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

## Solvable deals
Every new game deals a seed from `src/main/assets/solvable_deals.bin`: one sorted
table per mode × option (8 Klondike draw/pass configs, Spider 1/2/4 suits,
FreeCell, Pyramid relaxed/single-pass), keyed by the stats variant
(`KLONDIKE__DRAW_THREE_HARD`, ...). Each seed was solved to a full win offline
by `domain/DealProofs` (the mode's solver, climbing the pass-limit ladder:
a win with fewer passes is a win with more). Seeds deal through
`createShuffledDeck(seed)` / `createSpiderDeck(suits, seed)` exactly as before,
so a seed deals the same board everywhere; the state keeps it (`seed`), and
`restartDeal()` replays it.

`SolverReplayTest` / `SolvableDealTablesTest` replay solver lines from table
seeds through the real ViewModel ops. If they fail after a rules change (or a
solver change), regenerate:

```
./gradlew :games:solitaire:testDevUnitTest \
  --tests 'com.vayunmathur.games.solitaire.domain.SolvableSeedTableGenerator' \
  -PsolitaireSeedGen=$PWD/analysis/soldeal -PsolitaireSeedGenThreads=64
cp analysis/soldeal/solvable_deals.bin games/solitaire/src/main/assets/
```
Optional: `-PsolitaireSeedGenTarget=10000` (seeds per config),
`-PsolitaireSeedGenGroups=klondike-draw1,klondike-draw3,pyramid,freecell,spider1,spider2,spider4`
(subset; merge by hand), `-PsolitaireSeedGenHeap=40g` (test JVM heap).
`analysis/soldeal/solvable_deals_report.txt` has per-config solvable rates
(last run: Klondike draw-1 86% unlimited .. 19% one pass, draw-3 79% .. 2.6%,
FreeCell 99.9%, Pyramid 83% relaxed / 12.8% single pass, Spider 84/83/71% for
1/2/4 suits; proven within budget).
Output is deterministic for a given solver and budget (any thread count).
It is CPU-heavy (about 25 min on 64 cores at 10 000 per config; the build
hook turns off coverage and -ea for it, which otherwise make it ~10x slower):
on the shared build box run it in the toolchain container under
the heavy-build lock, e.g.
`MEM_CAP=60g GEXCL="" ~/release-opt2/gbuild.sh soldeal-gen ~/code/Modern-Apps <branch> ":games:solitaire:testDevUnitTest --tests com.vayunmathur.games.solitaire.domain.SolvableSeedTableGenerator -PsolitaireSeedGen=/work/out -PsolitaireSeedGenThreads=64 -PsolitaireSeedGenHeap=44g"`
(output in `~/release-opt2/runs/soldeal-gen/work/out/`).
