# AGENTS.md — findfamily-wear/ (':findfamily-wear')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `findfamily-wear/` + `findfamily/` (the phone side of the same feature).

- Gradle: :findfamily-wear / dir: findfamily-wear/ (convention: `common-conventions-wear`)
- Package roots present: ui, data, domain, sync
- Entry file: ui/WearMainActivity.kt

## Why this module exists (read before changing identity)
Wear OS companion of the phone FindFamily (`:findfamily`). This module
deliberately uses `applicationId = "com.vayunmathur.findfamily"` (the
phone app's) with the shared dev/release signing from the convention
plugins: WearLink's handshake token requires the same signing
certificate on both sides, so changing either breaks phone<->watch sync.
Bootstrap is a one-time ADB sideload of this APK onto the watch.

## Protocol
`domain/WearFindFamilyProtocol.kt` is a copy of the phone's file (Gradle
cannot depend on another app module). Change both copies together; each
side's `WearFindFamilyProtocolTest` pins the same golden JSON.

- Snapshot: WearLink data item (`/findfamily/snapshot`, phone -> watch) —
  ALL people in the circle as location summaries (place label, E7 coord,
  fix/report times, accuracy, battery, motion), no history/track.
- Watch state: WearLink data item (`/findfamily/watch-state`)
- Ops: WearLink message (`/findfamily/op`: STOP_SHARING_NOW,
  REQUEST_REFRESH); request-sync message (`/findfamily/request-sync`)
- Transport: :library:wearlink (RFCOMM, no Play services); app-lifecycle-bound

## What the watch shows
People home (every person, most-recent first), per-person detail via a
Route carrying the person id (precise fix age/accuracy/place + relative
view), and an all-people relative map centred on the phone-supplied
region (self when located). There is no vector basemap: the phone
FindFamily app owns no planet archive, so markers are plotted relative
to the centre — never fake precision, stale fixes are labelled by age.
The last snapshot persists on-watch for offline reading.

## Verify (this module only)
```
./gradlew :findfamily-wear:assembleDev
./gradlew :findfamily-wear:testDebugUnitTest
```
