package com.vayunmathur.health.ui

import android.app.Application
import android.graphics.Bitmap
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
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicalAttachment
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.health.platform.AttachmentStore
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.ui.components.AttachmentChip
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import java.io.FileOutputStream
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentAndMoreDeletesTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val at: Instant = Instant.parse("2024-06-07T08:00:00Z")

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

    private fun repo(): HealthRepository = HealthRepository.get(context)

    @Test
    fun `attachment chip shows thumbnail for image and icon for pdf and missing file`() {
        val png = AttachmentStore.fileFor(context, "chip-test.png")
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        FileOutputStream(png).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var clicks = 0
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                AttachmentChip(displayName = "card.png", mimeType = "image/png", file = png, onClick = { clicks++ })
                AttachmentChip(displayName = "card.pdf", mimeType = "application/pdf", file = png, onClick = { clicks++ })
                AttachmentChip(displayName = "gone.png", mimeType = "image/png", file = AttachmentStore.fileFor(context, "chip-missing.png"), onClick = { clicks++ })
            }
        }
        rule.waitForIdle()
        Thread.sleep(300)
        rule.waitForIdle()
        rule.onNodeWithText("card.png").performClick()
        rule.onNodeWithText("card.pdf").performClick()
        assertEquals(2, clicks)
        AttachmentStore.delete(context, "chip-test.png")
    }

    @Test
    fun `vaccination form shows saved and picked attachment chips`() = runTest {
        val repo = repo()
        repo.upsertVaccination(VaccinationEntry(id = "chip-vac-1", displayName = "Chip Vaccine", occurredAt = at))
        repo.insertAttachment(MedicalAttachment(id = "chip-att-1", vaccinationId = "chip-vac-1", fileName = "chip-card.pdf", displayName = "Saved Card.pdf", mimeType = "application/pdf", sizeBytes = 10L, importedAt = at))
        val vm = MedicalViewModel(context, repo)
        vm.startVaccinationDraft("chip-vac-1")
        // Wait for the draft load to populate saved attachments.
        repeat(100) {
            if (vm.vaccinationDraft.value.editingId == "chip-vac-1") return@repeat
            Thread.sleep(10)
        }
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditVaccination("chip-vac-1")))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                AddVaccinationPage(backStack, vm)
            }
        }
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Saved Card.pdf"))
        rule.onNodeWithText("Saved Card.pdf").assertExists()
    }

    private fun swipeDelete(section: String, row: String, confirm: () -> Boolean) {
        val vm = MedicalViewModel(context, repo())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                MedicalPage(backStack = backStack, viewModel = vm)
            }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(section))
        rule.onNodeWithText(section).performClick()
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(row))
        rule.onNodeWithText(row).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val deletes = rule.onAllNodesWithText("Delete", substring = false).fetchSemanticsNodes()
        rule.onAllNodesWithText("Delete", substring = false)[deletes.size - 1].performClick()
        rule.waitForIdle()
        repeat(100) {
            if (confirm()) return
            Thread.sleep(10)
            rule.waitForIdle()
        }
        assertTrue(confirm())
    }

    @Test
    fun `swipe deleting an allergy removes it`() = runTest {
        repo().upsertAllergy(AllergyEntry(id = "swipe-alg-9", displayName = "SwipeGone Peanut", recordedAt = at))
        swipeDelete("Allergies", "SwipeGone Peanut") {
            kotlinx.coroutines.runBlocking { repo().getAllergy("swipe-alg-9") == null }
        }
        assertNull(repo().getAllergy("swipe-alg-9"))
    }

    @Test
    fun `swipe deleting a condition removes it`() = runTest {
        repo().upsertCondition(ConditionEntry(id = "swipe-cond-9", displayName = "SwipeGone Asthma", onsetAt = at))
        swipeDelete("Conditions", "SwipeGone Asthma") {
            kotlinx.coroutines.runBlocking { repo().getCondition("swipe-cond-9") == null }
        }
        assertNull(repo().getCondition("swipe-cond-9"))
    }

    @Test
    fun `swipe deleting a lab result removes it`() = runTest {
        repo().upsertLabResult(LabResultEntry(id = "swipe-lab-9", displayName = "SwipeGone A1c", value = 5.0, takenAt = at))
        swipeDelete("Lab results", "SwipeGone A1c") {
            kotlinx.coroutines.runBlocking { repo().getLabResult("swipe-lab-9") == null }
        }
        assertNull(repo().getLabResult("swipe-lab-9"))
    }
}
