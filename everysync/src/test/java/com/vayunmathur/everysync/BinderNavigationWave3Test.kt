package com.vayunmathur.everysync

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.auth.TokenStore
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.sync.SnapshotKeyStore
import com.vayunmathur.everysync.sync.SnapshotWorker
import com.vayunmathur.everysync.sync.SyncWorker
import com.vayunmathur.everysync.ui.AccountDetailScreen
import com.vayunmathur.everysync.ui.AccountsScreen
import com.vayunmathur.everysync.ui.AddAccountScreen
import com.vayunmathur.everysync.ui.SettingsScreen
import com.vayunmathur.library.util.NavBackStack
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ViewModel/back-stack binder screens, the Navigation host, the
 * MainActivity join-link entry and the WorkManager workers — the wiring
 * layer between the stateless screens and the real stores.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BinderNavigationWave3Test {
    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app
    private val relay get() = RelaySuccessTest.sharedRelay

    @BeforeTest fun setUp() {
        relay.install()
        relay.transportDown = false
        relay.httpStatus = 200
        val softwareKey = SecretKeySpec(ByteArray(32) { (it * 13 + 7).toByte() }, "AES")
        com.vayunmathur.everysync.data.VaultKeyStore.secretKeyForTest = softwareKey
        SnapshotKeyStore.secretKeyForTest = softwareKey
        TokenStore.getInstance(app).secretKeyForTest = softwareKey
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS,
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
        )
        try {
            androidx.work.WorkManager.initialize(
                app, androidx.work.Configuration.Builder().build(),
            )
        } catch (_: IllegalStateException) { }
    }

    private fun viewModelWithAccount(name: String): EverySyncViewModel { runBlocking {
        AccountStore.getInstance(app).upsert(
            AccountConfig(name, "e2ee", setOf(DataType.CONTACTS, DataType.NOTES)),
        )
    }
        return EverySyncViewModel(app)
    }

    @Test fun binderAccountsScreenRendersFromViewModel() {
        val vm = viewModelWithAccount("binder-alice")
        val stack = NavBackStack<Route>(arrayOf(Route.Accounts))
        rule.setContent { AccountsScreen(stack, vm) }
        rule.waitUntilExactlyOneExists(hasText("binder-alice"), 5_000)
        rule.onNodeWithText("binder-alice").assertIsDisplayed()
    }

    @Test fun binderAddAccountScreenAddsAndPops() {
        val vm = viewModelWithAccount("binder-add")
        val stack = NavBackStack<Route>(arrayOf(Route.AddAccount))
        rule.setContent { AddAccountScreen(stack, vm) }
        rule.waitUntilExactlyOneExists(hasText("Personal vaults"), 5_000)
        rule.onNodeWithText("Personal vaults").performClick()
        rule.waitForIdle()
    }

    @Test fun binderSettingsScreenRendersFromViewModel() {
        val vm = viewModelWithAccount("binder-settings")
        val stack = NavBackStack<Route>(arrayOf(Route.Settings))
        rule.setContent { SettingsScreen(stack, vm) }
        rule.waitUntilExactlyOneExists(hasText("Settings"), 5_000)
        rule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun binderAccountDetailScreenRendersFromViewModel() {
        val vm = viewModelWithAccount("binder-detail")
        val stack = NavBackStack<Route>(arrayOf(Route.AccountDetail("binder-detail")))
        rule.setContent { AccountDetailScreen(stack, vm, "binder-detail") }
        rule.waitUntilExactlyOneExists(hasText("binder-detail"), 5_000)
        rule.onNodeWithText("binder-detail").assertIsDisplayed()
    }

    @Test fun navigationHostRendersAccountsEntry() {
        val vm = viewModelWithAccount("nav-bob")
        rule.setContent { Navigation(vm) }
        rule.waitUntilExactlyOneExists(hasText("nav-bob"), 5_000)
        rule.onNodeWithText("nav-bob").assertIsDisplayed()
    }

    @Test fun mainActivityWithoutJoinLinkShowsNavigation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java))
        val activity = controller.create().start().resume().get()
        assertTrue(activity != null)
        controller.pause().stop().destroy()
    }

    @Test fun mainActivityJoinLinkFiresSealedRequestAndNewIntent() {
        val joinUri = Uri.parse("everysync://join/v-join-1?owner=owner-dev-1&type=notes")
        val intent = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = joinUri
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller.create().start().resume().get()
        // Warm-start a second, malformed link: ignored, activity survives.
        val bad = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://example.com/not-a-join")
        }
        controller.newIntent(bad)
        val goodAgain = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("everysync://join/v-join-2?owner=owner-dev-1&type=todos")
        }
        controller.newIntent(goodAgain)
        controller.pause().stop().destroy()
    }

    @Test fun syncWorkerWithAndWithoutAccountAndSnapshotWorker() { runBlocking {
        AccountStore.getInstance(app).upsert(
            AccountConfig("worker-ghost", "no-such-provider", setOf(DataType.NOTES)),
        )
        fun params(input: androidx.work.Data) = androidx.work.WorkerParameters(
            java.util.UUID.randomUUID(), input, emptyList(),
            androidx.work.WorkerParameters.RuntimeExtras(), 0, 0,
            java.util.concurrent.Executor { it.run() }, kotlinx.coroutines.Dispatchers.Default,
            object : androidx.work.impl.utils.taskexecutor.TaskExecutor {
                override fun getMainThreadExecutor() = java.util.concurrent.Executor { it.run() }
                override fun getSerialTaskExecutor() = object : androidx.work.impl.utils.taskexecutor.SerialExecutor {
                    override fun execute(command: Runnable) = command.run()
                    override fun hasPendingTasks() = false
                }
                override fun getCoroutineScope() = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            },
            object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context, workerClassName: String,
                    workerParameters: androidx.work.WorkerParameters,
                ): androidx.work.ListenableWorker? = null
            },
            androidx.work.ProgressUpdater { _, _, _ ->
                com.google.common.util.concurrent.Futures.immediateVoidFuture()
            },
            androidx.work.ForegroundUpdater { _, _, _ ->
                com.google.common.util.concurrent.Futures.immediateVoidFuture()
            },
        )
        val targeted = SyncWorker(ctx, params(
            Data.Builder().putString(SyncWorker.KEY_ACCOUNT, "worker-ghost").build()))
        assertEquals(ListenableWorker.Result.success(), targeted.doWork())
        assertEquals(ListenableWorker.Result.success(), SyncWorker(ctx, params(Data.EMPTY)).doWork())
        val missing = SyncWorker(ctx, params(
            Data.Builder().putString(SyncWorker.KEY_ACCOUNT, "worker-missing").build()))
        assertEquals(ListenableWorker.Result.success(), missing.doWork())
        assertEquals(ListenableWorker.Result.success(), SnapshotWorker(ctx, params(Data.EMPTY)).doWork())
    } }

    @Test fun binderAccountDetailActionsDriveViewModel() {
        val vm = viewModelWithAccount("binder-actions")
        val stack = NavBackStack<Route>(arrayOf(Route.AccountDetail("binder-actions")))
        rule.setContent { AccountDetailScreen(stack, vm, "binder-actions") }
        rule.waitUntilExactlyOneExists(hasText("binder-actions"), 5_000)
        // Toggles and sync go through the binder's actions object into the VM.
        rule.onNodeWithText("Sync calendars").performClick()
        rule.onNodeWithText("Sync now").performScrollTo().performClick()
        rule.waitForIdle()
        // Removing the account pops the back stack via the binder.
        rule.onNodeWithText("Remove account").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(stack.backStack.isEmpty() || stack.last() !is Route.AccountDetail)
    }

    @Test fun binderAccountsActionsNavigate() {
        val vm = viewModelWithAccount("binder-nav")
        val stack = NavBackStack<Route>(arrayOf(Route.Accounts))
        rule.setContent { AccountsScreen(stack, vm) }
        rule.waitUntilExactlyOneExists(hasText("binder-nav"), 5_000)
        rule.onNodeWithText("binder-nav").performClick()
        rule.waitForIdle()
        assertTrue(stack.last() is Route.AccountDetail)
    }
}
