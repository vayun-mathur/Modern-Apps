package com.vayunmathur.office.util

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Slice 3/4, round 4b: Robolectric save/load round-trips on [OfficeViewModel].
 *
 * The plain-JVM Mockito harness can't serve `Uri.fromFile`/content-resolver calls,
 * so these use the real Robolectric Application: `persistToAppStorage` copies a
 * foreign file into `filesDir/documents`, and a real save to a tmp file followed
 * by `DocumentImporter.open` proves the packaged writer output parses.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfficeViewModelSaveSlice3Test : OfficeViewModelSlice3Harness() {

    private val roboApp: android.app.Application
        get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun roboVm(): OfficeViewModel {
        // Real Robolectric Application: contentResolver serves file:// uris, so
        // persistToAppStorage + save/load round-trips run for real.
        Dispatchers.setMain(dispatcher)
        return OfficeViewModel(roboApp)
    }

    private fun textDoc(vararg texts: String) = OdfDocument.TextDocument(
        title = "Doc",
        content = texts.map { OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(it)))) },
    )

    private fun roboLoaded(vararg texts: String): OfficeViewModel {
        val vm = roboVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(textDoc(*texts))
        return vm
    }

    private fun displayedText(vm: OfficeViewModel): String {
        val doc = (vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument
        return doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .joinToString("\n") { it.paragraph.spans.joinToString("") { s -> s.text } }
    }

    private fun paragraphsOf(doc: OdfDocument.TextDocument): String =
        doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .joinToString("\n") { it.paragraph.spans.joinToString("") { s -> s.text } }

    @Test
    fun `persistToAppStorage copies foreign files into docs dir`() = runTest {
        val vm = roboVm()
        val outside = java.io.File(tmpDir, "foreign.odt")
        outside.writeText("hello-foreign")
        val uri = android.net.Uri.fromFile(outside)
        val copied = vm.persistToAppStorage(uri, "foreign.odt")
        // Lands inside the app-private documents dir, with content preserved.
        assertTrue(copied.path!!.contains("documents"))
        val dest = java.io.File(copied.path!!)
        assertTrue(dest.exists())
        assertEquals("hello-foreign", dest.readText())
        // A second call on the copied uri short-circuits (already inside).
        assertEquals(copied, vm.persistToAppStorage(copied, "foreign.odt"))
    }

    @Test
    fun `save writes a package that re-imports`() = runTest {
        val vm = roboLoaded("saved line one", "saved line two")
        val target = java.io.File(tmpDir, "roundtrip.odt")
        val targetUri = android.net.Uri.fromFile(target)
        vm.documentUri = null
        vm.originalUri = targetUri
        vm.save()
        dispatcher.scheduler.runCurrent()
        // Let the IO-dispatched save coroutine finish (Robolectric runs it for real).
        var spins = 0
        while (!target.exists() && spins < 200) {
            Thread.sleep(25)
            spins++
        }
        assertTrue(target.exists(), "save should write ${target.absolutePath}")
        val reopened = com.vayunmathur.office.odf.DocumentImporter.open(
            roboApp, targetUri, "roundtrip.odt")
        val text = paragraphsOf(reopened as OdfDocument.TextDocument)
        assertTrue(text.contains("saved line one"), "round-trip text was: $text")
        assertTrue(text.contains("saved line two"))
        assertEquals("saved line one\nsaved line two", displayedText(vm))
    }

    @Test
    fun `save with source package preserves and rewrites`() = runTest {
        // First save a doc from scratch, then load it via DocumentImporter and save
        // again with the file as the source package (exercises copySourceEntries).
        val first = roboLoaded("v1 text")
        val target = java.io.File(tmpDir, "v1.odt")
        val targetUri = android.net.Uri.fromFile(target)
        first.documentUri = null
        first.originalUri = targetUri
        first.save()
        var spins = 0
        while (!target.exists() && spins < 200) {
            Thread.sleep(25)
            spins++
        }
        assertTrue(target.exists())
        val imported =
            com.vayunmathur.office.odf.DocumentImporter.open(roboApp, targetUri, "v1.odt")
        val vm2 = roboVm()
        vm2.stateMutable.value = OfficeViewModel.ViewState.Loaded(imported)
        vm2.documentUri = targetUri
        vm2.originalUri = targetUri
        vm2.updateParagraphText(0, "v2 text")
        vm2.save()
        spins = 0
        while (spins < 40) {
            Thread.sleep(25)
            spins++
        }
        val reopened =
            com.vayunmathur.office.odf.DocumentImporter.open(roboApp, targetUri, "v1.odt")
        val text = paragraphsOf(reopened as OdfDocument.TextDocument)
        assertTrue(text.contains("v2 text"), "rewritten text was: $text")
    }

    @Test
    fun `save to a docx target writes OOXML not an ODF package`() = runTest {
        val vm = roboLoaded("docx line one")
        val target = java.io.File(tmpDir, "roundtrip.docx")
        val targetUri = android.net.Uri.fromFile(target)
        vm.documentUri = null
        vm.originalUri = targetUri
        vm.save()
        var spins = 0
        while (!target.exists() && spins < 200) {
            Thread.sleep(25)
            spins++
        }
        assertTrue(target.exists(), "save should write ${target.absolutePath}")
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(target.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                names.add(e.name)
                e = zip.nextEntry
            }
        }
        assertTrue("word/document.xml" in names, "OOXML parts missing: $names")
        assertTrue("content.xml" !in names, "ODF part leaked into docx: $names")
        val reopened = com.vayunmathur.office.odf.DocumentImporter.open(
            roboApp, targetUri, "roundtrip.docx")
        assertTrue(
            paragraphsOf(reopened as OdfDocument.TextDocument).contains("docx line one"))
    }

    @Test
    fun `loadDocument imports a saved package`() = runTest {
        // Seed a real .odt via the writer, then loadDocument should import it
        // (exercises the IO coroutine: persistToAppStorage + DocumentImporter.open).
        val seed = roboLoaded("loaded line")
        val target = java.io.File(tmpDir, "seed.odt")
        val targetUri = android.net.Uri.fromFile(target)
        seed.documentUri = null
        seed.originalUri = targetUri
        seed.save()
        var spins = 0
        while (!target.exists() && spins < 200) {
            Thread.sleep(25)
            spins++
        }
        assertTrue(target.exists())
        val vm = roboVm()
        vm.loadDocument(targetUri, "seed.odt")
        spins = 0
        while (vm.state.value !is OfficeViewModel.ViewState.Loaded && spins < 200) {
            Thread.sleep(25)
            spins++
        }
        val state = vm.state.value
        assertTrue(state is OfficeViewModel.ViewState.Loaded, "state was $state")
        val text = paragraphsOf(state.document as OdfDocument.TextDocument)
        assertTrue(text.contains("loaded line"), "loaded text was: $text")
        assertTrue(vm.isEditMode.value)
    }

    @Test
    fun `loadDocument surfaces errors for missing files`() = runTest {
        val vm = roboVm()
        val missing = java.io.File(tmpDir, "does-not-exist.odt")
        vm.loadDocument(android.net.Uri.fromFile(missing), "does-not-exist.odt")
        var spins = 0
        while (vm.state.value is OfficeViewModel.ViewState.Loading && spins < 200) {
            Thread.sleep(25)
            spins++
        }
        assertTrue(
            vm.state.value is OfficeViewModel.ViewState.Error,
            "state was ${vm.state.value}")
    }
}
