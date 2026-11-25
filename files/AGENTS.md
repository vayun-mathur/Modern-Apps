# AGENTS.md — files/ (':files')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `files/` + allowed shared modules. Do not root-scan.

_Install: ./install files (dev by default)._
- Gradle: :files / dir: files/
- Package roots present: ui, data, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt (no `FilesApplication` class)
- Deps: :library:image
- Metadata: metadata_data/files.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Offline file manager — browse/move/copy/rename/delete across the device, built-in text editor, system file-picker provider, background long operations.
**Key features:**
- Directory listing with breadcrumbs, grid/list items, properties dialogs, clipboard (cut/copy/paste) and home screen
- Built-in text editor (`TextEditorActivity` + `TextEditorViewModel`); zip/unzip via workers with progress notifications
- SAF surfaces (`DocumentPickerActivity`, `StorageUris`) so other apps can pick files here
**Architecture / data-flow:** `ui/FilesShell` + directory pages → `platform/FilesViewModel` (+ `FilesUiContract`) → `platform/FilesListing` over SAF/MediaStore; long ops run in `ZipWorker`/`UnzipWorker` with `ProgressNotificationWorker` surfacing progress.
**Entry points + reading order:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/FilesShell.kt` → `platform/FilesViewModel.kt` → `platform/FilesListing.kt`.
**Gotchas:**
- File ops must survive process death — they belong in workers, never in the ViewModel
- Persistable SAF URI permissions are required for non-owned trees; losing them silently breaks listing

## Key files
### Root — entry + navigation
- com/vayunmathur/files/MainActivity.kt
- com/vayunmathur/files/Navigation.kt
- com/vayunmathur/files/Route.kt
### data/saf/ — picker + URI bookkeeping
- com/vayunmathur/files/data/saf/DocumentPickerActivity.kt
- com/vayunmathur/files/data/saf/StorageUris.kt
### platform/ — ViewModel, listing, clipboard, workers
- com/vayunmathur/files/platform/FilesViewModel.kt
- com/vayunmathur/files/platform/FilesUiContract.kt
- com/vayunmathur/files/platform/FilesListing.kt
- com/vayunmathur/files/platform/FilesClipboard.kt
- com/vayunmathur/files/platform/TextEditorViewModel.kt
- com/vayunmathur/files/platform/ZipWorker.kt
- com/vayunmathur/files/platform/UnzipWorker.kt
- com/vayunmathur/files/platform/ProgressNotificationWorker.kt
- com/vayunmathur/files/platform/ArchiveLimits.kt
### ui/ + ui/components/ + ui/dialogs/ — shell, listing, editor
- com/vayunmathur/files/ui/FilesShell.kt
- com/vayunmathur/files/ui/FilesNavActions.kt
- com/vayunmathur/files/ui/DirectoryScreen.kt
- com/vayunmathur/files/ui/DirectoryPage.kt
- com/vayunmathur/files/ui/DirectoryListing.kt
- com/vayunmathur/files/ui/DirectoryBreadcrumbs.kt
- com/vayunmathur/files/ui/DirectoryLeading.kt
- com/vayunmathur/files/ui/DirectoryMenus.kt
- com/vayunmathur/files/ui/DirectoryDialogs.kt
- com/vayunmathur/files/ui/DirectoryParentPane.kt
- com/vayunmathur/files/ui/HomeScreen.kt
- com/vayunmathur/files/ui/HomeScreenBinder.kt
- com/vayunmathur/files/ui/TextEditorActivity.kt
- com/vayunmathur/files/ui/components/DirectoryItem.kt
- com/vayunmathur/files/ui/components/GridItem.kt
- com/vayunmathur/files/ui/dialogs/PropertiesDialog.kt

## Verify (this module only)
```
./gradlew :files:compileDevKotlin
./gradlew :files:lint
./gradlew :files:checkMetadata
```
