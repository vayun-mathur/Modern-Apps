package com.vayunmathur.findfamily.tracker

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dedup contract for powered-off sighting reports: only a successful upload
 * may suppress retries. A failed resolve/upload must leave the EID
 * reportable, or one transient socket failure loses the whole 1024s slot.
 */
class PoweredOffReportingTest {

    @Test
    fun unreportedEidIsNotRecentlyReported() {
        val eid = ByteArray(20) { (it * 7).toByte() }
        assertFalse(PoweredOffReporting.isRecentlyReported(eid))
    }

    @Test
    fun markedEidIsRecentlyReportedAndOthersAreNot() {
        val eid = ByteArray(20) { (100 + it).toByte() }
        val other = ByteArray(20) { (200 - it).toByte() }
        PoweredOffReporting.markReported(eid)
        assertTrue(PoweredOffReporting.isRecentlyReported(eid))
        assertFalse(PoweredOffReporting.isRecentlyReported(other))
    }
}
