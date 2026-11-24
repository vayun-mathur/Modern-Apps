# AGENTS.md — appstore/wear/ (':appstore:wear')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `appstore/wear/` + the rest of `appstore/` (the phone side of the same feature).

- Gradle: :appstore:wear / dir: appstore/wear/ (convention: `common-conventions-wear`)
- Package roots present: ui, data, domain, platform, service
- Entry file: ui/WearMainActivity.kt

## Why this module exists (read before changing identity)
Wear OS companion of the phone App Store (`:appstore`). This module
deliberately uses `applicationId = "com.vayunmathur.appstore"` (the phone
app's) with the shared dev/release signing from the convention plugins:
WearLink's handshake token requires the same signing certificate on both
sides, so changing either breaks phone<->watch sync. Bootstrap is a
one-time ADB sideload of this APK onto the watch.

## Protocol
`domain/WearSyncProtocol.kt` is a byte-identical copy of the phone's file
(Gradle cannot depend on another app module). Change both copies together;
each side's `WearSyncProtocolTest` pins the same golden JSON.

- Catalog + watch-state sync: WearLink data items (`/appstore/catalog`, `/appstore/watch-state`)
- Offers / install requests / results: WearLink messages
- APK bytes: WearLink stream (`/appstore/apk/<package>`), verified size+SHA-256
  against the preceding offer before PackageInstaller ever sees them
- Transport: :library:wearlink (RFCOMM, no Play services); delivery is
  app-lifecycle-bound — no system listener wakes a dead process

## Install honesty
A normal Wear OS app **cannot silently install**. Verified APKs wait in
app-private storage until the wearer taps Install and confirms the system
PackageInstaller prompt. No state may claim otherwise.

## Verify (this module only)
```
./gradlew :appstore:wear:assembleDev
./gradlew :appstore:wear:testDebugUnitTest
./gradlew :appstore:wear:lint
```
