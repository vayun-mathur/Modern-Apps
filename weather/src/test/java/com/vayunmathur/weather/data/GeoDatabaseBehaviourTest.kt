package com.vayunmathur.weather.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GeoDatabase: FTS escaping edge cases, Meta round-trip, and search-before-ready behaviour. */
@RunWith(AndroidJUnit4::class)
class GeoDatabaseBehaviourTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() { GeoDatabase.init(ctx) }

    @Test fun escaperEdgeCases() {
        assertEquals("\"a\"* \"b\"* \"c\"*", GeoDatabase.escapeFtsQuery("a  b\tc"))
        assertEquals("\"x\"*", GeoDatabase.escapeFtsQuery("  x  "))
        // Digits are tokens too.
        assertEquals("\"route\"* \"66\"*", GeoDatabase.escapeFtsQuery("route 66"))
        // Only punctuation -> no tokens -> null.
        assertNull(GeoDatabase.escapeFtsQuery("!!!"))
        assertNull(GeoDatabase.escapeFtsQuery(" - ' "))
        // Multi-word with apostrophe splits like the tokenizer.
        assertEquals("\"l\"* \"aquila\"*", GeoDatabase.escapeFtsQuery("l'aquila"))
    }

    @Test fun metaJsonRoundTrip() {
        val json = Json { ignoreUnknownKeys = true }
        val meta = GeoDatabase.Meta(schemaVersion = 1, places = 171000, bytes = 39_000_000, compressedBytes = 13_000_000)
        val decoded = json.decodeFromString<GeoDatabase.Meta>(json.encodeToString(meta))
        assertEquals(meta, decoded)
        // Defaults tolerate an empty object (older generator output).
        assertEquals(0, json.decodeFromString<GeoDatabase.Meta>("{}").schemaVersion)
        // Unknown keys ignored.
        assertEquals(1, json.decodeFromString<GeoDatabase.Meta>("""{"schemaVersion":1,"future":true}""").schemaVersion)
    }

    @Test fun statusTypes() {
        val meta = GeoDatabase.Meta(schemaVersion = 1)
        assertEquals(meta, GeoDatabase.Status.Ready(meta).installed)
        assertNull(GeoDatabase.Status.Absent.installed)
        assertEquals(meta, GeoDatabase.Status.Failed("boom", meta).installed)
        assertEquals("boom", GeoDatabase.Status.Failed("boom", null).message)
    }

    @Test fun blankSearchIsEmptyWithoutDatabase() = runTest {
        assertTrue(GeoDatabase.searchPlaces("", 5).isEmpty())
        assertTrue(GeoDatabase.searchPlaces("   ", 5).isEmpty())
        assertTrue(GeoDatabase.searchPlaces("!!!", 5).isEmpty())
    }

    @Test fun columnConstantsMatchSelectOrder() {
        // The SELECT in searchPlaces lists id,name,lat,lon,country,code,admin1,tz in this order.
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), listOf(
            GeoDatabase.COL_ID, GeoDatabase.COL_NAME, GeoDatabase.COL_LATITUDE, GeoDatabase.COL_LONGITUDE,
            GeoDatabase.COL_COUNTRY, GeoDatabase.COL_COUNTRY_CODE, GeoDatabase.COL_ADMIN1, GeoDatabase.COL_TIMEZONE,
        ))
    }
}
