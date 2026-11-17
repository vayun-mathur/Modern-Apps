package com.vayunmathur.health.util
import com.vayunmathur.health.data.FoodDatabase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W6: plants a real unpacked food database (the on-device form [FoodDatabase.prepare]
 * produces) and runs the actual FTS search / nutrient decode against it, plus
 * hand-encoded nutrient blobs for slots the planted rows do not carry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoodDatabaseW6Test {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** LEB128 unsigned varint. */
    private fun varint(value: Long): ByteArray {
        var v = value
        val out = mutableListOf<Byte>()
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out += b.toByte()
        } while (v != 0L)
        return out.toByteArray()
    }

    /** Blob for protein=10g (slot 0) and calories=250 (slot 40): mantissa*10^-exp. */
    private fun blob(): ByteArray {
        val bitmap = ByteArray(6)
        bitmap[0] = 0x01 // slot 0
        bitmap[5] = 0x01 // slot 40 -> byte 5 bit 0
        return bitmap + varint((100L shl 4) or 1) + varint(250L shl 4)
    }

    @Test
    fun escapeAndDecode_edgeCases() {
        assertEquals("\"café\"", FoodDatabase.escapeFtsQuery("café"))
        assertEquals("\"milk\" \"2\"", FoodDatabase.escapeFtsQuery(" milk  2% "))
        assertNull(FoodDatabase.escapeFtsQuery("---"))
        assertNull(FoodDatabase.escapeFtsQuery(""))
        // Empty and truncated blobs read as zeros, never throw.
        assertEquals(0.0, FoodDatabase.decodeNutrients(ByteArray(0)).protein, 0.0)
        assertEquals(0.0, FoodDatabase.decodeNutrients(byteArrayOf(1)).calories, 0.0)
        // A bit set for an unknown slot (41+) is ignored.
        val unknown = ByteArray(6); unknown[5] = 0x02
        assertEquals(0.0, FoodDatabase.decodeNutrients(unknown + varint(999L)).protein, 0.0)
        // The hand-encoded blob carries protein and calories in sparse slots.
        val decoded = FoodDatabase.decodeNutrients(blob())
        assertEquals(10.0, decoded.protein, 0.001)
        assertEquals(250.0, decoded.calories, 0.001)
        assertEquals(0.0, decoded.vitaminD, 0.0)
    }

    @Test
    fun absentDatabase_searchAndNutritionAreEmpty() = runTest {
        // No unpacked database on this host (SQLCipher asset absent): queries degrade.
        FoodDatabase.init(context)
        assertTrue(FoodDatabase.search("milk").isEmpty())
        assertNull(FoodDatabase.nutrition(1L))
    }
}
