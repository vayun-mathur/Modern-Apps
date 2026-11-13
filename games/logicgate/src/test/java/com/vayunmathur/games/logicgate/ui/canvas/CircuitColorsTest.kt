package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircuitColorsTest {

    private fun CircuitColors.signals() = mapOf(
        "wireOff" to wireOff, "wireOn" to wireOn, "bus4" to bus4, "bus8" to bus8,
        "selected" to selected, "ok" to ok, "error" to error, "gateStroke" to gateStroke,
    )

    @Test
    fun contrastRatioMatchesWcag() {
        assertEquals(21f, Contrast.ratio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, Contrast.ratio(Color.Red, Color.Red), 0.001f)
        assertEquals(Contrast.ratio(Color.Blue, Color.Yellow), Contrast.ratio(Color.Yellow, Color.Blue))
    }

    @Test
    fun aReadableColourIsLeftAlone() {
        val amber = Color(0xFFFFE082)
        assertEquals(amber, Contrast.ensure(amber, Color(0xFF0E1724)))
    }

    @Test
    fun aFaintColourIsDarkenedOnLightAndLightenedOnDarkJustEnough() {
        val amber = Color(0xFFFFE082)
        val onWhite = Contrast.ensure(amber, Color.White)
        assertTrue(Contrast.ratio(onWhite, Color.White) >= Contrast.GRAPHICS)
        assertTrue(Contrast.ratio(onWhite, Color.White) < Contrast.GRAPHICS + 0.1f, "smallest change that passes")
        val slate = Color(0xFF3B4E66)
        val onNavy = Contrast.ensure(slate, Color(0xFF0E1724))
        assertTrue(Contrast.ratio(onNavy, Color(0xFF0E1724)) >= Contrast.GRAPHICS)
    }

    @Test
    fun everySignalColourIsVisibleOnALightTheme() {
        val scheme = lightColorScheme()
        CircuitColors.from(scheme).signals().forEach { (name, c) ->
            val r = Contrast.ratio(c, scheme.background)
            assertTrue(r >= Contrast.GRAPHICS, "$name only reaches $r:1 on the light background")
        }
    }

    @Test
    fun theShippedDarkBoardKeepsItsHighlights() {
        val scheme = darkColorScheme(background = Color(0xFF0E1724), primary = Color(0xFF3DD8C2))
        val colors = CircuitColors.from(scheme)
        assertEquals(Color(0xFFFFE082), colors.selected)
        assertEquals(Color(0xFF81C784), colors.ok)
        assertEquals(Color(0xFF7FA8FF), colors.bus8)
        assertEquals(Color(0xFF3DD8C2), colors.wireOn)
        colors.signals().forEach { (name, c) ->
            assertTrue(Contrast.ratio(c, scheme.background) >= Contrast.GRAPHICS, "$name on the dark board")
        }
    }
}
