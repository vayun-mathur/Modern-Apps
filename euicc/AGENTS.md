# AGENTS.md — euicc/ (':euicc')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `euicc/` + allowed shared modules. Do not root-scan.

_Install: ./install euicc (dev by default)._
- Gradle: :euicc / dir: euicc/
- Package roots present: ui, data, platform, service, telephony
- Entry files: Route.kt, Navigation.kt + platform/LuiActivity.kt (LPA UI entry; no MainActivity)
- Deps: :library:aosp-stubs, :library:network
- Metadata: metadata_data/euicc.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** Privileged eSIM manager (LPA) — list/enable/disable/delete/rename profiles, QR/activation-code downloads (SGP.22 ES9+), EID info, pending notifications. Protocol core in Rust; internet only for carrier downloads.
**Key features:** `ui/` home/download/detail/QR screens → `platform/EuiccViewModel` → `service/EuiccManagerService` → `telephony/EuiccChannelManager` → `EuiccNative` Rust bridge; carrier confirmations in `ui/download/`.
**Entry points:** `Route` → `Navigation.kt` → `platform/LuiActivity.kt` → `ui/EuiccHomeScreen.kt` → `platform/EuiccViewModel.kt` → `service/EuiccManagerService.kt`.
**Gotchas:** eUICC access is privileged hardware access — sideloaded builds can't manage profiles; profile deletion is destructive, gate it behind confirmation.

## Key files
### Root — navigation + Rust bridge (no MainActivity)
- com/vayunmathur/euicc/Navigation.kt
- com/vayunmathur/euicc/Route.kt
- com/vayunmathur/euicc/EuiccNative.kt — SGP.22 Rust bridge
### data/ — profiles + notifications
- com/vayunmathur/euicc/data/Profile.kt
- com/vayunmathur/euicc/data/EuiccInfo.kt
- com/vayunmathur/euicc/data/Notification.kt
### platform/ + telephony/ + service/ — LPA stack
- com/vayunmathur/euicc/platform/EuiccViewModel.kt
- com/vayunmathur/euicc/platform/LuiActivity.kt — LPA UI entry
- com/vayunmathur/euicc/platform/DownloadState.kt
- com/vayunmathur/euicc/telephony/EuiccChannelManager.kt
- com/vayunmathur/euicc/service/EuiccManagerService.kt
### ui/ + ui/dialogs/ + ui/download/ — LPA screens
- com/vayunmathur/euicc/ui/EuiccHomeScreen.kt
- com/vayunmathur/euicc/ui/AddSimScreen.kt
- com/vayunmathur/euicc/ui/ActivationCodeScreen.kt
- com/vayunmathur/euicc/ui/DownloadScreen.kt
- com/vayunmathur/euicc/ui/ProfileDetailScreen.kt
- com/vayunmathur/euicc/ui/DeviceInfoScreen.kt
- com/vayunmathur/euicc/ui/QrScanner.kt
- com/vayunmathur/euicc/ui/dialogs/EidDialog.kt
- com/vayunmathur/euicc/ui/dialogs/RenameDialog.kt
- com/vayunmathur/euicc/ui/download/ConfirmCarrierContent.kt
- com/vayunmathur/euicc/ui/download/ConfirmationCodeContent.kt
- com/vayunmathur/euicc/ui/download/InstallingContent.kt
- com/vayunmathur/euicc/ui/download/CompleteContent.kt
- com/vayunmathur/euicc/ui/download/FailedContent.kt

## Verify (this module only)
```
./gradlew :euicc:compileDevKotlin
./gradlew :euicc:lint
./gradlew :euicc:checkMetadata
```
