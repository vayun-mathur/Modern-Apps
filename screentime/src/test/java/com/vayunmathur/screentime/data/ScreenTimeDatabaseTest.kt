package com.vayunmathur.screentime.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room DAOs in-memory: timers, paused set, wind-down schedule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenTimeDatabaseTest {

    private lateinit var db: ScreenTimeDatabase
    @BeforeTest fun open() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ScreenTimeDatabase::class.java).build()
    }
    @AfterTest fun close() = db.close()

    @Test fun timers_crudAndOrder() = runTest {
        val dao = db.appTimerDao()
        dao.upsert(AppTimer("com.b.app", 60))
        dao.upsert(AppTimer("com.a.app", 30))
        assertEquals(listOf("com.a.app", "com.b.app"), dao.allFlow().first().map { it.packageName })
        assertEquals(2, dao.all().size)
        assertEquals(30, dao.byPackage("com.a.app")?.dailyLimitMinutes)
        assertEquals(2, dao.allFlow().first().size)
        dao.upsert(AppTimer("com.a.app", 45))
        assertEquals(45, dao.byPackage("com.a.app")?.dailyLimitMinutes)
        dao.delete(AppTimer("com.a.app", 45))
        assertNull(dao.byPackage("com.a.app"))
    }

    @Test fun paused_singleton() = runTest {
        val dao = db.pausedAppsDao()
        assertNull(dao.paused())
        dao.upsert(PausedApps(pausedPackages = listOf("com.a.app")))
        assertEquals(listOf("com.a.app"), dao.paused()?.pausedPackages)
        dao.upsert(PausedApps(pausedPackages = listOf("com.b.app", "com.c.app")))
        assertEquals(2, dao.paused()?.pausedPackages?.size)
        assertEquals(listOf("com.b.app", "com.c.app"), dao.pausedFlow().first()?.pausedPackages)
    }

    @Test fun windDown_singleton() = runTest {
        val dao = db.windDownDao()
        assertNull(dao.schedule())
        dao.upsert(WindDownSchedule(enabled = true, startMinute = 60, endMinute = 120))
        val s = dao.schedule()
        assertTrue(s?.enabled == true)
        assertEquals(60, s?.startMinute)
        assertEquals(WindDownSchedule.DEFAULT_END_MINUTE, WindDownSchedule().endMinute)
    }
}
