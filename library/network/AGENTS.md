# AGENTS.md — library/network/ (':library:network')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/network/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:network / dir: library/network/
- Package roots present: network
- Entry files: NetworkClient.kt + TrustBundle.kt (no MainActivity/Route — library)
- Deps: (none — pinned CAs ship in `assets/ca/`)
- Metadata: metadata_data/library-network.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

### Relay server (backend, outside this repo)
Relay hosts migrated from `findfamily.cc` to `api.maproj.org` (same Cloudflare FIRST_PARTY pins; `findfamily.cc` remains valid for backward compatibility). `FIRST_PARTY` bundle pins cover both hosts. Server checkout: `../location_share_server` (sibling of this repo root, `C:\Users\Vayun\Documents\code\location_share_server`). Live SSH: `ssh root@87.86.80.39`. Full details: root `AGENTS.md` → "Backend server (outside this repo)".

## Overview
**Purpose:** The ONLY network path in the repo — pinned-TLS HTTP engines + WebSocket + connectivity monitoring. Apps call `NetworkClient.init(context, TrustBundle.*)` first, then go through the engines; never raw OkHttp, never `TrustAll` (that lives in apps' dev-only code, never here).
**How to use:** `NetworkClient.init()` in `MainActivity.onCreateBeforeContent` with the narrowest bundle (`FIRST_PARTY` own relay / `STANDARD` public APIs / `EXTENDED` + `ESIM` special hosts / `SYSTEM` user-entered hosts like web browser). Requests via `HttpUrlEngine`/`PooledHttpEngine` (Rust `NativeHttpBridge`); pins in `BundledTrust` + `assets/ca/`; offline UI via `util/ConnectivityMonitor` (+ `ConnectivityCompose` banner in `:library:ui`).
**Entry points:** `NetworkClient.kt` → `TrustBundle.kt` → `HttpUrlEngine.kt` → `Urls.kt`.
**Gotchas:** `PooledHttpEngine`/`HttpUrlEngine`/`NativeHttpBridge` all exist — check which one your call site already uses before adding a new path. INTERNET permission is declared by consuming apps (or by `library/map`/`downloadservice` themselves), not here.

## Key files
- com/vayunmathur/library/network/NetworkClient.kt — init + narrowest-bundle rule
- com/vayunmathur/library/network/TrustBundle.kt
- com/vayunmathur/library/network/BundledTrust.kt
- com/vayunmathur/library/network/HttpUrlEngine.kt
- com/vayunmathur/library/network/PooledHttpEngine.kt
- com/vayunmathur/library/network/NativeHttpBridge.kt — Rust bridge
- com/vayunmathur/library/network/Urls.kt
- com/vayunmathur/library/network/WebSocketClient.kt
- com/vayunmathur/library/util/ConnectivityMonitor.kt

## Verify (this module only)
```
./gradlew :library:network:compileDevKotlin
./gradlew :library:network:lint
```
