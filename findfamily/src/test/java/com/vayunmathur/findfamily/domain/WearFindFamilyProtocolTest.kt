package com.vayunmathur.findfamily.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class WearFindFamilyProtocolTest {

    private fun person(
        id: Long,
        updatedAt: Long,
        name: String = "Person $id",
        place: String = "Home",
    ) = WearPerson(
        id = id,
        name = name,
        placeLabel = place,
        latE7 = 377_749_000,
        lonE7 = -1_224_194_000,
        updatedAtMillis = updatedAt,
    )

    @Test
    fun goldenSnapshotJson() {
        val snapshot = WearFindFamilySnapshot(
            generatedAtMillis = 1_000L,
            people = listOf(person(7L, 900L, name = "Ada")),
            region = WearRegion(377_749_000, -1_224_194_000, 200, true),
            totalPeople = 1,
        )
        assertEquals(
            """{"generatedAtMillis":1000,"people":[{"id":7,"name":"Ada","placeLabel":"Home",""" +
                """"latE7":377749000,"lonE7":-1224194000,"updatedAtMillis":900}],""" +
                """"region":{"centerLatE7":377749000,"centerLonE7":-1224194000,""" +
                """"hasCenter":true},"totalPeople":1}""",
            WearFindFamilyProtocol.json.encodeToString(snapshot),
        )
    }

    @Test
    fun goldenStopSharingOpJson() {
        assertEquals(
            """{"kind":"STOP_SHARING_NOW","requestedAtMillis":123}""",
            WearFindFamilyProtocol.json.encodeToString(WearFindFamilyOp(WearOpKind.STOP_SHARING_NOW, 123L)),
        )
        assertEquals(
            """{"kind":"REQUEST_REFRESH","requestedAtMillis":456}""",
            WearFindFamilyProtocol.json.encodeToString(WearFindFamilyOp(WearOpKind.REQUEST_REFRESH, 456L)),
        )
    }

    @Test
    fun allPeopleCompletenessNoSilentDrop() {
        val candidates = (1L..200L).map { person(it, it * 1_000L) }
        val snapshot = buildWearSnapshot(candidates, generatedAtMillis = 999_000L)
        assertEquals(200, snapshot.people.size)
        assertEquals(200, snapshot.totalPeople)
        assertFalse(snapshot.truncated)
        assertEquals(200L, snapshot.people.first().id)
    }

    @Test
    fun hardCapDropsOldestAndSaysSo() {
        val candidates = (1L..2_000L).map {
            person(it, it, name = "N".repeat(100), place = "P".repeat(120))
        }
        val snapshot = buildWearSnapshot(candidates, generatedAtMillis = 0L)
        assertTrue(snapshot.truncated)
        assertTrue(snapshot.droppedCount > 0)
        assertEquals(2_000L, snapshot.people.first().id)
        val bytes = WearFindFamilyProtocol.json.encodeToString(snapshot).encodeToByteArray().size
        assertTrue(bytes <= WearFindFamilyProtocol.MAX_JSON_BYTES, "snapshot $bytes over cap")
    }

    @Test
    fun stalenessAndRelativeMath() {
        val fresh = person(1L, 1_000_000L)
        val now = 1_000_000L + WearFindFamilyProtocol.STALE_AFTER_MS
        assertFalse(wearIsStale(fresh, now))
        assertTrue(wearIsStale(fresh, now + 1L))
        assertEquals(90, wearBearingDegrees(0, 0, 0, 100_000))
        assertEquals("E", wearCompass(90))
        val north = wearDistanceMeters(0, 0, 100_000, 0)
        assertTrue(north in 1_100.0..1_125.0, "north $north")
        assertEquals(WearMotion.MOVING, wearMotionFor(2.0f, true))
        assertEquals(WearMotion.UNKNOWN, wearMotionFor(5.0f, false))
    }
}
