# AGENTS.md — library/ui/ (':library:ui')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/ui/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:ui / dir: library/ui/
- Package roots present: ui
- Entry files: MaActivity.kt, AppScaffold.kt, Icons.kt (no app MainActivity/Route — this is the shared shell)
- Deps: :library
- Network: none — offline-banner connectivity UI lives in `:library:network`
  (`util/ConnectivityCompose.kt`); network apps depend on it directly (#884)
- Metadata: metadata_data/library-ui.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** All shared Compose chrome for every app — activity shells, scaffolds, theme, icons, messenger, editors, motion. If it renders in more than one app, it lives here.
**How to use (must-rules, enforced by lint):**
- Activities extend `MaActivity` (or `MaFragmentActivity` for biometric-gated apps) — override `Content()` + `onCreateBeforeContent()`, never repeat the edge-to-edge/theme shell
- Screens use the `AppScaffold` family (never raw `Scaffold`); icons via `IconXxx()` in `Icons.kt` (never `Icon()`/`painterResource()`/`material.icons.*` in apps); messages via `rememberMessenger()`/`AppMessages` (never `Toast`); motion via the `Motion*` helpers (never raw `animation` at call sites)
**Areas:** activity shells (`MaActivity.kt`) · scaffolds (`AppScaffold`, `Detail`/`LazyList`/`TabbedPager`/`Setup`, `ListPage`) · theme + tokens (`DynamicTheme`, `Material*`, `Spacing`, `Modifier`, `Motion*`) · `Icons.kt` · messenger (`Messages.kt`) · editors (Html/Markdown/Odf + controllers) · pickers + dialogs (`dialog/`) · game chrome (`game/`) · ODF/doc export (`odf/`) · panorama · permissions + special-access · adaptive/wide layouts (`AdaptiveGrid`, `DesktopMaxWidth`, `PaneMargins`) · connectivity banner (`util/ConnectivityCompose.kt`).
**Entry points + reading order:** `MaActivity.kt` → `AppScaffold.kt` → `Icons.kt` → `Messages.kt` → `DynamicTheme.kt`, then the area you need.
**Gotchas:**
- One public `@Composable` per file, named as the file; closed package roots still apply inside this library
- Never `imePadding()`/`navigationBarsPadding()`/`systemBarsPadding()` in this library
- Connectivity UI (`OfflineAware`/`rememberIsOnline`) lives in `:library:network`, not here (#884)
- `MaFragmentActivity(autoSetContent=false)` + `showContent()` is the biometric-gate pattern (passwords, photos)

## Key files
### Shell + scaffolds — start here
- com/vayunmathur/library/ui/MaActivity.kt — shared activity shell (incl. `MaFragmentActivity`)
- com/vayunmathur/library/ui/AppScaffold.kt
- com/vayunmathur/library/ui/DetailScaffold.kt
- com/vayunmathur/library/ui/LazyListScaffold.kt
- com/vayunmathur/library/ui/TabbedPagerScaffold.kt
- com/vayunmathur/library/ui/SetupScaffold.kt
- com/vayunmathur/library/ui/ListPage.kt
### Theme + tokens
- com/vayunmathur/library/ui/DynamicTheme.kt
- com/vayunmathur/library/ui/Icons.kt — ONLY place allowed `Icon()`/`painterResource()`
- com/vayunmathur/library/ui/Messages.kt — messenger + `AppMessages`
- com/vayunmathur/library/ui/MaterialAliases.kt
- com/vayunmathur/library/ui/MaterialComponents.kt
- com/vayunmathur/library/ui/MaterialComponentsExtra.kt
- com/vayunmathur/library/ui/MaterialFunctions.kt
- com/vayunmathur/library/ui/Motion.kt — motion helpers (use these, not raw animation)
- com/vayunmathur/library/ui/MotionValues.kt
- com/vayunmathur/library/ui/Spacing.kt
- com/vayunmathur/library/ui/Modifier.kt
- com/vayunmathur/library/ui/ContentColorOn.kt
- com/vayunmathur/library/ui/VerticalShape.kt
- com/vayunmathur/library/ui/DateString.kt
- com/vayunmathur/library/ui/RememberClock.kt
### Adaptive + wide layouts
- com/vayunmathur/library/ui/AdaptiveGrid.kt
- com/vayunmathur/library/ui/AdaptiveWindow.kt
- com/vayunmathur/library/ui/DesktopMaxWidth.kt
- com/vayunmathur/library/ui/PaneMargins.kt
- com/vayunmathur/library/ui/SwappedContent.kt
- com/vayunmathur/library/ui/SwappedTopBar.kt
- com/vayunmathur/library/ui/TopAppBarOverlay.kt
- com/vayunmathur/library/ui/SafeListItemLayout.kt
### Common screens + state
- com/vayunmathur/library/ui/StateScreens.kt
- com/vayunmathur/library/ui/NoPermissionsScreen.kt
- com/vayunmathur/library/ui/DashboardSection.kt
- com/vayunmathur/library/ui/GameCenterScreen.kt
- com/vayunmathur/library/ui/AchievementNotification.kt
- com/vayunmathur/library/ui/HistoryScrubber.kt
- com/vayunmathur/library/ui/SelectionMode.kt
- com/vayunmathur/library/ui/SwipeActionsBox.kt
- com/vayunmathur/library/ui/CommonSearchBar.kt
- com/vayunmathur/library/ui/OverflowMenu.kt
- com/vayunmathur/library/ui/ConfirmDialog.kt
- com/vayunmathur/library/ui/AddToListDialog.kt
- com/vayunmathur/library/ui/BannerVisibility.kt
- com/vayunmathur/library/ui/ExpandVisibility.kt
- com/vayunmathur/library/ui/FadeVisibility.kt
- com/vayunmathur/library/ui/PopVisibility.kt
- com/vayunmathur/library/ui/FreeHeightBottomSheet.kt
- com/vayunmathur/library/ui/FreeHeightSheetState.kt
### Forms + settings
- com/vayunmathur/library/ui/FormComponents.kt
- com/vayunmathur/library/ui/FormSection.kt
- com/vayunmathur/library/ui/SettingsComponents.kt
- com/vayunmathur/library/ui/DailyReminderSettingsSection.kt
- com/vayunmathur/library/ui/MultiCategoryPicker.kt
- com/vayunmathur/library/ui/RingtonePicker.kt
- com/vayunmathur/library/ui/BackupButtons.kt — shared backup destination picker buttons
- com/vayunmathur/library/ui/ShareVaultDialog.kt — everysync vault share dialog
### Reorderable grids
- com/vayunmathur/library/ui/ReorderableGrid.kt
- com/vayunmathur/library/ui/ReorderableGridGestures.kt
- com/vayunmathur/library/ui/ReorderableUtil.kt
### Permissions + system access
- com/vayunmathur/library/ui/AppPermissionsGate.kt
- com/vayunmathur/library/ui/PermissionRequest.kt
- com/vayunmathur/library/ui/PermissionRequirement.kt
- com/vayunmathur/library/ui/SpecialAccess.kt
- com/vayunmathur/library/ui/ExternalIntents.kt
- com/vayunmathur/library/ui/AppPullToRefreshBox.kt
### Maps + sensors
- com/vayunmathur/library/ui/MapsPoiVectors.kt
- com/vayunmathur/library/ui/MapUnavailableMessage.kt
- com/vayunmathur/library/ui/CompassCalibrationBanner.kt
- com/vayunmathur/library/ui/CompassCalibrationHint.kt
### Panorama
- com/vayunmathur/library/ui/PanoramaCameraState.kt
- com/vayunmathur/library/ui/PanoramaCrop.kt
- com/vayunmathur/library/ui/PanoramaSphere.kt
- com/vayunmathur/library/ui/PanoramaSphereView.kt
### Editors — Html
- com/vayunmathur/library/ui/HtmlEditor.kt
- com/vayunmathur/library/ui/HtmlEditorController.kt
- com/vayunmathur/library/ui/HtmlEditorControllerCore.kt
- com/vayunmathur/library/ui/HtmlEditorSpans.kt
- com/vayunmathur/library/ui/HtmlSerialize.kt
- com/vayunmathur/library/ui/HtmlText.kt — `scrollInternally`/`fitContentToWidth` for long untrusted mail (#900)
- com/vayunmathur/library/ui/HtmlTextDocument.kt — pure document wrapper (viewport, CSP, fit CSS)
- com/vayunmathur/library/ui/RichEmailSpans.kt
- com/vayunmathur/library/ui/EditorFormatter.kt
- com/vayunmathur/library/ui/EditorLink.kt
### Editors — Markdown
- com/vayunmathur/library/ui/MarkdownEditor.kt
- com/vayunmathur/library/ui/MarkdownEditOps.kt
- com/vayunmathur/library/ui/MarkdownToHtml.kt
- com/vayunmathur/library/ui/OdfMarkdownEditor.kt
### dialog/ — date/time pickers
- com/vayunmathur/library/ui/dialog/DatePickerDialog.kt
- com/vayunmathur/library/ui/dialog/TimePickerDialog.kt
### game/ — shared game chrome
- com/vayunmathur/library/ui/game/GameModeChooser.kt
- com/vayunmathur/library/ui/game/GameResultOverlay.kt
- com/vayunmathur/library/ui/game/GameTopBarActions.kt
- com/vayunmathur/library/ui/game/GameInfoBox.kt
- com/vayunmathur/library/ui/game/GameInfoBoxes.kt
- com/vayunmathur/library/ui/game/DailyChallengeCard.kt
- com/vayunmathur/library/ui/game/DailyStreakText.kt
- com/vayunmathur/library/ui/game/HeartsRow.kt
- com/vayunmathur/library/ui/game/FormatDuration.kt
### odf/ — document model + exporters
- com/vayunmathur/library/ui/odf/OdfDocument.kt
- com/vayunmathur/library/ui/odf/OdfText.kt
- com/vayunmathur/library/ui/odf/OdfTextEditorState.kt
- com/vayunmathur/library/ui/odf/OdfTextEditorView.kt
- com/vayunmathur/library/ui/odf/OdfTextHelpers.kt
- com/vayunmathur/library/ui/odf/OdfParagraphOps.kt
- com/vayunmathur/library/ui/odf/OdfRunEdits.kt
- com/vayunmathur/library/ui/odf/OdfDocTransform.kt
- com/vayunmathur/library/ui/odf/OdfListPrefixes.kt
- com/vayunmathur/library/ui/odf/OdfMetadata.kt
- com/vayunmathur/library/ui/odf/OdfTables.kt
- com/vayunmathur/library/ui/odf/OdfSlides.kt
- com/vayunmathur/library/ui/odf/OdfSerializer.kt
- com/vayunmathur/library/ui/odf/HtmlOdfConverter.kt
- com/vayunmathur/library/ui/odf/HtmlOdfExport.kt
- com/vayunmathur/library/ui/odf/MarkdownOdfConverter.kt
- com/vayunmathur/library/ui/odf/RtfOdfConverter.kt
- com/vayunmathur/library/ui/odf/EpubExporter.kt
- com/vayunmathur/library/ui/odf/OoxmlExporter.kt
- com/vayunmathur/library/ui/odf/PdfExporter.kt
- com/vayunmathur/library/ui/odf/LatexExporter.kt
### Connectivity
- com/vayunmathur/library/util/ConnectivityCompose.kt — offline banner (`OfflineAware`); `util` package but owned by this module
- com/vayunmathur/library/ui/Haptics.kt

## Verify (this module only)
```
./gradlew :library:ui:compileDevKotlin
./gradlew :library:ui:lint
```
