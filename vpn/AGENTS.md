# AGENTS.md — vpn/ (':vpn')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `vpn/` + allowed shared modules. Do not root-scan.

_Install: ./install vpn (dev by default)._
- Gradle: :vpn / dir: vpn/
- Package roots present: ui, data, platform, service
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:room, :library:network
- Metadata: metadata_data/vpn.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** Offline-first WireGuard VPN client — user-imported configs, per-app bypass, connection logging. Tunnel crypto runs in a native Rust library.
**Key features:**
- WireGuard configs (manual import) with connect/disconnect via `service/VpnTunnelService`
- Per-app bypass list (`platform/BypassList` + `BypassListPage`); SNI parsing + packet inspection + DNS cache in `service/`
- Connection log persisted in Room; Material You UI
**Architecture / data-flow:** `ui/` config list/detail + logging → `platform/VpnViewModel` → `data/VpnRepository` over Room (`VpnConfig`, connection-log entities); tunnel control goes ViewModel → `service/VpnTunnelService` → Rust core via `util/VpnNative` JNI bridge; `service/ConnectionTracker` feeds the log.
**Entry points + reading order:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/ConfigListPage.kt` → `platform/VpnViewModel.kt` → `service/VpnTunnelService.kt` → `util/VpnNative.kt`.
**Gotchas:**
- `util/` is NOT a closed package root — don't add more files there
- Tunnel state lives in the service + Rust core — UI reflects, never drives; reconnect races come from driving twice
- VPN permission + always-on/blocking modes are system-mediated; test grant/revoke, not just in-app toggles

## Key files
### Root — entry + navigation
- com/vayunmathur/vpn/MainActivity.kt
- com/vayunmathur/vpn/Navigation.kt
- com/vayunmathur/vpn/Route.kt
### data/ — configs + connection log (Room)
- com/vayunmathur/vpn/data/VpnDatabase.kt
- com/vayunmathur/vpn/data/VpnRepository.kt
- com/vayunmathur/vpn/data/VpnConfig.kt
- com/vayunmathur/vpn/data/ConnectionLogDao.kt
- com/vayunmathur/vpn/data/ConnectionLogEntity.kt
### platform/ — ViewModel + bypass
- com/vayunmathur/vpn/platform/VpnViewModel.kt
- com/vayunmathur/vpn/platform/BypassList.kt
### service/ — tunnel, inspection, tracking
- com/vayunmathur/vpn/service/VpnTunnelService.kt
- com/vayunmathur/vpn/service/ConnectionTracker.kt
- com/vayunmathur/vpn/service/PacketInspector.kt
- com/vayunmathur/vpn/service/SniParser.kt
- com/vayunmathur/vpn/service/DnsCache.kt
- com/vayunmathur/vpn/service/AppResolver.kt
### util/ — Rust bridge
- com/vayunmathur/vpn/util/VpnNative.kt — JNI bridge into the WireGuard core
### ui/ + ui/components/ — configs, bypass, logging
- com/vayunmathur/vpn/ui/VpnTabs.kt
- com/vayunmathur/vpn/ui/ConfigListPage.kt
- com/vayunmathur/vpn/ui/ConfigListContent.kt
- com/vayunmathur/vpn/ui/ConfigDetailPage.kt
- com/vayunmathur/vpn/ui/BypassListPage.kt
- com/vayunmathur/vpn/ui/LoggingPage.kt
- com/vayunmathur/vpn/ui/LoggingContent.kt
- com/vayunmathur/vpn/ui/SettingsPage.kt
- com/vayunmathur/vpn/ui/components/BypassHelpers.kt

## Verify (this module only)
```
./gradlew :vpn:compileDevKotlin
./gradlew :vpn:lint
./gradlew :vpn:checkMetadata
```
