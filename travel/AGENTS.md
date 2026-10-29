# AGENTS.md — travel/ (':travel')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `travel/` + allowed shared modules. Do not root-scan.

_Install: ./install travel (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :travel / dir: travel/
- Package roots present: ui, data, network
- Entry files: MainActivity.kt (no Route.kt — search/results/booking/manage flow with pages)
- Deps: :library:room, :library:network, :library:image
- Metadata: metadata_data/travel.md (present)
- Rust: no / screenshotTest: yes

### Relay server note (backend, outside this repo)
`travel` does not use the relay directly, but `NetworkClient.init(FIRST_PARTY)` covers `api.maproj.org` (relay hosts migrated from `findfamily.cc`; `findfamily.cc` remains valid for backward compatibility). Server checkout: `../location_share_server` (sibling of this repo root, `C:\Users\Vayun\Documents\code\location_share_server`). Full details: root `AGENTS.md` → "Backend server (outside this repo)".

## Overview
**Purpose:** Flight + hotel booking — search/comparison, seat maps, ancillaries/offers, trip management with hosted checkout; trips persist on-device so plans stay readable offline.
**Key features:**
- Flight search form (one-way / round-trip) → results → offer review → passengers → trip → ancillaries → hosted checkout → confirmation; Explore map (find-destination price pins → live re-search) ; change/cancel/manage on booked trips
- Round-trip step-by-step (grouped on-device); stays search → detail → guests → hosted checkout → confirmation; seat-map picker
- `data/` Room (trips, local booking profiles, frequent-flyer, recent searches) + `domain/` on-device grouping/status + `network/` trip/stays DTOs + APIs
**Architecture / data-flow:** `ui/` search/results/booking pages → `util/TravelViewModel` (+ `TravelUiContract`/`TravelUiState`, per-area `Travel*Ops`) → `domain/` grouping + `data/TravelRepository` over Room + `network/TravelApi`/`StaysApi`; booked trips stay readable from Room when offline.
**Entry points + reading order:** `MainActivity.kt` → `ui/HomePage.kt` → `ui/FlightSearchForm.kt` → `util/TravelViewModel.kt` → `util/TravelSearchOps.kt` → `network/TravelApi.kt`.
**Gotchas:**
- `util/` is NOT a closed package root — pending migration; don't add more files there
- Booking is money-moving — price/availability must be re-confirmed server-side before payment, never trusted from cached offers
- Offline readability is a feature (stored trips), offline booking is not — gate write paths on connectivity

## Key files
### Root — entry (no Route.kt)
- com/vayunmathur/travel/MainActivity.kt
### data/ — Room trips + profiles
- com/vayunmathur/travel/data/TravelDatabase.kt
- com/vayunmathur/travel/data/TravelRepository.kt
- com/vayunmathur/travel/data/BookedTrip.kt
- com/vayunmathur/travel/data/Customer.kt
- com/vayunmathur/travel/data/FrequentFlyer.kt
- com/vayunmathur/travel/data/RecentSearch.kt
### domain/ — on-device offer + trip logic
- com/vayunmathur/travel/domain/OfferGrouping.kt
- com/vayunmathur/travel/domain/TripStatus.kt
### intents/ — hosted-checkout Custom Tab
- com/vayunmathur/travel/intents/CheckoutTabs.kt
### network/ — airline + stays APIs
- com/vayunmathur/travel/network/TravelApi.kt
- com/vayunmathur/travel/network/OfferDto.kt
- com/vayunmathur/travel/network/OrderDto.kt
- com/vayunmathur/travel/network/ManageDto.kt
- com/vayunmathur/travel/network/AirlineDto.kt
- com/vayunmathur/travel/network/AncillaryDto.kt
- com/vayunmathur/travel/network/PlaceDto.kt
- com/vayunmathur/travel/network/StaysApi.kt
- com/vayunmathur/travel/network/StaysDto.kt
### util/ — ViewModel + booking ops (pending migration to closed roots)
- com/vayunmathur/travel/util/TravelViewModel.kt
- com/vayunmathur/travel/util/TravelUiContract.kt
- com/vayunmathur/travel/util/TravelUiState.kt
- com/vayunmathur/travel/util/TravelSearchOps.kt
- com/vayunmathur/travel/util/TravelBookingOps.kt
- com/vayunmathur/travel/util/TravelOrderOps.kt
- com/vayunmathur/travel/util/TravelCustomerOps.kt
- com/vayunmathur/travel/util/TravelStayOps.kt
- com/vayunmathur/travel/util/ContactAutofill.kt
### ui/ — search, results, booking, trips, stays
- com/vayunmathur/travel/ui/HomePage.kt
- com/vayunmathur/travel/ui/FlightSearchForm.kt
- com/vayunmathur/travel/ui/FlightResultsPage.kt
- com/vayunmathur/travel/ui/OfferReviewPage.kt
- com/vayunmathur/travel/ui/PartialOffersPages.kt
- com/vayunmathur/travel/ui/PassengersPage.kt
- com/vayunmathur/travel/ui/PassengersFields.kt
- com/vayunmathur/travel/ui/PaymentPage.kt
- com/vayunmathur/travel/ui/SeatMapPage.kt
- com/vayunmathur/travel/ui/AncillariesPage.kt
- com/vayunmathur/travel/ui/ConfirmationPage.kt
- com/vayunmathur/travel/ui/OrderDetailPage.kt
- com/vayunmathur/travel/ui/ChangePage.kt
- com/vayunmathur/travel/ui/CancellationPage.kt
- com/vayunmathur/travel/ui/TripsPage.kt
- com/vayunmathur/travel/ui/StaySearchForm.kt
- com/vayunmathur/travel/ui/StayResultsScreen.kt
- com/vayunmathur/travel/ui/StayDetailPage.kt
- com/vayunmathur/travel/ui/StayGuestsPage.kt
- com/vayunmathur/travel/ui/StayConfirmationPage.kt
- com/vayunmathur/travel/ui/StaysPages.kt
- com/vayunmathur/travel/ui/StayLabels.kt
- com/vayunmathur/travel/ui/SettingsPage.kt
- com/vayunmathur/travel/ui/Common.kt
- com/vayunmathur/travel/ui/TravelFormat.kt

## Verify (this module only)
```
./gradlew :travel:compileDevKotlin
./gradlew :travel:lint
./gradlew :travel:checkMetadata
```
