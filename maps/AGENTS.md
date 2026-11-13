# AGENTS.md — maps/ (':maps')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `maps/` + allowed shared modules. Do not root-scan.

_Install: ./install maps (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :maps / dir: maps/
- Package roots present: ui, data, intents
- Entry files: MainActivity.kt (no Route.kt — `Route` + `Navigation` are inline)
- Deps: :library:map, :library:image, :library:network, :library:downloadservice
- Metadata: metadata_data/maps.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** A fast open-source map + navigation app on OpenStreetMap, with all routing on device. Internet only for new tiles, search, traffic, and transit delays.
**Key features:**
- On-device routing (drive/walk/bike/transit) over a single packed `.mamaps` archive, downloaded once on first launch; viewed areas keep working offline
- Turn-by-turn navigation with voice (TTS), lane guidance, speed-limit widget, and elevation; Android Auto via `car/`
- Place details, saved places, transit departure boards, Street View, parking, and POI search (offline index from the archive; ratings/review counts/summaries are archive-only, `poi_attrs` keys 10-12)
- Google is used for exactly two opt-in features (Settings → Google services): Street View and non-review place details (photos, hours, phone/website, popular times) via `GooglePoiDataSource`. No Google search, traffic, POI discovery or reviews
- Handles external `geo:` / `google.navigation:` / Google-Maps links from other apps
**Architecture / data-flow:** `ui/` map surface + sheets over per-concern `util/` ViewModels (`SelectedFeatureViewModel`, `MapsSearchViewModel`, `SavedPlacesViewModel`, …) → `data/` stores/parsers + `util/OfflineRouter*` routing against the memory-mapped archive; guidance runs in the `util/NavigationService` foreground service via `NavigationSessionManager`. `onCreateBeforeContent`: `NetworkClient.init(FIRST_PARTY)` → delete the legacy `world_z0-6.pmtiles` → route the cold-start deep link; `Content()` gates on the `.mamaps` download before the map renders.
**Entry points + reading order:** `Route` (`MapPage` / `SavedPlacesPage` / `SettingsPage`, inline) → `MainActivity.kt` (`handleIntent`, `navigateTo`) → `ui/MapPage.kt` → `util/SelectedFeatureViewModel.kt` → `util/OfflineRouterRoadRoutes.kt`.
**Gotchas:**
- The `.mamaps` gate hosts app start — the archive URL must be live before a version ships; `adb push` the file to sideload a locally built archive
- `singleTop` warm-start geo/navigation links arrive via `onNewIntent`, not a fresh `onCreate`
- The map itself never splits panes (list/detail metadata only for settings/saved); its expanded side panel is separate inside `MapPage`
- Extra non-conforming dirs (`car/`, `ipc/`, `util/`) hold real code — closed-roots migration pending

## Key files
### Root — entry + inline navigation
- com/vayunmathur/maps/MainActivity.kt — Route, Navigation, geo-link handling, archive gate
### car/ — Android Auto surface
- com/vayunmathur/maps/car/MapsCarAppService.kt
- com/vayunmathur/maps/car/MapsSession.kt
- com/vayunmathur/maps/car/NavMapScreen.kt
- com/vayunmathur/maps/car/CarMapRenderer.kt
- com/vayunmathur/maps/car/CarManeuvers.kt
- com/vayunmathur/maps/car/CarSearchScreen.kt
### data/ — prefs, places, POI + transit sources
- com/vayunmathur/maps/data/MapPreferences.kt
- com/vayunmathur/maps/data/MapLinkParser.kt — external geo/maps URL parsing
- com/vayunmathur/maps/data/GeoJson.kt
- com/vayunmathur/maps/data/SpecificFeature.kt
- com/vayunmathur/maps/data/OpeningHours.kt
- com/vayunmathur/maps/data/OsmMaxspeed.kt
- com/vayunmathur/maps/data/SavedPlace.kt
- com/vayunmathur/maps/data/SavedPlaceStore.kt
- com/vayunmathur/maps/data/RecentSearchStore.kt
- com/vayunmathur/maps/data/ParkingStore.kt
- com/vayunmathur/maps/data/google/GooglePoiDataSource.kt — Google place details (photos/hours/phone; no ratings/reviews)
- com/vayunmathur/maps/data/google/GooglePoiModels.kt
- com/vayunmathur/maps/data/PoiPin.kt — offline map POI pin
- com/vayunmathur/maps/data/google/GoogleResponse.kt
- com/vayunmathur/maps/data/google/StreetViewDataSource.kt
- com/vayunmathur/maps/data/google/StreetViewModels.kt
- com/vayunmathur/maps/data/transit/TransitousDataSource.kt
- com/vayunmathur/maps/data/transit/TransitousModels.kt
### intents/ — assistant IPC
- com/vayunmathur/maps/intents/DirectionsIntent.kt
### ipc/ — cross-app estimate/lookup clients
- com/vayunmathur/maps/ipc/RideEstimateClient.kt — launches taxi intents
- com/vayunmathur/maps/ipc/OrderLookupClient.kt — launches fooddelivery intents
- com/vayunmathur/maps/ipc/FamilyLocationClient.kt
- com/vayunmathur/maps/ipc/FamilyLocationProtocol.kt
- com/vayunmathur/maps/ipc/IntentLaunch.kt
### ui/ — pages, sheets, layers
- com/vayunmathur/maps/ui/MapPage.kt
- com/vayunmathur/maps/ui/MapPageScope.kt
- com/vayunmathur/maps/ui/MapContentBox.kt
- com/vayunmathur/maps/ui/MapSearchActions.kt
- com/vayunmathur/maps/ui/MapSheets.kt
- com/vayunmathur/maps/ui/MapSheetEffects.kt
- com/vayunmathur/maps/ui/MapSidePanel.kt
- com/vayunmathur/maps/ui/MapWideHost.kt
- com/vayunmathur/maps/ui/MapWideLayout.kt
- com/vayunmathur/maps/ui/BottomSheetContent.kt
- com/vayunmathur/maps/ui/BottomSheetHeader.kt
- com/vayunmathur/maps/ui/SearchSheet.kt
- com/vayunmathur/maps/ui/PlaceSheet.kt
- com/vayunmathur/maps/ui/PlaceSheetHeader.kt
- com/vayunmathur/maps/ui/PlaceSheetHours.kt
- com/vayunmathur/maps/ui/PlaceActionRow.kt
- com/vayunmathur/maps/ui/ContactAddressButton.kt
- com/vayunmathur/maps/ui/SearchResultLayer.kt
- com/vayunmathur/maps/ui/RouteSheet.kt
- com/vayunmathur/maps/ui/RouteSheetTabs.kt
- com/vayunmathur/maps/ui/TripSheet.kt
- com/vayunmathur/maps/ui/NavigationOverlay.kt
- com/vayunmathur/maps/ui/DeparturesSheet.kt
- com/vayunmathur/maps/ui/DepartureTime.kt
- com/vayunmathur/maps/ui/CategoryChips.kt
- com/vayunmathur/maps/ui/LayersButton.kt
- com/vayunmathur/maps/ui/LayersSheet.kt
- com/vayunmathur/maps/ui/CompassButton.kt
- com/vayunmathur/maps/ui/MapScaleBar.kt
- com/vayunmathur/maps/ui/VoiceSearchButton.kt
- com/vayunmathur/maps/ui/SavedPlacesPage.kt
- com/vayunmathur/maps/ui/SavedPlacesLayer.kt
- com/vayunmathur/maps/ui/FamilyLocationLayer.kt
- com/vayunmathur/maps/ui/ParkingLayer.kt
- com/vayunmathur/maps/ui/ParkingSheet.kt
- com/vayunmathur/maps/ui/RoadsLayer.kt
- com/vayunmathur/maps/ui/SafetyLayer.kt
- com/vayunmathur/maps/ui/SatelliteLayer.kt
- com/vayunmathur/maps/ui/TransitStopsLayer.kt
- com/vayunmathur/maps/ui/PoiEnrichment.kt
- com/vayunmathur/maps/ui/RestaurantItem.kt
- com/vayunmathur/maps/ui/LineBadge.kt
- com/vayunmathur/maps/ui/ManeuverIcon.kt
### ui/map/ — surface, camera, overlays
- com/vayunmathur/maps/ui/map/MapSurface.kt
- com/vayunmathur/maps/ui/map/MapSurfaceHelpers.kt
- com/vayunmathur/maps/ui/map/MapCamera.kt
- com/vayunmathur/maps/ui/map/MapChrome.kt
- com/vayunmathur/maps/ui/map/MapChromeState.kt
- com/vayunmathur/maps/ui/map/MapFabStack.kt
- com/vayunmathur/maps/ui/map/MapLayers.kt
- com/vayunmathur/maps/ui/map/MapOverlays.kt
- com/vayunmathur/maps/ui/map/MapFeaturePicker.kt
- com/vayunmathur/maps/ui/map/MapPinFeatures.kt
- com/vayunmathur/maps/ui/map/RouteOverlayBuilder.kt
- com/vayunmathur/maps/ui/map/RailLines.kt
- com/vayunmathur/maps/ui/map/TransitVehicles.kt
- com/vayunmathur/maps/ui/map/TrafficPrefetchEffect.kt
- com/vayunmathur/maps/ui/map/WaypointList.kt
### ui/nav/ — guidance widgets
- com/vayunmathur/maps/ui/nav/ManeuverBanner.kt
- com/vayunmathur/maps/ui/nav/LaneGuidance.kt
- com/vayunmathur/maps/ui/nav/SpeedWidget.kt
- com/vayunmathur/maps/ui/nav/RouteShield.kt
- com/vayunmathur/maps/ui/nav/ArrivalSummary.kt
- com/vayunmathur/maps/ui/nav/StepsSheet.kt
- com/vayunmathur/maps/ui/nav/ElevationChart.kt
### ui/streetview/ + ui/settings/ + ui/theme/
- com/vayunmathur/maps/ui/streetview/StreetViewScreen.kt
- com/vayunmathur/maps/ui/streetview/StreetViewPegman.kt
- com/vayunmathur/maps/ui/streetview/StreetViewArrows.kt
- com/vayunmathur/maps/ui/settings/MapSettingsPage.kt
- com/vayunmathur/maps/ui/theme/MapTokens.kt
- com/vayunmathur/maps/ui/theme/BasemapPalette.kt
- com/vayunmathur/maps/ui/theme/MapChromeMetrics.kt
### util/ — ViewModels, offline router, navigation service (migration pending)
- com/vayunmathur/maps/util/SelectedFeatureViewModel.kt
- com/vayunmathur/maps/util/MapsSearchViewModel.kt
- com/vayunmathur/maps/util/MapsUiContract.kt
- com/vayunmathur/maps/util/SavedPlacesViewModel.kt
- com/vayunmathur/maps/util/MapSettingsViewModel.kt
- com/vayunmathur/maps/util/PlacePanelState.kt
- com/vayunmathur/maps/util/PoiMapViewModel.kt — map POI pins from the offline index
- com/vayunmathur/maps/util/TransitStopsViewModel.kt
- com/vayunmathur/maps/util/TransitClock.kt
- com/vayunmathur/maps/util/ParkingViewModel.kt
- com/vayunmathur/maps/util/MapTileCache.kt — `.mamaps` archive access
- com/vayunmathur/maps/util/OfflineRouter.kt
- com/vayunmathur/maps/util/OfflineRouterRoadRoutes.kt
- com/vayunmathur/maps/util/OfflineRouterRouteBuilder.kt
- com/vayunmathur/maps/util/OfflineRouterTraffic.kt
- com/vayunmathur/maps/util/OfflineRouterTransit.kt
- com/vayunmathur/maps/util/RouteService.kt
- com/vayunmathur/maps/util/NavigationService.kt — foreground guidance service
- com/vayunmathur/maps/util/NavigationSessionManager.kt
- com/vayunmathur/maps/util/NavigationTts.kt
- com/vayunmathur/maps/util/PolylineProgress.kt
- com/vayunmathur/maps/util/GTFSProvider.kt
- com/vayunmathur/maps/util/PoiIndex.kt
- com/vayunmathur/maps/util/PoiIndexAttrs.kt
- com/vayunmathur/maps/util/PoiIndexSpatial.kt
- com/vayunmathur/maps/util/PoiIndexWords.kt
- com/vayunmathur/maps/util/PoiIndexRegion.kt — `poi_region` (kind 15) boundary links
- com/vayunmathur/maps/util/PoiIndexAdmin.kt — `poi_admin` (kind 16) hierarchy; search-row locality ("City, State, Country")
- com/vayunmathur/maps/util/PoiSearchEngine.kt — indexed name search (ordinal scans + grid join, blended text/distance rank)
- com/vayunmathur/maps/util/PoiSearchHits.kt — ordinal hit table for the search engine
- com/vayunmathur/maps/util/PoiArchive.kt — POI sections out of the archive (per-section mmaps; planet is ~80 GB)
- com/vayunmathur/maps/util/PoiCategories.kt
- com/vayunmathur/maps/util/SpatialKey.kt
- com/vayunmathur/maps/util/RegionalUnits.kt
- com/vayunmathur/maps/util/ItineraryWording.kt
- com/vayunmathur/maps/util/Wikidata.kt
- com/vayunmathur/maps/util/CameraBounds.kt
- com/vayunmathur/maps/util/ContactLookup.kt
- com/vayunmathur/maps/util/FrameworkLocationManager.kt

## Verify (this module only)
```
./gradlew :maps:compileDevKotlin
./gradlew :maps:lint
./gradlew :maps:checkMetadata
```
