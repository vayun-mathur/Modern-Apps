package com.vayunmathur.games.hub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.ui.components.StatCard
import kotlin.math.abs
import kotlin.test.Test
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
}
