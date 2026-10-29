package com.vayunmathur.maps.util

import java.nio.MappedByteBuffer

/**
 * The attribute-decode half of [PoiIndex]: reading `poi_attrs.bin` records.
 *
 * Split out so PoiIndex.kt stays under the FileLength limit. The entry points
 * ([PoiIndex.attributesAt]) stay on the object and delegate to
 * [decodeAttrRecord] here.
 */

internal const val KEY_OPENING_HOURS = 1
internal const val KEY_PHONE = 2
internal const val KEY_WEBSITE = 3
internal const val KEY_HOUSENUMBER = 4
internal const val KEY_STREET = 5
internal const val KEY_CITY = 6
internal const val KEY_POSTCODE = 7
internal const val KEY_CUISINE = 8
internal const val KEY_WHEELCHAIR = 9
internal const val KEY_RATING = 10
internal const val KEY_REVIEW_COUNT = 11
internal const val KEY_REVIEW_SUMMARY = 12

/** The range a place rating can take; anything outside it is a bad value, not a rating. */
private val RATING_RANGE = 1.0..5.0

/** Mask for one unsigned byte read out of the file. */
private const val ATTR_BYTE_MASK = 0xFF
/** Mask for one unsigned short read out of the file. */
private const val ATTR_U16_MASK = 0xFFFF
/** Offset of the u16 length within one `u8 key, u16 len` field header. */
private const val ATTR_LEN_OFF = 1
/** Bytes of one `u8 key, u16 len` field header. */
private const val ATTR_HEADER_LEN = 3

/**
 * Walk one record's `u8 key, u16 len, value` fields.
 *
 * A key this build does not know is stepped over using its length rather than
 * abandoning the record, which is the whole reason the values are
 * length-prefixed: a device on an older build must still read the keys it does
 * understand out of a newer file.
 */
internal fun decodeAttrRecord(buf: MappedByteBuffer, from: Int, to: Int): PoiIndex.PoiAttributes? {
    val fields = AttrFields()
    var i = from
    while (i + ATTR_HEADER_LEN <= to) {
        val key = buf.get(i).toInt() and ATTR_BYTE_MASK
        val len = buf.getShort(i + ATTR_LEN_OFF).toInt() and ATTR_U16_MASK
        val start = i + ATTR_HEADER_LEN
        if (start + len > to) break
        // Only decode the bytes of a key we are going to keep.
        val value: String? = if (isKnownKey(key)) stringAt(buf, start, len) else null
        fields.set(key, value)
        i = start + len
    }
    return fields.build()
}

/** Known record keys (unknown ones are stepped over, not decoded). */
private fun isKnownKey(key: Int): Boolean = when (key) {
    KEY_OPENING_HOURS, KEY_PHONE, KEY_WEBSITE, KEY_HOUSENUMBER, KEY_STREET,
    KEY_CITY, KEY_POSTCODE, KEY_CUISINE, KEY_WHEELCHAIR,
    KEY_RATING, KEY_REVIEW_COUNT, KEY_REVIEW_SUMMARY -> true
    else -> false
}

/** Mutable decode accumulator for one attribute record. */
private class AttrFields {
    var openingHours: String? = null
    var phone: String? = null
    var website: String? = null
    var houseNumber: String? = null
    var street: String? = null
    var city: String? = null
    var postcode: String? = null
    var cuisine: String? = null
    var wheelchair: String? = null
    var rating: String? = null
    var reviewCount: String? = null
    var reviewSummary: String? = null

    fun set(key: Int, value: String?) {
        when (key) {
            KEY_OPENING_HOURS -> openingHours = value
            KEY_PHONE -> phone = value
            KEY_WEBSITE -> website = value
            KEY_HOUSENUMBER -> houseNumber = value
            KEY_STREET -> street = value
            KEY_CITY -> city = value
            KEY_POSTCODE -> postcode = value
            KEY_CUISINE -> cuisine = value
            KEY_WHEELCHAIR -> wheelchair = value
            KEY_RATING -> rating = value
            KEY_REVIEW_COUNT -> reviewCount = value
            KEY_REVIEW_SUMMARY -> reviewSummary = value
        }
    }

    fun build(): PoiIndex.PoiAttributes? {
        val decoded = PoiIndex.PoiAttributes(
            openingHours, phone, website, houseNumber, street, city, postcode, cuisine, wheelchair,
            rating = rating?.toDoubleOrNull()?.takeIf { it in RATING_RANGE },
            reviewCount = reviewCount?.toIntOrNull()?.takeIf { it > 0 },
            reviewSummary = reviewSummary?.trim()?.ifEmpty { null },
        )
        return decoded.takeUnless { it.isEmpty }
    }
}

private fun stringAt(buf: MappedByteBuffer, off: Int, len: Int): String? {
    if (len <= 0) return null
    val bytes = ByteArray(len)
    // Absolute reads via a duplicate, so the shared buffer's position is untouched.
    val dup = buf.duplicate()
    dup.position(off)
    dup.get(bytes, 0, len)
    return String(bytes, Charsets.UTF_8)
}
