# AGENTS.md — astronomy/ (':astronomy')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `astronomy/` + allowed shared modules. Do not root-scan.

_Install: ./install astronomy (dev by default)._
- Gradle: :astronomy / dir: astronomy/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: (none declared — bundled catalogs, Rust ephemeris)
- Metadata: metadata_data/astronomy.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** Offline AR night-sky viewer — sensor-tracked sky map, camera passthrough, time scrubber, bundled star/planet catalogs, rise/set search. 100% offline.
**Key features:** `ui/SkyCanvas` + grid/art layers over `domain/projection/` (stereographic) fed by `domain/engine/` calculators (solar/lunar/planetary/rise-set/time) with a Rust core (`domain/AstronomyNative`); catalogs in `data/` validated at build time by `scripts/` generators.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/SkyMapPage.kt` → `platform/AstronomyViewModel.kt` → `domain/engine/TimeEngine.kt`.
**Gotchas:** Catalog data is generated (`scripts/` DB generators) — never hand-edit; sensor + location drive the sky, test with movement, not static.

## Key files
### Root — entry + navigation
- com/vayunmathur/astronomy/MainActivity.kt
- com/vayunmathur/astronomy/Navigation.kt
- com/vayunmathur/astronomy/Route.kt
### data/ — bundled catalogs
- com/vayunmathur/astronomy/data/CatalogRepository.kt
- com/vayunmathur/astronomy/data/model/Models.kt
### domain/ — ephemeris + projection (Rust core + Kotlin math)
- com/vayunmathur/astronomy/domain/AstronomyNative.kt — Rust bridge
- com/vayunmathur/astronomy/domain/engine/TimeEngine.kt
- com/vayunmathur/astronomy/domain/engine/CoordinateTransforms.kt
- com/vayunmathur/astronomy/domain/engine/SolarCalculator.kt
- com/vayunmathur/astronomy/domain/engine/LunarCalculator.kt
- com/vayunmathur/astronomy/domain/engine/PlanetaryCalculator.kt
- com/vayunmathur/astronomy/domain/engine/RiseSetCalculator.kt
- com/vayunmathur/astronomy/domain/projection/SkyProjection.kt
- com/vayunmathur/astronomy/domain/projection/StereographicProjection.kt
- com/vayunmathur/astronomy/domain/projection/ViewState.kt
### platform/ — ViewModel + location
- com/vayunmathur/astronomy/platform/AstronomyViewModel.kt
- com/vayunmathur/astronomy/platform/AstronomyUiContract.kt
- com/vayunmathur/astronomy/platform/sensor/LocationProvider.kt
### ui/ — sky map + detail + search
- com/vayunmathur/astronomy/ui/SkyMapPage.kt
- com/vayunmathur/astronomy/ui/SkyCanvas.kt
- com/vayunmathur/astronomy/ui/SkyGrid.kt
- com/vayunmathur/astronomy/ui/SkyArt.kt
- com/vayunmathur/astronomy/ui/ObjectDetailPage.kt
- com/vayunmathur/astronomy/ui/SearchPage.kt
- com/vayunmathur/astronomy/ui/SettingsPage.kt
- com/vayunmathur/astronomy/ui/components/CameraBackground.kt

## Verify (this module only)
```
./gradlew :astronomy:compileDevKotlin
./gradlew :astronomy:lint
./gradlew :astronomy:checkMetadata
```
