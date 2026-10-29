# EverySync Apple — manual end-to-end checklist (Phase 3, Mac + devices)

`xcodebuild` cannot run on the Windows box, so this checklist is the Phase 3
verification gate. Run top-to-bottom on a Mac with two iOS simulators (or one
simulator + one Android emulator for the cross-device matrix). Every step
references the file that implements it.

Conventions: vault keys NEVER user-visible (share links carry ids only; keys
move via PQC-sealed inbox invites). Failed pull = abort, never empty. Only
explicit tombstones delete.

## 0. Build

- [ ] `sh apple/XCFramework/build_xcframework.sh` succeeds (see
      `XCFramework/BUILD_NOTES.md`); the XCFramework links into all five
      targets (Passwords, Notes, Hub, Credential Provider, Share extension).
- [ ] `./gradlew :library:everysync-core:compileKotlinIosArm64`
      `:library:everysync-core:compileKotlinIosSimulatorArm64` green (see
      `XCFramework/IOS_ACTUALS_NOTES.md`).
- [ ] `xcodebuild` Passwords / Notes / Hub schemes build for simulator.

## 1. Permissions (fresh install per app)

- [ ] Passwords launch: Face ID prompt (`NSFaceIDUsageDescription`); deny ->
      vault list still renders, unlock rows disabled (no crash, no secret leak).
- [ ] Hub launch: Reminders prompt (`NSRemindersUsageDescription`),
      Calendars (`NSCalendarsUsageDescription`), Contacts
      (`NSContactsUsageDescription`). Deny EACH ONE individually and confirm
      the other two sinks still cycle (per-store health — `HubSync.swift`).
- [ ] Keychain: lock the simulator, unlock, confirm vault keys + cursors
      survived (`KeychainSecureStore.swift`:
      `AfterFirstUnlockThisDeviceOnly`).

## 2. Relay conformance (simulator <-> Android/desktop, both directions)

Against staging `https://api.maproj.org/personal` (see plan Phase 1F):

- [ ] `register` / `getkey` round-trip from the simulator (device id stable
      across restarts — `personalDeviceId` in Keychain).
- [ ] `append` / `pull` on `passwords:<vault>`, `notes:<vault>`,
      `todos:<vault>`: channel-name assertions pass; records are
      `B64(AES_GCM)` per item; roster/epoch/transfer envelopes double-wrap
      as `B64(Json(...))`.
- [ ] PQC-sealed invite to `inbox:<device>` decrypts on the recipient.
- [ ] `everysync://join` link parses (`RelayLinks.parseJoinLink` via the
      XCFramework; URL scheme fires the app).
- [ ] Kill the network mid-pull: cycle ABORTS, local data untouched (never
      folded as empty). Restore network: next pull resumes from the cursor
      (`relay_cursor:<type>:<vault>:<channel>` in Keychain).
- [ ] Tombstone propagation per type: delete a note/todo on Android, confirm
      the tombstone removes the `EKReminder` / note row on iOS; delete a
      password, confirm the row VANISHES (hard-delete) while the snapshot
      remembers it.
- [ ] Purge a vault on iOS (`EnvelopeIPC.purgeVault`), pull: vault
      re-populates from the relay (re-enable repopulation).

## 3. Passwords + Credential Provider (autofill)

- [ ] Create a password on iOS; `mergeCredentials` pairs its passkey on the
      detail page (explicit `PasskeyLink.To` survives; auto-match needs
      domain AND username on exactly one candidate; ties stay standalone).
- [ ] TOTP code matches Android for the same secret/epoch (6-digit,
      30s step — `Totp.generate` in the XCFramework).
- [ ] Autofill in Safari: `prepareCredentialList` offers the right site
      (`DomainMatch` — `notexample.com` must NOT match `example.com`);
      `provideCredentialWithoutUserInteraction` fills without UI;
      confirmation UI path works with Face ID.
- [ ] XPC: kill the containing app, autofill from the extension ALONE
      (reads via `com.vayunmathur.everysync.envelope-ipc` — extension never
      touches the relay).
- [ ] Share sheet offers EDITOR only — never VIEWER (`RelayRoles`).
      Passwords vaults never grant VIEWER.

## 4. Notes (NoteBody)

- [ ] Text block round-trips markdown verbatim (merge hashes it — no
      normalization in Swift).
- [ ] Image block: photo added on Android appears on iOS via
      `notes-blobs:<vaultId>` prefetch (`NoteKind.imageNames`); missing blob
      renders a placeholder, never a crash. `widthFraction` resize persists.
- [ ] Ink block: draw with PencilKit; strokes survive a sync cycle to Android
      and back (mapping: `PencilKitMapping.swift`; serialization in
      `serializeNoteBody`). `heightDp` default 300.
- [ ] Soft-delete: delete a note on iOS, confirm the tombstone row persists
      locally AND propagates (never raw-deletes).
- [ ] Share extension: share a URL from Safari -> new note with a text block
      in the active vault (hub picks it up next cycle; extension never talks
      to the relay).

## 5. Hub sinks (no Todo app — Reminders IS the UI)

- [ ] Todo created on Android appears in the system Reminders app with title,
      `notes` (persisted — merge-hash input), due date, done state.
      `position` reorder on one side never looks like an edit.
- [ ] `createdAt` adoption: pre-existing reminders adopt on first sync via
      `contentKey = "todo␀" + title + "␀" + createdAt`.
- [ ] Delete a reminder in the Reminders app: after the next HEALTHY fetch,
      the tombstone envelope propagates (known-minus-fetched in `SyncIdMap`).
      Airplane-mode delete + fetch failure: cycle aborts, nothing deleted.
- [ ] Contact round-trip: name parts, org, note, all typed phones/emails/
      addresses, birthday `yyyy-MM-dd`. Shared type ints pass through
      (`PhoneKind`/`EmailKind`/`PostalKind` tables in `ContactSink.swift`).
      Swift never writes syncIds into contact fields.
- [ ] Calendar round-trip: summary/desc/location, start/end, allDay,
      timezone display, RRULE verbatim (masters store duration), overridden
      instance merges independently (`uid␀recurrenceId` key).
- [ ] APNs silent push wakes the hub and triggers a PULL (payload carries
      nothing — verify with a proxy: push body has no vault data/keys).

## 6. Background sync

- [ ] `BGAppRefreshTaskRequest` fires for all three identifiers; each handler
      re-schedules at the end (system gives no repeating schedule).
- [ ] Local edit -> one-shot `BGProcessingTaskRequest` pushes within a minute.
- [ ] Force-quit + relaunch: schedules re-register, cursors intact, no
      duplicate appends (idempotent `upsertRaw` keyed on `syncId`).

## 7. Loss-prevention spot checks (the three invariants, verbatim)

1. [ ] Failed pull returns null = abort, never empty (unplug network mid-pull
      on each type; data untouched).
2. [ ] Failed local read surfaces as failure, never empty (revoke Reminders
      access mid-cycle; hub aborts that sink, keeps the others).
3. [ ] Only explicit tombstones delete (delete server-side WITHOUT a
      tombstone: record resurrects/quarantines — never vanishes).
