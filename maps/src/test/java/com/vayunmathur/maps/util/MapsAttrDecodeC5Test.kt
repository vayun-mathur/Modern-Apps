package com.vayunmathur.maps.util
import com.vayunmathur.maps.data.KEY_REVIEW_SUMMARY
import com.vayunmathur.maps.data.KEY_REVIEW_COUNT
import com.vayunmathur.maps.data.KEY_RATING
import com.vayunmathur.maps.data.KEY_WHEELCHAIR
import com.vayunmathur.maps.data.KEY_CUISINE
import com.vayunmathur.maps.data.KEY_POSTCODE
import com.vayunmathur.maps.data.KEY_CITY
import com.vayunmathur.maps.data.KEY_STREET
import com.vayunmathur.maps.data.KEY_HOUSENUMBER
import com.vayunmathur.maps.data.KEY_WEBSITE
import com.vayunmathur.maps.data.KEY_PHONE
import com.vayunmathur.maps.data.KEY_OPENING_HOURS
import com.vayunmathur.maps.data.PoiIndex
import com.vayunmathur.maps.data.MappedPoiBytes
import com.vayunmathur.maps.data.PoiSearch
import com.vayunmathur.maps.data.decodeAttrRecord

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C5 attribute decoding: every known key, unknown keys stepped over,
 * rating/review sanity ranges, and truncated records.
 */
class MapsAttrDecodeC5Test {

    private fun field(key: Int, value: String): ByteArray {
        val v = value.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(key)
        out.write(v.size and 0xFF)
        out.write((v.size ushr 8) and 0xFF)
        out.write(v)
        return out.toByteArray()
    }

    private fun decode(vararg fields: ByteArray): PoiIndex.PoiAttributes? {
        val body = ByteArrayOutputStream()
        fields.forEach { body.write(it) }
        val bytes = body.toByteArray()
        val buf = MappedPoiBytes(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        return decodeAttrRecord(buf, 0, bytes.size)
    }

    @Test
    fun `every known key decodes and ranges are enforced`() {
        val attrs = decode(
            field(KEY_OPENING_HOURS, "Mo-Fr 09:00-17:00"),
            field(KEY_PHONE, "+1 555 0100"),
            field(KEY_WEBSITE, "https://example.org"),
            field(KEY_HOUSENUMBER, "42"),
            field(KEY_STREET, "C5 Avenue"),
            field(KEY_CITY, "San Francisco"),
            field(KEY_POSTCODE, "94103"),
            field(KEY_CUISINE, "coffee"),
            field(KEY_WHEELCHAIR, "yes"),
            field(KEY_RATING, "4.5"),
            field(KEY_REVIEW_COUNT, "128"),
            field(KEY_REVIEW_SUMMARY, "  Great coffee  "),
            field(99, "stepped over"),
        )
        assertEquals("Mo-Fr 09:00-17:00", attrs!!.openingHours)
        assertEquals("+1 555 0100", attrs.phone)
        assertEquals("https://example.org", attrs.website)
        assertEquals("42", attrs.houseNumber)
        assertEquals("C5 Avenue", attrs.street)
        assertEquals("San Francisco", attrs.city)
        assertEquals("94103", attrs.postcode)
        assertEquals("coffee", attrs.cuisine)
        assertEquals("yes", attrs.wheelchair)
        assertEquals(4.5, attrs.rating)
        assertEquals(128, attrs.reviewCount)
        assertEquals("Great coffee", attrs.reviewSummary)
    }

    @Test
    fun `out of range rating and non positive review count are dropped`() {
        val attrs = decode(
            field(KEY_CITY, "Nowhere"),
            field(KEY_RATING, "9.9"),
            field(KEY_REVIEW_COUNT, "0"),
            field(KEY_REVIEW_SUMMARY, "   "),
        )
        assertEquals("Nowhere", attrs!!.city)
        assertNull(attrs.rating)
        assertNull(attrs.reviewCount)
        assertNull(attrs.reviewSummary)
    }

    @Test
    fun `empty and truncated records decode to null`() {
        assertNull(decode())
        assertNull(decode(field(99, "only unknown")))
        // A field header that promises more than the record holds stops the walk.
        val truncated = byteArrayOf(KEY_CITY.toByte(), 50, 0, 'x'.code.toByte())
        val buf = MappedPoiBytes(ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN))
        assertNull(decodeAttrRecord(buf, 0, truncated.size))
    }

    @Test
    fun `coordinate queries parse and reject garbage`() {
        val parsed = PoiSearch.parseCoordinateQuery("37.7749, -122.4194")
        assertEquals(37.7749 to -122.4194, parsed)
        assertNull(PoiSearch.parseCoordinateQuery("cafe"))
        assertNull(PoiSearch.parseCoordinateQuery("999, 999"))
        assertNull(PoiSearch.parseCoordinateQuery(""))
        assertTrue(PoiSearch.parseCoordinateQuery("0,0") != null)
    }
}
