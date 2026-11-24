# AGENTS.md — notes-wear/ (':notes-wear')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `notes-wear/` + `notes/` (the phone side of the same feature).

- Gradle: :notes-wear / dir: notes-wear/ (convention: `common-conventions-wear`)
- Package roots present: ui, data, domain, sync
- Entry file: ui/WearMainActivity.kt

## Why this module exists (read before changing identity)
Wear OS companion of the phone Notes (`:notes`). This module deliberately
uses `applicationId = "com.vayunmathur.notes"` (the phone app's) with the
shared dev/release signing from the convention plugins: WearLink's
handshake token requires the same signing certificate on both sides, so
changing either breaks phone<->watch sync. Bootstrap is a one-time ADB
sideload of this APK onto the watch.

## Protocol
`domain/WearNotesProtocol.kt` is a copy of the phone's file (Gradle cannot
depend on another app module). Change both copies together; each side's
`WearNotesProtocolTest` pins the same golden JSON.

- Snapshot + watch-state sync: WearLink data items (`/notes/snapshot`, `/notes/watch-state`)
- Watch-originated ops: WearLink message (`/notes/op`, create/edit/delete)
- Sync request: WearLink message (`/notes/request-sync`), watch -> phone only
- Transport: :library:wearlink (RFCOMM, no Play services); app-lifecycle-bound

## Sync semantics (stated, not implied)
- Phone -> watch: bounded snapshot — 50 most recent notes, title ≤120
  chars, body ≤4000 chars, ≤90_000 JSON bytes (`truncated` flags loss).
  The watch persists it and reads fully offline.
- Watch -> phone: create/edit/delete ops flow over the link and apply on
  the phone only. Conflict policy is last-writer-wins per note by
  `updatedAt` (ties: tombstone, then larger title/body) — no text merge.
- Honest writes: notes with image/ink blocks on the phone (`textOnly =
  false`) are read-only on the watch except delete; watch creates/edits
  become single-text-block notes on the phone. An op stays *pending* on
  the watch (persisted, resent on reconnect) until a phone snapshot
  reflects it — the UI never claims "synced" before that.

## Verify (this module only)
```
./gradlew :notes-wear:assembleDev
./gradlew :notes-wear:testDebugUnitTest
```
