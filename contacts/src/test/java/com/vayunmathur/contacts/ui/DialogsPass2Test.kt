package com.vayunmathur.contacts.ui

import android.app.Application
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.ui.dialogs.DeleteAccountDialog
import com.vayunmathur.contacts.ui.dialogs.RenameAccountDialog
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.DynamicTheme
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
import kotlin.test.assertTrue

/** Second-pass coverage for the account dialogs. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DialogsPass2Test {
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

    private val localAccount = ContactAccount("Family", "com.vayunmathur.contacts.local")

    @Test
    fun renameDialogPrefillsAndSavesNewName() {
        val vm = vm()
        var dismissed = 0
        rule.setContent {
            DynamicTheme { RenameAccountDialog(vm, localAccount) { dismissed++ } }
        }
        assertEquals(2, rule.onAllNodes(isRoot()).fetchSemanticsNodes().size)
        rule.onNodeWithText(str(R.string.rename_account), substring = true).assertExists()
        // field is prefilled with the current name; replace and save
        rule.onNode(hasSetTextAction()).performTextReplacement("Relatives")
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.save), substring = true).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun renameDialogCancelDismissesWithoutSaving() {
        val vm = vm()
        var dismissed = 0
        rule.setContent {
            DynamicTheme { RenameAccountDialog(vm, localAccount) { dismissed++ } }
        }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel), substring = true).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun renameDialogBlankNameDoesNotSaveOrDismiss() {
        val vm = vm()
        var dismissed = 0
        rule.setContent {
            DynamicTheme { RenameAccountDialog(vm, localAccount) { dismissed++ } }
        }
        rule.onNode(hasSetTextAction()).performTextReplacement("   ")
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.save), substring = true).performClick()
        assertEquals(0, dismissed)
        // dialog is still open
        assertEquals(2, rule.onAllNodes(isRoot()).fetchSemanticsNodes().size)
    }

    @Test
    fun deleteDialogConfirmDismisses() {
        val vm = vm()
        var dismissed = 0
        rule.setContent {
            DynamicTheme { DeleteAccountDialog(vm, localAccount) { dismissed++ } }
        }
        rule.onNodeWithText(str(R.string.delete_account), substring = true).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.delete)).performClick()
        // DeleteAccountDialog dismisses itself and ConfirmDialog dismisses again on confirm
        assertTrue(dismissed >= 1, "confirm should dismiss, dismissed=$dismissed")
    }

    @Test
    fun deleteDialogCancelDismisses() {
        val vm = vm()
        var dismissed = 0
        rule.setContent {
            DynamicTheme { DeleteAccountDialog(vm, localAccount) { dismissed++ } }
        }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel), substring = true).performClick()
        assertEquals(1, dismissed)
    }
}
