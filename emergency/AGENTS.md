# AGENTS.md — emergency/ (':emergency')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `emergency/` + allowed shared modules. Do not root-scan.

_Install: ./install emergency (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :emergency / dir: emergency/
- Package roots present: ui, data, domain, platform, service, notifications
- Entry files: MainActivity.kt, Route.kt, Navigation.kt + ui/SosScreen.kt (SosActivity stays separate; see Navigation.kt)
- Deps: (none declared)
- Metadata: metadata_data/emergency.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** OS emergency surfaces — lockscreen medical info (Health Connect allergies/meds), emergency contacts, SOS countdown with slide-cancel + warning sound. 100% offline.
**Key features:** `data/EmergencyRepository` (info, imported FHIR medical, contacts) → `platform/SosCoordinator` → `service/SosCountdownService` + `receiver/SosActionReceiver` → `notifications/SosNotifications`; gesture trigger provider + number lookup with override.
**Entry points:** `MainActivity.kt` (ViewInfo/EditInfo host) + `ui/SosScreen.kt` SosActivity (Route.Sos host) → `platform/SosCoordinator.kt` → `service/SosCountdownService.kt` → `data/EmergencyRepository.kt`.
**Gotchas:**
- `receiver/` is NOT a closed package root — pending migration; don't add more files there
- SOS dials the real local emergency number — never trigger calls in tests, previews, or screenshots
- Lockscreen visibility is permission/signature-gated — verify on the OS build, not sideloaded

## Key files
### ui/ — SOS + info surfaces (the entries)
- com/vayunmathur/emergency/MainActivity.kt
- com/vayunmathur/emergency/Route.kt
- com/vayunmathur/emergency/Navigation.kt
- com/vayunmathur/emergency/ui/SosScreen.kt
- com/vayunmathur/emergency/ui/ViewInfoScreen.kt
- com/vayunmathur/emergency/ui/EditInfoScreen.kt
- com/vayunmathur/emergency/ui/OwnerAddressDialog.kt
- com/vayunmathur/emergency/ui/PermissionsRationaleActivity.kt
### data/ + domain/ — info + FHIR parsing
- com/vayunmathur/emergency/data/EmergencyRepository.kt
- com/vayunmathur/emergency/data/EmergencyInfo.kt
- com/vayunmathur/emergency/data/ImportedMedical.kt
- com/vayunmathur/emergency/domain/FhirMedicalParse.kt
- com/vayunmathur/emergency/domain/OwnerPostalFormat.kt
### platform/ — coordinator, lookup, gesture
- com/vayunmathur/emergency/platform/SosCoordinator.kt
- com/vayunmathur/emergency/platform/EmergencyViewModel.kt
- com/vayunmathur/emergency/platform/EmergencyUiContract.kt
- com/vayunmathur/emergency/platform/EmergencyNumberLookup.kt
- com/vayunmathur/emergency/platform/GestureProvider.kt
- com/vayunmathur/emergency/platform/ContactReader.kt
- com/vayunmathur/emergency/platform/OwnerIdentityReader.kt
- com/vayunmathur/emergency/platform/HealthConnectMedical.kt
- com/vayunmathur/emergency/platform/SosSound.kt
- com/vayunmathur/emergency/platform/EmergencySearchIndexablesProvider.kt
### receiver/ + service/ + notifications/ — countdown pipeline
- com/vayunmathur/emergency/receiver/SosActionReceiver.kt
- com/vayunmathur/emergency/service/SosCountdownService.kt
- com/vayunmathur/emergency/notifications/SosNotifications.kt

## Verify (this module only)
```
./gradlew :emergency:compileDevKotlin
./gradlew :emergency:lint
./gradlew :emergency:checkMetadata
```
