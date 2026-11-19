package com.vayunmathur.communicate.ui

import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconPersonAdd
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateCallLogEntry
import com.vayunmathur.communicate.data.CommunicateCallType
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.deleteCallLog
import com.vayunmathur.communicate.data.loadCallLogsMerged
import com.vayunmathur.communicate.data.placeCall
import com.vayunmathur.communicate.domain.callDurationParts
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CallLogsScreen() {
    val context = LocalContext.current
    val deleteFailedMessage = stringResource(R.string.delete_failed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var pendingDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<CommunicateCallLogEntry?>(null) }
    AppScaffold(
        title = stringResource(R.string.call_logs_title),
    ) { padding ->
        DefaultDialerGate(modifier = Modifier.padding(padding)) { roleRevision ->
            PermissionGate(
                permission = Manifest.permission.READ_CALL_LOG,
                message = stringResource(R.string.permission_call_logs_message),
                modifier = Modifier.padding(padding),
            ) { permissionRevision ->
                val callLogs = produceState<List<CommunicateCallLogEntry>?>(
                    initialValue = null,
                    roleRevision,
                    permissionRevision,
                    tick) {
                    value = withContext(Dispatchers.IO) { CommunicateRepository.loadCallLogsMerged(context) }
                }
                when (val rows = callLogs.value) {
                    null -> com.vayunmathur.library.ui.LoadingState(Modifier.padding(padding))
                    emptyList<CommunicateCallLogEntry>() -> EmptyState(
                        title = stringResource(R.string.empty_call_logs),
                        icon = { IconHistory() },
                        modifier = Modifier.padding(padding),
                    )
                    else -> {
                        CallLogsContent(
                            entries = rows,
                            modifier = Modifier.padding(padding),
                            onCall = { CommunicateRepository.placeCall(context, it.phoneNumber) },
                            onDelete = { toDelete ->
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { CommunicateRepository.deleteCallLog(
                                        context,
                                        toDelete) }
                                    if (ok) tick++ else AppMessages.show(deleteFailedMessage)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
