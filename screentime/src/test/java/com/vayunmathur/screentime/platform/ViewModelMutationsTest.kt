package com.vayunmathur.screentime.platform

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.databases
import com.vayunmathur.screentime.FakeAndroidKeyStore
import com.vayunmathur.screentime.Route
import com.vayunmathur.screentime.data.ScreenTimeDatabase
import com.vayunmathur.screentime.data.ScreenTimeRules
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ViewModel's mutations and Coordinator.reconcile against a real
 * rules store. The deep suite could only pin "accepted without throwing"
 * because the SQLCipher-backed store cannot open on a host JVM; seeding
 * the shared database cache with an in-memory database (and resetting
 * the rules singleton, restored afterwards) makes every write land and
 * lets the stored state be asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewModelMutationsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val rules: ScreenTimeRules get() = ScreenTimeRules.get(app)

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        resetRulesSingleton()
        synchronized(databases) {
            runCatching { databases[ScreenTimeDatabase::class]?.close() }
            databases[ScreenTimeDatabase::class] =
                Room.inMemoryDatabaseBuilder(app, ScreenTimeDatabase::class.java)
                    .allowMainThreadQueries().build()
        }
    }

    @AfterTest fun tearDown() {
        resetRulesSingleton()
        synchronized(databases) {
            runCatching { databases[ScreenTimeDatabase::class]?.close() }
            databases.remove(ScreenTimeDatabase::class)
        }
    }

    private fun resetRulesSingleton() {
        runCatching {
            val field = ScreenTimeRules::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (runCatching { cond() }.getOrDefault(false)) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out: $desc")
    }

    @Test fun timerPauseAndWindDownMutationsPersist() {
        val vm = ScreenTimeViewModel(app)

        vm.setTimer("com.a.app", 60)
        await("timer written") { runBlocking { rules.timer("com.a.app") }?.dailyLimitMinutes == 60 }
        vm.setTimer("com.a.app", null)
        await("timer deleted") { runBlocking { rules.timer("com.a.app") } == null }

        vm.setAppPaused("com.a.app", true)
        await("pause written") {
            "com.a.app" in runBlocking { rules.pausedNow() }.pausedPackages
        }
        vm.setAppPaused("com.a.app", false)
        await("pause lifted") {
            "com.a.app" !in runBlocking { rules.pausedNow() }.pausedPackages
        }

        vm.setWindDown(enabled = true, startMinute = 23 * 60, endMinute = 6 * 60)
        await("wind-down written") {
            val schedule = runBlocking { rules.windDownNow() }
            schedule.enabled && schedule.startMinute == 23 * 60 && schedule.endMinute == 6 * 60
        }
        vm.setWindDown(grayscale = false, doNotDisturb = false)
        await("wind-down flags written") {
            val schedule = runBlocking { rules.windDownNow() }
            !schedule.grayscale && !schedule.doNotDisturb
        }
    }

    @Test fun reconcileAppliesPausedAndTimerState() = runBlocking {
        rules.setPaused(
            com.vayunmathur.screentime.data.PausedApps(pausedPackages = listOf("com.manual.app")),
        )
        Coordinator(app).reconcile()
        // Reconcile is the funnel every trigger shares; with an empty usage
        // picture it must simply complete and keep the stored state intact.
        assertEquals(listOf("com.manual.app"), rules.pausedNow().pausedPackages)

        ScreenTimeViewModel(app).setTimer("com.timed.app", 30)
        await("timer stored for reconcile") { runBlocking { rules.timer("com.timed.app") } != null }
        Coordinator(app).reconcile()
        assertEquals(30, rules.timer("com.timed.app")?.dailyLimitMinutes)
    }

    @Test fun routesRoundTripThroughSerializer() {
        val json = Json
        val routes: List<Route> = listOf(
            Route.Dashboard, Route.AppDetails("com.example.app"), Route.WindDown,
        )
        for (route in routes) {
            val encoded = json.encodeToString(Route.serializer(), route)
            assertEquals(route, json.decodeFromString(Route.serializer(), encoded), encoded)
        }
        assertNull(
            runCatching {
                json.decodeFromString(Route.serializer(), """{"type":"nope"}""")
            }.getOrNull(),
            "an unknown destination must not decode",
        )
        assertTrue(Route.AppDetails("x").packageName == "x")
    }
}
