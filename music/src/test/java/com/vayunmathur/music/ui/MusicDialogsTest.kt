package com.vayunmathur.music.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.R
import com.vayunmathur.music.ui.dialogs.CreatePlaylistDialog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicDialogsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int): String = context.getString(id)

    @Test
    fun `create playlist dialog trims the name and creates`() {
        var created: String? = null
        var dismissed = false
        composeRule.setContent {
            CreatePlaylistDialog(onDismiss = { dismissed = true }, onCreate = { created = it })
        }
        composeRule.onNodeWithText(str(R.string.dialog_create_playlist)).assertIsDisplayed()
        composeRule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.create))
            .assertIsNotEnabled()
        composeRule.onNodeWithText(str(R.string.playlist_name_hint))
            .performTextInput("  Road Mix  ")
        composeRule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.create))
            .assertIsEnabled()
            .performClick()
        assertEquals("Road Mix", created)
        assertTrue(!dismissed)
    }

    @Test
    fun `create playlist dialog cancel dismisses without creating`() {
        var created: String? = null
        var dismissed = false
        composeRule.setContent {
            CreatePlaylistDialog(onDismiss = { dismissed = true }, onCreate = { created = it })
        }
        composeRule.onNodeWithText(str(R.string.playlist_name_hint)).performTextInput("Mix")
        composeRule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertTrue(dismissed)
        assertEquals(null, created)
    }

    @Test
    fun `create playlist dialog rejects blank names`() {
        var created: String? = null
        composeRule.setContent {
            CreatePlaylistDialog(onDismiss = {}, onCreate = { created = it })
        }
        composeRule.onNodeWithText(str(R.string.playlist_name_hint)).performTextInput("   ")
        composeRule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.create))
            .assertIsNotEnabled()
        assertEquals(null, created)
    }
}
