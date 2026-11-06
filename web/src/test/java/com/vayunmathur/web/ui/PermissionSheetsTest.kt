package com.vayunmathur.web.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.web.platform.SitePermissionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PermissionSheetsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the permission prompt grants the selection`() {
        var granted: List<SitePermissionType>? = null
        var denied = false
        rule.setContent {
            PermissionPromptSheet(
                origin = "https://example.com/call",
                types = listOf(SitePermissionType.CAMERA, SitePermissionType.MICROPHONE),
                onGrant = { granted = it },
                onDeny = { denied = true },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Camera").assertIsDisplayed()
        rule.onNodeWithText("Microphone").assertIsDisplayed()
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertTrue(granted?.contains(SitePermissionType.CAMERA) == true)
        assertTrue(granted?.contains(SitePermissionType.MICROPHONE) == true)
        assertEquals(false, denied)
    }

    @Test
    fun `unchecking narrows the grant`() {
        var granted: List<SitePermissionType>? = null
        rule.setContent {
            PermissionPromptSheet(
                origin = "https://example.com/call",
                types = listOf(SitePermissionType.CAMERA, SitePermissionType.MICROPHONE),
                onGrant = { granted = it },
                onDeny = {},
            )
        }
        rule.waitForIdle()
        // Uncheck the camera permission, then allow: only mic is granted.
        rule.onAllNodes(
            androidx.compose.ui.test.hasRole(androidx.compose.ui.semantics.Role.Checkbox)
        )[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertEquals(listOf(SitePermissionType.MICROPHONE), granted)
    }

    @Test
    fun `denying calls onDeny`() {
        var denied = false
        rule.setContent {
            PermissionPromptSheet(
                origin = "https://example.com",
                types = listOf(SitePermissionType.NOTIFICATIONS),
                onGrant = {},
                onDeny = { denied = true },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Block").performClick()
        rule.waitForIdle()
        assertTrue(denied)
    }

    @Test
    fun `the geolocation prompt allows or blocks`() {
        var allowed = false
        var denied = false
        rule.setContent {
            GeolocationPromptSheet(
                origin = "https://maps.example",
                onAllow = { allowed = true },
                onDeny = { denied = true },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertTrue(allowed)
        assertEquals(false, denied)
    }

    @Test
    fun `the local network prompt names the host`() {
        var allowed = false
        rule.setContent {
            LocalNetworkPromptSheet(
                host = "nas.local",
                onAllow = { allowed = true },
                onDeny = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("nas.local", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertTrue(allowed)
    }

    @Test
    fun `the file chooser shows accepted types`() {
        var triggered = false
        rule.setContent {
            FileChooserSheet(
                mimeTypes = listOf("image/*"),
                onCancel = {},
                onTriggerPicker = { triggered = true },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("image/*", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Pick", substring = true).performClick()
        rule.waitForIdle()
        assertTrue(triggered)
    }
}
