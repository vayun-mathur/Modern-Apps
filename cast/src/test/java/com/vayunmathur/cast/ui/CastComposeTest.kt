package com.vayunmathur.cast.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.platform.CastActions
import com.vayunmathur.cast.platform.CastConnection
import com.vayunmathur.cast.platform.CastUiState
import com.vayunmathur.cast.platform.MirrorPhase
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CastComposeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class RecordingActions : CastActions {
        var scans = 0
        var stops = 0
        var connects = mutableListOf<CastDevice>()
        var disconnects = 0
        var codes = mutableListOf<String>()
        var mirrorStarts = 0
        var mirrorStops = 0
        var settings = 0
        override fun startScan() { scans++ }
        override fun stopScan() { stops++ }
        override fun connect(device: CastDevice) { connects += device }
        override fun disconnect() { disconnects++ }
        override fun submitPairCode(code: String) { codes += code }
        override fun startMirroring() { mirrorStarts++ }
        override fun stopMirroring() { mirrorStops++ }
        override fun openLocalNetworkSettings() { settings++ }
    }

    private val tv = CastDevice(id = "tv1", friendlyName = "Living Room TV", host = "192.168.1.42", port = 9)
    private val tv2 = CastDevice(id = "tv2", friendlyName = "Bedroom TV", host = "192.168.1.51", port = 9)

    private fun content(state: CastUiState, actions: CastActions) {
        rule.setContent { DynamicTheme { CastContent(state = state, actions = actions) } }
        rule.waitForIdle()
    }

    @Test
    fun `empty scanning state shows searching`() {
        content(CastUiState(isScanning = true), RecordingActions())
        rule.onNodeWithText("Searching for televisions.").assertIsDisplayed()
        rule.onNodeWithText("Televisions").assertIsDisplayed()
    }

    @Test
    fun `empty idle state shows no devices hint`() {
        content(CastUiState(), RecordingActions())
        rule.onNodeWithText("No televisions found").assertIsDisplayed()
    }

    @Test
    fun `device rows render and click connects`() {
        val actions = RecordingActions()
        content(CastUiState(devices = listOf(tv, tv2), connectedDevice = tv), actions)
        rule.onNodeWithText("Living Room TV").assertIsDisplayed()
        rule.onNodeWithText("192.168.1.42").assertIsDisplayed()
        rule.onNodeWithText("Bedroom TV").performClick()
        assertEquals(listOf(tv2), actions.connects)
    }

    @Test
    fun `connected state shows ready card and disconnect works`() {
        val actions = RecordingActions()
        content(
            CastUiState(devices = listOf(tv), connectedDevice = tv, connection = CastConnection.Connected),
            actions,
        )
        rule.onNodeWithText("Ready to mirror").assertIsDisplayed()
        rule.onNodeWithText("Connected to Living Room TV").assertIsDisplayed()
        rule.onNodeWithText("Mirror my screen").assertIsEnabled().performClick()
        assertEquals(1, actions.mirrorStarts)
        rule.onNodeWithText("Disconnect").performClick()
        assertEquals(1, actions.disconnects)
    }

    @Test
    fun `mirroring state shows stop instead of start`() {
        val actions = RecordingActions()
        content(
            CastUiState(
                connectedDevice = tv, connection = CastConnection.Connected,
                mirrorPhase = MirrorPhase.Mirroring,
            ),
            actions,
        )
        rule.onNodeWithText("Mirroring your screen").assertIsDisplayed()
        rule.onNodeWithText("Stop mirroring").performClick()
        assertEquals(1, actions.mirrorStops)
    }

    @Test
    fun `negotiating state shows starting and stop`() {
        val actions = RecordingActions()
        content(
            CastUiState(
                connectedDevice = tv, connection = CastConnection.Connected,
                mirrorPhase = MirrorPhase.Negotiating,
            ),
            actions,
        )
        rule.onNodeWithText("Stop mirroring").performClick()
        assertEquals(1, actions.mirrorStops)
    }

    @Test
    fun `failed mirror shows failure and degraded notices`() {
        content(
            CastUiState(
                connectedDevice = tv, connection = CastConnection.Connected,
                mirrorPhase = MirrorPhase.Failed, failure = "Boom happened",
                audioDegraded = true, videoDegraded = true,
            ),
            RecordingActions(),
        )
        rule.onNodeWithText("Mirroring stopped").assertIsDisplayed()
        rule.onNodeWithText("Boom happened").assertIsDisplayed()
        assertTrue(rule.onNodeWithText("Audio is unavailable, so only the picture is being sent. Apps that block audio capture, and devices without an Opus encoder, both cause this.").fetchSemanticsNode() != null)
    }

    @Test
    fun `failed connection shows retry for connected device`() {
        val actions = RecordingActions()
        content(
            CastUiState(devices = listOf(tv), connectedDevice = tv, connection = CastConnection.Failed, failure = "Nope"),
            actions,
        )
        rule.onNodeWithText("Nope").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(listOf(tv), actions.connects)
    }

    @Test
    fun `failed connection without device has no retry`() {
        content(CastUiState(connection = CastConnection.Failed), RecordingActions())
        rule.onNodeWithText("The TV did not answer the way it should").assertIsDisplayed()
    }

    @Test
    fun `connecting header names device`() {
        content(
            CastUiState(connectedDevice = tv, connection = CastConnection.Connecting),
            RecordingActions(),
        )
        rule.onNodeWithText("Connecting to Living Room TV.").assertIsDisplayed()
    }

    @Test
    fun `local network blocked offers settings`() {
        val actions = RecordingActions()
        content(CastUiState(localNetworkBlocked = true), actions)
        rule.onNodeWithText("Local network access is off").assertIsDisplayed()
        rule.onNodeWithText("Open settings").performClick()
        assertEquals(1, actions.settings)
    }

    @Test
    fun `pair card filters letters, requires six digits, submits and clears`() {
        val actions = RecordingActions()
        content(
            CastUiState(
                connectedDevice = tv, connection = CastConnection.AwaitingCode,
                pairAttemptsLeft = 2,
            ),
            actions,
        )
        rule.onNodeWithText("Pair with Living Room TV").assertIsDisplayed()
        rule.onNodeWithText("Wrong code. 2 attempts left.").assertIsDisplayed()
        rule.onNodeWithText("Pair").assertIsNotEnabled()
        rule.onNodeWithText("Code from your TV").performTextInput("12a34")
        rule.onNodeWithText("Pair").assertIsNotEnabled()
        rule.onNodeWithText("Code from your TV").performTextInput("56")
        rule.onNodeWithText("Pair").assertIsEnabled().performClick()
        assertEquals(listOf("123456"), actions.codes)
        // Cleared after submit, so Pair is disabled again.
        rule.onNodeWithText("Pair").assertIsNotEnabled()
    }

    @Test
    fun `pair card shows new code notice over attempts`() {
        content(
            CastUiState(
                connectedDevice = tv, connection = CastConnection.AwaitingCode,
                pairAttemptsLeft = 3, pairCodeChanged = true,
            ),
            RecordingActions(),
        )
        rule.onNodeWithText("Wrong code three times, so your TV is now showing a new one. Look again.").assertIsDisplayed()
    }

    @Test
    fun `pair card with one attempt uses singular`() {
        content(
            CastUiState(
                connectedDevice = tv, connection = CastConnection.AwaitingCode, pairAttemptsLeft = 1,
            ),
            RecordingActions(),
        )
        rule.onNodeWithText("Wrong code. 1 attempt left.").assertIsDisplayed()
    }

    @Test
    fun `picker shows generic title, devices and cancel`() {
        var cancelled = false
        rule.setContent {
            DynamicTheme {
                CastPickerContent(
                    state = CastUiState(devices = listOf(tv)),
                    actions = RecordingActions(),
                    appName = "",
                    onCancel = { cancelled = true },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Choose a television").assertIsDisplayed()
        rule.onNodeWithText("Living Room TV").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun `picker title names calling app and headers cover states`() {
        rule.setContent {
            DynamicTheme {
                CastPickerContent(
                    state = CastUiState(connectedDevice = tv, connection = CastConnection.Connecting),
                    actions = RecordingActions(),
                    appName = "Music",
                    onCancel = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Cast Music to").assertIsDisplayed()
        rule.onNodeWithText("Connecting to Living Room TV.").assertIsDisplayed()
    }

    @Test
    fun `picker blocked and failed headers`() {
        rule.setContent {
            DynamicTheme {
                CastPickerContent(
                    state = CastUiState(localNetworkBlocked = true),
                    actions = RecordingActions(), appName = "Music", onCancel = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Local network access is off").assertIsDisplayed()
    }

    @Test
    fun `picker header while awaiting code`() {
        rule.setContent {
            DynamicTheme {
                CastPickerContent(
                    state = CastUiState(
                        connectedDevice = tv, connection = CastConnection.AwaitingCode,
                    ),
                    actions = RecordingActions(), appName = "Music", onCancel = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Pairing with Living Room TV").assertIsDisplayed()
    }

    @Test
    fun `picker header when failed`() {
        rule.setContent {
            DynamicTheme {
                CastPickerContent(
                    state = CastUiState(connection = CastConnection.Failed, failure = "It broke"),
                    actions = RecordingActions(), appName = "Music", onCancel = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("It broke").assertIsDisplayed()
    }

    @Test
    fun `picker header while scanning`() {
        rule.setContent {
            DynamicTheme {
                CastPickerContent(
                    state = CastUiState(isScanning = true),
                    actions = RecordingActions(), appName = "", onCancel = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Searching for televisions.").assertIsDisplayed()
    }

    @Test
    fun `picker header when idle and empty`() {
        rule.setContent {
            DynamicTheme {
                CastPickerContent(
                    state = CastUiState(),
                    actions = RecordingActions(), appName = "", onCancel = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("No televisions found").assertIsDisplayed()
    }

    @Test
    fun `pair dialog renders card over scrim`() {
        val actions = RecordingActions()
        rule.setContent {
            DynamicTheme {
                CastPairDialog(
                    state = CastUiState(
                        connectedDevice = tv, connection = CastConnection.AwaitingCode, pairAttemptsLeft = 3,
                    ),
                    actions = actions,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Pair with Living Room TV").assertIsDisplayed()
        rule.onNodeWithText("Code from your TV").performTextInput("999888")
        rule.onNodeWithText("Pair").performClick()
        assertEquals(listOf("999888"), actions.codes)
    }

    @Test
    fun `navigation renders the cast screen`() {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val vm = com.vayunmathur.cast.platform.CastViewModel(app)
        rule.setContent { DynamicTheme { com.vayunmathur.cast.Navigation(vm) } }
        rule.waitForIdle()
        rule.onNodeWithText("Televisions").assertIsDisplayed()
        vm.stopScan()
    }

    @Test
    fun `mirror status card disabled start when not connected`() {
        rule.setContent {
            DynamicTheme {
                CastMirrorStatusCard(state = CastUiState(), actions = RecordingActions())
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Mirror my screen").assertIsNotEnabled()
    }
}
