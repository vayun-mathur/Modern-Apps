package com.vayunmathur.contacts.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.CommAction
import com.vayunmathur.contacts.data.CommPlatform
import com.vayunmathur.contacts.data.CommPrefResolver
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.util.ContactPlatforms
import com.vayunmathur.contacts.util.PackageUtils
import com.vayunmathur.library.ui.BadgedBox
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/** Number-first picker shown by the Call/Message/Video buttons for multi-number contacts. */
@Composable
internal fun NumberPickerDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    phoneNumbers: List<PhoneNumber>,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        phoneNumbers.forEach { phone ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(formatPhoneNumber(phone.number))
                        Text(
                            phone.typeString(context),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = { onPick(phone.number) }
            )
        }
    }
}

internal fun launchSingleVideoOption(
    context: Context,
    number: String,
    platforms: ContactPlatforms,
    isGoogleMeetInstalled: Boolean,
) {
    when {
        isGoogleMeetInstalled -> launchGoogleMeet(context, number)
        platforms.whatsAppVideoId != null -> placePlatformCall(context, number, PackageUtils.WHATSAPP_PACKAGE, isVideo = true, fallbackDataRowId = platforms.whatsAppVideoId)
        platforms.signalVideoId != null -> placePlatformCall(context, number, PackageUtils.SIGNAL_PACKAGE, isVideo = true, fallbackDataRowId = platforms.signalVideoId)
        platforms.telegramVideoId != null -> placePlatformCall(context, number, PackageUtils.TELEGRAM_PACKAGE, isVideo = true, fallbackDataRowId = platforms.telegramVideoId)
    }
}

/** Platform picker for one header-button action, filtered to live availability. */
@Composable
internal fun CommPlatformDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    action: CommAction,
    platforms: ContactPlatforms,
    isGoogleMeetInstalled: Boolean,
    onPick: (CommPlatform) -> Unit,
) {
    val options = CommPrefResolver.availablePlatformsFor(action, platforms, isGoogleMeetInstalled)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        options.forEach { platform ->
            DropdownMenuItem(
                text = { Text(commPlatformLabel(platform)) },
                onClick = { onPick(platform) }
            )
        }
    }
}

@Composable
private fun commPlatformLabel(platform: CommPlatform): String = stringResource(
    when (platform) {
        CommPlatform.SIM -> R.string.system_default
        CommPlatform.SIGNAL -> R.string.signal
        CommPlatform.WHATSAPP -> R.string.whatsapp
        CommPlatform.TELEGRAM -> R.string.telegram
        CommPlatform.MEET -> R.string.google_meet
    }
)

@Composable
fun ActionButton(
    icon: @Composable () -> Unit,
    label: String,
    dropdownContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    action: () -> Unit
) {
    // Grows with the row slot so every button owns an equal share of the width
    // at any font scale. The label stays on one centered line with an ellipsis
    // instead of wrapping into its neighbors when space runs out.
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        BadgedBox(
            badge = {}
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { action() },
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    icon()
                }
                dropdownContent?.invoke()
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
