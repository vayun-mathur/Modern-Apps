# AGENTS.md — photos/ (':photos')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `photos/` + allowed shared modules. Do not root-scan.

_Install: ./install photos (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :photos / dir: photos/
- Package roots present: ui, data, domain, platform, service
- Entry files: MainActivity.kt (no Route.kt — `Route` + `Navigation` are inline)
- Deps: :library:map, :library:image, :library:ml, :library:room, :library:ink, :library:widgets, :library:biometric, :library:ocr, :library:downloadservice
- Metadata: metadata_data/photos.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** A photos app with a full non-destructive editor and on-device AI search — faces, text, and content all indexed locally. Internet only for basemap tiles behind the map view.
**Key features:**
- Gallery with adjustable grid, albums, people (on-device face grouping), map view, trash, and separate photo/video editors (`EditActivity`/`VideoEditActivity`)
- Non-destructive editor: curves, HSL, levels, healing, liquify, layers, selective edits, undo/redo — edits stored as `data/*Data` docs, composited by `LayerCompositor`
- On-device search by content (CLIP), by text (OCR layout), and by person (face recognition + segmentation); biometric-locked secure folder with optional relock-on-exit
- Wallpaper/live-wallpaper, Glance home widget, motion-photo support, and background sync worker
**Architecture / data-flow:** `ui/` pages over `util/` ViewModels (`GalleryViewModel`, `PhotoEditViewModel`, `VideoEditViewModel`, `PhotoMapViewModel`, `SecureFolderViewModel`) → `data/` Room (`PhotoDatabase`, `VaultDatabase`, `PhotosRepository`/`VaultRepository`) plus MediaStore indexing (`AlbumMediaStore`); ML (faces, CLIP, OCR, segmentation) runs on `:library:ml` with a Rust native bridge (`PhotosNative`). `onCreateBeforeContent`: widget previews + `ImageLoader.init`; `Content()` provides the column-count state behind a media-permissions gate into inline `Navigation()`.
**Entry points + reading order:** `Route` (`Gallery` / `PhotoPage` / `Map` / `People` / `Albums` / `Trash` / `SecureFolder` / `VaultViewer` / `Wallpaper`, inline) → `MainActivity.kt` → `ui/GalleryPage.kt` → `util/GalleryViewModel.kt` → `data/PhotosRepository.kt`.
**Gotchas:**
- Re-tapping a photo replaces the viewer (`pushPhoto`/`setLast`) instead of stacking — don't `add()` photo routes or back steps through stale viewers
- `ACTION_VIEW` from another app (e.g. the camera) makes that one photo the stack root with no gallery beneath; in-app back handling must not swallow the system predictive-back gesture there
- `MaFragmentActivity` (not `MaActivity`) — biometric + fragment-hosting requirement
- Extra non-conforming dirs (`glance/`, `util/`) hold real code — closed-roots migration pending

## Key files
### Root — entry + inline navigation
- com/vayunmathur/photos/MainActivity.kt — Route, Navigation, pushPhoto, relock logic
### data/ — Room + edit-document model
- com/vayunmathur/photos/data/PhotosRepository.kt
- com/vayunmathur/photos/data/PhotoDatabase.kt
- com/vayunmathur/photos/data/Photo.kt
- com/vayunmathur/photos/data/VaultRepository.kt
- com/vayunmathur/photos/data/VaultDatabase.kt
- com/vayunmathur/photos/data/EditDocument.kt
- com/vayunmathur/photos/data/ImageAdjustments.kt
- com/vayunmathur/photos/data/FxAdjustments.kt
- com/vayunmathur/photos/data/MoreAdjustmentsData.kt
- com/vayunmathur/photos/data/LayerData.kt
- com/vayunmathur/photos/data/LayerBlendMode.kt
- com/vayunmathur/photos/data/CurvesData.kt
- com/vayunmathur/photos/data/LevelsData.kt
- com/vayunmathur/photos/data/HslData.kt
- com/vayunmathur/photos/data/ColorBalanceData.kt
- com/vayunmathur/photos/data/ChannelMixerData.kt
- com/vayunmathur/photos/data/BlackAndWhiteData.kt
- com/vayunmathur/photos/data/BlurData.kt
- com/vayunmathur/photos/data/SharpenData.kt
- com/vayunmathur/photos/data/NoiseData.kt
- com/vayunmathur/photos/data/HealingData.kt
- com/vayunmathur/photos/data/ContentAwareData.kt
- com/vayunmathur/photos/data/LiquifyData.kt
- com/vayunmathur/photos/data/DodgeBurnData.kt
- com/vayunmathur/photos/data/GradientMapData.kt
- com/vayunmathur/photos/data/StylizeData.kt
- com/vayunmathur/photos/data/PerspectiveData.kt
- com/vayunmathur/photos/data/SelectionData.kt
- com/vayunmathur/photos/data/SelectiveEditData.kt
- com/vayunmathur/photos/data/Drawing.kt
- com/vayunmathur/photos/data/PaintOpsData.kt
- com/vayunmathur/photos/data/SmudgeData.kt
- com/vayunmathur/photos/data/OcrLayout.kt
- com/vayunmathur/photos/data/FaceData.kt
- com/vayunmathur/photos/data/RedEyeData.kt
- com/vayunmathur/photos/data/VideoEditState.kt
- com/vayunmathur/photos/data/BitmapExtensions.kt
### domain/ — OCR, motion-photo and face-grouping logic (pure)
- com/vayunmathur/photos/domain/PhotoOcr.kt
- com/vayunmathur/photos/domain/MotionPhotoXmp.kt
- com/vayunmathur/photos/domain/FaceAlignment.kt — five-point ArcFace alignment
- com/vayunmathur/photos/domain/FaceQuality.kt — which faces may form people
- com/vayunmathur/photos/domain/FaceClustering.kt — batch average-linkage grouping
- com/vayunmathur/photos/domain/FaceClusterIdentity.kt — keeps person ids/names across regroups
### glance/ — home widget
- com/vayunmathur/photos/glance/PhotoGlanceWidget.kt
- com/vayunmathur/photos/glance/PhotoGlanceWidgetReceiver.kt
### platform/ — motion-photo + ET models
- com/vayunmathur/photos/platform/MotionPhotoVideo.kt
- com/vayunmathur/photos/platform/PhotosEtModels.kt
### service/ — live wallpaper
- com/vayunmathur/photos/service/LiveWallpaperService.kt
### ui/ — gallery, albums, people, map
- com/vayunmathur/photos/ui/GalleryPage.kt
- com/vayunmathur/photos/ui/GalleryScreen.kt
- com/vayunmathur/photos/ui/GalleryGrouping.kt
- com/vayunmathur/photos/ui/AlbumsPage.kt
- com/vayunmathur/photos/ui/AlbumsScreen.kt
- com/vayunmathur/photos/ui/AlbumDetailPage.kt
- com/vayunmathur/photos/ui/AlbumDetailScreen.kt
- com/vayunmathur/photos/ui/AlbumPickerDialog.kt
- com/vayunmathur/photos/ui/PeoplePage.kt
- com/vayunmathur/photos/ui/MapPage.kt
- com/vayunmathur/photos/ui/TrashPage.kt
- com/vayunmathur/photos/ui/ImageFit.kt
### ui/ — viewer + metadata
- com/vayunmathur/photos/ui/PhotoPage.kt
- com/vayunmathur/photos/ui/PhotoDetailView.kt
- com/vayunmathur/photos/ui/PhotoDetailSection.kt
- com/vayunmathur/photos/ui/PhotoDetailMedia.kt
- com/vayunmathur/photos/ui/PhotoMetadataCard.kt
- com/vayunmathur/photos/ui/PhotoFilmstrip.kt
- com/vayunmathur/photos/ui/PhotoViewerActions.kt
- com/vayunmathur/photos/ui/PhotoViewerWideLayout.kt
- com/vayunmathur/photos/ui/PhotoZoomGestures.kt
- com/vayunmathur/photos/ui/EditRoute.kt
- com/vayunmathur/photos/ui/EditActivity.kt
- com/vayunmathur/photos/ui/FaceBoxLayer.kt
- com/vayunmathur/photos/ui/OcrTextLayer.kt
- com/vayunmathur/photos/ui/PanoramaFlatView.kt
- com/vayunmathur/photos/ui/PanoramaSphereView.kt
### ui/ — editor panels + canvas
- com/vayunmathur/photos/ui/EditPhotoPage.kt
- com/vayunmathur/photos/ui/EditPhotoEditorState.kt
- com/vayunmathur/photos/ui/EditPhotoModels.kt
- com/vayunmathur/photos/ui/EditPhotoModelHelpers.kt
- com/vayunmathur/photos/ui/EditPhotoCanvas.kt
- com/vayunmathur/photos/ui/EditPhotoPanels.kt
- com/vayunmathur/photos/ui/EditPhotoActivePanel.kt
- com/vayunmathur/photos/ui/EditPhotoAdjustPanels.kt
- com/vayunmathur/photos/ui/EditPhotoBrushPanels.kt
- com/vayunmathur/photos/ui/EditPhotoSelectionPanels.kt
- com/vayunmathur/photos/ui/EditPhotoBottomControls.kt
- com/vayunmathur/photos/ui/EditPhotoCropOverlay.kt
- com/vayunmathur/photos/ui/EditPhotoToolOverlays.kt
- com/vayunmathur/photos/ui/EditPhotoModeEffect.kt
- com/vayunmathur/photos/ui/EditPhotoTextDialog.kt
- com/vayunmathur/photos/ui/AdvancedPanels.kt
- com/vayunmathur/photos/ui/BlurPanel.kt
- com/vayunmathur/photos/ui/BlurOverlay.kt
- com/vayunmathur/photos/ui/CurvesPanel.kt
- com/vayunmathur/photos/ui/HslPanel.kt
- com/vayunmathur/photos/ui/HealingPanel.kt
- com/vayunmathur/photos/ui/HealingOverlay.kt
- com/vayunmathur/photos/ui/LayersPanel.kt
- com/vayunmathur/photos/ui/LayerRows.kt
- com/vayunmathur/photos/ui/MaskOverlay.kt
- com/vayunmathur/photos/ui/SelectiveEditPanel.kt
- com/vayunmathur/photos/ui/DrawingSettingsPage.kt
### ui/ — video editor + wallpaper + vault
- com/vayunmathur/photos/ui/VideoEditRoute.kt
- com/vayunmathur/photos/ui/VideoEditActivity.kt
- com/vayunmathur/photos/ui/VideoEditPage.kt
- com/vayunmathur/photos/ui/VideoEditPanels.kt
- com/vayunmathur/photos/ui/VideoEditPreview.kt
- com/vayunmathur/photos/ui/VideoEditCropOverlay.kt
- com/vayunmathur/photos/ui/VideoEditDialogs.kt
- com/vayunmathur/photos/ui/VideoPlayer.kt
- com/vayunmathur/photos/ui/WallpaperPage.kt
- com/vayunmathur/photos/ui/WallpaperPreview.kt
- com/vayunmathur/photos/ui/WallpaperControls.kt
- com/vayunmathur/photos/ui/SecureFolderPage.kt
- com/vayunmathur/photos/ui/VaultViewerPage.kt
- com/vayunmathur/photos/ui/VaultViewerScreen.kt
### util/ — ViewModels, ML, compositing, sync (migration pending)
- com/vayunmathur/photos/util/GalleryViewModel.kt
- com/vayunmathur/photos/util/GalleryUiContract.kt
- com/vayunmathur/photos/util/PhotoEditViewModel.kt
- com/vayunmathur/photos/util/VideoEditViewModel.kt
- com/vayunmathur/photos/util/PhotoMapViewModel.kt
- com/vayunmathur/photos/util/SecureFolderViewModel.kt
- com/vayunmathur/photos/util/SecureFolderManager.kt
- com/vayunmathur/photos/util/LayerCompositor.kt
- com/vayunmathur/photos/util/UndoRedoManager.kt
- com/vayunmathur/photos/util/OverlayRendering.kt
- com/vayunmathur/photos/util/FaceRecognizer.kt
- com/vayunmathur/photos/util/FaceCropTransformation.kt
- com/vayunmathur/photos/util/MlSegmentation.kt
- com/vayunmathur/photos/util/ClipEmbedder.kt
- com/vayunmathur/photos/util/ClipTokenizer.kt
- com/vayunmathur/photos/util/ImageLoader.kt
- com/vayunmathur/photos/util/PanoXmpParser.kt
- com/vayunmathur/photos/util/PhotosNative.kt — Rust bridge
- com/vayunmathur/photos/util/AlbumMediaStore.kt
- com/vayunmathur/photos/util/SyncWorker.kt
- com/vayunmathur/photos/util/SyncWorkerHelper.kt
- com/vayunmathur/photos/util/LiveWallpaperLauncher.kt
- com/vayunmathur/photos/util/WallpaperHelper.kt
- com/vayunmathur/photos/util/AppBackupAgent.kt

## Verify (this module only)
```
./gradlew :photos:compileDevKotlin
./gradlew :photos:lint
./gradlew :photos:checkMetadata
```
