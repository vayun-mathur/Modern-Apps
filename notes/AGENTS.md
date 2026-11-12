# AGENTS.md — notes/ (':notes')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `notes/` + allowed shared modules. Do not root-scan.

_Install: ./install notes (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :notes / dir: notes/
- Package roots present: ui, data, domain, platform, intents, sync
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:room, :library:ink, :library:image
- Metadata: metadata_data/notes.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Offline markdown+ note-taking app (100% offline). Close-second reference impl after `backup/`, and the canonical `intents/` IPC demo.
**Key features:**
- Block-based editor (`Note`/`NoteBlock`) with stylus ink (`:library:ink`) and images (`:library:image`, content-addressed `sha256.jpg`)
- `.md` import/export — opens files shared from other apps, desktop file-drop, shares notes back out
- OpenAssistant read/add via `intents/`; local backup/restore via `AppBackupAgent`
**Architecture / data-flow:** `ui/` Compose list/detail → `platform/NotesViewModel` (+ `NotesUiContract`) → `data/NotesRepository` singleton over Room (`Note`/`NoteBlock`/`NoteDatabase`). `MainActivity.onCreateBeforeContent`: repo init → one-time legacy-notes clear + image `img_<uuid>.jpg → sha256.jpg` migration with block-reference rewrite → `ready` gate before `Navigation`.
**Entry points + reading order:** `Route` (`NotesList` / `Note(id)` / `ExternalNote(uri)`) → `MainActivity.kt` → `Navigation.kt` → `platform/NotesViewModel.kt` → `data/NotesRepository.kt`.
**Gotchas:**
- Migrations must finish before `ready = true` — image references break if skipped
- External/shared URIs enter via the `ExternalNote` route, not the list
- `intents/` DTOs are the copy-pattern for cross-app IPC; keep them thin

## Key files
### Root — entry + navigation
- com/vayunmathur/notes/MainActivity.kt
- com/vayunmathur/notes/Navigation.kt
- com/vayunmathur/notes/Route.kt
### data/ — Room entities + repository
- com/vayunmathur/notes/data/Note.kt
- com/vayunmathur/notes/data/NoteBlock.kt
- com/vayunmathur/notes/data/NoteDatabase.kt
- com/vayunmathur/notes/data/NotesRepository.kt
- com/vayunmathur/notes/data/NoteConverters.kt — `tags` JSON-array column (#913)
### domain/ — pure rules (no Android)
- com/vayunmathur/notes/domain/NoteTags.kt — tag normalisation, rename/merge, counts, suggestions
### platform/ — ViewModel, contract, stores, export
- com/vayunmathur/notes/platform/NotesViewModel.kt
- com/vayunmathur/notes/platform/NotesUiContract.kt
- com/vayunmathur/notes/platform/NoteImageStore.kt — content-addressed image copy + migration
- com/vayunmathur/notes/platform/NoteMarkdownExporter.kt
- com/vayunmathur/notes/platform/AppBackupAgent.kt
### ui/ — list/detail/ink/external screens
- com/vayunmathur/notes/ui/NotesListScreen.kt
- com/vayunmathur/notes/ui/NotesListPage.kt
- com/vayunmathur/notes/ui/NoteScreen.kt
- com/vayunmathur/notes/ui/NotePage.kt
- com/vayunmathur/notes/ui/InkEditorPage.kt
- com/vayunmathur/notes/ui/ExternalNoteScreen.kt
- com/vayunmathur/notes/ui/components/NoteEditorComponents.kt
### intents/ — assistant IPC demo (copy this pattern)
- com/vayunmathur/notes/intents/GetIntent.kt
- com/vayunmathur/notes/intents/InsertIntent.kt

## Verify (this module only)
```
./gradlew :notes:compileDevKotlin
./gradlew :notes:lint
./gradlew :notes:checkMetadata
```
