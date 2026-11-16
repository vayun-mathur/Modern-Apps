# AGENTS.md — parentalcontrols/ (':parentalcontrols')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `parentalcontrols/` + allowed shared modules. Do not root-scan.

_Install: ./install parentalcontrols (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :parentalcontrols / dir: parentalcontrols/
- Package roots present: ui, data, domain, platform, service, notifications, auth
- Entry files: MainActivity.kt + Route.kt + Navigation.kt (single host) + ui/SetupActivity.kt and ui/PinActivities.kt (PinGateActivity, kept for lock-screen activity-result) + ui/LockActivities.kt (kept OS lock surfaces)
- Deps: :library:room, :library:aosp-stubs
- Metadata: metadata_data/parentalcontrols.md (present)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** On-device parental controls over Android's supervision APIs — screen-time/bedtime/app-limits, web filters, install restrictions, all PIN-gated (`auth/ParentPin`). No account, nothing uploaded.
**Key features:** `platform/SupervisionMaster` + `SupervisionPolicies`/`Apis`/`Caller` enforce `data/` rules + `domain/TimeWindow`; `platform/Enforcer` + `AppLimits` + `BedtimeScheduler` apply limits; `service/` supervision messengers bridge the system.
**Entry points:** `MainActivity.kt` → `Navigation.kt` (`Route.Dashboard/DailyLimit/AppLimits/Bedtime/Downtime/SchoolTime/WebFilters/PinSetup/PinGate`) → `platform/SupervisionViewModel.kt`; `ui/SetupActivity.kt` hands off to the host's PinSetup route then the platform setup flow → `platform/SupervisionMaster.kt` → `data/Rules.kt`.
**Gotchas:**
- `receiver/` is NOT a closed package root — pending migration; don't add more files there
- Enforcement is system-mediated (supervision APIs) — sideloaded builds can't enforce; PIN loss locks parents out by design, no backdoor
- Screenshot tests are off (`screenshotTest: no`) — verify lock/PIN flows on device

## Key files
### auth/ + data/ + domain/ — PIN, rules, windows
- com/vayunmathur/parentalcontrols/auth/ParentPin.kt
- com/vayunmathur/parentalcontrols/data/ParentalControlsDatabase.kt
- com/vayunmathur/parentalcontrols/data/Rules.kt
- com/vayunmathur/parentalcontrols/data/SupervisionRules.kt
- com/vayunmathur/parentalcontrols/data/Migrations.kt
- com/vayunmathur/parentalcontrols/domain/TimeWindow.kt
### platform/ — supervision + enforcement
- com/vayunmathur/parentalcontrols/platform/SupervisionMaster.kt
- com/vayunmathur/parentalcontrols/platform/SupervisionPolicies.kt
- com/vayunmathur/parentalcontrols/platform/SupervisionApis.kt
- com/vayunmathur/parentalcontrols/platform/SupervisionCaller.kt
- com/vayunmathur/parentalcontrols/platform/SupervisionViewModel.kt
- com/vayunmathur/parentalcontrols/platform/Enforcer.kt
- com/vayunmathur/parentalcontrols/platform/AppLimits.kt
- com/vayunmathur/parentalcontrols/platform/BedtimeScheduler.kt
- com/vayunmathur/parentalcontrols/platform/UsageAccess.kt
- com/vayunmathur/parentalcontrols/platform/WebContentFilters.kt
### receiver/ + service/ + notifications/ — system bridges
- com/vayunmathur/parentalcontrols/receiver/Receivers.kt
- com/vayunmathur/parentalcontrols/service/ParentalControlsAppService.kt
- com/vayunmathur/parentalcontrols/service/SupervisionMessengerService.kt
- com/vayunmathur/parentalcontrols/notifications/LockNotifier.kt
### ui/ — PIN-gated activities (the entries)
- com/vayunmathur/parentalcontrols/MainActivity.kt
- com/vayunmathur/parentalcontrols/Route.kt
- com/vayunmathur/parentalcontrols/Navigation.kt
- com/vayunmathur/parentalcontrols/ui/DashboardScreen.kt
- com/vayunmathur/parentalcontrols/ui/SetupActivity.kt
- com/vayunmathur/parentalcontrols/ui/PinSetupScreen.kt
- com/vayunmathur/parentalcontrols/ui/PinGateScreen.kt
- com/vayunmathur/parentalcontrols/ui/PinActivities.kt
- com/vayunmathur/parentalcontrols/ui/LockScreen.kt
- com/vayunmathur/parentalcontrols/ui/LockActivities.kt
- com/vayunmathur/parentalcontrols/ui/AppLimitsScreen.kt
- com/vayunmathur/parentalcontrols/ui/DailyLimitScreen.kt
- com/vayunmathur/parentalcontrols/ui/BedtimeScreen.kt
- com/vayunmathur/parentalcontrols/ui/WindowScheduleScreen.kt
- com/vayunmathur/parentalcontrols/ui/WebContentFiltersScreen.kt

## Verify (this module only)
```
./gradlew :parentalcontrols:compileDevKotlin
./gradlew :parentalcontrols:lint
./gradlew :parentalcontrols:checkMetadata
```
