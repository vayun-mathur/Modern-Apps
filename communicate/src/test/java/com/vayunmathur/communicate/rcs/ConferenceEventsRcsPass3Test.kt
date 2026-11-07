package com.vayunmathur.communicate.rcs

import com.vayunmathur.communicate.data.rcs.RcsConferenceEvents
import com.vayunmathur.communicate.data.rcs.RcsConferenceEvents.Participant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pass 3: conference-info parsing edge cases (deleted markers in each
 *  position, display text handling, missing attributes) and sipfrag. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConferenceEventsRcsPass3Test {

    @Test fun parseFullDocumentWithDeletedVariants() {
        val body = """
            <conference-info xmlns="urn:ietf:params:xml:ns:conference-info" entity="sip:conf-9@rcs" version="3" state="full">
              <users>
                <user entity="sip:+1001@rcs"><display-text>Alice</display-text><state>full</state></user>
                <user entity="sip:+1002@rcs" state="deleted"><display-text>Bob</display-text></user>
                <user entity="sip:+1003@rcs"><state>deleted</state></user>
                <user entity="sip:+1004@rcs"><endpoint state="deleted"/></user>
                <user entity="sip:+1005@rcs"><display-text>  </display-text></user>
                <user entity="sip:+1006@rcs"><state>partial</state><display-text>Carol</display-text></user>
              </users>
            </conference-info>
        """.trimIndent()
        val info = RcsConferenceEvents.parseConferenceInfo(body)
        assertNotNull(info)
        assertEquals("sip:conf-9@rcs", info.entity)
        assertEquals(3, info.version)
        assertEquals(
            listOf(
                Participant("sip:+1001@rcs", "full", "Alice"),
                Participant("sip:+1005@rcs", "full", null),
                Participant("sip:+1006@rcs", "partial", "Carol"),
            ),
            info.users,
        )
        assertEquals(
            listOf("sip:+1002@rcs", "sip:+1003@rcs"),
            RcsConferenceEvents.deletedUris(body).take(2),
        )
        assertTrue(RcsConferenceEvents.deletedUris(body).contains("sip:+1002@rcs"))
    }

    @Test fun parseDefaultsAndNonConferenceBodies() {
        assertNull(RcsConferenceEvents.parseConferenceInfo("just text"))
        assertNull(RcsConferenceEvents.parseConferenceInfo(""))
        assertEquals(emptyList(), RcsConferenceEvents.deletedUris("just text"))
        // No entity / no version attributes default to "" and 0.
        val info = RcsConferenceEvents.parseConferenceInfo(
            "<conference-info><users><user entity=\"sip:+1@rcs\"></user></users></conference-info>",
        )
        assertNotNull(info)
        assertEquals("", info.entity)
        assertEquals(0, info.version)
        assertEquals(listOf(Participant("sip:+1@rcs", "full", null)), info.users)
        // Malformed version also defaults.
        val bad = RcsConferenceEvents.parseConferenceInfo(
            "<conference-info entity=\"sip:c@rcs\" version=\"x\"></conference-info>",
        )
        assertEquals(0, bad?.version)
    }

    @Test fun buildParseRoundTrip() {
        val body = RcsConferenceEvents.buildConferenceInfo("sip:conf@rcs", 7, listOf("+1001", "sip:+1002@rcs"))
        val info = RcsConferenceEvents.parseConferenceInfo(body)
        assertNotNull(info)
        assertEquals("sip:conf@rcs", info.entity)
        assertEquals(7, info.version)
        assertEquals(2, info.users.size)
        assertTrue(info.users.any { it.uri.contains("+1001") })
    }

    @Test fun sipfragBuildAndParse() {
        val ok = RcsConferenceEvents.buildSipfrag(200, "OK")
        assertEquals(200 to "OK", RcsConferenceEvents.parseSipfrag(ok))
        val declined = RcsConferenceEvents.buildSipfrag(603, "Decline")
        assertEquals(603 to "Decline", RcsConferenceEvents.parseSipfrag(declined))
        assertNull(RcsConferenceEvents.parseSipfrag("garbage"))
        assertNull(RcsConferenceEvents.parseSipfrag(""))
    }
}
