package com.vayunmathur.findfamily.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.NoShowAlert
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * No-show sweep over the installed in-memory singleton: [NoShowCheckScheduler]
 * reads [FindFamilyRepository.get], which earlier suites could not point at
 * a test database. Due/arrived/one-shot/recurring outcomes are asserted in
 * Room after the real sweep and worker run.
 */
@RunWith(AndroidJUnit4::class)
class NoShowSweepRealRepoTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repo: FindFamilyRepository

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        }
        repo = TestFindFamilyRepository.install(ctx)
    }

    private suspend fun user(name: String): Long = repo.upsertUser(User(
        name = name, photo = null, locationName = "Home", sendingEnabled = true,
        requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
    ))

    private fun alert(watched: Long, waypoint: Long?, oneShot: Boolean = true) = NoShowAlert(
        watchedUserId = watched, waypointId = waypoint,
        expectedAt = Clock.System.now() - 30.minutes, grace = 5.minutes, oneShot = oneShot,
    )

    private suspend fun fix(userid: Long, coord: Coord, fresh: Boolean = true) {
        repo.upsertLocation(LocationValue(
            userid = userid, coord = coord, speed = 0f, acc = 5f,
            timestamp = if (fresh) Clock.System.now() else Clock.System.now() - 120.minutes,
            battery = 50f, reportedAt = Clock.System.now(), source = LocationSource.LIVE,
        ))
    }

    @Test fun sweepFiresOneShotForMissingUserAndDeletesRow() = runBlocking {
        val uid = user("Alice")
        val id = repo.noShowAlertStore.upsert(alert(uid, null))
        val fired = NoShowCheckScheduler.sweepNow(ctx)
        assertEquals(listOf(id), fired.map { it.id })
        assertTrue(repo.noShowAlertStore.getAll().isEmpty(), "one-shot row claimed and deleted")
        // A second sweep has nothing left to fire.
        assertTrue(NoShowCheckScheduler.sweepNow(ctx).isEmpty())
    }

    @Test fun sweepDoesNotFireWhenFreshFixArrivedAnywhere() = runBlocking {
        val uid = user("Bob")
        repo.noShowAlertStore.upsert(alert(uid, null))
        fix(uid, Coord(1.0, 2.0))
        assertTrue(NoShowCheckScheduler.sweepNow(ctx).isEmpty())
        assertEquals(1, repo.noShowAlertStore.getAll().size)
    }

    @Test fun sweepWaypointArrivalSuppressesButAbsenceFires() = runBlocking {
        val wp = repo.waypointStore.upsert(Waypoint(name = "Home", range = 200.0, coord = Coord(10.0, 20.0)))
        val arrived = user("Arrived")
        val missing = user("Missing")
        repo.noShowAlertStore.upsert(alert(arrived, wp))
        val missingAlert = repo.noShowAlertStore.upsert(alert(missing, wp))
        fix(arrived, Coord(10.0, 20.0))
        fix(missing, Coord(30.0, 40.0))
        val fired = NoShowCheckScheduler.sweepNow(ctx)
        assertEquals(listOf(missingAlert), fired.map { it.id })
    }

    @Test fun recurringAlertStaysFiredUntilRearmed() = runBlocking {
        val uid = user("Carol")
        val id = repo.noShowAlertStore.upsert(alert(uid, null, oneShot = false))
        assertEquals(1, NoShowCheckScheduler.sweepNow(ctx).size)
        val row = repo.noShowAlertStore.get(id)
        assertTrue(row?.fired == true)
        assertTrue(NoShowCheckScheduler.sweepNow(ctx).isEmpty())
        repo.noShowAlertStore.rearm(id)
        assertEquals(1, NoShowCheckScheduler.sweepNow(ctx).size)
    }

    @Test fun staleFixDoesNotCountAsArrival() = runBlocking {
        val uid = user("Dan")
        repo.noShowAlertStore.upsert(alert(uid, null))
        fix(uid, Coord(1.0, 2.0), fresh = false)
        assertEquals(1, NoShowCheckScheduler.sweepNow(ctx).size)
    }

    @Test fun scheduleCancelAndRescheduleAllRunAgainstWorkManager() = runBlocking {
        val uid = user("Erin")
        val id = repo.noShowAlertStore.upsert(alert(uid, null))
        NoShowCheckScheduler.schedule(ctx, repo.noShowAlertStore.get(id)!!)
        NoShowCheckScheduler.scheduleAt(ctx, id, Clock.System.now(), 1.minutes)
        NoShowCheckScheduler.cancel(ctx, id)
        NoShowCheckScheduler.rescheduleAll(ctx)
        // Work is enqueued (not run) under Robolectric; reaching here without
        // throwing and leaving the row untouched is the glue contract.
        assertEquals(1, repo.noShowAlertStore.getAll().size)
    }

    @Test fun workerSweepsAndSucceeds() = runBlocking {
        val uid = user("Frank")
        repo.noShowAlertStore.upsert(alert(uid, null))
        val worker = TestListenableWorkerBuilder<NoShowCheckWorker>(ctx).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(repo.noShowAlertStore.getAll().isEmpty())
    }

    @Test fun workerWithNoDueAlertsStillSucceeds() = runBlocking {
        val worker = TestListenableWorkerBuilder<NoShowCheckWorker>(ctx).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
}
