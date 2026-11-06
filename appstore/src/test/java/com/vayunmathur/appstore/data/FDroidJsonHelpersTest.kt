package com.vayunmathur.appstore.data

import android.util.JsonReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Index-v2 streaming-parser scalars: the catalogue JSON is read with
 * [JsonReader] straight off the wire, so every helper must consume exactly
 * its own value — leaving bytes on the stream desyncs the reader and drops
 * every package after the bad one.
 */
@RunWith(RobolectricTestRunner::class)
class FDroidJsonHelpersTest {

    private fun readerOf(json: String): JsonReader =
        JsonReader(StringReader(json)).apply { isLenient = true }

    // --- readLocalizedString ---

    @Test
    fun `plain strings pass through`() {
        assertEquals("Hello", readLocalizedString(readerOf("\"Hello\"")))
    }

    @Test
    fun `localised objects prefer en-US then en then first`() {
        assertEquals(
            "US",
            readLocalizedString(readerOf("""{"de":"DE","en":"EN","en-US":"US"}""")),
        )
        assertEquals("EN", readLocalizedString(readerOf("""{"de":"DE","en":"EN"}""")))
        assertEquals("DE", readLocalizedString(readerOf("""{"de":"DE"}""")))
    }

    @Test
    fun `non-string locales are skipped`() {
        assertEquals("EN", readLocalizedString(readerOf("""{"de":5,"en":"EN"}""")))
    }

    @Test
    fun `numbers and nulls read as null`() {
        assertNull(readLocalizedString(readerOf("42")))
        assertNull(readLocalizedString(readerOf("null")))
    }

    // --- readIconName ---

    @Test
    fun `nested density icon resolves its name`() {
        val json = """{"en-US":{"96":{"name":"/icons/a.png"}}}"""

        assertEquals("/icons/a.png", readIconName(readerOf(json)))
    }

    @Test
    fun `flat density icon resolves its name`() {
        val json = """{"en-US":{"name":"/icons/b.png"}}"""

        assertEquals("/icons/b.png", readIconName(readerOf(json)))
    }

    @Test
    fun `non-object icons read as null`() {
        assertNull(readIconName(readerOf("\"icon.png\"")))
        assertNull(readIconName(readerOf("null")))
    }

    // --- findFirstNameInAnyNested ---

    @Test
    fun `deeply nested names are found`() {
        assertEquals("x.png", findFirstNameInAnyNested(readerOf("""{"a":{"b":{"name":"x.png"}}}""")))
    }

    @Test
    fun `arrays are searched in order`() {
        assertEquals(
            "first.png",
            findFirstNameInAnyNested(readerOf("""[{"name":"first.png"},{"name":"second.png"}]""")),
        )
    }

    @Test
    fun `scalars read as null`() {
        assertNull(findFirstNameInAnyNested(readerOf("\"nope\"")))
    }

    // --- readScreenshotsV2 ---

    @Test
    fun `phone set is read and tv set travels alongside`() {
        val json = """{"phone":{"en-US":[{"name":"/p/1.png"}]},"tv":{"en-US":[{"name":"/t/1.png"}]}}"""
        val sets = readerOf(json).let { readScreenshotSetsV2(it, "https://f-droid.org/repo") }

        assertEquals(listOf("https://f-droid.org/repo/p/1.png"), sets.phone)
        assertEquals(listOf("https://f-droid.org/repo/t/1.png"), sets.tv)
    }

    @Test
    fun `phone falls back to whichever set exists`() {
        val json = """{"screenshots":{"en-US":[{"name":"/s/1.png"}]}}"""
        val sets = readerOf(json).let { readScreenshotSetsV2(it, "https://f-droid.org/repo") }

        assertEquals(listOf("https://f-droid.org/repo/s/1.png"), sets.phone)
        assertTrue(sets.tv.isEmpty())
    }

    @Test
    fun `non-object screenshots read as empty`() {
        assertTrue(readScreenshotsV2(readerOf("[]"), "https://f-droid.org/repo").isEmpty())
    }

    // --- readFileNameArray ---

    @Test
    fun `file entries reduce to their names`() {
        val json = """[{"name":"a.png","sha256":"x"},{"other":1},{"name":"b.png"}]"""

        assertEquals(listOf("a.png", "b.png"), readFileNameArray(readerOf(json)))
    }

    @Test
    fun `non-array file lists read as empty`() {
        assertTrue(readFileNameArray(readerOf("{}")).isEmpty())
    }

    // --- readObjectKeys ---

    @Test
    fun `object keys list the anti-features`() {
        assertEquals(
            listOf("Ads", "Tracking"),
            readObjectKeys(readerOf("""{"Ads":{},"Tracking":{}}""")),
        )
    }

    @Test
    fun `non-object key maps read as empty`() {
        assertTrue(readObjectKeys(readerOf("[]")).isEmpty())
    }

    // --- readStringArray ---

    @Test
    fun `string arrays skip non-strings`() {
        assertEquals(listOf("a", "b"), readStringArray(readerOf("""["a",5,"b",null]""")))
    }

    @Test
    fun `non-array string lists read as empty`() {
        assertTrue(readStringArray(readerOf("{}")).isEmpty())
    }

    // --- scalar readers ---

    @Test
    fun `nextStringOrNull reads strings numbers and null`() {
        assertEquals("hi", nextStringOrNull(readerOf("\"hi\"")))
        assertEquals("42", nextStringOrNull(readerOf("42")))
        assertNull(nextStringOrNull(readerOf("null")))
        assertNull(nextStringOrNull(readerOf("[]")))
    }

    @Test
    fun `nextLongOrNull reads numbers numeric strings and null`() {
        assertEquals(42L, nextLongOrNull(readerOf("42")))
        assertEquals(43L, nextLongOrNull(readerOf("\"43\"")))
        assertNull(nextLongOrNull(readerOf("null")))
        assertNull(nextLongOrNull(readerOf("\"abc\"")))
        assertNull(nextLongOrNull(readerOf("[]")))
    }

    @Test
    fun `nextIntOrNull reads numbers numeric strings and null`() {
        assertEquals(7, nextIntOrNull(readerOf("7")))
        assertEquals(8, nextIntOrNull(readerOf("\"8\"")))
        assertNull(nextIntOrNull(readerOf("null")))
        assertNull(nextIntOrNull(readerOf("\"abc\"")))
        assertNull(nextIntOrNull(readerOf("{}")))
    }

    // --- readFeatureNamesV2 ---

    @Test
    fun `feature objects reduce to their names`() {
        val json = """[{"name":"android.hardware.camera"},{},{"name":""}]"""

        assertEquals(
            listOf("android.hardware.camera"),
            readFeatureNamesV2(readerOf(json)),
        )
    }

    @Test
    fun `non-array features read as empty`() {
        assertTrue(readFeatureNamesV2(readerOf("{}")).isEmpty())
    }
}
