# AGENTS.md — camera-wear/ (':camera-wear')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `camera-wear/` + `camera/` (the phone side of the same feature).

- Gradle: :camera-wear / dir: camera-wear/ (convention: `common-conventions-wear`)
- Package roots present: ui, data, domain, sync
- Entry file: ui/WearMainActivity.kt

## Why this module exists (read before changing identity)
Wear OS companion of the phone Camera (`:camera`): a live viewfinder
streamed from the phone camera plus remote controls executed on the
phone. This module deliberately uses
`applicationId = "com.vayunmathur.camera"` (the phone app's) with the
shared dev/release signing from the convention plugins: WearLink's
handshake token requires the same signing certificate on both sides, so
changing either breaks phone<->watch control. Bootstrap is a one-time
ADB sideload of this APK onto the watch. The watch has no camera here —
with no phone nearby the app says so; it never fakes a local camera.

## Protocol
`domain/WearCameraProtocol.kt` is a copy of the phone's file (Gradle
cannot depend on another app module). Change both copies together; each
side's `WearCameraProtocolTest` pins the same golden JSON/frame bytes.

- Mirrored state + last-capture thumbnail: WearLink data items
  (`/camera/state`, `/camera/thumbnail` JPEG)
- Watch-originated controls: WearLink message (`/camera/op`)
- Live viewfinder: WearLink messages (`/camera/preview-frame`), NOT the
  STREAM primitive — STREAM is a bounded file transfer (total size +
  SHA-256 committed up front, verified at close, resumable by file
  offset), which a live ephemeral frame sequence cannot satisfy. The
  extension lives behind the camera protocol seam; :library:wearlink is
  unchanged. Phone drops frames latest-only; watch drops stale sequences.
- Transport: :library:wearlink (RFCOMM, no Play services); app-lifecycle-bound

## Viewfinder design point
Phone analysis-stream frames downscaled to ≤320 px wide, JPEG-encoded
once at that size, adaptive ~2–8 fps (nominal 5) backing off when
encode+send exceeds the frame interval. Media stays on the phone.

## Verify (this module only)
```
./gradlew :camera-wear:assembleDev
./gradlew :camera-wear:testDebugUnitTest
```
