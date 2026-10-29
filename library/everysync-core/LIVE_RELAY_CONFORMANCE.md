# Live-relay conformance — staging `https://findfamily.cc/personal`

Scripted manual checklist for a human (or a scripted device pair) to verify a
real relay deployment speaks the shared-core protocol. **No automation runs
this file** — each step lists the exact endpoint, channel, and envelope shape
to exercise, and the assertion to record. Automated coverage of the same
contract lives in `src/commonTest/.../network/RelayConformanceTest.kt` (fake
transport) and `SharedTransportTest.kt`.

Prerequisites: two clean installs (device A = owner, device B = joiner),
`SharedRelayTransport.init()` succeeding on both (PQC identity in
`SecureStore` under `personalKemPub/Priv`, `personalDsaPub/Priv`; stable id
under `personalDeviceId`). Record both device ids.

## 1. `POST /register` + `POST /getkey`

1. A: `register()` → HTTP 200.
2. B: `register()` → HTTP 200.
3. A: `getKey(B)` → non-empty bundle; B: `getKey(A)` → non-empty bundle.
4. A: `getKey("nonexistent-device")` → null (unknown id, no crash).

## 2. Record channels — `POST /append` + `POST /pull`

Channel names (`RelayContract.kt`): records `passwords:<vault>` /
`notes:<vault>` / `todos:<vault>` / `contacts:<vault>` / `calendar:<vault>`,
`members:<vault>`, `keys:<vault>`, `owner:<vault>`, `title:<vault>`,
`inbox:<device>`, blobs `notes-blobs:<vault>`.

1. A creates vault V (`newVaultId()`), key K (`newVaultKey()`, 32 bytes).
2. A: `appendRecords(recordChannel("notes", V), K, [noteJsonA, noteJsonB])` → seq S1 > 0.
3. B (same K, out of band = sealed invite, step 5): `pullRecords(channel, K, 0)`
   → `items == [noteJsonA, noteJsonB]`, `rawCount == 2`, `undecryptable == 0`,
   `fullyUndecryptable == false`, `seq == S1`.
4. B: `pullRecords(channel, WRONG_KEY, 0)` → `items.isEmpty()`,
   `undecryptable == 2`, `fullyUndecryptable == true` → caller MUST abort, not merge.

## 3. B64 AES record shape

1. `pullRaw(channel, 0)` → each envelope is valid Base64
   (`Base64.decode` succeeds) and `AesGcm.decrypt(K, bytes)` yields the
   plaintext JSON; IV-prepended layout (`[12B iv][ct||tag]`), same as Android
   `E2ee` and both `AesGcm` actuals.

## 4. Double-wrapped roster + epoch (`VaultPolicy.kt`)

1. A: `VaultRoster.recordMembers(V, [Member(B, role=editor)])` → true.
2. B: `fetchRoster(V, ownerKey)` → contains B as editor; tamper with one
   envelope on a scratch channel (append garbage) → skipped, roster still resolves.
3. A: `VaultKeys.rotateKey(V, epoch=0, {A: bundleA, B: bundleB})` → new key K2.
4. B: `fetchCurrentKey(V, inviteKey=K, ownerKey)` → `(1, K2)`; `K2 != K`.
5. Records appended under K2 do not decrypt under K (epoch isolation).

## 5. Sealed invite + join request (`RelayEnvelopes.kt`)

1. B: `sendJoinRequest(ownerId=A, V, "notes", B, name)` → true; lands on
   `inbox:A` with `kind:"req"` discriminator.
2. A: `pullInbox(0)` → `requests` contains B's request, `invites` empty.
3. A: `sendInvite(B, V, "notes", K_current, title, editor, ownerKeyB64, A)` → true.
4. B: `pullInvites(0)` → one invite, `vaultId == V`, `key` Base64-decodes to
   `K_current`. Relay log shows only ciphertext (spot-check: no plaintext
   title/key in raw `pullRaw(inbox:B, 0)` output).

## 6. `everysync://join` link parse (`RelayLinks`)

1. `shareLinkFor(V, A, "notes")` → `https://maproj.org/es/share#id=V&owner=A&type=notes`;
   contains no `key` (case-insensitive).
2. `parseJoinLink("everysync", "join", V, A, "notes")` → `Triple(V, A, "notes")`;
   wrong scheme / blank vault / blank owner → null.

## 7. The three invariants (abort conditions — verify each against staging)

1. **Failed pull returns null, never empty**: kill network mid-`pullRaw` (or
   point at a bad host) → null → `SyncDriver.drive` returns false and merges
   nothing. Provably-empty channel → empty list (merge proceeds).
2. **Only explicit tombstones delete**: delete a note locally, sync, pull on B
   → B's row is tombstoned (`deleted=true` envelope in the folded log), never
   silently dropped; an id absent from the log with no tombstone is resurrected,
   not deleted (`RelayMerge.reconcileLocalOnly` / `reconcileRemoteOnly`).
3. **Vault keys never user-visible**: share links carry ids only (step 6);
   keys move exclusively via PQC-sealed inbox invites (step 5); no key material
   in logcat, share sheets, or error messages.

## Sign-off

| Step | Date | Relay build | Pass? | Notes |
| ---- | ---- | ----------- | ----- | ----- |
| 1 register/getkey | 2026-10-18 | staging `https://api.maproj.org/personal` → HTTP 404 `Not Found` on all `/personal/*` routes (host serves static 200 at `/`) | BLOCKED | `POST /register` → 404 body=`Not Found` (also via pwsh `Invoke-WebRequest`, GET `/personal` → 404, trailing-slash + plain-HTTP variants → 404). `JvmRelayTransport.init()` false on both A+B. Runner: `LiveRelayConformanceTest` step 1 BLOCKED. |
| 2 append/pull | 2026-10-18 | same 404 staging | BLOCKED | No channel reachable; step never executed. Runner step 2 BLOCKED with the same 404 evidence. |
| 3 B64 AES shape | 2026-10-18 | same 404 staging (relay half) | partial — local half PASS | Local half genuinely executed+asserted with JVM `AesGcm`: IV-prepended `[12B iv][ct\|\|tag]` (pt+28B), random IV (two encryptions differ, both decrypt), B64 envelope round-trip (runner step 3-local PASS). Relay half BLOCKED (no `pullRaw` possible). |
| 4 roster/epoch | 2026-10-18 | same 404 staging | BLOCKED | Never executed against staging; runner step 4 BLOCKED. Scripted: roster editor + garbage-skip, `rotateKey` → epoch 1 K2, epoch isolation (runner `phaseRosterEpoch`). |
| 5 invite/join | 2026-10-18 | same 404 staging | BLOCKED | Never executed against staging; runner step 5 BLOCKED. Scripted: join-req `kind:req` → invite resolves vault+key → raw inbox ciphertext scan (runner `phaseInviteJoin`). |
| 6 join parse | 2026-10-18 | n/a (pure local) | PASS | Genuinely executed+asserted: share link ids-only, parse round-trips, bad scheme/host/blank ids → null (runner step 6 PASS). |
| 7 invariants | 2026-10-18 | same 404 staging (relay halves) | partial — local halves PASS | 7.1 PASS asserted: kill-network `pullRaw` → null → `SyncDriver.drive` false, zero writes. 7.2 merge half PASS asserted: absent-without-tombstone resurrects, `deleted=true` envelope tombstones. 7.3 local half PASS asserted: link ids-only + PQC-sealed envelope has no title/key substrings and opens for the recipient. Relay halves (live tombstone propagation) BLOCKED on the 404. |
