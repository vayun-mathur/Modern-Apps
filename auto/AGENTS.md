# AGENTS.md — auto/ (':auto')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `auto/` + allowed shared modules. Do not root-scan.

_Install: ./install auto (dev by default)._
- Gradle: :auto / dir: auto/
- Package roots present: ui, platform, network, service, notifications, telephony
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :auto:protocol (`:library:carhost` only if the build file says so — verify before adding)
- Metadata: metadata_data/auto.md (present)
- Rust: no / screenshotTest: yes (phone status/pairing/pinned-apps previews)

## Overview
**Purpose:** Car head-unit projection (Android Auto compatible) — NOT a phone app. Projects driving-safe nav/media/call UI to the car display over USB or wireless; test with `./dhu` (Desktop Head Unit loopback), not on a phone screen.
**Key features:**
- USB + wireless transports (`network/UsbConnector`/`WifiDirectConnector`, `HeadUnitServer`) speaking the GAL protocol in `:auto:protocol` (framing, TLS, video/input/sensor codecs)
- Car cards: nav guidance, media now-playing + capture, call cards, messaging with voice reply, TTS, sensor feed (speed/gear/night-mode)
- Role negotiation (MAOS host vs mirror), pairing flow, boot/startup services, `telephony/` in-call services for car calls
**Architecture / data-flow:** Transports → `:auto:protocol` codecs/sessions → `platform/` channels (video/audio/input/sensor/nav/media/messaging) → `HostManager`/`HostSession` (owned by `service/CarAppHostSession`) + card pushers → car display; `service/ProjectionService` owns the session, `platform/AutoViewModel` (+ `PairingViewModel`) drives `ui/` phone-side pairing/status.
**Entry points + reading order:** `Route` → `MainActivity.kt` → `service/CarAppHostSession.kt` → `service/ProjectionService.kt` → `network/HeadUnitServer.kt` → `:auto:protocol` `GalConnection.kt`.
**Gotchas:**
- Never evaluate this module on a phone screen — `./dhu` is the loop; car-display geometry comes from the head unit
- Driver-distraction rules constrain the car UI (cards, not free navigation) — keep car surfaces templated
- Connection teardown/reconnect (USB unplug, wireless drop) is the main bug surface; `ReconnectBackoff` + reset receivers matter

## Key files
### Root — entry + navigation
- com/vayunmathur/auto/MainActivity.kt
- com/vayunmathur/auto/Navigation.kt
- com/vayunmathur/auto/Route.kt — phone Home/Pairing/PinnedApps + car LauncherHome/App(appId) (car stack synced from CarLauncherState.selectedApp)
### network/ — head-unit transports
- com/vayunmathur/auto/network/HeadUnitServer.kt
- com/vayunmathur/auto/network/TransportIntake.kt
- com/vayunmathur/auto/network/UsbConnector.kt
- com/vayunmathur/auto/network/WifiDirectConnector.kt
### platform/ — host, cards, channels, pairing, receivers
- com/vayunmathur/auto/platform/HostSession.kt — one hosted car-app session
- com/vayunmathur/auto/platform/HostManager.kt — multi-app host fan-in
- com/vayunmathur/auto/service/CarAppHostSession.kt — session-scoped host owner
- com/vayunmathur/auto/platform/AutoViewModel.kt
- com/vayunmathur/auto/platform/PairingViewModel.kt
- com/vayunmathur/auto/platform/AutoConnectionState.kt
- com/vayunmathur/auto/platform/AutoSessionState.kt
- com/vayunmathur/auto/platform/CarDisplay.kt
- com/vayunmathur/auto/platform/CarPresentation.kt
- com/vayunmathur/auto/platform/CarApps.kt
- com/vayunmathur/auto/platform/CallCardPush.kt
- com/vayunmathur/auto/platform/CarTts.kt
- com/vayunmathur/auto/platform/VideoEncoder.kt
- com/vayunmathur/auto/platform/VideoSinkChannel.kt
- com/vayunmathur/auto/platform/AudioSinkChannel.kt
- com/vayunmathur/auto/platform/AudioEvent.kt
- com/vayunmathur/auto/platform/AudioState.kt
- com/vayunmathur/auto/platform/InputChannel.kt
- com/vayunmathur/auto/platform/InputEvent.kt
- com/vayunmathur/auto/platform/SensorChannel.kt
- com/vayunmathur/auto/platform/SensorEvent.kt
- com/vayunmathur/auto/platform/GuidanceChannel.kt
- com/vayunmathur/auto/platform/NavGuidanceMonitor.kt
- com/vayunmathur/auto/platform/NavStatusChannel.kt
- com/vayunmathur/auto/platform/MediaNowPlaying.kt
- com/vayunmathur/auto/platform/MediaPlaybackMonitor.kt
- com/vayunmathur/auto/platform/MusicCapture.kt
- com/vayunmathur/auto/platform/MusicCapturePrefs.kt
- com/vayunmathur/auto/platform/MusicCaptureSinkHolder.kt
- com/vayunmathur/auto/platform/Messaging.kt
- com/vayunmathur/auto/platform/MessagingAudio.kt
- com/vayunmathur/auto/platform/MessagingPrefs.kt
- com/vayunmathur/auto/platform/MicPermission.kt
- com/vayunmathur/auto/platform/MicSourceChannel.kt
- com/vayunmathur/auto/platform/PhoneStatusMonitor.kt
- com/vayunmathur/auto/platform/TransportState.kt
- com/vayunmathur/auto/platform/ActiveCallInfo.kt
- com/vayunmathur/auto/platform/MaosRoleStatus.kt
- com/vayunmathur/auto/platform/DeepLinkResolverActivity.kt
- com/vayunmathur/auto/platform/WirelessStartupActivity.kt
- com/vayunmathur/auto/platform/WirelessStartupReceiver.kt
- com/vayunmathur/auto/platform/WirelessSetupCarService.kt
- com/vayunmathur/auto/platform/WirelessSetupSharedService.kt
- com/vayunmathur/auto/platform/BootReceiver.kt
- com/vayunmathur/auto/platform/UsbReceiver.kt
- com/vayunmathur/auto/platform/UsbTPlusReceiver.kt
- com/vayunmathur/auto/platform/WifiBluetoothReceiver.kt
- com/vayunmathur/auto/platform/ConnectivityEventReceiver.kt
- com/vayunmathur/auto/platform/ConnectionResetReceiver.kt
- com/vayunmathur/auto/platform/CarStartupService.kt
- com/vayunmathur/auto/platform/CarSetupService.kt
- com/vayunmathur/auto/platform/CarChimeraService.kt
- com/vayunmathur/auto/platform/CarCompanionDeviceService.kt
- com/vayunmathur/auto/platform/CarSystemUiControllerService.kt
- com/vayunmathur/auto/platform/AppDecorService.kt
### service/ — projection + messaging/music capture
- com/vayunmathur/auto/service/ProjectionService.kt
- com/vayunmathur/auto/service/CarAppHostSession.kt
- com/vayunmathur/auto/service/CarMessagingAudio.kt
- com/vayunmathur/auto/service/MessagingCarAppService.kt
- com/vayunmathur/auto/service/MusicCaptureService.kt
- com/vayunmathur/auto/service/UnownedServiceLog.kt
### telephony/ — car in-call services
- com/vayunmathur/auto/telephony/CarProjectionInCallService.kt
- com/vayunmathur/auto/telephony/NonCarInCallService.kt
### notifications/ — message mirroring + replies
- com/vayunmathur/auto/notifications/MessageMirrorService.kt
- com/vayunmathur/auto/notifications/MessageMirrorBus.kt
- com/vayunmathur/auto/notifications/MessageReplyRoute.kt
### ui/ — phone-side pairing + session status
- com/vayunmathur/auto/ui/AutoScreen.kt
- com/vayunmathur/auto/ui/PairingScreen.kt
- com/vayunmathur/auto/ui/SessionCard.kt
- com/vayunmathur/auto/ui/AudioRow.kt
- com/vayunmathur/auto/ui/MessagingConsentCard.kt
- com/vayunmathur/auto/ui/MicPermissionCard.kt
- com/vayunmathur/auto/ui/MusicCaptureCard.kt
- com/vayunmathur/auto/ui/MusicCaptureGrant.kt

## Verify (this module only)
```
./gradlew :auto:compileDevKotlin
./gradlew :auto:lint
./gradlew :auto:checkMetadata
```
