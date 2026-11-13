package com.vayunmathur.games.hub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.ui.components.GameCard
import com.vayunmathur.games.hub.ui.components.StatCard
import com.vayunmathur.games.hub.ui.components.profileTabAvatarSymbol
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression tests for the games UX audit fixes (#ux-audit). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HubUxAuditTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun statCardCentresValueAndLabelInTheCard() {
        rule.setContent {
            Box(Modifier.width(300.dp)) {
                StatCard(label = "Playtime", value = "12h 5m", modifier = Modifier.width(300.dp).testTag("card"))
            }
        }
        val card = rule.onNodeWithTag("card").getBoundsInRoot()
        val cardCentre = (card.left + card.right) / 2
        for (text in listOf("Playtime", "12h 5m")) {
            val b = rule.onNodeWithText(text).getBoundsInRoot()
            val centre = (b.left + b.right) / 2
            assertTrue(abs((centre - cardCentre).value) < 1f, "$text centre $centre != card centre $cardCentre")
        }
    }

    private fun game(id: String, name: String) = HubGameEntity(
        gameId = id, packageName = "com.example.$id", displayName = name,
    )

    @Test
    fun gameCardShowsAchievementCountAsTextWithoutASpinnerLikeRing() {
        rule.setContent {
            GameCard(game("chess", "Chess"), isInstalled = true, achievementProgress = 3 to 10, onClick = {}, onPlay = {})
        }
        rule.onNodeWithText("Achievements: 3/10").assertIsDisplayed()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }

    @Test
    fun profileTabNeverReusesASiblingTabIcon() {
        // Default profile and ordinary avatars: unchanged (person / the avatar itself).
        assertNull(profileTabAvatarSymbol(null))
        assertEquals("star", profileTabAvatarSymbol("star"))
        assertEquals("rocket", profileTabAvatarSymbol("rocket"))
        // Avatars that render the Games, Achievements or Yuu tab glyph fall back to the person icon.
        for (symbol in listOf("stadia_controller", "sports_esports", "emoji_events", "psychology")) {
            assertNull(profileTabAvatarSymbol(symbol), symbol)
        }
    }
}
