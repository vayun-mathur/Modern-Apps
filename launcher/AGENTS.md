# AGENTS.md — launcher/ (':launcher')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `launcher/` + allowed shared modules. Do not root-scan.

_Install: ./install launcher (dev by default). For a TV over Wi-Fi: `sh launcher/install-tv.sh <TV_IP>` builds, installs and offers to set it as the default home; `sh launcher/undo-tv.sh <TV_IP>` restores the stock home._
- Gradle: :launcher / dir: launcher/
- Package roots present: ui, data, domain, platform + tv/{ui,data,domain,platform}
- Entry files: MainActivity.kt (UiMode-routed), Route.kt + Navigation.kt (phone), tv/TvRoute.kt + tv/TvNavigation.kt (TV)
- Deps: :library:room + androidx.tv.material (via common-conventions-tv)
- Metadata: metadata_data/launcher.md (present, mentions TV mode)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Offline home-screen replacement — paged grid, hotseat, folders, searchable drawer, real widget hosting on phones; calm banner home with HDMI inputs and D-pad reorder on TVs. One APK routes by UI mode. Layout in encrypted Room (phone) / DataStore order (TV); no account, nothing uploaded.
**Key features:**
- Workspace grid with drag/drop/reorder/folder-merge, drop-bar targets, page-edge dwell, widget resize frames (`ui/components/` drag system + `domain/` placement math)
- App drawer with fast scroll + predictions; widget host incl. configurable providers (`platform/LauncherWidgetHost`)
- Icon cache, app-change monitor, privilege-gated surfaces; settings pages
- TV mode (`tv/`): 6-per-row 16:9 banner grid, bottom glass row (App Store/HDMI/Settings/Reorder), clock + network header, single-OK pick-up/drop reorder with focus-chase and slot glide, edit/add menus, HOME role — DataStore order, no Room/widget init
**Architecture / data-flow:** `ui/` home/drawer/folder/settings → `platform/LauncherViewModel` (+ `LauncherUiContract`) → `data/LauncherRepository` over Room; `domain/` is pure grid math (occupancy, placement, reorder, folders, hotseat, pagination) — the single biggest pure-logic area in any app, testable without Compose. `tv/ui/` → `tv/platform/TvHomeViewModel` (+ `TvUiContract`) → `tv/data/TvPrefs` (DataStore); `tv/domain/TvRowOrder` is pure row math. `MainActivity` routes by `UiModeManager`; neither mode initializes the other's stack.
**Entry points + reading order:** `Route` → `MainActivity.kt` (UiMode branch) → phone `Navigation.kt` → `ui/HomeWorkspace.kt` → `platform/LauncherViewModel.kt` → `domain/GridPlacer.kt` + `domain/ReconcileUseCase.kt`; TV `TvRoute` → `tv/TvNavigation.kt` → `tv/ui/TvHomeScreen.kt` → `tv/platform/TvHomeViewModel.kt` → `tv/domain/TvRowOrder.kt`.
**Gotchas:**
- Exempt from the raw-animation rule: built on `animateBounds`/`Animatable` — do NOT "migrate" its motion to library helpers. TV code under `tv/` keeps using `library.ui` helpers anyway — no regression just because the package is exempt.
- Reconcile (installed-apps vs stored layout) runs on package changes — launcher bugs after installs live in `ReconcileUseCase` + `LauncherAppsMonitor`
- Drag input math is in `domain/`, gesture plumbing in `ui/components/` — keep them separated
- TV Back order: edit menu → cancel hold (quiet restore) → disarm reorder → no-op. TV hint slot is always composed (`TvReorderHint`) — never conditionally compose hints in the `SpaceBetween` column or the grid jumps.
- One HOME filter carries MAIN+LAUNCHER+LEANBACK_LAUNCHER+HOME+DEFAULT together — split filters on one activity confuse resolver elections.

## Key files
### Root — entry + navigation
- com/vayunmathur/launcher/MainActivity.kt
- com/vayunmathur/launcher/Navigation.kt
- com/vayunmathur/launcher/Route.kt
### data/ — encrypted layout store
- com/vayunmathur/launcher/data/LauncherDatabase.kt
- com/vayunmathur/launcher/data/LauncherRepository.kt
- com/vayunmathur/launcher/data/LauncherItemEntity.kt
### domain/ — pure grid math (no Android/Compose)
- com/vayunmathur/launcher/domain/ReconcileUseCase.kt
- com/vayunmathur/launcher/domain/GridPlacer.kt
- com/vayunmathur/launcher/domain/GridReorder.kt
- com/vayunmathur/launcher/domain/CellOccupancy.kt
- com/vayunmathur/launcher/domain/CellRect.kt
- com/vayunmathur/launcher/domain/ContainerRef.kt
- com/vayunmathur/launcher/domain/AutoPlacer.kt
- com/vayunmathur/launcher/domain/DropBarTargets.kt
- com/vayunmathur/launcher/domain/FolderMerge.kt
- com/vayunmathur/launcher/domain/FolderRules.kt
- com/vayunmathur/launcher/domain/GridPreview.kt
- com/vayunmathur/launcher/domain/HotseatArrange.kt
- com/vayunmathur/launcher/domain/LauncherItemType.kt
- com/vayunmathur/launcher/domain/LauncherTuning.kt
- com/vayunmathur/launcher/domain/PageCount.kt
- com/vayunmathur/launcher/domain/ReorderDwell.kt
- com/vayunmathur/launcher/domain/FastScroll.kt
- com/vayunmathur/launcher/domain/WidgetResize.kt
### platform/ — ViewModel, monitor, widgets, bridge
- com/vayunmathur/launcher/platform/LauncherViewModel.kt
- com/vayunmathur/launcher/platform/LauncherUiContract.kt
- com/vayunmathur/launcher/platform/LauncherAppsMonitor.kt
- com/vayunmathur/launcher/platform/LauncherPredictions.kt
- com/vayunmathur/launcher/platform/LauncherWidgetHost.kt
- com/vayunmathur/launcher/platform/LauncherWidgets.kt
- com/vayunmathur/launcher/platform/IconCache.kt
- com/vayunmathur/launcher/platform/ActivityBridge.kt
- com/vayunmathur/launcher/platform/LauncherPrivilege.kt
### tv/ — TV mode (UiMode-routed, DataStore order, no Room/widgets)
- com/vayunmathur/launcher/tv/TvRoute.kt
- com/vayunmathur/launcher/tv/TvNavigation.kt
- com/vayunmathur/launcher/tv/data/TvPrefs.kt
- com/vayunmathur/launcher/tv/domain/TvRowOrder.kt
- com/vayunmathur/launcher/tv/platform/TvHomeViewModel.kt
- com/vayunmathur/launcher/tv/platform/TvUiContract.kt
- com/vayunmathur/launcher/tv/platform/TvApps.kt
- com/vayunmathur/launcher/tv/platform/TvInputs.kt
- com/vayunmathur/launcher/tv/platform/TvHomeRole.kt
- com/vayunmathur/launcher/tv/platform/TvArtCache.kt
- com/vayunmathur/launcher/tv/ui/TvHomeScreen.kt
- com/vayunmathur/launcher/tv/ui/TvHomePage.kt
- com/vayunmathur/launcher/tv/ui/TvClockHeader.kt
- com/vayunmathur/launcher/tv/ui/TvAppGrid.kt
- com/vayunmathur/launcher/tv/ui/TvBottomRow.kt
- com/vayunmathur/launcher/tv/ui/TvEditMenu.kt
- com/vayunmathur/launcher/tv/ui/components/TvBannerTile.kt
- com/vayunmathur/launcher/tv/ui/components/TvReorderHint.kt
### ui/ + ui/components/ — workspace, drawer, drag system
- com/vayunmathur/launcher/ui/HomeWorkspace.kt
- com/vayunmathur/launcher/ui/HomePage.kt
- com/vayunmathur/launcher/ui/HomeContent.kt
- com/vayunmathur/launcher/ui/WorkspacePage.kt
- com/vayunmathur/launcher/ui/WorkspaceGrid.kt
- com/vayunmathur/launcher/ui/HomeGestureRoot.kt
- com/vayunmathur/launcher/ui/HomeDropLogic.kt
- com/vayunmathur/launcher/ui/HomeLaunch.kt
- com/vayunmathur/launcher/ui/HomeEffects.kt
- com/vayunmathur/launcher/ui/HomeOverlays.kt
- com/vayunmathur/launcher/ui/HomePopups.kt
- com/vayunmathur/launcher/ui/HomeConstants.kt
- com/vayunmathur/launcher/ui/HomeDrawerSwipe.kt
- com/vayunmathur/launcher/ui/WorkspaceOptionsContent.kt
- com/vayunmathur/launcher/ui/HotseatBar.kt
- com/vayunmathur/launcher/ui/FolderContent.kt
- com/vayunmathur/launcher/ui/DrawerContent.kt
- com/vayunmathur/launcher/ui/DrawerCells.kt
- com/vayunmathur/launcher/ui/DrawerFastScroll.kt
- com/vayunmathur/launcher/ui/ItemMenuContent.kt
- com/vayunmathur/launcher/ui/SettingsPage.kt
- com/vayunmathur/launcher/ui/SettingsContent.kt
- com/vayunmathur/launcher/ui/WidgetPickerContent.kt
- com/vayunmathur/launcher/ui/components/LauncherDragController.kt
- com/vayunmathur/launcher/ui/components/DragLayer.kt
- com/vayunmathur/launcher/ui/components/DragFeedback.kt
- com/vayunmathur/launcher/ui/components/DragSourceModifier.kt
- com/vayunmathur/launcher/ui/components/DropTargetModifier.kt
- com/vayunmathur/launcher/ui/components/DropBar.kt
- com/vayunmathur/launcher/ui/components/CellLayout.kt
- com/vayunmathur/launcher/ui/components/CellPlacement.kt
- com/vayunmathur/launcher/ui/components/LauncherDragInput.kt
- com/vayunmathur/launcher/ui/components/LauncherDragInputSection.kt
- com/vayunmathur/launcher/ui/components/LauncherAppIcon.kt
- com/vayunmathur/launcher/ui/components/LauncherItemIcon.kt
- com/vayunmathur/launcher/ui/components/LauncherPopup.kt
- com/vayunmathur/launcher/ui/components/LauncherPopupSurface.kt
- com/vayunmathur/launcher/ui/components/MergeRing.kt
- com/vayunmathur/launcher/ui/components/ReorderPreview.kt
- com/vayunmathur/launcher/ui/components/PageIndicator.kt
- com/vayunmathur/launcher/ui/components/PageEdgeDwell.kt
- com/vayunmathur/launcher/ui/components/FastScrollStrip.kt
- com/vayunmathur/launcher/ui/components/HostedWidget.kt
- com/vayunmathur/launcher/ui/components/MissingWidget.kt
- com/vayunmathur/launcher/ui/components/WidgetResizeFrame.kt
- com/vayunmathur/launcher/ui/components/AppWindowBounds.kt

## Verify (this module only)
```
./gradlew :launcher:compileDevKotlin
./gradlew :launcher:lint
./gradlew :launcher:checkMetadata
```
