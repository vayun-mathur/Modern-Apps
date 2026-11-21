package com.vayunmathur.findfamily.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Own-fix hybrid stamps ([OwnFixStamps]): a backwards clock correction must
 * never make this device's fixes arrive stale against its own high-water at
 * receivers (see `domain/IngestSequencing`).
 */
@RunWith(AndroidJUnit4::class)
class OwnFixStampsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setup() = runBlocking {
        DirectBootStore.store(ctx).removeKeys(listOf(OwnFixStamps.KEY))
    }

    @Test
    fun clockBackwardsSequenceStillYieldsIncreasingReportedAt() = runBlocking {
        var wall = 1_700_000_000_000L
        OwnFixStamps.resetForTesting(wallNow = { wall })
        val first = OwnFixStamps.next(ctx)
        wall = 1_600_000_000_000L // clock corrected backwards by a day-ish
        val second = OwnFixStamps.next(ctx)
        val third = OwnFixStamps.next(ctx)
        assertEquals(1_700_000_000_000L, first.toEpochMilliseconds())
        assertTrue(second > first, "reportedAt regressed after backwards clock: $first -> $second")
        assertTrue(third > second, "reportedAt must keep increasing: $second -> $third")
    }

    @Test
    fun persistedHighWaterSurvivesRestart() = runBlocking {
        var wall = 1_700_000_000_000L
        OwnFixStamps.resetForTesting(wallNow = { wall })
        val before = OwnFixStamps.next(ctx)
        assertEquals(
            before.toEpochMilliseconds(),
            DirectBootStore.store(ctx).getLongAwait(OwnFixStamps.KEY),
            "mint must persist the high-water",
        )
        // Process death: fresh generator, wall now far behind the persisted mark.
        wall = 10L
        OwnFixStamps.resetForTesting(wallNow = { wall })
        val after = OwnFixStamps.next(ctx)
        assertTrue(after > before, "restart must resume past the persisted high-water: $before -> $after")
    }

    @Test
    fun partingReportStampsAfterHeartbeatStamps() = runBlocking {
        var wall = 1_700_000_000_000L
        OwnFixStamps.resetForTesting(wallNow = { wall })
        val heartbeat = OwnFixStamps.next(ctx) // heartbeat mint
        wall = 1_600_000_000_000L // clock corrected backwards before shutdown
        val parting = OwnFixStamps.next(ctx) // parting/shutdown mint
        assertTrue(
            parting > heartbeat,
            "parting reportedAt $parting must sort after heartbeat $heartbeat or receivers drop it as stale",
        )
    }
}
