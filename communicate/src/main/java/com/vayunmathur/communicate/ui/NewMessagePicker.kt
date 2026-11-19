package com.vayunmathur.communicate.ui

import android.Manifest
import android.content.Context
import android.telephony.TelephonyManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.vayunmathur.communicate.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.communicate.data.CommunicateContact
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.loadContacts
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FAB flow: pick a line + recipient to start a 1:1 conversation, or flip the "Group" switch to
 * multi-select recipients (chips) + an optional name and create a group (WhatsApp, or SIM MMS).
 */
@Composable
internal fun NewMessagePicker(
    choices: List<LineChoice>,
    onDismiss: () -> Unit,
    onCompose: (LineChoice, String) -> Unit,
    onCreateGroup: (LineChoice, String, List<String>, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val region = remember { deviceRegion(context) }
    var query by remember { mutableStateOf("") }
    var groupMode by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var encryptedGroup by remember { mutableStateOf(false) }
    // Selected recipients for group mode, keyed by phone number (value = display label).
    val selectedContacts = remember { mutableStateListOf<Pair<String, String>>() }
    // Lines that support group chats: WhatsApp, Signal, RCS, and SIM (MMS). GV is 1:1 only.
    val groupChoices = remember(choices) {
        choices.filter {
            it.category == CommunicateLine.WhatsApp ||
                it.category == CommunicateLine.Signal ||
                it.category == CommunicateLine.Rcs ||
                it.category == CommunicateLine.Sim
        }
    }
    var selected by remember(choices) { mutableStateOf(choices.firstOrNull()) }
    val activeChoices = if (groupMode) groupChoices else choices

    // Keep the selected line valid when toggling into group mode.
    androidx.compose.runtime.LaunchedEffect(groupMode) {
        if (groupMode && (selected == null || selected !in groupChoices)) {
            selected = groupChoices.firstOrNull()
        }
    }

    fun toggleContact(phone: String, label: String) {
        val idx = selectedContacts.indexOfFirst { it.first == phone }
        if (idx >= 0) selectedContacts.removeAt(idx) else selectedContacts.add(phone to label)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (groupMode) "New group" else stringResource(R.string.new_message)) },
        text = {
            Column {
                if (groupChoices.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    ) {
                        Text("Group", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        com.vayunmathur.library.ui.Switch(
                            checked = groupMode,
                            onCheckedChange = { groupMode = it },
                        )
                    }
                }
                val sel = selected
                if (sel != null && activeChoices.size > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.choose_line),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        LineSelector(choices = activeChoices, selected = sel, onSelect = { selected = it })
                    }
                }
                if (groupMode) {
                    androidx.compose.material3.OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Group name (optional)") },
                        singleLine = true,
                    )
                    Spacer(Modifier.size(6.dp))
                    // Closed-loop MLS encryption, RCS groups only (dev-gated,
                    // needs the native library present).
                    if (selected?.category == CommunicateLine.Rcs &&
                        com.vayunmathur.communicate.data.rcs.RcsFeature.enabled &&
                        com.vayunmathur.communicate.data.rcs.e2e.RustMlsCrypto.isAvailable
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        ) {
                            Text(
                                stringResource(R.string.rcs_encrypted_group_toggle),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Spacer(Modifier.weight(1f))
                            com.vayunmathur.library.ui.Switch(
                                checked = encryptedGroup,
                                onCheckedChange = { encryptedGroup = it },
                            )
                        }
                        if (encryptedGroup) {
                            Text(
                                stringResource(R.string.rcs_encrypted_group_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(6.dp))
                        }
                    }
                    if (selectedContacts.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                        ) {
                            items(selectedContacts.toList(), key = { it.first }) { (phone, label) ->
                                FilterChip(
                                    selected = true,
                                    onClick = { toggleContact(phone, label) },
                                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingIcon = { IconClose(Modifier.size(16.dp)) },
                                )
                            }
                        }
                        Spacer(Modifier.size(6.dp))
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search name or number") },
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                PermissionGate(
                    permission = Manifest.permission.READ_CONTACTS,
                    message = "Allow contacts access to pick a recipient.",
                ) { rev ->
                    val contacts by produceState(initialValue = emptyList<CommunicateContact>(), rev) {
                        value = withContext(Dispatchers.IO) { CommunicateRepository.loadContacts(context) }
                    }
                    val q = query.trim()
                    val qDigits = q.filter { it.isDigit() }
                    val filtered = if (q.isEmpty()) {
                        contacts
                    } else {
                        contacts.filter { c ->
                            c.name.contains(q, ignoreCase = true) ||
                                (qDigits.isNotEmpty() && c.phoneNumber.filter { it.isDigit() }.contains(qDigits))
                        }
                    }
                    val exactExists = qDigits.isNotEmpty() &&
                        contacts.any { it.phoneNumber.filter { c -> c.isDigit() }.endsWith(qDigits) }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        // Fallback: a raw number not in contacts (shown formatted).
                        if (qDigits.length >= 4 && !exactExists) {
                            item {
                                val label = formatNumber(q, region)
                                ContactPickRow(title = "Send to $label", subtitle = null, selected = null) {
                                    if (groupMode) toggleContact(q, label) else selected?.let { onCompose(it, q) }
                                }
                            }
                        }
                        items(filtered, key = { it.id }) { c ->
                            val isSel = if (groupMode) selectedContacts.any { it.first == c.phoneNumber } else null
                            ContactPickRow(title = c.name, subtitle = c.phoneNumber, selected = isSel) {
                                if (groupMode) toggleContact(
                                    c.phoneNumber,
                                    c.name) else selected?.let { onCompose(it, c.phoneNumber) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (groupMode) {
                TextButton(
                    onClick = {
                        val choice = selected
                        if (choice != null && selectedContacts.isNotEmpty()) {
                            // Encryption only applies to RCS (toggle is hidden otherwise).
                            val encrypted = encryptedGroup && choice.category == CommunicateLine.Rcs
                            onCreateGroup(choice, groupName.trim(), selectedContacts.map { it.first }, encrypted)
                        }
                    },
                    enabled = selected != null && selectedContacts.size >= 1,
                ) { Text("Create") }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.clear)) }
            }
        },
        dismissButton = if (groupMode) {
            { TextButton(onClick = onDismiss) { Text("Cancel") } }
        } else {
            null
        },
    )
}

@Composable
internal fun ContactPickRow(title: String, subtitle: String?, selected: Boolean? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.size(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Device region (SIM > network > locale) for phone-number formatting/parsing. */
internal fun deviceRegion(context: Context): String {
    val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
    return (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso)?.uppercase()
        ?: context.resources.configuration.locales[0].country.ifEmpty { "US" }
}

/** Human-friendly display of a typed number (national format), falling back to the raw input. */
internal fun formatNumber(raw: String, region: String): String = runCatching {
    val util = PhoneNumberUtil.getInstance()
    util.format(util.parse(raw, region), PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
}.getOrDefault(raw)
