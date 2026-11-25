# AGENTS.md — sdk/cast/ (':sdk:cast')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `sdk/cast/` + allowed shared modules. Do not root-scan.

- Gradle: :sdk:cast / dir: sdk/cast/
- Package roots present: (public cast SDK — one package)
- Entry files: CastClient.kt + CastContract.kt (no MainActivity/Route — SDK)
- Deps: (none)
- Metadata: metadata_data/sdk-cast.md (absent — SDKs have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Public SDK third-party apps use to hand media to the TV through `cast/` — client + picker + playback contracts over the `:cast:protocol` session.
**Consumers:** `music/` (`CastPlayback`), youpipe, any app handing media to `cast/tv`.
**Entry points:** `CastContract.kt` → `CastClient.kt` → `Playback.kt`.
**Gotchas:** Contract stability across sender + receiver + third-party callers — same lockstep rule as `:cast:protocol`.

## Key files
- com/vayunmathur/sdk/cast/CastContract.kt — the handoff contract
- com/vayunmathur/sdk/cast/CastClient.kt
- com/vayunmathur/sdk/cast/CastPickerContract.kt
- com/vayunmathur/sdk/cast/CastResource.kt
- com/vayunmathur/sdk/cast/Playback.kt
- com/vayunmathur/sdk/cast/Exceptions.kt

## Verify (this module only)
```
./gradlew :sdk:cast:compileDevKotlin
./gradlew :sdk:cast:lint
```
