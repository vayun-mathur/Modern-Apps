# AGENTS.md — pdf/ (':pdf')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `pdf/` + allowed shared modules. Do not root-scan.

_Install: ./install pdf (dev by default)._
- Gradle: :pdf / dir: pdf/
- Package roots present: ui, data, platform, util
- Entry files: MainActivity.kt, Route.kt, Navigation.kt (Route host; see Navigation.kt)
- Deps: :library:image, :library:ocr
- Metadata: metadata_data/pdf.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** A simple open-source PDF viewer with capture and cut-and-glue assembly. No network permission at all — 100% offline.
**Key features:**
- Full viewer: text search, text selection, form fields, outlines, zoom state, print via share menu; several documents open as separate Recents entries
- Capture mode: photograph pages with auto-framing, crop, and compose them into a PDF; cut-and-glue screen for assembling pages
- Native Rust PDF engine (`PdfNative`) for parsing, rendering, and export
**Architecture / data-flow:** `MainActivity` hosts `Navigation.kt` (`Route.Home/Viewer/Capture/CapturePage/Crop/CutGlue`) over a single `platform/PdfViewModel` + `platform/PdfStateStore`, with `SafePdf*` platform wrappers around the Rust engine for document access, page rendering, and edits; capture flows through `data/CapturedImage` → `AutoFrameDetector` → `ComposePdfDocument` → `PdfExporter`. `onCreateBeforeContent` only captures the launch intent's data URI; `Content()` picks viewer vs capture vs cut-glue vs picker.
**Entry points + reading order:** `MainActivity.kt` + `Route.kt` + `Navigation.kt` (`InitialScreen` → viewer/capture/cut-glue) → `ui/SafePdfViewerScreen.kt` → `platform/PdfViewModel.kt` → `platform/SafePdfDocument.kt` → `platform/SafePdfParser.kt`.
**Gotchas:**
- Started-with-intent launches `finish()` on back instead of returning to the picker; in-app launches reset to the picker
- Document, frame, and edit code (`FrameGeometry`, `FrameImageOps`, `SafePdfDocumentEdits`, `SafePdfPrimitives`, the `SafePdf*` wrappers) is in `platform/`; `ui/` is `SafePdf*` + capture/crop/cut-glue screens; there is no `domain/` root
- `util/` holds only `PdfNative` (the Rust engine bridge) — don't add more files there; the capture model is in `data/`

## Key files
### Root — entry + navigation
- com/vayunmathur/pdf/MainActivity.kt — host, intent → Route.Viewer routing
- com/vayunmathur/pdf/Route.kt — Home/Viewer(uri)/Capture/CapturePage(index)/Crop(index)/CutGlue
- com/vayunmathur/pdf/Navigation.kt — MainNavigation host
### data/ — capture model
- com/vayunmathur/pdf/data/CapturedImage.kt
### ui/ — viewer chrome + state
- com/vayunmathur/pdf/ui/SafePdfViewerScreen.kt
- com/vayunmathur/pdf/ui/SafePdfViewerState.kt
- com/vayunmathur/pdf/ui/SafePdfViewerModels.kt
- com/vayunmathur/pdf/ui/SafePdfViewerChrome.kt
- com/vayunmathur/pdf/ui/SafePdfViewerLoad.kt
- com/vayunmathur/pdf/ui/SafePdfViewerDialogs.kt
- com/vayunmathur/pdf/ui/SafePdfPageViewer.kt
- com/vayunmathur/pdf/ui/SafePdfPageCanvas.kt
- com/vayunmathur/pdf/ui/SafePdfPageItem.kt
- com/vayunmathur/pdf/ui/PdfViewerWideLayout.kt
- com/vayunmathur/pdf/ui/PdfOutlineDrawer.kt
- com/vayunmathur/pdf/ui/SafePdfTextSelection.kt
- com/vayunmathur/pdf/ui/SafePdfFormFields.kt
### ui/ — safe-draw rendering kit
- com/vayunmathur/pdf/ui/SafePdfDrawContext.kt
- com/vayunmathur/pdf/ui/SafePdfDrawState.kt
- com/vayunmathur/pdf/ui/SafePdfDrawStrokes.kt
- com/vayunmathur/pdf/ui/SafePdfDrawFills.kt
- com/vayunmathur/pdf/ui/SafePdfDrawText.kt
- com/vayunmathur/pdf/ui/SafePdfDrawImages.kt
- com/vayunmathur/pdf/ui/SafePdfBlendModes.kt
- com/vayunmathur/pdf/ui/SafePdfSelectionGlyphs.kt
- com/vayunmathur/pdf/ui/SafePdfZoomState.kt
### ui/ — edit overlays + tooling
- com/vayunmathur/pdf/ui/SafePdfEditOverlay.kt
- com/vayunmathur/pdf/ui/SafePdfEditPreview.kt
- com/vayunmathur/pdf/ui/SafePdfEditToolbar.kt
- com/vayunmathur/pdf/ui/SafePdfNonEditOverlay.kt
- com/vayunmathur/pdf/ui/SafePdfToolMenus.kt
- com/vayunmathur/pdf/ui/SafePdfStyleDialog.kt
- com/vayunmathur/pdf/ui/SafePdfLaunchers.kt
### ui/ — capture + assembly screens
- com/vayunmathur/pdf/ui/CapturePdfScreen.kt
- com/vayunmathur/pdf/ui/CropScreen.kt
- com/vayunmathur/pdf/ui/CropScreenSection.kt
- com/vayunmathur/pdf/ui/CutGlueScreen.kt
- com/vayunmathur/pdf/ui/components/CameraPreview.kt
- com/vayunmathur/pdf/ui/components/SubcroppedImage.kt
### platform/ — ViewModel, engine wrappers, capture pipeline
- com/vayunmathur/pdf/platform/PdfViewModel.kt
- com/vayunmathur/pdf/platform/PdfStateStore.kt
- com/vayunmathur/pdf/platform/SafePdfDocument.kt
- com/vayunmathur/pdf/platform/SafePdfDocumentEdits.kt
- com/vayunmathur/pdf/platform/SafePdfParser.kt
- com/vayunmathur/pdf/platform/SafePdfModels.kt
- com/vayunmathur/pdf/platform/SafePdfPrimitives.kt
- com/vayunmathur/pdf/platform/SafePdfImages.kt
- com/vayunmathur/pdf/platform/SafePdfListings.kt
- com/vayunmathur/pdf/platform/AutoFrameDetector.kt
- com/vayunmathur/pdf/platform/FrameGeometry.kt
- com/vayunmathur/pdf/platform/FrameImageOps.kt
- com/vayunmathur/pdf/platform/ComposePdfDocument.kt
- com/vayunmathur/pdf/platform/PdfExporter.kt
### util/ — Rust engine bridge
- com/vayunmathur/pdf/util/PdfNative.kt — Rust engine bridge

## Verify (this module only)
```
./gradlew :pdf:compileDevKotlin
./gradlew :pdf:lint
./gradlew :pdf:checkMetadata
```
