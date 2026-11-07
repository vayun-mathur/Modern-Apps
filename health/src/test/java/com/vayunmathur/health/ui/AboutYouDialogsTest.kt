package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.PregnancyStatus
import com.vayunmathur.health.data.SmokingStatus
import com.vayunmathur.health.domain.SocialHistoryQuestions
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutYouDialogsTest {

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
        kotlinx.coroutines.runBlocking { HealthRepository.get(context).getLastRecord(com.vayunmathur.health.data.RecordType.Steps) }
    }

    private fun medicalVm(): MedicalViewModel = MedicalViewModel(context, HealthRepository.get(context))

    private fun section(vm: MedicalViewModel) {
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                MedicalAboutYouSection(backStack, vm, isExpanded = true, onToggle = {})
            }
        }
        rule.waitForIdle()
    }

    private fun settleProfile(vm: MedicalViewModel, cond: () -> Boolean) {
        repeat(100) {
            rule.waitForIdle()
            if (cond()) return
            Thread.sleep(10)
        }
    }

    @Test
    fun `smoking dialog pick writes smoking status`() = runTest {
        val vm = medicalVm()
        section(vm)
        rule.onNodeWithText("Do you smoke?").performClick()
        rule.waitForIdle()
        // Option labels come from SmokingStatus.labelRes; pick the last option.
        val label = context.getString(SmokingStatus.entries.last().labelRes())
        rule.onNodeWithText(label).performClick()
        settleProfile(vm) { vm.profile.value.smokingStatus == SmokingStatus.entries.last() }
        assertEquals(SmokingStatus.entries.last(), HealthRepository.get(context).getProfile()?.smokingStatus)
    }

    @Test
    fun `pregnancy dialog pick writes pregnancy status`() = runTest {
        val vm = medicalVm()
        section(vm)
        rule.onNodeWithText("Are you pregnant?").performClick()
        rule.waitForIdle()
        val label = context.getString(PregnancyStatus.Pregnant.labelRes())
        rule.onNodeWithText(label).performClick()
        settleProfile(vm) { vm.profile.value.pregnancyStatus == PregnancyStatus.Pregnant }
        assertEquals(PregnancyStatus.Pregnant, HealthRepository.get(context).getProfile()?.pregnancyStatus)
    }

    @Test
    fun `social history question dialog stores an answer`() = runTest {
        val vm = medicalVm()
        section(vm)
        val question = SocialHistoryQuestions.ALL.first()
        val title = context.getString(question.titleRes)
        rule.onNodeWithText(title).performClick()
        rule.waitForIdle()
        val answer = question.answers.first()
        rule.onNodeWithText(context.getString(answer.labelRes)).performClick()
        var stored = false
        repeat(100) {
            if (HealthRepository.get(context).getProfileAnswer(question.loinc)?.answerCode == answer.code) { stored = true; return@repeat }
            Thread.sleep(10)
            rule.waitForIdle()
        }
        assertTrue(stored)
        assertNotNull(HealthRepository.get(context).getProfileAnswersFlow().first().firstOrNull { it.loincCode == question.loinc })
    }
}
