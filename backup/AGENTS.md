# AGENTS.md — backup/ (':backup')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `backup/` + allowed shared modules. Do not root-scan.

_Install: ./install backup (dev by default)._
- Gradle: :backup / dir: backup/
- Package roots present: ui, data, domain, platform, service
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:aosp-stubs, :library:network, :library:work, :library:biometric, :library:e2ee-p2p
- Metadata: metadata_data/backup.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Privileged encrypted backup app for data, apps, and media. THE reference module — satisfies every repo rule; copy its layout for new apps.
**Key features:**
- AES-256-GCM E2EE keyed by a 12-word BIP-39 recovery code; restore from the code alone, no account
- SAF folder backend (internal/USB/SD) + WebDAV/Nextcloud remote backend behind one `BackupBackend` interface
- Scheduled encrypted photo/video/audio backup; system app-data transport on platform-signed installs
**Architecture / data-flow:** `ui/` onboarding→dashboard → `platform/BackupViewModel` → `data/backend/` (`BackendFactory` picks `SafBackend`/`WebDavBackend`, `BackupRepository` orchestrates) with `domain/crypto/` pure (Bip39/Crypto) and keys in `platform/crypto/KeyManager`; `platform/FileBackupWorker` runs schedules, `service/ConfigurableBackupTransport` exposes the system transport. `Navigation`: start gated on `onboarded` flag; `reset()` not `add()` (no back-navigation to onboarding); SAF folder picker + persistable URI perms owned at `Navigation` level since both screens use it.
**Entry points + reading order:** `Route` (`Onboarding` / `Dashboard`) → `MainActivity.kt` → `Navigation.kt` → `platform/BackupViewModel.kt` → `data/backend/BackendFactory.kt` + `BackupBackend.kt`.
**Gotchas:**
- Onboarding ends when the ViewModel has key + destination, not via navigation — the stack follows the flag
- Media-permission grant funnels straight into `backupFilesNow()`
- Internet is used only for WebDAV remotes; everything else is offline

## Why this is the reference (copy this, not others)
**Copy-this checklist:**
- `data/backend/BackupBackend` interface + `BackendFactory` + one impl per destination (SAF / WebDAV) — new destinations add a file, not a refactor
- Pure `domain/crypto/` (Bip39, Crypto) with zero Android imports; platform key custody isolated in `platform/crypto/KeyManager`
- `platform/` worker + ViewModel split: `FileBackupWorker` schedules/runs, `BackupViewModel` holds UI state — never mixed
- Onboarding-gated `Navigation` with the why-comments intact (`reset()` vs `add()`, picker ownership at `Navigation` level)
- Closed package roots (`ui data domain platform service`), one public `@Composable` per file
**Anti-patterns avoided here (seen elsewhere):**
- No `util/` dumping ground — helpers live in the package they serve
- No backend `when`-branches scattered through UI — all behind the interface
- No navigation back to onboarding; no picker duplicated per screen

## Key files
### Root — entry + navigation
- com/vayunmathur/backup/MainActivity.kt
- com/vayunmathur/backup/Navigation.kt — onboarding gate + picker ownership (read the comments)
- com/vayunmathur/backup/Route.kt
### data/ — backends, repo, file backup
- com/vayunmathur/backup/data/BackupConfig.kt
- com/vayunmathur/backup/data/backend/BackupBackend.kt — the interface to copy
- com/vayunmathur/backup/data/backend/BackendFactory.kt
- com/vayunmathur/backup/data/backend/BackupRepository.kt
- com/vayunmathur/backup/data/backend/SafBackend.kt
- com/vayunmathur/backup/data/backend/WebDavBackend.kt
- com/vayunmathur/backup/data/files/FileBackupManager.kt
- com/vayunmathur/backup/data/files/FileMetadata.kt
### domain/ — pure crypto (no Android)
- com/vayunmathur/backup/domain/crypto/Bip39.kt
- com/vayunmathur/backup/domain/crypto/Crypto.kt
### platform/ — ViewModel, worker, key custody
- com/vayunmathur/backup/platform/BackupViewModel.kt
- com/vayunmathur/backup/platform/FileBackupWorker.kt
- com/vayunmathur/backup/platform/crypto/KeyManager.kt
### service/ — system backup transport
- com/vayunmathur/backup/service/ConfigurableBackupTransport.kt
- com/vayunmathur/backup/service/ConfigurableBackupTransportService.kt
### ui/ — onboarding + dashboard
- com/vayunmathur/backup/ui/OnboardingScreen.kt
- com/vayunmathur/backup/ui/DashboardScreen.kt
- com/vayunmathur/backup/ui/components/BackendSection.kt

## Verify (this module only)
```
./gradlew :backup:compileDevKotlin
./gradlew :backup:lint
./gradlew :backup:checkMetadata
```
