# AGENTS.md — library/format/ (':library:format')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/format/` + allowed shared modules. Do not root-scan.

- Gradle: :library:format / dir: library/format/
- Package: `com.vayunmathur.library.format` (single package, like :library:log)
- Entry files: RelativeAge.kt (no MainActivity/Route — base library)

## Overview
**Purpose:** Pure display formatting of relative ages: bucketing a timestamp into just-now / minutes / hours / days / older (`relativeAge`), plus the shared `"<amount>m ago"` wording (`formatRelativeAge`). Calendar dates and clock times are NOT here — they belong to `library/ui`'s `DateString`, which deliberately excludes relative time.
**How to use:** Depend on `:library:format` only from apps that format a relative age. Keep app-specific wording at the call site: labels ("now" vs "just now"), timestamp parsing, and any older-than fallback date stay in the app; the thresholds live here.
**Gotchas:** No Android/Compose/resources in the bucketing API — keep it plain-JVM testable. Do not turn this into a generic formatting bucket: byte sizes, durations, and numbers each need their own justified home or stay app-local until their cluster is migrated.

## Verify (this module only)
```
./gradlew :library:format:testDebugUnitTest
./gradlew :library:format:lint
```
