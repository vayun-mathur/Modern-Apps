package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Semantics added by the shared-component a11y batch. */
@RunWith(RobolectricTestRunner::class)
class A11ySemanticsSliceTest {

    @get:Rule val rule = createComposeRule()

    @Test fun appScaffoldTitleIsAHeading() {
        rule.setContent {
            AppScaffold(title = "Inbox", scrollBehavior = appBarScrollBehavior()) {
                Text("Body")
            }
        }
        rule.onNodeWithText("Inbox").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        )
    }

    @Test fun labeledFieldErrorIsAssociatedWhenErrorWithSupportingText() {
        rule.setContent {
            LabeledTextField(
                value = "bad",
                onValueChange = {},
                label = "Field",
                isError = true,
                supportingText = "Must not be empty",
            )
        }
        rule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Error),
        ).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Error, "Must not be empty"),
        )
    }

    @Test fun labeledFieldWithoutErrorHasNoErrorSemantics() {
        rule.setContent {
            LabeledTextField(
                value = "fine",
                onValueChange = {},
                label = "Field",
                supportingText = "A helpful hint",
            )
        }
        val errors = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Error),
        ).fetchSemanticsNodes()
        assertTrue(errors.isEmpty(), "unexpected Error semantics: $errors")
    }

    @Test fun loadingMessageIsAPoliteLiveRegion() {
        rule.setContent { LoadingState(message = "Fetching things") }
        rule.onNodeWithText("Fetching things").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
    }

    @Test fun swipeRowExposesCustomAccessibilityActions() {
        var deleted = 0
        rule.setContent {
            SwipeActionsBox(
                accessibilityActions = listOf(
                    CustomAccessibilityAction("Delete row") { deleted++; true },
                ),
            ) {
                Box(Modifier.fillMaxWidth().height(56.dp).testTag("swipe-area")) {
                    Text("swipe-row")
                }
            }
        }
        val node = rule.onNodeWithText("swipe-row").fetchSemanticsNode()
        val actions = node.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Delete row"), actions.map { it.label })
        assertTrue(actions.single().action())
        assertEquals(1, deleted)
    }

    @Test fun sharedSnackbarHostIsAPoliteLiveRegion() {
        val host = SnackbarHostState()
        rule.setContent {
            SnackbarHost(hostState = host)
            LaunchedEffect(Unit) { host.showSnackbar("hello-snack") }
        }
        rule.waitForIdle()
        rule.onNodeWithText("hello-snack").assertIsDisplayed()
        val liveRegions = rule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        ).fetchSemanticsNodes()
        assertTrue(liveRegions.isNotEmpty(), "no Polite live region around the snackbar")
    }

    @Test fun messengerDefaultsToLongWithoutAnAction() {
        val host = SnackbarHostState()
        val messenger = Messenger(CoroutineScope(Dispatchers.Unconfined), host)
        messenger.show("queued-message")
        val data = host.currentSnackbarData
        assertTrue(data != null, "messenger did not queue a snackbar")
        assertEquals(SnackbarDuration.Long, data.visuals.duration)
        data.dismiss()
    }

    @Test fun swipeRowWithoutActionsKeepsContentUnmerged() {
        // Default (empty) actions must not change existing rows: the content
        // text stays its own node and no custom actions are advertised.
        rule.setContent {
            SwipeActionsBox {
                Box(Modifier.fillMaxWidth().height(56.dp)) { Text("plain-row") }
            }
        }
        rule.onNodeWithText("plain-row").assertIsDisplayed()
        val withActions = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
        ).fetchSemanticsNodes()
        assertTrue(withActions.isEmpty())
    }
}
