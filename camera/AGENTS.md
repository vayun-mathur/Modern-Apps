# AGENTS.md — camera/ (':camera')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `camera/` + allowed shared modules. Do not root-scan.

_Install: ./install camera (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :camera / dir: camera/
- Package roots present: ui, domain, platform
- Entry files: MainActivity.kt (no Route.kt — `Route` is inline, single-screen app)
- Deps: :library:ml
- Metadata: metadata_data/camera.md (present)
- Rust: yes / screenshotTest: hand-committed

## Overview
**Purpose:** The most featureful open-source camera app — usable as the system camera and from the lockscreen. 100% offline.
**Key features:**
- Photo + video capture (CameraX, works regardless of Camera2 extension support) with per-mode sessions: single, portrait, night, slo-mo, video
- Panorama stitching + night-burst alignment in the native Rust `camera_stitch` engine
- Portrait mode with on-device segmentation (MediaPipe selfie + MODNet matting on a Vulkan compute runtime, `.maml` assets)
- QR/barcode scanning (zxing) and optional GPS geotagging written into EXIF
**Architecture / data-flow:** Single-screen `ui/CameraScreen` + `util/CameraViewModel` over per-mode `util/` session objects; `domain/` holds pure lens-selection logic, `platform/` capability queries; heavy work (stitch, night align, bokeh) goes through Rust JNI (`StitchNative`) and lands in MediaStore via `MediaStoreSaver`. `onCreateBeforeContent` only honors the `ACTION_IMAGE_CAPTURE` capture-for-result intent; `Content()` sits behind a camera+mic permission gate.
**Entry points + reading order:** `MainActivity.kt` (`Route.Camera`/`Settings` inline) → `ui/CameraScreen.kt` → `util/CameraViewModel.kt` → `util/CameraSessions.kt` → `util/PanoramaEngine.kt` / `NightCaptureEngine.kt`.
**Gotchas:**
- `util/` is NOT a closed package root — the `util/`→closed-roots migration is pending; new platform/engine code still lands there, don't add more
- Screenshots are hand-captured (no metadata convention plugin — Layoutlib can't open a camera); committed listing images live under `metadata_data/photos/camera/phone/`
- Volume keys act as the shutter, gated on the camera route being foreground; Esc/space covers desktop keyboards
- `.maml` model assets ship uncompressed (`noCompress`) — never compress them

## Key files
### Root — entry (Route inline, no Route.kt)
- com/vayunmathur/camera/MainActivity.kt
### domain/ — pure lens-selection logic
- com/vayunmathur/camera/domain/LensModel.kt
- com/vayunmathur/camera/domain/LensSelectionLogic.kt
- com/vayunmathur/camera/domain/ArgbToI420Converter.kt — bulk ARGB→YUV 4:2:0 for the Motion Photo clip
### platform/ — capability queries
- com/vayunmathur/camera/platform/CameraCapabilities.kt
- com/vayunmathur/camera/platform/LensSelector.kt
### ui/ — single screen + settings + panes
- com/vayunmathur/camera/ui/CameraScreen.kt
- com/vayunmathur/camera/ui/CameraScreenState.kt
- com/vayunmathur/camera/ui/CameraScreenKit.kt
- com/vayunmathur/camera/ui/CameraScreenEffects.kt
- com/vayunmathur/camera/ui/CameraPreviewBox.kt
- com/vayunmathur/camera/ui/CameraPreviewOverlays.kt
- com/vayunmathur/camera/ui/CameraOverlays.kt
- com/vayunmathur/camera/ui/CameraPanoramaOverlay.kt
- com/vayunmathur/camera/ui/CameraTopBar.kt
- com/vayunmathur/camera/ui/CameraBottomControls.kt
- com/vayunmathur/camera/ui/CameraShutterPane.kt
- com/vayunmathur/camera/ui/CameraShutterRow.kt
- com/vayunmathur/camera/ui/CameraModeBars.kt
- com/vayunmathur/camera/ui/CameraModesPane.kt
- com/vayunmathur/camera/ui/CameraSettingsBars.kt
- com/vayunmathur/camera/ui/CameraSettingsColumn.kt
- com/vayunmathur/camera/ui/CameraStatusIndicators.kt
- com/vayunmathur/camera/ui/CameraWideLayout.kt
- com/vayunmathur/camera/ui/LensBar.kt
- com/vayunmathur/camera/ui/SettingsPage.kt
- com/vayunmathur/camera/ui/CameraPostProcessingSettings.kt
### util/ — ViewModel, sessions, engines, persistence (migration pending)
- com/vayunmathur/camera/util/CameraViewModel.kt
- com/vayunmathur/camera/util/CameraViewModelActions.kt
- com/vayunmathur/camera/util/CameraViewModelTypes.kt
- com/vayunmathur/camera/util/CameraStartup.kt
- com/vayunmathur/camera/util/CameraSessions.kt
- com/vayunmathur/camera/util/CameraPhotoSessions.kt
- com/vayunmathur/camera/util/CameraPhotoSessionSetup.kt
- com/vayunmathur/camera/util/CameraPhotoSessionShared.kt
- com/vayunmathur/camera/util/CameraPortraitSession.kt
- com/vayunmathur/camera/util/CameraSingleCapture.kt
- com/vayunmathur/camera/util/CameraPhotoCapture.kt
- com/vayunmathur/camera/util/CameraCaptureProcessing.kt
- com/vayunmathur/camera/util/CameraVideoSession.kt
- com/vayunmathur/camera/util/CameraVideoRecording.kt
- com/vayunmathur/camera/util/CameraSloMoSession.kt
- com/vayunmathur/camera/util/CameraManualControls.kt
- com/vayunmathur/camera/util/CameraControls.kt
- com/vayunmathur/camera/util/CameraNightMode.kt
- com/vayunmathur/camera/util/CameraNightCapture.kt
- com/vayunmathur/camera/util/NightCaptureEngine.kt
- com/vayunmathur/camera/util/PanoramaEngine.kt
- com/vayunmathur/camera/util/GpuStitcher.kt
- com/vayunmathur/camera/util/StitchNative.kt — JNI bridge into `camera_stitch`
- com/vayunmathur/camera/util/PanoXmp.kt
- com/vayunmathur/camera/util/PortraitBokeh.kt
- com/vayunmathur/camera/util/BokehAnalyzer.kt
- com/vayunmathur/camera/util/PhotoAnalyzer.kt
- com/vayunmathur/camera/util/MotionPhotoEncoder.kt
- com/vayunmathur/camera/util/MotionPhotoWriter.kt
- com/vayunmathur/camera/util/VideoProcessor.kt
- com/vayunmathur/camera/util/CodecSupport.kt
- com/vayunmathur/camera/util/MediaStoreSaver.kt
- com/vayunmathur/camera/util/CameraSettings.kt
- com/vayunmathur/camera/util/CameraSaveLocation.kt
- com/vayunmathur/camera/util/CameraSaveRouting.kt

## Verify (this module only)
```
./gradlew :camera:compileDevKotlin
./gradlew :camera:lint
./gradlew :camera:checkMetadata
```
