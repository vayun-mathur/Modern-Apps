# AGENTS.md — sdk/openassistant/ (':sdk:openassistant')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `sdk/openassistant/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :sdk:openassistant / dir: sdk/openassistant/
- Package roots present: (public assistant SDK — one package)
- Entry files: OpenAssistant.kt (no MainActivity/Route — SDK)
- Deps: (none — SecureResultReceiver is owned by this module, #884)
- Metadata: metadata_data/sdk-openassistant.md (absent — SDKs have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Public SDK apps expose tool-calling surfaces to `openassistant/` through — the counterpart to per-app `intents/` (notes/email/calendar/taxi/weather/fooddelivery expose `*Intent`s; this SDK is the assistant-facing client).
**Consumers:** `openassistant/` (tool calls out); any app exposing assistant tools (calls in).
**Entry points:** `OpenAssistant.kt` → `Exceptions.kt`.
**Gotchas:** Tool schemas are a compatibility surface on both ends — changes need assistant + provider updated together.

## Key files
- com/vayunmathur/sdk/openassistant/OpenAssistant.kt
- com/vayunmathur/sdk/openassistant/Exceptions.kt

## Verify (this module only)
```
./gradlew :sdk:openassistant:compileDevKotlin
./gradlew :sdk:openassistant:lint
```
