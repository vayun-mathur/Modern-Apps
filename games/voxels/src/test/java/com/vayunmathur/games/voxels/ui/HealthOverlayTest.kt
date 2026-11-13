package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class HealthOverlayTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `hearts fill whole, half or not at all`() {
        assertEquals(1f, heartFill(3f))
        assertEquals(0.5f, heartFill(0.5f))
        assertEquals(0.5f, heartFill(0.9f))
        assertEquals(0f, heartFill(0.4f))
        assertEquals(0f, heartFill(-2f))
    }

    @Test fun `health reads out as hearts, halves included`() {
        assertEquals("7.5", heartsLabel(7.5f))
        assertEquals("8", heartsLabel(8f))
        assertEquals("0", heartsLabel(-1f))
    }

    // A half heart is not only a colour: the row has a spoken value for screen readers too.
    @Test fun `the hearts row is described for accessibility`() {
        rule.setContent { HealthOverlay(healthJson = """{"hp":15,"max":20}""") }
        rule.onNodeWithContentDescription("Health: 7.5 of 10 hearts").assertExists()
    }
}
