# AGENTS.md — networklocation/ (':networklocation')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `networklocation/` + allowed shared modules. Do not root-scan.

_Install: ./install networklocation (dev by default)._
- Gradle: :networklocation / dir: networklocation/
- Package roots present: provider
- Entry files: MainActivity.kt (database-setup UI) + provider/LocationProviderImpl.kt (system provider entry)
- Deps: :library:locationprovider, :library:downloadservice
- Metadata: metadata_data/networklocation.md (present)
- Rust: yes / screenshotTest: no

## Overview
**Purpose:** Privileged system network-location + geocoder provider — Wi-Fi/cell fixes without GPS and offline geocoding, solved on-device in Rust. Beacons never leave the phone; internet only for one-time offline-DB downloads.
**Key features:** `provider/LocationProviderImpl` serves system fixes from `OfflineBeaconStore` (timed-LRU wifi/cell caches) over `NetworkLocationService`; `GeocodeService` resolves offline; `MainActivity` manages offline-DB state (`OfflineDatabases`).
**Entry points:** `provider/LocationProviderImpl.kt` → `provider/OfflineBeaconStore.kt` → `NetworkLocationService.kt` → `NetworkLocationNative.kt` (Rust solver).
**Gotchas:**
- `cache/`, `cell/`, `wifi/` are NOT closed package roots — don't add more files there
- System-provider registration is signature/role-gated — sideloaded builds won't serve fixes
- Beacon privacy is the product — never log or upload identifiers/positions

## Key files
### Root + provider/ — entries
- com/vayunmathur/networklocation/MainActivity.kt — offline-DB setup UI
- com/vayunmathur/networklocation/NetworkLocationService.kt
- com/vayunmathur/networklocation/GeocodeService.kt
- com/vayunmathur/networklocation/provider/LocationProviderImpl.kt — system entry
- com/vayunmathur/networklocation/provider/OfflineBeaconStore.kt
- com/vayunmathur/networklocation/provider/DatabaseStatusProvider.kt
- com/vayunmathur/networklocation/provider/DatabaseStatusContract.kt
- com/vayunmathur/networklocation/provider/LocationReportingTask.kt
### Root — solver + stores (Rust bridges)
- com/vayunmathur/networklocation/NetworkLocationNative.kt
- com/vayunmathur/networklocation/GeocoderNative.kt
- com/vayunmathur/networklocation/WpsStoreNative.kt
- com/vayunmathur/networklocation/OfflineDatabases.kt
- com/vayunmathur/networklocation/OfflineDatabaseSection.kt
- com/vayunmathur/networklocation/PositioningData.kt
- com/vayunmathur/networklocation/BeaconKeys.kt
### cache/ + cell/ + wifi/ — beacon pipeline
- com/vayunmathur/networklocation/cache/BeaconCache.kt
- com/vayunmathur/networklocation/cache/TimedLruCache.kt
- com/vayunmathur/networklocation/cell/NearbyCells.kt
- com/vayunmathur/networklocation/wifi/NearbyWifi.kt

## Verify (this module only)
```
./gradlew :networklocation:compileDevKotlin
./gradlew :networklocation:lint
./gradlew :networklocation:checkMetadata
```
