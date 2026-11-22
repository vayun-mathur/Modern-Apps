package com.vayunmathur.appstore.tv
import com.vayunmathur.appstore.platform.TvStoreViewModel
import com.vayunmathur.appstore.ui.tv.TvStoreDetail

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.toEntity
import com.vayunmathur.appstore.data.installer.InstallStage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The TV detail wrapper against the real ViewModel, plus the TV
 * row actions: install refusal records a dismissible failure, and
 * open/uninstall/browser fire the same intents as the phone's.
 */
@RunWith(RobolectricTestRunner::class)
class TvDetailWrapperAndActionsTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var vm: TvStoreViewModel
    private lateinit var app: Application

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = TvStoreViewModel(app, db)
    }

    private fun await(description: String, condition: () -> Boolean) {
        try {
            pumpUntil(description, condition)
        } catch (e: AssertionError) {
            throw AssertionError("timed out waiting for: $description; detail=${vm.detail.value}", e)
        }
    }

    /**
     * Step-bounded pumped wait on a terminal [condition]: every step
     * advances the paused main looper (running the ViewModel/DataStore
     * work queued there, including delayed tasks), then parks briefly
     * so Dispatchers.IO/Default producers get a CPU slice on a
     * contended box. The bound is pumping steps, never wall-clock
     * seconds that CI contention can burn before any work runs (the
     * previous 10-30s System.currentTimeMillis deadlines fired early
     * under a 5-CPU cap while the terminal state was still landing).
     */
    private fun pumpUntil(description: String, condition: () -> Boolean) {
        var steps = 0
        while (true) {
            // Pump first, then check: the loops this replaces always
            // idled at least once before judging the condition, and a
            // condition that is already true at entry can still need
            // that one pump for the work behind it to settle.
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(PUMP_STEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) break
            java.util.concurrent.locks.LockSupport.parkNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(PARK_MS),
            )
            if (++steps >= MAX_PUMP_STEPS) {
                throw AssertionError("timed out (step-bounded): $description after $MAX_PUMP_STEPS pumps")
            }
        }
    }

    private companion object {
        const val PUMP_STEP_MS = 200L
        const val PARK_MS = 50L
        const val MAX_PUMP_STEPS = 6_000
    }

    private fun nextStarted(): Intent? = Shadows.shadowOf(app).nextStartedActivity

    @Test
    fun `detail wrapper renders the app its package argument names`() {
        // Seed the catalogue the way the store would: the wrapper binds to
        // its route argument and resolves that package, with no selection
        // set on the ViewModel first. Seeding waits out the startup sync,
        // whose offline repo refresh would otherwise prune the row.
        await("startup settled") { !vm.isLoadingFlow.value }
        kotlinx.coroutines.runBlocking {
            db.cachedAppDao().upsert(
                UnifiedApp(
                    packageName = "com.example.wrapper",
                    source = AppSource.FDROID,
                    name = "Wrapper App",
                ).toEntity(),
            )
        }
        rule.setContent {
            TvStoreDetail(viewModel = vm, packageName = "com.example.wrapper", onBack = {})
        }
        await("loaded") { vm.detail.value.app?.name == "Wrapper App" }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText("Wrapper App").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Wrapper App").assertIsDisplayed()
    }

    @Test
    fun `install refusal records a failure that dismiss clears`() {
        val target = UnifiedApp(
            packageName = "com.example.refused",
            source = AppSource.FDROID,
            name = "Refused",
            apkUrl = null,
        )
        vm.install(target)
        await("failed stage") {
            vm.installer.stages.value["com.example.refused"] is InstallStage.Failed
        }
        vm.dismissInstallFailure("com.example.refused")
        assertNull(vm.installer.stages.value["com.example.refused"])
    }

    @Test
    fun `uninstall and browser actions fire their intents`() {
        vm.uninstallApp("com.example.target")
        val delete = nextStarted()
        assertNotNull(delete)
        assertEquals(Intent.ACTION_DELETE, delete.action)
        assertEquals("package:com.example.target", delete.dataString)

        vm.openInBrowser("https://example.org/app")
        val view = nextStarted()
        assertNotNull(view)
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("https://example.org/app", view.dataString)

        vm.openInBrowser("  ")
        assertNull(nextStarted())
    }

    @Test
    fun `openInPlayStore falls back to the web listing when no market app resolves`() {
        vm.openInPlayStore("com.example.target")
        val intent = nextStarted()
        assertNotNull(intent)
        assertTrue(
            intent.dataString!!.contains("com.example.target"),
            "was ${intent.dataString}",
        )
    }
}
