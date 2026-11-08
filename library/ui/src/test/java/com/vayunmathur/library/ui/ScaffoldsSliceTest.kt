package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3: scaffolds — [AppScaffold], [DetailScaffold], [DetailLazyColumn],
 * [DetailSection], [SetupScaffold], [LazyListScaffold], [TabbedPagerScaffold]
 * (tab-row style), [ChipTabRow], [TopAppBarOverlay].
 */
@RunWith(RobolectricTestRunner::class)
class ScaffoldsSliceTest {

    @get:Rule
    val rule = createComposeRule()

    @Test fun appScaffoldShowsTitleContentAndBack() {
        var backs = 0
        rule.setContent {
            AppScaffold(
                title = "Inbox",
                onNavigateBack = { backs++ },
                scrollBehavior = appBarScrollBehavior(),
            ) { Text("Body text") }
        }
        rule.onNodeWithText("Inbox").assertIsDisplayed()
        rule.onNodeWithText("Body text").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun appScaffoldCloseBeatsBack() {
        var closes = 0
        var backs = 0
        rule.setContent {
            AppScaffold(
                title = "T",
                onNavigateBack = { backs++ },
                onClose = { closes++ },
                scrollBehavior = appBarScrollBehavior(),
            ) { Text("B") }
        }
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, closes)
        assertEquals(0, backs)
    }

    @Test fun appScaffoldFlexibleSizesRenderSubtitle() {
        rule.setContent {
            AppScaffold(
                title = "Big",
                subtitle = "Sub",
                size = AppBarSize.MediumFlexible,
                scrollBehavior = appBarScrollBehavior(AppBarSize.MediumFlexible),
            ) { Text("B") }
        }
        rule.onNodeWithText("Big").assertIsDisplayed()
        rule.onNodeWithText("Sub").assertIsDisplayed()
    }

    @Test fun appScaffoldLargeFlexibleRenders() {
        rule.setContent {
            AppScaffold(
                title = "Large",
                size = AppBarSize.LargeFlexible,
                alignment = AppBarAlignment.Center,
                scrollBehavior = appBarScrollBehavior(AppBarSize.LargeFlexible),
            ) { Text("B") }
        }
        rule.onNodeWithText("Large").assertIsDisplayed()
    }

    @Test fun appScaffoldCustomTopBarRenders() {
        rule.setContent {
            AppScaffold(topBar = { Text("Custom bar") }) { Text("Under") }
        }
        rule.onNodeWithText("Custom bar").assertIsDisplayed()
        rule.onNodeWithText("Under").assertIsDisplayed()
    }

    @Test fun detailScaffoldShowsColumnContent() {
        rule.setContent {
            DetailScaffold(
                title = "Detail",
                scrollBehavior = appBarScrollBehavior(),
            ) { Text("Row") }
        }
        rule.onNodeWithText("Detail").assertIsDisplayed()
        rule.onNodeWithText("Row").assertIsDisplayed()
    }

    @Test fun detailLazyColumnShowsItems() {
        rule.setContent {
            DetailLazyColumn(
                title = "List",
                scrollBehavior = appBarScrollBehavior(),
            ) {
                items(listOf("Alpha", "Beta")) { Text(it) }
            }
        }
        rule.onNodeWithText("List").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test fun detailSectionShowsTitleAndContent() {
        rule.setContent {
            DetailSection(title = "Meta") { Text("Value") }
        }
        rule.onNodeWithText("Meta").assertIsDisplayed()
        rule.onNodeWithText("Value").assertIsDisplayed()
    }

    @Test fun setupScaffoldPrimaryAndSecondaryFire() {
        var primary = 0
        var secondary = 0
        rule.setContent {
            SetupScaffold(
                title = "Welcome",
                subtitle = "Let's go",
                primaryAction = SetupAction("Next", onClick = { primary++ }),
                secondaryAction = SetupAction("Skip", onClick = { secondary++ }),
                scrollBehavior = appBarScrollBehavior(),
            ) { Text("Extra") }
        }
        rule.onNodeWithText("Welcome").assertIsDisplayed()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Skip").performClick()
        assertEquals(1, primary)
        assertEquals(1, secondary)
    }

    @Test fun lazyListScaffoldTitleAndItems() {
        rule.setContent {
            LazyListScaffold(
                title = "Feed",
                scrollBehavior = appBarScrollBehavior(),
            ) {
                items(listOf("One", "Two")) { Text(it) }
            }
        }
        rule.onNodeWithText("Feed").assertIsDisplayed()
        rule.onNodeWithText("One").assertIsDisplayed()
        rule.onNodeWithText("Two").assertIsDisplayed()
    }

    @Test fun tabbedPagerScaffoldTabRowSwitchesPages() {
        val tabs = listOf(
            PagerTab("First", content = { Text("Page one") }),
            PagerTab("Second", content = { Text("Page two") }),
        )
        rule.setContent {
            TabbedPagerScaffold(tabs = tabs, tabStyle = TabStyle.PrimaryTabRow)
        }
        rule.onNodeWithText("First").assertIsDisplayed()
        rule.onNodeWithText("Page one").assertIsDisplayed()
        rule.onNodeWithText("Second").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Page two").assertIsDisplayed()
    }

    @Test fun chipTabRowSelects() {
        var selected = 0
        rule.setContent {
            ChipTabRow(
                tabs = listOf("Day", "Week"),
                selected = selected,
                onSelect = { selected = it },
            )
        }
        rule.onNodeWithText("Week").performClick()
        assertEquals(1, selected)
    }

    @Test fun topAppBarOverlayBackAndActions() {
        var backs = 0
        var shares = 0
        rule.setContent {
            TopAppBarOverlay(
                onNavigateBack = { backs++ },
                actions = listOf(
                    OverlayAction(
                        // Note: TopAppBarOverlay renders action.icon() inside the
                        // button; the data-class contentDescription is not applied
                        // to semantics, so the icon's own description is the hook.
                        icon = { IconShare() },
                        contentDescription = "Share it",
                        onClick = { shares++ },
                    ),
                ),
            )
        }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Share").performClick()
        assertEquals(1, backs)
        assertEquals(1, shares)
    }
}
