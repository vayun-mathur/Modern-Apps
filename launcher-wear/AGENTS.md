# AGENTS.md — launcher-wear/ (':launcher:wear')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `launcher-wear/` + `launcher/` (the phone side of the same feature).

- Gradle: :launcher:wear (dir launcher-wear/) / convention: `common-conventions-wear`
- Package roots present: ui, data, domain, platform, sync
- Entry file: ui/WearMainActivity.kt

## Why this module exists (read before changing identity)
Wear OS launcher-style companion of the phone Launcher (`:launcher`): a
full-screen app drawer / home-replacement *experience* on the watch. This
module deliberately uses `applicationId = "com.vayunmathur.launcher"` (the
phone app's) with the shared dev/release signing from the convention
plugins: WearLink's handshake token requires the same signing certificate
on both sides, so changing either breaks phone<->watch sync. Bootstrap is
a one-time ADB sideload of this APK onto the watch.

## What a watch launcher can and cannot be (platform reality)
- **Stock Wear OS: NOT the system HOME.** The watch home (watch face +
  system app launcher) is a privileged system component. There is no
  public `CATEGORY_HOME` election or `ROLE_HOME` grant for third-party
  apps on stock Wear OS builds (unlike phone/TV, where `:launcher`
  legitimately declares HOME). This module therefore declares only
  MAIN+LAUNCHER and must never claim HOME: users open/pin this app in the
  system launcher and get the launcher experience inside it.
- **What this provides:** app drawer for installed watch apps
  (icon+label, platform launch intents), favorites/pinned row synced
  from the phone hotseat, watch-local recents, quick access to our wear
  companions when installed, local search/filter, and settings rows that
  open only system intents that resolve on the watch (no fake toggles -
  brightness has no public direct intent, so Display opens system
  display settings).
- **Tiles/complications: not provided.** No wear module in this repo uses
  Tiles and `androidx.wear.tiles` is not in the version catalog; adding
  it is a supply-chain/build decision, not a cheap add-on here.
- **MAOS follow-up (OS-side only, not implemented here):** a privileged
  MAOS build could grant this package a real HOME/watch-launcher role
  (privileged permission / default-home configuration). That belongs in
  the OS build, never in this app module.

## Protocol
`domain/WearLauncherProtocol.kt` is a copy of the phone's file (Gradle
cannot depend on another app module). Change both copies together; each
side's `WearLauncherProtocolTest` pins the same golden JSON.

- Favorites + watch-state sync: WearLink data items
  (`/launcher/favorites`, `/launcher/watch-state`)
- Sync request: WearLink message (`/launcher/request-sync`), watch -> phone only
- Only the phone hotseat (pinned row) syncs - not the workspace grid,
  folders or widgets. Recents are watch-local and never sync.
- Transport: :library:wearlink (RFCOMM, no Play services); app-lifecycle-bound

## Verify (this module only)
```
./gradlew :launcher:wear:assembleDev
./gradlew :launcher:wear:testDebugUnitTest
./gradlew :launcher:wear:lint
```
