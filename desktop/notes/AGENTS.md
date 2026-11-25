# AGENTS.md — desktop/notes/ (':desktop:notes')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `desktop/notes/` + `settings.gradle.kts` (append include only) plus
> read-only access to the shared contracts. Do NOT touch
> `library/everysync-core`, `todo/`, `notes/`, `desktop/passwords`, or Apple dirs.

- Gradle: :desktop:notes / dir: desktop/notes/
- Targets: JVM desktop only (applies `common-conventions-jvm`)
- Package: `com.vayunmathur.desktop.notes`
- Entry files: `Main.kt` (CLI), `NoteSync.kt` (driver), `NoteVault.kt`, `TodoView.kt`
- Deps: `:library:everysync-core` (merge, DTOs, `JvmRelayTransport`), kotlinx-serialization
- Metadata: none (desktop shells have none by design)
- Rust: no (PQC via core's JVM actual)

## Overview
**Purpose:** Desktop Notes JVM shell on the shared core: syncs the
`notes:<vaultId>` + `todos:<vaultId>` relay channels once via `SyncDriver`
and prints counts. Windows renders todos inside the Notes UI
(`TodoView.todosAsNotes` projection, writes back via `updateTodoFromNote`).

**Key files**
- `NoteVault.kt` — `SyncSink<RemoteNote>` + `RelayStore<RemoteNote>` with
  SOFT-delete (tombstones persist; `listLocal` includes deleted).
- `TodoView.kt` — `SyncSink<RemoteTodo>` + `RelayStore<RemoteTodo>`, same
  tombstone semantics, plus the Windows todos-view projection.
- `NoteSync.kt` — drives both channels via `SyncDriver` + `JvmRelayTransport`;
  blob images by `fileName` via `BlobStore` (`notes-blobs:<vaultId>` LWW).
- `MarkdownBridge.kt` — `NoteBody` → markdown export (no Android deps).
- `Main.kt` — CLI: sync once, print counts.

**Invariants (from the shared core, preserved here):** failed pull = abort,
never empty; tombstone-only delete; reorder is not an edit
(`position` stripped from merge hashes); `notes`/`createdAt` persist verbatim
on todos.

## Verify (this module only)
Do NOT run Gradle (per task scope). When enabled:
```
./gradlew :desktop:notes:compileKotlin
```
Files <350 lines, no Android imports.
