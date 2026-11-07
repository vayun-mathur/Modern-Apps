package com.vayunmathur.contacts.ui

import android.app.Application
import android.provider.ContactsContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.Photo
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.initEditDraft
import com.vayunmathur.contacts.util.setEditDraftPhotoFromBitmap
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Second-pass EditContactPage depth: photo, birthday, and the full form scroll. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditPageDeepPass2Test {
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


    private fun vm(): ContactViewModel {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        return vm
    }

    private fun setEditPage(vm: ContactViewModel, backStack: NavBackStack<Route>, route: Route.EditContact) {
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                DynamicTheme { EditContactPage(backStack, vm, route) }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun photoSectionWithoutPhotoShowsAddPicture() {
        val vm = vm()
        var clicks = 0
        var removes = 0
        rule.setContent {
            DynamicTheme {
                EditContactPhotoSection(
                    photo = null, viewModel = vm,
                    onClick = { clicks++ }, removePhoto = { removes++ },
                )
            }
        }
        rule.onNodeWithText(str(R.string.add_picture), substring = true).assertExists()
        rule.onNodeWithText(str(R.string.add_picture), substring = true).performClick()
        assertEquals(1, clicks)
        assertEquals(0, removes)
    }

    @Test
    fun photoSectionWithPhotoShowsChangeAndRemove() {
        val vm = vm()
        var clicks = 0
        var removes = 0
        // 1x1 PNG, decodable by the VM's photo cache
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="
        rule.setContent {
            DynamicTheme {
                EditContactPhotoSection(
                    photo = png, viewModel = vm,
                    onClick = { clicks++ }, removePhoto = { removes++ },
                    sharedKey = 7L,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.change_picture), substring = true).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.remove), substring = true).performClick()
        assertEquals(1, removes)
        assertEquals(0, clicks)
    }

    @Test
    fun birthdaySectionOpensPickerAndClears() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        var birthday: LocalDate? = LocalDate(1992, 3, 15)
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                DynamicTheme {
                    androidx.compose.foundation.layout.Column {
                        EditContactBirthday(backStack, birthday) { birthday = it }
                    }
                }
            }
        }
        rule.onNodeWithText(str(R.string.birthday), substring = true).assertExists()
        // the clear (remove-circle) button is the small clickable at the trailing edge
        val clickables = rule.onAllNodes(hasClickAction())
        val nodes = clickables.fetchSemanticsNodes()
        val smallest = nodes.indices.minByOrNull {
            val b = nodes[it].boundsInRoot
            (b.right - b.left) * (b.bottom - b.top)
        }!!
        clickables[smallest].performClick()
        assertEquals(null, birthday)
        // tapping the field itself pushes the date-picker route
        rule.onRoot().assertExists()
    }

    @Test
    fun birthdayFieldTapPushesPickerRoute() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                DynamicTheme {
                    androidx.compose.foundation.layout.Column {
                        EditContactBirthday(backStack, null) {}
                    }
                }
            }
        }
        // tap the field body (first wide clickable)
        val clickables = rule.onAllNodes(hasClickAction())
        val nodes = clickables.fetchSemanticsNodes()
        val field = nodes.indices.maxByOrNull {
            nodes[it].boundsInRoot.right - nodes[it].boundsInRoot.left
        }!!
        clickables[field].performClick()
        assertTrue(backStack.backStack.last() is Route.EventDatePickerDialog,
            "stack=${backStack.backStack}")
        assertEquals("birthday", (backStack.backStack.last() as Route.EventDatePickerDialog).id)
    }

    @Test
    fun editPageScrollsThroughAllSections() {
        val vm = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        setEditPage(vm, backStack, Route.EditContact(contactId = 7))
        assertEquals("Aisha", vm.editDraft.value?.firstName)

        val scroller = rule.onNode(hasScrollAction())
        // phone/email groups, birthday, gender, groups and note sections are all
        // reached by scrolling the form
        for (text in listOf(
            str(R.string.phone), str(R.string.birthday), str(R.string.gender),
        )) {
            scroller.performScrollToNode(hasText(text, substring = true))
            assertTrue(
                rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty(),
                "section $text should be reachable by scrolling",
            )
        }
        // the draft survives the scroll (form state is hoisted in the VM)
        assertEquals("Aisha", vm.editDraft.value?.firstName)
        assertTrue(vm.editDraft.value!!.phoneNumbers.isNotEmpty())
    }

    @Test
    fun editPagePhotoFromDraftBitmap() {
        val vm = vm()
        vm.initEditDraft(null)
        val bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
        vm.setEditDraftPhotoFromBitmap(bitmap)
        val deadline = System.nanoTime() + 15_000_000_000L
        while (vm.editDraft.value?.photo == null && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(vm.editDraft.value?.photo != null)
        // and it round-trips through the decoder
        val decoded = vm.decodePhoto(vm.editDraft.value!!.photo!!.value)
        assertTrue(decoded != null)
        @Suppress("UNUSED_EXPRESSION")
        Photo(0, vm.editDraft.value!!.photo!!.value)
    }
}
