# AGENTS.md — sdk/controller/ (':sdk:controller')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `sdk/controller/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-17 -->
- Gradle: :sdk:controller / dir: sdk/controller/
- Package roots present: (public controller SDK — one package)
- Entry files: ControllerContract.kt + ControllerModels.kt (no MainActivity/Route — SDK)
- Deps: :library:e2ee-p2p (Hkdf key schedule + E2ee AES-GCM; PQC sealing stays in the hosting app)
- UI: none — headless by design. The Compose pad screen lives in the hosting app (`:games:hub` `ui/screens/ControllerPad.kt`, #884)
- Metadata: metadata_data/sdk-controller.md (absent — SDKs have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Public SDK for phone-as-controller play on TV — the contract side; the TV game's
`ControllerHost` is the sink side. Generic by design: buttons are a bitmask, sticks are normalized
axes, and nothing here names a kart. A future TV game reuses this SDK as-is.
**Consumers:** Kart TV (`ControllerHost`) + hub-hosted `ControllerPad` (`ControllerClient`); any
future TV game.
**Entry points:** `ControllerContract.kt` → `ControllerModels.kt` → `ControllerHost.kt` /
`ControllerClient.kt`.
**Gotchas:** Contract changes must stay compatible with host + client simultaneously — version the
contract, never break it silently. `UDP later is a transport swap`: seq/replay/rate-limits are
baked in so only `ControllerSocket` changes underneath.

## Key files
- com/vayunmathur/sdk/controller/ControllerContract.kt — version, service type, cadence, limits
- com/vayunmathur/sdk/controller/ControllerModels.kt — input/buttons/axes/lobby/join/rumble messages
- com/vayunmathur/sdk/controller/ControllerFraming.kt — int32be framing + AES-GCM codec
- com/vayunmathur/sdk/controller/ControllerKeys.kt — HKDF key schedule, pairing gate, transcript
- com/vayunmathur/sdk/controller/ControllerSocket.kt — TCP socket, both ends
- com/vayunmathur/sdk/controller/ControllerDiscovery.kt — mDNS browse (phone side)
- com/vayunmathur/sdk/controller/ControllerAdvertiser.kt — mDNS register (TV side)
- com/vayunmathur/sdk/controller/ControllerHost.kt — N player slots, replay window, rate limit
- com/vayunmathur/sdk/controller/ControllerInputGate.kt — per-player replay + rate-limit policy (JVM-testable)
- com/vayunmathur/sdk/controller/ControllerClient.kt — connect/pair/join/input emit

## Verify (this module only)
```
/gradlew :sdk:controller:compileReleaseKotlin
./gradlew :sdk:controller:lint
./gradlew :sdk:controller:testDebugUnitTest
```
