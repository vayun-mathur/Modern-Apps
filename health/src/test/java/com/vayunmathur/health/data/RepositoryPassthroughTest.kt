package com.vayunmathur.health.data

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryPassthroughTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val at: Instant = Instant.parse("2024-04-01T08:00:00Z")

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
    fun `vaccination find and delete through repository`() = runTest {
        val repo = repo()
        val entry = VaccinationEntry(id = "repo-vac-1", displayName = "Repo Flu", occurredAt = at, fhirResourceId = "fhir-vac-1", dataSourceId = "src-1")
        repo.upsertVaccination(entry)
        assertEquals("Repo Flu", repo.getVaccination("repo-vac-1")?.displayName)
        assertEquals("repo-vac-1", repo.findVaccination("src-1", "fhir-vac-1")?.id)
        assertNull(repo.findVaccination("src-1", "nope"))
        assertTrue(repo.getVaccinationsFlow().first().any { it.id == "repo-vac-1" })
        repo.deleteVaccination(entry)
        assertNull(repo.getVaccination("repo-vac-1"))
    }

    @Test
    fun `medication find by source and by fhir id`() = runTest {
        val repo = repo()
        val entry = MedicationEntry(id = "repo-med-1", displayName = "Repo Med", startedAt = at, fhirResourceId = "fhir-med-1", dataSourceId = "src-1")
        repo.upsertMedication(entry)
        assertEquals("repo-med-1", repo.findMedication("src-1", "fhir-med-1")?.id)
        assertEquals(1, repo.findMedicationsByFhirId("fhir-med-1").count { it.id == "repo-med-1" })
        assertTrue(repo.getMedicationsFlow().first().any { it.id == "repo-med-1" })
        repo.deleteMedication(entry)
        assertNull(repo.getMedication("repo-med-1"))
    }

    @Test
    fun `attachments link to vaccination and delete`() = runTest {
        val repo = repo()
        val attachment = MedicalAttachment(id = "repo-att-1", vaccinationId = "repo-vac-1", fileName = "card.pdf", displayName = "Card", mimeType = "application/pdf", sizeBytes = 123L, importedAt = at)
        repo.insertAttachment(attachment)
        assertEquals(listOf("repo-att-1"), repo.getAttachmentsFor("repo-vac-1").map { it.id })
        assertTrue(repo.getAttachmentsFlow().first().any { it.id == "repo-att-1" })
        repo.deleteAttachment(attachment)
        assertTrue(repo.getAttachmentsFor("repo-vac-1").isEmpty())
    }

    @Test
    fun `allergy condition and lab finders`() = runTest {
        val repo = repo()
        repo.upsertAllergy(AllergyEntry(id = "repo-alg-1", displayName = "Repo Peanut", recordedAt = at, fhirResourceId = "fhir-alg-1", dataSourceId = "src-1"))
        repo.upsertCondition(ConditionEntry(id = "repo-cond-1", displayName = "Repo Asthma", onsetAt = at, fhirResourceId = "fhir-cond-1", dataSourceId = "src-1"))
        repo.upsertLabResult(LabResultEntry(id = "repo-lab-1", displayName = "Repo A1c", value = 5.4, unit = "%", takenAt = at, fhirResourceId = "fhir-lab-1", dataSourceId = "src-1"))
        assertEquals("repo-alg-1", repo.findAllergy("src-1", "fhir-alg-1")?.id)
        assertEquals("repo-cond-1", repo.findCondition("src-1", "fhir-cond-1")?.id)
        assertEquals("repo-lab-1", repo.findLabResult("src-1", "fhir-lab-1")?.id)
        assertTrue(repo.getAllergiesFlow().first().any { it.id == "repo-alg-1" })
        assertTrue(repo.getConditionsFlow().first().any { it.id == "repo-cond-1" })
        assertTrue(repo.getLabResultsFlow().first().any { it.id == "repo-lab-1" })
        repo.deleteAllergy(repo.getAllergy("repo-alg-1")!!)
        repo.deleteCondition(repo.getCondition("repo-cond-1")!!)
        repo.deleteLabResult(repo.getLabResult("repo-lab-1")!!)
        assertNull(repo.getAllergy("repo-alg-1"))
        assertNull(repo.getCondition("repo-cond-1"))
        assertNull(repo.getLabResult("repo-lab-1"))
    }

    @Test
    fun `profile and profile answers round trip`() = runTest {
        val repo = repo()
        repo.upsertProfile(HealthProfile(pregnancyStatus = PregnancyStatus.Pregnant, smokingStatus = SmokingStatus.Former))
        assertEquals(PregnancyStatus.Pregnant, repo.getProfile()?.pregnancyStatus)
        assertEquals(SmokingStatus.Former, repo.getProfileFlow().first()?.smokingStatus)
        repo.upsertProfileAnswer(ProfileAnswer(loincCode = "repo-loinc-1", answerCode = "repo-code-1", recordedAt = at))
        assertEquals("repo-code-1", repo.getProfileAnswer("repo-loinc-1")?.answerCode)
        assertTrue(repo.getProfileAnswersFlow().first().any { it.loincCode == "repo-loinc-1" })
        repo.deleteProfileAnswer("repo-loinc-1")
        assertNull(repo.getProfileAnswer("repo-loinc-1"))
    }

    @Test
    fun `dose events recent and per medication`() = runTest {
        val repo = repo()
        val event = DoseEvent(id = "repo-dose-1", medicationId = "repo-med-1", takenAt = at, fhirResourceId = "fhir-dose-1", dataSourceId = "src-1")
        repo.upsertDoseEvent(event)
        assertEquals("repo-dose-1", repo.getDoseEvent("repo-dose-1")?.id)
        assertEquals("repo-dose-1", repo.findDoseEvent("src-1", "fhir-dose-1")?.id)
        assertTrue(repo.getDosesFor("repo-med-1").any { it.id == "repo-dose-1" })
        assertTrue(repo.getRecentDoses("repo-med-1", 5).any { it.id == "repo-dose-1" })
        assertTrue(repo.getDoseEventsFlow().first().any { it.id == "repo-dose-1" })
        repo.deleteDoseEvent(event)
        assertNull(repo.getDoseEvent("repo-dose-1"))
        repo.upsertDoseEvent(event)
        repo.deleteDosesFor("repo-med-1")
        assertTrue(repo.getDosesFor("repo-med-1").none { it.id == "repo-dose-1" })
    }

    @Test
    fun `schedule upsert get enabled and delete`() = runTest {
        val repo = repo()
        val schedule = MedicationSchedule(id = "repo-sched-1", medicationId = "repo-med-1", enabled = true, anchorDate = kotlinx.datetime.LocalDate(2024, 4, 1))
        repo.upsertSchedule(schedule)
        assertNotNull(repo.getSchedule("repo-sched-1"))
        assertEquals("repo-sched-1", repo.getScheduleFor("repo-med-1")?.id)
        assertTrue(repo.getEnabledSchedules().any { it.id == "repo-sched-1" })
        assertTrue(repo.getSchedulesFlow().first().any { it.id == "repo-sched-1" })
        repo.deleteScheduleFor("repo-med-1")
        assertNull(repo.getScheduleFor("repo-med-1"))
    }

    @Test
    fun `tombstones and pending mirrors`() = runTest {
        val repo = repo()
        val tombstone = DeletedMedicalResource(kind = MedicalKind.Allergy, dataSourceId = "src-1", fhirResourceId = "fhir-tomb-1", fhirResourceType = 1, retryDelete = true)
        repo.upsertTombstone(tombstone)
        assertTrue(repo.isTombstoned(MedicalKind.Allergy, "src-1", "fhir-tomb-1"))
        assertTrue(!repo.isTombstoned(MedicalKind.Allergy, "src-1", "other"))
        assertTrue(repo.getPendingRemoteDeletes("src-1").any { it.fhirResourceId == "fhir-tomb-1" && it.retryDelete })
        repo.markPending(MedicalKind.Medication, "repo-med-1")
        assertTrue(repo.getPendingMirrors().any { it.kind == MedicalKind.Medication && it.rowId == "repo-med-1" })
        repo.clearPending(MedicalKind.Medication, "repo-med-1")
        assertTrue(repo.getPendingMirrors().none { it.rowId == "repo-med-1" })
    }

    @Test
    fun `ingredient recipe and serving unit passthroughs`() = runTest {
        val repo = repo()
        val ingredient = Ingredient(id = "repo-ing-1", originalName = "Repo Oats", nutritionData = NutritionData(calories = 100.0), isRecipe = true)
        repo.insertIngredient(ingredient)
        assertEquals("Repo Oats", repo.getIngredient("repo-ing-1")?.originalName)
        assertTrue(repo.searchIngredients("Repo Oats").any { it.id == "repo-ing-1" })
        assertTrue(repo.getAllIngredientsFlow().first().any { it.id == "repo-ing-1" })
        assertTrue(repo.getIngredientsAsRecipesFlow().first().any { it.id == "repo-ing-1" })
        repo.updateIngredient(ingredient.copy(customName = "Repo Oats Custom"))
        assertEquals("Repo Oats Custom", repo.getIngredient("repo-ing-1")?.displayName)

        val unit = ServingUnit(id = "repo-unit-1", ingredientId = "repo-ing-1", name = "Cup", grams = 240.0)
        repo.insertServingUnit(unit)
        assertEquals(listOf("repo-unit-1"), repo.getUnitsForIngredient("repo-ing-1").map { it.id })

        val recipe = Recipe(id = "repo-recipe-1", name = "Repo Porridge")
        repo.insertRecipe(recipe)
        repo.insertRecipeIngredient(RecipeIngredient(id = "repo-ri-1", recipeId = "repo-recipe-1", ingredientId = "repo-ing-1", quantity = 1.0, unitId = "repo-unit-1"))
        assertEquals(1, repo.getIngredientsForRecipe("repo-recipe-1").size)
        assertTrue(repo.getAllRecipesFlow().first().any { it.id == "repo-recipe-1" })
        repo.updateRecipe(recipe.copy(name = "Repo Porridge v2"))
        assertEquals("Repo Porridge v2", repo.getRecipe("repo-recipe-1")?.name)
        repo.deleteRecipeIngredient(repo.getIngredientsForRecipe("repo-recipe-1").first())
        assertTrue(repo.getIngredientsForRecipe("repo-recipe-1").isEmpty())
        repo.deleteRecipe(recipe)
        assertNull(repo.getRecipe("repo-recipe-1"))
        repo.deleteServingUnit(unit)
        assertTrue(repo.getUnitsForIngredient("repo-ing-1").isEmpty())
        repo.deleteIngredient(ingredient)
        assertNull(repo.getIngredient("repo-ing-1"))
    }
}
