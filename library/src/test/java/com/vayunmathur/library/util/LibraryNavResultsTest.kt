package com.vayunmathur.library.util

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** NavResultRegistry/ResultEffect delivery and openSettingsIfRequested. */
@RunWith(RobolectricTestRunner::class)
class LibraryNavResultsTest {

    @get:Rule val rule = createComposeRule()

    @Serializable data class Page(val route: String) : NavKey

    @Test fun resultEffectDeliversOnlyMatchingKeyAndType() {
        val registry = NavResultRegistry()
        val received = mutableListOf<String>()
        var intSeen = -1
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                    Column {
                        ResultEffect<String>("greeting") { received += it }
                        ResultEffect<Int>("count") { intSeen = it }
                        Text("ResultsHost")
                    }
                }
            }
        }
        rule.onNodeWithText("ResultsHost").assertIsDisplayed()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        scope.launch {
            registry.dispatchResult("other", "ignored")
            registry.dispatchResult("greeting", 42) // wrong type for greeting
            registry.dispatchResult("greeting", "hello")
            registry.dispatchResult("count", 7)
        }
        rule.waitUntil(5_000) { received.isNotEmpty() && intSeen != -1 }
        assertEquals(listOf("hello"), received)
        assertEquals(7, intSeen)
    }

    private fun activityWithAction(action: String): ComponentActivity =
        Robolectric.buildActivity(
            ComponentActivity::class.java,
            Intent(action),
        ).create().get()

    @Test fun openSettingsIfRequestedPushesSettingsAndConsumesIntent() {
        val stack = NavBackStack(arrayOf(Page("home")))
        val activity = activityWithAction(Intent.ACTION_APPLICATION_PREFERENCES)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalActivity provides activity) {
                    with(stack) { openSettingsIfRequested(Page("settings")) }
                    Text("Current ${stack.last().route}")
                }
            }
        }
        rule.waitForIdle()
        assertEquals("settings", stack.last().route)
        assertEquals(Intent.ACTION_MAIN, activity.intent.action)
        rule.onNodeWithText("Current settings").assertIsDisplayed()
    }

    @Test fun openSettingsIfRequestedIgnoresNormalLaunch() {
        val stack = NavBackStack(arrayOf(Page("home")))
        val activity = activityWithAction(Intent.ACTION_MAIN)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalActivity provides activity) {
                    with(stack) { openSettingsIfRequested(Page("settings")) }
                    Text("Current ${stack.last().route}")
                }
            }
        }
        rule.waitForIdle()
        assertEquals("home", stack.last().route)
        assertTrue(stack.backStack.size == 1)
    }

    @Test fun openSettingsIfRequestedAlreadyOnSettingsDoesNotDuplicate() {
        val stack = NavBackStack(arrayOf(Page("settings")))
        val activity = activityWithAction(Intent.ACTION_APPLICATION_PREFERENCES)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalActivity provides activity) {
                    with(stack) { openSettingsIfRequested(Page("settings")) }
                    Text("Current ${stack.last().route}")
                }
            }
        }
        rule.waitForIdle()
        assertEquals(1, stack.backStack.size)
        assertEquals("settings", stack.last().route)
    }
}
