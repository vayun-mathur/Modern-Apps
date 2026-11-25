package com.vayunmathur.contacts.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.vayunmathur.contacts.wear.data.WearSnapshot
import com.vayunmathur.contacts.wear.domain.WearContact
import com.vayunmathur.contacts.wear.domain.WearContactSearch

/**
 * Round-screen contact list over the locally synced copy: works fully offline.
 * Search is local (every token must match name/phone/email).
 */
@Composable
fun ContactListScreen(
    snapshot: WearSnapshot,
    query: String,
    onQueryChange: (String) -> Unit,
    onContactClick: (WearContact) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val filtered = WearContactSearch.filter(snapshot.contacts, query)
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(state = listState) {
            item {
                Text(
                    text = statusLine(snapshot),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            item {
                // SCAFFOLD SEARCH EXCEPTION: Wear Compose Material Scaffold has no
                // search slot; this is the watch's only (round-screen) search field.
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text("Search contacts", color = Color.Gray, fontSize = 14.sp)
                        }
                        inner()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = if (snapshot.contacts.isEmpty()) {
                            "No contacts yet.\nOpen Contacts on your phone to sync."
                        } else {
                            "No matches"
                        },
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(filtered, key = { it.id }) { contact ->
                Chip(
                    onClick = { onContactClick(contact) },
                    label = {
                        Text(
                            contact.name.ifBlank { contact.phones.firstOrNull() ?: "" },
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = contact.phones.firstOrNull()?.let { phone ->
                        { Text(phone, maxLines = 1) }
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
    }
}

private fun statusLine(snapshot: WearSnapshot): String {
    val manifest = snapshot.manifest ?: return "${snapshot.contacts.size} contacts on watch"
    return if (manifest.truncated) {
        "${manifest.includedContacts} of ${manifest.totalContacts} contacts (watch limit)"
    } else {
        "${manifest.includedContacts} contacts on watch"
    }
}
