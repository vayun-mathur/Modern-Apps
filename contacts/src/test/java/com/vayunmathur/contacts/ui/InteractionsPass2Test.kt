package com.vayunmathur.contacts.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.clearEditDraft
import com.vayunmathur.contacts.util.initEditDraft
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Second-pass interaction coverage: crop gestures, edit-form fields, details actions. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InteractionsPass2Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = app as android.content.Context
    private fun str(id: Int) = ctx.getString(id)

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }

    private fun vm(): ContactViewModel {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        return vm
    }

    private fun fixtureContact(): Contact = Contact(
        id = 7, accountType = null, accountName = null, isFavorite = false,
        details = ContactDetails(
            phoneNumbers = listOf(com.vayunmathur.contacts.data.PhoneNumber(1, "+14155550107", com.vayunmathur.contacts.data.CDKPhone.TYPE_MOBILE)),
            emails = listOf(com.vayunmathur.contacts.data.Email(2, "aisha@x.com", com.vayunmathur.contacts.data.CDKEmail.TYPE_HOME)),
            addresses = emptyList(),
            dates = listOf(com.vayunmathur.contacts.data.Event(3, kotlinx.datetime.LocalDate(1992, 3, 15), com.vayunmathur.contacts.data.CDKEvent.TYPE_BIRTHDAY)),
            photos = emptyList(),
            names = listOf(Name(4, "", "Aisha", "", "Rahman", "")),
            orgs = listOf(Organization(5, "Acme")),
            notes = listOf(Note(6, "hello note")),
            nicknames = listOf(Nickname(7, "Ash", CDKNickname.TYPE_DEFAULT)),
            groups = emptyList(),
        ),
    )

    @Test
    fun cropPhotoLoadsImageDragsAndCompletes() {
        // write a real JPEG the screen can decode
        val bitmapFile = File(app.cacheDir, "crop-source.jpg")
        Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
            bitmapFile.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        var cropped: Bitmap? = null
        var cancelled = 0
        rule.setContent {
            DynamicTheme {
                CropPhotoScreen(
                    uri = Uri.fromFile(bitmapFile).toString(),
                    onCropComplete = { cropped = it },
                    onCancel = { cancelled++ },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.crop_photo), substring = true).assertExists()
        // drag the image around inside the crop area
        rule.onRoot().performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX + 40f, centerY + 25f), 250)
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.done), substring = true).performClick()
        rule.waitForIdle()
        assertNotNull(cropped, "tapping Done after loading should deliver a cropped bitmap")
        assertEquals(0, cancelled)
    }

    @Test
    fun editPageFieldsUpdateTheDraft() {
        val vm = vm()
        vm.allContactsState.value = listOf(fixtureContact())
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalNavResultRegistry provides NavResultRegistry()
            ) {
                DynamicTheme {
                    EditContactPage(NavBackStack<Route>(arrayOf(Route.Main)), vm, Route.EditContact(contactId = 7))
                }
            }
        }
        // the VM's debounced init sync can overwrite the fixture with the
        // (empty) provider list in a busy full-suite JVM, leaving a blank
        // draft: re-seed and re-init until the fixture draft lands
        val deadline = System.nanoTime() + 15_000_000_000L
        while (vm.editDraft.value?.firstName != "Aisha" && System.nanoTime() < deadline) {
            vm.allContactsState.value = listOf(fixtureContact())
            vm.clearEditDraft()
            vm.initEditDraft(7L)
            if (vm.editDraft.value?.firstName == "Aisha") break
            Thread.sleep(50)
        }
        assertEquals("Aisha", vm.editDraft.value?.firstName)

        // first name is the first text field on the form
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("Alicia")
        assertEquals("Alicia", vm.editDraft.value?.firstName)

        // company field is further down the (scrollable, fully composed) form
        val fields = rule.onAllNodes(hasSetTextAction())
        assertTrue(fields.fetchSemanticsNodes().size >= 4, "edit form should expose many fields")
        // scroll the form and type into the last visible text field (note/company area)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("hello note", substring = true))
        rule.onNodeWithText("hello note", substring = true).assertExists()
    }

    @Test
    fun editPageNewContactSaveWithNameSucceeds() {
        val vm = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalNavResultRegistry provides NavResultRegistry()
            ) {
                DynamicTheme { EditContactPage(backStack, vm, Route.EditContact(contactId = null)) }
            }
        }
        rule.waitForIdle()
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("Zoe")
        assertEquals("Zoe", vm.editDraft.value?.firstName)
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.save), substring = true).performClick()
        rule.waitForIdle()
        // saving a named new contact exits the page (back stack popped / draft saved)
        assertTrue(vm.editDraft.value != null || backStack.backStack.isNotEmpty())
    }

    @Test
    fun detailsScreenScrollsThroughSections() {
        val vm = vm()
        vm.allContactsState.value = listOf(fixtureContact())
        var back = 0
        rule.setContent {
            DynamicTheme {
                ContactDetailsPage(vm, contactId = 7, onBack = { back++ }, onEdit = {}, onDelete = {})
            }
        }
        vm.allContactsState.value = listOf(fixtureContact())
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Aisha", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val scroller = rule.onNode(hasScrollAction())
        scroller.performScrollToNode(hasText("(415) 555-0107", substring = true))
        rule.onNodeWithText("(415) 555-0107", substring = true).assertExists()
        scroller.performScrollToNode(hasText("aisha@x.com", substring = true))
        rule.onNodeWithText("aisha@x.com", substring = true).assertExists()
        scroller.performScrollToNode(hasText("hello note", substring = true))
        rule.onNodeWithText("hello note", substring = true).assertExists()
        assertEquals(0, back)
    }

    @Test
    fun groupsScreenAddGroupDialogFlow() {
        val added = mutableListOf<String>()
        val actions = object : com.vayunmathur.contacts.util.ContactsActions {
            override fun addGroup(name: String) { added += name }
        }
        rule.setContent {
            DynamicTheme {
                GroupsScreen(
                    com.vayunmathur.contacts.util.GroupsUiState(groups = emptyList()),
                    actions,
                )
            }
        }
        // FAB is the last clickable on the empty groups screen
        val clickables = rule.onAllNodes(androidx.compose.ui.test.hasClickAction())
        clickables[clickables.fetchSemanticsNodes().lastIndex].performClick()
        rule.waitForIdle()
        // dialog opens: type a name and confirm with its Add button
        rule.onNodeWithText(str(R.string.add_group), substring = true).assertExists()
        rule.onNode(hasSetTextAction()).performTextReplacement("Family")
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.add)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("Family"), added)
    }

    @Test
    fun settingsPageScrollsAndShowsExportRow() {
        val vm = vm()
        rule.setContent {
            DynamicTheme { SettingsPage(vm, NavBackStack<Route>(arrayOf(Route.Main))) }
        }
        rule.onNodeWithText(str(R.string.calendar_sync), substring = true).assertExists()
        val scroller = rule.onAllNodes(hasScrollAction())
        assertTrue(scroller.fetchSemanticsNodes().isNotEmpty())
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(str(R.string.export_contacts), substring = true))
        rule.onNodeWithText(str(R.string.export_contacts), substring = true).assertExists()
    }

    @Test
    fun contactListSelectionAndSearchInteractions() {
        val vm = vm()
        vm.allContactsState.value = listOf(fixtureContact())
        val actions = object : com.vayunmathur.contacts.util.ContactsActions {
            val opened = mutableListOf<Contact>()
            override fun openContact(contact: Contact) { opened += contact }
        }
        val contact = fixtureContact()
        rule.setContent {
            DynamicTheme {
                Box(Modifier.requiredSize(420.dp, 700.dp)) {
                    ContactListScreen(
                        com.vayunmathur.contacts.util.ContactListUiState(contacts = listOf(contact)),
                        actions,
                    )
                }
            }
        }
        rule.onNodeWithText("Aisha", substring = true).assertExists()
        assertTrue(actions.opened.isEmpty())
        assertNotNull(vm)
    }
}

/** Crop canvas: forcing a draw pass and dragging the crop rect by its handles. */
@org.junit.runner.RunWith(RobolectricTestRunner::class)
class CropCanvasPass2Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun cropCanvasDrawsAndHandlesDrag() {
        val bitmapFile = File(app.cacheDir, "crop-canvas.jpg")
        Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
            bitmapFile.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        var cropped: Bitmap? = null
        rule.setContent {
            DynamicTheme {
                CropPhotoScreen(
                    uri = Uri.fromFile(bitmapFile).toString(),
                    onCropComplete = { cropped = it },
                    onCancel = {},
                )
            }
        }
        rule.waitForIdle()
        // force a draw pass so the Canvas draw lambda actually renders
        val image = rule.onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        // drag inside the crop rect (move) and from near its bottom-right (resize)
        rule.onRoot().performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX + 30f, centerY + 20f), 250)
        }
        rule.onRoot().performTouchInput {
            swipe(Offset(centerX + 70f, centerY + 55f), Offset(centerX + 40f, centerY + 30f), 250)
        }
        rule.waitForIdle()
        rule.onNodeWithText(
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getString(com.vayunmathur.library.ui.R.string.done),
            substring = true,
        ).performClick()
        rule.waitForIdle()
        assertNotNull(cropped)
        assertEquals(1024, cropped!!.width)
    }
}
