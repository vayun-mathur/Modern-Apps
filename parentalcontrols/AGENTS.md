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

## Self-protection (force-stop / uninstall) — audit

What stops a child from force-stopping or uninstalling this app, honestly:

- **Already provided by the supervision tier (real):** the `SYSTEM_SUPERVISION` role
  (granted via `config_systemSupervision`, not by this app), `MANAGE_SUPERVISION`-gated
  activities, Settings-only IPC (`SupervisionCaller`), the parent PIN, and the platform
  clearing/re-applying policy state around supervision enable/disable. Enforcement is
  system-mediated; the app never polices itself.
- **Implemented in-app (the hardening a normal app can actually do):** re-arm on
  `BOOT_COMPLETED` (`BootReceiver`) and on our own update (`PackageReplacedReceiver` —
  alarms, usage observers and the platform binding all die with the old process);
  block/usage state persisted across process death (`ManagedPackages`, `LimitState`).
- **Deliberately NOT implemented (fake or abusive at this tier):** a legacy
  `DeviceAdminReceiver` — after admin activation it can hinder *uninstall* only, it
  cannot prevent force-stop, and presenting it as tamper-proofing would be lying;
  an accessibility service watching Settings to intercept force-stop/uninstall taps —
  an abuse vector this app must never ship. Neither is declared in the manifest.
- **MAOS-only (platform layer, not app code):** preventing force-stop/uninstall of the
  supervision app outright, pinning `config_systemSupervision` to this package, the
  `hidden-api-whitelisted-app` entry, and any privileged placement. A normal
  (non-privileged) app cannot obtain these from inside its own APK; do not simulate
  them here.

## Verify (this module only)
```
./gradlew :parentalcontrols:compileDevKotlin
./gradlew :parentalcontrols:lint
./gradlew :parentalcontrols:checkMetadata
```
