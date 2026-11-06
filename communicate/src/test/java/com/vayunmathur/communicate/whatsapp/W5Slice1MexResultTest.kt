package com.vayunmathur.communicate.whatsapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.mex.MexEnvelope
import com.vayunmathur.communicate.data.whatsapp.mex.MexResult
import org.json.JSONObject
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): MEX envelope + result parsing.
 * Robolectric: MexResult.fromEnvelope uses Android org.json.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice1MexResultTest {

    @Test
    fun fromEnvelope_dataOnly_isSuccess() {
        val r = MexResult.fromEnvelope("""{"data":{"xwa2_contact_discovery":{"results":[]}}}""")
        assertTrue(r.isSuccess)
        assertNull(r.transportError)
        assertEquals(emptyList(), r.errors)
        assertTrue(r.data!!.has("xwa2_contact_discovery"))
    }

    @Test
    fun fromEnvelope_graphqlErrors_notSuccess() {
        val r = MexResult.fromEnvelope(
            """{"data":null,"errors":[{"message":"bad query","code":123},{"message":"","code":null}]}""",
        )
        assertFalse(r.isSuccess)
        assertEquals(2, r.errors.size)
        assertEquals("bad query", r.errors[0].message)
        assertEquals(123, r.errors[0].code)
        assertNull(r.errors[1].message)
        assertNull(r.errors[1].code)
    }

    @Test
    fun fromEnvelope_malformed_isTransportError() {
        val r = MexResult.fromEnvelope("not json{{{")
        assertFalse(r.isSuccess)
        assertTrue(r.transportError!!.startsWith("parse_error:"), r.transportError)
        assertNull(r.data)
    }

    @Test
    fun fromEnvelope_emptyObject_isSuccessWithNullData() {
        val r = MexResult.fromEnvelope("{}")
        assertTrue(r.isSuccess)
        assertNull(r.data)
    }

    @Test
    fun transport_factory() {
        val r = MexResult.transport("no_persisted_id:X op")
        assertFalse(r.isSuccess)
        assertEquals("no_persisted_id:X op", r.transportError)
        assertNull(r.data)
    }

    @Test
    fun buildWwwBody_numericVsStringDocId() {
        val numeric = JSONObject(MexEnvelope.buildWwwBody("tok", "12345678901234567", "{}"))
        assertEquals(12345678901234567L, numeric.getLong("doc_id"))
        assertEquals("tok", numeric.getString("access_token"))
        assertEquals("en_US", numeric.getString("lang"))
        assertEquals("application/json", numeric.getString("Content-Type"))

        val strDoc = JSONObject(MexEnvelope.buildWwwBody("tok", "not-a-number", """{"a":1}"""))
        assertEquals("not-a-number", strDoc.getString("doc_id"))
        assertEquals(1, strDoc.getJSONObject("variables").getInt("a"))

        val blank = JSONObject(MexEnvelope.buildWwwBody("t", "1", "  "))
        assertEquals(0, blank.getJSONObject("variables").length())

        val bad = JSONObject(MexEnvelope.buildWwwBody("t", "1", "definitely not json"))
        assertEquals(0, bad.getJSONObject("variables").length())
    }
}
