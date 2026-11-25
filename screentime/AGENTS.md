# AGENTS.md — screentime/ (':screentime')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `screentime/` + allowed shared modules. Do not root-scan.

_Install: ./install screentime (dev by default)._
- Gradle: :screentime / dir: screentime/
- Package roots present: ui, data, domain, platform, service, widget, notifications
- Entry files: MainActivity.kt + Route.kt + Navigation.kt (single host; FrictionActivity stays a system-triggered activity)
- Deps: :library:room, :library:widgets
- Metadata: metadata_data/screentime.md (present)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** On-device screen-time dashboard — per-app usage, app timers + instant pauses, Wind Down (grayscale + DND), friction nudges, Glance widget. Sibling of `parentalcontrols/` (self-control vs supervised control). No account, nothing uploaded.
**Key features:** `platform/UsageHistory` + `domain/HourBuckets` feed dashboard/charts; `AppTimers`/`Suspender`/`Coordinator` enforce limits; `WindDownApplier` + `WindowScheduler` run bedtime; widget mirrors the dashboard.
**Entry points:** `MainActivity.kt` → `Navigation.kt` (`Route.Dashboard/AppDetails(packageName)/WindDown`) → `ui/DashboardScreen.kt` → `platform/ScreenTimeViewModel.kt` → `platform/Coordinator.kt` → `data/Rules.kt`.
**Gotchas:**
- `receiver/` is NOT a closed package root — don't add more files there
- Usage access is a special grant — behavior without it is an empty dashboard, not an error; handle gracefully
- Screenshot tests are off (`screenshotTest: no`) — verify timers/pause flows on device

## Key files
### data/ + domain/ — rules + buckets
- com/vayunmathur/screentime/data/ScreenTimeDatabase.kt
- com/vayunmathur/screentime/data/Rules.kt
- com/vayunmathur/screentime/data/Migrations.kt
- com/vayunmathur/screentime/domain/HourBuckets.kt
### platform/ — coordination + enforcement
- com/vayunmathur/screentime/platform/Coordinator.kt
- com/vayunmathur/screentime/platform/ScreenTimeViewModel.kt
- com/vayunmathur/screentime/platform/AppTimers.kt
- com/vayunmathur/screentime/platform/Suspender.kt
- com/vayunmathur/screentime/platform/UsageAccess.kt
- com/vayunmathur/screentime/platform/UsageHistory.kt
- com/vayunmathur/screentime/platform/WindDownApplier.kt
- com/vayunmathur/screentime/platform/WindowScheduler.kt
- com/vayunmathur/screentime/platform/DndState.kt
### receiver/ + service/ — system bridges
- com/vayunmathur/screentime/receiver/Receivers.kt
- com/vayunmathur/screentime/service/Services.kt
### ui/ + widget/ — dashboard + widget (the entries)
- com/vayunmathur/screentime/MainActivity.kt
- com/vayunmathur/screentime/Route.kt
- com/vayunmathur/screentime/Navigation.kt
- com/vayunmathur/screentime/ui/DashboardScreen.kt
- com/vayunmathur/screentime/ui/AppDetailsScreen.kt
- com/vayunmathur/screentime/ui/FocusScreen.kt
- com/vayunmathur/screentime/ui/FrictionScreen.kt
- com/vayunmathur/screentime/ui/WindDownScreen.kt
- com/vayunmathur/screentime/ui/UsageBarChart.kt
- com/vayunmathur/screentime/ui/UsageFormat.kt
- com/vayunmathur/screentime/ui/AppIconImage.kt
- com/vayunmathur/screentime/widget/ScreenTimeGlanceWidget.kt
- com/vayunmathur/screentime/widget/ScreenTimeWidgetReceiver.kt

## Verify (this module only)
```
./gradlew :screentime:compileDevKotlin
./gradlew :screentime:lint
./gradlew :screentime:checkMetadata
```
