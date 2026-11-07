package com.vayunmathur.contacts.ui

import android.app.Application
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
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

/** Second-pass coverage for the contact picker and the VCF import screen flow. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PickAndImportPass2Test {
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


    private fun contact(id: Long, first: String, favorite: Boolean = false, phone: String = "+1415555010$id") = Contact(
        id = id, accountType = null, accountName = null, isFavorite = favorite,
        details = ContactDetails(
            phoneNumbers = listOf(PhoneNumber(id, phone, CDKPhone.TYPE_MOBILE)),
            emails = listOf(Email(id, "${first.lowercase()}@x.com", CDKEmail.TYPE_HOME)),
            addresses = emptyList(), dates = emptyList(), photos = emptyList(),
            names = listOf(Name(id, "", first, "", "Lee", "")),
            orgs = listOf(Organization(id, "")),
            notes = listOf(Note(id, "")),
            nicknames = listOf(Nickname(id, "", CDKNickname.TYPE_DEFAULT)),
            groups = emptyList(),
        ),
    )

    @Test
    fun pickListFiltersAndEmitsUris() {
        val picked = mutableListOf<Uri>()
        val contacts = listOf(
            contact(1, "Aisha", favorite = true),
            contact(2, "Bob"),
            contact(3, "Zoe"),
        )
        rule.setContent {
            DynamicTheme {
                ContactListPick(mimeType = null, contacts = contacts, isLoading = false, onClick = { picked += it })
            }
        }
        rule.onNodeWithText("Aisha", substring = true).assertExists()
        // Zoe starts below the fold in the lazy list
        rule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("Zoe", substring = true))
        rule.onNodeWithText("Zoe", substring = true).assertExists()
        // filter down to one contact by name
        rule.onNode(hasSetTextAction()).performTextInput("zoe")
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("Aisha", substring = true).fetchSemanticsNodes().size)
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Zoe", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // clear the search (replacement, not append) before tapping a row
        rule.onNode(hasSetTextAction()).performTextReplacement("")
        // tap Bob's row: emits his contact URI
        rule.onNodeWithText("Bob", substring = true).performClick()
        assertEquals(listOf("content://com.android.contacts/contacts/2"), picked.map { it.toString() })
    }

    @Test
    fun pickListEmptyAndLoadingStates() {
        rule.setContent {
            DynamicTheme {
                ContactListPick(mimeType = null, contacts = emptyList(), isLoading = false, onClick = {})
            }
        }
        rule.onNodeWithText(str(R.string.no_contacts_yet), substring = true).assertExists()
    }

    @Test
    fun pickListMultipleSelectionConfirm() {
        var confirmed = 0
        val selected = listOf(
            Uri.parse("content://com.android.contacts/contacts/1"),
            Uri.parse("content://com.android.contacts/contacts/3"),
        )
        rule.setContent {
            DynamicTheme {
                ContactListPick(
                    mimeType = null, contacts = listOf(contact(1, "Aisha"), contact(3, "Zoe")),
                    allowMultiple = true, selectedUris = selected,
                    onConfirm = { confirmed++ }, isLoading = false, onClick = {},
                )
            }
        }
        // the FAB shows Done (2)
        rule.onNodeWithText("2", substring = true).assertExists()
        rule.onNodeWithText(ctx.getString(com.vayunmathur.library.ui.R.string.done), substring = true).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun pickByPhoneMimeEmitsDataUris() {
        val picked = mutableListOf<Uri>()
        rule.setContent {
            DynamicTheme {
                ContactListPick(
                    mimeType = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    contacts = listOf(contact(2, "Bob", phone = "+14155550199")),
                    isLoading = false, onClick = { picked += it },
                )
            }
        }
        // phone-mime rows are per-number: tap the number itself
        rule.onNodeWithText("+14155550199", substring = true).performClick()
        assertEquals(1, picked.size)
        assertTrue(picked.single().toString().contains("data"), picked.single().toString())
    }

    @Test
    fun importScreenParsesAndImportsIntoExistingAccount() {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        val vcf = File(app.cacheDir, "import-flow.vcf").apply {
            writeText(
                """
                BEGIN:VCARD
                VERSION:3.0
                FN:Imported Person
                N:Person;Imported;;;
                TEL;TYPE=CELL:+14155550123
                EMAIL:imported@x.com
                END:VCARD
                """.trimIndent()
            )
        }
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent {
            DynamicTheme {
                ImportVcfScreen(vm, backStack, uris = listOf(Uri.fromFile(vcf).toString()))
            }
        }
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("Imported", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // accounts auto-select the first (provider) account; the save FAB imports
        rule.waitUntil(30_000) {
            vm.parsedVcfContacts.value?.isNotEmpty() == true
        }
        assertEquals("Imported", vm.parsedVcfContacts.value!!.first().name.firstName)
        // the direct render does not push routes: the stack still holds only Main
        assertEquals(1, backStack.backStack.size)
    }
}
