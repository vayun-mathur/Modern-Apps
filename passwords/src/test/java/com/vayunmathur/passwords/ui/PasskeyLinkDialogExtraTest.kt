package com.vayunmathur.passwords.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.passwords.data.Password
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasskeyLinkDialogExtraTest {

    @get:Rule val rule = createComposeRule()

    @Test fun dialogDismissesThroughCancel() {
        var dismissed = false
        rule.setContent {
            PasskeyLinkDialog(
                passwords = listOf(Password(id = 1, name = "GitHub", username = "octo", syncId = "s1")),
                onDismiss = { dismissed = true },
                onSelect = {},
            )
        }
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }

    @Test fun dialogShowsFallbacksForBlankFields() {
        var selected: Password? = null
        rule.setContent {
            PasskeyLinkDialog(
                passwords = listOf(
                    Password(id = 1, name = "", email = "only@mail.com", syncId = "s1"),
                    Password(id = 2, name = "Named", username = "", email = "", syncId = "s2"),
                ),
                onDismiss = {},
                onSelect = { selected = it },
            )
        }
        // Blank names fall back to the no-name label; the email doubles as subtitle.
        rule.onNodeWithText("(no name)").assertIsDisplayed()
        rule.onNodeWithText("only@mail.com").assertIsDisplayed()
        rule.onNodeWithText("Named").performClick()
        assertEquals(2L, selected?.id)
    }
}
