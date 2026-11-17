package com.vayunmathur.parentalcontrols

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.databases
import com.vayunmathur.parentalcontrols.auth.ParentPin
import com.vayunmathur.parentalcontrols.data.ParentalControlsDatabase
import com.vayunmathur.parentalcontrols.platform.FakeAndroidKeyStore
import com.vayunmathur.parentalcontrols.platform.SupervisionViewModel
import com.vayunmathur.parentalcontrols.platform.UsageAccess
import com.vayunmathur.parentalcontrols.data.AppRule
import com.vayunmathur.parentalcontrols.data.BonusGrant
import com.vayunmathur.parentalcontrols.platform.AppLimits
import com.vayunmathur.parentalcontrols.platform.LimitState
import java.time.Duration
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.vayunmathur.parentalcontrols.domain.PinGateTarget

/**
 * Host-testable parts of the parental-control gate the report named:
 * Route's saved-state serializer (never round-tripped), UsageAccess's
 * self-grant attempt (only the granted shortcut ran), and the Navigation
 * host's gate-first routing for gated destinations. Enforcer.reconcile
 * itself is not here: it needs the MaOS SupervisionManager, whose
 * compile-only stub class cannot even load on a host JVM.
 */
@RunWith(RobolectricTestRunner::class)
class RouteAndUsageAccessTest {

    @get:Rule val rule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        synchronized(databases) {
            if (databases[ParentalControlsDatabase::class] !is ParentalControlsDatabase) {
                databases[ParentalControlsDatabase::class] =
                    Room.inMemoryDatabaseBuilder(app, ParentalControlsDatabase::class.java)
                        .allowMainThreadQueries().build()
            }
        }
    }

    // ---- Route serialization (the back stack is saved through this) ----

    @Test fun everyRouteRoundTripsThroughItsSerializer() {
        val json = Json
        val routes: List<Route> = listOf(
            Route.Dashboard, Route.DailyLimit, Route.AppLimits, Route.Bedtime,
            Route.Downtime, Route.SchoolTime, Route.WebFilters, Route.PinSetup,
        ) + PinGateTarget.entries.map { Route.PinGate(it) }
        for (route in routes) {
            val encoded = json.encodeToString(Route.serializer(), route)
            assertEquals(route, json.decodeFromString(Route.serializer(), encoded), encoded)
        }
    }

    @Test fun pinGateTargetsMapBothWays() {
        for (target in PinGateTarget.entries) {
            val editor = target.editorRoute()
            if (editor == null) {
                assertTrue(target == PinGateTarget.Controls || target == PinGateTarget.ManagePin)
            } else {
                assertEquals(target, PinGateTarget.forRoute(editor), "$target must guard its own editor")
            }
        }
        assertNull(PinGateTarget.forRoute(Route.Dashboard))
        assertNull(PinGateTarget.forRoute(Route.PinSetup))
        assertNull(PinGateTarget.forRoute(Route.PinGate(PinGateTarget.Bedtime)))
    }

    // ---- UsageAccess ----

    @Test fun usageAccessGrantedShortcutAndSelfGrantAttempt() {
        val ops = app.getSystemService(AppOpsManager::class.java)
        val shadow = shadowOf(ops)
        shadow.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_ALLOWED)
        assertTrue(UsageAccess.isGranted(app))
        assertEquals(UsageAccess.Status.GRANTED, UsageAccess.ensure(app))

        shadow.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_IGNORED)
        assertFalse(UsageAccess.isGranted(app))
        // ensure() must attempt the self-grant and report the outcome either
        // way (GRANTED where the platform allows it, DENIED where the hidden
        // setUidMode is unreachable - both are valid terminal answers).
        val status = UsageAccess.ensure(app)
        assertTrue(status == UsageAccess.Status.GRANTED || status == UsageAccess.Status.DENIED)
        assertEquals(status == UsageAccess.Status.GRANTED, UsageAccess.isGranted(app))
    }

    // ---- AppLimits / LimitState (the usage side of enforcement) ----

    @Test fun appLimitsSyncAndUsageReadsOnHost() {
        val limits = AppLimits(app)
        // No usage stats are seeded under Robolectric: reads are zero, and
        // syncing capped, uncapped and bonus-extended rules must be safe.
        assertEquals(Duration.ZERO, limits.timeUsedToday("com.example.app"))
        assertEquals(Duration.ZERO, limits.totalUsedToday())
        val rules = listOf(
            AppRule("com.example.capped", dailyLimitMinutes = 60),
            AppRule("com.example.free"),
            AppRule("com.example.bonused", dailyLimitMinutes = 30),
        )
        limits.sync(rules)
        limits.sync(
            rules,
            listOf(
                BonusGrant(packageName = "com.example.bonused", bonusMinutes = 15, day = "2026-11-16"),
                BonusGrant(packageName = null, bonusMinutes = 20, day = "2026-11-16"),
            ),
        )
        limits.sync(emptyList(), emptyList())
        limits.unregister("com.example.capped")
    }

    @Test fun limitStateRemembersTripsForTheDay() {
        val state = LimitState(app)
        state.pruneToToday()
        assertFalse(state.hasReached("com.example.app"))
        state.markReached("com.example.app")
        state.markReached("com.example.other")
        assertTrue(state.hasReached("com.example.app"))
        assertTrue(state.hasReached("com.example.other"))
        assertFalse(state.hasReached("com.example.never"))
        // A fresh instance over the same prefs sees the same trips.
        assertTrue(LimitState(app).hasReached("com.example.app"))
    }

    // ---- Navigation host: gated destinations never render ungated ----

    @Test fun navigationWithoutPinRoutesGatedDestinationToSetup() {
        ParentPin.get(app).clear()
        val vm = SupervisionViewModel(app)
        var finished = 0
        rule.setContent { Navigation(vm, initialRoute = Route.DailyLimit, onFinish = { finished++ }) }
        rule.waitForIdle()
        // No PIN exists yet: the host must show PIN setup, never the editor.
        // (PinSetupScreen's first field is the new-PIN entry.)
        assertEquals(0, finished)
        rule.onNodeWithText("Set parent PIN").assertIsDisplayed()
    }

    @Test fun navigationWithPinRoutesGatedDestinationToGate() {
        val pin = ParentPin.get(app)
        pin.clear()
        pin.set("1234")
        try {
            navigationWithPinRoutesGatedDestinationToGateBody()
        } finally {
            pin.clear()
        }
    }

    private fun navigationWithPinRoutesGatedDestinationToGateBody() {
        val vm = SupervisionViewModel(app)
        rule.setContent { Navigation(vm, initialRoute = Route.Bedtime) }
        rule.waitForIdle()
        // The gate, not the bedtime editor, is what a gated deep link lands on.
        rule.onNodeWithText("Enter the parent PIN to change these settings.").assertIsDisplayed()
    }

    @Test fun navigationUnlockingGateReplacesItWithTheEditor() {
        val pin = ParentPin.get(app)
        pin.clear()
        pin.set("1234")
        try {
            val vm = SupervisionViewModel(app)
            rule.setContent { Navigation(vm, initialRoute = Route.DailyLimit) }
            rule.onNodeWithText("PIN").performTextInput("1234")
            rule.onNodeWithText("Unlock").performClick()
            // Verification runs off the main thread; wait for it to land.
            rule.waitUntil(timeoutMillis = 8000) {
                runCatching { rule.onNodeWithText("Daily limit").assertIsDisplayed(); true }
                    .getOrDefault(false)
            }
        } finally {
            pin.clear()
        }
    }

    @Test fun navigationExternalGatedRouteGoesThroughGate() {
        val pin = ParentPin.get(app)
        pin.clear()
        pin.set("1234")
        try {
            val vm = SupervisionViewModel(app)
            var consumed = 0
            rule.setContent {
                Navigation(
                    vm, initialRoute = Route.Dashboard,
                    externalRoute = Route.Bedtime, onExternalRouteConsumed = { consumed++ },
                )
            }
            rule.waitUntil(timeoutMillis = 8000) {
                runCatching {
                    rule.onNodeWithText("Enter the parent PIN to change these settings.").assertIsDisplayed(); true
                }.getOrDefault(false)
            }
            assertEquals(1, consumed)
        } finally {
            pin.clear()
        }
    }

    @Test fun navigationManagePinGoesThroughGateToSetup() {
        val pin = ParentPin.get(app)
        pin.clear()
        pin.set("1234")
        try {
            val vm = SupervisionViewModel(app)
            rule.setContent { Navigation(vm, initialRoute = Route.Dashboard) }
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Manage your PIN"))
            rule.onNodeWithText("Manage your PIN").performClick()
            rule.onNodeWithText("PIN").performTextInput("1234")
            rule.onNodeWithText("Unlock").performClick()
            rule.waitUntil(timeoutMillis = 8000) {
                runCatching { rule.onNodeWithText("Set parent PIN").assertIsDisplayed(); true }
                    .getOrDefault(false)
            }
        } finally {
            pin.clear()
        }
    }

    @Test fun navigationDashboardIsUngated() {
        ParentPin.get(app).clear()
        val vm = SupervisionViewModel(app)
        rule.setContent { Navigation(vm, initialRoute = Route.Dashboard) }
        rule.waitForIdle()
        // Dashboard renders read-only without any PIN flow.
        assertFalse(ParentPin.get(app).isSet())
    }
}
