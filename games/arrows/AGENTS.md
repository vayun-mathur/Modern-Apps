# AGENTS.md — games/arrows/ (':games:arrows')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/arrows/` + allowed shared modules. Do not root-scan.

_Install: ./install games/arrows (dev by default)._
- Gradle: :games:arrows / dir: games/arrows/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games, :library:work
- Metadata: metadata_data/games-arrows.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Order-clearing arrow puzzle — every arrow must fly off unblocked; redirectors from level 11; daily board + 3-hearts escape overlay. 100% offline.
**Game shape (shared across MA games):** `ui/` board + screens → `platform/*ViewModel` (+ `UiContract`) → `domain/` pure rules/generator (+ solver where present) → `data/` progress store; achievements via `platform/*AchievementsManager` → `:sdk:games` → hub; daily boards via `:library:work`.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `platform/ArrowsViewModel.kt` → `domain/ArrowsRules.kt`.
**Gotchas:** Every generated board must be clearable — generator changes need solvability checks.

## Key files
### Root — entry + navigation
- com/vayunmathur/games/arrows/MainActivity.kt
- com/vayunmathur/games/arrows/Navigation.kt
- com/vayunmathur/games/arrows/Route.kt
### data/ + domain/ — progress + pure rules
- com/vayunmathur/games/arrows/data/ArrowModels.kt
- com/vayunmathur/games/arrows/data/ArrowsDataStore.kt
- com/vayunmathur/games/arrows/domain/ArrowsGenerator.kt
- com/vayunmathur/games/arrows/domain/ArrowsRules.kt
### platform/ — ViewModel + achievements
- com/vayunmathur/games/arrows/platform/ArrowsViewModel.kt
- com/vayunmathur/games/arrows/platform/ArrowsUiContract.kt
- com/vayunmathur/games/arrows/platform/ArrowsAchievementsManager.kt
- com/vayunmathur/games/arrows/platform/AppBackupAgent.kt
### ui/ — board + screens
- com/vayunmathur/games/arrows/ui/ArrowsGameScreen.kt
- com/vayunmathur/games/arrows/ui/ArrowsGamePage.kt
- com/vayunmathur/games/arrows/ui/SettingsPage.kt
- com/vayunmathur/games/arrows/ui/components/ArrowsBoard.kt
- com/vayunmathur/games/arrows/ui/components/ArrowsBoardDrawing.kt

## Verify (this module only)
```
./gradlew :games:arrows:compileDevKotlin
./gradlew :games:arrows:lint
./gradlew :games:arrows:checkMetadata
```
