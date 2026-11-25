# AGENTS.md — camera/ (':camera')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `camera/` + allowed shared modules. Do not root-scan.

_Install: ./install camera (dev by default)._
- Gradle: :camera / dir: camera/
- Package roots present: ui, domain, platform
- Entry files: MainActivity.kt, Route.kt (`Route.Camera` / `Route.Settings`)
- Deps: :library:ml
- Metadata: metadata_data/camera.md (present)
- Rust: yes / screenshotTest: hybrid (phone hand-captured; wide controls preview-rendered)

## Overview
**Purpose:** The most featureful open-source camera app — usable as the system camera and from the lockscreen. 100% offline.
**Key features:**
- Photo + video capture on a direct Camera2 implementation (`platform/camera2/` driver, sessions, extension and high-speed sessions) with per-mode sessions: single, portrait, night, slo-mo, video
- Panorama stitching + night-burst alignment in the native Rust `camera_stitch` engine
- Portrait mode with on-device segmentation (MediaPipe selfie + MODNet matting on a Vulkan compute runtime, `.maml` assets)
- QR/barcode scanning (zxing) and optional GPS geotagging written into EXIF
**Architecture / data-flow:** Single-screen `ui/CameraScreen` + `platform/CameraViewModel` over per-mode `platform/` session objects on the `platform/camera2/` driver; `domain/` holds pure lens-selection logic, `platform/` capability queries; heavy work (stitch, night align, bokeh) goes through Rust JNI (`platform/StitchNative`) and lands in MediaStore via `data/MediaStoreSaver`. `onCreateBeforeContent` only honors the `ACTION_IMAGE_CAPTURE` capture-for-result intent; `Content()` sits behind a camera+mic permission gate.
**Entry points + reading order:** `MainActivity.kt` → `Route.kt` (`Route.Camera`/`Settings`) → `ui/CameraScreen.kt` → `platform/CameraViewModel.kt` → `platform/CameraSessions.kt` → `platform/PanoramaEngine.kt` / `NightCaptureEngine.kt`.
**Gotchas:**
- Capture code lives in `platform/` (the Camera2 driver in `platform/camera2/`); there is no `util/` root in this module
- Screenshots are hand-captured (no metadata convention plugin — Layoutlib can't open a camera); committed listing images live under `metadata_data/photos/camera/phone/`
- Volume keys act as the shutter, gated on the camera route being foreground; Esc/space covers desktop keyboards
- `.maml` model assets ship uncompressed (`noCompress`) — never compress them

## Key files
### Root — entry + navigation
- com/vayunmathur/camera/MainActivity.kt
- com/vayunmathur/camera/Route.kt
### domain/ — pure lens-selection logic
- com/vayunmathur/camera/domain/LensModel.kt
- com/vayunmathur/camera/domain/LensSelectionLogic.kt
- com/vayunmathur/camera/domain/ArgbToI420Converter.kt — bulk ARGB→YUV 4:2:0 for the Motion Photo clip
- com/vayunmathur/camera/domain/MotionSaveQueue.kt — bound on Motion Photos still saving after the shutter
- com/vayunmathur/camera/domain/StillCaptureModePolicy.kt — ZSL vs MINIMIZE_LATENCY choice
- com/vayunmathur/camera/domain/PanoXmp.kt
- com/vayunmathur/camera/domain/MotionPhotoWriter.kt
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
- com/vayunmathur/camera/ui/CaptureFlashOverlay.kt
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
### platform/ — ViewModel, sessions, engines
- com/vayunmathur/camera/platform/CameraViewModel.kt
- com/vayunmathur/camera/platform/CameraViewModelActions.kt
- com/vayunmathur/camera/platform/CameraViewModelTypes.kt
- com/vayunmathur/camera/platform/CameraStartup.kt
- com/vayunmathur/camera/platform/CameraSessions.kt
- com/vayunmathur/camera/platform/CameraSingleCapture.kt
- com/vayunmathur/camera/platform/CameraPhotoCapture.kt
- com/vayunmathur/camera/platform/CameraCaptureProcessing.kt
- com/vayunmathur/camera/platform/CameraVideoRecording.kt
- com/vayunmathur/camera/platform/Camera2VideoRecordingEngine.kt
- com/vayunmathur/camera/platform/Camera2SloMoGlue.kt
- com/vayunmathur/camera/platform/CameraManualControls.kt
- com/vayunmathur/camera/platform/CameraControls.kt
- com/vayunmathur/camera/platform/CameraNightMode.kt
- com/vayunmathur/camera/platform/CameraNightCapture.kt
- com/vayunmathur/camera/platform/NightCaptureEngine.kt
- com/vayunmathur/camera/platform/PanoramaEngine.kt
- com/vayunmathur/camera/platform/GpuStitcher.kt
- com/vayunmathur/camera/platform/StitchNative.kt — JNI bridge into `camera_stitch`
- com/vayunmathur/camera/platform/PortraitBokeh.kt
- com/vayunmathur/camera/platform/BokehAnalyzer.kt
- com/vayunmathur/camera/platform/PhotoAnalyzer.kt
- com/vayunmathur/camera/platform/MotionPhotoEncoder.kt
- com/vayunmathur/camera/platform/VideoProcessor.kt
- com/vayunmathur/camera/platform/CodecSupport.kt
### platform/camera2/ — Camera2 driver
- com/vayunmathur/camera/platform/camera2/Camera2Driver.kt
- com/vayunmathur/camera/platform/camera2/Camera2Session.kt
- com/vayunmathur/camera/platform/camera2/Camera2ExtensionSession.kt
- com/vayunmathur/camera/platform/camera2/Camera2HighSpeedSession.kt
- com/vayunmathur/camera/platform/camera2/StillCapture.kt
- com/vayunmathur/camera/platform/camera2/StreamSelection.kt
### data/ — settings + saving
- com/vayunmathur/camera/data/MediaStoreSaver.kt
- com/vayunmathur/camera/data/CameraSettings.kt
- com/vayunmathur/camera/data/CameraSaveLocation.kt
- com/vayunmathur/camera/data/CameraSaveRouting.kt

## Verify (this module only)
```
./gradlew :camera:compileDevKotlin
./gradlew :camera:lint
./gradlew :camera:checkMetadata
```
