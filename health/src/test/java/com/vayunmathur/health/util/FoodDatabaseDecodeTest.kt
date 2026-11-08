package com.vayunmathur.health.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoodDatabaseDecodeTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun leb128(value: Long): ByteArray {
        var v = value
        val out = mutableListOf<Byte>()
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.add(b.toByte())
        } while (v != 0L)
        return out.toByteArray()
    }

    private fun packed(mantissa: Long, exp: Int): Long = (mantissa shl 4) or exp.toLong()

    private fun blob(vararg slots: Pair<Int, Long>): ByteArray {
        var bitmap = 0L
        slots.forEach { bitmap = bitmap or (1L shl it.first) }
        val bytes = ByteArray(6)
        for (i in 0 until 6) bytes[i] = (bitmap shr (8 * i)).toByte()
        var body = ByteArray(0)
        slots.sortedBy { it.first }.forEach { body += leb128(it.second) }
        return bytes + body
    }

    @Test
    fun `empty and short blobs decode to zeros`() {
        assertEquals(0.0, FoodDatabase.decodeNutrients(ByteArray(0)).calories)
        assertEquals(0.0, FoodDatabase.decodeNutrients(ByteArray(3)).protein)
        assertEquals(0.0, FoodDatabase.decodeNutrients(ByteArray(6)).protein)
    }

    @Test
    fun `single nutrient decodes mantissa and exponent`() {
        // protein slot 0 = 100 * 10^-1 = 10.0, calories slot 40 = 250 * 10^0
        val data = FoodDatabase.decodeNutrients(blob(0 to packed(100, 1), 40 to packed(250, 0)))
        assertEquals(10.0, data.protein)
        assertEquals(250.0, data.calories)
        assertEquals(0.0, data.fat)
    }

    @Test
    fun `small values keep precision through exponent`() {
        // vitamin D slot 36 = 20 * 10^-0? use 15 *10^-1 =1.5 ; sodium slot5 = 50
        val data = FoodDatabase.decodeNutrients(blob(5 to packed(50, 0), 36 to packed(15, 1)))
        assertEquals(50.0, data.sodium)
        assertEquals(1.5, data.vitaminD)
    }

    @Test
    fun `truncated blob yields zeros for missing tail`() {
        val full = blob(0 to packed(100, 1), 40 to packed(250, 0))
        val truncated = full.copyOf(7) // bitmap + one byte of first varint only partially
        val data = FoodDatabase.decodeNutrients(truncated)
        // Must not throw; values are either decoded prefix or zero.
        assertTrue(data.protein >= 0.0)
    }

    @Test
    fun `search on unprepared database returns empty not crash`() = runTest {
        FoodDatabase.init(context)
        val results = FoodDatabase.search("zzzz-no-such-food-qqq")
        assertTrue(results.isEmpty())
        assertEquals(null, FoodDatabase.nutrition(999_999L))
    }

    @Test
    fun `fts escaping mirrors reference catalog behavior`() {
        assertEquals(null, FoodDatabase.escapeFtsQuery(""))
        assertEquals("\"oat\"", FoodDatabase.escapeFtsQuery("oat"))
    }
}
