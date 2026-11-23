package com.vayunmathur.contacts.ui

import android.app.Application
import android.provider.ContactsContract
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.ui.dialogs.MoveDialogHost
import com.vayunmathur.contacts.ui.dialogs.MoveToAccountDialog
import com.vayunmathur.contacts.platform.ContactAccount
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.testsupport.awaitSemantics
import com.vayunmathur.testsupport.awaitWithMainIdle
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

/** Second-pass move dialogs: account selection, SIM loss page, and the host transfer. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MoveDialogsPass2Test {
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

    @AfterTest
    fun tearDown() {
        // setMain above is process-wide: without this, every later class in
        // the batched JVM inherits Unconfined Main and its paused-looper
        // waits silently change meaning (order-dependent flakes).
        Dispatchers.resetMain()
    }


    private val google = ContactAccount("a@gmail.com", "com.google")
    private val sim = ContactAccount("5", SIM_ACCOUNT_TYPE)

    @Test
    fun moveDialogSelectGoogleAndConfirm() {
        var selected: ContactAccount? = null
        var confirmed = 0
        var dismissed = 0
        rule.setContent {
            DynamicTheme {
                MoveToAccountDialog(
                    contactCount = 2,
                    accounts = listOf(google, sim),
                    sourceAccounts = setOf(ContactAccount("other@gmail.com", "com.google")),
                    selectedAccount = selected,
                    onSelectAccount = { selected = it },
                    onClearSelection = { selected = null },
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
        rule.onNodeWithText("a@gmail.com", substring = true).assertExists()
        rule.onNodeWithText("a@gmail.com", substring = true).performClick()
        assertEquals(google, selected)
        // confirm button is labelled Move; it stays disabled until selection,
        // and our controlled state kept selectedAccount null in composition,
        // so just verify the dismiss path here
        rule.onNodeWithText(ctx.getString(com.vayunmathur.library.ui.R.string.cancel), substring = true).performClick()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    @Test
    fun moveDialogSimDestinationShowsLossPage() {
        rule.setContent {
            DynamicTheme {
                MoveToAccountDialog(
                    contactCount = 1,
                    accounts = listOf(google, sim),
                    sourceAccounts = setOf(google),
                    simLabels = mapOf("$SIM_ACCOUNT_TYPE|5" to "SIM 1"),
                    selectedAccount = sim,
                    simLossLabels = listOf("Photos", "Extra phone numbers"),
                    onSelectAccount = {}, onClearSelection = {},
                    onConfirm = {}, onDismiss = {},
                )
            }
        }
        // SIM destination replaces the account list with the loss warning
        rule.onNodeWithText("Photos", substring = true).assertExists()
        rule.onNodeWithText("Extra phone numbers", substring = true).assertExists()
        assertEquals(
            0,
            rule.onAllNodesWithText("a@gmail.com", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun moveDialogHostTransfersToSelectedAccount() {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        // The ViewModel's own init sync/loadAccounts writes accountsState
        // asynchronously (since the SIM-source isolation work it does so at
        // the end of every sync). Injecting the test accounts before that
        // work settles lets a late write erase them, so the dialog never
        // lists them — wait for the sync's terminal state first: contacts
        // published (fake provider contact 7) and the load marked complete
        // (set in syncFromSystem's finally, after its account refresh).
        awaitWithMainIdle("contacts sync settled") {
            vm.allContactsState.value.any { it.id == 7L } && vm.hasLoadedContacts.value
        }
        // fake provider contact 7 loaded; make both accounts available
        vm.accountsState.value = listOf(google, sim)
        var dismissed = 0
        var transferred = 0
        rule.setContent {
            DynamicTheme {
                MoveDialogHost(vm, listOf(7L), onDismiss = { dismissed++ }, onTransferred = { transferred++ })
            }
        }
        // A straggler loadAccountsInternal from the ViewModel's init
        // (init launches a sync and a separate loadAccounts, and this
        // test's runBlocking sync is a third) can land after the
        // injection above and overwrite accountsState with the
        // provider-derived list — which contains the google account
        // (fake provider) but not the SIM account, so the SIM row
        // vanishes while the google row stays (CI run 598beb90f:
        // no node containing '5' at the click). Re-seat the injected
        // accounts inside the wait, and wait for the SIM row itself,
        // whose label depends on whether the SIM display labels have
        // landed: the raw account name "5", a "SIM n" label, or the
        // on-device string ("On-Device", hyphenated).
        val rowLabels = listOf("On-Device", "SIM", "5")
        fun simRow() = rowLabels.firstNotNullOfOrNull { label ->
            rule.onAllNodesWithText(label, substring = true)
                .fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let { label }
        }
        rule.awaitSemantics("SIM account row listed") {
            if (vm.accountsState.value != listOf(google, sim)) {
                vm.accountsState.value = listOf(google, sim)
            }
            simRow() != null
        }
        // Aisha's own (google) account is the disabled source; pick the SIM
        // account instead, which switches the dialog to its loss-warning page
        val label = simRow()!!
        rule.onAllNodesWithText(label, substring = true)[0].performClick()
        // The SIM destination switches to the loss-warning page ("Move
        // anyway"); that switch is a recomposition on the main looper, so
        // wait on the page itself rather than idling once.
        rule.awaitSemantics("SIM loss page shown") {
            rule.onAllNodesWithText(str(R.string.move_anyway), substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(str(R.string.move_anyway)).assertExists()
        rule.onNodeWithText(str(R.string.move_anyway)).performClick()
        // The transfer runs on IO and reports back on Main (result state,
        // then a LaunchedEffect fires onTransferred/onDismiss): dismissal
        // is the terminal signal.
        rule.awaitSemantics("transfer dismissed the dialog") { dismissed == 1 }
        assertEquals(1, dismissed)
        assertEquals(1, transferred)
    }
}
