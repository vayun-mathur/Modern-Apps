# AGENTS.md — flashcards/ (':flashcards')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `flashcards/` + allowed shared modules. Do not root-scan.

_Install: ./install flashcards (dev by default)._
- Gradle: :flashcards / dir: flashcards/
- Package roots present: ui, data
- Entry files: MainActivity.kt (no Route.kt — decks/notes/review/stats flow)
- Deps: :library:room, :library:image
- Metadata: metadata_data/flashcards.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Offline spaced-repetition flashcards — Anki-style notes/templates/decks with FSRS scheduling, review reminders, deck file sharing, APKG import/export. 100% offline.
**Key features:** Note-type editor + templating (`TemplateEngine`, `HtmlConvert`), card generation, review sessions with grading, FSRS optimizer, stats/charts, TTS speaker, media store; reminders via `ReviewReminderWorker`.
**Entry points:** `MainActivity.kt` → `ui/DeckListPage.kt` → `util/FlashcardsViewModel.kt` → `data/FlashcardsRepository.kt` → `util/Scheduler.kt` → `ui/ReviewPage.kt`.
**Gotchas:**
- `util/` is NOT a closed package root — don't add more files there
- Scheduling correctness (FSRS) is the product — scheduler changes need review-log-driven tests, not just UI checks
- APKG import/export must stay compatible with Anki's format — test round-trips, not just one direction

## Key files
### Root — entry (no Route.kt)
- com/vayunmathur/flashcards/MainActivity.kt
### data/ — notes, decks, review log (Room)
- com/vayunmathur/flashcards/data/FlashcardsDatabase.kt
- com/vayunmathur/flashcards/data/FlashcardsRepository.kt
- com/vayunmathur/flashcards/data/Note.kt
- com/vayunmathur/flashcards/data/NoteType.kt
- com/vayunmathur/flashcards/data/NoteTypeField.kt
- com/vayunmathur/flashcards/data/Card.kt
- com/vayunmathur/flashcards/data/CardTemplate.kt
- com/vayunmathur/flashcards/data/Deck.kt
- com/vayunmathur/flashcards/data/ReviewLog.kt
### util/ — ViewModel, scheduler, import/export
- com/vayunmathur/flashcards/util/FlashcardsViewModel.kt
- com/vayunmathur/flashcards/util/FlashcardsViewModelHelper.kt
- com/vayunmathur/flashcards/util/FlashcardsUiContract.kt
- com/vayunmathur/flashcards/util/Scheduler.kt
- com/vayunmathur/flashcards/util/FsrsOptimizer.kt
- com/vayunmathur/flashcards/util/ReviewSession.kt
- com/vayunmathur/flashcards/util/CardGenerator.kt
- com/vayunmathur/flashcards/util/TemplateEngine.kt
- com/vayunmathur/flashcards/util/HtmlConvert.kt
- com/vayunmathur/flashcards/util/ApkgImport.kt
- com/vayunmathur/flashcards/util/ApkgExport.kt
- com/vayunmathur/flashcards/util/DeckIo.kt
- com/vayunmathur/flashcards/util/MediaStore.kt
- com/vayunmathur/flashcards/util/TtsSpeaker.kt
- com/vayunmathur/flashcards/util/ReviewReminderWorker.kt
- com/vayunmathur/flashcards/util/AppBackupAgent.kt
### ui/ — decks, notes, review, stats
- com/vayunmathur/flashcards/ui/DeckListPage.kt
- com/vayunmathur/flashcards/ui/ReviewPage.kt
- com/vayunmathur/flashcards/ui/ReviewGradeButtons.kt
- com/vayunmathur/flashcards/ui/StudyModeRow.kt
- com/vayunmathur/flashcards/ui/CustomStudyDialog.kt
- com/vayunmathur/flashcards/ui/NoteListPage.kt
- com/vayunmathur/flashcards/ui/NoteListScreen.kt
- com/vayunmathur/flashcards/ui/NoteRowItem.kt
- com/vayunmathur/flashcards/ui/NoteEditPage.kt
- com/vayunmathur/flashcards/ui/NoteListFileHelpers.kt
- com/vayunmathur/flashcards/ui/ReorderableNoteList.kt
- com/vayunmathur/flashcards/ui/NoteTypeEditorPage.kt
- com/vayunmathur/flashcards/ui/NoteTypeEditorSection.kt
- com/vayunmathur/flashcards/ui/TagDialog.kt
- com/vayunmathur/flashcards/ui/TagFilterRow.kt
- com/vayunmathur/flashcards/ui/StatsPage.kt
- com/vayunmathur/flashcards/ui/FlashcardsCharts.kt
- com/vayunmathur/flashcards/ui/MarkdownContent.kt
- com/vayunmathur/flashcards/ui/MoveDeckDialog.kt
- com/vayunmathur/flashcards/ui/SettingsPage.kt

## Verify (this module only)
```
./gradlew :flashcards:compileDevKotlin
./gradlew :flashcards:lint
./gradlew :flashcards:checkMetadata
```
