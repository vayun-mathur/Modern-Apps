package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicalSwipeDeleteTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setup() {
        synchronized(databases) {
            if (databases[HealthDatabase::class] == null) {
                databases[HealthDatabase::class] = Room
                    .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
        }
    }

    @Test
    fun `swiping a vaccination row and confirming deletes it`() = runTest {
        val repo = HealthRepository.get(context)
        repo.upsertVaccination(VaccinationEntry(id = "swipe-vac-1", displayName = "SwipeDelete Flu", occurredAt = Instant.parse("2024-05-05T08:00:00Z")))
        val vm = MedicalViewModel(context, repo)
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                MedicalPage(backStack = backStack, viewModel = vm)
            }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Vaccinations"))
        rule.onNodeWithText("Vaccinations").performClick()
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("SwipeDelete Flu"))
        rule.onNodeWithText("SwipeDelete Flu").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        // Confirm dialog names the record; Delete is its confirm action.
        rule.onAllNodesWithText("Delete", substring = false).fetchSemanticsNodes().let { rule.onAllNodesWithText("Delete", substring = false)[it.size - 1].performClick() }
        rule.waitForIdle()
        var deleted = false
        repeat(100) {
            if (repo.getVaccination("swipe-vac-1") == null) { deleted = true; return@repeat }
            Thread.sleep(10)
            rule.waitForIdle()
        }
        assertTrue(deleted)
        assertNull(repo.getVaccination("swipe-vac-1"))
    }

    @Test
    fun `swipe delete can be dismissed without deleting`() = runTest {
        val repo = HealthRepository.get(context)
        repo.upsertVaccination(VaccinationEntry(id = "swipe-vac-2", displayName = "SwipeKeep Flu", occurredAt = Instant.parse("2024-05-05T08:00:00Z")))
        val vm = MedicalViewModel(context, repo)
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                MedicalPage(backStack = backStack, viewModel = vm)
            }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Vaccinations"))
        rule.onNodeWithText("Vaccinations").performClick()
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("SwipeKeep Flu"))
        rule.onNodeWithText("SwipeKeep Flu").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onAllNodesWithText("Cancel", substring = false).fetchSemanticsNodes().let { nodes ->
            if (nodes.isNotEmpty()) rule.onAllNodesWithText("Cancel", substring = false)[0].performClick()
            else rule.onNodeWithText("Delete the record", substring = true).assertExists()
        }
        rule.waitForIdle()
        assertTrue(repo.getVaccination("swipe-vac-2") != null)
    }
}
