# AGENTS.md — logviewer/ (':logviewer')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `logviewer/` + allowed shared modules. Do not root-scan.

_Install: ./install logviewer (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :logviewer / dir: logviewer/
- Package roots present: ui, data, domain, platform, intents, provider
- Entry files: ui/LogViewerScreen.kt + intents/LogcatActivity.kt + intents/ErrorReportActivity.kt (no MainActivity/Route — viewer surfaces, not a nav app)
- Deps: (none declared)
- Metadata: metadata_data/logviewer.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** On-device log + crash-report reader — logcat with level/buffer/regex filters, tombstones, system error reports, share-without-saving. 100% offline.
**Key features:** `platform/LogcatReader` + `LogViewerController`/`ViewModel` over `domain/LogDocument`/`LogKind`/`LogLevel`; heads-up `HiddenFrameworkApi` for system access; `provider/BlobProvider` shares without writing to storage; crash→app-log jump.
**Entry points:** `intents/LogcatActivity.kt` (log) / `intents/ErrorReportActivity.kt` (reports) → `platform/LogViewerController.kt` → `platform/LogcatReader.kt` → `domain/LogDocument.kt`.
**Gotchas:** Needs privileged log access — behavior differs by build/signature; never assume logcat is readable everywhere.

## Key files
### intents/ + provider/ — viewer surfaces (the entries)
- com/vayunmathur/logviewer/intents/LogcatActivity.kt
- com/vayunmathur/logviewer/intents/ErrorReportActivity.kt
- com/vayunmathur/logviewer/intents/LogViewerIntents.kt
- com/vayunmathur/logviewer/provider/BlobProvider.kt
### data/ + domain/ — tombstones + log model
- com/vayunmathur/logviewer/data/TombstoneFiles.kt
- com/vayunmathur/logviewer/domain/LogDocument.kt
- com/vayunmathur/logviewer/domain/LogKind.kt
- com/vayunmathur/logviewer/domain/LogLevel.kt
### platform/ — readers + controller + ViewModel
- com/vayunmathur/logviewer/platform/LogViewerController.kt
- com/vayunmathur/logviewer/platform/LogViewerViewModel.kt
- com/vayunmathur/logviewer/platform/LogViewerUiContract.kt
- com/vayunmathur/logviewer/platform/LogcatReader.kt
- com/vayunmathur/logviewer/platform/ErrorReportReader.kt
- com/vayunmathur/logviewer/platform/HiddenFrameworkApi.kt
- com/vayunmathur/logviewer/platform/ReportHeaders.kt
- com/vayunmathur/logviewer/platform/LogLoadResult.kt
### ui/ — viewer + dialogs
- com/vayunmathur/logviewer/ui/LogViewerScreen.kt
- com/vayunmathur/logviewer/ui/LogViewerPage.kt
- com/vayunmathur/logviewer/ui/components/LogLineList.kt
- com/vayunmathur/logviewer/ui/dialogs/StackTraceDialog.kt
- com/vayunmathur/logviewer/ui/dialogs/TextInputDialog.kt

## Verify (this module only)
```
./gradlew :logviewer:compileDevKotlin
./gradlew :logviewer:lint
./gradlew :logviewer:checkMetadata
```
