# AGENTS.md — updater/ (':updater')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `updater/` + allowed shared modules. Do not root-scan.

_Install: ./install updater (dev by default)._
- Gradle: :updater / dir: updater/
- Package roots present: ui, domain, platform, service, notifications
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:work, :library:aosp-stubs
- Metadata: metadata_data/updater.md (present)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Verified A/B OTA system updates — background check/download/apply to the inactive slot, release-key signature verification, idle-only reboot. Internet only for update traffic; metered-gated.
**Key features:** `UpdateCheckWorker` polling → `UpdateChecker` → `OtaDownloader` (incremental plans) → `OtaInstaller`/`UpdateInstallService` → `IdleReboot`; `domain/` validates packages/metadata against the release key before anything is written.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/UpdaterScreen.kt` → `platform/UpdateChecker.kt` → `domain/OtaPackageValidation.kt`.
**Gotchas:** Signature verification is the product — never weaken it for UX; failed updates must leave the device bootable (A/B invariant); screenshot tests off, verify on device.

## Key files
### Root — entry + navigation
- com/vayunmathur/updater/MainActivity.kt
- com/vayunmathur/updater/Navigation.kt
- com/vayunmathur/updater/Route.kt
### domain/ — pure OTA validation (no Android)
- com/vayunmathur/updater/domain/OtaPackageValidation.kt
- com/vayunmathur/updater/domain/OtaPackageMetadata.kt
- com/vayunmathur/updater/domain/OtaArtifacts.kt
- com/vayunmathur/updater/domain/OtaDownloadPlan.kt
- com/vayunmathur/updater/domain/PayloadProperties.kt
- com/vayunmathur/updater/domain/UpdateMetadata.kt
- com/vayunmathur/updater/domain/UpdateComparison.kt
- com/vayunmathur/updater/domain/UpdateEngineOutcome.kt
- com/vayunmathur/updater/domain/AutoInstallPolicy.kt
### platform/ — check, download, install, reboot
- com/vayunmathur/updater/platform/UpdateChecker.kt
- com/vayunmathur/updater/platform/UpdateCheckWorker.kt
- com/vayunmathur/updater/platform/OtaDownloader.kt
- com/vayunmathur/updater/platform/OtaInstaller.kt
- com/vayunmathur/updater/platform/UpdaterViewModel.kt
- com/vayunmathur/updater/platform/UpdaterPreferences.kt
- com/vayunmathur/updater/platform/SystemBuild.kt
- com/vayunmathur/updater/platform/IdleReboot.kt
- com/vayunmathur/updater/platform/RebootReceiver.kt
- com/vayunmathur/updater/platform/UpdateBootReceiver.kt
### service/ + notifications/ — install service + status
- com/vayunmathur/updater/service/UpdateInstallService.kt
- com/vayunmathur/updater/notifications/UpdateNotifications.kt
### ui/ — updater surface
- com/vayunmathur/updater/ui/UpdaterScreen.kt

## Verify (this module only)
```
./gradlew :updater:compileDevKotlin
./gradlew :updater:lint
./gradlew :updater:checkMetadata
```
