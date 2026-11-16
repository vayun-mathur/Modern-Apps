# AGENTS.md — pdf/ (':pdf')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `pdf/` + allowed shared modules. Do not root-scan.

_Install: ./install pdf (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :pdf / dir: pdf/
- Package roots present: ui
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
**Architecture / data-flow:** `MainActivity` hosts `Navigation.kt` (`Route.Home/Viewer/Capture/CapturePage/Crop/CutGlue`) over a single `util/PdfViewModel` + `util/PdfStateStore`, with `SafePdf*` utils wrapping the Rust engine for document access, page rendering, and edits; capture flows through `model/CapturedImage` → `AutoFrameDetector` → `ComposePdfDocument` → `PdfExporter`. `onCreateBeforeContent` only captures the launch intent's data URI; `Content()` picks viewer vs capture vs cut-glue vs picker.
**Entry points + reading order:** `MainActivity.kt` + `Route.kt` + `Navigation.kt` (`InitialScreen` → viewer/capture/cut-glue) → `ui/SafePdfViewerScreen.kt` → `util/PdfViewModel.kt` → `util/SafePdfDocument.kt` → `util/SafePdfParser.kt`.
**Gotchas:**
- Started-with-intent launches `finish()` on back instead of returning to the picker; in-app launches reset to the picker
- Card drift fixed: `util/` gained `FrameGeometry`, `FrameImageOps`, `SafePdfDocumentEdits`, `SafePdfPrimitives`; `ui/` is `SafePdf*` + capture/crop/cut-glue screens (no separate `domain/` or `platform/`)
- Extra non-conforming dirs (`model/`, `util/`) hold real code — closed-roots migration pending

## Key files
### Root — entry + navigation
- com/vayunmathur/pdf/MainActivity.kt — host, intent → Route.Viewer routing
- com/vayunmathur/pdf/Route.kt — Home/Viewer(uri)/Capture/CapturePage(index)/Crop(index)/CutGlue
- com/vayunmathur/pdf/Navigation.kt — MainNavigation host
### model/ — capture model
- com/vayunmathur/pdf/model/CapturedImage.kt
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
### util/ — ViewModel, engine bridge, capture pipeline (migration pending)
- com/vayunmathur/pdf/util/PdfViewModel.kt
- com/vayunmathur/pdf/util/PdfStateStore.kt
- com/vayunmathur/pdf/util/PdfNative.kt — Rust engine bridge
- com/vayunmathur/pdf/util/SafePdfDocument.kt
- com/vayunmathur/pdf/util/SafePdfDocumentEdits.kt
- com/vayunmathur/pdf/util/SafePdfParser.kt
- com/vayunmathur/pdf/util/SafePdfModels.kt
- com/vayunmathur/pdf/util/SafePdfPrimitives.kt
- com/vayunmathur/pdf/util/SafePdfImages.kt
- com/vayunmathur/pdf/util/SafePdfListings.kt
- com/vayunmathur/pdf/util/AutoFrameDetector.kt
- com/vayunmathur/pdf/util/FrameGeometry.kt
- com/vayunmathur/pdf/util/FrameImageOps.kt
- com/vayunmathur/pdf/util/ComposePdfDocument.kt
- com/vayunmathur/pdf/util/PdfExporter.kt

## Verify (this module only)
```
./gradlew :pdf:compileDevKotlin
./gradlew :pdf:lint
./gradlew :pdf:checkMetadata
```
