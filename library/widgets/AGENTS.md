# AGENTS.md — library/widgets/ (':library:widgets')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/widgets/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:widgets / dir: library/widgets/
- Package roots present: (Glance widget bases — one package)
- Entry files: MaWidget.kt + MaWidgetReceiver.kt (no MainActivity/Route — library)
- Deps: :library (permission-free; only the Log facade is imported, #884)
- Metadata: metadata_data/library-widgets.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Shared Glance widget bases — `MaWidget` + `MaWidgetReceiver` replace per-app boilerplate the same way `MaActivity` replaced per-app shells. App widgets (calculator, calendar, clock, email, weather, screentime) extend these.
**How to use:** Widget extends `MaWidget`, receiver extends `MaWidgetReceiver`; theme via `WidgetTheme`; shapes via `MaterialShapeBitmap`.
**Entry points:** `MaWidget.kt` → `MaWidgetReceiver.kt` → `WidgetTheme.kt`.
**Gotchas:** Widgets must render from cache alone — never network on the widget path (see weather's card for the rule).

## Key files
- com/vayunmathur/library/widgets/MaWidget.kt — shared widget base
- com/vayunmathur/library/widgets/MaWidgetReceiver.kt — shared receiver base
- com/vayunmathur/library/widgets/WidgetTheme.kt
- com/vayunmathur/library/widgets/Widgets.kt
- com/vayunmathur/library/widgets/MaterialShapeBitmap.kt

## Verify (this module only)
```
./gradlew :library:widgets:compileDevKotlin
./gradlew :library:widgets:lint
```
