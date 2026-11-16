# AGENTS.md — madeveloper/ (':madeveloper')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `madeveloper/` + allowed shared modules. Do not root-scan.

_Install: ./install madeveloper (dev by default)._
<!-- last-reviewed: 2026-11-16 -->
- Gradle: :madeveloper / dir: madeveloper/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:network, :library:work
- Metadata: metadata_data/madeveloper.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Developer companion for the maproj infrastructure: git host pages, CI status, a read-only build catalog, and server-pushed dev installs.
**Key features:**
- Git pages over the host JSON (`/tests.json`, `/issues.json`, `/prs.json`) and native repo browsing via the first-party JSON API (`/api/repos[/<repo>]`, `/commits`, `/tree`, `/file` on git.maproj.org) with branch switching and commit detail (no diff endpoint: metadata + link-out)
- Read-only F-Droid catalog (`static.maproj.org/fdroid/repo/index-v2.json`, newest build per package)
- Pushed installs: paired WebSocket to `wss://api.maproj.org/dev/ws`, download + SHA-256 verify, then a user-tapped PackageInstaller session (never silent)
**Architecture / data-flow:** `ui/` screens → `platform/MaDevViewModel` → `data/GitHostRepository` (NetworkClient, FIRST_PARTY) + `data/DevPushClient` seam over `WebSocketClient`; pure parsing and the install state machine live in `domain/` (`GitParsers`, `DevProtocol`) with no Android imports.
**Entry points + reading order:** `Route` → `MainActivity.kt` → `Navigation.kt` → `platform/MaDevViewModel.kt` → `data/GitHostRepository.kt` / `domain/DevProtocol.kt`.
**Gotchas:**
- The JSON API has no diff endpoint — commit detail shows metadata (subject/body/author/time) plus a link-out to the git host; do not invent a diff view. The API may 404 until deployed server-side
- Android forbids silent installs here by design: a push only downloads and verifies, the Install button and system prompt do the rest
- The `/dev/ws` server endpoint is built in parallel; the client is coded to the agreed JSON contract behind `DevSocketTransport`

## Key files
### Root — entry + navigation
- com/vayunmathur/madeveloper/MainActivity.kt
- com/vayunmathur/madeveloper/Navigation.kt
- com/vayunmathur/madeveloper/Route.kt
### data/ — git host, catalog and push transport
- com/vayunmathur/madeveloper/data/GitHostRepository.kt
- com/vayunmathur/madeveloper/data/DevPushClient.kt
### domain/ — pure models, parsers and push protocol
- com/vayunmathur/madeveloper/domain/GitModels.kt
- com/vayunmathur/madeveloper/domain/GitParsers.kt
- com/vayunmathur/madeveloper/domain/RepoBrowser.kt
- com/vayunmathur/madeveloper/domain/DevProtocol.kt
### platform/ — view model, pairing, notifications, installer
- com/vayunmathur/madeveloper/platform/MaDevViewModel.kt
- com/vayunmathur/madeveloper/platform/PairingStore.kt
- com/vayunmathur/madeveloper/platform/InstallNotifications.kt
- com/vayunmathur/madeveloper/platform/InstallStatusReceiver.kt
- com/vayunmathur/madeveloper/platform/ReconnectWorker.kt
### ui/ — screens
- com/vayunmathur/madeveloper/ui/HomeScreen.kt
- com/vayunmathur/madeveloper/ui/RepoScreen.kt
- com/vayunmathur/madeveloper/ui/CommitsScreen.kt
- com/vayunmathur/madeveloper/ui/CommitScreen.kt
- com/vayunmathur/madeveloper/ui/FileScreen.kt
- com/vayunmathur/madeveloper/ui/TestsScreen.kt
- com/vayunmathur/madeveloper/ui/CatalogScreen.kt
- com/vayunmathur/madeveloper/ui/PushScreen.kt

## Verify (this module only)
```
./gradlew :madeveloper:compileDevKotlin
./gradlew :madeveloper:lint
./gradlew :madeveloper:checkMetadata
```
