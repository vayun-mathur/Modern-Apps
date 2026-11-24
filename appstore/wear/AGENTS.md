# AGENTS.md — appstore/wear/ (':appstore:wear')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `appstore/wear/` + the rest of `appstore/` (the phone side of the same feature).

- Gradle: :appstore:wear / dir: appstore/wear/ (convention: `common-conventions-wear`)
- Package roots present: ui, data, domain, platform, service
- Entry file: ui/WearMainActivity.kt

## Why this module exists (read before changing identity)
Wear OS companion of the phone App Store (`:appstore`). The Wearable Data
Layer only connects apps sharing a **package name and signing certificate**,
so this module deliberately uses `applicationId = "com.vayunmathur.appstore"`
(the phone app's) with the shared dev/release signing from the convention
plugins. Changing either breaks phone<->watch sync silently. Bootstrap is a
one-time ADB sideload of this APK onto the watch.

## Protocol
`domain/WearSyncProtocol.kt` is a byte-identical copy of the phone's file
(Gradle cannot depend on another app module). Change both copies together;
each side's `WearSyncProtocolTest` pins the same golden JSON.

- Catalog + watch-state sync: DataClient (`/appstore/catalog`, `/appstore/watch-state`)
- Offers / install requests / results: MessageClient
- APK bytes: ChannelClient (`/appstore/apk/<package>`), verified size+SHA-256
  against the preceding offer before PackageInstaller ever sees them

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
