package com.vayunmathur.communicate.ui

import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateContact
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.loadContacts
import com.vayunmathur.communicate.data.placeCall
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconContacts
import com.vayunmathur.library.ui.AppScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DialerScreen() {
    val context = LocalContext.current
    val lineChoices = rememberLineChoices()
    var selectedLine by remember { mutableStateOf<LineChoice?>(null) }
    var userSelectedLine by remember { mutableStateOf(false) }

    LaunchedEffect(lineChoices) {
        val current = selectedLine
        if (current == null || current !in lineChoices) {
            selectedLine = lineChoices.firstOrNull { it is LineChoice.GoogleVoice } ?: lineChoices.firstOrNull()
            userSelectedLine = false
        } else if (!userSelectedLine && lineChoices.any { it is LineChoice.GoogleVoice }) {
            selectedLine = lineChoices.first { it is LineChoice.GoogleVoice }
        }
    }

    fun place(target: String) {
        if (target.isBlank()) return
        val choice = selectedLine
        if (choice is LineChoice.GoogleVoice) {
            GoogleVoiceCallManager.init(context)
        }
        CommunicateRepository.placeCall(context, choice, target)
    }

    var isRefreshing by remember { mutableStateOf(false) }
    AppScaffold(
        title = stringResource(R.string.dialer_title),
        isRefreshing = isRefreshing,
        actions = {
            val sel = selectedLine
            if (lineChoices.size > 1 && sel != null) {
                LineSelector(
                    choices = lineChoices,
                    selected = sel,
                    onSelect = {
                        selectedLine = it
                        userSelectedLine = true
                    },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        },
    ) { padding ->
        DefaultDialerGate(modifier = Modifier.padding(padding)) { roleRevision ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                PermissionGate(
                    permission = Manifest.permission.READ_CONTACTS,
                    message = stringResource(R.string.permission_contacts_message),
                    modifier = Modifier.weight(1f),
                ) { permissionRevision ->
                    val contacts = produceState<List<CommunicateContact>?>(
                        initialValue = null,
                        roleRevision,
                        permissionRevision) {
                        value = withContext(Dispatchers.IO) { CommunicateRepository.loadContacts(context) }
                    }
                    androidx.compose.runtime.SideEffect { isRefreshing = contacts.value == null }
                    // Keyed on the contact list only: the query must never re-run the provider query.
                    val rows = contacts.value
                    DialerContent(
                        contacts = rows.orEmpty(),
                        onPlace = ::place,
                        selectedLine = selectedLine,
                        modifier = Modifier.weight(1f),
                        hasLoaded = rows != null,
                    )
                }
            }
        }
    }
}
