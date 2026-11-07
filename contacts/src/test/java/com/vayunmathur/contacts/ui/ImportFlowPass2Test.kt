package com.vayunmathur.contacts.ui

import android.app.Application
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Second-pass import flow: mode selection, account creation, and the FAB import. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImportFlowPass2Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = app as android.content.Context
    private fun str(id: Int) = ctx.getString(id)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
    }


    private fun vcfUri(): Uri {
        val file = File(app.cacheDir, "import-flow2.vcf")
        file.writeText(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Imported Person
            N:Person;Imported;;;
            TEL;TYPE=CELL:+14155550123
            EMAIL:imported@x.com
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            FN:Second Person
            N:Person;Second;;;
            TEL;TYPE=HOME:+14155550124
            END:VCARD
            """.trimIndent()
        )
        return Uri.fromFile(file)
    }

    private fun renderImport(vm: ContactViewModel, backStack: NavBackStack<Route>) {
        rule.setContent {
            DynamicTheme { ImportVcfScreen(vm, backStack, uris = listOf(vcfUri().toString())) }
        }
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("Imported", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun importIntoExistingAccountPopsBack() {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        val backStack = NavBackStack<Route>(arrayOf(Route.Main, Route.ImportVcf(listOf("x"))))
        renderImport(vm, backStack)
        // summary shows both parsed contacts
        assertTrue(
            rule.onAllNodesWithText("2", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
        // FAB (save icon, the only FAB) imports into the auto-selected account
        val clickables = rule.onAllNodes(hasClickAction())
        // FAB is typically the last clickable; click it and expect a pop
        clickables[clickables.fetchSemanticsNodes().lastIndex].performClick()
        rule.waitUntil(30_000) { backStack.backStack.size == 1 }
        assertEquals(Route.Main, backStack.backStack.last())
    }

    @Test
    fun importIntoNewAccountCreatesAndImports() {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        val backStack = NavBackStack<Route>(arrayOf(Route.Main, Route.ImportVcf(listOf("x"))))
        renderImport(vm, backStack)

        rule.onNodeWithText(str(R.string.create_new_account_option), substring = true).performClick()
        rule.waitForIdle()
        // name the new account
        rule.onNode(hasSetTextAction()).performTextInput("Imported Account")
        rule.waitForIdle()

        val clickables = rule.onAllNodes(hasClickAction())
        clickables[clickables.fetchSemanticsNodes().lastIndex].performClick()
        // create + import completes and pops back to Main
        rule.waitUntil(60_000) { backStack.backStack.size == 1 }
        assertEquals(Route.Main, backStack.backStack.last())
    }

    @Test
    fun importEmptyParseShowsEmptyState() {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        val empty = File(app.cacheDir, "empty.vcf").apply { writeText("") }
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent {
            DynamicTheme {
                ImportVcfScreen(vm, backStack, uris = listOf(Uri.fromFile(empty).toString()))
            }
        }
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText(str(R.string.no_contacts_found), substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(emptyList(), vm.parsedVcfContacts.value)
    }
}
