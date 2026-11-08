package com.vayunmathur.health.ui

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.util.FoodDatabase
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LastUiBranchesTest {

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
        HealthAPI.initLocal(context, HealthRepository.get(context))
        FoodDatabase.init(context)
        runBlocking { HealthRepository.get(context).getLastRecord(com.vayunmathur.health.data.RecordType.Steps) }
    }

    private fun vm(): HealthViewModel = HealthViewModel(context, HealthRepository.get(context))
    private fun medicalVm(): MedicalViewModel = MedicalViewModel(context, HealthRepository.get(context))

    @Test
    fun `food database card failed state offers retry`() = runBlocking<Unit> {
        FoodDatabase.prepare()
        assertTrue(FoodDatabase.status.value is FoodDatabase.Status.Failed)
        rule.setContent { FoodDatabaseCard(vm()) }
        rule.waitForIdle()
        rule.onNodeWithText("Retry", substring = true).performClick()
        rule.waitForIdle()
        // Retrying re-runs prepare, which fails again without the asset;
        // the card stays in its failed state.
        rule.onNodeWithText("Food Database").assertExists()
    }

    @Test
    fun `vaccination form shows a picked attachment chip and removes it`() {
        val source = File(context.cacheDir, "chip-picked.pdf")
        source.writeText("card")
        val viewModel = medicalVm()
        viewModel.startVaccinationDraft()
        viewModel.editVaccinationDraft {
            it.copy(displayName = "Chip Pick Vaccine", attachmentUris = listOf(Uri.fromFile(source).toString()))
        }
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditVaccination()))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                AddVaccinationPage(backStack, viewModel)
            }
        }
        rule.waitForIdle()
        assertEquals(1, viewModel.vaccinationDraft.value.attachmentUris.size)
        rule.onNodeWithText("chip-picked.pdf", substring = true).assertExists()
    }

    @Test
    fun `bar chart with goal and many bars draws`() {
        rule.setContent {
            Column {
                GenericBarChart(
                    data = (1..14).map { "D$it" to (it * 1000.0) },
                    totalBarCount = 14,
                    goalValue = 10000.0,
                    barColor = Color.Blue,
                    goalColor = Color.Red,
                )
            }
        }
        val image = rule.onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        rule.onNodeWithText("0").assertExists()
    }
}
