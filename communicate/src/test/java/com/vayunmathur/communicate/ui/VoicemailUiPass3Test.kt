package com.vayunmathur.communicate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.VoicemailEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UiPass3 coverage for the voicemail list (`VoicemailContent.kt`): rows,
 * expand/collapse, transcripts, the no-audio state and delete-confirm.
 * The ExoPlayer itself needs real media codecs, so expanded rows here use
 * `hasAudio = false`; player behaviour is listed as an untestable remainder.
 */
@RunWith(RobolectricTestRunner::class)
class VoicemailUiPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int): String = context.getString(id)

    private fun entry(
        id: Long = 1L,
        number: String = "+1555",
        read: Boolean = true,
        hasAudio: Boolean = false,
        transcript: String? = null,
        line: CommunicateLine = CommunicateLine.Sim,
    ) = VoicemailEntry(
        id = id,
        phoneNumber = number,
        timestampMillis = System.currentTimeMillis(),
        durationSeconds = 42L,
        contentUri = "content://voicemail/$id",
        mimeType = "audio/amr",
        hasAudio = hasAudio,
        transcript = transcript,
        isRead = read,
        line = line,
    )

    @Test
    fun `rows show the number and expand on tap`() {
        val toggled = mutableListOf<VoicemailEntry>()
        val e = entry()
        rule.setContent {
            VoicemailContent(
                entries = listOf(e), expandedId = null,
                onToggleExpand = { toggled.add(it) }, onDelete = {},
            )
        }
        rule.onNodeWithText("+1555").assertIsDisplayed()
        rule.onNodeWithText("+1555").performClick()
        rule.waitForIdle()
        assertEquals(listOf(e), toggled)
    }

    @Test
    fun `expanded row without audio says so`() {
        val e = entry(hasAudio = false)
        rule.setContent {
            VoicemailContent(
                entries = listOf(e), expandedId = e.id,
                onToggleExpand = {}, onDelete = {},
            )
        }
        rule.onNodeWithText(str(R.string.voicemail_no_audio)).assertIsDisplayed()
    }

    @Test
    fun `expanded row shows its transcript`() {
        val e = entry(transcript = "call me back please")
        rule.setContent {
            VoicemailContent(
                entries = listOf(e), expandedId = e.id,
                onToggleExpand = {}, onDelete = {},
            )
        }
        rule.onNodeWithText("call me back please").assertIsDisplayed()
    }

    @Test
    fun `collapsed row hides its transcript`() {
        val e = entry(transcript = "call me back please")
        rule.setContent {
            VoicemailContent(
                entries = listOf(e), expandedId = null,
                onToggleExpand = {}, onDelete = {},
            )
        }
        rule.onNodeWithText("call me back please").assertDoesNotExist()
    }

    @Test
    fun `delete asks first and only then deletes`() {
        val deleted = mutableListOf<VoicemailEntry>()
        val e = entry()
        rule.setContent {
            VoicemailContent(
                entries = listOf(e), expandedId = null,
                onToggleExpand = {}, onDelete = { deleted.add(it) },
            )
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("More")).performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.delete)).performClick()
        rule.onNodeWithText(str(R.string.delete_voicemail_title)).assertIsDisplayed()
        assertEquals(emptyList(), deleted)
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.delete)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(e), deleted)
    }

    @Test
    fun `google voice rows render their line tag`() {
        rule.setContent {
            VoicemailContent(
                entries = listOf(entry(line = CommunicateLine.GoogleVoice)),
                expandedId = null, onToggleExpand = {}, onDelete = {},
            )
        }
        rule.onNodeWithText("+1555").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.line_gv)).assertIsDisplayed()
    }

    @Test
    fun `player composes with a play control for an unreachable clip`() {
        var played = 0
        rule.setContent {
            VoicemailPlayer(audioUri = "content://voicemail/1", onPlayed = { played++ })
        }
        rule.waitForIdle()
        rule.onNode(
            androidx.compose.ui.test.hasClickAction() and
                androidx.compose.ui.test.hasContentDescriptionExactly("Play"),
        ).assertExists()
        assertEquals(0, played)
    }

    @Test
    fun `empty list composes without rows`() {
        rule.setContent {
            VoicemailContent(
                entries = emptyList(), expandedId = null,
                onToggleExpand = {}, onDelete = {},
            )
        }
        rule.onNodeWithText("+1555").assertDoesNotExist()
    }
}
