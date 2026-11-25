# AGENTS.md — things/ (':things')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `things/` + allowed shared modules. Do not root-scan.

_Install: ./install things (dev by default)._
- Gradle: :things / dir: things/
- Package roots present: ui, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: (none declared — BLE + Health Connect only)
- Metadata: metadata_data/things.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** BLE hyd ration tracker — pairs a smart water bottle, totals sips against a daily goal. No account, no cloud; readings stay on the phone. (Scale BLE files sit alongside the bottle flow.)
**Key features:** Scan/pair/disconnect, live per-sip history, goal progress; Health Connect bridge helper; permissions-gated onboarding.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/HomePage.kt` → `platform/DeviceController.kt` → `platform/BleManager.kt`.
**Gotchas:** BLE behavior is device- and radio-state-dependent — test scan/pair/drop/reconnect on hardware, not the emulator.

## Key files
### Root — entry + navigation
- com/vayunmathur/things/MainActivity.kt
- com/vayunmathur/things/Navigation.kt
- com/vayunmathur/things/Route.kt
### platform/ — BLE + device control
- com/vayunmathur/things/platform/BleManager.kt
- com/vayunmathur/things/platform/DeviceController.kt
- com/vayunmathur/things/platform/DeviceService.kt
- com/vayunmathur/things/platform/ScaleBleManager.kt
- com/vayunmathur/things/platform/ScaleBleConnection.kt
- com/vayunmathur/things/platform/ScaleBleProtocol.kt
- com/vayunmathur/things/platform/BodyComposition.kt
- com/vayunmathur/things/platform/HealthConnectHelper.kt
### ui/ — home, devices, permissions
- com/vayunmathur/things/ui/HomePage.kt
- com/vayunmathur/things/ui/DevicesPage.kt
- com/vayunmathur/things/ui/PermissionsPage.kt
- com/vayunmathur/things/ui/PermissionsRationaleActivity.kt

## Verify (this module only)
```
./gradlew :things:compileDevKotlin
./gradlew :things:lint
./gradlew :things:checkMetadata
```
