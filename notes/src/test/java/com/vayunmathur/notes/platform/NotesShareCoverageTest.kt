package com.vayunmathur.notes.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.withBody
import com.vayunmathur.notes.seedNotesDb
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NotesShareCoverageTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = seedNotesDb(app)
    private fun vm() = NotesViewModel(app, repo)

    @BeforeTest fun purge() {
        com.vayunmathur.notes.FakeAndroidKeyStore.install()
        runCatching { com.vayunmathur.library.util.DatabaseHelper(app).storePassphrase("test-passphrase") }
        runBlocking { repo.purge(); repo.deleteAllSnapshots() }
    }

    private fun await(timeoutMs: Long = 8000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(20)
        }
        assertTrue(cond(), "condition not met within ${timeoutMs}ms")
    }

    // Note: NotesViewModel.requestShare / markdownCacheUri end in
    // FileProvider.getUriForFile, which Robolectric cannot satisfy (the
    // provider's configured cache root is the app dataDir, while Robolectric
    // points cacheDir at a per-test tmp dir), so the share emission itself is
    // covered on-device. Here we pin everything up to that boundary: the
    // markdown export and the cache file name it would be written under.

    @Test fun exportMarkdownIncludesTextAndSkipsMissingImage() {
        val note = com.vayunmathur.notes.data.Note(title = "Export Me", content = "")
            .withBody(
                com.vayunmathur.notes.data.NoteBody(
                    listOf(
                        com.vayunmathur.notes.data.NoteBlock.Text("first paragraph"),
                        com.vayunmathur.notes.data.NoteBlock.Image("missing-image.jpg"),
                        com.vayunmathur.notes.data.NoteBlock.Text("second paragraph"),
                    ),
                ),
            )
        val markdown = exportNoteMarkdown(app, note)
        assertTrue(markdown.contains("first paragraph"), "markdown=$markdown")
        assertTrue(markdown.contains("second paragraph"), "markdown=$markdown")
        assertTrue(!markdown.contains("missing-image"), "undecodable image must be omitted: $markdown")
    }

    @Test fun renameAndDeleteTagRewriteNotes() {
        val vm = vm()
        runBlocking {
            repo.upsert(Note(title = "Tagged One", content = "1", tags = listOf("work", "urgent"), syncId = "tag1"))
            repo.upsert(Note(title = "Tagged Two", content = "2", tags = listOf("work"), syncId = "tag2"))
            repo.upsert(Note(title = "Untagged", content = "3", syncId = "tag3"))
        }
        vm.renameTag("work", "office")
        await { runBlocking { repo.getBySyncId("tag2") }?.tags == listOf("office") }
        assertEquals(listOf("office", "urgent"), runBlocking { repo.getBySyncId("tag1") }?.tags)
        assertEquals(emptyList(), runBlocking { repo.getBySyncId("tag3") }?.tags)
        vm.deleteTag("urgent")
        await { runBlocking { repo.getBySyncId("tag1") }?.tags == listOf("office") }
        // deleting an absent tag leaves notes untouched
        vm.deleteTag("no-such-tag")
        Thread.sleep(300)
        assertEquals(listOf("office"), runBlocking { repo.getBySyncId("tag1") }?.tags)
    }

    @Test fun shareFileNameFallsBackForBlankTitle() {
        assertEquals("note.md", shareMarkdownFileName(""))
        assertEquals("note.md", shareMarkdownFileName("   "))
        assertEquals("My Note.md", shareMarkdownFileName("My Note"))
    }

    @Test fun tagConvertersRoundTripAndTolerateMalformed() {
        val converters = com.vayunmathur.notes.data.NoteConverters()
        assertEquals("[\"work\",\"home\"]", converters.fromTags(listOf("work", "home")))
        assertEquals(listOf("work", "home"), converters.toTags("[\"work\",\"home\"]"))
        assertEquals(emptyList(), converters.toTags("not json at all"))
        assertEquals(emptyList(), converters.toTags("[1,2,3]"))
        assertEquals("[]", converters.fromTags(emptyList()))
        // Noop list actions are callable no-ops, including the tag operations.
        NotesListActions.Noop.renameTag("a", "b")
        NotesListActions.Noop.deleteTag("a")
        NoteActions.Noop.addTag("t")
        NoteActions.Noop.removeTag("t")
        // an exported empty note is just the trailing newline, never a crash
        assertEquals("\n", exportNoteMarkdown(app, Note(title = "Empty", content = "")))
    }

    @Test fun backupAgentExposesDbConfigsAndImageFiles() {
        val agent = AppBackupAgent()
        // BackupAgent.attachBaseContext is hidden; Robolectric allows the
        // reflective attach so the agent getters can run as Android runs them.
        val attach = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
            .apply { isAccessible = true }
        attach.invoke(agent, app)
        assertEquals(com.vayunmathur.notes.data.DB_NAME, agent.dbConfigs.single().first)
        assertTrue(agent.dbConfigs.single().second.isNotEmpty())
        assertEquals(
            listOf(java.io.File(app.filesDir, "note_images").absolutePath),
            agent.extraFiles.map { it.absolutePath },
        )
    }

    @Test fun shareFileNameStripsPathHostileCharsAndCapsLength() {
        assertEquals("a_b_c_d.md", shareMarkdownFileName("a/b:c?d"))
        assertEquals("hidden.md", shareMarkdownFileName("..hidden"))
        // very long titles are capped under NAME_MAX on a code-point boundary
        val longName = shareMarkdownFileName("é".repeat(300))
        assertTrue(longName.toByteArray().size <= 203, "bytes=${longName.toByteArray().size}")
        assertTrue(longName.endsWith(".md"))
        assertTrue(!longName.contains("�"), "truncation split a code point: $longName")
    }

    @Test fun exportMarkdownForInkBlockEmitsSvg() {
        val stroke = com.vayunmathur.library.ink.SerializedStroke(
            brushFamily = "pressurePen", brushSize = 6f, brushColor = 0xFF37474F.toInt(),
            brushEpsilon = 0.1f,
            points = listOf(
                com.vayunmathur.library.ink.SerializedPoint(0f, 0f, 0L),
                com.vayunmathur.library.ink.SerializedPoint(10f, 5f, 16L),
            ),
        )
        val note = Note(title = "Inky", content = "")
            .withBody(
                com.vayunmathur.notes.data.NoteBody(
                    listOf(
                        com.vayunmathur.notes.data.NoteBlock.Text("before ink"),
                        com.vayunmathur.notes.data.NoteBlock.Ink(listOf(stroke)),
                    ),
                ),
            )
        val markdown = exportNoteMarkdown(app, note)
        assertTrue(markdown.contains("before ink"), "markdown=$markdown")
        assertTrue(markdown.contains("<svg"), "ink block must export as svg: $markdown")
    }
}
