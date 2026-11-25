# AGENTS.md — music-wear/ (':music:wear')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `music-wear/` + `music/` (the phone side of the same feature).

- Gradle: :music:wear / dir: music-wear/ (convention: `common-conventions-wear`)
- Package roots present: ui, data, domain, sync
- Entry file: ui/WearMainActivity.kt

## Why this module exists (read before changing identity)
Wear OS companion of the phone Music (`:music`). This module deliberately
uses `applicationId = "com.vayunmathur.music"` (the phone app's) with the
shared dev/release signing from the convention plugins: WearLink's
handshake token requires the same signing certificate on both sides, so
changing either breaks phone<->watch sync. Bootstrap is a one-time ADB
sideload of this APK onto the watch.

## Protocol
`domain/WearMusicProtocol.kt` is a copy of the phone's file (Gradle cannot
depend on another app module). Change both copies together; each side's
`WearMusicProtocolTest` pins the same golden JSON.

- Page snapshots: WearLink data item (`/music/page`, phone -> watch),
  requested by message (`/music/page-request`, watch -> phone)
- Now-playing: WearLink data item (`/music/playback`, phone -> watch)
- Watch state: WearLink data item (`/music/watch-state`, watch -> phone)
- Playback/library ops: WearLink message (`/music/op`, watch -> phone)
- Search: WearLink message (`/music/search-request`, watch -> phone)
- Transport: :library:wearlink (RFCOMM, no Play services); app-lifecycle-bound

## Execution model (stated, not implied)
The phone owns the catalog and is the ONLY audio player. The watch stores
no music files and no whole catalog: it caches only visited page snapshots
and the latest playback state, and renders them offline. Every playback
action is an op executed by the phone's playback service; the watch UI
reflects the phone-reported state and never claims an unconfirmed result.

## Verify (this module only)
```
./gradlew :music:wear:assembleDev
./gradlew :music:wear:testDebugUnitTest
```
