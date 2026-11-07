package com.vayunmathur.contacts.util

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.ui.ImportVcfScreen
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Second-pass VCF import coverage: real file parsing through the ViewModel + the import screen. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VcfImportPass2Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private val vcfText = """
        BEGIN:VCARD
        VERSION:3.0
        FN:Aisha Rahman
        N:Rahman;Aisha;;;
        TEL;TYPE=CELL:+1 415 555 0102
        EMAIL:aisha@example.com
        ORG:Acme
        END:VCARD
        BEGIN:VCARD
        VERSION:3.0
        FN:Bob Lee
        N:Lee;Bob;;;
        TEL;TYPE=HOME:+1 415 555 0103
        END:VCARD
    """.trimIndent()

    private fun vcfFile(): Uri {
        val file = File(app.cacheDir, "import-test.vcf")
        file.writeText(vcfText)
        return Uri.fromFile(file)
    }

    private fun parsedContacts(vm: ContactViewModel): List<Contact> = runBlocking {
        withTimeout(30_000) { vm.parsedVcfContacts.first { it != null } }!!
    }

    @Test
    fun parseVcfUrisReadsContactsFromFile() {
        val vm = ContactViewModel(app)
        vm.parseVcfUris(listOf(vcfFile()))
        val contacts = parsedContacts(vm)
        assertEquals(2, contacts.size)
        val aisha = contacts.first { it.name.firstName == "Aisha" }
        assertEquals("Rahman", aisha.name.lastName)
        assertEquals(listOf("+1 415 555 0102"), aisha.details.phoneNumbers.map { it.number })
        assertEquals(listOf("aisha@example.com"), aisha.details.emails.map { it.address })
        assertEquals("Acme", aisha.org.company)

        vm.clearParsedVcf()
        assertNull(vm.parsedVcfContacts.value)
    }

    @Test
    fun parseEmptyAndMissingUris() {
        val vm = ContactViewModel(app)
        vm.parseVcfUris(emptyList())
        assertEquals(emptyList(), vm.parsedVcfContacts.value)

        vm.parseVcfUris(listOf(Uri.fromFile(File(app.cacheDir, "missing.vcf"))))
        val contacts = parsedContacts(vm)
        assertTrue(contacts.isEmpty(), "missing file should parse to no contacts, got $contacts")
    }

    @Test
    fun importVcfScreenShowsParsedContacts() {
        val vm = ContactViewModel(app)
        vm.parseVcfUris(listOf(vcfFile()))
        val contacts = parsedContacts(vm)
        assertEquals(2, contacts.size)

        rule.setContent {
            DynamicTheme {
                ImportVcfScreen(
                    vm,
                    NavBackStack<Route>(arrayOf(Route.Main)),
                    uris = listOf(vcfFile().toString()),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Aisha", substring = true).assertExists()
        rule.onRoot().assertExists()
        assertNotNull(vm.parsedVcfContacts.value)
    }

    @Test
    fun importVcfContactsToProviderAccountCompletes() {
        // Main must be Unconfined before the VM is created: viewModelScope binds
        // the Main dispatcher at first use, and the compose rule's paused test
        // dispatcher would otherwise starve the import's onDone delivery.
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            importVcfContactsToProviderAccountCompletesBody()
        } finally {
            // leave Main as Unconfined: resetting it while VM coroutines from
            // other classes are still dispatching trips TestMainDispatcher's
            // concurrent-modification guard in a shared test JVM
        }
    }

    private fun importVcfContactsToProviderAccountCompletesBody() {
        val vm = ContactViewModel(app)
        vm.parseVcfUris(listOf(vcfFile()))
        val contacts = parsedContacts(vm)
        val doneSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
        vm.importVcfContacts(contacts, "On device", "com.vayunmathur.contacts.local") {
            doneSignal.complete(Unit)
        }
        runBlocking { withTimeout(30_000) { doneSignal.await() } }
    }
}
