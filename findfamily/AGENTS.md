# AGENTS.md — findfamily/ (':findfamily')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `findfamily/` + allowed shared modules. Do not root-scan.

_Install: ./install findfamily (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :findfamily / dir: findfamily/
- Package roots present: ui, data, domain, platform, intents, service
- Entry files: MainActivity.kt (no Route.kt — `Route` + `Navigation` are inline)
- Deps: :library:network, :library:e2ee-p2p, :library:room, :library:work, :library:image, :library:map, :library:aosp-stubs
- Metadata: metadata_data/findfamily.md (present)
- Rust: no / screenshotTest: yes

### Relay server (backend, outside this repo)
WebSocket relay host: `wss://api.maproj.org/api/ws` (Cloudflare FIRST_PARTY pins). Server checkout: `../location_share_server` (sibling of this repo root, `C:\Users\Vayun\Documents\code\location_share_server`). Live SSH: `ssh root@87.86.80.39`. Full details: root `AGENTS.md` → "Backend server (outside this repo)".

## Overview
**Purpose:** End-to-end encrypted location sharing for family and friends — no emails, phone numbers, or personal info required. Requires internet.
**Key features:**
- Temporary per-person sharing toggles, `findfamily://add/<base26>` invites with key-fingerprint check, and temporary links
- Saved places (waypoints) with enter/leave notifications plus no-show alerts driven by `domain/NoShowPolicy`
- Live battery, speed, and dwell-time per person; UWB precision ranging where hardware supports it
- Powered-off finding (BLE beacons) and a Quick Settings tile to stop sharing instantly
**Architecture / data-flow:** Single `MainPage` UI + `util/FindFamilyViewModel` over `data/FindFamilyRepository` (Room: users, waypoints, links, no-show alerts); live location flows through `util/LocationTracking*` foreground service + `tracker/` BLE protocol and `uwb/` ranging, relayed E2EE via `:library:e2ee-p2p` (`util/Networking*`). `onCreateBeforeContent`: parse the add-invite deep link, then `NetworkClient.init(FIRST_PARTY)`.
**Entry points + reading order:** `Route` (`MainPage` / `AddPersonDialog` / `AddLinkDialog` / `UwbRangingPage` + dialogs, all inline in `MainActivity.kt`) → `Navigation()` → `ui/MainPage.kt` → `util/FindFamilyViewModel.kt` → `data/FindFamilyRepository.kt`.
**Gotchas:**
- Location permission spec must request fine AND coarse together (Android 12+ Precise/Approximate choice; fine alone is ignored), plus background location and `BLUETOOTH_SCAN` — the powered-off-finder scanner starts then dies without it
- `singleTop` warm-start invites/UWB taps arrive via `onNewIntent`, not a fresh `onCreate`
- `AddTrackerDialog` is dev-only (`DEV_BUILD` custom UWB tracker binding); don't expose it in release flows
- Extra non-conforming dirs (`ipc/`, `tracker/`, `uwb/`, `util/`) hold real code — like camera's `util/`, a closed-roots migration is pending

## Key files
### Root — entry + inline navigation
- com/vayunmathur/findfamily/MainActivity.kt — Route, Navigation, deep-link parsing, permission spec
### data/ — Room entities + repository
- com/vayunmathur/findfamily/data/FindFamilyRepository.kt
- com/vayunmathur/findfamily/data/FFDatabase.kt
- com/vayunmathur/findfamily/data/User.kt
- com/vayunmathur/findfamily/data/Waypoint.kt
- com/vayunmathur/findfamily/data/TemporaryLink.kt
- com/vayunmathur/findfamily/data/NoShowAlert.kt
- com/vayunmathur/findfamily/data/NoShowAlertDao.kt
- com/vayunmathur/findfamily/data/Coord.kt
- com/vayunmathur/findfamily/data/LocationValue.kt
- com/vayunmathur/findfamily/data/DirectBootStore.kt
### domain/ — pairing auth + no-show policy (pure logic)
- com/vayunmathur/findfamily/domain/PairAuth.kt
- com/vayunmathur/findfamily/domain/NoShowPolicy.kt
### intents/ — assistant IPC
- com/vayunmathur/findfamily/intents/GetIntent.kt
- com/vayunmathur/findfamily/intents/CreateLinkIntent.kt
### ipc/ — cross-app family-location protocol
- com/vayunmathur/findfamily/ipc/FamilyLocationProtocol.kt
- com/vayunmathur/findfamily/ipc/FamilyLocationService.kt
### platform/ — reporters + schedulers
- com/vayunmathur/findfamily/platform/FinalLocationReporter.kt
- com/vayunmathur/findfamily/platform/NoShowCheckScheduler.kt
- com/vayunmathur/findfamily/platform/PoweredOffBeacon.kt
### service/ — Quick Settings tile
- com/vayunmathur/findfamily/service/SharingTileService.kt
### tracker/ — BLE tracker protocol + powered-off finding
- com/vayunmathur/findfamily/tracker/TrackerProtocol.kt
- com/vayunmathur/findfamily/tracker/TrackerBle.kt
- com/vayunmathur/findfamily/tracker/TrackerProvisioner.kt
- com/vayunmathur/findfamily/tracker/TrackerStore.kt
- com/vayunmathur/findfamily/tracker/TrackerReporting.kt
- com/vayunmathur/findfamily/tracker/TrackerFeature.kt
- com/vayunmathur/findfamily/tracker/TrackerBeaconScanner.kt
- com/vayunmathur/findfamily/tracker/TrackerBinder.kt
- com/vayunmathur/findfamily/tracker/TrackerUwbGatt.kt
- com/vayunmathur/findfamily/tracker/TrackerUwbKeys.kt
- com/vayunmathur/findfamily/tracker/PoweredOffProtocol.kt
- com/vayunmathur/findfamily/tracker/PoweredOffBle.kt
- com/vayunmathur/findfamily/tracker/PoweredOffScanner.kt
- com/vayunmathur/findfamily/tracker/PoweredOffReporting.kt
- com/vayunmathur/findfamily/tracker/PoweredOffRecovery.kt
- com/vayunmathur/findfamily/tracker/PoweredOffKeyStore.kt
### uwb/ — UWB ranging stack
- com/vayunmathur/findfamily/uwb/UwbController.kt
- com/vayunmathur/findfamily/uwb/UwbEnvelope.kt
- com/vayunmathur/findfamily/uwb/UwbInbox.kt
- com/vayunmathur/findfamily/uwb/UwbAccessoryProtocol.kt
### ui/ — main page + dialogs + UWB screen
- com/vayunmathur/findfamily/ui/MainPage.kt
- com/vayunmathur/findfamily/ui/MainPageContent.kt
- com/vayunmathur/findfamily/ui/MainPageActions.kt
- com/vayunmathur/findfamily/ui/MainPageCards.kt
- com/vayunmathur/findfamily/ui/MainPageEffects.kt
- com/vayunmathur/findfamily/ui/MainPageOverlays.kt
- com/vayunmathur/findfamily/ui/MainMapSlot.kt
- com/vayunmathur/findfamily/ui/MainSheetContent.kt
- com/vayunmathur/findfamily/ui/MapView.kt
- com/vayunmathur/findfamily/ui/MainFabOverlay.kt
- com/vayunmathur/findfamily/ui/FamilyListSheet.kt
- com/vayunmathur/findfamily/ui/PersonDetailSheet.kt
- com/vayunmathur/findfamily/ui/UserCard.kt
- com/vayunmathur/findfamily/ui/AutoToggleRow.kt
- com/vayunmathur/findfamily/ui/FindFamilyWideLayout.kt
- com/vayunmathur/findfamily/ui/UwbRangingScreen.kt
- com/vayunmathur/findfamily/ui/dialogs/AddPersonDialog.kt — also hosts `decodeBase26`
- com/vayunmathur/findfamily/ui/dialogs/AddLinkDialog.kt
- com/vayunmathur/findfamily/ui/dialogs/AddTrackerDialog.kt — dev-only
- com/vayunmathur/findfamily/ui/dialogs/NoShowAlertDialog.kt
- com/vayunmathur/findfamily/ui/dialogs/GpsFallbackWarningDialog.kt
- com/vayunmathur/findfamily/ui/dialogs/KeyMismatchDialog.kt
- com/vayunmathur/findfamily/ui/dialogs/OutdatedPeerDialog.kt
- com/vayunmathur/findfamily/ui/dialogs/SecurityCodeDialog.kt
### util/ — ViewModel, tracking service, networking (migration pending)
- com/vayunmathur/findfamily/util/FindFamilyViewModel.kt
- com/vayunmathur/findfamily/util/FindFamilyUiContract.kt
- com/vayunmathur/findfamily/util/MapPageState.kt
- com/vayunmathur/findfamily/util/LocationTrackingService.kt
- com/vayunmathur/findfamily/util/LocationServiceController.kt
- com/vayunmathur/findfamily/util/LocationTrackingHeartbeat.kt
- com/vayunmathur/findfamily/util/LocationTrackingInbound.kt
- com/vayunmathur/findfamily/util/LocationTrackingDirectBoot.kt
- com/vayunmathur/findfamily/util/LocationTrackingCrowdFinding.kt
- com/vayunmathur/findfamily/util/LocationTrackingNotifications.kt
- com/vayunmathur/findfamily/util/LocationProviderStatus.kt
- com/vayunmathur/findfamily/util/Networking.kt
- com/vayunmathur/findfamily/util/NetworkingLive.kt
- com/vayunmathur/findfamily/util/NetworkingHelper.kt
- com/vayunmathur/findfamily/util/NetworkingTracker.kt
- com/vayunmathur/findfamily/util/UwbSessionManager.kt
- com/vayunmathur/findfamily/util/Platform.kt
- com/vayunmathur/findfamily/util/FindFamilyNotificationChannels.kt
- com/vayunmathur/findfamily/util/TrackingTileService.kt
- com/vayunmathur/findfamily/util/BootReceiver.kt
- com/vayunmathur/findfamily/util/AppBackupAgent.kt

## Verify (this module only)
```
./gradlew :findfamily:compileDevKotlin
./gradlew :findfamily:lint
./gradlew :findfamily:checkMetadata
```
