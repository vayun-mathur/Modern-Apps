# AGENTS.md — auto/protocol/ (':auto:protocol')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `auto/protocol/` + allowed shared modules. Do not root-scan.

- Gradle: :auto:protocol / dir: auto/protocol/
- Package roots present: (pure-Kotlin protocol library — single package, no Android roots)
- Entry files: GalConnection.kt + GalControlSession.kt (no MainActivity/Route — library)
- Deps: :library
- Metadata: metadata_data/auto-protocol.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Pure-Kotlin GAL (head-unit) protocol library for `:auto` — framing, session control, TLS, and media/input/sensor codecs. No Android, no UI; testable as plain JVM.
**Covers:** Frame read/write + fragmenter/assembler, version negotiation, USB/wireless sessions, transport selector, reconnect backoff, ack tracking; codecs for video, audio, input, sensors, nav status, maps guidance, messaging; TLS + credential/key handling; host-vs-mirror role policy and display routing.
**Entry points + reading order:** `GalConnection.kt` → `GalControlSession.kt` → `FrameReader.kt`/`FrameWriter.kt` → `VersionNegotiation.kt` → the codec for your channel (`VideoCodec`, `InputCodec`, `SensorCodec`, …).
**Gotchas:**
- Protocol changes break car interop — version negotiation must stay backward-compatible
- Keep it pure Kotlin (no Android imports) so it stays unit-testable without a device

## Key files
### Session + framing
- com/vayunmathur/auto/protocol/GalConnection.kt
- com/vayunmathur/auto/protocol/GalControlSession.kt
- com/vayunmathur/auto/protocol/GalTransport.kt
- com/vayunmathur/auto/protocol/StreamTransport.kt
- com/vayunmathur/auto/protocol/FrameHeader.kt
- com/vayunmathur/auto/protocol/FrameReader.kt
- com/vayunmathur/auto/protocol/FrameWriter.kt
- com/vayunmathur/auto/protocol/Fragmenter.kt
- com/vayunmathur/auto/protocol/FrameAssembler.kt
- com/vayunmathur/auto/protocol/FrameId.kt
- com/vayunmathur/auto/protocol/ChannelSendQueue.kt
- com/vayunmathur/auto/protocol/AckTracker.kt
### Negotiation + transports
- com/vayunmathur/auto/protocol/VersionNegotiation.kt
- com/vayunmathur/auto/protocol/TransportKind.kt
- com/vayunmathur/auto/protocol/TransportSelector.kt
- com/vayunmathur/auto/protocol/UsbSession.kt
- com/vayunmathur/auto/protocol/WirelessSession.kt
- com/vayunmathur/auto/protocol/ReconnectBackoff.kt
### Codecs
- com/vayunmathur/auto/protocol/VideoCodec.kt
- com/vayunmathur/auto/protocol/AudioCodec.kt
- com/vayunmathur/auto/protocol/InputCodec.kt
- com/vayunmathur/auto/protocol/SensorCodec.kt
- com/vayunmathur/auto/protocol/NavStatusCodec.kt
- com/vayunmathur/auto/protocol/MapsGuidance.kt
- com/vayunmathur/auto/protocol/MessageCodec.kt
- com/vayunmathur/auto/protocol/MessagingCodec.kt
### Security + roles
- com/vayunmathur/auto/protocol/TlsCodec.kt
- com/vayunmathur/auto/protocol/GalCredential.kt
- com/vayunmathur/auto/protocol/GalMessage.kt
- com/vayunmathur/auto/protocol/FocusArbitration.kt
- com/vayunmathur/auto/protocol/HostVsMirror.kt
- com/vayunmathur/auto/protocol/MaosRole.kt
- com/vayunmathur/auto/protocol/DisplayRoutePolicy.kt

## Verify (this module only)
```
./gradlew :auto:protocol:compileDevKotlin
./gradlew :auto:protocol:lint
```
