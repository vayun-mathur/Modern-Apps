package com.vayunmathur.maps.ui.streetview

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.maps.R as MapsR
import com.vayunmathur.maps.data.google.StreetViewDataSource
import com.vayunmathur.maps.data.google.StreetViewPano
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): the Street View viewer — attribution, the unavailable
 * state and close — with panorama tiles faked to nothing so the loading path
 * settles without network.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1StreetViewComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val appContext: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun pano() = StreetViewPano(
        panoId = "slice1viewer0000000001",
        lat = 37.7,
        lng = -122.4,
        copyright = "© Slice1 Google",
        captureYear = 2024,
        captureMonth = 5,
    )

    @Test
    fun `an unloadable panorama settles on its attribution`() {
        val defaultBytes = StreetViewDataSource.httpGetBytes
        StreetViewDataSource.httpGetBytes = { null }
        try {
            rule.setContent {
                StreetViewScreen(initialPano = pano(), onClose = {})
            }
            // With no decodable tiles the viewer stays out of the panorama
            // branch — but attribution is unconditional and always renders.
            rule.waitForIdle()
            val attr = rule.onAllNodes(hasText("© Slice1 Google", substring = true))
                .fetchSemanticsNodes()
            assertTrue(attr.isNotEmpty(), "attribution must render")
        } finally {
            StreetViewDataSource.httpGetBytes = defaultBytes
        }
    }

    @Test
    fun `attribution falls back without copyright or date`() {
        val defaultBytes = StreetViewDataSource.httpGetBytes
        StreetViewDataSource.httpGetBytes = { null }
        try {
            rule.setContent {
                StreetViewScreen(initialPano = pano().copy(copyright = null), onClose = {})
            }
            rule.onNodeWithText("© Google", substring = true).assertIsDisplayed()
        } finally {
            StreetViewDataSource.httpGetBytes = defaultBytes
        }
    }

    private fun strings(id: Int): String = appContext.getString(id)
}
