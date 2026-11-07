package com.vayunmathur.parentalcontrols.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParentalControlsDatabaseTest {

    private lateinit var db: ParentalControlsDatabase

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ParentalControlsDatabase::class.java)
            .setSingleConnectionPool()
            .allowMainThreadQueries().build()
    }

    // No close(): Robolectric's in-memory SQLite store is shared per sandbox, and closing
    // one Room instance drops the tables under the repository's cached instance.

    @Test
    fun appRuleCrudAndOrdering() = runTest {
        val dao = db.appRuleDao()
        assertTrue(dao.all().isEmpty())
        dao.upsert(AppRule("com.zeta", dailyLimitMinutes = 30))
        dao.upsert(AppRule("com.alpha", blockedAtBedtime = true))
        dao.upsert(AppRule("com.mid", allowedInDowntime = true))
        assertEquals(listOf("com.alpha", "com.mid", "com.zeta"), dao.allFlow().first().map { it.packageName })
        assertEquals(30, dao.byPackage("com.zeta")?.dailyLimitMinutes)
        assertNull(dao.byPackage("com.missing"))
        dao.upsert(AppRule("com.zeta", dailyLimitMinutes = 45))
        assertEquals(45, dao.byPackage("com.zeta")?.dailyLimitMinutes)
        dao.delete(AppRule("com.zeta"))
        assertNull(dao.byPackage("com.zeta"))
        assertEquals(2, dao.all().size)
    }

    @Test
    fun scheduleDaosRoundTripSingletons() = runTest {
        assertNull(db.bedtimeDao().schedule())
        db.bedtimeDao().upsert(BedtimeSchedule(enabled = true, startMinute = 1200, endMinute = 400))
        assertEquals(1200, db.bedtimeDao().schedule()?.startMinute)
        assertEquals(1200, db.bedtimeDao().scheduleFlow().first()?.startMinute)
        db.downtimeDao().upsert(DowntimeSchedule(enabled = true, daysMask = 3))
        assertEquals(3, db.downtimeDao().scheduleFlow().first()?.daysMask)
        db.schoolTimeDao().upsert(SchoolTimeSchedule(enabled = true, endMinute = 800))
        assertEquals(800, db.schoolTimeDao().schedule()?.endMinute)
        // Upsert replaces the singleton row.
        db.bedtimeDao().upsert(BedtimeSchedule(enabled = false))
        assertEquals(false, db.bedtimeDao().schedule()?.enabled)
    }

    @Test
    fun dailyLimitAndBonusGrants() = runTest {
        assertNull(db.dailyLimitDao().limit())
        db.dailyLimitDao().upsert(DailyLimit(dailyLimitMinutes = 120))
        assertEquals(120, db.dailyLimitDao().limitFlow().first()?.dailyLimitMinutes)
        db.dailyLimitDao().upsert(DailyLimit(dailyLimitMinutes = null))
        assertNull(db.dailyLimitDao().limit()?.dailyLimitMinutes)

        val bonus = db.bonusGrantDao()
        bonus.upsert(BonusGrant(packageName = "com.a", bonusMinutes = 15, day = "2026-11-06"))
        bonus.upsert(BonusGrant(packageName = null, bonusMinutes = 30, day = "2026-11-06"))
        bonus.upsert(BonusGrant(packageName = "com.a", bonusMinutes = 99, day = "2026-11-05"))
        assertEquals(2, bonus.forDay("2026-11-06").size)
        bonus.pruneForeignDays("2026-11-06")
        assertTrue(bonus.forDay("2026-11-05").isEmpty())
        assertEquals(45, bonus.forDay("2026-11-06").sumOf { it.bonusMinutes })
    }
}
