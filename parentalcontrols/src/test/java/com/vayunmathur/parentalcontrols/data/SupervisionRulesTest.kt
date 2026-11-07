package com.vayunmathur.parentalcontrols.data

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SupervisionRulesTest {

    companion object {
        private var sharedDb: ParentalControlsDatabase? = null
    }

    private lateinit var rules: SupervisionRules

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val mem = sharedDb ?: Room.inMemoryDatabaseBuilder(app, ParentalControlsDatabase::class.java)
            .setSingleConnectionPool()
            .setSingleConnectionPool()
            .allowMainThreadQueries().build().also { sharedDb = it }
        synchronized(databases) { databases[ParentalControlsDatabase::class] = mem }
        rules = SupervisionRules.get(app)
    }

    @Test
    fun rulesCrudAndLimitedFilter() = runBlocking {
        rules.upsert(AppRule("com.rules.a", dailyLimitMinutes = 20))
        rules.upsert(AppRule("com.rules.b", blockedAtBedtime = true))
        assertEquals(20, rules.rule("com.rules.a")?.dailyLimitMinutes)
        assertTrue(rules.allRulesNow().any { it.packageName == "com.rules.b" })
        assertEquals(listOf("com.rules.a"), rules.limitedRules.first().map { it.packageName })
        assertTrue(rules.allRules.first().size >= 2)
        rules.delete(AppRule("com.rules.b"))
        assertNull(rules.rule("com.rules.b"))
    }

    @Test
    fun schedulesDefaultAndPersist() = runBlocking {
        // A row always comes back (defaulted in the DAO layer when unset, never null).
        val initial = rules.scheduleNow()
        assertEquals(BedtimeSchedule.SINGLETON_ID, initial.id)
        rules.setSchedule(BedtimeSchedule(enabled = true, startMinute = 1300, endMinute = 300))
        assertEquals(1300, rules.scheduleNow().startMinute)
        assertEquals(1300, rules.schedule.first().startMinute)
        rules.setDowntime(DowntimeSchedule(enabled = true, daysMask = 5))
        assertEquals(5, rules.downtimeNow().daysMask)
        assertEquals(5, rules.downtimeSchedule.first().daysMask)
        rules.setSchoolTime(SchoolTimeSchedule(enabled = true, endMinute = 777))
        assertEquals(777, rules.schoolTimeNow().endMinute)
        assertEquals(777, rules.schoolTimeSchedule.first().endMinute)
        assertEquals("parentalcontrols-db", DB_NAME)
    }

    @Test
    fun dailyLimitAndBonusesWithPruning() = runBlocking {
        assertNull(rules.dailyLimitNow())
        rules.setDailyLimit(90)
        assertEquals(90, rules.dailyLimitNow())
        assertEquals(90, rules.dailyLimitMinutes.first())
        rules.setDailyLimit(null)
        assertNull(rules.dailyLimitNow())
        rules.grantBonus(BonusGrant(packageName = "com.rules.a", bonusMinutes = 10, day = "2026-01-01"))
        rules.grantBonus(BonusGrant(packageName = null, bonusMinutes = 5, day = "2026-01-02"))
        // bonusesToday prunes the foreign-day row first.
        val today = rules.bonusesToday("2026-01-02")
        assertEquals(1, today.size)
        assertEquals(5, today.single().bonusMinutes)
        assertTrue(rules.bonusesToday("2026-01-01").isEmpty())
    }
}
