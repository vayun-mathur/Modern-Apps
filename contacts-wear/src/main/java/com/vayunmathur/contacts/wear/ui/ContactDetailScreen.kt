package com.vayunmathur.contacts.wear.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.vayunmathur.contacts.wear.domain.WearAction
import com.vayunmathur.contacts.wear.domain.WearActionPolicy
import com.vayunmathur.contacts.wear.domain.WearContact
import com.vayunmathur.contacts.wear.domain.WearHandoffRequest
import com.vayunmathur.contacts.wear.platform.WearDeviceCapabilities
import com.vayunmathur.contacts.wear.sync.WearWatchStatePublisher
import kotlinx.coroutines.launch

/**
 * Contact detail from the local copy: phones/emails as text, plus only the
 * actions this watch can actually complete (see [WearActionPolicy]). A contact
 * with no completable action shows no buttons at all — never a dead tap.
 */
@Composable
fun ContactDetailScreen(
    contact: WearContact,
    capabilities: WearDeviceCapabilities,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = WearActionPolicy.actionsFor(
        contact = contact,
        hasTelephony = capabilities.canCallOnWatch,
        hasSmsHandler = capabilities.hasSmsHandler,
        hasEmailHandler = capabilities.hasEmailHandler,
        phoneReachable = capabilities.phoneReachable,
    )
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn {
            item {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            items(contact.phones) { phone ->
                Chip(
                    onClick = {},
                    label = { Text(phone, maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(contact.emails) { email ->
                Chip(
                    onClick = {},
                    label = { Text(email, maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(actions) { action ->
                Chip(
                    onClick = {
                        runAction(context, action, contact) { request ->
                            scope.launch {
                                WearWatchStatePublisher.requestHandoff(context, request)
                            }
                        }
                    },
                    label = { Text(actionLabel(action)) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun actionLabel(action: WearAction): String = when (action) {
    WearAction.CALL_ON_WATCH -> "Call"
    WearAction.CALL_VIA_PHONE -> "Call via phone"
    WearAction.MESSAGE_ON_WATCH -> "Message"
    WearAction.MESSAGE_VIA_PHONE -> "Message via phone"
    WearAction.EMAIL_ON_WATCH -> "Email"
}

private fun runAction(
    context: android.content.Context,
    action: WearAction,
    contact: WearContact,
    onHandoff: (WearHandoffRequest) -> Unit,
) {
    val phone = contact.phones.firstOrNull().orEmpty()
    when (action) {
        WearAction.CALL_ON_WATCH -> {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CALL_PHONE,
            ) == PackageManager.PERMISSION_GRANTED
            val intent = Intent(
                if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                Uri.parse("tel:${Uri.encode(phone)}"),
            )
            runCatching { context.startActivity(intent) }
        }
        WearAction.MESSAGE_ON_WATCH -> runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone)}")),
            )
        }
        WearAction.EMAIL_ON_WATCH -> runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_SENDTO,
                    Uri.parse("mailto:${contact.emails.firstOrNull().orEmpty()}"),
                ),
            )
        }
        WearAction.CALL_VIA_PHONE ->
            onHandoff(WearHandoffRequest(WearHandoffRequest.KIND_CALL, phone))
        WearAction.MESSAGE_VIA_PHONE ->
            onHandoff(WearHandoffRequest(WearHandoffRequest.KIND_MESSAGE, phone))
    }
}
