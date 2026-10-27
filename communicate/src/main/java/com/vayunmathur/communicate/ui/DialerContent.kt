package com.vayunmathur.communicate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.communicate.data.CommunicateContact
import com.vayunmathur.communicate.data.T9
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconContacts
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.launch

/**
 * Dialer list plus pad, stateless apart from the entered number: contacts,
 * lines and placement come in as parameters so screenshot previews can render
 * without the SIM/session providers, the role and permission gates, or the
 * contact content provider above.
 */
@Composable
fun DialerContent(
    contacts: List<CommunicateContact>,
    onPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var number by remember { mutableStateOf("") }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Keyed on the contact list only: the query must never re-run the provider query.
    val entries = remember(contacts) { contacts.map { T9.entryFor(it.name, it.phoneNumber) } }
    val query = T9.normalizeQuery(number)
    val filtered = remember(contacts, query) {
        if (query.isEmpty()) contacts
        else contacts.filterIndexed { index, _ -> T9.matches(entries[index], query) }
    }
    Column(modifier = modifier.fillMaxSize()) {
        if (contacts.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.empty_contacts),
                icon = { IconContacts() },
                modifier = Modifier.weight(1f),
            )
        } else if (filtered.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.empty_contacts_for_digits),
                icon = { IconContacts() },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item {
                    Text(
                        stringResource(UiR.string.contacts),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(filtered, key = { "${it.id}-${it.phoneNumber}" }) { contact ->
                    ContactRow(contact) {
                        onPlace(contact.phoneNumber)
                    }
                }
            }
        }
        DialPad(
            number = number,
            onAppend = { number += it },
            onBackspace = { number = number.dropLast(1) },
            onClear = { number = "" },
            onPaste = {
                scope.launch {
                    val pasted = clipboard.getClipEntry()
                        ?.clipData
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                        .orEmpty()
                        .filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                    if (pasted.isNotBlank()) number = pasted
                }
            },
            onCall = { onPlace(number) },
        )
    }
}

@Composable
private fun ContactRow(contact: CommunicateContact, onClick: () -> Unit) {
    ListItem(
        content = {
            Text(
                contact.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = { Text("${contact.label}  ${contact.phoneNumber}", maxLines = 1) },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initialsFor(contact.name),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        trailingContent = { IconCall() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
