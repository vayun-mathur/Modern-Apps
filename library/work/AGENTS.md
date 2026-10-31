# AGENTS.md — library/work/ (':library:work')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/work/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:work / dir: library/work/
- Package roots present: (WorkManager helpers — one package)
- Entry files: Work.kt (no MainActivity/Route — library)
- Deps: :library (permission-free; daily-reminder notification resources come from base, #884)
- Metadata: metadata_data/library-work.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Shared WorkManager helpers — scheduling wrappers plus the daily-puzzle reminder used by games. Thin by design; app workers live in consumers.
**How to use:** Schedule via `Work.kt` helpers; daily game content via `DailyPuzzleReminder`.
**Entry points:** `Work.kt` → `DailyPuzzleReminder.kt`.
**Gotchas:** WorkManager restores jobs across reboots itself — `BootReceiver` re-scheduling (everysync, clock, calendar) is belt-and-suspenders, keep both.

## Key files
- com/vayunmathur/library/work/Work.kt
- com/vayunmathur/library/work/DailyPuzzleReminder.kt

## Verify (this module only)
```
./gradlew :library:work:compileDevKotlin
./gradlew :library:work:lint
```
