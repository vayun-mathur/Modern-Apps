package com.vayunmathur.everysync

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.data.sink.NoteSink
import com.vayunmathur.everysync.data.sink.PasswordSink
import com.vayunmathur.everysync.data.sink.TodoIpcSink
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.everysync.platform.BootReceiver
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.sync.SnapshotWorker
import com.vayunmathur.everysync.sync.SyncEngine
import com.vayunmathur.everysync.sync.SyncScheduler
import com.vayunmathur.everysync.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** App entry points: MainActivity/Navigation, sync engine, workers, boot, routes. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppEntryTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val relay get() = RelaySuccessTest.sharedRelay

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        relay.install()
        FakeIpcProvider.install(NoteSink.AUTHORITY, PasswordSink.AUTHORITY, TodoIpcSink.AUTHORITY)
        try {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) { /* already initialised */ }
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain(); relay.uninstall() }

    @Test fun authenticatorIsAllNoOps() {
        val auth = com.vayunmathur.everysync.auth.Authenticator(ctx)
        val account = android.accounts.Account("alice", "com.vayunmathur.everysync")
        assertEquals(null, auth.editProperties(null, "t"))
        assertEquals(null, auth.addAccount(null, "t", "tok", null, null))
        assertEquals(null, auth.confirmCredentials(null, account, null))
        assertEquals(null, auth.getAuthToken(null, account, "tok", null))
        assertEquals(null, auth.getAuthTokenLabel("tok"))
        assertEquals(null, auth.updateCredentials(null, account, "tok", null))
        assertEquals(null, auth.hasFeatures(null, account, arrayOf("f")))
        val service = com.vayunmathur.everysync.auth.AuthenticatorService()
        assertNotNull(service)
        // Provider metadata is stable
        val provider = com.vayunmathur.everysync.provider.ProviderRegistry.get("e2ee")
        assertEquals("e2ee", provider?.id)
        assertEquals(com.vayunmathur.everysync.provider.AuthType.RELAY, provider?.authType)
    }

    @Test fun routesRoundTripThroughSerialization() {
        val routes: List<Route> = listOf(Route.Accounts, Route.AddAccount, Route.AccountDetail("alice"), Route.Settings)
        for (route in routes) {
            val encoded = Json.encodeToString(Route.serializer(), route)
            assertEquals(route, Json.decodeFromString(Route.serializer(), encoded), encoded)
        }
        assertEquals("alice", (Json.decodeFromString(Route.serializer(), Json.encodeToString(Route.serializer(), Route.AccountDetail("alice"))) as Route.AccountDetail).accountName)
    }

    @Test fun syncEngineRecordsSuccessAndSkipsUnknown() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val store = AccountStore.getInstance(ctx)
        store.upsert(AccountConfig("eng-acc", "e2ee", emptySet()))
        // Enabled types empty: provider syncs nothing and succeeds
        SyncEngine.syncAccount(ctx, "eng-acc")
        val after = store.get("eng-acc")
        assertNotNull(after?.lastSyncEpochMs?.takeIf { it > 0 })
        assertEquals(null, after?.lastSyncError)
        // Unknown account and unknown provider return quietly
        SyncEngine.syncAccount(ctx, "ghost")
        store.upsert(AccountConfig("bad-provider", "nope", emptySet()))
        SyncEngine.syncAccount(ctx, "bad-provider")
        SyncEngine.syncAll(ctx)
        // A notes-enabled account goes through the real provider against the fake relay
        store.upsert(AccountConfig("eng-notes", "e2ee", setOf(DataType.NOTES)))
        SyncEngine.syncAccount(ctx, "eng-notes")
        assertEquals(null, store.get("eng-notes")?.lastSyncError)
    } }

    @Test fun workersAndSchedulerAndBoot() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val store = AccountStore.getInstance(ctx)
        store.upsert(AccountConfig("worker-acc", "e2ee", emptySet()))
        val allWorker = TestListenableWorkerBuilder<SyncWorker>(ctx).build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), allWorker.startWork().get())
        val oneWorker = TestListenableWorkerBuilder<SyncWorker>(ctx)
            .setInputData(Data.Builder().putString(SyncWorker.KEY_ACCOUNT, "worker-acc").build())
            .build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), oneWorker.startWork().get())
        val snapshotWorker = TestListenableWorkerBuilder<SnapshotWorker>(ctx).build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), snapshotWorker.startWork().get())
        // Scheduler enqueues without crashing (WorkManager initialised above)
        SyncScheduler.schedulePeriodic(ctx)
        SyncScheduler.syncNow(ctx, "worker-acc")
        SyncScheduler.syncAllNow(ctx)
        SnapshotWorker.scheduleDaily(ctx)
        SnapshotWorker.cancel(ctx)
        SnapshotWorker.scheduleDaily(ctx)
        // Boot receiver reschedules on boot / update, ignores other actions
        val receiver = BootReceiver()
        receiver.onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(ctx, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        receiver.onReceive(ctx, Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED))
        Unit
    } }

    @Test fun navigationShowsAccountsAndRoutes() {
        org.robolectric.Shadows.shadowOf(ctx as Application).grantPermissions(
            android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS,
            android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR,
        )
        val store = AccountStore.getInstance(ctx)
        runBlocking { store.upsert(AccountConfig("nav-acc", "e2ee", setOf(DataType.NOTES))) }
        val vm = com.vayunmathur.everysync.platform.EverySyncViewModel(ctx as Application)
        rule.setContent { Navigation(vm) }
        rule.waitForIdle()
        rule.onNodeWithText("nav-acc").assertExists()
    }

    @Test fun mainActivityPlainAndJoinLink() {
        // Plain launch shows the accounts content
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.pause().stop().destroy()
        // Join link launch fires the sealed join request path (fake relay answers)
        runBlocking { assertTrue(RelaySync.init(ctx)) }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("everysync://join/v-join-1?owner=${RelaySync.deviceId}&type=notes"))
        val c2 = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = c2.get()
        // Warm-start a second join link through onNewIntent
        val intent2 = Intent(Intent.ACTION_VIEW, Uri.parse("everysync://join/v-join-2?owner=${RelaySync.deviceId}&type=todos"))
        c2.newIntent(intent2)
        // Non-join intents are ignored by the parser
        c2.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/other")))
        c2.pause().stop().destroy()
        // The application class boots (scheduling + migration) via a fresh instance
        val freshApp = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .newApplication(EverySyncApplication::class.java.classLoader, EverySyncApplication::class.java.name, ctx) as EverySyncApplication
        freshApp.onCreate()
        assertNotNull(freshApp)
    }
}
