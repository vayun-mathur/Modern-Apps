# WearLink — first-party Wear Data Layer replacement

The Google Wearable Data Layer is proprietary Play services. WearLink
reimplements the parts Modern-Apps actually used, over platform Bluetooth,
with no Play services anywhere.

## Transport choice

**Bluetooth Classic RFCOMM for everything, over the existing bond.**

- Wear OS watches are already BT-bonded to the phone by the system
  companion, but third-party apps cannot ride that system link — we open
  our own RFCOMM service (UUID `4d41574c-0001-…`, name
  `ModernApps WearLink`) on top of the same bond.
- Candidates come from `BluetoothManager.adapter.bondedDevices`
  (`getBondedDevices()`); RFCOMM connect is
  `createRfcommSocketToServiceRecord`, accept is
  `listenUsingRfcommWithServiceRecord` (both verified against the local
  android-37 SDK). Secure/bonded sockets only.
- **Both sides listen and both sides dial**, so either side can initiate.
  When both dial at once, the handshake's per-process instance ids break
  the tie: the pair initiated by the smaller instance id survives, on both
  ends, deterministically.
- Why not BLE GATT as primary: a GATT design (watch = server advertising,
  phone = central) would need BLUETOOTH_ADVERTISE/SCAN, MTU negotiation
  and characteristic chunking — workable for messages, but APK-sized
  streams become slow, fiddly MTU plumbing. RFCOMM is an ordered stream
  socket with socket flow control: bulk is just bytes. BLE's only win
  would be lower idle power for discovery, and we do not discover at all
  (bonded lookup instead), so BLE is not used.

## Layer mapping (vs Data Layer)

| Data Layer | WearLink |
|---|---|
| CapabilityClient | Bonded-set candidates + HELLO handshake for `appId` (the old capability string, reused verbatim). `WearLinkState` replaces node reachability. |
| MessageClient | `MESSAGE` frames: fire-and-forget, optional `MESSAGE_ACK`, or `RESPONSE`-correlated requests (ids + timeout + duplicate suppression). Same paths/payloads as before. |
| DataClient (DataItem) | `DATA` frames: path-keyed document, generation + chunk index/count + total size + SHA-256; applied only when complete and verified. No 100 KB cap; replaces contacts' hand-rolled chunk paths and appstore/calendar's DataMap JSON. |
| ChannelClient | `STREAM_OPEN/CHUNK/CLOSE` on the same session: total size + SHA-256 header, receiver `STREAM_ACK` carries its durable offset, sender seeks — **resumable** across reconnects when the sink kept its partial file. |
| WearableListenerService | *Nothing.* See degradations. |

Golden-JSON payloads inside messages/data items are unchanged — only the
transport underneath them changed.

## Framing & reliability

`[u32 length][u8 type][u64 id][u16 path len][path][body]`, capped at
64 KiB per frame, 256 KiB per message, 4 MiB per data item, 200 MiB per
stream. Corrupt length/type fails the session closed. All IO runs on
`Dispatchers.IO` inside `WearLinkNode`; reconnects back off 1 s → 30 s.
Failure is surfaced through `WearLinkState` into each app's existing
phone/watch sync states (no fake "synced").

## Trust

Bonding proves the paired device and encrypts the link; it does not prove
the peer *app*. The handshake also requires: equal `appId`, and a token
`SHA-256(signingCertHash ‖ peerNonce ‖ appId)` where each side derives the
expected hash from its **own** PackageManager signing certificate
(`GET_SIGNING_CERTIFICATES`). A differently-signed peer fails; the nonce
binding stops replay. Honest limitation: an APK certificate is public, so
a malicious app already on the already-bonded watch could compute a token
— WearLink proves *same-signed app on the paired device*, not more, and
no more is available to third-party apps (the signing private key is not
accessible to app code).

## Permissions

Library manifest declares only `BLUETOOTH_CONNECT` (API 31+) plus
`BLUETOOTH`/`BLUETOOTH_ADMIN` capped at API 30. No scan/advertise (we
never scan or advertise), and no location permission or
`neverForLocation` workaround. The hosting activity requests
`BLUETOOTH_CONNECT` at runtime; until granted the node reports
`PermissionMissing` instead of crashing.

## What degrades vs GMS (honest)

- **No background system wakeups.** `WearableListenerService` woke a dead
  app on every message/data change; WearLink listeners live and die with
  the app process. A watch request that arrives while the phone app is
  dead is not delivered until the phone app next runs and reconnects
  (then state documents republish/reconcile as before).
- **No store-and-forward.** DataItems were persisted by Play services and
  reconciled whenever devices met; WearLink data items are delivered on
  a live session and each app persists what it receives (all three wear
  apps already did). Publishing while disconnected is a no-op reported
  through state, not queued by the system.
- **Presence is session presence**, not system reachability: "watch
  connected" now means "our handshake completed", which is stricter and
  slower to become true (a connect attempt), not instantaneous.
- Same-package + same-signing is no longer a *transport* requirement
  (any of our app pairs could share WearLink), but all three pairs keep
  it: the handshake token still requires the same signing certificate.
