# AGENTS.md — clock-wear/ (':clock:wear')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `clock-wear/` + `clock/` (the phone side of the same feature).

- Gradle: :clock:wear (dir clock-wear/) / dir: clock-wear/ (convention: `common-conventions-wear`)
- Package roots present: ui, data, domain, platform, sync
- Entry file: ui/WearMainActivity.kt

## Why this module exists (read before changing identity)
Wear OS companion of the phone Clock (`:clock`), replacing the stock watch
clock utilities. This module deliberately uses
`applicationId = "com.vayunmathur.clock"` (the phone app's) with the shared
dev/release signing from the convention plugins: WearLink's handshake token
requires the same signing certificate on both sides, so changing either
breaks phone<->watch sync. Bootstrap is a one-time ADB sideload of this APK
onto the watch.

## Protocol
`domain/WearClockProtocol.kt` is a copy of the phone's file (Gradle cannot
depend on another app module). Change both copies together; each side's
`WearClockProtocolTest` pins the same golden JSON.

- Snapshot + watch-state sync: WearLink data items (`/clock/snapshot`, `/clock/watch-state`)
- Sync request: WearLink message (`/clock/request-sync`), watch -> phone only
- Transport: :library:wearlink (RFCOMM, no Play services); app-lifecycle-bound

## Watch-native vs synced (do not blur this)
- **Synced from the phone (snapshot):** world clocks (display only by
  nature) and the alarm list. A synced alarm rings on the watch only
  because `platform/WatchAlarmScheduler` arms it with the watch's own
  AlarmManager on snapshot arrival / boot. The UI calls it armed only
  from persisted `ArmedAlarmRecord` proof (`domain/WearAlarmState.kt`);
  enabled-but-unarmed alarms are labelled display-only with the reason.
- **Watch-native (never synced):** timers (`platform/WatchTimerScheduler`
  + `WatchTimerReceiver`, presets in `domain/WatchTimerState.kt`) and the
  stopwatch. They are created, armed, paused and rung entirely on the
  watch and survive reboot via `platform/BootReceiver`.

## Verify (this module only)
```
./gradlew :clock:wear:assembleDev
./gradlew :clock:wear:testDebugUnitTest
```
