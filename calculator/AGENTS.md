# AGENTS.md — calculator/ (':calculator')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `calculator/` + allowed shared modules. Do not root-scan.

_Install: ./install calculator (dev by default)._
- Gradle: :calculator / dir: calculator/
- Package roots present: ui, widget
- Entry files: MainActivity.kt (no Route.kt — single-screen app with pages, not nav destinations)
- Deps: :library:network, :library:widgets
- Metadata: metadata_data/calculator.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Modern Material You calculator — scientific/basic modes, function graphing with analysis, and a multi-category unit converter with live currency rates.
**Key features:**
- Scientific + basic keypad with expression history
- Graph page: plots functions with root / intercept / min-max / intersection detection
- Unit converter across many categories incl. live currency rates (`CurrencyApi` over `:library:network`)
- Home-screen widgets for both calculator and unit converter (`:library:widgets` Glance)
**Architecture / data-flow:** Single-screen `MainActivity` hosting `ui/` pages (`CalculatorPage`, `GraphPage`, `UnitConverterPage`) over a `util/` ViewModel + pure expression/graph/units helpers; widget receivers in `widget/` share the same logic. Internet is used only for currency rates.
**Entry points + reading order:** `MainActivity.kt` → `ui/CalculatorPage.kt` → `util/CalculatorViewModel.kt` + `util/CalculatorUiContract.kt` → `util/Expression.kt` / `util/GraphAnalysis.kt` / `util/Units.kt` → `widget/CalculatorGlanceWidget.kt`.
**Gotchas:**
- `util/` is NOT a closed package root — don't add more files there
- Currency rates are the only network call — everything else must stay offline-safe

## Key files
### Root — entry (no Route.kt)
- com/vayunmathur/calculator/MainActivity.kt
### ui/ — keypad, graph, converter pages
- com/vayunmathur/calculator/ui/CalculatorPage.kt
- com/vayunmathur/calculator/ui/CalculatorKeypad.kt
- com/vayunmathur/calculator/ui/CalculatorDialogs.kt
- com/vayunmathur/calculator/ui/GraphPage.kt
- com/vayunmathur/calculator/ui/UnitConverterPage.kt
### util/ — ViewModel + pure engine
- com/vayunmathur/calculator/util/CalculatorViewModel.kt
- com/vayunmathur/calculator/util/CalculatorUiContract.kt
- com/vayunmathur/calculator/util/Expression.kt — expression parsing/eval
- com/vayunmathur/calculator/util/GraphAnalysis.kt — root/intercept/min-max/intersection
- com/vayunmathur/calculator/util/Units.kt
- com/vayunmathur/calculator/util/CurrencyApi.kt — only network call
- com/vayunmathur/calculator/util/Formatting.kt
### widget/ — Glance widgets sharing the same logic
- com/vayunmathur/calculator/widget/CalculatorGlanceWidget.kt
- com/vayunmathur/calculator/widget/CalculatorGlanceWidgetReceiver.kt
- com/vayunmathur/calculator/widget/CalculatorKeyAction.kt
- com/vayunmathur/calculator/widget/CalculatorWidgetSettingsActivity.kt
- com/vayunmathur/calculator/widget/UnitsGlanceWidget.kt
- com/vayunmathur/calculator/widget/UnitsGlanceWidgetReceiver.kt
- com/vayunmathur/calculator/widget/UnitsWidgetActions.kt

## Verify (this module only)
```
./gradlew :calculator:compileDevKotlin
./gradlew :calculator:lint
./gradlew :calculator:checkMetadata
```
