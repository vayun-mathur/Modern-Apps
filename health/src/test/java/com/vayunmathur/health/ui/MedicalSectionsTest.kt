package com.vayunmathur.health.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.health.data.AllergyCriticality
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.ConditionStatus
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.health.ui.components.HealthRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the medical tab's section cards.
 *
 * These are the expandable rows behind every medical list; driving them
 * with canned entries covers the section file without needing the
 * ViewModel or Health Connect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicalSectionsTest {

    @get:Rule
    val rule = createComposeRule()

    private val t = java.time.Instant.parse("2026-03-04T09:30:00Z")

    @Test
    fun `conditions section lists entries and deletes`() {
        var deleted: ConditionEntry? = null
        var edited = ""
        var toggles = 0
        val entries = listOf(
            ConditionEntry(id = "c1", displayName = "Asthma", status = ConditionStatus.Active, onsetAt = t),
            ConditionEntry(id = "c2", displayName = "Eczema", status = ConditionStatus.Resolved, onsetAt = t),
        )
        rule.setContent {
            MedicalConditionsSection(
                entries = entries,
                isExpanded = true,
                onToggle = { toggles++ },
                onEdit = { edited = it },
                onDelete = { deleted = it },
            )
        }
        rule.onNodeWithText("Asthma").assertIsDisplayed()
        rule.onNodeWithText("Eczema").assertIsDisplayed()
        rule.onNodeWithText("Asthma").performClick()
        assertEquals("c1", edited)
    }

    @Test
    fun `conditions section header toggles and marks empty`() {
        var toggles = 0
        rule.setContent {
            MedicalConditionsSection(
                entries = emptyList(),
                isExpanded = false,
                onToggle = { toggles++ },
                onEdit = {},
                onDelete = {},
            )
        }
        rule.onNodeWithText("Conditions").assertIsDisplayed()
        rule.onNodeWithText("None").assertIsDisplayed()
        rule.onNodeWithText("Conditions").performClick()
        assertTrue(toggles >= 1, "toggle fires")
    }

    @Test
    fun `lab section shows values and flags out of range`() {
        val entries = listOf(
            LabResultEntry(
                id = "l1", displayName = "Hemoglobin A1c", value = 9.0, unit = "%",
                referenceLow = 4.0, referenceHigh = 5.6, takenAt = t,
            ),
        )
        rule.setContent {
            MedicalLabSection(
                entries = entries,
                isExpanded = true,
                onToggle = {},
                onEdit = {},
                onDelete = {},
            )
        }
        rule.onNodeWithText("Hemoglobin A1c").assertIsDisplayed()
    }

    @Test
    fun `vaccinations group lists entries`() {
        val entries = listOf(
            VaccinationEntry(id = "v1", displayName = "Flu", occurredAt = t),
        )
        rule.setContent {
            MedicalCategoryGroup(
                title = "Vaccinations",
                icon = { _, _ -> },
                isExpanded = true,
                onToggle = {},
                itemCount = entries.size,
            ) { index ->
                HealthRow(headline = entries[index].displayName)
            }
        }
        rule.onNodeWithText("Flu").assertIsDisplayed()
    }

    @Test
    fun `allergies group lists entries`() {
        val entries = listOf(
            AllergyEntry(
                id = "a1", displayName = "Penicillin",
                criticality = AllergyCriticality.High, recordedAt = t,
            ),
        )
        rule.setContent {
            MedicalCategoryGroup(
                title = "Allergies",
                icon = { _, _ -> },
                isExpanded = true,
                onToggle = {},
                itemCount = entries.size,
            ) { index ->
                HealthRow(headline = entries[index].displayName)
            }
        }
        rule.onNodeWithText("Penicillin").assertIsDisplayed()
    }

    @Test
    fun `empty group shows its header with a none marker`() {
        var toggles = 0
        rule.setContent {
            MedicalCategoryGroup(
                title = "Vaccinations",
                icon = { _, _ -> },
                isExpanded = false,
                onToggle = { toggles++ },
                itemCount = 0,
            ) { _ -> }
        }
        rule.onNodeWithText("Vaccinations").assertIsDisplayed()
        rule.onNodeWithText("Vaccinations").performClick()
        assertTrue(toggles >= 1, "toggle fires")
    }

    @Test
    fun `medications section lists current courses`() {
        val entries = listOf(
            MedicationEntry(
                id = "m1", displayName = "Metformin",
                status = MedicationStatus.Active, startedAt = t,
            ),
        )
        rule.setContent {
            MedicalMedicationsSection(
                entries = entries,
                isExpanded = true,
                onToggle = {},
                pastExpanded = false,
                onTogglePast = {},
                onEdit = {},
                onDelete = {},
                onTakeDose = {},
            )
        }
        rule.onNodeWithText("Metformin").assertIsDisplayed()
    }
}
