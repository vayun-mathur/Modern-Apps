package com.vayunmathur.todo.platform

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.todo.data.TestTodoRepository
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.getLists
import com.vayunmathur.todo.data.upsertList
import com.vayunmathur.todo.domain.format.ExportFormat
import com.vayunmathur.todo.domain.format.ImportedTask
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Genuine [TodoDataTransfer] import / read / export behaviour.
 *
 * Faked API: only the Storage Access Framework stream endpoints: tests use
 * `file://` URIs in the cache dir, so the real ContentResolver file streams
 * drive [TodoDataTransfer.read]/[TodoDataTransfer.export] format detection
 * and serialisation without a document provider. Import runs against the
 * real repository.
 */
@RunWith(RobolectricTestRunner::class)
class TodoDataTransferTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var harness: TestTodoRepository? = null
    private val repo get() = harness!!.repository

    @BeforeTest fun setUp() {
        harness = TestTodoRepository.create(context)
    }

    @AfterTest fun tearDown() {
        harness?.close()
        harness = null
    }

    private fun inputUri(name: String, bytes: ByteArray): Uri {
        val file = File(context.cacheDir, name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun outputUri(name: String): Uri {
        val file = File(context.cacheDir, name)
        file.delete()
        return Uri.fromFile(file)
    }

    private fun absentUri(name: String): Uri {
        val file = File(context.cacheDir, name)
        file.delete()
        return Uri.fromFile(file)
    }

    private fun outputText(uri: Uri): String = File(uri.path!!).readText(Charsets.UTF_8)

    // --- import() -----

    @Test fun importCreatesTasksAndMissingListsByName() = runTest {
        val transfer = TodoDataTransfer(context, repo)
        val count = transfer.import(
            listOf(
                ImportedTask(title = "One", listName = "Groceries"),
                ImportedTask(title = "Two", listName = "Groceries"),
                ImportedTask(title = "Three"),
            ),
        )
        assertEquals(3, count)
        assertEquals(3, repo.getAll().size)
        val lists = repo.getLists()
        assertEquals(1, lists.size)
        assertEquals("Groceries", lists.single().name)
        val inList = repo.getAll().filter { it.listSyncId == lists.single().syncId }
        assertEquals(setOf("One", "Two"), inList.map { it.title }.toSet())
        assertNull(repo.getAll().first { it.title == "Three" }.listSyncId)
    }

    @Test fun importListLookupIsCaseInsensitiveAndReusesExisting() = runTest {
        repo.upsertList(com.vayunmathur.todo.data.TodoList(syncId = "work", name = "Work"))
        val transfer = TodoDataTransfer(context, repo)
        transfer.import(listOf(ImportedTask(title = "Task", listName = "work")))
        assertEquals(1, repo.getLists().size)
        assertEquals("work", repo.getAll().single().listSyncId)
    }

    @Test fun importLinksSubtasksThroughRefsWithFreshIdentities() = runTest {
        val transfer = TodoDataTransfer(context, repo)
        transfer.import(
            listOf(
                ImportedTask(title = "Parent", ref = "p1"),
                ImportedTask(title = "Child", ref = "c1", parentRef = "p1"),
                ImportedTask(title = "Orphan", parentRef = "missing"),
            ),
        )
        val parent = repo.getAll().first { it.title == "Parent" }
        val child = repo.getAll().first { it.title == "Child" }
        val orphan = repo.getAll().first { it.title == "Orphan" }
        assertEquals(parent.syncId, child.parentSyncId)
        // Refs from the file never become local sync ids, and unknown parents drop.
        assertTrue(parent.syncId != "p1")
        assertNull(orphan.parentSyncId)
        // Parent precedes child so ordering positions increase in file order.
        assertTrue(child.position > parent.position)
        assertTrue(orphan.position > child.position)
    }

    @Test fun importPreservesFieldsAndCompletionDefaults() = runTest {
        val transfer = TodoDataTransfer(context, repo)
        transfer.import(
            listOf(
                ImportedTask(
                    title = "Detailed", notes = "notes", done = true, dueAt = 777L,
                    dueAllDay = false, priority = 2, section = "Now", tags = listOf("x"),
                    recurrence = "FREQ=DAILY", startAt = 100L, createdAt = 50L,
                ),
                ImportedTask(title = "Open", createdAt = null),
            ),
        )
        val detailed = repo.getAll().first { it.title == "Detailed" }
        assertEquals("notes", detailed.notes)
        assertTrue(detailed.done)
        assertEquals(777L, detailed.dueAt)
        assertFalse(detailed.dueAllDay)
        assertEquals(2, detailed.priority)
        assertEquals("Now", detailed.section)
        assertEquals(listOf("x"), detailed.tags)
        assertEquals("FREQ=DAILY", detailed.recurrence)
        assertEquals(100L, detailed.startAt)
        assertEquals(50L, detailed.createdAt)
        // A done task without an explicit completion time is stamped at import.
        assertNotNull(detailed.completedAt)
        val open = repo.getAll().first { it.title == "Open" }
        assertFalse(open.done)
        assertNull(open.completedAt)
        assertTrue(open.createdAt > 0L)
    }

    @Test fun importEmptyListReturnsZero() = runTest {
        val transfer = TodoDataTransfer(context, repo)
        assertEquals(0, transfer.import(emptyList()))
        assertTrue(repo.getAll().isEmpty())
    }

    // --- read() -----

    @Test fun readDetectsTodoTxtAndFiltersBlankTitles() = runTest {
        val tasks = TodoDataTransfer(context, repo).read(
            inputUri("a.txt", "Buy milk +Home @errands\n\nx 2026-01-01 Finished task\n(x) not a task\nCall mum".toByteArray()),
        )
        assertEquals(listOf("Buy milk", "Finished task", "(x) not a task", "Call mum"), tasks.map { it.title })
        assertEquals(listOf(false, true, false, false), tasks.map { it.done })
    }

    @Test fun readDetectsCsvFromHeader() = runTest {
        val csv = "Title,Notes,Done,Due,Priority,List,Tags\nCsv task,from file,false,,1,Work,x\n"
        val tasks = TodoDataTransfer(context, repo).read(inputUri("a.csv", csv.toByteArray()))
        assertEquals(1, tasks.size)
        assertEquals("Csv task", tasks.single().title)
    }

    @Test fun readDetectsICalendar() = runTest {
        val ics = buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\n")
            append("UID:task1\r\nSUMMARY:Ics task\r\nEND:VTODO\r\nEND:VCALENDAR\r\n")
        }
        val tasks = TodoDataTransfer(context, repo).read(inputUri("a.ics", ics.toByteArray()))
        assertEquals(listOf("Ics task"), tasks.map { it.title })
    }

    @Test fun readStripsBomAndHandlesEmptyOrMissingStream() = runTest {
        val transfer = TodoDataTransfer(context, repo)
        val bom = inputUri("bom.txt", ("\uFEFF" + "Bom task").toByteArray(Charsets.UTF_8))
        assertEquals(listOf("Bom task"), transfer.read(bom).map { it.title })
        assertFailsWith<java.io.FileNotFoundException> { transfer.read(absentUri("absent.txt")) }
        assertTrue(transfer.read(inputUri("empty.txt", ByteArray(0))).isEmpty())
    }

    // --- export() -----

    @Test fun exportWritesTodoTxtCsvAndICalendar() = runTest {
        repo.upsertList(com.vayunmathur.todo.data.TodoList(syncId = "work", name = "Work"))
        repo.upsert(Todo(syncId = "a", title = "Export me", listSyncId = "work", tags = listOf("office")))
        val transfer = TodoDataTransfer(context, repo)

        val txtUri = outputUri("out.txt")
        transfer.export(txtUri, ExportFormat.TODO_TXT)
        val txt = outputText(txtUri)
        assertTrue(txt.contains("Export me"), txt)
        assertTrue(txt.contains("+Work"), txt)

        val csvUri = outputUri("out.csv")
        transfer.export(csvUri, ExportFormat.CSV)
        val csv = outputText(csvUri)
        assertTrue(csv.startsWith("Title,"), csv)
        assertTrue(csv.contains("Export me"), csv)

        val icsUri = outputUri("out.ics")
        transfer.export(icsUri, ExportFormat.ICALENDAR)
        val ics = outputText(icsUri)
        assertTrue(ics.contains("BEGIN:VCALENDAR"), ics)
        assertTrue(ics.contains("Export me"), ics)
    }

    @Test fun exportThenReadRoundTripsTitle() = runTest {
        repo.upsert(Todo(syncId = "a", title = "Round trip task"))
        val transfer = TodoDataTransfer(context, repo)
        val uri = outputUri("rt.txt")
        transfer.export(uri, ExportFormat.TODO_TXT)
        val read = transfer.read(uri)
        assertEquals(listOf("Round trip task"), read.map { it.title })
    }
}
