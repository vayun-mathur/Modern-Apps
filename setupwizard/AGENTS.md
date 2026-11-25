# AGENTS.md — setupwizard/ (':setupwizard')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `setupwizard/` + allowed shared modules. Do not root-scan.

_Install: ./install setupwizard (dev by default)._
- Gradle: :setupwizard / dir: setupwizard/
- Package roots present: ui, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: (none declared)
- Metadata: metadata_data/setupwizard.md (present)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** First-run OS setup — language, Wi-Fi, time, location, lock, restore, bootloader warning; hands steps to owner apps, disables itself when done. Shorter secondary-user flow.
**Key features:** `platform/SetupFlow` step machine + `SetupViewModel` → welcome/gestures/location/migration/OEM-unlock/handoff/finish screens; `SystemSetup` + `SetupIntents` delegate to owner apps; `DebugFlags` for testing.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `platform/SetupFlow.kt` → `ui/WelcomeScreen.kt`.
**Gotchas:** Runs once and disables itself — testing needs the re-enable path (`DebugFlags`); bootloader-warning copy is security-sensitive, don't soften it.

## Key files
### Root — entry + navigation
- com/vayunmathur/setupwizard/MainActivity.kt
- com/vayunmathur/setupwizard/Navigation.kt
- com/vayunmathur/setupwizard/Route.kt
### platform/ — flow machine + delegation
- com/vayunmathur/setupwizard/platform/SetupFlow.kt
- com/vayunmathur/setupwizard/platform/SetupViewModel.kt
- com/vayunmathur/setupwizard/platform/SystemSetup.kt
- com/vayunmathur/setupwizard/platform/SetupIntents.kt
- com/vayunmathur/setupwizard/platform/DebugFlags.kt
### ui/ — setup steps
- com/vayunmathur/setupwizard/ui/WelcomeScreen.kt
- com/vayunmathur/setupwizard/ui/GesturesScreen.kt
- com/vayunmathur/setupwizard/ui/LocationScreen.kt
- com/vayunmathur/setupwizard/ui/MigrationScreen.kt
- com/vayunmathur/setupwizard/ui/OemUnlockScreen.kt
- com/vayunmathur/setupwizard/ui/HandoffScreen.kt
- com/vayunmathur/setupwizard/ui/FinishScreen.kt
- com/vayunmathur/setupwizard/ui/components/SetupCheckboxRow.kt
- com/vayunmathur/setupwizard/ui/dialogs/LanguagePickerDialog.kt

## Verify (this module only)
```
./gradlew :setupwizard:compileDevKotlin
./gradlew :setupwizard:lint
./gradlew :setupwizard:checkMetadata
```
