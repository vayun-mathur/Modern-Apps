# AGENTS.md — desktop/passwords/ (':desktop:passwords')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `desktop/passwords/` + allowed shared modules. Do not root-scan.

- Gradle: :desktop:passwords / dir: desktop/passwords/
- Package roots present: com.vayunmathur.desktop.passwords (plain Kotlin, no Compose)
- Entry files: Main.kt, PasswordSync.kt, PasswordVault.kt, KdbxCompat.kt
- Deps: :library:everysync-core, kotlinx-serialization-json, okhttp, jna (all via catalog)
- Metadata: none (CLI shell, no app metadata)
- Rust: no (consumes e2ee_pqc host lib via core's JvmPqcCrypto) / screenshotTest: no

## Overview
**Purpose:** Desktop JVM shell on the shared EverySync core for the passwords vault (Phase 2a). Syncs `RemotePassword` records over `JvmRelayTransport` through the shared `SyncDriver` + `PasswordKind` merge. No Compose UI yet — UI lands after the core proves.
**Key features:**
- File-backed `PasswordVault` implementing core `SyncSink<RemotePassword>` with hard-delete semantics
- One-shot `PasswordSync.drive` via shared `SyncDriver` (null-pull aborts, never merges a failed pull)
- `KdbxCompat` KeePass-style CSV/JSON interchange using verbatim shared `EntryMapper` key names
- `Main.kt` CLI: init transport, sync once, print counts
**Architecture / data-flow:** `Main` (FileSecureStore + JvmRelayTransport.init) → `PasswordSync.syncOnce` (vault key load-or-create → pullRecords since 0 → fold → SyncDriver.drive → append) → `PasswordVault` (rows + `MemoryBaselineStore` snapshots on disk).
**Entry points + reading order:** `Main.kt` → `PasswordSync.kt` → `PasswordVault.kt` → `KdbxCompat.kt`.
**Gotchas:**
- Passwords HARD-delete: `listLocal` never returns deleted rows; `delete()` removes the row and keeps the baseline snapshot so the merge reads "baseline present + row absent" as a local delete. Only explicit `deleted=true` relay envelopes delete (see `PasswordKind`).
- Relay log is append-only: `syncOnce` folds the FULL log (last envelope per uid wins) before driving; a null pull aborts the cycle.
- `KdbxCompat` carries field maps only — no new crypto, no KDBX encryption. File protection stays out of scope.
- JVM-only module: no `Toast`, no `Icon`/`Scaffold`, no Compose, no Room strings.

## Key files
- com/vayunmathur/desktop/passwords/PasswordVault.kt — file JSON store + SyncSink with hard-delete rule
- com/vayunmathur/desktop/passwords/PasswordSync.kt — FileSecureStore, RelayStore adapter, syncOnce drive
- com/vayunmathur/desktop/passwords/KdbxCompat.kt — KeePassXC-compat field-map import/export
- com/vayunmathur/desktop/passwords/Main.kt — CLI entry (sync once, print counts)

## Verify (this module only)
```
./gradlew :desktop:passwords:compileKotlin
```
(Orchestrator verifies; do not run Gradle from this task.)
