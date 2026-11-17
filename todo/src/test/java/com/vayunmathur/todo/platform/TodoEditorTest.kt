package com.vayunmathur.todo.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.todo.data.TestTodoRepository
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.awaitCondition
import com.vayunmathur.todo.domain.Recurrence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * Genuine [TodoEditor] edit-session behaviour against a real (in-memory)
 * repository. The editor debounces saves by [TodoEditor.SAVE_DEBOUNCE_MS] on the
 * supplied scope, so tests poll the repository until the save lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TodoEditorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var harness: TestTodoRepository? = null
    private val repo get() = harness!!.repository
    private lateinit var scope: CoroutineScope

    @BeforeTest fun setUp() {
        // TodoEditor.duplicate() resumes on Dispatchers.Main; Robolectric's
        // main looper is paused, so give it an immediate dispatcher or the
        // withContext(Main) callback never fires.
        Dispatchers.setMain(Dispatchers.Unconfined)
        harness = TestTodoRepository.create(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
        harness?.close()
        harness = null
    }

    private fun await(description: String, condition: suspend () -> Boolean) = runBlocking {
        assertTrue(awaitCondition(timeoutMs = 20_000) { condition() }, "Timed out: $description")
    }

    private fun editorFor(id: Long = 0L, newSyncId: String? = null, template: Todo = Todo()): TodoEditor =
        TodoEditor(repo, scope, id, newSyncId, template)

    @Test fun existingTaskLoadsById() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "Stored", notes = "keep"))
        val id = repo.getBySyncId("a")!!.id
        val editor = editorFor(id = id)
        await("load by id") { editor.state.value?.syncId == "a" }
        assertEquals("Stored", editor.state.value!!.title)
        editor.close()
    }

    @Test fun existingTaskLoadsByNewSyncIdWhenIdIsZero() = runBlocking {
        repo.upsert(Todo(syncId = "from-sync", title = "Pulled"))
        val editor = editorFor(id = 0L, newSyncId = "from-sync")
        await("load by sync id") { editor.state.value?.title == "Pulled" }
        editor.close()
    }

    @Test fun newTaskUsesTemplateAndIsNotInsertedUntilTitled() = runBlocking {
        val editor = editorFor(
            newSyncId = "fresh",
            template = Todo(title = "", listSyncId = "work", priority = 2),
        )
        await("template loads") { editor.state.value?.syncId == "fresh" }
        assertEquals("work", editor.state.value!!.listSyncId)
        assertEquals(2, editor.state.value!!.priority)
        // No title yet, and closing must not create a row.
        editor.close()
        // Give the close-time save a chance to (not) write, then check the store.
        await("still absent after settle") {
            repo.getBySyncId("fresh") == null
        }
        assertNull(repo.getBySyncId("fresh"))
    }

    @Test fun typingTitleDebouncesInsertAndAssignsId() = runBlocking {
        val editor = editorFor(newSyncId = "typed")
        await("template") { editor.state.value != null }
        editor.update { it.copy(title = "Hello task") }
        await("debounced insert") { repo.getBySyncId("typed")?.title == "Hello task" }
        await("id back-filled") { (editor.state.value?.id ?: 0L) != 0L }
        assertEquals(repo.getBySyncId("typed")!!.id, editor.state.value!!.id)
        editor.close()
    }

    @Test fun editsToExistingTaskPersistThroughDebounce() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "Before"))
        val editor = editorFor(id = repo.getBySyncId("a")!!.id)
        await("load") { editor.state.value?.title == "Before" }
        editor.update { it.copy(title = "After", priority = 1, tags = listOf("x")) }
        await("edit persists") { repo.getBySyncId("a")?.title == "After" }
        assertEquals(1, repo.getBySyncId("a")!!.priority)
        assertEquals(listOf("x"), repo.getBySyncId("a")!!.tags)
        editor.close()
    }

    @Test fun rapidUpdatesCoalesceToLatestValue() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "v0"))
        val editor = editorFor(id = repo.getBySyncId("a")!!.id)
        await("load") { editor.state.value != null }
        editor.update { it.copy(title = "v1") }
        editor.update { it.copy(title = "v2") }
        editor.update { it.copy(title = "v3") }
        await("latest persists") { repo.getBySyncId("a")?.title == "v3" }
        editor.close()
    }

    @Test fun updateBeforeLoadAndAfterCloseAreIgnored() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "Stable"))
        // Gate the editor's scope on a single occupied thread so the load
        // cannot complete before the update below: on an unloaded box the
        // load (Dispatchers.Default) can win that race, the "too early"
        // update then legitimately applies, and the load await times out
        // waiting for a title that was correctly overwritten.
        val gate = java.util.concurrent.CountDownLatch(1)
        val gatedScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.Default.limitedParallelism(1)
        )
        gatedScope.launch { gate.await() }
        val editor = TodoEditor(repo, gatedScope, repo.getBySyncId("a")!!.id, null, Todo())
        // state is still null here, so this update must be dropped, not crash.
        editor.update { it.copy(title = "Too early") }
        gate.countDown()
        await("load") { editor.state.value?.title == "Stable" }
        editor.close()
        editor.update { it.copy(title = "Too late") }
        assertEquals("Stable", repo.getBySyncId("a")!!.title)
        gatedScope.cancel()
    }

    @Test fun closeFlushesPendingEditWithoutWaitingForDebounce() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "Before"))
        val editor = editorFor(id = repo.getBySyncId("a")!!.id)
        await("load") { editor.state.value != null }
        editor.update { it.copy(title = "Flushed") }
        editor.close()
        await("close flushes") { repo.getBySyncId("a")?.title == "Flushed" }
    }

    @Test fun clearingTitleOfBrandNewTaskDiscardsItOnClose() = runBlocking {
        val editor = editorFor(newSyncId = "temp")
        await("template") { editor.state.value != null }
        editor.update { it.copy(title = "Temporary") }
        await("inserted") { repo.getBySyncId("temp") != null }
        editor.update { it.copy(title = "") }
        editor.close()
        await("discarded on close") { repo.getBySyncId("temp")?.deleted == true }
    }

    @Test fun clearingTitleOfExistingTaskKeepsTheRow() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "Named"))
        val editor = editorFor(id = repo.getBySyncId("a")!!.id)
        await("load") { editor.state.value != null }
        editor.update { it.copy(title = "") }
        editor.close()
        await("edit settled") { repo.getBySyncId("a")?.title == "" }
        assertNotNull(repo.getBySyncId("a"))
        assertFalse(repo.getBySyncId("a")!!.deleted)
    }

    @Test fun setDoneCompletesPlainTaskAndUncompleteReopens() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "Task"))
        val editor = editorFor(id = repo.getBySyncId("a")!!.id)
        await("load") { editor.state.value != null }
        editor.setDone(true)
        await("done persists") { repo.getBySyncId("a")?.done == true }
        assertNotNull(repo.getBySyncId("a")!!.completedAt)
        editor.setDone(false)
        await("reopen persists") { repo.getBySyncId("a")?.done == false }
        assertNull(repo.getBySyncId("a")!!.completedAt)
        editor.close()
    }

    @Test fun setDoneOnRepeatingTaskRollsForwardAndReopensSubtasks() = runBlocking {
        val due = System.currentTimeMillis() - 86_400_000L
        repo.upsert(
            Todo(
                syncId = "r", title = "Daily", dueAt = due, dueAllDay = true,
                recurrence = Recurrence.Daily(1).serialize(),
            ),
        )
        repo.upsert(Todo(syncId = "sub", title = "Sub", parentSyncId = "r", done = true, completedAt = due))
        val editor = editorFor(id = repo.getBySyncId("r")!!.id)
        await("load") { editor.state.value?.syncId == "r" }
        editor.setDone(true)
        await("rolled forward") {
            val stored = repo.getBySyncId("r")
            stored != null && !stored.done && (stored.dueAt ?: 0L) > due
        }
        await("subtask reopened") { repo.getBySyncId("sub")?.done == false }
        editor.close()
    }

    @Test fun addSubtaskCreatesChildAndBlankIsIgnored() = runBlocking {
        repo.upsert(Todo(syncId = "p", title = "Parent"))
        val editor = editorFor(id = repo.getBySyncId("p")!!.id)
        await("load") { editor.state.value != null }
        editor.addSubtask("   ")
        editor.addSubtask("Real subtask")
        await("subtask created") { repo.getSubtasks("p").size == 1 }
        assertEquals("Real subtask", repo.getSubtasks("p").single().title)
        editor.close()
    }

    @Test fun addSubtaskToNewTaskSavesParentFirst() = runBlocking {
        val editor = editorFor(newSyncId = "newparent")
        await("template") { editor.state.value != null }
        editor.update { it.copy(title = "New parent") }
        editor.addSubtask("Child")
        await("parent and child exist") {
            repo.getBySyncId("newparent") != null && repo.getSubtasks("newparent").size == 1
        }
        editor.close()
    }

    @Test fun subtasksFlowReflectsChildrenOfLoadedTask() = runBlocking {
        repo.upsert(Todo(syncId = "p", title = "Parent"))
        repo.upsert(Todo(syncId = "s", title = "Sub", parentSyncId = "p"))
        val editor = editorFor(id = repo.getBySyncId("p")!!.id)
        await("load") { editor.state.value != null }
        val subs = editor.subtasks.first { it.isNotEmpty() }
        assertEquals(listOf("Sub"), subs.map { it.title })
        editor.close()
    }

    @Test fun duplicateCreatesCopyAndReportsNewId() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "Original"))
        repo.upsert(Todo(syncId = "s", title = "Sub", parentSyncId = "a"))
        val editor = editorFor(id = repo.getBySyncId("a")!!.id)
        await("load") { editor.state.value != null }
        var createdId: Long? = null
        editor.duplicate { createdId = it }
        await("duplicate callback") { createdId != null }
        val copy = repo.getById(createdId!!)
        assertNotNull(copy)
        assertEquals("Original", copy!!.title)
        assertTrue(copy.syncId != "a")
        assertEquals(1, repo.getSubtasks(copy.syncId).size)
        editor.close()
    }

    @Test fun duplicateOfUnsavedNewTaskDoesNothing() = runBlocking {
        val editor = editorFor(newSyncId = "unsaved")
        await("template") { editor.state.value != null }
        var called = false
        editor.duplicate { called = true }
        // Give the save-first path time to run; an unsaved blank task must
        // never be duplicated.
        Thread.sleep(600)
        assertTrue(repo.getAll().isEmpty())
        assertFalse(called)
        editor.close()
    }

    @Test fun deleteTombstonesTaskAndFurtherUpdatesAreIgnored() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "Doomed"))
        val editor = editorFor(id = repo.getBySyncId("a")!!.id)
        await("load") { editor.state.value != null }
        editor.delete()
        await("tombstoned") { repo.getBySyncId("a")?.deleted == true }
        editor.update { it.copy(title = "Zombie") }
        assertEquals("Doomed", repo.getBySyncId("a")!!.title)
    }

    @Test fun storedChangesFlowIntoStateWhileNotEditing() = runBlocking {
        repo.upsert(Todo(syncId = "a", title = "Before"))
        val editor = editorFor(id = repo.getBySyncId("a")!!.id)
        await("load") { editor.state.value?.title == "Before" }
        // Simulate a sync pull / another screen updating the same row.
        repo.upsert(repo.getBySyncId("a")!!.copy(title = "From elsewhere"))
        await("state follows store") { editor.state.value?.title == "From elsewhere" }
        editor.close()
    }
}
