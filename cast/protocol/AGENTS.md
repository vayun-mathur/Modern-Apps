# AGENTS.md — cast/protocol/ (':cast:protocol')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `cast/protocol/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :cast:protocol / dir: cast/protocol/
- Package roots present: (pure-Kotlin protocol library — single package, no Android roots)
- Entry files: HandshakeMessages.kt + Pairing.kt (no MainActivity/Route — library)
- Deps: :library:e2ee-p2p
- Metadata: metadata_data/cast-protocol.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Pure-Kotlin cast wire protocol shared by the `cast/` sender and `cast/tv` receiver — pairing, E2EE handshake, control framing, RTP media. No Android, no UI.
**Covers:** Pairing + handshake messages, ephemeral-TLS key schedule, secret sealing, control framing + frame assembly, streaming sessions + receiver sessions, RTP packetize/depacketize + RTCP, media proxy, codec negotiation, stream multiplexing.
**Entry points + reading order:** `Pairing.kt` → `HandshakeMessages.kt` → `KeySchedule.kt` → `StreamingSession.kt` → `RtpPacketizer.kt`/`RtpDepacketizer.kt`.
**Gotchas:**
- Sender and receiver must upgrade in lockstep — wire changes touch `cast/` + `cast/tv` together
- Crypto changes need adversarial review; test against the recorded handshake vectors if present

## Key files
### Pairing + handshake + crypto
- com/vayunmathur/cast/protocol/Pairing.kt
- com/vayunmathur/cast/protocol/HandshakeMessages.kt
- com/vayunmathur/cast/protocol/EphemeralTls.kt
- com/vayunmathur/cast/protocol/KeySchedule.kt
- com/vayunmathur/cast/protocol/Crypto.kt
- com/vayunmathur/cast/protocol/SecretSealing.kt
### Framing + sessions
- com/vayunmathur/cast/protocol/ControlFraming.kt
- com/vayunmathur/cast/protocol/FrameAssembler.kt
- com/vayunmathur/cast/protocol/FrameId.kt
- com/vayunmathur/cast/protocol/StreamingSession.kt
- com/vayunmathur/cast/protocol/ReceiverSession.kt
- com/vayunmathur/cast/protocol/Streams.kt
### Media transport
- com/vayunmathur/cast/protocol/RtpPacketizer.kt
- com/vayunmathur/cast/protocol/RtpDepacketizer.kt
- com/vayunmathur/cast/protocol/Rtcp.kt
- com/vayunmathur/cast/protocol/MediaProxy.kt
- com/vayunmathur/cast/protocol/CodecNegotiation.kt

## Verify (this module only)
```
./gradlew :cast:protocol:compileDevKotlin
./gradlew :cast:protocol:lint
```
