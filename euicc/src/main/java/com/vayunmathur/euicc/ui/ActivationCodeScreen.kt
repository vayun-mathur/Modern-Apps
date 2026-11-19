package com.vayunmathur.euicc.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import com.vayunmathur.euicc.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.euicc.Route
import com.vayunmathur.library.ui.IconQrCode
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack

/**
 * Manual activation-code entry, for when there is no QR code to point a camera at.
 *
 * The code is only sanity-checked ([looksLikeActivationCode]), not parsed: an SGP.22
 * activation code is `LPA:1$<smdp>$<matchingId>`, and the native core does the real
 * parsing (format version, matching-ID charset, SM-DP+ hostname). Rejecting anything
 * more aggressively here risks turning a working code into an unexplained refusal.
 *
 * IMEI is prefilled from telephony (when readable) and stays user-editable: some
 * carriers bind the profile to the device, and the value rides in ctxParams1.
 * The confirmation-code field is collected up front when the activation code's
 * flag (`...$1`) says the profile needs one, matching OpenEUICC's
 * `DownloadWizardDetailsFragment`.
 */
@Composable
fun ActivationCodeScreen(backStack: NavBackStack<Route>) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var imei by remember { mutableStateOf<String?>(null) }
    var confirmationCode by remember { mutableStateOf("") }
    val trimmed = code.trim()

    LaunchedEffect(Unit) {
        if (imei == null) imei = readDeviceImei(context).orEmpty()
    }

    val flagSet = trimmed.split('$').getOrNull(4)?.trim() == "1"

    SetupScaffold(
        title = stringResource(R.string.activation_code_title),
        subtitle = stringResource(R.string.activation_code_text),
        icon = { IconQrCode() },
        backStack = backStack,
        primaryAction = SetupAction(
            label = stringResource(UiR.string.continue_label),
            onClick = {
                backStack.add(
                    Route.Download(
                        activationCode = trimmed,
                        imei = imei?.trim()?.ifEmpty { null },
                        confirmationCode = confirmationCode.trim().ifEmpty { null },
                    ),
                )
            },
            enabled = looksLikeActivationCode(trimmed),
        ),
        secondaryAction = SetupAction(
            label = stringResource(R.string.activation_code_scan_button),
            onClick = { backStack.add(Route.ScanQr) },
        ),
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(R.string.activation_code_label)) },
            supportingText = { Text(stringResource(R.string.activation_code_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = imei.orEmpty(),
            onValueChange = { imei = it },
            label = { Text(stringResource(R.string.device_imei_label)) },
            supportingText = { Text(stringResource(R.string.device_imei_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirmationCode,
            onValueChange = { confirmationCode = it },
            label = { Text(stringResource(R.string.wizard_confirmation_code_label)) },
            supportingText = {
                Text(
                    if (flagSet) stringResource(R.string.confirmation_code_title)
                    else stringResource(R.string.wizard_confirmation_code_hint),
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Best-effort IMEI for ctxParams1: readable only with READ_PHONE_STATE /
 * READ_PRIVILEGED_PHONE_STATE, so a denial just leaves the field for the user.
 */
private fun readDeviceImei(context: Context): String? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return null
    }
    return runCatching {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
        telephony.imei?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
