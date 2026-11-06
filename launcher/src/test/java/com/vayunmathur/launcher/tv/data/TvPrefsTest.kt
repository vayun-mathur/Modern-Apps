package com.vayunmathur.launcher.tv.data

import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [TvPrefs]: row order and quick-settings round-trip through
 * DataStore, starting from [TvSettings] defaults.
 */
@RunWith(RobolectricTestRunner::class)
class TvPrefsTest {

    private fun prefs(): TvPrefs {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TvPrefs(ctx)
    }

    @BeforeTest
    fun clearStore() {
        // DataStore persists per context file: wipe it so tests are independent.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("tvlauncher_config.preferences_pb", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        java.io.File(ctx.filesDir.parent, "datastore/tvlauncher_config.preferences_pb").delete()
    }

    @Test fun defaultsAreEmpty() = runTest {
        val s = prefs().settings.first()
        assertNull(s.appOrder)
        assertEquals(emptyList(), s.quickSettings)
    }

    @Test fun orderRoundTrip() = runTest {
        val p = prefs()
        p.saveOrder(listOf("com.example.a", "com.example.b"))
        assertEquals(listOf("com.example.a", "com.example.b"), p.settings.first().appOrder)
    }

    @Test fun orderOverwrite() = runTest {
        val p = prefs()
        p.saveOrder(listOf("com.example.a"))
        p.saveOrder(listOf("com.example.b", "com.example.c"))
        assertEquals(listOf("com.example.b", "com.example.c"), p.settings.first().appOrder)
    }

    @Test fun quickRoundTrip() = runTest {
        val p = prefs()
        p.saveQuick(listOf("Bluetooth", "SOUND"))
        // Quick settings are normalised to lowercase.
        assertEquals(listOf("bluetooth", "sound"), p.settings.first().quickSettings)
    }
}
