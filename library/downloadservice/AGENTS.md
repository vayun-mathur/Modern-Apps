# AGENTS.md — library/downloadservice/ (':library:downloadservice')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/downloadservice/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:downloadservice / dir: library/downloadservice/
- Package roots present: (foreground-download helpers — one package)
- Entry files: InitialDownloader.kt (no MainActivity/Route — library)
- Deps: :library (permission-free edge; WorkManager comes from
  androidx.work directly — the `:library:work` helpers schedule that module's
  own reminders, nothing reusable here, #884)
- Metadata: metadata_data/library-downloadservice.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Foreground downloads for large assets/models (ML weights, offline DBs) — declares INTERNET itself so consumers don't have to. Used by speech/translate (models), networklocation (beacon DBs).
**How to use:** `InitialDownloader`/`InitialModelDownloadChecker` for first-run gates (file lists live in the consumer's `platform/` or `data/`); `ModelDownloadWorker` for WorkManager-driven fetches; URLs in `ModelUrls`.
**Entry points:** `InitialDownloader.kt` → `ModelUrls.kt` → `ModelDownloadWorker.kt`.
**Gotchas:** On-device paths are `/storage/emulated/0/Android/data/<pkg>/files/<fileName>` (`+.part` while downloading) — never hardcode, resolve per hosting app (libraries have no `applicationId`).

## Key files
- com/vayunmathur/library/downloadservice/InitialDownloader.kt
- com/vayunmathur/library/downloadservice/ModelDownloadWorker.kt
- com/vayunmathur/library/downloadservice/ModelUrls.kt

## Verify (this module only)
```
./gradlew :library:downloadservice:compileDevKotlin
./gradlew :library:downloadservice:lint
```
