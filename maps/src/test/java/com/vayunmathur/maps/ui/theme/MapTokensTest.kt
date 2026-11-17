package com.vayunmathur.maps.ui.theme
import com.vayunmathur.maps.data.nearest
import com.vayunmathur.maps.data.string

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [toStyleHex] was written to feed MapLibre, which parses CSS colour syntax, and the trap it
 * closed was that `#aarrggbb` — the form Android's own `Color.parseColor` accepts — is not
 * valid CSS, so an alpha colour serialized the obvious way was silently mis-parsed.
 *
 * That consumer is gone, and the rationale has inverted. The only live caller is now
 * `RouteLayer`, which hands the string to `android.graphics.Color.parseColor` — and that
 * parser accepts `#rrggbb` and `#aarrggbb` but *rejects* `rgba(...)`, which it throws on.
 * So the alpha branch these tests pin now emits the one form its only consumer cannot read.
 *
 * There is no live bug: every route and traffic token is fully opaque (`0xFF…` at
 * `MapTokens.kt:109-112,132-133,145-148,170-171`), so the `#rrggbb` branch is the only one
 * reached. The failure mode if that changes is silent rather than loud —
 * `RouteLayer.kt:66` wraps the parse in `runCatching { … }.getOrDefault(0xFF1710F1)`, so a
 * translucent token would not crash; every affected segment would just quietly draw opaque
 * blue, collapsing the jam/slow/free traffic colouring into one wrong colour.
 *
 * The assertions below are still correct — they pin what [toStyleHex] actually does — so
 * they stay. Giving a route or traffic token an alpha is what needs care, and if the rgba
 * branch ever loses its last justification the function should shed it rather than keep a
 * form nothing can parse.
 */
class MapTokensTest {

    @Test
    fun `opaque colours render as six-digit hex`() {
        assertEquals("#ff0000", Color(0xFFFF0000).toStyleHex())
        assertEquals("#000000", Color(0xFF000000).toStyleHex())
        assertEquals("#ffffff", Color(0xFFFFFFFF).toStyleHex())
        assertEquals("#1b1d22", Color(0xFF1B1D22).toStyleHex())
    }

    @Test
    fun `translucent colours render as rgba, never as eight-digit hex`() {
        // 0.5f is stored as 128/255, so the emitted value is the quantized one rounded to
        // two places — that is the whole reason precision is capped there.
        val hex = Color(0xFFFF0000).copy(alpha = 0.5f).toStyleHex()
        assertEquals("rgba(255,0,0,0.5)", hex)
        assertTrue(!hex.startsWith("#"), "alpha must not be emitted as hex: $hex")
    }

    /** No live caller reaches the alpha path today; this pins the behaviour regardless. */
    @Test
    fun `a low alpha keeps two decimals`() {
        assertEquals("rgba(255,0,0,0.12)", Color(0xFFFF0000).copy(alpha = 0.12f).toStyleHex())
    }

    @Test
    fun `formatting does not depend on the default locale`() {
        val original = java.util.Locale.getDefault()
        try {
            // A comma-decimal locale would otherwise emit "rgba(255,0,0,0,5)" — a
            // four-argument rgba that no parser accepts.
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("rgba(255,0,0,0.5)", Color(0xFFFF0000).copy(alpha = 0.5f).toStyleHex())
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `a fully transparent colour still round-trips as rgba`() {
        assertEquals("rgba(0,0,0,0)", Color.Transparent.toStyleHex())
    }

    /** Rounding, not truncation — 0.5/255 must not fall a channel short. */
    @Test
    fun `channels round to nearest`() {
        assertEquals("#808080", Color(0xFF808080).toStyleHex())
    }

    @Test
    fun `the two palettes differ everywhere it matters`() {
        val light = mapTokens(isDark = false)
        val dark = mapTokens(isDark = true)
        assertTrue(light.traffic.jam != dark.traffic.jam, "traffic ramp must adapt to the basemap")
        assertTrue(light.traffic.free != dark.traffic.free)
        assertTrue(light.transitMode.subway != dark.transitMode.subway)
        assertTrue(light.routeInert != dark.routeInert)
    }

    /** A road sign has no dark variant, and this is where that stops being a comment. */
    @Test
    fun `the speed limit sign is identical in both palettes`() {
        assertEquals(mapTokens(isDark = false).speedSign, mapTokens(isDark = true).speedSign)
        assertEquals(SpeedSign, mapTokens(isDark = true).speedSign)
    }
}
