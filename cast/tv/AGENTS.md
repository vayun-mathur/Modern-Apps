# AGENTS.md — cast/tv/ (':cast:tv')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `cast/tv/` + allowed shared modules. Do not root-scan.

_Install: ./install cast/tv (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :cast:tv / dir: cast/tv/
- Package roots present: ui, platform, service
- Entry files: MainActivity.kt, Route.kt, Navigation.kt (receiver shell — NOT a phone app; one-deep Route host, TV panel modes instead)
- Deps: :cast:protocol, :library:e2ee-p2p
- Metadata: metadata_data/cast-tv.md (present)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Android-TV receiver for the `cast/` sender — advertises, pairs, decodes and plays mirrored screen + handed-off media. Runs on TV, never evaluated as a phone app.
**Key features:**
- Receiver advertisement + pairing handshake (`ReceiverAdvertiser`, `ReceiverPairing`, `PairingStore`) against `:cast:protocol`
- Control channel + media loop (`ReceiverController`, `ReceiverMediaLoop`) feeding `ContentPlayer`/`AudioPlayer`/`VideoDecoder` with artwork fetch
- TV panel modes + transport overlay + now-playing UI (`PanelModes`, `TransportOverlay`, `NowPlaying`)
**Architecture / data-flow:** `service/ReceiverService` owns lifetime → `platform/ReceiverController` + `ControlChannel` → `ReceiverMediaLoop` → decoders/players → `ui/` TV surfaces; mirror input lands in `MirrorActivity`.
**Entry points + reading order:** `MainActivity.kt` → `service/ReceiverService.kt` → `platform/ReceiverController.kt` → `platform/ReceiverMediaLoop.kt` → `platform/ContentPlayer.kt`.
**Gotchas:**
- TV-only: no edge-to-edge phone chrome, D-pad focus matters, no touch assumptions
- Decoder capabilities feed back to the sender's codec negotiation — receiver bugs surface as sender quality drops
- Pairing store persists trust; clearing it force-drops all senders

## Key files
### Root — receiver shell + navigation
- com/vayunmathur/cast/tv/MainActivity.kt
- com/vayunmathur/cast/tv/Route.kt — Starting/Ready/Pairing/Connected/Failed destinations
- com/vayunmathur/cast/tv/Navigation.kt — one-deep MainNavigation host (phase → Route)
### platform/ — receiver pipeline
- com/vayunmathur/cast/tv/platform/ReceiverController.kt
- com/vayunmathur/cast/tv/platform/ReceiverUiContract.kt
- com/vayunmathur/cast/tv/platform/ReceiverAdvertiser.kt
- com/vayunmathur/cast/tv/platform/ReceiverPairing.kt
- com/vayunmathur/cast/tv/platform/PairingStore.kt
- com/vayunmathur/cast/tv/platform/ControlChannel.kt
- com/vayunmathur/cast/tv/platform/ReceiverMediaLoop.kt
- com/vayunmathur/cast/tv/platform/ReceiverContentSession.kt
- com/vayunmathur/cast/tv/platform/MediaReceiver.kt
- com/vayunmathur/cast/tv/platform/ContentPlayer.kt
- com/vayunmathur/cast/tv/platform/AudioPlayer.kt
- com/vayunmathur/cast/tv/platform/VideoDecoder.kt
- com/vayunmathur/cast/tv/platform/ArtworkFetcher.kt
- com/vayunmathur/cast/tv/platform/MirrorActivity.kt
- com/vayunmathur/cast/tv/platform/PanelModes.kt
### service/ — receiver lifetime
- com/vayunmathur/cast/tv/service/ReceiverService.kt
### ui/ — TV surfaces (D-pad, not touch)
- com/vayunmathur/cast/tv/ui/ReceiverContent.kt
- com/vayunmathur/cast/tv/ui/NowPlaying.kt
- com/vayunmathur/cast/tv/ui/TransportOverlay.kt
- com/vayunmathur/cast/tv/ui/Theme.kt — TV receiver theme (not DynamicTheme)

## Verify (this module only)
```
./gradlew :cast:tv:compileDevKotlin
./gradlew :cast:tv:lint
./gradlew :cast:tv:checkMetadata
```
