package com.vayunmathur.findfamily.ui.dialogs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Base26 invite-id encoding (pure) and the small static dialogs. */
@RunWith(RobolectricTestRunner::class)
class Base26AndSmallDialogsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun base26RoundTrips() {
        for (value in listOf(1L, 25L, 26L, 27L, 675L, 123_456_789L, Long.MAX_VALUE - 1)) {
            assertEquals(value, value.encodeBase26().decodeBase26(), "value=$value")
        }
        assertEquals("", 0L.encodeBase26())
        assertEquals(0L, "".decodeBase26())
        assertEquals("B", 1L.encodeBase26())
        assertEquals("BA", 26L.encodeBase26())
        assertEquals(26L, "BA".decodeBase26())
        assertEquals(25L, "Z".decodeBase26())
        // Encoding is uppercase-only by construction.
        assertTrue(12345L.encodeBase26().all { it in 'A'..'Z' })
    }

    @Test fun gpsFallbackDialogDismisses() {
        var dismissed = 0
        composeRule.setContent {
            DynamicTheme { GpsFallbackWarningDialog(onDismiss = { dismissed++ }) }
        }
        composeRule.onNodeWithText("Using GPS only").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun keyMismatchDialogDismisses() {
        var dismissed = 0
        composeRule.setContent {
            DynamicTheme { KeyMismatchDialog(onDismiss = { dismissed++ }) }
        }
        composeRule.onNodeWithText("Couldn't verify this person").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun outdatedPeerDialogDismisses() {
        var dismissed = 0
        composeRule.setContent {
            DynamicTheme { OutdatedPeerDialog(onDismiss = { dismissed++ }) }
        }
        composeRule.onNodeWithText("Update needed").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        assertEquals(1, dismissed)
    }
}
