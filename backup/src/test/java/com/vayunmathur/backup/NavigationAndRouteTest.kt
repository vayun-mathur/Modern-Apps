package com.vayunmathur.backup

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.backup.data.BackupConfig
import com.vayunmathur.backup.platform.BackupViewModel
import com.vayunmathur.backup.platform.crypto.KeyManager
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NavigationAndRouteTest {
    @get:Rule val rule = createComposeRule()
    private val store = androidx.lifecycle.ViewModelStore()
    @After fun tearDown() { store.clear() }
    private fun vm(): BackupViewModel =
        androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    BackupViewModel(app) as T
            },
        )[BackupViewModel::class.java]

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        FakeAndroidKeyStore.install(); FakeAndroidKeyStore.clear()
        app.getSharedPreferences("backup_secure_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        // Pumped off-main clear, never runBlocking on this thread: a
        // BackupViewModel from the previous test collects the shared
        // BackupConfig from viewModelScope (paused main looper), and
        // parking this thread on the clear can wait forever for that
        // looper — the BackupViewModelTest setUp wedge under 5 CPUs.
        val done = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.IO).launch {
            try { BackupConfig(app).clear(); done.complete(Unit) }
            catch (e: Throwable) { done.completeExceptionally(e) }
        }
        pumpUntil("backup config clear") { done.isCompleted }
        done.getCompletionExceptionOrNull()?.let { throw it }
        runCatching { KeyManager(app).clear() }
    }

    @Test fun routesAreDistinctNavKeys() {
        assertEquals(Route.Onboarding, Route.Onboarding)
        assertEquals(Route.Dashboard, Route.Dashboard)
        assertNotEquals<Route>(Route.Onboarding, Route.Dashboard)
        assertTrue(Route.Onboarding.toString().isNotEmpty())
    }

    @Test fun navigationShowsOnboardingWhenNotOnboarded() {
        val vm = vm()
        rule.setContent { Navigation(vm) }
        rule.onNodeWithText("Set up Backup").assertIsDisplayed()
        rule.onNodeWithText("Recovery code").performScrollTo().assertIsDisplayed()
    }

    @Test fun mainActivityContentType() {
        // MainActivity is a MaActivity whose Content delegates to Navigation;
        // launching it under Robolectric exercises that wiring end to end.
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
        controller.create().start().resume()
        controller.pause().stop().destroy()
    }

    /**
     * Step-bounded pumped wait on a terminal [condition]: every step
     * advances the paused main looper (running the ViewModel/DataStore
     * work queued there, including delayed tasks), then parks briefly
     * so Dispatchers.IO/Default producers get a CPU slice on a
     * contended box. The bound is pumping steps, never wall-clock
     * seconds that CI contention can burn before any work runs, and
     * the failure is raised only after [MAX_PUMP_STEPS] pumps.
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
        const val PUMP_STEP_MS = 100L
        const val PARK_MS = 10L
        const val MAX_PUMP_STEPS = 6_000
    }
}
