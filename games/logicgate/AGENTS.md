# AGENTS.md — games/logicgate/ (':games:logicgate')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/logicgate/` + allowed shared modules. Do not root-scan.

_Install: ./install games/logicgate (dev by default)._
<!-- last-reviewed: 2026-11-01 -->
- Gradle: :games:logicgate / dir: games/logicgate/
- Package roots present: domain, data, platform, ui
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games
- Metadata: metadata_data/games-logicgate.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** NAND-to-computer circuit puzzle. 28 levels in 5 chapters (Basics, Routing & buses, Arithmetic, Memory, CPU). Every level is checked by a real cycle-accurate simulator, finishing with a CPU that runs three programs out of RAM. 100% offline.
**Game shape:** `domain/` (pure Kotlin: circuit model, chip catalog, simulator, verifier, levels) → `platform/LogicViewModel` (undo/redo, debounced re-verify on `Dispatchers.Default`) → `ui/` (one-canvas editor, chip tray, test panel, level map). `data/` persists drafts (DataStore key `logicgate2_circuits`) and best costs (`LevelStatsRepository` prefs `logicgate2_stats`).
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/GamePage.kt` → `ui/GameScreen.kt` → `platform/LogicViewModel.kt` → `domain/sim/Verifier.kt`.
**Model:**
- Signals are `Int` bit masks on typed 1/4/8-bit pins. SPLIT/JOIN chips convert widths, and wires must match widths.
- Level I/O are real `LevelInput`/`LevelOutput` nodes. Unconnected inputs read 0.
- One tick of an implicit global clock = settle, then latch. Feedback loops iterate Gauss-Seidel (cap 64 passes → oscillation). State-only chips (DFF/REG/PC) break loops.
- Solving a level unlocks its chip as a trusted native behaviour (`domain/model/Chips.kt`). Level pins, tests and par for reward levels derive from that chip (`ChipLevel.kt`), so they cannot drift.
- ISA (`domain/model/Isa.kt`): `op[4]|operand[4]`, LDA STA ADD SUB LDI JMP JZ HLT, one instruction per tick via dual-port RAM16.
**Gotchas:**
- Pars are asserted equal to the reference solution costs in `src/test/.../domain/LevelSolutionsTest.kt`. Change a chip's `nandCost` and its reference together.
- `ui/canvas/` is world-unit geometry (≈dp at 1×). `CameraState.scale` already includes density, so canvas text uses `sizeWorld.toSp()`.
- Nothing edits on finger-down. Chips are created by pulling them up out of the tray (`ChipTray`: an upward drag claims the gesture, a sideways one scrolls) or by tapping them. Gates are deleted by dropping them back on the bottom area (`ui/canvas/TrashZone.kt`) or from the selection bar.
- Test rows carry plain-language titles (`domain/levels/RowTitles.kt`, e.g. `1 OR 1 = 1`), which are shown instead of row numbers.

## Key files
### domain/ — pure, unit-tested
- domain/model/Circuit.kt — nodes, pins, wires, immutable edits, `sanitized`
- domain/model/ChipSpec.kt, domain/model/Chips.kt, domain/model/Isa.kt
- domain/sim/Simulator.kt, domain/sim/Scheduler.kt — tick semantics, SCC ordering
- domain/sim/Verifier.kt, domain/sim/TestSpec.kt, domain/sim/Scoring.kt
- domain/levels/LevelRegistry.kt + {Basics,Routing,Arithmetic,Memory,Cpu}Levels.kt
### data/ + platform/
- data/CircuitStore.kt, data/ProgressStore.kt
- platform/LogicUiContract.kt (state + `LogicActions.Noop`), platform/LogicViewModel.kt
- platform/LogicAchievements.kt (`assets/achievements.json`, ids `lg2_*`), platform/AppBackupAgent.kt
### ui/
- ui/GameScreen.kt, ui/CircuitCanvas.kt, ui/ChipTray.kt, ui/TestPanel.kt, ui/SelectionBar.kt
- ui/BriefSheet.kt, ui/BusValueDialog.kt, ui/LevelCompleteOverlay.kt, ui/ProgressionScreen.kt, ui/StarRating.kt
- ui/canvas/ — Camera, CameraState, GateGeometry (layout), HitTest, CanvasGestures, CanvasController, GatePainter, GateShapes

## Verify (this module only)
```
./gradlew :games:logicgate:testDevUnitTest
./gradlew :games:logicgate:compileDevKotlin
./check games/logicgate
./gradlew :games:logicgate:checkMetadata
```
