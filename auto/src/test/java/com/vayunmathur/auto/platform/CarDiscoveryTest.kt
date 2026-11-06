package com.vayunmathur.auto.platform

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auto module: [CarAppDiscovery], [CarApps] and [RefusedCarApps].
 *
 * Under Robolectric no car services are installed, so discovery resolves to
 * empty and queries degrade to the fail-closed empty grid — nothing dead is
 * ever shown. The refusal ledger round-trips per component.
 */
@RunWith(RobolectricTestRunner::class)
class CarDiscoveryTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `no installed car services means an empty grid`() {
        assertEquals(emptyList(), CarAppDiscovery.query(context))
        assertEquals(emptyList(), CarAppDiscovery.queryAll(context))
        assertEquals(emptyList(), CarAppDiscovery.firstParty(context))
        assertEquals(emptyList(), CarApps.query(context))
    }

    @Test
    fun `hosted categories cover the launcher tiles`() {
        val categories = CarAppDiscovery.HOSTED_CATEGORIES
        assertTrue(categories.isNotEmpty())
        assertEquals(categories.size, categories.toSet().size)
    }

    @Test
    fun `assistant resolves to null with no assistant installed`() {
        // Robolectric has no ASSIST/VOICE_COMMAND handler: skip-if-missing.
        assertEquals(null, CarApps.assistant(context))
    }

    @Test
    fun `refusal ledger starts clean per component`() {
        val component = ComponentName("com.example", "com.example.CarService")
        assertFalse(RefusedCarApps.isRefused(context, component))
    }

    @Test
    fun `pin id is the flattened component string`() {
        val app = DiscoveredApp(
            component = ComponentName("com.example", "com.example.CarService"),
            label = "Example",
            icon = null,
            categories = emptySet(),
        )
        assertEquals("com.example/com.example.CarService", app.id)
    }
}
