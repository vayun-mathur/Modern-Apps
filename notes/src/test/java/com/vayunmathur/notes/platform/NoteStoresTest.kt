package com.vayunmathur.notes.platform

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ink.SerializedPoint
import com.vayunmathur.library.ink.SerializedStroke
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteBlock
import com.vayunmathur.notes.data.NoteBody
import com.vayunmathur.notes.data.withBody
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NoteStoresTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val imagesDir: File get() = File(context.filesDir, "note_images")

    @BeforeTest fun clean() { imagesDir.deleteRecursively() }

    private fun writeImageFile(name: String, w: Int = 40, h: Int = 20): File {
        imagesDir.mkdirs()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF336699.toInt())
        val f = File(imagesDir, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return f
    }

    @Test fun fileForAndDelete() {
        val f = NoteImageStore.fileFor(context, "abc.jpg")
        assertEquals("abc.jpg", f.name)
        assertEquals(imagesDir.absolutePath, f.parentFile!!.absolutePath)
        assertTrue(imagesDir.exists())
        writeImageFile("todelete.jpg")
        assertTrue(File(imagesDir, "todelete.jpg").exists())
        NoteImageStore.delete(context, "todelete.jpg")
        assertFalse(File(imagesDir, "todelete.jpg").exists())
        NoteImageStore.delete(context, "missing.jpg") // no throw
    }

    @Test fun importFromFileUriContentAddressesAndDedupes() {
        val src = File(context.cacheDir, "src.jpg")
        val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFFAA0000.toInt())
        src.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        val name = NoteImageStore.import(context, Uri.fromFile(src))
        assertTrue(name != null && name.endsWith(".jpg") && name.length == 68, "name=$name") // 64 hex + .jpg
        assertTrue(NoteImageStore.fileFor(context, name!!).exists())
        val name2 = NoteImageStore.import(context, Uri.fromFile(src))
        assertEquals(name, name2)
        // import of missing uri returns null
        assertEquals(null, NoteImageStore.import(context, Uri.fromFile(File(context.cacheDir, "nope.jpg"))))
        assertEquals(null, NoteImageStore.import(context, Uri.parse("content://missing/img")))
    }

    @Test fun migrateToContentAddressedAndPrune() {
        val legacy = writeImageFile("img_1234abcd.jpg")
        writeImageFile("already.jpg") // not legacy prefix: left alone (name starts check)
        // a file whose hashed name would equal itself is impossible; also add non-image file
        File(imagesDir, "img_notes.txt").writeText("hello")
        val renames = NoteImageStore.migrateToContentAddressed(context)
        assertTrue(renames.containsKey("img_1234abcd.jpg"), "renames=$renames")
        val hashed = renames.getValue("img_1234abcd.jpg")
        assertTrue(hashed.endsWith(".jpg") && hashed.length == 68)
        assertTrue(File(imagesDir, hashed).exists())
        assertTrue(legacy.exists(), "migration copies, never moves")
        // txt legacy file also hashed to .jpg content name
        assertTrue(renames.containsKey("img_notes.txt"))
        NoteImageStore.pruneMigratedSources(context, renames)
        assertFalse(legacy.exists())
        assertTrue(File(imagesDir, hashed).exists())
        NoteImageStore.pruneMigratedSources(context, emptyMap()) // no-op
        // already content-addressed files (no img_ prefix) not renamed
        assertFalse(renames.containsKey("already.jpg"))
    }

    @Test fun exportTextInkAndMissingImage() {
        val stroke = SerializedStroke("pen", 4f, 0xFF112233.toInt(), 0.1f, listOf(SerializedPoint(0f, 0f, 0), SerializedPoint(10f, 20.5f, 1), SerializedPoint(20f, 5f, 2)))
        val emptyStroke = SerializedStroke("pen", 2f, 0, 0.1f, emptyList())
        val note = Note(title = "T", content = "").withBody(NoteBody(listOf(
            NoteBlock.Text("# Head", "t1"),
            NoteBlock.Text("   ", "blank"),
            NoteBlock.Image("missing-file.jpg", 0.5f, "i1"),
            NoteBlock.Ink(listOf(stroke, emptyStroke), heightDp = 120, id = "k1"),
            NoteBlock.Text("tail", "t2"),
        )))
        val md = exportNoteMarkdown(context, note)
        assertTrue(md.startsWith("# Head"))
        assertTrue(md.contains("<svg"))
        assertTrue(md.contains("M 0 0 L 10 20.5 L 20 5"))
        assertTrue(md.contains("rgba(17,34,51,1)"))
        assertTrue(md.endsWith("tail\n"))
        assertFalse(md.contains("missing-file"))
        // empty note exports just newline
        assertEquals("\n", exportNoteMarkdown(context, Note(title = "", content = "")))
        // ink with no strokes: svg sized by heightDp
        val inkOnly = Note(title = "", content = "").withBody(NoteBody(listOf(NoteBlock.Ink(emptyList(), heightDp = 222, id = "k"))))
        val svg = exportNoteMarkdown(context, inkOnly)
        assertTrue(svg.contains("height=\"222\""), svg)
    }

    @Test fun exportInlinesRealImageWithWidthPercent() {
        writeImageFile("real.jpg", 60, 30)
        val note = Note(title = "Img", content = "").withBody(NoteBody(listOf(
            NoteBlock.Image("real.jpg", 0.5f, "i1"),
            NoteBlock.Image("real.jpg", 5f, "i2"), // clamped to 100%
            NoteBlock.Image("real.jpg", 0.001f, "i3"), // clamped to 1%
        )))
        val md = exportNoteMarkdown(context, note)
        assertTrue(md.contains("data:image/jpeg;base64,"), md.take(200))
        assertTrue(md.contains("width:50%"))
        assertTrue(md.contains("width:100%"))
        assertTrue(md.contains("width:1%"))
        // Note: Robolectric's BitmapFactory decodes any path/bytes, so the
        // undecodable/missing-image omission branch cannot be simulated here.
    }

    @Test fun exportDownscalesLargeImage() {
        writeImageFile("big.jpg", 2100, 100)
        val note = Note(title = "", content = "").withBody(NoteBody(listOf(NoteBlock.Image("big.jpg", id = "big"))))
        val md = exportNoteMarkdown(context, note)
        assertTrue(md.contains("data:image/jpeg;base64,"))
    }

    @Test fun markdownCacheUriSanitizesPathHostileTitles() {
        val uri = markdownCacheUri(context, Note(title = "a/b ../c", content = ""), "body")
        assertTrue(uri.toString().isNotEmpty())
        val dir = File(context.cacheDir, "shared_notes")
        val written = File(dir, shareMarkdownFileName("a/b ../c"))
        assertTrue(written.exists())
        assertEquals(dir.absolutePath, written.parentFile!!.absolutePath)
        assertEquals("note.md", shareMarkdownFileName(""))
        assertEquals("note.md", shareMarkdownFileName("..."))
        assertEquals("My Note.md", shareMarkdownFileName("My Note"))
    }

    @Test fun markdownCacheUriWritesFile() {
        val note = Note(title = "My Note", content = "x")
        val uri = markdownCacheUri(context, note, "# hello")
        assertTrue(uri.toString().isNotEmpty())
        val f = File(context.cacheDir, "shared_notes/My Note.md")
        assertTrue(f.exists())
        assertEquals("# hello", f.readText())
        val blank = markdownCacheUri(context, Note(title = "", content = ""), "body")
        assertTrue(File(context.cacheDir, "shared_notes/note.md").exists())
        assertTrue(blank.toString().isNotEmpty())
    }

    @Test fun exportInkAlphaAndFractionalValues() {
        val stroke = SerializedStroke("pen", 2.567f, 0x80102030.toInt(), 0.1f, listOf(SerializedPoint(1.234f, 2f, 0), SerializedPoint(3f, 4.891f, 1)))
        val note = Note(title = "", content = "").withBody(NoteBody(listOf(NoteBlock.Ink(listOf(stroke), heightDp = 10, id = "k"))))
        val md = exportNoteMarkdown(context, note)
        assertTrue(md.contains("rgba(16,32,48,0.5)"), md)
        assertTrue(md.contains("stroke-width=\"2.57\""), md)
        assertTrue(md.contains("M 1.23 2"), md)
    }

    @Test fun backupFilesCoversImagesDir() {
        writeImageFile("backed-up.jpg")
        val files = NoteImageStore.backupFiles(context)
        assertEquals(listOf(imagesDir.absolutePath), files.map { it.absolutePath })
        assertTrue(File(files.single(), "backed-up.jpg").exists())
    }

    @Test fun pruneSkipsNonLegacyKeysAndKeepsFiles() {
        writeImageFile("keepme.jpg")
        NoteImageStore.pruneMigratedSources(context, mapOf("keepme.jpg" to "hashed.jpg"))
        assertTrue(File(imagesDir, "keepme.jpg").exists())
    }
}
