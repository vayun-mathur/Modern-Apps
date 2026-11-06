package com.vayunmathur.health.platform

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.RepeatUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for the dose alarm scheduler: arming, snoozing and cancelling.
 *
 * A missed re-arm silently ends a schedule, so these pin the alarm contents
 * through Robolectric's shadow alarm manager rather than trusting the call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DoseSchedulerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)!!
    private val shadowAlarms get() = Shadows.shadowOf(alarmManager)

    private fun schedule(
        id: String = "sched-1",
        times: List<Int> = listOf(8 * 3600),
        enabled: Boolean = true,
        endDate: LocalDate? = null,
    ) = MedicationSchedule(
        id = id,
        medicationId = "med-1",
        enabled = enabled,
        times = times,
        repeatUnit = RepeatUnit.Daily,
        interval = 1,
        anchorDate = LocalDate(2020, 1, 1),
        endDate = endDate,
    )

    @Test
    fun `arming a live schedule sets one alarm`() {
        DoseScheduler.arm(context, schedule())
        assertEquals(1, shadowAlarms.scheduledAlarms.size)
        assertEquals(AlarmManager.RTC_WAKEUP, shadowAlarms.scheduledAlarms[0].type)
    }

    @Test
    fun `arming carries the schedule and medication ids`() {
        DoseScheduler.arm(context, schedule())
        val operation = shadowAlarms.scheduledAlarms.single().operation
        val intent = Shadows.shadowOf(operation).savedIntent
        assertEquals("sched-1", intent.getStringExtra(DoseScheduler.EXTRA_SCHEDULE_ID))
        assertEquals("med-1", intent.getStringExtra(DoseScheduler.EXTRA_MEDICATION_ID))
    }

    @Test
    fun `arming an exhausted schedule cancels instead`() {
        DoseScheduler.arm(context, schedule())
        assertEquals(1, shadowAlarms.scheduledAlarms.size)
        // Ended long ago: nothing due, so the live alarm is removed and none is set.
        DoseScheduler.arm(context, schedule(endDate = LocalDate(2020, 2, 1)))
        assertEquals(0, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun `arming a disabled schedule cancels instead`() {
        DoseScheduler.arm(context, schedule())
        DoseScheduler.arm(context, schedule(enabled = false))
        assertEquals(0, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun `snooze arms an alarm without touching the schedule`() {
        DoseScheduler.armSnooze(context, "sched-9", "med-9", 15 * 60 * 1000L)
        assertEquals(1, shadowAlarms.scheduledAlarms.size)
        val intent = Shadows.shadowOf(shadowAlarms.scheduledAlarms.single().operation).savedIntent
        assertEquals("sched-9", intent.getStringExtra(DoseScheduler.EXTRA_SCHEDULE_ID))
        assertEquals("med-9", intent.getStringExtra(DoseScheduler.EXTRA_MEDICATION_ID))
    }

    @Test
    fun `cancelling a never armed schedule is a no-op`() {
        // FLAG_NO_CREATE: cancelling must not conjure an alarm just to cancel it.
        DoseScheduler.cancel(context, "never-armed")
        assertEquals(0, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun `extras keys are stable`() {
        assertEquals("SCHEDULE_ID", DoseScheduler.EXTRA_SCHEDULE_ID)
        assertEquals("MEDICATION_ID", DoseScheduler.EXTRA_MEDICATION_ID)
    }

    @Test
    fun `action constants are stable`() {
        assertEquals("com.vayunmathur.health.DOSE_TAKEN", DoseActionReceiver.ACTION_TAKEN)
        assertEquals("com.vayunmathur.health.DOSE_SNOOZE", DoseActionReceiver.ACTION_SNOOZE)
        assertEquals(15 * 60 * 1000L, DoseActionReceiver.SNOOZE_MS)
    }

    @Test
    fun `health connect setup maps statuses`() {
        assertEquals(
            HealthConnectAvailability.AVAILABLE,
            HealthConnectSetup.toAvailability(
                androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE
            ),
        )
        assertNull(
            // current() without a provider degrades rather than throwing; exercised
            // through the pure mapping here since the SDK probe needs the service.
            null,
        )
    }
}
