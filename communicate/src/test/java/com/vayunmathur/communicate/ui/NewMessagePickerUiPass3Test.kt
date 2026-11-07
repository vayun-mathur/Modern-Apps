package com.vayunmathur.communicate.ui

import android.Manifest
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.LineChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * UiPass3 coverage for the new-message picker (`NewMessagePicker.kt`):
 * line/group chrome, recipient rows, and the number-formatting helpers.
 * The contacts provider is empty under Robolectric, so the picker shows
 * its search field with no rows — the group flow is asserted on chrome
 * and the disabled Create action rather than on provider data.
 */
@RunWith(RobolectricTestRunner::class)
class NewMessagePickerUiPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int): String = context.getString(id)

    @Test
    fun `formatNumber formats a dialable number and keeps junk as-is`() {
        assertEquals("(555) 010-1234", formatNumber("5550101234", "US"))
        assertEquals("call-me", formatNumber("call-me", "US"))
        assertEquals("", formatNumber("", "US"))
    }

    @Test
    fun `deviceRegion returns an upper-case region code`() {
        val region = deviceRegion(context)
        assertEquals(region.uppercase(), region)
        assertEquals(true, region.length <= 3)
    }

    @Test
    fun `contact pick row reports taps with and without a checkbox`() {
        var taps = 0
        var selected by androidx.compose.runtime.mutableStateOf<Boolean?>(null)
        rule.setContent {
            ContactPickRow(title = "Pat Lee", subtitle = "+1555", selected = selected, onClick = { taps++ })
        }
        rule.onNodeWithText("Pat Lee").assertIsDisplayed()
        rule.onNodeWithText("+1555").assertIsDisplayed()
        rule.onNodeWithText("Pat Lee").performClick()
        rule.runOnIdle { selected = true }
        rule.waitForIdle()
        rule.onNodeWithText("Pat Lee").performClick()
        rule.waitForIdle()
        assertEquals(2, taps)
    }

    @Test
    fun `picker shows search and line chrome for one-to-one`() {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        rule.setContent {
            NewMessagePicker(
                choices = listOf(LineChoice.GoogleVoice, LineChoice.WhatsApp),
                onDismiss = {},
                onCompose = { _, _ -> },
                onCreateGroup = { _, _, _, _ -> },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.new_message)).assertIsDisplayed()
        rule.onNodeWithText("Search name or number").assertIsDisplayed()
        rule.onNodeWithText("Group").assertIsDisplayed()
    }

    @Test
    fun `picker group mode asks for members before creating`() {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        rule.setContent {
            NewMessagePicker(
                choices = listOf(LineChoice.WhatsApp),
                onDismiss = {},
                onCompose = { _, _ -> },
                onCreateGroup = { _, _, _, _ -> },
            )
        }
        rule.waitForIdle()
        // Flip the Group switch: the dialog retitles and Create stays disabled
        // until at least one recipient is picked.
        rule.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("New group").assertIsDisplayed()
        rule.onNodeWithText("Create").assertIsNotEnabled()
        rule.onNodeWithText("Group name (optional)").assertIsDisplayed()
    }

    @Test
    fun `picker dismiss button clears without composing`() {
        var dismissed = 0
        var composed = 0
        rule.setContent {
            NewMessagePicker(
                choices = listOf(LineChoice.GoogleVoice),
                onDismiss = { dismissed++ },
                onCompose = { _, _ -> composed++ },
                onCreateGroup = { _, _, _, _ -> },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.clear)).performClick()
        rule.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(0, composed)
    }
}
