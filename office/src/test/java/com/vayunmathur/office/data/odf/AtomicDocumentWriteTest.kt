package com.vayunmathur.office.data.odf

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Atomic document replace: a save must never truncate the only copy.
 * Bytes go to `<name>.tmp` in the same directory, are fsynced, and are
 * renamed over the original; any failure leaves the original intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AtomicDocumentWriteTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `write replaces an existing file and leaves no tmp behind`() {
        val target = File(context.cacheDir, "atomic-replace.odt")
        target.writeText("original-bytes")
        try {
            writeDocumentBytesAtomically(context, Uri.fromFile(target), "replacement".toByteArray())
            assertEquals("replacement", target.readText())
            assertFalse(File(context.cacheDir, "atomic-replace.odt.tmp").exists())
        } finally {
            target.delete()
        }
    }

    @Test
    fun `write creates a new file when none existed`() {
        val target = File(context.cacheDir, "atomic-new.odt")
        target.delete()
        try {
            writeDocumentBytesAtomically(context, Uri.fromFile(target), "fresh".toByteArray())
            assertEquals("fresh", target.readText())
            assertFalse(File(context.cacheDir, "atomic-new.odt.tmp").exists())
        } finally {
            target.delete()
        }
    }

    @Test
    fun `failed rename surfaces an error and keeps the original intact`() {
        // A non-empty directory at the target path cannot be replaced by
        // renaming a file over it, which exercises the rename-failure path.
        val target = File(context.cacheDir, "atomic-dir.odt")
        target.mkdirs()
        File(target, "keep.txt").writeText("keep me")
        try {
            assertFailsWith<IOException> {
                writeDocumentBytesAtomically(context, Uri.fromFile(target), "replacement".toByteArray())
            }
            assertTrue(target.isDirectory)
            assertEquals("keep me", File(target, "keep.txt").readText())
            assertFalse(File(context.cacheDir, "atomic-dir.odt.tmp").exists())
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun `odf writer save to a file target replaces the previous package`() {
        val target = File(context.cacheDir, "atomic-writer.odt")
        target.writeText("not a zip - the only copy before save")
        val doc = OdfDocument.TextDocument(
            "Saved",
            listOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(listOf(OdfSpan("round trip"))),
                ),
            ),
        )
        try {
            OdfWriter.save(context, null, doc, Uri.fromFile(target))
            assertTrue(target.length() > 100, "save wrote ${target.length()} bytes")
            java.util.zip.ZipFile(target).use { z ->
                assertTrue(z.getEntry("content.xml") != null)
            }
            assertFalse(File(context.cacheDir, "atomic-writer.odt.tmp").exists())
        } finally {
            target.delete()
        }
    }
}
