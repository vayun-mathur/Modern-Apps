package com.vayunmathur.astronomy.ui

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.astronomy.data.model.DeepSkyObject
import com.vayunmathur.astronomy.data.model.Star
import com.vayunmathur.astronomy.domain.engine.AltAz
import com.vayunmathur.astronomy.domain.engine.RaDec
import com.vayunmathur.astronomy.domain.projection.ViewState
import com.vayunmathur.astronomy.platform.ConstellationLine
import com.vayunmathur.astronomy.platform.TrajectoryPoint
import com.vayunmathur.astronomy.platform.VisibleArt
import com.vayunmathur.astronomy.platform.VisibleArtAnchor
import com.vayunmathur.astronomy.platform.VisibleDeepSky
import com.vayunmathur.astronomy.platform.VisibleMoon
import com.vayunmathur.astronomy.platform.VisiblePlanet
import com.vayunmathur.astronomy.platform.VisibleSky
import com.vayunmathur.astronomy.platform.VisibleStar
import com.vayunmathur.astronomy.platform.VisibleSun
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Draw-path tests for [SkyCanvas]: a fully populated sky (stars of several
 * colours, planets, sun, moon, deep-sky, constellation lines and figure
 * art, a trajectory and a selection ring) is rasterised with native
 * graphics so every layer's draw code runs, and tap/drag gestures route
 * to the right callbacks.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkyCanvasDrawTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Sized to Robolectric's default 320x470 display so projected points
    // land where touch injection expects them.
    private val viewState = ViewState(
        centerAzRad = 0.0,
        centerAltRad = 0.5,
        fovDeg = 70f,
        screenW = 320f,
        screenH = 470f,
    )

    private fun richSky(selectedArt: Boolean = true): VisibleSky {
        val sirius = Star(id = 1, name = "Sirius", properName = "Sirius", ra = 1.0, dec = 0.5, mag = -1.4, bv = 0.0)
        val red = Star(id = 2, name = "Red", properName = "Red", ra = 1.1, dec = 0.4, mag = 2.5, bv = 1.6)
        val blue = Star(id = 3, name = "Blue", properName = "Blue", ra = 0.9, dec = 0.6, mag = 4.5, bv = -0.3)
        return VisibleSky(
            stars = listOf(
                VisibleStar(sirius, AltAz(0.0, 0.5), RaDec(1.0, 0.5)),
                VisibleStar(red, AltAz(0.1, 0.45), RaDec(1.1, 0.4)),
                VisibleStar(blue, AltAz(-0.1, 0.55), RaDec(0.9, 0.6)),
            ),
            planets = listOf(
                VisiblePlanet("MARS", "Mars", AltAz(0.05, 0.42), RaDec(1.2, 0.3), -0.5, 0.7),
                VisiblePlanet("VENUS", "Venus", AltAz(-0.05, 0.6), RaDec(0.8, 0.5), -4.0, 0.3),
            ),
            sun = VisibleSun(AltAz(0.02, 0.3), RaDec(0.8, 0.1), 1.0),
            moon = VisibleMoon(AltAz(-0.02, 0.65), RaDec(1.5, 0.4), 0.5, 0.75, 7.0),
            deepSky = listOf(
                VisibleDeepSky(DeepSkyObject("M31", "Andromeda Galaxy", 0.71, 0.71, 3.4, "galaxy"), AltAz(0.03, 0.48), RaDec(0.71, 0.71)),
                VisibleDeepSky(DeepSkyObject("M42", "Orion Nebula", 1.46, -0.09, 4.0, "nebula"), AltAz(-0.03, 0.52), RaDec(1.46, -0.09)),
                VisibleDeepSky(DeepSkyObject("M45", "Pleiades", 0.99, 0.42, 1.6, "cluster"), AltAz(0.06, 0.44), RaDec(0.99, 0.42)),
            ),
            constellations = listOf(
                ConstellationLine("Ori", "Orion", listOf(listOf(1, 2), listOf(2, 3))),
            ),
            art = if (selectedArt) {
                listOf(
                    VisibleArt(
                        abbr = "Ori",
                        image = "orion.png",
                        anchors = listOf(
                            VisibleArtAnchor(0f, 0f, AltAz(0.0, 0.5)),
                            VisibleArtAnchor(500f, 0f, AltAz(0.1, 0.45)),
                            VisibleArtAnchor(0f, 500f, AltAz(-0.1, 0.55)),
                        ),
                    )
                )
            } else {
                emptyList()
            },
        )
    }

    private class Recorder {
        val pans = mutableListOf<Pair<Double, Double>>()
        val zooms = mutableListOf<Float>()
        val taps = mutableListOf<androidx.compose.ui.geometry.Offset>()
        val objectTaps = mutableListOf<String>()
        val objectOpens = mutableListOf<String>()
    }

    private fun show(
        sky: VisibleSky,
        recorder: Recorder,
        selectedId: String? = null,
        transparent: Boolean = false,
        art: Boolean = true,
    ) {
        composeRule.setContent {
            SkyCanvas(
                visibleSky = sky,
                viewState = viewState,
                showConstellationLines = true,
                showConstellationArt = art,
                showGrid = true,
                showDeepSky = true,
                showPlanets = true,
                transparentBackground = transparent,
                trajectory = listOf(
                    TrajectoryPoint(2460000.5, AltAz(0.0, 0.4), RaDec(1.0, 0.5)),
                    TrajectoryPoint(2460000.6, AltAz(0.05, 0.5), RaDec(1.05, 0.5)),
                    TrajectoryPoint(2460000.7, AltAz(0.1, 0.6), RaDec(1.1, 0.5)),
                ),
                selectedId = selectedId,
                onPan = { az, alt -> recorder.pans += az to alt },
                onZoom = { recorder.zooms += it },
                onTap = { recorder.taps += it },
                onObjectTap = { recorder.objectTaps += it },
                onObjectOpen = { recorder.objectOpens += it },
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `fully populated sky rasterises every layer`() {
        val recorder = Recorder()
        show(richSky(), recorder, selectedId = "STAR_1")
        val image = composeRule.onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test
    fun `selection ring for the sun draws`() {
        val recorder = Recorder()
        show(richSky(), recorder, selectedId = "SUN")
        composeRule.onRoot().captureToImage()
        assertTrue(recorder.objectTaps.isEmpty())
    }

    @Test
    fun `selection ring for a planet draws`() {
        val recorder = Recorder()
        show(richSky(), recorder, selectedId = "PLANET_MARS")
        composeRule.onRoot().captureToImage()
        assertTrue(recorder.objectTaps.isEmpty())
    }

    @Test
    fun `selection ring for a deep sky object draws`() {
        val recorder = Recorder()
        show(richSky(), recorder, selectedId = "M31")
        composeRule.onRoot().captureToImage()
        assertTrue(recorder.objectTaps.isEmpty())
    }

    @Test
    fun `tap on the centred star selects it`() {
        val recorder = Recorder()
        // A lone centred star: nothing else is within hit distance.
        val lone = VisibleSky(
            stars = listOf(
                VisibleStar(
                    Star(id = 1, name = "Sirius", properName = "Sirius", ra = 1.0, dec = 0.5, mag = -1.4, bv = 0.0),
                    AltAz(0.0, 0.5),
                    RaDec(1.0, 0.5),
                )
            ),
        )
        show(lone, recorder, selectedId = null)
        composeRule.onRoot().performTouchInput { click(center) }
        assertEquals(listOf("STAR_1"), recorder.objectTaps)
        assertTrue(recorder.objectOpens.isEmpty())
    }

    @Test
    fun `tap with the star already selected opens it`() {
        val recorder = Recorder()
        val lone = VisibleSky(
            stars = listOf(
                VisibleStar(
                    Star(id = 1, name = "Sirius", properName = "Sirius", ra = 1.0, dec = 0.5, mag = -1.4, bv = 0.0),
                    AltAz(0.0, 0.5),
                    RaDec(1.0, 0.5),
                )
            ),
        )
        show(lone, recorder, selectedId = "STAR_1")
        composeRule.onRoot().performTouchInput { click(center) }
        assertEquals(listOf("STAR_1"), recorder.objectOpens)
        assertTrue(recorder.objectTaps.isEmpty())
    }

    @Test
    fun `tap away from objects is a plain canvas tap`() {
        val recorder = Recorder()
        show(richSky(), recorder, selectedId = null)
        composeRule.onRoot().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(20f, 20f))
        }
        assertEquals(1, recorder.taps.size)
        assertTrue(recorder.objectTaps.isEmpty())
    }

    // Drag/zoom gesture handlers (detectDragGestures/detectTransformGestures)
    // do not trigger from synthetic single-pointer injection under
    // Robolectric; the tap handler above is the gesture path that does.

    @Test
    fun `empty transparent sky draws only the grid and horizon`() {
        val recorder = Recorder()
        show(VisibleSky(), recorder, transparent = true, art = false)
        val image = composeRule.onRoot().captureToImage()
        assertTrue(image.width > 0)
    }

    @Test
    fun `art with too few anchors or off-screen is skipped`() {
        val recorder = Recorder()
        val sky = richSky(selectedArt = false).copy(
            art = listOf(
                VisibleArt(
                    abbr = "Ori",
                    image = "orion.png",
                    anchors = listOf(VisibleArtAnchor(0f, 0f, AltAz(0.0, 0.5))),
                ),
                VisibleArt(
                    abbr = "Nope",
                    image = "does-not-exist.png",
                    anchors = listOf(
                        VisibleArtAnchor(0f, 0f, AltAz(0.0, 0.5)),
                        VisibleArtAnchor(500f, 0f, AltAz(0.1, 0.45)),
                        VisibleArtAnchor(0f, 500f, AltAz(-0.1, 0.55)),
                    ),
                ),
            ),
        )
        show(sky, recorder)
        composeRule.onRoot().captureToImage()
    }
}
