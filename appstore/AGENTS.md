# AGENTS.md — appstore/ (':appstore')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `appstore/` + allowed shared modules. Do not root-scan.

_Install: ./install appstore (dev by default)._
- Gradle: :appstore / dir: appstore/
- Package roots present: ui, data, domain
- Entry files: MainActivity.kt (no Route.kt — home/library/search/sources/trust/updates pages flow)
- Deps: :library:room, :library:network, :library:work, :library:image
- Metadata: metadata_data/appstore.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** Security-first app store — six sources (own repo, curated mirror, F-Droid, GrapheneOS, Accrescent, Play) with every download signature-checked and a plain-language security tier per app (who could push a malicious update).
**Key features:**
- Multi-source catalogs: F-Droid (reproducible-builds only), Accrescent (Signify trust), anonymous Play (no Google account), GrapheneOS bundle one-tap
- Install pipeline: `InstallCoordinator` → `SessionInstaller` → `InstallStatusReceiver`, verified by `InstallVerifier` (APK certs, signed-jar index, source stamps) before the system installer sees anything
- `UpdateCheckWorker` background checks; outdated-target-API apps refused, not hidden
**Architecture / data-flow:** `ui/` home/search/detail/library/updates/sources/trust → `util/AppStoreViewModel` (+ per-area `*Ops`, `AppStoreUiContract`) → `data/` providers per source (`*Repository`, Play/F-Droid/Accrescent clients) + `data/installer/` + `data/security/`; `domain/SearchRanking` is pure ranking.
**Repo addresses:** our own F-Droid-format repos are consumed from the static R2 bucket — `DefaultRepos.MODERN_APPS` = `https://static.maproj.org/fdroid/repo`, `DefaultRepos.PROPRIETARY` = `https://static.maproj.org/proprietary/repo` (same signed indexes/APKs the server publishes on every build; signing fingerprint pinned as before). The legacy origin addresses (`maproj.org/fdroid/repo`, `maproj.org/proprietary/repo`) keep serving external F-Droid clients and are named only as `LEGACY_*_ORIGIN_URL` constants: stored repo records and cached rows are keyed by URL, so `CatalogRepository.ensureDefaultRepos` prunes rows written under the old addresses and the next sync re-imports under the static ones (no double-added repos).
**Entry points + reading order:** `MainActivity.kt` → `ui/HomePage.kt` → `util/AppStoreViewModel.kt` → `data/CatalogRepository.kt` → `data/installer/InstallCoordinator.kt` → `data/security/InstallVerifier.kt`.
**Gotchas:**
- `util/` is NOT a closed package root — don't add more files there
- Signature verification is the product — never shortcut it for UX; trust-profile changes need adversarial review
- Anonymous Play auth sessions are fragile by nature; isolate Play failures from other sources

## Key files
### Root — entry (no Route.kt)
- com/vayunmathur/appstore/MainActivity.kt
### data/ — catalog, providers, installer, security
- com/vayunmathur/appstore/data/CatalogRepository.kt
- com/vayunmathur/appstore/data/AppProvider.kt
- com/vayunmathur/appstore/data/AppModels.kt
- com/vayunmathur/appstore/data/AppDatabase.kt
- com/vayunmathur/appstore/data/AppStoreDatabaseRepository.kt
- com/vayunmathur/appstore/data/InstalledAppsRepository.kt
- com/vayunmathur/appstore/data/SettingsRepository.kt
- com/vayunmathur/appstore/data/UpdateCheckWorker.kt
- com/vayunmathur/appstore/data/FDroidRepository.kt
- com/vayunmathur/appstore/data/FDroidAppProvider.kt
- com/vayunmathur/appstore/data/FDroidVerification.kt
- com/vayunmathur/appstore/data/accrescent/AccrescentRepository.kt
- com/vayunmathur/appstore/data/accrescent/AccrescentRepo.kt
- com/vayunmathur/appstore/data/accrescent/AccrescentRepoData.kt
- com/vayunmathur/appstore/data/accrescent/AccrescentApi.kt
- com/vayunmathur/appstore/data/accrescent/AccrescentTrustStore.kt
- com/vayunmathur/appstore/data/accrescent/DeviceAttributesProvider.kt
- com/vayunmathur/appstore/data/accrescent/SignifyNative.kt — Rust Signify bridge
- com/vayunmathur/appstore/data/play/PlayRepository.kt
- com/vayunmathur/appstore/data/play/PlayStoreApi.kt
- com/vayunmathur/appstore/data/play/PlayHttpClient.kt
- com/vayunmathur/appstore/data/play/AnonymousAuthRepository.kt
- com/vayunmathur/appstore/data/play/DeviceInfoProvider.kt
- com/vayunmathur/appstore/data/play/GsfVersionProvider.kt
- com/vayunmathur/appstore/data/play/EglExtensionProvider.kt
- com/vayunmathur/appstore/data/play/CertUtil.kt
- com/vayunmathur/appstore/data/play/PlayDownloader.kt
- com/vayunmathur/appstore/data/installer/InstallCoordinator.kt
- com/vayunmathur/appstore/data/installer/SessionInstaller.kt
- com/vayunmathur/appstore/data/installer/InstallStatusReceiver.kt
- com/vayunmathur/appstore/data/security/InstallVerifier.kt
- com/vayunmathur/appstore/data/security/ApkCertificates.kt
- com/vayunmathur/appstore/data/security/SignedJarIndex.kt
- com/vayunmathur/appstore/data/security/SourceStamp.kt
- com/vayunmathur/appstore/data/security/TrustProfile.kt
- com/vayunmathur/appstore/data/security/Signify.kt
- com/vayunmathur/appstore/data/PlayStoreLinks.kt
- com/vayunmathur/appstore/data/RestrictedPackages.kt
### domain/ — pure ranking (no Android)
- com/vayunmathur/appstore/domain/SearchRanking.kt
### util/ — ViewModel + per-area ops
- com/vayunmathur/appstore/util/AppStoreViewModel.kt
- com/vayunmathur/appstore/util/AppStoreUiContract.kt
- com/vayunmathur/appstore/util/AppStoreHomeOps.kt
- com/vayunmathur/appstore/util/AppStoreSearchOps.kt
- com/vayunmathur/appstore/util/AppStoreDetailOps.kt
- com/vayunmathur/appstore/util/AppStoreUpdateOps.kt
- com/vayunmathur/appstore/util/AppStoreActionOps.kt
### ui/ — store pages
- com/vayunmathur/appstore/ui/HomePage.kt
- com/vayunmathur/appstore/ui/SearchPage.kt
- com/vayunmathur/appstore/ui/AppDetailPage.kt
- com/vayunmathur/appstore/ui/AppDetailHeader.kt
- com/vayunmathur/appstore/ui/AppDetailSections.kt
- com/vayunmathur/appstore/ui/ScreenshotViewerScreen.kt — full-screen swipe/zoom screenshot viewer (`Route.Screenshots`)
- com/vayunmathur/appstore/ui/AppTile.kt
- com/vayunmathur/appstore/ui/AppComponents.kt
- com/vayunmathur/appstore/ui/AppMeta.kt
- com/vayunmathur/appstore/ui/LibraryPage.kt
- com/vayunmathur/appstore/ui/UpdatesPage.kt
- com/vayunmathur/appstore/ui/SourcesPage.kt
- com/vayunmathur/appstore/ui/TrustPage.kt

## Verify (this module only)
```
./gradlew :appstore:compileDevKotlin
./gradlew :appstore:lint
./gradlew :appstore:checkMetadata
```
