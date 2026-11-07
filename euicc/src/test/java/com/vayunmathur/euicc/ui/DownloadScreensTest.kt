package com.vayunmathur.euicc.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.euicc.R
import com.vayunmathur.euicc.data.Profile
import com.vayunmathur.euicc.platform.DownloadState
import com.vayunmathur.euicc.ui.dialogs.EidDialog
import com.vayunmathur.euicc.ui.dialogs.RenameDialog
import com.vayunmathur.euicc.ui.download.CompleteContent
import com.vayunmathur.euicc.ui.download.ConfirmCarrierContent
import com.vayunmathur.euicc.ui.download.ConfirmationCodeContent
import com.vayunmathur.euicc.ui.download.FailedContent
import com.vayunmathur.euicc.ui.download.InstallingContent
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DownloadScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)
    private fun uiStr(id: Int) = ctx.getString(id)

    @Test fun `download screen idle starts download and shows installing`() {
        var starts = listOf<Any?>()
        rule.setContent {
            DownloadScreen(
                activationCode = "LPA:1\$a.b\$X", imei = "123", confirmationCode = null,
                state = DownloadState.Idle,
                onStart = { a, i, c -> starts = listOf(a, i, c) },
                onConfirm = {}, onSubmitCode = {}, onCancelSession = {}, onDone = {},
            )
        }
        rule.onNodeWithText(str(R.string.installing_title)).assertIsDisplayed()
        assertEquals(listOf("LPA:1\$a.b\$X", "123", null), starts)
    }

    @Test fun `download screen preparing does not restart`() {
        var starts = 0
        rule.setContent {
            DownloadScreen(
                activationCode = "code", imei = null, confirmationCode = "cc",
                state = DownloadState.Preparing,
                onStart = { _, _, _ -> starts++ },
                onConfirm = {}, onSubmitCode = {}, onCancelSession = {}, onDone = {},
            )
        }
        rule.onNodeWithText(str(R.string.installing_title)).assertExists()
        assertEquals(0, starts)
    }

    @Test fun `download screen confirm phase routes confirm and cancel`() {
        var confirmed: String? = "unset"; var cancels = 0
        rule.setContent {
            DownloadScreen(
                activationCode = "code", imei = null, confirmationCode = null,
                state = DownloadState.Confirm("Telco", "Prepaid Plan"),
                onStart = { _, _, _ -> }, onConfirm = { confirmed = it },
                onSubmitCode = {}, onCancelSession = { cancels++ }, onDone = {},
            )
        }
        rule.onNodeWithText("Telco").assertIsDisplayed()
        rule.onNodeWithText("Prepaid Plan").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.download_esim_button)).performClick()
        assertEquals(null, confirmed)
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertEquals(1, cancels)
    }

    @Test fun `download screen confirmation code phase submits trimmed code`() {
        var submitted: String? = null; var cancels = 0
        rule.setContent {
            DownloadScreen(
                activationCode = "code", imei = null, confirmationCode = null,
                state = DownloadState.AwaitingConfirmationCode("Telco", error = false),
                onStart = { _, _, _ -> }, onConfirm = {},
                onSubmitCode = { submitted = it }, onCancelSession = { cancels++ }, onDone = {},
            )
        }
        rule.onNodeWithText(str(R.string.confirmation_code_text, "Telco")).assertExists()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.continue_label)).assertIsNotEnabled()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput(" 9876 ")
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.continue_label)).assertIsEnabled()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.continue_label)).performClick()
        assertEquals("9876", submitted)
        assertEquals(0, cancels)
    }

    @Test fun `confirmation code error state shows wrong code text`() {
        rule.setContent {
            ConfirmationCodeContent(carrier = null, error = true, onSubmit = {}, onCancel = {})
        }
        rule.onNodeWithText(str(R.string.confirmation_code_text_no_carrier)).assertExists()
        rule.onNodeWithText(str(R.string.confirmation_code_wrong)).assertExists()
    }

    @Test fun `download screen installing complete and failed phases`() {
        var retries = 0; var dones = 0; var starts = mutableListOf<String>()
        var phase = androidx.compose.runtime.mutableStateOf<DownloadState>(DownloadState.Installing(0.5f))
        rule.setContent {
            DownloadScreen(
                activationCode = "code9", imei = null, confirmationCode = null,
                state = phase.value,
                onStart = { a, _, _ -> starts += a },
                onConfirm = {}, onSubmitCode = {}, onCancelSession = {}, onDone = { dones++ },
            )
        }
        rule.onNodeWithText(str(R.string.installing_title)).assertExists()
        phase.value = DownloadState.Complete("Telco")
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.download_complete_title)).assertExists()
        rule.onNodeWithText("Telco").assertExists()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.done)).performClick()
        assertEquals(1, dones)
        assertTrue(starts.isEmpty())
        // Failed is exercised standalone below (its own composition).
        assertEquals(0, retries)
    }

    @Test fun `download screen failed phase retries via start`() {
        var starts = mutableListOf<String>(); var dones = 0
        rule.setContent {
            DownloadScreen(
                activationCode = "code9", imei = "imei9", confirmationCode = null,
                state = DownloadState.Failed("SM-DP+ refused"),
                onStart = { a, _, _ -> starts += a },
                onConfirm = {}, onSubmitCode = {}, onCancelSession = {}, onDone = { dones++ },
            )
        }
        rule.onNodeWithText("SM-DP+ refused").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        assertEquals(listOf("code9"), starts)
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertEquals(1, dones)
    }

    @Test fun `failed content without message shows generic only`() {
        rule.setContent { FailedContent(message = null, onRetry = {}, onCancel = {}) }
        rule.onNodeWithText(str(R.string.download_fail_dialog_title)).assertExists()
        rule.onNodeWithText(str(R.string.download_fail_generic)).assertExists()
    }

    @Test fun `installing content indeterminate and determinate render`() {
        var progress = androidx.compose.runtime.mutableStateOf<Float?>(null)
        rule.setContent { InstallingContent(progress = progress.value) }
        rule.onNodeWithText(str(R.string.installing_text)).assertExists()
        progress.value = 0.25f
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.installing_title)).assertExists()
    }

    @Test fun `confirm carrier with same name as carrier shows one label`() {
        var confirms = 0; var cancels = 0
        rule.setContent {
            ConfirmCarrierContent(carrier = "Telco", profileName = "Telco", onConfirm = { confirms++ }, onCancel = { cancels++ })
        }
        rule.onNodeWithText("Telco").assertExists()
        rule.onNodeWithText(str(R.string.download_esim_button)).performClick()
        assertEquals(1, confirms)
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertEquals(1, cancels)
    }

    @Test fun `confirm carrier without carrier shows titles only`() {
        rule.setContent { ConfirmCarrierContent(carrier = null, profileName = null, onConfirm = {}, onCancel = {}) }
        rule.onNodeWithText(str(R.string.carrier_confirm_title)).assertExists()
    }

    @Test fun `complete without carrier hides carrier label`() {
        var dones = 0
        rule.setContent { CompleteContent(carrier = null, onDone = { dones++ }) }
        rule.onNodeWithText(str(R.string.download_complete_title)).assertExists()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.done)).performClick()
        assertEquals(1, dones)
    }

    @Test fun `rename dialog cancel dismisses without saving`() {
        var saved: String? = null; var dismissals = 0
        rule.setContent {
            RenameDialog(
                profile = Profile(nickname = "Work", iccidDisplay = "8901"),
                onConfirm = { saved = it }, onDismiss = { dismissals++ },
            )
        }
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("Other")
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertEquals(1, dismissals)
        assertEquals(null, saved)
    }

    @Test fun `eid dialog groups digits and done dismisses`() {
        var dismissals = 0
        rule.setContent { EidDialog(eid = "89044000001234567890123456789012", onDismiss = { dismissals++ }) }
        rule.onNodeWithText("8904 4000 0012 3456 7890 1234 5678 9012").assertIsDisplayed()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.copy)).assertExists()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.done)).performClick()
        assertEquals(1, dismissals)
    }
}
