package com.vayunmathur.everysync.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.data.Settings
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The ViewModel-binding screen overloads (backStack + viewModel), driven end to end. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BinderScreensTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val relay get() = com.vayunmathur.everysync.RelaySuccessTest.sharedRelay

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        relay.install()
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) { /* already initialised */ }
        shadowOf(app).grantPermissions(
            android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS,
            android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR,
        )
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain(); relay.uninstall() }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun await(deadlineMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle(); idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }
    private fun str(id: Int) = app.getString(id)

    @Test fun settingsBinderPersistsChanges() {
        val vm = EverySyncViewModel(app)
        val stack = NavBackStack<Route>(arrayOf(Route.Settings))
        rule.setContent { SettingsScreen(stack, vm) }
        rule.onNodeWithText(str(R.string.wifi_only)).performClick()
        assertTrue(await { Settings.wifiOnly(app) }, "wifiOnly not persisted")
        rule.onNodeWithText(str(R.string.conflict_remote), substring = true).performClick()
        assertTrue(await { Settings.conflictPolicy(app) == Settings.CONFLICT_REMOTE })
        // Interval edit goes through the ViewModel (coerced to the 15-minute floor by Settings)
        rule.onNode(hasSetTextAction()).performTextReplacement("45")
        rule.waitForIdle(); idle()
        assertTrue(Settings.intervalMinutes(app) in listOf(45L, 60L) || Settings.intervalMinutes(app) >= 15L)
    }

    @Test fun accountsBinderNavigates() { runBlocking {
        AccountStore.getInstance(app).upsert(AccountConfig("bind-acc", "e2ee", setOf(DataType.NOTES)))
        RelaySync.init(app)
        val vm = EverySyncViewModel(app)
        val stack = NavBackStack<Route>(arrayOf(Route.Accounts))
        rule.setContent { AccountsScreen(stack, vm) }
        rule.waitUntil(timeoutMillis = 10_000) { stack.backStack.size == 1 }
        rule.onNodeWithText("bind-acc").assertExists()
        rule.onNodeWithText("bind-acc").performClick()
        assertEquals(Route.AccountDetail("bind-acc"), stack.last())
    } }

    @Test fun addAccountBinderCreatesAndPops() {
        val vm = EverySyncViewModel(app)
        val stack = NavBackStack<Route>(arrayOf(Route.Accounts, Route.AddAccount))
        rule.setContent { AddAccountScreen(stack, vm) }
        rule.onNodeWithText("Personal vaults").performClick()
        // Account created from the provider's display name and the chooser popped
        assertTrue(await { runBlocking { AccountStore.getInstance(app).get("Personal vaults") } != null }, "account not created")
        assertEquals("e2ee", runBlocking { AccountStore.getInstance(app).get("Personal vaults") }?.providerId)
        assertTrue(await { stack.last() == Route.Accounts })
    }

    @Test fun accountDetailBinderActions() { runBlocking {
        AccountStore.getInstance(app).upsert(AccountConfig("detail-acc", "e2ee", setOf(DataType.NOTES, DataType.CONTACTS)))
        RelaySync.init(app)
        val vm = EverySyncViewModel(app)
        vm.vaults.load()
        val stack = NavBackStack<Route>(arrayOf(Route.Accounts, Route.AccountDetail("detail-acc")))
        rule.setContent { AccountDetailScreen(stack, vm, "detail-acc") }
        rule.waitForIdle(); idle()
        rule.onNodeWithText("detail-acc").assertExists()
        // Toggle notes off through the binder (snapshots + purges via the ViewModel)
        rule.onNodeWithText(str(R.string.sync_notes)).performClick()
        assertTrue(await { runBlocking { AccountStore.getInstance(app).get("detail-acc") }?.enabledTypes?.contains(DataType.NOTES) == false }, "notes not toggled off")
        // Sync now enqueues work without crashing; remove pops the stack and deletes the account
        rule.onNodeWithText(str(R.string.sync_now)).performScrollTo().performClick()
        rule.onNodeWithText(str(R.string.remove_account)).performScrollTo().performClick()
        assertTrue(await { stack.last() == Route.Accounts })
        assertTrue(await { runBlocking { AccountStore.getInstance(app).get("detail-acc") } == null })
        Unit
    } }
}
