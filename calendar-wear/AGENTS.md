# AGENTS.md — calendar-wear/ (':calendar:wear')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `calendar-wear/` + `calendar/` (the phone side of the same feature).

- Gradle: :calendar:wear (dir calendar-wear/) / dir: calendar-wear/ (convention: `common-conventions-wear`)
- Package roots present: ui, data, domain, service
- Entry file: ui/WearMainActivity.kt

## Why this module exists (read before changing identity)
Wear OS companion of the phone Calendar (`:calendar`). This module
deliberately uses `applicationId = "com.vayunmathur.calendar"` (the phone
app's) with the shared dev/release signing from the convention plugins:
WearLink's handshake token requires the same signing certificate on both
sides, so changing either breaks phone<->watch sync. Bootstrap is a
one-time ADB sideload of this APK onto the watch.

## Protocol
`domain/WearCalendarProtocol.kt` is a copy of the phone's file (Gradle cannot
depend on another app module). Change both copies together; each side's
`WearCalendarProtocolTest` pins the same golden JSON.

- Snapshot + watch-state sync: WearLink data items (`/calendar/snapshot`, `/calendar/watch-state`)
- Sync request: WearLink message (`/calendar/request-sync`), watch -> phone only
- Transport: :library:wearlink (RFCOMM, no Play services); app-lifecycle-bound

## What the watch can and cannot do
Read-only. It renders today's agenda, upcoming events and event detail from
its locally stored snapshot, offline. There is no event editing or RSVP:
the phone model has no attendee/self-status for local calendars, so no
watch action claims to change an event. The one watch-originated write is
asking the phone for a fresh snapshot.

## Verify (this module only)
```
./gradlew :calendar:wear:assembleDev
./gradlew :calendar:wear:testDebugUnitTest
```
