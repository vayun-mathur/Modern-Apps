package com.vayunmathur.health.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The FTS query escaping behind every medical catalogue picker.
 *
 * A crash here is a crash in the search box on every keystroke, and a wrong
 * escaping silently returns nothing for half-typed words. Pure JVM.
 */
class ReferenceCatalogFtsTest {

    @Test
    fun `empty and punctuation only input matches nothing`() {
        assertNull(ReferenceCatalog.escapeFtsQuery(""))
        assertNull(ReferenceCatalog.escapeFtsQuery("   "))
        assertNull(ReferenceCatalog.escapeFtsQuery("-- (**)"))
    }

    @Test
    fun `every token becomes a quoted prefix term`() {
        // The trailing star is what makes half-typed words match; without it
        // "diab" finds nothing until the final "etes" is typed.
        assertEquals("\"diab\"*", ReferenceCatalog.escapeFtsQuery("diab"))
        assertEquals("\"type\"* \"2\"* \"diab\"*", ReferenceCatalog.escapeFtsQuery("type 2 diab"))
    }

    @Test
    fun `fts operators in user input are quoted, not executed`() {
        assertEquals("\"a\"* \"b\"*", ReferenceCatalog.escapeFtsQuery("a-b"))
        assertEquals("\"amox\"*", ReferenceCatalog.escapeFtsQuery("(amox*"))
    }

    @Test
    fun `unicode tokens split like the unicode61 tokenizer`() {
        assertEquals("\"caf\u00e9\"*", ReferenceCatalog.escapeFtsQuery("caf\u00e9"))
        assertEquals(
            "\"jalape\u00f1o\"* \"sopa\"*",
            ReferenceCatalog.escapeFtsQuery("jalape\u00f1o sopa"),
        )
    }

    @Test
    fun `catalogue status carries its installed metadata`() {
        val meta = ReferenceCatalog.Meta(schemaVersion = 4, medications = 21_000)
        assertNull(ReferenceCatalog.Status.Absent.installed)
        assertEquals(meta, ReferenceCatalog.Status.Ready(meta).installed)
        val failed = ReferenceCatalog.Status.Failed("boom", meta)
        assertEquals("boom", failed.message)
        assertEquals(meta, failed.installed)
        assertNull(ReferenceCatalog.Status.Failed("boom", null).installed)
    }

    @Test
    fun `profile answers key on their LOINC code`() {
        val answer = ProfileAnswer(
            loincCode = "68518-0",
            answerCode = "LA6270-8",
            recordedAt = java.time.Instant.EPOCH,
        )
        assertEquals("68518-0", answer.loincCode)
        assertNull(answer.fhirResourceId)
    }

    @Test
    fun `the singleton profile id is stable`() {
        assertEquals("profile", HealthProfile.SINGLETON_ID)
        assertTrue(HealthProfile().pregnancyStatus == PregnancyStatus.Unknown)
        assertTrue(HealthProfile().smokingStatus == SmokingStatus.Unknown)
    }

    @Test
    fun `dose events default to unmirrored`() {
        val event = DoseEvent(
            id = "d1",
            medicationId = "m1",
            takenAt = java.time.Instant.EPOCH,
        )
        assertNull(event.fhirResourceId)
        assertNull(event.dataSourceId)
    }
}
