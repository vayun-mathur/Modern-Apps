package com.vayunmathur.contacts.pure

import com.vayunmathur.contacts.data.rewriteNoYearSentinel
import com.vayunmathur.contacts.data.toExternalDateString
import com.vayunmathur.contacts.data.VcfUtils
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

/**
 * Pins the year-less birthday encoding contract (see issue #858): external
 * stores — the Android provider's Event.START_DATE and vCard BDAY/X-EVENT —
 * must carry "--MM-DD" for unknown years, never the internal 1604 sentinel.
 * The read/import path accepts both forms, keeping iOS vCards (which use
 * "1604-MM-DD") importable.
 */
class NoYearBirthdayEncodingTest {

    @Test
    fun datedEventKeepsFullIso() {
        assertEquals("1990-05-04", LocalDate(1990, 5, 4).toExternalDateString())
    }

    @Test
    fun noYearEventUsesDashDashForm() {
        assertEquals("--05-04", LocalDate(1604, 5, 4).toExternalDateString())
    }

    @Test
    fun sentinelRewriteConvertsToDashDashForm() {
        assertEquals("--05-04", rewriteNoYearSentinel("1604-05-04"))
    }

    @Test
    fun sentinelRewriteLeavesDatedEventsAlone() {
        assertNull(rewriteNoYearSentinel("1990-05-04"))
        assertNull(rewriteNoYearSentinel("--05-04"))
        assertNull(rewriteNoYearSentinel(""))
        assertNull(rewriteNoYearSentinel("1604-5-4"))
        assertNull(rewriteNoYearSentinel("1604-05-04T00:00"))
    }

    @Test
    fun iosVcfWith1604BirthdayImportsAsNoYear() {
        val contacts = parse(vcard("BEGIN:VCARD", "VERSION:3.0", "FN:A", "BDAY:1604-05-04", "END:VCARD"))
        assertEquals("--05-04", contacts[0].details.dates[0].startDate.toExternalDateString())
    }

    @Test
    fun vcfWithDashDashBirthdayImportsAsNoYear() {
        val contacts = parse(vcard("BEGIN:VCARD", "VERSION:3.0", "FN:A", "BDAY:--05-04", "END:VCARD"))
        assertEquals("--05-04", contacts[0].details.dates[0].startDate.toExternalDateString())
    }

    @Test
    fun vcfWithDatedBirthdayKeepsYear() {
        val contacts = parse(
            vcard("BEGIN:VCARD", "VERSION:3.0", "FN:A", "BDAY:19900504", "END:VCARD"),
        )
        assertEquals("1990-05-04", contacts[0].details.dates[0].startDate.toExternalDateString())
    }

    private fun parse(vcf: String): List<com.vayunmathur.contacts.data.Contact> =
        VcfUtils.parseContacts(ByteArrayInputStream(vcf.toByteArray(Charsets.UTF_8)))

    private fun vcard(vararg lines: String): String = lines.joinToString("\r\n")
}
