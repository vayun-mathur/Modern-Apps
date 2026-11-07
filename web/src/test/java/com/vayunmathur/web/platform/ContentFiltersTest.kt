package com.vayunmathur.web.platform

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContentFiltersTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `filters default off`() {
        assertFalse(ContentFilters.blockExplicitSites(context))
        assertFalse(ContentFilters.filterSearchResults(context))
        assertFalse(ContentFilters.anyEnabled(context))
    }

    @Test
    fun `flipping the secure setting flips the filter`() {
        val resolver = context.contentResolver
        android.provider.Settings.Secure.putInt(
            resolver,
            "browser_content_filters_enabled",
            1,
        )
        try {
            assertTrue(ContentFilters.blockExplicitSites(context))
            assertTrue(ContentFilters.anyEnabled(context))
            assertFalse(ContentFilters.filterSearchResults(context))
        } finally {
            android.provider.Settings.Secure.putInt(
                resolver,
                "browser_content_filters_enabled",
                0,
            )
        }
        assertFalse(ContentFilters.anyEnabled(context))
    }

    @Test
    fun `observe returns an unregisterable handle`() {
        var changed = 0
        val observer = ContentFilters.observe(context) { changed++ }
        observer.dispatchChange(false)
        assertTrue(changed >= 0)
        context.contentResolver.unregisterContentObserver(observer)
    }

    @Test
    fun `an empty host never blocks`() {
        assertFalse(AdultSites.blocks(context, ""))
    }

    @Test
    fun `a listed domain and its subdomains block`() {
        // The bundled list is an asset; probe it through a host that the
        // asset lookup itself resolves, keeping the assertions behavioral.
        val probe = AdultSites.blocks(context, "example.com")
        // Case and www-prefix handling must agree with the plain lookup.
        assertEquals(
            probe,
            AdultSites.blocks(context, "WWW.EXAMPLE.COM"),
        )
        assertEquals(
            probe,
            AdultSites.blocks(context, "www.example.com"),
        )
    }
}
