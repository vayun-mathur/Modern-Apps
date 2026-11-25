# AGENTS.md — uniflix/ (':uniflix')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `uniflix/` + allowed shared modules. Do not root-scan.

_Install: ./install uniflix (dev by default)._
- Gradle: :uniflix / dir: uniflix/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:network, :library:image, :library:room, media3 exoplayer/hls, datastore-preferences, quickjs-kt, chicory-runtime
- Metadata: metadata_data/uniflix.md (present)
- Rust: no / screenshotTest: no (previews pending)

## Overview
**Purpose:** TMDB catalog browser (trending/top-rated/search, watchlist, lists,
resume) with playback supplied by user-installed Stremio-style URL addons. Ships
with no provider and no secrets — with zero addons the app is catalog-only and
Watch/Detail link to the addon manager.
**Key features:**
- Addon manager: add-by-URL, enable/disable, default star, priority ordering, per-addon settings, error badges
- `StreamProvider` is the single player-facing contract; `PluginRegistry` resolves default-first then priority fallbacks
- Pure-HTTP addon protocol subset: `manifest.json` + `GET {base}/stream/{type}/{id}.json` with `tmdb:` ids
- Installable JS plugins (`.js` import): QuickJS runtime + generic host (`__ufetch` via NetworkClient, `__uwasm_*` via Chicory, `__uaesgcm_open`, `__urand`); sync-only contract, WASM travels inside the plugin file
**Architecture / data-flow:** `ui/` (Home→Detail→Watch, AddonsScreen/AddonDetailScreen) → `platform/` (`DetailViewModel` resolves via `RegistryFactory.AllInstalled`, `AddonsViewModel` owns install/order toggles + `addJsPlugin`) -> (`data/` (`AddonStreamProvider` HTTP impl, `JsStreamProvider`/`JsPluginEngine` JS impl, `UniflixRepository` over Room v3 `AddonEntity` with `kind`/`jsSource`) with pure `domain/` (`AddonManifest` validation, `JsPluginManifest` parsing, `PluginRegistry` ordering). `Navigation`: TMDB-key onboarding gate (`reset()` not `add()`); `Addons` + `AddonDetail(dbId)` routes.
**Entry points + reading order:** `Route` (`Onboarding` / `Home` / `Addons` / `Detail` / `Watch`) → `MainActivity.kt` → `Navigation.kt` → `platform/AddonsViewModel.kt` + `platform/DetailViewModel.kt` → `domain/PluginRegistry.kt` + `data/AddonStreamProvider.kt`.
**Gotchas:**
- Manifest `resources` accepts Stremio string-or-object forms; only `stream` for movie/series validates
- URL addons enforce HTTPS except loopback/private (dev); remote `http://` is rejected as `InsecureUrl`
- `UniflixDatabase` is v3, with `Migration(1,2)` adding `AddonEntity` and `Migration(2,3)` adding `kind`/`jsSource`; default addon id lives in DataStore, ordering in Room priority
- Private `personal/uniflix_strem/` keeps the native Strem provider + hardcoded TMDB key — never copy those into `uniflix/`

## Key files
### Root — entry + navigation
- com/vayunmathur/uniflix/MainActivity.kt
- com/vayunmathur/uniflix/Navigation.kt
- com/vayunmathur/uniflix/Route.kt
### domain/ — pure addon protocol + resolution order (no Android)
- com/vayunmathur/uniflix/domain/StreamProvider.kt — player contract both plugin kinds implement
- com/vayunmathur/uniflix/domain/PluginRegistry.kt — default-first, priority fallback ordering
- com/vayunmathur/uniflix/domain/AddonManifest.kt — manifest parse + validate
- com/vayunmathur/uniflix/domain/AddonModels.kt — tmdb: id mapping, stream→VideoSource
- com/vayunmathur/uniflix/domain/JsPluginManifest.kt — JS plugin header + wasm section parse
### data/ — HTTP provider, JS engine, Room, prefs
- com/vayunmathur/uniflix/data/AddonStreamProvider.kt — URL addon as StreamProvider
- com/vayunmathur/uniflix/data/JsPluginEngine.kt — QuickJS + Chicory host, sync-only contract
- com/vayunmathur/uniflix/data/JsStreamProvider.kt — JS plugin as StreamProvider
- com/vayunmathur/uniflix/data/AddonEntity.kt — installed-addon entity + DAO
- com/vayunmathur/uniflix/data/UniflixDatabase.kt — Room v3 + Migration(1,2) + Migration(2,3)
- com/vayunmathur/uniflix/data/UniflixRepository.kt — singleton incl. addon flows
- com/vayunmathur/uniflix/data/AddonPrefs.kt — default addon id (DataStore)
- com/vayunmathur/uniflix/data/TmdbKeyStore.kt — user key only, no default
### platform/ — ViewModels
- com/vayunmathur/uniflix/platform/DetailViewModel.kt — registry injection + RegistryFactory
- com/vayunmathur/uniflix/platform/AddonsViewModel.kt — manager state
### ui/ — catalog + manager
- com/vayunmathur/uniflix/ui/AddonsScreen.kt — manager list
- com/vayunmathur/uniflix/ui/AddonDetailScreen.kt — per-addon settings
- com/vayunmathur/uniflix/ui/dialogs/AddAddonDialog.kt — add-by-URL

## Verify (this module only)
```
./gradlew :uniflix:compileDevKotlin
./gradlew :uniflix:lint
./gradlew :uniflix:checkMetadata
```
