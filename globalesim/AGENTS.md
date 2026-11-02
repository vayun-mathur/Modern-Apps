# AGENTS.md — globalesim/ (':globalesim')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `globalesim/` + allowed shared modules. Do not root-scan.

_Install: ./install globalesim (dev by default)._
<!-- last-reviewed: 2026-10-24 -->
- Gradle: :globalesim / dir: globalesim/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:network, :library:log, :library:ui (via conventions), androidx.datastore.preferences, stripe-android
- Metadata: metadata_data/globalesim.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Travel data eSIM storefront. One global eSIM per anonymous account — top up USD credit, data deducts at the local per-country rate. Credit never expires.
**Key features:**
- Pay-first: no eSIM until the first top-up ($5 min, card or crypto); the server keeps a $3 setup fee and funds the rest
- Anonymous account (client-minted UUIDv4, no PII). The account id is the only credential and is shown as the "SIM ID": Copy SIM ID on the old phone, Claim on a phone without an eSIM
- Rate catalog (server-scaled to the wallet's face value), Stripe card and NOWPayments crypto (+5%) top-ups, $5–$20 per payment
- One-tap install via the standard `https://esimsetup.android.com/esim_qrcode_provisioning?carddata=LPA:1$<smdp>$<code>` link (unpinned ACTION_VIEW, raw carddata)
**Architecture / data-flow:** `ui/` screens → `platform/EsimViewModel` (single `EsimUiState`) → `data/api/EsimApi` (own `/esim/v1/*` on api.maproj.org, FIRST_PARTY) with pure `domain/QuoteMath` and `data/AnonymousIdStore` (DataStore UUID). The reseller vendor is server-only (`location_share_server/src/handlers/esim*.rs`) and never named here.
**Entry points + reading order:** `Route.Tabs` (Purchase + Pricing tabs, no other routes) → `MainActivity.kt` → `Navigation.kt` → `platform/EsimViewModel.kt` → `data/api/EsimApi.kt`.
**Gotchas:**
- After any payment the ViewModel polls `/status` until the wallet rises (server-owned pending checkout + `pending_credit_cents` keep it going); pull-to-refresh on Purchase reloads status
- One open crypto checkout per account, owned by the server and restored from `/status`
- PaymentSheet presents each client secret once (saved across recreation); cleared on done/cancel
- ViewModel messages are shown once by the tab host in `Navigation.kt`, not by the screens
- Never log the account id / SIM ID; the clipboard copy is flagged sensitive
- Install shows no QR/code — same-phone QR is unscannable; direct LPA handoff only
- `rememberPaymentSheet` is deprecated in stripe-android 23.x (same as fooddelivery); leave until repo migrates

## Key files
### Root — entry + navigation
- com/vayunmathur/globalesim/MainActivity.kt
- com/vayunmathur/globalesim/Navigation.kt
- com/vayunmathur/globalesim/Route.kt
### data/ — api + anonymous id
- com/vayunmathur/globalesim/data/api/EsimApi.kt — BASE_URL + url(path) + NetworkClient calls
- com/vayunmathur/globalesim/data/api/EsimDtos.kt — generic DTOs only, no vendor strings
- com/vayunmathur/globalesim/data/AnonymousIdStore.kt
### domain/ — pure logic (no Android)
- com/vayunmathur/globalesim/domain/QuoteMath.kt — payment bounds, cents formatting/parsing
- com/vayunmathur/globalesim/domain/CheckoutUrls.kt
- com/vayunmathur/globalesim/domain/EsimActivationCode.kt
### platform/ — ViewModel
- com/vayunmathur/globalesim/platform/EsimViewModel.kt
### ui/ — one public @Composable per file
- com/vayunmathur/globalesim/ui/HomeScreen.kt — Purchase tab (balance, top-up, install, Copy SIM ID / Claim)
- com/vayunmathur/globalesim/ui/PricingScreen.kt — Pricing tab

## Verify (this module only)
```
./gradlew :globalesim:compileDevKotlin
./gradlew :globalesim:lint
./gradlew :globalesim:testDevUnitTest
```
