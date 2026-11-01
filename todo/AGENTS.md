# AGENTS.md — todo/ (':todo')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `todo/` + allowed shared modules. Do not root-scan.

_Install: ./install todo (dev by default)._
<!-- last-reviewed: 2026-11-01 -->
- Gradle: :todo / dir: todo/
- Package roots present: ui, data, domain, platform, sync, notifications, widget, service, intents
- Entry files: MainActivity.kt, Route.kt, Navigation.kt, TodoApplication.kt
- Deps: :library:room, :library:widgets
- Metadata: metadata_data/todo.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Offline task manager: lists, smart views, subtasks, priorities, timed due dates, repeats, reminders, natural-language quick add, widgets, calendar mirror, import/export, and E2EE sync via EverySync.
**Architecture / data-flow:** `ui/` (stateless `*Screen` + binding `*Page`) → `platform/TodoViewModel` / `platform/TodoEditor` → `data/TodoRepository` over Room (`Todo`, `TodoList`, `SyncSnapshot`). Pure logic lives in `domain/` (no Android): `TodoQuery` (views/sort/groups), `QuickAddParser`, `Recurrence`, `Completion`, `Positions`, `DueDates`, `format/` (todo.txt, CSV, VTODO). `platform/TodoBackground` (started by `TodoApplication`) reacts to any data change: reminder alarms, digest, per-list channels, widgets, calendar mirror.
**Entry points + reading order:** `Route` → `Navigation.kt` → `ui/TodoShell` → `ui/TodoListPage` → `platform/TodoViewModel` → `domain/TodoQuery` → `data/TodoRepository` → `sync/RelayTodoProvider`.
**Gotchas:**
- Relay envelopes (`sync/RelayEnvelopes.kt`) mirror everysync's `RemoteTodo` / `RemoteTodoList` field-for-field. Never rename a key; new fields need defaults (encodeDefaults is off, so legacy hashes stay identical). Update both sides together.
- Lists sync on their own `todolists:<vault>` channel so pre-list builds never see them as tasks.
- All-day `dueAt` is **local** midnight. The Material date picker speaks UTC midnight — go through `DueDates.fromPickerMillis` / `toPickerMillis` (or `ui/TodoDatePickerDialog`).
- Editor writes run on `TodoRepository.appScope` and flush on close; a new task is only inserted once it has a title (`platform/TodoEditor`).
- Completing a repeating task rolls the same row forward (`domain/Completion`), it does not create a copy.
- `exportSchema = true`: bump the DB version and commit `schemas/…/<n>.json` with every schema change; extend `TodoMigrationTest`.
- `settings.gradle.kts` lists `:todo` twice — harmless Gradle quirk.

## Key files
### Root
- MainActivity.kt (intent capture → `platform/IntentRouter`), Navigation.kt, Route.kt, TodoApplication.kt
### data/
- Todo.kt, TodoList.kt, TodoDatabase.kt (DAOs), TodoMigrations.kt, TodoRepository.kt, TodoSettings.kt (DataStore)
### domain/
- TodoQuery.kt, TodoView.kt, QuickAddParser.kt + QuickAddWords.kt, Recurrence.kt, Completion.kt, DueDates.kt, DueBuckets.kt, Positions.kt, Reminders.kt, format/
### platform/
- TodoViewModel.kt, TodoEditor.kt, TodoListUiContract.kt, TodoEditorUiContract.kt, TodoBackground.kt, IntentRouter.kt, TodoDataTransfer.kt, AppBackupAgent.kt
### sync/
- RelayTodoProvider.kt, RelayEnvelopes.kt, CalendarMirror.kt
### notifications/ widget/ service/ intents/
- ReminderScheduler, ReminderReceiver, ReminderActionReceiver, BootReceiver, DigestScheduler; TodayWidget, QuickAddWidget; QuickAddTileService; Get/Insert/CompleteTodoIntent (openassistant tools)

## Verify (this module only)
```
./gradlew :todo:compileDevKotlin :todo:testDevUnitTest :todo:lint
./gradlew checkMetadata
./gradlew :todo:metadata   # regenerate listing screenshots after UI changes
```
Relay changes: also `./gradlew :everysync:testDevUnitTest`.
