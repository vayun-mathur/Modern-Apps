package com.vayunmathur.clock.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.platform.WorldClockCities
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.runBlocking
import com.vayunmathur.testsupport.awaitValueBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ClockPage with real selected cities, and its add-city navigation. */
@RunWith(AndroidJUnit4::class)
class ClockPageDeepTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun vm() = ClockViewModel(ctx as Application, ClockRepository.get(ctx))
    private fun ds() = DataStoreUtils.getInstance(ctx)

    @Test fun clockPageShowsSelectedWorldClocksAndAddNavigates() {
        val vm = vm()
        // Terminal signal: the cities StateFlow emits its asset-parsed map.
        awaitValueBlocking(vm.cities) { it != null }
        val cities = vm.cities.value.orEmpty()
        assertTrue(cities.isNotEmpty())
        val picked = cities.keys.take(2)
        runBlocking { WorldClockCities.set(ds(), picked) }

        val backStack = NavBackStack(arrayOf<Route>(Route.Alarm))
        rule.setContent { ClockPage(backStack, ds(), vm) }
        rule.waitForIdle()
        for (city in picked) rule.onNodeWithText(city).assertExists()

        rule.onNodeWithContentDescription("Add")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(Route.SelectTimeZonesDialog, backStack.backStack.last())
        runBlocking { WorldClockCities.set(ds(), emptyList()) }
    }
}
