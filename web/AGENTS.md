# AGENTS.md — web/ (':web')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `web/` + allowed shared modules. Do not root-scan.

_Install: ./install web (dev by default)._
- Gradle: :web / dir: web/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:room, :library:image, :library:network
- Metadata: metadata_data/web.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** Privacy browser — WebView with bundled-filter Shields (ad/tracker blocking), fingerprint randomization, per-site controls, PWA install, private tabs. User-host browsing pins `TrustBundle.SYSTEM` (deliberate exception to the narrowest-bundle rule).
**Key features:**
- Shields engine (`platform/shields/`: request filter, WebView clients, JS injection, service-worker client) over the Rust core (`shields/ShieldsNative`) with `domain/shields/` URL cleaning; per-site panel + fingerprint controls
- Tabs with thumbnails, bookmarks/history/downloads, installed-site PWAs in a standalone `PwaActivity`, desktop-mode toggle
- LAN safety policy (`domain/LanPolicy`, `LocalNetwork`) + per-site permissions/storage (`WebPermissions`, permission sheets)
**Architecture / data-flow:** `ui/` chrome (omnibox, menu, tab switcher, panels) → `platform/WebViewModel` (+ split `Permissions`/`SiteData` helpers, `BrowserState`) → `data/WebRepository` over Room (bookmarks/history/tabs/favicons) + `platform/shields/ShieldsEngine` on the network path; `platform/ContentFilters` + `ThumbnailCapture` + `WindowLauncher` support.
**Entry points + reading order:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/BrowserPage.kt` → `platform/WebViewModel.kt` → `platform/shields/ShieldsEngine.kt` → `domain/shields/UrlCleaner.kt`.
**Gotchas:**
- `SYSTEM` trust bundle is intentional here (arbitrary user hosts) — do NOT "fix" it to a narrower bundle
- Shields filtering runs on the request path — regressions break all browsing; test with Shields on AND off
- PWA windows run outside the browser chrome (`PwaActivity` + `PwaHelper`); window-launch bugs live there, not in `BrowserPage`

## Key files
### Root — entry + navigation
- com/vayunmathur/web/MainActivity.kt
- com/vayunmathur/web/Navigation.kt
- com/vayunmathur/web/Route.kt
### data/ — Room (bookmarks/history/tabs/permissions)
- com/vayunmathur/web/data/WebDatabase.kt
- com/vayunmathur/web/data/WebRepository.kt
- com/vayunmathur/web/data/Bookmark.kt
- com/vayunmathur/web/data/HistoryEntry.kt
- com/vayunmathur/web/data/InstalledSite.kt
- com/vayunmathur/web/data/TabThumbnailStore.kt
- com/vayunmathur/web/data/FaviconStore.kt
- com/vayunmathur/web/data/ShieldSetting.kt
- com/vayunmathur/web/data/SitePermission.kt
### domain/ + domain/shields/ — pure LAN policy + shields rules (no Android)
- com/vayunmathur/web/domain/LanPolicy.kt
- com/vayunmathur/web/domain/LocalNetwork.kt
- com/vayunmathur/web/domain/ShieldsSettings.kt
- com/vayunmathur/web/domain/shields/UrlCleaner.kt
- com/vayunmathur/web/domain/shields/ResourceTypes.kt
### platform/ + platform/shields/ — ViewModel, PWA, permissions, engine
- com/vayunmathur/web/platform/WebViewModel.kt
- com/vayunmathur/web/platform/WebViewModelFactory.kt
- com/vayunmathur/web/platform/WebViewModelPermissions.kt
- com/vayunmathur/web/platform/WebViewModelSiteData.kt
- com/vayunmathur/web/platform/BrowserState.kt
- com/vayunmathur/web/platform/ContentFilters.kt
- com/vayunmathur/web/platform/WebPermissions.kt
- com/vayunmathur/web/platform/PermissionPrompt.kt
- com/vayunmathur/web/platform/ExternalIntents.kt
- com/vayunmathur/web/platform/PwaActivity.kt
- com/vayunmathur/web/platform/PwaHelper.kt
- com/vayunmathur/web/platform/WindowLauncher.kt
- com/vayunmathur/web/platform/ThumbnailCapture.kt
- com/vayunmathur/web/platform/shields/ShieldsEngine.kt
- com/vayunmathur/web/platform/shields/ShieldsRequestFilter.kt
- com/vayunmathur/web/platform/shields/ShieldsWebViewClient.kt
- com/vayunmathur/web/platform/shields/ShieldsServiceWorkerClient.kt
- com/vayunmathur/web/platform/shields/ShieldsInjection.kt
- com/vayunmathur/web/platform/shields/InetHostResolver.kt
### shields/ — Rust bridge
- com/vayunmathur/web/shields/ShieldsNative.kt — JNI bridge into the filter core
### ui/ + ui/components/ — chrome, tabs, panels, settings
- com/vayunmathur/web/ui/BrowserPage.kt
- com/vayunmathur/web/ui/BrowserChrome.kt
- com/vayunmathur/web/ui/BrowserOmnibox.kt
- com/vayunmathur/web/ui/BrowserOmniboxEditor.kt
- com/vayunmathur/web/ui/BrowserMenu.kt
- com/vayunmathur/web/ui/BrowserOverlays.kt
- com/vayunmathur/web/ui/WebViewBrowser.kt
- com/vayunmathur/web/ui/WebViewClients.kt
- com/vayunmathur/web/ui/WebViewSettings.kt
- com/vayunmathur/web/ui/TabSwitcher.kt
- com/vayunmathur/web/ui/LinkContextMenu.kt
- com/vayunmathur/web/ui/BookmarksPage.kt
- com/vayunmathur/web/ui/HistoryPage.kt
- com/vayunmathur/web/ui/DownloadsPage.kt
- com/vayunmathur/web/ui/InstalledSitesPage.kt
- com/vayunmathur/web/ui/PermissionSheets.kt
- com/vayunmathur/web/ui/SettingsPage.kt
- com/vayunmathur/web/ui/ShieldsPanel.kt
- com/vayunmathur/web/ui/components/SiteIcon.kt
- com/vayunmathur/web/ui/components/TabTile.kt

## Verify (this module only)
```
./gradlew :web:compileDevKotlin
./gradlew :web:lint
./gradlew :web:checkMetadata
```
