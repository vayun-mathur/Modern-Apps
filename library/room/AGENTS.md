# AGENTS.md — library/room/ (':library:room')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/room/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:room / dir: library/room/
- Package roots present: (pure-Kotlin Room3 + SQLCipher wiring — single package)
- Entry files: RoomRepository.kt (no MainActivity/Route — library)
- Deps: :library (permission-free; SqlCipherDbCodec implements base's DbBackupCodec, #884)
- Metadata: metadata_data/library-room.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Room 3 + SQLCipher wiring for every app database. Apps never call `buildDatabase()` — they subclass `RoomRepository` and apply `implementRoom(libs)`.
**How to use:** `abstract class XxxRepository : RoomRepository` → `implementRoom(libs)` in the module build file → committed schemas under the app's `schemas/` (read single files only, never scan).
**Entry points:** `RoomRepository.kt` → `SqlCipher.kt`.
**Gotchas:** The literal string `androidx.room.` is lint-flagged even in comments — write `Room` in prose. Schema migrations must be tested with the actual old DB, not fresh installs.

## Key files
- com/vayunmathur/library/room/RoomRepository.kt — subclass this, never `buildDatabase()`
- com/vayunmathur/library/room/SqlCipher.kt
- com/vayunmathur/library/room/SqlCipherDbCodec.kt

## Verify (this module only)
```
./gradlew :library:room:compileDevKotlin
./gradlew :library:room:lint
```
