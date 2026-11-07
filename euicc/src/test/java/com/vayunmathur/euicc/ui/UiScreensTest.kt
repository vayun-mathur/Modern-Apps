package com.vayunmathur.euicc.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.euicc.R
import com.vayunmathur.euicc.Route
import com.vayunmathur.euicc.data.EuiccInfo
import com.vayunmathur.euicc.data.Notification
import com.vayunmathur.euicc.data.Profile
import com.vayunmathur.euicc.platform.EuiccScreenState
import com.vayunmathur.library.util.NavBackStack
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class UiScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)

    private val enabledProfile = Profile(
        iccid = "iccid-1", iccidDisplay = "8901000001", state = "enabled",
        nickname = "Work", serviceProvider = "Example Mobile", name = "Prepaid",
    )
    private val disabledProfile = Profile(
        iccid = "iccid-2", iccidDisplay = "8901000002", state = "disabled",
        serviceProvider = "Travel eSIM", name = "Global Data",
    )

    private fun stack(vararg routes: Route) = NavBackStack(arrayOf(*routes))

    // ---- Home ----

    @Test fun `home empty state offers add sim`() {
        var adds = 0
        rule.setContent {
            EuiccHomeScreen(
                state = EuiccScreenState(loading = false),
                backStack = stack(Route.Home), onReload = {}, onAddSim = { adds++ },
            )
        }
        rule.onNodeWithText(str(R.string.no_profiles_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.add_profile_title)).performClick()
        assertEquals(1, adds)
    }

    @Test fun `home lists enabled and disabled profiles and navigates`() {
        var adds = 0; var reloads = 0
        val backStack = stack(Route.Home)
        rule.setContent {
            EuiccHomeScreen(
                state = EuiccScreenState(
                    loading = false, eid = "89044000001234567890123456789012",
                    profiles = listOf(enabledProfile, disabledProfile),
                ),
                backStack = backStack, onReload = { reloads++ }, onAddSim = { adds++ },
            )
        }
        rule.onNodeWithText(str(R.string.esim_enabled)).assertExists()
        rule.onNodeWithText(str(R.string.available_carriers)).assertExists()
        rule.onNodeWithText("Work").performClick()
        assertEquals(Route.ProfileDetail("iccid-1"), backStack.last())
        // Device-info / Add rows sit below the fold in the test viewport and
        // the home column does not scroll, so their clicks are covered on
        // smaller compositions (empty-state Add, Navigation DeviceInfo seed).
        rule.onNodeWithText(str(R.string.device_info_title)).assertExists()
        assertEquals(0, reloads)
    }

    @Test fun `home shows loading error and profile fallback labels`() {
        rule.setContent {
            EuiccHomeScreen(
                state = EuiccScreenState(
                    loading = true, error = "eUICC unavailable",
                    profiles = listOf(Profile(iccid = "x", iccidDisplay = "89019999", state = "disabled")),
                ),
                backStack = stack(Route.Home), onReload = {}, onAddSim = {},
            )
        }
        rule.onNodeWithText("eUICC unavailable").assertIsDisplayed()
        rule.onNodeWithText("•••• 9999").assertExists()
    }

    @Test fun `home profile row replaces existing detail destination`() {
        val backStack = stack(Route.Home, Route.ProfileDetail("old"))
        rule.setContent {
            EuiccHomeScreen(
                state = EuiccScreenState(loading = false, profiles = listOf(enabledProfile)),
                backStack = backStack, onReload = {}, onAddSim = {},
            )
        }
        rule.onNodeWithText("Work").performClick()
        assertEquals(Route.ProfileDetail("iccid-1"), backStack.last())
        assertEquals(2, backStack.backStack.size)
    }

    // ---- AddSim / ActivationCode ----

    @Test fun `add sim screen routes to scan and code entry`() {
        val backStack = stack(Route.Home, Route.AddSim)
        rule.setContent { AddSimScreen(backStack = backStack) }
        rule.onNodeWithText(str(R.string.scan_qr_button)).performClick()
        assertEquals(Route.ScanQr, backStack.last())
        backStack.pop()
        rule.onNodeWithText(str(R.string.enter_code_button)).performClick()
        assertEquals(Route.ActivationCode, backStack.last())
    }

    @Test fun `activation code screen enables continue for valid code and navigates with inputs`() {
        val backStack = stack(Route.Home, Route.ActivationCode)
        rule.setContent { ActivationCodeScreen(backStack = backStack) }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.continue_label)).assertIsNotEnabled()
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("LPA:1\$smdp.example.com\$MATCH-1\$FF\$1")
        fields[1].performTextInput("352099001761481")
        fields[2].performTextInput("secret123")
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.continue_label)).assertIsEnabled()
        // Confirmation-code-required supporting text appears for the $1 flag.
        rule.onNodeWithText(str(R.string.confirmation_code_title)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.continue_label)).performClick()
        val route = backStack.last() as Route.Download
        assertEquals("LPA:1\$smdp.example.com\$MATCH-1\$FF\$1", route.activationCode)
        assertEquals("352099001761481", route.imei)
        assertEquals("secret123", route.confirmationCode)
    }

    @Test fun `activation code scan button routes to scanner`() {
        val backStack = stack(Route.Home, Route.ActivationCode)
        rule.setContent { ActivationCodeScreen(backStack = backStack) }
        rule.onNodeWithText(str(R.string.activation_code_scan_button)).performClick()
        assertEquals(Route.ScanQr, backStack.last())
    }

    @Test fun `qr scanner without camera permission shows wall and cancel pops`() {
        val backStack = stack(Route.Home, Route.ScanQr)
        var results = 0
        rule.setContent { QrScannerScreen(backStack = backStack, onResult = { results++ }) }
        rule.onNodeWithText(str(R.string.camera_permission_required)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertEquals(Route.Home, backStack.last())
        assertEquals(0, results)
    }

    // ---- DeviceInfo ----

    @Test fun `device info shows unknown eid and no notifications`() {
        rule.setContent {
            DeviceInfoScreen(state = EuiccScreenState(loading = false), backStack = stack(Route.Home), onRemoveNotification = {})
        }
        rule.onNodeWithText(str(R.string.eid_not_found)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.unknown)).assertExists()
        rule.onNodeWithText(str(R.string.no_notifications)).assertExists()
    }

    @Test fun `device info shows version notifications remove and eid dialog`() {
        var removed: Int? = null
        rule.setContent {
            DeviceInfoScreen(
                state = EuiccScreenState(
                    loading = false, eid = "89044000001234567890123456789012",
                    info = EuiccInfo(svn = "2.2.0"),
                    notifications = listOf(Notification(seqNumber = 3, operation = "install", address = "smdp.example.com")),
                ),
                backStack = stack(Route.Home), onRemoveNotification = { removed = it },
            )
        }
        rule.onNodeWithText("2.2.0").assertExists()
        rule.onNodeWithText(str(R.string.notification_title, 3, "install")).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.remove)).performClick()
        assertEquals(3, removed)
        rule.onNodeWithText("89044000001234567890123456789012").performClick()
        rule.onNodeWithText("8904 4000 0012 3456 7890 1234 5678 9012").assertIsDisplayed()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.done)).performClick()
        rule.onNodeWithText(str(R.string.eid_qr_code_dialog_title)).assertDoesNotExist()
    }

    // ---- ProfileDetail ----

    @Test fun `profile detail disabled profile enable dialog confirms`() {
        var enabled: Profile? = null
        rule.setContent {
            ProfileDetailScreen(
                iccid = "iccid-2",
                state = EuiccScreenState(loading = false, profiles = listOf(enabledProfile, disabledProfile)),
                backStack = stack(Route.Home),
                onEnable = { enabled = it }, onDisable = {}, onErase = {}, onRename = { _, _ -> },
            )
        }
        rule.onNodeWithText(str(R.string.profile_status_disabled)).assertExists()
        rule.onNodeWithText("Travel eSIM").assertExists()
        rule.onNodeWithText("8901000002").assertExists()
        rule.onNodeWithText(str(R.string.enable_profile_title)).performClick()
        rule.onNodeWithText(str(R.string.enable_carrier_dialog_text, "Global Data", "Work")).assertIsDisplayed()
        // Confirm button carries the same Enable label; click the last match (dialog).
        rule.onAllNodesWithText(str(R.string.enable_profile_title))[1].performClick()
        assertEquals(disabledProfile, enabled)
    }

    @Test fun `profile detail enabled profile disable and erase dialogs dismiss and confirm`() {
        var disabled: Profile? = null; var erased: Profile? = null
        rule.setContent {
            ProfileDetailScreen(
                iccid = "iccid-1",
                state = EuiccScreenState(loading = false, profiles = listOf(enabledProfile)),
                backStack = stack(Route.Home),
                onEnable = {}, onDisable = { disabled = it }, onErase = { erased = it }, onRename = { _, _ -> },
            )
        }
        rule.onNodeWithText(str(R.string.profile_status_enabled)).assertExists()
        rule.onNodeWithText(str(R.string.disable_profile_title)).performClick()
        rule.onNodeWithText(str(R.string.disable_carrier_dialog_text, "Work")).assertIsDisplayed()
        rule.onAllNodesWithText(str(R.string.disable_profile_title))[1].performClick()
        assertEquals(enabledProfile, disabled)
        rule.onNodeWithText(str(R.string.erase_sim_confirm_button)).performClick()
        rule.onNodeWithText(str(R.string.erase_sim_dialog_title, "Work")).assertIsDisplayed()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertTrue(erased == null)
        rule.onNodeWithText(str(R.string.erase_sim_confirm_button)).performClick()
        rule.onAllNodesWithText(str(R.string.erase_sim_confirm_button))[1].performClick()
        assertEquals(enabledProfile, erased)
    }

    @Test fun `profile detail rename dialog saves new name`() {
        var renamed: Pair<Profile, String>? = null
        rule.setContent {
            ProfileDetailScreen(
                iccid = "iccid-1",
                state = EuiccScreenState(loading = false, profiles = listOf(enabledProfile)),
                backStack = stack(Route.Home),
                onEnable = {}, onDisable = {}, onErase = {}, onRename = { p, n -> renamed = p to n },
            )
        }
        rule.onNodeWithText(str(R.string.rename_profile_title)).performClick()
        val field = rule.onAllNodes(hasSetTextAction())[0]
        field.performTextReplacement("Personal")
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.save)).performClick()
        assertEquals(enabledProfile to "Personal", renamed)
    }

    @Test fun `profile detail without provider hides provider row`() {
        rule.setContent {
            ProfileDetailScreen(
                iccid = "iccid-9",
                state = EuiccScreenState(
                    loading = false,
                    profiles = listOf(Profile(iccid = "iccid-9", iccidDisplay = "89019", state = "disabled")),
                ),
                backStack = stack(Route.Home),
                onEnable = {}, onDisable = {}, onErase = {}, onRename = { _, _ -> },
            )
        }
        rule.onNodeWithText(str(R.string.profile_provider)).assertDoesNotExist()
        rule.onNodeWithText("89019").assertExists()
    }

    @Test fun `profile detail missing profile pops when not loading`() {
        val backStack = stack(Route.Home, Route.ProfileDetail("gone"))
        rule.setContent {
            ProfileDetailScreen(
                iccid = "gone",
                state = EuiccScreenState(loading = false, profiles = emptyList()),
                backStack = backStack,
                onEnable = {}, onDisable = {}, onErase = {}, onRename = { _, _ -> },
            )
        }
        rule.waitForIdle()
        assertEquals(Route.Home, backStack.last())
    }
}
