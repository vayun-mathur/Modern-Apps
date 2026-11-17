package com.vayunmathur.contacts.ui

import android.content.ClipData
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.formatDisplay
import com.vayunmathur.contacts.platform.ContactPlatforms
import com.vayunmathur.contacts.platform.ContactsActions
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconCake
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconChat
import com.vayunmathur.library.ui.IconDirections
import com.vayunmathur.library.ui.IconEvent
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconMail
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ringtoneTitle
import com.vayunmathur.library.ui.staggeredEntrance
import com.vayunmathur.library.util.sharedContainer
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun PhonesSection(
    details: ContactDetails,
    platforms: ContactPlatforms,
    arriving: Boolean,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.staggeredEntrance(index = 1, arriving = arriving),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        details.phoneNumbers.forEachIndexed { index, phone ->
            var showSmsDropdown by androidx.compose.runtime.remember(phone.id) {
                androidx.compose.runtime.mutableStateOf(false)
            }

            DetailItem(
                icon = { IconCall() },
                data = formatPhoneNumber(phone.number),
                label = phone.typeString(context),
                trailingIcon = { IconChat() },
                onTrailingIconClick = {
                    if (platforms.hasAnyPlatform) {
                        showSmsDropdown = true
                    } else {
                        ExternalIntents.sendSms(context, phone.number)
                    }
                },
                onClick = {
                    // Tapping the number itself places a plain system call, so the row
                    // stays distinct from the Call/Message buttons above (which route
                    // through Signal/WhatsApp/Telegram when those rows exist).
                    placeCall(context, phone.number)
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
                modifier = Modifier.sharedContainer("contact-phone-${phone.id}"),
                sharedTextKey = "contact-phone-${phone.id}-text",
            )
        }
    }
}

@Composable
internal fun EmailsSection(details: ContactDetails, arriving: Boolean) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.staggeredEntrance(index = 2, arriving = arriving),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        details.emails.forEachIndexed { index, email ->
            DetailItem(
                icon = { IconMail() },
                data = email.address,
                label = email.typeString(context),
                onClick = {
                    ExternalIntents.sendEmail(context, email.address)
                },
                shape = groupShape(index, details.emails.size),
                modifier = Modifier.sharedContainer("contact-email-${email.id}"),
                sharedTextKey = "contact-email-${email.id}-text",
            )
        }
    }
}

@Composable
internal fun AddressesSection(details: ContactDetails) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        details.addresses.forEachIndexed { index, address ->
            DetailItem(
                icon = { IconLocationOn() },
                data = address.formattedAddress,
                label = address.typeString(context),
                trailingIcon = { IconDirections() },
                onTrailingIconClick = {
                    ExternalIntents.openMap(context, address.formattedAddress)
                },
                shape = groupShape(index, details.addresses.size),
            )
        }
    }
}

@Composable
internal fun DatesSection(contact: Contact, details: ContactDetails) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val locale = LocalLocale.current.platformLocale
    GroupedSection(title = stringResource(R.string.about_name, contact.name.firstName)) {
        val birthday = contact.birthday
        if (birthday != null) {
            val birthdayText = birthday.startDate.formatDisplay(locale)
            val age = calculateAge(birthday.startDate)
            val displayText = if (age != null) "$birthdayText ($age)" else birthdayText
            ListItem(
                content = { Text(displayText) },
                supportingContent = { Text(stringResource(R.string.birthday)) },
                leadingContent = { IconCake() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.combinedClickable(
                    onClick = { },
                    onLongClick = {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("date", displayText))) }
                    }
                )
            )
        }
        for (event in details.dates.filter { it.type != CDKEvent.TYPE_BIRTHDAY }) {
            val eventText = event.startDate.formatDisplay(locale)
            ListItem(
                content = { Text(eventText) },
                supportingContent = { Text(event.typeString(context)) },
                leadingContent = { IconEvent() },
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

@Composable
internal fun GenderSection(contact: Contact, details: ContactDetails) {
    val gender = details.genders.firstOrNull { it.code.isNotBlank() } ?: return
    val options = stringArrayResource(R.array.genders).toList()
    val display = genderDisplayText(gender.code, gender.text, options)
    if (display.isBlank()) return
    DetailItem(
        icon = { IconPerson() },
        data = display,
        label = stringResource(R.string.gender),
        sharedTextKey = "contact-gender-${contact.id}",
    )
}

@Composable
internal fun NoteSection(contact: Contact) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    GroupedSection(title = stringResource(UiR.string.note)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { },
                    onLongClick = {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("note", contact.note.content))) }
                    }
                )
                .padding(16.dp)
        ) {
            Text(
                text = com.vayunmathur.library.util.parseMarkdown(
                    contact.note.content,
                    showMarkers = false,
                ),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
internal fun GroupsSection(contact: Contact, groups: List<com.vayunmathur.contacts.data.ContactGroup>) {
    val contactGroups = contactGroupsOf(contact, groups)
    if (contactGroups.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            contactGroups.forEachIndexed { index, group ->
                DetailItem(
                    icon = { IconGroup() },
                    data = group.name,
                    label = stringResource(R.string.groups),
                    shape = groupShape(index, contactGroups.size),
                    modifier = Modifier.sharedContainer(
                        "contact-group-${contact.id}-${group.id}"
                    ),
                )
            }
        }
    }
}

@Composable
internal fun RingtoneSection(contact: Contact, actions: ContactsActions) {
    val context = LocalContext.current
    DetailItem(
        icon = { IconVolumeUp() },
        data = ringtoneTitle(context, contact.customRingtone),
        label = stringResource(R.string.ringtone),
        onClick = { actions.pickRingtone(contact) },
    )
}
