package com.vayunmathur.contacts.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.CommAction
import com.vayunmathur.contacts.data.CommPlatform
import com.vayunmathur.contacts.data.CommPref
import com.vayunmathur.contacts.data.CommPrefResolver
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.util.ContactPlatforms
import com.vayunmathur.contacts.util.PackageUtils
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconMail
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.IconVideoCamera
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.isCompactHeight
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.util.sharedContent

@Composable
fun ProfileHeader(contact: Contact, decodePhoto: ((String) -> android.graphics.Bitmap?)? = null) {
    // Wide windows (landscape phones, tablets) put avatar beside the name so the
    // header fits above the fold; portrait keeps the centered stack verbatim.
    if (isExpandedWidth() || isCompactHeight()) {
        ContactDetailsHeaderLandscape(contact, decodePhoto)
        return
    }
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        // No whole-block shared key. The avatar, each name part and the company pair up individually
        // with their counterparts in the list row and in the editor, and a container morph on top of
        // those would drag the same content to a second destination at the same time.
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        ContactAvatar(
            contact = contact,
            decodePhoto = decodePhoto,
            modifier = Modifier
                .size(100.dp)
                .sharedContent("contact-avatar-${contact.id}"),
            initialsStyle = MaterialTheme.typography.headlineLarge,
        )

        Spacer(modifier = Modifier.size(16.dp))

        // Composed from the structured name rather than the joined display name, so that each part can
        // travel to the field that owns it when this page morphs into the editor. A single Text could
        // only ever land in one of them.
        ProfileNameColumn(contact, startAligned = false)
    }
}

@Composable
fun ActionButtonsRow(
    phoneNumbers: List<PhoneNumber>,
    email: String?,
    platforms: ContactPlatforms,
    isGoogleMeetInstalled: Boolean,
    commPref: CommPref? = null,
    onSaveCommPref: ((CommPref) -> Unit)? = null,
) {
    val context = LocalContext.current
    // Wide windows centre the buttons with fixed gaps instead of spreading them
    // across the full width; portrait keeps SpaceEvenly verbatim.
    val wide = isExpandedWidth() || isCompactHeight()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = if (wide) {
            Arrangement.spacedBy(32.dp, androidx.compose.ui.Alignment.CenterHorizontally)
        } else {
            Arrangement.SpaceEvenly
        }
    ) {
        if (phoneNumbers.isNotEmpty()) {
            CommActionButton(
                action = CommAction.CALL,
                icon = { IconCall() },
                label = stringResource(UiR.string.call),
                phoneNumbers = phoneNumbers,
                platforms = platforms,
                isGoogleMeetInstalled = isGoogleMeetInstalled,
                commPref = commPref,
                onSaveCommPref = onSaveCommPref,
            )
            CommActionButton(
                action = CommAction.MESSAGE,
                icon = { IconSms() },
                label = stringResource(R.string.action_message),
                phoneNumbers = phoneNumbers,
                platforms = platforms,
                isGoogleMeetInstalled = isGoogleMeetInstalled,
                commPref = commPref,
                onSaveCommPref = onSaveCommPref,
            )

            if (hasVideoOptions(platforms, isGoogleMeetInstalled)) {
                CommActionButton(
                    action = CommAction.VIDEO,
                    icon = { IconVideoCamera() },
                    label = stringResource(R.string.action_video),
                    phoneNumbers = phoneNumbers,
                    platforms = platforms,
                    isGoogleMeetInstalled = isGoogleMeetInstalled,
                    commPref = commPref,
                    onSaveCommPref = onSaveCommPref,
                )
            }
        }
        if (email != null) {
            ActionButton(icon = { IconMail() }, label = stringResource(UiR.string.email)) {
                val intent = Intent(Intent.ACTION_SENDTO)
                intent.data = "mailto:$email".toUri()
                ExternalIntents.launch(context, intent)
            }
        }
    }
}

private fun hasVideoOptions(platforms: ContactPlatforms, isGoogleMeetInstalled: Boolean) =
    CommPrefResolver.availablePlatformsFor(
        CommAction.VIDEO, platforms, isGoogleMeetInstalled
    ).isNotEmpty()

/**
 * One Call/Message/Video header button. Uses the shared [commPref] when it is
 * still valid for this action; otherwise walks number picker (skipped for a
 * single number) then platform picker (skipped when only SIM is available),
 * saving the pick before launching.
 */
@Composable
private fun CommActionButton(
    action: CommAction,
    icon: @Composable () -> Unit,
    label: String,
    phoneNumbers: List<PhoneNumber>,
    platforms: ContactPlatforms,
    isGoogleMeetInstalled: Boolean,
    commPref: CommPref?,
    onSaveCommPref: ((CommPref) -> Unit)?,
) {
    val context = LocalContext.current
    var pickedNumber by remember(phoneNumbers) { mutableStateOf(phoneNumbers.first().number) }
    var showNumberPicker by remember { mutableStateOf(false) }
    var showPlatformPicker by remember { mutableStateOf(false) }

    fun launch(number: String, platform: CommPlatform, save: Boolean = true) {
        if (save) onSaveCommPref?.invoke(CommPref(0, number, platform))
        launchCommAction(context, number, platform, action, platforms, isGoogleMeetInstalled)
    }

    fun launchWithNumber(number: String) {
        val options = CommPrefResolver.availablePlatformsFor(action, platforms, isGoogleMeetInstalled)
        when {
            options.isEmpty() -> Unit
            options.size == 1 -> launch(number, options.first())
            else -> {
                pickedNumber = number
                showPlatformPicker = true
            }
        }
    }

    fun press() {
        if (CommPrefResolver.isPrefValidFor(
                commPref, action, phoneNumbers, platforms, isGoogleMeetInstalled
            )
        ) {
            launch(commPref!!.number, commPref.platform, save = false)
            return
        }
        if (phoneNumbers.size == 1) {
            launchWithNumber(phoneNumbers.first().number)
        } else {
            showNumberPicker = true
        }
    }

    ActionButton(
        icon = icon,
        label = label,
        action = ::press,
        dropdownContent = {
            NumberPickerDropdown(
                expanded = showNumberPicker,
                onDismiss = { showNumberPicker = false },
                phoneNumbers = phoneNumbers,
                onPick = { picked ->
                    showNumberPicker = false
                    launchWithNumber(picked)
                }
            )
            CommPlatformDropdown(
                expanded = showPlatformPicker,
                onDismiss = { showPlatformPicker = false },
                action = action,
                platforms = platforms,
                isGoogleMeetInstalled = isGoogleMeetInstalled,
                onPick = { platform ->
                    showPlatformPicker = false
                    launch(pickedNumber, platform)
                }
            )
        }
    )
}

/** Launches [number] via [platform] for [action], reusing the comms launch paths. */
private fun launchCommAction(
    context: android.content.Context,
    number: String,
    platform: CommPlatform,
    action: CommAction,
    platforms: ContactPlatforms,
    isGoogleMeetInstalled: Boolean,
) {
    when (action) {
        CommAction.CALL -> when (platform) {
            CommPlatform.SIM -> placeCall(context, number)
            CommPlatform.SIGNAL -> placePlatformCall(
                context, number, PackageUtils.SIGNAL_PACKAGE,
                fallbackDataRowId = platforms.signalCallId
            )
            CommPlatform.WHATSAPP -> placePlatformCall(
                context, number, PackageUtils.WHATSAPP_PACKAGE,
                fallbackDataRowId = platforms.whatsAppCallId
            )
            CommPlatform.TELEGRAM -> placePlatformCall(
                context, number, PackageUtils.TELEGRAM_PACKAGE,
                fallbackDataRowId = platforms.telegramCallId
            )
            CommPlatform.MEET -> placeCall(context, number)
        }
        CommAction.MESSAGE -> when (platform) {
            CommPlatform.SIM -> ExternalIntents.sendSms(context, number)
            CommPlatform.SIGNAL -> platforms.signalMessageId?.let { launchPlatformAction(context, it) }
            CommPlatform.WHATSAPP -> platforms.whatsAppMessageId?.let { launchPlatformAction(context, it) }
            CommPlatform.TELEGRAM -> platforms.telegramMessageId?.let { launchPlatformAction(context, it) }
            CommPlatform.MEET -> ExternalIntents.sendSms(context, number)
        }
        CommAction.VIDEO -> when (platform) {
            CommPlatform.MEET -> launchGoogleMeet(context, number)
            CommPlatform.SIGNAL -> placePlatformCall(
                context, number, PackageUtils.SIGNAL_PACKAGE, isVideo = true,
                fallbackDataRowId = platforms.signalVideoId
            )
            CommPlatform.WHATSAPP -> placePlatformCall(
                context, number, PackageUtils.WHATSAPP_PACKAGE, isVideo = true,
                fallbackDataRowId = platforms.whatsAppVideoId
            )
            CommPlatform.TELEGRAM -> placePlatformCall(
                context, number, PackageUtils.TELEGRAM_PACKAGE, isVideo = true,
                fallbackDataRowId = platforms.telegramVideoId
            )
            CommPlatform.SIM -> launchSingleVideoOption(context, number, platforms, isGoogleMeetInstalled)
        }
    }
}
