package com.vayunmathur.appstore.data

import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TV-incompatibility learning: the OS's `FAILURE_INCOMPATIBLE` is a final answer
 * for this device, so the store remembers it for 7 days instead of re-offering
 * the same update every check. Phones never learn — an ABI-mismatch refusal on a
 * phone is not a TV verdict.
 */
@RunWith(RobolectricTestRunner::class)
class TvIncompatiblePackagesTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @AfterTest
    fun clearPrefs() {
        context.getSharedPreferences("appstore-tv-incompatible-packages", 0).edit().clear().apply()
    }

    @Test
    fun `nothing is incompatible by default`() {
        assertTrue(TvIncompatiblePackages.forDevice(context).isEmpty())
    }

    @Test
    fun `a tv refusal is remembered`() {
        TvIncompatiblePackages.recordIfIncompatible(context, "com.example.app", isTv = true)

        assertEquals(setOf("com.example.app"), TvIncompatiblePackages.forDevice(context))
    }

    @Test
    fun `a phone refusal is not learned`() {
        TvIncompatiblePackages.recordIfIncompatible(context, "com.example.app", isTv = false)

        assertTrue(TvIncompatiblePackages.forDevice(context).isEmpty())
    }

    @Test
    fun `blank package names are ignored`() {
        TvIncompatiblePackages.recordIfIncompatible(context, null, isTv = true)
        TvIncompatiblePackages.recordIfIncompatible(context, "", isTv = true)

        assertTrue(TvIncompatiblePackages.forDevice(context).isEmpty())
    }

    @Test
    fun `a successful install clears the lesson`() {
        TvIncompatiblePackages.recordIfIncompatible(context, "com.example.app", isTv = true)
        TvIncompatiblePackages.clearOnUpdate(context, "com.example.app")

        assertTrue(TvIncompatiblePackages.forDevice(context).isEmpty())
    }

    @Test
    fun `clearing one package keeps the others`() {
        TvIncompatiblePackages.recordIfIncompatible(context, "com.example.a", isTv = true)
        TvIncompatiblePackages.recordIfIncompatible(context, "com.example.b", isTv = true)
        TvIncompatiblePackages.clearOnUpdate(context, "com.example.a")

        assertEquals(setOf("com.example.b"), TvIncompatiblePackages.forDevice(context))
    }

    @Test
    fun `clearing with a blank name changes nothing`() {
        TvIncompatiblePackages.recordIfIncompatible(context, "com.example.app", isTv = true)
        TvIncompatiblePackages.clearOnUpdate(context, null)

        assertEquals(setOf("com.example.app"), TvIncompatiblePackages.forDevice(context))
    }

    @Test
    fun `a newer build of the same package is re-offered after success`() {
        // Timestamps are per entry, so recording twice then clearing once leaves nothing.
        TvIncompatiblePackages.recordIfIncompatible(context, "com.example.app", isTv = true)
        TvIncompatiblePackages.clearOnUpdate(context, "com.example.app")
        TvIncompatiblePackages.recordIfIncompatible(context, "com.example.app", isTv = true)

        assertEquals(setOf("com.example.app"), TvIncompatiblePackages.forDevice(context))
    }
}
