package com.vayunmathur.web.platform

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.web.domain.ShieldLevel
import com.vayunmathur.web.domain.ShieldsSettings
import com.vayunmathur.web.platform.shields.CosmeticResources
import com.vayunmathur.web.platform.shields.FarblingConfig
import com.vayunmathur.web.platform.shields.ShieldsInjection
import com.vayunmathur.web.platform.shields.ShieldsRequestFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ShieldsAndPlatformHelpersTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun farblingConfigJsonEscapesAndResolves() {
        val cfg = FarblingConfig.of(
            ShieldsSettings.AGGRESSIVE_DEFAULTS,
            mapOf("x.example" to ShieldsSettings(fingerprintProtection = false)),
        )
        assertTrue(cfg.enabled)
        assertTrue(cfg.aggressive)
        assertEquals(false, cfg.overrides["x.example"]?.first)
        val json = cfg.toJson()
        assertTrue(json.contains("\"enabled\":true"), json)
        assertTrue(json.contains("x.example"), json)
        val off = FarblingConfig.of(ShieldsSettings(level = ShieldLevel.OFF), emptyMap())
        assertFalse(off.enabled)
        assertTrue(off.toJson().contains("\"overrides\":{}"), off.toJson())
    }

    @Test fun jsStringEscapesSpecials() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u003c\"", ShieldsInjection.jsString("a\"b\\c\nd<"))
        assertTrue(ShieldsInjection.jsString("x\u2028y").contains("\\u2028"))
    }

    @Test fun hideCssChunksAndEmpty() {
        assertEquals("", ShieldsInjection.hideCss(emptyList()))
        val css = ShieldsInjection.hideCss(listOf(".ad", "#banner"))
        assertTrue(css.contains(".ad,#banner{display:none !important;}"), css)
        assertTrue(css.contains("__shields_css__"), css)
        val many = ShieldsInjection.hideCss((1..300).map { ".c$it" })
        assertTrue(many.split("{display:none").size >= 3, "chunked into multiple rules")
    }

    @Test fun cosmeticPayloadVariants() {
        assertEquals("", ShieldsInjection.cosmetic(null))
        val empty = ShieldsInjection.cosmetic(CosmeticResources(hide = emptyList(), script = "", genericHide = true, exceptions = emptyList()))
        assertTrue(empty.contains("__shieldsCosmetic"), empty)
        val full = ShieldsInjection.cosmetic(
            CosmeticResources(hide = listOf(".ad"), script = "window.x=1;", genericHide = false, exceptions = listOf(".keep"))
        )
        assertTrue(full.contains("window.x=1"), full)
        assertTrue(full.contains(".ad"), full)
        assertTrue(full.contains("MutationObserver"), full)
    }

    @Test fun farblingScriptEmbedsConfigAndSalt() {
        val cfg = FarblingConfig(enabled = true, aggressive = false, overrides = emptyMap())
        val js = ShieldsInjection.farbling(context, cfg, 42)
        assertTrue(js.contains("__shieldsFarbled"), js.take(200))
        assertTrue(js.contains("42|0"), js)
        assertTrue(js.contains("\"enabled\":true"), js)
    }

    @Test fun emptyResponsesHaveMatchingMime() {
        assertEquals("image/gif", ShieldsRequestFilter.emptyResponse("image").mimeType)
        assertEquals("application/javascript", ShieldsRequestFilter.emptyResponse("script").mimeType)
        assertEquals("text/css", ShieldsRequestFilter.emptyResponse("stylesheet").mimeType)
        assertEquals("video/mp4", ShieldsRequestFilter.emptyResponse("media").mimeType)
        assertEquals("application/json", ShieldsRequestFilter.emptyResponse("xmlhttprequest").mimeType)
        assertEquals("text/html", ShieldsRequestFilter.emptyResponse("subdocument").mimeType)
        assertEquals("text/plain", ShieldsRequestFilter.emptyResponse("font").mimeType)
        assertEquals(200, ShieldsRequestFilter.emptyResponse("script").statusCode)
    }

    private fun throwingContext(): android.content.ContextWrapper =
        object : android.content.ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                throw android.content.ActivityNotFoundException("no handler in test")
            }
        }

    @Test fun externalUriToUnhandledSchemeReturnsFalse() {
        var fallback: String? = null
        val handled = openExternalUri(throwingContext(), "myscheme://do/thing") { fallback = it }
        assertFalse(handled)
        assertEquals(null, fallback)
    }

    @Test fun externalIntentUriWithFallbackUsesFallbackWhenUnhandled() {
        var fallback: String? = null
        val intentUrl = "intent://scan/#Intent;scheme=zxing;package=com.nonexistent.app.xyz;S.browser_fallback_url=https%3A%2F%2Ffallback.example%2F;end"
        val handled = openExternalUri(throwingContext(), intentUrl) { fallback = it }
        assertTrue(handled)
        assertEquals("https://fallback.example/", fallback)
    }

    @Test fun externalUriHandledWhenActivityStarts() {
        val handled = openExternalUri(context, "tel:12345") {}
        assertTrue(handled)
        assertNotNull(shadowOf(context as android.app.Application).nextStartedActivity)
    }

    @Test fun launchNewWebWindowStartsMainActivity() {
        launchNewWebWindow(context, incognito = true)
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(true, started.getBooleanExtra(com.vayunmathur.web.MainActivity.EXTRA_INCOGNITO, false))
        assertNotNull(started.getStringExtra(com.vayunmathur.web.MainActivity.EXTRA_WINDOW_ID))
    }

    @Test fun fileChooserHelpers() {
        assertTrue(listOf("image/*").toLaunchArray().contentEquals(arrayOf("image/*")))
        assertTrue(listOf("a", "b").toLaunchArray().contentEquals(arrayOf("a", "b")))
        // Blank entries are dropped and an empty list falls back to a wildcard pick.
        assertTrue(listOf("", " ").toLaunchArray().contentEquals(arrayOf("*/*")))
        assertTrue(emptyList<String>().toLaunchArray().contentEquals(arrayOf("*/*")))
    }
}
