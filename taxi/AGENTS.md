# AGENTS.md — taxi/ (':taxi')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `taxi/` + allowed shared modules. Do not root-scan.

_Install: ./install taxi (dev by default)._
- Gradle: :taxi / dir: taxi/
- Package roots present: ui, data, platform, network, intents, provider, notifications
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:network, :library:map, :library:image
- Metadata: metadata_data/taxi.md (present)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** US/CA ride-hailing aggregator — side-by-side Uber vs Lyft fare comparison, Lyft booking without the Lyft app, live tracking; address search with no Google Places/Play Services.
**Key features:**
- Quote flow: `provider/QuoteRepository` over `provider/RideProvider` impls → `ui/RideResults` comparison → per-provider booking (`LyftBooking`, Uber WebView auth)
- Live ride: tracking map + cards + driver/ETA notification (`notifications/RideTrackingService`); deep-link re-entry (`platform/deeplink/`)
- Cards tokenised straight to Stripe (`LyftCardTokenizer`) — numbers never touch the app backend
**Architecture / data-flow:** `ui/RideScreen` inputs → `provider/` quotes → results → booking → `notifications/` live tracking; `network/uber/` + `network/lyft/` own provider APIs/auth/parsers; `ipc/` directions + ride-handoff contracts bridge maps/assistant.
**Entry points + reading order:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/RideScreen.kt` → `provider/QuoteRepository.kt` → `provider/RideProvider.kt` → `network/lyft/LyftProvider.kt` / `network/uber/UberProvider.kt`.
**Gotchas:**
- `ipc/` is NOT a closed package root — don't add more files there
- Unofficial-provider APIs are brittle — isolate Uber/Lyft failures from each other and from the quote UI
- Screenshot tests are off for this module (`screenshotTest: no`) — verify ride flows on device

## Key files
### Root — entry + navigation
- com/vayunmathur/taxi/MainActivity.kt
- com/vayunmathur/taxi/Navigation.kt
- com/vayunmathur/taxi/Route.kt
### data/ — models + token stores
- com/vayunmathur/taxi/data/Models.kt
- com/vayunmathur/taxi/data/lyft/LyftTokenStore.kt
- com/vayunmathur/taxi/data/uber/UberSession.kt
### provider/ — quote abstraction
- com/vayunmathur/taxi/provider/RideProvider.kt
- com/vayunmathur/taxi/provider/QuoteRepository.kt
### network/lyft/ + network/uber/ — provider clients
- com/vayunmathur/taxi/network/lyft/LyftProvider.kt
- com/vayunmathur/taxi/network/lyft/LyftAuth.kt
- com/vayunmathur/taxi/network/lyft/LyftRideParser.kt
- com/vayunmathur/taxi/network/lyft/LyftOffersParser.kt
- com/vayunmathur/taxi/network/lyft/LyftProto.kt
- com/vayunmathur/taxi/network/lyft/LyftCardTokenizer.kt
- com/vayunmathur/taxi/network/uber/UberProvider.kt
- com/vayunmathur/taxi/network/uber/UberAuth.kt
- com/vayunmathur/taxi/network/uber/UberWebView.kt
### platform/ + notifications/ — location, deep links, tracking
- com/vayunmathur/taxi/platform/location/LocationProvider.kt
- com/vayunmathur/taxi/platform/deeplink/RideDeepLinks.kt
- com/vayunmathur/taxi/notifications/RideTrackingService.kt
- com/vayunmathur/taxi/notifications/RideLiveUpdate.kt
### intents/ + ipc/ — assistant + handoff contracts
- com/vayunmathur/taxi/intents/RideEstimateIntent.kt
- com/vayunmathur/taxi/ipc/RideHandoffContract.kt
- com/vayunmathur/taxi/ipc/DirectionsClient.kt
- com/vayunmathur/taxi/ipc/IntentLaunch.kt
### ui/ — search, results, booking, tracking, accounts
- com/vayunmathur/taxi/ui/RideScreen.kt
- com/vayunmathur/taxi/ui/RideContent.kt
- com/vayunmathur/taxi/ui/RideInputs.kt
- com/vayunmathur/taxi/ui/RideResults.kt
- com/vayunmathur/taxi/ui/RideRoute.kt
- com/vayunmathur/taxi/ui/LyftBooking.kt
- com/vayunmathur/taxi/ui/LyftSignInScreen.kt
- com/vayunmathur/taxi/ui/UberSignInScreen.kt
- com/vayunmathur/taxi/ui/UberConnectScreen.kt
- com/vayunmathur/taxi/ui/AddCardDialog.kt
- com/vayunmathur/taxi/ui/CurrentRideScreen.kt
- com/vayunmathur/taxi/ui/RideTrackingScreen.kt
- com/vayunmathur/taxi/ui/RideTrackingMap.kt
- com/vayunmathur/taxi/ui/RideTrackingCards.kt
- com/vayunmathur/taxi/ui/AccountsScreen.kt
- com/vayunmathur/taxi/ui/TaxiRideWideLayout.kt

## Verify (this module only)
```
./gradlew :taxi:compileDevKotlin
./gradlew :taxi:lint
./gradlew :taxi:checkMetadata
```
