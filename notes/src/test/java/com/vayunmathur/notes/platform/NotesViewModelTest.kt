package com.vayunmathur.notes.platform

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.seedNotesDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NotesViewModelTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = seedNotesDb(app)
    private fun vm() = NotesViewModel(app, repo)

    @BeforeTest fun purge() = runBlocking { repo.purge(); repo.deleteAllSnapshots() }

    private fun await(timeoutMs: Long = 5000, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (runBlocking { cond() }) return
            Thread.sleep(20)
        }
        assertTrue(runBlocking { cond() }, "condition not met within ${timeoutMs}ms")
    }

    @Test fun notesFlowDeleteAndUpsertAll() {
        val vm = vm()
        assertEquals(emptyList(), vm.notes.value)
        val collectJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { vm.notes.collect {} }
        val id1 = runBlocking { repo.upsert(Note(title = "One", content = "1", syncId = "vm1")) }
        runBlocking { repo.upsert(Note(title = "Two", content = "2", syncId = "vm2")) }
        await { vm.notes.value.size == 2 }
        assertEquals(setOf("vm1", "vm2"), vm.notes.value.map { it.syncId }.toSet())
        vm.delete(runBlocking { repo.getBySyncId("vm1")!! })
        await { repo.getAll().map { it.syncId } == listOf("vm2") }
        vm.upsertAll(listOf(Note(title = "Three", content = "3", syncId = "vm3")))
        await { repo.getBySyncId("vm3") != null }
        assertNotNull(id1)
        collectJob.cancel()
    }

    @Test fun parseDisplayCachesAndSearchCount() {
        val vm = vm()
        val a = vm.parseDisplay("# Hello world hello")
        val b = vm.parseDisplay("# Hello world hello")
        assertEquals(a.text, b.text)
        assertTrue(a === b, "cache should return same instance")
        val c = vm.parseDisplay("# Hello world hello", searchQuery = "hello", searchIndex = 0)
        assertTrue(c.text.contains("Hello"))
        assertEquals(3, vm.searchResultsCount("hello Hello HELLO", "hello"))
        assertEquals(0, vm.searchResultsCount("hello", ""))
        assertEquals(0, vm.searchResultsCount("hello", "absent"))
        // fill cache beyond 32 entries; oldest evicted but results stay correct
        repeat(40) { vm.parseDisplay("content-$it filler") }
        assertEquals("content-39 filler", vm.parseDisplay("content-39 filler").text)
    }

    @Test fun externalOpenAndConsume() {
        val vm = vm()
        assertEquals(emptyList(), vm.externalOpens.value)
        vm.openExternal(emptyList())
        assertEquals(emptyList(), vm.externalOpens.value)
        vm.openExternal(listOf(Uri.parse("content://x/a.md"), Uri.parse("content://x/b.md")))
        vm.openExternal(listOf(Uri.parse("content://x/a.md")))
        assertEquals(3, vm.externalOpens.value.size)
        vm.consumeExternal("content://x/a.md")
        assertEquals(listOf("content://x/b.md"), vm.externalOpens.value)
        vm.consumeExternal("content://missing")
        assertEquals(listOf("content://x/b.md"), vm.externalOpens.value)
    }

    @Test fun readExternalMissingAndInvalidReturnsNull() {
        val vm = vm()
        assertNull(runBlocking { vm.readExternal("content://nonexistent/none.md") })
        assertNull(runBlocking { vm.readExternal("::not a uri::") })
    }

    @Test fun readExternalFromFileAndSaveRoundTrip() {
        val vm = vm()
        val dir = File(app.cacheDir, "ext-test").apply { mkdirs() }
        val file = File(dir, "meeting.md").apply { writeText("# Agenda\nitems") }
        val uri = Uri.fromFile(file).toString()
        val read = runBlocking { vm.readExternal(uri) }
        assertNotNull(read)
        assertEquals("meeting", read.title)
        assertEquals("# Agenda\nitems", read.content)
        var saved: Boolean? = null
        vm.saveExternal(uri, "updated body") { saved = it }
        await { saved != null }
        assertEquals(true, saved)
        assertEquals("updated body", file.readText())
        // .markdown suffix stripping
        val file2 = File(dir, "doc.markdown").apply { writeText("x") }
        assertEquals("doc", runBlocking { vm.readExternal(Uri.fromFile(file2).toString()) }?.title)
    }

    @Test fun saveExternalToBadUriReportsFalse() {
        val vm = vm()
        var result: Boolean? = null
        vm.saveExternal("content://nonexistent/x.md", "data") { result = it }
        await { result != null }
        assertEquals(false, result)
    }

    @Test fun addExternalToAppInsertsNote() {
        val vm = vm()
        var addedId = -1L
        vm.addExternalToApp("Imported Title", "imported body") { addedId = it }
        await { addedId > 0 }
        val stored = runBlocking { repo.notes.first().singleOrNull { it.title == "Imported Title" } }
        assertNotNull(stored)
        assertEquals("imported body", stored.content)
        assertEquals(addedId, stored.id)
    }

    @Test fun importFilesFromFileUrisAndEmptyList() {
        val vm = vm()
        vm.importFiles(emptyList()) // no-op
        val dir = File(app.cacheDir, "import-test").apply { mkdirs() }
        val f1 = File(dir, "alpha.txt").apply { writeText("alpha body") }
        val f2 = File(dir, "beta.md").apply { writeText("beta body") }
        vm.importFiles(listOf(Uri.fromFile(f1), Uri.fromFile(f2), Uri.parse("content://missing/x")))
        await { repo.getAll().size >= 2 }
        val titles = runBlocking { repo.getAll().map { it.title } }
        assertTrue(titles.any { it.contains("alpha") }, "titles=$titles")
        assertTrue(runBlocking { repo.getAll().any { it.content == "beta body" } })
    }


    @Test fun factoryCreatesAndRejects() {
        val factory = NotesViewModelFactory(app, repo)
        assertTrue(factory.create(NotesViewModel::class.java) is NotesViewModel)
        assertFailsWith<IllegalArgumentException> { factory.create(OtherVm::class.java) }
    }

    @Test fun dataClassesHoldValues() {
        val share = NotesViewModel.NoteShare(Uri.parse("content://x"), "md")
        assertEquals("md", share.markdown)
        val ext = NotesViewModel.ExternalNoteContent("t", "c")
        assertEquals("t" to "c", ext.title to ext.content)
        assertEquals(NoteUiState(), NoteUiState(title = "", blocks = emptyList()))
        assertEquals(NotesListUiState(), NotesListUiState(notes = emptyList(), showAddButton = true))
        // Noop actions are callable no-ops
        NotesListActions.Noop.openNote(1); NotesListActions.Noop.createNote()
        NotesListActions.Noop.delete(Note(title = "x", content = "")); NotesListActions.Noop.upsertAll(emptyList())
        NoteActions.Noop.back(); NoteActions.Noop.setTitle("t"); NoteActions.Noop.setBlockMarkdown("i", "m")
        NoteActions.Noop.addImage(null); NoteActions.Noop.addDrawing("b"); NoteActions.Noop.resizeImage("i", 0.5f)
        NoteActions.Noop.moveBlock("i", 1); NoteActions.Noop.deleteBlock("i"); NoteActions.Noop.copyNote()
        NoteActions.Noop.shareNote(); NoteActions.Noop.deleteNote()
        NoteActions.Noop.editDrawing(com.vayunmathur.notes.data.NoteBlock.Ink())
    }
}

private class OtherVm : androidx.lifecycle.ViewModel()
