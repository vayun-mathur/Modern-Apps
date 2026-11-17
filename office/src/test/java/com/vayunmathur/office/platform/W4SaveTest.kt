package com.vayunmathur.office.platform

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.office.data.odf.Slice4ImportExportTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * W4: [OfficeViewModel.save] — ODF and OOXML targets written for real
 * through the test content provider, the no-target no-op, and the
 * unwritable-target failure branch.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class W4SaveTest {

    private val roboApp: android.app.Application
        get() = ApplicationProvider.getApplicationContext()

    init {
        Robolectric.setupContentProvider(
            Slice4ImportExportTest.Slice4Provider::class.java,
            "com.vayunmathur.office.w4save")
    }

    private var outTarget: File?
        get() = Slice4ImportExportTest.Slice4Provider.outTarget
        set(v) { Slice4ImportExportTest.Slice4Provider.outTarget = v }

    private fun contentUri(name: String): Uri = Uri.parse("content://com.vayunmathur.office.w4save/$name")

    @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        outTarget = null
    }

    private fun loadedVm(): OfficeViewModel {
        val v = OfficeViewModel(roboApp)
        v.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.TextDocument(
                title = "Savable",
                content = listOf(
                    OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("save me")))))))
        v.hasUnsavedChangesMutable.value = true
        return v
    }

    private fun awaitIdle(v: OfficeViewModel) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (!v.isSaving.value) return
            Thread.sleep(25)
        }
        assertFalse(v.isSaving.value, "save should finish")
    }

    private fun zipEntryNames(file: File): Set<String> {
        val names = mutableSetOf<String>()
        java.util.zip.ZipFile(file).use { z ->
            val e = z.entries()
            while (e.hasMoreElements()) names.add(e.nextElement().name)
        }
        return names
    }

    @Test
    fun `save writes an odt package and clears the dirty flag`() {
        val out = File(roboApp.cacheDir, "w4-save.odt")
        out.delete()
        outTarget = out
        val v = loadedVm()
        try {
            v.save(contentUri("w4-save.odt"))
            awaitIdle(v)
            assertTrue(out.exists() && out.length() > 100, "saved file missing")
            assertTrue(zipEntryNames(out).contains("content.xml"))
            assertFalse(v.hasUnsavedChanges.value)
            assertEquals(contentUri("w4-save.odt"), v.originalUri)
        } finally {
            out.delete()
        }
    }

    @Test
    fun `save to a docx target uses the ooxml exporter`() {
        val out = File(roboApp.cacheDir, "w4-save.docx")
        out.delete()
        outTarget = out
        val v = loadedVm()
        try {
            v.save(contentUri("w4-save.docx"))
            awaitIdle(v)
            assertTrue(out.exists() && out.length() > 100, "saved file missing")
            assertTrue(zipEntryNames(out).any { it.contains("document.xml") }, zipEntryNames(out).toString())
            assertFalse(v.hasUnsavedChanges.value)
        } finally {
            out.delete()
        }
    }

    @Test
    fun `save without any target is a no-op`() {
        val v = loadedVm()
        v.originalUri = null
        v.save()
        assertFalse(v.isSaving.value)
        assertTrue(v.hasUnsavedChanges.value, "dirty flag must survive a no-op save")
    }

    @Test
    fun `save to an unwritable target reports failure and clears saving`() {
        val v = loadedVm()
        // No provider registered for this authority: the stream open fails.
        v.save(Uri.parse("content://com.vayunmathur.office.nowhere/x.odt"))
        awaitIdle(v)
        assertTrue(v.hasUnsavedChanges.value, "failed save must stay dirty")
    }
}
