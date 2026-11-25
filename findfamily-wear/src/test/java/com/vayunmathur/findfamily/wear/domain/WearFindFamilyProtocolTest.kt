package com.vayunmathur.findfamily.wear.domain

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
        val op = WearFindFamilyOp(WearOpKind.STOP_SHARING_NOW, 123L)
        assertEquals(
            """{"kind":"STOP_SHARING_NOW","requestedAtMillis":123}""",
            WearFindFamilyProtocol.json.encodeToString(op),
        )
        val refresh = WearFindFamilyOp(WearOpKind.REQUEST_REFRESH, 456L)
        assertEquals(
            """{"kind":"REQUEST_REFRESH","requestedAtMillis":456}""",
            WearFindFamilyProtocol.json.encodeToString(refresh),
        )
    }

    @Test
    fun snapshotRoundTrips() {
        val snapshot = WearFindFamilySnapshot(
            generatedAtMillis = 9L,
            people = listOf(person(1L, 10L), person(2L, 20L)),
            alerts = listOf(WearAlert(WearAlertKind.NOSHOW, 2L, "School", 30L, fired = true)),
            sharingEnabled = false,
            region = WearRegion(377_749_000, -1_224_194_000, 500, true),
            truncated = true,
            droppedCount = 3,
            totalPeople = 5,
        )
        assertEquals(
            snapshot,
            WearFindFamilyProtocol.json.decodeFromString<WearFindFamilySnapshot>(
                WearFindFamilyProtocol.json.encodeToString(snapshot),
            ),
        )
    }

    @Test
    fun allPeopleCompletenessNoSilentDrop() {
        val candidates = (1L..200L).map { person(it, it * 1_000L) }
        val snapshot = buildWearSnapshot(candidates, generatedAtMillis = 999_000L)
        assertEquals(200, snapshot.people.size)
        assertEquals(200, snapshot.totalPeople)
        assertFalse(snapshot.truncated)
        assertEquals(0, snapshot.droppedCount)
        // Most recently updated first.
        assertEquals(200L, snapshot.people.first().id)
        assertEquals(1L, snapshot.people.last().id)
    }

    @Test
    fun hardCapDropsOldestAndSaysSo() {
        val candidates = (1L..2_000L).map {
            person(it, it, name = "N".repeat(100), place = "P".repeat(120))
        }
        val snapshot = buildWearSnapshot(candidates, generatedAtMillis = 0L)
        assertTrue(snapshot.truncated)
        assertTrue(snapshot.droppedCount > 0)
        assertEquals(2_000, snapshot.totalPeople)
        assertEquals(snapshot.totalPeople - snapshot.droppedCount, snapshot.people.size)
        // Most recent survives; oldest are the ones dropped.
        assertEquals(2_000L, snapshot.people.first().id)
        val bytes = WearFindFamilyProtocol.json.encodeToString(snapshot).encodeToByteArray().size
        assertTrue(bytes <= WearFindFamilyProtocol.MAX_JSON_BYTES, "snapshot $bytes over cap")
    }

    @Test
    fun summarisingCapsFieldsBeforeDropping() {
        val snapshot = buildWearSnapshot(
            listOf(person(1L, 1L, name = "N".repeat(100), place = "P".repeat(120))),
            generatedAtMillis = 0L,
        )
        assertEquals(WearFindFamilyProtocol.MAX_NAME_CHARS, snapshot.people.single().name.length)
        assertEquals(WearFindFamilyProtocol.MAX_PLACE_CHARS, snapshot.people.single().placeLabel.length)
        assertFalse(snapshot.truncated)
    }

    @Test
    fun stalenessLabelling() {
        val fresh = person(1L, 1_000_000L)
        val now = 1_000_000L + WearFindFamilyProtocol.STALE_AFTER_MS
        assertFalse(wearIsStale(fresh, now))
        assertTrue(wearIsStale(fresh, now + 1L))
        assertTrue(wearIsStale(person(2L, 0L), now))
        assertEquals("Stale · 31m ago", wearStaleLabel(fresh, now + 60_000L))
        assertTrue(wearSnapshotIsStale(WearFindFamilySnapshot(generatedAtMillis = 0L), now))
        assertFalse(wearSnapshotIsStale(WearFindFamilySnapshot(generatedAtMillis = now), now))
    }

    @Test
    fun relativeViewMath() {
        // 0.01 degrees north of equator is about 1.11 km.
        val north = wearDistanceMeters(0, 0, 100_000, 0)
        assertTrue(north in 1_100.0..1_125.0, "north distance $north")
        assertEquals(90, wearBearingDegrees(0, 0, 0, 100_000))
        assertEquals(0, wearBearingDegrees(0, 0, 100_000, 0))
        assertEquals("E", wearCompass(90))
        assertEquals("N", wearCompass(0))
        assertEquals("NE", wearCompass(45))
        val (east, northM) = wearRelativeOffsetMeters(0, 0, 0, 100_000)
        assertTrue(east in 1_100.0..1_125.0 && northM == 0.0, "offset $east/$northM")
        val projection = WearRelativeProjection(200f, 200f, 100)
        assertEquals(100f to 100f, projection.project(0.0, 0.0))
        assertEquals(200f to 100f, projection.project(100.0, 0.0))
        assertEquals(100f to 0f, projection.project(0.0, 100.0))
        assertTrue(projection.inside(60.0, 60.0))
        assertFalse(projection.inside(90.0, 90.0))
    }

    @Test
    fun motionFromSpeed() {
        assertEquals(WearMotion.MOVING, wearMotionFor(2.0f, true))
        assertEquals(WearMotion.STATIONARY, wearMotionFor(0.0f, true))
        assertEquals(WearMotion.UNKNOWN, wearMotionFor(null, true))
        assertEquals(WearMotion.UNKNOWN, wearMotionFor(5.0f, false))
    }

    @Test
    fun regionCentresOnSelfAndCoversEveryone() {
        val self = person(1L, 100L).copy(isSelf = true)
        val far = person(2L, 90L).copy(latE7 = 387_749_000) // ~111 km north
        val region = wearRegionFor(listOf(far, self))
        assertEquals(self.latE7, region.centerLatE7)
        assertTrue(region.hasCenter)
        assertEquals(100_000, region.radiusM)
        assertEquals(WearRegion(), wearRegionFor(listOf(person(3L, 1L).copy(latE7 = null, lonE7 = null))))
    }

    @Test
    fun distanceSummaryAndAge() {
        val self = person(1L, 100L).copy(isSelf = true)
        val other = person(2L, 100L).copy(latE7 = 378_749_000) // ~11 km north
        assertTrue(wearDistanceSummary(self, other).endsWith(" N"))
        assertEquals("", wearDistanceSummary(null, other))
        assertEquals("just now", wearAgeLabel(1_000L, 30_000L))
        assertEquals("5m ago", wearAgeLabel(0L + 1_000L, 1_000L + 5 * 60_000L))
        assertEquals("2h ago", wearAgeLabel(1_000L, 1_000L + 2 * 3_600_000L))
        assertEquals("500 m", wearFormatDistance(500.0))
        assertEquals("1.5 km", wearFormatDistance(1_500.0))
    }
}
