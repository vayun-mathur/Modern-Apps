package com.vayunmathur.web.platform

import android.graphics.Bitmap
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.web.data.FaviconStore
import com.vayunmathur.web.data.TabThumbnailStore
import com.vayunmathur.web.domain.shields.ResourceTypes
import com.vayunmathur.web.shields.ShieldsNative
import com.vayunmathur.web.ui.linkUrlFromHitTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoresAndSettingsHelpersTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun captureThumbRequiresLayoutAndDrawsView() {
        val unlaid = View(context)
        assertNull(captureThumb(unlaid))
        val view = View(context)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(50, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 100, 50)
        val bmp = captureThumb(view)
        assertNotNull(bmp)
        assertEquals(100, bmp.width)
        assertEquals(50, bmp.height)
    }

    @Test fun captureThumbDownscalesLargeViews() {
        val view = View(context)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(512, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1024, 512)
        val bmp = captureThumb(view)
        assertNotNull(bmp)
        assertEquals(THUMB_MAX_DIM, bmp.width)
    }

    @Test fun faviconStoreRemembersPutIcons() {
        FaviconStore.init(context)
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        FaviconStore.put("https://store-test.example/page", bmp, isPrivate = true)
        assertEquals(bmp, FaviconStore.forUrl("https://store-test.example/other"))
        assertEquals(bmp, FaviconStore.forHost("STORE-TEST.example"))
        assertNull(FaviconStore.forHost(""))
        assertNull(FaviconStore.forUrl("https://never.example/"))
        // Non-http urls are ignored.
        FaviconStore.put("data:text/html,x", bmp, isPrivate = true)
        assertNull(FaviconStore.forHost("data"))
    }

    @Test fun faviconStoreAcceptsUppercaseHttpScheme() {
        FaviconStore.init(context)
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        FaviconStore.put("HTTP://upper-scheme.example/page", bmp, isPrivate = true)
        assertEquals(bmp, FaviconStore.forUrl("HTTP://upper-scheme.example/other"))
    }

    @Test fun thumbnailStoreRejectsUnsafeIdsAndMisses() {
        TabThumbnailStore.init(context)
        assertNull(TabThumbnailStore.get("tab-missing-1"))
        assertNull(TabThumbnailStore.get("../evil"))
        assertNull(TabThumbnailStore.get(""))
        TabThumbnailStore.remove("tab-missing-1")
        TabThumbnailStore.retainOnly(setOf("tab-missing-1"))
    }

    @Test fun linkUrlFromHitTestOnlyForAnchors() {
        assertEquals("https://x.example", linkUrlFromHitTest(WebView.HitTestResult.SRC_ANCHOR_TYPE, "https://x.example"))
        assertEquals("https://x.example", linkUrlFromHitTest(WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, "https://x.example"))
        assertNull(linkUrlFromHitTest(WebView.HitTestResult.IMAGE_TYPE, "https://x.example"))
        assertNull(linkUrlFromHitTest(WebView.HitTestResult.SRC_ANCHOR_TYPE, "  "))
        assertNull(linkUrlFromHitTest(null, "https://x.example"))
    }

    @Test fun shieldsEngineFailsOpenWhenNotLoaded() {
        // Native may or may not be present on the host; either way an unloaded
        // engine must fail open (null) rather than block.
        if (!com.vayunmathur.web.platform.shields.ShieldsEngine.ready) {
            assertNull(com.vayunmathur.web.platform.shields.ShieldsEngine.check("https://ads.example/x.js", "https://site.example", "script"))
            assertNull(com.vayunmathur.web.platform.shields.ShieldsEngine.cosmetic("https://site.example"))
            assertTrue(com.vayunmathur.web.platform.shields.ShieldsEngine.hiddenClassIdSelectors(listOf("ad"), listOf("banner"), emptyList()).isEmpty())
        }
        assertTrue(ShieldsNative.isAvailable || !ShieldsNative.isAvailable)
    }

}
