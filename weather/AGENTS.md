# AGENTS.md — weather/ (':weather')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `weather/` + allowed shared modules. Do not root-scan.

_Install: ./install weather (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :weather / dir: weather/
- Package roots present: ui, data, domain, platform, network, intents, widget
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:network, :library:widgets, :library:map, :library:room
- Metadata: metadata_data/weather.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** Material You weather — Open-Meteo hourly/daily forecasts, air-quality/UV/pollen/humidity/pressure/wind/visibility blocks, map layers, Glance widgets, assistant intents.
**Key features:**
- Saved locations + device location (reverse-geocoded) with Room-cached forecasts refreshed by `WeatherRefreshWorker`
- Metric blocks + graphs per location; Open-Meteo vector map tiles via the Rust core (`domain/map/OmTilesNative`)
- Two Glance widgets (standard + blob); `GetWeather`/`GetWeatherByName` assistant intents
**Architecture / data-flow:** `ui/` home/map/locations → `platform/WeatherViewModel` (+ `WeatherUiContract`) → `data/WeatherRepository` over Room + `network/WeatherApi` DTOs; `domain/` holds pure unit/metric/WMO formatting + map metadata; widgets read the same repository snapshot.
**Entry points + reading order:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/HomePage.kt` → `platform/WeatherViewModel.kt` → `data/WeatherRepository.kt` → `network/WeatherApi.kt`.
**Gotchas:**
- Unit system follows the device locale (`platform/SystemUnits`) — format in `domain/`, never in `ui/`
- Map tiles are native-rendered Open-Meteo domains; tile bugs live in Rust or `domain/map/`, not the Compose layer
- Widgets must render from cache alone — never network on the widget path

## Key files
### Root — entry + navigation
- com/vayunmathur/weather/MainActivity.kt
- com/vayunmathur/weather/Navigation.kt
- com/vayunmathur/weather/Route.kt
### data/ — cache + repository + refresh
- com/vayunmathur/weather/data/WeatherDatabase.kt
- com/vayunmathur/weather/data/WeatherRepository.kt
- com/vayunmathur/weather/data/WeatherDao.kt
- com/vayunmathur/weather/data/WeatherCache.kt
- com/vayunmathur/weather/data/WeatherCacheStore.kt
- com/vayunmathur/weather/data/WeatherRefreshWorker.kt
- com/vayunmathur/weather/data/SavedLocation.kt
### domain/ + domain/map/ — pure formatting + map metadata (no Android)
- com/vayunmathur/weather/domain/WmoCode.kt
- com/vayunmathur/weather/domain/UnitFormat.kt
- com/vayunmathur/weather/domain/DateTimeFormat.kt
- com/vayunmathur/weather/domain/MetricSeries.kt
- com/vayunmathur/weather/domain/SelectedData.kt
- com/vayunmathur/weather/domain/SelectedDateOrTime.kt
- com/vayunmathur/weather/domain/map/OmDomains.kt
- com/vayunmathur/weather/domain/map/OmMapMetadata.kt
- com/vayunmathur/weather/domain/map/OmColorize.kt
- com/vayunmathur/weather/domain/map/OmTilesNative.kt — Rust tile bridge
### network/ — Open-Meteo DTOs + API
- com/vayunmathur/weather/network/WeatherApi.kt
- com/vayunmathur/weather/network/OpenMeteoDto.kt
- com/vayunmathur/weather/network/GeocodingDto.kt
- com/vayunmathur/weather/network/AirQualityDto.kt
### platform/ — ViewModel, location, summaries
- com/vayunmathur/weather/platform/WeatherViewModel.kt
- com/vayunmathur/weather/platform/WeatherUiContract.kt
- com/vayunmathur/weather/platform/LocationProvider.kt
- com/vayunmathur/weather/platform/ReverseGeocode.kt
- com/vayunmathur/weather/platform/Summary.kt
- com/vayunmathur/weather/platform/Precipitation.kt
- com/vayunmathur/weather/platform/SystemUnits.kt
- com/vayunmathur/weather/platform/AppBackupAgent.kt
### intents/ — assistant IPC
- com/vayunmathur/weather/intents/GetWeatherIntent.kt
- com/vayunmathur/weather/intents/GetWeatherByNameIntent.kt
### ui/ + ui/components/ + blocks/ — home, map, locations, metric blocks
- com/vayunmathur/weather/ui/HomePage.kt
- com/vayunmathur/weather/ui/ForecastColumn.kt
- com/vayunmathur/weather/ui/DeviceLocation.kt
- com/vayunmathur/weather/ui/LocationsScreen.kt
- com/vayunmathur/weather/ui/SearchLocationPage.kt
- com/vayunmathur/weather/ui/WeatherMapPage.kt
- com/vayunmathur/weather/ui/WeatherMapState.kt
- com/vayunmathur/weather/ui/WeatherMapComponents.kt
- com/vayunmathur/weather/ui/components/CurrentWeatherCard.kt
- com/vayunmathur/weather/ui/components/HourlyCard.kt
- com/vayunmathur/weather/ui/components/DailyCard.kt
- com/vayunmathur/weather/ui/components/SummaryCard.kt
- com/vayunmathur/weather/ui/components/WeatherBlocks.kt
- com/vayunmathur/weather/ui/components/CardsHeader.kt
- com/vayunmathur/weather/ui/components/SelectedHeader.kt
- com/vayunmathur/weather/ui/components/MainSearchBar.kt
- com/vayunmathur/weather/ui/components/LocationItem.kt
- com/vayunmathur/weather/ui/components/UseDeviceLocationCard.kt
- com/vayunmathur/weather/ui/components/MetricGraphSheet.kt
- com/vayunmathur/weather/ui/components/WeatherIconBox.kt
- com/vayunmathur/weather/ui/components/blocks/StatBlock.kt
- com/vayunmathur/weather/ui/components/blocks/BlockHeader.kt
- com/vayunmathur/weather/ui/components/blocks/AirQualityBlock.kt
- com/vayunmathur/weather/ui/components/blocks/UvIndexBlock.kt
- com/vayunmathur/weather/ui/components/blocks/PollenBlock.kt
- com/vayunmathur/weather/ui/components/blocks/HumidityBlock.kt
- com/vayunmathur/weather/ui/components/blocks/PressureBlock.kt
- com/vayunmathur/weather/ui/components/blocks/WindBlock.kt
- com/vayunmathur/weather/ui/components/blocks/VisibilityBlock.kt
- com/vayunmathur/weather/ui/components/blocks/PrecipitationBlock.kt
- com/vayunmathur/weather/ui/components/blocks/CloudCoverBlock.kt
- com/vayunmathur/weather/ui/components/blocks/SunBlock.kt
- com/vayunmathur/weather/ui/components/blocks/MoonBlock.kt
### widget/glance/ — Glance widgets (cache-fed only)
- com/vayunmathur/weather/widget/glance/WeatherGlanceWidget.kt
- com/vayunmathur/weather/widget/glance/WeatherGlanceWidgetReceiver.kt
- com/vayunmathur/weather/widget/glance/WeatherBlobGlanceWidget.kt
- com/vayunmathur/weather/widget/glance/WeatherBlobGlanceWidgetReceiver.kt
- com/vayunmathur/weather/widget/glance/WidgetWeather.kt — shared widget model

## Verify (this module only)
```
./gradlew :weather:compileDevKotlin
./gradlew :weather:lint
./gradlew :weather:checkMetadata
```
