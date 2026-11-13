package com.vayunmathur.games.voxels.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BlockNamesTest {
    // Names now come from string resources so they can be translated; every catalog entry needs one.
    @Test fun `every catalog block and item has a translatable name`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val all = catalogNatural + catalogOres + catalogOcean + catalogItems + catalogBlessings + catalogGear +
            catalogMusic + catalogBuilding
        val missing = all.filter { id -> blockNameRes[id]?.let { ctx.getString(it).isNotBlank() } != true }
        assertTrue(missing.isEmpty(), "no name resource for ids $missing")
    }
}
