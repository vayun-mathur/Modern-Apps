package com.vayunmathur.maps.util

import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Slice 3/3 extra: RegionalUnits locale logic + MapsUiContract defaults. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegionalAndContractSlice3ExtraTest {

    @Test fun imperialForUsAndUkMetricElsewhere() {
        assertTrue(isImperialUnits(Locale.US))
        assertTrue(isImperialUnits(Locale.UK))
        assertFalse(isImperialUnits(Locale.GERMANY))
        assertFalse(isImperialUnits(Locale.FRANCE))
        assertFalse(isImperialUnits(Locale.JAPAN))
    }

    @Test fun distanceFormattingUsesRegionalUnits() {
        // US locale: miles/feet; German locale: km/m. Just assert non-blank
        // and that the large distance differs by locale (unit system switch).
        val us = formatDistance(5000.0, Locale.US)
        val de = formatDistance(5000.0, Locale.GERMANY)
        assertTrue(us.isNotBlank(), "us=$us")
        assertTrue(de.isNotBlank(), "de=$de")
        assertTrue(us != de, "us=$us de=$de")
        assertTrue(formatDistance(100.0, Locale.US).isNotBlank())
        assertTrue(formatDistance(100.0, Locale.GERMANY).isNotBlank())
    }

    @Test fun durationFormattingRendersLargestUnits() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val long = formatDuration(ctx, 1.hours + 12.minutes, Locale.US)
        assertTrue(long.contains("1"), "long=$long")
        assertTrue(formatDuration(ctx, 45.minutes, Locale.US).isNotBlank())
        // Under a minute renders the dedicated string resource, non-blank.
        assertTrue(formatDuration(ctx, 0.minutes, Locale.US).isNotBlank())
    }

    @Test fun noopSearchActionsAreSafe() {
        val noop = SearchActions.Noop
        // Every default must be a safe no-op (preview contract).
        noop.setQuery("x")
        noop.selectResult(SearchResult("1", "A", null, 1.0, 2.0, null))
        noop.pickContactAddress("1 Main St")
        noop.clearRecents()
        noop.back()
        // SearchUiState defaults: Idle phase.
        assertEquals(SearchPhase.Idle, SearchUiState().phase)
        assertEquals("", SearchUiState().query)
        assertTrue(SearchUiState().results.isEmpty())
    }
}
