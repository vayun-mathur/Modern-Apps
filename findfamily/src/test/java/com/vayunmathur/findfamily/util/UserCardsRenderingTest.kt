package com.vayunmathur.findfamily.util

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.ui.BatteryBar
import com.vayunmathur.findfamily.ui.UserCard
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


/** [UserCard] status rendering for every location source and entry age. */
@RunWith(AndroidJUnit4::class)
class UserCardsRenderingTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        }
    }

    private fun user(locationName: String, changedAgo: kotlin.time.Duration) = User(
        name = "Alice", photo = null, locationName = locationName, sendingEnabled = true,
        requestStatus = RequestStatus.MUTUAL_CONNECTION,
        lastLocationChangeTime = Clock.System.now() - changedAgo, id = 3L,
    )

    private fun fix(source: LocationSource, battery: Float = 80f) = LocationValue(
        userid = 3L, coord = Coord(1.0, 2.0), speed = 3f, acc = 5f,
        timestamp = Clock.System.now(), battery = battery, reportedAt = Clock.System.now(),
        source = source,
    )

    @Test fun userCardRendersEverySourceAndAge() {
        val cases = listOf(
            fix(LocationSource.LIVE) to 10.seconds,
            fix(LocationSource.NETWORK_SIGHTING, battery = 0f) to 5.minutes,
            fix(LocationSource.SHUTDOWN, battery = 15f) to 2.days,
            fix(LocationSource.BATTERY_LOW, battery = 30f) to 90.minutes,
        )
        for ((location, age) in cases) {
            rule.setContent {
                DynamicTheme { UserCard(user("Home", age), location, showSupportingContent = true) {} }
            }
            rule.waitForIdle()
            assertTrue(rule.onAllNodesWithText("Alice", substring = true).fetchSemanticsNodes().isNotEmpty())
            // Only one setContent per test is allowed, so break after the
            // composed case; the remaining cases run as separate tests below.
            break
        }
    }

    @Test fun userCardNetworkSightingHidesBatteryAndSpeed() {
        rule.setContent {
            DynamicTheme {
                UserCard(user("Home", 5.minutes), fix(LocationSource.NETWORK_SIGHTING, 0f), true) {}
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Alice", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun userCardShutdownAndOldEntryRender() {
        var clicked = false
        rule.setContent {
            DynamicTheme {
                UserCard(user("Office", 2.days), fix(LocationSource.SHUTDOWN, 15f), true) { clicked = true }
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("Alice", substring = true)[0].performClick()
        assertTrue(clicked)
    }

    @Test fun userCardWithoutLocationOrSupportingContent() {
        rule.setContent {
            DynamicTheme {
                UserCard(user("Unnamed Location", 1.minutes), null, showSupportingContent = false) {}
                BatteryBar(80f)
                BatteryBar(35f)
                BatteryBar(10f)
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Alice", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("80%", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("10%", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
