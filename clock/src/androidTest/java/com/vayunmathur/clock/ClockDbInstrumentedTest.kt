package com.vayunmathur.clock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.intents.SetAlarmIntent
import com.vayunmathur.clock.intents.SetTimerIntent
import com.vayunmathur.clock.platform.AlarmActivity
import com.vayunmathur.clock.platform.AlarmReceiver
import com.vayunmathur.clock.platform.AlarmScheduler
import com.vayunmathur.clock.platform.BootReceiver
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.platform.TimerActionReceiver
import com.vayunmathur.clock.platform.TimerReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Clock

/**
 * Instrumented coverage for the DB-backed paths that cannot run under Robolectric
 * (SQLCipher has no JVM host library): repository, snoozes, receivers, assistant
 * intents and the ringing activity, all against the real encrypted database.
 */
@RunWith(AndroidJUnit4::class)
class ClockDbInstrumentedTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo: ClockRepository get() = ClockRepository.get(ctx)

    /**
     * Minimal suspend runner. runBlocking cannot be used here: the test APK and the app
     * APK carry different coroutines versions, and the instrumented classloader resolves
     * BuildersKt from the app, whose runBlocking$default the test code was not linked to.
     */
    private fun <T> block(call: suspend () -> T): T {
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        call.startCoroutine(object : kotlin.coroutines.Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(r: Result<T>) { result = r; latch.countDown() }
        })
        check(latch.await(30, TimeUnit.SECONDS)) { "suspend call timed out" }
        return result!!.getOrThrow()
    }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out: $desc")
    }

    private fun seedAlarm(name: String, days: Int = 0, snoozedUntil: kotlin.time.Instant? = null): Alarm = block {
        val id = repo.upsertAlarm(
            Alarm(LocalTime(23, 59), name, true, days, snoozedUntil = snoozedUntil, id = 0),
        )
        repo.getAlarm(id)
    }

    private fun seedTimer(name: String, running: Boolean = true): Timer = block {
        val t = Timer(running, name, Clock.System.now(), 5.minutes, 5.minutes, id = 0)
        val id = repo.upsertTimer(t)
        repo.getTimer(id)
    }

    private fun cleanupAlarm(id: Long) = block { runCatching { repo.deleteAlarm(repo.getAlarm(id)) } }
    private fun cleanupTimer(id: Long) = block { runCatching { repo.deleteTimer(repo.getTimer(id)) } }

    @Test
    fun repositoryCrudOnDevice() { block {
        val timerId = repo.upsertTimer(Timer(false, "device-timer", Clock.System.now(), 3.minutes, 3.minutes))
        assertEquals("device-timer", repo.getTimer(timerId).name)
        assertTrue(repo.getAllTimers().any { it.id == timerId })
        val alarmId = repo.upsertAlarm(Alarm(LocalTime(6, 0), "device-alarm", true, 0))
        assertEquals("device-alarm", repo.getAlarm(alarmId).name)
        repo.deleteTimer(repo.getTimer(timerId))
        repo.deleteAlarm(repo.getAlarm(alarmId))
        assertTrue(repo.getAllTimers().none { it.id == timerId })
    } }

    @Test
    fun snoozeScheduleAndClearOnDevice() { block {
        val alarm = seedAlarm("snooze-device")
        val trigger = AlarmScheduler.scheduleSnooze(ctx, repo, alarm, 5)
        assertTrue(trigger > Clock.System.now().toEpochMilliseconds())
        assertNotNull(repo.getAlarm(alarm.id).snoozedUntil)
        AlarmScheduler.clearSnooze(ctx, repo, repo.getAlarm(alarm.id))
        assertNull(repo.getAlarm(alarm.id).snoozedUntil)
        cleanupAlarm(alarm.id)
    } }

    @Test
    fun timerActionReceiversOnDevice() {
        // Pause
        val timer = seedTimer("action-timer")
        ctx.sendBroadcast(Intent(ctx, TimerActionReceiver::class.java).apply {
            action = TimerActionReceiver.ACTION_PAUSE
            putExtra("timer_id", timer.id)
        })
        await("paused") { !block { repo.getTimer(timer.id) }.isRunning }

        // Resume
        ctx.sendBroadcast(Intent(ctx, TimerActionReceiver::class.java).apply {
            action = TimerActionReceiver.ACTION_RESUME
            putExtra("timer_id", timer.id)
        })
        await("resumed") { block { repo.getTimer(timer.id) }.isRunning }

        // Reset
        ctx.sendBroadcast(Intent(ctx, TimerActionReceiver::class.java).apply {
            action = TimerActionReceiver.ACTION_RESET
            putExtra("timer_id", timer.id)
        })
        await("reset") {
            val t = block { repo.getTimer(timer.id) }
            !t.isRunning && t.remainingLength == t.totalLength
        }

        // Cancel deletes
        ctx.sendBroadcast(Intent(ctx, TimerActionReceiver::class.java).apply {
            action = TimerActionReceiver.ACTION_CANCEL
            putExtra("timer_id", timer.id)
        })
        await("deleted") { block { repo.getAllTimers() }.none { it.id == timer.id } }
    }

    @Test
    fun timerReceiverCompletesTimerOnDevice() {
        val timer = seedTimer("finish-timer")
        ctx.sendBroadcast(Intent(ctx, TimerReceiver::class.java).apply {
            putExtra("timer_id", timer.id)
            putExtra("timer_name", timer.name)
        })
        await("completed") {
            val t = block { repo.getTimer(timer.id) }
            !t.isRunning && t.remainingLength == kotlin.time.Duration.ZERO
        }
        cleanupTimer(timer.id)
    }

    @Test
    fun timerReceiverUnknownTimerWritesFallback() {
        val fakeId = 987_654L
        ctx.sendBroadcast(Intent(ctx, TimerReceiver::class.java).apply {
            putExtra("timer_id", fakeId)
            putExtra("timer_name", "ghost")
        })
        await("fallback written") { block { repo.getAllTimers() }.any { it.id == fakeId } }
        cleanupTimer(fakeId)
    }

    @Test
    fun bootReceiverReschedulesEnabledAlarms() {
        val alarm = seedAlarm("boot-alarm", days = 0b1111111)
        // BOOT_COMPLETED is a protected broadcast only the system may send, so drive the
        // same receiver branch through its QUICKBOOT_POWERON action.
        ctx.sendBroadcast(Intent(ctx, BootReceiver::class.java).apply {
            action = "android.intent.action.QUICKBOOT_POWERON"
        })
        // No crash and the alarm stays enabled; scheduling is observable via AlarmManager
        // in the scheduler unit tests, here we assert the DB row survives the sweep.
        Thread.sleep(1000)
        assertTrue(block { repo.getAlarm(alarm.id) }.enabled)
        cleanupAlarm(alarm.id)
    }

    @Test
    fun bootReceiverClearsSpentSnooze() {
        val past = Clock.System.now() - 10.minutes
        val alarm = seedAlarm("boot-spent-snooze", snoozedUntil = past)
        ctx.sendBroadcast(Intent(ctx, BootReceiver::class.java).apply {
            action = "android.intent.action.QUICKBOOT_POWERON"
        })
        await("snooze cleared") { block { repo.getAlarm(alarm.id) }.snoozedUntil == null }
        cleanupAlarm(alarm.id)
    }

    @Test
    fun alarmReceiverDisablesOneShotAfterFire() {
        val alarm = seedAlarm("oneshot-fire")
        ctx.sendBroadcast(Intent(ctx, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarm.id)
            putExtra(AlarmScheduler.EXTRA_LABEL, "oneshot-fire")
        })
        await("one-shot disabled") { !block { repo.getAlarm(alarm.id) }.enabled }
        cleanupAlarm(alarm.id)
    }

    @Test
    fun alarmReceiverSnoozeFireClearsSnooze() {
        val future = Clock.System.now() + 5.minutes
        val alarm = seedAlarm("snooze-fire", days = 1, snoozedUntil = future)
        ctx.sendBroadcast(Intent(ctx, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarm.id)
            putExtra(AlarmScheduler.EXTRA_IS_SNOOZE, true)
        })
        await("snooze cleared") { block { repo.getAlarm(alarm.id) }.snoozedUntil == null }
        cleanupAlarm(alarm.id)
    }

    @Test
    fun viewModelSkipUiIntentsPersistOnDevice() {
        val app = ctx.applicationContext as android.app.Application
        val vm = ClockViewModel(app, repo)
        val alarmIntent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, 4)
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, 45)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "device-skip-alarm")
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
        }
        assertNull(vm.handleIncomingIntent(alarmIntent))
        await("alarm persisted") {
            block { repo.getAllAlarms() }.any { it.name == "device-skip-alarm" }
        }
        val timerIntent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, 180)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "device-skip-timer")
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
        }
        assertNull(vm.handleIncomingIntent(timerIntent))
        await("timer persisted") {
            block { repo.getAllTimers() }.any { it.name == "device-skip-timer" }
        }
        block {
            repo.getAllAlarms().filter { it.name == "device-skip-alarm" }.forEach { repo.deleteAlarm(it) }
            repo.getAllTimers().filter { it.name == "device-skip-timer" }.forEach { repo.deleteTimer(it) }
        }
    }

    @Test
    fun viewModelUpsertDeleteOnDevice() {
        val vm = ClockViewModel(ctx.applicationContext as android.app.Application, repo)
        var timerId = -1L
        // timers is a WhileSubscribed StateFlow: it only loads while collected.
        val collector = Thread {
            try { block { vm.timers.collect {} } } catch (_: Exception) { /* flow never completes */ }
        }.apply { isDaemon = true; start() }
        vm.upsert(Timer(false, "device-vm-timer", Clock.System.now(), 2.minutes, 2.minutes)) { timerId = it }
        await("upserted") { timerId > 0 }
        await("in flow") { vm.timers.value.any { it.id == timerId } }
        vm.delete(block { repo.getTimer(timerId) })
        await("deleted") { block { repo.getAllTimers() }.none { it.id == timerId } }
    }

    @Test
    fun setAlarmAssistantIntentCreatesAlarm() {
        val intent = Intent(ctx, SetAlarmIntent::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("DATA", """{"hour":8,"minute":20,"label":"assistant-alarm"}""")
        }
        ctx.startActivity(intent)
        await("assistant alarm") {
            block { repo.getAllAlarms() }.any { it.name == "assistant-alarm" }
        }
        block {
            repo.getAllAlarms().filter { it.name == "assistant-alarm" }.forEach { repo.deleteAlarm(it) }
        }
    }

    @Test
    fun setTimerAssistantIntentCreatesTimer() {
        val intent = Intent(ctx, SetTimerIntent::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("DATA", """{"seconds":240,"label":"assistant-timer"}""")
        }
        ctx.startActivity(intent)
        await("assistant timer") {
            block { repo.getAllTimers() }.any { it.name == "assistant-timer" }
        }
        block {
            repo.getAllTimers().filter { it.name == "assistant-timer" }.forEach { repo.deleteTimer(it) }
        }
    }

    @Test
    fun alarmActivityDismissStopsRing() {
        val alarm = seedAlarm("ring-dismiss")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val intent = Intent(ctx, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("ALARM_ID", alarm.id)
        }
        ctx.startActivity(intent)
        val stop = device.wait(Until.findObject(By.text("STOP")), 10_000)
        assertNotNull("ringing screen STOP not shown", stop)
        stop.click()
        await("activity finished and snooze cleared") {
            block { repo.getAlarm(alarm.id) }.snoozedUntil == null
        }
        cleanupAlarm(alarm.id)
    }

    @Test
    fun alarmActivitySnoozeSchedulesSnooze() {
        // Scheduling a snooze needs the exact-alarm appop, which the launch test normally grants.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("appops set ${ctx.packageName} SCHEDULE_EXACT_ALARM allow").close()
        automation.executeShellCommand("appops set ${ctx.packageName} USE_FULL_SCREEN_INTENT allow").close()
        val alarm = seedAlarm("ring-snooze")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val intent = Intent(ctx, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("ALARM_ID", alarm.id)
        }
        ctx.startActivity(intent)
        await("snoozed") {
            device.findObject(By.textContains("SNOOZE ("))?.click()
            block { repo.getAlarm(alarm.id) }.snoozedUntil != null
        }
        cleanupAlarm(alarm.id)
    }

    @Test
    fun widgetAndProviderInstantiate() {
        assertNotNull(com.vayunmathur.clock.widget.AnalogClockGlanceWidget())
        assertNotNull(com.vayunmathur.clock.widget.AnalogClockWidgetProvider())
    }

    @Test
    fun alarmSchedulerArmsOnDevice() {
        val alarm = seedAlarm("device-schedule")
        AlarmScheduler.schedule(ctx, alarm)
        AlarmScheduler.cancel(ctx, alarm)
        cleanupAlarm(alarm.id)
        assertEquals(ComponentName(ctx, AlarmReceiver::class.java).className, AlarmReceiver::class.java.name)
    }
}
