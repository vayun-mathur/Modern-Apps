# AGENTS.md — office/ (':office')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `office/` + allowed shared modules. Do not root-scan.

_Install: ./install office (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :office / dir: office/
- Package roots present: ui
- Entry files: MainActivity.kt (no Route.kt — `OfficeRoute` is inline)
- Deps: :library:network, :library:e2ee-p2p
- Metadata: metadata_data/office.md (present)
- Rust: yes / screenshotTest: yes

### Relay server (backend, outside this repo)
Host migrated from `findfamily.cc` to `api.maproj.org` (same Cloudflare FIRST_PARTY pins; `findfamily.cc` remains valid for backward compatibility). Server checkout: `../location_share_server` (sibling of this repo root, `C:\Users\Vayun\Documents\code\location_share_server`). Live SSH: `ssh root@87.86.80.39`. Env template: `../location_share_server/.env.example` (copy to `.env`, never commit secrets). Full details: root `AGENTS.md` → "Backend server (outside this repo)".

## Overview
**Purpose:** Document viewer/editor for ODF (odt/ods/odp) and Microsoft (docx/xlsx/pptx) files, with E2EE collaboration over a dumb relay. Internet only for collaboration.
**Key features:**
- Full ODF + OOXML importers (`odf/`), incl. Excel formulas, number formats, charts, diagrams, themes, and math (ODF/OMML→MathML)
- Editing across text, spreadsheets, and presentations with comments, change tracking, footnotes, headers/footers, and export/print menus
- Native Rust formula engine + document CRDT (`OfficeNative`); online collaboration is E2EE with no accounts — the relay only forwards ciphertext
- Opens files shared from other apps or `office://join` collaboration links
**Architecture / data-flow:** Root-level document chrome + `ui/` type-specific views over the `util/OfficeViewModel*` family (split per surface: documents, text, sheet, slides, tables, sync A/B/C, export) backed by `:library:ui`'s `OdfDocument` model; sync goes through `util/OfficeSync` + `util/DocumentTreeCrdt` to the E2EE relay. `onCreateBeforeContent`: `NetworkClient.init(FIRST_PARTY)` → load settings → split join-links from document URIs; `Content()` roots the `OfficeRoute` (`Main` / `OfflineEditor` / `OnlineEditor`) graph and fires `initSync`.
**Entry points + reading order:** `OfficeRoute` (`Main` / `OfflineEditor(uri)` / `OnlineEditor(docId)`, inline) → `MainActivity.kt` (`OfficeRoot`, `OfficeNavGraph`) → `OfficeDocumentPane.kt` → `util/OfficeViewModel.kt` → `odf/DocumentImporter.kt`.
**Gotchas:**
- Display names must come from `OpenableColumns.DISPLAY_NAME`, not the URI path — `DocumentImporter` routes by extension and content URIs often have extensionless ids
- The document "paper" stays light in dark mode by design (`OfficeLightTheme`); dark mode inverts luminance via a color matrix, never the model colors
- `OfficeLightTheme` is public for `screenshotTest` — don't privatize it
- `office://join` links never enter the document-loading path (different scheme); handle them via `parseJoinLink`/`handleJoinLink` only

## Key files
### Root — entry, chrome, and document shell
- com/vayunmathur/office/MainActivity.kt — OfficeRoute, OfficeRoot, join-link handling
- com/vayunmathur/office/DocumentScreenShell.kt
- com/vayunmathur/office/DocumentScreenChrome.kt
- com/vayunmathur/office/OfficeBottomBarHost.kt
- com/vayunmathur/office/OfficeDocumentPane.kt
- com/vayunmathur/office/OfficeDocumentState.kt
- com/vayunmathur/office/OfficeDocumentUtils.kt
- com/vayunmathur/office/OfficeDocumentMenuBar.kt
- com/vayunmathur/office/OfficeDocumentOverlays.kt
- com/vayunmathur/office/OfficeDocumentLaunchers.kt
- com/vayunmathur/office/OfficeDocumentFindBars.kt
- com/vayunmathur/office/OfficeHomeScreens.kt
- com/vayunmathur/office/OfficeOnlineScreens.kt
- com/vayunmathur/office/OfficeEnableOnlineDialog.kt
- com/vayunmathur/office/OfficeOutlinePane.kt
- com/vayunmathur/office/OfficeExportMenu.kt
- com/vayunmathur/office/OfficeInsertMenu.kt
- com/vayunmathur/office/OfficeShareMembers.kt
- com/vayunmathur/office/OfficeCellOverlays.kt
- com/vayunmathur/office/OfficeChangesOverlays.kt
- com/vayunmathur/office/OfficeCommentsOverlays.kt
- com/vayunmathur/office/OfficePageSetupOverlays.kt
- com/vayunmathur/office/OfficeSlideElementOverlays.kt
- com/vayunmathur/office/OfficeSlideOverlays.kt
### odf/ — ODF + OOXML import/export
- com/vayunmathur/office/odf/DocumentImporter.kt — extension-routed entry
- com/vayunmathur/office/odf/OdfParser.kt
- com/vayunmathur/office/odf/OdfParserText.kt
- com/vayunmathur/office/odf/OdfParserSheet.kt
- com/vayunmathur/office/odf/OdfParserSlides.kt
- com/vayunmathur/office/odf/OdfParserInline.kt
- com/vayunmathur/office/odf/OdfParserChart.kt
- com/vayunmathur/office/odf/OdfParserStyles.kt
- com/vayunmathur/office/odf/OdfMath.kt
- com/vayunmathur/office/odf/OmmlToMathml.kt
- com/vayunmathur/office/odf/OdfWriter.kt
- com/vayunmathur/office/odf/ExcelFormula.kt
- com/vayunmathur/office/odf/ExcelNumFmt.kt
- com/vayunmathur/office/odf/OoxmlImporter.kt
- com/vayunmathur/office/odf/OoxmlDocx.kt
- com/vayunmathur/office/odf/OoxmlDocxStyles.kt
- com/vayunmathur/office/odf/OoxmlXlsx.kt
- com/vayunmathur/office/odf/OoxmlXlsxHelper.kt
- com/vayunmathur/office/odf/OoxmlPptx.kt
- com/vayunmathur/office/odf/OoxmlChart.kt
- com/vayunmathur/office/odf/OoxmlDiagram.kt
- com/vayunmathur/office/odf/OoxmlTheme.kt
- com/vayunmathur/office/odf/OoxmlColor.kt
- com/vayunmathur/office/odf/OoxmlMetadata.kt
- com/vayunmathur/office/odf/OoxmlPackage.kt
- com/vayunmathur/office/odf/OoxmlUnits.kt
- com/vayunmathur/office/odf/OoxmlXml.kt
### ui/ — type views + editor dialogs
- com/vayunmathur/office/ui/DocumentViews.kt
- com/vayunmathur/office/ui/TextDocumentView.kt
- com/vayunmathur/office/ui/ParagraphViews.kt
- com/vayunmathur/office/ui/SpreadsheetViews.kt
- com/vayunmathur/office/ui/PresentationViews.kt
- com/vayunmathur/office/ui/FloatingElementViews.kt
- com/vayunmathur/office/ui/OdfChartViews.kt
- com/vayunmathur/office/ui/OdfImageViews.kt
- com/vayunmathur/office/ui/MathViews.kt
- com/vayunmathur/office/ui/OfficeTextFormat.kt
- com/vayunmathur/office/ui/OfficeCellFormat.kt
- com/vayunmathur/office/ui/OfficeEditorWideLayout.kt
- com/vayunmathur/office/ui/OfficeBottomBar.kt
- com/vayunmathur/office/ui/CommentDialog.kt
- com/vayunmathur/office/ui/ChartEditorDialog.kt
- com/vayunmathur/office/ui/AddBookmarkDialog.kt
- com/vayunmathur/office/ui/ColorPickerDialog.kt
- com/vayunmathur/office/ui/FontSizePickerDialog.kt
- com/vayunmathur/office/ui/FootnoteDialog.kt
- com/vayunmathur/office/ui/GoToSlideDialog.kt
- com/vayunmathur/office/ui/HeaderFooterDialog.kt
- com/vayunmathur/office/ui/InsertHyperlinkDialog.kt
- com/vayunmathur/office/ui/InsertTableDialog.kt
- com/vayunmathur/office/ui/SortDialog.kt
- com/vayunmathur/office/ui/SpecialCharsDialog.kt
- com/vayunmathur/office/ui/SettingsDialog.kt
### util/ — ViewModels, sync, CRDT, native bridge
- com/vayunmathur/office/util/OfficeViewModel.kt
- com/vayunmathur/office/util/OfficeViewModelDocuments.kt
- com/vayunmathur/office/util/OfficeViewModelText.kt
- com/vayunmathur/office/util/OfficeViewModelSheet.kt
- com/vayunmathur/office/util/OfficeViewModelSlides.kt
- com/vayunmathur/office/util/OfficeViewModelTables.kt
- com/vayunmathur/office/util/OfficeViewModelSyncA.kt
- com/vayunmathur/office/util/OfficeViewModelSyncB.kt
- com/vayunmathur/office/util/OfficeViewModelSyncC.kt
- com/vayunmathur/office/util/OfficeViewModelExport.kt
- com/vayunmathur/office/util/OfficeSync.kt
- com/vayunmathur/office/util/DocumentTreeCrdt.kt
- com/vayunmathur/office/util/OfficeNative.kt — Rust formula/CRDT bridge

## Verify (this module only)
```
./gradlew :office:compileDevKotlin
./gradlew :office:lint
./gradlew :office:checkMetadata
```
