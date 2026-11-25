# AGENTS.md — communicate-wear/ (':communicate-wear')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `communicate-wear/` + `communicate/` (the phone side of the same feature).

- Gradle: :communicate-wear / dir: communicate-wear/ (convention: `common-conventions-wear`)
- Package roots present: ui, data, domain, sync
- Entry file: ui/WearMainActivity.kt

## Why this module exists (read before changing identity)
Wear OS companion of the phone Communicate (`:communicate`). This module
deliberately uses `applicationId = "com.vayunmathur.communicate"` (the
phone app's) with the shared dev/release signing from the convention
plugins: WearLink's handshake token requires the same signing certificate
on both sides, so changing either breaks phone<->watch sync. Bootstrap is
a one-time ADB sideload of this APK onto the watch.

## Protocol
`domain/WearCommunicateProtocol.kt` is a copy of the phone's file (Gradle
cannot depend on another app module). Change both copies together; each
side's `WearCommunicateProtocolTest` pins the same golden JSON.

- Snapshot: WearLink data item (`/communicate/snapshot`, phone -> watch);
  watch state back (`/communicate/watch-state`)
- Thread pages: message request (`/communicate/request-thread`) ->
  message page (`/communicate/thread-page`), cached on the watch
- Alerts: messages (`/communicate/new-message`, `/communicate/incoming-call`)
- Watch ops: message (`/communicate/op`) + phone verdict
  (`/communicate/op-result`); ops stay pending until a success verdict
- Transport: :library:wearlink (RFCOMM, no Play services); app-lifecycle-bound

## Page mapping (mirrors the phone, stated in the protocol file too)
- Phone Messages -> watch conversation list (all merged lines)
- Phone Conversation -> watch thread view (paged, cached offline)
- Phone CallLogs -> watch call log (read-only)
- Phone voicemail -> watch voicemail indicators (no audio/URIs/headers)
- Omitted: Dialer, Accounts/registration, calling setup, Call screen,
  voicemail playback, media viewing, full settings, archive/mute (the
  phone UI offers neither), and answer-on-watch (no guaranteed watch
  audio route — decline only)

## Privacy
Disappearing-flagged content never crosses in body/snippet form (empty +
`contentExcluded` marker). E2EE plaintext is decrypted on the phone and
only capped text crosses the cert-bound link; no keys, serviceData, media
URLs or attachment bytes ever cross (media is a label only).

## Verify (this module only)
```
./gradlew :communicate-wear:assembleDev
./gradlew :communicate-wear:testDebugUnitTest
```
