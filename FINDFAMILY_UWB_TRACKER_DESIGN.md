# FindFamily Custom UWB Tracker — Design

Design for adding **custom, DIY UWB item-trackers** to FindFamily: binding a
tracker to its owner, and a global crowd-finding network so any phone running
FindFamily can anonymously report a tracker's location back to its owner —
plus phone-native UWB precision finding for the last few meters.

Hardware target: **nRF52 MCU + Qorvo DW3000** (FiRa-capable UWB). The reference
build is a **Qorvo DWM3001CDK** (nRF52833 + DW3110); firmware lives in
`firmware/findfamily-tracker/`.

---

## Implementation status (v1 scaffold, DEV_BUILD-gated)

A first full-stack scaffold is implemented behind `BuildConfig.DEV_BUILD`
(true for `assembleDev`/`assembleDebug`, false for `assembleRelease`). Chosen
crypto: **rotating-hash + server-served ML-KEM key** (max reuse; server can
link a rotating id → tracker, accepted tradeoff).

Client (`findfamily/`):
- `build.gradle.kts` — `DEV_BUILD` BuildConfig flag; `src/dev/AndroidManifest.xml` — BLE permissions (dev only).
- `data/User.kt` (`UserKind`, `kind`) + `data/FFDatabase.kt` (migration 9→10).
- `tracker/` — `TrackerFeature` (gate), `TrackerProtocol` (epoch-id HMAC + report seal/open, unit-tested), `TrackerStore` (owner secret/private-key store), `TrackerBle` (BLE contract), `TrackerBeaconScanner` (finder scan), `TrackerProvisioner` (GATT binding), `TrackerBinder` (bind flow), `TrackerReporting` (finder upload + owner fetch), `TrackerUwbGatt` (FiRa-over-GATT seam).
- `util/Networking.kt` — opcodes 0x06–0x0B + `registerTracker`/`resolveTrackerBundle`/`uploadTrackerReport`/`fetchTrackerReports`.
- `util/LocationTrackingService.kt` — finder scan + owner report polling (gated).
- UI — dev-only "Add tracker" FAB (`ui/MainPage.kt`) + `ui/dialogs/AddTrackerDialog.kt` + `Route.AddTrackerDialog`.
- Test — `src/test/.../tracker/TrackerProtocolTest.kt`.

Server (`~/Documents/code/location_share_server`):
- `src/handlers/findfamily.rs` — same opcodes 0x06–0x0B (TRACKER_REGISTER / RESOLVE / REPORT_PUT / REPORT_GET) with HMAC epoch-id, lazy TTL purge, unit tests.
- `src/state.rs`, `src/main.rs` — `ff_trackers` + `ff_reports` maps.

Remaining seams: DULT anti-stalking detector; and UWB ranging end to end — the
phone side is written (`TrackerUwbGatt.startRanging`) and the firmware side
(`src/tracker_uwb.c`) is written but **staged behind `CONFIG_FF_TRACKER_UWB`
(default `n`) and never compiled**, because the licensed Qorvo DW3xxx driver is
not vendored. BLE crowd-finding firmware is implemented and flashable.

---

## 1. Locked decisions

| Area | Decision | Rationale |
|---|---|---|
| Tracker identity | Model as `User(kind = TRACKER)` | Reuses map pins, Room DB, the E2E relay, and `processIncomingLocations` with near-zero UI churn — the whole app already keys on `userid`. |
| Crowd-finding crypto | **Rotating-hash + server-served ML-KEM key** | The originally locked "Option B" (rotating per-epoch EC keys, §5 below) was **not** what shipped. Option B hides the tracker from the server structurally; what shipped lets the server link a rotating id → tracker, which is a real weakening, accepted in exchange for reusing the existing PQC identity plumbing wholesale instead of adding an X25519 path to the app, the server and the firmware. Revisit if the threat model tightens. |
| UWB | Phone-native **FiRa** ranging; **Android-only** OOB for v1 | The phone's UWB radio speaks FiRa; Android interop only needs matched session params exchanged out-of-band (no Apple MFi/NI needed yet). |
| UWB hardware | **Qorvo DW3110** (DWM3000 module, built on DW3110) + own MCU + **Qorvo FiRa stack** | DW3110 is 802.15.4z with STS, channels 5 & 9 → real FiRa sessions that range with phones. RYUW122 as a *sealed module* runs proprietary AT-command ranging and can't interop with phones. |
| Provisioning | **BLE GATT** | Same radio as the crowd-finding beacon; wireless binding. |

---

## 2. Why not the RYUW122 as-is, and why UWB (not BLE) for finding

- The RYUW122 is built on a **Qorvo DW3000** (FiRa-capable silicon), but it is a
  sealed module whose onboard MCU runs **Reyax's proprietary AT-command**
  ranging over UART. That firmware is not FiRa and (almost certainly) not
  reflashable — so a phone cannot range with it. The fix is to keep the DW3000
  chip but use a board whose MCU **we** flash with a FiRa stack.
- **UWB is not a discovery radio** — it has no advertising/beaconing mode. The
  global "any phone notices it" network therefore rides **BLE advertising**
  (discovery + crowd reports), while **UWB/FiRa** is used only for the owner's
  precision finding once nearby. This mirrors how commercial UWB trackers work.

### Chip choice (locked): Qorvo DW3110

- **DW3110** — 802.15.4z + **STS**, channels **5 & 9** → interoperates with
  phone-native UWB. This is the silicon inside the **DWM3000** module.
- **Single RF port → no on-chip PDoA/AoA.** This is fine: the **phone** measures
  angle with its own multi-antenna array (`UwbController.stream()` reads
  azimuth/elevation from the phone's `RangingData`); the tracker only has to be a
  FiRa **responder**. Distance + arrow still work. The dual-port **DW3210** would
  only be needed if the *tracker* had to sense direction (not required here).
- **Module vs. bare chip**: start with the **DWM3000 module** (RF/antenna/
  shielding done + pre-tested) to skip RF headaches; drop to the bare **DW3110**
  on a custom PCB later if needed.
- **Rejected: DW1000** — first-gen 802.15.4-2011, **no STS**, no channel 9;
  cannot range with phones (same trap as the sealed RYUW122).

### Android FiRa OOB — the key simplification
To interop with an **iPhone**, an accessory must implement Apple's Nearby
Interaction Accessory Protocol (the `UwbAccessoryProtocol.kt` stub) and, for a
product, MFi. To interop with **Android**, the phone (FiRa **controller /
initiator**) picks the session params and hands them to the tracker (FiRa
**controlee / responder**) over any side channel. FindFamily already generates
exactly these params in `UwbController.openController()` and already exchanges
them for phone↔phone over its WebSocket. For the tracker we deliver them over
**BLE GATT** instead. This is meaningfully less work than a full MFi accessory.

Two of those params are deliberately **not** handed over: the **STS key** and the
tracker's **UWB address**. The GATT link to a tracker is unbonded and
unencrypted, so a stranger standing nearby could write their own key and range
against someone else's tracker. Both sides instead derive them from the bind-time
beacon secret (`TrackerUwbKeys.kt` on the phone, `src/tracker_sts.c` in the
firmware), and the per-find write carries only address, session id, channel and
preamble — 8 bytes, useless without the secret.

---

## 3. Entity model

Add a discriminator to `findfamily/src/main/java/com/vayunmathur/findfamily/data/User.kt`:

```
enum class UserKind { PERSON, TRACKER }
// User gains: val kind: UserKind = UserKind.PERSON
```

A tracker `User`:
- has its own random 64-bit `userid` and PQC bundle;
- is **owner-only** custodian of the tracker's **private** PQC bundle + **beacon
  master secret** (stored in DataStore, never synced);
- is non-interactive: no sharing toggles, no waypoint auto-toggle, no heartbeat
  publish. It only ever appears as a received location (a map pin).

A tracker report is just a `LocationValue` with `userid = tracker_userid`, so it
renders through the existing `processIncomingLocations` path unchanged.

---

## 4. Binding (goal a) — BLE GATT provisioning

1. Unprovisioned tracker advertises a BLE "new tracker" GATT service.
2. Phone scans → shows "New tracker found" → user taps **Bind**.
3. Phone mints locally:
   - random `userid`,
   - PQC keypair — reuse `Networking.generatePqcKeyPair()`,
   - 32-byte `beacon_secret`,
   - a long-term **UWB STS/session key** derived from `beacon_secret`.
4. Phone writes over a GATT **provisioning characteristic** a
   `[8B userid BE][32B beacon_secret][8B unix_seconds BE]` blob. The trailing
   timestamp is the tracker's only source of wall-clock time — it has no
   battery-backed RTC, and epoch ids are a function of `unix_seconds / 900`, so
   without it the tracker beacons ids outside the owner's search window and
   resolves to nothing. No UWB params are sent: channel and preamble come with each
   find, and the STS key and UWB address are derived (§2).
5. Phone persists `User(kind = TRACKER)` + private bundle + secret + the tracker's
   BLE address (needed to re-open GATT for a UWB find).
6. **Ownership = possession of the private key + secret.** Unbind requires the
   secret (anti-theft lock), so a found tracker can't be silently re-bound.

The tracker never runs PQC — it only derives rotating beacon keys (cheap). All
heavy crypto stays on phones.

---

## 5. Crowd finding (goal b) — as shipped

> The per-epoch EC scheme described in this section is the **original Option B
> design and is not what shipped**. What shipped is the rotating-hash scheme in
> `TrackerProtocol.kt`: `epochId = HMAC-SHA256(beacon_secret, "fftrk1" ||
> u64_be(epoch))[..16]`, with the location sealed to the tracker's ML-KEM public
> bundle which the server serves to finders. This section is kept because the
> privacy properties it describes are the ones to aim for if the scheme is
> revisited (see §1).

### 5.1 Beacon (tracker)
Every epoch (~15 min):
```
priv_epoch = HKDF(beacon_secret, epoch)
pub_epoch  = priv_epoch · G          // X25519 (recommended curve)
```
BLE-advertise `pub_epoch` (+ a battery byte). No static identifier is ever
broadcast, so the tracker can't be followed by ID. Cost on-device: one HKDF +
one EC scalar-mult per epoch.

### 5.2 Finder (any FindFamily phone)
- Add a **BLE scan callback** in `LocationTrackingService`, right beside
  `handleUwbEnvelopes()` (the foreground service is already the natural host).
- On hearing a tracker beacon:
  - build a `LocationValue` at the finder's **own** current GPS,
  - `ciphertext = ECIES(pub_epoch, LocationValue)`,
  - upload `{ SHA256(pub_epoch), ciphertext }` to the new report-upload endpoint.
- Rate-limit / dedup per `(pub_epoch, coarse-time)`; require decent GPS accuracy.
- The finder never learns whose tracker it is.

### 5.3 Server (new work — the critical piece outside this repo)
Two endpoints on `api.maproj.org`:
- `report-upload { hash, ciphertext }`
- `report-fetch-by-hash [hashes] -> [ciphertext]`
with a retention window (e.g. 7 days) + abuse/rate-limit policy. The server
sees only opaque hashes and ciphertext — it cannot link reports to a tracker or
an owner.

### 5.4 Owner retrieval
- Recompute the expected `SHA256(pub_epoch)` for the last N epochs.
- Fetch matching ciphertexts, decrypt with the per-epoch `priv_epoch`.
- Emit a `LocationValue` with `userid = tracker_userid` → flows through the
  existing `processIncomingLocations` → renders as the tracker's map pin. **No
  map/UI changes required.**

---

### 5.5 On-air format (as shipped)
The advertisement carries `[16B epochId][1B battery%]` as **service data** under the
128-bit beacon service UUID. That does **not** fit a legacy advertisement:
`Service Data — 128-bit UUID` costs `1B length + 1B type + 16B UUID = 18B`, so with
the 17-byte payload it is **35 bytes against the 31-byte legacy limit**. The full
16-byte UUID has to be on air because the phone reads it back via
`ScanRecord.getServiceData(ParcelUuid)`, so no legacy encoding works. The beacon is
therefore a **BLE 5 extended advertisement** (secondary PHY 1M, connectable so the
owner can still write UWB session params), and finder phones must scan with
`ScanSettings.Builder().setLegacy(false)`.

Pairing mode stays **legacy** — flags + a 128-bit service UUID is only 21 bytes — so
discovery of an unprovisioned tracker works on default scan settings.

---

## 6. UWB precision finding (owner, FiRa OOB)

Owner-only, once near the tracker:
- Reuse `UwbController.openController()` to mint FiRa params (phone = controller
  / initiator).
- Deliver params to the tracker over a **BLE GATT session characteristic**
  (instead of the WS `UwbEnvelope`). Because the STS key was derived at bind
  time, the per-find handoff carries only channel/slot.
- Both start the FiRa session; `UwbController.stream()` yields distance + AoA
  arrow **unchanged**.
- The `UwbAccessoryProtocol` stubs stay parked for a future iOS phase.

This reuses the entire ranging path; only the *handshake transport* changes
(BLE GATT vs. WebSocket envelope).

---

## 7. Anti-stalking (DULT)

Independent of the above and important to ship: a finder-side detector that
flags a **foreign beacon persisting across time and space** ("unknown tracker
traveling with you"), aligned with the cross-industry DULT specification.

---

## 8. Firmware responsibilities (DW3110 + own MCU)

Implemented in `firmware/findfamily-tracker/` (Zephyr / nRF Connect SDK):

- BLE GATT server: provisioning characteristic + per-find UWB-session
  characteristic (`src/tracker_ble.c`).
- BLE beacon: rotating epoch id per epoch, HMAC-SHA256 via PSA Crypto so it is
  byte-identical to the Kotlin and Rust sides (`src/tracker_epoch.c`).
- NVS persistence of the provisioning blob and time base (`src/tracker_store.c`).
- Qorvo FiRa **responder / controlee** session on the DW3000 — written but staged
  behind `CONFIG_FF_TRACKER_UWB` (default `n`) (`src/tracker_uwb.c`).
- **Critical-path risk**: a reliable FiRa responder with solid phone interop
  (STS / timing) is a multi-week embedded effort, and Qorvo stack access
  typically needs registration/NDA. The Android app-side work is comparatively
  small.

---

## 9. Phasing

1. **Entity + binding** — `User.kind`, BLE GATT provisioning, key/secret
   generation & storage.
2. **Crowd network** — beacon format, finder BLE scan + upload, server
   endpoints, owner fetch/decrypt → map.
3. **UWB precision find** — BLE GATT param handoff + firmware FiRa responder +
   wiring into `UwbSessionManager`.
4. **Anti-stalking** detector.

---

## 10. Open items to confirm

- ECIES curve — **X25519** is the clean pick.
- Epoch length vs. beacon-privacy / retrieval-cost tradeoff.
- Server retention window + abuse / rate-limit policy.
- Ownership of the server-side change (the one piece outside this repo).
- Verify (datasheet / Reyax) there is truly no FiRa/NI mode or reflash path on
  the RYUW122 before fully committing to new hardware.

---

## 11. Key existing code this builds on

- `findfamily/.../data/User.kt` — entity to extend with `kind`.
- `findfamily/.../data/LocationValue.kt` — report payload (already `userid`-keyed).
- `findfamily/.../util/Networking.kt` — PQC identity, relay, `generatePqcKeyPair()`.
- `findfamily/.../util/LocationTrackingService.kt` — foreground host for the BLE
  scan + `processIncomingLocations` (renders tracker pins for free).
- `findfamily/.../uwb/UwbController.kt` — FiRa param generation + `stream()`.
- `findfamily/.../util/UwbSessionManager.kt` — session state machine to wire the
  BLE GATT handshake into.
- `findfamily/.../uwb/UwbAccessoryProtocol.kt` — parked for future iOS interop.
