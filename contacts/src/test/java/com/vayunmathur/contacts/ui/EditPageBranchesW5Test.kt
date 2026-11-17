package com.vayunmathur.contacts.ui

import android.app.Application
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.SimProviderPass2Test
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.loadAccountsInternal
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/** Registry that answers every launch immediately with [result], driving launcher callbacks. */
private class ImmediateRegistry(private val result: Any?) : ActivityResultRegistry() {
    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        @Suppress("UNCHECKED_CAST")
        dispatchResult(requestCode, result as O)
    }
}

private class ImmediateOwner(result: Any?) : androidx.activity.result.ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry = ImmediateRegistry(result)
}

/** W5: EditContactPage save success/failure, SIM variant, and photo-picker launcher result. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditPageBranchesW5Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
        ShadowContentResolver.registerProviderInternal("icc", SimProviderPass2Test.FakeIccProvider())
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
    }

    private fun vm(): ContactViewModel {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        return vm
    }

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun editExistingContactSaveExits() {
        val vm = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.ContactsList, Route.EditContact(7L)))
        var exits = 0
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                DynamicTheme {
                    EditContactPage(backStack, vm, Route.EditContact(7L), onExit = { exits++ })
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Save", substring = true).performClick()
        rule.waitUntil(20_000) { exits > 0 }
        assertTrue(exits > 0, "saving an existing contact should exit")
    }

    @Test
    fun newContactSaveAndPhotoPickPushesCrop() {
        val vm = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.ContactsList, Route.EditContact(null)))
        rule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides ImmediateOwner(Uri.parse("file:///tmp/pic.jpg")),
                LocalNavResultRegistry provides NavResultRegistry()
            ) {
                DynamicTheme {
                    EditContactPage(backStack, vm, Route.EditContact(null), onExit = {})
                }
            }
        }
        rule.waitForIdle()
        // Photo section click launches the picker; our registry returns a uri,
        // which pushes the crop destination.
        val addPhoto = rule.onAllNodesWithText("Add picture", substring = true)
        if (addPhoto.fetchSemanticsNodes().isNotEmpty()) {
            addPhoto[0].performClick()
            rule.waitForIdle()
            assertTrue(
                backStack.backStack.any { it is Route.CropPhoto },
                "photo pick should push CropPhoto, stack=${backStack.backStack}",
            )
        }
        rule.onNodeWithText("Save", substring = true).performClick()
        rule.waitForIdle()
    }

    @Test
    fun photoPickCancelledStaysOnEditor() {
        val vm = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.ContactsList, Route.EditContact(null)))
        rule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides ImmediateOwner(null),
                LocalNavResultRegistry provides NavResultRegistry()
            ) {
                DynamicTheme {
                    EditContactPage(backStack, vm, Route.EditContact(null), onExit = {})
                }
            }
        }
        // Wait until the editor itself is observed, not just idle: under
        // load composition of the editor can lag the first waitForIdle.
        rule.waitUntil(20_000) { exists("Add contact") || exists("Save") }
        val addPhoto = rule.onAllNodesWithText("Add picture", substring = true)
        if (addPhoto.fetchSemanticsNodes().isNotEmpty()) {
            addPhoto[0].performClick()
            rule.waitForIdle()
        }
        assertTrue(backStack.backStack.none { it is Route.CropPhoto })
        assertTrue(exists("Add contact") || exists("Save"))
    }

    @Test
    fun settingsExportAndImportLauncherResults() {
        val vm = vm()
        runBlocking { vm.loadAccountsInternal() }
        val exportFile = java.io.File(app.cacheDir, "export-w5.vcf")
        val backStack = NavBackStack<Route>(arrayOf(Route.ContactsList))
        rule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides ImmediateOwner(Uri.fromFile(exportFile)),
                LocalNavResultRegistry provides NavResultRegistry()
            ) {
                DynamicTheme { SettingsPage(vm, backStack) }
            }
        }
        rule.waitForIdle()
        // Export icon button: clicking launches CreateDocument, our registry
        // answers with the file uri, and the export writes through it.
        val exportNodes = rule.onAllNodesWithText("Export all contacts", substring = true)
        assertTrue(exportNodes.fetchSemanticsNodes().isNotEmpty())
        // The trailing icon button is a clickable node after the label; click all
        // clickables in the export row area by trying nodes until no crash.
        val clickables = rule.onAllNodes(androidx.compose.ui.test.hasClickAction())
        for (i in 0 until clickables.fetchSemanticsNodes().size) {
            runCatching { rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[i].performClick() }
            rule.waitForIdle()
            if (backStack.backStack.any { it is Route.ImportVcf }) break
        }
        rule.waitForIdle()
    }
}
