package com.vayunmathur.todo.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Phone <-> watch protocol for the Wear OS Todo companion.
 *
 * SINGLE SOURCE OF TRUTH, DELIBERATELY DUPLICATED: Gradle cannot let one
 * application module depend on another, so the wear module (`:todo-wear`,
 * applicationId `com.vayunmathur.todo`) carries a copy of this file under
 * `com.vayunmathur.todo.wear.domain`. Change both copies together; each
 * side's unit tests pin the same golden JSON so a one-sided edit fails loudly.
 *
 * The watch mirrors the phone app's navigation, not a glance card: the phone
 * `Route.TodoList(viewKey)` destinations (smart views, per-list views, tag
 * views) become watch pages with the SAME [WearTodoViewEntry.key] strings
 * (`TodoView.key`: "inbox", "today", "upcoming", "priority", "all",
 * "completed", "list:<syncId>", "tag:<tag>"), and `Route.TodoDetail(id)`
 * becomes a watch task page keyed by the task's stable syncId. The phone's
 * `Route.Settings` has no watch counterpart (see todo-wear/AGENTS.md).
 *
 * Transport (WearLink, :library:wearlink — RFCOMM, no Play services):
 * - Data items: [CATALOG_PATH] phone -> watch (view index with counts),
 *   [PAGE_PATH] phone -> watch (one bounded paginated page snapshot),
 *   [WATCH_STATE_PATH] watch -> phone (what the watch cached + pending ops).
 * - Messages: [OP_PATH] watch -> phone (one op), [REQUEST_PAGE_PATH]
 *   watch -> phone (ask for one page), [REQUEST_SYNC_PATH] watch -> phone
 *   (ask for catalog + the default/last pages). Delivery needs a live
 *   session; nothing wakes a dead phone process, so the watch caches every
 *   catalog/page it receives and reads fully offline.
 *
 * Data lives on the phone. The watch never holds the task database: it
 * renders cached catalog/page snapshots and queues ops. Ops apply on the
 * phone only (source of truth); an op stays pending on the watch until a
 * refreshed catalog/page reflects it, and the watch UI says so.
 */
object WearTodoProtocol {
    /** WearLink appId the phone requires in the peer handshake. */
    const val CAPABILITY = "todo_wear_companion"

    /** Data-item path (phone -> watch): the [WearTodoCatalog] JSON. */
    const val CATALOG_PATH = "/todo/catalog"

    /** Data-item path (phone -> watch): one [WearTodoPage] JSON. */
    const val PAGE_PATH = "/todo/page"

    /** Data-item path (watch -> phone): the [WearTodoWatchState] JSON. */
    const val WATCH_STATE_PATH = "/todo/watch-state"

    /** Message path (watch -> phone): one [WearTodoOp] JSON. */
    const val OP_PATH = "/todo/op"

    /** Message path (watch -> phone): one [WearTodoPageRequest] JSON. */
    const val REQUEST_PAGE_PATH = "/todo/request-page"

    /** Message path (watch -> phone): ask for catalog + default pages. */
    const val REQUEST_SYNC_PATH = "/todo/request-sync"

    /** Tasks per page snapshot; "load more" fetches the next offset. */
    const val PAGE_SIZE = 20

    /** Largest catalog/page JSON in UTF-8 bytes (near the old 100 KB ceiling). */
    const val MAX_JSON_BYTES = 90_000

    /** Largest view index kept in one catalog; extras are dropped (tags last). */
    const val MAX_VIEWS = 100

    /** Field caps applied when a snapshot is built. */
    const val MAX_TITLE_CHARS = 120
    const val MAX_NOTES_CHARS = 1_000
    const val MAX_SUBTASKS = 20

    /** Cap on watch-entered text (quick-add / edit title) before it is sent. */
    const val MAX_TEXT_CHARS = 500

    /** Wire JSON: unknown keys ignored so a newer side never breaks an older one. */
    val json: Json = Json { ignoreUnknownKeys = true }
}

/** One phone navigation destination in the watch catalog (a `TodoView.key`). */
@Serializable
data class WearTodoViewEntry(
    val key: String,
    val title: String,
    /** SMART, LIST or TAG — mirrors the phone drawer's sections. */
    val kind: String = "SMART",
    val openCount: Int = 0,
    val totalCount: Int = 0,
)

/** The phone's view index for the watch home page ([WearTodoProtocol.CATALOG_PATH]). */
@Serializable
data class WearTodoCatalog(
    val generatedAtMillis: Long = 0L,
    val views: List<WearTodoViewEntry> = emptyList(),
    /** The phone's default view key; the watch opens here first. */
    val defaultViewKey: String = "today",
    /** True when the view-count/byte caps dropped entries. */
    val truncated: Boolean = false,
)

/** One subtask trimmed to watch size, shown on the task page. */
@Serializable
data class WearTodoSubtask(
    val syncId: String,
    val title: String,
    val done: Boolean = false,
)

/** One top-level task trimmed to watch size. [syncId] is the stable sync identity. */
@Serializable
data class WearTodoTask(
    val syncId: String,
    val title: String,
    val notes: String = "",
    val done: Boolean = false,
    val dueAt: Long? = null,
    val dueAllDay: Boolean = true,
    val priority: Int = 0,
    val listSyncId: String? = null,
    val listName: String? = null,
    val section: String? = null,
    val tags: List<String> = emptyList(),
    val recurrence: String? = null,
    val reminderAt: Long? = null,
    val completedAt: Long? = null,
    val updatedAt: Long = 0L,
    val subtasks: List<WearTodoSubtask> = emptyList(),
    val overdue: Boolean = false,
    val blocked: Boolean = false,
    /** Due-bucket / section / "Completed" label the phone list groups this row under. */
    val groupLabel: String = "",
    val notesTruncated: Boolean = false,
)

/**
 * One bounded page of one view ([WearTodoProtocol.PAGE_PATH]). Paging is by
 * [offset] into the phone-ordered rows of [pageKey]; [totalCount] is the
 * full row count on the phone at [generatedAtMillis].
 */
@Serializable
data class WearTodoPage(
    val pageKey: String,
    val title: String = "",
    val generatedAtMillis: Long = 0L,
    val offset: Int = 0,
    val totalCount: Int = 0,
    val tasks: List<WearTodoTask> = emptyList(),
    /** True when the byte cap cut this page short (the rest pages normally). */
    val truncated: Boolean = false,
) {
    val hasMore: Boolean get() = offset + tasks.size < totalCount
    val nextOffset: Int get() = offset + tasks.size
}

/** What the watch last cached, published at [WearTodoProtocol.WATCH_STATE_PATH]. */
@Serializable
data class WearTodoWatchState(
    val appliedCatalogAtMillis: Long = 0L,
    val cachedPages: Int = 0,
    /** Watch-originated ops not yet reflected by the phone. */
    val pendingOps: Int = 0,
)

/** Ask the phone for one page (message [WearTodoProtocol.REQUEST_PAGE_PATH]). */
@Serializable
data class WearTodoPageRequest(
    val pageKey: String,
    val offset: Int = 0,
)

/** Kind of a watch-originated change. */
@Serializable
enum class WearTodoOpKind { SET_DONE, SNOOZE_HOUR, SNOOZE_TOMORROW, QUICK_ADD, EDIT, DELETE }

/**
 * One watch-originated op, as [WearTodoProtocol.OP_PATH]. [opId] is unique
 * per user action so quick-adds never dedupe against each other; the other
 * kinds carry the target task's [syncId]. EDIT uses null fields for
 * "unchanged" and [clearDue] for removing the due date. QUICK_ADD carries
 * raw [text] — the PHONE parses it with its QuickAddParser, so watch
 * dictation gets the phone's exact date/list/tag/priority semantics.
 */
@Serializable
data class WearTodoOp(
    val kind: WearTodoOpKind,
    val opId: String = "",
    val syncId: String = "",
    val done: Boolean = false,
    val text: String = "",
    val pageKey: String = "",
    val title: String? = null,
    val dueAt: Long? = null,
    val clearDue: Boolean = false,
    val priority: Int? = null,
)

/** Builds a bounded catalog: view caps then the byte cap (last entries drop first). */
fun buildWearCatalog(
    views: List<WearTodoViewEntry>,
    generatedAtMillis: Long,
    defaultViewKey: String,
): WearTodoCatalog {
    var kept = views.take(WearTodoProtocol.MAX_VIEWS)
    var truncated = kept.size < views.size
    var catalog = WearTodoCatalog(generatedAtMillis, kept, defaultViewKey, truncated)
    while (kept.isNotEmpty() &&
        WearTodoProtocol.json.encodeToString(catalog).encodeToByteArray().size >
        WearTodoProtocol.MAX_JSON_BYTES
    ) {
        kept = kept.dropLast(1)
        truncated = true
        catalog = catalog.copy(views = kept, truncated = true)
    }
    return catalog
}

/**
 * Builds one bounded page: field caps, the [WearTodoProtocol.PAGE_SIZE]
 * slice at [offset], then the byte cap enforced by shortening the page
 * (paging continues from the shortened end via [WearTodoPage.nextOffset]).
 */
fun buildWearPage(
    pageKey: String,
    title: String,
    allTasks: List<WearTodoTask>,
    offset: Int,
    generatedAtMillis: Long,
): WearTodoPage {
    val capped = allTasks.map { task ->
        task.copy(
            title = task.title.take(WearTodoProtocol.MAX_TITLE_CHARS),
            notes = task.notes.take(WearTodoProtocol.MAX_NOTES_CHARS),
            notesTruncated = task.notesTruncated ||
                task.notes.length > WearTodoProtocol.MAX_NOTES_CHARS,
            subtasks = task.subtasks.take(WearTodoProtocol.MAX_SUBTASKS).map {
                it.copy(title = it.title.take(WearTodoProtocol.MAX_TITLE_CHARS))
            },
        )
    }
    val safeOffset = offset.coerceIn(0, capped.size)
    var kept = capped.drop(safeOffset).take(WearTodoProtocol.PAGE_SIZE)
    var page = WearTodoPage(
        pageKey = pageKey,
        title = title,
        generatedAtMillis = generatedAtMillis,
        offset = safeOffset,
        totalCount = capped.size,
        tasks = kept,
    )
    while (kept.isNotEmpty() &&
        WearTodoProtocol.json.encodeToString(page).encodeToByteArray().size >
        WearTodoProtocol.MAX_JSON_BYTES
    ) {
        kept = kept.dropLast(1)
        page = page.copy(tasks = kept, truncated = true)
    }
    return page
}

/**
 * The watch's local view of a cached page while ops are pending: SET_DONE,
 * EDIT and DELETE apply to the cached rows so the watch does not show
 * pre-op state as truth; SNOOZE and QUICK_ADD cannot be rendered locally
 * (the phone computes reminder times and parses quick-add text) and only
 * count as pending. Phone snapshots remain the source of truth.
 */
fun overlayPendingTasks(tasks: List<WearTodoTask>, pendingOps: List<WearTodoOp>): List<WearTodoTask> {
    if (pendingOps.isEmpty()) return tasks
    var result = tasks
    for (op in pendingOps) {
        result = when (op.kind) {
            WearTodoOpKind.DELETE -> result.filter { it.syncId != op.syncId }
            WearTodoOpKind.SET_DONE -> result.map { task ->
                when {
                    task.syncId == op.syncId -> task.copy(done = op.done)
                    task.subtasks.any { it.syncId == op.syncId } -> task.copy(
                        subtasks = task.subtasks.map {
                            if (it.syncId == op.syncId) it.copy(done = op.done) else it
                        },
                    )
                    else -> task
                }
            }
            WearTodoOpKind.EDIT -> result.map { task ->
                if (task.syncId != op.syncId) task else task.copy(
                    title = op.title?.take(WearTodoProtocol.MAX_TITLE_CHARS) ?: task.title,
                    dueAt = when {
                        op.clearDue -> null
                        op.dueAt != null -> op.dueAt
                        else -> task.dueAt
                    },
                    priority = op.priority ?: task.priority,
                )
            }
            WearTodoOpKind.SNOOZE_HOUR, WearTodoOpKind.SNOOZE_TOMORROW,
            WearTodoOpKind.QUICK_ADD -> result
        }
    }
    return result
}
