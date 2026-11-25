# AGENTS.md — library/ (':library')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/` + allowed shared modules. Do not root-scan.

- Gradle: :library / dir: library/
- Package roots present: intents
- Entry files: util/Navigation.kt + util/AssistantIntent.kt (no MainActivity/Route — base library)
- Deps: :sdk:games
- Metadata: metadata_data/library.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Base library every module depends on — navigation keys, cross-app `AssistantIntent` + shared IPC DTOs (`intents/<app>`), `RoomRepository` base, messenger, backup/storage helpers, game-hub reporting.
**How to use:** Routes implement `util/NavKey`; screens navigate via `util/Navigation.kt` (+ bottom-bar/motion/results helpers). Cross-app calls go through `AssistantIntent<In,Out>` with DTOs in `library.intents.<app>`. Room entities subclass `util/RoomViewModel`/`DatabaseHelper` patterns; backups via `BackupAgent`/`BackupHelper`/`BackupFormat`. Games report via `GameHubReporter`/`DailyStreakReporter`.
**Entry points:** `util/Navigation.kt` → `util/NavKey` (same file) → `util/AssistantIntent.kt` → `intents/notes/` (copy-pattern) → `util/AppMessages.kt`.
**Gotchas:** `util/` here is grandfathered (this IS the util library) — the no-`util/` rule applies to apps, not this module. DTO changes break every consumer — keep `intents/` backward-compatible.

## Key files
### intents/ — shared IPC DTOs (one package per app)
- com/vayunmathur/library/intents/notes/NoteData.kt
- com/vayunmathur/library/intents/notes/InsertNoteData.kt
- com/vayunmathur/library/intents/calendar/EventData.kt
- com/vayunmathur/library/intents/clock/ClockData.kt
- com/vayunmathur/library/intents/contacts/ContactData.kt
- com/vayunmathur/library/intents/email/EmailData.kt
- com/vayunmathur/library/intents/findfamily/FamilyMemberData.kt
- com/vayunmathur/library/intents/fooddelivery/OrderLookupData.kt
- com/vayunmathur/library/intents/maps/DirectionsData.kt
- com/vayunmathur/library/intents/music/MusicData.kt
- com/vayunmathur/library/intents/taxi/RideEstimateData.kt
- com/vayunmathur/library/intents/weather/WeatherData.kt
### util/ — navigation, intents, messenger, storage, games glue
- com/vayunmathur/library/util/Navigation.kt — `NavKey` + `MainNavigation` + `rememberNavBackStack`
- com/vayunmathur/library/util/NavigationBottomBar.kt
- com/vayunmathur/library/util/NavigationMotion.kt
- com/vayunmathur/library/util/NavigationResults.kt
- com/vayunmathur/library/util/AssistantIntent.kt
- com/vayunmathur/library/util/AppMessages.kt — `AppMessages.show()` (never `Toast`)
- com/vayunmathur/library/util/Notifications.kt
- com/vayunmathur/library/util/RoomViewModel.kt
- com/vayunmathur/library/util/DatabaseHelper.kt
- com/vayunmathur/library/util/BackupAgent.kt
- com/vayunmathur/library/util/BackupHelper.kt
- com/vayunmathur/library/util/BackupFormat.kt
- com/vayunmathur/library/util/DataStoreUtils.kt
- com/vayunmathur/library/util/AppPreferencesEntry.kt
- com/vayunmathur/library/util/GameHubReporter.kt
- com/vayunmathur/library/util/GameHubCompose.kt
- com/vayunmathur/library/util/DailyStreakReporter.kt
- com/vayunmathur/library/util/DailyChallengeStore.kt
- com/vayunmathur/library/util/Achievement.kt
- com/vayunmathur/library/util/AchievementsManager.kt
- com/vayunmathur/library/util/LevelStatsRepository.kt
- com/vayunmathur/library/util/IntentHelper.kt
- com/vayunmathur/library/util/FileDropModifier.kt — `onFileDrop` (see notes/)
- com/vayunmathur/library/util/SecureResultReceiver.kt
- com/vayunmathur/library/util/SharedEditableText.kt
- com/vayunmathur/library/util/Hkdf.kt
- com/vayunmathur/library/util/Markdown.kt
- com/vayunmathur/library/util/Format.kt
- com/vayunmathur/library/util/LocalizedDateNames.kt
- com/vayunmathur/library/util/Util.kt
### sensor/ — shared sensors
- com/vayunmathur/library/sensor/OrientationManager.kt

## Verify (this module only)
```
./gradlew :library:compileDevKotlin
./gradlew :library:lint
```
