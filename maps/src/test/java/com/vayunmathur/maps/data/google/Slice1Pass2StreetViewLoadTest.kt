package com.vayunmathur.maps.data.google

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/3 second pass (maps): [StreetViewDataSource.loadPanorama] tile
 * stitching and the live-network seam defaults' failure behaviour, beyond
 * the first pass's metadata parsing.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1Pass2StreetViewLoadTest {

    private fun pngBytes(color: Int, w: Int = 8, h: Int = 8): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test
    fun `seam defaults refuse unreachable hosts without throwing`() = runBlocking {
        // The default seams hit the live network; a refused localhost port is
        // the deterministic offline stand-in for any transport failure.
        assertNull(StreetViewDataSource.httpGet("http://127.0.0.1:1/meta"))
        assertNull(StreetViewDataSource.httpGetBytes("http://127.0.0.1:1/tile"))
        assertNull(com.vayunmathur.maps.data.google.GooglePoiDataSource.httpGet("http://127.0.0.1:1/search"))
    }

    @Test
    fun `load panorama stitches a full tile grid`() = runBlocking {
        val originalBytes = StreetViewDataSource.httpGetBytes
        val requested = mutableListOf<String>()
        StreetViewDataSource.httpGetBytes = { url ->
            requested += url
            pngBytes(Color.RED)
        }
        try {
            val pano = StreetViewPano(
                panoId = "pano-stitch",
                lat = 37.0, lng = -122.0,
                tileSize = 8,
                levelDims = listOf(16 to 16),
            )
            val bmp = StreetViewDataSource.loadPanorama(pano)
            assertNotNull(bmp)
            assertEquals(16, bmp.width)
            assertEquals(16, bmp.height)
            // 2x2 grid of 8px tiles at zoom 0
            assertEquals(4, requested.size)
            assertTrue(requested.all { it.contains("pano-stitch") })
        } finally {
            StreetViewDataSource.httpGetBytes = originalBytes
        }
    }

    @Test
    fun `load panorama returns null when every tile fails`() = runBlocking {
        val originalBytes = StreetViewDataSource.httpGetBytes
        StreetViewDataSource.httpGetBytes = { null }
        try {
            val pano = StreetViewPano(
                panoId = "pano-empty",
                lat = 37.0, lng = -122.0,
                tileSize = 8,
                levelDims = listOf(16 to 8),
            )
            assertNull(StreetViewDataSource.loadPanorama(pano))
        } finally {
            StreetViewDataSource.httpGetBytes = originalBytes
        }
    }

    @Test
    fun `load panorama tolerates partial and undecodable tiles`() = runBlocking {
        val originalBytes = StreetViewDataSource.httpGetBytes
        var calls = 0
        StreetViewDataSource.httpGetBytes = { _ ->
            calls++
            when (calls % 3) {
                0 -> null
                1 -> byteArrayOf(9, 9, 9) // undecodable
                else -> pngBytes(Color.BLUE)
            }
        }
        try {
            // no levelDims: the modern 512-based pyramid shape is assumed and
            // the level picker must stay within the tile-width cap
            val pano = StreetViewPano(
                panoId = "pano-partial",
                lat = 37.0, lng = -122.0,
                tileSize = 512,
            )
            val bmp = StreetViewDataSource.loadPanorama(pano)
            assertNotNull(bmp)
            assertTrue(calls > 1)
        } finally {
            StreetViewDataSource.httpGetBytes = originalBytes
        }
    }

    @Test
    fun `nearest and byPano serve resolved panos from cache`() = runBlocking {
        val originalGet = StreetViewDataSource.httpGet
        var fetches = 0
        StreetViewDataSource.httpGet = { _ ->
            fetches++
            // minimal SingleImageSearch-shaped payload is covered exhaustively
            // by the first pass; here an unparseable body exercises the
            // negative-cache path of nearest/byPano themselves
            "not a pano payload"
        }
        try {
            val lat = 51.5007 + (System.nanoTime() % 1000) / 1_000_000.0
            assertNull(StreetViewDataSource.nearest(lat, -0.1246))
            val afterFirst = fetches
            assertNull(StreetViewDataSource.nearest(lat, -0.1246))
            assertEquals(afterFirst, fetches)
            val id = "missing-${System.nanoTime()}"
            assertNull(StreetViewDataSource.byPano(id))
            val afterByPano = fetches
            assertNull(StreetViewDataSource.byPano(id))
            assertEquals(afterByPano, fetches)
        } finally {
            StreetViewDataSource.httpGet = originalGet
        }
    }
}
