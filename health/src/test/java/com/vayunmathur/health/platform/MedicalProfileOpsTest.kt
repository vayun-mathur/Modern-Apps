package com.vayunmathur.health.platform

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthProfile
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.PregnancyStatus
import com.vayunmathur.health.data.SmokingStatus
import com.vayunmathur.health.domain.SocialHistoryQuestions
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for the standing-profile operations ([MedicalProfileOps.kt]):
 * pregnancy/smoking status writes and social-history answers.
 *
 * Health Connect is unavailable under Robolectric, so the mirror half stays
 * local — the assertions pin the Room write (status, timestamps, answer
 * rows) plus the update-reuses-FHIR-id contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicalProfileOpsTest {

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

    private fun repository(): HealthRepository = HealthRepository.get(context)
    private fun viewModel(): MedicalViewModel = MedicalViewModel(context, repository())

    private suspend fun settle(cond: suspend () -> Boolean) {
        repeat(200) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            delay(5)
        }
    }

    @Test
    fun `pregnancy status write dates the observation now and mints one fhir id`() = runTest {
        val repo = repository()
        val before = repo.getProfile()
        val vm = viewModel()
        vm.setPregnancyStatus(PregnancyStatus.Pregnant, null)
        settle { repo.getProfile()?.pregnancyRecordedAt != before?.pregnancyRecordedAt }
        val profile = repo.getProfile()
        assertNotNull(profile)
        assertEquals(PregnancyStatus.Pregnant, profile.pregnancyStatus)
        assertNotNull(profile.pregnancyRecordedAt)
        assertNotNull(profile.pregnancyFhirId)

        // A second write reuses the FHIR id: one standing observation, not a log.
        val firstId = profile.pregnancyFhirId
        vm.setPregnancyStatus(PregnancyStatus.NotPregnant, null)
        settle { repo.getProfile()?.pregnancyStatus == PregnancyStatus.NotPregnant }
        assertEquals(firstId, repo.getProfile()?.pregnancyFhirId)
    }

    @Test
    fun `pregnancy status carries the due date`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val due = LocalDate(2026, 9, 1)
        vm.setPregnancyStatus(PregnancyStatus.Pregnant, due)
        settle { repo.getProfile()?.dueDate != null }
        val stored = repo.getProfile()
        assertNotNull(stored?.dueDate)
        assertEquals(due, stored.dueDate?.toLocalDate())
    }

    @Test
    fun `smoking status write dates the observation now`() = runTest {
        val repo = repository()
        val before = repo.getProfile()
        val vm = viewModel()
        vm.setSmokingStatus(SmokingStatus.Former)
        settle { repo.getProfile()?.smokingRecordedAt != before?.smokingRecordedAt }
        val profile = repo.getProfile()
        assertNotNull(profile)
        assertEquals(SmokingStatus.Former, profile.smokingStatus)
        assertNotNull(profile.smokingRecordedAt)
        assertNotNull(profile.smokingFhirId)
    }

    @Test
    fun `blank singleton profile has id profile`() = runTest {
        val repo = repository()
        val flowValue = repo.getProfileFlow().first()
        // Before any write there is no row; the ViewModel maps that to the blank singleton.
        if (flowValue == null) {
            assertEquals(HealthProfile().id, "profile")
        } else {
            assertEquals("profile", flowValue.id)
        }
    }

    @Test
    fun `social history answer is stored under the question loinc`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val question = SocialHistoryQuestions.ALCOHOL
        val answer = question.answers.first()
        vm.setSocialHistoryAnswer(question, answer)
        settle { repo.getProfileAnswer(question.loinc) != null }
        val row = repo.getProfileAnswer(question.loinc)
        assertNotNull(row)
        assertEquals(answer.code, row.answerCode)
        assertNotNull(row.fhirResourceId)
    }

    @Test
    fun `answering twice reuses the fhir id`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val question = SocialHistoryQuestions.HOUSING
        vm.setSocialHistoryAnswer(question, question.answers.first())
        settle { repo.getProfileAnswer(question.loinc) != null }
        val firstId = repo.getProfileAnswer(question.loinc)?.fhirResourceId
        vm.setSocialHistoryAnswer(question, question.answers.last())
        settle { repo.getProfileAnswer(question.loinc)?.answerCode == question.answers.last().code }
        val row = repo.getProfileAnswer(question.loinc)
        assertNotNull(row)
        assertEquals(question.answers.last().code, row.answerCode)
        assertEquals(firstId, row.fhirResourceId)
    }

    @Test
    fun `clearing an answer removes the row`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val question = SocialHistoryQuestions.TRANSPORT
        vm.setSocialHistoryAnswer(question, question.answers.first())
        settle { repo.getProfileAnswer(question.loinc) != null }
        vm.clearSocialHistoryAnswer(question)
        settle { repo.getProfileAnswer(question.loinc) == null }
        assertNull(repo.getProfileAnswer(question.loinc))
    }

    @Test
    fun `profile answers flow maps rows by loinc`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val question = SocialHistoryQuestions.DRUGS
        vm.setSocialHistoryAnswer(question, question.answers.first())
        settle { repo.getProfileAnswer(question.loinc) != null }
        val mapped = repo.getProfileAnswersFlow().first().associateBy { it.loincCode }
        assertTrue(mapped.containsKey(question.loinc))
    }
}
