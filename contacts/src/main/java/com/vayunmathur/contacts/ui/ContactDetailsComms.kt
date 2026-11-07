package com.vayunmathur.contacts.ui

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.util.ContactPlatforms
import com.vayunmathur.contacts.util.PackageUtils
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.Text

enum class CommunicationType { CALL, SMS }

@Composable
fun CommunicationDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    number: String,
    type: CommunicationType,
    platforms: ContactPlatforms
) {
    val context = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.system_default)) },
            onClick = {
                handleCommunication(context, number, type, null)
                onDismiss()
            }
        )
        if (platforms.hasSignal) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.signal)) },
                onClick = {
                    handleCommunication(context, number, type, PackageUtils.SIGNAL_PACKAGE)
                    onDismiss()
                }
            )
        }
        if (platforms.hasWhatsApp) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.whatsapp)) },
                onClick = {
                    handleCommunication(context, number, type, PackageUtils.WHATSAPP_PACKAGE)
                    onDismiss()
                }
            )
        }
        if (platforms.hasTelegram) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.telegram)) },
                onClick = {
                    handleCommunication(context, number, type, PackageUtils.TELEGRAM_PACKAGE)
                    onDismiss()
                }
            )
        }
    }
}

internal fun handleCommunication(
    context: android.content.Context,
    number: String,
    type: CommunicationType,
    packageName: String?
) {
    if (type == CommunicationType.CALL) {
        if (packageName != null) {
            placePlatformCall(context, number, packageName)
        } else {
            placeCall(context, number)
        }
        return
    }
    val intent = smsIntentFor(number, packageName)
    // Broad catch is deliberate: startActivity crosses arbitrary handler apps
    // and every failure mode falls back to the system SMS app below.
    @Suppress("TooGenericExceptionCaught")
    try {
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        Log.status("ContactDetailsComms", "SMS handler missing", e)
        fallbackToSystemSms(context, number, type, packageName)
    } catch (e: SecurityException) {
        Log.status("ContactDetailsComms", "SMS handler missing", e)
        fallbackToSystemSms(context, number, type, packageName)
    } catch (e: Exception) {
        Log.status("ContactDetailsComms", "SMS handler missing", e)
        fallbackToSystemSms(context, number, type, packageName)
    }
}

private fun smsIntentFor(number: String, packageName: String?): Intent =
    when (packageName) {
        PackageUtils.SIGNAL_PACKAGE ->
            Intent(Intent.ACTION_SENDTO, "smsto:$number".toUri())
                .apply { setPackage(PackageUtils.SIGNAL_PACKAGE) }
        PackageUtils.WHATSAPP_PACKAGE ->
            Intent(Intent.ACTION_VIEW, "https://wa.me/${number.filter { it.isDigit() }}".toUri())
        PackageUtils.TELEGRAM_PACKAGE ->
            Intent(
                Intent.ACTION_VIEW,
                "https://t.me/+${number.filter { it.isDigit() }}".toUri()
            )
        else -> Intent(Intent.ACTION_SENDTO, "sms:$number".toUri())
    }

private fun fallbackToSystemSms(
    context: android.content.Context,
    number: String,
    type: CommunicationType,
    packageName: String?,
) {
    if (packageName != null) {
        handleCommunication(context, number, type, null)
    }
}

internal fun placeCall(context: android.content.Context, number: String) {
    val uri = Uri.fromParts("tel", number, null)
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
        == PackageManager.PERMISSION_GRANTED
    ) {
        try {
            val telecomManager = context.getSystemService(TelecomManager::class.java)
            val extras = Bundle()
            telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)?.let {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
            }
            telecomManager.placeCall(uri, extras)
        } catch (_: Exception) {
            ExternalIntents.launch(context, Intent(Intent.ACTION_DIAL, uri))
        }
    } else {
        ExternalIntents.launch(context, Intent(Intent.ACTION_DIAL, uri))
    }
}

internal fun placePlatformCall(
    context: android.content.Context,
    number: String,
    packageName: String,
    isVideo: Boolean = false,
    fallbackDataRowId: Long? = null
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
        != PackageManager.PERMISSION_GRANTED
    ) {
        if (fallbackDataRowId != null) launchPlatformAction(context, fallbackDataRowId)
        else ExternalIntents.launch(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
        return
    }
    try {
        val telecomManager = context.getSystemService(TelecomManager::class.java)
        val handle = telecomManager.callCapablePhoneAccounts.firstOrNull {
            it.componentName.packageName == packageName
        }
        if (handle != null) {
            val uri = Uri.fromParts("tel", number, null)
            val extras = Bundle()
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            if (isVideo) {
                extras.putInt(
                    TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                    VideoProfile.STATE_BIDIRECTIONAL
                )
            }
            telecomManager.placeCall(uri, extras)
        } else if (fallbackDataRowId != null) {
            launchPlatformAction(context, fallbackDataRowId)
        } else {
            ExternalIntents.launch(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
        }
    } catch (_: Exception) {
        if (fallbackDataRowId != null) launchPlatformAction(context, fallbackDataRowId)
        else ExternalIntents.launch(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    }
}

internal fun launchPlatformAction(context: android.content.Context, dataRowId: Long) {
    // Broad catch is deliberate: the platform row's handler app is arbitrary
    // and a missing handler must be a silent no-op from a row tap.
    @Suppress("TooGenericExceptionCaught")
    try {
        val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataRowId)
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: android.content.ActivityNotFoundException) {
        Log.status("ContactDetailsComms", "Platform action handler missing", e)
    } catch (e: SecurityException) {
        Log.status("ContactDetailsComms", "Platform action handler missing", e)
    } catch (e: Exception) {
        Log.status("ContactDetailsComms", "Platform action handler missing", e)
    }
}

internal fun launchGoogleMeet(context: android.content.Context, number: String) {
    // Broad catch is deliberate: Meet may be absent and a missing handler
    // must be a silent no-op from a row tap.
    @Suppress("TooGenericExceptionCaught")
    try {
        val intent = Intent("com.google.android.apps.tachyon.action.CALL").apply {
            data = "tel:$number".toUri()
            setPackage(PackageUtils.GOOGLE_MEET_PACKAGE)
        }
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        Log.status("ContactDetailsComms", "Meet handler missing", e)
    } catch (e: SecurityException) {
        Log.status("ContactDetailsComms", "Meet handler missing", e)
    } catch (e: Exception) {
        Log.status("ContactDetailsComms", "Meet handler missing", e)
    }
}
