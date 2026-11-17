package com.vayunmathur.contacts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.CommPlatform
import com.vayunmathur.contacts.data.CommPref
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconRemoveCircle
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.expandFromLine

/**
 * Preferred number + platform picker for the edit form: two read-only fields
 * opening dropdowns, plus a clear entry that restores ask-every-time.
 * Shaped like [EditContactGenderSection].
 */
@Composable
fun EditContactCommPrefSection(
    draft: ContactViewModel.ContactDraft,
    contactId: Long?,
    onUpdate: (ContactViewModel.ContactDraft) -> Unit,
) {
    val numbers = draft.phoneNumbers.filter { it.number.isNotBlank() }
    if (numbers.isEmpty()) return
    val current = draft.commPref?.takeIf { pref ->
        numbers.any { normalizeForPref(it.number) == normalizeForPref(pref.number) }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.preferred_contact_method),
            style = com.vayunmathur.library.ui.MaterialTheme.typography.labelMedium,
            color = com.vayunmathur.library.ui.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        CommPrefNumberField(numbers, current, contactId) { number ->
            val platform = current?.platform ?: CommPlatform.SIM
            onUpdate(draft.copy(commPref = CommPref(0, number, platform)))
        }
        Spacer(Modifier.height(12.dp))
        CommPrefPlatformField(current, contactId) { platform ->
            val number = current?.number ?: numbers.first().number
            onUpdate(draft.copy(commPref = CommPref(0, number, platform)))
        }
        if (current != null) {
            Spacer(Modifier.height(4.dp))
            Row {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onUpdate(draft.copy(commPref = null)) }) {
                    IconRemoveCircle()
                }
            }
        }
    }
}

@Composable
private fun CommPrefNumberField(
    numbers: List<PhoneNumber>,
    current: CommPref?,
    contactId: Long?,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val display = current?.number?.let(::formatPhoneNumber).orEmpty()
    Box {
        LabeledTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = stringResource(R.string.phone),
            sharedTextKey = contactId?.let { "contact-commpref-number-$it" },
            modifier = Modifier.fillMaxWidth().expandFromLine(),
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    IconArrowDropDown()
                }
            },
        )
        Row(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { expanded = true }
            )
            Spacer(Modifier.width(MenuButtonWidth))
        }
        DropdownMenu(expanded, { expanded = false }) {
            numbers.forEach { phone ->
                SelectableDropdownMenuItem(
                    selected = current?.number?.let { normalizeForPref(it) } ==
                        normalizeForPref(phone.number),
                    onClick = {
                        onPick(phone.number)
                        expanded = false
                    },
                    text = { Text(formatPhoneNumber(phone.number)) },
                    selectedLeadingIcon = { IconCheck() },
                )
            }
        }
    }
}

@Composable
private fun CommPrefPlatformField(
    current: CommPref?,
    contactId: Long?,
    onPick: (CommPlatform) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val none = stringResource(UiR.string.none)
    val display = current?.let { platformDisplayName(it.platform) } ?: none
    Box {
        LabeledTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = stringResource(R.string.preferred_method),
            sharedTextKey = contactId?.let { "contact-commpref-platform-$it" },
            modifier = Modifier.fillMaxWidth().expandFromLine(),
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    IconArrowDropDown()
                }
            },
        )
        Row(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { expanded = true }
            )
            Spacer(Modifier.width(MenuButtonWidth))
        }
        DropdownMenu(expanded, { expanded = false }) {
            SelectableDropdownMenuItem(
                selected = current == null,
                onClick = { expanded = false },
                text = { Text(none) },
                selectedLeadingIcon = { IconCheck() },
            )
            CommPlatform.entries.forEach { platform ->
                SelectableDropdownMenuItem(
                    selected = current?.platform == platform,
                    onClick = {
                        onPick(platform)
                        expanded = false
                    },
                    text = { Text(platformDisplayName(platform)) },
                    selectedLeadingIcon = { IconCheck() },
                )
            }
        }
    }
}

@Composable
private fun platformDisplayName(platform: CommPlatform): String = stringResource(
    when (platform) {
        CommPlatform.SIM -> R.string.system_default
        CommPlatform.SIGNAL -> R.string.signal
        CommPlatform.WHATSAPP -> R.string.whatsapp
        CommPlatform.TELEGRAM -> R.string.telegram
        CommPlatform.MEET -> R.string.google_meet
    }
)

private fun normalizeForPref(raw: String): String =
    raw.filter { it.isDigit() || it == '+' }.trim()

private val MenuButtonWidth = 48.dp
