# AGENTS.md — fooddelivery/ (':fooddelivery')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `fooddelivery/` + allowed shared modules. Do not root-scan.

_Install: ./install fooddelivery (dev by default)._
- Gradle: :fooddelivery / dir: fooddelivery/
- Package roots present: ui, data, platform, intents, notifications
- Entry files: MainActivity.kt + FoodDeliveryApplication.kt + platform/AppInit.kt (no Route.kt — home/restaurant/cart/checkout/orders flow)
- Deps: :library:image, :library:map, :library:network
- Metadata: metadata_data/fooddelivery.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** US-only food-delivery client — restaurant browse, cart + Stripe checkout (pickup/delivery, tips, promos), live order tracking, deals/loyalty. Requires internet throughout.
**Key features:**
- Home/deals → restaurant → cart → checkout → success flow with modifier dialogs and totals; account addresses/auth/profile
- `data/CartStore` + `AddressStore` client state over `api/BitesApi`; order tracking in `notifications/` (live update + foreground tracking service)
- Assistant order lookup via `intents/OrderLookupIntent` (+ `ipc/OrderLookupContract`)
**Architecture / data-flow:** `ui/` flow screens → `platform/AppInit`-wired state (`data/CartStore`) → `api/BitesApi` backend; tracking runs in `notifications/OrderTrackingService` independent of the UI process.
**Entry points + reading order:** `FoodDeliveryApplication.kt` → `platform/AppInit.kt` → `MainActivity.kt` → `ui/HomeScreen.kt` → `ui/RestaurantScreen.kt` → `ui/CartScreen.kt` → `ui/CheckoutScreen.kt` → `api/BitesApi.kt`.
**Gotchas:**
- `api/` and `ipc/` are NOT closed package roots — don't add more files there
- Payment data goes straight to Stripe tokenization — card numbers must never hit app state or logs
- Tracking must survive the app being killed; keep it in the service, not the ViewModel

## Key files
### Root + platform/ — app init + entry (no Route.kt)
- com/vayunmathur/fooddelivery/FoodDeliveryApplication.kt
- com/vayunmathur/fooddelivery/MainActivity.kt
- com/vayunmathur/fooddelivery/platform/AppInit.kt
### api/ — backend client
- com/vayunmathur/fooddelivery/api/BitesApi.kt
### data/ — cart, addresses, models
- com/vayunmathur/fooddelivery/data/CartStore.kt
- com/vayunmathur/fooddelivery/data/AddressStore.kt
- com/vayunmathur/fooddelivery/data/Models.kt
### intents/ + ipc/ — assistant lookup contract
- com/vayunmathur/fooddelivery/intents/OrderLookupIntent.kt
- com/vayunmathur/fooddelivery/ipc/OrderLookupContract.kt
### notifications/ — live tracking
- com/vayunmathur/fooddelivery/notifications/OrderTrackingService.kt
- com/vayunmathur/fooddelivery/notifications/OrderLiveUpdate.kt
### ui/ — browse, cart, checkout, orders, account
- com/vayunmathur/fooddelivery/ui/HomeScreen.kt
- com/vayunmathur/fooddelivery/ui/DealsScreen.kt
- com/vayunmathur/fooddelivery/ui/RestaurantScreen.kt
- com/vayunmathur/fooddelivery/ui/RestaurantSections.kt
- com/vayunmathur/fooddelivery/ui/ModifierDialog.kt
- com/vayunmathur/fooddelivery/ui/CartScreen.kt
- com/vayunmathur/fooddelivery/ui/CheckoutScreen.kt
- com/vayunmathur/fooddelivery/ui/CheckoutSections.kt
- com/vayunmathur/fooddelivery/ui/CheckoutTotals.kt
- com/vayunmathur/fooddelivery/ui/CheckoutSuccess.kt
- com/vayunmathur/fooddelivery/ui/OrdersScreen.kt
- com/vayunmathur/fooddelivery/ui/OrdersScreenSection.kt
- com/vayunmathur/fooddelivery/ui/OrderTrackingScreen.kt
- com/vayunmathur/fooddelivery/ui/AccountScreen.kt
- com/vayunmathur/fooddelivery/ui/AccountAuth.kt
- com/vayunmathur/fooddelivery/ui/AccountProfile.kt
- com/vayunmathur/fooddelivery/ui/AccountAddresses.kt
- com/vayunmathur/fooddelivery/ui/AddressFormCard.kt

## Verify (this module only)
```
./gradlew :fooddelivery:compileDevKotlin
./gradlew :fooddelivery:lint
./gradlew :fooddelivery:checkMetadata
```
