package com.vayunmathur.contacts.ui

import android.Manifest
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.formatDisplay
import com.vayunmathur.contacts.util.ContactPlatforms
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.util.PackageUtils
import com.vayunmathur.contacts.util.VcfUtils
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailsPage(
    viewModel: ContactViewModel,
    contactId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: () -> Unit,
    showBackButton: Boolean = true
) {
    val contact by remember { viewModel.getContactFlow(contactId).filterNotNull() }.collectAsStateWithLifecycle(initialValue = viewModel.getContact(contactId))
    val details = contact?.details

    if (contact == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(stringResource(R.string.contact_not_found))
        }
        return
    }
    val context = LocalContext.current
    val platforms by produceState(ContactPlatforms(), contactId, details) {
        value = withContext(Dispatchers.IO) { PackageUtils.getContactPlatforms(context, contactId) }
    }
    val isGoogleMeetInstalled by produceState(false) {
        value = withContext(Dispatchers.IO) { PackageUtils.isGoogleMeetInstalled(context) }
    }

    val scope = rememberCoroutineScope()
    val shareContactLabel = stringResource(R.string.share_contact)

    Scaffold(Modifier, {
            TopAppBar({}, Modifier, {if (showBackButton) IconNavigation(onBack) },
                actions = {
                    IconButton({
                        val newFavoriteState = !contact!!.isFavorite
                        scope.launch(Dispatchers.IO) {
                            val newContact = contact!!.copy(isFavorite = newFavoriteState)
                            viewModel.saveContact(newContact)
                        }
                    }) {
                        Icon(
                            if (!contact!!.isFavorite) painterResource(R.drawable.outline_star_24) else painterResource(R.drawable.baseline_star_24),
                            stringResource(R.string.favorite),
                            tint = if (contact!!.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { onEdit(contact!!.id) }) {
                        IconEdit()
                    }
                    IconButton(onClick = {
                        shareContactsAsVcf(scope, context, listOf(contact!!), "${contact!!.name.value.replace(' ', '_')}.vcf", shareContactLabel)
                    }) {
                        IconShare()
                    }
                    IconButton(onClick = onDelete) {
                        IconDelete()
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }, containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (details == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = paddingValues + PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {

            item {
                ProfileHeader(contact!!, viewModel)
            }

            item {
                ActionButtonsRow(
                    details.phoneNumbers.firstOrNull()?.number,
                    details.emails.firstOrNull()?.address,
                    platforms,
                    isGoogleMeetInstalled
                )
            }

            if (details.phoneNumbers.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        details.phoneNumbers.forEachIndexed { index, phone ->
                            var showCallDropdown by remember(phone.id) { mutableStateOf(false) }
                            var showSmsDropdown by remember(phone.id) { mutableStateOf(false) }

                            DetailItem(
                                icon = painterResource(R.drawable.outline_call_24),
                                data = formatPhoneNumber(phone.number),
                                label = phone.typeString(context),
                                trailingIcon = painterResource(R.drawable.outline_chat_24),
                                onTrailingIconClick = {
                                    if (platforms.hasAnyPlatform) {
                                        showSmsDropdown = true
                                    } else {
                                        val intent = Intent(Intent.ACTION_SENDTO)
                                        intent.data = "sms:${phone.number}".toUri()
                                        context.startActivity(intent)
                                    }
                                },
                                onClick = {
                    if (platforms.hasAnyPlatform) {
                                        showCallDropdown = true
                                    } else {
                                        placeCall(context, phone.number)
                                    }
                                },
                                dropdownContent = {
                                    CommunicationDropdown(
                                        expanded = showCallDropdown,
                                        onDismiss = { showCallDropdown = false },
                                        number = phone.number,
                                        type = CommunicationType.CALL,
                                        platforms = platforms
                                    )
                                },
                                trailingDropdownContent = {
                                    CommunicationDropdown(
                                        expanded = showSmsDropdown,
                                        onDismiss = { showSmsDropdown = false },
                                        number = phone.number,
                                        type = CommunicationType.SMS,
                                        platforms = platforms
                                    )
                                },
                                shape = groupShape(index, details.phoneNumbers.size),
                            )
                        }
                    }
                }
            }
            if (details.emails.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        details.emails.forEachIndexed { index, email ->
                            DetailItem(
                                icon = painterResource(R.drawable.outline_mail_24),
                                data = email.address,
                                label = email.typeString(context),
                                onClick = {
                                    val intent = Intent(Intent.ACTION_SENDTO)
                                    intent.data = "mailto:${email.address}".toUri()
                                    context.startActivity(intent)
                                },
                                shape = groupShape(index, details.emails.size),
                            )
                        }
                    }
                }
            }
            if (details.addresses.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        details.addresses.forEachIndexed { index, address ->
                            DetailItem(
                                icon = painterResource(R.drawable.outline_location_on_24),
                                data = address.formattedAddress,
                                label = address.typeString(context),
                                trailingIcon = painterResource(R.drawable.outline_directions_24),
                                onTrailingIconClick = {
                                    val gmmIntentURI = "geo:0,0?q=${Uri.encode(address.formattedAddress)}".toUri()
                                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentURI)
                                    context.startActivity(mapIntent)
                                },
                                shape = groupShape(index, details.addresses.size),
                            )
                        }
                    }
                }
            }

            if(details.dates.isNotEmpty()) {
                item {
                    val clipboard = LocalClipboard.current
                    GroupedSection(title = stringResource(R.string.about_name, contact!!.name.firstName)) {
                        contact!!.birthday?.let { birthday ->
                            val birthdayText = birthday.startDate.formatDisplay()
                            ListItem(
                                content = { Text(birthdayText) },
                                supportingContent = { Text(stringResource(R.string.birthday)) },
                                leadingContent = { Icon(painterResource(R.drawable.outline_cake_24), birthday.typeString(context)) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.combinedClickable(
                                    onClick = { },
                                    onLongClick = {
                                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("date", birthdayText))) }
                                    }
                                )
                            )
                        }
                        details.dates.filter{it.type != CDKEvent.TYPE_BIRTHDAY }.forEach { event ->
                            val eventText = event.startDate.formatDisplay()
                            ListItem(
                                content = { Text(eventText) },
                                supportingContent = { Text(event.typeString(context)) },
                                leadingContent = { Icon(painterResource(R.drawable.outline_event_24), event.typeString(context)) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.combinedClickable(
                                    onClick = { },
                                    onLongClick = {
                                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("date", eventText))) }
                                    }
                                )
                            )
                        }
                    }
                }
            }
            
            if (contact?.note?.content?.isNotEmpty() == true) {
                item {
                    val clipboard = LocalClipboard.current
                    GroupedSection(title = stringResource(R.string.note)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { },
                                    onLongClick = {
                                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("note", contact!!.note.content))) }
                                    }
                                )
                                .padding(16.dp)
                        ) {
                            Text(
                                text = com.vayunmathur.library.util.parseMarkdown(
                                    contact!!.note.content,
                                    showMarkers = false,
                                ),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }

            if (details.groups.isNotEmpty()) {
                item {
                    val allGroups by viewModel.groups.collectAsStateWithLifecycle()
                    val contactGroups = contactGroupsOf(contact!!, allGroups)
                    if (contactGroups.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            contactGroups.forEachIndexed { index, group ->
                                DetailItem(
                                    icon = painterResource(R.drawable.baseline_group_24),
                                    data = group.name,
                                    label = stringResource(R.string.groups),
                                    shape = groupShape(index, contactGroups.size),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileHeader(contact: Contact, viewModel: ContactViewModel) {
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 16.dp)
    ) {
        ContactAvatar(
            contact = contact,
            viewModel = viewModel,
            modifier = Modifier.size(100.dp),
            initialsStyle = MaterialTheme.typography.headlineLarge,
        )

        Spacer(modifier = Modifier.size(16.dp))

        Text(
            text = contactDisplayName(contact),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = contact.org.company,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ActionButtonsRow(
    number: String?,
    email: String?,
    platforms: ContactPlatforms,
    isGoogleMeetInstalled: Boolean
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        if (number != null) {
            var showCallDropdown by remember { mutableStateOf(false) }
            var showSmsDropdown by remember { mutableStateOf(false) }
            var showVideoDropdown by remember { mutableStateOf(false) }

            ActionButton(
                icon = painterResource(R.drawable.outline_call_24),
                label = stringResource(R.string.action_call),
                action = {
                    if (platforms.hasAnyPlatform) {
                        showCallDropdown = true
                    } else {
                        placeCall(context, number)
                    }
                },
                dropdownContent = {
                    DropdownMenu(expanded = showCallDropdown, onDismissRequest = { showCallDropdown = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.system_default)) },
                            onClick = {
                                placeCall(context, number)
                                showCallDropdown = false
                            }
                        )
                        platforms.signalCallId?.let { id ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.signal)) },
                                onClick = { placePlatformCall(context, number, PackageUtils.SIGNAL_PACKAGE, fallbackDataRowId = id); showCallDropdown = false }
                            )
                        }
                        platforms.whatsAppCallId?.let { id ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.whatsapp)) },
                                onClick = { placePlatformCall(context, number, PackageUtils.WHATSAPP_PACKAGE, fallbackDataRowId = id); showCallDropdown = false }
                            )
                        }
                        platforms.telegramCallId?.let { id ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.telegram)) },
                                onClick = { placePlatformCall(context, number, PackageUtils.TELEGRAM_PACKAGE, fallbackDataRowId = id); showCallDropdown = false }
                            )
                        }
                    }
                }
            )
            ActionButton(
                icon = painterResource(R.drawable.outline_sms_24),
                label = stringResource(R.string.action_message),
                action = {
                    if (platforms.hasAnyPlatform) {
                        showSmsDropdown = true
                    } else {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, "sms:$number".toUri()))
                    }
                },
                dropdownContent = {
                    DropdownMenu(expanded = showSmsDropdown, onDismissRequest = { showSmsDropdown = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.system_default)) },
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_SENDTO, "sms:$number".toUri()))
                                showSmsDropdown = false
                            }
                        )
                        platforms.signalMessageId?.let { id ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.signal)) },
                                onClick = { launchPlatformAction(context, id); showSmsDropdown = false }
                            )
                        }
                        platforms.whatsAppMessageId?.let { id ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.whatsapp)) },
                                onClick = { launchPlatformAction(context, id); showSmsDropdown = false }
                            )
                        }
                        platforms.telegramMessageId?.let { id ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.telegram)) },
                                onClick = { launchPlatformAction(context, id); showSmsDropdown = false }
                            )
                        }
                    }
                }
            )

            val hasVideoOptions = platforms.whatsAppVideoId != null ||
                    platforms.signalVideoId != null ||
                    platforms.telegramVideoId != null ||
                    isGoogleMeetInstalled
            if (hasVideoOptions) {
                val videoOptionCount = listOf(
                    isGoogleMeetInstalled,
                    platforms.whatsAppVideoId != null,
                    platforms.signalVideoId != null,
                    platforms.telegramVideoId != null
                ).count { it }

                ActionButton(
                    icon = painterResource(R.drawable.outline_videocam_24),
                    label = stringResource(R.string.action_video),
                    action = {
                        if (videoOptionCount == 1) {
                            when {
                                isGoogleMeetInstalled -> launchGoogleMeet(context, number)
                                platforms.whatsAppVideoId != null -> placePlatformCall(context, number, PackageUtils.WHATSAPP_PACKAGE, isVideo = true, fallbackDataRowId = platforms.whatsAppVideoId)
                                platforms.signalVideoId != null -> placePlatformCall(context, number, PackageUtils.SIGNAL_PACKAGE, isVideo = true, fallbackDataRowId = platforms.signalVideoId)
                                platforms.telegramVideoId != null -> placePlatformCall(context, number, PackageUtils.TELEGRAM_PACKAGE, isVideo = true, fallbackDataRowId = platforms.telegramVideoId)
                            }
                        } else {
                            showVideoDropdown = true
                        }
                    },
                    dropdownContent = {
                        DropdownMenu(expanded = showVideoDropdown, onDismissRequest = { showVideoDropdown = false }) {
                            if (isGoogleMeetInstalled) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.google_meet)) },
                                    onClick = { launchGoogleMeet(context, number); showVideoDropdown = false }
                                )
                            }
                            platforms.whatsAppVideoId?.let { id ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.whatsapp)) },
                                    onClick = { placePlatformCall(context, number, PackageUtils.WHATSAPP_PACKAGE, isVideo = true, fallbackDataRowId = id); showVideoDropdown = false }
                                )
                            }
                            platforms.signalVideoId?.let { id ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.signal)) },
                                    onClick = { placePlatformCall(context, number, PackageUtils.SIGNAL_PACKAGE, isVideo = true, fallbackDataRowId = id); showVideoDropdown = false }
                                )
                            }
                            platforms.telegramVideoId?.let { id ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.telegram)) },
                                    onClick = { placePlatformCall(context, number, PackageUtils.TELEGRAM_PACKAGE, isVideo = true, fallbackDataRowId = id); showVideoDropdown = false }
                                )
                            }
                        }
                    }
                )
            }
        }
        if (email != null) {
            ActionButton(icon = painterResource(R.drawable.outline_mail_24), label = stringResource(R.string.email)) {
                val intent = Intent(Intent.ACTION_SENDTO)
                intent.data = "mailto:$email".toUri()
                context.startActivity(intent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionButton(
    icon: Painter,
    label: String,
    dropdownContent: (@Composable () -> Unit)? = null,
    action: () -> Unit
) {
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                Icon(
                    icon,
                    contentDescription = label,
                    modifier = Modifier.size(24.dp)
                )
                dropdownContent?.invoke()
            }
        }
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailItem(
    icon: Painter,
    data: String,
    label: String,
    trailingIcon: Painter? = null,
    onTrailingIconClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    dropdownContent: (@Composable () -> Unit)? = null,
    trailingDropdownContent: (@Composable () -> Unit)? = null,
    /**
     * Outer shape of the card. Pass [groupShape] when this item is part of a
     * vertically stacked sibling group so corners between siblings are
     * squared off while the group's outer corners stay rounded.
     */
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick ?: { },
                onLongClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("detail", data))) } }
            )
    ) {
        ListItem(
            content = {
                Box {
                    Text(data, style = MaterialTheme.typography.bodyLarge)
                    dropdownContent?.invoke()
                }
            },
            supportingContent = { Text(label, style = MaterialTheme.typography.bodySmall) },
            leadingContent = { Icon(icon, label) },
            trailingContent = {
                if (trailingIcon != null && onTrailingIconClick != null) {
                    Box {
                        IconButton(onClick = onTrailingIconClick) {
                            Icon(trailingIcon, stringResource(R.string.action))
                        }
                        trailingDropdownContent?.invoke()
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

enum class CommunicationType { CALL, SMS }

/**
 * Outer shape for a card that sits at a given [index] inside a vertically
 * stacked sibling group of [size] items.
 */
fun groupShape(
    index: Int,
    size: Int,
    outerRadius: androidx.compose.ui.unit.Dp = 16.dp,
    innerRadius: androidx.compose.ui.unit.Dp = 4.dp,
    flatTop: Boolean = false,
    flatBottom: Boolean = false,
): androidx.compose.ui.graphics.Shape {
    val isFirst = index == 0 && !flatTop
    val isLast = index == size - 1 && !flatBottom
    return RoundedCornerShape(
        topStart = if (isFirst) outerRadius else innerRadius,
        topEnd = if (isFirst) outerRadius else innerRadius,
        bottomStart = if (isLast) outerRadius else innerRadius,
        bottomEnd = if (isLast) outerRadius else innerRadius,
    )
}

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

private fun handleCommunication(
    context: android.content.Context,
    number: String,
    type: CommunicationType,
    packageName: String?
) {
    if (type == CommunicationType.CALL) {
        if (packageName != null) placePlatformCall(context, number, packageName) else placeCall(context, number)
        return
    }
    val intent = when (packageName) {
        PackageUtils.SIGNAL_PACKAGE -> Intent(Intent.ACTION_SENDTO, "smsto:$number".toUri()).apply { setPackage(PackageUtils.SIGNAL_PACKAGE) }
        PackageUtils.WHATSAPP_PACKAGE -> Intent(Intent.ACTION_VIEW, "https://wa.me/${number.filter { it.isDigit() }}".toUri())
        PackageUtils.TELEGRAM_PACKAGE -> Intent(Intent.ACTION_VIEW, "https://t.me/+${number.filter { it.isDigit() || it == '+' }}".toUri())
        else -> Intent(Intent.ACTION_SENDTO, "sms:$number".toUri())
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        if (packageName != null) {
            handleCommunication(context, number, CommunicationType.SMS, null)
        }
    }
}

private fun placeCall(context: android.content.Context, number: String) {
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
            context.startActivity(Intent(Intent.ACTION_DIAL, uri))
        }
    } else {
        context.startActivity(Intent(Intent.ACTION_DIAL, uri))
    }
}

private fun placePlatformCall(
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
        }
    } catch (_: Exception) {
        if (fallbackDataRowId != null) launchPlatformAction(context, fallbackDataRowId)
    }
}

private fun launchPlatformAction(context: android.content.Context, dataRowId: Long) {
    try {
        val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataRowId)
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: Exception) {}
}

private fun launchGoogleMeet(context: android.content.Context, number: String) {
    try {
        val intent = Intent("com.google.android.apps.tachyon.action.CALL").apply {
            data = "tel:$number".toUri()
            setPackage(PackageUtils.GOOGLE_MEET_PACKAGE)
        }
        context.startActivity(intent)
    } catch (_: Exception) {}
}

@Composable
fun GroupedSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
        )
        content()
    }
}

fun formatPhoneNumber(numberString: String, defaultRegion: String = "US"): String {
    val phoneUtil = PhoneNumberUtil.getInstance()

    return try {
        val phoneNumber = phoneUtil.parse(numberString, defaultRegion)
        if (!phoneUtil.isValidNumber(phoneNumber)) return numberString
        val regionOfNumber = phoneUtil.getRegionCodeForNumber(phoneNumber)
        val formatType = if (regionOfNumber == defaultRegion) {
            PhoneNumberUtil.PhoneNumberFormat.NATIONAL
        } else {
            PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL
        }

        phoneUtil.format(phoneNumber, formatType)

    } catch (e: NumberParseException) {
        numberString
    }
}
