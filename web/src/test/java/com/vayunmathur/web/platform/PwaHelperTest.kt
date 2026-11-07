package com.vayunmathur.web.platform

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PwaHelperTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun parsesEscapedProbeJson() {
        val raw = """{"title":"Example App","name":"Example","shortName":"Ex","iconUrl":"https://x.example/icon.png","faviconUrl":"https://x.example/f.ico","themeColor":"#fff","bgColor":"#000","displayMode":"standalone","startUrl":"https://x.example/","manifestUrl":"https://x.example/m.json","hasManifest":true,"origin":"https://x.example"}"""
        val escaped = "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val info = PwaHelper.parseProbeJson(escaped)
        assertNotNull(info)
        assertEquals("Example", info.name)
        assertEquals("Ex", info.shortName)
        assertEquals("https://x.example/icon.png", info.iconUrl)
        assertEquals("#fff", info.themeColor)
        assertEquals("standalone", info.displayMode)
        assertTrue(info.hasManifest)
        assertEquals("https://x.example", info.origin)
    }

    @Test fun parsesPlainProbeJsonAndDefaults() {
        val info = PwaHelper.parseProbeJson("""{"title":"Only Title","origin":"https://y.example"}""")
        assertNotNull(info)
        assertEquals("Only Title", info.name)
        assertEquals("browser", info.displayMode)
        assertEquals("", info.shortName)
    }

    @Test fun rejectsEmptyProbe() {
        assertNull(PwaHelper.parseProbeJson(null))
        assertNull(PwaHelper.parseProbeJson(""))
        assertNull(PwaHelper.parseProbeJson("\"null\""))
        assertNull(PwaHelper.parseProbeJson("""{"title":"","origin":""}"""))
    }

    @Test fun displayTitlePrefersNameThenTitleThenHost() {
        val info = PwaInfo(name = "My App", origin = "https://x.example")
        assertEquals("My App", PwaHelper.displayTitle(info, "Fallback", "https://x.example/page"))
        assertEquals("Fallback", PwaHelper.displayTitle(null, "Fallback", "https://x.example/page"))
        assertEquals("x.example", PwaHelper.displayTitle(null, "", "https://x.example/page"))
        assertEquals("Site", PwaHelper.displayTitle(null, "", ""))
        // Long names are truncated to 48 chars.
        val long = PwaInfo(name = "A".repeat(80), origin = "https://x.example")
        assertEquals(48, PwaHelper.displayTitle(long, "", "https://x.example").length)
    }

    @Test fun shortcutIdIsStableAndUrlSensitive() {
        assertEquals(PwaHelper.shortcutId("https://x.example/a"), PwaHelper.shortcutId("https://x.example/a"))
        assertTrue(PwaHelper.shortcutId("https://x.example/a") != PwaHelper.shortcutId("https://x.example/b"))
        assertTrue(PwaHelper.shortcutId("https://x.example/a").startsWith("pwa_"))
    }

    @Test fun createPwaIntentCarriesUrlAndTitle() {
        val intent = PwaHelper.createPwaIntent(context, "https://x.example/app", "My App")
        assertEquals("https://x.example/app", intent.getStringExtra(PwaActivity.EXTRA_URL))
        assertEquals("My App", intent.getStringExtra(PwaActivity.EXTRA_TITLE))
        assertEquals("https://x.example/app", intent.dataString)
    }

    @Test fun textIconBitmapUsesFirstLetter() {
        val bmp = PwaHelper.textIconBitmap("hello", 48)
        assertEquals(48, bmp.width)
        assertEquals(48, bmp.height)
        assertEquals(Bitmap.Config.ARGB_8888, bmp.config)
        val blank = PwaHelper.textIconBitmap("   ", 24)
        assertEquals(24, blank.width)
    }

    @Test fun manifestProbeJsMentionsManifestAndIcons() {
        assertTrue(PwaHelper.MANIFEST_PROBE_JS.contains("manifest"))
        assertTrue(PwaHelper.MANIFEST_PROBE_JS.contains("JSON.stringify"))
    }
}
