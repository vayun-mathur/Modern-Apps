# AGENTS.md — library/everysync-core/ (':library:everysync-core')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/everysync-core/` + allowed shared modules. Do not root-scan.

- Gradle: :library:everysync-core / dir: library/everysync-core/
- Targets: android + jvm (desktop) + iosArm64 + iosSimulatorArm64 (KMP plugin version == Kotlin version)
- Package: `com.vayunmathur.everysync.core` (commonMain) + platform actuals
- Entry files: `commonMain/sync/RelayMerge.kt`, `commonMain/data/RemoteModels.kt` (Phase 1A moves)
- Deps: kotlinx-coroutines-core, kotlinx-serialization-json, kotlinx-datetime (common only)
- Metadata: none (libraries have none by design)
- Rust: no (binds the `:library:e2ee-p2p` crate via JNI/JNA/cinterop — no new Rust)

## Overview
**Purpose:** Shared KMP sync brain for the EverySync E2EE relay protocol — merge, vault policy, DTOs, formats. No Android API in commonMain; platform coupling behind expect/actual (`RelayTransport`, `SecureStore`, `SyncScheduler`, `SyncSink`, `AesGcm`, `SyncDigest`).
**Invariants (verbatim from the Android impl):** a failed pull returns null = abort, never empty; only explicit tombstones delete; vault keys never user-visible. Relay contract: `https://api.maproj.org/personal` + `wss://api.maproj.org/personal/ws`, `POST /register|/getkey|/append|/pull`.
**Gotchas:** never depend on `:library:log` / `:library:network` / `:library:room` from commonMain (Android-only, no common variant); FIRST_PARTY pinning DERs are copied by `syncCaResources` into `src/commonMain/resources/ca/` (gitignored build output) and loaded at runtime by the OkHttp actuals.

### Relay server (backend, outside this repo)
Relay contract host: `https://api.maproj.org/personal` (Cloudflare FIRST_PARTY pins). Server checkout: `../location_share_server` (sibling of this repo root, `C:\Users\Vayun\Documents\code\location_share_server`). Live SSH: `ssh root@87.86.80.39`. Full details: root `AGENTS.md` → "Backend server (outside this repo)".

## Verify (this module only)
```
./gradlew :library:everysync-core:compileKotlinJvm
./gradlew :library:everysync-core:lint
```

## Cross-platform
Program status: root `CROSS_PLATFORM_STATUS.md`. System-sink interfaces: `src/commonMain/kotlin/com/vayunmathur/everysync/core/sinks/SystemSinks.kt`. Shells: `desktop/` (EDS/Windows), `apple/` (EventKit/CNContactStore).
