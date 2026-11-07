package com.vayunmathur.parentalcontrols.platform

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.databases
import com.vayunmathur.parentalcontrols.data.AppRule
import com.vayunmathur.parentalcontrols.data.ParentalControlsDatabase
import com.vayunmathur.parentalcontrols.data.SupervisionRules
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SupervisionViewModelTest {

    companion object {
        private var sharedDb: ParentalControlsDatabase? = null
    }

    private lateinit var app: Application
    private lateinit var rules: SupervisionRules
    private lateinit var vm: SupervisionViewModel

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>()
        val mem = sharedDb ?: Room.inMemoryDatabaseBuilder(app, ParentalControlsDatabase::class.java)
            .setSingleConnectionPool()
            .setSingleConnectionPool()
            .allowMainThreadQueries().build().also { sharedDb = it }
        synchronized(databases) { databases[ParentalControlsDatabase::class] = mem }
        rules = SupervisionRules.get(app)
        vm = SupervisionViewModel(app)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        scope.launch { vm.state.collect {} }
        await("initial state") { true }
    }

    @AfterTest
    fun absorbReconcileLinkageErrors() {
        // Every edit reconciles through Enforcer, whose SupervisionManager class only exists
        // in the MaOS framework (compileOnly stubs), so on this JVM those fire-and-forget
        // coroutines die with a linkage error. Give them a moment to land, then enter a
        // throwaway test scope to drain the uncaught-exception collector here instead of
        // letting it fail the next Compose test in this JVM.
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(250)
        runCatching { kotlinx.coroutines.test.runTest { } }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(50)
        }
        error("timed out waiting for $label: state=${vm.state.value}")
    }

    @Test
    fun scheduleEditsPersist() {
        vm.setScheduleEnabled(true)
        await("bedtime enabled") { runBlocking { rules.scheduleNow().enabled } }
        vm.setStart(20, 30)
        await("bedtime start") { runBlocking { rules.scheduleNow().startMinute } == 20 * 60 + 30 }
        vm.setEnd(6, 15)
        await("bedtime end") { runBlocking { rules.scheduleNow().endMinute } == 6 * 60 + 15 }
        val before = runBlocking { rules.scheduleNow().daysMask }
        vm.toggleDay(3)
        await("bedtime day toggled") { runBlocking { rules.scheduleNow().daysMask } == (before xor (1 shl 3)) }
        vm.toggleDay(3)
        await("bedtime day restored") { runBlocking { rules.scheduleNow().daysMask } == before }
        await("state reflects schedule") { vm.state.value.schedule.enabled }
    }

    @Test
    fun downtimeAndSchoolEditsPersist() {
        vm.setDowntimeEnabled(true)
        await("downtime enabled") { runBlocking { rules.downtimeNow().enabled } }
        vm.setDowntimeStart(11, 0)
        await("downtime start") { runBlocking { rules.downtimeNow().startMinute } == 660 }
        vm.setDowntimeEnd(13, 30)
        await("downtime edited") {
            runBlocking { rules.downtimeNow() }.let { it.enabled && it.startMinute == 660 && it.endMinute == 810 }
        }
        val mask = runBlocking { rules.downtimeNow().daysMask }
        vm.toggleDowntimeDay(0)
        await("downtime day") { runBlocking { rules.downtimeNow().daysMask } == (mask xor 1) }
        vm.setSchoolTimeEnabled(true)
        await("school enabled") { runBlocking { rules.schoolTimeNow().enabled } }
        vm.setSchoolTimeStart(7, 45)
        await("school start") { runBlocking { rules.schoolTimeNow().startMinute } == 465 }
        vm.setSchoolTimeEnd(14, 45)
        await("school edited") {
            runBlocking { rules.schoolTimeNow() }.let { it.enabled && it.startMinute == 465 && it.endMinute == 885 }
        }
        val schoolMask = runBlocking { rules.schoolTimeNow().daysMask }
        vm.toggleSchoolTimeDay(6)
        await("school day") { runBlocking { rules.schoolTimeNow().daysMask } == (schoolMask xor (1 shl 6)) }
    }

    @Test
    fun ruleEditsCreateUpdateAndDeleteRows() {
        vm.setDailyLimit("com.vm.game", 30)
        await("rule created") { runBlocking { rules.rule("com.vm.game")?.dailyLimitMinutes } == 30 }
        vm.setBedtimeBlocked("com.vm.game", true)
        await("bedtime flag") { runBlocking { rules.rule("com.vm.game")?.blockedAtBedtime } == true }
        vm.setAllowedInDowntime("com.vm.game", true)
        await("downtime flag") { runBlocking { rules.rule("com.vm.game")?.allowedInDowntime } == true }
        // Clearing every restriction deletes the row (it no longer supervises anything).
        vm.setAllowedInDowntime("com.vm.game", false)
        await("downtime flag cleared") { runBlocking { rules.rule("com.vm.game")?.allowedInDowntime } == false }
        vm.setBedtimeBlocked("com.vm.game", false)
        await("flags cleared but limit remains") {
            runBlocking { rules.rule("com.vm.game") }.let { it != null && it.dailyLimitMinutes == 30 && !it.blockedAtBedtime }
        }
        vm.setDailyLimit("com.vm.game", null)
        await("rule deleted") { runBlocking { rules.rule("com.vm.game") } == null }
        assertNull(runBlocking { rules.rule("com.vm.game") })
    }

    @Test
    fun deviceDailyLimitAndMasterSwitch() {
        vm.setDeviceDailyLimit(120)
        await("device limit") { runBlocking { rules.dailyLimitNow() } == 120 }
        await("state device limit") { vm.state.value.dailyLimitMinutes == 120 }
        vm.setDeviceDailyLimit(null)
        await("device limit cleared") { runBlocking { rules.dailyLimitNow() } == null }
        vm.setControlsEnabled(false)
        await("controls off in state") { !vm.state.value.controlsEnabled }
        assertFalse(SupervisionMaster.isEnabled(app))
        vm.setControlsEnabled(true)
        await("controls on in state") { vm.state.value.controlsEnabled }
        assertTrue(SupervisionMaster.isEnabled(app))
    }

    @Test
    fun supervisableAppDerivedState() {
        val supervised = SupervisableApp("com.a", "Alpha", AppRule("com.a", dailyLimitMinutes = 5))
        assertTrue(supervised.isSupervised)
        assertEquals("Alpha", supervised.label)
        val free = SupervisableApp("com.b", "Beta", null)
        assertFalse(free.isSupervised)
        assertEquals(
            SupervisionUiState().apps,
            emptyList(),
        )
        assertTrue(SupervisionUiState().loading)
        assertTrue(SupervisionUiState().controlsEnabled)
    }
}
