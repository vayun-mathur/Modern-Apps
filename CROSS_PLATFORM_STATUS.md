# EverySync Cross-Platform Status

Single source of truth for the shared-core + system-sink + shell program
(Phases 1–3). Relay contract and invariants are restated here verbatim so no
target re-derives them.

## Phase table

| Phase | Scope | Status |
|-------|-------|--------|
| 1 — core | Shared KMP sync brain (`library/everysync-core`): relay merge, vault policy, DTOs, iCal/vCard formats, PQC transport surface | DONE (`6eb6023d2`, `5ff387959`, `82a121367`) |
| 1D — bindings | Platform actuals: OkHttp (Android/JVM), Darwin (Apple), JNI/JNA PQC, secure stores, scheduler | DONE |
| 2 — desktop providers | JVM `sinks/` over EDS `libecal`/`libebook` (Linux, file-backed + JNA stub) and read-only `AppointmentStore`/`ContactStore` + Notes-view todos (Windows v1); KDBX round-trip + todos-in-Notes + EDS sink tests green (`6cbdeaaab`); pre-existing `NoteSync.syncChannel` log-fold bug fixed in the same commit | DONE (device runs open: native EDS link, Windows store wiring, live-relay per target) |
| 2c/3b — system sinks (this change) | `sinks/SystemSinks.kt` interface docs: `CalendarSystemSink`, `ContactsSystemSink`, `TodoSystemSink` over existing `SyncSink<T>` | DONE |
| 3 — Apple providers | Swift shells + `Shared/` IPC/crypto/scheduler + `XCFramework/` build spec (cinterop `.def`, ref header, build script, iOS-actuals notes) + sink bindings (`EKReminder`/`CNContactStore`/`EKEvent`) + E2E checklist (`5c3d6ef20`); Kotlin `iosMain` actuals + `xcodebuild` + simulator conformance need a Mac | DONE (Mac-side work open) |
| live-relay | scripted `LiveRelayConformanceTest` runner + sign-off table (`595d3d714`); staging `POST /personal/*` returns 404 (routes undeployed) — 6 local steps PASS, 6 relay steps BLOCKED with exact evidence | PARTIAL (needs relay redeploy, then re-run) |

## Relay contract

- Base `https://api.maproj.org/personal` (migrated from `https://findfamily.cc/personal`; findfamily.cc remains valid for backward compatibility), WS `wss://api.maproj.org/personal/ws`.
- Endpoints: `POST /register | /getkey | /append | /pull`.
- Channels: `<type>:<vaultId>` records, `members:`, `keys:`, `owner:`, `title:`, `inbox:<device>`, `notes-blobs:<vaultId>`.
- The relay is an untrusted append-only log: records are never rewritten, only appended; authority is client-side (owner-signed roster/epochs/transfers); vault keys never user-visible.

## Invariants (verbatim)

1. A failed pull returns null = abort, never empty. Folding a failure as empty reads every baseline-present id as deleted.
2. A failed local read surfaces as failure (`LocalReadException` / `listLocalOk == false`), never as an empty list. An empty list means "provably holds nothing locally".
3. Only explicit tombstones delete; absence without a tombstone resurrects (soft-delete types) or quarantines — never deletes.
4. `remoteOut` appends to the relay BEFORE persisting locally.
5. Passwords hard-delete (no tombstone rows); notes/todos soft-delete (tombstone rows persist and propagate); calendar/contacts soft-delete via provider `DELETED` flags.
6. Passwords vaults never grant VIEWER.

## Sink mapping summary

- `CalendarSystemSink : SyncSink<RemoteEvent>` — `RemoteEvent` <-> iCal VEVENT (`core.format.ICalendar`; UID + RECURRENCE-ID identity; RRULE verbatim, duration-instead-of-end for masters). Android: `_SYNC_ID`/`SYNC_DATA1`/`SYNC_DATA2` + `CALLER_IS_SYNCADAPTER` + `DIRTY`/`DELETED`. Apple: `EKEvent`. Linux: `libecal`. Windows: `AppointmentStore` read-only v1.
- `ContactsSystemSink : SyncSink<RemoteContact>` — `RemoteContact` <-> vCard (`core.format.VCard`; shared `PhoneKind`/`EmailKind`/`PostalKind` ints). Android: `SOURCE_ID`/`SYNC1`/`SYNC2` + `CALLER_IS_SYNCADAPTER` + `DIRTY`/`DELETED`. Apple: `CNContactStore`. Linux: `libebook`. Windows: `ContactStore` read-only v1.
- `TodoSystemSink : SyncSink<RemoteTodo>` — `RemoteTodo` JSON on `todos:<vaultId>` <-> native reminder (`EKReminder` / `libecal` todo / Notes-view row). `notes` + `createdAt` must persist (envelope + hash inputs); tombstones are real rows; `position` is ordering-only.

## Drift fixes applied

- Todo `notes`/`createdAt`: persisted in the envelope AND the merge hash (`TodoKind.canonical`); native stores must persist both or LWW on those fields never converges.
- Passwords KDoc: `RemotePassword` documents the native vault-field mirror (no KDBX mapping) and the password/passkey halves; `PasswordKind`/`EntryMapper` document hard-delete + tombstone-marker semantics.

## Verification matrix

- Slices: `:library:everysync-core:compileKotlinJvm`, `:library:everysync-core:lint` (no Gradle run in this change per scope).
- Tests (existing, must stay green): `RelayConformanceTest`, `SharedTransportTest`, `SyncDigestTest`, `TodoKindTest` (commonTest, 25 tests) + `JvmPqcVectorConformanceTest` (jvmTest, 6 tests).
- Live-relay checklist: register → getkey → append → pull round-trip against `https://api.maproj.org/personal` (migrated from findfamily.cc; findfamily.cc remains valid for backward compatibility); null-pull abort; tombstone propagation per type; re-enable repopulation after purge.

## Next steps

1. Desktop providers (`desktop/`): implement `sinks/` over EDS (Linux) and read-only stores + Notes-view todos (Windows v1).
2. Apple providers (`apple/`): implement `sinks/` over EventKit + `CNContactStore`.
3. Live-relay pass per target using the checklist above.
