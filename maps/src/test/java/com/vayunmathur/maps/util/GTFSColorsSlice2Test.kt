package com.vayunmathur.maps.util

import android.content.Context
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith

/**
 * The GTFS route-colour lookup against a real `routes.txt`: header mapping,
 * short/long name matching, quoted commas, caching and absent rows.
 * Slice-2 coverage for maps (basename G, outside the slice-1 dirs).
 *
 * The feed lives in a temp dir registered as an extra asset path on the
 * application's AssetManager.
 */
@RunWith(AndroidJUnit4::class)
class GTFSColorsSlice2Test {

    private val app get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun contextWithRoutes(feed: String, routesTxt: String): Context {
        // Pack the feed as a zip with an assets/ root and register it as an
        // extra asset path on the app's own AssetManager (the addAssetPath
        // the platform itself uses for overlays); feed names are unique per
        // test, so nothing collides.
        val zip = Files.createTempFile("gtfs-feed", ".zip").toFile()
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("assets/$feed/routes.txt"))
            out.write(routesTxt.encodeToByteArray())
            out.closeEntry()
        }
        AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            .invoke(app.assets, zip.absolutePath)
        return app
    }

    private val routes = """
        route_id,route_short_name,route_long_name,route_type,route_color
        R1,N,"Judah, Ocean Beach",3,0000FF
        R2,38,Geary,3,FF0000
        R3,L,,3,
        """.trimIndent() + "\n"

    @Test fun `short and long names resolve to their colours`() {
        val context = contextWithRoutes("feed-a", routes)
        assertEquals("#0000FF", GTFSProvider.getRouteColor(context, "feed-a", "N"))
        assertEquals("#FF0000", GTFSProvider.getRouteColor(context, "feed-a", "38"))
        // The quoted long name with its comma intact is also a key.
        assertEquals("#0000FF", GTFSProvider.getRouteColor(context, "feed-a", "Judah, Ocean Beach"))
    }

    @Test fun `a cached colour survives the feed disappearing`() {
        val context = contextWithRoutes("feed-b", routes)
        assertEquals("#FF0000", GTFSProvider.getRouteColor(context, "feed-b", "Geary"))
        // Second lookup is served from the cache: even a context whose assets
        // are gone (the base app, no such feed) returns the colour.
        assertEquals("#FF0000", GTFSProvider.getRouteColor(app, "feed-b", "Geary"))
    }

    @Test fun `unknown routes and empty colours read null`() {
        val context = contextWithRoutes("feed-c", routes)
        assertNull(GTFSProvider.getRouteColor(context, "feed-c", "ZZZ"))
        // Row exists but its colour cell is empty.
        assertNull(GTFSProvider.getRouteColor(context, "feed-c", "L"))
    }

    @Test fun `a feed without a colour column reads null`() {
        val context = contextWithRoutes(
            "feed-d",
            "route_id,route_short_name,route_long_name\nR1,N,Judah\n",
        )
        assertNull(GTFSProvider.getRouteColor(context, "feed-d", "N"))
    }

    @Test fun `an empty feed file reads null`() {
        val context = contextWithRoutes("feed-e", "")
        assertNull(GTFSProvider.getRouteColor(context, "feed-e", "N"))
    }
}
