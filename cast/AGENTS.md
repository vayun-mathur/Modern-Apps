# AGENTS.md — cast/ (':cast')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `cast/` + allowed shared modules. Do not root-scan.

_Install: ./install cast (dev by default)._
- Gradle: :cast / dir: cast/
- Package roots present: ui, domain, platform, network, service
- Entry files: MainActivity.kt, Route.kt, Navigation.kt (no `CastApplication` class)
- Deps: :library:aosp-stubs, :cast:protocol, :sdk:cast
- Metadata: metadata_data/cast.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Phone-side sender for offline screen mirroring + media handoff to the `cast/tv` receiver over your own Wi-Fi — E2EE session with pairing-code-bound handshake, no internet. Sibling `:cast:protocol` owns the wire; `:sdk:cast` is the third-party handoff API.
**Key features:**
- Mirroring pipeline: screen capture → video/audio encoders (`platform/mirror/`) → RTP sender → TV; codec negotiated from the receiver's capabilities so the phone sends full resolution
- Content casting (`CastContentSession`, `MediaProxyServer`) so other apps hand media off through `:sdk:cast`
- Discovery + pairing UI, Quick Settings tile start/stop, remote-display provider for system routing
**Architecture / data-flow:** `ui/` picker/status → `platform/CastViewModel` (+ `CastUiContract`) + `platform/CastController` → `platform/MirrorClient`/`CastConnection` over `network/` UDP + control sockets, framed by `:cast:protocol` (`StreamingSession`, `Pairing`); `service/CastService` + `ContentCastService` own the session lifetime.
**Entry points + reading order:** `Route` → `MainActivity.kt` → `Navigation.kt` → `platform/CastController.kt` → `platform/mirror/MirrorEngine.kt` → `network/ControlSocket.kt`.
**Gotchas:**
- Encoder support varies by device (`mirror/EncoderSupport`) — capability checks before assuming codecs
- Screen-capture consent is one-shot per session (`MirrorConsentActivity`); losing it mid-mirror kills the stream
- Keep `:cast:protocol` untouched from here — wire changes need sender + TV receiver updated together

## Key files
### Root — entry + navigation
- com/vayunmathur/cast/MainActivity.kt
- com/vayunmathur/cast/Navigation.kt
- com/vayunmathur/cast/Route.kt
### domain/ — device + client state
- com/vayunmathur/cast/domain/CastDevice.kt
- com/vayunmathur/cast/domain/ClientState.kt
### network/ — transports
- com/vayunmathur/cast/network/CastUdpTransport.kt
- com/vayunmathur/cast/network/ControlSocket.kt
### platform/ — controller, sessions, discovery, proxy
- com/vayunmathur/cast/platform/CastViewModel.kt
- com/vayunmathur/cast/platform/CastUiContract.kt
- com/vayunmathur/cast/platform/CastController.kt
- com/vayunmathur/cast/platform/CastConnection.kt
- com/vayunmathur/cast/platform/CastSessionTypes.kt
- com/vayunmathur/cast/platform/CastSessionWatch.kt
- com/vayunmathur/cast/platform/CastCodecNegotiation.kt
- com/vayunmathur/cast/platform/CastContentSession.kt
- com/vayunmathur/cast/platform/CastMirrorSession.kt
- com/vayunmathur/cast/platform/MirrorClient.kt
- com/vayunmathur/cast/platform/MediaProxyServer.kt
- com/vayunmathur/cast/platform/CastPickerActivity.kt
- com/vayunmathur/cast/platform/CastPairActivity.kt
- com/vayunmathur/cast/platform/CastDesktopDisplay.kt
- com/vayunmathur/cast/platform/discovery/CastDiscoveryManager.kt
### platform/mirror/ — capture + encoders + sender
- com/vayunmathur/cast/platform/mirror/MirrorEngine.kt
- com/vayunmathur/cast/platform/mirror/MirrorSource.kt
- com/vayunmathur/cast/platform/mirror/ScreenCapture.kt
- com/vayunmathur/cast/platform/mirror/VideoEncoder.kt
- com/vayunmathur/cast/platform/mirror/AudioEncoder.kt
- com/vayunmathur/cast/platform/mirror/PcmAudioEncoder.kt
- com/vayunmathur/cast/platform/mirror/OpusEncoder.kt
- com/vayunmathur/cast/platform/mirror/EncoderSupport.kt
- com/vayunmathur/cast/platform/mirror/StreamSender.kt
- com/vayunmathur/cast/platform/mirror/MirrorGeometry.kt
- com/vayunmathur/cast/platform/mirror/MirrorPreferences.kt
- com/vayunmathur/cast/platform/mirror/MirrorConsentActivity.kt
- com/vayunmathur/cast/platform/mirror/MirrorTileService.kt
### platform/remotedisplay/ — system display routing
- com/vayunmathur/cast/platform/remotedisplay/MaRemoteDisplayProvider.kt
- com/vayunmathur/cast/platform/remotedisplay/CastSystemDisplay.kt
### service/ — session services
- com/vayunmathur/cast/service/CastService.kt
- com/vayunmathur/cast/service/ContentCastService.kt
- com/vayunmathur/cast/service/CastRemoteDisplayService.kt
- com/vayunmathur/cast/service/ClientResourceResolver.kt
### ui/ — picker + status + pairing
- com/vayunmathur/cast/ui/CastScreen.kt
- com/vayunmathur/cast/ui/CastPickerContent.kt
- com/vayunmathur/cast/ui/CastDeviceRow.kt
- com/vayunmathur/cast/ui/CastContent.kt
- com/vayunmathur/cast/ui/CastMirrorStatusCard.kt
- com/vayunmathur/cast/ui/CastPairCodeCard.kt
- com/vayunmathur/cast/ui/CastPairDialog.kt

## Verify (this module only)
```
./gradlew :cast:compileDevKotlin
./gradlew :cast:lint
./gradlew :cast:checkMetadata
```
