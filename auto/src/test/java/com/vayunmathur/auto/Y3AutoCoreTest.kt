package com.vayunmathur.auto

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.data.PinnedAppsPrefs
import com.vayunmathur.auto.domain.PinnedAppRow
import com.vayunmathur.auto.domain.PinnedAppsViewModel
import com.vayunmathur.auto.platform.CarTts
import com.vayunmathur.auto.platform.DiscoveredApp
import com.vayunmathur.auto.ui.PinnedAppsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3AutoCoreTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = app as android.content.Context

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        runBlocking { PinnedAppsPrefs.setPinned(ctx, emptyList()) }
    }
    @AfterTest fun tearDown() {
        runBlocking { PinnedAppsPrefs.setPinned(ctx, emptyList()) }
        Dispatchers.resetMain()
    }

    private fun waitPinned(expected: List<String>, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runBlocking { PinnedAppsPrefs.pinnedNow(ctx) } == expected) return
            Thread.sleep(50)
        }
        assertEquals(expected, runBlocking { PinnedAppsPrefs.pinnedNow(ctx) })
    }

    @Test fun pinnedVmToggleCapRejectMoveAndConsume() {
        val vm = PinnedAppsViewModel(app)
        // Pin five (the cap), the sixth is rejected.
        for (id in listOf("a", "b", "c", "d", "e")) vm.toggle(id)
        waitPinned(listOf("a", "b", "c", "d", "e"))
        vm.toggle("f")
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.rejected.value == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("f", vm.rejected.value)
        vm.consumeRejected(); assertEquals(null, vm.rejected.value)
        // Reorder: move e up twice, a down once.
        vm.moveUp("e"); waitPinned(listOf("a", "b", "c", "e", "d")); vm.moveUp("e"); waitPinned(listOf("a", "b", "e", "c", "d"))
        vm.moveDown("a"); waitPinned(listOf("b", "a", "e", "c", "d"))
        vm.moveUp("b"); waitPinned(listOf("b", "a", "e", "c", "d")) // already first: no-op
        vm.moveDown("missing"); vm.moveUp("missing")
        // Unpin.
        vm.toggle("b"); waitPinned(listOf("a", "e", "c", "d"))
        vm.refresh(); assertNotNull(vm.allApps)
    }

    @Test fun pinnedScreenStatelessToggleAndMoveCallbacks() {
        val app1 = DiscoveredApp(android.content.ComponentName("com.a", "com.a.Car"), "Alpha", null, emptySet())
        val app2 = DiscoveredApp(android.content.ComponentName("com.b", "com.b.Car"), "Beta", null, emptySet())
        val toggled = mutableListOf<String>(); val ups = mutableListOf<String>(); val downs = mutableListOf<String>()
        var back = 0
        compose.setContent {
            PinnedAppsScreen(
                onNavigateBack = { back++ },
                rows = listOf(PinnedAppRow(app1, 0), PinnedAppRow(app2, null)),
                onToggle = { toggled += it }, onMoveUp = { ups += it }, onMoveDown = { downs += it },
            )
        }
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("Beta").assertExists()
        // Checkbox toggles pin (Beta is the second, unpinned row).
        compose.onAllNodes(androidx.compose.ui.test.isToggleable())[1].performClick()
        assertTrue(toggled.contains("com.b/com.b.Car"), "$toggled")
        assertEquals(0, back)
    }

    @Test fun carTtsLifecycleWithoutEngine() {
        val events = mutableListOf<com.vayunmathur.auto.platform.AudioEvent>()
        val tts = CarTts(ctx, systemSink = { null }, onEvent = { events += it })
        tts.speak("before start is dropped silently") // no handler yet
        tts.start()
        tts.speak("hello car")
        Thread.sleep(800)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        tts.stop(); tts.stop()
        // Engine never became ready under Robolectric: either dropped or silent, never a crash.
        assertTrue(events.all { it is com.vayunmathur.auto.platform.AudioEvent.TtsDropped })
    }

    @Test fun mainActivityLaunchAndNewIntent() {
        val c = Robolectric.buildActivity(MainActivity::class.java, Intent(ctx, MainActivity::class.java))
        c.create().start().resume()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(500); Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(c.get())
        c.newIntent(Intent(ctx, MainActivity::class.java))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        c.pause().stop().destroy()
    }

    @Test fun routesAreDistinct() {
        val routes = listOf(Route.Home, Route.Pairing, Route.PinnedApps)
        assertEquals(3, routes.toSet().size)
    }

    @Test fun routeSerializationRoundTrip() {
        val json = kotlinx.serialization.json.Json
        for (route in listOf<Route>(Route.Home, Route.Pairing, Route.PinnedApps)) {
            val text = json.encodeToString(Route.serializer(), route)
            assertEquals(route, json.decodeFromString(Route.serializer(), text))
        }
    }

    @Test fun navigationGraphRendersHome() {
        val vm = com.vayunmathur.auto.platform.AutoViewModel()
        val pairing = com.vayunmathur.auto.platform.PairingViewModel()
        compose.setContent { Navigation(vm, pairing) }
        Thread.sleep(500)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(compose.onAllNodesWithText(ctx.getString(R.string.app_name), substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
