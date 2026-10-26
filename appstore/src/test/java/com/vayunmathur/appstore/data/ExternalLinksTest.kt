package com.vayunmathur.appstore.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F-Droid listing URLs end in a trailing slash (`.../packages/<pkg>/`), so taking
 * the text after the last `/` yields an empty string and the launch falls through
 * to the home page instead of the app's detail page.
 */
class ExternalLinksTest {

    @Test
    fun `fdroid localised url with trailing slash resolves the package`() {
        assertEquals(
            "org.fdroid.fdroid",
            ExternalLinks.parseExternalPackage("https://f-droid.org/en/packages/org.fdroid.fdroid/"),
        )
    }

    @Test
    fun `fdroid url without trailing slash resolves the package`() {
        assertEquals(
            "org.fdroid.fdroid",
            ExternalLinks.parseExternalPackage("https://f-droid.org/en/packages/org.fdroid.fdroid"),
        )
    }

    @Test
    fun `fdroid url with query resolves the package`() {
        assertEquals(
            "org.fdroid.fdroid",
            ExternalLinks.parseExternalPackage(
                "https://f-droid.org/en/packages/org.fdroid.fdroid/?ref=share",
            ),
        )
    }

    @Test
    fun `fdroid bare packages page resolves nothing`() {
        assertNull(ExternalLinks.parseExternalPackage("https://f-droid.org/en/packages/"))
        assertNull(ExternalLinks.parseExternalPackage("https://f-droid.org/"))
    }

    @Test
    fun `market intent resolves the package`() {
        assertEquals(
            "com.example.app",
            ExternalLinks.parseExternalPackage("market://details?id=com.example.app"),
        )
    }

    @Test
    fun `play url resolves the package`() {
        assertEquals(
            "com.example.app",
            ExternalLinks.parseExternalPackage(
                "https://play.google.com/store/apps/details?id=com.example.app&hl=en",
            ),
        )
    }

    @Test
    fun `unrelated url resolves nothing`() {
        assertNull(ExternalLinks.parseExternalPackage("https://example.com/some/page"))
        assertNull(ExternalLinks.parseExternalPackage(null))
    }
}
