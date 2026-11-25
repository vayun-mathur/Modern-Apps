# AGENTS.md — games/wordmaker/ (':games:wordmaker')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/wordmaker/` + allowed shared modules. Do not root-scan.

_Install: ./install games/wordmaker (dev by default)._
- Gradle: :games:wordmaker / dir: games/wordmaker/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games, :library:work
- Metadata: metadata_data/games-wordmaker.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Word puzzle — letter-wheel + crossword modes over a bundled dictionary with definitions; competitive lobby + level generation. 100% offline.
**Game shape:** `ui/` wheel/crossword/lobby screens → `platform/WordMakerViewModel` → `domain/` dictionary + generators → `data/` level store + competitive mode; achievements → `:sdk:games` → hub.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/WordGameScreen.kt` → `platform/WordMakerViewModel.kt` → `domain/Dictionary.kt`.
**Gotchas:** Dictionary is bundled and locale-sensitive — word-list changes need per-locale validation.

## Key files
### Root — entry + navigation
- com/vayunmathur/games/wordmaker/MainActivity.kt
- com/vayunmathur/games/wordmaker/Navigation.kt
- com/vayunmathur/games/wordmaker/Route.kt
### data/ + domain/ — levels, dictionary, modes
- com/vayunmathur/games/wordmaker/data/LevelDataStore.kt
- com/vayunmathur/games/wordmaker/data/CrosswordData.kt
- com/vayunmathur/games/wordmaker/data/CompetitiveMode.kt
- com/vayunmathur/games/wordmaker/data/WheelSpacing.kt
- com/vayunmathur/games/wordmaker/domain/Dictionary.kt
- com/vayunmathur/games/wordmaker/domain/CompetitiveLevelGenerator.kt
### platform/ — ViewModel + achievements
- com/vayunmathur/games/wordmaker/platform/WordMakerViewModel.kt
- com/vayunmathur/games/wordmaker/platform/WordMakerUiContract.kt
- com/vayunmathur/games/wordmaker/platform/WordMakerAchievementsManager.kt
- com/vayunmathur/games/wordmaker/platform/AppBackupAgent.kt
### ui/ — wheel, crossword, lobby
- com/vayunmathur/games/wordmaker/ui/WordGameScreen.kt
- com/vayunmathur/games/wordmaker/ui/WordGamePage.kt
- com/vayunmathur/games/wordmaker/ui/WordGameWheel.kt
- com/vayunmathur/games/wordmaker/ui/WordGameSubmission.kt
- com/vayunmathur/games/wordmaker/ui/WordGameOverlays.kt
- com/vayunmathur/games/wordmaker/ui/WordMakerGameLoader.kt
- com/vayunmathur/games/wordmaker/ui/CompetitiveLobbyScreen.kt
- com/vayunmathur/games/wordmaker/ui/ChooserLetter.kt
- com/vayunmathur/games/wordmaker/ui/SettingsPage.kt
- com/vayunmathur/games/wordmaker/ui/dialogs/WordDialogs.kt
- com/vayunmathur/games/wordmaker/ui/components/CrosswordBoard.kt
- com/vayunmathur/games/wordmaker/ui/components/LetterChooser.kt
- com/vayunmathur/games/wordmaker/ui/components/WordComponents.kt
- com/vayunmathur/games/wordmaker/ui/components/WordMakerTopBar.kt

## Verify (this module only)
```
./gradlew :games:wordmaker:compileDevKotlin
./gradlew :games:wordmaker:lint
./gradlew :games:wordmaker:checkMetadata
```
