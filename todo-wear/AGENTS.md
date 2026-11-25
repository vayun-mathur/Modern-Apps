# AGENTS.md — todo-wear/ (':todo-wear')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `todo-wear/` + `todo/` (the phone side of the same feature).

- Gradle: :todo-wear / dir: todo-wear/ (convention: `common-conventions-wear`)
- Package roots present: ui, data, domain, sync
- Entry file: ui/WearMainActivity.kt

## Why this module exists (read before changing identity)
Wear OS companion of the phone Todo (`:todo`). This module deliberately
uses `applicationId = "com.vayunmathur.todo"` (the phone app's) with the
shared dev/release signing from the convention plugins: WearLink's
handshake token requires the same signing certificate on both sides, so
changing either breaks phone<->watch sync. Bootstrap is a one-time ADB
sideload of this APK onto the watch.

## Navigation mirror (phone Route -> watch WearRoute)
- `Route.TodoList("inbox"|"today"|"upcoming"|"priority"|"all"|"completed")`
  -> `WearRoute.Page` with the same key (smart views, phone drawer order)
- `Route.TodoList("list:<syncId>")` / `("tag:<tag>")` -> `WearRoute.Page`
  with the same key (catalog carries every list/tag from the phone drawer)
- `Route.TodoDetail(id)` -> `WearRoute.TaskDetail(syncId)` (syncId, not the
  phone's Room id, is the cross-device identity)
- Quick-add sheet -> `WearRoute.QuickAdd(pageKey)`; detail edit ->
  `WearRoute.EditTask(syncId)`; delete -> `WearRoute.ConfirmDelete(syncId)`
- `Route.Settings`: NO watch counterpart — settings, sync setup, import/
  export and data management stay on the phone (screen + data-entry cost
  with no watch benefit). Also omitted, deliberately: search, sort,
  manual reorder, bulk selection, list/tag/section editing, recurrence
  and reminder-offset editing, blocked-by/estimates, subtask creation
  (existing subtasks show and can be checked off). These remain fully
  available in the phone detail editor the watch data comes from.

## Protocol
`domain/WearTodoProtocol.kt` is a copy of the phone's file (Gradle cannot
depend on another app module). Change both copies together; each side's
`WearTodoProtocolTest` pins the same golden JSON.

- Catalog + page snapshots: WearLink data items (`/todo/catalog`,
  `/todo/page`, phone -> watch; paginated, PAGE_SIZE tasks per page)
- Page/sync requests: WearLink messages (`/todo/request-page`,
  `/todo/request-sync`, watch -> phone)
- Watch-originated ops: WearLink message (`/todo/op`: SET_DONE incl.
  repeating-task roll-forward on the phone, SNOOZE_HOUR/SNOOZE_TOMORROW,
  QUICK_ADD parsed by the phone's QuickAddParser, EDIT title/due/priority,
  DELETE with watch-side confirmation)
- Transport: :library:wearlink (RFCOMM, no Play services); app-lifecycle-bound

## Sync semantics (stated, not implied)
- Phone -> watch: bounded catalog (≤100 views) + per-view pages of 20
  tasks, ≤90_000 JSON bytes each. The watch caches catalog and every
  visited page in app-private files and renders them fully offline.
- Watch -> phone: ops apply on the phone only (source of truth). An op
  stays pending (persisted, resent on reconnect) until a refreshed
  catalog/page reflects it; SET_DONE/EDIT/DELETE also overlay the cached
  pages locally so the watch never shows pre-op state as confirmed.

## Verify (this module only)
```
./gradlew :todo-wear:assembleDev
./gradlew :todo-wear:testDebugUnitTest
```
