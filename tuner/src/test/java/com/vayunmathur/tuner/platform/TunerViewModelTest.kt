package com.vayunmathur.tuner.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ViewModel state lifecycle without a real microphone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TunerViewModelTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun initialStateLoadsCatalogueDefaults() {
        val vm = TunerViewModel(app)
        assertTrue(vm.state.instruments.isNotEmpty())
        assertEquals(vm.state.instruments.first(), vm.state.instrument)
        assertFalse(vm.state.listening)
        assertNull(vm.state.failure)
        assertNull(vm.state.note.readout)
        assertNull(vm.state.chord.reading)
        assertTrue(vm.state.chord.voicings.isEmpty())
    }

    @Test
    fun selectInstrumentSwitchesAndIgnoresUnknown() {
        val vm = TunerViewModel(app)
        val first = vm.state.instrument
        val other = vm.state.instruments.firstOrNull { it.id != first.id }
        assertNotNull(other)
        vm.selectInstrument(other.id)
        assertEquals(other.id, vm.state.instrument.id)
        assertNull(vm.state.note.readout)
        // Unknown id is a no-op, keeping the current instrument.
        vm.selectInstrument("no-such-instrument")
        assertEquals(other.id, vm.state.instrument.id)
        // Re-selecting the first instrument works too.
        vm.selectInstrument(first.id)
        assertEquals(first.id, vm.state.instrument.id)
    }

    @Test
    fun stopAndBackgroundClearReadouts() {
        val vm = TunerViewModel(app)
        vm.stop()
        assertFalse(vm.state.listening)
        assertNull(vm.state.note.readout)
        vm.onBackground()
        assertFalse(vm.state.listening)
        // Foreground without a prior start must not open capture intent:
        // state stays not-listening until frames arrive (none in Robolectric).
        vm.onForeground()
        assertNull(vm.state.note.readout)
    }
}
