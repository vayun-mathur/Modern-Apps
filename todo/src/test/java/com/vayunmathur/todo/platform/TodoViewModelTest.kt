package com.vayunmathur.todo.platform

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.todo.data.TestTodoRepository
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.data.TodoSettings
import com.vayunmathur.todo.data.getListBySyncId
import com.vayunmathur.todo.data.getLists
import com.vayunmathur.todo.data.upsertList
import kotlinx.coroutines.launch
import com.vayunmathur.todo.data.awaitCondition
import com.vayunmathur.todo.domain.SortMode
import com.vayunmathur.todo.domain.TodoView
import com.vayunmathur.todo.domain.format.ImportedTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Genuine [TodoViewModel] behaviour against a real (in-memory) [com.vayunmathur.todo.data.TodoRepository].
 * Repository writes are launched by the ViewModel, so assertions poll the
 * repository / StateFlows until the launched write lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TodoViewModelTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var harness: TestTodoRepository? = null
    private val repo get() = harness!!.repository

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        harness = TestTodoRepository.create(context)
    }

    @AfterTest fun tearDown() {
        harness?.close()
        harness = null
        Dispatchers.resetMain()
    }

    private fun viewModel(saved: Map<String, Any?> = emptyMap()): TodoViewModel =
        TodoViewModel(app, repo, TodoSettings(context), SavedStateHandle(saved))

    private fun await(description: String, condition: suspend () -> Boolean) = runBlocking {
        assertTrue(awaitCondition { condition() }, "Timed out waiting for: $description")
    }

    @Test fun savedStateRestoresSortAndQueryButNotView() {
        // The selected view lives in Route.TodoList, not SavedState: a stale
        // "view" entry must not resurrect a view (there is no view state to read).
        val vm = viewModel(
            mapOf(
                "search_query" to "report",
                "view" to TodoView.InList("work").key,
                "sort" to SortMode.TITLE.name,
            ),
        )
        assertEquals("report", vm.searchQuery.value)
        assertEquals(SortMode.TITLE, vm.sort.value)
    }

    @Test fun invalidSavedSortFallsBackToManual() {
        val vm = viewModel(mapOf("sort" to "not-a-sort"))
        assertEquals(SortMode.MANUAL, vm.sort.value)
    }

    @Test fun clearSearchQueryAndSetSortAndQueryUpdateState() {
        val vm = viewModel()
        vm.setSearchQuery("milk")
        assertEquals("milk", vm.searchQuery.value)
        vm.clearSearchQuery()
        assertEquals("", vm.searchQuery.value)
        vm.setSort(SortMode.PRIORITY)
        assertEquals(SortMode.PRIORITY, vm.sort.value)
        vm.setSearchQuery("eggs")
        assertEquals("eggs", vm.searchQuery.value)
    }

    @Test fun quickAddRequestCanBeSetAndCleared() {
        val vm = viewModel()
        assertNull(vm.quickAddRequest.value)
        vm.requestQuickAdd("Buy milk")
        assertEquals("Buy milk", vm.quickAddRequest.value)
        vm.clearQuickAddRequest()
        assertNull(vm.quickAddRequest.value)
    }

    @Test fun quickAddCreatesParsedTaskInCurrentListContext() { runBlocking {
        repo.upsertList(TodoList(syncId = "home", name = "Home"))
        val vm = viewModel()
        vm.quickAdd(listOf("Buy milk tomorrow #errands", "  ", ""), TodoView.InList("home"))
        await("quick add writes one task") { repo.getAll().size == 1 }
        val task = repo.getAll().single()
        assertEquals("Buy milk", task.title)
        assertEquals("home", task.listSyncId)
        assertEquals(listOf("errands"), task.tags)
        assertNotNull(task.dueAt)
    } }

    @Test fun quickAddInTodayViewDatesTaskTodayAndInPriorityViewPrioritises() = runBlocking {
        val vm = viewModel()
        vm.quickAdd(listOf("Call plumber"), TodoView.Today)
        await("today quick add") { repo.getAll().any { it.title == "Call plumber" } }
        assertNotNull(repo.getAll().first { it.title == "Call plumber" }.dueAt)

        vm.quickAdd(listOf("Urgent fix"), TodoView.Priority)
        await("priority quick add") { repo.getAll().any { it.title == "Urgent fix" } }
        assertEquals(1, repo.getAll().first { it.title == "Urgent fix" }.priority)
    }

    @Test fun quickAddInTaggedViewAppliesTheTag() = runBlocking {
        val vm = viewModel()
        vm.quickAdd(listOf("Post parcel"), TodoView.Tagged("errands"))
        await("tagged quick add") { repo.getAll().size == 1 }
        assertEquals(listOf("errands"), repo.getAll().single().tags)
    }

    @Test fun toggleDoneFlipsStoredTaskBothWays() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "A"))
        val vm = viewModel()
        val task = repo.getBySyncId("a")!!
        vm.toggleDone(task)
        await("toggle on") { repo.getBySyncId("a")!!.done }
        vm.toggleDone(repo.getBySyncId("a")!!)
        await("toggle off") { !repo.getBySyncId("a")!!.done }
    }

    @Test fun completeDeleteAndRestoreActOnWholeSelection() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "A"))
        repo.upsert(Todo(syncId = "b", title = "B", done = true, completedAt = 1L))
        val vm = viewModel()
        vm.complete(listOf(repo.getBySyncId("a")!!, repo.getBySyncId("b")!!))
        await("complete selection") { repo.getBySyncId("a")!!.done }

        vm.delete(listOf(repo.getBySyncId("a")!!))
        await("delete selection") { repo.getBySyncId("a")!!.deleted }
        assertEquals(listOf("b"), repo.getAll().map { it.syncId })

        vm.restore(listOf(repo.getBySyncId("a")!!))
        await("restore selection") { !repo.getBySyncId("a")!!.deleted }
    }

    @Test fun bulkMoveRescheduleAndSetPriorityRewriteRows() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "A", section = "Now"))
        val vm = viewModel()
        val task = repo.getBySyncId("a")!!

        vm.move(listOf(task), "work")
        await("move") { repo.getBySyncId("a")!!.listSyncId == "work" }
        assertNull(repo.getBySyncId("a")!!.section)

        vm.reschedule(listOf(repo.getBySyncId("a")!!), 123_000L)
        await("reschedule") { repo.getBySyncId("a")!!.dueAt == 123_000L }
        assertTrue(repo.getBySyncId("a")!!.dueAllDay)

        vm.setPriority(listOf(repo.getBySyncId("a")!!), 2)
        await("priority") { repo.getBySyncId("a")!!.priority == 2 }

        vm.upsertAll(listOf(repo.getBySyncId("a")!!.copy(title = "Renamed")))
        await("upsertAll") { repo.getBySyncId("a")!!.title == "Renamed" }
    }

    @Test fun upsertAllWithEmptyListIsNoOp() = runBlocking {
        val vm = viewModel()
        vm.upsertAll(emptyList())
        assertTrue(repo.getAll().isEmpty())
    }

    @Test fun saveListAssignsPositionToNewListAndDeleteListTombstonesIt() = runBlocking {
        val vm = viewModel()
        vm.saveList(TodoList(syncId = "work", name = "Work"))
        await("save list") { repo.getListBySyncId("work") != null }
        val saved = repo.getListBySyncId("work")!!
        assertTrue(saved.position != 0.0 || repo.getLists().size == 1)

        vm.deleteList(saved)
        await("delete list") {
            runCatching { harness!!.database.todoListDao().getBySyncId("work")!!.deleted }
                .getOrDefault(false)
        }
        // Resetting the route to Inbox when the viewed list is deleted is the
        // shell's job (it owns the back stack), not the ViewModel's.
    }

    @Test fun reorderListsPersistsGivenOrder() = runBlocking {
        repo.upsertList(TodoList(syncId = "a", name = "A", position = 1.0))
        repo.upsertList(TodoList(syncId = "b", name = "B", position = 2.0))
        val vm = viewModel()
        val a = repo.getListBySyncId("a")!!
        val b = repo.getListBySyncId("b")!!
        vm.reorderLists(listOf(b.copy(position = 1.0), a.copy(position = 2.0)))
        await("reorder") { repo.getLists().map { it.syncId } == listOf("b", "a") }
    }

    @Test fun idForSyncIdReturnsNullForMissingOrDeleted() = runBlocking {
        repo.upsert(Todo(syncId = "live", title = "Live"))
        repo.upsert(Todo(syncId = "gone", title = "Gone"))
        repo.tombstone(repo.getBySyncId("gone")!!)
        val vm = viewModel()
        assertEquals(repo.getBySyncId("live")!!.id, vm.idForSyncId("live"))
        assertNull(vm.idForSyncId("gone"))
        assertNull(vm.idForSyncId("missing"))
    }

    @Test fun todosListsModelCountsAndTagsReflectRepository() = runBlocking {
        repo.upsertList(TodoList(syncId = "work", name = "Work"))
        repo.upsert(Todo(syncId = "a", title = "Report", listSyncId = "work", tags = listOf("office")))
        repo.upsert(Todo(syncId = "b", title = "Done", done = true, completedAt = 1L, tags = listOf("home")))
        val vm = viewModel()
        val model = vm.modelFor(TodoView.All)

        // StateFlows are WhileSubscribed: collect them, then wait for real values.
        val todosJob = launch { vm.todos.collect {} }
        val listsJob = launch { vm.lists.collect {} }
        val modelJob = launch { model.collect {} }
        val countsJob = launch { vm.counts.collect {} }
        val tagsJob = launch { vm.tags.collect {} }
        try {
            await("todos flow") { vm.todos.value.map { it.syncId }.containsAll(listOf("a", "b")) }
            await("lists flow") { vm.lists.value.any { it.syncId == "work" } }
            await("tags flow") { vm.tags.value.containsAll(listOf("office", "home")) }
            await("counts") { vm.counts.value.values.sum() >= 1 }
            await("model") {
                model.value.first.groups.flatMap { it.rows }.any { it.todo.syncId == "a" }
            }
            assertEquals(1, vm.counts.value[TodoView.InList("work")])
            assertFalse(TodoView.All in vm.counts.value.keys)
        } finally {
            todosJob.cancel(); listsJob.cancel(); modelJob.cancel(); countsJob.cancel(); tagsJob.cancel()
        }
    }

    @Test fun modelForComputesPerRouteView() = runBlocking {
        repo.upsertList(TodoList(syncId = "work", name = "Work"))
        repo.upsert(Todo(syncId = "in", title = "In inbox"))
        repo.upsert(Todo(syncId = "wk", title = "In work", listSyncId = "work"))
        val vm = viewModel()
        val inboxModel = vm.modelFor(TodoView.Inbox)
        val workModel = vm.modelFor(TodoView.InList("work"))
        val inboxJob = launch { inboxModel.collect {} }
        val workJob = launch { workModel.collect {} }
        try {
            await("inbox model") {
                inboxModel.value.first.groups.flatMap { it.rows }.any { it.todo.syncId == "in" }
            }
            await("work model") {
                workModel.value.first.groups.flatMap { it.rows }.any { it.todo.syncId == "wk" }
            }
            assertTrue(
                inboxModel.value.first.groups.flatMap { it.rows }.none { it.todo.syncId == "wk" },
            )
            assertTrue(
                workModel.value.first.groups.flatMap { it.rows }.none { it.todo.syncId == "in" },
            )
        } finally {
            inboxJob.cancel(); workJob.cancel()
        }
    }

    @Test fun modelHidesCompletedWhenSettingOff() = runBlocking {
        repo.upsert(Todo(syncId = "open", title = "Open"))
        repo.upsert(Todo(syncId = "done", title = "Done", done = true, completedAt = 1L))
        val vm = viewModel()
        val model = vm.modelFor(TodoView.All)
        vm.updateSettings { setShowCompleted(false) }
        val job = launch { model.collect {} }
        try {
            await("completed row hidden") {
                model.value.first.groups.flatMap { it.rows }.any { it.todo.syncId == "open" } &&
                    model.value.first.completed.isEmpty()
            }
            assertTrue(
                model.value.first.groups.flatMap { it.rows }.any { it.todo.syncId == "open" },
            )
        } finally {
            job.cancel()
        }
        assertFalse(TodoSettings(context).showCompleted.first())
    }

    @Test fun updateSettingsWritesThroughToSettingsStore() = runBlocking {
        val vm = viewModel()
        vm.updateSettings { setDigestEnabled(true) }
        await("digest setting") { TodoSettings(context).digestEnabled.first() }
        vm.updateSettings { setCalendarMirror(true) }
        await("calendar setting") { TodoSettings(context).calendarMirror.first() }
    }

    @Test fun importDelegatesToDataTransferAndReportsCount() { runBlocking {
        val vm = viewModel()
        var reported: Int? = null
        vm.import(
            listOf(
                ImportedTask(title = "Imported one", listName = "Imported"),
                ImportedTask(title = "Imported two"),
            ),
        ) { reported = it }
        await("import writes") { repo.getAll().size == 2 }
        await("import callback") { reported != null }
        assertEquals(2, reported)
        assertNotNull(repo.getLists().firstOrNull { it.name == "Imported" })
    } }

    @Test fun openEditorForExistingAndNewTasks() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "Existing"))
        val vm = viewModel()
        val existing = vm.openEditor(repo.getBySyncId("a")!!.id, null, TodoView.Inbox)
        await("editor loads existing") { existing.state.value?.title == "Existing" }
        existing.close()

        val fresh = vm.openEditor(0L, "brand-new-sync", TodoView.InList("work"))
        await("editor loads template") { fresh.state.value != null }
        assertEquals("brand-new-sync", fresh.state.value!!.syncId)
        assertEquals("work", fresh.state.value!!.listSyncId)
        fresh.close()
    }

    @Test fun pendingSyncCountsChangesAfterLastSync() = runBlocking {
        TodoSettings(context).markSynced(1L)
        repo.upsert(Todo(syncId = "a", title = "A"))
        val vm = viewModel()
        val job = launch { vm.pendingSync.collect {} }
        try {
            await("pending sync") { vm.pendingSync.value >= 1 }
        } finally {
            job.cancel()
        }
    }
}
