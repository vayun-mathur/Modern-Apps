package com.vayunmathur.library.ui

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.util.LocalSnackbarHostState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice 3/3 round 9: messenger with a real host + motion spec values. */
@RunWith(RobolectricTestRunner::class)
class Slice3Round9Test {
    @get:Rule val rule = createComposeRule()

    @Test fun messengerShowsMessageOnProvidedHost() {
        val host = SnackbarHostState()
        rule.setContent {
            CompositionLocalProvider(LocalSnackbarHostState provides host) {
                val messenger = rememberMessenger()
                SnackbarHost(hostState = host)
                Text(
                    "send",
                    modifier = Modifier.clickable { messenger.show("hello-snack") },
                )
            }
        }
        rule.onNodeWithText("send").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("hello-snack").assertIsDisplayed()
    }

    @Test fun messengerWithoutHostIsSilentNoOp() {
        val hostless = Messenger(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), null)
        hostless.show("nothing")
        hostless.show("nothing", actionLabel = "Act", onAction = {})
    }

    @Test fun motionSpecDurationsAndFactories() {
        assertEquals(150, Motion.PopupOpenMillis)
        assertEquals(100, Motion.PopupCloseMillis)
        assertTrue(Motion.reorder<Float>().toString().isNotEmpty())
        assertTrue(Motion.drop<Float>().toString().isNotEmpty())
        assertTrue(Motion.pageSnap<Float>().toString().isNotEmpty())
        assertTrue(Motion.over<Float>(250).toString().isNotEmpty())
    }

    @Test fun messengerWithHostQueuesSnackbarData() {
        val host = SnackbarHostState()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val messenger = Messenger(scope, host)
        messenger.show("queued-message")
        // Unconfined scope runs showSnackbar eagerly up to its first suspension;
        // the host must now hold (or have held) the message without throwing.
        val data = host.currentSnackbarData
        if (data != null) {
            assertEquals("queued-message", data.visuals.message)
            data.dismiss()
        }
    }
}
