# AGENTS.md — maps-wear/ (':maps:wear')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `maps-wear/` + `maps/` (the phone side of the same feature).

- Gradle: :maps:wear / dir: maps-wear/ (convention: `common-conventions-wear`)
- Package roots present: ui, data, domain, sync
- Entry file: ui/WearMainActivity.kt

## Why this module exists (read before changing identity)
Wear OS companion of the phone Maps (`:maps`). This module deliberately
uses `applicationId = "com.vayunmathur.maps"` (the phone app's) with the
shared dev/release signing from the convention plugins: WearLink's
handshake token requires the same signing certificate on both sides, so
changing either breaks phone<->watch sync. Bootstrap is a one-time ADB
sideload of this APK onto the watch.

## Protocol
`domain/WearMapsProtocol.kt` is a copy of the phone's file (Gradle cannot
depend on another app module). Change both copies together; each side's
`WearMapsProtocolTest` pins the same golden JSON.

- Region snapshot: WearLink data item (`/maps/region`, phone -> watch) —
  bounded, simplified, quantized vector geometry for a Canvas renderer;
  the watch carries no tile stack and no planet data
- Route overlay: WearLink data item (`/maps/route`, phone -> watch);
  `active=false` is the explicit clear when navigation ends
- Position: WearLink data item (`/maps/position`, phone -> watch)
- Watch state: WearLink data item (`/maps/watch-state`, watch -> phone)
- Fresh region: WearLink message (`/maps/request-region`), watch -> phone
- Transport: :library:wearlink (RFCOMM, no Play services); app-lifecycle-bound

## Execution model (stated, not implied)
The PHONE supplies the region and is the position source (its location
manager / navigation session); the watch runs no GPS of its own. The
phone republishes the region when the user nears the snapshot edge
(hysteresis, 65% of radius); between republishes the watch moves the
user dot and follows it within the last region. With the link down the
last region renders with a stale/offline label — never as live data.
North-up in v1; the renderer is a plain Canvas projection, so bearing
rotation is a cheap future step, not claimed here. The phone has no
Home/Work saved-place concept, so the saved glance shows saved places
with distance — no ETA is invented.

## Verify (this module only)
```
./gradlew :maps:wear:assembleDev
./gradlew :maps:wear:testDebugUnitTest
```
