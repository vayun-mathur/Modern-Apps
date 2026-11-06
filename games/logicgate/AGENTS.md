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
**Purpose:** NAND-to-computer circuit puzzle. Two bottom-bar tabs. **Computer** has 28 levels in 5 chapters (Basics, Routing & buses, Arithmetic, Memory, CPU), each checked by a real cycle-accurate simulator, finishing with a CPU that runs three programs out of RAM. In **Sandbox**, players build their own components (custom pins, unlocked built-ins and other components) and can then use them in any level. 100% offline.
**Game shape:** `domain/` (pure Kotlin: circuit model, chip catalog, simulator, verifier, levels) → `platform/LogicViewModel` (progression, components, catalog) + `platform/EditorSession` (undo/redo, debounced re-verify on `Dispatchers.Default`) → `ui/` (one-canvas editor, grouped chip tray, test panel, level map, sandbox list). `data/` persists drafts (DataStore key `logicgate2_circuits`), components (DataStore key `logicgate2_components`) and best costs (`LevelStatsRepository` prefs `logicgate2_stats`).
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/GamePage.kt` / `ui/ComponentPage.kt` → `ui/GameScreen.kt` → `platform/EditorSession.kt` → `domain/sim/Verifier.kt`.
**Model:**
- Signals are `Int` bit masks on typed 1/4/8-bit pins. SPLIT/JOIN chips convert widths, and wires must match widths.
- Level I/O are real `LevelInput`/`LevelOutput` nodes. Unconnected inputs read 0.
- One tick of an implicit global clock = settle, then latch. Feedback loops iterate Gauss-Seidel (cap 64 passes → oscillation). State-only chips (DFF/REG/PC) break loops.
- Solving a level unlocks its chip as a trusted native behaviour (`domain/model/Chips.kt`). Level pins, tests and par for reward levels derive from that chip (`ChipLevel.kt`), so they cannot drift.
- Sandbox components (`domain/model/CustomChip.kt`, ids `custom:<uuid>`) are a `Board` (circuit + editable `LevelIo`) behind `Behavior.Composite`. `ChipCatalog` resolves built-ins + components (costs summed recursively; self-containing components resolve to null). `domain/sim/Flattener.kt` inlines composites before simulating, so feedback across component boundaries is exact. Chip pin order is the board's pin nodes sorted top-to-bottom. When an edit reorders or removes pins, `remapChipPins` rewires every other component and level draft that uses the component.
- Sandbox tray = `LevelRegistry.unlockedChips(completed)` + components that don't contain the open one + `PinChips` (pseudo-chips `pin:in|out:{1,4,8}` that add board pins). Level tray = `allowedChips(level)` + every component. Tray tabs come from `domain/levels/ChipGroups.kt` (Pins, built-ins by chapter, player group names).
- ISA (`domain/model/Isa.kt`): `op[4]|operand[4]`, LDA STA ADD SUB LDI JMP JZ HLT, one instruction per tick via dual-port RAM16.
**Gotchas:**
- Pars are asserted equal to the reference solution costs in `src/test/.../domain/LevelSolutionsTest.kt`. Change a chip's `nandCost` and its reference together.
- `ui/canvas/` is world-unit geometry (≈dp at 1×). `CameraState.scale` already includes density, so canvas text uses `sizeWorld.toSp()`.
- Nothing edits on finger-down. Chips are created by pulling them up out of the tray (`ChipTray`: an upward drag claims the gesture, a sideways one scrolls) or from `ui/ChipInfoSheet.kt`, opened by tapping a tray card (chip names and blurbs are `strings.xml` resources mapped in `domain/model/ChipInfo.kt`; UI resolves names via `ui/ChipLabels.kt`). Gates are deleted by dropping them back on the bottom area (`ui/canvas/TrashZone.kt`) or from the selection bar.
- Level titles, briefs and hints are `@StringRes` ids (`lg_level_<id>_{title,brief,hint}`); gate-named level titles alias the chip name string.
- Test rows carry plain-language titles (`domain/levels/RowTitles.kt`, e.g. `1 OR 1 = 1`), which are shown instead of row numbers.

## Key files
### domain/ — pure, unit-tested
- domain/model/Circuit.kt — nodes, pins, wires, immutable edits, `sanitized`
- domain/model/Board.kt (circuit + io, pin add/remove/rename), domain/model/CustomChip.kt (+ `PinChips`, `pinRemap`, `remapChipPins`), domain/model/ChipCatalog.kt
- domain/model/ChipSpec.kt, domain/model/Chips.kt, domain/model/ChipInfo.kt (chip name + blurb string ids), domain/model/Isa.kt
- domain/sim/Simulator.kt, domain/sim/Scheduler.kt — tick semantics, SCC ordering
- domain/sim/Verifier.kt, domain/sim/TestSpec.kt, domain/sim/Scoring.kt, domain/sim/Flattener.kt
- domain/levels/ChipGroups.kt (tray tabs)
- domain/levels/LevelRegistry.kt + {Basics,Routing,Arithmetic,Memory,Cpu}Levels.kt
### data/ + platform/
- data/CircuitStore.kt, data/ComponentStore.kt, data/ProgressStore.kt
- platform/LogicUiContract.kt (state + `LogicActions.Noop`, `EditorMode` Level/Sandbox), platform/EditorSession.kt, platform/LogicViewModel.kt
- platform/LogicAchievements.kt (`assets/achievements.json`, ids `lg2_*`), platform/AppBackupAgent.kt
### ui/
- ui/GameScreen.kt, ui/CircuitCanvas.kt, ui/ChipTray.kt, ui/TestPanel.kt, ui/SelectionBar.kt
- ui/BriefSheet.kt, ui/ChipInfoSheet.kt, ui/PinLine.kt, ui/BusValueDialog.kt, ui/LevelCompleteOverlay.kt, ui/ProgressionScreen.kt, ui/StarRating.kt
- ui/SandboxPage.kt, ui/SandboxScreen.kt, ui/ComponentPage.kt, ui/ComponentSheet.kt (name, group, pins, delete)
- ui/canvas/ — Camera, CameraState, GateGeometry (layout), HitTest, CanvasGestures, CanvasController, GatePainter, GateShapes

## Verify (this module only)
```
./gradlew :games:logicgate:testDevUnitTest
./gradlew :games:logicgate:compileDevKotlin
./check games/logicgate
./gradlew :games:logicgate:checkMetadata
```
